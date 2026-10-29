#!/usr/bin/env python3
"""Kotlin-only fixtures for the informational jscpd report."""
# Adapted from leugenea/qmix at 0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0.
# Copyright (c) 2026 QMix contributors; MIT License (see LICENSE in this directory).
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

MODULE = pathlib.Path(__file__).with_name("jscpd_report.py")
SPEC = importlib.util.spec_from_file_location("jscpd_report", MODULE)
reporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reporter)

ROOT = pathlib.Path(__file__).resolve().parents[2]


def fixture():
    def clone(fmt, path, other, lines, tokens):
        return {"format": fmt, "lines": lines, "tokens": tokens,
                "firstFile": {"name": str(ROOT / path), "start": 4, "end": 4 + lines - 1},
                "secondFile": {"name": str(ROOT / other), "start": 20, "end": 20 + lines - 1},
                "fragment": "sample"}

    row = {"percentage": 2.5, "clones": 3, "lines": 300,
           "duplicatedLines": 15, "sources": 6}
    return {"statistics": {"total": row.copy(), "formats": {"kotlin": row.copy()}},
            "duplicates": [clone("kotlin", "app/src/main/java/Store.kt", "app/src/main/java/Hub.kt", 12, 80),
                           clone("kotlin", "app/src/main/java/Screen.kt", "app/src/main/java/Other.kt", 10, 55),
                           clone("kotlin", "app/src/main/java/First.kt", "app/src/main/java/Second.kt", 17, 60)]}



class JscpdReportTest(unittest.TestCase):
    def test_missing_or_malformed_raw_output_fails_without_reports(self):
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            raw, output, markdown = [base / name for name in ("raw.json", "report.json", "report.md")]
            for content in (None, "{broken", "{}", "null", "[]"):
                if content is not None:
                    raw.write_text(content)
                result = subprocess.run([sys.executable, str(MODULE), "report", str(raw),
                                         str(output), str(markdown)], capture_output=True)
                with self.subTest(content=content):
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(output.exists())
                    self.assertFalse(markdown.exists())

    def test_report_cli_writes_normalized_json_and_same_markdown(self):
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            source, output, markdown = [base / filename for filename in ("raw.json", "report.json", "report.md")]
            source.write_text(json.dumps(fixture()))
            subprocess.run([sys.executable, str(MODULE), "report", str(source), str(output), str(markdown),
                            "--repo", str(ROOT)], check=True)
            data = json.loads(output.read_text())
            self.assertEqual(data["statistics"]["total"]["clones"], 3)
            self.assertEqual(markdown.read_text(), reporter.render_markdown(data))
            self.assertEqual(data["duplicates"][0]["firstFile"]["name"], "app/src/main/java/Store.kt")


if __name__ == "__main__":
    unittest.main()
