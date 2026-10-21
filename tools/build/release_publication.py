"""Bounded GitHub Release protocol, injectable transport; never clobbers or deletes."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request

from release_runtime import canonical, fingerprint
from release_signing import _unique

API_VERSION = '2026-03-10'
MAX_BYTES = 512 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identifier(value):
    require(type(value) is int and value > 0, 'Noncanonical remote numeric ID')
    return str(value)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http(method, url, headers, data):
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    opener = urllib.request.build_opener(NoRedirect)
    try:
        response = opener.open(request, timeout=60)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read(MAX_BYTES + 1)
        require(len(body) <= MAX_BYTES, 'Response exceeds bounded release size')
        return response.code, {k.lower(): v for k, v in response.headers.items()}, body


class GitHub:
    def __init__(self, repository, token, transport=http, *, read_only=False):
        require(bool(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository)), 'Invalid repository')
        require(bool(token), 'Explicit authenticated draft-capable token required')
        self.repository, self.token, self.transport, self.read_only = repository, token, transport, read_only
        self.prefix = '/repos/' + repository

    def request(self, method, path, data=None, *, binary=False, upload=False):
        require(path.startswith(self.prefix + '/') or path == self.prefix, 'Foreign repository endpoint')
        segments = path.split('?', 1)[0].split('/')
        require('://' not in path and '#' not in path and not any(part in ('.', '..') for part in segments),
                'Unsafe API endpoint')
        require(not self.read_only or method == 'GET', 'GET-only admission transport')
        host = 'uploads.github.com' if upload else 'api.github.com'
        # Actions archive downloads require JSON Accept before redirecting to binary storage.
        artifact_zip = path.startswith(self.prefix + '/actions/artifacts/') and segments[-1] == 'zip'
        headers = {'Authorization': 'Bearer ' + self.token, 'X-GitHub-Api-Version': API_VERSION,
                   'Accept': 'application/octet-stream' if binary and not artifact_zip else 'application/vnd.github+json',
                   'Content-Type': 'application/octet-stream' if upload else 'application/json'}
        encoded = data if upload else (None if data is None else canonical(data))
        status, response_headers, body = self.transport(method, 'https://' + host + path, headers, encoded)
        if binary:
            for _ in range(4):
                if status == 200:
                    return body
                require(status in (301, 302, 303, 307, 308), 'Binary download failed')
                url = response_headers.get('location', '')
                parsed = urllib.parse.urlsplit(url)
                # Fixed provider origins only; never forward Authorization to storage.
                allowed = parsed.hostname in ('release-assets.githubusercontent.com', 'objects.githubusercontent.com')
                allowed = allowed or bool(parsed.hostname and parsed.hostname.endswith('.blob.core.windows.net'))
                require(parsed.scheme == 'https' and allowed and not parsed.username and not parsed.password
                        and parsed.port in (None, 443) and not parsed.fragment, 'Untrusted binary redirect')
                status, response_headers, body = self.transport('GET', url, {'Accept': 'application/octet-stream'}, None)
            raise ValueError('Too many binary redirects')
        require(status in (200, 201), 'GitHub API failed; not permission to create/retry')
        return json.loads(body, object_pairs_hook=_unique)

    def get(self, path):
        return self.request('GET', self.prefix + path)

    def pages(self, path, key=None):
        rows, seen = [], set()
        separator = '&' if '?' in path else '?'
        for page in range(1, 1001):
            response = self.get(path + separator + f'per_page=100&page={page}')
            batch = response if key is None else response[key]
            require(type(batch) is list and len(batch) <= 100, 'Malformed paginated response')
            for row in batch:
                identity = identifier(row['id'])
                require(identity not in seen, 'Repeated ID across pages; incomplete/unstable inventory')
                seen.add(identity)
            rows.extend(batch)
            if len(batch) < 100:
                if key is not None:
                    require(type(response['total_count']) is int and response['total_count'] == len(rows),
                            'Incomplete paginated inventory')
                return rows
        raise ValueError('Pagination exceeds bounded release inventory')

    def releases(self):
        return self.pages('/releases')

    def assets(self, release_id):
        return self.pages('/releases/' + identifier(release_id) + '/assets')

    def download(self, asset):
        data = self.request('GET', self.prefix + '/releases/assets/' + identifier(asset['id']), binary=True)
        require(asset.get('state') == 'uploaded' and type(asset.get('size')) is int and len(data) == asset['size'],
                'Asset state/size differs from downloaded bytes; owner cleanup may be required')
        if asset.get('digest') is not None:
            require(asset['digest'] == 'sha256:' + fingerprint(data)['sha256'], 'Asset API digest differs')
        return data


def marker(assets):
    return 'guarded-android-release sha256:' + fingerprint(assets['release-manifest.json'])['sha256']


def release_binding(record, expected, body):
    require(type(record['id']) is int and record['id'] > 0 and record['tag_name'] == expected['tag']
            and record['target_commitish'] == expected['source']['sha'] and record['name'] == expected['versionName']
            and record['body'] == body and type(record['draft']) is bool
            and record['prerelease'] is (expected['channel'] == 'rc'), 'Release binding/flags conflict')


def read_assets(api, release_id, wanted=None):
    result = {}
    for row in api.assets(release_id):
        name = row['name']
        require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_.+-]+', name), 'Unsafe asset filename')
        require(name not in result and (wanted is None or name in wanted), 'Extra/duplicate Release asset')
        result[name] = api.download(row)
        if wanted is not None:
            require(result[name] == wanted[name], 'Existing asset bytes conflict; refusing overwrite')
    return result


def publish(api, expected, assets, recheck):
    """Only matching partial drafts can resume. Unknown writes are read back, never blindly retried."""
    recheck()
    matches = [r for r in api.releases() if r['tag_name'] == expected['tag']]
    require(len(matches) <= 1, 'Ambiguous draft/published tag')
    body = marker(assets)
    if not matches:
        payload = {'tag_name': expected['tag'], 'target_commitish': expected['source']['sha'],
                   'name': expected['versionName'], 'body': body, 'draft': True,
                   'prerelease': expected['channel'] == 'rc', 'make_latest': 'false',
                   'generate_release_notes': False}
        try:
            record = api.request('POST', api.prefix + '/releases', payload)
        except (OSError, ValueError):
            # A timeout may already have created a draft. Retain the failure; next dispatch classifies it.
            api.releases()
            raise
    else:
        record = matches[0]
    release_binding(record, expected, body)
    release_id = record['id']
    record = api.get('/releases/' + identifier(release_id))
    require(record['id'] == release_id, 'Release ID changed on exact readback')
    require(type(record.get('immutable')) is bool, 'Unknown immutable state')
    require(not (record['draft'] and record['immutable']), 'Immutable draft is not a publishable state')
    release_binding(record, expected, body)
    actual = read_assets(api, release_id, assets)
    if not record['draft']:
        require(actual == assets, 'Published/immutable Release is incomplete; no repairs allowed')
        if expected['channel'] == 'stable':
            require(api.get('/releases/latest')['id'] == release_id, 'Stable latest Release readback differs')
        return release_id  # Exact published rerun: read-only no-op.
    for name in sorted(assets.keys() - actual.keys()):
        try:
            uploaded = api.request('POST', api.prefix + '/releases/' + identifier(release_id)
                                   + '/assets?name=' + urllib.parse.quote(name, safe=''), assets[name], upload=True)
            require(uploaded['name'] == name and api.download(uploaded) == assets[name], 'Uploaded asset readback differs')
            # Include exact returned ID in readback, not merely another same-name object.
            rows = api.assets(release_id)
            require(sum(row['id'] == uploaded['id'] and row['name'] == name for row in rows) == 1,
                    'Uploaded asset ID missing/ambiguous on readback')
        except (OSError, ValueError):
            api.assets(release_id)  # Includes starter assets; no DELETE and no automatic POST retry.
            raise
    require(read_assets(api, release_id, assets) == assets, 'Draft asset inventory incomplete')
    recheck()  # Fresh configuration/tag/history immediately before the final mutation.
    flags = {'draft': False, 'prerelease': expected['channel'] == 'rc',
             'make_latest': 'false' if expected['channel'] == 'rc' else 'true'}
    try:
        api.request('PATCH', api.prefix + '/releases/' + identifier(release_id), flags)
    except (OSError, ValueError):
        api.get('/releases/' + identifier(release_id))
        raise
    final = api.get('/releases/' + identifier(release_id))
    release_binding(final, expected, body)
    require(final['draft'] is False and final['id'] == release_id, 'Published release readback differs')
    require(read_assets(api, release_id, assets) == assets, 'Published asset readback differs')
    if expected['channel'] == 'stable':
        require(api.get('/releases/latest')['id'] == release_id, 'Stable latest Release readback differs')
    recheck()  # Includes peeled tag/source and accepted history after publication readback.
    return release_id
