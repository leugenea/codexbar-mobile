"""Quick offline M2 contracts. Every runtime-like value here is explicitly synthetic."""
import copy
import itertools
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import m2_coverage as m2

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("RUNNER_TEMP", os.environ.get("M1_CONTRACT_SCRATCH", tempfile.gettempdir())))


class CoverageContracts(unittest.TestCase):
    def setUp(self):
        self.identity = {key: "SYNTHETIC-" + key for key in m2.IDENTITY_KEYS}
        self.context = dict(self.identity, startedMillis=5000)
        self.inventory: dict = {
            "classes": [{"name": "synthetic/Activity", "classId": "a123", "instructions": 100,
                         "path": "app/build/m2-coverage/classes/synthetic/Activity.class", "sha256": "unused"}],
            "datasets": [{"kind": kind, "path": f"synthetic.{ext}", "modifiedMillis": 6000, "bytes": 100,
                          "sessions": [{"startMillis": 5500, "dumpMillis": 6000}],
                          "records": [{"name": "synthetic/Activity", "classId": "a123", "probes": 7}]}
                         for kind, ext in (("jvm", "exec"), ("native", "ec"))],
        }

    def validate(self, context=None, inventory=None):
        return m2.validate_inputs(context or self.context, inventory or self.inventory, 7000, self.identity)

    def test_identity_binds_actual_checkout_pr_head_run_and_attempt_without_receipt(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as temporary:
            event = Path(temporary) / "event.json"
            event.write_text(json.dumps({"pull_request": {"head": {"sha": "SYNTHETIC-source-head"}}}))
            environment = {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_PATH": str(event),
                           "GITHUB_SHA": "SYNTHETIC-event-merge", "GITHUB_RUN_ID": "SYNTHETIC-run",
                           "GITHUB_RUN_ATTEMPT": "2"}
            with patch.dict(os.environ, environment, clear=True), patch.object(
                    m2.subprocess, "check_output", return_value="SYNTHETIC-actual-checkout\n"):
                self.assertEqual(m2.current_identity(), {
                    "checkoutSha": "SYNTHETIC-actual-checkout", "sourceHeadSha": "SYNTHETIC-source-head",
                    "GITHUB_RUN_ID": "SYNTHETIC-run", "GITHUB_RUN_ATTEMPT": "2"})
                event.write_text("{}")
                self.assertEqual(m2.current_identity()["sourceHeadSha"], "SYNTHETIC-event-merge")
                os.environ["GITHUB_RUN_ATTEMPT"] = "3"
                self.assertEqual(m2.current_identity()["GITHUB_RUN_ATTEMPT"], "3")
                event.write_text("invalid JSON")
                with self.assertRaises(json.JSONDecodeError):
                    m2.current_identity()

    def test_hosted_identity_requires_event_and_run_context(self):
        environment = {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_PATH": "SYNTHETIC-unused",
                       "GITHUB_SHA": "SYNTHETIC-head", "GITHUB_RUN_ID": "SYNTHETIC-run",
                       "GITHUB_RUN_ATTEMPT": "1"}
        for key in ("GITHUB_EVENT_PATH", "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
            incomplete = {name: value for name, value in environment.items() if name != key}
            with self.subTest(missing=key), patch.dict(os.environ, incomplete, clear=True), patch.object(
                    m2.subprocess, "check_output", return_value="SYNTHETIC-checkout"), patch.object(
                    Path, "read_text", return_value="{}"), self.assertRaises(KeyError):
                m2.current_identity()

    def test_local_identity_uses_checkout_without_actions_context(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(
                m2.subprocess, "check_output", return_value="SYNTHETIC-local-checkout"):
            self.assertEqual(m2.current_identity(), {
                "checkoutSha": "SYNTHETIC-local-checkout", "sourceHeadSha": "SYNTHETIC-local-checkout",
                "GITHUB_RUN_ID": None, "GITHUB_RUN_ATTEMPT": None})

    def test_compatible_complete_union_inputs(self):
        self.assertEqual(set(self.validate()), {"synthetic/Activity"})

    def test_missing_empty_stale_and_incompatible_inputs_reject(self):
        for mutation in ("missing-jvm", "missing-native", "empty-classes", "duplicate-classes", "zero-denominator",
                         "empty-data", "stale-data", "wrong-class-id", "no-matching-class", "probe-count",
                         "no-sessions", "stale-session", "invalid-session"):
            with self.subTest(mutation=mutation):
                inventory = copy.deepcopy(self.inventory)
                if mutation.startswith("missing-"):
                    inventory["datasets"] = [d for d in inventory["datasets"] if d["kind"] != mutation[8:]]
                elif mutation == "empty-classes":
                    inventory["classes"] = []
                elif mutation == "duplicate-classes":
                    inventory["classes"] *= 2
                elif mutation == "zero-denominator":
                    inventory["classes"][0]["instructions"] = 0
                elif mutation == "empty-data":
                    inventory["datasets"][0]["bytes"] = 0
                elif mutation == "stale-data":
                    inventory["datasets"][0]["modifiedMillis"] = 4999
                elif mutation == "wrong-class-id":
                    inventory["datasets"][0]["records"][0]["classId"] = "bad"
                elif mutation == "no-matching-class":
                    inventory["datasets"][0]["records"] = []
                elif mutation == "probe-count":
                    inventory["datasets"][0]["records"][0]["probes"] = 8
                elif mutation == "no-sessions":
                    inventory["datasets"][0]["sessions"] = []
                elif mutation == "stale-session":
                    inventory["datasets"][0]["sessions"][0]["startMillis"] = 1
                else:
                    inventory["datasets"][0]["sessions"][0]["dumpMillis"] = 1
                with self.assertRaises(ValueError):
                    self.validate(inventory=inventory)

    def test_stale_wrong_sha_run_attempt_and_future_context_reject(self):
        for key in (*m2.IDENTITY_KEYS, "startedMillis"):
            context = dict(self.context)
            context[key] = 8000 if key == "startedMillis" else "SYNTHETIC-other"
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate(context=context)

    def report(self, covered=90, missed=10):
        root = ET.Element("report", name="SYNTHETIC")
        package = ET.SubElement(root, "package", name="synthetic")
        cls = ET.SubElement(package, "class", name="synthetic/Activity")
        for element in (cls, root):
            ET.SubElement(element, "counter", type="INSTRUCTION", covered=str(covered), missed=str(missed))
        return root

    def test_exact_90_instruction_ratio_accepts(self):
        result = m2.instruction_counts(ET.tostring(self.report()), self.validate())
        self.assertEqual(result, {"covered": 90, "missed": 10, "ratio": 0.9})

    def test_low_zero_negative_missing_extra_or_changed_denominator_reject(self):
        for mutation in ("low", "zero", "negative", "missing-class", "extra-class", "missing-counter",
                         "duplicate-counter", "wrong-counter", "changed-denominator", "wrong-total"):
            root = self.report()
            cls = root.find("./package/class")
            package = root.find("package")
            counter = root.find("counter")
            assert cls is not None and package is not None and counter is not None
            if mutation in ("low", "zero", "negative", "changed-denominator"):
                counters = {"low": (89, 11), "zero": (0, 0), "negative": (-1, 101), "changed-denominator": (99, 1)}
                root = self.report(*counters[mutation])
                if mutation == "changed-denominator":
                    for counter in root.findall(".//counter"):
                        counter.set("covered", "100")
            elif mutation == "missing-class":
                root.remove(package)
            elif mutation == "extra-class":
                extra = copy.deepcopy(cls)
                extra.set("name", "synthetic/extra")
                package.append(extra)
            elif mutation == "missing-counter":
                root.remove(counter)
            elif mutation == "duplicate-counter":
                root.append(copy.deepcopy(counter))
            elif mutation == "wrong-counter":
                counter.set("type", "LINE")
            else:
                counter.set("covered", "91")
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                m2.instruction_counts(ET.tostring(root), self.validate())

    def test_zero_instruction_intrinsic_filter_is_inventoried_not_excluded(self):
        expected = self.validate()
        expected["synthetic/Filtered"] = {"instructions": 0}
        root = self.report()
        package = root.find("package")
        assert package is not None
        ET.SubElement(package, "class", name="synthetic/Filtered")
        m2.instruction_counts(ET.tostring(root), expected)
        with self.assertRaisesRegex(ValueError, "Report class inventory mismatch"):
            m2.instruction_counts(ET.tostring(self.report()), expected)

    def test_file_hash_changes_and_stale_report_fail_closed(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as temporary:
            previous = Path.cwd()
            try:
                os.chdir(temporary)
                inventory = copy.deepcopy(self.inventory)
                inventory["context"] = self.context
                for entry in inventory["classes"] + inventory["datasets"]:
                    path = Path(entry["path"])
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("SYNTHETIC bytes")
                inventory["files"] = {entry["path"]: m2.digest(entry["path"])
                                      for entry in inventory["classes"] + inventory["datasets"]}
                m2.write_json(m2.COVERAGE / "validated-inputs.json", inventory)
                report = m2.BUILD / "reports/jacoco/jacocoDebugReport/jacocoDebugReport.xml"
                report.parent.mkdir(parents=True)
                report.write_bytes(ET.tostring(self.report()))
                with patch.object(m2, "current_identity", return_value=self.identity):
                    m2.check_report()
                    os.utime(report, ns=(1, 1))
                    with self.assertRaisesRegex(ValueError, "Stale coverage report"):
                        m2.check_report()
                    report.write_bytes(ET.tostring(self.report()))
                    Path(inventory["classes"][0]["path"]).write_text("SYNTHETIC changed compilation")
                    with self.assertRaisesRegex(ValueError, "changed after class-ID validation"):
                        m2.check_report()
            finally:
                os.chdir(previous)

    def test_terminal_native_success_and_later_coverage_failure_stay_separate(self):
        log = "\n".join(f"M2_TASK_OUTCOME :app:{task} " + ("FAILED" if phase == "verification" else "SUCCESS")
                        for phase, task in m2.PHASES.items())
        result = m2.phase_outcomes(log)
        self.assertEqual(result["instrumentation"], "SUCCESS")
        self.assertEqual(result["jvm"], "SUCCESS")
        self.assertEqual(result["report"], "SUCCESS")
        self.assertEqual(result["verification"], "FAILED")
        self.assertEqual(set(m2.phase_outcomes(":app:testDebugUnitTest started").values()), {"NOT_RUN"})
        with self.assertRaisesRegex(ValueError, "Duplicate task"):
            m2.phase_outcomes(log + "\n" + log)

    def test_result_truth_table_all_states(self):
        workflow = (ROOT / ".github/workflows/m1-toolchain.yml").read_text()
        result_body = workflow.split("  result:\n", 1)[1]
        shell = result_body.split("        run: |\n", 1)[1]
        states = ("success", "failure", "skipped", "cancelled", "", "unknown")
        for checkpoint, native in itertools.product(states, repeat=2):
            needs = {"checkpoint": {"result": checkpoint}, "native": {"result": native}}
            observed = subprocess.run(["bash", "-euo", "pipefail", "-c", shell],
                                      env=dict(os.environ, CHECKPOINT_RESULT=checkpoint, NATIVE_RESULT=native),
                                      capture_output=True, timeout=5).returncode
            self.assertEqual(observed == 0, checkpoint == native == "success")
            with self.subTest(checkpoint=checkpoint, native=native):
                if checkpoint == native == "success":
                    m2.result_truth_table(needs)
                else:
                    with self.assertRaises(ValueError):
                        m2.result_truth_table(needs)
        for malformed in ({}, {"checkpoint": {"result": "success"}},
                          {"checkpoint": {"result": "success"}, "native": {}, "extra": {"result": "success"}}):
            with self.assertRaises(ValueError):
                m2.result_truth_table(malformed)

    def test_scoped_full_production_wiring_and_always_running_result(self):
        build = (ROOT / "app/build.gradle").read_text()
        for seam in ("ScopedArtifacts.Scope.PROJECT", "ScopedArtifact.CLASSES.INSTANCE", "classJars.get()",
                     "classDirs.get()", "ExecutionDataStore", "CoverageBuilder", "ExecFileLoader", "coverage.id",
                     "testCoverage { jacocoVersion = '0.8.15' }", "enableUnitTestCoverage = true",
                     "enableAndroidTestCoverage = true", "counter = 'INSTRUCTION'", "value = 'COVEREDRATIO'",
                     "minimum = 0.90", "xml.required.set(true)", "html.required.set(true)",
                     "M2_TASK_OUTCOME", "m2PrepareDebugCoverage", "Incompatible JaCoCo tooling"):
            self.assertIn(seam, build)
        self.assertIn("Copyright (c) 2026 QMix contributors", build)
        self.assertIn("The above copyright notice and this permission notice shall be included", build)
        self.assertIn('THE SOFTWARE IS PROVIDED "AS IS"', build)
        self.assertNotIn("tmp/kotlin-classes", build)
        self.assertNotIn("exclude(", build)
        for forbidden in ("ComposableSingletons", "*Lambda*", "*Activity*", "**/io/github/"):
            self.assertNotIn(forbidden, build)
        workflow = (ROOT / ".github/workflows/m1-toolchain.yml").read_text()
        result = workflow.split("  result:\n", 1)[1]
        for contract in ("name: M2 Android result", "if: always()", "needs: [checkpoint, native]",
                         "CHECKPOINT_RESULT: ${{ needs.checkpoint.result }}", "NATIVE_RESULT: ${{ needs.native.result }}",
                         '[[ "$CHECKPOINT_RESULT" == success && "$NATIVE_RESULT" == success ]]' ):
            self.assertIn(contract, result)
        names = []
        for path in (ROOT / ".github/workflows").iterdir():
            if path.suffix in (".yaml", ".yml"):
                names.extend(re.findall(r"^    name: (.+)$", path.read_text(), re.M))
        self.assertEqual(names.count("M2 Android result"), 1)
        for path in ("app/build/outputs/unit_test_code_coverage/", "app/build/outputs/code_coverage/",
                     "app/build/m2-coverage/", "app/build/test-results/", "app/build/outputs/androidTest-results/"):
            self.assertIn(path, workflow.split("  native:\n")[1])
        self.assertNotIn("paths:", workflow.split("jobs:")[0])
        script = (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text()
        self.assertEqual(script.count("./gradlew "), 1)
        self.assertIn("m2_coverage.py phases --exit", script)
        self.assertIn('exit "$test_status"', script)
        for suite in ("jvm", "native"):
            self.assertIn("verify_m1_test_reports.py " + suite, script)


if __name__ == "__main__":
    unittest.main()
