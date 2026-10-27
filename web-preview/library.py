"""Read music metadata from one user-selected directory; sources stay untouched."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor

AUDIO_EXTENSIONS = {".flac", ".mp3", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".aif", ".aiff", ".wma"}


class MusicLibrary:
    def __init__(self, root):
        self.root = Path(root)
        self.lock = threading.RLock()
        self.cancel_scan = threading.Event()
        self.options = {"minDuration": 0, "excludedFolders": []}
        prepared = json.loads((self.root / "tracks.js").read_text(encoding="utf-8").split("=", 1)[1].strip().rstrip(";"))
        self.prepared = {str((self.root / item["src"]).resolve()): item for item in prepared}
        self.folder = str((self.root / "media").resolve())
        self.tracks = [dict(item, codec="FLAC", path=str((self.root / item["src"]).resolve())) for item in prepared]
        self.scan = {"running": False, "processed": 0, "total": 0, "failed": 0, "skipped": 0, "cancelled": False, "lastScan": None, "error": ""}
        try:
            saved = json.loads((self.root / ".library.json").read_text(encoding="utf-8"))
            self.folder = saved["folder"]
            self.tracks = saved["tracks"]
            self.scan["lastScan"] = saved.get("lastScan")
            self.options.update(saved.get("options", {}))
        except (OSError, ValueError, KeyError):
            pass

    def snapshot(self):
        with self.lock:
            return {"folder": self.folder, "tracks": [
                dict({key: value for key, value in item.items() if key not in {"path", "mtime", "embeddedLyrics"}},
                     filename=Path(item["path"]).name,
                     folder=str(Path(item["path"]).parent.relative_to(Path(self.folder))) if Path(item["path"]).is_relative_to(Path(self.folder)) else Path(item["path"]).parent.name)
                for item in self.tracks
            ], "scan": dict(self.scan), "options": dict(self.options)}

    def save(self):
        target = self.root / ".library.json"
        temp = target.with_suffix(".tmp")
        temp.write_text(json.dumps({
            "folder": self.folder, "tracks": self.tracks, "lastScan": self.scan["lastScan"], "options": self.options,
        }, ensure_ascii=False), encoding="utf-8")
        temp.replace(target)

    @staticmethod
    def directory(value):
        if not isinstance(value, str) or not value.strip() or not Path(value).is_absolute():
            raise ValueError("请输入完整的文件夹路径")
        path = Path(value).resolve(strict=True)
        if not path.is_dir():
            raise ValueError("请选择文件夹")
        return path

    def set_folder(self, value):
        folder = self.directory(value)
        with self.lock:
            if self.scan["running"]:
                raise ValueError("请等待当前扫描完成")
            previous = self.folder
            self.folder = str(folder)
            try:
                self.save()
            except OSError:
                self.folder = previous
                raise

    def folders(self, value):
        folder = self.directory(value or self.folder)
        children = []
        for item in folder.iterdir():
            try:
                if item.is_dir() and not item.is_symlink() and not item.name.startswith((".", "$")):
                    children.append({"name": item.name, "path": str(item.resolve())})
            except OSError:
                continue
        return {"path": str(folder), "parent": str(folder.parent), "folders": sorted(children, key=lambda item: item["name"].casefold())}

    def start_scan(self):
        if not shutil.which("ffprobe") or not shutil.which("ffmpeg"):
            raise ValueError("未找到音频扫描组件")
        with self.lock:
            if self.scan["running"]:
                return
            self.directory(self.folder)
            self.cancel_scan.clear()
            self.scan.update(running=True, phase="discovering", processed=0, total=0, failed=0, skipped=0, cancelled=False, error="", rate=0)
            threading.Thread(target=self._scan, daemon=True).start()

    def set_options(self, options):
        minimum = options.get("minDuration", 0)
        excluded = options.get("excludedFolders", [])
        if type(minimum) is not int or minimum not in {0, 60} or not isinstance(excluded, list) or len(excluded) > 100:
            raise ValueError("扫描选项不正确")
        normalized = []
        for value in excluded:
            if not isinstance(value, str) or len(value) > 500:
                raise ValueError("屏蔽目录不正确")
            value = value.strip().replace("\\", "/")
            if value.startswith("/") or ":" in value or ".." in value.split("/"):
                raise ValueError("请填写扫描目录内的相对文件夹路径")
            value = value.strip("/")
            if not value:
                continue
            normalized.append(value)
        with self.lock:
            if self.scan["running"]:
                raise ValueError("请等待扫描停止后修改选项")
            previous = self.options
            self.options = {"minDuration": minimum, "excludedFolders": list(dict.fromkeys(normalized))}
            try:
                self.save()
            except OSError:
                self.options = previous
                raise

    def excluded(self, path, folder):
        relative = path.relative_to(folder).as_posix().casefold()
        return any(relative == value.casefold() or relative.startswith(value.casefold() + "/") for value in self.options["excludedFolders"])

    def _scan(self):
        started = time.monotonic()
        try:
            folder = Path(self.folder).resolve()
            found = []
            def walk_error(error):
                raise error
            for base, dirs, files in os.walk(folder, followlinks=False, onerror=walk_error):
                if self.cancel_scan.is_set():
                    return
                dirs[:] = [name for name in dirs if not (Path(base) / name).is_symlink() and (Path(base) / name).resolve().is_relative_to(folder) and not self.excluded(Path(base) / name, folder)]
                for name in sorted(files):
                    path = Path(base) / name
                    if path.suffix.lower() in AUDIO_EXTENSIONS and not path.is_symlink() and path.resolve().is_relative_to(folder):
                        found.append(path.resolve())
            with self.lock:
                self.scan["total"] = len(found)
                self.scan["phase"] = "metadata"
                cached = {item["path"]: item for item in self.tracks}
            def read_track(path):
                if self.cancel_scan.is_set():
                    return None
                try:
                    stat = path.stat()
                    old = cached.get(str(path))
                    if old and old.get("schema") == 2 and old.get("mtime") == stat.st_mtime_ns and old["bytes"] == stat.st_size:
                        track = old
                    else:
                        track = self.probe(path, stat)
                    return dict(track, added=old.get("added", time.time()) if old else time.time())
                except (OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError):
                    return None
            result = []
            with ThreadPoolExecutor(max_workers=4) as readers:
                for track in readers.map(read_track, sorted(set(found))):
                    if self.cancel_scan.is_set():
                        return
                    with self.lock:
                        if track is None:
                            self.scan["failed"] += 1
                        elif track["duration"] >= self.options["minDuration"]:
                            result.append(track)
                        else:
                            self.scan["skipped"] += 1
                        self.scan["processed"] += 1
                        self.scan["rate"] = self.scan["processed"] / max(.001, time.monotonic() - started)
            with self.lock:
                if self.cancel_scan.is_set():
                    return
                previous = self.tracks
                previous_time = self.scan["lastScan"]
                self.tracks = result
                self.scan["lastScan"] = time.time()
                try:
                    self.save()
                except OSError:
                    self.tracks = previous
                    self.scan["lastScan"] = previous_time
                    raise
        except (OSError, ValueError):
            with self.lock:
                self.scan["error"] = "无法完成扫描，请检查目录和访问权限"
        finally:
            with self.lock:
                self.scan["cancelled"] = self.cancel_scan.is_set()
                self.scan["running"] = False

    def probe(self, path, stat):
        response = subprocess.run(
            ["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)],
            capture_output=True, encoding="utf-8", check=True, timeout=25,
        )
        metadata = json.loads(response.stdout)
        stream = next(item for item in metadata["streams"] if item.get("codec_type") == "audio")
        duration = float(metadata.get("format", {}).get("duration") or stream.get("duration") or 0)
        if not int(stream.get("sample_rate") or 0) or not int(stream.get("channels") or 0) or duration <= 0:
            raise ValueError("Invalid audio stream")
        tags = {key.lower(): value for key, value in {
            **stream.get("tags", {}), **metadata.get("format", {}).get("tags", {}),
        }.items()}
        prepared = self.prepared.get(str(path))
        track_id = prepared["id"] if prepared else hashlib.sha256(os.path.normcase(str(path)).encode()).hexdigest()[:24]
        cover = prepared["cover"] if prepared else "cover-placeholder.svg"
        artwork = next((path.parent / name for name in ["cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png"] if (path.parent / name).is_file() and not (path.parent / name).is_symlink()), None)
        embedded_cover = any(item.get("codec_type") == "video" for item in metadata["streams"])
        if not prepared and (embedded_cover or artwork):
            target = self.root / "scanned-covers" / f"{track_id}.jpg"
            target.parent.mkdir(exist_ok=True)
            try:
                subprocess.run(
                    ["ffmpeg", "-v", "error", "-y", "-i", str(path if embedded_cover else artwork), "-map", "0:v:0", "-frames:v", "1",
                     "-vf", "scale=400:400:force_original_aspect_ratio=decrease", "-q:v", "4", str(target)],
                    capture_output=True, check=True, timeout=25,
                )
                cover = "scanned-covers/" + target.name
            except (OSError, subprocess.SubprocessError):
                pass
        return {
            "id": track_id, "path": str(path), "mtime": stat.st_mtime_ns, "schema": 2,
            "title": tags.get("title") or path.stem,
            "artist": tags.get("artist") or tags.get("album_artist") or "未知歌手",
            "album": tags.get("album") or path.parent.name,
            "duration": duration,
            "sampleRate": int(stream.get("sample_rate") or 0),
            "bits": int(stream.get("bits_per_raw_sample") or stream.get("bits_per_sample") or 0),
            "channels": int(stream.get("channels") or 0),
            "codec": stream.get("codec_name", path.suffix[1:]).upper(),
            "bitrate": int(stream.get("bit_rate") or metadata.get("format", {}).get("bit_rate") or 0),
            "trackNumber": tags.get("track", ""), "discNumber": tags.get("disc", ""),
            "year": tags.get("date") or tags.get("year", ""),
            "embeddedLyrics": tags.get("lyrics") or tags.get("unsyncedlyrics") or next((value for key, value in tags.items() if key.startswith("lyrics-")), ""),
            "replayGain": {key: tags[key] for key in ["replaygain_track_gain", "replaygain_album_gain", "replaygain_track_peak", "replaygain_album_peak"] if key in tags},
            "src": prepared["src"] if prepared else "library/audio/" + track_id,
            "cover": cover, "bytes": stat.st_size,
        }

    def audio_path(self, track_id):
        with self.lock:
            track = next((item for item in self.tracks if item["id"] == track_id), None)
            return Path(track["path"]) if track else None

    def details(self, track_id):
        with self.lock:
            track = next((item.copy() for item in self.tracks if item["id"] == track_id), None)
        if not track:
            raise ValueError("歌曲不在当前曲库中")
        path = Path(track["path"])
        if track.get("schema") != 2:
            track = self.probe(path, path.stat())
        lyrics = ""
        lyric_source = ""
        sidecar = next((item for item in path.parent.iterdir() if item.suffix.lower() == ".lrc" and item.stem.casefold() == path.stem.casefold() and not item.is_symlink()), None)
        if sidecar and sidecar.stat().st_size <= 2 * 1024 * 1024:
            data = sidecar.read_bytes()
            encodings = ["utf-16", "utf-8-sig", "gb18030"] if data.startswith((b"\xff\xfe", b"\xfe\xff")) else ["utf-8-sig", "gb18030"]
            for encoding in encodings:
                try:
                    lyrics = data.decode(encoding)
                    lyric_source = sidecar.name
                    break
                except UnicodeError:
                    continue
        if not lyrics:
            lyrics = track.get("embeddedLyrics", "")
            lyric_source = "内嵌歌词" if lyrics else ""
        return {**{key: value for key, value in track.items() if key not in {"embeddedLyrics", "mtime", "schema"}},
                "filename": path.name, "folder": str(path.parent), "lyrics": lyrics, "lyricSource": lyric_source}
