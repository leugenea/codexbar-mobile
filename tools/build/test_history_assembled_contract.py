"""Explicitly synthetic #83 parser/source controls; no application/native execution."""
import copy
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
from verify_test_reports import HISTORY_ASSEMBLED_CASES, HISTORY_ASSEMBLED_CLASS, verify_reports
from verify_history_text_captures import (ASSEMBLED_CAPTURES, ASSEMBLED_COUNTS, CAPTURES, decoded_samples,
                                         paeth, verify_captures)

ROOT = Path(__file__).resolve().parents[2]


class HistoryAssembledContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_exact_four_native_identities_extend_all_107_inherited(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryAssembledAcceptanceTest.kt").read_text()
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)), HISTORY_ASSEMBLED_CASES)
        self.fixture.report()
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 107 + len(HISTORY_ASSEMBLED_CASES))
        for name in HISTORY_ASSEMBLED_CASES:
            self.fixture.report(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native history assembled acceptance"):
                verify_reports(self.directory, "native")

    def test_each_native_case_rejects_failure_error_skip_and_wrong_class(self):
        for name in HISTORY_ASSEMBLED_CASES:
            for mutation in ("failure", "error", "skipped", "wrong-class"):
                path = self.fixture.report()
                tree = ET.parse(path)
                case = next(case for case in tree.getroot().iter("testcase") if case.get("name") == name)
                if mutation == "wrong-class":
                    case.set("classname", HISTORY_ASSEMBLED_CLASS + ".wrong")
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")

    def test_five_representative_images_extend_all_twelve_inherited_and_are_each_required(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryAssembledAcceptanceTest.kt").read_text()
        self.assertEqual(set(re.findall(r'"(history-assembled-[^"]+)"', source)), set(ASSEMBLED_CAPTURES))
        self.assertEqual(len(CAPTURES), 12 + len(ASSEMBLED_CAPTURES))
        for name in ASSEMBLED_CAPTURES:
            self.fixture.captures()
            self.assertEqual(len(verify_captures(self.directory)), 17)
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
                               ("uiCursor", "newest"), ("visibleControls", [])):
                with self.subTest(name=name, key=key):
                    self.mutate(name, key, value)

    def test_full_canvas_rejects_hidden_clipping_even_when_clipped_receipt_is_contained(self):
        for name in ASSEMBLED_COUNTS:
            for key, value in (("canvasLeft", -1), ("canvasTop", float("nan")), ("canvasRight", 100),
                               ("canvasClippedLeft", 0.5), ("canvasClippedBottom", True),
                               ("markerCount", True), ("connectionCount", 99), ("referenceCount", 99),
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

    def test_native_driver_keeps_actual_production_root_labeled_waits_and_scoped_negative_oracles(self):
        native = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile"
        source = (native / "HistoryAssembledAcceptanceTest.kt").read_text()
        collector = (native / "HistoryAcceptanceCapture.kt").read_text()
        self.assertIn("ActivityScenario.launch(MainActivity::class.java)", source)
        self.assertIn("compose.waitUntil", source)
        self.assertIn('hasAnyAncestor(hasTestTag("connection-history"))', source)
        self.assertNotRegex(source + collector, r"setContent|runOnIdle|Thread\.sleep|runBlocking|delay\(")
        self.assertIn("Rect(node.positionInRoot, node.size.toSize())", collector)
        self.assertIn("node.boundsInRoot", collector)
        self.assertIn("bitmap.getPixel", collector)
        self.assertIn("layout.layoutInput.density.fontScale", source)
        self.assertIn("fixture.transport.counts()", source)


if __name__ == "__main__":
    unittest.main()
