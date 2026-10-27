"""Local-only static preview server with byte ranges for seeking FLAC files."""
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import json
import subprocess
from urllib.parse import urlsplit, parse_qs, unquote

from library import MusicLibrary

ROOT = Path(__file__).resolve().parent


def byte_range(header, size):
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", header)
    if not match or not any(match.groups()) or size <= 0:
        raise ValueError("Invalid byte range")
    left, right = match.groups()
    if left:
        start = int(left)
        end = min(int(right), size - 1) if right else size - 1
    else:
        suffix = int(right)
        if suffix == 0:
            raise ValueError("Empty suffix")
        start, end = max(0, size - suffix), size - 1
    if not 0 <= start <= end < size:
        raise ValueError("Unsatisfiable byte range")
    return start, end


class PreviewHandler(SimpleHTTPRequestHandler):
    def json_response(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.headers.get("Host") not in {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}:
            return self.json_response({"error": "仅允许本机访问"}, 403)
        url = urlsplit(self.path)
        try:
            if url.path == "/api/library":
                return self.json_response(self.server.library.snapshot())
            if url.path == "/api/scan-status":
                with self.server.library.lock:
                    return self.json_response({"scan": dict(self.server.library.scan)})
            if url.path == "/api/folders":
                path = parse_qs(url.query).get("path", [""])[0]
                return self.json_response(self.server.library.folders(path))
            if url.path == "/api/track":
                track_id = parse_qs(url.query).get("id", [""])[0]
                return self.json_response(self.server.library.details(track_id))
        except (ValueError, OSError, KeyError, StopIteration, subprocess.SubprocessError):
            message = "无法读取歌曲信息，请检查原文件" if url.path == "/api/track" else "无法打开文件夹，请检查路径和访问权限"
            return self.json_response({"error": message}, 400)
        return super().do_GET()

    def do_POST(self):
        if self.headers.get("Host") not in {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}:
            return self.json_response({"error": "仅允许本机访问"}, 403)
        origin = self.headers.get("Origin")
        if origin and origin != "http://" + self.headers.get("Host", ""):
            return self.json_response({"error": "不允许来自其他页面的操作"}, 403)
        try:
            length = int(self.headers.get("Content-Length", 0))
            if not 0 < length <= 8192 or self.headers.get_content_type() != "application/json":
                raise ValueError("请求格式不正确")
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError("请求格式不正确")
            path = urlsplit(self.path).path
            if path == "/api/folder":
                self.server.library.set_folder(body.get("folder"))
            elif path == "/api/scan":
                self.server.library.start_scan()
            elif path == "/api/scan-cancel":
                self.server.library.cancel_scan.set()
            elif path == "/api/scan-options":
                self.server.library.set_options(body)
            else:
                return self.json_response({"error": "没有此操作"}, 404)
            return self.json_response(self.server.library.snapshot())
        except (ValueError, OSError) as error:
            return self.json_response({"error": str(error) if isinstance(error, ValueError) else "无法保存目录设置"}, 400)

    def translate_path(self, path):
        route = unquote(urlsplit(path).path)
        if route.startswith("/library/audio/"):
            source = self.server.library.audio_path(route.rsplit("/", 1)[-1])
            return str(source) if source else str(ROOT / ".missing-track")
        if any(part.startswith(".") for part in route.split("/") if part):
            return str(ROOT / ".missing-track")
        return super().translate_path(path)

    def send_head(self):
        self.remaining = None
        requested = self.headers.get("Range")
        path = Path(self.translate_path(self.path))
        if not requested or not path.is_file():
            return super().send_head()
        size = path.stat().st_size
        try:
            start, end = byte_range(requested, size)
        except ValueError:
            self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return None
        stream = path.open("rb")
        stream.seek(start)
        self.remaining = end - start + 1
        self.send_response(HTTPStatus.PARTIAL_CONTENT)
        self.send_header("Content-Type", self.guess_type(str(path)))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(self.remaining))
        self.end_headers()
        return stream

    def copyfile(self, source, outputfile):
        try:
            if self.remaining is None:
                return super().copyfile(source, outputfile)
            while self.remaining:
                chunk = source.read(min(self.remaining, 64 * 1024))
                if not chunk:
                    break
                outputfile.write(chunk)
                self.remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # Audio requests are cancelled when switching tracks or seeking.


if __name__ == "__main__":
    handler = partial(PreviewHandler, directory=str(ROOT))
    ThreadingHTTPServer.allow_reuse_address = False
    with ThreadingHTTPServer(("127.0.0.1", 18765), handler) as server:
        server.library = MusicLibrary(ROOT)
        print("M3X Player preview: http://127.0.0.1:18765/", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
