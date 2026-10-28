from functools import partial
import http.client
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import unittest

from preview_server import ClassificationHandler, ClassificationLibrary


class PreviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.music = self.root / "music"
        self.music.mkdir()
        (self.music / "sample.flac").write_bytes(b"fLaC12345678")
        source = {"path": "sample.flac", "title": ["Sample"], "album": ["Album"],
                  "artist": ["Role (CV: Actor)"], "project": "Test", "format": ".flac",
                  "size": 12}
        self.document = {
            "schema": 2, "source_root": str(self.music), "source_generated_at": "test",
            "engine": "semantic_exact_lookup", "summary": {"semantic_tag_count": 1},
            "issue_labels": {"semantic_unresolved": "Unresolved"},
            "tracks": [{"id": "track-test", "profile": "idolmaster", "source": source,
                        "memberships": {"character": ["character-role"], "group": [],
                                        "artist": [], "original": ["original-credit"]},
                        "semantic_decisions": [{"field": "artist", "reason": "Explicit role"},
                                               {"field": "albumartist", "reason": "Album only"}],
                        "issues": []}],
            "entities": [{"id": "character-role", "profile": "idolmaster",
                          "kind": "character", "name": "Role", "track_ids": ["track-test"]}],
        }
        self.data_path = self.root / "classification.json"

    def tearDown(self):
        self.temp.cleanup()

    def library(self):
        self.data_path.write_text(json.dumps(self.document), encoding="utf-8")
        return ClassificationLibrary(self.data_path)

    def test_reads_snapshot_without_changing_it(self):
        library = self.library()
        original = self.data_path.read_bytes()
        self.assertEqual(library.data["tracks"][0]["title"], "Sample")
        self.assertEqual(library.data["engine"], "semantic_exact_lookup")
        self.assertEqual(library.data["issue_labels"]["semantic_unresolved"], "Unresolved")
        self.assertEqual(library.data["tracks"][0]["decisions"], [
            {"field": "artist", "reason": "Explicit role"}])
        self.assertTrue(library.data["tracks"][0]["available"])
        self.assertEqual(library.audio_path("track-test"), self.music / "sample.flac")
        self.assertIsNone(library.audio_path("../sample.flac"))
        self.assertIsNone(library.audio_path("unknown"))
        self.assertEqual(self.data_path.read_bytes(), original)
        self.assertEqual((self.music / "sample.flac").read_bytes(), b"fLaC12345678")

    def test_missing_file_stays_browsable(self):
        (self.music / "sample.flac").unlink()
        library = self.library()
        self.assertFalse(library.data["tracks"][0]["available"])
        self.assertIsNone(library.audio_path("track-test"))

    def test_other_profiles_excluded(self):
        self.document["tracks"][0]["profile"] = "unrelated"
        self.document["entities"][0]["profile"] = "unrelated"
        library = self.library()
        self.assertEqual(library.data["tracks"], [])
        self.assertEqual(library.data["entities"], [])

    def test_unsafe_paths_rejected(self):
        for path in ("..\\secret.flac", "C:\\secret.flac", "\\secret.flac",
                     "sample.flac:stream", "sub/../../secret.flac"):
            with self.subTest(path=path):
                self.document["tracks"][0]["source"]["path"] = path
                with self.assertRaises(ValueError):
                    self.library()

    def test_symlink_outside_root_rejected(self):
        link = self.music / "link.flac"
        outside = self.root / "outside.flac"
        outside.write_bytes(b"private")
        try:
            link.symlink_to(outside)
        except OSError:
            self.skipTest("Symlink creation is unavailable")
        self.document["tracks"][0]["source"]["path"] = "link.flac"
        with self.assertRaises(ValueError):
            self.library()

    def test_http_read_only_and_ranges(self):
        library = self.library()
        handler = partial(ClassificationHandler)
        with ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
            server.library = library
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                def request(path, method="GET", headers=None):
                    client = http.client.HTTPConnection("127.0.0.1", server.server_port)
                    try:
                        client.request(method, path, headers=headers or {})
                        response = client.getresponse()
                        return response.status, dict(response.getheaders()), response.read()
                    finally:
                        client.close()

                status, _, body = request("/api/classification")
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body)["tracks"][0]["artist"], ["Role (CV: Actor)"])
                status, headers, body = request("/library/audio/track-test",
                                                headers={"Range": "bytes=0-3"})
                self.assertEqual((status, body), (206, b"fLaC"))
                self.assertEqual(headers["Content-Range"], "bytes 0-3/12")
                self.assertEqual(request("/library/audio/track-test",
                                         headers={"Range": "bytes=90-"})[0], 416)
                for path in ("/library/audio/unknown", "/../rules.json",
                             "/%2e%2e/rules.json", "/api/library", "/fonts/../server.py"):
                    self.assertEqual(request(path)[0], 404)
                self.assertEqual(request("/api/classification", headers={"Host": "evil.test"})[0], 403)
                self.assertEqual(request("/", method="HEAD", headers={"Host": "evil.test"})[0], 403)
                self.assertEqual(request("/api/folder", method="POST")[0], 405)
                self.assertEqual(request("/")[0], 200)
                self.assertEqual(request("/app.js")[0], 200)
            finally:
                server.shutdown()
                thread.join()


if __name__ == "__main__":
    unittest.main()
