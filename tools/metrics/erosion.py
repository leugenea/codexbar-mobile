#!/usr/bin/env python3
"""Informational Kotlin complexity erosion; analyzer errors fail closed."""
# Adapted from leugenea/qmix at 0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0.
# Copyright (c) 2026 QMix contributors; MIT License (see LICENSE in this directory).

import argparse
import html
import json
import math
import os
import pathlib

LANGUAGES = ("Kotlin",)
SOURCE_ROOT = "app/src/main"
EXCLUDED_DIRS = {"test", "tests", "androidTest", "build", "generated", "third_party", "vendor"}
MARKER = "<!-- code-erosion -->"


def is_in_scope(path, root=None):
    """Select handwritten production Kotlin; read failures are never exclusions."""
    parts = pathlib.PurePosixPath(path).parts
    if (pathlib.PurePosixPath(path).is_absolute() or ".." in parts or
            not path.startswith(SOURCE_ROOT + "/") or not path.endswith(".kt") or
            any(part in EXCLUDED_DIRS for part in parts) or
            path.endswith(("Test.kt", ".generated.kt", "_generated.kt"))):
        return False
    if root is not None:
        try:
            with (root / path).open(encoding="utf-8") as stream:
                header = "".join(stream.readline() for _ in range(20))
        except (OSError, UnicodeError) as exc:
            raise ValueError(f"{path}:1: cannot read source header: {exc}") from exc
        if "Code generated" in header and "DO NOT EDIT" in header:
            return False
    return True


def language_for(path):
    if is_in_scope(path):
        return "Kotlin"
    raise ValueError(f"Unexpected source path: {path}")


def discover_sources(root):
    """Walk fail-closed: os.walk otherwise silently ignores unreadable directories."""
    base = root / SOURCE_ROOT
    if not base.is_dir():
        raise ValueError(f"{SOURCE_ROOT}:1: missing production source directory")

    def unreadable(error):
        raise ValueError(f"{error.filename}:1: cannot discover Kotlin sources: {error}") from error

    selected = []
    for directory, dirs, files in os.walk(base, onerror=unreadable):
        dirs[:] = sorted(set(dirs) - EXCLUDED_DIRS)
        for name in sorted(files):
            path = (pathlib.Path(directory) / name).relative_to(root).as_posix()
            if is_in_scope(path, root):
                selected.append(path)
    return sorted(selected)


def calculate(rows, sources=None):
    """Return unrounded values; consumers can format without losing precision."""
    if sources is None:
        sources = {row["file"] for row in rows}
    selected = set(sources)
    with_functions = {row["file"] for row in rows}

    def aggregate(group, source_paths):
        mass = math.fsum(row["mass"] for row in group)
        high = [row for row in group if row["ccn"] > 10]
        high_mass = math.fsum(row["mass"] for row in high)
        return {"erosion": high_mass / mass if mass else 0.0,
                "mass": mass, "high_ccn_mass": high_mass,
                "function_count": len(group), "high_ccn_count": len(high),
                "files_selected": len(source_paths),
                "files_with_functions": len(with_functions & source_paths)}

    return {"schema_version": 2,
            "languages": {lang: aggregate([row for row in rows if row["language"] == lang],
                                          {path for path in selected if language_for(path) == lang})
                          for lang in LANGUAGES},
            "functions": rows,
            "top_five": {lang: sorted((row for row in rows if row["language"] == lang),
                                     key=lambda row: (-row["mass"], row["file"], row["function"]))[:5]
                         for lang in LANGUAGES}}


def render_markdown(report):
    """Render the same report content for the artifact and job summary."""
    def cell(value):
        return html.escape(str(value), quote=True).replace("|", "\\|").replace("\n", " ")

    lines = [MARKER, "### Code complexity erosion", "", "Informational only; CCN > 10 contributes to erosion.",
             "Kotlin CCN follows the repository tree-sitter counting contract.", "",
             "| Scope | Erosion | Functions | Functions with CCN > 10 | Files selected | Files with functions |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for label, metrics in report["languages"].items():
        lines.append(f"| {label} | {metrics['erosion']:.2%} | {metrics['function_count']} | {metrics['high_ccn_count']} | {metrics['files_selected']} | {metrics['files_with_functions']} |")
    lines += ["", "A selected file may contain no functions; these counts expose gaps without treating declarations as failures."]
    for language, rows in report["top_five"].items():
        lines += ["", f"Top {len(rows)} {language} functions by mass (CCN × √NLOC):", "",
                  "| File | Function | CCN | NLOC | Mass |", "| --- | --- | ---: | ---: | ---: |"]
        for row in rows:
            lines.append(f"| {cell(row['file'])} | {cell(row['function'])} | {row['ccn']} | {row['nloc']} | {row['mass']:.2f} |")
    return "\n".join(lines) + "\n"


def analyze(root):
    sources = discover_sources(root)
    if not sources:
        raise ValueError("no production Kotlin sources selected")
    from kotlin_complexity import analyze_files
    rows = analyze_files(root, [pathlib.Path(path) for path in sources])
    for row in rows:
        row["language"] = "Kotlin"
        row["function"] = ".".join(filter(None, (row["owner"], row["name"])))
        row["start"], row["end"] = row["start_line"], row["end_line"]
        row["mass"] = row["ccn"] * math.sqrt(row["nloc"])
    return calculate(rows, sources)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=pathlib.Path, default=pathlib.Path("."))
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--summary", required=True, type=pathlib.Path)
    args = parser.parse_args()
    report = analyze(args.repo.resolve())
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    args.summary.write_text(render_markdown(report), encoding="utf-8")


if __name__ == "__main__":
    main()
