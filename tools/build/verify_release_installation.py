"""Bounded production-package observer on the existing hosted emulator, outside coverage."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

import release_artifact
from release_runtime import fingerprint, load
from release_signing import asset_names, verify_assets
from verify_manifests import PACKAGE, MAIN_ACTIVITY, check_installed_dump


def installed_path(text):
    rows = text.splitlines()
    pattern = r'package:(/data/app/(?:[A-Za-z0-9_~+=.-]+/)*' + re.escape(PACKAGE) + r'-[A-Za-z0-9_~+=.-]+/base\.apk)'
    if len(rows) != 1 or not (match := re.fullmatch(pattern, rows[0])) or '/..' in rows[0]:
        raise ValueError('Release must have exactly one confined production base.apk path')
    return match[1]


def component_pattern():
    return re.escape(PACKAGE) + r'/(?:' + re.escape(MAIN_ACTIVITY) + r'|\.MainActivity)'


def foreground(activity, pid):
    return bool(re.fullmatch(r'[1-9][0-9]*\s*', pid) and re.search(
        r'(?m)^\s*(?:mResumedActivity:\s*|topResumedActivity[=:]\s*)ActivityRecord\{[^\n]*\bu0 '
        + component_pattern() + r'(?:[ }])', activity))


def observe(root, asset_dir, sdk, serial, reports, *, execute=subprocess.run, clock=time.monotonic, wait=time.sleep):
    reports.mkdir(parents=True, exist_ok=True)
    statuses = []
    adb = sdk / 'platform-tools/adb'
    def run(label, *arguments, seconds=15):
        try:
            result = execute([str(adb), '-s', serial, *map(str, arguments)], text=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=seconds)
            code, text = result.returncode, result.stdout
        except subprocess.TimeoutExpired:
            code, text = 124, 'Bounded ADB command timed out\n'
        (reports / (label + '.txt')).write_text(text)
        statuses.append({'step': label, 'exit': code})
        (reports / 'statuses.json').write_text(json.dumps(statuses, indent=2) + '\n')
        if code:
            raise ValueError('Release observation failed at ' + label)
        return text
    failure = None
    try:
        assets = {p.name: p.read_bytes() for p in asset_dir.iterdir() if p.is_file()}
        manifest = load(asset_dir / 'release-manifest.json')
        # The fixture's independent public certificate is outside the downloaded assets.
        certificate = (asset_dir.parent / 'certificate.sha256').read_text().strip()
        manifest, verified_copy = verify_assets(root, assets, sdk, reports / 'download-verification', expected_certificate=certificate)
        expected = manifest['buildInputs']
        apk = asset_dir / asset_names(expected)[0]
        if apk.read_bytes() != verified_copy.read_bytes():
            raise ValueError('Exact downloaded APK changed before installation')
        if not re.search(r'(?m)^Success\r?$', run('install', 'install', apk, seconds=30)):
            raise ValueError('Release install did not report Success')
        path = installed_path(run('path', 'shell', 'pm', 'path', PACKAGE))
        dump = run('package', 'shell', 'dumpsys', 'package', PACKAGE)
        check_installed_dump(dump, PACKAGE)
        if (re.findall(r'\bversionCode=([0-9]+)\b', dump) != [str(expected['versionCode'])]
                or re.findall(r'(?m)^\s*versionName=(\S+)\s*$', dump) != [expected['versionName']]
                or re.search(r'\bDEBUGGABLE\b', dump)):
            raise ValueError('Installed release version/debuggability differs')
        pulled = reports / asset_names(expected)[0]
        run('pull', 'pull', path, pulled, seconds=30)
        if pulled.read_bytes() != apk.read_bytes():
            raise ValueError('Installed APK differs from exact mock-downloaded signed bytes')
        release_artifact.inspect_apk(pulled, expected, sdk, reports / 'installed-inspection', expected_signer=certificate)
        launch = run('launch', 'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN',
                     '-c', 'android.intent.category.LAUNCHER', '-n', PACKAGE + '/' + MAIN_ACTIVITY, seconds=30)
        if (not re.search(r'(?m)^Status: ok\r?$', launch)
                or not re.search(r'(?m)^Activity: ' + component_pattern() + r'\r?$', launch)):
            raise ValueError('Exact release Activity launch not successful')
        deadline = clock() + 30  # Fresh observer timer, independent of boot/test/coverage clocks.
        observed = False
        attempt = 0
        while clock() < deadline:
            attempt += 1
            activity = run(f'foreground-{attempt}', 'shell', 'dumpsys', 'activity', 'activities', seconds=5)
            pid = run(f'process-{attempt}', 'shell', 'pidof', PACKAGE, seconds=5)
            if foreground(activity, pid):
                observed = True
                break
            wait(1)
        if not observed:
            raise ValueError('No positive exact resumed Activity/default-process observation before deadline')
        (reports / 'identity.json').write_text(json.dumps({'downloadedSha256': fingerprint(apk.read_bytes())['sha256'],
            'installedSha256': fingerprint(pulled.read_bytes())['sha256'], 'certificateSha256': certificate,
            'buildInputs': expected, 'foregroundObserved': True}, indent=2) + '\n')
    except BaseException as error:
        failure = error
    finally:
        try:
            if not re.search(r'(?m)^Success\r?$', run('uninstall', 'uninstall', PACKAGE)):
                raise ValueError('Release cleanup did not report Success')
        except BaseException as cleanup:
            if failure is None:
                failure = cleanup
    if failure is not None:
        raise failure


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Hosted emulator only; release observation is prohibited locally')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--reports', type=Path, required=True)
    args = parser.parse_args()
    def interrupted(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, interrupted)
    observe(Path(__file__).resolve().parents[2], args.assets, Path(os.environ['ANDROID_HOME']),
            os.environ['ANDROID_SERIAL'], args.reports)


if __name__ == '__main__':
    main()
