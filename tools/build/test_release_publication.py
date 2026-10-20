"""Pure publication protocol and transport failure/recovery controls; no real Release writes."""
import copy
import json
import unittest
from urllib.parse import urlsplit

from release_mock import ReleaseAPI
from release_publication import GitHub, marker, publish, read_assets
from release_runtime import canonical
import test_release_policy as fixtures


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.expected = fixtures.policy.admit(fixtures.PolicyAPI(), fixtures.configuration(), fixtures.context())[1]
        self.assets = {'candidate.apk': b'SYNTHETIC not an APK', 'release-manifest.json': b'SYNTHETIC manifest',
                       'SHA256SUMS': b'SYNTHETIC checksums'}
        self.model = ReleaseAPI()
        self.api = GitHub('fixture/repository', 'synthetic-token', self.model.transport)
        self.checks = 0

    def recheck(self):
        self.checks += 1

    def writes(self):
        return [call for call in self.model.calls if call[0] != 'GET']

    def published(self):
        return publish(self.api, self.expected, self.assets, self.recheck)

    def test_exact_create_upload_readback_flags_and_published_readonly_noop(self):
        release_id = self.published()
        self.assertEqual(read_assets(self.api, release_id), self.assets)
        first = self.writes()[0]
        create = json.loads(first[2])
        self.assertEqual(create, {'tag_name': self.expected['tag'], 'target_commitish': self.expected['source']['sha'],
                                 'name': self.expected['versionName'], 'body': marker(self.assets), 'draft': True,
                                 'prerelease': True, 'make_latest': 'false', 'generate_release_notes': False})
        self.assertEqual(json.loads(self.writes()[-1][2]), {'draft': False, 'prerelease': True, 'make_latest': 'false'})
        self.assertEqual(self.checks, 3)
        self.model.calls.clear()
        self.assertEqual(self.published(), release_id)
        self.assertEqual(self.writes(), [])

    def test_stable_flags_are_explicit_not_inherited_from_rc(self):
        self.expected.update(channel='stable', tag='v0.1.0', versionName='0.1.0')
        self.published()
        self.assertIs(json.loads(self.writes()[0][2])['prerelease'], False)
        self.assertEqual(json.loads(self.writes()[-1][2]), {'draft': False, 'prerelease': False, 'make_latest': 'true'})

    def test_partial_owned_draft_resumes_only_missing_assets_without_clobber(self):
        self.published()
        self.model.records[0]['draft'] = True
        existing_id = next(iter(self.model.files))
        keep = self.model.files[existing_id]
        self.model.files = {existing_id: keep}
        self.model.calls.clear()
        self.published()
        uploads = [c for c in self.writes() if c[0] == 'POST']
        self.assertEqual(len(uploads), len(self.assets) - 1)
        self.assertEqual(self.model.files[existing_id], keep)
        self.assertFalse(any(c[0] == 'DELETE' for c in self.model.calls))

    def test_conflicts_and_unowned_or_published_partial_state_have_zero_writes(self):
        self.published()
        baseline = copy.deepcopy((self.model.records, self.model.files))
        mutations = [lambda: self.model.records[0].update(body='other candidate'),
                     lambda: self.model.records[0].update(target_commitish='f' * 40),
                     lambda: self.model.records.append(copy.deepcopy(self.model.records[0])),
                     lambda: self.model.files.pop(next(iter(self.model.files))),
                     lambda: self.model.files[next(iter(self.model.files))][0].update(state='starter'),
                     lambda: self.model.files[next(iter(self.model.files))][0].update(name='unexpected.txt'),
                     lambda: self.model.records[0].update(prerelease=False)]
        for mutation in mutations:
            self.model.records, self.model.files = copy.deepcopy(baseline)
            mutation()
            self.model.calls.clear()
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.published()
            self.assertEqual(self.writes(), [])
        self.model.records, self.model.files = copy.deepcopy(baseline)
        self.model.records[0].update(draft=True, immutable=True)
        self.model.files.pop(next(iter(self.model.files)))
        self.model.calls.clear()
        with self.assertRaises(ValueError):
            self.published()
        self.assertEqual(self.writes(), [])

    def test_download_corruption_and_asset_api_digest_conflict_deny_before_writes(self):
        self.published()
        key = next(iter(self.model.files))
        row, data = self.model.files[key]
        self.model.files[key] = row, data + b'corrupt'
        self.model.calls.clear()
        with self.assertRaises(ValueError):
            self.published()
        self.assertEqual(self.writes(), [])

    def test_upload_failure_rereads_by_id_and_never_blind_retries_or_deletes_starter(self):
        def failure(method, path, data):
            if method == 'POST' and path.endswith('/assets'):
                return 502, {}, b'upstream failure'
        self.model.fail = failure
        with self.assertRaises(ValueError):
            self.published()
        self.assertEqual(sum(c[0] == 'POST' and c[1].endswith('/assets') for c in self.model.calls), 1)
        self.assertEqual(self.model.calls[-1][0], 'GET')
        self.assertTrue(self.model.calls[-1][1].endswith('/1/assets'))
        self.assertFalse(any(c[0] in ('DELETE', 'PATCH') for c in self.model.calls))

    def test_timeout_after_create_or_publish_reads_back_and_retry_is_not_blind(self):
        real = self.model.transport
        for stage in ('create', 'publish'):
            self.model = ReleaseAPI()
            real = self.model.transport
            tripped = False
            def transport(method, url, headers, data):
                nonlocal tripped
                response = real(method, url, headers, data)
                path = urlsplit(url).path
                target = (method == 'POST' and path.endswith('/releases')) if stage == 'create' else method == 'PATCH'
                if target and not tripped:
                    tripped = True
                    raise TimeoutError('synthetic lost response after write')
                return response
            self.api = GitHub('fixture/repository', 'synthetic-token', transport)
            with self.subTest(stage=stage), self.assertRaises(TimeoutError):
                self.published()
            self.assertEqual(len(self.model.records), 1)
            self.assertEqual(self.model.calls[-1][0], 'GET')
            prior_posts = sum(c[0] == 'POST' and c[1].endswith('/releases') for c in self.model.calls)
            self.published()
            self.assertEqual(sum(c[0] == 'POST' and c[1].endswith('/releases') for c in self.model.calls), prior_posts)
            self.assertFalse(any(c[0] == 'DELETE' for c in self.model.calls))

    def test_recheck_failure_never_publishes_owned_complete_draft(self):
        def check():
            self.recheck()
            if self.checks == 2:
                raise ValueError('revoked/stale')
        with self.assertRaises(ValueError):
            publish(self.api, self.expected, self.assets, check)
        self.assertTrue(self.model.records[0]['draft'])
        self.assertFalse(any(c[0] == 'PATCH' for c in self.writes()))

    def test_real_protocol_through_localhost_without_external_credentials(self):
        with self.model.localhost() as transport:
            api = GitHub('fixture/repository', 'not-forwarded', transport)
            release_id = publish(api, self.expected, self.assets, self.recheck)
            self.assertEqual(read_assets(api, release_id), self.assets)


