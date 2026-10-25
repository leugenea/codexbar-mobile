"""Offline issue-195 identity/security controls. Not application or scheduler execution."""
import copy
import os
from pathlib import Path
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET

from periodic_refresh_contract_expectations import PERIODIC_JVM, PERIODIC_NATIVE, PERIODIC_NATIVE_COUNT, PERIODIC_JVM_COUNT
from verify_test_reports import PERIODIC_REFRESH_JVM, PERIODIC_REFRESH_NATIVE, verify_reports
from test_background_refresh_contract import mutate
import test_workflow_contract as workflow
from verify_manifests import ANDROID, TOOLS, SOURCE_PERMISSIONS, check_manifest, check_source_manifests, approved_work_removal
from work_manifest_fixtures import COMPONENT_XML, STARTUP_APK, components_apk
from work_manifest_policy import check_components, apk_components

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'io.github.leugenea.codexbarmobile.dev'


class PeriodicRefreshContracts(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'))
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.writer = workflow.SyntheticReportTests()
        self.writer.directory = self.directory

    def test_independent_exact_method_registries_and_counts(self):
        self.assertEqual(PERIODIC_JVM, PERIODIC_REFRESH_JVM)
        self.assertEqual(PERIODIC_NATIVE, PERIODIC_REFRESH_NATIVE)
        self.assertEqual(sum(map(len, PERIODIC_JVM.values())), PERIODIC_JVM_COUNT)
        self.assertEqual(sum(map(len, PERIODIC_NATIVE.values())), PERIODIC_NATIVE_COUNT)
        for source_set, registry in (('test', PERIODIC_JVM), ('androidTest', PERIODIC_NATIVE)):
            for classname, names in registry.items():
                source = ROOT / f'app/src/{source_set}/java/{classname.replace(".", "/")}.kt'
                declared = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', source.read_text())
                self.assertEqual(len(declared), len(set(declared)))
                self.assertEqual(set(declared), names)

    def test_every_periodic_case_fails_closed_on_omission_spoof_duplicate_error_failure_skip(self):
        for kind, registry in (('jvm', PERIODIC_JVM), ('native', PERIODIC_NATIVE)):
            for classname, names in registry.items():
                for name in names:
                    for mutation in ('missing', 'wrong-class', 'duplicate', 'error', 'failure', 'skipped'):
                        path = self.writer.write_report(kind)
                        tree = ET.parse(path)
                        suite = tree.getroot()
                        case = next(c for c in suite if c.get('classname') == classname and c.get('name') == name)
                        mutate(suite, case, mutation)
                        tree.write(path)
                        with self.subTest(kind=kind, name=name, mutation=mutation), self.assertRaises(ValueError):
                            verify_reports(self.directory, kind)

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

    def test_os_dispatch_fixture_declares_its_room_superclass_on_test_compile_classpath(self):
        build = (ROOT / 'app/build.gradle').read_text()
        declarations = re.findall(r"(?m)^\s*(\w+)\s+'androidx.room:room-runtime:([^']+)'", build)
        self.assertEqual(declarations, [('androidTestImplementation', '2.7.0')])

    def test_production_adapter_is_ordinary_lifetime_only_and_preserves_cancellation(self):
        source = (ROOT / 'app/src/main/java/io/github/leugenea/codexbarmobile/PeriodicUsageRefresh.kt').read_text()
        code = re.sub(r'//[^\n]*|/\*.*?\*/', '', source, flags=re.S)
        for forbidden in ('Result.retry(', 'setExpedited(', 'setForeground(', 'setForegroundAsync(', 'AlarmManager',
                          '.signOut(', '.shutdown(', 'NativeConnection.create(', 'usageForeground(', 'while ('):
            self.assertNotIn(forbidden, code)
        self.assertNotRegex(code, r'catch\s*\([^)]*:\s*(?:Exception|Throwable|CancellationException)\b')
        self.assertIn('ExistingPeriodicWorkPolicy.KEEP', code)
        self.assertIn('NetworkType.CONNECTED', code)
        self.assertIn('setRequiresBatteryNotLow(true)', code)
        self.assertIn('minimumSuccessAgeMillis', code)
        self.assertIn('NativeConnection.get(applicationContext)', code)
        self.assertEqual(SOURCE_PERMISSIONS, frozenset({'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
                                                     'android.permission.RECEIVE_BOOT_COMPLETED', 'android.permission.WAKE_LOCK'}))
