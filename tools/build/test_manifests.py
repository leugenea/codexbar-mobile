"""Source, merged, APK and installed manifest security regressions using synthetic tools."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from work_manifest_fixtures import COMPONENT_XML, STARTUP_APK, components_apk, components_xml
from work_manifest_policy import check_components, apk_components
from verify_manifests import (ANDROID, TOOLS, APP_PERMISSIONS, DEBUG_PACKAGE as PACKAGE,
                              MAIN_ACTIVITY, RECEIVER_PERMISSION, SOURCE_PERMISSIONS,
                              approved_work_removal, check_apk_dump, check_installed_dump,
                              check_installed_path, check_manifest, check_permissions,
                              check_source_manifests)

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("RUNNER_TEMP", os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir())))


class ManifestTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.directory = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def synthetic_manifest(self, permissions=APP_PERMISSIONS):
        declarations = (f'<permission android:name="{RECEIVER_PERMISSION}" android:protectionLevel="signature"/>'
                        if permissions == APP_PERMISSIONS else '')
        permission_removal = ('<uses-permission android:name="android.permission.FOREGROUND_SERVICE" tools:node="remove"/>'
                              if permissions == SOURCE_PERMISSIONS else '')
        service_removal = ('<service android:name="androidx.work.impl.foreground.SystemForegroundService" tools:node="remove"/>'
                           if permissions == SOURCE_PERMISSIONS else '')
        return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                'xmlns:tools="http://schemas.android.com/tools">'
                + ''.join(f'<uses-permission android:name="{name}"/>' for name in sorted(permissions))
                + permission_removal + declarations + '<application android:usesCleartextTraffic="false">'
                '<activity android:name=".MainActivity" android:launchMode="singleTask"/>'
                + (components_xml(PACKAGE) if permissions == APP_PERMISSIONS else '')
                + service_removal
                + '</application></manifest>')

    def synthetic_apk_activity(self, name=MAIN_ACTIVITY):
        return ('    E: activity (line=5)\n'
                f'      A: android:name(0x01010003)="{name}"\n'
                '      A: android:launchMode(0x0101001d)=(type 0x10)0x2\n' + components_apk(PACKAGE))

    def test_manifest_launcher_and_process_policy_rejects_synthetic_regressions(self):
        manifest = self.directory / "SYNTHETIC-AndroidManifest.xml"
        for permissions in (SOURCE_PERMISSIONS, APP_PERMISSIONS):
            valid = self.synthetic_manifest(permissions)
            activity = '<activity android:name=".MainActivity" android:launchMode="singleTask"/>'
            for name in ('.MainActivity', 'MainActivity', MAIN_ACTIVITY):
                manifest.write_text(valid.replace('.MainActivity', name))
                self.assertEqual(check_manifest(manifest, permissions)['mainActivityLaunchMode'], 'singleTask')
            mutations = [
                (valid.replace(' android:launchMode="singleTask"', ''), 'singleTask'),
                (valid.replace(activity, ''), 'singleTask'),
                (valid.replace(activity, activity + activity), 'singleTask'),
                (valid.replace('.MainActivity', '.OtherActivity'), 'singleTask'),
            ]
            for mode in ('standard', 'singleTop', 'singleInstance', 'singleInstancePerTask'):
                mutations.append((valid.replace('singleTask', mode), 'singleTask'))
            for element in ('application', 'activity'):
                mutations.append((valid.replace('<' + element + ' ',
                                                '<' + element + ' android:process=":other" '), 'single process'))
            mutations.append((valid.replace('</application>',
                              '<service android:name=".Other" android:process=":other"/></application>'), 'single process'))
            for text, error in mutations:
                with self.subTest(permissions=permissions, mutation=text):
                    manifest.write_text(text)
                    with self.assertRaisesRegex(ValueError, error):
                        check_manifest(manifest, permissions)

    def test_apk_launcher_and_process_policy_rejects_synthetic_regressions(self):
        permissions = f'package: {PACKAGE}\n' + ''.join(
            f"uses-permission: name='{name}'\n" for name in sorted(APP_PERMISSIONS))
        prefix = (f'  E: permission (line=3)\n'
                  f'    A: android:name(0x01010003)="{RECEIVER_PERMISSION}"\n'
                  '    A: android:protectionLevel(0x01010009)=(type 0x11)0x2\n'
                  '  E: application (line=4)\n'
                  '    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0\n')
        activity = self.synthetic_apk_activity()
        for name in ('.MainActivity', 'MainActivity', MAIN_ACTIVITY):
            self.assertEqual(check_apk_dump(permissions, prefix + self.synthetic_apk_activity(name)), sorted(APP_PERMISSIONS))
        for tree, error in (
            (prefix, 'singleTask'),
            (prefix + activity + activity, 'singleTask'),
            (prefix + activity.replace('MainActivity', 'OtherActivity'), 'singleTask'),
            (prefix + activity.replace('      A: android:launchMode(0x0101001d)=(type 0x10)0x2\n', ''), 'singleTask'),
            (prefix + activity.replace('0x10)0x2', '0x10)0x0'), 'singleTask'),
            (prefix + activity.replace('0x10)0x2', '0x10)0x1'), 'singleTask'),
            (prefix + activity.replace('0x10)0x2', '0x10)0x3'), 'singleTask'),
            (prefix + '    A: android:process(0x01010011)=":other"\n' + activity, 'single process'),
            (prefix + activity + '      A: android:process(0x01010011)=":other"\n', 'single process'),
            (prefix + activity + '    E: service (line=6)\n      A: android:process(0x01010011)=":other"\n', 'single process'),
        ):
            with self.subTest(tree=tree), self.assertRaisesRegex(ValueError, error):
                check_apk_dump(permissions, tree)

    def write_synthetic_manifest_tree(self):
        files = {
            'app/src/main/AndroidManifest.xml': self.synthetic_manifest(SOURCE_PERMISSIONS),
            'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml': self.synthetic_manifest(),
            'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml': self.synthetic_manifest(),
            'tools/build/verify_manifests.py': (ROOT / 'tools/build/verify_manifests.py').read_text(),
            'tools/build/work_manifest_policy.py': (ROOT / 'tools/build/work_manifest_policy.py').read_text(),
            'tools/build/historical_apk_contract.py': (ROOT / 'tools/build/historical_apk_contract.py').read_text(),
        }
        for name, text in files.items():
            path = self.directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if '/release/' in name:
                text = text.replace(PACKAGE, PACKAGE.removesuffix('.dev'))
            path.write_text(text)

    def run_manifest_cli(self):
        return subprocess.run([sys.executable, '-B', 'tools/build/verify_manifests.py'],
                              cwd=self.directory, text=True, capture_output=True, timeout=5)

    def test_normal_manifest_cli_requires_and_checks_release(self):
        self.write_synthetic_manifest_tree()
        self.assertEqual(self.run_manifest_cli().returncode, 0)
        receipt = json.loads((self.directory / 'evidence/verified-manifests.json').read_text())
        self.assertEqual(len(receipt), 3)
        self.assertIn('/release/processReleaseManifest/', receipt[2]['manifest'])
        release = self.directory / 'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'
        valid = release.read_text()
        for text, error in (
            (valid.replace('<uses-permission android:name="android.permission.INTERNET"/>', ''), 'allowlist'),
            (valid.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="true"'), 'Cleartext'),
            (valid.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="false" android:networkSecurityConfig="@xml/unsafe"'), 'Cleartext'),
            (valid.replace('protectionLevel="signature"', 'protectionLevel="normal"'), 'signature-only'),
            (valid.replace('launchMode="singleTask"', 'launchMode="standard"'), 'singleTask'),
            (valid.replace('launchMode="singleTask"', 'launchMode="singleTask" android:process=":other"'), 'single process'),
        ):
            with self.subTest(release_mutation=error):
                release.write_text(text)
                result = self.run_manifest_cli()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(error, result.stderr)
        release.unlink()
        self.assertNotEqual(self.run_manifest_cli().returncode, 0)

    def test_variant_overlay_reviewer_bypass_is_rejected_by_source_and_cli(self):
        self.write_synthetic_manifest_tree()
        for variant in ('release', 'debug', 'freeRelease'):
            path = self.directory / f'app/src/{variant}/AndroidManifest.xml'
            path.parent.mkdir(parents=True, exist_ok=True)
            # The reviewer's INTERNET-removal bypass; even a benign overlay is forbidden.
            for content in ('<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>', ''):
                with self.subTest(variant=variant, content=content):
                    path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                                    'xmlns:tools="http://schemas.android.com/tools">' + content + '</manifest>')
                    with self.assertRaisesRegex(ValueError, 'overlays'):
                        check_source_manifests(self.directory)
                    result = self.run_manifest_cli()
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('overlays', result.stderr)
            path.unlink()

    def test_protected_source_merge_directives_fail_closed(self):
        self.write_synthetic_manifest_tree()
        main = self.directory / 'app/src/main/AndroidManifest.xml'
        valid = main.read_text()
        for element, directive in (
            ('uses-permission', 'tools:node="remove"'),
            ('uses-permission', 'tools:node="replace"'),
            ('uses-permission', 'tools:node="removeAll"'),
            ('uses-permission', 'tools:remove="android:name"'),
            ('uses-permission', 'tools:replace="android:name"'),
            ('application', 'tools:node="replace"'),
            ('application', 'tools:remove="android:label, android:usesCleartextTraffic"'),
            ('application', 'tools:replace="android:usesCleartextTraffic"'),
            ('application', 'tools:remove="android:networkSecurityConfig"'),
            ('application', 'android:networkSecurityConfig="@xml/unsafe"'),
            ('activity', 'tools:remove="android:launchMode"'),
            ('activity', 'tools:replace="android:launchMode"'),
            ('application', 'tools:remove="android:process"'),
            ('activity', 'tools:replace="android:process"'),
        ):
            with self.subTest(element=element, directive=directive):
                main.write_text(valid.replace('<' + element + ' ', '<' + element + ' ' + directive + ' ', 1))
                with self.assertRaisesRegex(ValueError, 'directive|override'):
                    check_source_manifests(self.directory)
                self.assertNotEqual(self.run_manifest_cli().returncode, 0)

    def run_synthetic_installed_observation(self, mutation='', graph_exit=0):
        # Execute the shipped post-graph shell against explicitly synthetic SDK tools.
        # Start uninstalled, reproducing AGP cleanup; never invoke a real device/SDK.
        self.write_synthetic_manifest_tree()
        sdk = self.directory / 'SYNTHETIC-sdk'
        apk = self.directory / 'app/build/outputs/apk/debug/app-debug.apk'
        apk.parent.mkdir(parents=True, exist_ok=True)
        apk.write_bytes(b'SYNTHETIC APK bytes, not an Android artifact')
        evidence = self.directory / 'evidence/native'
        evidence.mkdir(parents=True, exist_ok=True)
        (self.directory / 'events.txt').write_text('')
        (self.directory / 'installed').unlink(missing_ok=True)
        permissions = f'package: {PACKAGE}\n' + ''.join(
            f"uses-permission: name='{name}'\n" for name in sorted(APP_PERMISSIONS))
        tree = ('  E: permission (line=1)\n'
                f'    A: android:name(0x01010003)="{RECEIVER_PERMISSION}"\n'
                '    A: android:protectionLevel(0x01010009)=(type 0x11)0x2\n'
                '  E: application (line=2)\n    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0\n'
                + self.synthetic_apk_activity())
        dump = f'  Package [{PACKAGE}] (synthetic):\n    requested permissions:\n' + ''.join(
            f'      {name}\n' for name in sorted(APP_PERMISSIONS)) + '    install permissions:\n'
        if mutation == 'extra-installed-permission':
            dump = dump.replace('    install permissions:', '      android.permission.CAMERA\n    install permissions:')
        if mutation == 'unsafe-apk':
            tree = tree.replace('0x12)0x0', '0x12)0xffffffff')
        for name, text in (('permissions.txt', permissions), ('tree.txt', tree), ('dump.txt', dump)):
            (self.directory / name).write_text(text)
        common = (f'#!{sys.executable}\nimport os, sys\nfrom pathlib import Path\n'
                  'args = sys.argv[1:]\nmutation = os.environ["SYNTHETIC_MUTATION"]\n'
                  'with open("events.txt", "a") as log: log.write(" ".join(args) + "\\n")\n')
        tools = {
            'build-tools/36.0.0/aapt': common + '''
if args[1] == 'permissions':
    print(Path('permissions.txt').read_text(), end='')
else:
    print(Path('tree.txt').read_text(), end='')
    if mutation == 'mutated-apk':
        with open(args[2], 'ab') as apk: apk.write(b'mutated after inspection')
''',
            'platform-tools/adb': common + f'''
args = args[2:]  # -s serial
installed = Path('installed')
if args[0] == 'install':
    if mutation == 'install-failure': sys.exit(7)
    installed.write_text('synthetic installed state')
    print('no success receipt' if mutation == 'install-no-success' else 'Success')
elif args[:3] == ['shell', 'pm', 'path']:
    if installed.exists() and mutation != 'missing-package':
        package = 'wrong.package' if mutation == 'wrong-package' else '{PACKAGE}'
        print(f'package:/data/app/~~synthetic/{{package}}-synthetic/base.apk')
    if mutation == 'path-failure': sys.exit(8)
elif args[:3] == ['shell', 'dumpsys', 'package']:
    print(Path('dump.txt').read_text() if installed.exists() else 'Unable to find package: {PACKAGE}')
    if mutation == 'dump-failure': sys.exit(9)
elif args[0] == 'uninstall':
    installed.unlink(missing_ok=True)
    print('Success')
    if mutation == 'uninstall-failure': sys.exit(10)
else:
    sys.exit(99)
''',
            'bin/python3': common + f'os.execv({sys.executable!r}, [{sys.executable!r}] + args)\n',
        }
        for name, text in tools.items():
            path = sdk / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            path.chmod(0o755)
        script = (ROOT / 'tools/build/run-hosted-native-smoke.sh').read_text()
        block = '# Inspect and bind' + script.split('# Inspect and bind', 1)[1]
        shell = ('set -uo pipefail\nset +e\n'
                 'adb="$ANDROID_HOME/platform-tools/adb"\n'
                 f'test_status={graph_exit}\nmanifest_status=0\nnative_report_status=0\njvm_report_status=0\n'
                 + block)
        # The debug oracle remains unchanged; the separate release observer has its own
        # synthetic protocol tests. Stub only that new boundary in this debug harness.
        parser = self.directory / 'native-release-venv/bin/python'
        parser.parent.mkdir(parents=True, exist_ok=True)
        parser.write_text(f'#!{sys.executable}\nimport os, sys\nsys.exit(27 if os.environ["SYNTHETIC_MUTATION"] == "release-observation-failure" else 0)\n')
        parser.chmod(0o755)
        environment = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SERIAL='SYNTHETIC-serial',
                           RUNNER_TEMP=str(self.directory),
                           SYNTHETIC_MUTATION=mutation, PATH=str(sdk / 'bin') + os.pathsep + os.environ['PATH'])
        result = subprocess.run(['bash', '-c', shell], cwd=self.directory, env=environment,
                                text=True, capture_output=True, timeout=10)
        events = (self.directory / 'events.txt').read_text().splitlines()
        statuses = dict(line.split('=', 1) for line in (evidence / 'installed-observation-status.txt').read_text().splitlines())
        self.assertFalse((self.directory / 'installed').exists(), 'Cleanup must remove synthetic installed state')
        return result, events, statuses

    def test_post_agp_uninstall_reinstalls_verified_apk_before_observing_once(self):
        script = (ROOT / 'tools/build/run-hosted-native-smoke.sh').read_text()
        self.assertLess(script.index('coverage_attempt=2\ndone'), script.index('# Inspect and bind'))
        self.assertEqual(script.count('"$adb" -s "$ANDROID_SERIAL" install -r "$apk"'), 1)
        result, events, statuses = self.run_synthetic_installed_observation()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(value == '0' for value in statuses.values()), statuses)
        self.assertEqual([line.split()[0] for line in events],
                         ['dump', 'dump', 'tools/build/verify_manifests.py', '-s', '-s',
                          'tools/build/verify_manifests.py', '-s', 'tools/build/verify_manifests.py', '-s'])
        adb_events = [line.split()[2:] for line in events if line.startswith('-s ')]
        self.assertEqual(adb_events, [
            ['install', '-r', 'app/build/outputs/apk/debug/app-debug.apk'],
            ['shell', 'pm', 'path', PACKAGE], ['shell', 'dumpsys', 'package', PACKAGE], ['uninstall', PACKAGE]])
        digest = (self.directory / 'evidence/native/inspected-apk.sha256').read_text().split()[0]
        apk = self.directory / 'app/build/outputs/apk/debug/app-debug.apk'
        self.assertEqual(digest, hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertEqual(events[5], 'tools/build/verify_manifests.py --installed-path evidence/native/installed-package-path.txt')
        self.assertIn('--installed-package evidence/native/installed-package.txt', events[7])

    def test_installed_observation_fails_closed_and_preserves_graph_error(self):
        for mutation, failed_status in (
            ('mutated-apk', 'install_hash_exit'), ('unsafe-apk', 'apk_policy_exit'),
            ('install-failure', 'install_exit'), ('install-no-success', 'install_exit'),
            ('missing-package', 'installed_path_policy_exit'), ('wrong-package', 'installed_path_policy_exit'),
            ('path-failure', 'installed_path_exit'), ('dump-failure', 'installed_dump_exit'),
            ('extra-installed-permission', 'permission_exit'), ('uninstall-failure', 'uninstall_exit'),
        ):
            with self.subTest(mutation=mutation):
                result, events, statuses = self.run_synthetic_installed_observation(mutation)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotEqual(statuses[failed_status], '0')
                self.assertEqual(sum(' uninstall ' in line for line in events), 1)
                if mutation in {'mutated-apk', 'unsafe-apk'}:
                    self.assertFalse(any(' install ' in line for line in events))
                if mutation in {'missing-package', 'wrong-package', 'path-failure'}:
                    self.assertFalse(any(' dumpsys ' in line for line in events))
        result, _, _ = self.run_synthetic_installed_observation('missing-package', graph_exit=23)
        self.assertEqual(result.returncode, 23, 'Original graph failure takes precedence')

    def test_release_observer_failure_is_mandatory_but_preserves_original_debug_graph_error(self):
        result, _, statuses = self.run_synthetic_installed_observation('release-observation-failure')
        self.assertTrue(all(value == '0' for value in statuses.values()), statuses)
        self.assertNotEqual(result.returncode, 0)
        result, _, _ = self.run_synthetic_installed_observation('release-observation-failure', graph_exit=23)
        self.assertEqual(result.returncode, 23)

    def test_installed_path_requires_nonempty_exact_package(self):
        valid = f'package:/data/app/~~synthetic/{PACKAGE}-synthetic/base.apk\n'
        check_installed_path(valid)
        check_installed_path(valid.replace('\n', '\r\n'))
        for text in ('', '\n', 'Unable to find package: ' + PACKAGE, valid.replace(PACKAGE, 'wrong.package'),
                     valid.replace('package:', ''), valid + 'unrelated\n'):
            with self.subTest(path=text), self.assertRaisesRegex(ValueError, 'installed package path'):
                check_installed_path(text)

    def test_source_and_merged_manifest_parser_fails_closed(self):
        manifest = self.directory / "SYNTHETIC-AndroidManifest.xml"
        prefix = '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
        application = ('<application android:usesCleartextTraffic="false">'
                       '<activity android:name=".MainActivity" android:launchMode="singleTask"/>'
                       '</application>')
        for expected in (SOURCE_PERMISSIONS, APP_PERMISSIONS):
            for tag in ("uses-permission", "uses-permission-sdk-23"):
                permissions = ''.join(f'<{tag} android:name="{name}"/>' for name in sorted(expected))
                declaration = (f'<permission android:name="{RECEIVER_PERMISSION}" android:protectionLevel="signature"/>'
                               if expected == APP_PERMISSIONS else '')
                observed = application.replace('</application>', components_xml(PACKAGE) + '</application>') if declaration else application
                valid = prefix + permissions + declaration + observed + '</manifest>'
                manifest.write_text(valid)
                self.assertEqual(check_manifest(manifest, expected)["requestedPermissions"], sorted(expected))
                mutations = [
                    (valid.replace(observed, '<uses-permission android:name="android.permission.CAMERA"/>' + observed), "allowlist"),
                    (valid.replace(permissions, ''), "allowlist"),
                    (valid.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="true"'), "Cleartext"),
                    (valid.replace('android:usesCleartextTraffic="false"', ''), "Cleartext"),
                    (valid.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="false" android:networkSecurityConfig="@xml/unsafe"'), "Cleartext"),
                    (valid.replace('android:name="android.permission.INTERNET"', 'android:name="android.permission.INTERNET" android:maxSdkVersion="27"'), "Conditional"),
                ]
                if declaration:
                    mutations.extend([
                        (valid.replace(declaration, ""), "signature-only"),
                        (valid.replace('protectionLevel="signature"', 'protectionLevel="normal"'), "signature-only"),
                        (valid.replace('protectionLevel="signature"', 'protectionLevel="dangerous"'), "signature-only"),
                    ])
                for text, error in mutations:
                    with self.subTest(expected=expected, tag=tag, mutation=text):
                        manifest.write_text(text)
                        with self.assertRaisesRegex(ValueError, error):
                            check_manifest(manifest, expected)
        manifest.write_text("<unrelated/>")
        with self.assertRaisesRegex(ValueError, "Not an Android manifest"):
            check_manifest(manifest)
        with self.assertRaises(FileNotFoundError):
            check_manifest(self.directory / "missing.xml")

    def test_apk_dump_requires_exact_permissions_and_cleartext_off(self):
        permissions = f'package: {PACKAGE}\n' + ''.join(
            f"uses-permission: name='{name}'\n" for name in sorted(APP_PERMISSIONS))
        declaration = (f'  E: permission (line=3)\n'
                       f'    A: android:name(0x01010003)="{RECEIVER_PERMISSION}" (Raw: "{RECEIVER_PERMISSION}")\n'
                       '    A: android:protectionLevel(0x01010009)=(type 0x11)0x2\n')
        xmltree = (declaration + '  E: application (line=4)\n'
                   '    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0\n'
                   + self.synthetic_apk_activity())
        self.assertEqual(check_apk_dump(permissions, xmltree), sorted(APP_PERMISSIONS))
        for bad_permissions, bad_tree, error in (
                (permissions + "uses-permission: name='android.permission.CAMERA'\n", xmltree, "allowlist"),
                (permissions.replace("uses-permission: name='android.permission.INTERNET'\n", ''), xmltree, "allowlist"),
                (permissions.replace("INTERNET'", "INTERNET' maxSdkVersion='27'"), xmltree, "conditional"),
                (permissions.replace(PACKAGE, 'synthetic.wrong.package'), xmltree, "identity"),
                (permissions, xmltree.replace('0x0\n', '0xffffffff\n'), "cleartext"),
                (permissions, xmltree.replace(declaration, ''), "declaration"),
                (permissions, xmltree.replace('0x11)0x2', '0x11)0x0'), "signature-only"),
                (permissions, xmltree.replace('0x11)0x2', '0x11)0x1'), "signature-only"),
                (permissions, declaration, "cleartext"),
                (permissions, xmltree + '    A: android:networkSecurityConfig(0x01010527)=@0x7f010000\n', "cleartext")):
            with self.subTest(permissions=bad_permissions, tree=bad_tree):
                with self.assertRaisesRegex(ValueError, error):
                    check_apk_dump(bad_permissions, bad_tree)

    def test_installed_dump_requires_exact_requested_permissions(self):
        text = f'Packages:\n  Package [{PACKAGE}] (synthetic):\n    flags=[ DEBUGGABLE HAS_CODE ]\n    requested permissions:\n' + ''.join(
            f'      {name}\n' for name in sorted(APP_PERMISSIONS)) + '    install permissions:\n      android.permission.INTERNET: granted=true\n'
        self.assertEqual(check_installed_dump(text), sorted(APP_PERMISSIONS))
        for bad, error in (
                ('', "identity"),
                ('Unable to find package: ' + PACKAGE, "identity"),
                (text.replace('    install permissions:', '      android.permission.CAMERA\n    install permissions:'), "allowlist"),
                (text.replace('      android.permission.INTERNET\n', ''), "allowlist"),
                (text.replace(PACKAGE, 'synthetic.wrong.package'), "identity"),
                (text.replace('requested permissions:', 'unrelated section:'), "requested permissions"),
                (text.replace('    install permissions:', '    requested permissions:'), "requested permissions")):
            with self.subTest(text=bad), self.assertRaisesRegex(ValueError, error):
                check_installed_dump(bad)

    def test_permission_allowlist_rejects_unexpected_and_missing_permissions(self):
        self.assertEqual(check_permissions(APP_PERMISSIONS), sorted(APP_PERMISSIONS))
        for actual in (APP_PERMISSIONS | {'android.permission.CAMERA'},
                       APP_PERMISSIONS - {'android.permission.INTERNET'}, set()):
            with self.assertRaisesRegex(ValueError, 'allowlist'):
                check_permissions(actual)

    def assert_backup_policy(self, manifest, legacy, extraction):
        ns = "{http://schemas.android.com/apk/res/android}"
        application = manifest.find("application")
        assert application is not None, "Missing application declaration"
        self.assertEqual(application.get(ns + "allowBackup"), "false")
        self.assertEqual(application.get(ns + "fullBackupContent"), "@xml/backup_rules")
        self.assertEqual(application.get(ns + "dataExtractionRules"), "@xml/data_extraction_rules")
        self.assertEqual(legacy.tag, "full-backup-content")
        self.assertEqual(extraction.tag, "data-extraction-rules")
        self.assertEqual([section.tag for section in extraction], ["cloud-backup", "device-transfer"])
        domains = {"root", "file", "database", "sharedpref", "external", "device_root",
                   "device_file", "device_database", "device_sharedpref"}
        for section in (legacy, *extraction):
            self.assertEqual(len(section), len(domains))
            self.assertEqual({entry.get("domain") for entry in section}, domains)
            for entry in section:
                self.assertEqual(entry.tag, "exclude")
                self.assertEqual(entry.attrib, {"domain": entry.get("domain"), "path": "."})

    def test_explicit_legacy_cloud_and_device_transfer_exclusions(self):
        self.assert_backup_policy(
            ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot(),
            ET.parse(ROOT / "app/src/main/res/xml/backup_rules.xml").getroot(),
            ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot(),
        )

    def test_backup_contract_rejects_policy_weakening(self):
        # Synthetic mutations test source policy only, not Android backup execution.
        for mutation in ("allow_backup", "missing_reference", "missing_cloud", "missing_transfer",
                         "legacy_domain", "cloud_domain", "transfer_domain", "narrow_path", "include"):
            with self.subTest(mutation=mutation):
                manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
                legacy = ET.parse(ROOT / "app/src/main/res/xml/backup_rules.xml").getroot()
                extraction = ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot()
                ns = "{http://schemas.android.com/apk/res/android}"
                application = manifest.find("application")
                assert application is not None
                if mutation == "allow_backup":
                    application.set(ns + "allowBackup", "true")
                elif mutation == "missing_reference":
                    del application.attrib[ns + "dataExtractionRules"]
                elif mutation == "missing_cloud":
                    extraction.remove(extraction[0])
                elif mutation == "missing_transfer":
                    extraction.remove(extraction[1])
                elif mutation == "legacy_domain":
                    legacy.remove(legacy[-1])
                elif mutation == "cloud_domain":
                    extraction[0].remove(extraction[0][-1])
                elif mutation == "transfer_domain":
                    extraction[1].remove(extraction[1][-1])
                elif mutation == "narrow_path":
                    extraction[1][0].set("path", "placeholder")
                else:
                    extraction[1][0].tag = "include"
                with self.assertRaises(AssertionError):
                    self.assert_backup_policy(manifest, legacy, extraction)

    def test_app_manifest_overlays_cannot_broaden_network_policy(self):
        check_source_manifests(ROOT)

    def test_only_two_exact_reviewed_removal_markers_are_permitted(self):
        source = ROOT / 'app/src/main/AndroidManifest.xml'
        check_source_manifests(ROOT)
        xml = ET.parse(source).getroot()
        removed = [node for node in xml.iter() if node.get(TOOLS + 'node') == 'remove']
        self.assertEqual([(node.tag, node.get(ANDROID + 'name')) for node in removed], [
            ('uses-permission', 'android.permission.FOREGROUND_SERVICE'),
            ('service', 'androidx.work.impl.foreground.SystemForegroundService')])
        for node in removed:
            self.assertTrue(approved_work_removal(node))
            for alteration in ('name', 'attribute', 'child', 'directive', 'tag'):
                bad = copy.deepcopy(node)
                if alteration == 'name': bad.set(ANDROID + 'name', 'unapproved')
                elif alteration == 'attribute': bad.set(ANDROID + 'exported', 'true')
                elif alteration == 'child': ET.SubElement(bad, 'meta-data')
                elif alteration == 'directive': bad.set(TOOLS + 'node', 'removeAll')
                else: bad.tag = 'receiver'
                self.assertFalse(approved_work_removal(bad))
                candidate = ET.fromstring(source.read_text())
                candidate.append(bad)
                path = self.directory / 'AndroidManifest.xml'
                ET.ElementTree(candidate).write(path)
                with self.assertRaises(ValueError): check_manifest(path, SOURCE_PERMISSIONS)
            for placement in ('duplicate', 'wrong-parent'):
                candidate = ET.fromstring(source.read_text())
                if placement == 'duplicate':
                    parent = candidate if node.tag == 'uses-permission' else candidate.find('application')
                else:
                    parent = candidate.find('application') if node.tag == 'uses-permission' else candidate
                parent.append(copy.deepcopy(node))
                path = self.directory / 'AndroidManifest.xml'
                ET.ElementTree(candidate).write(path)
                with self.subTest(placement=placement), self.assertRaises(ValueError):
                    check_manifest(path, SOURCE_PERMISSIONS)

    def test_pinned_component_membership_export_protection_boot_and_startup_fail_closed(self):
        original = ET.fromstring(COMPONENT_XML.replace('PACKAGE', PACKAGE))
        check_components(original, PACKAGE)
        apk_components('  E: application (line=1)\n' + components_apk(PACKAGE), PACKAGE)
        for index, node in enumerate(original):
            for mutation in ('missing', 'duplicate', 'name', 'export', 'permission', 'process', 'isolated-process', 'direct-boot'):
                bad = copy.deepcopy(original)
                selected = bad[index]
                if mutation == 'missing': bad.remove(selected)
                elif mutation == 'duplicate': bad.append(copy.deepcopy(selected))
                elif mutation == 'name': selected.set(ANDROID + 'name', 'unapproved')
                elif mutation == 'export': selected.set(ANDROID + 'exported', 'false' if selected.get(ANDROID + 'exported') == 'true' else 'true')
                elif mutation == 'permission': selected.set(ANDROID + 'permission', 'android.permission.INTERNET')
                elif mutation == 'process': selected.set(ANDROID + 'process', ':other')
                elif mutation == 'isolated-process': selected.set(ANDROID + 'isolatedProcess', 'true')
                elif selected.tag != 'provider': selected.set(ANDROID + 'directBootAware', 'false' if selected.get(ANDROID + 'directBootAware') == 'true' else 'true')
                else: selected.set(ANDROID + 'authorities', 'wrong')
                with self.subTest(node=node.tag, mutation=mutation), self.assertRaises(ValueError):
                    check_components(bad, PACKAGE)
        provider = original.find('provider')
        for index in range(len(provider)):
            for attribute in ('name', 'value'):
                bad = copy.deepcopy(original)
                bad.find('provider')[index].set(ANDROID + attribute, 'unapproved')
                with self.subTest(initializer=index, attribute=attribute), self.assertRaises(ValueError):
                    check_components(bad, PACKAGE)
            for mutation in ('missing', 'duplicate', 'extra'):
                bad = copy.deepcopy(original)
                selected = bad.find('provider')[index]
                if mutation == 'missing': bad.find('provider').remove(selected)
                else:
                    extra = copy.deepcopy(selected)
                    if mutation == 'extra': extra.set(ANDROID + 'name', 'unapproved.Initializer')
                    bad.find('provider').append(extra)
                with self.subTest(initializer=index, mutation=mutation), self.assertRaises(ValueError):
                    check_components(bad, PACKAGE)
        self.assertEqual(len(provider.findall('meta-data')), 5)
        bad = copy.deepcopy(original); bad.find('provider').set(ANDROID + 'directBootAware', 'true')
        with self.assertRaises(ValueError): check_components(bad, PACKAGE)
        bad = copy.deepcopy(original)  # Exercise exact action membership.
        action = ET.SubElement(ET.SubElement(bad.find('receiver'), 'intent-filter'), 'action')
        action.set(ANDROID + 'name', 'unapproved')
        with self.assertRaises(ValueError): check_components(bad, PACKAGE)

    def test_captured_apk_startup_metadata_preserves_exact_initializer_contract(self):
        for package in ('io.github.leugenea.codexbarmobile', PACKAGE):
            # The provider is last in the synthetic component fixture. Replace it
            # with the independently captured APK block, not a rendered allowlist.
            xmltree = '  E: application (line=22)\n' + components_apk(package).split('    E: provider ', 1)[0]
            captured = STARTUP_APK.replace('PACKAGE', package)
            apk_components(xmltree + captured, package)
            for mutation in ('name', 'value', 'missing', 'duplicate', 'authority', 'export'):
                bad = captured
                if mutation == 'name': bad = bad.replace('okhttp3.internal.platform.PlatformInitializer', 'unapproved.Initializer')
                elif mutation == 'value': bad = bad.replace('="androidx.startup"', '="unapproved"', 1)
                elif mutation == 'missing': bad = bad.split('      E: meta-data (line=60)', 1)[0]
                elif mutation == 'duplicate': bad += bad[bad.index('      E: meta-data (line=60)'):]
                elif mutation == 'authority': bad = bad.replace(package + '.androidx-startup', 'wrong.androidx-startup')
                else: bad = bad.replace('(type 0x12)0x0', '(type 0x12)0xffffffff', 1)
                with self.subTest(package=package, mutation=mutation), self.assertRaises(ValueError):
                    apk_components(xmltree + bad, package)
