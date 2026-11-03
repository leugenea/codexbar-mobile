"""Bounded historical-policy regressions; remote/SDK outcomes are synthetic unless labeled captured."""
import base64
import copy
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

import historical_apk_contract as contracts
from historical_apk_fixtures import SOURCE_MANIFEST, PERMISSIONS, TREE
import release_artifact as artifact
import release_bundle as bundle
import release_policy as policy
import release_signing as signing
from release_publication import marker
from release_runtime import canonical, file_record, fingerprint, members
import test_release_artifact as current
import test_release_policy as fixtures
import test_release_signing as asset_tests
from verify_manifests import PACKAGE, check_apk_dump, check_installed_dump
from work_manifest_policy import apk_components

LEGACY = contracts.ApkContract.PRE_WORKMANAGER
FIRST_SOURCE = {'sha': '296e030c68e79235397d7e4d082aaf463ce95f7e',
                'tree': '30353f7174afd47b5e62d676e434bcffa1aafefb'}
FIRST_TAG = 'v0.1.0-rc.1'
PRE_BRANDING = contracts.RenderingContract.PRE_BRANDING
THIRD_SOURCE = {'sha': '5cfb6f9e3f1e06ea6cea415acdad34a210984568',
                'tree': '830f21a42e920fd8abd3694b8301885a694bb053'}


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
        self.model = legacy_model()
        self.api = fixtures.wrapper(self.model)
        self.record = {**fixtures.configuration(), **fixtures.context(), 'workflowId': 8}
        self.identity = legacy_identity()
        self.authority = {'repository': self.api.repository, 'workflowId': 8, 'controllerSha': 'c' * 40,
                          'runId': 90, 'runAttempt': 2}
        self.manifest = {'buildInputs': self.identity, 'authority': self.authority}
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.reports = Path(temporary.name)

    def read(self, **kwargs):
        assets = {'release-manifest.json': canonical(self.manifest)}
        return policy.verify_remote_assets(self.api, self.record, assets, self.reports, self.reports, **kwargs)

    def observe(self, root, assets, sdk, reports, **kwargs):
        self.assertEqual(kwargs['expected_binding'], {key: self.identity[key] for key in ('tag', 'source')})
        self.assertEqual(kwargs['expected_authority'], self.authority)
        self.assertEqual(kwargs['expected_certificate'], 'd' * 64)
        self.assertIs(kwargs['apk_contract'], LEGACY)
        self.assertIs(kwargs['rendering_contract'], contracts.rendering_for(
            self.identity['tag'], self.identity['source'], self.authority['controllerSha']))
        self.assertIn('/git/ref/tags/' + self.identity['tag'], self.model.calls)
        self.assertIn('/git/commits/' + self.identity['source']['sha'], self.model.calls)
        self.assertIn('/contents/app/src/main/AndroidManifest.xml?ref=' + self.identity['source']['sha'], self.model.calls)
        check_apk_dump(PERMISSIONS, TREE, PACKAGE, apk_contract=kwargs['apk_contract'])
        return copy.deepcopy(self.manifest), None

    def test_all_seven_reviewed_tags_resolve_before_legacy_observation(self):
        observed = []
        for sha, (tree, tags) in contracts.LEGACY_SOURCES.items():
            for tag in sorted(tags):
                self.identity = legacy_identity(tag, {'sha': sha, 'tree': tree})
                self.manifest['buildInputs'] = self.identity
                self.model = legacy_model(self.identity)
                self.api = fixtures.wrapper(self.model)
                with self.subTest(tag=tag), patch.object(policy, 'verify_assets', side_effect=self.observe):
                    self.assertEqual(self.read(), self.manifest)
                observed.append(tag)
        self.assertEqual(set(observed), {'v0.1.0-rc.1', 'v0.1.0-rc.2', 'v0.1.0', 'v0.2.0-rc.1',
                                         'v0.2.0-rc.2', 'v0.2.0-rc.3', 'v0.2.0'})
        self.assertEqual(len(contracts.LEGACY_SOURCES), 5)
        self.assertEqual(fingerprint(SOURCE_MANIFEST)['sha256'], contracts.LEGACY_MANIFEST_SHA256)

    def test_nine_rendering_identities_require_authenticated_source_and_producer(self):
        observed = set()
        for sha, (tree, tags, controller) in contracts.PRE_BRANDING_SOURCES.items():
            for tag in tags:
                self.identity = legacy_identity(tag, {'sha': sha, 'tree': tree})
                self.manifest['buildInputs'] = self.identity
                self.authority['controllerSha'] = controller
                self.model = legacy_model(self.identity)
                path = '/actions/runs/90/attempts/2'
                origin = {**self.model.get(path), 'head_sha': controller}
                self.model.overrides[path] = origin
                self.api = fixtures.wrapper(self.model)
                with patch.object(policy, 'verify_assets', return_value=(self.manifest, None)) as verify:
                    self.read()
                    self.assertIs(verify.call_args.kwargs['rendering_contract'], PRE_BRANDING)
                    self.assertIs(verify.call_args.kwargs['apk_contract'], contracts.contract_for(tag, self.identity['source']))
                    self.assertIn('/git/ref/tags/' + tag, self.model.calls)
                    self.assertIn('/git/commits/' + sha, self.model.calls)
                    observed.add(tag)
                    # Even an authenticated but different producer cannot gain old rendering.
                    self.authority['controllerSha'] = 'e' * 40
                    self.model.overrides[path] = {**origin, 'head_sha': 'e' * 40}
                    self.read()
                    self.assertIsNone(verify.call_args.kwargs['rendering_contract'])
        self.assertEqual(observed, {'v0.1.0-rc.1', 'v0.1.0-rc.2', 'v0.1.0', 'v0.2.0-rc.1',
                                    'v0.2.0-rc.2', 'v0.2.0-rc.3', 'v0.2.0', 'v0.3.0-rc.1', 'v0.3.0'})
        for tag in ('v0.3.0-rc.1', 'v0.3.0'):
            source = next({'sha': sha, 'tree': row[0]} for sha, row in contracts.PRE_BRANDING_SOURCES.items() if tag in row[1])
            self.assertIsNone(contracts.contract_for(tag, source))

    def test_unknown_or_mismatched_rendering_identities_never_select_compatibility(self):
        for tag, source, controller in [
            ('v0.3.0-rc.2', THIRD_SOURCE, THIRD_SOURCE['sha']),
            ('v0.3.0', {**THIRD_SOURCE, 'sha': 'e' * 40}, THIRD_SOURCE['sha']),
            ('v0.3.0', {**THIRD_SOURCE, 'tree': 'e' * 40}, THIRD_SOURCE['sha']),
            ('v0.3.0', THIRD_SOURCE, 'e' * 40),
        ]:
            with self.subTest(tag=tag, source=source, controller=controller):
                self.assertIsNone(contracts.rendering_for(tag, source, controller))

    def test_tag_source_tree_and_manifest_drift_fail_before_apk_observation(self):
        paths = [('/git/ref/tags/' + FIRST_TAG, {'object': {'type': 'commit', 'sha': 'e' * 40}}),
                 ('/git/commits/' + FIRST_SOURCE['sha'], {'tree': {'sha': 'e' * 40}}),
                 ('/contents/app/src/main/AndroidManifest.xml?ref=' + FIRST_SOURCE['sha'],
                  {'encoding': 'base64', 'content': base64.b64encode(b'forged manifest').decode()})]
        for path, response in paths:
            original = copy.deepcopy(self.model.overrides)
            self.model.overrides[path] = response
            with self.subTest(path=path), patch.object(policy, 'verify_assets') as observe, self.assertRaises(ValueError):
                self.read()
            observe.assert_not_called()
            self.model.overrides = original
        for key, value in [('source', {'sha': 'e' * 40, 'tree': FIRST_SOURCE['tree']}),
                           ('source', {'sha': FIRST_SOURCE['sha'], 'tree': 'e' * 40}), ('tag', 'v0.1.0-rc.2')]:
            original = copy.deepcopy(self.identity)
            self.identity[key] = value
            with self.subTest(key=key), patch.object(policy, 'verify_assets') as observe, self.assertRaises(ValueError):
                self.read(tag=FIRST_TAG)
            observe.assert_not_called()
            self.identity.clear()
            self.identity.update(original)

    def test_complete_seven_release_history_keeps_counter_floor_and_no_skipped_entry(self):
        assets_by_id, observed = {}, []
        self.model.records = []
        for sha, (tree, tags) in contracts.LEGACY_SOURCES.items():
            for tag in sorted(tags):
                release_id = len(assets_by_id) + 1
                identity = legacy_identity(tag, {'sha': sha, 'tree': tree}, release_id)
                authority = {**self.authority, 'runId': 1000 + release_id,
                             'controllerSha': contracts.PRE_BRANDING_SOURCES[sha][2]}
                manifest = {'buildInputs': identity, 'authority': authority}
                assets = {'release-manifest.json': canonical(manifest)}
                assets_by_id[release_id] = assets
                self.model.overrides.update(legacy_model(identity).overrides)
                run = {**self.model.get('/actions/runs/90'), 'id': authority['runId'], 'run_number': release_id,
                       'head_sha': authority['controllerSha']}
                self.model.overrides[f'/actions/runs/{authority["runId"]}/attempts/2'] = run
                self.model.records.append({'id': release_id, 'tag_name': tag, 'target_commitish': sha,
                                           'name': tag[1:], 'body': marker(assets), 'draft': False,
                                           'prerelease': identity['channel'] == 'rc'})
        def observe(root, assets, sdk, reports, **kwargs):
            manifest = json.loads(assets['release-manifest.json'])
            self.identity, self.authority = manifest['buildInputs'], manifest['authority']
            self.manifest = manifest
            observed.append(self.identity['tag'])
            return self.observe(root, assets, sdk, reports, **kwargs)
        expected = {**legacy_identity(), 'tag': 'v0.3.0-rc.1', 'versionCode': 20}
        with patch.object(policy, 'read_assets', side_effect=lambda api, release_id: assets_by_id[release_id]), \
                patch.object(policy, 'verify_assets', side_effect=observe):
            self.assertEqual(policy.history(self.api, self.record, expected, self.reports, self.reports), (7, False))
            self.assertEqual(len(observed), 7)
            self.assertEqual(set(observed), {row['tag_name'] for row in self.model.records})
            duplicate = json.loads(assets_by_id[2]['release-manifest.json'])
            duplicate['buildInputs']['versionCode'] = 1
            assets_by_id[2]['release-manifest.json'] = canonical(duplicate)
            self.model.records[1]['body'] = marker(assets_by_id[2])
            self.model.overrides['/actions/runs/1002/attempts/2']['run_number'] = 1
            with self.assertRaisesRegex(ValueError, 'Reused official versionCode'):
                policy.history(self.api, self.record, expected, self.reports, self.reports)

    def test_unknown_sources_tags_and_tree_never_select_legacy(self):
        for tag, source in [('v0.2.0-rc.9', FIRST_SOURCE),
                            (FIRST_TAG, {'sha': 'e' * 40, 'tree': FIRST_SOURCE['tree']}),
                            (FIRST_TAG, {**FIRST_SOURCE, 'tree': 'e' * 40})]:
            with self.subTest(tag=tag, source=source):
                self.assertIsNone(contracts.contract_for(tag, source))
        self.identity['source'] = {'sha': 'e' * 40, 'tree': 'f' * 40}
        self.model = legacy_model(self.identity)
        self.api = fixtures.wrapper(self.model)
        def observe(root, assets, sdk, reports, **kwargs):
            self.assertIsNone(kwargs['apk_contract'])
            check_apk_dump(PERMISSIONS, TREE, PACKAGE, apk_contract=kwargs['apk_contract'])
        with patch.object(policy, 'verify_assets', side_effect=observe), self.assertRaisesRegex(ValueError, 'Permission allowlist'):
            self.read()

    def test_foreign_failed_stale_origin_and_reused_counter_fail_before_policy_selection(self):
        original = self.model.get('/actions/runs/90/attempts/2')
        for change in ({'head_sha': 'e' * 40}, {'run_number': 8}, {'run_attempt': 1},
                       {'status': 'in_progress'}, {'conclusion': 'failure'}, {'head_branch': 'feature'},
                       {'repository': {'full_name': 'fork/repo'}}, {'workflow_id': 9}):
            self.model.overrides['/actions/runs/90/attempts/2'] = {**original, **change}
            with self.subTest(change=change), patch.object(policy, 'bound_contract') as select, self.assertRaises(ValueError):
                self.read()
            select.assert_not_called()

    def test_retained_legacy_candidate_authenticates_then_reserves_tag(self):
        self.authority['controllerSha'] = 'e20afff53f44075e1e461b0fefa937d96f77c48c'
        self.model.overrides['/actions/runs/90/attempts/2'] = {
            **self.model.get('/actions/runs/90/attempts/2'), 'head_sha': self.authority['controllerSha']}
        raw = signing.zip_bytes({'release-manifest.json': canonical(self.manifest)})
        self.model.archive = raw
        run = {**self.model.get('/actions/runs/90/attempts/2'), 'repository': {'id': 7}}
        artifact_record = {'id': 80, 'name': 'signed-90-2', 'expired': False,
                           'digest': 'sha256:' + fingerprint(raw)['sha256'],
                           'workflow_run': {'id': 90, 'head_sha': self.authority['controllerSha']}}
        self.model.overrides['/actions/workflows/8/runs?event=workflow_dispatch'] = [run]
        self.model.overrides['/actions/runs/90/artifacts'] = [artifact_record]
        self.model.overrides['/actions/artifacts/80'] = artifact_record
        with patch.object(policy, 'verify_assets', side_effect=self.observe) as verify:
            policy.prepared_tags(self.api, self.record, {**self.identity, 'tag': 'v0.3.0-rc.1'}, set(), self.reports, self.reports)
            self.assertIs(verify.call_args.kwargs['rendering_contract'], PRE_BRANDING)
            with self.assertRaisesRegex(ValueError, 'Tag already admitted'):
                policy.prepared_tags(self.api, self.record, self.identity, set(), self.reports, self.reports)
            self.assertEqual(policy.candidate_assets(self.api, self.record, self.identity, run, self.reports, self.reports),
                             {'release-manifest.json': canonical(self.manifest)})
            self.assertIs(verify.call_args.kwargs['rendering_contract'], PRE_BRANDING)
        artifact_record['expired'] = True
        with patch.object(policy, 'verify_assets') as observe, self.assertRaisesRegex(ValueError, 'Expired unresolved'):
            policy.prepared_tags(self.api, self.record, self.identity, set(), self.reports, self.reports)
        observe.assert_not_called()
        artifact_record['expired'] = False
        attempt_path = '/actions/runs/90/attempts/2'
        original = self.model.get(attempt_path)
        for change in ({'conclusion': 'failure'}, {'run_number': 8}, {'head_sha': 'e' * 40}):
            self.model.overrides[attempt_path] = {**original, **change}
            with self.subTest(change=change), patch.object(policy, 'verify_assets') as observe, self.assertRaises(ValueError):
                policy.prepared_tags(self.api, self.record, self.identity, set(), self.reports, self.reports)
            observe.assert_not_called()

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


