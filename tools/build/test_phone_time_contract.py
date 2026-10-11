"""Source and synthetic mandatory-result controls; no Android/application execution."""
import copy
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
import test_native_coverage_retry as retry_contract
from verify_test_reports import PHONE_TIME_CASES, HISTORY_NAVIGATION_CLASS, verify_reports

ROOT = Path(__file__).resolve().parents[2]


class PhoneTimeContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def test_exact_native_family_and_each_missing_failed_errored_skipped_spoofed_duplicate_case(self):
        self.assertEqual(PHONE_TIME_CASES, {
            "productionLiveAndBankedClocksFollowSystemZoneResourceLocaleAndRecreationWithoutReads",
            "productionHistoryClocksFollowSystemZoneResourceLocaleAndRecreationWithoutReads",
        })
        for name in PHONE_TIME_CASES:
            for mutation in ("missing", "failure", "error", "skipped", "wrong-class", "duplicate"):
                path = self.fixture.report()
                tree = ET.parse(path)
                root = tree.getroot()
                case = next(item for item in root if item.get("classname") == HISTORY_NAVIGATION_CLASS and item.get("name") == name)
                if mutation == "missing":
                    root.remove(case)
                elif mutation == "wrong-class":
                    case.set("classname", "SYNTHETIC-wrong-class")
                elif mutation == "duplicate":
                    root.append(copy.deepcopy(case))
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.fixture.directory, "native")

    def test_each_new_case_prevents_retry_on_omission_failure_error_skip_spoof_or_duplicate(self):
        fixture = retry_contract.RetryContracts()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        for name in PHONE_TIME_CASES:
            for mutation in ("missing", "failure", "error", "skipped", "wrong-class", "duplicate"):
                retry_contract.synthetic_reports(fixture.build)
                path = fixture.build / "outputs/androidTest-results/connected/SYNTHETIC.xml"
                tree = ET.parse(path)
                root = tree.getroot()
                case = next(item for item in root if item.get("name") == name)
                if mutation == "missing":
                    root.remove(case)
                elif mutation == "wrong-class":
                    case.set("classname", "SYNTHETIC-wrong-class")
                elif mutation == "duplicate":
                    root.append(copy.deepcopy(case))
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    fixture.require()

    def test_shared_projection_and_scoped_observer_preserve_presentation_boundary(self):
        main = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"
        observer = (main / "PhoneTimeConfiguration.kt").read_text()
        self.assertIn("Intent.ACTION_TIMEZONE_CHANGED", observer)
        self.assertIn("ContextCompat.RECEIVER_NOT_EXPORTED", observer)
        self.assertIn("LocalConfiguration.current.locales[0]", observer)
        self.assertIn("Lifecycle.Event.ON_RESUME", observer)
        self.assertIn("lifecycle.removeObserver(observer)", observer)
        self.assertIn("context.unregisterReceiver(receiver)", observer)
        self.assertNotRegex(observer, r"readUsage|ConnectionController|delay\(|Timer|Clock")
        for name in ("LiveUsageScreen.kt", "ConnectionHistory.kt"):
            source = (main / name).read_text()
            self.assertIn("phoneTimeConfiguration()", source)
            self.assertNotIn("ZoneId.systemDefault()", source)
        presentation = (main / "HistoryTextPresentation.kt").read_text()
        self.assertNotRegex(presentation, r"(?:observedAt|nominalStartAt|resetAt)\??\.toString\(\)")
        self.assertIn("timeField(R.string.history_reset_exact", presentation)
        self.assertIn("timeField(R.string.history_reset_derived", presentation)
        native = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/PhoneTimeEvidence.kt").read_text()
        for expected in ("ActivityScenario.launch(MainActivity::class.java)", "scenario.recreate()", "compose.waitUntil",
                         "alarm.setTimeZone(id)", "applicationLocales", "fixture.transport.counts()", "assertSame",
                         "capture.layout", "SemanticsProperties.ContentDescription", "SemanticsProperties.StateDescription"):
            self.assertIn(expected, native)
        self.assertNotRegex(native, r"setContent|runOnIdle|runBlocking|Thread\.sleep|delay\(")
        tests = (ROOT / "app/src/test/java/io/github/leugenea/codexbarmobile/TimestampPresentationTest.kt").read_text()
        self.assertEqual(len(re.findall(r"@Test\s+fun", tests)), 6)


if __name__ == "__main__":
    unittest.main()
