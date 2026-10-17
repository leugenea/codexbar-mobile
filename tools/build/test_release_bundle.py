"""Read-only signed-final orchestration with synthetic signature text, never real keys."""
import copy
import json
import struct
from pathlib import Path
import unittest
from unittest.mock import patch

import release_artifact as artifact
import release_bundle as bundle
import release_runtime as runtime
from test_release_artifact import (EXPECTED, PERMISSIONS, TREE, BADGING, RESOURCES, LEGACY, EXTRACTION)
from test_release_runtime import RuntimeFixture, archive


CERT = 'c' * 64
SIGNED = 'Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: ' + CERT + '\n'


class FinalBundleTests(RuntimeFixture):
    def setUp(self):
        super().setUp()
        self.unsigned = self.root / 'unsigned-bundle'
        bundle.write_bundle(self.unsigned, self.files(), [self.apk])
        self.final = self.root / 'candidate-signed.apk'
        entries = list(runtime.members(self.apk.read_bytes()).items())
        self.final.write_bytes(archive([*reversed(entries), ('META-INF/CERT.SF', b'SYNTHETIC SF'),
                                        ('META-INF/CERT.RSA', b'SYNTHETIC certificate, NOT a signature')]))

    def final_files(self, inspection=None):
        signed = {'unsigned': False, 'signerCertificateSha256': CERT} if inspection is None else inspection
        with patch.object(artifact, 'inventory', return_value=self.receipt), \
                patch.object(bundle, 'observe_classes', return_value=sorted(self.classes)), \
                patch.object(artifact, 'inspect_apk', return_value=signed) as inspect:
            files = bundle.final_files(self.root, EXPECTED, self.apk, self.unsigned,
                                       self.final, CERT, self.root, self.root / 'reports')
        self.assertEqual(inspect.call_args.kwargs['expected_signer'], CERT)
        return files

    def test_final_exact_byte_binding_preserves_both_apks_and_unsigned_sidecars(self):
        before = {p: p.read_bytes() for p in [self.apk, self.final, *[p for p in self.unsigned.rglob('*') if p.is_file()]]}
        files = self.final_files()
        self.assertEqual(files, self.final_files())
        manifest = json.loads(files['bundle-manifest.json'])
        self.assertEqual(manifest['stage'], 'final-signed')
        self.assertEqual(manifest['apk'], runtime.file_record(self.final))
        self.assertEqual(manifest['acceptedUnsigned']['apk'], runtime.file_record(self.apk))
        self.assertEqual(manifest['acceptedUnsigned']['bundleManifestSha256'],
                         runtime.fingerprint((self.unsigned / 'bundle-manifest.json').read_bytes())['sha256'])
        self.assertFalse(manifest['distributionApproval'])
        bom = json.loads(files['runtime.cdx.json'])
        self.assertEqual(bom['metadata']['component']['hashes'][0]['content'], manifest['apk']['sha256'])
        self.assertEqual(files['runtime-inventory.json'], (self.unsigned / 'runtime-inventory.json').read_bytes())
        self.assertEqual(files['THIRD_PARTY_NOTICES.txt'], (self.unsigned / 'THIRD_PARTY_NOTICES.txt').read_bytes())
        output = self.root / 'final-bundle'
        bundle.write_bundle(output, files, [self.apk, self.final, self.unsigned])
        bundle.verify_files(output, self.final_files())
        self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_payload_mutations_including_non_signature_meta_inf_are_rejected(self):
        original = runtime.members(self.final.read_bytes())
        for mutation in (
            lambda d: d.update({'classes.dex': b'changed code'}), lambda d: d.pop('res/a.xml'),
            lambda d: d.update({'assets/extra.dat': b'new'}), lambda d: d.update({'lib/x86/libexample.so': b'changed'}),
            lambda d: d.update({'META-INF/runtime-metadata': b'not signing metadata'}),
            lambda d: d.update({'META-INF/INVALID.NAME.SF': b'not recognized'}),
        ):
            contents = copy.deepcopy(original)
            mutation(contents)
            self.final.write_bytes(archive(contents.items()))
            with self.subTest(contents=contents), self.assertRaisesRegex(ValueError, 'payload differs'):
                self.final_files()

    def test_accepted_unsigned_identity_omissions_and_notice_substitution_fail(self):
        inv_path = self.unsigned / 'runtime-inventory.json'
        original = inv_path.read_bytes()
        for mutate in (lambda i: i['members'].pop(), lambda i: i['classes'].pop(),
                       lambda i: i['attribution'].pop(), lambda i: i.update(buildInputs={**EXPECTED, 'versionCode': 2})):
            inv = json.loads(original)
            mutate(inv)
            inv_path.write_bytes(runtime.canonical(inv))
            with self.assertRaises(ValueError):
                self.final_files()
        inv_path.write_bytes(original)
        (self.unsigned / 'THIRD_PARTY_NOTICES.txt').write_text('wrong notices')
        with self.assertRaisesRegex(ValueError, 'bytes differ'):
            self.final_files()

    def test_final_input_drift_during_actual_inspection_fails(self):
        def drift(*args, **kwargs):
            self.final.write_bytes(self.final.read_bytes() + b'changed layout during inspection')
            return {'unsigned': False, 'signerCertificateSha256': CERT}
        with patch.object(artifact, 'inventory', return_value=self.receipt), \
                patch.object(bundle, 'observe_classes', return_value=sorted(self.classes)), \
                patch.object(artifact, 'inspect_apk', side_effect=drift), self.assertRaisesRegex(ValueError, 'changed'):
            bundle.final_files(self.root, EXPECTED, self.apk, self.unsigned,
                               self.final, CERT, self.root, self.root / 'reports')

    def test_signed_inspection_keeps_identity_security_backup_and_signer_requirements(self):
        outputs = [(0, PERMISSIONS), (0, TREE), (0, BADGING), (0, SIGNED),
                   (0, RESOURCES.replace('res/xml/backup_rules.xml', 'res/a.xml')
                    .replace('res/xml/data_extraction_rules.xml', 'res/b.xml')),
                   (0, LEGACY), (0, EXTRACTION)]
        # The production inspector resolves optimized names, not conventional source paths.
        contents = runtime.members(self.final.read_bytes())
        contents['res/b.xml'] = b'SYNTHETIC optimized extraction XML'
        self.final.write_bytes(archive(contents.items()))
        with patch.object(artifact, 'run_tool', side_effect=outputs):
            result = artifact.inspect_apk(self.final, EXPECTED, self.root, self.root / 'signed-reports', expected_signer=CERT)
        self.assertFalse(result['unsigned'])
        self.assertEqual(result['signerCertificateSha256'], CERT)
        for output in ((1, SIGNED), (0, 'generic success'), (0, SIGNED.replace(CERT, 'd' * 64)),
                       (0, SIGNED + 'Signer #2 certificate SHA-256 digest: ' + CERT + '\n')):
            with patch.object(artifact, 'run_tool', side_effect=[*outputs[:3], output]), self.assertRaises(ValueError):
                artifact.inspect_apk(self.final, EXPECTED, self.root, self.root / 'bad-signer', expected_signer=CERT)
        for bad in ('', '0' * 63, 'C' * 64, CERT + '\n'):
            with self.assertRaisesRegex(ValueError, 'canonical'):
                artifact.signer_certificate(SIGNED, bad)

    def test_all_four_cli_operations_regenerate_and_verify_synthetic_capsules(self):
        raw = self.root / 'resolution.json'
        receipt = self.root / 'artifact.json'
        raw.write_bytes(runtime.canonical(self.raw))
        receipt.write_bytes(runtime.canonical(self.receipt))
        unsigned = self.root / 'cli-unsigned'
        final = self.root / 'cli-final'
        common = ['--root', str(self.root), '--expected-sha', EXPECTED['source']['sha'],
                  '--sdk', str(self.root), '--reports', str(self.root / 'cli-reports')]
        for operation in ('create-unsigned', 'verify-unsigned', 'bind-final', 'verify-final'):
            options = (['--apk', str(self.apk), '--bundle', str(unsigned), '--resolution', str(raw),
                        '--artifact-manifest', str(receipt)] if operation.endswith('unsigned') else
                       ['--apk', str(self.final), '--bundle', str(final), '--unsigned-apk', str(self.apk),
                        '--unsigned-bundle', str(unsigned), '--expected-signer', CERT])
            with patch('sys.argv', ['bundle', operation, *common, *options]), \
                    patch.object(bundle, 'resolve', return_value=EXPECTED), patch.object(bundle, 'git', return_value=''), \
                    patch.object(bundle, 'observe_classes', return_value=sorted(self.classes)), \
                    patch.object(artifact, 'inventory', return_value=self.receipt), \
                    patch.object(artifact, 'inspect_apk', return_value={'unsigned': False, 'signerCertificateSha256': CERT}):
                bundle.main()
        self.assertEqual(runtime.load(final / 'bundle-manifest.json')['apk'], runtime.file_record(self.final))
        protected = unsigned / 'do-not-write-reports'
        with patch('sys.argv', ['bundle', 'verify-final', *common, *options, '--reports', str(protected)]), \
                patch.object(bundle, 'resolve', return_value=EXPECTED), patch.object(bundle, 'git', return_value=''), \
                self.assertRaises(SystemExit):
            bundle.main()
        self.assertFalse(protected.exists())
        before = self.apk.read_bytes()
        with patch('sys.argv', ['bundle', 'verify-final', *common, *options, '--reports', str(self.root)]), \
                patch.object(bundle, 'resolve', return_value=EXPECTED), patch.object(bundle, 'git', return_value=''), \
                self.assertRaises(SystemExit):
            bundle.main()
        self.assertEqual(self.apk.read_bytes(), before)

    def test_full_unsigned_regeneration_and_byte_tamper_rejection(self):
        raw_path = self.root / 'resolution.json'
        receipt_path = self.root / 'artifact.json'
        raw_path.write_bytes(runtime.canonical(self.raw))
        receipt_path.write_bytes(runtime.canonical(self.receipt))
        with patch.object(artifact, 'inventory', return_value=self.receipt), \
                patch.object(bundle, 'observe_classes', return_value=sorted(self.classes)):
            files = bundle.unsigned_files(self.root, EXPECTED, self.apk, receipt_path, raw_path, self.root, self.root / 'reports')
        bundle.verify_files(self.unsigned, files)
        self.raw['artifacts'] = []
        raw_path.write_bytes(runtime.canonical(self.raw))
        with patch.object(artifact, 'inventory', return_value=self.receipt), \
                patch.object(bundle, 'observe_classes', return_value=sorted(self.classes)), self.assertRaisesRegex(ValueError, 'Missing'):
            bundle.unsigned_files(self.root, EXPECTED, self.apk, receipt_path, raw_path, self.root, self.root / 'reports')


