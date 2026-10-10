"""Static/synthetic report controls for #90; no application or native execution."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
from verify_test_reports import HISTORY_AUTHORITY_CASES, HISTORY_ISOLATION_CASES, HISTORY_ISOLATION_CLASS, verify_reports
from verify_test_reports import ACCOUNT_NAME_CASES, ACCOUNT_NAME_CLASS
from verify_test_reports import HISTORY_ASSEMBLED_CASES

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
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 98 + len(HISTORY_AUTHORITY_CASES) + len(HISTORY_ISOLATION_CASES) + len(HISTORY_ASSEMBLED_CASES) + len(ACCOUNT_NAME_CASES))
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

    def test_driver_uses_actual_activity_labeled_receipts_and_both_query_orderings(self):
        native = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile"
        source = (native / "HistoryIsolationTest.kt").read_text()
        fixture = (native / "HistoryIsolationFixture.kt").read_text()
        self.assertIn("ActivityScenario.launch(MainActivity::class.java)", source)
        self.assertEqual(source.count("for (published in listOf(false, true))"), 2)
        self.assertIn("compose.waitUntil", source)
        self.assertIn("fixture.settled", source)
        self.assertIn('hasAnyAncestor(hasTestTag("connection-history"))', source)
        self.assertNotRegex(source, r"setContent|runOnIdle|Thread\.sleep|delay\(")
        self.assertNotRegex(source + fixture, r"Thread\.currentThread\(\)\.name|stackTrace")
        self.assertLess(fixture.index("val outcome = access.read(query)"), fixture.index("gate?.pause(receipt)"))
        self.assertIn("history = HistoryLifetimeCoordinator(storage)", fixture)
        self.assertIn("refreshClock = transport.clock", fixture)
        self.assertIn("gates += it", fixture)
        self.assertIn("finally { gate.release()", source)


if __name__ == "__main__":
    unittest.main()
