"""Synthetic dashboard capture controls; no application execution or visual approval."""
import copy
import json
import unittest

from verify_history_text_captures import (BANKED_TABLE_ROWS, DASHBOARD_CAPTURES, DASHBOARD_LAYOUTS,
                                         capture_receipt)
import test_history_text_contract as history_fixture


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


class DashboardContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = history_fixture.HistoryTextContracts()
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
