#!/usr/bin/env python3
"""Contract tests for the tree-sitter Kotlin function counter."""
# Adapted from leugenea/qmix at 0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0.
# Copyright (c) 2026 QMix contributors; MIT License (see LICENSE in this directory).
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("kotlin_complexity.py")


def analyzer():
    spec = importlib.util.spec_from_file_location("kotlin_complexity", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class KotlinComplexityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = Path("app/src/main/java/Sample.kt")
        (self.root / self.path).parent.mkdir(parents=True)

    def scan(self, source):
        (self.root / self.path).write_text(source, encoding="utf-8")
        return analyzer().analyze_files(self.root, [self.path])

    def test_expression_block_and_bodyless_functions(self):
        rows = self.scan("fun expression(x: Int) = if (x > 0) x else 0\n"
                         "fun block() { // comment\n val x = 1 /* comment\n comment */\n}\n"
                         "fun bodyless(x: Int): Int\n")
        self.assertEqual([(r["name"], r["ccn"], r["nloc"]) for r in rows],
                         [("expression", 2, 1), ("block", 1, 3)])
        self.assertEqual(rows[0], {"file": self.path.as_posix(), "owner": "",
                                    "name": "expression", "receiver": "",
                                    "parameter_types": ["Int"],
                                    "id": self.path.as_posix() + "::expression(Int)",
                                    "start_line": 1, "end_line": 1,
                                    "ccn": 2, "nloc": 1,
                                    "analyzer": "tree-sitter-kotlin"})

    def test_parse_error_and_missing_node_fail_with_file_and_line(self):
        for bad in ('fun broken( = 1\n', 'fun broken(x: Int) {\n  if (x > 0) 1\n'):
            with self.subTest(bad=bad):
                with self.assertRaisesRegex(ValueError, r'Sample\.kt:\d+:'):
                    self.scan(bad)

    def test_unreadable_or_invalid_utf8_file_fails_with_context(self):
        module = analyzer()
        with self.assertRaisesRegex(ValueError, r'Sample\.kt:1:'):
            module.analyze_files(self.root, [self.path])
        (self.root / self.path).write_bytes(b'fun ok() = 1\n\xff')
        with self.assertRaisesRegex(ValueError, r'Sample\.kt:2:'):
            module.analyze_files(self.root, [self.path])

if __name__ == "__main__":
    unittest.main()
