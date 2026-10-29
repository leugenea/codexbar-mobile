"""Kotlin-only benchmark conversion fixtures adapted from qmix."""
# Adapted from leugenea/qmix at 0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0.
# Copyright (c) 2026 QMix contributors; MIT License (see LICENSE in this directory).
import unittest

import benchmark_metrics

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

if __name__ == "__main__":
    unittest.main()
