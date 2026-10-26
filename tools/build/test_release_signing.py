"""Pure signing-wrapper and full capsule packaging controls; never generate keys or run SDK tools."""
import base64
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release_artifact as artifact
import release_bundle as bundle
import release_signing as signing
import release_runtime as runtime
from test_release_artifact import EXPECTED
from test_release_runtime import RuntimeFixture, archive

CERT = 'c' * 64


class SigningTests(unittest.TestCase):
    def test_materialization_requires_every_explicit_secret_and_removes_private_files_on_failure(self):
        env = dict(zip(signing.SECRETS, [base64.b64encode(b'SYNTHETIC NOT A KEYSTORE').decode(), 'owner-alias',
                                       'synthetic-store-password', 'synthetic-key-password']))
        with tempfile.TemporaryDirectory() as directory:
            for key in signing.SECRETS:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    with signing.materialize({**env, key: ''}, directory):
                        self.fail('missing secret admitted')
            with self.assertRaisesRegex(ValueError, 'synthetic failure'):
                with signing.materialize(env, directory) as key:
                    self.assertEqual(key[0].stat().st_mode & 0o777, 0o600)
                    self.assertEqual(key[0].parent.stat().st_mode & 0o777, 0o700)
                    paths = key[0].parent
                    raise ValueError('synthetic failure')
            self.assertFalse(paths.exists())
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_fixture_only_guard_and_private_file_sentinel_archive_inventory_controls(self):
        import hosted_signed_release_fixture as fixture
        with patch.dict(os.environ, {}, clear=True), patch.object(fixture, 'private_command') as keytool:
            with self.assertRaises(SystemExit):
                fixture.main()
            keytool.assert_not_called()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence, cache = root / 'evidence', root / 'cache'
            evidence.mkdir()
            cache.mkdir()
            (evidence / 'public.txt').write_bytes(b'public fixture receipt')
            self.assertEqual(fixture.assert_excluded([evidence, cache], [b'synthetic-private-sentinel']),
                             {'evidenceFiles': 1, 'cacheFiles': 0})
            (evidence / 'public.txt').write_bytes(b'synthetic-private-sentinel')
            with self.assertRaises(ValueError):
                fixture.assert_excluded([evidence, cache], [b'synthetic-private-sentinel'])
            (evidence / 'public.txt').write_bytes(b'public')
            (cache / 'private.jks').write_bytes(b'SYNTHETIC not a key')
            with self.assertRaises(ValueError):
                fixture.assert_excluded([evidence, cache], [])
            (cache / 'private.jks').unlink()
            (evidence / 'bad.zip').write_bytes(archive([('nested/private.pass', b'SYNTHETIC')]))
            with self.assertRaises(ValueError):
                fixture.assert_excluded([evidence, cache], [])
            import zipfile
            with zipfile.ZipFile(evidence / 'bad.zip', 'w', compression=zipfile.ZIP_DEFLATED) as zipped:
                zipped.writestr('ordinary.txt', b'synthetic-private-sentinel')
            with self.assertRaisesRegex(ValueError, 'archive member'):
                fixture.assert_excluded([evidence, cache], [b'synthetic-private-sentinel'])

    def test_hosted_fixture_uses_separate_private_password_files_and_cleans_up_on_sign_failure(self):
        import hosted_signed_release_fixture as fixture
        password = 'synthetic-fixture-password'
        certificate = b'SYNTHETIC public DER, not a certificate'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'VERSION').write_text('0.1.0\n')
            runner_temp = root / 'runner-temp'
            runner_temp.mkdir()
            env = {'GITHUB_ACTIONS': 'true', 'RUNNER_TEMP': str(runner_temp), 'ANDROID_HOME': str(root / 'sdk')}

            def command(cwd, *args):
                if args[:2] == ('git', 'clone'):
                    source = Path(args[-1])
                    source.mkdir()
                    (source / 'VERSION').write_text('0.1.0\n')

            def build_case(source, name, environment, reports):
                capsule = reports / 'bundle'
                capsule.mkdir(parents=True)
                (capsule / 'bundle-manifest.json').write_bytes(b'synthetic accepted capsule')
                return EXPECTED

            def keytool(command):
                self.assertEqual(command[0], 'keytool')
                if '-genkeypair' in command:
                    self.assertEqual(command[command.index('-storetype') + 1], 'PKCS12')
                    for option in ('-storepass:file', '-keypass:file'):
                        self.assertEqual(command[command.index(option) + 1].read_bytes(), password.encode() + b'\n')
                    command[command.index('-keystore') + 1].write_bytes(b'SYNTHETIC not a keystore')
                else:
                    self.assertIn('-exportcert', command)
                    command[command.index('-file') + 1].write_bytes(certificate)

            def sign(*args):
                key, alias, store_pass, key_pass = args[-1]
                self.assertEqual(alias, 'fixture')
                self.assertEqual(store_pass.parent, key.parent)
                self.assertEqual(key_pass.parent, key.parent)
                self.assertEqual(key.parent.parent, runner_temp)
                self.assertEqual(key.parent.stat().st_mode & 0o777, 0o700)
                self.assertNotEqual(store_pass.resolve(), key_pass.resolve())
                for path in (key, store_pass, key_pass):
                    self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                # Each apksigner file: input starts at its own first line, not shared EOF.
                self.assertEqual(store_pass.read_bytes(), password.encode() + b'\n')
                self.assertEqual(key_pass.read_bytes(), store_pass.read_bytes())
                self.assertEqual(args[5], runtime.fingerprint(certificate)['sha256'])
                raise RuntimeError('synthetic stop before real signing')

            with patch.dict(os.environ, env, clear=True), patch.object(fixture, 'ROOT', root), \
                    patch.object(fixture, 'command', side_effect=command) as commands, patch.object(fixture, 'git', return_value=''), \
                    patch.object(fixture, 'build_case', side_effect=build_case), \
                    patch.object(fixture.secrets, 'token_hex', return_value=password), \
                    patch.object(fixture, 'private_command', side_effect=keytool) as private, \
                    patch.object(fixture, 'sign', side_effect=sign) as signer:
                with self.assertRaisesRegex(RuntimeError, 'synthetic stop'):
                    fixture.main()
                signer.assert_called_once()
                self.assertEqual(private.call_count, 2)
                self.assertEqual(commands.call_count, 3)
            self.assertEqual(list(runner_temp.iterdir()), [])
            self.assertEqual((root / 'evidence/signed-release-fixture/certificate.der').read_bytes(), certificate)
            self.assertEqual(fixture.assert_excluded([root / 'evidence'], [password.encode(), b'SYNTHETIC not a keystore']),
                             {'evidenceFiles': 3, 'cacheFiles': 0})

    def test_private_command_output_and_timeout_never_disclose_arguments(self):
        with patch.object(signing.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['secret-value'], 60)):
            with self.assertRaises(ValueError) as caught:
                signing.private_command(['secret-value'])
            self.assertNotIn('secret-value', str(caught.exception))
        with patch.object(signing.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)) as run:
            with self.assertRaises(ValueError):
                signing.private_command(['synthetic'])
            self.assertEqual(run.call_args.kwargs['stdout'], subprocess.DEVNULL)
            self.assertEqual(run.call_args.kwargs['stderr'], subprocess.DEVNULL)

    def test_sign_requires_pinned_unsigned_and_explicit_files_then_preserves_exact_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            unsigned = root / 'unsigned.apk'
            unsigned.write_bytes(b'SYNTHETIC unsigned bytes')
            capsule = root / 'unsigned-bundle'
            capsule.mkdir()
            (capsule / 'bundle-manifest.json').write_bytes(b'synthetic accepted capsule')
            digest = runtime.fingerprint((capsule / 'bundle-manifest.json').read_bytes())['sha256']
            key = [root / n for n in ('synthetic.jks', 'synthetic-store.pass', 'synthetic-key.pass')]
            for p in key:
                p.write_bytes(b'SYNTHETIC NOT REAL KEY MATERIAL')
            signed = root / 'signed.apk'
            final = root / 'final-bundle'
            calls = []
            def sdk(command):
                calls.append(command)
                if 'sign' in command:
                    signed.write_bytes(b'SYNTHETIC signed output, not a signature')
            with patch.object(bundle, 'verify_accepted_unsigned') as accepted, patch.object(signing, 'private_command', side_effect=sdk), \
                    patch.object(bundle, 'final_files', return_value={'bundle-manifest.json': b'synthetic final'}) as bind:
                with self.assertRaisesRegex(ValueError, 'digest'):
                    signing.sign(root, EXPECTED, unsigned, capsule, '0' * 64, CERT, root, root / 'reports', signed, final,
                                 (key[0], 'alias', key[1], key[2]))
                self.assertEqual(calls, [])
                signing.sign(root, EXPECTED, unsigned, capsule, digest, CERT, root, root / 'reports', signed, final,
                             (key[0], 'alias', key[1], key[2]))
                self.assertTrue(accepted.called)
                self.assertEqual(bind.call_count, 2)
                self.assertEqual(bind.call_args.args[5], CERT)
            self.assertEqual(unsigned.read_bytes(), b'SYNTHETIC unsigned bytes')
            self.assertEqual(calls[0][1:], ['-c', '-P', '16', '-v', '4', unsigned])
            self.assertIn('file:' + str(key[1]), calls[1])
            self.assertIn('--v4-signing-enabled', calls[1])
            self.assertFalse(any('genkey' in str(arg) for call in calls for arg in call))


