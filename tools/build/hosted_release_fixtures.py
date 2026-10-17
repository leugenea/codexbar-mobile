"""Real Git/Gradle/APK fixtures: hosted only, isolated clones, no public tags or signing."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from release_artifact import inventory
from release_bundle import unsigned_files, write_bundle, verify_files
from version_identity import git, resolve

ROOT = Path(__file__).resolve().parents[2]


def command(root, *args, env=None, log=None):
    with log.open('w') if log else open(os.devnull, 'w') as output:
        subprocess.run(args, cwd=root, env=env, stdout=output, stderr=subprocess.STDOUT,
                       check=True, timeout=300 if log is None else 600)


def build_case(root, label, env, reports):
    reports.mkdir(parents=True)
    expected = resolve(root, env)
    command(root, './gradlew', '--no-daemon', '--dependency-verification', 'strict', '--build-cache',
            '--no-configuration-cache', '--stacktrace', '--console=plain', ':app:assembleRelease',
            ':app:collectReleaseRuntimeInventory', env=env, log=reports / 'assemble.log')
    if (reports / 'assemble.log').read_text().splitlines().count(
            'GATE_TASK_OUTCOME :app:collectReleaseRuntimeInventory SUCCESS') != 1:
        raise ValueError('Hosted fixture requires fresh release runtime collection')
    name = f'codexbar-mobile-{expected["versionName"]}-{expected["versionCode"]}-unsigned.apk'
    apk = reports / name
    shutil.copyfile(root / 'app/build/outputs/apk/release/app-release-unsigned.apk', apk)
    receipt = inventory(apk, expected, env['GITHUB_RUN_ID'], env['GITHUB_RUN_ATTEMPT'],
                        Path(env['ANDROID_HOME']), reports / 'inspection')
    (reports / 'artifact-manifest.json').write_text(json.dumps(receipt, indent=2) + '\n')
    if receipt != inventory(apk, resolve(root, env), env['GITHUB_RUN_ID'], env['GITHUB_RUN_ATTEMPT'],
                            Path(env['ANDROID_HOME']), reports / 'reinspection'):
        raise ValueError(f'Hosted fixture reverification failed: {label}')
    resolution = root / 'app/build/runtime-release/runtime-resolution.json'
    capsule = reports / 'bundle'
    files = unsigned_files(root, expected, apk, reports / 'artifact-manifest.json', resolution,
                           Path(env['ANDROID_HOME']), reports / 'runtime-inspection')
    write_bundle(capsule, files, [apk, resolution])
    verify_files(capsule, unsigned_files(root, resolve(root, env), apk, reports / 'artifact-manifest.json',
                                        resolution, Path(env['ANDROID_HOME']), reports / 'runtime-reinspection'))
    return expected


def reject(label, operation):
    try:
        operation()
    except (ValueError, subprocess.CalledProcessError):
        return label
    raise ValueError(f'Hosted negative fixture unexpectedly passed: {label}')


def exercise(root, reports, env):
    target = (root / 'VERSION').read_text().strip()
    # Tags exist ONLY in this disposable local clone, never in the Actions checkout.
    for tag in ('v' + target, 'v' + target + '-rc.1'):
        if tag in git(root, 'tag', '--list').splitlines():
            command(root, 'git', 'tag', '-d', tag)
    command(root, 'git', 'tag', 'v' + target)
    dev = build_case(root, 'tagged-dev', env, reports / 'tagged-dev')
    if not dev['versionName'].startswith(target + '-dev.0+') or dev['channel'] != 'dev':
        raise ValueError('Tagged checkout must still build as development by default')
    command(root, 'git', 'tag', 'v' + target + '-rc.1')
    for channel, tag, code, previous in (('rc', 'v' + target + '-rc.1', '2', '1'),
                                          ('stable', 'v' + target, '3', '2')):
        official = {**env, 'RELEASE_CHANNEL': channel, 'RELEASE_TAG': tag,
                    'RELEASE_VERSION_CODE': code, 'RELEASE_PREVIOUS_VERSION_CODE': previous}
        identity = build_case(root, channel, official, reports / channel)
        if identity['versionName'] != tag[1:] or identity['versionCode'] != int(code):
            raise ValueError('Official fixture identity differs from explicit inputs')
    negatives = [reject('wrong-source-sha', lambda: resolve(root, env, '0' * 40))]
    negatives.append(reject('tag-channel-conflict', lambda: resolve(root, {**official, 'RELEASE_CHANNEL': 'rc'})))
    negatives.append(reject('counter-reuse', lambda: resolve(root, {**official, 'RELEASE_PREVIOUS_VERSION_CODE': '3'})))
    # Force a real shallow clone of the fixture source; no network/private transfer.
    shallow = root.parent / 'shallow'
    command(root.parent, 'git', 'clone', '--depth=1', '--no-tags', root.as_uri(), str(shallow))
    negatives.append(reject('shallow-history', lambda: resolve(shallow, env)))
    command(root, 'git', 'tag', 'v01.0.0')
    negatives.append(reject('malformed-tag', lambda: resolve(root, env)))
    command(root, 'git', 'tag', '-d', 'v01.0.0')
    # A real successor source cannot claim an earlier tag; this commit stays in the clone.
    command(root, 'git', '-c', 'user.name=Hosted fixture', '-c', 'user.email=fixture@example.invalid',
            'commit', '--allow-empty', '-m', 'Synthetic version history fixture')
    if not resolve(root, env)['versionName'].startswith(target + '-dev.1+'):
        raise ValueError('Development distance must count actual reachable successor commits')
    negatives.append(reject('tag-source-conflict', lambda: resolve(root, official)))
    (reports / 'negative-results.json').write_text(json.dumps(negatives, indent=2) + '\n')


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Hosted runner only; real Git/Gradle/APK fixtures are prohibited locally')
    reports = ROOT / 'evidence/release-fixtures'
    reports.mkdir(parents=True)
    # Official fixture inputs do not escape to ordinary CI or create a production allocator.
    env = {key: value for key, value in os.environ.items() if not key.startswith('RELEASE_')}
    with tempfile.TemporaryDirectory(prefix='release-fixtures-', dir=os.environ['RUNNER_TEMP']) as directory:
        root = Path(directory) / 'source'
        command(Path(directory), 'git', 'clone', '--no-hardlinks', str(ROOT), str(root))
        command(root, 'git', 'checkout', '--detach', git(ROOT, 'rev-parse', 'HEAD'))
        exercise(root, reports, env)
    print('Hosted tagged-dev/RC/stable unsigned APK and negative history/identity fixtures passed')


if __name__ == '__main__':
    main()
