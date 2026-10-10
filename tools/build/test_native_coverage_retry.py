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
from test_native_coverage_retry import synthetic_reports

scenario = os.environ['SYNTHETIC_SCENARIO']
count = Path('SYNTHETIC-invocations')
attempt = int(count.read_text()) + 1 if count.exists() else 1
count.write_text(str(attempt))
coverage_gate.prepare()
synthetic_reports(coverage_gate.BUILD, failed=scenario == 'test-failure')
context = json.loads((coverage_gate.COVERAGE / 'context.json').read_text())
time.sleep(0.01)  # synthetic file timestamp ordering, never native readiness
path = coverage_gate.BUILD / 'outputs/code_coverage/debugAndroidTest/connected/SYNTHETIC/coverage.ec'
path.parent.mkdir(parents=True)
reason = 'truncated' if scenario == 'truncated-then-green' else 'empty'
green = scenario in ('first-green', 'empty-then-green', 'truncated-then-green') and (attempt == 2 or scenario == 'first-green')
path.write_bytes(b'SYNTHETIC current attempt data' if green else b'SYNTHETIC truncated' if reason == 'truncated' else b'')
if not green:
    failure = dict(kind='native', reason=reason, path=str(path), bytes=path.stat().st_size,
                   modifiedMillis=path.stat().st_mtime_ns // 1000000, sha256=coverage_gate.digest(path))
    coverage_gate.write_json(coverage_gate.COVERAGE / 'collection-failure.json', failure)
print("Caching disabled for task ':app:testDebugUnitTest' because:")
print('  Build cache is disabled')
print('Loading library manifest /SYNTHETIC/library/AndroidManifest.xml')
print('Collecting code coverage data.')
if scenario != 'no-offline' and not green:
    print('adb: device offline')
for phase, task in coverage_gate.PHASES.items():
    if green:
        state = 'SUCCESS'
    elif phase in ('report', 'verification'):
        continue
    else:
        state = 'FAILED' if phase == 'inputs' else 'SUCCESS'
    print(f'COVERAGE_TASK_OUTCOME :app:{task} {state}')
raise SystemExit(0 if green else 124 if scenario == 'timeout' else 1)
'''


class SyntheticRunnerTests(unittest.TestCase):
    def test_real_runner_block_bounds_retry_and_preserves_each_attempt(self):
        script = (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text()
        block = script.split("graph_deadline=$((SECONDS + 900))", 1)[1].split(
            "# Evaluate the actual suites even if a later coverage task failed.", 1)[0]
        block = "graph_deadline=$((SECONDS + 900))" + block + '\nexit "$test_status"\n'
        for scenario, expected_count, expected_status in (
            ("first-green", 1, 0), ("empty-then-green", 2, 0), ("truncated-then-green", 2, 0),
            ("empty-twice", 2, 1), ("test-failure", 1, 1), ("no-offline", 1, 1), ("timeout", 1, 124),
        ):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(dir=SCRATCH) as temporary:
                base = Path(temporary)
                tools = base / "tools/build"
                tools.mkdir(parents=True)
                for filename in ("coverage_gate.py", "native_coverage_retry.py", "verify_test_reports.py",
                                 "filter-gradle-console.sh"):
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
                result = subprocess.run(["bash", "-c", prefix + block], cwd=base, env=environment,
                                        text=True, capture_output=True, timeout=15)
                self.assertEqual(result.returncode, expected_status, result.stdout + result.stderr)
                self.assertEqual(int((base / "SYNTHETIC-invocations").read_text()), expected_count)
                for attempt in range(1, expected_count + 1):
                    attempt_path = evidence / f"attempt-{attempt}"
                    self.assertTrue((attempt_path / "strict-connected.log").is_file())
                    self.assertTrue((attempt_path / "graph-exit-status.txt").is_file())
                    self.assertTrue((attempt_path / "task-phase-outcomes.json").is_file())
                self.assertNotIn("Caching disabled for task", result.stdout)
                self.assertNotIn("Loading library manifest", result.stdout)
                latest = (evidence / "strict-connected.log").read_text()
                self.assertIn("Caching disabled for task", latest)
                self.assertIn("Loading library manifest", latest)
                self.assertEqual(latest.count("COVERAGE_TASK_OUTCOME :app:connectedDebugAndroidTest"), 1)
                if expected_count == 2:
                    saved = evidence / "attempt-1/app/build/outputs/code_coverage/debugAndroidTest/connected/SYNTHETIC/coverage.ec"
                    self.assertEqual(saved.read_bytes(), b"SYNTHETIC truncated" if scenario == "truncated-then-green" else b"")
                    self.assertTrue(json.loads((evidence / "retry-decision.json").read_text())["retry"])
                    phases = json.loads((evidence / "task-phase-outcomes.json").read_text())
                    self.assertEqual(phases["graphExit"], expected_status)
                    self.assertEqual(phases["inputs"], "SUCCESS" if expected_status == 0 else "FAILED")

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
