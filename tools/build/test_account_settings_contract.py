"""Parsed account settings resource copy; behavior belongs to the native suites."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class AccountSettingsContracts(unittest.TestCase):
    def test_retired_inline_account_action_strings_are_removed(self):
        retired = {"gate_connect", "dashboard_login", "dashboard_reauthorize_action"}
        for path in (ROOT / "app/src/main/res").rglob("*.xml"):
            names = {node.get("name") for node in ET.parse(path).getroot()}
            with self.subTest(resource_file=path.relative_to(ROOT)):
                self.assertFalse(names & retired)

    def test_disconnected_resource_copy_points_to_account_settings(self):
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {node.get("name"): node.text for node in strings.findall("string")}
        self.assertEqual(values["account_settings_title"], "Account settings")
        self.assertEqual(values["account_settings_required"], "Add an account in Account settings to view usage.")
        for key in ("live_disconnected", "live_reauthorize"):
            self.assertIn("Account settings", values[key])
            self.assertNotIn("controls below", values[key])
