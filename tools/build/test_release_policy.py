"""Pure exact-run/activation/history controls; all remote and SDK observations are synthetic."""
import base64
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_policy as policy
from release_runtime import canonical


def authorization(channel='rc', operation='prepare'):
    tag = 'v0.1.0' + ('-rc.1' if channel == 'rc' else '')
    record = {'schema': 1, 'repository': 'leugenea/codexbar-mobile', 'repositoryId': 7, 'workflowId': 8,
              'workflowPath': policy.WORKFLOW, 'controllerSha': 'c' * 40, 'controllerRef': 'refs/heads/main',
              'operation': operation, 'runId': 100, 'runNumber': 10, 'channel': channel, 'tagRef': 'refs/tags/' + tag,
              'sourceSha': 'a' * 40, 'sourceTree': 'b' * 40, 'certificateSha256': 'd' * 64,
              'trustMode': 'hosted-environment', 'signingEnvironment': 'release-sign',
              'publicationEnvironment': 'release-publish', 'previous': {'emptyHistory': True}}
    if operation == 'publish':
        record['candidate'] = {'runId': 90, 'runAttempt': 2, 'artifactId': 80,
                               'artifactSha256': 'e' * 64, 'bundleManifestSha256': 'f' * 64}
    return record


def context(record):
    return {'repository': record['repository'], 'repositoryId': 7, 'event': 'workflow_dispatch',
            'ref': 'refs/heads/main', 'sha': record['controllerSha'], 'workflowSha': record['controllerSha'],
            'workflowRef': record['repository'] + '/' + policy.WORKFLOW + '@refs/heads/main',
            'runId': 100, 'runNumber': 10, 'operation': record['operation'], 'channel': record['channel'],
            'sourceSha': record['sourceSha'], 'tag': record['tagRef'][10:]}


class PolicyAPI:
    repository = 'leugenea/codexbar-mobile'

    def __init__(self, record):
        self.record = record
        self.overrides = {}
        self.records = []
        self.calls = []

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
                    'run_number': 10 if run_id == 100 else 9, 'run_attempt': 2, 'status': 'completed', 'conclusion': 'success'}
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
                {'type': 'required_reviewers', 'prevent_self_review': True, 'reviewers': [{'type': 'User'}]}],
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


