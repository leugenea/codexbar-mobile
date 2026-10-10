"""Synthetic fail-closed report controls; not application or native execution evidence."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
from verify_test_reports import ACCOUNT_REMOVAL_CASES, ACCOUNT_REMOVAL_CLASS, verify_reports
from verify_test_reports import DEVICE_CODE_COPY_CASES

ROOT = Path(__file__).resolve().parents[2]


class AccountRemovalContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_all_removal_cases_are_mandatory_and_preserve_inherited_cases(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/AccountRemovalLifecycleTest.kt").read_text()
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)), ACCOUNT_REMOVAL_CASES)
        self.fixture.report()
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 115 + len(ACCOUNT_REMOVAL_CASES) + len(DEVICE_CODE_COPY_CASES))
        for name in ACCOUNT_REMOVAL_CASES:
            self.fixture.report(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native local account removal"):
                verify_reports(self.directory, "native")

    def test_removal_cases_reject_failure_error_skip_and_class_spoofing(self):
        for name in ACCOUNT_REMOVAL_CASES:
            for mutation in ("failure", "error", "skipped", "wrong-class"):
                path = self.fixture.report()
                tree = ET.parse(path)
                case = next(case for case in tree.getroot().iter("testcase") if case.get("name") == name)
                if mutation == "wrong-class":
                    case.set("classname", ACCOUNT_REMOVAL_CLASS + ".wrong")
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")
