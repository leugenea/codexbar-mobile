"""Synthetic report/capture parser controls only; never execute application code."""
import json
import os
from pathlib import Path
import re
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zlib

import test_workflow_contract
from verify_test_reports import HISTORY_ISOLATION_CASES, HISTORY_ISOLATION_CLASS
from verify_test_reports import ACCOUNT_REMOVAL_CASES, ACCOUNT_REMOVAL_CLASS
from verify_test_reports import ACCOUNT_NAME_CASES, ACCOUNT_NAME_CLASS
from verify_test_reports import HISTORY_ASSEMBLED_CASES, HISTORY_ASSEMBLED_CLASS
from verify_test_reports import HISTORY_AUTHORITY_CASES, HISTORY_NAVIGATION_CASES, HISTORY_CHART_CASES, HISTORY_TEXT_CASES, HISTORY_TEXT_CLASS, verify_reports
from unittest.mock import patch
from verify_history_text_captures import CAPTURES, CHART_COUNTS, INTEGRATED_CAPTURES, INTEGRATED_CONTROLS, PNG_CHANNELS, png_size, read_png, verify_captures
from verify_history_text_captures import ASSEMBLED_CAPTURES, ASSEMBLED_PAGES, ASSEMBLED_CONTROLS, ASSEMBLED_COUNTS

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir()))


