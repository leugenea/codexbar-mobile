"""Account settings ownership/source contracts; not native runtime or visual evidence."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"


class AccountSettingsContracts(unittest.TestCase):
    def test_account_management_has_only_the_settings_host(self):
        calls = {name: [] for name in ("AccountNameHeader", "AccountRemovalAction", "DeviceLoginCode")}
        tags = {name: [] for name in ("connect", "cancel-connect", "open-browser", "verification-url")}
        for path in MAIN.rglob("*.kt"):
            source = path.read_text()
            for name in calls:
                if re.search(rf"(?<!fun )\b{name}\(", source):
                    calls[name].append(path.name)
            for name in tags:
                if f'testTag("{name}")' in source:
                    tags[name].append(path.name)
        for name, hosts in (calls | tags).items():
            with self.subTest(control=name):
                self.assertEqual(hosts, ["AccountSettingsScreen.kt"])
        settings = (MAIN / "AccountSettingsScreen.kt").read_text()
        for command in ("controller::connect", "controller::cancel", "controller::renameAccount"):
            self.assertIn(command, settings)
        self.assertNotIn("rememberSaveable", settings)

    def test_navigation_and_disconnected_copy_point_to_settings_without_inline_sign_in(self):
        navigation = (MAIN / "DashboardNavigation.kt").read_text()
        self.assertIn("ACCOUNT_SETTINGS(R.string.account_settings_title)", navigation)
        self.assertIn("DashboardRoute.ACCOUNT_SETTINGS -> AccountSettingsScreen", navigation)
        self.assertIn("DashboardRoute.CONNECTION_DETAILS, DashboardRoute.ACCOUNT_SETTINGS -> HistoryPresentationRequest.Idle", navigation)
        self.assertIn('testTag("drawer-ACCOUNT_SETTINGS")', navigation)
        self.assertIn("Column(Modifier.weight(1f).verticalScroll(rememberScrollState()))", navigation)
        for filename in ("DashboardScreen.kt", "ConnectionScreen.kt"):
            self.assertIn("AccountSettingsLink(openAccountSettings)", (MAIN / filename).read_text())
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {node.get("name"): node.text for node in strings.findall("string")}
        self.assertEqual(values["account_settings_title"], "Account settings")
        self.assertEqual(values["account_settings_required"], "Add an account in Account settings to view usage.")
        for key in ("live_disconnected", "live_reauthorize"):
            self.assertIn("Account settings", values[key])
            self.assertNotIn("controls below", values[key])
