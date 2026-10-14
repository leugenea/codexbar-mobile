"""Synthetic ZIP/aapt/apksigner observations only; no SDK/application probes."""
import copy
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import release_artifact as artifact
from verify_manifests import MAIN_ACTIVITY, PACKAGE, permissions_for

EXPECTED = {'source': {'sha': 'a' * 40, 'tree': 'b' * 40}, 'target': '0.1.0', 'channel': 'dev',
            'tag': None, 'versionName': '0.1.0-dev.27+gaaaaaaaaaaaa', 'versionCode': 1,
            'debugPackage': PACKAGE + '.dev', 'releasePackage': PACKAGE}
PERMISSIONS = 'package: ' + PACKAGE + '\n' + ''.join(
    f"uses-permission: name='{name}'\n" for name in sorted(permissions_for(PACKAGE)))
TREE = f'''E: manifest (line=1)
  E: permission (line=2)
    A: android:name(0x01010003)="{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    A: android:protectionLevel(0x01010009)=(type 0x11)0x2
  E: application (line=3)
    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0
    A: android:allowBackup(0x01010280)=(type 0x12)0x0
    A: android:fullBackupContent(0x010104eb)=@0x7f080000
    A: android:dataExtractionRules(0x0101064e)=@0x7f080001
    E: activity (line=4)
      A: android:name(0x01010003)="{MAIN_ACTIVITY}"
      A: android:launchMode(0x0101001d)=(type 0x10)0x2
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
'''
BADGING = (f"package: name='{PACKAGE}' versionCode='1' versionName='{EXPECTED['versionName']}' platformBuildVersionName='37'\n"
           f"launchable-activity: name='{MAIN_ACTIVITY}' label='Synthetic' icon=''\n")
SIGNATURE = 'DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n'

RESOURCES = 'spec resource 0x7f080000 synthetic:xml/backup_rules flags=0x0\n' + \
            'spec resource 0x7f080001 synthetic:xml/data_extraction_rules flags=0x0\n'


def exclusion_tree(root, sections=()):
    tree = 'E: ' + root + ' (line=1)\n'
    for section in sections or (None,):
        if section:
            tree += '  E: ' + section + ' (line=2)\n'
        for domain in ('root', 'file', 'database', 'sharedpref', 'external', 'device_root',
                       'device_file', 'device_database', 'device_sharedpref'):
            tree += f'    E: exclude (line=3)\n      A: domain="{domain}" (Raw: "{domain}")\n      A: path="." (Raw: ".")\n'
    return tree


