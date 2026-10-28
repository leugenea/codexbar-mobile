"""Synthetic dashboard capture controls; no application execution or visual approval."""
import copy
import json
import unittest

from dashboard_contract_expectations import EXPECTED_DASHBOARD_CAPTURE_SPECS, EXPECTED_DASHBOARD_LAYOUTS, EXPECTED_CURRENT_HOST, EXPECTED_BANKED_TABLE_ROWS, EXPECTED_COMPACT_COPY
from verify_history_text_captures import DASHBOARD_CAPTURES, DASHBOARD_LAYOUTS, dashboard_capture_receipt
import test_history_text_contract as history_fixture


def dashboard_captures(directory, fixture):
    for name, (orientation, dark, font, route, mode, kind) in EXPECTED_DASHBOARD_CAPTURE_SPECS.items():
        label, roots = EXPECTED_DASHBOARD_LAYOUTS[name]
        size = (4, 5) if orientation == 1 else (5, 4)
        data = dict(name=name, synthetic=True, productionEntry=True, api=36, orientation=orientation,
                    dark=dark, fontScale=font, density=1, renderedLocale="en-US", width=size[0], height=size[1],
                    route=route, requestMode=mode, sourceKind=kind, requests=5, gets=2,
                    activity="io.github.leugenea.codexbarmobile.MainActivity", visibleRoots=roots,
                    layoutLabel=label, layoutFontScale=font, layoutDensity=1, windows=[],
                    currentHost=EXPECTED_CURRENT_HOST if mode == "CURRENT" else None, compactCopy=None)
        if mode == "CURRENT":
            data["windows"] = [dict(kind=k, limit=100000, after=0, reset="2027-01-15T09:00:00Z", duration=d,
                                    selectedOrdinals=[1], lastAdmitted=1) for k, d in (("FIVE_HOUR", 18000), ("WEEKLY", 604800))]
        if name == "dashboard-drawer-portrait-dark":
            data["drawerLabels"] = ["Full local history details", "Full live connection details", "Account settings"]
        if name in {"dashboard-banked-table-portrait-light", "dashboard-connection-details-landscape-dark"}:
            data.update(inventoryCount=7, inventoryRows=3)
        if name == "dashboard-banked-table-portrait-light":
            data["bankedTableRows"] = copy.deepcopy(EXPECTED_BANKED_TABLE_ROWS)
        image = fixture.png(*size)
        if kind != "NONE":
            data["compactCopy"] = dict(EXPECTED_COMPACT_COPY[kind], firstLineCount=1, lastLineCount=1)
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
                pixels[index:index+3] = bytes((color >> shift) & 255 for shift in (16, 8, 0))
            filtered = b"".join(b"\0" + pixels[y*size[0]*3:(y+1)*size[0]*3] for y in range(size[1]))
            image = fixture.png(*size, filtered=filtered)
        (directory / (name + ".json")).write_text(json.dumps(data))
        (directory / (name + ".png")).write_bytes(image)


class DashboardContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = history_fixture.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_each_dashboard_image_requires_exact_route_configuration_and_native_owner_receipts(self):
        self.assertEqual(DASHBOARD_CAPTURES, EXPECTED_DASHBOARD_CAPTURE_SPECS)
        self.assertEqual(DASHBOARD_LAYOUTS, EXPECTED_DASHBOARD_LAYOUTS)
        self.assertEqual(len(EXPECTED_DASHBOARD_CAPTURE_SPECS), 7)
        for name in EXPECTED_DASHBOARD_CAPTURE_SPECS:
            dashboard_captures(self.directory, self.fixture)
            path = self.directory / (name + ".png")
            self.assertEqual(dashboard_capture_receipt(path)["name"], name)
            original = json.loads(path.with_suffix(".json").read_text())
            mutations = (("route", "SYNTHETIC-wrong-route"), ("requestMode", "NONE"), ("api", 35),
                         ("layoutFontScale", True), ("density", float("nan")), ("visibleRoots", []),
                         ("requests", 99), ("sourceKind", "INVALID"), ("windows", [{}]),
                         ("uiCursor", "SYNTHETIC-hidden-cursor"), ("layoutLabel", "SYNTHETIC-arbitrary"),
                         ("visibleRoots", ["connection-screen"]), ("visibleRoots", ["connection-screen", "SYNTHETIC-wrong-child"]),
                         ("currentHost", {}), ("compactCopy", {}))
            for key, value in mutations:
                data = dict(original); data[key] = value
                path.with_suffix(".json").write_text(json.dumps(data))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)
            for key in ("visibleRoots", "layoutLabel", "currentHost", "compactCopy"):
                data = dict(original); data.pop(key)
                path.with_suffix(".json").write_text(json.dumps(data))
                with self.subTest(name=name, missing=key), self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)
            if original["currentHost"] is not None:
                for key in EXPECTED_CURRENT_HOST:
                    data = copy.deepcopy(original); data["currentHost"][key] = "MISMATCH"
                    path.with_suffix(".json").write_text(json.dumps(data))
                    with self.subTest(name=name, wrongHost=key), self.assertRaises(ValueError):
                        dashboard_capture_receipt(path)
            if name == "dashboard-banked-table-portrait-light":
                self.assertEqual(original["bankedTableRows"], EXPECTED_BANKED_TABLE_ROWS)
                wrong_rows = [None, [], original["bankedTableRows"][:2], original["bankedTableRows"][::-1]]
                for index in range(3):
                    for key, value in (("expiry", "Time unavailable"), ("remaining", "Entitlement expired"),
                                       ("description", "SYNTHETIC duplicated state"), ("textCellCount", 3),
                                       ("textCellCount", 2.0)):
                        rows = copy.deepcopy(original["bankedTableRows"])
                        rows[index][key] = value
                        wrong_rows.append(rows)
                for rows in wrong_rows:
                    data = dict(original, bankedTableRows=rows)
                    path.with_suffix(".json").write_text(json.dumps(data))
                    with self.subTest(bankedTableRows=rows), self.assertRaises(ValueError):
                        dashboard_capture_receipt(path)
                data = dict(original); data.pop("bankedTableRows")
                path.with_suffix(".json").write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)
            if name == "dashboard-drawer-portrait-dark":
                labels = ["Full local history details", "Full live connection details", "Account settings"]
                self.assertEqual(original["drawerLabels"], labels)
                for wrong_labels in ([label.lower() for label in labels],
                                     [labels[0], labels[1].replace("live", "love"), labels[2]],
                                     labels[::-1], labels[:1], labels + ["SYNTHETIC-extra-row"], None):
                    data = dict(original); data["drawerLabels"] = wrong_labels
                    path.with_suffix(".json").write_text(json.dumps(data))
                    with self.subTest(name=name, drawerLabels=wrong_labels), self.assertRaises(ValueError):
                        dashboard_capture_receipt(path)
                data = dict(original); data.pop("drawerLabels")
                path.with_suffix(".json").write_text(json.dumps(data))
                with self.subTest(name=name, missing="drawerLabels"), self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)
            path.with_suffix(".json").write_text(json.dumps(original))
            if original["sourceKind"] != "NONE":
                self.assert_compact_copy_mutations(path, original)
                for key, value in (("observationCount", 0), ("connectionCount", 1), ("canvasClippedLeft", .5), ("pixelSamples", [])):
                    data = dict(original); data[key] = value
                    path.with_suffix(".json").write_text(json.dumps(data))
                    with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                        dashboard_capture_receipt(path)
                path.with_suffix(".json").write_text(json.dumps(original))
                path.write_bytes(self.fixture.png(4, 5))
                with self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)

    def assert_compact_copy_mutations(self, path, original):
        wrong = dict(updated="Measurement observed at (second precision): Jan 15, 2027, 8:00:00 AM",
                     first="Time axis start: " + original["compactCopy"]["first"],
                     last="Time axis end: Jan 15, 09:00", legendDescription="SYNTHETIC missing explanation",
                     visibleLegendCount=1, firstLineCount=0, lastLineCount=True)
        for key, value in wrong.items():
            for missing in (False, True):
                data = copy.deepcopy(original)
                if missing:
                    data["compactCopy"].pop(key)
                else:
                    data["compactCopy"][key] = value
                path.with_suffix(".json").write_text(json.dumps(data))
                with self.subTest(compactCopy=key, missing=missing), self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)
        for updated in ("Updated Jan 15, 08:00", "Updated Jan 15, 08:38", "Updated Jan 15, 08:37:59"):
            data = copy.deepcopy(original); data["compactCopy"]["updated"] = updated
            path.with_suffix(".json").write_text(json.dumps(data))
            with self.subTest(updated=updated), self.assertRaises(ValueError):
                dashboard_capture_receipt(path)
        if original["fontScale"] == 1:
            data = copy.deepcopy(original); data["compactCopy"]["firstLineCount"] = 2
            path.with_suffix(".json").write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                dashboard_capture_receipt(path)
