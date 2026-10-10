"""Offline, explicitly synthetic native-coverage retry/runner regressions; no emulator/Gradle."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import coverage_gate
import native_coverage_retry as retry
from verify_test_reports import HISTORY_ISOLATION_CASES, HISTORY_ISOLATION_CLASS
from verify_test_reports import ACCOUNT_NAME_CASES, ACCOUNT_NAME_CLASS
from verify_test_reports import HISTORY_ASSEMBLED_CASES, HISTORY_ASSEMBLED_CLASS
from verify_test_reports import (HISTORY_AUTHORITY_CASES, HISTORY_AUTHORITY_CLASS, HISTORY_NAVIGATION_CASES, HISTORY_NAVIGATION_CLASS, HISTORY_CHART_CASES, HISTORY_CHART_CLASS, HISTORY_TEXT_CASES, HISTORY_TEXT_CLASS, HISTORY_SAMPLING_CASES, HISTORY_SAMPLING_CLASS, HISTORY_LIFETIME_CASES, HISTORY_LIFETIME_CLASS, HISTORY_CASES, HISTORY_CLASS, BANKED_RESET_CASES, BANKED_RESET_CLASS, CLASS, CONNECTION_CASES, CONNECTION_CLASS, CREDENTIAL_CASES, CREDENTIAL_CLASS,
                                 EXPECTED, LIVE_USAGE_CASES, LIVE_USAGE_CLASS, USAGE_REFRESH_CASES, USAGE_REFRESH_CLASS)

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("RUNNER_TEMP", os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir())))
# Synthetic paths/package, with the exact AGP coverage-pull error shape seen in CI.
COVERAGE_PULL_OFFLINE = (
    "adb exec-out run-as SYNTHETIC.package cat /data/data/SYNTHETIC.package/coverage.ec "
    "failed with exit code 255. Error: error: device offline"
)


def synthetic_reports(build, failed=False):
    for kind, relative in (("jvm", "test-results/testDebugUnitTest"),
                           ("native", "outputs/androidTest-results/connected")):
        directory = build / relative
        directory.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", tests=str(len(EXPECTED[kind])), failures="0", errors="0", skipped="0")
        for name in sorted(EXPECTED[kind]):
            case = ET.SubElement(suite, "testcase", classname=CLASS[kind], name=name)
            if failed and kind == "native":
                ET.SubElement(case, "failure", message="SYNTHETIC test failure")
        if kind == "native":
            for name in sorted(CREDENTIAL_CASES):
                ET.SubElement(suite, "testcase", classname=CREDENTIAL_CLASS, name=name)
            for classname, required in ((CONNECTION_CLASS, CONNECTION_CASES), (USAGE_REFRESH_CLASS, USAGE_REFRESH_CASES),
                                        (LIVE_USAGE_CLASS, LIVE_USAGE_CASES), (BANKED_RESET_CLASS, BANKED_RESET_CASES), (HISTORY_CLASS, HISTORY_CASES), (HISTORY_LIFETIME_CLASS, HISTORY_LIFETIME_CASES), (HISTORY_SAMPLING_CLASS, HISTORY_SAMPLING_CASES), (HISTORY_TEXT_CLASS, HISTORY_TEXT_CASES), (HISTORY_CHART_CLASS, HISTORY_CHART_CASES), (HISTORY_NAVIGATION_CLASS, HISTORY_NAVIGATION_CASES), (HISTORY_AUTHORITY_CLASS, HISTORY_AUTHORITY_CASES), (HISTORY_ISOLATION_CLASS, HISTORY_ISOLATION_CASES), (HISTORY_ASSEMBLED_CLASS, HISTORY_ASSEMBLED_CASES), (ACCOUNT_NAME_CLASS, ACCOUNT_NAME_CASES)):
                for name in sorted(required):
                    ET.SubElement(suite, "testcase", classname=classname, name=name)
        suite.set("tests", str(len(list(suite))))
        ET.ElementTree(suite).write(directory / "SYNTHETIC.xml")


class RetryContracts(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(self.temporary.cleanup)
        self.build = Path(self.temporary.name) / "app/build"
        self.native = self.build / "outputs/code_coverage/debugAndroidTest/connected/SYNTHETIC/coverage.ec"
        self.native.parent.mkdir(parents=True)
        self.native.write_bytes(b"")
        self.unit = self.build / "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
        self.unit.parent.mkdir(parents=True)
        self.unit.write_bytes(b"SYNTHETIC JVM dataset")
        self.identity = {key: "SYNTHETIC-" + key for key in coverage_gate.IDENTITY_KEYS}
        self.context = dict(self.identity, startedMillis=5000)
        self.failure = {"kind": "native", "reason": "empty", "path": str(self.native),
                        "bytes": 0, "sha256": coverage_gate.digest(self.native), "modifiedMillis": 6000}
        synthetic_reports(self.build)
        self.log = "Collecting code coverage data.\nPulling coverage file: SYNTHETIC\nadb: device offline\n"
        states = {"jvm": "SUCCESS", "instrumentation": "SUCCESS", "inputs": "FAILED"}
        self.log += "\n".join(f"COVERAGE_TASK_OUTCOME :app:{coverage_gate.PHASES[phase]} {state}"
                              for phase, state in states.items())

    def require(self, *, status=1, log=None, context=None, failure=None):
        with patch.object(retry, "BUILD", self.build):
            return retry.require_transport_failure(status, self.log if log is None else log,
                                                   self.context if context is None else context,
                                                   self.failure if failure is None else failure,
                                                   self.identity, 7000)

    def prepare_missing(self):
        self.native.unlink()
        self.failure = {"kind": "native", "reason": "missing", "path": str(self.native.parent.parent)}
        # Positive control ensures negatives exercise missing-specific guards,
        # rather than passing because the reason is unconditionally unsupported.
        self.assertEqual(self.require(), "missing")

    def test_missing_native_accepts_root_child_and_absent_directory_without_file_claims(self):
        self.prepare_missing()
        for directory in (self.native.parent.parent, self.native.parent, self.native.parent / "absent"):
            with self.subTest(directory=directory):
                self.assertEqual(self.require(failure=dict(self.failure, path=str(directory))), "missing")

    def test_missing_native_rejects_missing_jvm_dataset(self):
        self.prepare_missing()
        self.unit.unlink()
        with self.assertRaisesRegex(ValueError, "Missing JVM exec"):
            self.require()

    def test_missing_record_rejects_existing_ec_even_outside_recorded_child(self):
        self.prepare_missing()
        self.native.write_bytes(b"SYNTHETIC unexpectedly present")
        for directory in (self.native.parent.parent, self.native.parent.parent / "absent-sibling"):
            with self.subTest(directory=directory), self.assertRaisesRegex(ValueError, "Native coverage exists"):
                self.require(failure=dict(self.failure, path=str(directory)))

    def test_missing_record_rejects_wrong_directory_and_file_paths(self):
        self.prepare_missing()
        for path in (self.build, self.build / "outputs/code_coverage/debugAndroidTest/connected-other",
                     self.unit, self.native.parent / ".." / ".." / "outside"):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "output directory"):
                self.require(failure=dict(self.failure, path=str(path)))
        file = self.native.parent / "not-a-directory"
        file.touch()
        with self.assertRaisesRegex(ValueError, "output directory"):
            self.require(failure=dict(self.failure, path=str(file)))

    def test_missing_record_rejects_file_metadata_claims(self):
        self.prepare_missing()
        for field, value in (("bytes", 0), ("sha256", "SYNTHETIC"), ("modifiedMillis", 6000)):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "must not claim file metadata"):
                self.require(failure=dict(self.failure, **{field: value}))

    def test_missing_retry_requires_ordinary_failure_and_exact_phase_outcomes(self):
        self.prepare_missing()
        for status in (0, 2, 124, 130, 137, 143):
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "ordinary Gradle failure"):
                self.require(status=status)
        for phase in ("jvm", "instrumentation"):
            task = coverage_gate.PHASES[phase]
            with self.subTest(phase=phase), self.assertRaisesRegex(ValueError, "passing suites"):
                self.require(log=self.log.replace(f":app:{task} SUCCESS", f":app:{task} FAILED"))
        for phase in ("report", "verification"):
            with self.subTest(phase=phase), self.assertRaisesRegex(ValueError, "passing suites"):
                self.require(log=self.log + f"\nCOVERAGE_TASK_OUTCOME :app:{coverage_gate.PHASES[phase]} SUCCESS")

    def test_missing_retry_requires_matching_identity_and_valid_start_time(self):
        self.prepare_missing()
        for key in coverage_gate.IDENTITY_KEYS:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "Wrong checkout/run/attempt"):
                self.require(context=dict(self.context, **{key: "SYNTHETIC-other"}))
        for start in ("invalid", 8000):
            with self.subTest(start=start), self.assertRaisesRegex(ValueError, "Invalid retry context"):
                self.require(context=dict(self.context, startedMillis=start))
        for changes in ({"kind": "jvm"}, {"reason": "unknown"}):
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "native collection data"):
                self.require(failure=dict(self.failure, **changes))

    def test_missing_retry_requires_offline_evidence_after_last_collection(self):
        self.prepare_missing()
        for log in (self.log.replace("adb: device offline", ""),
                    self.log + "\nCollecting code coverage data.\nPulling coverage file: SYNTHETIC\n"):
            with self.subTest(log=log), self.assertRaisesRegex(ValueError, "ADB-offline evidence"):
                self.require(log=log)

    def test_missing_retry_rejects_failed_or_missing_jvm_and_native_junit_reports(self):
        self.prepare_missing()
        for kind, relative in (("jvm", "test-results/testDebugUnitTest"),
                               ("native", "outputs/androidTest-results/connected")):
            path = self.build / relative / "SYNTHETIC.xml"
            tree = ET.parse(path)
            ET.SubElement(tree.getroot()[0], "failure", message="SYNTHETIC failure")
            tree.write(path)
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, "failed/errored/skipped"):
                self.require()
            path.unlink()
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, f"No {kind} JUnit XML"):
                self.require()
            synthetic_reports(self.build)

    def test_coverage_pull_offline_line_is_sufficient_without_uninstall_output(self):
        for line in (COVERAGE_PULL_OFFLINE, COVERAGE_PULL_OFFLINE.replace("Error: error:", "Error: adb:")):
            with self.subTest(line=line):
                self.assertEqual(self.require(log=self.log.replace("adb: device offline", line)), "empty")

    def test_uninstall_only_offline_is_not_coverage_pull_evidence(self):
        log = self.log.replace("adb: device offline", "Uninstalling SYNTHETIC.package.\nError Output: adb: device offline")
        with self.assertRaisesRegex(ValueError, "ADB-offline evidence"):
            self.require(log=log)

    def test_empty_and_truncated_transport_failures_after_green_suites_can_retry(self):
        self.assertEqual(self.require(), "empty")
        self.native.write_bytes(b"SYNTHETIC truncated JaCoCo bytes")
        self.failure.update(reason="truncated", bytes=self.native.stat().st_size,
                            sha256=coverage_gate.digest(self.native))
        self.assertEqual(self.require(), "truncated")

    def test_never_retries_success_timeout_signal_or_unrelated_graph_failure(self):
        for status in (0, 2, 124, 126, 127, 130, 137, 143):
            with self.subTest(status=status), self.assertRaises(ValueError):
                self.require(status=status)
        for phase in ("jvm", "instrumentation", "inputs"):
            task = coverage_gate.PHASES[phase]
            old = "FAILED" if phase == "inputs" else "SUCCESS"
            for state in ("FAILED", "SKIPPED", "UP_TO_DATE", "SUCCESS"):
                if state != old:
                    with self.subTest(phase=phase, state=state), self.assertRaises(ValueError):
                        self.require(log=self.log.replace(f":app:{task} {old}", f":app:{task} {state}"))
        with self.assertRaises(ValueError):
            self.require(log=self.log + "\nCOVERAGE_TASK_OUTCOME :app:jacocoDebugReport FAILED")

    def test_requires_disconnect_at_collection_not_an_earlier_or_absent_offline(self):
        for log in (self.log.replace("adb: device offline", ""),
                    "adb: device offline\n" + self.log.replace("adb: device offline", ""),
                    self.log.replace("Collecting code coverage data.", "Collecting unrelated data.")):
            with self.subTest(log=log), self.assertRaisesRegex(ValueError, "ADB-offline evidence"):
                self.require(log=log)

    def test_stale_wrong_context_changed_data_and_noncollection_defects_cannot_retry(self):
        for key in coverage_gate.IDENTITY_KEYS:
            context = dict(self.context, **{key: "SYNTHETIC-other"})
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.require(context=context)
        for changes in ({"startedMillis": 8000}, {"startedMillis": "invalid"}):
            with self.assertRaises(ValueError):
                self.require(context=dict(self.context, **changes))
        for changes in ({"kind": "jvm"}, {"reason": "class-id"}, {"reason": "no-sessions"},
                        {"reason": "stale"}, {"reason": "probe-count"}, {"reason": "truncated"},
                        {"bytes": 1}, {"sha256": "SYNTHETIC-changed"}, {"modifiedMillis": 4999},
                        {"path": str(self.build / "not-native.ec")}):
            with self.subTest(changes=changes), self.assertRaises((ValueError, FileNotFoundError)):
                self.require(failure=dict(self.failure, **changes))
        os.utime(self.native, ns=(1, 1))
        with self.assertRaisesRegex(ValueError, "Stale"):
            self.require()

    def test_actual_junit_failure_missing_cases_or_reports_prevent_retry(self):
        synthetic_reports(self.build, failed=True)
        with self.assertRaisesRegex(ValueError, "failed/errored/skipped"):
            self.require()
        shutil.rmtree(self.build / "outputs/androidTest-results/connected")
        with self.assertRaisesRegex(ValueError, "No native JUnit XML"):
            self.require()

    def test_missing_held_authority_case_prevents_coverage_transport_retry(self):
        for name in HISTORY_AUTHORITY_CASES:
            synthetic_reports(self.build)
            path = self.build / "outputs/androidTest-results/connected/SYNTHETIC.xml"
            tree = ET.parse(path)
            root = tree.getroot()
            case = next(case for case in root if case.get("classname") == HISTORY_AUTHORITY_CLASS and case.get("name") == name)
            root.remove(case)
            tree.write(path)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native history display authority"):
                self.require()

    def test_snapshot_preserves_failed_inputs_and_tests_before_prepare_deletes_them(self):
        coverage = self.build / "coverage-gate"
        coverage.mkdir()
        (coverage / "context.json").write_text(json.dumps(self.context))
        destination = Path(self.temporary.name) / "evidence/native/attempt-1"
        with patch.object(retry, "BUILD", self.build):
            retry.archive_failed_graph(destination)
        with patch.object(coverage_gate, "BUILD", self.build), patch.object(coverage_gate, "COVERAGE", coverage), patch.object(
                coverage_gate, "current_identity", return_value=self.identity):
            coverage_gate.prepare()
        self.assertFalse(self.native.exists())
        saved = destination / "app/build" / self.native.relative_to(self.build)
        self.assertEqual(saved.read_bytes(), b"")
        self.assertEqual(json.loads((destination / "app/build/coverage-gate/context.json").read_text()), self.context)
        self.assertTrue((destination / "app/build/test-results/testDebugUnitTest/SYNTHETIC.xml").exists())
        self.assertTrue((destination / "app/build/outputs/androidTest-results/connected/SYNTHETIC.xml").exists())

    def test_missing_integrated_isolation_case_prevents_coverage_transport_retry(self):
        for name in HISTORY_ISOLATION_CASES:
            synthetic_reports(self.build)
            path = self.build / "outputs/androidTest-results/connected/SYNTHETIC.xml"
            tree = ET.parse(path)
            root = tree.getroot()
            case = next(case for case in root if case.get("classname") == HISTORY_ISOLATION_CLASS and case.get("name") == name)
            root.remove(case)
            tree.write(path)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native history isolation"):
                self.require()


    def test_each_assembled_case_missing_failed_errored_or_skipped_prevents_retry(self):
        for name in HISTORY_ASSEMBLED_CASES:
            for mutation in ("missing", "failure", "error", "skipped"):
                synthetic_reports(self.build)
                path = self.build / "outputs/androidTest-results/connected/SYNTHETIC.xml"
                tree = ET.parse(path)
                root = tree.getroot()
                case = next(case for case in root if case.get("classname") == HISTORY_ASSEMBLED_CLASS and case.get("name") == name)
                if mutation == "missing":
                    root.remove(case)
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    self.require()


# The runner block below is exercised with labelled synthetic command doubles,
# not real Gradle/device output. This checks bounded control flow and evidence,
# not Android compilation, instrumentation or an actual JaCoCo report.
SYNTHETIC_GRADLE = '''
import json, os
from pathlib import Path
import time
import coverage_gate
import verify_gradle_execution
from test_native_coverage_retry import COVERAGE_PULL_OFFLINE, synthetic_reports

scenario = os.environ['SYNTHETIC_SCENARIO']
count = Path('SYNTHETIC-invocations')
attempt = int(count.read_text()) + 1 if count.exists() else 1
count.write_text(str(attempt))
coverage_gate.prepare()
synthetic_reports(coverage_gate.BUILD, failed=scenario == 'test-failure')
context = json.loads((coverage_gate.COVERAGE / 'context.json').read_text())
time.sleep(0.01)  # synthetic file timestamp ordering, never native readiness
unit = coverage_gate.BUILD / 'outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec'
unit.parent.mkdir(parents=True)
unit.write_bytes(b'SYNTHETIC JVM dataset')
path = coverage_gate.BUILD / 'outputs/code_coverage/debugAndroidTest/connected/SYNTHETIC/coverage.ec'
path.parent.mkdir(parents=True)
reason = scenario.split('-')[0] if scenario.startswith(('missing-', 'truncated-')) else 'empty'
green = scenario in ('first-green', 'cached-green', 'missing-then-green', 'empty-then-green', 'truncated-then-green') and (attempt == 2 or scenario in ('first-green', 'cached-green'))
if green or reason != 'missing':
    path.write_bytes(b'SYNTHETIC current attempt data' if green else b'SYNTHETIC truncated' if reason == 'truncated' else b'')
if not green:
    if reason == 'missing':
        failure = dict(kind='native', reason=reason, path=str(path.parent.parent))
    else:
        failure = dict(kind='native', reason=reason, path=str(path), bytes=path.stat().st_size,
                       modifiedMillis=path.stat().st_mtime_ns // 1000000, sha256=coverage_gate.digest(path))
    coverage_gate.write_json(coverage_gate.COVERAGE / 'collection-failure.json', failure)
print("Caching disabled for task ':app:testDebugUnitTest' because:")
print('  Build cache is disabled')
print('Loading library manifest /SYNTHETIC/library/AndroidManifest.xml')
print('Collecting code coverage data.')
if scenario != 'no-offline' and not green:
    print(COVERAGE_PULL_OFFLINE)
for phase, task in coverage_gate.PHASES.items():
    if green:
        state = 'SUCCESS'
    elif phase in ('report', 'verification'):
        continue
    else:
        state = 'FAILED' if phase == 'inputs' else 'SUCCESS'
    print(f'COVERAGE_TASK_OUTCOME :app:{task} {state}')
for task in verify_gradle_execution.REQUIRED['native']:
    if not green and task.startswith('jacocoDebug'):
        continue
    state = 'FAILED' if task == 'collectDebugCoverageInputs' and not green else 'SUCCESS'
    if scenario in ('cached-test', 'cached-green') and task == 'testDebugUnitTest':
        state = 'FROM_CACHE'
    print(f'GATE_TASK_OUTCOME :app:{task} {state}')
raise SystemExit(0 if green else 124 if scenario == 'timeout' else 1)
'''


class SyntheticRunnerTests(unittest.TestCase):
    def runner_block(self):
        script = (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text()
        block = script.split("graph_deadline=$((SECONDS + 900))", 1)[1].split(
            "# Evaluate the actual suites even if a later coverage task failed.", 1)[0]
        return "graph_deadline=$((SECONDS + 900))" + block + '\nexit "$test_status"\n'

    def run_scenario(self, base, scenario):
        tools = base / "tools/build"
        tools.mkdir(parents=True)
        for filename in ("coverage_gate.py", "native_coverage_retry.py", "verify_test_reports.py",
                         "filter-gradle-console.sh", "verify_gradle_execution.py"):
            shutil.copyfile(ROOT / "tools/build" / filename, tools / filename)
        evidence = base / "evidence/native"
        evidence.mkdir(parents=True)
        (evidence / "boot-success.txt").touch()
        binary = base / "bin"
        binary.mkdir()
        (binary / "git").write_text('#!/bin/sh\nprintf "%s\\n" "SYNTHETIC-checkout"\n')
        (binary / "adb").write_text('#!/bin/sh\ncase "$*" in\n*get-state*) printf "device\\n";;\n*sys.boot_completed*) printf "1\\n";;\n*) printf "SYNTHETIC readiness\\n";;\nesac\n')
        (base / "gradlew").write_text(f"#!{sys.executable}\n" + SYNTHETIC_GRADLE)
        for executable in (binary / "git", binary / "adb", base / "gradlew"):
            executable.chmod(0o755)
        environment = {"PATH": str(binary) + os.pathsep + os.environ["PATH"],
                       "PYTHONPATH": str(ROOT / "tools/build"), "PYTHONDONTWRITEBYTECODE": "1",
                       "SYNTHETIC_SCENARIO": scenario}
        prefix = 'set -euo pipefail\nadb="$PWD/bin/adb"\nANDROID_SERIAL=SYNTHETIC\nemulator_pid=$$\n'
        return subprocess.run(["bash", "-c", prefix + self.runner_block()], cwd=base, env=environment,
                              text=True, capture_output=True, timeout=15)

    def assert_runner_logs(self, base, result, count):
        evidence = base / "evidence/native"
        for attempt in range(1, count + 1):
            for filename in ("strict-connected.log", "graph-exit-status.txt", "task-phase-outcomes.json"):
                self.assertTrue((evidence / f"attempt-{attempt}" / filename).is_file())
        self.assertNotIn("Caching disabled for task", result.stdout)
        self.assertNotIn("Loading library manifest", result.stdout)
        latest = (evidence / "strict-connected.log").read_text()
        self.assertIn("Caching disabled for task", latest)
        self.assertIn("Loading library manifest", latest)
        self.assertEqual(latest.count("COVERAGE_TASK_OUTCOME :app:connectedDebugAndroidTest"), 1)

    def assert_archived_graph(self, base, scenario, status):
        evidence = base / "evidence/native"
        archived = evidence / "attempt-1/app/build"
        saved = archived / "outputs/code_coverage/debugAndroidTest/connected/SYNTHETIC/coverage.ec"
        reason = scenario.split("-")[0]
        if reason == "missing":
            self.assertFalse(saved.exists())
            failure = json.loads((archived / "coverage-gate/collection-failure.json").read_text())
            self.assertEqual(set(failure), {"kind", "reason", "path"})
            self.assertEqual(failure["reason"], "missing")
        else:
            self.assertEqual(saved.read_bytes(), b"SYNTHETIC truncated" if reason == "truncated" else b"")
        for relative in ("coverage-gate/context.json", "test-results/testDebugUnitTest/SYNTHETIC.xml",
                         "outputs/androidTest-results/connected/SYNTHETIC.xml",
                         "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"):
            self.assertTrue((archived / relative).is_file())
        decision = json.loads((evidence / "retry-decision.json").read_text())
        self.assertTrue(decision["retry"])
        self.assertEqual(decision["reason"], f"ADB-offline/{reason}-native-coverage")
        phases = json.loads((evidence / "task-phase-outcomes.json").read_text())
        self.assertEqual(phases["graphExit"], status)
        self.assertEqual(phases["inputs"], "SUCCESS" if status == 0 else "FAILED")

    def test_real_runner_block_bounds_retry_and_preserves_each_attempt(self):
        for scenario, expected_count, expected_status in (
            ("first-green", 1, 0), ("empty-then-green", 2, 0), ("truncated-then-green", 2, 0),
            ("missing-then-green", 2, 0), ("missing-twice", 2, 1),
            ("empty-twice", 2, 1), ("test-failure", 1, 1), ("no-offline", 1, 1), ("timeout", 1, 124),
        ):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(dir=SCRATCH) as temporary:
                base = Path(temporary)
                result = self.run_scenario(base, scenario)
                self.assertEqual(result.returncode, expected_status, result.stdout + result.stderr)
                self.assertEqual(int((base / "SYNTHETIC-invocations").read_text()), expected_count)
                self.assert_runner_logs(base, result, expected_count)
                if expected_count == 2:
                    self.assert_archived_graph(base, scenario, expected_status)

    def test_cached_gate_prevents_transport_retry_and_preserves_graph_exit(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as temporary:
            base = Path(temporary)
            result = self.run_scenario(base, "cached-test")
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertEqual((base / "SYNTHETIC-invocations").read_text(), "1")
            gate_log = (base / "evidence/native/attempt-1/gate-execution.log").read_text()
            self.assertIn("FROM_CACHE", gate_log)
            self.assertFalse((base / "evidence/native/retry-decision.json").exists())

    def test_green_graph_with_cached_gate_cannot_pass(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as temporary:
            base = Path(temporary)
            result = self.run_scenario(base, "cached-green")
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((base / "evidence/native/graph-exit-status.txt").read_text(), "graph_task_exit=0\n")
            self.assertIn("FROM_CACHE", (base / "evidence/native/attempt-1/gate-execution.log").read_text())

    def test_missing_native_diagnostic_source_contract_excludes_jvm_and_file_metadata(self):
        build = (ROOT / "app/build.gradle").read_text()
        unit_guard = "if (!unit.isFile()) throw new GradleException('Missing JVM exec coverage data')"
        self.assertIn(unit_guard, build)
        missing = build.split("if (nativeFiles.empty) {", 1)[1].split("def union =", 1)[0]
        self.assertLess(build.index(unit_guard), build.index("if (nativeFiles.empty) {"))
        for required in ("base.toPath().relativize(nativeDirectory.toPath()).toString()",
                         "collection-failure.json", "kind: 'native', reason: 'missing', path: relative",
                         "Missing native ec coverage data under:"):
            self.assertIn(required, missing)
        for forbidden in ("bytes:", "sha256:", "modifiedMillis:"):
            self.assertNotIn(forbidden, missing)
        self.assertIn("project.fileTree(nativeDirectory)", build)
        self.assertIn("new File(project.buildDir, 'outputs/code_coverage/debugAndroidTest/connected')", build)

    def test_hosted_emulator_launch_uses_4096_mib_and_retains_other_flags(self):
        script = (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text()
        launch = script.split('"$emulator" -avd ', 1)[1].split('> evidence/native/emulator.log', 1)[0]
        self.assertEqual(launch.replace("\\\n", "").split(),
                         ['"$avd"', '-port', '5554', '-accel', 'on', '-cores', '2', '-memory', '4096',
                          '-no-window', '-no-snapshot', '-no-boot-anim', '-noaudio', '-gpu', 'swiftshader_indirect'])

    def test_collection_diagnostic_wiring_keeps_parse_and_union_failures_closed(self):
        build = (ROOT / "app/build.gradle").read_text()
        for required in ("collection-failure.json", "file.length() == 0", "rejectCollection('empty')",
                         "catch (java.io.EOFException failure)", "rejectCollection('truncated')",
                         "execution.load(file)", "union.load(file)", "':app:collectDebugCoverageInputs'"):
            self.assertIn(required, build)
        script = (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text()
        self.assertIn("coverage_attempt == 2", script)
        self.assertIn("coverage_attempt=2", script)
        self.assertNotIn("coverage_attempt=3", script)
        self.assertIn('native_coverage_retry.py --exit "$test_status"', script)
        self.assertIn("graph_deadline=$((SECONDS + 900))", script)
        self.assertIn("retry_deadline=$((SECONDS + 60))", script)
        self.assertIn('[[ "$retry_ready" == true ]] || exit "$test_status"', script)
        self.assertLess(script.index("native_coverage_retry.py"), script.index("coverage_attempt=2"))
        self.assertEqual(script.count("./gradlew "), 1)


if __name__ == "__main__":
    unittest.main()
