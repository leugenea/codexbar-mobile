"""Inspect actual unsigned release bytes against independent source/build inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

from verify_manifests import check_apk_dump, xmltree_elements, MAIN_ACTIVITY
from version_identity import resolve, git


def run_tool(command):
    result = subprocess.run([str(part) for part in command], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    return result.returncode, result.stdout



def dump_apk(tools, apk, label, arguments, reports):
    status, output = run_tool([tools / 'aapt', 'dump', arguments[0], apk, *arguments[1:]])
    (reports / (label + '.txt')).write_text(output)
    if status:
        raise ValueError(f'aapt {label} failed with exit {status}; see inspection report')
    return output


def backup_exclusions(tree, roots):
    elements = re.findall(r'(?m)^\s*E: ([\w-]+) \(line=\d+\)', tree)
    expected = [roots[0]]
    for root in roots[1:]:
        expected += [root] + ['exclude'] * 9
    if len(roots) == 1:
        expected += ['exclude'] * 9
    if elements != expected:
        raise ValueError('APK backup rules must exclude all data in each required section')
    domains = {'root', 'file', 'database', 'sharedpref', 'external', 'device_root',
               'device_file', 'device_database', 'device_sharedpref'}
    exclusions = xmltree_elements(tree, 'exclude')
    for start in range(0, len(exclusions), 9):
        actual = []
        for block in exclusions[start:start + 9]:
            domain = re.findall(r'(?m)^\s*A: domain="([^"]+)"', block)
            path = re.findall(r'(?m)^\s*A: path="([^"]+)"', block)
            if len(domain) != 1 or path != ['.']:
                raise ValueError('APK backup exclusion must cover an entire named domain')
            actual += domain
        if set(actual) != domains:
            raise ValueError('APK backup exclusion domains differ from source policy')


def inspect_backup(apk, tools, xmltree, reports):
    resources = dump_apk(tools, apk, 'resources', ['resources'], reports)
    app = xmltree_elements(xmltree, 'application')[0]
    for attribute_name, filename, roots in (
        ('fullBackupContent', 'backup_rules', ('full-backup-content',)),
        ('dataExtractionRules', 'data_extraction_rules', ('data-extraction-rules', 'cloud-backup', 'device-transfer')),
    ):
        reference = attribute(app, attribute_name, r'(@0x[0-9a-f]+)')[1:]
        ids = set(re.findall(r'\bresource (0x[0-9a-f]+) [^\s:]+:xml/' + filename + r'(?=[:\s])', resources))
        if ids != {reference}:
            raise ValueError('APK backup reference differs from the intended source resource')
        tree = dump_apk(tools, apk, filename, ['xmltree', 'res/xml/' + filename + '.xml'], reports)
        backup_exclusions(tree, roots)

def apk_bytes(apk):
    data = apk.read_bytes()
    # Android signing blocks sit immediately before the ZIP central directory.
    with zipfile.ZipFile(apk) as archive:
        if data[archive.start_dir - 16:archive.start_dir] == b'APK Sig Block 42':
            raise ValueError('Release APK contains an APK signing block')
        if any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', name, re.I) for name in archive.namelist()):
            raise ValueError('Release APK contains a JAR signature')
    return {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def attribute(block, name, pattern):
    values = re.findall(r'(?m)^\s*A: android:' + re.escape(name) + r'\([^\n]*?\)=' + pattern + r'[ \t]*$', block)
    if len(values) != 1:
        raise ValueError(f'APK must declare one valid {name}')
    return values[0]


def application_policy(xmltree):
    applications = xmltree_elements(xmltree, 'application')
    if len(applications) != 1:
        raise ValueError('APK must have one application')
    app = applications[0]
    attribute(app, 'allowBackup', r'(\(type 0x12\)0x0)')
    debuggable = re.findall(r'(?m)^\s*A: android:debuggable\([^\n]*', app)
    if debuggable and (len(debuggable) != 1 or not re.search(r'=\(type 0x12\)0x0\s*$', debuggable[0])):
        raise ValueError('Production APK must not be debuggable')
    for name in ('fullBackupContent', 'dataExtractionRules'):
        attribute(app, name, r'(@0x[0-9a-f]+)')
    activities = xmltree_elements(xmltree, 'activity')
    launchers = [block for block in activities if f'="{MAIN_ACTIVITY}"' in block]
    if len(launchers) != 1:
        raise ValueError('APK launcher class must preserve the namespace')
    attribute(launchers[0], 'exported', r'(\(type 0x12\)0xffffffff)')


def inspect_apk(apk, expected, sdk, report_dir):
    report_dir.mkdir(parents=True, exist_ok=True)
    tools = sdk / 'build-tools/36.0.0'
    results = {}
    for label, arguments in (('permissions', ['permissions']),
                             ('manifest', ['xmltree', 'AndroidManifest.xml']),
                             ('badging', ['badging'])):
        results[label] = dump_apk(tools, apk, label, arguments, report_dir)
    permissions = check_apk_dump(results['permissions'], results['manifest'], expected['releasePackage'])
    package = re.findall(r"(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", results['badging'])
    identity = (expected['releasePackage'], str(expected['versionCode']), expected['versionName'])
    if package != [identity]:
        raise ValueError('APK package/version differs from independent build inputs')
    launch = re.findall(r"(?m)^launchable-activity: name='([^']+)'", results['badging'])
    if launch != [MAIN_ACTIVITY]:
        raise ValueError('APK must expose exactly the production MAIN/LAUNCHER activity')
    application_policy(results['manifest'])
    status, signature = run_tool([tools / 'apksigner', 'verify', '--verbose', apk])
    (report_dir / 'signature.txt').write_text(signature)
    # A generic tool failure is not unsigned evidence. Both absence of a signing
    # block/JAR signature and apksigner's explicit missing-META-INF refusal are needed.
    if status == 0 or not re.search(r'Missing META-INF/MANIFEST.MF|No JAR signatures', signature):
        raise ValueError('apksigner did not independently confirm an unsigned APK')
    inspect_backup(apk, tools, results['manifest'], report_dir)
    return {'backupExclusionsVerified': True, 'package': package[0][0], 'versionCode': int(package[0][1]), 'versionName': package[0][2],
            'requestedPermissions': permissions, 'cleartextPermitted': False,
            'allowBackup': False, 'debuggable': False, 'launcher': MAIN_ACTIVITY,
            'launchMode': 'singleTask', 'defaultProcessOnly': True, 'unsigned': True}


def inventory(apk, expected, run_id, attempt, sdk, reports):
    if not re.fullmatch(r'[1-9][0-9]*', run_id) or not re.fullmatch(r'[1-9][0-9]*', attempt):
        raise ValueError('Run ID and attempt must be positive canonical integers')
    name = f'codexbar-mobile-{expected["versionName"]}-{expected["versionCode"]}-unsigned.apk'
    if apk.name != name:
        raise ValueError(f'Intended APK filename must be {name}')
    fingerprint = apk_bytes(apk)
    inspection = inspect_apk(apk, expected, sdk, reports)
    if fingerprint != apk_bytes(apk):
        raise ValueError('APK bytes changed during inspection')
    return {'schema': 1, 'source': expected['source'], 'runId': run_id, 'runAttempt': attempt,
            'buildInputs': expected, 'filename': name, **fingerprint, 'inspection': inspection,
            'purpose': 'unsigned signing input; non-distributable; evidence only'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('create', 'verify'))
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--reports', type=Path, required=True)
    args = parser.parse_args()
    try:
        expected = resolve(args.root, expected_sha=args.expected_sha)
        if git(args.root, 'status', '--porcelain'):
            raise ValueError('Artifact inventory requires a clean source checkout')
        receipt = inventory(args.apk, expected, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'],
                            Path(os.environ['ANDROID_HOME']), args.reports)
        if args.operation == 'verify':
            recorded = json.dumps(json.loads(args.manifest.read_text()), sort_keys=True)
            if recorded != json.dumps(receipt, sort_keys=True):
                raise ValueError('Artifact manifest differs from current independent source/build/APK observations')
        else:
            args.manifest.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
        print(f'Unsigned release artifact {args.operation} passed: {args.apk.name}')
    except (ValueError, OSError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Release artifact failed: {error}\n')


if __name__ == '__main__':
    main()
