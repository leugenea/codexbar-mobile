"""Synthetic parser controls for mandatory naming cases; not native execution evidence."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
from verify_test_reports import ACCOUNT_NAME_CASES, ACCOUNT_NAME_CLASS, verify_reports

ROOT = Path(__file__).resolve().parents[2]


class AccountNameContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_native_cases_extend_the_registry_and_each_is_required(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/AccountNameLifecycleTest.kt").read_text()
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)), ACCOUNT_NAME_CASES)
        self.fixture.report()
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 111 + len(ACCOUNT_NAME_CASES))
        for name in ACCOUNT_NAME_CASES:
            self.fixture.report(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native local account name"):
                verify_reports(self.directory, "native")

    def test_each_case_rejects_failure_error_skip_and_wrong_class(self):
        for name in ACCOUNT_NAME_CASES:
            for mutation in ("failure", "error", "skipped", "wrong-class"):
                path = self.fixture.report()
                tree = ET.parse(path)
                case = next(case for case in tree.getroot().iter("testcase") if case.get("name") == name)
                if mutation == "wrong-class":
                    case.set("classname", ACCOUNT_NAME_CLASS + ".wrong")
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")
