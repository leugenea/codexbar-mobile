"""Explicitly synthetic assembled-capture controls; no application/native execution."""
import copy
import json
import unittest

import test_history_text_contract as text_contract
from verify_history_text_captures import (ASSEMBLED_CAPTURES, ASSEMBLED_COUNTS, CAPTURES, decoded_samples,
                                         paeth, verify_captures)


class HistoryAssembledContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_five_representative_images_extend_all_twelve_inherited_and_are_each_required(self):
        self.assertEqual(len(CAPTURES), 12 + len(ASSEMBLED_CAPTURES))
        for name in ASSEMBLED_CAPTURES:
            self.fixture.captures()
            self.assertEqual(len(verify_captures(self.directory)), len(CAPTURES))
            (self.directory / (name + ".png")).unlink()
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def mutate(self, name, key, value):
        self.fixture.captures()
        path = self.directory / (name + ".json")
        data = json.loads(path.read_text())
        data[key] = value
        path.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            verify_captures(self.directory)

    def test_configuration_owner_cursor_and_counts_fail_closed_for_every_new_capture(self):
        for name in ASSEMBLED_CAPTURES:
            for key, value in (("api", 35), ("fontScale", 3), ("layoutFontScale", True), ("density", float("nan")),
                               ("layoutDensity", 2), ("orientation", True), ("productionEntry", False), ("activity", "fixture"),
                               ("queryLimit", 257), ("queryAfter", -1), ("pageEntries", True), ("phase", "FAILED"),
                               ("readiness", "ERROR"), ("hasMore", True), ("requests", 99), ("gets", 99),
                               ("uiCursor", "newest"), ("visibleControls", ["connection-tab"]), ("visibleRoots", None), ("visibleControls", None), ("visibleRoots", []), ("visibleRoots", ["offline-tab"])):
                with self.subTest(name=name, key=key):
                    self.mutate(name, key, value)

    def test_dashboard_chart_captures_require_exact_accessible_plot_legend_without_visible_text(self):
        for name in ASSEMBLED_COUNTS:
            self.fixture.captures()
            path = self.directory / (name + ".json")
            data = json.loads(path.read_text())
            receipt = data["compactLegend"]
            mutations = [None, {}, "SYNTHETIC not a receipt"]
            for key, value in (("plotTag", "history-chart-five-hour-canvas"),
                               ("legendDescription", "SYNTHETIC missing explanation"),
                               ("legendDescription", receipt["legendDescription"].split("\n")[0]),
                               ("visibleLegendCount", 1), ("visibleLegendCount", False),
                               ("hiddenFromAccessibility", True), ("hiddenFromAccessibility", 0)):
                mutations.append(dict(receipt, **{key: value}))
                mutations.append({field: item for field, item in receipt.items() if field != key})
            for value in mutations:
                with self.subTest(name=name, value=value):
                    self.mutate(name, "compactLegend", value)
            data.pop("compactLegend")
            path.write_text(json.dumps(data))
            with self.subTest(name=name, missing=True), self.assertRaises(ValueError):
                verify_captures(self.directory)
        for name in ASSEMBLED_CAPTURES.keys() - ASSEMBLED_COUNTS.keys():
            with self.subTest(name=name, unexpected=True):
                self.mutate(name, "compactLegend", receipt)

    def test_full_canvas_rejects_hidden_clipping_even_when_clipped_receipt_is_contained(self):
        for name in ASSEMBLED_COUNTS:
            for key, value in (("canvasLeft", -1), ("canvasTop", float("nan")), ("canvasRight", 100),
                               ("canvasClippedLeft", 0.5), ("canvasClippedBottom", True),
                               ("observationCount", True), ("connectionCount", 99), ("referenceCount", 99),
                               ("boundaryCount", 99), ("pixelOraclePassed", False), ("pixelSamples", [])):
                with self.subTest(name=name, key=key):
                    self.mutate(name, key, value)

    def test_png_bytes_not_metadata_alone_prove_each_registered_sample(self):
        for name in ASSEMBLED_COUNTS:
            self.fixture.captures()
            path = self.directory / (name + ".json")
            data = json.loads(path.read_text())
            for key, value in (("actualArgb", -1), ("matched", False), ("targetArgb", True), ("x", -1),
                               ("y", 9999), ("x", True), ("kind", "invented")):
                mutated = copy.deepcopy(data)
                mutated["pixelSamples"][0][key] = value
                path.write_text(json.dumps(mutated))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    verify_captures(self.directory)
            path.write_text(json.dumps(data))
            size = (2, 3) if ASSEMBLED_CAPTURES[name][0] == 1 else (3, 2)
            # CRC-valid and dimension-correct but different actual decoded pixels.
            filtered = (b"\0" + b"\xff" * (size[0] * 3)) * size[1]
            (self.directory / (name + ".png")).write_bytes(self.fixture.png(*size, filtered=filtered))
            with self.subTest(name=name, mutation="different PNG pixels"), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_decoded_rgb_and_rgba_samples_reconstruct_all_five_filters(self):
        rows = [bytes([17, 34, 51, 255, 68, 85, 102, 255]), bytes([119, 136, 153, 255, 170, 187, 204, 255])]
        for color, channels in ((2, 3), (6, 4)):
            actual_rows = [b"".join(row[x:x + channels] for x in (0, 4)) for row in rows]
            for kind in range(5):
                previous = bytes(2 * channels)
                filtered = b""
                for row in actual_rows:
                    encoded = bytearray()
                    for index, value in enumerate(row):
                        left = row[index - channels] if index >= channels else 0
                        above = previous[index]
                        corner = previous[index - channels] if index >= channels else 0
                        predictors = (0, left, above, (left + above) // 2, paeth(left, above, corner))
                        encoded.append((value - predictors[kind]) & 255)
                    filtered += bytes([kind]) + encoded
                    previous = row
                image = self.fixture.png(2, 2, color=color, filtered=filtered)
                samples = [dict(x=x, y=y) for y in range(2) for x in range(2)]
                with self.subTest(color=color, filter=kind):
                    self.assertEqual(decoded_samples(image, samples), [-15654349, -12298906, -8943463, -5588020])


if __name__ == "__main__":
    unittest.main()