LEGACY = exclusion_tree('full-backup-content')
EXTRACTION = exclusion_tree('data-extraction-rules', ('cloud-backup', 'device-transfer'))


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.apk = self.root / f'codexbar-mobile-{EXPECTED["versionName"]}-1-unsigned.apk'
        self.zip()
        self.reports = self.root / 'reports'

    def zip(self, signature=None):
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('AndroidManifest.xml', b'SYNTHETIC, not Android binary XML')
            if signature:
                archive.writestr(signature, b'SYNTHETIC signature')

    def inspect(self, permissions=PERMISSIONS, tree=TREE, badging=BADGING, signature=(1, SIGNATURE)):
        with patch.object(artifact, 'run_tool', side_effect=[(0, permissions), (0, tree), (0, badging), signature,
                                                           (0, RESOURCES), (0, LEGACY), (0, EXTRACTION)]):
            return artifact.inspect_apk(self.apk, EXPECTED, self.root / 'sdk', self.reports)

    def test_actual_byte_fingerprint_and_independent_expected_production_identity(self):
        result = self.inspect()
        self.assertTrue(result['unsigned'])
        self.assertFalse(result['allowBackup'])
        self.assertEqual(result['requestedPermissions'], sorted(permissions_for(PACKAGE)))
        fingerprint = artifact.apk_bytes(self.apk)
        self.assertEqual(fingerprint['size'], self.apk.stat().st_size)
        with patch.object(artifact, 'inspect_apk', return_value=result):
            receipt = artifact.inventory(self.apk, EXPECTED, '38491197590', '2', self.root, self.reports)
        self.assertEqual(receipt['source'], EXPECTED['source'])
        self.assertEqual(receipt['buildInputs'], EXPECTED)
        self.assertEqual(receipt['sha256'], fingerprint['sha256'])
        self.assertEqual(receipt['runAttempt'], '2')

    def test_identity_and_security_mutations_fail(self):
        for kwargs in (
            {'permissions': PERMISSIONS.replace(PACKAGE, PACKAGE + '.dev')},
            {'permissions': PERMISSIONS + "uses-permission: name='android.permission.CAMERA'\n"},
            {'badging': BADGING.replace("versionCode='1'", "versionCode='2'")},
            {'badging': BADGING.replace(EXPECTED['versionName'], '0.1.0')},
            {'badging': BADGING.replace(MAIN_ACTIVITY, PACKAGE + '.OtherActivity')},
            {'tree': TREE.replace('usesCleartextTraffic(0x010104ec)=(type 0x12)0x0', 'usesCleartextTraffic(0x010104ec)=(type 0x12)0xffffffff')},
            {'tree': TREE.replace('allowBackup(0x01010280)=(type 0x12)0x0', 'allowBackup(0x01010280)=(type 0x12)0xffffffff')},
            {'tree': TREE.replace('    E: activity', '    A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n    E: activity')},
            {'tree': TREE.replace('fullBackupContent', 'invalidReference')},
            {'tree': TREE.replace('dataExtractionRules', 'invalidReference')},
            {'tree': TREE.replace('launchMode(0x0101001d)=(type 0x10)0x2', 'launchMode(0x0101001d)=(type 0x10)0x0')},
            {'tree': TREE.replace('    E: activity', '    A: android:process(0x01010011)=":other"\n    E: activity')},
            {'tree': TREE.replace('exported(0x01010010)=(type 0x12)0xffffffff', 'exported(0x01010010)=(type 0x12)0x0')},
        ):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                self.inspect(**kwargs)

    def test_actual_backup_resources_reject_wrong_reference_or_weakened_exclusions(self):
        for tree, roots in ((LEGACY, ('full-backup-content',)),
                            (EXTRACTION, ('data-extraction-rules', 'cloud-backup', 'device-transfer'))):
            artifact.backup_exclusions(tree, roots)
            for bad in (tree.replace('exclude', 'include', 1), tree.replace('path="."', 'path="narrow"', 1),
                        tree.replace('domain="database"', 'domain="root"', 1)):
                with self.subTest(tree=bad), self.assertRaises(ValueError):
                    artifact.backup_exclusions(bad, roots)
        with patch.object(artifact, 'run_tool', return_value=(0, RESOURCES.replace('0x7f080000', '0x7f080099'))):
            self.reports.mkdir()
            with self.assertRaisesRegex(ValueError, 'reference'):
                artifact.inspect_backup(self.apk, self.root, TREE, self.reports)

    def test_signature_claim_and_generic_tool_failures_never_pass(self):
        for signature in ((0, 'Verifies'), (1, 'Java runtime missing'), (127, 'command not found'), (1, 'DOES NOT VERIFY')):
            with self.subTest(signature=signature), self.assertRaises(ValueError):
                self.inspect(signature=signature)
        with patch.object(artifact, 'run_tool', return_value=(1, 'aapt failed')), self.assertRaisesRegex(ValueError, 'aapt'):
            artifact.inspect_apk(self.apk, EXPECTED, self.root, self.reports)
        for name in ('META-INF/CERT.RSA', 'META-INF/CERT.SF', 'META-INF/CERT.EC'):
            self.zip(name)
            with self.subTest(signature=name), self.assertRaisesRegex(ValueError, 'signature'):
                artifact.apk_bytes(self.apk)
        self.zip()
        data = self.apk.read_bytes()
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            start = archive.start_dir
        self.apk.write_bytes(data[:start - 16] + b'APK Sig Block 42' + data[start:])
        with self.assertRaisesRegex(ValueError, 'signing block'):
            artifact.apk_bytes(self.apk)

    def test_filename_run_attempt_and_concurrent_byte_drift_rejections(self):
        for run, attempt in (('', '1'), ('01', '1'), ('1', '0'), ('1', '+1')):
            with self.subTest(run=run, attempt=attempt), self.assertRaises(ValueError):
                artifact.inventory(self.apk, EXPECTED, run, attempt, self.root, self.reports)
        with self.assertRaisesRegex(ValueError, 'filename'):
            artifact.inventory(self.root / 'wrong.apk', EXPECTED, '1', '1', self.root, self.reports)
        def drift(*args):
            with self.apk.open('ab') as output:
                output.write(b'byte drift')
            return {'unsigned': True}
        with patch.object(artifact, 'inspect_apk', side_effect=drift), self.assertRaisesRegex(ValueError, 'changed'):
            artifact.inventory(self.apk, EXPECTED, '1', '1', self.root, self.reports)

    def cli(self, operation, env=None):
        args = ['artifact', operation, '--root', str(self.root), '--expected-sha', EXPECTED['source']['sha'],
                '--apk', str(self.apk), '--manifest', str(self.root / 'manifest.json'), '--reports', str(self.reports)]
        defaults = {'GITHUB_RUN_ID': '38491197590', 'GITHUB_RUN_ATTEMPT': '1', 'ANDROID_HOME': str(self.root)}
        with patch('sys.argv', args), patch.dict(os.environ, defaults if env is None else {**defaults, **env}), \
                patch.object(artifact, 'resolve', return_value=EXPECTED), patch.object(artifact, 'git', return_value=''), \
                patch.object(artifact, 'inspect_apk', return_value={'unsigned': True}):
            artifact.main()

    def test_reverification_rejects_inventory_metadata_and_byte_tampering(self):
        self.cli('create')
        self.cli('verify')
        manifest = self.root / 'manifest.json'
        valid = json.loads(manifest.read_text())
        for field, value in (('source', {'sha': 'c' * 40, 'tree': 'b' * 40}), ('runId', '2'),
                             ('runAttempt', '2'), ('sha256', '0' * 64), ('size', 0),
                             ('filename', 'wrong.apk'), ('inspection', {'unsigned': False}),
                             ('buildInputs', {**EXPECTED, 'versionCode': 4}), ('schema', True),
                             ('buildInputs', {**EXPECTED, 'versionCode': True})):
            tampered = copy.deepcopy(valid)
            tampered[field] = value
            manifest.write_text(json.dumps(tampered))
            with self.subTest(field=field), self.assertRaises(SystemExit):
                self.cli('verify')
        manifest.write_text(json.dumps(valid))
        with self.apk.open('ab') as output:
            output.write(b'drift after upload')
        with self.assertRaises(SystemExit):
            self.cli('verify')


if __name__ == '__main__':
    unittest.main()
