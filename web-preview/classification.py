"""Frozen classification and editable web-preview drafts; never write music tags."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path, PureWindowsPath
import threading

BASELINE = Path(__file__).resolve().parent.parent / "artist-classification" / "semantic" / "library-preview.json"
EDITABLE_KINDS = {"character", "group", "artist"}


def freeze(data, names):
    return {
        "schema": 1,
        "folder": data["source_root"],
        "source_sha256": data["source_sha256"],
        "generated_at": data["generated_at"],
        "name_policy": names["policy"],
        "name_sources": names["mappings"],
        "issue_labels": data["issue_labels"],
        "entities": [{key: entity[key] for key in ("id", "kind", "name")}
                     for entity in data["entities"]],
        "tracks": [{
            "id": track["id"], "path": track["source"]["path"],
            "title": " / ".join(track["source"]["title"]) or PureWindowsPath(track["source"]["path"]).stem,
            "artist": " / ".join(track["source"]["artist"]),
            "album": " / ".join(track["source"]["album"]),
            "album_artist": track["source"]["albumartist"],
            "bytes": track["source"]["size"],
            "memberships": [entity for ids in track["memberships"].values() for entity in ids],
            "issues": track["issues"],
        } for track in data["tracks"]],
    }


class ClassificationPreview:
    def __init__(self, baseline=BASELINE, draft=None):
        raw = baseline.read_bytes()
        self.data = json.loads(raw)
        if self.data["schema"] != 1:
            raise ValueError("不支持的分类配置版本")
        self.baseline = hashlib.sha256(raw).hexdigest()
        self.root = Path(self.data["folder"]).resolve()
        self.entities = {item["id"]: item for item in self.data["entities"]}
        self.tracks = {item["id"]: item for item in self.data["tracks"]}
        self.paths = {}
        for track in self.tracks.values():
            relative = PureWindowsPath(track["path"])
            if relative.drive or relative.root or ".." in relative.parts or ":" in track["path"]:
                raise ValueError("分类配置包含不安全的路径")
            path = self.root.joinpath(*relative.parts)
            if not path.resolve().is_relative_to(self.root):
                raise ValueError("分类路径超出曲库")
            self.paths[track["id"]] = path
        self.draft = draft or Path(__file__).resolve().parent / ".classification-draft.json"
        self.lock = threading.RLock()
        self.metadata = {}
        stored = json.loads(self.draft.read_text(encoding="utf-8")) if self.draft.exists() else None
        self.edits = self.validate(stored) if stored is not None else self.empty_edits()
        self.active = stored is not None and stored.get("active", True) is True

    def empty_edits(self):
        return {"schema": 1, "baseline": self.baseline, "entities": {}, "memberships": {}}

    def validate(self, edits):
        if not isinstance(edits, dict) or edits.get("schema") != 1 or edits.get("baseline") != self.baseline:
            raise ValueError("草稿与初始分类不匹配，请先备份草稿")
        entities, memberships = edits.get("entities"), edits.get("memberships")
        if not isinstance(entities, dict) or not isinstance(memberships, dict):
            raise ValueError("分类草稿格式不正确")
        result = self.empty_edits()
        for key, value in entities.items():
            original = self.entities.get(key)
            if not original or original["kind"] not in EDITABLE_KINDS or not isinstance(value, dict):
                raise ValueError("只能修改已有的角色、组合或歌手")
            name, kind = value.get("name"), value.get("kind")
            if not isinstance(name, str) or not name.strip() or len(name.strip()) > 120 or not isinstance(kind, str) or kind not in EDITABLE_KINDS:
                raise ValueError("名称不能为空或超过 120 字，分类类型必须有效")
            entry = {"name": name.strip(), "kind": kind}
            if entry != {field: original[field] for field in entry}:
                result["entities"][key] = entry
        seen = set()
        for key, original in self.entities.items():
            entry = result["entities"].get(key, original)
            pair = (entry["kind"], entry["name"])
            if pair in seen:
                raise ValueError("同类名称已存在；此预览不自动合并分类")
            seen.add(pair)
        for key, values in memberships.items():
            original = self.tracks.get(key)
            if not original or not isinstance(values, list) or not all(isinstance(value, str) and value in self.entities for value in values):
                raise ValueError("歌曲或归属不存在")
            values = sorted(set(values))
            protected = lambda ids: {value for value in ids if self.entities[value]["kind"] not in EDITABLE_KINDS}
            if protected(values) != protected(original["memberships"]):
                raise ValueError("原始标签和专辑艺术家仅供对照，不能在此修改")
            if values != sorted(original["memberships"]):
                result["memberships"][key] = values
        return result

    def snapshot(self):
        with self.lock:
            config = deepcopy(self.data)
            # Old scan diagnostics are historical, not outstanding work in the confirmed result.
            config["status"] = "confirmed"
            config["issue_labels"] = {}
            for track in config["tracks"]:
                track["issues"] = []
            return {"config": config, "edits": deepcopy(self.edits), "applied": self.active}

    def library_snapshot(self):
        with self.lock:
            entities = {key: {**item, **self.edits["entities"].get(key, {})} for key, item in self.entities.items()}
            tracks = []
            for item in self.data["tracks"]:
                credits = [entities[key] for key in self.edits["memberships"].get(item["id"], item["memberships"])
                           if entities[key]["kind"] in EDITABLE_KINDS]
                relative = PureWindowsPath(item["path"])
                cached = self.metadata.get(item["id"])
                metadata = cached[1] if cached else {}
                tracks.append({
                    "id": item["id"], "title": item["title"], "album": item.get("album", ""),
                    "artist": " / ".join(credit["name"] for credit in credits) or item.get("artist") or "未知歌手",
                    "originalArtist": item.get("artist", ""), "credits": credits,
                    "bytes": item.get("bytes", 0), "filename": relative.name, "folder": str(relative.parent),
                    "codec": relative.suffix[1:].upper(), "duration": 0,
                    "src": "/classification/audio/" + item["id"], "cover": "cover-placeholder.svg",
                    **{key: metadata[key] for key in ("duration", "sampleRate", "bits", "channels", "codec", "cover",
                                                    "bitrate", "year", "trackNumber", "discNumber", "replayGain") if key in metadata},
                    "classificationPreview": True,
                })
            return {
                "folder": str(self.root), "tracks": tracks, "options": {},
                "scan": {"running": False},
                "classification": {"status": "confirmed", "entities": [item for item in entities.values() if item["kind"] in EDITABLE_KINDS]},
            }

    def store(self, edits, active):
        with self.lock:
            temporary = self.draft.with_suffix(".tmp")
            temporary.write_text(json.dumps({**edits, "active": active}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            temporary.replace(self.draft)
            self.edits = edits
            self.active = active

    def save(self, body):
        edits = self.validate(body)
        self.store(edits, True)
        return deepcopy(edits)

    def audio_path(self, track_id):
        path = self.paths.get(track_id)
        if path and path.resolve().is_relative_to(self.root) and path.is_file():
            return path
        return None

    def details(self, track_id, library):
        path = self.audio_path(track_id)
        if path is None:
            raise ValueError("歌曲不存在或已移出曲库")
        stat = path.stat()
        with self.lock:
            cached = self.metadata.get(track_id)
            if cached and cached[0] == (stat.st_mtime_ns, stat.st_size):
                return cached[1]
        details = library.read_details({"path": str(path)})
        details.update(id=track_id, src="/classification/audio/" + track_id)
        with self.lock:
            self.metadata[track_id] = ((stat.st_mtime_ns, stat.st_size), details)
        return details


if __name__ == "__main__":
    source = BASELINE.parent.parent / "output" / "classification.json"
    names = BASELINE.parent / "encyclopedia-names.json"
    config = freeze(json.loads(source.read_text(encoding="utf-8")), json.loads(names.read_text(encoding="utf-8")))
    # Exclusive creation keeps later experiments from replacing the agreed baseline.
    with BASELINE.open("x", encoding="utf-8") as output:
        json.dump(config, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(f"Frozen {len(config['tracks'])} tracks: {BASELINE}")
