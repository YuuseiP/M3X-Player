"""Disk persistence and localhost HTTP tests using disposable screenshot batches."""
import copy
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest

from PIL import Image

SPEC = importlib.util.spec_from_file_location('appearance_review', Path(__file__).with_name('appearance-review.py'))
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)
RUN = 'appearance-test'


def fixture(root):
    folder = Path(root) / RUN
    folder.mkdir()
    pages = []
    for theme in ('light', 'dark'):
        name = theme + '-player'
        path = folder / (name + '.png')
        Image.new('RGB', (768, 1280), '#f9fafb' if theme == 'light' else '#202020').save(path)
        pages.append({'id': name, 'title': 'Player', 'file': path.name, 'status': 'captured',
                      'theme': theme, 'width': 768, 'height': 1280,
                      'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    (folder / 'manifest.json').write_text(json.dumps({'schema': 1, 'device': 'M3X test fixture',
        'android': '7.1.1', 'version': 'test', 'pages': pages}), encoding='utf-8')
    (folder / 'installed-base.apk').write_bytes(b'private-apk')
    return folder


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='m3x-review-test-')
        self.addCleanup(self.temp.cleanup)
        self.folder = fixture(self.temp.name)
        self.store = review.ReviewStore(self.temp.name)
        self.initial = self.store.load(RUN)

    def body(self):
        return {'revision': 0, 'globalNote': 'Tighter spacing', 'pages': {'light-player': {
            'sourceSha256': self.store.screenshots(RUN)['light-player']['sha256'],
            'note': 'Keep cover centered', 'annotations': [{'id': 'rectangle-1', 'type': 'rect',
                'color': '#e35e3e', 'width': 5, 'points': [[.2, .2], [.5, .5]], 'text': 'Reduce margin'}]}}}

    def test_roundtrip_render_and_original_preserved(self):
        before = (self.folder / 'light-player.png').read_bytes()
        saved = self.store.save(RUN, self.body())
        self.assertEqual(saved['revision'], 1)
        self.assertEqual(self.store.load(RUN), saved)
        self.assertEqual(before, (self.folder / 'light-player.png').read_bytes())
        output = self.folder / 'review' / 'annotated' / saved['pages']['light-player']['annotatedFile']
        with Image.open(output) as picture:
            self.assertEqual(picture.size, (768, 1280))
            self.assertEqual(picture.getpixel((154, 400)), (227, 94, 62))
        summary = (self.folder / 'review' / 'AGENT_DRAFT.md').read_text(encoding='utf-8')
        self.assertIn('Reduce margin', summary)
        self.assertIn(output.name, summary)
        self.assertEqual(json.loads((self.folder / 'review' / 'draft.previous.json').read_text()), self.initial)

    def test_all_drawing_tools_and_text_only(self):
        for kind, points in [('pen', [[.1, .1], [.2, .3], [.4, .2]]), ('arrow', [[.2, .6], [.7, .3]]), ('pin', [[1, 0]])]:
            with self.subTest(kind=kind):
                body = self.body()
                body['revision'] = self.store.load(RUN)['revision']
                body['pages']['light-player']['annotations'][0].update(type=kind, points=points)
                self.store.save(RUN, body)
        body = self.body()
        body['revision'] = self.store.load(RUN)['revision']
        body['pages']['light-player']['annotations'] = []
        saved = self.store.save(RUN, body)
        self.assertNotIn('annotatedFile', saved['pages']['light-player'])

    def test_revision_conflict_does_not_overwrite(self):
        saved = self.store.save(RUN, self.body())
        with self.assertRaises(review.Conflict):
            self.store.save(RUN, self.body())
        self.assertEqual(self.store.load(RUN), saved)

    def test_reject_invalid_annotations_and_sources(self):
        for field, value in [('points', [[-0.1, .5], [.6, .8]]), ('width', float('nan')),
                             ('id', 123), ('color', ['red']), ('type', ['rect']), ('points', [[.1, .2]])]:
            body = self.body()
            body['pages']['light-player']['annotations'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.store.save(RUN, body)
        body = self.body()
        body['pages']['light-player']['sourceSha256'] = 'wrong'
        with self.assertRaises(review.Conflict):
            self.store.save(RUN, body)
        body = self.body()
        body['pages']['unknown'] = body['pages'].pop('light-player')
        with self.assertRaises(ValueError):
            self.store.save(RUN, body)
        self.assertEqual(self.store.load(RUN)['revision'], 0)

    def test_changed_original_and_path_traversal_rejected(self):
        (self.folder / 'light-player.png').write_bytes(b'changed')
        with self.assertRaises(review.Conflict):
            self.store.save(RUN, self.body())
        for invalid in ['../appearance-test', 'appearance-test/../other', 'appearance-..', None]:
            with self.subTest(run=invalid), self.assertRaises((ValueError, OSError)):
                self.store.folder(invalid)

    def test_clear_annotations_and_preserve_broken_draft(self):
        self.store.save(RUN, self.body())
        saved = self.store.save(RUN, {'revision': 1, 'globalNote': '', 'pages': {}})
        self.assertEqual(saved['pages'], {})
        path = self.folder / 'review' / 'draft.json'
        path.write_text('[]', encoding='utf-8')
        with self.assertRaises(ValueError):
            self.store.load(RUN)
        self.assertEqual(path.read_text(), '[]')


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='m3x-review-http-')
        self.folder = fixture(self.temp.name)
        self.store = review.ReviewStore(self.temp.name)
        self.server = review.ReviewServer(('127.0.0.1', 0), self.store, RUN)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)
        self.host = '127.0.0.1:' + str(self.server.server_port)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, response.read(), response.headers
        finally:
            connection.close()

    def test_resource_allowlist_and_host(self):
        self.assertEqual(self.request('GET', '/')[0], 200)
        self.assertEqual(self.request('GET', '/api/session?run=' + RUN)[0], 200)
        self.assertEqual(self.request('GET', '/screenshots/' + RUN + '/light-player.png')[0], 200)
        for path in ['/screenshots/' + RUN + '/installed-base.apk', '/screenshots/' + RUN + '/manifest.json',
                     '/screenshots/' + RUN + '/../installed-base.apk', '/review/draft.json', '/../../README.md']:
            self.assertEqual(self.request('GET', path)[0], 404, path)
        self.assertEqual(self.request('GET', '/', headers={'Host': 'evil.example'})[0], 403)

    def test_save_requires_same_origin_and_token(self):
        body = json.dumps({'revision': 0, 'globalNote': 'HTTP persisted', 'pages': {}})
        headers = {'Content-Type': 'application/json', 'Origin': 'http://' + self.host,
                   'X-Review-Token': self.server.token}
        for missing in ['Origin', 'X-Review-Token']:
            invalid = dict(headers)
            del invalid[missing]
            self.assertEqual(self.request('POST', '/api/draft?run=' + RUN, body, invalid)[0], 403)
        self.assertEqual(self.request('POST', '/api/draft?run=' + RUN, body, headers)[0], 200)
        self.assertEqual(self.store.load(RUN)['globalNote'], 'HTTP persisted')
        self.assertEqual(self.request('POST', '/api/draft?run=' + RUN, body, headers)[0], 409)
        self.assertEqual(self.request('POST', '/api/draft?run=' + RUN, '[]', headers)[0], 400)


if __name__ == '__main__':
    unittest.main()
