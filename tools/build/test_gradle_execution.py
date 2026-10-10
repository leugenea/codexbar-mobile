"""Synthetic evidence only: these tests never invoke Gradle, tests or an emulator."""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

from verify_gradle_execution import REQUIRED, validate

ROOT = Path(__file__).resolve().parents[2]


def successful_log(graph):
    return '\n'.join(f'GATE_TASK_OUTCOME :app:{name} SUCCESS' for name in REQUIRED[graph])


class ExecutionEvidenceTests(unittest.TestCase):
    def test_both_graphs_require_every_gate_but_allow_cached_build_outputs(self):
        for graph in REQUIRED:
            log = successful_log(graph) + '\n> Task :app:compileDebugKotlin FROM-CACHE\n'
            self.assertEqual(len(validate(log, graph)), len(REQUIRED[graph]))

    def test_each_gate_rejects_every_nonexecution_or_failure_outcome(self):
        for graph, names in REQUIRED.items():
            for name in names:
                for state in ('FROM_CACHE', 'UP_TO_DATE', 'SKIPPED', 'NO_SOURCE', 'FAILED'):
                    with self.subTest(graph=graph, name=name, state=state), self.assertRaises(ValueError):
                        validate(successful_log(graph).replace(f'{name} SUCCESS', f'{name} {state}'), graph)

    def test_each_missing_gate_fails_including_missing_all_receipts(self):
        for graph, names in REQUIRED.items():
            for name in names:
                log = '\n'.join(line for line in successful_log(graph).splitlines() if f':app:{name} ' not in line)
                with self.subTest(graph=graph, name=name), self.assertRaisesRegex(ValueError, 'Missing'):
                    validate(log, graph)
            with self.assertRaisesRegex(ValueError, 'Missing'):
                validate('', graph)

    def test_headers_cannot_contradict_receipts_and_future_lint_gates_are_checked(self):
        for name in ('testDebugUnitTest', 'lintAnalyzeNewVariant', 'lintVitalRelease', 'lintVitalReportRelease'):
            for state in ('FROM-CACHE', 'UP-TO-DATE', 'SKIPPED', 'NO-SOURCE'):
                with self.subTest(name=name, state=state), self.assertRaisesRegex(ValueError, 'header'):
                    validate(successful_log('build') + f'\n> Task :app:{name} {state}', 'build')
            for state in ('FROM_CACHE', 'UP_TO_DATE'):
                with self.subTest(name=name, receipt=state), self.assertRaises(ValueError):
                    # Extra lint receipts are never exempt from freshness checks.
                    validate(successful_log('native') + f'\nGATE_TASK_OUTCOME :app:{name} {state}', 'native')

    def test_cli_reads_both_graphs_and_returns_failure_for_reused_gates(self):
        scratch = Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir()))
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as temporary:
            log = Path(temporary) / 'SYNTHETIC-gradle.log'
            for graph in REQUIRED:
                for state in ('SUCCESS', 'UP_TO_DATE'):
                    log.write_text(successful_log(graph).replace('testDebugUnitTest SUCCESS', f'testDebugUnitTest {state}'))
                    result = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/build/verify_gradle_execution.py'),
                                             graph, str(log)], text=True, capture_output=True, timeout=5)
                    self.assertEqual(result.returncode == 0, state == 'SUCCESS', result.stdout + result.stderr)

    def test_duplicate_malformed_and_unknown_receipts_fail_closed(self):
        for extra in ('GATE_TASK_OUTCOME :app:testDebugUnitTest SUCCESS',
                      'GATE_TASK_OUTCOME :app:lintDebug UNKNOWN', 'GATE_TASK_OUTCOME bad'):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                validate(successful_log('build') + '\n' + extra, 'build')

    def test_failed_collection_can_omit_downstream_but_never_reuse_attempt_outputs(self):
        log = successful_log('native').replace('collectDebugCoverageInputs SUCCESS', 'collectDebugCoverageInputs FAILED')
        log = '\n'.join(line for line in log.splitlines() if 'jacocoDebug' not in line)
        self.assertEqual(validate(log, 'native', 1)[':app:collectDebugCoverageInputs'], 'FAILED')
        for state in ('FROM_CACHE', 'UP_TO_DATE', 'SKIPPED', 'NO_SOURCE'):
            with self.subTest(state=state), self.assertRaises(ValueError):
                validate(log.replace('testDebugUnitTest SUCCESS', f'testDebugUnitTest {state}'), 'native', 1)
        with self.assertRaises(ValueError):
            validate(log, 'native', 0)

    def test_failed_graph_with_successful_collection_cannot_omit_coverage_receipts(self):
        log = '\n'.join(line for line in successful_log('native').splitlines() if 'jacocoDebug' not in line)
        for failed in ('testDebugUnitTest', 'connectedDebugAndroidTest', 'verifyResolvedToolchain', 'unrelatedTask'):
            candidate = log.replace(f'{failed} SUCCESS', f'{failed} FAILED')
            with self.subTest(failed=failed), self.assertRaisesRegex(ValueError, 'Missing'):
                validate(candidate, 'native', 1)
        with self.assertRaisesRegex(ValueError, 'Missing'):
            validate(log + '\nGATE_TASK_OUTCOME :app:lintDebug FAILED', 'native', 1)


