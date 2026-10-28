"""Synthetic/source controls for issue142; no application/native execution or visual approval."""
import copy
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

from tree_sitter import Language, Parser
import tree_sitter_kotlin
from dashboard_contract_expectations import (PROTECTED_MIXED_SOURCE, SCANNER_CLASS,
    EXPECTED_DASHBOARD_CAPTURE_SPECS, EXPECTED_DASHBOARD_LAYOUTS, EXPECTED_CURRENT_HOST,
    EXPECTED_DASHBOARD_NATIVE_COUNT, EXPECTED_DASHBOARD_JVM_COUNT, EXPECTED_BANKED_TABLE_ROWS,
    EXPECTED_COMPACT_COPY)
from verify_test_reports import DASHBOARD_JVM, DASHBOARD_NATIVE, DASHBOARD_NATIVE_COUNT, DASHBOARD_JVM_COUNT, CURRENT_HISTORY_JVM, verify_reports
from verify_history_text_captures import DASHBOARD_CAPTURES, DASHBOARD_LAYOUTS, dashboard_capture_receipt
import test_history_text_contract as history_fixture
import test_workflow_contract

ROOT = Path(__file__).resolve().parents[2]


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


def annotated_methods(text, classname):
    """Only @Test methods directly owned by the intended Kotlin class/package."""
    data = text.encode()
    tree = Parser(Language(tree_sitter_kotlin.language())).parse(data)
    package = next(node for node in tree.root_node.named_children if node.type == "package_header")
    actual_package = data[package.start_byte:package.end_byte].decode().removeprefix("package ").strip()
    methods = set()
    for declaration in tree.root_node.named_children:
        if declaration.type != "class_declaration":
            continue
        name = next(node for node in declaration.named_children if node.type == "identifier")
        if actual_package + "." + data[name.start_byte:name.end_byte].decode() != classname:
            continue
        body = next(node for node in declaration.named_children if node.type == "class_body")
        for function in body.named_children:
            if function.type != "function_declaration":
                continue
            modifiers = next((node for node in function.named_children if node.type == "modifiers"), None)
            if modifiers is None or not re.search(r"@(?:org\.junit\.)?Test\b", data[modifiers.start_byte:modifiers.end_byte].decode()):
                continue
            name = function.child_by_field_name("name")
            methods.add(data[name.start_byte:name.end_byte].decode())
    return methods


class DashboardContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = history_fixture.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_exact_additive_dashboard_identities_fail_closed_without_removing_inherited_cases(self):
        writer = test_workflow_contract.SyntheticReportTests()
        writer.directory = self.directory
        self.assertEqual(DASHBOARD_NATIVE_COUNT, EXPECTED_DASHBOARD_NATIVE_COUNT)
        self.assertEqual(DASHBOARD_JVM_COUNT, EXPECTED_DASHBOARD_JVM_COUNT)
        for kind, registry, source_set in (("jvm", DASHBOARD_JVM, "test"), ("native", DASHBOARD_NATIVE, "androidTest")):
            for classname, required in registry.items():
                source = ROOT / f"app/src/{source_set}/java/{classname.replace('.', '/')}.kt"
                actual = annotated_methods(source.read_text(), classname)
                protected = PROTECTED_MIXED_SOURCE.get(classname)
                if protected is None:
                    self.assertEqual(required, actual)
                else:
                    self.assertEqual(protected | required, actual)
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

    def test_simplified_window_resources_retain_the_approved_copy(self):
        resources = {item.get("name"): item.text for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        for removed in ("dashboard_single_observation", "dashboard_recorded_observations", "dashboard_history_incomplete"):
            self.assertNotIn(removed, resources)
        self.assertEqual(resources["dashboard_updated"], "Updated %1$s")
        self.assertEqual(resources["dashboard_next_reset"], "Next reset in %1$s")
        self.assertEqual(resources["dashboard_over_pace"], "Over pace by %1$s%%")
        self.assertEqual(resources["dashboard_reserve"], "%1$s%% in reserve")
        self.assertEqual(resources["dashboard_on_pace"], "On pace")
        self.assertEqual(resources["drawer_history"], "Full local history details")
        self.assertEqual(resources["drawer_connection"], "Full live connection details")
        self.assertFalse(any("hour precision" in (value or "") for value in resources.values()))

    def test_banked_table_resources_retain_the_approved_copy(self):
        resources = {item.get("name"): item.text for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        self.assertNotIn("dashboard_expanded", resources)
        self.assertNotIn("dashboard_collapsed", resources)
        self.assertEqual(resources["dashboard_banked_expires"], "Expires")
        self.assertEqual(resources["dashboard_banked_remaining"], "Remaining")
        self.assertEqual(resources["dashboard_banked_row"], "Banked reset %1$d, %2$s")
        self.assertEqual(resources["dashboard_banked_missing_expiry"], "—")
        self.assertEqual(resources["dashboard_banked_expiry_unknown"], "expiry unknown")
        self.assertEqual(resources["dashboard_banked_expires_on"], "expires %1$s")
        self.assertEqual(resources["dashboard_banked_expired_on"], "expired %1$s")
        self.assertEqual(resources["dashboard_banked_state"], "state %1$s")
        for state in ("unknown", "unsupported", "unavailable", "malformed", "discrepant"):
            self.assertEqual(resources[f"dashboard_banked_state_{state}"], state.capitalize())

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

    def test_source_annotations_and_class_identity_cannot_be_spoofed_by_names(self):
        classname = "io.github.leugenea.codexbarmobile.DashboardUiTest"
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/DashboardUiTest.kt").read_text()
        expected = DASHBOARD_NATIVE[classname]
        for name in expected:
            without_annotation = re.sub(rf"@Test\s+(?=fun {re.escape(name)}\b)", "", source)
            self.assertNotIn(name, annotated_methods(without_annotation, classname))
        wrong_class = source.replace("class DashboardUiTest", "class UnrelatedTest")
        self.assertEqual(annotated_methods(wrong_class, classname), set())
        scanner = ROOT / f"app/src/test/java/{SCANNER_CLASS.replace('.', '/')}.kt"
        self.assertEqual(annotated_methods(scanner.read_text(), SCANNER_CLASS), CURRENT_HISTORY_JVM[SCANNER_CLASS])

    def test_mixed_source_census_rejects_extra_annotated_methods_without_expanding_report_targets(self):
        for classname in (
                "io.github.leugenea.codexbarmobile.history.HistoryDisplayAuthorityTest",
                "io.github.leugenea.codexbarmobile.history.UsageHistoryRecorderTest"):
            protected = PROTECTED_MIXED_SOURCE[classname]
            source = ROOT / f"app/src/test/java/{classname.replace('.', '/')}.kt"
            text = source.read_text()
            expected = protected | DASHBOARD_JVM[classname]
            self.assertEqual(expected, annotated_methods(text, classname))
            declaration = f"class {classname.rsplit('.', 1)[1]}"
            body = text.index("{", text.index(declaration)) + 1
            extra = text[:body] + "\n    @Test fun unregisteredSyntheticRegression() {}\n" + text[body:]
            with self.subTest(classname=classname), self.assertRaises(AssertionError):
                self.assertEqual(expected, annotated_methods(extra, classname))
            for name in expected:
                without_annotation = re.sub(rf"@Test\s+(?=fun {re.escape(name)}\b)", "", text)
                self.assertNotIn(name, annotated_methods(without_annotation, classname))
            wrong_class = text.replace(declaration, "class UnrelatedTest", 1)
            self.assertEqual(set(), annotated_methods(wrong_class, classname))
            # Whole annotated source equality protects old methods without registering
            # them as additional mandatory report identities.
            self.assertTrue(protected.isdisjoint(DASHBOARD_JVM[classname]))
