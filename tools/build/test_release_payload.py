"""Pure synthetic APK-dump/inventory parser controls, not Android artifact proof."""
import copy
import json
from pathlib import Path
import tempfile
import os
import unittest
import zipfile
from unittest.mock import patch

from complete_codeql_checkout import canonical, digest
from release_identity import identity_digest
from test_release_identity import fixture_identity
from verify_manifests import (NAMESPACE, MAIN_ACTIVITY, check_apk_dump, check_apk_identity,
                              check_backup_dump, check_manifest, variant_policy, backup_resource_files, check_backup_resources)
from verify_release_payload import (EVIDENCE_NAMES, METHODS, safe_file, unsigned_container,
                                    unsigned_diagnostic, validate_inventory, verify, source_record)


# Host-side test allocation stays separate from mocked Git child environments.
from complete_codeql_checkout import host_scratch
SCRATCH = host_scratch({'RUNNER_TEMP': os.environ.get('RUNNER_TEMP') if os.environ.get('GITHUB_ACTIONS') == 'true' else os.environ.get('BUILD_CONTRACT_SCRATCH')})


def tree(identity, variant='release'):
    package, receiver, _ = variant_policy(variant)
    result = f'''E: manifest (line=0)
  A: package="{package}"
  A: android:versionName(0x0101021c)="{identity['versionName']}"
  A: android:versionCode(0x0101021b)=(type 0x10)0x{identity['versionCode']:x}
  E: permission (line=1)
    A: android:name(0x01010003)="{receiver}"
    A: android:protectionLevel(0x01010009)=(type 0x11)0x2
  E: application (line=2)
    A: android:allowBackup(0x01010280)=(type 0x12)0x0
    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0
    A: android:fullBackupContent(0x010104eb)=@0x7f010001
    A: android:dataExtractionRules(0x0101064e)=@0x7f010002
    E: activity (line=3)
      A: android:name(0x01010003)="{MAIN_ACTIVITY}"
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
      A: android:launchMode(0x0101001d)=(type 0x10)0x2
      E: intent-filter (line=4)
        E: action (line=5)
          A: android:name(0x01010003)="android.intent.action.MAIN"
        E: category (line=6)
          A: android:name(0x01010003)="android.intent.category.LAUNCHER"
'''
    if variant == 'debug':
        result = result.replace('    A: android:allowBackup', '    A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n    A: android:allowBackup')
    for n, (key, value) in enumerate((('SOURCE_REVISION', identity['sourceSha']), ('SOURCE_TREE', identity['sourceTree']), ('BUILD_CHANNEL', identity['channel'])), 7):
        result += f'    E: meta-data (line={n})\n      A: android:name(0x01010003)="{NAMESPACE}.{key}"\n      A: android:value(0x01010024)="{value}"\n'
    return result


def backup(extraction=False):
    domains = ('root', 'file', 'database', 'sharedpref', 'external', 'device_root', 'device_file', 'device_database', 'device_sharedpref')
    result = 'E: data-extraction-rules (line=0)\n' if extraction else 'E: full-backup-content (line=0)\n'
    for section in (('cloud-backup', 'device-transfer') if extraction else ('full',)):
        indent = '    ' if extraction else '  '
        if extraction:
            result += f'  E: {section} (line=1)\n'
        for n, domain in enumerate(domains, 2):
            result += f'{indent}E: exclude (line={n})\n{indent}  A: domain="{domain}"\n{indent}  A: path="."\n'
    return result


def resource_table(variant='release'):
    # Deliberately synthetic aapt2 transcript; actual SDK formatting is hosted.
    package = variant_policy(variant)[0]
    return f"""Package name={package} id=7f
  type xml id=01 entryCount=2
    resource 0x7f010001 xml/backup_rules
      () (file) res/xml/backup_rules.xml type=XML
    resource 0x7f010002 xml/data_extraction_rules
      () (file) res/xml/data_extraction_rules.xml type=XML
"""


