"""Synthetic saved-PNG and gallery validator regressions; not native visual approval."""
import copy
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image

from verify_history_text_captures import (CAPTURES, CHART_COUNTS, INTEGRATED_CONTROLS,
                                         PRODUCTION_ROUTES, ASSEMBLED_CAPTURES, ASSEMBLED_CONTROLS,
                                         ASSEMBLED_COUNTS, capture_receipt, decode_png, decoded_samples,
                                         read_png, verify_captures, BANKED_TABLE_ROWS, DASHBOARD_CAPTURES,
                                         DASHBOARD_LAYOUTS, LIVE_CAPTURES, verify_gallery)

SCRATCH = Path(os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir()))


class PngCaptureTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def captures(self):
        for name, (orientation, dark, font) in CAPTURES.items():
            size = (2, 3) if orientation == 1 else (3, 2)
            metadata: dict = dict(name=name, synthetic=True, api=36, orientation=orientation, dark=dark,
                            fontScale=int(font), density=1, renderedLocale="en-US", width=size[0], height=size[1])
            if name in CHART_COUNTS:
                metadata.update(zip(("observationCount", "connectionCount", "referenceCount"), CHART_COUNTS[name]))
                metadata.update(pixelOraclePassed=True, canvasLeft=0.1, canvasTop=0.1, canvasRight=1.9, canvasBottom=1.9,
                                trendCount=int(CHART_COUNTS[name][0] >= 2),
                                trendPixelOraclePassed=name == "history-chart-measured-portrait-dark")
            if name in PRODUCTION_ROUTES:
                metadata.update(productionEntry=True, visibleRoots=["connection-screen"], route=PRODUCTION_ROUTES[name],
                                visibleControls=(ASSEMBLED_CONTROLS if name in ASSEMBLED_CAPTURES else INTEGRATED_CONTROLS)[name],
                                layoutFontScale=font, layoutDensity=1,
                                canvasLeft=0.1, canvasTop=0.1, canvasRight=1.9, canvasBottom=1.9,
                                canvasClippedLeft=0.1, canvasClippedTop=0.1, canvasClippedRight=1.9, canvasClippedBottom=1.9)
            if name in ASSEMBLED_CAPTURES:
                metadata["layoutLabel"] = ("history-page-cursor" if metadata["route"] == "LOCAL_HISTORY_DETAILS" else
                                           "dashboard-no-info-FIVE_HOUR" if name == "history-assembled-fresh-portrait-light" else
                                           "history-chart-five-hour-pace")
            if name in ASSEMBLED_COUNTS:
                metadata.update(zip(("observationCount", "connectionCount", "referenceCount", "boundaryCount"), ASSEMBLED_COUNTS[name]))
                metadata.update(pixelOraclePassed=True, pixelSamples=self.assembled_samples(ASSEMBLED_COUNTS[name]),
                                compactLegend=dict(plotTag="history-chart-five-hour-plot",
                                    legendDescription=("Solid line: measured observations connected in time order; single observation: short tick\n"
                                                       "Faint short dashes: least-squares usage trend when available\n"
                                                       "Long dashes: nominal analytical full-quota reference, not a forecast"),
                                    visibleLegendCount=0, hiddenFromAccessibility=False))
            image = self.png(*size)
            if name.startswith("history-current-"):
                image = self.current_capture(metadata, name, dark)
            (self.directory / (name + ".json")).write_text(json.dumps(metadata))
            (self.directory / (name + ".png")).write_bytes(image)

    def current_capture(self, metadata, name, dark):
        primary, reference = ((-10006364, -11975345) if not dark else (-3097345, -3488560))
        metadata.update(width=4, height=5, canvasLeft=0, canvasTop=0, canvasRight=4, canvasBottom=4,
                        canvasClippedLeft=0, canvasClippedTop=0, canvasClippedRight=4, canvasClippedBottom=4)
        samples = []
        for item in self.assembled_samples(ASSEMBLED_COUNTS[name]):
            kind, matched = item["kind"], item["matched"]
            target = reference if kind == "dash" else primary
            x, y = ((2, 2) if matched else (3, 2)) if kind == "dash" else (1, 1)
            samples.append(dict(kind=kind, x=x, y=y, targetArgb=target, actualArgb=target if matched else -1, matched=matched))
        metadata["pixelSamples"] = samples
        pixels = bytearray(b"\xff" * 4 * 5 * 3)
        for x, y, color in ((1, 1, primary), (2, 2, reference)):
            index = (y * 4 + x) * 3
            pixels[index:index + 3] = bytes((color >> shift) & 255 for shift in (16, 8, 0))
        return self.png(4, 5, pixels=bytes(pixels))

    @staticmethod
    def assembled_samples(counts):
        samples = []
        for kind, count in zip(("observation", "edge", "dash", "bridge"), (counts[0], counts[1], counts[2] * 56, counts[3])):
            for index in range(count):
                matched = kind != "dash" or index < 28
                samples.append(dict(kind=kind, x=1, y=1, targetArgb=-16777216 if matched else -1,
                                    actualArgb=-16777216, matched=matched))
        return samples

    @staticmethod
    def png(width, height, pixels=None, mode="RGB", format="PNG"):
        image = Image.frombytes(mode, (width, height), pixels) if pixels is not None else Image.new(mode, (width, height))
        with image, BytesIO() as stream:
            image.save(stream, format=format)
            return stream.getvalue()

    def test_png_samples_use_android_signed_argb_from_saved_rgb_and_rgba_pixels(self):
        for mode, pixels, expected in (("RGB", b"\x11\x22\x33\x44\x55\x66", [-15654349, -12298906]),
                                       ("RGBA", b"\x11\x22\x33\xff\x44\x55\x66\x00", [-15654349, 4478310])):
            with self.subTest(mode=mode), decode_png(self.png(2, 1, pixels, mode)) as image:
                self.assertEqual(decoded_samples(image, [dict(x=0, y=0), dict(x=1, y=0)]), expected)

    def test_png_rejects_incomplete_wrong_format_and_oversized_inputs(self):
        data = self.png(2, 3)
        for invalid in (data[:-1], data[:-20], data[:len(data) // 2] + data[-12:],
                        b"not an image", self.png(2, 3, format="GIF") + data[-12:]):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                decode_png(invalid)
        for budget, limit in (("MAX_PNG_BYTES", len(data) - 1), ("MAX_PIXEL_BYTES", 23), ("MAX_DIMENSION", 2)):
            with self.subTest(budget=budget), patch("verify_history_text_captures." + budget, limit):
                with self.assertRaisesRegex(ValueError, "budget"):
                    decode_png(data)
        # Exercise Pillow's warning and hard-error paths without allocating a bomb.
        for limit in (4, 2):
            with patch.object(Image, "MAX_IMAGE_PIXELS", limit), self.assertRaises(ValueError):
                decode_png(data)
        with patch.object(Image.Image, "load", side_effect=OSError("synthetic decode failure")), self.assertRaises(ValueError):
            decode_png(data)
        path = self.directory / "bounded.png"
        path.write_bytes(data)
        with patch("verify_history_text_captures.MAX_PNG_BYTES", 8), self.assertRaisesRegex(ValueError, "budget"):
            read_png(path)

    def test_observed_configuration_and_input_hashes_bind_the_capture(self):
        self.captures()
        path = self.directory / "history-measured-portrait-light.png"
        receipt = capture_receipt(path)
        self.assertEqual(receipt["pngSha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        sidecar = path.with_suffix(".json")
        self.assertEqual(receipt["metadataSha256"], hashlib.sha256(sidecar.read_bytes()).hexdigest())
        original = json.loads(sidecar.read_text())
        for key, invalid in (("api", 35), ("fontScale", 3), ("dark", True), ("width", 0), ("density", float("nan"))):
            sidecar.write_text(json.dumps(dict(original, **{key: invalid})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)

    def test_missing_duplicate_corrupt_and_symlink_inputs_fail_closed(self):
        self.captures()
        path = self.directory / "history-measured-portrait-light.png"
        data = path.read_bytes()
        for mutation in ("missing", "duplicate", "corrupt", "symlink"):
            path.write_bytes(data)
            other = self.directory / "duplicate" / path.name
            other.parent.mkdir(exist_ok=True)
            if mutation == "missing":
                path.unlink()
            elif mutation == "duplicate":
                other.write_bytes(data)
            elif mutation == "corrupt":
                path.write_bytes(data[:-1])
            else:
                path.unlink()
                path.symlink_to(other)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                verify_captures(self.directory)
            if path.is_symlink():
                path.unlink()
            other.unlink(missing_ok=True)
        path.write_bytes(data)
        sidecar = path.with_suffix(".json")
        sidecar.unlink()
        sidecar.symlink_to(self.directory / "missing.json")
        with self.assertRaises(ValueError):
            capture_receipt(path)

class CurrentCaptureTests(unittest.TestCase):
    def test_current_paint_targets_remain_registered_for_each_role_and_theme(self):
        fixture = PngCaptureTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.captures()
        for theme in ("light", "dark"):
            path = fixture.directory / f"history-current-{theme}-portrait.png"
            sidecar = path.with_suffix(".json")
            original = sidecar.read_text()
            for role in ("observation", "edge", "dash", "bridge"):
                data = json.loads(original)
                next(item for item in data["pixelSamples"] if item["kind"] == role)["targetArgb"] = -1
                sidecar.write_text(json.dumps(data))
                with self.subTest(theme=theme, role=role), self.assertRaises(ValueError):
                    capture_receipt(path)
            sidecar.write_text(original)

def dashboard_captures(directory, fixture):
    for name, (orientation, dark, font, route, mode, kind) in DASHBOARD_CAPTURES.items():
        label, roots = DASHBOARD_LAYOUTS[name]
        size = (4, 5) if orientation == 1 else (5, 4)
        data: dict = dict(name=name, synthetic=True, productionEntry=True, api=36, orientation=orientation,
                    dark=dark, fontScale=font, density=1, renderedLocale="en-US", width=size[0], height=size[1],
                    route=route, requestMode=mode, sourceKind=kind,
                    activity="io.github.leugenea.codexbarmobile.MainActivity", visibleRoots=roots,
                    layoutLabel=label, layoutFontScale=font, layoutDensity=1, compactCopy=None)
        if name == "dashboard-drawer-portrait-dark":
            data["drawerLabels"] = ["Full local history details", "Full live connection details", "Account settings"]
        if name in {"dashboard-banked-table-portrait-light", "dashboard-connection-details-landscape-dark"}:
            data.update(inventoryCount=7, inventoryRows=3)
        if name == "dashboard-banked-table-portrait-light":
            data["bankedTableRows"] = BANKED_TABLE_ROWS
        image = fixture.png(*size)
        if kind != "NONE":
            data["compactCopy"] = dict(updated="Updated Jan 15, 08:37", first="Jan 8, 09:00" if kind == "WEEKLY" else "Jan 15, 04:00",
                                       last="Jan 15, 09:00", firstLineCount=1, lastLineCount=1, visibleLegendCount=0,
                                       legendDescription=("Solid line: measured observations connected in time order; single observation: short tick\n"
                                                          "Faint short dashes: least-squares usage trend when available\n"
                                                          "Long dashes: nominal analytical full-quota reference, not a forecast"))
            primary, reference = ((-10006364, -11975345) if not dark else (-3097345, -3488560))
            samples = [dict(kind="observation", x=1, y=1, targetArgb=primary, actualArgb=primary, matched=True)]
            samples.extend(dict(kind="dash", x=2 if n < 28 else 3, y=2, targetArgb=reference,
                                actualArgb=reference if n < 28 else -1, matched=n < 28) for n in range(56))
            data.update(observationCount=1, connectionCount=0, referenceCount=1, boundaryCount=0, pixelOraclePassed=True,
                        canvasLeft=0, canvasTop=0, canvasRight=4, canvasBottom=4,
                        canvasClippedLeft=0, canvasClippedTop=0, canvasClippedRight=4, canvasClippedBottom=4,
                        pixelSamples=samples)
            pixels = bytearray(b"\xff" * size[0] * size[1] * 3)
            for x, y, color in ((1, 1, primary), (2, 2, reference)):
                index = (y * size[0] + x) * 3
                pixels[index:index + 3] = bytes((color >> shift) & 255 for shift in (16, 8, 0))
            image = fixture.png(*size, pixels=bytes(pixels))
        (directory / (name + ".json")).write_text(json.dumps(data))
        (directory / (name + ".png")).write_bytes(image)

class DashboardCaptureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = PngCaptureTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory
        dashboard_captures(self.directory, self.fixture)

    def test_dashboard_routes_roots_copy_and_native_accessibility_remain_required(self):
        for name in DASHBOARD_CAPTURES:
            path = self.directory / (name + ".png")
            self.assertEqual(capture_receipt(path)["name"], name)
        for name, key, value in (
            ("dashboard-main-portrait-light", "route", "WRONG"),
            ("dashboard-drawer-portrait-dark", "visibleRoots", ["connection-screen"]),
            ("dashboard-drawer-portrait-dark", "drawerLabels", []),
            ("dashboard-main-landscape-dark-large-font", "layoutFontScale", 1),
            ("dashboard-banked-table-portrait-light", "bankedTableRows", []),
        ):
            path = self.directory / (name + ".png")
            sidecar = path.with_suffix(".json")
            original = sidecar.read_text()
            sidecar.write_text(json.dumps(dict(json.loads(original), **{key: value})))
            with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                capture_receipt(path)
            sidecar.write_text(original)
        path = self.directory / "dashboard-main-portrait-light.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("updated", "Updated Jan 15, 08:00"), ("legendDescription", "incomplete"),
                           ("visibleLegendCount", 1), ("firstLineCount", 2)):
            changed = copy.deepcopy(original)
            changed["compactCopy"][key] = value
            sidecar.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)

    def test_dashboard_png_bytes_and_full_canvas_must_match_native_samples(self):
        path = self.directory / "dashboard-main-portrait-light.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("canvasClippedLeft", .5), ("pixelSamples", [])):
            sidecar.write_text(json.dumps(dict(original, **{key: value})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)
        sidecar.write_text(json.dumps(original))
        path.write_bytes(self.fixture.png(4, 5))
        with self.assertRaisesRegex(ValueError, "decoded PNG pixels"):
            capture_receipt(path)

class AssembledCaptureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = PngCaptureTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory
        self.fixture.captures()

    def test_real_entry_configuration_controls_and_full_canvas_are_required(self):
        for name in ("history-integrated-chart-portrait-light", "history-assembled-single-portrait-dark"):
            path = self.directory / (name + ".png")
            sidecar = path.with_suffix(".json")
            original = json.loads(sidecar.read_text())
            for key, value in (("productionEntry", False), ("visibleRoots", []), ("visibleControls", None),
                               ("route", "WRONG"), ("canvasLeft", -1), ("canvasTop", float("nan")),
                               ("canvasClippedLeft", .5), ("canvasClippedBottom", True)):
                sidecar.write_text(json.dumps(dict(original, **{key: value})))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    capture_receipt(path)
            sidecar.write_text(json.dumps(original))

    def test_saved_pixels_not_metadata_alone_prove_registered_paint_and_dash_gaps(self):
        path = self.directory / "history-assembled-single-portrait-dark.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("actualArgb", -1), ("matched", False), ("x", True), ("y", 9999)):
            changed = copy.deepcopy(original)
            changed["pixelSamples"][0][key] = value
            sidecar.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)
        # Mutually agreeing all-painted receipts must still prove unpainted gaps.
        changed = copy.deepcopy(original)
        for sample in changed["pixelSamples"]:
            if sample["kind"] == "dash":
                sample.update(targetArgb=-16777216, matched=True)
        sidecar.write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, "dash gaps"):
            capture_receipt(path)
        sidecar.write_text(json.dumps(original))
        path.write_bytes(self.fixture.png(2, 3, pixels=b"\xff" * 18))
        with self.assertRaisesRegex(ValueError, "decoded PNG pixels"):
            capture_receipt(path)

    def test_compact_legend_must_remain_complete_accessible_and_not_visible_copy(self):
        path = self.directory / "history-assembled-single-portrait-dark.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("legendDescription", "missing explanation"), ("visibleLegendCount", 1),
                           ("hiddenFromAccessibility", True)):
            changed = copy.deepcopy(original)
            changed["compactLegend"][key] = value
            sidecar.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)

