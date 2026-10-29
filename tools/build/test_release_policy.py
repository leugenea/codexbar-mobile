"""Pure config/source/run/history controls; all remote and SDK observations are synthetic."""
import base64
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qsl, urlencode, urlsplit

import release_policy as policy
from release_publication import GitHub
from release_runtime import canonical, fingerprint
from release_signing import zip_bytes


def configuration(stable=False):
    return {'schema': 2, 'certificateSha256': 'd' * 64, 'signingEnvironment': 'release-signing',
            'publicationEnvironment': 'release-publication', 'rcEnabled': True, 'stableEnabled': stable}


def context(channel='rc', operation='prepare'):
    return {'repository': 'leugenea/codexbar-mobile', 'repositoryId': 7, 'event': 'workflow_dispatch',
            'ref': 'refs/heads/main', 'sha': 'c' * 40, 'workflowSha': 'c' * 40,
            'workflowRef': 'leugenea/codexbar-mobile/' + policy.WORKFLOW + '@refs/heads/main',
            'runId': 100, 'runNumber': 10, 'operation': operation, 'channel': channel,
            'prepareRunId': '90' if operation == 'publish' else '',
            'notes': 'Stable changes' if channel == 'stable' and operation == 'publish' else '',
            'tag': 'v0.1.0' + ('-rc.1' if channel == 'rc' else '')}