class AssetTests(RuntimeFixture):
    def build_inputs(self):
        return {**EXPECTED, 'channel': 'rc', 'tag': 'v0.1.0-rc.1', 'versionName': '0.1.0-rc.1'}

    def setUp(self):
        super().setUp()
        self.expected = self.build_inputs()
        self.inventory['buildInputs'] = self.expected
        self.receipt['buildInputs'] = self.expected
        self.receipt['source'] = self.expected['source']
        self.signed = self.root / signing.asset_names(self.expected)[0]
        self.signed.write_bytes(archive([*runtime.members(self.apk.read_bytes()).items(),
                                        ('META-INF/CERT.SF', b'SYNTHETIC'), ('META-INF/CERT.RSA', b'NOT A SIGNATURE')]))
        unsigned = self.files()
        self.inspection = {'unsigned': False, 'signerCertificateSha256': CERT}
        files = bundle.render(self.root, self.inventory, self.receipt, stage='final-signed',
                              apk_record=runtime.file_record(self.signed), signed=self.inspection,
                              accepted={'apk': runtime.file_record(self.apk),
                                        'bundleManifestSha256': runtime.fingerprint(unsigned['bundle-manifest.json'])['sha256']})
        self.final = self.root / 'final-bundle'
        bundle.write_bundle(self.final, files, [self.apk, self.signed])
        self.authority = {'repository': 'fixture/repository', 'workflowId': 8, 'controllerSha': 'd' * 40,
                          'runId': 100, 'runAttempt': 2}
        self.assets = signing.package_assets(self.signed, self.final, self.authority)

    def verify(self, assets=None, **kwargs):
        with patch.object(artifact, 'inspect_apk', return_value=self.inspection), \
                patch.object(bundle, 'observe_classes', return_value=sorted(self.classes)):
            return signing.verify_assets(self.root, self.assets if assets is None else assets, self.root,
                                         self.root / 'reports', expected_certificate=CERT, **kwargs)

    def test_deterministic_complete_legal_zip_sbom_final_hash_and_independent_pin(self):
        self.assertEqual(self.assets, signing.package_assets(self.signed, self.final, self.authority))
        record, apk = self.verify(expected_authority=self.authority)
        self.assertEqual(apk.read_bytes(), self.signed.read_bytes())
        zipped = runtime.members(self.assets[signing.asset_names(self.expected)[1]])
        self.assertEqual(zipped, {p.relative_to(self.final).as_posix(): p.read_bytes() for p in self.final.rglob('*') if p.is_file()})
        self.assertIn('legal/license.txt', zipped)
        bom = json.loads(self.assets['runtime.cdx.json'])
        self.assertEqual(bom['metadata']['component']['hashes'][0]['content'], runtime.fingerprint(apk.read_bytes())['sha256'])
        self.assertEqual(len(self.assets), 6)
        self.assertNotIn(self.apk.name, self.assets)
        self.assertEqual(self.assets['SHA256SUMS'].count(b'\n'), 5)
        with self.assertRaises(ValueError):
            self.verify(expected_authority={**self.authority, 'runId': 101})

    def test_asset_omission_extra_checksum_byte_and_complete_legal_tamper_deny(self):
        for mutation in (lambda a: a.pop('THIRD_PARTY_NOTICES.txt'), lambda a: a.update(extra=b'extra'),
                         lambda a: a.update({'runtime.cdx.json': b'changed'}),
                         lambda a: a.update({'SHA256SUMS': b'wrong'})):
            assets = dict(self.assets)
            mutation(assets)
            with self.assertRaises((ValueError, KeyError)):
                self.verify(assets)
        # Even self-consistently rehashed legal substitutions cannot replace the accepted corpus.
        (self.final / 'legal/license.txt').write_bytes(b'changed legal bytes')
        bad = signing.package_assets(self.signed, self.final, self.authority)
        with self.assertRaisesRegex(ValueError, 'legal/SBOM'):
            self.verify(bad)

    def test_payload_drift_and_accepted_unsigned_binding_deny_even_with_fresh_outer_hashes(self):
        contents = runtime.members(self.signed.read_bytes())
        contents['classes.dex'] = b'changed code'
        self.signed.write_bytes(archive(contents.items()))
        manifest_path = self.final / 'bundle-manifest.json'
        manifest = runtime.load(manifest_path)
        manifest['apk'] = runtime.file_record(self.signed)
        manifest_path.write_bytes(runtime.canonical(manifest))
        bad = signing.package_assets(self.signed, self.final, self.authority)
        with self.assertRaisesRegex(ValueError, 'payload'):
            self.verify(bad)


if __name__ == '__main__':
    unittest.main()
