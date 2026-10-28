from copy import deepcopy
from functools import partial
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

from classification import ClassificationPreview, freeze
from server import PreviewHandler


class ClassificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.music = self.root / "music"
        self.music.mkdir()
        (self.music / "song.flac").write_bytes(b"fLaC-test-audio")
        self.baseline = self.root / "baseline.json"
        self.draft = self.root / "draft.json"
        self.data = {
            "schema": 1, "folder": str(self.music), "name_policy": "encyclopedia only",
            "entities": [
                {"id": "character-a", "kind": "character", "name": "Character A"},
                {"id": "artist-a", "kind": "artist", "name": "Singer A"},
                {"id": "original-a", "kind": "original", "name": "Raw A"},
            ],
            "tracks": [{"id": "track-a", "path": "song.flac", "title": "Song", "memberships": ["character-a", "original-a"]}],
        }
        self.baseline.write_text(json.dumps(self.data), encoding="utf-8")
        self.preview = ClassificationPreview(self.baseline, self.draft)

    def tearDown(self):
        self.temp.cleanup()

    def test_draft_round_trip_preserves_baseline_and_source(self):
        original = self.baseline.read_bytes()
        edits = self.preview.empty_edits()
        edits["entities"]["character-a"] = {"name": "Renamed", "kind": "group"}
        edits["memberships"]["track-a"] = ["artist-a", "original-a"]
        self.preview.save(edits)
        loaded = ClassificationPreview(self.baseline, self.draft)
        self.assertEqual(loaded.edits, edits)
        self.assertEqual(loaded.snapshot()["config"]["entities"], self.data["entities"])
        self.assertTrue(loaded.active)
        self.assertTrue(loaded.snapshot()["applied"])
        self.assertEqual(self.baseline.read_bytes(), original)
        self.assertEqual((self.music / "song.flac").read_bytes(), b"fLaC-test-audio")
        snapshot = loaded.snapshot()
        snapshot["edits"]["entities"].clear()
        self.assertEqual(loaded.edits, edits)

    def test_confirmed_result_has_no_pending_diagnostics(self):
        self.preview.data["issue_labels"] = {"unresolved": "Old diagnostic"}
        self.preview.data["tracks"][0]["issues"] = ["unresolved"]
        config = self.preview.snapshot()["config"]
        self.assertEqual(config["status"], "confirmed")
        self.assertEqual(config["issue_labels"], {})
        self.assertEqual(config["tracks"][0]["issues"], [])
        self.assertEqual(self.preview.data["tracks"][0]["issues"], ["unresolved"])

    def test_applied_library_uses_saved_names_kinds_and_all_memberships(self):
        edits = self.preview.empty_edits()
        edits["entities"]["character-a"] = {"name": "Renamed", "kind": "group"}
        edits["memberships"]["track-a"] = ["artist-a", "character-a", "original-a"]
        self.preview.save(edits)
        loaded = ClassificationPreview(self.baseline, self.draft)
        library = loaded.library_snapshot()
        track = library["tracks"][0]
        self.assertEqual([(item["kind"], item["name"]) for item in track["credits"]], [("artist", "Singer A"), ("group", "Renamed")])
        self.assertEqual(track["artist"], "Singer A / Renamed")
        self.assertEqual(track["src"], "/classification/audio/track-a")
        self.assertEqual(library["classification"]["status"], "confirmed")
        loaded.store(loaded.edits, False)
        reloaded = ClassificationPreview(self.baseline, self.draft)
        self.assertFalse(reloaded.active)
        self.assertEqual(reloaded.edits, edits)

    def test_previously_saved_configuration_is_applied_on_startup(self):
        self.draft.write_text(json.dumps(self.preview.empty_edits()), encoding="utf-8")
        self.assertTrue(ClassificationPreview(self.baseline, self.draft).active)

    def test_invalid_drafts_are_rejected(self):
        for entities, memberships in [
            ({"unknown": {"name": "A", "kind": "artist"}}, {}),
            ({"original-a": {"name": "A", "kind": "artist"}}, {}),
            ({"character-a": {"name": " ", "kind": "artist"}}, {}),
            ({"character-a": {"name": "x" * 121, "kind": "artist"}}, {}),
            ({"character-a": {"name": "A", "kind": []}}, {}),
            ({"character-a": {"name": "Singer A", "kind": "artist"}}, {}),
            ({}, {"unknown": []}),
            ({}, {"track-a": ["unknown", "original-a"]}),
            ({}, {"track-a": ["character-a"]}),
            ({}, {"track-a": [{}]}),
        ]:
            edits = self.preview.empty_edits()
            edits.update(entities=entities, memberships=memberships)
            with self.subTest(edits=edits), self.assertRaises(ValueError):
                self.preview.save(edits)
        edits = self.preview.empty_edits()
        edits["baseline"] = "different"
        with self.assertRaises(ValueError):
            self.preview.save(edits)
        self.assertFalse(self.draft.exists())

    def test_failed_save_does_not_replace_memory_or_old_draft(self):
        self.preview.save(self.preview.empty_edits())
        before = self.draft.read_bytes()
        edits = self.preview.empty_edits()
        edits["entities"]["character-a"] = {"name": "Changed", "kind": "character"}
        with patch.object(Path, "replace", side_effect=OSError("disk")), self.assertRaises(OSError):
            self.preview.save(edits)
        self.assertEqual(self.preview.edits, self.preview.empty_edits())
        self.assertEqual(self.draft.read_bytes(), before)
        self.assertTrue(self.preview.active)

    def test_audio_and_metadata_use_only_configured_paths(self):
        self.assertEqual(self.preview.audio_path("track-a"), self.music / "song.flac")
        self.assertIsNone(self.preview.audio_path("../baseline.json"))
        calls = []
        library = SimpleNamespace(read_details=lambda track: calls.append(track) or {"id": "probe-id", "duration": 1})
        details = self.preview.details("track-a", library)
        self.assertEqual(details["id"], "track-a")
        self.assertEqual(details["src"], "/classification/audio/track-a")
        self.preview.details("track-a", library)
        self.assertEqual(len(calls), 1)
        for path in ["..\\outside.flac", "C:\\outside.flac", "song.flac:stream", "\\outside.flac"]:
            data = deepcopy(self.data)
            data["tracks"][0]["path"] = path
            self.baseline.write_text(json.dumps(data), encoding="utf-8")
            with self.subTest(path=path), self.assertRaises(ValueError):
                ClassificationPreview(self.baseline, self.draft)

    def test_freeze_keeps_all_memberships_original_tags_and_name_sources(self):
        source = {"source_root": str(self.music), "source_sha256": "hash", "generated_at": "now", "issue_labels": {},
                  "entities": self.data["entities"], "tracks": [{
                      "id": "track-a", "source": {"path": "song.flac", "title": ["Song"], "artist": ["Raw A"],
                                                 "album": ["Album"], "albumartist": ["Album Artist"], "size": 10},
                      "memberships": {"character": ["character-a"], "original": ["original-a"]},
                      "issues": ["unresolved"],
                  }]}
        names = {"policy": "encyclopedia only", "mappings": [{"original": "A", "name": "B", "sources": ["source"]}]}
        config = freeze(source, names)
        self.assertEqual(config["tracks"][0]["memberships"], ["character-a", "original-a"])
        self.assertEqual(config["tracks"][0]["artist"], "Raw A")
        self.assertEqual(config["tracks"][0]["album_artist"], ["Album Artist"])
        self.assertEqual(config["name_sources"], names["mappings"])

    def test_local_http_api_save_ranges_and_origin(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), partial(PreviewHandler, directory=str(self.root)))
        server.classification = self.preview
        server.library = SimpleNamespace(snapshot=lambda: {"tracks": []}, set_folder=lambda path: None)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            with urllib.request.urlopen(base + "/api/classification") as response:
                self.assertFalse(json.load(response)["applied"])
            with urllib.request.urlopen(base + "/api/library") as response:
                self.assertEqual(json.load(response), {"tracks": []})
            edits = self.preview.empty_edits()
            edits["entities"]["character-a"] = {"name": "Changed", "kind": "character"}
            request = urllib.request.Request(base + "/api/classification", json.dumps(edits).encode(), {"Content-Type": "application/json"})
            with urllib.request.urlopen(request) as response:
                data = json.load(response)
                self.assertEqual(data["edits"], edits)
                self.assertEqual(data["library"]["tracks"][0]["artist"], "Changed")
            with urllib.request.urlopen(base + "/api/library") as response:
                self.assertEqual(json.load(response)["tracks"][0]["artist"], "Changed")
            server.classification = ClassificationPreview(self.baseline, self.draft)
            with urllib.request.urlopen(base + "/api/library") as response:
                self.assertEqual(json.load(response)["tracks"][0]["artist"], "Changed")
            with urllib.request.urlopen(base + "/api/classification/library") as response:
                self.assertEqual(json.load(response)["tracks"][0]["artist"], "Changed")
            folder_request = urllib.request.Request(base + "/api/folder", b'{"folder":"other"}', {"Content-Type": "application/json"})
            with urllib.request.urlopen(folder_request) as response:
                self.assertEqual(json.load(response), {"tracks": []})
            self.assertFalse(ClassificationPreview(self.baseline, self.draft).active)
            self.assertEqual(server.classification.edits, edits)
            with urllib.request.urlopen(base + "/api/library") as response:
                self.assertEqual(json.load(response), {"tracks": []})
            server.library.snapshot = lambda: {"tracks": [], "scan": {"running": True}}
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request)
            self.assertEqual(error.exception.code, 400)
            self.assertFalse(server.classification.active)
            request.add_header("Origin", "http://evil.invalid")
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request)
            self.assertEqual(error.exception.code, 403)
            request = urllib.request.Request(base + "/classification/audio/track-a", headers={"Range": "bytes=0-3"})
            with urllib.request.urlopen(request) as response:
                self.assertEqual(response.status, 206)
                self.assertEqual(response.read(), b"fLaC")
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(base + "/classification/audio/unknown")
            self.assertEqual(error.exception.code, 404)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == "__main__":
    unittest.main()