class PolicyAPI:
    repository = 'leugenea/codexbar-mobile'
    prefix = '/repos/' + repository

    def __init__(self, ctx=None):
        self.ctx = ctx or context()
        self.overrides, self.records, self.calls = {}, [], []

    def get(self, path):
        self.calls.append(path)
        if path in self.overrides:
            value = self.overrides[path]
            if isinstance(value, Exception):
                raise value
            return copy.deepcopy(value)
        if path == '':
            return {'id': 7, 'full_name': self.repository, 'default_branch': 'main', 'fork': False}
        if path.startswith('/actions/workflows/'):
            return {'id': 8, 'path': policy.WORKFLOW, 'state': 'active'}
        if path.startswith('/actions/runs/'):
            run_id = int(path.split('/')[3])
            return {'id': run_id, 'workflow_id': 8, 'event': 'workflow_dispatch', 'head_branch': 'main',
                    'path': policy.WORKFLOW, 'repository': {'full_name': self.repository}, 'head_sha': 'c' * 40,
                    'run_number': 10 if run_id == 100 else 9, 'run_attempt': 2, 'status': 'completed',
                    'conclusion': 'success', 'display_title': f'prepare {self.ctx["channel"]} {self.ctx["tag"]}'}
        if path == '/git/ref/heads/main':
            return {'object': {'sha': 'c' * 40}}
        if path.startswith('/git/ref/tags/'):
            return {'object': {'sha': 'a' * 40, 'type': 'commit'}}
        if path.startswith('/git/commits/'):
            return {'tree': {'sha': 'b' * 40}}
        if path.startswith('/git/trees/'):
            return {'truncated': False, 'tree': [{'path': '.github', 'type': 'tree', 'sha': 'e' * 40},
                                                {'path': 'workflows', 'type': 'tree', 'sha': 'f' * 40}]}
        if path.startswith('/compare/'):
            return {'status': 'ahead'}
        if path.startswith('/contents/'):
            return {'encoding': 'base64', 'content': base64.b64encode(b'0.1.0\n').decode()}
        if path.startswith('/environments/'):
            return {'name': path.split('/')[2], 'protection_rules': [
                {'type': 'required_reviewers', 'prevent_self_review': False, 'reviewers': [{'type': 'User'}]}],
                'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True}}
        raise AssertionError(path)

    def pages(self, path, key=None):
        self.calls.append(path)
        if path in self.overrides:
            value = self.overrides[path]
            if isinstance(value, Exception):
                raise value
            return copy.deepcopy(value)
        if path.endswith('/runs?event=workflow_dispatch'):
            return []
        if 'deployment-branch-policies' in path:
            return [{'id': 1, 'name': 'main', 'type': 'branch'}]
        filename = path.split('/')[3]
        return [{'id': 1, 'head_sha': 'a' * 40, 'head_branch': 'main', 'event': 'push',
                 'path': '.github/workflows/' + filename, 'repository': {'id': 7},
                 'status': 'completed', 'conclusion': 'success'}]

    def releases(self):
        return copy.deepcopy(self.records)


def wrapper(model):
    def transport(method, url, headers, data):
        parsed = urlsplit(url)
        storage = 'https://fixture.blob.core.windows.net/artifact.zip'
        if url == storage:
            assert (method, headers, data) == ('GET', {'Accept': 'application/octet-stream'}, None)
            return 200, {}, model.archive
        assert (method, parsed.scheme, parsed.netloc, data) == ('GET', 'https', 'api.github.com', None)
        assert headers['Authorization'] == 'Bearer synthetic-token'
        assert parsed.path.startswith(model.prefix)
        path = parsed.path[len(model.prefix):]
        if path.startswith('/actions/artifacts/') and path.endswith('/zip'):
            if headers.get('Accept') != 'application/vnd.github+json':
                return 415, {}, b'Actions artifact download requires application/vnd.github+json'
            return 302, {'location': storage}, b''
        query = parse_qsl(parsed.query)
        paginated = ('page', '1') in query
        query = [(key, value) for key, value in query if key not in ('page', 'per_page')]
        if query:
            path += '?' + urlencode(query)
        if paginated and path == '/releases':
            return 200, {}, canonical(model.releases())
        if paginated:
            key = ('branch_policies' if 'deployment-branch-policies' in path else
                   'artifacts' if path.endswith('/artifacts') else 'workflow_runs')
            rows = model.pages(path, key)
            response = {'total_count': len(rows), key: rows}
        else:
            response = model.get(path)
        return 200, {}, canonical(response)
    return GitHub(model.repository, 'synthetic-token', transport, read_only=True)


class ConfigurationTests(unittest.TestCase):
    def test_only_static_config_and_explicit_channel_enablement(self):
        config = configuration()
        self.assertEqual(policy.configuration(json.dumps(config), 'rc'), config)
        for channel in ('stable', 'dev'):
            with self.assertRaises(ValueError):
                policy.configuration(json.dumps(config), channel)
        for channel in ('rc', 'stable'):
            disabled = {**configuration(True), channel + 'Enabled': False}
            with self.assertRaises(ValueError):
                policy.configuration(json.dumps(disabled), channel)
        self.assertEqual(policy.configuration(json.dumps(configuration(True)), 'stable'), configuration(True))

    def test_missing_unknown_duplicate_types_and_noncanonical_config_deny(self):
        from jsonschema import ValidationError
        for text in ('', ' ', '{}', 'null', '{"schema":2,"schema":2}'):
            with self.subTest(text=text), self.assertRaises((ValueError, ValidationError)):
                policy.configuration(text, 'rc')
        for key, value in [('schema', True), ('schema', 2.0), ('schema', 1), ('extra', 'x'),
                           ('certificateSha256', 'D' * 64), ('certificateSha256', 'd' * 64 + '\n'),
                           ('signingEnvironment', ''), ('publicationEnvironment', ' release-publication'),
                           ('rcEnabled', 1), ('stableEnabled', 'false')]:
            with self.subTest(key=key, value=value), self.assertRaises((ValueError, ValidationError)):
                policy.configuration(json.dumps({**configuration(), key: value}), 'rc')
        for key in configuration():
            bad = configuration()
            del bad[key]
            with self.assertRaises(ValidationError):
                policy.configuration(json.dumps(bad), 'rc')

    def test_admission_through_real_github_wrapper_both_operations_and_channels(self):
        for channel in ('rc', 'stable'):
            for operation in ('prepare', 'publish'):
                with self.subTest(channel=channel, operation=operation):
                    ctx = context(channel, operation)
                    model = PolicyAPI(ctx)
                    record, expected, origin = policy.admit(wrapper(model), configuration(True), ctx)
                    self.assertIn('/compare/' + 'a' * 40 + '...' + 'c' * 40, model.calls)
                    self.assertEqual(record['sourceSha'], 'a' * 40)
                    self.assertEqual(expected['source'], {'sha': 'a' * 40, 'tree': 'b' * 40})
                    self.assertEqual(expected['channel'], channel)
                    self.assertEqual(expected['versionCode'], 10 if operation == 'prepare' else 9)
                    self.assertEqual(None if origin is None else origin['id'], None if operation == 'prepare' else 90)

    def test_notes_admission_requires_stable_publish_and_rejects_prepare_notes_or_invalid_text(self):
        for channel, operation, notes in [('stable', 'publish', ''), ('stable', 'publish', ' \n\t'),
                                          ('rc', 'prepare', 'Changes'), ('stable', 'prepare', 'Changes'),
                                          ('rc', 'publish', 'bad\0text'), ('rc', 'publish', 'x' * 20001),
                                          ('rc', 'publish', None)]:
            ctx = {**context(channel, operation), 'notes': notes}
            model = PolicyAPI(ctx)
            with self.subTest(channel=channel, operation=operation, notes=notes), self.assertRaises(ValueError):
                policy.admit(wrapper(model), configuration(True), ctx)
            self.assertEqual(model.calls, [])

    def test_source_admission_requires_active_gates_without_retired_research_run(self):
        expected = ('android.yml', 'repository-policy.yml', 'codeql.yml')
        self.assertEqual(policy.SOURCE_GATES, expected)
        for operation in ('prepare', 'publish'):
            ctx = context(operation=operation)
            model = PolicyAPI(ctx)
            retired = '/actions/workflows/research-contract.yml/runs?head_sha=' + 'a' * 40 + '&event=push'
            model.overrides[retired] = AssertionError('Retired workflow must not be queried')
            policy.admit(wrapper(model), configuration(), ctx)
            self.assertEqual({path for path in model.calls if '&event=push' in path}, {
                '/actions/workflows/' + name + '/runs?head_sha=' + 'a' * 40 + '&event=push'
                for name in expected})

    def test_event_controller_source_green_ci_and_environment_drift_deny(self):
        for key, value in [('event', 'pull_request'), ('ref', 'refs/heads/feature'), ('repository', 'fork/repo'),
                           ('repositoryId', 9), ('runNumber', 11), ('sha', 'e' * 40), ('workflowSha', 'f' * 40),
                           ('workflowRef', 'wrong'), ('operation', 'sign'), ('tag', 'v0.2.0-rc.1'),
                           ('tag', 'v0.1.0'), ('prepareRunId', '90')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                policy.admit(wrapper(PolicyAPI()), configuration(), {**context(), key: value})
        for path, response in [('/git/ref/heads/main', {'object': {'sha': 'e' * 40}}),
                               ('/git/trees/' + 'b' * 40, {'truncated': True, 'tree': []}),
                               ('/compare/' + 'a' * 40 + '...' + 'c' * 40, {'status': 'diverged'}),
                               ('/actions/workflows/release.yml', {'id': 8, 'path': 'other.yml', 'state': 'active'}),
                               ('/environments/release-signing', OSError('unknown remote state'))]:
            api = PolicyAPI()
            api.overrides[path] = response
            with self.subTest(path=path), self.assertRaises((ValueError, OSError)):
                policy.admit(wrapper(api), configuration(), context())
        for filename in policy.SOURCE_GATES:
            api = PolicyAPI()
            path = '/actions/workflows/' + filename + '/runs?head_sha=' + 'a' * 40 + '&event=push'
            for rows in ([], [{**api.pages(path)[0], 'conclusion': 'failure'}],
                         [{**api.pages(path)[0], 'event': 'pull_request'}]):
                api.overrides[path] = rows
                with self.subTest(filename=filename), self.assertRaises(ValueError):
                    policy.admit(wrapper(api), configuration(), context())
        for change in ({'protection_rules': []}, {'protection_rules': [{'type': 'required_reviewers', 'reviewers': []}]},
                       {'deployment_branch_policy': None}):
            api = PolicyAPI()
            path = '/environments/release-signing'
            api.overrides[path] = {**api.get(path), **change}
            with self.assertRaises(ValueError):
                policy.admit(wrapper(api), configuration(), context())
        api = PolicyAPI()
        api.overrides['/environments/release-signing/deployment-branch-policies'] = [{'id': 1, 'name': '*', 'type': 'branch'}]
        with self.assertRaises(ValueError):
            policy.admit(wrapper(api), configuration(), context())

    def test_environment_self_review_policy_does_not_substitute_for_required_reviewer(self):
        for prevent in (False, True):
            api = PolicyAPI()
            path = '/environments/release-signing'
            api.overrides[path] = {**api.get(path), 'protection_rules': [
                {'type': 'required_reviewers', 'prevent_self_review': prevent, 'reviewers': [{'type': 'User'}]}]}
            self.assertEqual(policy.admit(wrapper(api), configuration(), context())[1]['versionCode'], 10)
        api.overrides[path]['protection_rules'] *= 2
        with self.assertRaises(ValueError):
            policy.admit(wrapper(api), configuration(), context())

    def test_wrong_foreign_failed_prepare_runs_and_invalid_counters_deny(self):
        ctx = context(operation='publish')
        for change in ({'workflow_id': 9}, {'event': 'push'}, {'head_branch': 'feature'}, {'path': 'other.yml'},
                       {'repository': {'full_name': 'fork/repo'}}, {'conclusion': 'failure'}, {'status': 'in_progress'},
                       {'display_title': 'publish rc v0.1.0-rc.1'}, {'display_title': 'prepare rc v0.1.0-rc.2'},
                       {'display_title': 'prepare stable v0.1.0'}, {'run_number': 0}, {'run_number': 2100000001}):
            api = PolicyAPI(ctx)
            api.overrides['/actions/runs/90'] = {**api.get('/actions/runs/90'), **change}
            with self.subTest(change=change), self.assertRaises(ValueError):
                policy.admit(wrapper(api), configuration(), ctx)
        for run_id in ('', '090', '-1', '1.0', '100'):
            with self.subTest(run_id=run_id), self.assertRaises(ValueError):
                policy.admit(wrapper(PolicyAPI(ctx)), configuration(), {**ctx, 'prepareRunId': run_id})
        for code in (0, 2100000001, 10.0, True):
            api = PolicyAPI()
            api.overrides['/actions/runs/100'] = {**api.get('/actions/runs/100'), 'run_number': code}
            with self.assertRaises(ValueError):
                policy.admit(wrapper(api), configuration(), context())


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.ctx = context(operation='publish')
        self.model = PolicyAPI(self.ctx)
        self.api = wrapper(self.model)
        self.record, self.expected, self.origin = policy.admit(self.api, configuration(), self.ctx)
        self.manifest = {'buildInputs': self.expected,
                         'authority': {'repository': self.api.repository, 'workflowId': 8,
                                       'controllerSha': 'c' * 40, 'runId': 90, 'runAttempt': 2}}
        self.assets = {'release-manifest.json': canonical(self.manifest)}  # Synthetic, no signature.
        self.model.archive = zip_bytes(self.assets)
        self.artifact = {'id': 80, 'name': 'signed-90-2', 'expired': False,
                         'digest': 'sha256:' + fingerprint(self.model.archive)['sha256'],
                         'workflow_run': {'id': 90, 'head_sha': 'c' * 40}}
        self.model.overrides['/actions/runs/90/artifacts'] = [self.artifact]
        self.model.overrides['/actions/artifacts/80'] = self.artifact
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)

    def read(self):
        return policy.candidate_assets(self.api, self.record, self.expected, self.origin, self.path, self.path)

    def test_real_wrapper_resolves_retained_candidate_and_independent_verifier_inputs(self):
        with patch.object(policy, 'verify_assets', return_value=(self.manifest, None)) as verify:
            self.assertEqual(self.read(), self.assets)
            self.assertEqual(verify.call_args.kwargs, {'expected_certificate': 'd' * 64,
                'expected_binding': {key: self.expected[key] for key in ('tag', 'source')}, 'apk_contract': None,
                'expected_authority': {'repository': self.api.repository, 'workflowId': 8,
                                      'controllerSha': 'c' * 40, 'runId': 90, 'runAttempt': 2}})
        for key, value in [('tag', 'v0.1.0-rc.2'), ('channel', 'stable'), ('source', {'sha': 'e' * 40, 'tree': 'b' * 40}),
                           ('versionCode', 10)]:
            with self.subTest(key=key), patch.object(policy, 'verify_assets',
                    return_value=({'buildInputs': {**self.expected, key: value}}, None)), self.assertRaises(ValueError):
                self.read()
        with patch.object(policy, 'verify_assets', side_effect=ValueError('wrong certificate')), self.assertRaises(ValueError):
            self.read()

    def test_missing_ambiguous_expired_foreign_attempt_digest_and_bytes_deny_before_verification(self):
        for rows in ([], [self.artifact, {**self.artifact, 'id': 81}], [{**self.artifact, 'name': 'signed-90-1'}]):
            self.model.overrides['/actions/runs/90/artifacts'] = rows
            with self.assertRaises(ValueError):
                self.read()
        self.model.overrides['/actions/runs/90/artifacts'] = [self.artifact]
        for key, value in [('expired', True), ('name', 'signed-90-1'), ('digest', 'sha256:' + 'e' * 64),
                           ('workflow_run', {'id': 91, 'head_sha': 'c' * 40}),
                           ('workflow_run', {'id': 90, 'head_sha': 'e' * 40})]:
            self.model.overrides['/actions/artifacts/80'] = {**self.artifact, key: value}
            with self.subTest(key=key), patch.object(policy, 'verify_assets') as verify, self.assertRaises(ValueError):
                self.read()
            verify.assert_not_called()
        self.model.overrides['/actions/artifacts/80'] = self.artifact
        self.model.archive += b'drift'
        with self.assertRaises(ValueError):
            self.read()


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.api = PolicyAPI()
        self.record, self.expected, _ = policy.admit(self.api, configuration(), context())
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.reports = Path(self.temp.name)

    def read(self):
        return policy.history(self.api, self.record, self.expected, self.reports, self.reports)

    def test_complete_empty_inventory_is_derived_but_unknown_read_is_not_zero(self):
        self.assertEqual(self.read(), (0, False))
        with patch.object(self.api, 'releases', side_effect=OSError('offline')), self.assertRaises(OSError):
            self.read()

    def published(self, code=9, tag='v0.1.0-rc.2', run_id=90):
        expected = {**self.expected, 'versionCode': code, 'tag': tag, 'versionName': tag[1:]}
        manifest = {'buildInputs': expected, 'bundleManifestSha256': 'f' * 64,
                    'authority': {'repository': self.api.repository, 'workflowId': 8, 'controllerSha': 'c' * 40,
                                  'runId': run_id, 'runAttempt': 2}}
        assets = {'release-manifest.json': canonical(manifest)}  # Synthetic, no signature.
        from release_publication import marker
        self.api.records = [{'id': 5, 'tag_name': tag, 'target_commitish': 'a' * 40, 'name': tag[1:],
                             'body': marker(assets), 'draft': False, 'prerelease': True}]
        return assets, manifest

    def published_api(self, assets, manifest, notes=''):
        assets['release-manifest.json'] = canonical(manifest)
        from release_mock import ReleaseAPI
        from release_publication import publish
        remote = ReleaseAPI()
        api = GitHub(self.api.repository, 'synthetic-token', remote.transport)
        publish(api, manifest['buildInputs'], assets, lambda: None, notes=notes)
        policy_transport = wrapper(self.api).transport
        def transport(method, url, headers, data):
            if '/releases' in urlsplit(url).path:
                return remote.transport(method, url, headers, data)
            return policy_transport(method, url, headers, data)
        return GitHub(self.api.repository, 'synthetic-token', transport, read_only=True), remote

    def test_real_wrapper_history_accepts_both_body_shapes_and_rejects_marker_tampering(self):
        for channel, tag in [('rc', 'v0.1.0-rc.2'), ('stable', 'v0.1.0')]:
            assets, manifest = self.published(tag=tag)
            manifest['buildInputs']['channel'] = channel
            for notes in ('', '## Changes\n- Fix usage display'):
                api, remote = self.published_api(assets, manifest, notes)
                body = remote.records[0]['body']
                wrong_digest = body[:-1] + ('0' if body[-1] != '0' else '1')
                remote.calls.clear()
                with self.subTest(channel=channel, notes=notes), \
                        patch.object(policy, 'verify_assets', return_value=(manifest, None)):
                    self.assertEqual(policy.history(api, self.record, self.expected, self.reports, self.reports), (9, False))
                    for bad in ('No marker', wrong_digest, body + '\n', body + '\nNot last',
                                body.replace('\n\n', '\n') if notes else 'Notes\n' + body, None):
                        remote.records[0]['body'] = bad
                        with self.subTest(body=bad), self.assertRaisesRegex(ValueError, 'marker'):
                            policy.history(api, self.record, self.expected, self.reports, self.reports)
                self.assertTrue(all(call[0] == 'GET' for call in remote.calls))

    def test_real_wrapper_accepted_publish_requires_same_notes_without_retained_artifact(self):
        ctx = {**context(operation='publish'), 'notes': '## Changes\n- Fix usage display'}
        self.record, self.expected, _ = policy.admit(wrapper(self.api), configuration(), ctx)
        assets, manifest = self.published(9, self.expected['tag'], 90)
        api, remote = self.published_api(assets, manifest, ctx['notes'])
        remote.calls.clear()
        with patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            self.assertEqual(policy.history(api, self.record, self.expected, self.reports, self.reports), (0, True))
            for notes in ('', 'Different notes'):
                self.record['notes'] = notes
                with self.subTest(notes=notes), self.assertRaisesRegex(ValueError, 'Release binding'):
                    policy.history(api, self.record, self.expected, self.reports, self.reports)
        self.assertTrue(all(call[0] == 'GET' for call in remote.calls))
        self.assertFalse(any('/artifacts' in path for path in self.api.calls))

    def test_history_floor_gaps_reset_same_run_noop_and_new_run_same_tag_conflict(self):
        assets, manifest = self.published()
        with patch.object(policy, 'read_assets', return_value=assets), patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            self.assertEqual(self.read(), (9, False))
            self.expected['versionCode'] = 9
            with self.assertRaises(ValueError):
                self.read()
            self.expected['versionCode'] = 100
            self.assertEqual(self.read(), (9, False))
        self.expected['versionCode'] = 10
        assets, manifest = self.published(10, self.expected['tag'], 100)
        with patch.object(policy, 'read_assets', return_value=assets), patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            self.assertEqual(self.read(), (0, True))
            manifest['authority']['runId'] = 90
            with self.assertRaises(ValueError):
                self.read()

    def test_publish_history_requires_verified_exact_origin_attempt_for_readonly_noop(self):
        self.record, self.expected, _ = policy.admit(self.api, configuration(), context(operation='publish'))
        assets, manifest = self.published(9, self.expected['tag'], 90)
        with patch.object(policy, 'read_assets', return_value=assets), patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            self.assertEqual(self.read(), (0, True))
            manifest['authority']['runAttempt'] = 1
            with self.assertRaises(ValueError):
                self.read()

    def test_real_wrapper_published_noop_survives_successful_prepare_rerun(self):
        model = self.api
        api = wrapper(model)
        prepare_ctx = {**context(), 'runId': 90, 'runNumber': 9}
        record, expected, _ = policy.admit(api, configuration(), prepare_ctx)
        self.assertEqual(policy.history(api, record, expected, self.reports, self.reports), (0, False))
        publish_ctx = context(operation='publish')
        record, expected, _ = policy.admit(api, configuration(), publish_ctx)
        assets, manifest = self.published(9, expected['tag'], 90)
        published = copy.deepcopy(model.records)
        # Synthetic accepted-release/signature observations; GitHub reads use the real wrapper.
        with patch.object(policy, 'read_assets', return_value=assets), \
                patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            self.assertEqual(policy.history(api, record, expected, self.reports, self.reports), (0, True))
            original = model.get('/actions/runs/90')
            model.overrides['/actions/runs/90/attempts/2'] = copy.deepcopy(original)
            model.overrides['/actions/runs/90'] = {**original, 'run_attempt': 3}
            record, prepare_expected, _ = policy.admit(api, configuration(), prepare_ctx)
            self.assertEqual(prepare_expected, expected)
            self.assertEqual(policy.history(api, record, expected, self.reports, self.reports), (0, True))
            record, publish_expected, _ = policy.admit(api, configuration(), publish_ctx)
            self.assertEqual(publish_expected, expected)
            self.assertEqual(record['prepareAttempt'], 3)
            self.assertEqual(manifest['authority']['runAttempt'], 2)
            self.assertEqual(policy.history(api, record, expected, self.reports, self.reports), (0, True))
            self.assertEqual(model.records, published)
            self.assertIn('/actions/runs/90/attempts/2', model.calls)
            self.assertFalse(any('/artifacts' in path for path in model.calls))
            model.overrides['/actions/runs/90/attempts/2'] = {**original, 'conclusion': 'failure'}
            with self.assertRaisesRegex(ValueError, 'History origin attempt not accepted'):
                policy.history(api, record, expected, self.reports, self.reports)

    def test_retained_official_candidate_reserves_tag_and_expired_unknown_blocks(self):
        path = '/actions/workflows/8/runs?event=workflow_dispatch'
        run = {'id': 90, 'status': 'completed', 'conclusion': 'success', 'path': policy.WORKFLOW,
               'event': 'workflow_dispatch', 'head_branch': 'main', 'repository': {'id': 7},
               'head_sha': 'c' * 40, 'run_number': 9}
        self.api.overrides[path] = [run]
        manifest = {'buildInputs': {**self.expected, 'versionCode': 9},
                    'authority': {'runId': 90, 'workflowId': 8, 'repository': self.api.repository,
                                  'controllerSha': 'c' * 40, 'runAttempt': 2}}
        raw = zip_bytes({'release-manifest.json': canonical(manifest)})
        artifact = {'id': 80, 'name': 'signed-90-2', 'expired': False, 'digest': 'sha256:' + fingerprint(raw)['sha256']}
        self.api.overrides['/actions/runs/90/artifacts'] = [artifact]
        with patch.object(self.api, 'request', return_value=raw, create=True) as download, \
                patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            with self.assertRaisesRegex(ValueError, 'Tag already admitted'):
                self.read()
            manifest['buildInputs']['tag'] = 'v0.1.0-rc.2'
            raw = zip_bytes({'release-manifest.json': canonical(manifest)})
            artifact['digest'] = 'sha256:' + fingerprint(raw)['sha256']
            download.return_value = raw
            self.assertEqual(self.read(), (0, False))
            artifact['expired'] = True
            with self.assertRaisesRegex(ValueError, 'Expired unresolved'):
                self.read()
        self.api.overrides[path] = [dict(run, status='queued')]
        self.assertEqual(self.read(), (0, False))

    def test_partial_unknown_drafts_duplicates_and_sdk_history_failures_deny(self):
        self.api.records = [{'id': 1, 'tag_name': self.expected['tag'], 'draft': True}]
        with self.assertRaises(ValueError):
            self.read()
        self.api.records *= 2
        with self.assertRaises(ValueError):
            self.read()
        assets, _ = self.published()
        with patch.object(policy, 'read_assets', return_value=assets), patch.object(policy, 'verify_assets', side_effect=ValueError('bad signature')):
            with self.assertRaises(ValueError):
                self.read()


if __name__ == '__main__':
    unittest.main()
