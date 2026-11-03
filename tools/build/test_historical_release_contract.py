"""Bounded historical-policy regressions; remote/SDK outcomes are synthetic unless labeled captured."""
import base64
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

import historical_apk_contract as contracts
from historical_apk_fixtures import SOURCE_MANIFEST, PERMISSIONS, TREE
import release_artifact as artifact
import release_policy as policy
import release_signing as signing
from release_runtime import canonical, fingerprint
import test_release_artifact as current
import test_release_policy as fixtures
import test_release_signing as asset_tests
from verify_manifests import PACKAGE, check_apk_dump, check_installed_dump
from work_manifest_policy import apk_components

LEGACY = contracts.ApkContract.PRE_WORKMANAGER
FIRST_SOURCE = {'sha': '296e030c68e79235397d7e4d082aaf463ce95f7e',
                'tree': '30353f7174afd47b5e62d676e434bcffa1aafefb'}
FIRST_TAG = 'v0.1.0-rc.1'


def without_node(tree, kind, name):
    """Remove a complete independently named aapt node, including its children."""
    lines = tree.splitlines(keepends=True)
    for index, line in enumerate(lines):
        if re.match(r'\s*E: ' + kind + r'\s', line):
            indent = len(line) - len(line.lstrip())
            end = index + 1
            while end < len(lines) and len(lines[end]) - len(lines[end].lstrip()) > indent:
                end += 1
            if '="' + name + '"' in ''.join(lines[index:end]):
                return ''.join(lines[:index] + lines[end:])
    raise AssertionError('Fixture node not found: ' + name)


def legacy_identity(tag=FIRST_TAG, source=None, code=9):
    target = tag.removeprefix('v').split('-rc.', 1)[0]
    return {**current.EXPECTED, 'source': source or FIRST_SOURCE, 'target': target,
            'channel': 'rc' if '-rc.' in tag else 'stable', 'tag': tag,
            'versionName': tag[1:], 'versionCode': code}


def legacy_model(identity=None):
    model = fixtures.PolicyAPI()
    identity = identity or legacy_identity()
    source = identity['source']
    model.overrides['/git/ref/tags/' + identity['tag']] = {'object': {'type': 'commit', 'sha': source['sha']}}
    model.overrides['/git/commits/' + source['sha']] = {'tree': {'sha': source['tree']}}
    model.overrides['/contents/app/src/main/AndroidManifest.xml?ref=' + source['sha']] = {
        'encoding': 'base64', 'content': base64.encodebytes(SOURCE_MANIFEST).decode()}
    return model


