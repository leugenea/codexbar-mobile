"""Synthetic/source controls for issue142; no application/native execution or visual approval."""
import copy
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

from tree_sitter import Language, Parser
import tree_sitter_kotlin
from dashboard_contract_expectations import (EXPECTED_DASHBOARD_JVM, EXPECTED_DASHBOARD_NATIVE,
    EXPECTED_SCANNER_ADDITIONS, EXPECTED_CURRENT_READ_JVM, PROTECTED_MIXED_SOURCE, SCANNER_CLASS,
    EXPECTED_DASHBOARD_CAPTURE_SPECS, EXPECTED_DASHBOARD_LAYOUTS, EXPECTED_CURRENT_HOST,
    EXPECTED_DASHBOARD_NATIVE_COUNT, EXPECTED_DASHBOARD_JVM_COUNT)
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
                    currentHost=EXPECTED_CURRENT_HOST if mode == "CURRENT" else None)
        if mode == "CURRENT":
            data["windows"] = [dict(kind=k, limit=100000, after=0, reset="2027-01-15T09:00:00Z", duration=d,
                                    selectedOrdinals=[1], lastAdmitted=1) for k, d in (("FIVE_HOUR", 18000), ("WEEKLY", 604800))]
        if name == "dashboard-drawer-portrait-dark":
            data["drawerLabels"] = ["Full local history details", "Full live connection details", "Account settings"]
        if name in {"dashboard-banked-table-portrait-light", "dashboard-connection-details-landscape-dark"}:
            data.update(inventoryCount=7, inventoryRows=3)
        image = fixture.png(*size)
        if kind != "NONE":
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
        self.assertEqual(DASHBOARD_JVM, EXPECTED_DASHBOARD_JVM)
        self.assertEqual(DASHBOARD_NATIVE, EXPECTED_DASHBOARD_NATIVE)
        self.assertEqual(DASHBOARD_NATIVE_COUNT, EXPECTED_DASHBOARD_NATIVE_COUNT)
        self.assertEqual(DASHBOARD_JVM_COUNT, EXPECTED_DASHBOARD_JVM_COUNT)
        self.assertEqual(CURRENT_HISTORY_JVM[SCANNER_CLASS], EXPECTED_CURRENT_READ_JVM)
        self.assertEqual(sum(map(len, EXPECTED_DASHBOARD_NATIVE.values())), 17)
        self.assertEqual(sum(map(len, EXPECTED_DASHBOARD_JVM.values())) + len(EXPECTED_SCANNER_ADDITIONS), 30)
        for kind, registry, source_set in (("jvm", EXPECTED_DASHBOARD_JVM, "test"), ("native", EXPECTED_DASHBOARD_NATIVE, "androidTest")):
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

    def test_metadata_ticks_reuse_lazy_analytics_and_remember_drawing_by_stable_series(self):
        main = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"
        graph = (main / "history/HistoryProjection.kt").read_text()
        self.assertIn("val entries: List<HistoryGraphEntry> by lazy", graph)
        self.assertIn("val points: List<HistoryPoint> by lazy", graph)
        dashboard = (main / "DashboardScreen.kt").read_text()
        self.assertIn("remember { HistoryPlotCache() }.project(display)", dashboard)
        self.assertIn("remember(series) { DashboardPresentation.hasChart(series) }", dashboard)
        compact = (main / "CompactHistoryChart.kt").read_text()
        self.assertIn("remember(series) { historyChartDrawing(series) }", compact)
        self.assertIn("DashboardPresentation.paceComparison(display, observation, series.kind)", compact)

    def test_simplified_window_copy_removes_retired_lines_without_rounding_stored_history(self):
        main = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"
        resources = {item.get("name"): item.text for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        for removed in ("dashboard_single_observation", "dashboard_recorded_observations", "dashboard_history_incomplete"):
            self.assertNotIn(removed, resources)
        self.assertEqual(resources["dashboard_next_reset"], "Next reset in %1$s")
        self.assertEqual(resources["dashboard_over_pace"], "Over pace by %1$s%%")
        self.assertEqual(resources["dashboard_reserve"], "%1$s%% in reserve")
        self.assertEqual(resources["dashboard_on_pace"], "On pace")
        compact = (main / "CompactHistoryChart.kt").read_text()
        for removed in ("history_measured", "history_point_time", "history_delta", "CompactHistoryFacts", "incomplete("):
            self.assertNotIn(removed, compact)
        dashboard = (main / "DashboardScreen.kt").read_text()
        self.assertNotIn("live_reset_absolute", dashboard)
        self.assertIn("remainingTimeLabel(it)", dashboard)
        self.assertIn('testTag("dashboard-observed-at")', dashboard)
        # Full stored text is the deliberate exact-value route, not the compact window block.
        stored = (main / "HistoryTextPresentation.kt").read_text()
        self.assertIn("comparison.deltaPercentagePoints.toString()", stored)
        self.assertNotIn("setScale", stored)

    def test_banked_card_keeps_shared_style_wrapping_table_and_no_disclosure_state(self):
        main = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"
        card = (main / "BankedResetCard.kt").read_text()
        windows = (main / "DashboardScreen.kt").read_text()
        for shared in ("Card(Modifier.fillMaxWidth()", "Column(Modifier.padding(20.dp)",
                       "Arrangement.spacedBy(12.dp)", "MaterialTheme.typography.titleLarge"):
            self.assertIn(shared, card)
            self.assertIn(shared, windows)
        for required in ("TimePresentation.chartTime(", "remainingTimeLabel(it)",
                         "HorizontalDivider(", "Modifier.weight(1f)", "TextAlign.End",
                         "semantics(mergeDescendants = true)", "contentDescription = description",
                         "model.entitlements.items.isNotEmpty()"):
            self.assertIn(required, card)
        for removed in ("OutlinedButton", "expanded", "remember", "maxLines", "TextOverflow"):
            self.assertNotIn(removed, card)
        self.assertEqual(card.count("Card(Modifier"), 1)
        self.assertFalse((main / "BankedResetDisclosure.kt").exists())
        resources = {item.get("name"): item.text for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        self.assertNotIn("dashboard_expanded", resources)
        self.assertNotIn("dashboard_collapsed", resources)
        self.assertEqual(resources["dashboard_banked_expires"], "Expires")
        self.assertEqual(resources["dashboard_banked_remaining"], "Remaining")
        self.assertEqual(resources["dashboard_banked_row"], "Banked reset %1$d, expires %2$s, %3$s")

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
                         ("currentHost", {}))
            for key, value in mutations:
                data = dict(original); data[key] = value
                path.with_suffix(".json").write_text(json.dumps(data))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)
            for key in ("visibleRoots", "layoutLabel", "currentHost"):
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
                for key, value in (("observationCount", 0), ("connectionCount", 1), ("canvasClippedLeft", .5), ("pixelSamples", [])):
                    data = dict(original); data[key] = value
                    path.with_suffix(".json").write_text(json.dumps(data))
                    with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                        dashboard_capture_receipt(path)
                path.with_suffix(".json").write_text(json.dumps(original))
                path.write_bytes(self.fixture.png(4, 5))
                with self.assertRaises(ValueError):
                    dashboard_capture_receipt(path)

    def test_source_annotations_and_class_identity_cannot_be_spoofed_by_names(self):
        classname = "io.github.leugenea.codexbarmobile.DashboardUiTest"
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/DashboardUiTest.kt").read_text()
        expected = EXPECTED_DASHBOARD_NATIVE[classname]
        for name in expected:
            without_annotation = source.replace(f"@Test fun {name}", f"fun {name}")
            self.assertNotIn(name, annotated_methods(without_annotation, classname))
        wrong_class = source.replace("class DashboardUiTest", "class UnrelatedTest")
        self.assertEqual(annotated_methods(wrong_class, classname), set())
        scanner = ROOT / f"app/src/test/java/{SCANNER_CLASS.replace('.', '/')}.kt"
        self.assertEqual(annotated_methods(scanner.read_text(), SCANNER_CLASS), EXPECTED_CURRENT_READ_JVM)

    def test_mixed_source_census_rejects_extra_annotated_methods_without_expanding_report_targets(self):
        for classname in (
                "io.github.leugenea.codexbarmobile.history.HistoryDisplayAuthorityTest",
                "io.github.leugenea.codexbarmobile.history.UsageHistoryRecorderTest"):
            protected = PROTECTED_MIXED_SOURCE[classname]
            source = ROOT / f"app/src/test/java/{classname.replace('.', '/')}.kt"
            text = source.read_text()
            expected = protected | EXPECTED_DASHBOARD_JVM[classname]
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
            self.assertTrue(protected.isdisjoint(EXPECTED_DASHBOARD_JVM[classname]))

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
        for host in (root, stored):
            self.assertIn("remember(controller, source.generation)", host)
            self.assertNotIn("remember(controller, authority?.generation", host)
        controller = (main / "ConnectionController.kt").read_text()
        self.assertIn("command(UsageForegroundCommand(observer, foreground))", controller)
        self.assertIn("context: CoroutineContext = EmptyCoroutineContext", controller)
        self.assertIn("AbstractCoroutineContextElement(Key)", controller)
        for name in ("LaunchForegroundCheckpoint.kt", "UsageRefreshLifecycleTest.kt"):
            ledger = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile" / name).read_text()
            self.assertIn("context[UsageForegroundCommand]", ledger)
            self.assertIn("typed.foreground" if name.startswith("Launch") else "ticket.foreground", ledger)
            self.assertIn("submittedObservers", ledger)
        capture = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/DashboardCapture.kt").read_text()
        self.assertIn("observer.hold()", capture)
        self.assertIn("finally { observer.release() }", capture)
        self.assertIn("revalidate(frame, scenario, fixture.owner)", capture)
        for begin, end in (("private fun window(", "private fun chart("), ("private fun chart(", "private fun save(")):
            helper = capture[capture.index(begin):capture.index(end)]
            self.assertIn("frame.current", helper)
            self.assertNotIn("historySnapshots.value", helper)
            self.assertNotIn("observer.currentFrame", helper)
            self.assertNotIn("recordedHistory(owner", helper)
        strings = {
            x.get("name"): x.text or "" for x in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
        self.assertEqual(strings["drawer_history"], "Full local history details")
        self.assertEqual(strings["drawer_connection"], "Full live connection details")
        self.assertFalse(any("hour precision" in x for x in strings.values()))
