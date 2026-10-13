"""Pure synthetic APK-dump/inventory parser controls, not Android artifact proof."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from complete_codeql_checkout import canonical, digest
from release_identity import identity_digest
from test_release_identity import fixture_identity
from verify_manifests import (NAMESPACE, MAIN_ACTIVITY, check_apk_dump, check_apk_identity,
                              check_backup_dump, check_manifest, variant_policy)
from verify_release_payload import (EVIDENCE_NAMES, METHODS, safe_file, unsigned_container,
                                    unsigned_diagnostic, validate_inventory, verify, source_record)


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
                             (MAIN_ACTIVITY, NAMESPACE + '.dev.MainActivity'), ('0x11)0x2', '0x11)0x1'),
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
        with tempfile.TemporaryDirectory(dir='/opt/data/tmp') as scratch:
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
        with tempfile.TemporaryDirectory(dir='/opt/data/tmp') as scratch:
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
                     'unsigned.txt': json.dumps({'exit': 1, 'stdout': '', 'stderr': 'DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n'})}
            for path, text in texts.items():
                (root/path).write_text(text)
            state = {'head': identity['sourceSha'], 'tree': identity['sourceTree'], 'root': scratch,
                     'trackedFiles': [{'path': 'VERSION', 'gitMode': '100644', 'blobSha': 'c' * 40, 'sha256': 'd' * 64}]}
            observation = {'mode': 'normal', 'state': state, 'context': {'sourceHeadSha': identity['sourceSha'], 'ref': 'refs/heads/main'}, 'tagMapSha256': 'e' * 64}
            producer = {'job': 'SYNTHETIC-parser-producer', 'runId': '1'}
            inventory = dict(schemaVersion=1, stage='unsigned', purpose=identity['purpose'], distributionAuthorized=False,
                             identity=identity, identitySha256=identity_digest(identity), source=source_record(observation, identity),
                             producer=producer, payload=dict(path=name, intendedFinalName='codexbar-mobile-' + identity['versionName'] + '.apk',
                             **unsigned_container(apk), signatureState='unsigned', applicationId=NAMESPACE, variant='release'),
                             verification=dict(methods=METHODS, evidence=[{'path': n, 'sha256': digest((root/n).read_bytes())} for n in EVIDENCE_NAMES],
                                               manifestPolicy='passed', identity='passed', unsigned='passed'), signedFinal=None)
            path = root/'SYNTHETIC-inventory.json'
            path.write_bytes(canonical(inventory))
            with patch('verify_release_payload.local_identity'), patch('verify_release_payload.read_snapshot', return_value=(None, observation)), patch('verify_release_payload.producer', return_value=producer):
                verify(root, path, identity, {})
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
                        verify(root, path, identity, {})
                path.write_bytes(canonical(inventory))
                (root/'manifest.txt').write_text(texts['manifest.txt'] + 'SYNTHETIC corruption')
                with self.assertRaises(ValueError):
                    verify(root, path, identity, {})

    def test_paths_never_accept_absolute_traversal_or_symlinks(self):
        with tempfile.TemporaryDirectory(dir='/opt/data/tmp') as scratch:
            root = Path(scratch)
            for relative in ('../bad', '/bad', 'a/../b', './a', 'a//b', 'a\\b'):
                with self.assertRaises(ValueError):
                    safe_file(root, relative)
            (root/'link').symlink_to('/opt/data/tmp')
            with self.assertRaises(ValueError):
                safe_file(root, 'link/a')
