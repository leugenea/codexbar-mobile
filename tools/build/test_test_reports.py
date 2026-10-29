"""Small independent JUnit parser/CLI fixtures; no application testcase catalogue."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_test_reports import verify_reports

ROOT = Path(__file__).resolve().parents[2]
PASSING = '<testsuite tests="1"><testcase classname="fixture.Checks" name="passes"/></testsuite>'


class ReportTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=os.environ.get("BUILD_CONTRACT_SCRATCH"))
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.report = self.directory / "results.xml"

    def test_nested_and_multiple_reports_preserve_receipt_fields(self):
        self.report.write_text('<testsuites><testsuites>' + PASSING + '</testsuites></testsuites>')
        second = self.directory / "nested" / "second.xml"
        second.parent.mkdir()
        second.write_text(PASSING.replace('name="passes"', 'name="another"'))
        paths = sorted((str(self.report), str(second)))
        for kind in ("jvm", "native"):
            self.assertEqual(verify_reports(self.directory, kind), {
                "kind": kind, "testCount": 2, "reports": paths,
                "testcases": [{"class": "fixture.Checks", "name": "another" if path == str(second) else "passes",
                               "report": path} for path in paths],
            })

    def test_missing_empty_non_junit_and_malformed_reports_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "No native JUnit XML"):
            verify_reports(self.directory, "native")
        for text in ('<testsuite tests="12"/>', '<testsuites/>', '<unrelated/>', '', '<testsuite>',
                     PASSING.replace('tests="1"', 'errors="invalid"')):
            self.report.write_text(text)
            with self.subTest(xml=text), self.assertRaises(ValueError):
                verify_reports(self.directory, "native")
        self.report.write_text(PASSING)
        (self.directory / "broken.xml").write_text('<testsuite>')
        with self.assertRaisesRegex(ValueError, "malformed JUnit XML"):
            verify_reports(self.directory, "native")

    def test_suite_and_container_failure_counters_fail_closed(self):
        for tag in ("testsuite", "testsuites"):
            for attr in ("failures", "errors", "skipped"):
                root = ET.fromstring(PASSING) if tag == "testsuite" else ET.fromstring('<testsuites>' + PASSING + '</testsuites>')
                root.set(attr, "1")
                self.report.write_bytes(ET.tostring(root))
                with self.subTest(tag=tag, attr=attr), self.assertRaisesRegex(ValueError, "nonzero"):
                    verify_reports(self.directory, "jvm")

    def test_testcase_and_container_failure_nodes_fail_closed(self):
        for parent in ("testcase", "testsuite", "testsuites"):
            for failure in ("failure", "error", "skipped"):
                root = ET.fromstring('<testsuites>' + PASSING + '</testsuites>')
                ET.SubElement(next(root.iter(parent)), failure)
                self.report.write_bytes(ET.tostring(root))
                with self.subTest(parent=parent, failure=failure), self.assertRaisesRegex(ValueError, "failed/errored/skipped"):
                    verify_reports(self.directory, "jvm")

    def test_cli_writes_compatible_receipts_and_rejects_bad_reports(self):
        script = self.directory / "tools/build/verify_test_reports.py"
        script.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / "tools/build/verify_test_reports.py", script)
        for kind, relative in (("jvm", "test-results/testDebugUnitTest"),
                               ("native", "outputs/androidTest-results/connected")):
            directory = self.directory / "app/build" / relative
            directory.mkdir(parents=True)
            report = directory / "results.xml"
            report.write_text(PASSING)
            command = [sys.executable, "-B", str(script), kind]
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            receipt = self.directory / "evidence" / kind / "executed-tests.json"
            self.assertEqual(json.loads(receipt.read_text()), verify_reports(directory, kind))
            self.assertIn("1 executed tests; no failures/errors/skips", result.stdout)
            receipt.unlink()
            report.write_text('<testsuite>')
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(receipt.exists())