class HistoryTextContracts(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def report(self, missing=None):
        writer = test_workflow_contract.SyntheticReportTests()
        writer.directory = self.directory
        return writer.write_report("native", missing=missing)

    def test_all_new_native_cases_are_mandatory_without_changing_inherited_cases(self):
        source = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryTextComponentTest.kt"
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source.read_text())), HISTORY_TEXT_CASES)
        self.report()
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 77 + len(HISTORY_TEXT_CASES) + len(HISTORY_CHART_CASES) + len(HISTORY_NAVIGATION_CASES) + len(HISTORY_AUTHORITY_CASES) + len(HISTORY_ISOLATION_CASES) + len(HISTORY_ASSEMBLED_CASES) + len(ACCOUNT_NAME_CASES) + len(ACCOUNT_REMOVAL_CASES))
        for name in HISTORY_TEXT_CASES:
            self.report(name)
            with self.assertRaisesRegex(ValueError, "Missing real native history text"):
                verify_reports(self.directory, "native")

    def test_new_native_cases_cannot_be_skipped_failed_or_spoofed(self):
        for mutation in ("skipped", "failure", "error", "wrong-class"):
            path = self.report()
            root = ET.parse(path).getroot()
            case = next(case for case in root.iter("testcase") if case.get("classname") == HISTORY_TEXT_CLASS)
            if mutation == "wrong-class":
                case.set("classname", "SYNTHETIC-wrong-class")
            else:
                ET.SubElement(case, mutation)
            ET.ElementTree(root).write(path)
            with self.assertRaises(ValueError):
                verify_reports(self.directory, "native")

    def captures(self):
        for name, (orientation, dark, font) in CAPTURES.items():
            size = (2, 3) if orientation == 1 else (3, 2)
            metadata = dict(name=name, synthetic=True, api=36, orientation=orientation, dark=dark,
                            fontScale=font, density=1, renderedLocale="en-US", width=size[0], height=size[1])
            if name in CHART_COUNTS:
                metadata.update(zip(("markerCount", "connectionCount", "referenceCount"), CHART_COUNTS[name]))
                metadata.update(pixelOraclePassed=True, canvasLeft=0.1, canvasTop=0.1, canvasRight=1.9, canvasBottom=1.9)
            if name in INTEGRATED_CAPTURES:
                metadata.update(productionEntry=True, activity="io.github.leugenea.codexbarmobile.MainActivity",
                                phase="RESTORED", queryKind="FIVE_HOUR", queryLimit=32, queryAfter=0,
                                firstOrdinal=1, lastOrdinal=32, pageEntries=32, hasMore=True, requests=5, gets=2,
                                visibleControls=INTEGRATED_CONTROLS[name], canvasLeft=0.1, canvasTop=0.1,
                                canvasRight=1.9, canvasBottom=1.9,
                                canvasClippedLeft=0.1, canvasClippedTop=0.1,
                                canvasClippedRight=1.9, canvasClippedBottom=1.9)
            if name in ASSEMBLED_CAPTURES:
                keys = ("phase", "readiness", "queryKind", "queryLimit", "queryAfter", "firstOrdinal", "lastOrdinal", "pageEntries", "requests", "gets")
                metadata.update(zip(keys, ASSEMBLED_PAGES[name]))
                metadata.update(productionEntry=True, activity="io.github.leugenea.codexbarmobile.MainActivity", hasMore=False,
                                visibleControls=ASSEMBLED_CONTROLS[name], layoutFontScale=font, layoutDensity=1,
                                uiCursor=f"Exclusive admission cursor: {metadata['queryAfter']} · Page limit: 32")
                if name in ASSEMBLED_COUNTS:
                    metadata.update(zip(("markerCount", "connectionCount", "referenceCount", "boundaryCount"), ASSEMBLED_COUNTS[name]))
                    metadata.update(pixelOraclePassed=True, canvasLeft=0.1, canvasTop=0.1, canvasRight=1.9, canvasBottom=1.9,
                                    canvasClippedLeft=0.1, canvasClippedTop=0.1, canvasClippedRight=1.9, canvasClippedBottom=1.9)
                    metadata["pixelSamples"] = self.assembled_samples(ASSEMBLED_COUNTS[name])
            (self.directory / (name + ".json")).write_text(json.dumps(metadata))
            (self.directory / (name + ".png")).write_bytes(self.png(*size))

    @staticmethod
    def assembled_samples(counts):
        samples = []
        for kind, count in zip(("marker", "edge", "dash", "break"), (counts[0], counts[1], counts[2] * 56, counts[3])):
            for index in range(count):
                matched = kind != "break" and (kind != "dash" or index < 28)
                samples.append(dict(kind=kind, x=1, y=1, targetArgb=-16777216 if matched else -1,
                                    actualArgb=-16777216, matched=matched))
        return samples

    @staticmethod
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    def png_container(self, header, chunks):
        return b"\x89PNG\r\n\x1a\n" + self.chunk(b"IHDR", header) + b"".join(
            self.chunk(kind, body) for kind, body in chunks)

    def png(self, width, height, depth=8, color=2, filtered=None, compressed=None):
        header = struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, 0)
        if filtered is None:
            filtered = (b"\0" + b"\0" * (width * PNG_CHANNELS[color] * (depth // 8))) * height
        if compressed is None:
            compressed = zlib.compress(filtered)
        return self.png_container(header, [(b"IDAT", compressed), (b"IEND", b"")])

    def test_png_pixels_decode_for_supported_bitmap_formats_and_all_filters(self):
        for color, channels in PNG_CHANNELS.items():
            for depth in (8, 16):
                # Every filtered channel pattern is invertible, including filters
                # with left/prior-row prediction. These are real synthetic pixels.
                row = bytes(range(2 * channels * (depth // 8)))
                filtered = b"".join(bytes([kind]) + row for kind in range(5))
                with self.subTest(color=color, depth=depth):
                    self.assertEqual(png_size(self.png(2, 5, depth, color, filtered)), (2, 5))

    def test_png_aggregate_idat_is_one_complete_zlib_stream(self):
        header = struct.pack(">IIBBBBB", 2, 3, 8, 6, 0, 0, 0)
        compressed = zlib.compress((b"\0" + b"\x12\x34\x56\xff" * 2) * 3)
        chunks = [(b"PLTE", b"\0\0\0"), (b"tEXt", b"fixture\0synthetic pixels"),
                  (b"IDAT", compressed[:1]), (b"IDAT", b""), (b"IDAT", compressed[1:]),
                  (b"tEXt", b"after\0pixels"), (b"IEND", b"")]
        self.assertEqual(png_size(self.png_container(header, chunks)), (2, 3))

    def test_crc_correct_undecodable_or_incomplete_pixel_streams_fail_closed(self):
        complete = zlib.compress(b"\0" * 21)
        invalid = {
            "empty-idat": b"", "invalid-zlib": b"not-zlib", "zero-pixels": zlib.compress(b""),
            "truncated-zlib": complete[:-1], "trailing-data": complete + b"extra",
            "second-zlib-stream": complete + complete,
            "bad-zlib-checksum": complete[:-1] + bytes([complete[-1] ^ 1]),
        }
        self.captures()
        path = self.directory / "history-measured-portrait-light.png"
        for name, compressed in invalid.items():
            path.write_bytes(self.png(2, 3, compressed=compressed))
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_crc_correct_wrong_scanlines_and_inflation_bombs_fail_closed(self):
        invalid = {
            "short-row": b"\0" * 20, "extra-pixel": b"\0" * 22,
            "first-filter": b"\5" + b"\0" * 20,
            "later-filter": b"\0" * 7 + b"\xff" + b"\0" * 13,
            "inflation-bomb": b"\0" * (1024 * 1024),
        }
        for name, filtered in invalid.items():
            with self.subTest(name=name), self.assertRaises(ValueError):
                png_size(self.png(2, 3, filtered=filtered))

    def test_png_header_and_chunk_order_fail_closed(self):
        header = struct.pack(">IIBBBBB", 2, 3, 8, 2, 0, 0, 0)
        image = (b"IDAT", zlib.compress(b"\0" * 21))
        end = (b"IEND", b"")
        invalid = [
            [end], [image, (b"IEND", b"not-empty")], [image, end, (b"tEXt", b"x\0y")],
            [(b"IHDR", header), image, end], [end, image, end],
            [image, (b"tEXt", b"x\0y"), image, end],
            [(b"ABCD", b""), image, end], [(b"abcd", b""), image, end],
            [(b"PLTE", b"x"), image, end], [image, (b"PLTE", b"\0\0\0"), end],
            [(b"PLTE", b"\0\0\0"), (b"PLTE", b"\0\0\0"), image, end],
        ]
        for chunks in invalid:
            with self.subTest(chunks=chunks), self.assertRaises(ValueError):
                png_size(self.png_container(header, chunks))
        for fields in ((0, 3, 8, 2, 0, 0, 0), (2, 0, 8, 2, 0, 0, 0),
                       (8193, 3, 8, 2, 0, 0, 0), (8192, 8192, 16, 6, 0, 0, 0),
                       (2, 3, 4, 2, 0, 0, 0), (2, 3, 8, 3, 0, 0, 0),
                       (2, 3, 8, 2, 1, 0, 0), (2, 3, 8, 2, 0, 1, 0), (2, 3, 8, 2, 0, 0, 1)):
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                png_size(self.png_container(struct.pack(">IIBBBBB", *fields), [image, end]))
        for data in (b"\x89PNG\r\n\x1a\n", self.png(2, 3) + b"x", self.png(2, 3)[:-1]):
            with self.subTest(data=data), self.assertRaises(ValueError):
                png_size(data)

    def test_png_resource_budgets_are_enforced_before_decode_or_unbounded_read(self):
        image = self.png(2, 3)
        for budget, limit in (("MAX_PNG_BYTES", len(image) - 1), ("MAX_PIXEL_BYTES", 20), ("MAX_CHUNKS", 2)):
            with self.subTest(budget=budget), patch("verify_history_text_captures." + budget, limit):
                with self.assertRaisesRegex(ValueError, "budget"):
                    png_size(image)
        path = self.directory / "over-budget.png"
        path.write_bytes(image)
        with patch("verify_history_text_captures.MAX_PNG_BYTES", 8):
            with self.assertRaisesRegex(ValueError, "budget"):
                read_png(path)

    def test_count_copy_is_quantity_neutral_with_original_format_arguments(self):
        resources = {item.get("name"): item.text for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        expected = {
            "history_series_counts": "Timestamped measurements: %1$d · Nominal analytical reference segments: %2$d",
            "history_lost": "Samples lost or unconfirmed: %1$d; history is incomplete.",
            "history_show_details": "Show exact details · Page entries: %1$d",
            "history_hide_details": "Hide exact details · Page entries: %1$d",
            "history_chunks": "Exact value continues in ordered text parts (count: %1$d); concatenate in order. No digits omitted.",
        }
        for name, copy in expected.items():
            with self.subTest(name=name):
                self.assertEqual(resources[name], copy)
                self.assertNotRegex(copy, r"%\d+\$d\s+[A-Za-z]")

    def test_capture_collection_accepts_only_complete_observed_configurations(self):
        self.captures()
        self.assertEqual(len(verify_captures(self.directory)), len(CAPTURES))
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn("python3 tools/build/verify_history_text_captures.py", workflow)
        self.assertIn("app/build/outputs/connected_android_test_additional_output/", workflow)
        for key, invalid in (("api", 35), ("fontScale", 3), ("orientation", 2), ("dark", True),
                             ("synthetic", False), ("width", 0), ("density", 0), ("renderedLocale", "")):
            self.captures()
            path = self.directory / "history-measured-portrait-light.json"
            metadata = json.loads(path.read_text())
            metadata[key] = invalid
            path.write_text(json.dumps(metadata))
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_missing_duplicate_and_corrupt_pngs_fail_closed(self):
        nested = self.directory / "duplicate"
        for mutation in ("missing", "duplicate", "corrupt"):
            self.captures()
            path = self.directory / "history-measured-portrait-light.png"
            if mutation == "missing":
                path.unlink()
            elif mutation == "duplicate":
                nested = self.directory / "duplicate"
                nested.mkdir()
                (nested / path.name).write_bytes(path.read_bytes())
            else:
                path.write_bytes(path.read_bytes()[:-1])
            with self.subTest(mutation=mutation), self.assertRaises((ValueError, struct.error)):
                verify_captures(self.directory)
            if mutation == "duplicate":
                (nested / path.name).unlink()
                nested.rmdir()


if __name__ == "__main__":
    unittest.main()
