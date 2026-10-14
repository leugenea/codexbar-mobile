"""Source/YAML and synthetic compiler evidence contracts; no real tool execution."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'policy'))
from repository_policy import load_yaml, workflow_policy
from verify_gradle_execution import COMPILERS, validate, validate_compilation, REQUIRED

ROOT = Path(__file__).resolve().parents[2]


class ReleasePipelineTests(unittest.TestCase):
    def test_checkout_history_and_unprivileged_required_android_gates(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        workflow_policy(text, 'android.yml')
        config = load_yaml(text)
        for job in ('build', 'instrumented'):
            self.assertEqual(config['jobs'][job]['env']['RELEASE_CHANNEL'], 'dev')
            checkout = config['jobs'][job]['steps'][0]
            self.assertEqual(checkout['with'], {'persist-credentials': False, 'fetch-depth': 0})
        build = config['jobs']['build']['steps']
        graph = next(step['run'] for step in build if step['name'] == 'Build, lint and unit tests with strict verification')
        for task in ('lintRelease', 'assembleRelease', 'testDebugUnitTest', 'assembleDebugAndroidTest'):
            self.assertIn(':app:' + task, graph)
        inspection = next(step for step in build if 'reverify unsigned' in step['name'])
        self.assertEqual(inspection['env']['EXPECTED_SOURCE_SHA'], '${{ github.sha }}')
        self.assertIn('for operation in create verify', inspection['run'])
        self.assertIn('hosted_release_fixtures.py', text)
        result = config['jobs']['result']
        self.assertEqual(result['name'], 'Android CI result')
        self.assertEqual(result['needs'], ['build', 'instrumented'])

    def test_codeql_languages_events_permissions_setup_and_manual_build(self):
        text = (ROOT / '.github/workflows/codeql.yml').read_text()
        workflow_policy(text, 'codeql.yml')
        config = load_yaml(text)
        self.assertEqual(set(config['on']), {'pull_request', 'push', 'workflow_dispatch', 'schedule'})
        self.assertEqual(config['on']['push']['branches'], ['main'])
        job = config['jobs']['analyze']
        self.assertEqual(job['permissions'], {'contents': 'read', 'security-events': 'write'})
        modes = {item['language']: item['build-mode'] for item in job['strategy']['matrix']['include']}
        self.assertEqual(modes, {'actions': 'none', 'python': 'none', 'java-kotlin': 'manual'})
        steps = job['steps']
        self.assertEqual(steps[0]['with'], {'persist-credentials': False, 'fetch-depth': 0})
        init = next(i for i, step in enumerate(steps) if step.get('uses', '').startswith('github/codeql-action/init@'))
        compile_step = steps[init + 1]
        self.assertEqual(compile_step['if'], "matrix.language == 'java-kotlin'")
        for flag in ('--dependency-verification strict', '--no-build-cache', '--no-configuration-cache', 'clean'):
            self.assertIn(flag, compile_step['run'])
        for compiler in COMPILERS:
            # Assemble supplies the application compilers, explicit tasks supply both test sets.
            task = compiler if 'Test' in compiler else compiler.replace('compile', 'assemble').removesuffix('Kotlin')
            self.assertIn(':app:' + task, compile_step['run'])
        self.assertIn('verify_gradle_execution.py codeql', compile_step['run'])
        self.assertTrue(steps[-1]['uses'].startswith('github/codeql-action/analyze@'))
        for forbidden in ('autobuild', 'pull_request_target', 'secrets.', 'continue-on-error', 'paths:'):
            self.assertNotIn(forbidden, text)

    def test_codeql_write_permission_is_narrow_and_no_extra_writes_are_admitted(self):
        text = (ROOT / '.github/workflows/codeql.yml').read_text()
        for old, new in (('security-events: write', 'security-events: read'),
                         ('security-events: write', 'security-events: write\n      contents: write'),
                         ('persist-credentials: false', 'persist-credentials: true')):
            with self.subTest(mutation=new), self.assertRaises(ValueError):
                workflow_policy(text.replace(old, new), 'codeql.yml')
        with self.assertRaises(ValueError):
            workflow_policy(text, 'other.yml')

    def test_compiler_skip_cache_missing_failed_or_duplicate_cannot_pass(self):
        valid = '\n'.join('> Task :app:' + name for name in COMPILERS)
        validate_compilation(valid)
        for name in COMPILERS:
            for status in ('FROM-CACHE', 'UP-TO-DATE', 'SKIPPED', 'NO-SOURCE', 'FAILED'):
                with self.subTest(task=name, status=status), self.assertRaises(ValueError):
                    validate_compilation(valid.replace(name, name + ' ' + status))
            with self.assertRaises(ValueError):
                validate_compilation(valid.replace('> Task :app:' + name, ''))
            with self.assertRaises(ValueError):
                validate_compilation(valid + '\n> Task :app:' + name)

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
