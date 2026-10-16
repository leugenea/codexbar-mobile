"""Source/YAML and synthetic compiler evidence contracts; no real tool execution."""
from pathlib import Path
import subprocess
import sys
import unittest

from verify_gradle_execution import validate, REQUIRED

ROOT = Path(__file__).resolve().parents[2]


class ReleasePipelineTests(unittest.TestCase):
    def test_new_android_consumers_import_without_policy_site_packages(self):
        # The Android metrics-only venv must not depend on the broader policy venv.
        consumers = ('test_release_pipeline', 'test_release_artifact', 'test_version_identity',
                     'release_artifact', 'version_identity', 'hosted_release_fixtures', 'verify_gradle_execution')
        script = 'import importlib, sys; sys.path.insert(0, sys.argv[1]); ' \
                 '[importlib.import_module(name) for name in sys.argv[2:]]'
        result = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', script,
                                 str(ROOT / 'tools/build'), *consumers],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_release_lint_preserves_only_agp_redundant_vital_suppression(self):
        log = '\n'.join('GATE_TASK_OUTCOME :app:' + name + ' SUCCESS' for name in REQUIRED['build'])
        for name in ('lintVitalRelease', 'lintVitalReportRelease'):
            valid = log + f'\nGATE_TASK_OUTCOME :app:{name} SKIPPED\n> Task :app:{name} SKIPPED'
            validate(valid, 'build')
            partner = name.replace('Vital', '')
            with self.assertRaises(ValueError):
                validate(valid.replace(partner + ' SUCCESS', partner + ' SKIPPED'), 'build')
            with self.assertRaises(ValueError):
                validate(valid.replace(f'{name} SKIPPED', f'{name} FROM_CACHE'), 'build')

    def test_gradle_package_and_single_version_resolver_contract(self):
        app = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("namespace 'io.github.leugenea.codexbarmobile'", app)
        self.assertIn("applicationId 'io.github.leugenea.codexbarmobile'", app)
        self.assertIn("applicationIdSuffix '.dev'", app)
        self.assertIn('versionCode buildIdentity.versionCode', app)
        self.assertIn('versionName buildIdentity.versionName', app)
        self.assertEqual(app.count('version_identity.py'), 1)
        self.assertIn('android:name="io.github.leugenea.codexbarmobile.MainActivity"',
                      (ROOT / 'app/src/main/AndroidManifest.xml').read_text())
        fixtures = (ROOT / 'tools/build/hosted_release_fixtures.py').read_text()
        self.assertIn('Hosted runner only', fixtures)
        self.assertIn("dir=os.environ['RUNNER_TEMP']", fixtures)
        self.assertNotIn('push', fixtures)
        self.assertNotIn("'fetch'", (ROOT / 'tools/build/version_identity.py').read_text().split('def git', 1)[1])


if __name__ == '__main__':
    unittest.main()
