"""Kotlin-only benchmark conversion fixtures adapted from qmix."""
# Adapted from leugenea/qmix at 0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0.
# Copyright (c) 2026 QMix contributors; MIT License (see LICENSE in this directory).
import copy
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

import benchmark_metrics

SCRIPT = pathlib.Path(__file__).with_name("benchmark_metrics.py")


class BenchmarkMetricsTest(unittest.TestCase):
    def report(self):
        return {"schema_version": 2,
                "languages": {"Kotlin": {"erosion": 0.0025, "high_ccn_count": 1}}}

    def duplication_report(self):
        return {"schema_version": 1,
                "statistics": {"total": {"percentage": 0.503012345, "clones": 4}}}

    def test_named_smaller_is_better_metrics_and_units(self):
        self.assertEqual(benchmark_metrics.metrics_from_report(self.report()), [
            {"name": "Kotlin erosion", "unit": "%", "value": 0.25},
            {"name": "Kotlin CCN > 10", "unit": "functions", "value": 1},
        ])
        self.assertEqual(benchmark_metrics.duplication_metrics_from_report(self.duplication_report()), [
            {"name": "Duplication", "unit": "%", "value": 0.503012345},
            {"name": "Duplication clones", "unit": "clones", "value": 4},
        ])

    def test_small_positive_erosion_is_not_rounded_to_zero(self):
        report = self.report()
        report["languages"]["Kotlin"]["erosion"] = 1e-10
        self.assertGreater(benchmark_metrics.metrics_from_report(report)[0]["value"], 0)

    def test_cli_writes_generic_json_arrays_for_both_kinds(self):
        for kind, report, convert in (
            ("erosion", self.report(), benchmark_metrics.metrics_from_report),
            ("duplication", self.duplication_report(), benchmark_metrics.duplication_metrics_from_report),
        ):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                source, output = pathlib.Path(temp) / "report.json", pathlib.Path(temp) / "metrics.json"
                source.write_text(json.dumps(report))
                subprocess.run([sys.executable, str(SCRIPT), "--kind", kind,
                                "--input", str(source), "--output", str(output)], check=True)
                self.assertEqual(json.loads(output.read_text()), convert(report))

    def test_rejects_malformed_erosion_and_values(self):
        for change in (
            lambda r: r.update(schema_version=1),
            lambda r: r.pop("languages"),
            lambda r: r["languages"].pop("Kotlin"),
            lambda r: r.update(overall={}),
            *(lambda r, value=value: r["languages"]["Kotlin"].update(erosion=value)
              for value in (float("nan"), float("inf"), -0.01, 1.01, True, "0")),
            *(lambda r, value=value: r["languages"]["Kotlin"].update(high_ccn_count=value)
              for value in (-1, 1.2, True, "1")),
        ):
            report = self.report()
            change(report)
            with self.subTest(report=report), self.assertRaises((ValueError, KeyError)):
                benchmark_metrics.metrics_from_report(report)

    def test_rejects_malformed_duplication_and_values(self):
        for change in (
            lambda r: r.pop("schema_version"),
            lambda r: r.update(schema_version=True),
            lambda r: r.update(schema_version=2),
            lambda r: r["statistics"].pop("total"),
            *(lambda r, value=value: r["statistics"]["total"].update(percentage=value)
              for value in (float("nan"), float("inf"), -0.01, 100.01, True, "0")),
            *(lambda r, value=value: r["statistics"]["total"].update(clones=value)
              for value in (-1, 1.5, True, "1")),
        ):
            report = copy.deepcopy(self.duplication_report())
            change(report)
            with self.subTest(report=report), self.assertRaises((ValueError, KeyError)):
                benchmark_metrics.duplication_metrics_from_report(report)


if __name__ == "__main__":
    unittest.main()
