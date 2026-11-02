"""Pure ADB observation/failure precedence and mandatory hosted wiring contracts; no emulator."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import verify_release_installation as observer
from verify_manifests import PACKAGE, MAIN_ACTIVITY, permissions_for, check_installed_dump


class InstallationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = self.root / 'downloaded'
        self.assets.mkdir()
        (self.root / 'certificate.sha256').write_text('c' * 64)
        self.expected = {'versionCode': 2, 'versionName': '0.1.0-rc.1'}
        (self.assets / 'release-manifest.json').write_text(json.dumps({'buildInputs': self.expected}))
        self.apk = self.assets / observer.asset_names(self.expected)[0]
        self.apk.write_bytes(b'SYNTHETIC downloaded signed bytes; no real APK/signature')
        self.calls = []
        self.failure = None
        self.now = 0
        self.ui_scene = 'dashboard'
        self.ui_mutation = lambda text: text
        self.ui_scroll_needed = False
        self.ui_scrolled = False

    def ui_xml(self):
        def action(label, y, enabled=True, description=False):
            return (f'<node package="{PACKAGE}" class="android.widget.Button" clickable="{str(enabled).lower()}" '
                    f'enabled="{str(enabled).lower()}" bounds="[10,{y}][500,{y + 48}]" '
                    f'{"content-desc" if description else "text"}="{label}"/>')
        if self.ui_scene == 'drawer':
            nodes = action('Account settings', 700) + action('Close drawer', 100, description=True)
        elif self.ui_scene == 'account':
            nodes = (f'<node package="{PACKAGE}" text="Local usage history"/>' + action('Export history', 300)
                     + action('Import history', 400, False)) if not self.ui_scroll_needed or self.ui_scrolled else action('Add account', 200)
            nodes = f'<node package="{PACKAGE}" scrollable="true" bounds="[0,100][1080,1800]">{nodes}</node>'
        else:
            nodes = action('Open details menu', 10, description=True) + action('Account settings', 200)
        return self.ui_mutation('<hierarchy>' + nodes + '</hierarchy>')

    def clock(self):
        self.now += 3
        return self.now

    def adb(self, command, **kwargs):
        args = command[3:]
        self.calls.append(args)
        if self.failure is not None:
            result = self.failure(args)
            if result is not None:
                return result
        if args[0] in ('install', 'uninstall'):
            text = 'Success\n'
        elif args[:3] == ['shell', 'pm', 'path']:
            text = f'package:/data/app/~~synthetic/{PACKAGE}-abc==/base.apk\n'
        elif args[:3] == ['shell', 'dumpsys', 'package']:
            text = f'  Package [{PACKAGE}] (synthetic):\n    versionCode=2 minSdk=26 targetSdk=37\n    versionName=0.1.0-rc.1\n    requested permissions:\n' + ''.join('      ' + p + '\n' for p in sorted(permissions_for(PACKAGE))) + '    install permissions:\n'
        elif args[0] == 'pull':
            if args[1] == observer.UI_XML:
                Path(args[2]).write_text(self.ui_xml())
            elif args[1] == observer.UI_PNG:
                Path(args[2]).write_bytes(b'\x89PNG\r\n\x1a\nSYNTHETIC screenshot transport fixture, not native pixels')
            else:
                Path(args[2]).write_bytes(self.apk.read_bytes())
            text = '1 file pulled\n'
        elif args[:3] == ['shell', 'uiautomator', 'dump'] or args[:2] in (['shell', 'screencap'], ['shell', 'rm']):
            text = ''
        elif args[:3] == ['shell', 'input', 'tap']:
            self.ui_scene = 'drawer' if self.ui_scene == 'dashboard' else 'account'
            text = ''
        elif args[:3] == ['shell', 'input', 'keyevent']:
            self.ui_scene = 'dashboard'
            text = ''
        elif args[:3] == ['shell', 'input', 'swipe']:
            self.ui_scrolled = True
            text = ''
        elif args[:3] == ['shell', 'am', 'start']:
            text = f'Status: ok\nActivity: {PACKAGE}/{MAIN_ACTIVITY}\n'
        elif args[:3] == ['shell', 'dumpsys', 'activity']:
            text = f'  mResumedActivity: ActivityRecord{{abcdef u0 {PACKAGE}/.MainActivity t12}}\n'
        elif args[:2] == ['shell', 'pidof']:
            text = '1234\n'
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(command, 0, text)

    def observe(self):
        with patch.object(observer, 'verify_assets', return_value=({'buildInputs': self.expected}, self.apk)), \
                patch.object(observer.release_artifact, 'inspect_apk') as inspect:
            observer.observe(self.root, self.assets, self.root, 'emulator-5554', self.root / 'reports',
                             execute=self.adb, clock=self.clock, wait=lambda seconds: None)
            return inspect

    def test_download_install_pull_sdk_identity_positive_foreground_and_always_cleanup(self):
        inspect = self.observe()
        self.assertEqual(self.calls[0], ['install', str(self.apk)])
        self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])
        self.assertIn(['shell', 'pidof', PACKAGE], self.calls)
        self.assertEqual(inspect.call_args.kwargs['expected_signer'], 'c' * 64)
        receipt = json.loads((self.root / 'reports/identity.json').read_text())
        self.assertEqual(receipt['downloadedSha256'], receipt['installedSha256'])
        self.assertTrue(receipt['foregroundObserved'])
        ui = receipt['ui']
        self.assertEqual([scene['scene'] for scene in ui['scenes']], ['release-dashboard', 'release-drawer',
                         'release-account-history-tools', 'release-back-dashboard'])
        self.assertTrue(ui['diagnosticEntriesAbsent'])
        self.assertTrue(ui['accountHistoryToolsReachable'])
        self.assertTrue(ui['disconnectedExportEnabled'])
        self.assertTrue(ui['disconnectedImportDisabled'])
        self.assertFalse(ui['authenticatedSafFlowObserved'])
        for scene in ui['scenes']:
            for kind in ('xml', 'screenshot'):
                item = scene[kind]
                self.assertEqual(item['sha256'], observer.fingerprint((self.root / 'reports' / item['filename']).read_bytes())['sha256'])
        self.assertIn(['shell', 'rm', '-f', observer.UI_XML, observer.UI_PNG], self.calls)
        self.assertFalse(any('.dev' in str(arg) for call in self.calls for arg in call))

    def test_single_base_path_and_exact_resumed_default_process_fail_closed(self):
        for text in ('', f'package:/data/app/{PACKAGE}.dev-xyz/base.apk',
                     f'package:/data/app/{PACKAGE}-xyz/split_x.apk',
                     f'package:/data/app/{PACKAGE}-xyz/base.apk\npackage:/data/app/{PACKAGE}-abc/base.apk',
                     f'package:/data/app/../{PACKAGE}-xyz/base.apk'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                observer.installed_path(text)
        good = f' mResumedActivity: ActivityRecord{{abcdef u0 {PACKAGE}/.MainActivity t12}}'
        self.assertTrue(observer.foreground(good, '12'))
        for activity, pid in ((good.replace(PACKAGE, PACKAGE + '.dev'), '12'), (good, ''), (good, '12 13'),
                              (good.replace('mResumedActivity', 'mLastPausedActivity'), '12')):
            self.assertFalse(observer.foreground(activity, pid))

    def test_failed_install_or_timeout_keeps_original_error_and_cleans_production(self):
        def fail(args):
            if args[0] == 'install':
                return subprocess.CompletedProcess([], 1, 'install failure')
            if args[0] == 'uninstall':
                return subprocess.CompletedProcess([], 1, 'cleanup failure')
        self.failure = fail
        with self.assertRaisesRegex(ValueError, 'install$'):
            self.observe()
        self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])
        self.assertFalse(any(c[0] == 'pull' for c in self.calls))
        def timeout(args):
            if args[0] == 'install':
                raise subprocess.TimeoutExpired([], 30)
        self.failure = timeout
        with self.assertRaisesRegex(ValueError, 'install$'):
            self.observe()
        self.assertIn('124', (self.root / 'reports/statuses.json').read_text())

    def test_wrong_pulled_bytes_launch_or_foreground_never_count_as_success(self):
        def corrupt(args):
            if args[0] == 'pull':
                Path(args[2]).write_bytes(b'changed installed bytes')
                return subprocess.CompletedProcess([], 0, 'pulled')
        self.failure = corrupt
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.observe()
        for phase, text in (('launch', 'Status: ok\nActivity: wrong/.MainActivity\n'),
                            ('foreground', 'mLastPausedActivity: stale\n')):
            def fail(args):
                if phase == 'launch' and args[:3] == ['shell', 'am', 'start']:
                    return subprocess.CompletedProcess([], 0, text)
                if phase == 'foreground' and args[:3] == ['shell', 'dumpsys', 'activity']:
                    return subprocess.CompletedProcess([], 0, text)
            self.failure = fail
            with self.subTest(phase=phase), self.assertRaises(ValueError):
                self.observe()
            self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])


    def test_release_ui_rejects_either_diagnostic_label_and_still_cleans_up(self):
        for label in sorted(observer.DIAGNOSTIC_LABELS):
            for attribute in ('text', 'content-desc'):
                self.ui_scene = 'dashboard'
                self.ui_mutation = lambda text: text.replace('</hierarchy>',
                    f'<node package="{PACKAGE}" {attribute}="{label}"/></hierarchy>') if self.ui_scene == 'drawer' else text
                with self.subTest(label=label, attribute=attribute), self.assertRaisesRegex(ValueError, 'diagnostic'):
                    self.observe()
                self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])
                self.assertFalse((self.root / 'reports/identity.json').exists())

    def test_release_ui_requires_real_package_controls_disabled_import_and_valid_xml(self):
        mutations = [lambda text: text.replace(PACKAGE, PACKAGE + '.dev'),
                     lambda text: text.replace('Export history', 'Missing export'),
                     lambda text: text.replace('Import history', 'Missing import'),
                     lambda text: text.replace('enabled="false"', 'enabled="true"'),
                     lambda text: text.replace('Local usage history', 'Missing group'),
                     lambda text: '<not-xml']
        for mutation in mutations:
            self.ui_scene = 'dashboard'
            self.ui_mutation = mutation
            with self.subTest(mutation=mutation), self.assertRaises((ValueError, observer.ET.ParseError)):
                self.observe()
            self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])

    def test_ui_bounds_and_ambiguous_actions_fail_closed(self):
        tree = observer.ui_tree(self.ui_xml())
        node = observer.ui_action(tree, 'Account settings')
        assert node is not None
        self.assertEqual(observer.ui_bounds(node), (10, 200, 500, 248))
        for bounds in ('', '[0,0][0,1]', '[-1,0][2,3]', '[0,2][1,1]'):
            node.set('bounds', bounds)
            with self.subTest(bounds=bounds), self.assertRaises(ValueError):
                observer.ui_bounds(node)
        tree.append(observer.ET.fromstring(f'<node package="{PACKAGE}" clickable="true" text="Account settings"/>'))
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            observer.ui_action(tree, 'Account settings')
        with self.assertRaisesRegex(ValueError, 'hierarchy'):
            observer.ui_tree('<hierarchy/>')

    def test_account_tools_scroll_in_own_container_and_resolve_child_button_labels(self):
        self.ui_scroll_needed = True
        def nested(text):
            tree = observer.ET.fromstring(text)
            for node in list(tree.iter('node')):
                if node.get('class') == 'android.widget.Button' and node.get('text'):
                    label = node.attrib.pop('text')
                    observer.ET.SubElement(node, 'node', {'package': PACKAGE, 'text': label})
            return observer.ET.tostring(tree, encoding='unicode')
        self.ui_mutation = nested
        self.observe()
        self.assertEqual([call for call in self.calls if call[:3] == ['shell', 'input', 'swipe']],
                         [['shell', 'input', 'swipe', '540', '1460', '540', '440', '300']])
        self.assertTrue((self.root / 'reports/release-account-history-tools-2.xml').is_file())

    def test_missing_fresh_dump_or_lost_ui_foreground_never_reuses_prior_evidence(self):
        reports = self.root / 'reports'
        reports.mkdir()
        stale = reports / 'release-dashboard-1.xml'
        stale.write_text(self.ui_xml())
        def missing(args):
            if args[0] == 'pull' and args[1] == observer.UI_XML:
                return subprocess.CompletedProcess([], 0, 'synthetic silent no-op')
        self.failure = missing
        with self.assertRaises(FileNotFoundError):
            self.observe()
        self.assertFalse(stale.exists())
        self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])
        def lost(args):
            if self.ui_scene == 'account' and args[:3] == ['shell', 'dumpsys', 'activity']:
                return subprocess.CompletedProcess([], 0, 'mLastPausedActivity: stale')
        self.failure = lost
        with self.assertRaisesRegex(ValueError, 'left the verified foreground'):
            self.observe()
        self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])

    def test_ui_timeout_bad_screenshot_or_cleanup_failure_never_pass(self):
        def fail(args):
            if args[:3] == ['shell', 'uiautomator', 'dump']:
                raise subprocess.TimeoutExpired([], 10)
        self.failure = fail
        with self.assertRaisesRegex(ValueError, 'release-dashboard-dump'):
            self.observe()
        self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])
        def corrupt(args):
            if args[0] == 'pull' and args[1] == observer.UI_PNG:
                Path(args[2]).write_bytes(b'not a PNG')
                return subprocess.CompletedProcess([], 0, 'pulled')
        self.failure = corrupt
        with self.assertRaisesRegex(ValueError, 'not PNG'):
            self.observe()
        def cleanup(args):
            if args[:2] == ['shell', 'rm'] and (self.root / 'reports/identity.json').exists():
                return subprocess.CompletedProcess([], 1, 'cleanup failed')
        self.failure = cleanup
        with self.assertRaisesRegex(ValueError, 'ui-cleanup'):
            self.observe()
        self.assertEqual(self.calls[-1], ['uninstall', PACKAGE])


class HostedWiringTests(unittest.TestCase):
    def test_one_additive_build_and_same_emulator_after_debug_graph_with_exit_precedence(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/android.yml').read_text()
        native = (root / 'tools/build/run-hosted-native-smoke.sh').read_text()
        fixture = (root / 'tools/build/hosted_signed_release_fixture.py').read_text()
        self.assertEqual(workflow.count('tools/build/hosted_signed_release_fixture.py'), 1)
        self.assertLess(workflow.index('Prepare native release parser environment'), workflow.index('tools/build/hosted_signed_release_fixture.py'))
        self.assertLess(workflow.index('tools/build/hosted_signed_release_fixture.py'), workflow.index('Require hosted KVM access'))
        self.assertEqual(fixture.count("expected = build_case("), 1)
        self.assertIn("os.environ.get('GITHUB_ACTIONS') != 'true'", fixture)
        self.assertIn("dir=os.environ['RUNNER_TEMP']", fixture)
        self.assertLess(native.index('installed-observation-status.txt'), native.index('tools/build/verify_release_installation.py'))
        self.assertLess(native.index('tools/build/verify_release_installation.py'), native.index('if (( test_status != 0 )); then exit "$test_status"; fi', native.index('tools/build/verify_release_installation.py')))
        self.assertIn('release_observation_status == 0', native)
        self.assertEqual(native.count('"$emulator" -avd'), 1)
        self.assertNotIn('keytool', (root / 'tools/build/release_signing.py').read_text())


if __name__ == '__main__':
    unittest.main()
