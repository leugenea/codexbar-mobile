"""Canonical non-distributable unsigned release payload; no signer or allocator."""
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import sys
import zipfile

from complete_codeql_checkout import canonical, digest, read_snapshot, require_sha
from release_identity import identity_digest, resolve_identity, validate_identity
from verify_gradle_execution import validate as validate_execution
from verify_manifests import check_apk_dump, check_backup_dump, check_manifest, ROOT, NAMESPACE

SCHEMA_FIELDS = {'schemaVersion', 'stage', 'purpose', 'distributionAuthorized', 'identity',
                 'identitySha256', 'source', 'producer', 'payload', 'verification', 'signedFinal'}
METHODS = ['aapt permissions', 'aapt manifest/resources', 'apksigner unsigned check', 'ZIP signature-material absence']
EVIDENCE_NAMES = ['permissions.txt', 'manifest.txt', 'backup.txt', 'extraction.txt', 'unsigned.txt']


def safe_file(root, relative):
    if (not isinstance(relative, str) or not relative or '\\' in relative
            or PurePosixPath(relative).is_absolute() or any(p in ('', '.', '..') for p in relative.split('/'))):
        raise ValueError('Unsafe payload path')
    path = Path(root) / relative
    if not path.resolve().is_relative_to(Path(root).resolve()):
        raise ValueError('Payload path escapes root')
    if any(p.is_symlink() for p in [path, *path.parents] if p != Path(root).parent):
        raise ValueError('Payload symlink forbidden')
    return path


def local_identity(identity, root):
    validate_identity(identity)
    if identity['channel'] == 'dev':
        actual = resolve_identity(root, 'dev')
    else:
        actual = resolve_identity(root, identity['channel'], identity['tag'], identity['sourceSha'],
                                  identity['versionCode'], {'fixturePreviousCode': 0})
    if actual != identity:
        raise ValueError('Identity does not match acquired actual local source/refs')


def unsigned_container(path):
    """Check ZIP CRCs and absence of v1/v2/v3 material, not signature validity."""
    data = Path(path).read_bytes()
    if len(data) < 22 or len(data) > 256 * 1024 * 1024:
        raise ValueError('Invalid bounded APK container size')
    eocd = data.rfind(b'PK\x05\x06', max(0, len(data) - 65557))
    if eocd < 0 or eocd + 22 > len(data):
        raise ValueError('Missing APK ZIP directory')
    _, disk, directory_disk, entries_disk, entries, directory_size, directory, comment = struct.unpack_from('<4s4H2IH', data, eocd)
    if disk or directory_disk or entries_disk != entries or eocd + 22 + comment != len(data) or directory + directory_size != eocd:
        raise ValueError('Unsupported APK ZIP directory')
    if directory >= 24 and data[directory - 16:directory] == b'APK Sig Block 42':
        raise ValueError('APK signing block present')
    if Path(str(path) + '.idsig').exists():
        raise ValueError('APK v4 signature sidecar present')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names) or archive.testzip() is not None:
            raise ValueError('Corrupt/duplicate APK ZIP entries')
        if not {'AndroidManifest.xml', 'classes.dex', 'resources.arsc'} <= set(names):
            raise ValueError('Missing actual Android APK contents')
        if any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', name, re.I) for name in names):
            raise ValueError('JAR signature material present')
    return dict(size=len(data), sha256=digest(data))


def unsigned_diagnostic(code, stdout, stderr):
    # A random SDK error is not proof of unsigned identity. AGP's unsigned APK
    # lacks a v1 manifest; v2/v3/JAR material absence is checked independently.
    lines = [line for line in (stdout + '\n' + stderr).splitlines() if line]
    if type(code) is not int or code != 1 or sorted(lines) != sorted(['DOES NOT VERIFY', 'ERROR: Missing META-INF/MANIFEST.MF']):
        raise ValueError('Unknown/signed/malformed apksigner result, not unsigned proof')


