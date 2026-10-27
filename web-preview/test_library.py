import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import wave
from functools import partial
from http.server import ThreadingHTTPServer
from unittest.mock import patch

from library import MusicLibrary
from server import PreviewHandler


def wav(path, rate, width):
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(width)
        output.setframerate(rate)
        output.writeframes(b"\0" * rate * width)


@unittest.skipUnless(shutil.which("ffprobe") and shutil.which("ffmpeg"), "Needs local audio tools")
class LibraryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "tracks.js").write_text("window.M3X_TRACKS = [];", encoding="utf-8")
        (self.root / "index.html").write_text("player", encoding="utf-8")
        self.music = self.root / "chosen"
        self.music.mkdir()
        (self.music / "sub").mkdir()
        wav(self.music / "16bit.wav", 44100, 2)
        wav(self.music / "sub" / "24bit.wav", 48000, 3)
        (self.music / "bad.flac").write_bytes(b"not music")
        (self.music / "notes.txt").write_text("ignore", encoding="utf-8")
        wav(self.root / "outside.wav", 22050, 2)
        self.library = MusicLibrary(self.root)
        self.library.set_folder(str(self.music))

    def tearDown(self):
        self.temp.cleanup()

    def scan(self):
        self.library.start_scan()
        deadline = time.monotonic() + 15
        while self.library.snapshot()["scan"]["running"] and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertFalse(self.library.snapshot()["scan"]["running"])

    def test_actual_metadata_restriction_cache_and_restart(self):
        self.scan()
        data = self.library.snapshot()
        self.assertEqual(len(data["tracks"]), 2)
        self.assertEqual(data["scan"]["failed"], 1)
        self.assertEqual({(item["bits"], item["sampleRate"]) for item in data["tracks"]}, {(16, 44100), (24, 48000)})
        self.assertTrue(all("path" not in item for item in data["tracks"]))
        self.assertNotIn("outside", str(data["tracks"]))
        ids = {item["id"] for item in data["tracks"]}
        (self.music / "bad.flac").unlink()
        with patch.object(self.library, "probe", side_effect=AssertionError("Cached metadata expected")):
            self.scan()
        self.assertEqual(ids, {item["id"] for item in self.library.snapshot()["tracks"]})
        restarted = MusicLibrary(self.root)
        self.assertEqual(restarted.snapshot()["folder"], str(self.music.resolve()))
        self.assertEqual(restarted.snapshot()["tracks"], self.library.snapshot()["tracks"])
        (self.music / "16bit.wav").unlink()
        self.scan()
        self.assertEqual(len(self.library.snapshot()["tracks"]), 1)

    def test_invalid_folder_does_not_replace_setting(self):
        previous = self.library.folder
        for folder in ["relative", str(self.root / "missing"), str(self.music / "16bit.wav"), "", None]:
            with self.subTest(folder=folder), self.assertRaises((ValueError, OSError)):
                self.library.set_folder(folder)
            self.assertEqual(previous, self.library.folder)

    def test_settings_do_not_scan_until_started(self):
        self.library.set_options({"minDuration": 60, "excludedFolders": []})
        self.assertFalse(self.library.scan["running"])
        self.assertIsNone(self.library.scan["lastScan"])
        self.assertEqual(self.library.tracks, [])
        restarted = MusicLibrary(self.root)
        self.assertFalse(restarted.scan["running"])
        self.assertIsNone(restarted.scan["lastScan"])
        self.assertEqual(restarted.folder, str(self.music.resolve()))

    def test_metadata_reads_are_parallel_and_bounded(self):
        for index in range(9):
            wav(self.music / f"parallel-{index}.wav", 8000, 2)
        active = maximum = 0
        lock = threading.Lock()
        def probe(path, stat):
            nonlocal active, maximum
            with lock:
                active += 1
                maximum = max(maximum, active)
            time.sleep(.05)
            with lock:
                active -= 1
            return {"path": str(path), "id": path.stem, "duration": 1, "bytes": stat.st_size}
        with patch.object(self.library, "probe", side_effect=probe):
            self.scan()
        self.assertGreater(maximum, 1)
        self.assertLessEqual(maximum, 4)
        self.assertEqual(self.library.scan["processed"], 12)
        self.assertEqual(self.library.scan["processed"], self.library.scan["total"])
        self.assertGreater(self.library.scan["rate"], 0)

    def test_filters_persistence_and_first_added_time(self):
        self.scan()
        added = {item["id"]: item["added"] for item in self.library.snapshot()["tracks"]}
        self.library.set_options({"minDuration": 0, "excludedFolders": ["sub/", "sub"]})
        self.scan()
        tracks = self.library.snapshot()["tracks"]
        self.assertEqual([item["filename"] for item in tracks], ["16bit.wav"])
        self.assertEqual(tracks[0]["added"], added[tracks[0]["id"]])
        self.assertEqual(MusicLibrary(self.root).options["excludedFolders"], ["sub"])
        self.library.set_options({"minDuration": 60, "excludedFolders": []})
        self.scan()
        self.assertEqual(self.library.snapshot()["tracks"], [])
        self.assertEqual(self.library.scan["skipped"], 2)
        for options in [
            {"minDuration": True}, {"minDuration": -1}, {"excludedFolders": "../other"},
            {"excludedFolders": ["/absolute"]}, {"excludedFolders": ["../outside"]},
            {"excludedFolders": ["C:\\outside"]},
        ]:
            with self.subTest(options=options), self.assertRaises(ValueError):
                self.library.set_options(options)

    def test_cancel_keeps_original_catalog(self):
        self.scan()
        previous = self.library.snapshot()["tracks"]
        last_scan = self.library.scan["lastScan"]
        # Force a new probe so cancellation occurs after real work, before committing.
        for track in self.library.tracks:
            track["schema"] = 1
        probe = self.library.probe
        def cancel_after_probe(*args):
            result = probe(*args)
            self.library.cancel_scan.set()
            return result
        with patch.object(self.library, "probe", side_effect=cancel_after_probe):
            self.scan()
        self.assertTrue(self.library.scan["cancelled"])
        self.assertEqual(self.library.scan["lastScan"], last_scan)
        self.assertEqual([item["id"] for item in self.library.snapshot()["tracks"]], [item["id"] for item in previous])
        self.assertEqual(MusicLibrary(self.root).scan["lastScan"], last_scan)

    def test_lyrics_tags_and_folder_art(self):
        source = self.music / "tagged.flac"
        subprocess.run([
            "ffmpeg", "-v", "error", "-y", "-i", str(self.music / "16bit.wav"),
            "-c:a", "flac", "-metadata", "lyrics=[00:00.50]内嵌歌词",
            "-metadata", "replaygain_track_gain=-6.0 dB", "-metadata", "replaygain_track_peak=0.8",
            "-metadata", "track=3/10", str(source),
        ], check=True, capture_output=True)
        image = self.music / "art.ppm"
        image.write_bytes(b"P6\n2 2\n255\n" + b"\xff\0\0" * 4)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(image), "-frames:v", "1", str(self.music / "cover.jpg")], check=True, capture_output=True)
        self.scan()
        track = next(item for item in self.library.tracks if item["path"] == str(source.resolve()))
        details = self.library.details(track["id"])
        self.assertEqual(details["lyrics"], "[00:00.50]内嵌歌词")
        self.assertEqual(details["lyricSource"], "内嵌歌词")
        self.assertEqual(details["replayGain"]["replaygain_track_gain"], "-6.0 dB")
        self.assertEqual(details["trackNumber"], "3/10")
        self.assertTrue((self.root / details["cover"]).is_file())
        self.assertNotIn("embeddedLyrics", self.library.snapshot()["tracks"][0])
        for encoding in ["utf-8-sig", "utf-16", "gb18030"]:
            (self.music / "tagged.LRC").write_bytes("[00:01.25]本地歌词".encode(encoding))
            with self.subTest(encoding=encoding):
                details = self.library.details(track["id"])
                self.assertEqual(details["lyrics"], "[00:01.25]本地歌词")
                self.assertEqual(details["lyricSource"], "tagged.LRC")
        with self.assertRaises(ValueError):
            self.library.details("missing")

    def test_http_ranges_private_state_and_origin(self):
        self.scan()
        with ThreadingHTTPServer(("127.0.0.1", 0), partial(PreviewHandler, directory=str(self.root))) as server:
            server.library = self.library
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                data = json.load(urllib.request.urlopen(base + "/api/library"))
                details = json.load(urllib.request.urlopen(base + "/api/track?id=" + data["tracks"][0]["id"]))
                self.assertTrue(details["path"].endswith(".wav"))
                self.assertEqual(details["lyrics"], "")
                source = "/" + data["tracks"][0]["src"]
                request = urllib.request.Request(base + source, headers={"Range": "bytes=0-3"})
                with urllib.request.urlopen(request) as response:
                    self.assertEqual(response.status, 206)
                    self.assertEqual(response.read(), b"RIFF")
                for route in ["/.library.json", "/%2Elibrary.json", "/library/audio/not-a-track"]:
                    with self.subTest(route=route), self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(base + route)
                    self.assertEqual(error.exception.code, 404)
                request = urllib.request.Request(base + "/api/scan", data=b"{}", headers={"Content-Type": "application/json", "Origin": "https://other.invalid"})
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request)
                self.assertEqual(error.exception.code, 403)
                request = urllib.request.Request(base + "/api/library", headers={"Host": "other.invalid"})
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request)
                self.assertEqual(error.exception.code, 403)
                request = urllib.request.Request(base + "/api/folder", data=b'{"folder":"relative"}', headers={"Content-Type": "application/json"})
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request)
                self.assertEqual(error.exception.code, 400)
            finally:
                server.shutdown()
                worker.join()


if __name__ == "__main__":
    unittest.main()
