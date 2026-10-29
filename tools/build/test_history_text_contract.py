"""Synthetic capture controls only; never execute application code."""
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
                                         read_png, verify_captures)

SCRATCH = Path(os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir()))

# Independent selected-family oracle for mandatory native report execution.
RETAINED_TEXT_CASES = {
    "readinessContentErrorsLossAndTruncationRemainIndependent",
    "collapsedDetailsReachEveryGapAndResetBreakWithoutOmission",
    "unknownBaselineKeepsMeasuredValueAndB1ResetContext",
    "longExactDecimalIsChunkedWithOneAccessibleOwnerPerPart",
    "portraitLightMeasuredScreenshotHasReachableUnclippedLabels",
    "landscapeDarkGappedScreenshotHasReachableControls",
    "largeFontUnknownScreenshotKeepsCompleteNativeTextAndControls",
}


class HistoryTextContracts(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
