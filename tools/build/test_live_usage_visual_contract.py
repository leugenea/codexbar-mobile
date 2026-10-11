"""Synthetic child130 collector/report controls only; no application execution."""
import copy
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

from test_history_text_contract import HistoryTextContracts
from test_native_coverage_retry import RetryContracts, synthetic_reports
from verify_test_reports import LIVE_USAGE_CLASS, LIVE_USAGE_CASES, verify_reports
from verify_history_text_captures import LIVE_CAPTURES, LIVE_FACTS, live_capture_receipt, verify_gallery

ROOT = Path(__file__).resolve().parents[2]
NEW_CASES = {"productionLiveLightPaintAndExactUsageResetCapture", "productionLiveDarkPaintAndLargeFontRemainReadable"}


class LiveUsageVisualContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def captures(self):
        self.fixture.captures()
        for name, dark in LIVE_CAPTURES.items():
            samples = [dict(kind=kind, x=1, y=1, targetArgb=-16777216, actualArgb=-16777216,
                            matched=True, bounds=[0, 0, 2, 3])
                       for kind in ("background", "card", "content", "progress", "track")]
            data = dict(name=name, screen="live-usage", synthetic=True, productionEntry=True,
                        activity=LIVE_USAGE_CLASS.replace("LiveUsageScreenTest", "MainActivity"), phase="OBSERVED",
                        api=36, orientation=1, dark=dark, fontScale=1.0, layoutFontScale=1.0,
                        density=1, layoutDensity=1, renderedLocale="en-US", width=2, height=3,
                        visibleFacts=LIVE_FACTS, visibleControls=[], visibleRoots=["connection-screen"], pixelSamples=samples, pixelOraclePassed=True)
            (self.directory / (name + ".png")).write_bytes(self.fixture.png(2, 3))
            (self.directory / (name + ".json")).write_text(json.dumps(data))

    def test_new_cases_are_registered_and_keep_all_inherited_cases(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/LiveUsageScreenTest.kt").read_text()
        self.assertTrue(NEW_CASES <= LIVE_USAGE_CASES)
        self.assertEqual(len(LIVE_USAGE_CASES), 8 + len(NEW_CASES))
        self.assertTrue(NEW_CASES <= set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)))
        self.assertEqual(set(re.findall(r'"(live-usage-portrait-[^"]+)"', source)), set(LIVE_CAPTURES))

    def test_new_native_cases_fail_closed_in_reports_and_retry(self):
        retry_fixture = RetryContracts()
        retry_fixture.setUp()
        self.addCleanup(retry_fixture.doCleanups)
        self.assertEqual(retry_fixture.require(), "empty")
        for name in NEW_CASES:
            for mutation in ("missing", "failure", "error", "skipped", "wrong-class"):
                build = retry_fixture.build
                synthetic_reports(build)
                path = build / "outputs/androidTest-results/connected/SYNTHETIC.xml"
                tree = ET.parse(path)
                root = tree.getroot()
                case = next(item for item in root if item.get("name") == name)
                if mutation == "missing":
                    root.remove(case)
                elif mutation == "wrong-class":
                    case.set("classname", "SYNTHETIC-wrong-class")
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation):
                    with self.assertRaises(ValueError):
                        verify_reports(path.parent, "native")
                    with self.assertRaises(ValueError):
                        retry_fixture.require()

    def test_gallery_preserves_17_history_images_and_requires_both_live_images(self):
        self.captures()
        self.assertEqual(len(verify_gallery(self.directory)), 21)
        for name in LIVE_CAPTURES:
            self.captures()
            (self.directory / (name + ".png")).unlink()
            with self.assertRaises(ValueError):
                verify_gallery(self.directory)

    def test_effective_screen_locale_layout_and_paint_are_not_filename_receipts(self):
        for name in LIVE_CAPTURES:
            self.captures()
            path = self.directory / (name + ".json")
            data = json.loads(path.read_text())
            for key, value in (("screen", "history"), ("renderedLocale", "de-DE"), ("api", 35),
                               ("dark", not data["dark"]), ("fontScale", 2), ("density", 2),
                               ("layoutFontScale", True), ("orientation", 2), ("productionEntry", False),
                               ("phase", "RESTORED"), ("visibleRoots", None), ("visibleControls", None), ("visibleRoots", []), ("visibleRoots", ["offline-tab"]), ("visibleControls", ["connection-tab"]), ("visibleFacts", {}), ("pixelSamples", []),
                               ("pixelOraclePassed", False)):
                path.write_text(json.dumps(dict(data, **{key: value})))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    verify_gallery(self.directory)
            for key, value in (("actualArgb", -1), ("targetArgb", -1), ("matched", False), ("x", True),
                               ("bounds", [0, 0, 1, 1]), ("bounds", [-1, 0, 2, 3]), ("kind", "invented")):
                changed = copy.deepcopy(data)
                changed["pixelSamples"][0][key] = value
                path.write_text(json.dumps(changed))
                with self.subTest(name=name, sample=key), self.assertRaises(ValueError):
                    live_capture_receipt(path.with_suffix(".png"))
            path.write_text(json.dumps(data))
            (self.directory / (name + ".png")).write_bytes(self.fixture.png(2, 3, filtered=(b"\0" + b"\xff" * 6) * 3))
            with self.assertRaises(ValueError):
                live_capture_receipt(path.with_suffix(".png"))
