"""Synthetic complete-gallery and live paint controls; no native execution."""
import copy
import json
import unittest

import test_history_text_contract as text_contract
from test_dashboard_contract import dashboard_captures
from verify_history_text_captures import LIVE_CAPTURES, capture_receipt, verify_gallery


class LiveUsageVisualContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory
        self.fixture.captures()
        for name, dark in LIVE_CAPTURES.items():
            samples = [dict(kind=kind, x=1, y=1, targetArgb=-16777216, actualArgb=-16777216,
                            matched=True, bounds=[0, 0, 2, 3])
                       for kind in ("background", "card", "content", "progress", "track")]
            data = dict(name=name, screen="live-usage", route="CONNECTION_DETAILS", synthetic=True, productionEntry=True,
                        activity="io.github.leugenea.codexbarmobile.MainActivity", phase="OBSERVED",
                        api=36, orientation=1, dark=dark, fontScale=1.0, layoutFontScale=1.0,
                        density=1, layoutDensity=1, renderedLocale="en-US", width=2, height=3,
                        visibleFacts={"live-five-hour-percent": "12.5% used", "live-weekly-percent": "76.25% used",
                                      "live-five-hour-reset-absolute": "Reset: Oct 8, 2026, 01:00",
                                      "live-five-hour-reset-relative": "Reset: 1 hour 30 minutes remaining",
                                      "live-weekly-reset-absolute": "Reset: Oct 10, 2026, 01:00",
                                      "live-weekly-reset-relative": "Reset: 2 days 1 hour remaining"},
                        visibleControls=[], visibleRoots=["connection-screen"], pixelSamples=samples, pixelOraclePassed=True)
            (self.directory / (name + ".png")).write_bytes(self.fixture.png(2, 3))
            (self.directory / (name + ".json")).write_text(json.dumps(data))
        dashboard_captures(self.directory, self.fixture)

    def test_complete_gallery_requires_every_image_once_with_consistent_density(self):
        self.assertEqual(len(verify_gallery(self.directory)), 28)
        for path in sorted(self.directory.glob("*.png")):
            data = path.read_bytes()
            path.unlink()
            with self.subTest(missing=path.name), self.assertRaises(ValueError):
                verify_gallery(self.directory)
            path.write_bytes(data)
        path = self.directory / "history-measured-portrait-light.json"
        data = json.loads(path.read_text())
        path.write_text(json.dumps(dict(data, density=2)))
        with self.assertRaisesRegex(ValueError, "density changed"):
            verify_gallery(self.directory)

    def test_live_facts_bounds_and_saved_pixels_are_not_filename_receipts(self):
        path = self.directory / "live-usage-portrait-light.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("renderedLocale", "de-DE"), ("layoutDensity", 2), ("visibleRoots", []), ("pixelSamples", [])):
            sidecar.write_text(json.dumps(dict(original, **{key: value})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)
        changed = copy.deepcopy(original)
        changed["visibleFacts"]["live-five-hour-reset-relative"] = "Reset: 1 hour remaining"
        sidecar.write_text(json.dumps(changed))
        with self.assertRaises(ValueError):
            capture_receipt(path)
        for key, value in (("actualArgb", -1), ("bounds", [0, 0, 1, 1])):
            changed = copy.deepcopy(original)
            changed["pixelSamples"][0][key] = value
            sidecar.write_text(json.dumps(changed))
            with self.subTest(sample=key), self.assertRaises(ValueError):
                capture_receipt(path)
        sidecar.write_text(json.dumps(original))
        path.write_bytes(self.fixture.png(2, 3, pixels=b"\xff" * 18))
        with self.assertRaisesRegex(ValueError, "decoded PNG pixels"):
            capture_receipt(path)
