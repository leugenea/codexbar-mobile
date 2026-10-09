"""Chart parser/source contract controls; synthetic metadata is NOT native visual evidence."""
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
from verify_history_text_captures import CAPTURES, CHART_CAPTURES, CHART_COUNTS, verify_captures
from verify_test_reports import HISTORY_CHART_CASES, HISTORY_CHART_CLASS, verify_reports

ROOT = Path(__file__).resolve().parents[2]


class HistoryChartContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_every_chart_native_identity_is_required_on_top_of_all_85_inherited(self):
        source = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryChartComponentTest.kt"
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source.read_text())), HISTORY_CHART_CASES)
        self.fixture.report()
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 85 + len(HISTORY_CHART_CASES))
        for name in HISTORY_CHART_CASES:
            self.fixture.report(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native history chart"):
                verify_reports(self.directory, "native")

    def test_each_chart_case_rejects_skip_failure_error_and_class_spoof(self):
        for name in HISTORY_CHART_CASES:
            for mutation in ("skipped", "failure", "error", "wrong-class"):
                path = self.fixture.report()
                tree = ET.parse(path)
                case = next(case for case in tree.getroot().iter("testcase") if case.get("name") == name)
                if mutation == "wrong-class":
                    case.set("classname", HISTORY_CHART_CLASS + ".wrong")
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")

    def test_collector_preserves_four_text_images_and_requires_six_actual_chart_images(self):
        self.assertEqual(set(CAPTURES) - set(CHART_CAPTURES), {
            "history-measured-portrait-light", "history-exact-measurement-portrait-light",
            "history-gap-landscape-dark", "history-unknown-large-font"})
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryChartComponentTest.kt").read_text()
        self.assertEqual(set(re.findall(r'capture\("(history-chart-[^"]+)"', source)), set(CHART_CAPTURES))
        self.assertEqual(set(CHART_COUNTS), set(CHART_CAPTURES))
        for name in CHART_CAPTURES:
            self.fixture.captures()
            self.assertEqual(len(verify_captures(self.directory)), 10)
            (self.directory / (name + ".png")).unlink()
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_chart_capture_rejects_false_pixels_counts_nonfinite_or_outside_bounds(self):
        mutations = (("pixelOraclePassed", False), ("pixelOraclePassed", 1),
                     ("markerCount", 99), ("markerCount", True), ("connectionCount", None),
                     ("referenceCount", -1), ("canvasLeft", -0.1), ("canvasTop", -0.1),
                     ("canvasRight", 4), ("canvasBottom", 4), ("canvasTop", float("nan")),
                     ("canvasLeft", "0"), ("canvasBottom", None), ("canvasRight", 0.1))
        for name in CHART_CAPTURES:
            for key, value in mutations:
                self.fixture.captures()
                path = self.directory / (name + ".json")
                data = json.loads(path.read_text())
                data[key] = value
                path.write_text(json.dumps(data))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    verify_captures(self.directory)

    def test_chart_counts_are_quantity_neutral_and_no_dependency_or_coverage_exemption_exists(self):
        resources = {item.get("name"): item.text for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        self.assertEqual(resources["history_chart_counts"],
                         "Drawn markers: %1$d · Within-run connections: %2$d · Nominal reference lines: %3$d")
        self.assertNotRegex(resources["history_chart_counts"], r"%\d+\$d\s+[A-Za-z]")
        build = (ROOT / "app/build.gradle").read_text()
        self.assertIn("minimum = 0.90", build)
        self.assertNotRegex(build, r"exclude.*HistoryChart|chart.*[Dd]ependenc")


if __name__ == "__main__":
    unittest.main()
