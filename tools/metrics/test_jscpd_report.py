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
    def test_config_pins_owner_approved_minimum_without_raising_token_limit(self):
        config = json.loads((ROOT / ".jscpd.json").read_text(encoding="utf-8"))
        self.assertEqual(config["formats"], ["kotlin"])
        self.assertEqual(config["minLines"], 10)
        self.assertNotIn("minTokens", config)  # Retain jscpd's 50-token default.
        self.assertNotIn("mode", config)  # Retain jscpd's mild default.
        self.assertEqual(config["threshold"], 100)

    def test_normalizes_absolute_paths_and_renders_five_largest(self):
        data = fixture()
        data["duplicates"] += json.loads(json.dumps(data["duplicates"]))
        data["statistics"]["total"]["clones"] = 6
        data["statistics"]["formats"]["kotlin"]["clones"] = 6
        normalized = reporter.normalize(data, ROOT)
        self.assertEqual(normalized["duplicates"][0]["firstFile"]["name"], "app/src/main/java/Store.kt")
        self.assertEqual(data["duplicates"][0]["firstFile"]["name"], str(ROOT / "app/src/main/java/Store.kt"))
        text = reporter.render_markdown(normalized)
        self.assertIn("<!-- code-duplication -->", text)
        self.assertIn("| Scope | Duplication, % | Clones |", text)
        self.assertIn("| Overall | 2.50% | 6 |", text)
        self.assertIn("| Kotlin | 2.50% | 6 |", text)
        self.assertLess(text.index("app/src/main/java/First.kt:4–20"), text.index("app/src/main/java/Store.kt:4–15"))
        self.assertIn("app/src/main/java/Hub.kt:20–31", text)
        self.assertEqual(text.count("↔"), 5)

    def test_missing_kotlin_format_is_an_error_not_zero(self):
        data = fixture()
        data["statistics"]["formats"] = {}
        with self.assertRaises(ValueError):
            reporter.normalize(data, ROOT)

    def test_rejects_malformed_or_unexpected_sources(self):
        for mutation in (lambda d: d.pop("statistics"),
                         lambda d: d["statistics"]["total"].update(percentage="bad"),
                         lambda d: d["statistics"]["total"].update(sources=0),
                         lambda d: d["statistics"]["formats"].update(rust={}),
                         lambda d: d["statistics"]["formats"].pop("kotlin"),
                         lambda d: d["duplicates"][0]["firstFile"].update(name="/outside.kt"),
                         lambda d: d["duplicates"][0]["firstFile"].update(start=0),
                         lambda d: d["duplicates"][0].update(lines=-1),
                         lambda d: d["duplicates"][0].update(tokens=True),
                         lambda d: d["duplicates"][0]["firstFile"].update(name="app/src/main/relative.kt"),
                         lambda d: d["duplicates"][0]["firstFile"].update(end=1),
                         lambda d: d["duplicates"][0]["firstFile"].update(name=str(ROOT / "app/src/test/Test.kt")),
                         lambda d: d["statistics"]["total"].update(percentage=float("nan")),
                         lambda d: d["statistics"]["total"].update(percentage=float("inf")),
                         lambda d: d["statistics"]["total"].update(clones=True),
                         lambda d: d["statistics"]["total"].update(clones=99),
                         lambda d: d["statistics"]["formats"]["kotlin"].update(lines=301)):
            data = fixture()
            mutation(data)
            with self.subTest(data=data), self.assertRaises(ValueError):
                reporter.normalize(data, ROOT)

    def test_zero_clones_and_markdown_escaping(self):
        data = fixture()
        data["duplicates"] = []
        for row in [data["statistics"]["total"], *data["statistics"]["formats"].values()]:
            row["clones"] = row["duplicatedLines"] = row["percentage"] = 0
        normalized = reporter.normalize(data, ROOT)
        self.assertIn("No clones found.", reporter.render_markdown(normalized))
        data = fixture()
        data["duplicates"][0]["firstFile"]["name"] = str(ROOT / "app/src/main/a|b.kt")
        self.assertIn("a\\|b.kt", reporter.render_markdown(reporter.normalize(data, ROOT)))

    def test_sources_cli_rejects_empty_tree_and_excludes_generated_headers(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            empty = subprocess.run([sys.executable, str(MODULE), "sources", str(root)], capture_output=True)
            self.assertNotEqual(empty.returncode, 0)
            base = root / "app/src/main"
            base.mkdir(parents=True)
            (base / "Generated.kt").write_text("// Code generated by tool. DO NOT EDIT.\nfun generated() {}\n")
            (base / "Main.kt").write_text("fun main() {}\n")
            result = subprocess.run([sys.executable, str(MODULE), "sources", str(root)], capture_output=True, check=True)
            self.assertEqual(result.stdout, b"app/src/main/Main.kt\0")

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
