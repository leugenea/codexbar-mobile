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

    def test_selected_prepare_still_runs_full_legal_renders_and_rejects_drift(self):
        import release_artifact as artifact
        import release_bundle as bundle
        import release_signing as signing
        from test_release_signing import AssetTests
        fixture = AssetTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.expected = fixture.expected
        self.record['certificateSha256'] = fixture.inspection['signerCertificateSha256']
        self.assets = signing.package_assets(fixture.signed, fixture.final, self.manifest['authority'])
        self.model.archive = zip_bytes(self.assets)
        self.artifact['digest'] = 'sha256:' + fingerprint(self.model.archive)['sha256']
        self.model.overrides['/actions/runs/90/attempts/2'] = {**self.origin, 'run_number': self.expected['versionCode']}
        with patch.object(policy, 'ROOT', fixture.root), \
                patch.object(artifact, 'inspect_apk', return_value=fixture.inspection), \
                patch.object(bundle, 'observe_classes', return_value=sorted(fixture.classes)), \
                patch.object(bundle, 'render', wraps=bundle.render) as render:
            self.assertEqual(self.read(), self.assets)
            self.assertEqual(render.call_count, 2)
            (fixture.root / 'LICENSE').write_text('SYNTHETIC changed current notice\n')
            with self.assertRaisesRegex(ValueError, 'accepted unsigned capsule binding'):
                self.read()
            self.assertEqual(render.call_count, 3)

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
        self.model = PolicyAPI()
        self.api = wrapper(self.model)
        self.record, self.expected, _ = policy.admit(self.api, configuration(), context())

    def read(self):
        return policy.history(self.api, self.record, self.expected)

    def published(self, code=9, tag='v0.1.0-rc.2', run_id=90, notes=''):
        from release_mock import ReleaseAPI
        from release_publication import publish
        identity = {**self.expected, 'versionCode': code, 'tag': tag, 'versionName': tag[1:],
                    'channel': 'rc' if '-rc.' in tag else 'stable'}
        self.manifest = {'schema': 1, 'kind': 'guarded-android-release', 'buildInputs': identity,
                         'authority': {'repository': self.api.repository, 'workflowId': 8,
                                       'controllerSha': 'c' * 40, 'runId': run_id, 'runAttempt': 2}}
        assets = {'release-manifest.json': canonical(self.manifest),
                  'unrelated.apk': b'NOT an APK; history must not download it',
                  'old-bundle.zip': b'NOT a ZIP; history must not inspect it',
                  'SHA256SUMS': b'Not audited by allocation history'}
        remote = ReleaseAPI()
        publish(GitHub(self.model.repository, 'synthetic-token', remote.transport), identity, assets, lambda: None, notes=notes)
        policy_transport = wrapper(self.model).transport
        def transport(method, url, headers, data):
            if '/releases' in urlsplit(url).path:
                return remote.transport(method, url, headers, data)
            return policy_transport(method, url, headers, data)
        self.api = GitHub(self.model.repository, 'synthetic-token', transport, read_only=True)
        self.model.overrides[f'/actions/runs/{run_id}/attempts/2'] = {
            **self.model.get('/actions/runs/' + str(run_id)), 'run_number': code,
            'display_title': f'prepare {identity["channel"]} {tag}'}
        remote.calls.clear()
        return remote

    def test_complete_empty_inventory_but_unknown_or_partial_reads_fail_closed(self):
        self.assertEqual(self.read(), (0, False))
        with patch.object(self.api, 'releases', side_effect=OSError('offline')), self.assertRaises(OSError):
            self.read()
        remote = self.published()
        row = next(row for row, data in remote.files.values() if row['name'] == 'release-manifest.json')
        for rows in ([], [row, dict(row, id=999)], [dict(row, size=65537)]):
            with patch.object(self.api, 'assets', return_value=rows), self.assertRaises(ValueError):
                self.read()
        with patch.object(self.api, 'assets', side_effect=OSError('partial inventory')), self.assertRaises(OSError):
            self.read()

    def test_real_wrapper_history_reads_only_manifest_and_derives_floor(self):
        import release_bundle as bundle
        import release_artifact as artifact
        for tag, notes in [('v0.1.0-rc.2', ''), ('v0.1.0', '## Changes')]:
            remote = self.published(tag=tag, notes=notes)
            with patch.object(policy, 'verify_assets', side_effect=AssertionError('No candidate inspection')) as verify, \
                    patch.object(bundle, 'render', side_effect=AssertionError('No historical render')) as render, \
                    patch.object(artifact, 'run_tool', side_effect=AssertionError('No SDK')) as sdk:
                self.assertEqual(self.read(), (9, False))
            verify.assert_not_called()
            render.assert_not_called()
            sdk.assert_not_called()
            manifest_id = next(row['id'] for row, data in remote.files.values() if row['name'] == 'release-manifest.json')
            downloads = [path for method, path, data in remote.calls if '/releases/assets/' in path]
            self.assertEqual(downloads, [self.api.prefix + '/releases/assets/' + str(manifest_id)])
            self.expected['versionCode'] = 9
            with self.assertRaisesRegex(ValueError, 'non-increasing'):
                self.read()
            self.expected['versionCode'] = 100  # Gaps are allowed.
            self.assertEqual(self.read(), (9, False))

    def test_history_rejects_allocation_identity_source_producer_and_marker_drift(self):
        for section, key, value in [('buildInputs', 'source', {'sha': 'a' * 40, 'tree': 'e' * 40}),
                                    ('buildInputs', 'tag', 'v0.1.0-rc.9'), ('buildInputs', 'versionCode', True),
                                    ('authority', 'workflowId', 99), ('authority', 'runAttempt', 1)]:
            self.published()
            self.manifest[section][key] = value
            with patch.object(self.api, 'download', return_value=canonical(self.manifest)), \
                    self.subTest(key=key), self.assertRaises(ValueError):
                self.read()
        remote = self.published()
        path = '/actions/runs/90/attempts/2'
        self.model.overrides[path]['conclusion'] = 'failure'
        with self.assertRaisesRegex(ValueError, 'origin attempt'):
            self.read()
        self.model.overrides[path]['conclusion'] = 'success'
        remote.records[0]['body'] += '\n'
        with self.assertRaisesRegex(ValueError, 'marker'):
            self.read()
        remote = self.published()
        remote.records.append({**remote.records[0], 'id': 99})
        with self.assertRaisesRegex(ValueError, 'Duplicate release tags'):
            self.read()

    def test_published_noop_keeps_original_attempt_and_notes_but_never_reallocates_tag(self):
        self.record, self.expected, _ = policy.admit(self.api, configuration(), context(operation='publish'))
        remote = self.published(tag=self.expected['tag'], notes='Changes')
        self.record['notes'] = 'Changes'
        self.model.overrides['/actions/runs/90'] = {**self.model.get('/actions/runs/90'), 'run_attempt': 3}
        self.assertEqual(self.read(), (0, True))
        self.assertFalse(any('/artifacts' in path for path in self.model.calls))
        self.record['notes'] = 'Different notes'
        with self.assertRaisesRegex(ValueError, 'Release binding'):
            self.read()
        self.record['notes'] = 'Changes'
        self.record['prepareRunId'] = 91
        with self.assertRaisesRegex(ValueError, 'different allocation'):
            self.read()
        remote.records[0]['draft'] = True
        self.assertEqual(self.read(), (0, False))  # Exact draft resume belongs to the publisher.
        self.record['operation'] = 'prepare'
        with self.assertRaisesRegex(ValueError, 'Unresolved draft'):
            self.read()

    def test_duplicate_published_counter_from_another_origin_fails_closed(self):
        from release_publication import publish
        remote = self.published()
        second = copy.deepcopy(self.manifest)
        second['buildInputs'].update(tag='v0.1.0-rc.3', versionName='0.1.0-rc.3')
        second['authority']['runId'] = 91
        publish(GitHub(self.model.repository, 'synthetic-token', remote.transport), second['buildInputs'],
                {'release-manifest.json': canonical(second)}, lambda: None)
        self.model.overrides['/actions/runs/91/attempts/2'] = {
            **self.model.get('/actions/runs/91'), 'display_title': 'prepare rc v0.1.0-rc.3'}
        with self.assertRaisesRegex(ValueError, 'Reused official versionCode'):
            self.read()

    def test_missing_signed_inventory_rejects_unknown_conclusion_or_invalid_generated_title(self):
        run = {**self.model.get('/actions/runs/90'), 'repository': {'id': 7},
               'display_title': 'prepare rc v0.1.0-rc.2'}
        self.model.overrides['/actions/runs/90/artifacts'] = []
        for change in ({'display_title': 'Unknown title'}, {'conclusion': 'unknown'},
                       {'display_title': 'publish stable v0.1.0-rc.2'},
                       {'display_title': 'publish rc v0.1.0-rc.02'}):
            self.model.overrides['/actions/workflows/8/runs?event=workflow_dispatch'] = [{**run, **change}]
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.read()
        self.assertNotIn('/actions/runs/90/artifacts', self.model.calls)

    def test_missing_signed_inventory_accepts_valid_publish_and_known_unsuccessful_prepares(self):
        run = {**self.model.get('/actions/runs/90'), 'repository': {'id': 7}}
        self.model.overrides['/actions/runs/90/artifacts'] = []
        for conclusion in ('success', 'failure', 'cancelled', 'timed_out', 'neutral', 'skipped', 'action_required', 'stale'):
            title = 'publish rc v0.1.0-rc.2' if conclusion == 'success' else 'prepare rc v0.1.0-rc.2'
            self.model.overrides['/actions/workflows/8/runs?event=workflow_dispatch'] = [
                {**run, 'conclusion': conclusion, 'display_title': title}]
            with self.subTest(conclusion=conclusion):
                self.assertEqual(self.read(), (0, False))

    def test_retained_reservations_use_metadata_and_reject_unknown_expired_or_conflicting_state(self):
        run = {**self.model.get('/actions/runs/90'), 'repository': {'id': 7},
               'display_title': 'prepare rc v0.1.0-rc.2'}
        artifact = {'id': 80, 'name': 'signed-90-2', 'expired': False,
                    'workflow_run': {'id': 90, 'head_sha': 'c' * 40}}
        self.model.overrides['/actions/workflows/8/runs?event=workflow_dispatch'] = [run]
        self.model.overrides['/actions/runs/90/artifacts'] = [artifact]
        path = '/actions/runs/90/attempts/2'
        self.model.overrides[path] = {**run, 'repository': {'full_name': self.api.repository}}
        self.assertEqual(self.read(), (0, False))  # Any archive request would fail in the wrapper.
        original = copy.deepcopy(self.model.overrides[path])
        for change in ({'display_title': 'prepare rc ' + self.expected['tag']},
                       {'display_title': 'Unknown title'}, {'display_title': 'prepare stable v0.1.0-rc.2'},
                       {'conclusion': 'failure'}, {'run_number': 8}, {'head_sha': 'e' * 40}):
            self.model.overrides[path] = {**original, **change}
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.read()
        self.model.overrides[path] = original
        for change in ({'expired': True}, {'name': 'signed-90-malformed'},
                       {'workflow_run': {'id': 91, 'head_sha': 'c' * 40}}):
            self.model.overrides['/actions/runs/90/artifacts'] = [{**artifact, **change}]
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.read()
        self.model.overrides['/actions/runs/90/artifacts'] = []
        with self.assertRaisesRegex(ValueError, 'Missing retained'):
            self.read()

if __name__ == '__main__':
    unittest.main()
