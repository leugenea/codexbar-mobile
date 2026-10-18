"""Pure controller artifact provenance and release-only Gradle receipt controls."""
import copy
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
        class API:
            prefix = '/repos/fixture/repository'
            record = {'id': 1, 'name': 'signed-10-2', 'expired': False, 'digest': 'sha256:' + digest,
                      'workflow_run': {'id': 10, 'head_sha': 'c' * 40}}
            def get(self, path):
                self.path = path
                return copy.deepcopy(self.record)
            def request(self, method, path, **kwargs):
                self.download = method, path, kwargs
                return archive
        api = API()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = controller.download_artifact(api, 1, digest, 10, 2, 'signed-10-2', 'c' * 40, root / 'download')
            self.assertEqual(set(files), {'candidate.apk', 'legal/source.kt'})
            self.assertEqual((root / 'download/candidate.apk').read_bytes(), b'SYNTHETIC no signature')
            original = copy.deepcopy(api.record)
            for key, value in [('id', 2), ('name', 'signed-10-1'), ('expired', True), ('digest', 'sha256:' + 'f' * 64),
                               ('workflow_run', {'id': 11, 'head_sha': 'c' * 40}),
                               ('workflow_run', {'id': 10, 'head_sha': 'd' * 40})]:
                api.record = {**original, key: value}
                with self.subTest(key=key), self.assertRaises(ValueError):
                    controller.download_artifact(api, 1, digest, 10, 2, 'signed-10-2', 'c' * 40, root / 'rejected')
            api.record = original
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


if __name__ == '__main__':
    unittest.main()