class PreBrandingRenderingTests(asset_tests.RuntimeFixture):
    """Small deterministic pre-branding capsule; APK/SDK observations are synthetic."""
    def setUp(self):
        with patch('test_release_runtime.archive', side_effect=lambda rows: signing.zip_bytes(dict(rows))):
            super().setUp()
        self.expected = legacy_identity('v0.3.0', THIRD_SOURCE, 19)
        self.inventory['buildInputs'] = self.receipt['buildInputs'] = self.expected
        self.receipt['source'] = THIRD_SOURCE
        self.binding = {key: self.expected[key] for key in ('tag', 'source')}
        self.authority = {'repository': 'leugenea/codexbar-mobile', 'workflowId': 388385730,
                          'controllerSha': THIRD_SOURCE['sha'], 'runId': 40378036550, 'runAttempt': 1}
        self.rendering: dict = {'rendering_contract': PRE_BRANDING, 'expected_binding': self.binding,
                          'controller_sha': THIRD_SOURCE['sha']}
        # Construct old bytes independently of the compatibility branch. Pin the
        # resulting unsigned digest below, not an expected value from render().
        self.old = self.files()
        legal = {name: data for name, data in self.old.items()
                 if name.startswith('legal/') and not name.startswith('legal/branding/')}
        self.old = {name: data for name, data in self.old.items() if not name.startswith('legal/branding/')}
        self.old['THIRD_PARTY_NOTICES.txt'] = bundle.notices(json.loads(self.old['runtime-inventory.json'])['attribution'], legal)
        manifest = json.loads(self.old.pop('bundle-manifest.json'))
        manifest['files'] = [{'path': name, **fingerprint(data)} for name, data in sorted(self.old.items())]
        self.old['bundle-manifest.json'] = canonical(manifest)
        self.accepted = {'apk': self.inventory['apk'],
                         'bundleManifestSha256': fingerprint(self.old['bundle-manifest.json'])['sha256']}
        self.signed = self.root / signing.asset_names(self.expected)[0]
        self.signed.write_bytes(signing.zip_bytes({**members(self.apk.read_bytes()), 'META-INF/CERT.SF': b'SYNTHETIC'}))
        self.inspection = {'unsigned': False, 'signerCertificateSha256': asset_tests.CERT}
        # Rebind the independently constructed old unsigned files to signed bytes.
        final = dict(self.old)
        final['runtime.cdx.json'] = canonical(bundle.sbom(self.inventory, json.loads(final['runtime-inventory.json'])['attribution'],
                                                         file_record(self.signed)))
        manifest.update(stage='final-signed', apk=file_record(self.signed), signedInspection=self.inspection,
                        acceptedUnsigned=self.accepted)
        manifest['files'] = [{'path': name, **fingerprint(data)} for name, data in sorted(final.items())
                             if name != 'bundle-manifest.json']
        final['bundle-manifest.json'] = canonical(manifest)
        self.final = self.root / 'old-final'
        bundle.write_bundle(self.final, final, [self.apk, self.signed])
        self.assets = signing.package_assets(self.signed, self.final, self.authority)

    def verify(self, assets=None, **overrides):
        kwargs = {'expected_binding': self.binding, 'expected_authority': self.authority, 'rendering_contract': PRE_BRANDING}
        kwargs.update(overrides)
        with patch.object(artifact, 'inspect_apk', return_value=self.inspection) as inspect, \
                patch.object(bundle, 'observe_classes', return_value=sorted(self.classes)):
            result = signing.verify_assets(self.root, self.assets if assets is None else assets, self.root,
                                           self.root / 'reports', expected_certificate=asset_tests.CERT, **kwargs)
        self.assertEqual(inspect.call_args.kwargs, {'expected_signer': asset_tests.CERT, 'apk_contract': None})
        return result

    def test_historical_unsigned_and_complete_final_roundtrip_preserve_current_apk_policy(self):
        self.assertEqual(self.accepted['bundleManifestSha256'],
                         '3cd9112ab6fec8f4ca720b0dc5c7710b4389ea9b503408b6622271022fdcc003')
        self.assertEqual(bundle.render(self.root, self.inventory, self.receipt, **self.rendering), self.old)
        self.assertEqual(self.verify()[0]['buildInputs'], self.expected)
        with self.assertRaisesRegex(ValueError, 'accepted unsigned capsule binding'):
            self.verify(rendering_contract=None)

    def test_unbound_unknown_and_mismatched_rendering_contracts_fail_before_inspection(self):
        for overrides in ({'expected_binding': None}, {'expected_authority': None},
                          {'expected_binding': {**self.binding, 'source': {**THIRD_SOURCE, 'tree': 'e' * 40}}},
                          {'expected_binding': {**self.binding, 'tag': 'v0.3.0-rc.2'}},
                          {'expected_authority': {**self.authority, 'controllerSha': 'e' * 40}},
                          {'rendering_contract': True}, {'rendering_contract': 'pre-branding'}):
            kwargs = {'expected_binding': self.binding, 'expected_authority': self.authority, 'rendering_contract': PRE_BRANDING}
            kwargs.update(overrides)
            with self.subTest(overrides=overrides), patch.object(artifact, 'inspect_apk') as inspect, self.assertRaises(ValueError):
                signing.verify_assets(self.root, self.assets, self.root, self.root / 'reports', **kwargs)
            inspect.assert_not_called()
        for rendering in ({'rendering_contract': PRE_BRANDING}, {**self.rendering, 'controller_sha': None},
                          {**self.rendering, 'rendering_contract': True}):
            with self.assertRaises(ValueError):
                bundle.render(self.root, self.inventory, self.receipt, **rendering)

    def test_default_requires_branding_and_current_source_cannot_use_old_rendering(self):
        current_files = self.files()
        self.assertEqual(set(current_files) - set(self.old),
                         {'legal/branding/chatgpt-mark-MIT.txt', 'legal/branding/chatgpt-mark-SOURCE.txt'})
        self.inventory['buildInputs'] = self.receipt['buildInputs'] = current.EXPECTED
        self.receipt['source'] = current.EXPECTED['source']
        with self.assertRaises(ValueError):
            bundle.render(self.root, self.inventory, self.receipt, **self.rendering)
        (self.root / bundle.LEGAL / 'branding/chatgpt-mark-MIT.txt').unlink()
        with self.assertRaises(FileNotFoundError):
            self.files()

    def test_historical_complete_bytes_accepted_digest_and_legal_evidence_remain_mandatory(self):
        for name in ('THIRD_PARTY_NOTICES.txt', 'runtime.cdx.json', 'legal/license.txt', 'bundle-manifest.json'):
            path = self.final / name
            original = path.read_bytes()
            if name == 'bundle-manifest.json':
                manifest = json.loads(original)
                manifest['acceptedUnsigned']['bundleManifestSha256'] = 'e' * 64
                path.write_bytes(canonical(manifest))
            else:
                path.write_bytes(original + b'changed')
            bad = signing.package_assets(self.signed, self.final, self.authority)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'legal/SBOM|unsigned capsule binding'):
                self.verify(bad)
            path.write_bytes(original)
        (self.root / bundle.LEGAL / 'license.txt').write_bytes(b'changed source legal evidence')
        with self.assertRaisesRegex(ValueError, 'License/NOTICE/source bytes changed'):
            self.verify()

    def test_historical_rendering_keeps_payload_classes_and_certificate_checks(self):
        self.classes.append('unapproved.ExtraClass')
        with self.assertRaisesRegex(ValueError, 'defined classes'):
            self.verify()
        self.classes.pop()
        for mutation, message in (('certificate', 'certificate pin'), ('payload', 'signed payload')):
            assets = dict(self.assets)
            record = json.loads(assets['release-manifest.json'])
            if mutation == 'certificate':
                record['certificateSha256'] = 'e' * 64
            else:
                name = self.signed.name
                assets[name] = signing.zip_bytes({**members(assets[name]), 'unexpected.bin': b'changed'})
                record['assets'][name] = fingerprint(assets[name])
            assets['release-manifest.json'] = canonical(record)
            assets['SHA256SUMS'] = signing.checksums(assets)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, message):
                self.verify(assets)
