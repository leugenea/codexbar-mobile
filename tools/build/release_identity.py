"""Pure local version/source resolution after explicit verified producer acquisition."""
import argparse
import json
from pathlib import Path
import re
import sys

from complete_codeql_checkout import VERSION, canonical, digest, read_snapshot, require_sha

NAMESPACE = 'io.github.leugenea.codexbarmobile'
PACKAGES = {'debug': NAMESPACE + '.dev', 'release': NAMESPACE}
MAX_CODE = 2147483647
IDENTITY_FIELDS = {'schema', 'targetVersion', 'channel', 'versionName', 'versionCode', 'sourceSha',
                   'sourceTree', 'shortSha', 'stableAncestor', 'devDistance', 'tag', 'tagObjectSha',
                   'purpose', 'counterAuthority', 'officialAdmission'}


def read_target(path):
    text = Path(path).read_text()
    if len(text) > 65 or not re.fullmatch(VERSION + r'\n', text):
        raise ValueError('VERSION must contain one canonical X.Y.Z line')
    return text[:-1]


def positive_code(value):
    if isinstance(value, str):
        if not re.fullmatch(r'[1-9][0-9]{0,9}', value):
            raise ValueError('Counter must be a positive canonical integer')
        value = int(value)
    if type(value) is not int or not 1 <= value <= MAX_CODE:
        raise ValueError('Counter outside positive Android integer range')
    return value


def validate_identity(identity):
    if set(identity) != IDENTITY_FIELDS or type(identity['schema']) is not int or identity['schema'] != 1:
        raise ValueError('Invalid identity schema')
    target = identity['targetVersion']
    if not isinstance(target, str) or len(target) > 64 or not re.fullmatch(VERSION, target):
        raise ValueError('Invalid identity target')
    require_sha(identity['sourceSha']); require_sha(identity['sourceTree'])
    short = identity['shortSha']
    if not isinstance(short, str) or not re.fullmatch(r'[0-9a-f]{12,40}', short) or not identity['sourceSha'].startswith(short):
        raise ValueError('Short SHA is not the actual checkout SHA')
    positive_code(identity['versionCode'])
    channel = identity['channel']
    if identity['officialAdmission'] is not None:
        raise ValueError('A1 identities cannot assert official admission')
    if channel == 'dev':
        distance = identity['devDistance']
        if type(distance) is not int or distance < 0:
            raise ValueError('Invalid reachable development distance')
        if identity['stableAncestor'] is not None:
            require_sha(identity['stableAncestor'])
        if identity['tag'] is not None or identity['tagObjectSha'] is not None:
            raise ValueError('Development identity cannot become a tagged release')
        expected = f'{target}-dev.{distance}+g{short}'
        purpose, authority = 'development-packaging-fixture', 'development'
    elif channel in ('rc', 'stable'):
        pattern = 'v' + re.escape(target) + (r'-rc\.[1-9][0-9]*' if channel == 'rc' else '')
        if not isinstance(identity['tag'], str) or not re.fullmatch(pattern, identity['tag']):
            raise ValueError('Unsupported tag or conflicting VERSION target')
        require_sha(identity['tagObjectSha'])
        if identity['stableAncestor'] is not None or identity['devDistance'] is not None:
            raise ValueError('Release fixture cannot claim development ancestry identity')
        expected = identity['tag'][1:]
        purpose, authority = 'release-identity-fixture', 'fixture'
    else:
        raise ValueError('Unsupported identity channel')
    if len(expected) > 128 or identity['versionName'] != expected:
        raise ValueError('Version name/source/channel mismatch')
    if identity['purpose'] != purpose or identity['counterAuthority'] != authority:
        raise ValueError('Identity fixture purpose/authority mismatch')
    return identity


def admit_counter(identity, prior, rerun=False):
    validate_identity(identity)
    if prior is None:
        raise ValueError('Explicit prior fixture code/history is required')
    if set(prior) == {'fixturePreviousCode'} and not rerun:
        previous = prior['fixturePreviousCode']
        if type(previous) is not int or not 0 <= previous < MAX_CODE:
            raise ValueError('Invalid prior fixture code')
    else:
        validate_identity(prior)
        if rerun:
            if identity != prior:
                raise ValueError('Rerun must preserve exact tag/source/version/code tuple')
            return
        previous = prior['versionCode']
    if identity['versionCode'] <= previous:
        raise ValueError('New release fixture counter must increase')


