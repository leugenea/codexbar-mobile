"""Synthetic/source controls for issue142; no application/native execution or visual approval."""
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

from verify_test_reports import DASHBOARD_JVM, DASHBOARD_NATIVE, verify_reports
from verify_history_text_captures import DASHBOARD_CAPTURES, dashboard_capture_receipt
import test_history_text_contract as history_fixture
import test_workflow_contract

ROOT = Path(__file__).resolve().parents[2]


def dashboard_captures(directory, fixture):
    for name, (orientation, dark, font, route, mode, kind) in DASHBOARD_CAPTURES.items():
        size = (4, 5) if orientation == 1 else (5, 4)
        data = dict(name=name, synthetic=True, productionEntry=True, api=36, orientation=orientation,
                    dark=dark, fontScale=font, density=1, renderedLocale="en-US", width=size[0], height=size[1],
                    route=route, requestMode=mode, sourceKind=kind, requests=5, gets=2,
                    activity="io.github.leugenea.codexbarmobile.MainActivity", visibleRoots=["connection-screen"],
                    layoutLabel="SYNTHETIC-layout", layoutFontScale=font, layoutDensity=1, windows=[])
        if mode == "CURRENT":
            data["windows"] = [dict(kind=k, limit=32, after=0, reset="2027-01-15T09:00:00Z", duration=d,
                                    selectedOrdinals=[1], lastAdmitted=1) for k, d in (("FIVE_HOUR", 18000), ("WEEKLY", 604800))]
        if name == "dashboard-drawer-portrait-dark":
            data["drawerLabels"] = ["full local history details", "full love connection details"]
        if name in {"dashboard-banked-expanded-portrait-light", "dashboard-connection-details-landscape-dark"}:
            data.update(inventoryCount=7, inventoryRows=3)
        image = fixture.png(*size)
        if kind != "NONE":
            primary, reference = ((-10006364, -11975345) if not dark else (-3097345, -3488560))
            samples = [dict(kind="marker", x=1, y=1, targetArgb=primary, actualArgb=primary, matched=True)]
            samples.extend(dict(kind="dash", x=2 if n < 28 else 3, y=2, targetArgb=reference,
                                actualArgb=reference if n < 28 else -1, matched=n < 28) for n in range(56))
            data.update(markerCount=1, connectionCount=0, referenceCount=1, boundaryCount=0, pixelOraclePassed=True,
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

    def test_exact_additive_dashboard_identities_fail_closed_without_removing_inherited_cases(self):
        writer = test_workflow_contract.SyntheticReportTests()
        writer.directory = self.directory
        for kind, registry, source_set in (("jvm", DASHBOARD_JVM, "test"), ("native", DASHBOARD_NATIVE, "androidTest")):
            for classname, required in registry.items():
                source = ROOT / f"app/src/{source_set}/java/{classname.replace('.', '/')}.kt"
                actual = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source.read_text()))
                self.assertTrue(required <= actual)
                for name in required:
                    for mutation in ("missing", "wrong-class", "duplicate", "failure", "error", "skipped"):
                        path = writer.write_report(kind)
                        tree = ET.parse(path)
                        suite = tree.getroot()
                        case = next(c for c in suite if c.get("classname") == classname and c.get("name") == name)
                        if mutation == "missing":
                            suite.remove(case)
                        elif mutation == "wrong-class":
                            case.set("classname", "SYNTHETIC-wrong-class")
                        elif mutation == "duplicate":
                            ET.SubElement(suite, "testcase", **case.attrib)
                        else:
                            ET.SubElement(case, mutation)
                        tree.write(path)
                        with self.subTest(kind=kind, name=name, mutation=mutation), self.assertRaises(ValueError):
                            verify_reports(self.directory, kind)

    def test_each_dashboard_image_requires_exact_route_configuration_and_native_owner_receipts(self):
        for name in DASHBOARD_CAPTURES:
            dashboard_captures(self.directory, self.fixture)
            path = self.directory / (name + ".png")
            self.assertEqual(dashboard_capture_receipt(path)["name"], name)
            original = json.loads(path.with_suffix(".json").read_text())
            mutations = (("route", "SYNTHETIC-wrong-route"), ("requestMode", "NONE"), ("api", 35),
                         ("layoutFontScale", True), ("density", float("nan")), ("visibleRoots", []),
                         ("requests", 99), ("sourceKind", "INVALID"), ("windows", [{}]),
                         ("uiCursor", "SYNTHETIC-hidden-cursor"))
            for key, value in mutations:
                data = dict(original); data[key] = value
                path.with_suffix(".json").write_text(json.dumps(data))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)
            path.with_suffix(".json").write_text(json.dumps(original))
            if original["sourceKind"] != "NONE":
                for key, value in (("markerCount", 0), ("connectionCount", 1), ("canvasClippedLeft", .5), ("pixelSamples", [])):
                    data = dict(original); data[key] = value
                    path.with_suffix(".json").write_text(json.dumps(data))
                    with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                        dashboard_capture_receipt(path)
                path.with_suffix(".json").write_text(json.dumps(original))
                path.write_bytes(self.fixture.png(4, 5))
                with self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)

    def test_request_boundary_keeps_single_owner_exact_cursor_and_explicit_reread_distinction(self):
        main = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"
        scan = (main / "history/CurrentHistoryRead.kt").read_text()
        recorder = (main / "history/UsageHistoryRecorder.kt").read_text()
        root = (main / "DashboardNavigation.kt").read_text()
        stored = (main / "ConnectionHistory.kt").read_text()
        self.assertIn("scan(access, query.after", scan)
        self.assertIn("scan(access, null", scan)
        self.assertIn("query.storage(partition)", scan)
        self.assertIn("if (request == next) return", recorder)
        self.assertIn("fun query(next: HistoryGraphQuery) = select", recorder)
        self.assertIn("current.permission.context.selection === requested", recorder)
        self.assertIn("controller.currentHistoryDisplayContext()", root)
        self.assertIn("controller.historySnapshots.collectAsState()", stored)
        self.assertNotRegex(root + stored, r"rememberSaveable|SQLiteHistory|usageForeground|Instant\.now")
        self.assertNotIn("historySnapshots.value", root + stored)
        strings = {
            x.get("name"): x.text or "" for x in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        self.assertEqual(strings["drawer_history"], "full local history details")
        self.assertEqual(strings["drawer_connection"], "full love connection details")
        self.assertFalse(any("hour precision" in x for x in strings.values()))
