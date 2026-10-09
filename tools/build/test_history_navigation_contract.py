"""Synthetic parser/source controls for production navigation; not Android runtime evidence."""
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
from verify_history_text_captures import CAPTURES, INTEGRATED_CAPTURES, verify_captures
from verify_test_reports import HISTORY_NAVIGATION_CASES, HISTORY_NAVIGATION_CLASS, verify_reports

ROOT = Path(__file__).resolve().parents[2]


class HistoryNavigationContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_all_basic_integrated_native_cases_extend_the_93_inherited(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryNavigationTest.kt").read_text()
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)), HISTORY_NAVIGATION_CASES)
        self.fixture.report()
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 93 + len(HISTORY_NAVIGATION_CASES))
        for name in HISTORY_NAVIGATION_CASES:
            self.fixture.report(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native history navigation"):
                verify_reports(self.directory, "native")

    def test_every_integrated_case_fails_closed_on_skip_error_failure_or_class_spoof(self):
        for name in HISTORY_NAVIGATION_CASES:
            for mutation in ("skipped", "error", "failure", "wrong-class"):
                path = self.fixture.report()
                tree = ET.parse(path)
                case = next(case for case in tree.getroot().iter("testcase") if case.get("name") == name)
                if mutation == "wrong-class":
                    case.set("classname", HISTORY_NAVIGATION_CLASS + ".wrong")
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")

    def test_two_production_entry_images_extend_not_replace_the_ten_component_images(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryNavigationTest.kt").read_text()
        self.assertEqual(set(re.findall(r'capture\.capture\("(history-integrated-[^"]+)"', source)), set(INTEGRATED_CAPTURES))
        self.assertEqual(len(CAPTURES), 10 + len(INTEGRATED_CAPTURES))
        for name in INTEGRATED_CAPTURES:
            self.fixture.captures()
            self.assertEqual(len(verify_captures(self.directory)), 12)
            (self.directory / (name + ".png")).unlink()
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_integrated_capture_requires_real_entry_bounded_page_dormancy_and_reachable_controls(self):
        mutations = (("productionEntry", False), ("productionEntry", 1), ("activity", "fixture"),
                     ("phase", "OBSERVED"), ("queryKind", "WEEKLY"), ("queryLimit", 257),
                     ("queryAfter", 32), ("pageEntries", True), ("firstOrdinal", 0),
                     ("lastOrdinal", 256), ("hasMore", False), ("gets", 3), ("requests", 6),
                     ("visibleControls", []))
        for name in INTEGRATED_CAPTURES:
            for key, value in mutations:
                self.fixture.captures()
                path = self.directory / (name + ".json")
                data = json.loads(path.read_text())
                data[key] = value
                path.write_text(json.dumps(data))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    verify_captures(self.directory)

    def test_integrated_canvas_must_be_finite_and_within_actual_capture(self):
        for key, value in (("canvasLeft", -1), ("canvasTop", float("nan")), ("canvasRight", 100), ("canvasBottom", None)):
            self.fixture.captures()
            path = self.directory / "history-integrated-chart-portrait-light.json"
            data = json.loads(path.read_text())
            data[key] = value
            path.write_text(json.dumps(data))
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_navigation_uses_only_the_existing_owner_query_and_does_not_save_facts(self):
        main = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"
        source = (main / "ConnectionHistory.kt").read_text()
        self.assertIn("controller.historySnapshots.collectAsState()", source)
        self.assertIn("controller.queryHistory(query)", source)
        self.assertNotRegex(source, r"rememberSaveable|readUsage|usageForeground|SQLiteHistory|ConnectionController\(")
        native = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryNavigationTest.kt").read_text()
        self.assertNotRegex(native, r"setContent|runBlocking|Thread\.sleep|delay\(")
        self.assertIn("compose.waitUntil", native)
        resources = {item.get("name"): item.text or "" for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        self.assertEqual(resources["history_page_cursor"], "Exclusive admission cursor: %1$d · Page limit: %2$d")
        self.assertNotRegex(resources["history_page_cursor"], r"%\d+\$d\s+[A-Za-z]")


if __name__ == "__main__":
    unittest.main()
