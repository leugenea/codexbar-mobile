"""Pure offline acquisition security/argv/state controls; no Git or network probes."""
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

from complete_codeql_checkout import (Git, PUBLIC_URL, config_entries, release_refs,
                                     safe_environment, validate_config, validate_snapshot, prepare)


class AcquisitionContracts(unittest.TestCase):
    def test_environment_discards_credentials_proxy_tracing_config_and_lazy_overrides(self):
        original = dict(PATH='/usr/bin', GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='http.extraheader',
                        GIT_CONFIG_VALUE_0='synthetic-secret', GH_TOKEN='synthetic-secret',
                        HTTPS_PROXY='https://wrong.invalid', GIT_TRACE='1', GIT_ASKPASS='/unsafe',
                        GIT_OBJECT_DIRECTORY='/unsafe', GIT_ALTERNATE_OBJECT_DIRECTORIES='/unsafe')
        environment = safe_environment(original)
        for key in original.keys() - {'PATH', 'GIT_ASKPASS'}:
            self.assertNotIn(key, environment)
        self.assertEqual(environment['GIT_ASKPASS'], '/bin/false')
        self.assertEqual(environment['GIT_OPTIONAL_LOCKS'], '0')
        self.assertEqual(environment['GIT_NO_LAZY_FETCH'], '1')
        self.assertEqual(environment['GIT_CONFIG_GLOBAL'], '/dev/null')
        self.assertEqual(environment['GIT_ALLOW_PROTOCOL'], 'https')

    def test_public_transport_argv_resets_headers_helpers_and_side_effects_without_values(self):
        calls = []
        def fake(argv, **kwargs):
            calls.append((argv, kwargs))
            return subprocess.CompletedProcess(argv, 0, b'', b'')
        git = Git('/opt/data/tmp/synthetic-root', {'PATH': '/usr/bin'}, fake)
        git.headers = ['http.https://github.com/.extraheader']
        git.call('fetch', '--no-tags', '--no-write-fetch-head', '--no-auto-maintenance',
                 '--no-write-commit-graph', '--refmap=', PUBLIC_URL, 'a' * 40)
        argv, options = calls[0]
        for guard in ('credential.helper=', 'http.extraHeader=', 'http.https://github.com/.extraheader=',
                      'http.followRedirects=false', 'http.proxy=', 'http.cookieFile=', 'http.sslCert=',
                      'core.hooksPath=/dev/null', 'core.fsmonitor=false', 'protocol.allow=never'):
            self.assertIn(guard, argv)
        self.assertEqual(argv[-2:], [PUBLIC_URL, 'a' * 40])
        self.assertFalse(options['check'])
        self.assertLessEqual(options['timeout'], 60)
        self.assertNotIn('origin', argv)

    def test_failure_never_exposes_git_diagnostics_or_retries(self):
        calls = []
        def refused(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 1, b'Authorization: synthetic-secret', b'synthetic-secret')
        with self.assertRaisesRegex(ValueError, 'refused') as error:
            Git('/opt/data/tmp/synthetic', {}, refused).call('fetch', PUBLIC_URL, 'a' * 40)
        self.assertNotIn('synthetic-secret', str(error.exception))
        self.assertEqual(len(calls), 1)
        def timeout(argv, **kwargs):
            raise subprocess.TimeoutExpired(argv, 60, stderr='synthetic-secret')
        with self.assertRaisesRegex(ValueError, 'timed out'):
            Git('/opt/data/tmp/synthetic', {}, timeout).call('fetch', PUBLIC_URL, 'a' * 40)

    def test_config_parsing_resets_managed_headers_but_rejects_unsafe_scopes(self):
        raw = b'core.filemode\ntrue\0http.https://github.com/.extraheader\nAuthorization: synthetic\0credential.helper\nunsafe-helper\0'
        headers, includes = validate_config(config_entries(raw), '/opt/data/tmp')
        self.assertEqual(headers, ['http.https://github.com/.extraheader'])
        self.assertEqual(includes, [])
        for key in ('url.https://wrong.invalid.insteadof', 'http.proxy', 'http.sslcert',
                    'include.path', 'remote.origin.promisor', 'extensions.partialclone',
                    'credential.https://github.com.helper', 'credential.username'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_config([(key, 'synthetic')], '/opt/data/tmp')
        for key in ('http.https://userinfo:synthetic@github.com/.extraheader', 'http.https://wrong.invalid/.extraheader'):
            with self.assertRaises(ValueError):
                validate_config([(key, 'synthetic')], '/opt/data/tmp')

    def test_authorization_include_is_only_nonrecursive_runner_temp_checkout_data(self):
        key = 'includeif.gitdir:/synthetic/.git.path'
        path = '/opt/data/tmp/git-credentials-abc-123.config'
        self.assertEqual(validate_config([(key, path)], '/opt/data/tmp')[1], [Path(path)])
        for wrong in ('../git-credentials-a.config', '/wrong/git-credentials-a.config', '/opt/data/tmp/other.config'):
            with self.assertRaises(ValueError):
                validate_config([(key, wrong)], '/opt/data/tmp')
        with self.assertRaises(ValueError):
            validate_config([('includeif.onbranch:main.path', path)], '/opt/data/tmp')

    def test_release_advertisement_requires_exact_raw_canonical_ref_objects(self):
        text = 'a' * 40 + '\trefs/tags/v0.1.0\n' + 'b' * 40 + '\trefs/tags/v0.1.0-rc.1\n'
        self.assertEqual(len(release_refs(text)), 2)
        for malformed in (text + text, 'bad\trefs/tags/v0.1.0', 'a' * 40 + '\trefs/tags/v0.1.0-rc.0',
                          'a' * 40 + '\trefs/heads/main', 'a' * 40 + '\trefs/tags/v01.1.0'):
            with self.assertRaises(ValueError):
                release_refs(malformed)
        self.assertEqual(release_refs('a' * 40 + '\trefs/tags/unrelated'), {})

    def test_missing_extractor_marker_fails_before_any_git_or_network(self):
        def forbidden(*args, **kwargs):
            self.fail('Missing marker reached a Git subprocess')
        with self.assertRaises(ValueError):
            prepare('/opt/data/tmp/synthetic', 'managed', {}, forbidden)

    def test_snapshot_cannot_replace_actual_local_graph_or_source_with_a_stamp(self):
        context = {'fixture': True}
        state = {'head': 'a' * 40}
        from complete_codeql_checkout import canonical, digest
        snapshot = dict(schema=1, mode='fixture', context=context, state=state,
                        tags={}, peeled={}, tagMapSha256=digest(canonical({})))
        with patch('complete_codeql_checkout.fingerprint', return_value=state), patch('complete_codeql_checkout.verify_tags', return_value={}) as actual:
            self.assertEqual(validate_snapshot(object(), snapshot, context), snapshot)
            actual.assert_called_once()
        for change in ('context', 'state', 'tagMapSha256'):
            broken = snapshot | {change: 'tampered'}
            with patch('complete_codeql_checkout.fingerprint', return_value=state), self.assertRaises(ValueError):
                validate_snapshot(object(), broken, context)
        with patch('complete_codeql_checkout.fingerprint', return_value=state), patch('complete_codeql_checkout.verify_tags', side_effect=ValueError('Missing parent/object')), self.assertRaises(ValueError):
            validate_snapshot(object(), snapshot, context)
        with self.assertRaises(ValueError):
            validate_snapshot(object(), {'completed': True}, context)

    def test_settings_prepares_before_app_and_preserves_extraction_cache_guard(self):
        source = (Path(__file__).resolve().parents[2] / 'settings.gradle').read_text()
        self.assertIn("environmentVariable('CODEQL_EXTRACTOR_JAVA_TRAP_DIR').orElse('').get().trim()", source)
        self.assertIn("'tools/build/complete_codeql_checkout.py', '--mode', 'managed'", source)
        self.assertLess(source.index('complete_codeql_checkout.py'), source.index("include ':app'"))
        self.assertIn('enabled = false', source)
