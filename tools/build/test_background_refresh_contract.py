"""Cheap issue-194 source/report controls; these do not execute Android/JVM application code."""
import os
from pathlib import Path
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET

from background_refresh_contract_expectations import BACKGROUND_JVM_CLASS, BACKGROUND_JVM_CASES, BACKGROUND_NATIVE_CASES
import test_workflow_contract as workflow
from verify_test_reports import BACKGROUND_REFRESH_JVM, HISTORY_SAMPLING_CLASS, HISTORY_SAMPLING_CASES, verify_reports

ROOT = Path(__file__).resolve().parents[2]
RETAINED_SAMPLING = {
    "admittedEndpointPersistsThroughFreshRuntimeDormancyAndLogoutRejectsLateLoginData",
    "partialEndpointsPreserveActualUsageAndIndependentInventoryClocksWithoutReplay",
    "actualHeldWriteAdmissionAndCommittedWriteAreRevokedBeforeCombinedLogoutCompletion",
    "nativeHistoryReadFailureIsCategoricalAndNeverReplacesLiveUsage",
    "composeForegroundLossRecreationAndFinishRetireCurrentlyUsableHistoryPorts",
}


def mutate(suite, case, mutation):
    if mutation == "missing":
        suite.remove(case)
    elif mutation == "wrong-class":
        case.set("classname", "SYNTHETIC-wrong-class")
    elif mutation == "duplicate":
        ET.SubElement(suite, "testcase", **case.attrib)
    else:
        ET.SubElement(case, mutation)


class BackgroundRefreshContracts(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.writer = workflow.SyntheticReportTests()
        self.writer.directory = self.directory

    def test_source_and_mandatory_sets_match_independent_literal_oracles(self):
        self.assertEqual(len(BACKGROUND_JVM_CASES), 17)
        self.assertEqual(len(BACKGROUND_NATIVE_CASES), 12)
        self.assertEqual(BACKGROUND_REFRESH_JVM, {BACKGROUND_JVM_CLASS: BACKGROUND_JVM_CASES})
        self.assertEqual(HISTORY_SAMPLING_CASES, RETAINED_SAMPLING | BACKGROUND_NATIVE_CASES)
        for source_set, classname, cases in (("test", BACKGROUND_JVM_CLASS, BACKGROUND_JVM_CASES),
                                             ("androidTest", HISTORY_SAMPLING_CLASS, HISTORY_SAMPLING_CASES)):
            source = ROOT / f"app/src/{source_set}/java/{classname.replace('.', '/')}.kt"
            declared = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source.read_text()))
            self.assertEqual(declared, cases)

    def test_every_background_case_rejects_omission_spoof_failure_error_skip_and_duplicate(self):
        families = (("jvm", BACKGROUND_JVM_CLASS, BACKGROUND_JVM_CASES),
                    ("native", HISTORY_SAMPLING_CLASS, BACKGROUND_NATIVE_CASES))
        for kind, classname, cases in families:
            for name in cases:
                self.check_mutations(kind, classname, name)

    def check_mutations(self, kind, classname, name):
        for mutation in ("missing", "wrong-class", "failure", "error", "skipped", "duplicate"):
            path = self.writer.write_report(kind)
            tree = ET.parse(path)
            suite = tree.getroot()
            case = next(c for c in suite if c.get("classname") == classname and c.get("name") == name)
            mutate(suite, case, mutation)
            tree.write(path)
            with self.subTest(kind=kind, name=name, mutation=mutation), self.assertRaises(ValueError):
                verify_reports(self.directory, kind)
