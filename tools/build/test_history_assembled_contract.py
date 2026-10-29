"""Synthetic saved-pixel, clipping and accessibility controls; not visual approval."""
import copy
import json
import unittest

import test_history_text_contract as text_contract
from verify_history_text_captures import capture_receipt


class HistoryAssembledContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory
        self.fixture.captures()

    def test_real_entry_configuration_controls_and_full_canvas_are_required(self):
        for name in ("history-integrated-chart-portrait-light", "history-assembled-single-portrait-dark"):
            path = self.directory / (name + ".png")
            sidecar = path.with_suffix(".json")
            original = json.loads(sidecar.read_text())
            for key, value in (("productionEntry", False), ("visibleRoots", []), ("visibleControls", None),
                               ("route", "WRONG"), ("canvasLeft", -1), ("canvasTop", float("nan")),
                               ("canvasClippedLeft", .5), ("canvasClippedBottom", True)):
                sidecar.write_text(json.dumps(dict(original, **{key: value})))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    capture_receipt(path)
            sidecar.write_text(json.dumps(original))

    def test_saved_pixels_not_metadata_alone_prove_registered_paint_and_dash_gaps(self):
        path = self.directory / "history-assembled-single-portrait-dark.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("actualArgb", -1), ("matched", False), ("x", True), ("y", 9999)):
            changed = copy.deepcopy(original)
            changed["pixelSamples"][0][key] = value
            sidecar.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)
        # Mutually agreeing all-painted receipts must still prove unpainted gaps.
        changed = copy.deepcopy(original)
        for sample in changed["pixelSamples"]:
            if sample["kind"] == "dash":
                sample.update(targetArgb=-16777216, matched=True)
        sidecar.write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, "dash gaps"):
            capture_receipt(path)
        sidecar.write_text(json.dumps(original))
        path.write_bytes(self.fixture.png(2, 3, pixels=b"\xff" * 18))
        with self.assertRaisesRegex(ValueError, "decoded PNG pixels"):
            capture_receipt(path)

    def test_compact_legend_must_remain_complete_accessible_and_not_visible_copy(self):
        path = self.directory / "history-assembled-single-portrait-dark.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("legendDescription", "missing explanation"), ("visibleLegendCount", 1),
                           ("hiddenFromAccessibility", True)):
            changed = copy.deepcopy(original)
            changed["compactLegend"][key] = value
            sidecar.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)