def producer(environment, observation):
    required = ('GITHUB_REPOSITORY', 'GITHUB_EVENT_NAME', 'GITHUB_WORKFLOW_REF', 'GITHUB_WORKFLOW_SHA',
                'GITHUB_JOB', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_SHA')
    if environment.get('GITHUB_ACTIONS') != 'true' or any(not environment.get(k) for k in required):
        raise ValueError('Actual hosted producer metadata required')
    if environment['GITHUB_REPOSITORY'] != 'leugenea/codexbar-mobile' or environment['GITHUB_JOB'] != 'build':
        raise ValueError('Unknown unsigned fixture producer')
    workflow = environment['GITHUB_WORKFLOW_REF']
    if not workflow.startswith('leugenea/codexbar-mobile/.github/workflows/android.yml@'):
        raise ValueError('Unknown producer workflow ref')
    require_sha(environment['GITHUB_WORKFLOW_SHA']); require_sha(environment['GITHUB_SHA'])
    for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        if not re.fullmatch(r'[1-9][0-9]*', environment[key]):
            raise ValueError('Invalid actual producer run/attempt')
    root = Path(observation['state']['root'])
    log = root / ('evidence/strict-release.log' if observation['mode'] == 'fixture' else 'evidence/strict.log')
    validate_execution(log.read_text(), 'release' if observation['mode'] == 'fixture' else 'build')
    event = json.loads(Path(environment['GITHUB_EVENT_PATH']).read_text())
    source_head = event.get('pull_request', {}).get('head', {}).get('sha', environment['GITHUB_SHA'])
    require_sha(source_head)
    return dict(sourceHeadSha=source_head, strictExecutionLogSha256=digest(log.read_bytes()),
                toolchainEvidenceSha256=digest((root/'evidence/resolved-toolchain.json').read_bytes()),
                repository=environment['GITHUB_REPOSITORY'], event=environment['GITHUB_EVENT_NAME'],
                workflowRef=workflow, workflowSha=environment['GITHUB_WORKFLOW_SHA'],
                workflowPath='.github/workflows/android.yml',
                workflowFileSha256=next(row['sha256'] for row in observation['state']['trackedFiles'] if row['path'] == '.github/workflows/android.yml'),
                job=environment['GITHUB_JOB'], runId=environment['GITHUB_RUN_ID'], runAttempt=environment['GITHUB_RUN_ATTEMPT'],
                checkoutSha=environment['GITHUB_SHA'], artifactName='android-build-' + environment['GITHUB_RUN_ID'] + '-' + environment['GITHUB_RUN_ATTEMPT'],
                dependencyVerification='strict', tasks=['assembleRelease', 'lintRelease', 'writeBuildIdentity'])


def validate_inventory(inventory, root, identity):
    validate_identity(identity)
    if (set(inventory) != SCHEMA_FIELDS or type(inventory['schemaVersion']) is not int or inventory['schemaVersion'] != 1 or inventory['stage'] != 'unsigned'
            or inventory['distributionAuthorized'] is not False or inventory['signedFinal'] is not None
            or inventory['identity'] != identity or inventory['identitySha256'] != identity_digest(identity)
            or inventory['purpose'] != identity['purpose']):
        raise ValueError('Inventory schema/stage/authority/source identity mismatch')
    payload = inventory['payload']
    if set(payload) != {'path', 'intendedFinalName', 'size', 'sha256', 'signatureState', 'applicationId', 'variant'}:
        raise ValueError('Inventory payload schema mismatch')
    expected_name = f"codexbar-mobile-{identity['versionName']}-release-c{identity['versionCode']}-g{identity['shortSha']}-unsigned.apk"
    if (payload['path'] != expected_name or payload['intendedFinalName'] != 'codexbar-mobile-' + identity['versionName'] + '.apk'
            or payload['signatureState'] != 'unsigned' or payload['applicationId'] != NAMESPACE or payload['variant'] != 'release'
            or type(payload['size']) is not int or payload['size'] <= 0):
        raise ValueError('Inventory payload name/variant/unsigned mismatch')
    actual = unsigned_container(safe_file(root, payload['path']))
    if any(payload[k] != actual[k] for k in ('size', 'sha256')):
        raise ValueError('Unsigned payload size/hash changed')
    verification = inventory['verification']
    if set(verification) != {'methods', 'evidence', 'manifestPolicy', 'identity', 'unsigned'} or verification['methods'] != METHODS:
        raise ValueError('Unknown/omitted verification method')
    if any(verification[k] != 'passed' for k in ('manifestPolicy', 'identity', 'unsigned')):
        raise ValueError('Missing actual unsigned verification')
    evidence = verification['evidence']
    if [item.get('path') for item in evidence] != EVIDENCE_NAMES or any(set(item) != {'path', 'sha256'} for item in evidence):
        raise ValueError('Duplicate/missing inspection evidence')
    texts = {}
    for item in evidence:
        path = safe_file(root, item['path'])
        if digest(path.read_bytes()) != item['sha256']:
            raise ValueError('Inspection evidence changed')
        texts[item['path']] = path.read_text()
    check_apk_dump(texts['permissions.txt'], texts['manifest.txt'], variant='release', identity=identity)
    check_backup_dump(texts['backup.txt']); check_backup_dump(texts['extraction.txt'], True)
    diagnostic = json.loads(texts['unsigned.txt'])
    if set(diagnostic) != {'exit', 'stdout', 'stderr'}:
        raise ValueError('Malformed unsigned diagnostic')
    unsigned_diagnostic(diagnostic['exit'], diagnostic['stdout'], diagnostic['stderr'])
    return inventory


def inspect(apk, sdk, output):
    tools = Path(sdk) / 'build-tools/36.0.0'
    if not tools.is_dir():
        raise ValueError('Pinned SDK Build Tools required')
    commands = [('permissions.txt', [str(tools/'aapt'), 'dump', 'permissions', str(apk)]),
                ('manifest.txt', [str(tools/'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml']),
                ('backup.txt', [str(tools/'aapt'), 'dump', 'xmltree', str(apk), 'res/xml/backup_rules.xml']),
                ('extraction.txt', [str(tools/'aapt'), 'dump', 'xmltree', str(apk), 'res/xml/data_extraction_rules.xml'])]
    for name, argv in commands:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=60, check=True)
        (output/name).write_text(result.stdout)
    result = subprocess.run([str(tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)],
                            capture_output=True, text=True, timeout=60, check=False)
    unsigned_diagnostic(result.returncode, result.stdout, result.stderr)
    (output/'unsigned.txt').write_bytes(canonical(dict(exit=result.returncode, stdout=result.stdout, stderr=result.stderr)) + b'\n')


def create(root, identity, apk, output, sdk, environment=None):
    environment = dict(os.environ if environment is None else environment)
    local_identity(identity, root)
    git, observation = read_snapshot(root, environment)
    state = observation['state']
    # Actual manifest and output metadata are independent observations.
    check_manifest(Path(root)/'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml', variant='release', identity=identity)
    metadata = json.loads((Path(apk).parent/'output-metadata.json').read_text())
    elements = metadata.get('elements', [])
    if (metadata.get('applicationId') != NAMESPACE or metadata.get('variantName') != 'release' or len(elements) != 1
            or elements[0].get('filters') != [] or elements[0].get('outputFile') != Path(apk).name
            or elements[0].get('versionCode') != identity['versionCode'] or elements[0].get('versionName') != identity['versionName']):
        raise ValueError('Release output-metadata does not identify one exact APK')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    name = f"codexbar-mobile-{identity['versionName']}-release-c{identity['versionCode']}-g{identity['shortSha']}-unsigned.apk"
    target = safe_file(output, name)
    original = unsigned_container(apk)
    inspect(Path(apk).resolve(), sdk, output)
    if unsigned_container(apk) != original:
        raise ValueError('APK changed during SDK inspection')
    shutil.copyfile(apk, target)
    source = source_record(observation, identity)
    inventory = dict(schemaVersion=1, stage='unsigned', purpose=identity['purpose'], distributionAuthorized=False,
                     identity=identity, identitySha256=identity_digest(identity), source=source, producer=producer(environment, observation),
                     payload=dict(path=name, intendedFinalName='codexbar-mobile-' + identity['versionName'] + '.apk', **original,
                                  signatureState='unsigned', applicationId=NAMESPACE, variant='release'),
                     verification=dict(methods=METHODS, manifestPolicy='passed', identity='passed', unsigned='passed',
                                       evidence=[dict(path=n, sha256=digest((output/n).read_bytes())) for n in EVIDENCE_NAMES]), signedFinal=None)
    validate_inventory(inventory, output, identity)
    (output/'inventory.json').write_bytes(canonical(inventory) + b'\n')
    verify(root, output/'inventory.json', identity, environment)
    return inventory


def source_record(observation, identity):
    state = observation['state']
    kind = 'isolated-fixture' if observation['mode'] == 'fixture' else ('pr-merge' if state['head'] != observation['context']['sourceHeadSha'] else 'direct')
    return dict(repository='leugenea/codexbar-mobile', checkoutSha=state['head'], sourceTree=state['tree'],
                  sourceHeadSha=observation['context'].get('sourceHeadSha', state['head']), checkoutKind=kind,
                  ref=identity['tag'] if kind == 'isolated-fixture' else observation['context']['ref'],
                  tagMapSha256=observation['tagMapSha256'], trackedFiles=state['trackedFiles'],
                  trackedInventorySha256=digest(canonical(state['trackedFiles'])))


def verify(repo, path, identity, environment=None):
    local_identity(identity, repo)
    _, observation = read_snapshot(repo, environment)
    inventory = validate_inventory(json.loads(Path(path).read_text()), Path(path).parent, identity)
    state = observation['state']
    source = inventory['source']
    if (source != source_record(observation, identity)
            or inventory['producer'] != producer(dict(os.environ if environment is None else environment), observation)):
        raise ValueError('Inventory source/producer differs from actual acquired job/root')
    return inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('create', 'verify'))
    parser.add_argument('--repo', type=Path, default=ROOT)
    parser.add_argument('--identity', type=Path, required=True)
    parser.add_argument('--apk', type=Path); parser.add_argument('--output', type=Path); parser.add_argument('--inventory', type=Path)
    args = parser.parse_args()
    try:
        identity = json.loads(args.identity.read_text())
        if args.mode == 'create':
            if not args.apk or not args.output:
                parser.error('create requires --apk and --output')
            create(args.repo, identity, args.apk, args.output, os.environ['ANDROID_HOME'])
        else:
            if not args.inventory:
                parser.error('verify requires --inventory')
            verify(args.repo, args.inventory, identity)
        print('Verified unsigned release fixture; distribution is not authorized')
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, zipfile.BadZipFile):
        print('Unsigned release identity/policy/inventory rejected', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
