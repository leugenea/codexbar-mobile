"""Kotlin-only regression fixtures adapted from qmix's erosion tests."""
# Adapted from leugenea/qmix at 0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0.
# Copyright (c) 2026 QMix contributors; MIT License (see LICENSE in this directory).
import json
import math
import pathlib
import subprocess
import sys
import tempfile
import unittest

import erosion

SCRIPT = pathlib.Path(__file__).with_name("erosion.py")


class ErosionTest(unittest.TestCase):
    def test_weighted_erosion_strict_threshold(self):
        rows = [{"file": "app/src/main/A.kt", "function": name, "language": "Kotlin",
                 "ccn": ccn, "nloc": nloc, "mass": ccn * math.sqrt(nloc)}
                for name, ccn, nloc in (("low", 10, 4), ("high", 11, 9))]
        report = erosion.calculate(rows)
        self.assertEqual(report["schema_version"], 2)
        self.assertEqual(set(report["languages"]), {"Kotlin"})
        self.assertEqual(report["languages"]["Kotlin"]["mass"], 53)
        self.assertEqual(report["languages"]["Kotlin"]["high_ccn_mass"], 33)
        self.assertEqual(report["languages"]["Kotlin"]["high_ccn_count"], 1)
        self.assertAlmostEqual(report["languages"]["Kotlin"]["erosion"], 33 / 53)

    def test_empty_aggregate_is_zero_but_missing_corpus_is_an_error(self):
        report = erosion.calculate([])
        self.assertEqual(report["languages"]["Kotlin"]["erosion"], 0)
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            with self.assertRaisesRegex(ValueError, "missing production"):
                erosion.analyze(root)
            (root / "app/src/main").mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, "no production"):
                erosion.analyze(root)

    def test_cli_writes_current_source_json_and_matching_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            path = root / "app/src/main/A.kt"
            path.parent.mkdir(parents=True)
            path.write_text("fun branch() = if (true) 1 else 0\n")
            output, summary = root / "report.json", root / "report.md"
            subprocess.run([sys.executable, str(SCRIPT), "--repo", str(root),
                            "--output", str(output), "--summary", str(summary)], check=True)
            report = json.loads(output.read_text())
            self.assertEqual(report["functions"][0]["ccn"], 2)
            self.assertEqual(report["languages"]["Kotlin"]["function_count"], 1)
            self.assertEqual(report["functions"][0]["mass"], 2)
            self.assertEqual(report["languages"]["Kotlin"]["files_selected"], 1)
            self.assertEqual(summary.read_text(), erosion.render_markdown(report))


if __name__ == "__main__":
    unittest.main()