def resource_inspections():
    return {name: [{'configuration': '', 'path': 'res/xml/' + name + '.xml', 'xmltree': backup(extraction)}]
            for name, extraction in (('backup_rules', False), ('data_extraction_rules', True))}


class PayloadContracts(unittest.TestCase):
    def test_explicit_variant_never_normalizes_inspected_package_receiver_or_class(self):
        identity = fixture_identity()
        for variant in ('debug', 'release'):
            package, receiver, permissions = variant_policy(variant)
            dump = f'package: {package}\n' + ''.join(f"uses-permission: name='{p}'\n" for p in sorted(permissions))
            manifest = tree(identity, variant)
            self.assertEqual(check_apk_dump(dump, manifest, variant=variant, identity=identity), sorted(permissions))
            other = 'release' if variant == 'debug' else 'debug'
            with self.assertRaises(ValueError):
                check_apk_dump(dump, manifest, variant=other, identity=identity)
            for old, new in ((package, 'arbitrary.package'), (receiver, 'arbitrary.receiver'),
                             (MAIN_ACTIVITY, NAMESPACE + '.dev.MainActivity'), (MAIN_ACTIVITY, '.MainActivity'), (MAIN_ACTIVITY, 'MainActivity'), ('0x11)0x2', '0x11)0x1'),
                             ('0x10)0x29', '0x10)0x2a'), (identity['sourceSha'], 'd' * 40),
                             ('android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0', 'android:usesCleartextTraffic(0x010104ec)=(type 0x12)0xffffffff'),
                             ('android:allowBackup(0x01010280)=(type 0x12)0x0', 'android:allowBackup(0x01010280)=(type 0x12)0xffffffff')):
                with self.subTest(variant=variant, mutation=old), self.assertRaises(ValueError):
                    check_apk_dump(dump.replace(old, new), manifest.replace(old, new), variant=variant, identity=identity)

    def test_release_rejects_test_components_flags_duplicates_and_process_overrides(self):
        identity = fixture_identity()
        valid = tree(identity)
        for insertion in ('    A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n',
                          '    A: android:testOnly(0x01010272)=(type 0x12)0xffffffff\n',
                          '    A: android:process(0x01010011)=":other"\n'):
            changed = valid.replace('    A: android:allowBackup', insertion + '    A: android:allowBackup')
            with self.assertRaises(ValueError):
                check_apk_dump('package: ' + NAMESPACE + '\n' + ''.join(f"uses-permission: name='{n}'\n" for n in sorted(variant_policy('release')[2])), changed, variant='release', identity=identity)
        for extra in ('    E: activity (line=11)\n      A: android:name(0x01010003)="androidx.activity.ComponentActivity"\n',
                      '    E: provider (line=11)\n      A: android:name(0x01010003)="androidx.test.Bad"\n',
                      '  E: instrumentation (line=11)\n'):
            with self.assertRaises(ValueError):
                check_apk_identity(valid + extra, 'release', identity)
        with self.assertRaises(ValueError):
            check_apk_identity(valid.replace('  A: package=', '  A: package="wrong"\n  A: package='), 'release', identity)

    def test_backup_resource_content_requires_every_exclusion(self):
        for extraction in (False, True):
            valid = backup(extraction)
            check_backup_dump(valid, extraction)
            for changed in (valid.replace('A: path="."', 'A: path="narrow"', 1),
                            valid.replace('E: exclude', 'E: include', 1),
                            valid.replace('A: domain="database"', 'A: domain="file"', 1)):
                with self.assertRaises(ValueError):
                    check_backup_dump(changed, extraction)

    def test_unsigned_is_not_arbitrary_sdk_failure_or_signed_container(self):
        unsigned_diagnostic(1, '', 'DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n')
        unsigned_diagnostic(1, 'DOES NOT VERIFY\n', 'ERROR: Missing META-INF/MANIFEST.MF\n')
        for code, out, error in ((0, 'Verified\nSigner certificate', ''), (1, '', 'malformed APK'),
                                 (2, '', 'ERROR: Missing META-INF/MANIFEST.MF'), (True, '', 'DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF')):
            with self.assertRaises(ValueError):
                unsigned_diagnostic(code, out, error)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as scratch:
            path = Path(scratch)/'SYNTHETIC-container.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                for name in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc'):
                    archive.writestr(name, b'SYNTHETIC parser input, not Android bytes')
            self.assertGreater(unsigned_container(path)['size'], 0)
            with zipfile.ZipFile(path, 'a') as archive:
                archive.writestr('META-INF/SYNTHETIC.SF', b'not a signature')
            with self.assertRaises(ValueError):
                unsigned_container(path)

    def test_canonical_inventory_reverification_rejects_byte_source_producer_and_authority_drift(self):
        # These are explicitly synthetic ZIP/text parser inputs. The production
        # create path additionally requires actual successful SDK inspections.
        with tempfile.TemporaryDirectory(dir=SCRATCH) as scratch:
            root = Path(scratch)
            identity = fixture_identity()
            name = f"codexbar-mobile-{identity['versionName']}-release-c41-g{identity['shortSha']}-unsigned.apk"
            apk = root/name
            with zipfile.ZipFile(apk, 'w') as archive:
                for entry in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc'):
                    archive.writestr(entry, b'SYNTHETIC parser bytes; not Android evidence')
            package, _, permissions = variant_policy('release')
            texts = {'permissions.txt': f'package: {package}\n' + ''.join(f"uses-permission: name='{p}'\n" for p in sorted(permissions)),
                     'manifest.txt': tree(identity), 'backup.txt': backup(), 'extraction.txt': backup(True),
                     'resources.txt': resource_table(), 'backup-resources.json': json.dumps(resource_inspections(), sort_keys=True, separators=(',', ':')) + '\n',
                     'unsigned.txt': json.dumps({'exit': 1, 'stdout': '', 'stderr': 'DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n'})}
            for path, text in texts.items():
                (root/path).write_text(text)
            state = {'head': identity['sourceSha'], 'tree': identity['sourceTree'], 'root': scratch,
                     'trackedFiles': [{'path': 'VERSION', 'gitMode': '100644', 'blobSha': 'c' * 40, 'sha256': 'd' * 64}]}
            observation = {'mode': 'normal', 'state': state, 'context': {'sourceHeadSha': identity['sourceSha'], 'compiledCheckoutSha': identity['sourceSha'], 'checkoutRole': 'direct',
                          'producerEventSha': identity['sourceSha'], 'producerEventRef': 'refs/heads/main', 'eventName': 'push', 'eventSha256': 'f' * 64, 'admissionHeld': False}, 'tagMapSha256': 'e' * 64}
            producer = {'job': 'SYNTHETIC-parser-producer', 'runId': '1'}
            inventory = dict(schemaVersion=1, stage='unsigned', purpose=identity['purpose'], distributionAuthorized=False,
                             identity=identity, identitySha256=identity_digest(identity), source=source_record(observation, identity),
                             producer=producer, payload=dict(path=name, intendedFinalName='codexbar-mobile-' + identity['versionName'] + '.apk',
                             **unsigned_container(apk), signatureState='unsigned', applicationId=NAMESPACE, variant='release'),
                             verification=dict(methods=METHODS, evidence=[{'path': n, 'sha256': digest((root/n).read_bytes())} for n in EVIDENCE_NAMES],
                                               manifestPolicy='passed', identity='passed', unsigned='passed'), signedFinal=None)
            path = root/'SYNTHETIC-inventory.json'
            path.write_bytes(canonical(inventory))
            frozen = unsigned_container(apk)
            def mock_sdk(current_apk, sdk):
                # Models independent SDK rejection of replacement non-Android bytes.
                # This is a mock control, never actual SDK or artifact evidence.
                if unsigned_container(current_apk) != frozen:
                    raise ValueError('SYNTHETIC SDK rejected replacement bytes')
                return texts.copy()
            with patch('verify_release_payload.local_identity'), patch('verify_release_payload.read_snapshot', return_value=(None, observation)), patch('verify_release_payload.producer', return_value=producer), patch('verify_release_payload.inspect', side_effect=mock_sdk) as current_inspection:
                verify(root, path, identity, {'ANDROID_HOME': 'SYNTHETIC-pinned-SDK'})
                current_inspection.assert_called_once_with(apk, 'SYNTHETIC-pinned-SDK')
                mutations = [inventory | {'distributionAuthorized': True}, inventory | {'signedFinal': {'sha256': inventory['payload']['sha256']}},
                             inventory | {'purpose': 'official-input'}, inventory | {'schemaVersion': True}, inventory | {'unknown': True},
                             inventory | {'identitySha256': '0' * 64}, inventory | {'producer': {'job': 'SYNTHETIC-wrong-producer'}},
                             inventory | {'source': inventory['source'] | {'sourceHeadSha': 'f' * 40}},
                             inventory | {'source': inventory['source'] | {'sourceTree': 'f' * 40}},
                             inventory | {'source': inventory['source'] | {'trackedFiles': []}},
                             inventory | {'payload': inventory['payload'] | {'size': True}},
                             inventory | {'payload': inventory['payload'] | {'sha256': '0' * 64}},
                             inventory | {'payload': inventory['payload'] | {'applicationId': 'arbitrary.package'}},
                             inventory | {'payload': inventory['payload'] | {'path': '../outside.apk'}},
                             inventory | {'verification': inventory['verification'] | {'methods': ['unknown verifier']}},
                             inventory | {'verification': inventory['verification'] | {'evidence': inventory['verification']['evidence'][:-1]}}]
                for index, broken in enumerate(mutations):
                    path.write_bytes(canonical(broken))
                    with self.subTest(mutation=index), self.assertRaises(ValueError):
                        verify(root, path, identity, {'ANDROID_HOME': 'SYNTHETIC-pinned-SDK'})
                path.write_bytes(canonical(inventory))
                (root/'manifest.txt').write_text(texts['manifest.txt'] + 'SYNTHETIC corruption')
                with self.assertRaises(ValueError):
                    verify(root, path, identity, {'ANDROID_HOME': 'SYNTHETIC-pinned-SDK'})
                (root/'manifest.txt').write_text(texts['manifest.txt'])
                with zipfile.ZipFile(apk, 'w') as archive:
                    for entry in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc'):
                        archive.writestr(entry, b'SYNTHETIC coherently replaced invalid Android bytes')
                replaced = copy.deepcopy(inventory)
                replaced['payload'].update(unsigned_container(apk))
                path.write_bytes(canonical(replaced))
                current_inspection.reset_mock()
                with self.assertRaisesRegex(ValueError, 'SDK rejected replacement'):
                    verify(root, path, identity, {'ANDROID_HOME': 'SYNTHETIC-pinned-SDK'})
                current_inspection.assert_called_once_with(apk, 'SYNTHETIC-pinned-SDK')

    def test_paths_never_accept_absolute_traversal_or_symlinks(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as scratch:
            root = Path(scratch)
            for relative in ('../bad', '/bad', 'a/../b', './a', 'a//b', 'a\\b'):
                with self.assertRaises(ValueError):
                    safe_file(root, relative)
            (root/'link').symlink_to(root/'controlled-target')
            with self.assertRaises(ValueError):
                safe_file(root, 'link/a')


    def test_packaged_backup_ids_configurations_and_every_selected_inspection_are_bound(self):
        manifest, table, inspections = tree(fixture_identity()), resource_table(), resource_inspections()
        check_backup_resources(manifest, table, inspections)
        for oid in ('0x7f010001', '0x7f010002'):
            with self.subTest(oid=oid), self.assertRaises(ValueError):
                check_backup_resources(manifest.replace('@' + oid, '@0x7f010099'), table, inspections)
        qualified = table.replace('() (file) res/xml/backup_rules.xml type=XML',
                                  '() (file) res/xml/backup_rules.xml type=XML\n      (v31) (file) res/xml-v31/backup_rules.xml type=XML')
        with self.assertRaises(ValueError):
            check_backup_resources(manifest, qualified, inspections)
        all_variants = copy.deepcopy(inspections)
        all_variants['backup_rules'].append({'configuration': 'v31', 'path': 'res/xml-v31/backup_rules.xml', 'xmltree': backup()})
        check_backup_resources(manifest, qualified, all_variants)
        all_variants['backup_rules'][1]['xmltree'] = backup().replace('A: path="."', 'A: path="weakened"', 1)
        with self.assertRaises(ValueError):
            check_backup_resources(manifest, qualified, all_variants)
        for changed in (table.replace('xml/backup_rules', 'xml/unreviewed_rules'),
                        table.replace('res/xml/backup_rules.xml', 'res/xml/unreviewed.xml'),
                        table.replace('() (file) res/xml/backup_rules.xml type=XML', '() @0x7f010099'),
                        table.replace('() (file) res/xml/backup_rules.xml type=XML', '(v31) (file) res/xml-v31/backup_rules.xml type=XML')):
            with self.assertRaises(ValueError):
                backup_resource_files(manifest, changed)

    def test_launcher_is_one_complete_owned_filter_not_global_action_category_counts(self):
        valid = tree(fixture_identity())
        check_apk_identity(valid, 'release', fixture_identity())
        mutations = [valid.replace('        E: category', '      E: intent-filter (line=17)\n        E: category', 1),
                     valid.replace('      E: intent-filter', '    E: intent-filter', 1),
                     valid.replace('        E: category', '        E: action (line=17)\n          A: android:name(0x01010003)="android.intent.action.OTHER"\n        E: category', 1),
                     valid.replace('    E: meta-data', '        E: category (line=17)\n          A: android:name(0x01010003)="android.intent.category.OTHER"\n    E: meta-data', 1)]
        for index, changed in enumerate(mutations):
            with self.subTest(index=index), self.assertRaises(ValueError):
                check_apk_identity(changed, 'release', fixture_identity())

    def test_root_and_immediate_parent_symlinks_are_never_payload_paths(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as scratch:
            base = Path(scratch)
            real = base/'real'
            (real/'payload').mkdir(parents=True)
            (real/'payload/file').write_text('SYNTHETIC')
            (base/'root-link').symlink_to(real/'payload')
            (base/'parent-link').symlink_to(real)
            for root in (base/'root-link', base/'parent-link/payload'):
                with self.assertRaises(ValueError):
                    safe_file(root, 'file')
            self.assertEqual(safe_file(real/'payload', 'file').read_text(), 'SYNTHETIC')


    def test_selected_resource_structure_never_hides_qualified_or_orphan_rows(self):
        manifest, table, inspections = tree(fixture_identity()), resource_table(), resource_inspections()
        continuation = '      (v31) (file) res/xml-v31/backup_rules.xml type=XML\n'
        anchor = '      () (file) res/xml/backup_rules.xml type=XML\n'
        for boundary in ('      type xml id=01 entryCount=2\n', '      Package name=wrong id=7f\n',
                         '  type malformed\n', 'Package malformed\n',
                         '    resource 0x7f010001 xml/backup_rules\n'):
            changed = table.replace(anchor, anchor + boundary + continuation)
            with self.subTest(boundary=boundary), self.assertRaises(ValueError):
                check_backup_resources(manifest, changed, inspections)
        for changed in (continuation + table, table + continuation,
                        table.replace('    resource 0x7f010002', '      resource 0x7f010002'),
                        table.replace('  type xml id=01 entryCount=2', '      type xml id=01 entryCount=2')):
            with self.assertRaises(ValueError):
                backup_resource_files(manifest, changed)

    def test_effective_application_and_activity_enabled_and_data_free_launcher(self):
        identity = fixture_identity()
        valid = tree(identity)
        for anchor in ('    A: android:allowBackup', '      A: android:name'):
            indent = anchor[:len(anchor)-len(anchor.lstrip())]
            explicit = indent + 'A: android:enabled(0x0101000e)=(type 0x12)0xffffffff\n'
            check_apk_identity(valid.replace(anchor, explicit + anchor, 1), 'release', identity)
            for value in ('(type 0x12)0x0', '(type 0x10)0xffffffff', '@0x7f010099', '"true"', '(type 0x12)0x1'):
                changed = valid.replace(anchor, indent + 'A: android:enabled(0x0101000e)=' + value + '\n' + anchor, 1)
                with self.subTest(anchor=anchor, value=value), self.assertRaises(ValueError):
                    check_apk_identity(changed, 'release', identity)
            with self.assertRaises(ValueError):
                check_apk_identity(valid.replace(anchor, explicit * 2 + anchor, 1), 'release', identity)
        for extra in ('        E: data (line=19)\n          A: android:scheme(0x01010027)="https"\n',
                      '        E: unknown (line=19)\n',
                      '        A: android:priority(0x0101001c)=(type 0x10)0x1\n'):
            with self.assertRaises(ValueError):
                check_apk_identity(valid.replace('        E: action', extra + '        E: action', 1), 'release', identity)
        with self.assertRaises(ValueError):
            check_apk_identity(valid.replace('        E: category', '          E: category', 1), 'release', identity)

    def test_generated_enabled_and_launcher_structure_use_source_policy(self):
        from verify_manifests import ANDROID, source_metadata, check_generated_policy
        import xml.etree.ElementTree as ET
        identity = fixture_identity()
        root = ET.Element('manifest', {'package': NAMESPACE, ANDROID+'versionName': identity['versionName'],
                                       ANDROID+'versionCode': str(identity['versionCode'])})
        app = ET.SubElement(root, 'application', {ANDROID+'allowBackup': 'false', ANDROID+'fullBackupContent': '@xml/backup_rules',
                                                  ANDROID+'dataExtractionRules': '@xml/data_extraction_rules'})
        activity = ET.SubElement(app, 'activity', {ANDROID+'name': MAIN_ACTIVITY, ANDROID+'exported': 'true'})
        launcher = ET.SubElement(activity, 'intent-filter')
        ET.SubElement(launcher, 'action', {ANDROID+'name': 'android.intent.action.MAIN'})
        ET.SubElement(launcher, 'category', {ANDROID+'name': 'android.intent.category.LAUNCHER'})
        for key, value in source_metadata(identity).items():
            ET.SubElement(app, 'meta-data', {ANDROID+'name': key, ANDROID+'value': value})
        check_generated_policy(root, 'release', identity)
        for node in (app, activity):
            node.set(ANDROID+'enabled', 'true')
            check_generated_policy(root, 'release', identity)
            for value in ('false', '@bool/enabled', '1', 'unknown'):
                node.set(ANDROID+'enabled', value)
                with self.assertRaises(ValueError):
                    check_generated_policy(root, 'release', identity)
            node.attrib.pop(ANDROID+'enabled')
        extra = ET.SubElement(launcher, 'data', {ANDROID+'scheme': 'https'})
        with self.assertRaises(ValueError):
            check_generated_policy(root, 'release', identity)
        launcher.remove(extra)
        launcher[0].set(ANDROID+'unknown', 'selector')
        with self.assertRaises(ValueError):
            check_generated_policy(root, 'release', identity)
