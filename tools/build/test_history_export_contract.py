"""Issue167 source/schema/report contracts; no Android or JVM application execution."""
import copy
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

import jsonschema
from test_dashboard_contract import annotated_methods
import test_workflow_contract
from verify_test_reports import HISTORY_EXPORT_JVM, HISTORY_EXPORT_NATIVE, verify_reports

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_JVM = {
    "io.github.leugenea.codexbarmobile.history.HistoryExportTest": {
        "emptyHistoryRoundTripsWithZeroHighWaterAndNoInventedEntries",
        "statusOnlyRoundTripPreservesFieldKnowledgeSlotsAndIndependentFlags",
        "measuredRoundTripKeepsExactDecimalScaleFractionalResetAndClock",
        "gapsAndMultipleSegmentsRoundTripWithEvictedOriginsAndContinuationTail",
        "checkedInV1FixtureDecodesToExpectedEntriesAndContinuation",
        "unknownFieldsAtEveryObjectLevelAreIgnoredAndDestinationPartitionIsExplicit",
        "newerVersionAndMalformedKnownFieldsAreRejectedWithoutStorageWrites",
        "explicitOutputAllowlistNeverIncludesSecretsAccountPlanOrLocalPartition",
    },
}
EXPECTED_NATIVE = {
    "io.github.leugenea.codexbarmobile.HistoryExportLifecycleTest": {
        "localHistoryExportLaunchesJsonSafAndWritesCoherentPagedHistoryOffMain",
    },
}


class HistoryExportContracts(unittest.TestCase):
    def test_exact_export_tests_are_required_once_and_cannot_be_spoofed(self):
        self.assertEqual(EXPECTED_JVM, HISTORY_EXPORT_JVM)
        self.assertEqual(EXPECTED_NATIVE, HISTORY_EXPORT_NATIVE)
        writer = test_workflow_contract.SyntheticReportTests()
        writer.setUp()
        self.addCleanup(writer.doCleanups)
        for kind, registry, source_set in (("jvm", EXPECTED_JVM, "test"), ("native", EXPECTED_NATIVE, "androidTest")):
            for classname, methods in registry.items():
                source = ROOT / f"app/src/{source_set}/java/{classname.replace('.', '/')}.kt"
                self.assertEqual(annotated_methods(source.read_text(), classname), methods)
                for method in methods:
                    for mutation in ("missing", "duplicate", "wrong-class", "failure", "error", "skipped"):
                        path = writer.write_report(kind)
                        tree = ET.parse(path)
                        suite = tree.getroot()
                        case = next(c for c in suite if c.get("classname") == classname and c.get("name") == method)
                        if mutation == "missing":
                            suite.remove(case)
                        elif mutation == "duplicate":
                            ET.SubElement(suite, "testcase", **case.attrib)
                        elif mutation == "wrong-class":
                            case.set("classname", "SYNTHETIC-spoof")
                        else:
                            ET.SubElement(case, mutation)
                        tree.write(path)
                        with self.subTest(kind=kind, method=method, mutation=mutation), self.assertRaises(ValueError):
                            verify_reports(writer.directory, kind)

    def test_checked_in_fixture_matches_versioned_schema_and_unknown_fields_are_additive(self):
        schema = json.loads((ROOT / "docs/history-export-v1.schema.json").read_text())
        fixture = json.loads((ROOT / "app/src/test/resources/history-export-v1.json").read_text())
        jsonschema.Draft202012Validator.check_schema(schema)
        validator = jsonschema.Draft202012Validator(schema)
        validator.validate(fixture)
        extended = copy.deepcopy(fixture)
        extended["future"] = {"unknown": True}
        extended["continuation"]["cursor"]["fiveHour"]["future"] = 42
        validator.validate(extended)
        for key, value in (("formatVersion", 2), ("formatVersion", "1"), ("appVersion", None), ("entries", {})):
            invalid = dict(fixture, **{key: value})
            with self.subTest(key=key, value=value), self.assertRaises(jsonschema.ValidationError):
                validator.validate(invalid)
        docs = (ROOT / "docs/history-export.md").read_text()
        self.assertIn("Unknown object members are ignored", docs)
        self.assertIn("Future app versions must keep reading v1", docs)
        self.assertIn("docs/history-export.md", (ROOT / "ARCHITECTURE.md").read_text())

    def test_export_is_saf_only_and_root_registered_without_new_permission_or_provider_reads(self):
        main = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"
        action = (main / "HistoryExportAction.kt").read_text()
        root = (main / "DashboardNavigation.kt").read_text()
        self.assertIn('ActivityResultContracts.CreateDocument("application/json")', action)
        self.assertIn('openOutputStream(uri, "wt")', action)
        self.assertIn("withContext(Dispatchers.IO)", action)
        self.assertIn("val exporter = historyExporter(controller)", root)
        self.assertIn("HistoryExportAction(exporter)", root)
        self.assertNotIn("readUsage", action)
        self.assertNotIn("rememberSaveable", action)
        writer = (main / "history/HistoryJsonWriter.kt").read_text()
        self.assertNotIn(".partition", writer)
        self.assertNotIn("accountName", writer)


if __name__ == "__main__":
    unittest.main()
