"""Pure controller artifact provenance and release-only Gradle receipt controls."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_controller as controller
from release_runtime import fingerprint
from release_signing import zip_bytes
from verify_gradle_execution import REQUIRED, validate


class ArtifactTests(unittest.TestCase):
    def test_artifact_id_digest_origin_attempt_expiry_and_archive_bytes_are_all_required(self):
        archive = zip_bytes({'candidate.apk': b'SYNTHETIC no signature', 'legal/source.kt': b'synthetic source'})
        digest = fingerprint(archive)['sha256']
        from test_release_policy import PolicyAPI, wrapper
        model = PolicyAPI()
        model.archive = archive
        record = {'id': 1, 'name': 'signed-10-2', 'expired': False, 'digest': 'sha256:' + digest,
                  'workflow_run': {'id': 10, 'head_sha': 'c' * 40}}
        model.overrides['/actions/artifacts/1'] = record
        api = wrapper(model)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = controller.download_artifact(api, 1, digest, 10, 2, 'signed-10-2', 'c' * 40, root / 'download')
            self.assertEqual(set(files), {'candidate.apk', 'legal/source.kt'})
            self.assertEqual((root / 'download/candidate.apk').read_bytes(), b'SYNTHETIC no signature')
            original = copy.deepcopy(record)
            for key, value in [('id', 2), ('name', 'signed-10-1'), ('expired', True), ('digest', 'sha256:' + 'f' * 64),
                               ('workflow_run', {'id': 11, 'head_sha': 'c' * 40}),
                               ('workflow_run', {'id': 10, 'head_sha': 'd' * 40})]:
                model.overrides['/actions/artifacts/1'] = {**original, key: value}
                with self.subTest(key=key), self.assertRaises(ValueError):
                    controller.download_artifact(api, 1, digest, 10, 2, 'signed-10-2', 'c' * 40, root / 'rejected')
            model.overrides['/actions/artifacts/1'] = original
            with self.assertRaises(ValueError):
                controller.download_artifact(api, 1, 'e' * 64, 10, 2, 'signed-10-2', 'c' * 40, root / 'corrupt')

    def test_release_graph_requires_fresh_toolchain_full_lint_and_runtime_observer(self):
        rows = [f'GATE_TASK_OUTCOME :app:{name} SUCCESS' for name in REQUIRED['release']]
        validate('\n'.join(rows), 'release')
        for index in range(len(rows)):
            with self.subTest(index=index), self.assertRaises(ValueError):
                validate('\n'.join(rows[:index] + rows[index + 1:]), 'release')
            with self.assertRaises(ValueError):
                validate('\n'.join(rows).replace(rows[index], rows[index].replace('SUCCESS', 'FROM_CACHE')), 'release')


class ControllerTests(unittest.TestCase):
    def setUp(self):
        import test_release_policy as fixtures
        self.fixtures = fixtures
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {'GITHUB_REPOSITORY': 'leugenea/codexbar-mobile', 'GITHUB_REPOSITORY_ID': '7',
                    'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
                    'GITHUB_SHA': 'c' * 40, 'GITHUB_WORKFLOW_SHA': 'c' * 40,
                    'GITHUB_WORKFLOW_REF': 'leugenea/codexbar-mobile/' + controller.policy.WORKFLOW + '@refs/heads/main',
                    'GITHUB_RUN_ID': '100', 'GITHUB_RUN_NUMBER': '10', 'GITHUB_RUN_ATTEMPT': '2',
                    'REQUEST_OPERATION': 'prepare', 'REQUEST_CHANNEL': 'rc', 'REQUEST_TAG': 'v0.1.0-rc.1',
                    'GH_TOKEN': 'synthetic-token', 'RELEASE_CONFIG': json.dumps(fixtures.configuration()),
                    'GITHUB_OUTPUT': str(self.root / 'output'), 'GITHUB_STEP_SUMMARY': str(self.root / 'summary')}
        self.api = fixtures.wrapper(fixtures.PolicyAPI())

    def run_main(self, operation):
        with patch.dict(os.environ, self.env, clear=True), patch('sys.argv',
                ['release_controller.py', operation, '--work', str(self.root / 'work')]), \
                patch.object(controller, 'GitHub', return_value=self.api):
            controller.main()

    def test_pre_environment_admission_uses_wrapper_and_prints_derived_owner_confirmation(self):
        self.run_main('admit')
        self.assertIn('allow=true', (self.root / 'output').read_text())
        summary = (self.root / 'summary').read_text()
        for value in ('rc', 'v0.1.0-rc.1', 'a' * 40, '"versionCode": 10'):
            self.assertIn(value, summary)

    def test_bad_or_disabled_config_denies_without_constructing_remote_client(self):
        from jsonschema import ValidationError
        for text in ('', '{}', json.dumps({**self.fixtures.configuration(), 'rcEnabled': False})):
            with patch.object(controller, 'GitHub') as github, self.assertRaises((ValueError, ValidationError)):
                controller.admitted({**self.env, 'RELEASE_CONFIG': text})
            github.assert_not_called()

    def test_candidate_verification_must_pass_before_environment_job_can_be_scheduled(self):
        self.env.update(REQUEST_OPERATION='publish', REQUEST_PREPARE_RUN_ID='90')
        self.api = self.fixtures.wrapper(self.fixtures.PolicyAPI(self.fixtures.context(operation='publish')))
        with patch.object(controller.policy, 'history', return_value=(0, False)), \
                patch.object(controller.policy, 'candidate_assets', side_effect=ValueError('Prepare source differs')) as candidate, \
                self.assertRaises(ValueError):
            self.run_main('remote-state')
        candidate.assert_called_once()
        self.assertEqual((self.root / 'output').read_text(), 'allow=false\n')

    def test_accepted_publish_is_readonly_and_does_not_require_retained_actions_artifact(self):
        self.env.update(REQUEST_OPERATION='publish', REQUEST_PREPARE_RUN_ID='90', ACCEPTED_PREVIOUS_CODE='0')
        model = self.fixtures.PolicyAPI(self.fixtures.context(operation='publish'))
        model.records = [{'id': 5, 'tag_name': 'v0.1.0-rc.1', 'draft': False}]
        self.api = self.fixtures.wrapper(model)
        with patch.object(controller.policy, 'history', return_value=(0, True)), \
                patch.object(controller.policy, 'candidate_assets') as candidate, patch.object(controller, 'publish') as publish:
            self.run_main('remote-state')
            self.run_main('publish')
        candidate.assert_not_called()
        publish.assert_not_called()


if __name__ == '__main__':
    unittest.main()
