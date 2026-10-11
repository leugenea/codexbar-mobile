"""Synthetic report and write-only source guards; not native execution evidence."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

import test_history_text_contract as text_contract
from verify_test_reports import DEVICE_CODE_COPY_CASES, DEVICE_CODE_COPY_CLASS, verify_reports

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"


class DeviceCodeCopyContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_new_copy_cases_are_mandatory_and_preserve_all_inherited_cases(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/ConnectionLifecycleTest.kt").read_text()
        actual = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source))
        self.assertTrue(DEVICE_CODE_COPY_CASES <= actual)
        self.fixture.report()
        self.assertEqual(verify_reports(self.directory, "native")["testCount"], 120 + len(DEVICE_CODE_COPY_CASES))
        for name in DEVICE_CODE_COPY_CASES:
            self.fixture.report(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing real native device code copy"):
                verify_reports(self.directory, "native")

    def test_copy_cases_reject_failures_errors_skips_and_class_spoofing(self):
        for name in DEVICE_CODE_COPY_CASES:
            for mutation in ("failure", "error", "skipped", "wrong-class"):
                path = self.fixture.report()
                tree = ET.parse(path)
                case = next(case for case in tree.getroot().iter("testcase") if case.get("name") == name)
                if mutation == "wrong-class":
                    case.set("classname", DEVICE_CODE_COPY_CLASS + ".wrong")
                else:
                    ET.SubElement(case, mutation)
                tree.write(path)
                with self.subTest(name=name, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")

    def test_only_adapter_writes_clipboard_and_production_never_reads_it(self):
        writers = []
        for path in MAIN.rglob("*.kt"):
            source = path.read_text()
            self.assertNotRegex(source, r"\b(?:getPrimaryClip|hasPrimaryClip|primaryClip|primaryClipDescription)\b")
            if "setPrimaryClip" in source:
                writers.append(path.name)
        self.assertEqual(writers, ["DeviceCodeClipboard.kt"])
        adapter = (MAIN / "DeviceCodeClipboard.kt").read_text()
        self.assertIn('val key = "android.content.extra.IS_SENSITIVE"', adapter)
        self.assertNotRegex(adapter, r"\bimport android\.(?:os\.Build|content\.ClipDescription)\b|\bsdk\s*:")
        self.assertIn("PersistableBundle().apply { putBoolean(key, true) }", adapter)
        self.assertNotRegex(adapter, r"device_auth_id|code_verifier|access_token|refresh_token")
        ui = (MAIN / "DeviceLoginCode.kt").read_text()
        self.assertNotRegex(ui, r"rememberSaveable|contentDescription|Toast|Log\.")
        self.assertIn('testTag("copy-device-code")', ui)
        self.assertIn("LiveRegionMode.Polite", ui)
