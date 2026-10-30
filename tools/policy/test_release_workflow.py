"""Release workflow policy contracts run in the existing hash-pinned policy venv."""
from pathlib import Path
import unittest

from repository_policy import load_yaml, workflow_policy

ROOT = Path(__file__).resolve().parents[2]


class ReleaseWorkflowTests(unittest.TestCase):
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
        for task in ('assembleDebug', 'assembleRelease', 'compileDebugUnitTestKotlin', 'compileDebugAndroidTestKotlin'):
            self.assertIn(':app:' + task, compile_step['run'])
        self.assertIn('if (( build_status[0] != 0 )); then exit "${build_status[0]}"; fi', compile_step['run'])
        self.assertIn('(( build_status[1] == 0 ))', compile_step['run'])
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

    def test_policy_consumers_use_existing_full_hash_pinned_environment(self):
        text = (ROOT / '.github/workflows/repository-policy.yml').read_text()
        config = load_yaml(text)
        steps = config['jobs']['policy']['steps']
        prepare = next(i for i, step in enumerate(steps) if 'Install hash-pinned policy' in step['name'])
        check = next(i for i, step in enumerate(steps) if step.get('run') == 'bash tools/check.sh')
        self.assertLess(prepare, check)
        for requirement in ('.github/requirements-policy.txt', 'tools/research/requirements.txt',
                            '.github/requirements-metrics.txt'):
            self.assertIn('-r ' + requirement, steps[prepare]['run'])
        self.assertIn('--require-hashes --no-deps', steps[prepare]['run'])
        self.assertIn('"$RUNNER_TEMP/policy-venv/bin/python" -m pip', steps[prepare]['run'])
        self.assertEqual(steps[check]['env']['CHECK_PYTHON'], '${{ runner.temp }}/policy-venv/bin/python')


if __name__ == '__main__':
    unittest.main()
