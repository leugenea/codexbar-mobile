"""Existing-key SDK signing and deterministic, exact-byte release assets. No key generation."""
import base64
from contextlib import contextmanager
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

import release_artifact as artifact
import release_bundle as bundle
from historical_apk_contract import check_binding
from release_runtime import canonical, file_record, fingerprint, load, members, payload, safe_name

from version_identity import official_inputs

SECRETS = ('ANDROID_RELEASE_KEYSTORE_BASE64', 'ANDROID_RELEASE_KEY_ALIAS',
           'ANDROID_RELEASE_STORE_PASSWORD', 'ANDROID_RELEASE_KEY_PASSWORD')


def private_command(command):
    # Key tools can echo secret-bearing diagnostics. Never retain their output or exception command.
    try:
        result = subprocess.run([str(p) for p in command], stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        if result.returncode:
            raise ValueError('Private signing command failed (diagnostics suppressed)')
    except subprocess.SubprocessError:
        raise ValueError('Private signing command timed out (diagnostics suppressed)') from None


@contextmanager
def materialize(env, runner_temp):
    values = [env.get(k, '') for k in SECRETS]
    if any(not isinstance(v, str) or not v or '\n' in v or '\r' in v for v in values):
        raise ValueError('All four explicit signing secrets are required as single-line values')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', values[1]):
        raise ValueError('Signing alias must be an explicit safe identifier')
    try:
        key = base64.b64decode(values[0], validate=True)
    except ValueError:
        raise ValueError('Invalid keystore encoding') from None
    if not key:
        raise ValueError('Empty keystore')
    with tempfile.TemporaryDirectory(prefix='release-private-', dir=runner_temp) as directory:
        private = Path(directory)
        private.chmod(0o700)
        paths = [private / name for name in ('identity.jks', 'store.pass', 'key.pass')]
        for path, data in zip(paths, (key, values[2].encode() + b'\n', values[3].encode() + b'\n')):
            with path.open('xb') as stream:
                os.chmod(path, 0o600)
                stream.write(data)
        try:
            yield paths[0], values[1], paths[1], paths[2]
        finally:
            for path in paths:
                path.unlink(missing_ok=True)


def sign(root, expected, unsigned_apk, unsigned_bundle, unsigned_digest, certificate,
         sdk, reports, signed_apk, final_bundle, key):
    if fingerprint((unsigned_bundle / 'bundle-manifest.json').read_bytes())['sha256'] != unsigned_digest:
        raise ValueError('Accepted unsigned capsule digest differs')
    artifact.signer_certificate('Signer #1 certificate SHA-256 digest: ' + certificate, certificate)
    if signed_apk.exists() or signed_apk.resolve() == unsigned_apk.resolve():
        raise ValueError('Signing output must be a new distinct APK')
    # Inspect source-bound accepted bytes BEFORE any key-tool invocation.
    bundle.verify_accepted_unsigned(root, expected, unsigned_apk, unsigned_bundle, sdk, reports / 'pre-sign')
    before = file_record(unsigned_apk)
    tools = sdk / 'build-tools/36.0.0'
    private_command([tools / 'zipalign', '-c', '-P', '16', '-v', '4', unsigned_apk])
    keystore, alias, store_pass, key_pass = key
    if not all(p.is_file() and not p.is_symlink() for p in (keystore, store_pass, key_pass)) or not alias:
        raise ValueError('Explicit existing key/password files and alias required')
    private_command([tools / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', alias,
                     '--ks-pass', 'file:' + str(store_pass), '--key-pass', 'file:' + str(key_pass),
                     '--v4-signing-enabled', 'false', '--out', signed_apk, unsigned_apk])
    if file_record(unsigned_apk) != before:
        raise ValueError('Signing changed accepted unsigned bytes')
    files = bundle.final_files(root, expected, unsigned_apk, unsigned_bundle, signed_apk, certificate, sdk, reports)
    bundle.write_bundle(final_bundle, files, [unsigned_apk, unsigned_bundle, signed_apk, reports])
    bundle.verify_files(final_bundle, bundle.final_files(root, expected, unsigned_apk, unsigned_bundle,
                                                       signed_apk, certificate, sdk, reports / 'reverify'))


def zip_bytes(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(files.items()):
            safe_name(name)
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return stream.getvalue()


def asset_names(expected):
    prefix = f'codexbar-mobile-{expected["versionName"]}-{expected["versionCode"]}'
    return prefix + '.apk', prefix + '-bundle.zip'


def package_assets(signed_apk, final_bundle, authority):
    manifest = load(final_bundle / 'bundle-manifest.json')
    if manifest['stage'] != 'final-signed' or manifest['apk'] != file_record(signed_apk):
        raise ValueError('Final capsule/APK identity differs')
    apk_name, zip_name = asset_names(manifest['buildInputs'])
    if signed_apk.name != apk_name:
        raise ValueError('Noncanonical signed APK name')
    files = {p.relative_to(final_bundle).as_posix(): p.read_bytes() for p in final_bundle.rglob('*') if p.is_file()}
    bundle.verify_files(final_bundle, files)  # Reject symlinks before packaging.
    assets = {apk_name: signed_apk.read_bytes(), zip_name: zip_bytes(files),
              'runtime.cdx.json': files['runtime.cdx.json'],
              'THIRD_PARTY_NOTICES.txt': files['THIRD_PARTY_NOTICES.txt']}
    record = {'schema': 1, 'kind': 'guarded-android-release', 'buildInputs': manifest['buildInputs'],
              'authority': authority, 'certificateSha256': manifest['signedInspection']['signerCertificateSha256'],
              'bundleManifestSha256': fingerprint(files['bundle-manifest.json'])['sha256'],
              'assets': {name: fingerprint(data) for name, data in sorted(assets.items())}}
    assets['release-manifest.json'] = canonical(record)
    assets['SHA256SUMS'] = checksums(assets)
    return assets


def checksums(assets):
    return ''.join(f'{fingerprint(data)["sha256"]}  {name}\n' for name, data in sorted(assets.items())
                   if name != 'SHA256SUMS').encode()


def verify_assets(root, assets, sdk, reports, *, expected_certificate=None, expected_authority=None,
                  expected_binding=None, apk_contract=None):
    """Re-derive delivered legal/SBOM bytes and inspect the downloaded signed APK; no rebuild."""
    record = json.loads(assets['release-manifest.json'], object_pairs_hook=_unique)
    if (set(record) != {'schema', 'kind', 'buildInputs', 'authority', 'certificateSha256',
                       'bundleManifestSha256', 'assets'} or type(record['schema']) is not int
            or record['schema'] != 1 or record['kind'] != 'guarded-android-release'):
        raise ValueError('Unknown release manifest')
    expected = record['buildInputs']
    check_binding(expected, expected_binding, apk_contract)
    if expected['channel'] not in ('rc', 'stable') or expected['versionName'] != expected['tag'][1:]:
        raise ValueError('Distributable assets require official RC/stable identity')
    official_inputs(expected['target'], expected['channel'], {'RELEASE_TAG': expected['tag'],
                    'RELEASE_VERSION_CODE': str(expected['versionCode']), 'RELEASE_PREVIOUS_VERSION_CODE': '0'})
    authority = record['authority']
    if (set(authority) != {'repository', 'workflowId', 'controllerSha', 'runId', 'runAttempt'}
            or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', authority['repository'])
            or not re.fullmatch(r'[0-9a-f]{40}', authority['controllerSha'])
            or any(type(authority[k]) is not int or authority[k] < 1 for k in ('workflowId', 'runId', 'runAttempt'))):
        raise ValueError('Invalid dedicated-workflow authority')
    apk_name, zip_name = asset_names(expected)
    if set(assets) != {apk_name, zip_name, 'runtime.cdx.json', 'THIRD_PARTY_NOTICES.txt', 'release-manifest.json', 'SHA256SUMS'}:
        raise ValueError('Release asset allowlist differs')
    if (assets['SHA256SUMS'] != checksums(assets) or canonical(record['assets']) != canonical({
            name: fingerprint(assets[name]) for name in (apk_name, zip_name, 'runtime.cdx.json', 'THIRD_PARTY_NOTICES.txt')})):
        raise ValueError('Release checksums/inventory differ')
    if expected_certificate is not None and record['certificateSha256'] != expected_certificate:
        raise ValueError('Release certificate pin differs')
    if expected_authority is not None and record['authority'] != expected_authority:
        raise ValueError('Release origin differs')
    files = members(assets[zip_name])
    manifest = json.loads(files['bundle-manifest.json'], object_pairs_hook=_unique)
    if fingerprint(files['bundle-manifest.json'])['sha256'] != record['bundleManifestSha256']:
        raise ValueError('Final bundle digest differs')
    reports.mkdir(parents=True, exist_ok=True)
    apk = reports / apk_name
    apk.write_bytes(assets[apk_name])
    inspection = artifact.inspect_apk(apk, expected, sdk, reports / 'inspection',
                                      expected_signer=record['certificateSha256'], apk_contract=apk_contract)
    inventory = json.loads(files['runtime-inventory.json'], object_pairs_hook=_unique)
    receipt = json.loads(files['artifact-manifest.json'], object_pairs_hook=_unique)
    if inventory['buildInputs'] != expected or manifest['buildInputs'] != expected:
        raise ValueError('Delivered capsule source differs')
    # Independent observation also covers the retained unsigned payload inventory.
    actual = payload(apk)
    if actual != {r['path']: {k: r[k] for k in ('size', 'sha256')} for r in inventory['members']}:
        raise ValueError('Downloaded signed payload differs from accepted inventory')
    if bundle.observe_classes(apk, sdk, reports) != sorted(r['name'] for r in inventory['classes']):
        raise ValueError('Downloaded defined classes differ')
    unsigned = bundle.render(root, inventory, receipt)
    accepted = {'apk': inventory['apk'], 'bundleManifestSha256': fingerprint(unsigned['bundle-manifest.json'])['sha256']}
    if manifest['acceptedUnsigned'] != accepted:
        raise ValueError('Delivered accepted unsigned capsule binding differs')
    rendered = bundle.render(root, inventory, receipt, stage='final-signed', apk_record=file_record(apk),
                             signed=inspection, accepted=accepted)
    if files != rendered or any(assets[n] != files[n] for n in ('runtime.cdx.json', 'THIRD_PARTY_NOTICES.txt')):
        raise ValueError('Delivered complete legal/SBOM capsule differs')
    return record, apk


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result