class TransportTests(unittest.TestCase):
    def test_unsafe_and_foreign_endpoints_deny_before_transport(self):
        def transport(*args):
            self.fail('Rejected endpoint reached transport')
        api = GitHub('fixture/repository', 'synthetic-token', transport)
        for suffix in ('/../x', '/a/../../b', '/a/..', '/./releases', '/a/.',
                       '/a/../releases?name=safe', '/releases#fragment', '/https://evil.invalid'):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, 'Unsafe API endpoint'):
                api.request('GET', api.prefix + suffix)
        for path in ('https://evil.invalid/repos/fixture/repository/releases',
                     '/repos/other/repository/releases', api.prefix + '-other/releases'):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'Foreign repository endpoint'):
                api.request('GET', path)

    def test_pagination_all_pages_and_repeated_or_incomplete_rows_deny(self):
        calls = []
        def transport(method, url, headers, data):
            calls.append(url)
            return 200, {}, canonical([{'id': n} for n in range(1, 101)] if '&page=1' in url else [{'id': 101}])
        api = GitHub('fixture/repository', 'synthetic-token', transport, read_only=True)
        self.assertEqual(len(api.releases()), 101)
        self.assertEqual(len(calls), 2)
        with self.assertRaises(ValueError):
            api.request('POST', api.prefix + '/releases', {})
        api.transport = lambda *args: (403, {}, b'forbidden')
        with self.assertRaises(ValueError):
            api.releases()
        api.transport = lambda *args: (200, {}, canonical([{'id': 1}] * 100))
        with self.assertRaises(ValueError):
            api.releases()
        api.transport = lambda *args: (200, {}, canonical({'total_count': 2, 'rows': [{'id': 1}]}))
        with self.assertRaises(ValueError):
            api.pages('/path', 'rows')

    def test_binary_redirect_strips_authorization_and_denies_arbitrary_or_cleartext_origins(self):
        for location in ('https://release-assets.githubusercontent.com/public.apk',
                         'https://objects.githubusercontent.com/archive.zip'):
            calls = []
            def transport(method, url, headers, data):
                calls.append((url, headers))
                return (302, {'location': location}, b'') if len(calls) == 1 else (200, {}, b'asset')
            api = GitHub('fixture/repository', 'synthetic-token', transport)
            self.assertEqual(api.request('GET', api.prefix + '/releases/assets/1', binary=True), b'asset')
            self.assertIn('Authorization', calls[0][1])
            self.assertNotIn('Authorization', calls[1][1])
        for url in ('http://release-assets.githubusercontent.com/file', 'https://evil.invalid/file',
                    'https://release-assets.githubusercontent.com.evil.invalid/file',
                    'https://user:password@release-assets.githubusercontent.com/file',
                    'https://release-assets.githubusercontent.com:444/file'):
            api = GitHub('fixture/repository', 'synthetic-token', lambda *args: (302, {'location': url}, b''))
            with self.subTest(url=url), self.assertRaises(ValueError):
                api.request('GET', api.prefix + '/releases/assets/1', binary=True)
        with self.assertRaises(ValueError):
            api.request('GET', '/repos/other/repository/releases')


if __name__ == '__main__':
    unittest.main()
