"""Synthetic navigation capture controls; not Android runtime evidence."""
import json
import unittest

import test_history_text_contract as text_contract
from verify_history_text_captures import CAPTURES, INTEGRATED_CAPTURES, verify_captures
from verify_history_text_captures import ASSEMBLED_CAPTURES


class HistoryNavigationContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_two_production_entry_images_extend_not_replace_the_ten_component_images(self):
        self.assertEqual(len(CAPTURES), 10 + len(INTEGRATED_CAPTURES) + len(ASSEMBLED_CAPTURES))
        for name in INTEGRATED_CAPTURES:
            self.fixture.captures()
            self.assertEqual(len(verify_captures(self.directory)), 12 + len(ASSEMBLED_CAPTURES))
            (self.directory / (name + ".png")).unlink()
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_integrated_capture_requires_real_entry_bounded_page_dormancy_and_reachable_controls(self):
        mutations = (("productionEntry", False), ("productionEntry", 1), ("activity", "fixture"),
                     ("phase", "RESTORED"), ("queryKind", "WEEKLY"), ("queryLimit", 257),
                     ("queryAfter", 32), ("pageEntries", True), ("firstOrdinal", 0),
                     ("lastOrdinal", 256), ("hasMore", False), ("gets", 3), ("requests", 6),
                     ("visibleControls", ["connection-tab"]), ("visibleRoots", None), ("visibleControls", None), ("visibleRoots", []), ("visibleRoots", ["offline-tab"]))
        for name in INTEGRATED_CAPTURES:
            for key, value in mutations:
                self.fixture.captures()
                path = self.directory / (name + ".json")
                data = json.loads(path.read_text())
                data[key] = not data["hasMore"] if key == "hasMore" else value
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

    def test_integrated_canvas_rejects_partial_visibility_even_with_contained_clipped_bounds(self):
        mutations = (("canvasTop", -1), ("canvasClippedTop", 0.5),
                     ("canvasClippedLeft", None), ("canvasClippedRight", float("nan")),
                     ("canvasClippedBottom", True))
        for key, value in mutations:
            self.fixture.captures()
            path = self.directory / "history-integrated-chart-portrait-light.json"
            data = json.loads(path.read_text())
            data[key] = value
            path.write_text(json.dumps(data))
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_full_unclipped_canvas_passes_without_changing_inherited_component_receipts(self):
        self.fixture.captures()
        captures = verify_captures(self.directory)
        self.assertEqual(len(captures), 12 + len(ASSEMBLED_CAPTURES))
        inherited = [item for item in captures if item["name"] not in INTEGRATED_CAPTURES and item["name"] not in ASSEMBLED_CAPTURES]
        self.assertEqual(len(inherited), 10)
        self.assertTrue(all("canvasClippedLeft" not in item for item in inherited))


if __name__ == "__main__":
    unittest.main()
