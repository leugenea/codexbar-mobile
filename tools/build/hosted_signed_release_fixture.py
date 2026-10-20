"""Hosted-only disposable signing fixture: one RC build, real SDK, mock publication/download."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import tempfile
import zipfile

from hosted_release_fixtures import ROOT, build_case, command, reject
from release_mock import ReleaseAPI
from release_policy import configuration
from release_publication import GitHub, publish, read_assets
from release_runtime import canonical, fingerprint
from release_signing import asset_names, package_assets, private_command, sign, verify_assets
from version_identity import git


def assert_excluded(roots, sentinels):
    forbidden = {'.jks', '.p12', '.pfx', '.pem', '.key', '.pass'}
    inventory = {'evidenceFiles': 0, 'cacheFiles': 0}
    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob('*'):
            if not path.is_file():
                continue
            inventory['evidenceFiles' if root.name == 'evidence' else 'cacheFiles'] += 1
            if path.suffix.lower() in forbidden:
                raise ValueError('Private-key/password member in intended evidence/cache inventory')
            # Large Gradle caches never receive fixture secrets; enumerate paths, scan evidence bytes.
            if root.name == 'evidence':
                data = path.read_bytes()
                if any(secret in data for secret in sentinels):
                    raise ValueError('Fixture secret sentinel in intended evidence')
                if zipfile.is_zipfile(path):
                    with zipfile.ZipFile(path) as archive:
                        if any(Path(n).suffix.lower() in forbidden for n in archive.namelist()):
                            raise ValueError('Private-key/password archive member in evidence')
                        for name in archive.namelist():
                            if any(secret in archive.read(name) for secret in sentinels):
                                raise ValueError('Fixture secret sentinel in evidence archive member')
    return inventory


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not os.environ.get('RUNNER_TEMP'):
        raise SystemExit('Hosted runner only; fixture key generation/signing/build prohibited locally')
    reports = ROOT / 'evidence/signed-release-fixture'
    reports.mkdir(parents=True)
    sdk = Path(os.environ['ANDROID_HOME'])
    env = {k: v for k, v in os.environ.items() if not k.startswith('RELEASE_')}
    with tempfile.TemporaryDirectory(prefix='signed-source-', dir=os.environ['RUNNER_TEMP']) as directory:
        root = Path(directory) / 'source'
        command(Path(directory), 'git', 'clone', '--no-hardlinks', str(ROOT), str(root))
        command(root, 'git', 'checkout', '--detach', git(ROOT, 'rev-parse', 'HEAD'))
        tag = 'v' + (root / 'VERSION').read_text().strip() + '-rc.1'
        if git(root, 'tag', '--list', tag):
            command(root, 'git', 'tag', '-d', tag)
        command(root, 'git', 'tag', tag)
        env.update(RELEASE_CHANNEL='rc', RELEASE_TAG=tag, RELEASE_VERSION_CODE='2', RELEASE_PREVIOUS_VERSION_CODE='1')
        expected = build_case(root, 'signed-rc', env, reports / 'unsigned')
        unsigned = reports / 'unsigned'
        apk_name, _ = asset_names(expected)
        unsigned_apk = unsigned / apk_name.replace('.apk', '-unsigned.apk')
        unsigned_digest = fingerprint((unsigned / 'bundle/bundle-manifest.json').read_bytes())['sha256']
        password = secrets.token_hex(32).encode()
        with tempfile.TemporaryDirectory(prefix='fixture-private-', dir=os.environ['RUNNER_TEMP']) as private_dir:
            private = Path(private_dir)
            private.chmod(0o700)
            key = private / 'fixture.jks'
            store_pass = private / 'store.pass'
            key_pass = private / 'key.pass'
            # apksigner consumes successive lines when both password options share a file.
            # PKCS12 uses the same value, but each option needs its own single-line file.
            for passfile in (store_pass, key_pass):
                passfile.write_bytes(password + b'\n')
                passfile.chmod(0o600)
            # Fixture generation is deliberately not reachable from release_signing.sign().
            private_command(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keyalg', 'RSA', '-keysize', '2048',
                             '-sigalg', 'SHA256withRSA', '-validity', '2', '-alias', 'fixture', '-dname', 'CN=Hosted Fixture',
                             '-keystore', key, '-storepass:file', store_pass, '-keypass:file', key_pass])
            key.chmod(0o600)
            certificate = private / 'public.der'
            private_command(['keytool', '-exportcert', '-keystore', key, '-alias', 'fixture',
                             '-storepass:file', store_pass, '-file', certificate])
            pin = hashlib.sha256(certificate.read_bytes()).hexdigest()  # Independent DER pin, not apksigner output.
            config = configuration(json.dumps({'schema': 2, 'certificateSha256': pin,
                'signingEnvironment': 'fixture-signing', 'publicationEnvironment': 'fixture-publication',
                'rcEnabled': True, 'stableEnabled': False}), 'rc')
            pin = config['certificateSha256']
            (reports / 'certificate.der').write_bytes(certificate.read_bytes())
            (reports / 'certificate.sha256').write_text(pin + '\n')
            key_bytes = key.read_bytes()
            signed = reports / apk_name
            sign(ROOT, expected, unsigned_apk, unsigned / 'bundle', unsigned_digest, pin, sdk,
                 reports / 'signing-inspection', signed, reports / 'final-bundle', (key, 'fixture', store_pass, key_pass))
        if private.exists():
            raise ValueError('Fixture private directory still exists')
        assets = package_assets(signed, reports / 'final-bundle', {
            'repository': os.environ['GITHUB_REPOSITORY'], 'workflowId': 1,
            'controllerSha': git(ROOT, 'rev-parse', 'HEAD'), 'runId': int(env['GITHUB_RUN_ID']),
            'runAttempt': int(env['GITHUB_RUN_ATTEMPT'])})
        verify_assets(ROOT, assets, sdk, reports / 'signed-verification', expected_certificate=pin)
        # Actual SDK negatives; never a fake successful signature or weakened production inspection.
        negative = [reject('wrong-certificate', lambda: verify_assets(ROOT, assets, sdk, reports / 'wrong-cert',
                                                                     expected_certificate='0' * 64))]
        from release_artifact import inspect_apk
        negative.append(reject('actual-sdk-wrong-signer', lambda: inspect_apk(signed, expected, sdk,
                              reports / 'wrong-sdk-signer', expected_signer='0' * 64)))
        negative.append(reject('actual-sdk-wrong-version', lambda: inspect_apk(signed,
                              {**expected, 'versionCode': 3}, sdk, reports / 'wrong-sdk-version', expected_signer=pin)))
        model = ReleaseAPI()
        with model.localhost() as transport:
            api = GitHub('fixture/repository', 'fixture-not-a-credential', transport)
            release_id = publish(api, expected, assets, lambda: None)
            downloaded = read_assets(api, release_id, assets)
        destination = reports / 'downloaded'
        destination.mkdir()
        for name, data in downloaded.items():
            (destination / name).write_bytes(data)
        manifest, downloaded_apk = verify_assets(ROOT, downloaded, sdk, reports / 'download-inspection', expected_certificate=pin)
        if downloaded_apk.read_bytes() != signed.read_bytes():
            raise ValueError('Mock-downloaded signed bytes differ')
        (reports / 'fixture.json').write_bytes(canonical({'kind': 'fixture-only-not-production', 'privateFilesRemoved': True,
            'certificateSha256': pin, 'manifestSha256': fingerprint(assets['release-manifest.json'])['sha256'],
            'buildInputs': expected, 'negativeControls': negative, 'mockReleaseId': release_id,
            'mockCalls': [{'method': method, 'path': path} for method, path, _ in model.calls]}))
        scanned = assert_excluded([ROOT / 'evidence', Path(os.environ['GRADLE_USER_HOME'])], [password, key_bytes])
        (reports / 'secret-exclusion.json').write_bytes(canonical({**scanned, 'privateDirectoryRemoved': True,
            'evidenceSentinelsAbsent': True, 'privateFileTypesAbsent': True,
            'scope': 'Evidence bytes/archive members plus evidence/cache file inventories; not forensic erasure'}))
    print('Hosted disposable signed RC / independent SDK inspection / mock download passed; no production release')


if __name__ == '__main__':
    main()
