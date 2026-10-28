"""Static/synthetic report controls for #90; no application or native execution."""
from verify_test_reports import CURRENT_HISTORY_NATIVE_COUNT
from background_refresh_contract_expectations import BACKGROUND_NATIVE_CASES
from periodic_refresh_contract_expectations import PERIODIC_NATIVE_COUNT
from dashboard_contract_expectations import EXPECTED_DASHBOARD_NATIVE_COUNT
from verify_test_reports import HISTORY_EXPORT_NATIVE_COUNT, HISTORY_IMPORT_NATIVE_COUNT
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
from verify_test_reports import HISTORY_AUTHORITY_CASES, HISTORY_ISOLATION_CASES, HISTORY_ISOLATION_CLASS, verify_reports
from verify_test_reports import ACCOUNT_REMOVAL_CASES
from verify_test_reports import DEVICE_CODE_COPY_CASES
from verify_test_reports import ACCOUNT_NAME_CASES, ACCOUNT_NAME_CLASS
from verify_test_reports import HISTORY_ASSEMBLED_CASES

from verify_test_reports import PHONE_TIME_CASES

ROOT = Path(__file__).resolve().parents[2]


class HistoryIsolationContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_exact_integrated_isolation_identities_extend_all_101_inherited_cases(self):
        source = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/HistoryIsolationTest.kt"
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source.read_text())), HISTORY_ISOLATION_CASES)
        self.fixture.report()
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 100 + len(BACKGROUND_NATIVE_CASES) + PERIODIC_NATIVE_COUNT + len(PHONE_TIME_CASES) + len(HISTORY_AUTHORITY_CASES) + len(HISTORY_ISOLATION_CASES) + len(HISTORY_ASSEMBLED_CASES) + len(ACCOUNT_NAME_CASES) + len(ACCOUNT_REMOVAL_CASES) + len(DEVICE_CODE_COPY_CASES) + CURRENT_HISTORY_NATIVE_COUNT + EXPECTED_DASHBOARD_NATIVE_COUNT + HISTORY_EXPORT_NATIVE_COUNT + HISTORY_IMPORT_NATIVE_COUNT)
        for name in HISTORY_ISOLATION_CASES:
            self.fixture.report(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native history isolation"):
                verify_reports(self.directory, "native")

    def test_every_case_fails_closed_on_error_failure_skip_and_class_spoof(self):
        for name in HISTORY_ISOLATION_CASES:
            for mutation in ("error", "failure", "skipped", "wrong-class"):
                path = self.fixture.report()
                tree = ET.parse(path)
                case = next(case for case in tree.getroot().iter("testcase") if case.get("name") == name)
                if mutation == "wrong-class":
                    case.set("classname", HISTORY_ISOLATION_CLASS + ".wrong")
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")


if __name__ == "__main__":
    unittest.main()