class LocalExecutionPolicyTests(unittest.TestCase):
    def assert_local_policy(self, app, policy, properties):
        self.assertIn("apply from: rootProject.file('tools/build/always-execute.gradle')", app)
        self.assertIn('org.gradle.caching=true', properties.splitlines())
        for name in set(sum(REQUIRED.values(), ())):
            if name not in ('lintAnalyzeDebug', 'lintAnalyzeDebugUnitTest', 'lintAnalyzeDebugAndroidTest', 'lintReportDebug', 'lintDebug'):
                self.assertIn("'" + name + "'", policy)
        for contract in ("task.name.startsWith('lint')", 'tasks.configureEach { task ->',
                         'task.outputs.upToDateWhen { false }',
                         "task.outputs.doNotCacheIf('Verification and observation must execute every invocation') { true }",
                         'gradle.taskGraph.afterTask { task, state ->',
                         'GATE_TASK_OUTCOME ${task.path} ${outcome}',
                         "state.skipMessage == 'FROM-CACHE'", "state.noSource", "state.upToDate", "state.skipped",
                         "if (!(outcome in ['SUCCESS', 'FAILED']) && !permittedSuppression)", 'throw new GradleException'):
            self.assertIn(contract, policy)
        self.assert_vital_suppression_contract(policy)

    def assert_vital_suppression_contract(self, policy):
        # Source contract: no local Gradle/Android execution is permitted here.
        for contract in ("import com.android.build.gradle.internal.lint.AndroidLintTask",
                         "import com.android.build.gradle.internal.lint.AndroidLintTextOutputTask",
                         "if (state.skipMessage != 'SKIPPED' || task.enabled) return false",
                         "if (!(task instanceof AndroidLintTask || task instanceof AndroidLintTextOutputTask)) return false",
                         "def vital = task.name =~ /^lintVital(Report)?([A-Z].*)$/",
                         "if (!vital.matches()) return false",
                         "def fullLintName = 'lint' + (vital.group(1) ?: '') + vital.group(2)",
                         "def fullLint = gradle.taskGraph.allTasks.find {",
                         "it.project == task.project && it.name == fullLintName",
                         "return fullLint != null && isGate(fullLint) && fullLint.enabled",
                         "def permittedSuppression = outcome == 'SKIPPED' && isRedundantVitalSuppression(task, state)"):
            self.assertIn(contract, policy)

    def vital_pattern(self):
        policy = self.policy_sources()[1]
        self.assert_vital_suppression_contract(policy)
        match = re.search(r'def vital = task.name =~ /(.+)/', policy)
        assert match is not None
        return policy, match.group(1)

    def test_disabled_vital_suppression_requires_matching_full_variant_in_same_graph(self):
        _, pattern = self.vital_pattern()
        for name, full in (('lintVitalRelease', 'lintRelease'),
                           ('lintVitalReportRelease', 'lintReportRelease'),
                           ('lintVitalReportPaidRelease', 'lintReportPaidRelease')):
            match = re.fullmatch(pattern, name)
            assert match is not None
            self.assertEqual('lint' + (match[1] or '') + match[2], full)

    def test_skipped_non_vital_gates_have_no_execution_exemption(self):
        policy, pattern = self.vital_pattern()
        for name in ('testDebugUnitTest', 'lintRelease', 'lintReportRelease', 'lintVital', 'lintDebug'):
            self.assertIsNone(re.fullmatch(pattern, name))
        self.assertIn("if (!(outcome in ['SUCCESS', 'FAILED']) && !permittedSuppression)", policy)
        self.assertIn('throw new GradleException', policy)

    def test_only_agp_report_and_text_types_allow_suppression_not_vital_analysis(self):
        policy = self.policy_sources()[1]
        self.assert_vital_suppression_contract(policy)
        self.assertNotIn('AndroidLintAnalysisTask', policy)
        self.assertIn('if (!(task instanceof AndroidLintTask || task instanceof AndroidLintTextOutputTask)) return false', policy)

    def test_vital_cache_and_up_to_date_states_cannot_use_suppression(self):
        policy = self.policy_sources()[1]
        self.assert_vital_suppression_contract(policy)
        # gateOutcome is evaluated first; only its literal SKIPPED result qualifies.
        for fragment in ("if (state.skipMessage == 'FROM-CACHE') return 'FROM_CACHE'",
                         "if (state.upToDate) return 'UP_TO_DATE'"):
            self.assertIn(fragment, policy)
            self.assertLess(policy.index(fragment), policy.index("if (state.skipped) return 'SKIPPED'"))
        self.assertLess(policy.index('def outcome = gateOutcome(state)'), policy.index('def permittedSuppression ='))

    def test_removing_any_vital_suppression_guard_fails_contract(self):
        policy = self.policy_sources()[1]
        for guard in ("state.skipMessage != 'SKIPPED' || ", ' || task.enabled',
                      'if (!(task instanceof AndroidLintTask || task instanceof AndroidLintTextOutputTask)) return false',
                      'if (!vital.matches()) return false', 'gradle.taskGraph.allTasks.find',
                      'it.project == task.project && ', 'it.name == fullLintName',
                      'fullLint != null && ', 'isGate(fullLint) && ', ' && fullLint.enabled',
                      "outcome == 'SKIPPED' && "):
            self.assertIn(guard, policy)
            with self.subTest(guard=guard), self.assertRaises(AssertionError):
                self.assert_vital_suppression_contract(policy.replace(guard, '', 1))

    def policy_sources(self):
        return [(ROOT / path).read_text() for path in
                ('app/build.gradle', 'tools/build/always-execute.gradle', 'gradle.properties')]

    def test_local_cache_and_every_verification_observation_are_protected(self):
        self.assert_local_policy(*self.policy_sources())

    def test_removing_application_or_any_always_execute_guarantee_fails_contract(self):
        original = self.policy_sources()
        mutations = [(0, "apply from: rootProject.file('tools/build/always-execute.gradle')"),
                     (2, 'org.gradle.caching=true')]
        mutations += [(1, text) for text in ("'prepareDebugCoverage'", "'verifyResolvedToolchain'",
                       "'testDebugUnitTest'", "'connectedDebugAndroidTest'", "'collectDebugCoverageInputs'",
                       "'jacocoDebugReport'", "'jacocoDebugCoverageVerification'", "task.name.startsWith('lint')",
                       'task.outputs.upToDateWhen { false }',
                       "task.outputs.doNotCacheIf('Verification and observation must execute every invocation') { true }",
                       'GATE_TASK_OUTCOME ${task.path} ${outcome}', 'throw new GradleException')]
        for index, text in mutations:
            mutated = original.copy()
            self.assertIn(text, mutated[index])
            mutated[index] = mutated[index].replace(text, '', 1)
            with self.subTest(text=text), self.assertRaises(AssertionError):
                self.assert_local_policy(*mutated)


if __name__ == '__main__':
    unittest.main()