class CorpusTests(unittest.TestCase):
    def test_corresponding_psl_source_reproduces_cataloged_binary_hash(self):
        # OkHttp's preserved versioned generator: UTF-8 rules sorted by bytes,
        # normal/exception blocks prefixed by their big-endian byte lengths.
        source = (runtime.ROOT / bundle.LEGAL / 'psl/public_suffix_list.dat').read_bytes()
        groups = [set(), set()]
        for line in source.splitlines():
            if line.strip() and not line.startswith(b'//'):
                exception = line.startswith(b'!')
                groups[int(exception)].add(line[1:] if exception else line)
        blocks = [b''.join(line + b'\n' for line in sorted(group)) for group in groups]
        data = b''.join(struct.pack('>I', len(block)) + block for block in blocks)
        catalog = runtime.load(runtime.ROOT / bundle.CATALOG)
        psl = next(r for r in catalog['embedded'] if r['id'].endswith('#public-suffix-list'))
        self.assertEqual(runtime.fingerprint(data)['sha256'], psl['selector']['members'][0]['sha256'])

    def test_selected_futures_coordinates_have_exact_source_backed_attribution(self):
        sources = {
            'androidx.concurrent:concurrent-futures:1.1.0':
                ('cc06fb6045a6c066f50e237a3c2126bc88c16d358a57b3612ba633c9f530b0a0',
                 b'Copyright 2018 The Android Open Source Project'),
            'com.google.guava:listenablefuture:1.0':
                ('3d1bd8d4a39b293612a40e547ec51d9ce34fa638d7adeae83871cdbe2923b161',
                 b'Copyright (C) 2007 The Guava Authors'),
        }
        inventory = {'resolution': {'components': [{'id': key, 'status': 'observed'} for key in sources]},
                     'artifacts': [], 'members': [], 'classes': []}
        entries, files = bundle.attribution(runtime.ROOT, inventory)
        self.assertEqual({entry['id'] for entry in entries}, set(sources))
        for entry in entries:
            digest, copyright_text = sources[entry['id']]
            self.assertEqual(entry['sourceArchive']['sha256'], digest)
            self.assertEqual(entry['license'], 'Apache-2.0')
            evidence = next(row for row in entry['evidence'] if row['role'] == 'attribution')
            self.assertEqual(evidence['source'], entry['sourceArchive']['url'])
            self.assertIn(copyright_text, files['legal/' + evidence['path']])
        self.assertIn('legal/Apache-2.0.txt', files)

    def test_catalog_full_preserved_bytes_schemas_and_known_scoped_material(self):
        catalog = runtime.load(runtime.ROOT / bundle.CATALOG)
        self.assertEqual(len({r['id'] for r in catalog['components'] + catalog['embedded']}),
                         len(catalog['components']) + len(catalog['embedded']))
        for entry in catalog['components'] + catalog['embedded']:
            for evidence in entry['evidence']:
                data = runtime.confined(runtime.ROOT / bundle.LEGAL, evidence['path']).read_bytes()
                self.assertEqual(runtime.fingerprint(data), {k: evidence[k] for k in ('size', 'sha256')})
        children = {r['id'].split('#')[1]: r for r in catalog['embedded']}
        self.assertIsNone(children['flatbuffers']['version'])
        self.assertIn('BSD-3-Clause', children['flatbuffers']['license'])
        self.assertIn('MPL-2.0', children['public-suffix-list']['license'])
        self.assertTrue(any(e['role'] == 'source' for e in children['public-suffix-list']['evidence']))
        core = (runtime.ROOT / bundle.LEGAL / 'apache-imports/candidate-Android-Harmony-notice-sections.txt').read_text()
        self.assertIn('Intel', core)
        expected = {'bom-1.6.schema.json': '3e92dddbc30cf7f6a02b80f0942b1a4cfd4fb1c26f1dfc4310afa9d613cafb93',
                    'spdx.schema.json': 'baa9d3bd1ed57b6751b0887edead6b5063ff53ff7429cf85d476c6c94af0166e',
                    'jsf-0.82.schema.json': '8bae002c25e723db7ee1f26afde680ae1a2b1a8f6b4b4b0fd65dc3becb090aae'}
        for name, digest in expected.items():
            self.assertEqual(runtime.fingerprint((runtime.ROOT / 'tools/build/schemas/cyclonedx' / name).read_bytes())['sha256'], digest)


if __name__ == '__main__':
    unittest.main()
