"""Synthetic issue-134 registry/capture controls; no Android application execution."""
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
import test_workflow_contract
from verify_test_reports import CURRENT_HISTORY_JVM, CURRENT_HISTORY_NATIVE, verify_reports
from verify_history_text_captures import ASSEMBLED_CAPTURES, verify_captures

ROOT = Path(__file__).resolve().parents[2]


class CurrentHistoryContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_exact_current_jvm_native_identities_are_required_once_without_inherited_removal(self):
        for kind, registry, source_set in (("jvm", CURRENT_HISTORY_JVM, "test"), ("native", CURRENT_HISTORY_NATIVE, "androidTest")):
            writer = test_workflow_contract.SyntheticReportTests()
            writer.directory = self.directory
            for classname, required in registry.items():
                source = ROOT / f"app/src/{source_set}/java/{classname.replace('.', '/')}.kt"
                self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source.read_text())), required)
                for name in required:
                    for mutation in ("missing", "wrong-class", "duplicate", "failure", "error", "skipped"):
                        path = writer.write_report(kind)
                        tree = ET.parse(path)
                        suite = tree.getroot()
                        case = next(case for case in suite if case.get("classname") == classname and case.get("name") == name)
                        if mutation == "missing":
                            suite.remove(case)
                        elif mutation == "wrong-class":
                            case.set("classname", "SYNTHETIC-wrong-class")
                        elif mutation == "duplicate":
                            ET.SubElement(suite, "testcase", **case.attrib)
                        else:
                            ET.SubElement(case, mutation)
                        tree.write(path)
                        with self.subTest(kind=kind, name=name, mutation=mutation), self.assertRaises(ValueError):
                            verify_reports(self.directory, kind)

    def test_current_paint_targets_and_exact_period_selection_fail_closed(self):
        for name in (name for name in ASSEMBLED_CAPTURES if name.startswith("history-current-")):
            for key, value in (("currentOnly", False), ("currentReset", "2027-01-15T09:00:01Z"),
                               ("currentDuration", 604800), ("selectedOrdinals", [1, 2, 3, 4, 5]), ("lastAdmitted", 306)):
                self.fixture.captures()
                path = self.directory / (name + ".json")
                data = json.loads(path.read_text())
                data[key] = value
                path.write_text(json.dumps(data))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    verify_captures(self.directory)
            for role in ("observation", "edge", "dash", "bridge"):
                self.fixture.captures()
                path = self.directory / (name + ".json")
                data = json.loads(path.read_text())
                sample = next(item for item in data["pixelSamples"] if item["kind"] == role)
                sample["targetArgb"] = sample["actualArgb"]
                # Even a mutually agreeing receipt/PNG cannot choose its own registered role target.
                if sample["targetArgb"] != -1:
                    sample["targetArgb"] = -1
                path.write_text(json.dumps(data))
                with self.subTest(name=name, role=role), self.assertRaises(ValueError):
                    verify_captures(self.directory)


if __name__ == "__main__":
    unittest.main()
