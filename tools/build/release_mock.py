"""In-memory fixture Release API and localhost adapter. Never contacts GitHub."""
from contextlib import contextmanager
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import urllib.parse
import urllib.request

from release_runtime import canonical, fingerprint


class ReleaseAPI:
    def __init__(self):
        self.records, self.files, self.calls = [], {}, []
        self.next_id = 1
        self.fail = None

    def transport(self, method, url, headers, data):
        parsed = urllib.parse.urlsplit(url)
        if parsed.hostname not in ('api.github.com', 'uploads.github.com'):
            raise ValueError('Fixture rejects external origins')
        path = parsed.path
        self.calls.append((method, path, data))
        if self.fail is not None:
            failure = self.fail(method, path, data)
            if failure:
                return failure
        suffix = path.split('/releases', 1)[1]
        if method == 'GET' and suffix == '':
            page = int(urllib.parse.parse_qs(parsed.query)['page'][0])
            return 200, {}, canonical(self.records[(page - 1) * 100:page * 100])
        if method == 'GET' and suffix == '/latest':
            return 200, {}, canonical(next(r for r in reversed(self.records) if not r['draft'] and not r['prerelease']))
        if method == 'POST' and suffix == '':
            record = json.loads(data)
            record.update(id=self.next_id, immutable=False)
            self.next_id += 1
            self.records.append(record)
            return 201, {}, canonical(record)
        if suffix.startswith('/assets/'):
            asset_id = int(suffix.rsplit('/', 1)[1])
            row, content = self.files[asset_id]
            return 200, {}, content
        release_id = int(suffix.split('/')[1])
        record = next(r for r in self.records if r['id'] == release_id)
        if suffix.endswith('/assets'):
            if method == 'GET':
                page = int(urllib.parse.parse_qs(parsed.query)['page'][0])
                rows = [row for row, _ in self.files.values() if row['releaseId'] == release_id]
                return 200, {}, canonical(rows[(page - 1) * 100:page * 100])
            name = urllib.parse.parse_qs(parsed.query)['name'][0]
            row = {'id': self.next_id, 'releaseId': release_id, 'name': name, 'state': 'uploaded',
                   'size': len(data), 'digest': 'sha256:' + fingerprint(data)['sha256']}
            self.next_id += 1
            self.files[row['id']] = (row, data)
            return 201, {}, canonical(row)
        if method == 'PATCH':
            record.update(json.loads(data))
        return 200, {}, canonical(copy.deepcopy(record))

    @contextmanager
    def localhost(self):
        model = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def serve(self):
                data = self.rfile.read(int(self.headers.get('Content-Length', '0'))) or None
                host = 'uploads.github.com' if self.command == 'POST' and '/assets?' in self.path else 'api.github.com'
                status, headers, body = model.transport(self.command, 'https://' + host + self.path, {}, data)
                self.send_response(status)
                for key, value in headers.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)

            do_GET = do_POST = do_PATCH = serve
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        def transport(method, url, headers, data):
            parsed = urllib.parse.urlsplit(url)
            if parsed.hostname not in ('api.github.com', 'uploads.github.com'):
                raise ValueError('Mock cannot access remote origins')
            # No real credential is ever forwarded, even to localhost.
            request = urllib.request.Request(f'http://127.0.0.1:{server.server_port}' + parsed.path
                                             + ('?' + parsed.query if parsed.query else ''), data=data, method=method)
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, dict(response.headers), response.read()
        try:
            yield transport
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