class ChartCaptureTests(unittest.TestCase):
    def test_chart_requires_native_paint_trend_and_contained_canvas(self):
        fixture = PngCaptureTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.captures()
        path = fixture.directory / "history-chart-measured-portrait-dark.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("pixelOraclePassed", False), ("trendPixelOraclePassed", False),
                           ("observationCount", 99), ("canvasRight", 4)):
            sidecar.write_text(json.dumps(dict(original, **{key: value})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)

class LiveCaptureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = PngCaptureTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory
        self.fixture.captures()
        for name, dark in LIVE_CAPTURES.items():
            samples = [dict(kind=kind, x=1, y=1, targetArgb=-16777216, actualArgb=-16777216,
                            matched=True, bounds=[0, 0, 2, 3])
                       for kind in ("background", "card", "content", "progress", "track")]
            data = dict(name=name, screen="live-usage", route="CONNECTION_DETAILS", synthetic=True, productionEntry=True,
                        activity="io.github.leugenea.codexbarmobile.MainActivity", phase="OBSERVED",
                        api=36, orientation=1, dark=dark, fontScale=1.0, layoutFontScale=1.0,
                        density=1, layoutDensity=1, renderedLocale="en-US", width=2, height=3,
                        visibleFacts={"live-five-hour-percent": "12.5% used", "live-weekly-percent": "76.25% used",
                                      "live-five-hour-reset-absolute": "Reset: Oct 8, 2026, 01:00",
                                      "live-five-hour-reset-relative": "Reset: 1 hour 30 minutes remaining",
                                      "live-weekly-reset-absolute": "Reset: Oct 10, 2026, 01:00",
                                      "live-weekly-reset-relative": "Reset: 2 days 1 hour remaining"},
                        visibleControls=[], visibleRoots=["connection-screen"], pixelSamples=samples, pixelOraclePassed=True)
            (self.directory / (name + ".png")).write_bytes(self.fixture.png(2, 3))
            (self.directory / (name + ".json")).write_text(json.dumps(data))
        dashboard_captures(self.directory, self.fixture)

    def test_complete_gallery_requires_every_image_once_with_consistent_density(self):
        self.assertEqual(len(verify_gallery(self.directory)), 28)
        for path in sorted(self.directory.glob("*.png")):
            data = path.read_bytes()
            path.unlink()
            with self.subTest(missing=path.name), self.assertRaises(ValueError):
                verify_gallery(self.directory)
            path.write_bytes(data)
        path = self.directory / "history-measured-portrait-light.json"
        data = json.loads(path.read_text())
        path.write_text(json.dumps(dict(data, density=2)))
        with self.assertRaisesRegex(ValueError, "density changed"):
            verify_gallery(self.directory)

    def test_live_facts_bounds_and_saved_pixels_are_not_filename_receipts(self):
        path = self.directory / "live-usage-portrait-light.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("renderedLocale", "de-DE"), ("layoutDensity", 2), ("visibleRoots", []), ("pixelSamples", [])):
            sidecar.write_text(json.dumps(dict(original, **{key: value})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)
        changed = copy.deepcopy(original)
        changed["visibleFacts"]["live-five-hour-reset-relative"] = "Reset: 1 hour remaining"
        sidecar.write_text(json.dumps(changed))
        with self.assertRaises(ValueError):
            capture_receipt(path)
        for key, value in (("actualArgb", -1), ("bounds", [0, 0, 1, 1])):
            changed = copy.deepcopy(original)
            changed["pixelSamples"][0][key] = value
            sidecar.write_text(json.dumps(changed))
            with self.subTest(sample=key), self.assertRaises(ValueError):
                capture_receipt(path)
        sidecar.write_text(json.dumps(original))
        path.write_bytes(self.fixture.png(2, 3, pixels=b"\xff" * 18))
        with self.assertRaisesRegex(ValueError, "decoded PNG pixels"):
            capture_receipt(path)
