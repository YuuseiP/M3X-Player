"""Read-only, localhost preview of the saved Idolmaster classification."""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path, PureWindowsPath
from urllib.parse import unquote, urlsplit

HERE = Path(__file__).resolve().parent
FONTS = HERE.parent / "android" / "ui" / "fonts"
from range_handler import RangeHandler


class ClassificationLibrary:
    def __init__(self, data_path):
        data = json.loads(data_path.read_text(encoding="utf-8"))
        if data["schema"] != 2:
            raise ValueError("Expected classification schema 2")
        self.root = Path(data["source_root"]).resolve(strict=True)
        if not self.root.is_dir():
            raise ValueError("Music root is not a directory")
        self.paths = {}
        tracks = []
        for track in data["tracks"]:
            if track["profile"] != "idolmaster":
                continue
            source = track["source"]
            relative = PureWindowsPath(source["path"])
            if relative.drive or relative.root or ".." in relative.parts or ":" in source["path"]:
                raise ValueError("Unsafe source path")
            path = self.root.joinpath(*relative.parts).resolve()
            if not path.is_relative_to(self.root):
                raise ValueError("Source path escapes music root")
            if track["id"] in self.paths:
                raise ValueError("Duplicate track ID")
            self.paths[track["id"]] = path
            tracks.append({
                "id": track["id"], "title": " / ".join(source["title"]) or path.stem,
                "album": " / ".join(source["album"]), "artist": source["artist"],
                "path": source["path"], "project": source["project"],
                "format": source["format"].lstrip(".").upper(), "bytes": source["size"],
                "memberships": track["memberships"], "issues": track["issues"],
                "decisions": [entry for entry in track.get("semantic_decisions", [])
                              if entry["field"] == "artist"],
                "available": path.is_file(), "src": "/library/audio/" + track["id"],
            })
        ids = set(self.paths)
        entities = [
            {"id": e["id"], "kind": e["kind"], "name": e["name"],
             "track_ids": [key for key in e["track_ids"] if key in ids]}
            for e in data["entities"]
            if e["profile"] == "idolmaster" and e["kind"] != "album_artist"
        ]
        self.data = {
            "root": str(self.root), "generated_at": data["source_generated_at"],
            "tracks": tracks, "entities": entities, "issue_labels": data.get("issue_labels", {}),
            "engine": data.get("engine", "legacy_rules"),
            "classification_summary": data["summary"] if "summary" in data else {},
        }

    def audio_path(self, track_id):
        path = self.paths.get(track_id)
        # Resolve again: a replaced symlink must not grant access outside the library.
        if path and path.resolve().is_relative_to(self.root) and path.is_file():
            return path
        return None


class ClassificationHandler(RangeHandler):
    def local_request(self):
        return self.headers.get("Host") in {
            f"localhost:{self.server.server_port}", f"127.0.0.1:{self.server.server_port}"}

    def do_GET(self):
        if not self.local_request():
            return self.json_response({"error": "Local access only"}, 403)
        if urlsplit(self.path).path == "/api/classification":
            return self.json_response(self.server.library.data)
        return SimpleHTTPRequestHandler.do_GET(self)

    def do_HEAD(self):
        if not self.local_request():
            return self.send_error(403)
        return SimpleHTTPRequestHandler.do_HEAD(self)

    def do_POST(self):
        self.send_error(405, "Read-only preview")

    def translate_path(self, path):
        route = unquote(urlsplit(path).path)
        if route.startswith("/library/audio/"):
            source = self.server.library.audio_path(route[len("/library/audio/"):])
            return str(source or HERE / "preview" / ".missing")
        if route in {"/", "/index.html", "/styles.css", "/app.js"}:
            return str(HERE / "preview" / (route.lstrip("/") or "index.html"))
        if route in {"/fonts/HarmonyOS_Sans_SC_Medium.woff2",
                     "/fonts/HarmonyOS_Sans_SC_Bold.woff2",
                     "/fonts/NotoSansJP_Medium.woff2"}:
            return str(FONTS / Path(route).name)
        return str(HERE / "preview" / ".missing")


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--data", type=Path, default=HERE / "output" / "classification.json")
    cli.add_argument("--port", type=int, default=18767)
    args = cli.parse_args()
    library = ClassificationLibrary(args.data)
    handler = partial(ClassificationHandler, directory=str(HERE / "preview"))
    with ThreadingHTTPServer(("127.0.0.1", args.port), handler) as server:
        server.library = library
        print(f"Classification preview: http://127.0.0.1:{server.server_port}/", flush=True)
        print(f"{len(library.data['tracks'])} tracks; original music files are read-only.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