class HistoricalParserTests(unittest.TestCase):
    def test_captured_legacy_surface_only_passes_exact_legacy_contract(self):
        expected = ['android.permission.INTERNET', PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']
        self.assertEqual(check_apk_dump(PERMISSIONS, TREE, PACKAGE, apk_contract=LEGACY), expected)
        with self.assertRaisesRegex(ValueError, 'Permission allowlist'):
            check_apk_dump(PERMISSIONS, TREE, PACKAGE)
        with self.assertRaisesRegex(ValueError, 'component allowlist'):
            apk_components(TREE, PACKAGE)
        for flag in (True, 'legacy', 'pre-workmanager', {}, object()):
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                check_apk_dump(PERMISSIONS, TREE, PACKAGE, apk_contract=flag)

    def test_legacy_rejects_every_extra_permission_and_component(self):
        for permission in ('ACCESS_NETWORK_STATE', 'RECEIVE_BOOT_COMPLETED', 'WAKE_LOCK', 'CAMERA', 'FOREGROUND_SERVICE'):
            extra = PERMISSIONS + f"uses-permission: name='android.permission.{permission}'\n"
            with self.subTest(permission=permission), self.assertRaisesRegex(ValueError, 'Permission allowlist'):
                check_apk_dump(extra, TREE, PACKAGE, apk_contract=LEGACY)
        for kind in ('service', 'receiver', 'provider'):
            extra = TREE + f'      E: {kind} (line=99)\n        A: android:name(0x01010003)="unapproved.Component"\n'
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, 'component allowlist'):
                check_apk_dump(PERMISSIONS, extra, PACKAGE, apk_contract=LEGACY)
        for permission in ('android.permission.INTERNET', PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'):
            missing = PERMISSIONS.replace(f"uses-permission: name='{permission}'\n", '')
            with self.subTest(permission=permission), self.assertRaisesRegex(ValueError, 'Permission allowlist'):
                check_apk_dump(missing, TREE, PACKAGE, apk_contract=LEGACY)

    def test_legacy_rejects_missing_duplicate_or_changed_startup_metadata(self):
        names = ('androidx.emoji2.text.EmojiCompatInitializer', 'androidx.lifecycle.ProcessLifecycleInitializer',
                 'okhttp3.internal.platform.PlatformInitializer', 'androidx.profileinstaller.ProfileInstallerInitializer')
        for name in names:
            omitted = without_node(TREE, 'meta-data', name)
            extra = f'        E: meta-data (line=99)\n          A: android:name(0x01010003)="{name}"\n          A: android:value(0x01010024)="androidx.startup"\n'
            # Add metadata under the provider, before the following uses-library node.
            duplicate = TREE.replace('      E: uses-library (line=59)', extra + '      E: uses-library (line=59)')
            changed = TREE.replace('="' + name + '"', '="unapproved.Initializer"')
            for bad in (omitted, duplicate, changed):
                with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'initializer contract'):
                    check_apk_dump(PERMISSIONS, bad, PACKAGE, apk_contract=LEGACY)
        bad = TREE.replace('="androidx.startup"', '="wrong"', 1)
        extra = TREE.replace('      E: uses-library (line=59)',
                             '        E: meta-data (line=99)\n          A: android:name(0x01010003)="androidx.work.WorkManagerInitializer"\n'
                             '          A: android:value(0x01010024)="androidx.startup"\n      E: uses-library (line=59)')
        for tree in (bad, extra):
            with self.assertRaisesRegex(ValueError, 'initializer contract'):
                check_apk_dump(PERMISSIONS, tree, PACKAGE, apk_contract=LEGACY)

    def test_legacy_keeps_receiver_provider_process_cleartext_and_signature_protections(self):
        mutations = [
            TREE.replace(PACKAGE + '.androidx-startup', 'wrong.androidx-startup'),
            TREE.replace('android:exported(0x01010010)=(type 0x12)0x0', 'android:exported(0x01010010)=(type 0x12)0xffffffff'),
            TREE.replace('="android.permission.DUMP"', '="android.permission.INTERNET"'),
            TREE.replace('android:exported(0x01010010)=(type 0x12)0xffffffff', 'android:exported(0x01010010)=(type 0x12)0x0'),
            TREE.replace('android:directBootAware(0x01010505)=(type 0x12)0x0', 'android:directBootAware(0x01010505)=(type 0x12)0xffffffff'),
            TREE.replace('androidx.profileinstaller.action.INSTALL_PROFILE', 'unapproved.ACTION'),
            TREE.replace('android:protectionLevel(0x01010009)=(type 0x11)0x2', 'android:protectionLevel(0x01010009)=(type 0x11)0x0'),
            TREE.replace('android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0', 'android:usesCleartextTraffic(0x010104ec)=(type 0x12)0xffffffff'),
            TREE.replace('android:launchMode(0x0101001d)=(type 0x10)0x2', 'android:launchMode(0x0101001d)=(type 0x10)0x0'),
        ]
        for attribute in ('process', 'isolatedProcess', 'externalService', 'directBootAware'):
            mutations.append(TREE.replace('      E: provider (line=41)',
                             f'      E: provider (line=41)\n        A: android:{attribute}(0x01010000)="true"'))
        for kind, name in (('receiver', 'androidx.profileinstaller.ProfileInstallReceiver'),
                           ('provider', 'androidx.startup.InitializationProvider')):
            mutations.append(without_node(TREE, kind, name))
        for tree in mutations:
            with self.subTest(tree=tree), self.assertRaises(ValueError):
                check_apk_dump(PERMISSIONS, tree, PACKAGE, apk_contract=LEGACY)

    def test_current_omissions_stay_strict_despite_old_version_labels(self):
        for permission in ('ACCESS_NETWORK_STATE', 'RECEIVE_BOOT_COMPLETED', 'WAKE_LOCK'):
            permissions = current.PERMISSIONS.replace(f"uses-permission: name='android.permission.{permission}'\n", '')
            with self.subTest(permission=permission), self.assertRaisesRegex(ValueError, 'Permission allowlist'):
                check_apk_dump(permissions, current.TREE, PACKAGE)
        for kind, name in (('service', 'androidx.work.impl.background.systemjob.SystemJobService'),
                           ('receiver', 'androidx.work.impl.diagnostics.DiagnosticsReceiver'),
                           ('meta-data', 'androidx.work.WorkManagerInitializer')):
            tree = without_node(current.TREE, kind, name)
            with self.subTest(name=name), self.assertRaises(ValueError):
                check_apk_dump(current.PERMISSIONS, tree, PACKAGE)
        installed = ('Package [' + PACKAGE + ']\n  requested permissions:\n'
                     '    android.permission.INTERNET\n    ' + PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\n')
        with self.assertRaisesRegex(ValueError, 'Permission allowlist'):
            check_installed_dump(installed, PACKAGE)


class HistoricalInspectionTests(unittest.TestCase):
    def test_signed_legacy_inspection_preserves_backup_debug_launch_identity_and_signer_checks(self):
        fixture = current.ArtifactTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        identity = legacy_identity()
        badging = (f"package: name='{PACKAGE}' versionCode='9' versionName='0.1.0-rc.1'\n"
                   f"launchable-activity: name='{PACKAGE}.MainActivity'\n")
        signature = 'Signer #1 certificate SHA-256 digest: ' + asset_tests.CERT + '\n'
        resources = current.RESOURCES.replace('0x7f080000', '0x7f0d0000').replace('0x7f080001', '0x7f0d0001')
        def inspect(tree=TREE, badge=badging, signer=signature, backup=current.LEGACY):
            outputs = [(0, PERMISSIONS), (0, tree), (0, badge), (0, signer),
                       (0, resources), (0, backup), (0, current.EXTRACTION)]
            with patch.object(artifact, 'run_tool', side_effect=outputs):
                return artifact.inspect_apk(fixture.apk, identity, fixture.root, fixture.reports,
                                            expected_signer=asset_tests.CERT, apk_contract=LEGACY)
        result = inspect()
        self.assertTrue(result['backupExclusionsVerified'])
        self.assertFalse(result['unsigned'])
        self.assertEqual(result['signerCertificateSha256'], asset_tests.CERT)
        mutations = [
            {'tree': TREE.replace('android:allowBackup(0x01010280)=(type 0x12)0x0',
                                  'android:allowBackup(0x01010280)=(type 0x12)0xffffffff')},
            {'tree': TREE.replace('    E: application (line=19)',
                                  '    E: application (line=19)\n      A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff')},
            {'badge': badging.replace("versionCode='9'", "versionCode='8'")},
            {'badge': badging.replace("versionName='0.1.0-rc.1'", "versionName='0.2.0'")},
            {'badge': badging.replace(PACKAGE + '.MainActivity', PACKAGE + '.WrongActivity')},
            {'signer': signature.replace(asset_tests.CERT, 'e' * 64)},
            {'backup': current.LEGACY.replace('path="."', 'path="narrow"', 1)},
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                inspect(**mutation)


class HistoricalBindingTests(unittest.TestCase):
    def setUp(self):
        self.identity = legacy_identity()
        self.manifest = {'buildInputs': self.identity, 'authority': {}}
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.reports = Path(temporary.name)

    def test_selected_legacy_candidate_uses_admitted_source_and_checks_manifest_before_inspection(self):
        candidate = fixtures.CandidateTests()
        candidate.setUp()
        self.addCleanup(candidate.doCleanups)
        candidate.model.overrides.update(legacy_model().overrides)
        for filename in policy.SOURCE_GATES:
            path = '/actions/workflows/' + filename + '/runs?head_sha=' + FIRST_SOURCE['sha'] + '&event=push'
            candidate.model.overrides[path] = [{**candidate.model.pages(path)[0], 'head_sha': FIRST_SOURCE['sha']}]
        candidate.record, candidate.expected, candidate.origin = policy.admit(candidate.api, fixtures.configuration(), candidate.ctx)
        candidate.manifest['buildInputs'] = candidate.expected
        candidate.assets = {'release-manifest.json': canonical(candidate.manifest)}
        candidate.model.archive = signing.zip_bytes(candidate.assets)
        candidate.artifact['digest'] = 'sha256:' + fingerprint(candidate.model.archive)['sha256']
        with patch.object(policy, 'verify_assets', return_value=(candidate.manifest, None)) as verify:
            self.assertEqual(candidate.read(), candidate.assets)
            self.assertIs(verify.call_args.kwargs['apk_contract'], LEGACY)
            self.assertEqual(verify.call_args.kwargs['expected_binding'], {'tag': FIRST_TAG, 'source': FIRST_SOURCE})
            candidate.model.overrides['/contents/app/src/main/AndroidManifest.xml?ref=' + FIRST_SOURCE['sha']]['content'] = base64.b64encode(b'changed').decode()
            with self.assertRaisesRegex(ValueError, 'Historical source manifest'):
                candidate.read()
            verify.assert_called_once()

    def test_legacy_contract_cannot_be_requested_without_exact_independent_binding(self):
        assets = {'release-manifest.json': canonical({**self.manifest, 'schema': 1, 'kind': 'guarded-android-release',
                  'certificateSha256': 'd' * 64, 'bundleManifestSha256': 'e' * 64, 'assets': {}})}
        for kwargs in ({'apk_contract': LEGACY}, {'apk_contract': True},
                       {'apk_contract': LEGACY, 'expected_binding': {'tag': FIRST_TAG, 'source': {'sha': 'e' * 40, 'tree': 'f' * 40}}}):
            with self.subTest(kwargs=kwargs), patch.object(artifact, 'inspect_apk') as observe, self.assertRaises(ValueError):
                signing.verify_assets(self.reports, assets, self.reports, self.reports, **kwargs)
            observe.assert_not_called()
        with patch.object(artifact, 'run_tool') as tool, self.assertRaises(ValueError):
            artifact.inspect_apk(self.reports / 'unused.apk', self.identity, self.reports, self.reports, apk_contract=LEGACY)
        tool.assert_not_called()

class LegacyAssetTests(asset_tests.AssetTests):
    """Repeat existing full capsule/integrity controls with the optional legacy contract."""
    def build_inputs(self):
        return legacy_identity()

    def verify(self, assets=None, **kwargs):
        return super().verify(assets, expected_binding={key: self.expected[key] for key in ('tag', 'source')},
                              apk_contract=LEGACY, **kwargs)

    def test_optional_contract_still_checks_defined_classes_and_certificate(self):
        self.classes.append('unapproved.ExtraClass')
        with self.assertRaisesRegex(ValueError, 'defined classes'):
            self.verify()
        self.classes.pop()
        assets = dict(self.assets)
        record = json.loads(assets['release-manifest.json'])
        record['certificateSha256'] = 'e' * 64
        assets['release-manifest.json'] = canonical(record)
        assets['SHA256SUMS'] = signing.checksums(assets)
        with self.assertRaisesRegex(ValueError, 'certificate pin'):
            self.verify(assets)

    def test_inspector_receives_only_bound_contract_and_independent_signer(self):
        with patch.object(artifact, 'inspect_apk', return_value=self.inspection) as inspect, \
                patch.object(signing.bundle, 'observe_classes', return_value=sorted(self.classes)):
            signing.verify_assets(self.root, self.assets, self.root, self.root / 'reports',
                                  expected_certificate=asset_tests.CERT, apk_contract=LEGACY,
                                  expected_binding={key: self.expected[key] for key in ('tag', 'source')})
        self.assertEqual(inspect.call_args.kwargs, {'expected_signer': asset_tests.CERT, 'apk_contract': LEGACY})

    def test_self_rehashed_bundle_source_accepted_digest_and_sbom_tampering_still_fail(self):
        original = {path: path.read_bytes() for path in self.final.iterdir() if path.is_file()}
        for name, change, message in (
            ('runtime-inventory.json', lambda value: value['buildInputs']['source'].update(sha='e' * 40), 'capsule source'),
            ('bundle-manifest.json', lambda value: value['acceptedUnsigned'].update(bundleManifestSha256='e' * 64), 'unsigned capsule binding'),
            ('runtime.cdx.json', lambda value: value.update(serialNumber='urn:uuid:00000000-0000-0000-0000-000000000000'), 'legal/SBOM'),
        ):
            for path, data in original.items():
                path.write_bytes(data)
            path = self.final / name
            value = json.loads(path.read_bytes())
            change(value)
            path.write_bytes(canonical(value))
            if name != 'bundle-manifest.json':
                manifest_path = self.final / 'bundle-manifest.json'
                manifest = json.loads(manifest_path.read_bytes())
                entry = next(row for row in manifest['files'] if row['path'] == name)
                entry.update(fingerprint(path.read_bytes()))
                manifest_path.write_bytes(canonical(manifest))
            assets = signing.package_assets(self.signed, self.final, self.authority)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, message):
                self.verify(assets)

    def test_forged_version_and_source_cannot_relabel_a_legacy_capsule(self):
        for key, value in [('target', '0.2.0'), ('versionName', '0.2.0'),
                           ('source', {'sha': 'e' * 40, 'tree': 'f' * 40}), ('tag', 'v0.1.0-rc.2')]:
            assets = dict(self.assets)
            record = json.loads(assets['release-manifest.json'])
            record['buildInputs'][key] = value
            assets['release-manifest.json'] = canonical(record)
            assets['SHA256SUMS'] = signing.checksums(assets)
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.verify(assets)
