"""Copy ten local tracks and extract their tags/covers without changing sources."""
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
SOURCE = Path(r"D:\BaiduNetdiskDownload\THE_IDOLMASTER\Shiny Colors")


def prepare():
    canvas = SOURCE / "“CANVAS” 系列"
    selected = [
        sorted((album / "Audio").glob("*.flac"))[0]
        for album in sorted(canvas.iterdir())
        if (album / "Audio").is_dir()
    ]
    collection = next((SOURCE / "WING COLLECTION 系列").glob("[[]28colors]*"))
    selected += sorted((collection / "Audio" / "CD 1").glob("*.flac"))[:2]
    assert len(selected) == 10 and len(set(selected)) == 10, "Expected ten distinct songs"
    for folder in ["media", "covers"]:
        (ROOT / folder).mkdir(exist_ok=True)
    tracks = []
    for index, source in enumerate(selected, 1):
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(source)],
            capture_output=True, encoding="utf-8", check=True,
        )
        metadata = json.loads(probe.stdout)
        tags = {key.lower(): value for key, value in metadata["format"].get("tags", {}).items()}
        stream = next(item for item in metadata["streams"] if item["codec_type"] == "audio")
        target = ROOT / "media" / f"{index:02}.flac"
        if not target.exists():
            shutil.copy2(source, target)
        elif target.stat().st_size != source.stat().st_size:
            raise RuntimeError(f"Existing copy differs in size: {target}")
        cover = ROOT / "covers" / f"{index:02}.jpg"
        if not cover.exists():
            subprocess.run(
                ["ffmpeg", "-v", "error", "-i", str(source), "-map", "0:v:0",
                 "-frames:v", "1", "-vf", "scale=600:600:force_original_aspect_ratio=decrease",
                 "-q:v", "3", str(cover)],
                capture_output=True, check=True,
            )
        track = {
            "id": f"{index:02}",
            "title": tags.get("title", source.stem),
            "artist": tags.get("artist", tags.get("album_artist", "未知歌手")),
            "album": tags.get("album", source.parent.parent.name),
            "duration": float(metadata["format"]["duration"]),
            "sampleRate": int(stream["sample_rate"]),
            "bits": int(stream.get("bits_per_raw_sample") or stream.get("bits_per_sample") or 0),
            "channels": int(stream["channels"]),
            "src": f"media/{index:02}.flac",
            "cover": f"covers/{index:02}.jpg",
            "bytes": target.stat().st_size,
        }
        tracks.append(track)
        print(f"{index:02}  {track['title']} — {track['artist']}", flush=True)
    (ROOT / "tracks.js").write_text(
        "window.M3X_TRACKS = " + json.dumps(tracks, ensure_ascii=False, indent=2) + ";\n",
        encoding="utf-8",
    )
    print(f"Ready: {len(tracks)} songs, {sum(t['bytes'] for t in tracks) / 1024 ** 3:.2f} GiB")


if __name__ == "__main__":
    prepare()
