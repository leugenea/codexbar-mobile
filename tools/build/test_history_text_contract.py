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
from verify_test_reports import HISTORY_TEXT_CASES, HISTORY_TEXT_CLASS, verify_reports
from verify_history_text_captures import CAPTURES, verify_captures

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
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 77 + len(HISTORY_TEXT_CASES))
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
            (self.directory / (name + ".json")).write_text(json.dumps(metadata))
            (self.directory / (name + ".png")).write_bytes(self.png(*size))

    def png(self, width, height):
        def chunk(kind, body):
            return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
        header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(
            (b"\0" + b"\0\0\0" * width) * height)) + chunk(b"IEND", b"")

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
