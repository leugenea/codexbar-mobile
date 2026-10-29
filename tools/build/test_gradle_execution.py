"""Synthetic evidence only: these tests never invoke Gradle, tests or an emulator."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from verify_gradle_execution import COMPILERS, REQUIRED, validate, validate_compilation

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
                    name = 'testDebugUnitTest' if 'testDebugUnitTest' in REQUIRED[graph] else REQUIRED[graph][0]
                    log.write_text(successful_log(graph).replace(f'{name} SUCCESS', f'{name} {state}'))
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
        for failed in ('testDebugUnitTest', 'connectedDebugAndroidTest', 'verifyResolvedToolchain'):
            candidate = log.replace(f'{failed} SUCCESS', f'{failed} FAILED')
            self.assertIn(f':app:{failed} FAILED', candidate)
            with self.subTest(failed=failed), self.assertRaisesRegex(ValueError, 'Missing'):
                validate(candidate, 'native', 1)
        # This lint failure is unrelated to coverage collection, which succeeded.
        candidate = log + '\nGATE_TASK_OUTCOME :app:lintDebug FAILED'
        self.assertIn(':app:collectDebugCoverageInputs SUCCESS', candidate)
        self.assertIn(':app:lintDebug FAILED', candidate)
        with self.assertRaisesRegex(ValueError, 'Missing'):
            validate(candidate, 'native', 1)


def compiler_log():
    # The retained hosted --info log had 3/2/2/2 plain headers for one execution.
    lines = []
    for name, repetitions in zip(COMPILERS, (3, 2, 2, 2)):
        lines += ['> Task :app:' + name] * repetitions
        lines += ['COMPILER_TASK_OUTCOME :app:' + name + ' SUCCESS']
    return '\n'.join(lines)


class CompilationEvidenceTests(unittest.TestCase):
    def test_repeated_info_headers_accept_successful_terminal_outcomes(self):
        validate_compilation(compiler_log())
        # Interleaved/reprinted headers after the receipt remain informational.
        validate_compilation(compiler_log() + '\n' + '\n'.join('> Task :app:' + name for name in COMPILERS))
        validate_compilation(compiler_log() + '\n> Task :app:compileDebugKotlinOther FROM-CACHE')

    def test_each_compiler_requires_receipt_and_header(self):
        for name in COMPILERS:
            for prefix in ('COMPILER_TASK_OUTCOME :app:', '> Task :app:'):
                log = '\n'.join(line for line in compiler_log().splitlines() if not line.startswith(prefix + name))
                with self.subTest(task=name, prefix=prefix), self.assertRaises(ValueError):
                    validate_compilation(log)

    def test_each_compiler_rejects_nonexecution_and_failure_terminal_states(self):
        for name in COMPILERS:
            for state in ('FROM_CACHE', 'UP_TO_DATE', 'SKIPPED', 'NO_SOURCE', 'FAILED'):
                log = compiler_log().replace(name + ' SUCCESS', name + ' ' + state)
                with self.subTest(task=name, state=state), self.assertRaises(ValueError):
                    validate_compilation(log)

    def test_successful_receipt_cannot_hide_a_contradictory_header_before_or_after_it(self):
        for name in COMPILERS:
            for state in ('FROM-CACHE', 'UP-TO-DATE', 'SKIPPED', 'NO-SOURCE', 'FAILED'):
                bad = f'> Task :app:{name} {state}'
                for log in (bad + '\n' + compiler_log(), compiler_log() + '\n' + bad):
                    with self.subTest(task=name, state=state), self.assertRaises(ValueError):
                        validate_compilation(log)

    def test_duplicate_or_conflicting_terminal_receipts_fail_in_both_orders(self):
        for name in COMPILERS:
            for state in ('SUCCESS', 'FAILED', 'FROM_CACHE', 'SKIPPED'):
                bad = f'COMPILER_TASK_OUTCOME :app:{name} {state}'
                for log in (bad + '\n' + compiler_log(), compiler_log() + '\n' + bad):
                    with self.subTest(task=name, state=state), self.assertRaisesRegex(ValueError, 'Duplicate'):
                        validate_compilation(log)

    def test_malformed_or_wrong_kind_receipts_do_not_prove_compilation(self):
        for bad in ('COMPILER_TASK_OUTCOME :app:compileDebugKotlin UNKNOWN',
                    'COMPILER_TASK_OUTCOME :app:compileDebugKotlin', 'COMPILER_TASK_OUTCOME bad',
                    'COMPILER_TASK_OUTCOME :app:compileDebugKotlin SUCCESS extra'):
            with self.subTest(receipt=bad), self.assertRaises(ValueError):
                validate_compilation(compiler_log() + '\n' + bad)
        with self.assertRaises(ValueError):
            validate_compilation(compiler_log().replace('COMPILER_TASK_OUTCOME', 'GATE_TASK_OUTCOME'))

    def test_nonzero_graph_exit_cannot_pass_with_successful_compilers(self):
        for code in (1, 7, 124, 130, 143):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'graph failed'):
                validate_compilation(compiler_log(), code)


if __name__ == '__main__':
    unittest.main()