def stable_anchor(git, peeled, head):
    eligible = {}
    ancestors = set(git.text('rev-list', head).splitlines())
    for ref, commit in peeled.items():
        if not re.fullmatch('refs/tags/v' + VERSION, ref):
            continue
        if commit in ancestors:
            if commit in eligible and eligible[commit] != ref:
                raise ValueError('Conflicting stable versions at one source')
            eligible[commit] = ref
    maxima = set(eligible)
    for commit in eligible:
        for other in eligible:
            if commit != other and commit in set(git.text('rev-list', other).splitlines()):
                maxima.discard(commit)
    if len(maxima) > 1:
        raise ValueError('Ambiguous incomparable stable ancestors')
    return next(iter(maxima), None)


def resolve_identity(root, channel='dev', tag=None, source_sha=None, counter=None, prior=None, rerun=False):
    git, observation = read_snapshot(root)
    state = observation['state']
    head = state['head']
    target = read_target(Path(root) / 'VERSION')
    short = git.text('rev-parse', '--short=12', 'HEAD')
    identity = dict(schema=1, targetVersion=target, channel=channel, versionName='', versionCode=0,
                    sourceSha=head, sourceTree=state['tree'], shortSha=short, stableAncestor=None,
                    devDistance=None, tag=None, tagObjectSha=None, purpose='', counterAuthority='', officialAdmission=None)
    if channel == 'dev':
        if any(value is not None for value in (tag, source_sha, counter, prior)) or rerun:
            raise ValueError('Normal development must not consume release counter inputs')
        anchor = stable_anchor(git, observation['peeled'], head)
        if anchor is not None:
            stable = next(ref.removeprefix('refs/tags/v') for ref, commit in observation['peeled'].items()
                          if commit == anchor and re.fullmatch('refs/tags/v' + VERSION, ref))
            if tuple(map(int, target.split('.'))) < tuple(map(int, stable.split('.'))):
                raise ValueError('Development target precedes stable ancestor version')
            if anchor == head and stable != target:
                raise ValueError('Tagged HEAD and VERSION target disagree')
        count = git.text('rev-list', '--count', (anchor + '..' + head) if anchor else head)
        total = git.text('rev-list', '--count', head)
        if not re.fullmatch(r'0|[1-9][0-9]*', count):
            raise ValueError('Failed reachable commit discovery')
        identity.update(stableAncestor=anchor, devDistance=int(count), versionCode=positive_code(total),
                        versionName=f'{target}-dev.{count}+g{short}', purpose='development-packaging-fixture',
                        counterAuthority='development')
    elif channel in ('rc', 'stable'):
        if observation['mode'] != 'fixture':
            raise ValueError('A1 tagged builds are isolated hosted fixtures only')
        if require_sha(source_sha) != head or not isinstance(tag, str) or len(tag) > 129:
            raise ValueError('Exact tag/source input required')
        ref = 'refs/tags/' + tag
        if observation['peeled'].get(ref) != head:
            raise ValueError('Tag does not identify actual compiled source')
        identity.update(tag=tag, tagObjectSha=observation['tags'][ref], versionName=tag[1:],
                        versionCode=positive_code(counter), purpose='release-identity-fixture', counterAuthority='fixture')
        admit_counter(identity, prior, rerun)
    else:
        raise ValueError('Unsupported channel')
    return validate_identity(identity)


def identity_digest(identity):
    return digest(canonical(validate_identity(identity)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('resolve',))
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--channel', choices=('dev', 'rc', 'stable'), default='dev')
    parser.add_argument('--tag'); parser.add_argument('--source-sha'); parser.add_argument('--release-counter')
    parser.add_argument('--prior-identity', type=Path); parser.add_argument('--rerun', action='store_true')
    args = parser.parse_args()
    try:
        prior = json.loads(args.prior_identity.read_text()) if args.prior_identity else None
        identity = resolve_identity(args.repo, args.channel, args.tag, args.source_sha, args.release_counter, prior, args.rerun)
        print(canonical(identity).decode())
    except (ValueError, KeyError, OSError, json.JSONDecodeError):
        print('Source/version identity rejected; explicit complete acquisition required', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