class ActivationTests(unittest.TestCase):
    def test_both_channels_require_their_own_exact_record(self):
        for channel in ('rc', 'stable'):
            record = authorization(channel)
            self.assertEqual(policy.activation(json.dumps(record), channel), record)
            self.assertEqual(policy.admit(PolicyAPI(record), record, context(record))[0]['versionCode'], 10)
        with self.assertRaises(ValueError):
            policy.activation(json.dumps(authorization('rc')), 'stable')

    def test_missing_unknown_duplicate_types_noncanonical_and_publish_pins_deny(self):
        from jsonschema import ValidationError
        for text in ('', ' ', '{}', 'null', '{"schema":1,"schema":1}'):
            with self.subTest(text=text), self.assertRaises((ValueError, ValidationError)):
                policy.activation(text, 'rc')
        for key, value in [('schema', True), ('schema', 1.0), ('runId', 0), ('runNumber', '10'),
                           ('runId', 100.0), ('repositoryId', True), ('sourceSha', 'A' * 40),
                           ('certificateSha256', 'D' * 64), ('certificateSha256', 'd' * 64 + '\n'),
                           ('sourceSha', 'a' * 40 + '\n'), ('trustMode', 'auto'), ('extra', 'x'),
                           ('signingEnvironment', ''), ('controllerRef', 'refs/tags/v0.1.0')]:
            bad = {**authorization(), key: value}
            with self.subTest(key=key, value=value), self.assertRaises((ValueError, ValidationError)):
                policy.activation(json.dumps(bad), 'rc')
        bad = authorization(operation='publish')
        del bad['candidate']
        with self.assertRaises(ValidationError):
            policy.activation(json.dumps(bad), 'rc')

    def test_event_controller_run_tag_source_gate_and_environment_drift_deny(self):
        record = authorization()
        for key, value in [('event', 'pull_request'), ('ref', 'refs/heads/feature'), ('repository', 'fork/repo'),
                           ('runId', 101), ('runNumber', 11), ('sha', 'e' * 40), ('workflowSha', 'f' * 40),
                           ('workflowRef', 'wrong'), ('sourceSha', 'f' * 40), ('tag', 'v0.1.0-rc.2')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                policy.admit(PolicyAPI(record), record, {**context(record), key: value})
        for path, response in [('/git/ref/heads/main', {'object': {'sha': 'e' * 40}}),
                               ('/git/ref/tags/v0.1.0-rc.1', {'object': {'type': 'commit', 'sha': 'f' * 40}}),
                               ('/git/commits/' + 'a' * 40, {'tree': {'sha': 'f' * 40}}),
                               ('/git/trees/' + 'b' * 40, {'truncated': True, 'tree': []}),
                               ('/compare/' + 'a' * 40 + '...' + 'c' * 40, {'status': 'diverged'}),
                               ('/actions/workflows/8', {'id': 8, 'path': 'other.yml', 'state': 'active'}),
                               ('/environments/release-sign', OSError('unknown remote state'))]:
            api = PolicyAPI(record)
            api.overrides[path] = response
            with self.subTest(path=path), self.assertRaises((ValueError, OSError)):
                policy.admit(api, record, context(record))
        for filename in policy.SOURCE_GATES:
            api = PolicyAPI(record)
            path = '/actions/workflows/' + filename + '/runs?head_sha=' + 'a' * 40 + '&event=push'
            for rows in ([], [{**api.pages(path)[0], 'conclusion': 'failure'}],
                         [{**api.pages(path)[0], 'event': 'pull_request'}]):
                api.overrides[path] = rows
                with self.subTest(filename=filename), self.assertRaises(ValueError):
                    policy.admit(api, record, context(record))
        api = PolicyAPI(record)
        api.overrides['/environments/release-sign/deployment-branch-policies'] = [{'id': 1, 'name': '*', 'type': 'branch'}]
        with self.assertRaises(ValueError):
            policy.admit(api, record, context(record))

    def test_publish_uses_origin_run_number_not_new_dispatch_and_rerun_is_stable(self):
        record = authorization(operation='publish')
        expected, _ = policy.admit(PolicyAPI(record), record, context(record))
        self.assertEqual(expected['versionCode'], 9)
        record = authorization()
        api = PolicyAPI(record)
        first = policy.admit(api, record, context(record))[0]
        api.overrides['/actions/runs/100'] = {**api.get('/actions/runs/100'), 'run_attempt': 3}
        self.assertEqual(first, policy.admit(api, record, context(record))[0])

    def test_missing_or_unprotected_environment_never_defaults_to_approval(self):
        record = authorization()
        for change in ({'protection_rules': []}, {'protection_rules': [{'type': 'required_reviewers', 'reviewers': []}]},
                       {'deployment_branch_policy': None}):
            api = PolicyAPI(record)
            api.overrides['/environments/release-sign'] = {**api.get('/environments/release-sign'), **change}
            with self.assertRaises(ValueError):
                policy.admit(api, record, context(record))


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.record = authorization()
        self.api = PolicyAPI(self.record)
        self.expected = policy.admit(self.api, self.record, context(self.record))[0]
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.reports = Path(self.temp.name)

    def read(self):
        return policy.history(self.api, self.record, self.expected, self.reports, self.reports)

    def test_empty_inventory_requires_explicit_bootstrap_and_unknown_read_is_not_zero(self):
        self.assertEqual(self.read(), (0, False))
        self.record['previous'] = {'releaseId': 1, 'versionCode': 9, 'bundleManifestSha256': 'f' * 64}
        with self.assertRaises(ValueError):
            self.read()
        with patch.object(self.api, 'releases', side_effect=OSError('offline')), self.assertRaises(OSError):
            self.read()

    def published(self, code=9, tag='v0.1.0-rc.2', run_id=90):
        expected = {**self.expected, 'versionCode': code, 'tag': tag, 'versionName': tag[1:]}
        assets = {'release-manifest.json': b'synthetic manifest, not a real signature'}
        from release_publication import marker
        release = {'id': 5, 'tag_name': tag, 'target_commitish': 'a' * 40, 'name': tag[1:],
                   'body': marker(assets), 'draft': False, 'prerelease': True}
        manifest = {'buildInputs': expected, 'bundleManifestSha256': 'f' * 64,
                    'authority': {'repository': self.api.repository, 'workflowId': 8, 'controllerSha': 'c' * 40,
                                  'runId': run_id, 'runAttempt': 2}}
        self.api.records = [release]
        return assets, manifest

    def test_history_anchor_floor_gaps_reset_same_run_noop_and_new_run_same_tag_conflict(self):
        assets, manifest = self.published()
        self.record['previous'] = {'releaseId': 5, 'versionCode': 9, 'bundleManifestSha256': 'f' * 64}
        with patch.object(policy, 'read_assets', return_value=assets), patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            self.assertEqual(self.read(), (9, False))
            self.expected['versionCode'] = 9
            with self.assertRaises(ValueError):
                self.read()
            self.expected['versionCode'] = 100  # gaps are legal; never infer a contiguous counter.
            self.assertEqual(self.read(), (9, False))
            self.record['previous']['bundleManifestSha256'] = 'e' * 64
            with self.assertRaises(ValueError):
                self.read()
        self.expected['versionCode'] = 10
        assets, manifest = self.published(10, self.expected['tag'], 100)
        self.record['previous'] = {'emptyHistory': True}
        with patch.object(policy, 'read_assets', return_value=assets), patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            self.assertEqual(self.read(), (0, True))
            manifest['authority']['runId'] = 90
            with self.assertRaises(ValueError):
                self.read()

    def test_retained_official_candidate_reserves_tag_and_expired_unknown_blocks(self):
        from release_signing import zip_bytes
        from release_runtime import fingerprint
        raw = zip_bytes({'release-manifest.json': b'synthetic'})
        path = '/actions/workflows/8/runs?event=workflow_dispatch'
        run = {'id': 90, 'status': 'completed', 'conclusion': 'success', 'path': policy.WORKFLOW,
               'event': 'workflow_dispatch', 'head_branch': 'main', 'repository': {'id': 7},
               'head_sha': 'c' * 40, 'run_number': 9}
        self.api.overrides[path] = [run]
        artifact = {'id': 80, 'name': 'signed-90-2', 'expired': False,
                    'digest': 'sha256:' + fingerprint(raw)['sha256']}
        self.api.overrides['/actions/runs/90/artifacts'] = [artifact]
        manifest = {'buildInputs': {**self.expected, 'versionCode': 9},
                    'authority': {'runId': 90, 'workflowId': 8, 'repository': self.api.repository,
                                  'controllerSha': 'c' * 40, 'runAttempt': 2}}
        self.api.prefix = '/repos/' + self.api.repository
        with patch.object(self.api, 'request', return_value=raw, create=True), \
                patch.object(policy, 'verify_assets', return_value=(manifest, None)):
            with self.assertRaisesRegex(ValueError, 'Tag already admitted'):
                self.read()
            manifest['buildInputs']['tag'] = 'v0.1.0-rc.2'
            self.assertEqual(self.read(), (0, False))
            artifact['expired'] = True
            with self.assertRaisesRegex(ValueError, 'Expired unresolved'):
                self.read()
        self.api.overrides[path] = [dict(run, status='queued')]
        self.assertEqual(self.read(), (0, False))  # A queued dispatch is not a reservation/ordering promise.

    def test_partial_unknown_drafts_duplicates_and_sdk_history_failures_deny(self):
        self.api.records = [{'id': 1, 'tag_name': self.expected['tag'], 'draft': True}]
        with self.assertRaises(ValueError):
            self.read()
        self.api.records *= 2
        with self.assertRaises(ValueError):
            self.read()
        assets, manifest = self.published()
        with patch.object(policy, 'read_assets', return_value=assets), patch.object(policy, 'verify_assets', side_effect=ValueError('bad signature')):
            with self.assertRaises(ValueError):
                self.read()


if __name__ == '__main__':
    unittest.main()
