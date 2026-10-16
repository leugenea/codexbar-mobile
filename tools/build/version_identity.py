"""Resolve build inputs from VERSION and read-only complete Git history; never fetch."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

PACKAGE = 'io.github.leugenea.codexbarmobile'
SEMVER = r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)'
TAG = re.compile(r'v' + SEMVER + r'(?:-rc\.([1-9][0-9]*))?')
MAX_CODE = 2100000000


def target_version(text):
    value = text.removesuffix('\n')
    if not re.fullmatch(SEMVER, value):
        raise ValueError('VERSION must contain one canonical MAJOR.MINOR.PATCH target')
    return value


def parse_tag(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError(f'Malformed version tag: {tag}; expected vMAJOR.MINOR.PATCH[-rc.N]')
    return tuple(int(part) for part in match.groups()[:3]), match.group(4)


def counter(value, label, minimum):
    if not re.fullmatch(r'0|[1-9][0-9]{0,9}', value or ''):
        raise ValueError(f'{label} must be a canonical integer')
    number = int(value)
    if not minimum <= number <= MAX_CODE:
        raise ValueError(f'{label} must be between {minimum} and {MAX_CODE}')
    return number


def official_inputs(target, channel, env):
    tag = env.get('RELEASE_TAG', '')
    version, rc = parse_tag(tag)
    if version != tuple(map(int, target.split('.'))) or (rc is not None) != (channel == 'rc'):
        raise ValueError('RELEASE_TAG must agree exactly with VERSION and RELEASE_CHANNEL')
    code = counter(env.get('RELEASE_VERSION_CODE'), 'RELEASE_VERSION_CODE', 1)
    previous = counter(env.get('RELEASE_PREVIOUS_VERSION_CODE'), 'RELEASE_PREVIOUS_VERSION_CODE', 0)
    if code <= previous:
        raise ValueError('RELEASE_VERSION_CODE must increase beyond RELEASE_PREVIOUS_VERSION_CODE')
    return tag, code


def git(root, *args):
    result = subprocess.run(['git', '-C', str(root), *args], check=True, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    return result.stdout.strip()


def source_identity(root, expected_sha=None):
    if git(root, 'rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Full Git history/tags required; use checkout fetch-depth: 0')
    sha = git(root, 'rev-parse', 'HEAD')
    tree = git(root, 'rev-parse', 'HEAD^{tree}')
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or not re.fullmatch(r'[0-9a-f]{40}', tree):
        raise ValueError('Unsupported Git source identity')
    if expected_sha is not None and sha != expected_sha:
        raise ValueError('Checked-out source SHA differs from the independent expected SHA')
    return {'sha': sha, 'tree': tree}


def development_distance(root, target):
    stable = []
    reachable = set(git(root, 'tag', '--merged', 'HEAD', '--list', 'v[0-9]*').splitlines())
    # Validate version-like tags even if they are not reachable; unrelated tags are ignored.
    for tag in filter(None, git(root, 'tag', '--list', 'v[0-9]*').splitlines()):
        version, rc = parse_tag(tag)
        if rc is None and tag in reachable:
            distance = int(git(root, 'rev-list', '--count', tag + '..HEAD'))
            stable.append((distance, version, tag))
    if not stable:
        return int(git(root, 'rev-list', '--count', 'HEAD'))
    distance, version, _ = min(stable, key=lambda entry: (entry[0], tuple(-n for n in entry[1])))
    if tuple(map(int, target.split('.'))) < version:
        raise ValueError('VERSION development target precedes the last stable ancestor')
    return distance


def resolve(root, env=None, expected_sha=None):
    env = os.environ if env is None else env
    target = target_version((root / 'VERSION').read_text())
    channel = env.get('RELEASE_CHANNEL', 'dev')
    if channel not in {'dev', 'rc', 'stable'}:
        raise ValueError('RELEASE_CHANNEL must be dev, rc or stable')
    source = source_identity(root, expected_sha)
    if channel == 'dev':
        if any(env.get(key) is not None for key in ('RELEASE_TAG', 'RELEASE_VERSION_CODE', 'RELEASE_PREVIOUS_VERSION_CODE')):
            raise ValueError('Official tag/counter inputs are forbidden in dev mode')
        distance = development_distance(root, target)
        name, code, tag = f'{target}-dev.{distance}+g{source["sha"][:12]}', 1, None
    else:
        tag, code = official_inputs(target, channel, env)
        if git(root, 'rev-parse', 'refs/tags/' + tag + '^{commit}') != source['sha']:
            raise ValueError('Official version tag must point to the compiled checkout HEAD')
        if git(root, 'status', '--porcelain'):
            raise ValueError('Official fixture builds require a clean source checkout')
        name = tag[1:]
    return {'source': source, 'target': target, 'channel': channel, 'tag': tag,
            'versionName': name, 'versionCode': code,
            'debugPackage': PACKAGE + '.dev', 'releasePackage': PACKAGE}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--expected-sha')
    args = parser.parse_args()
    try:
        print(json.dumps(resolve(args.root, expected_sha=args.expected_sha), sort_keys=True))
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f'Version identity failed: {error}\n')


if __name__ == '__main__':
    main()
