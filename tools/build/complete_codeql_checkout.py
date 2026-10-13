"""Bounded hosted acquisition. Identity consumers only revalidate local snapshots.

The managed bootstrap uses the fixed anonymous public destination, never a remote
name, persisted credentials, a moving tip, or an identity-discovery fallback.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import tempfile

PUBLIC_REPOSITORY = 'leugenea/codexbar-mobile'
PUBLIC_URL = 'https://github.com/' + PUBLIC_REPOSITORY + '.git'
SHA = re.compile(r'[0-9a-f]{40}')
VERSION = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'
TAG = re.compile(r'refs/tags/v' + VERSION + r'(?:-rc\.[1-9][0-9]*)?')
MAX_SECONDS = 300


def canonical(data):
    return json.dumps(data, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ValueError('Invalid full source/object SHA')
    return value


def safe_environment(environment):
    # Allowlist, not a list of known token variable names. No tracing, inherited
    # env-config, proxy, askpass, client certificate or lazy object acquisition.
    safe = {key: environment[key] for key in ('PATH', 'LANG', 'LC_ALL', 'SYSTEMROOT') if key in environment}
    safe.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_SYSTEM=os.devnull,
                GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0',
                GIT_OPTIONAL_LOCKS='0', GIT_NO_LAZY_FETCH='1',
                GIT_NO_REPLACE_OBJECTS='1', GIT_ASKPASS='/bin/false',
                SSH_ASKPASS='/bin/false', GIT_ALLOW_PROTOCOL='https')
    return safe


class Git:
    def __init__(self, root, environment=None, run=subprocess.run):
        self.root = Path(root).resolve()
        self.environment = dict(os.environ if environment is None else environment)
        self.env = safe_environment(self.environment)
        self.run = run
        self.deadline = time.monotonic() + MAX_SECONDS
        self.headers = []
        self.config_files = []

    def call(self, *args):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('Acquisition time budget exhausted')
        overrides = ['credential.helper=', 'core.askPass=/bin/false', 'core.fsmonitor=false',
                     'core.hooksPath=/dev/null', 'core.commitGraph=false', 'http.extraHeader=', 'http.followRedirects=false',
                     'http.proxy=', 'http.cookieFile=', 'http.saveCookies=false',
                     'http.sslVerify=true', 'http.sslCert=', 'http.sslKey=',
                     'http.sslCertPasswordProtected=false', 'gc.auto=0',
                     'core.logAllRefUpdates=false', 'maintenance.auto=false', 'fetch.writeCommitGraph=false',
                     'protocol.allow=never', 'protocol.https.allow=always'] + [key + '=' for key in self.headers]
        argv = ['git', '-C', str(self.root)]
        for item in overrides:
            argv += ['-c', item]
        try:
            if args and args[0] in ('fetch', 'ls-remote'):
                # libcurl may otherwise consult a user's .netrc even when Git
                # helpers/headers are disabled. Only this child process gets an
                # empty private home; parent environment/credentials stay intact.
                with tempfile.TemporaryDirectory(prefix='anonymous-git-', dir=self.environment.get('RUNNER_TEMP', '/opt/data/tmp')) as empty_home:
                    network_env = dict(self.env, HOME=empty_home, XDG_CONFIG_HOME=empty_home)
                    result = self.run(argv + list(args), env=network_env, capture_output=True,
                                      timeout=min(60, remaining), check=False)
            else:
                result = self.run(argv + list(args), env=self.env, capture_output=True,
                                  timeout=min(60, remaining), check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ValueError('Git operation failed or timed out; no credential diagnostics retained') from error
        if result.returncode:
            # Git diagnostics can contain URL/config/header values. Fail without
            # forwarding stdout/stderr, argv or exception strings into build logs.
            raise ValueError('Git operation refused; no alternate ref or retry')
        return result.stdout

    def text(self, *args):
        return self.call(*args).decode('utf-8').strip()


def config_entries(raw):
    entries = []
    for item in raw.decode('utf-8').split('\0'):
        if not item:
            continue
        key, separator, value = item.partition('\n')
        if not separator or not re.fullmatch(r'[A-Za-z0-9][^\x00-\x20]*', key):
            raise ValueError('Malformed Git configuration')
        entries.append((key.lower(), value))
    return entries


def validate_config(entries, runner_temp):
    """Return header reset keys and tightly scoped managed credential includes.

    checkout v6 stores authorization-only data in runner-temp includeIf files.
    Read those as data with --no-includes; never invoke helpers or recursively
    include another file. All other include/rewrite/proxy/client-auth escapes fail.
    """
    headers, includes = [], []
    for key, value in entries:
        if key.endswith('.extraheader') and key.startswith('http.'):
            # Only the public HTTPS host/repository auth scopes used by checkout.
            scope = key[5:-12]
            if scope not in ('', 'https://github.com/', 'https://github.com/' + PUBLIC_REPOSITORY,
                             'https://github.com/' + PUBLIC_REPOSITORY + '.git'):
                raise ValueError('Unsupported authorization header scope')
            headers.append(key)
        elif key.startswith('includeif.') and key.endswith('.path'):
            condition = key[len('includeif.'):-len('.path')]
            if not condition.startswith('gitdir:'):
                raise ValueError('Unsupported configuration include condition')
            path = Path(value)
            temporary = Path(runner_temp).resolve()
            if (not path.is_absolute() or path.is_symlink() or path.parent.resolve() != temporary
                    or not re.fullmatch(r'git-credentials-[A-Za-z0-9-]+\.config', path.name)):
                raise ValueError('Unsupported configuration include destination')
            includes.append(path)
        elif (key.startswith(('include.', 'url.', 'http.', 'https.', 'proxy.', 'filter.'))
              or key in ('core.sshcommand', 'core.gitproxy', 'core.alternateobjectdirectories',
                         'extensions.partialclone', 'extensions.worktreeconfig')
              or key.endswith(('.proxy', '.promisor', '.partialclonefilter'))):
            raise ValueError('Unsupported unsafe Git configuration')
        elif key.startswith('credential.') and key != 'credential.helper':
            raise ValueError('Unsupported scoped credential configuration')
        elif key == 'credential.helper' or key in ('core.askpass', 'core.fsmonitor', 'core.hookspath'):
            # Always overridden; no helper or askpass retrieval occurs.
            continue
    return sorted(set(headers)), includes


def inspect_config(git):
    entries = config_entries(git.call('config', '--local', '--no-includes', '--null', '--list'))
    headers, includes = validate_config(entries, git.environment.get('RUNNER_TEMP', ''))
    for path in includes:
        nested = config_entries(git.call('config', '--file', str(path), '--no-includes', '--null', '--list'))
        if any(not key.startswith('http.') or not key.endswith('.extraheader') for key, _ in nested):
            raise ValueError('Managed authorization include contains unsupported behavior')
        more, recursive = validate_config(nested, git.environment['RUNNER_TEMP'])
        if recursive:
            raise ValueError('Recursive authorization include forbidden')
        headers += more
    git.headers = sorted(set(headers))
    config = Path(git.text('rev-parse', '--git-path', 'config'))
    if not config.is_absolute():
        config = git.root / config
    git.config_files = [config] + includes
    origins = [value for key, value in entries if key == 'remote.origin.url']
    if len(origins) != 1:
        raise ValueError('Missing or ambiguous checkout origin')
    return origins[0]


def release_refs(text):
    result = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) != 2:
            raise ValueError('Malformed advertised tag map')
        oid, ref = fields
        require_sha(oid)
        if ref.startswith('refs/tags/v'):
            if not TAG.fullmatch(ref) or len(ref) > 140 or ref in result:
                raise ValueError('Unsupported or duplicate release tag')
            result[ref] = oid
        elif not ref.startswith('refs/tags/'):
            raise ValueError('Unexpected advertised ref')
    return dict(sorted(result.items()))


def local_tags(git):
    return release_refs(git.text('for-each-ref', '--format=%(objectname) %(refname)', 'refs/tags'))


def fingerprint(git):
    if Path(git.text('rev-parse', '--show-toplevel')).resolve() != git.root:
        raise ValueError('Git source root differs from captured workspace')
    if git.call('ls-files', '--others', '--exclude-standard', '-z'):
        raise ValueError('Untracked source is not committed provenance')
    head = require_sha(git.text('rev-parse', 'HEAD'))
    tree = require_sha(git.text('rev-parse', 'HEAD^{tree}'))
    logical = git.call('ls-files', '--stage', '-z')
    index_path = Path(git.text('rev-parse', '--git-path', 'index'))
    if not index_path.is_absolute():
        index_path = git.root / index_path
    tracked = []
    for record in logical.split(b'\0'):
        if not record:
            continue
        info, name = record.split(b'\t', 1)
        mode, blob, stage = info.decode().split()
        relative = name.decode('utf-8')
        file = git.root / relative
        if stage != '0' or mode not in ('100644', '100755') or file.is_symlink():
            raise ValueError('Unsupported index entry or symlink')
        data = file.read_bytes()
        if git.call('cat-file', 'blob', blob) != data:
            raise ValueError('Tracked working source differs from index')
        actual_mode = '100755' if file.stat().st_mode & 0o111 else '100644'
        if actual_mode != mode:
            raise ValueError('Tracked source mode differs from index')
        tracked.append(dict(path=relative, gitMode=mode, blobSha=blob, sha256=digest(data)))
    # Index equality with HEAD, without status/diff/index refresh hooks.
    entries = git.call('ls-tree', '-r', '-z', 'HEAD')
    expected = b''.join(record.replace(b' blob ', b' ').replace(b'\t', b' 0\t', 1) + b'\0'
                        for record in entries.split(b'\0') if record)
    if logical != expected:
        raise ValueError('Index differs from committed source')
    configs = {str(p.resolve()): digest(p.read_bytes()) for p in git.config_files}
    all_refs = git.text('for-each-ref', '--format=%(refname) %(objectname)')
    refs = '\n'.join(line for line in all_refs.splitlines() if not TAG.fullmatch(line.split()[0]))
    head_path = Path(git.text('rev-parse', '--git-path', 'HEAD'))
    if not head_path.is_absolute():
        head_path = git.root / head_path
    return dict(headFile=digest(head_path.read_bytes()), root=str(git.root), head=head, tree=tree, rawIndex=digest(index_path.read_bytes()),
                logicalIndex=digest(logical), trackedFiles=tracked, configs=configs,
                branch=head_path.read_text().strip(),
                nonTagRefs=digest(refs.encode()))


def reject_shortcuts(git):
    for name in ('info/grafts', 'objects/info/alternates', 'objects/info/http-alternates'):
        path = Path(git.text('rev-parse', '--git-path', name))
        if not path.is_absolute():
            path = git.root / path
        if path.exists():
            raise ValueError('Graft or alternate object shortcut forbidden')
    if git.text('for-each-ref', '--format=%(refname)', 'refs/replace'):
        raise ValueError('Replacement objects forbidden')
    common = Path(git.text('rev-parse', '--git-common-dir'))
    if not common.is_absolute():
        common = git.root / common
    if list((common / 'objects/pack').glob('*.promisor')):
        raise ValueError('Promisor object packs forbidden')


def complete_graph(git, heads):
    reject_shortcuts(git)
    if git.text('rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Incomplete shallow source history')
    for head in heads:
        require_sha(head)
    objects = git.text('rev-list', '--objects', '--missing=print', *heads)
    if not objects or any(line.startswith('?') for line in objects.splitlines()):
        raise ValueError('Incomplete source/tag object graph')
    ids = [line.split()[0] for line in objects.splitlines()]
    # cat-file observes every object with lazy acquisition disabled, not only a
    # declaration that fetch-depth was zero. rev-list fails for missing parents.
    observed = git.call('cat-file', '--batch-check', '--batch-all-objects')
    available = {line.split()[0].decode() for line in observed.splitlines() if b' missing' not in line}
    if not set(ids) <= available:
        raise ValueError('Missing actual graph objects')
    parents = git.text('rev-list', '--parents', *heads)
    commits = {line.split()[0] for line in parents.splitlines()}
    if not commits or any(not set(line.split()[1:]) <= commits for line in parents.splitlines()):
        raise ValueError('Incomplete parent graph')


def verify_tags(git, tags):
    if local_tags(git) != tags:
        raise ValueError('Local acquired tag map changed or incomplete')
    peeled = {ref: require_sha(git.text('rev-parse', ref + '^{commit}')) for ref in tags}
    complete_graph(git, [require_sha(git.text('rev-parse', 'HEAD'))] + list(peeled.values()))
    return peeled


def job_context(environment, root, head, *, fixture=False):
    if environment.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Hosted acquisition context required')
    for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB', 'RUNNER_TEMP'):
        if not environment.get(key):
            raise ValueError('Missing hosted acquisition context')
    if fixture:
        if not root.is_relative_to(Path(environment['RUNNER_TEMP']).resolve()):
            raise ValueError('Fixture root must be isolated in runner temp')
        return {key: environment[key] for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB')} | {'fixture': True}
    if (not environment.get('GITHUB_WORKSPACE') or environment.get('GITHUB_REPOSITORY') != PUBLIC_REPOSITORY
            or Path(environment.get('GITHUB_WORKSPACE', '')).resolve() != root
            or environment.get('GITHUB_SHA') != head):
        raise ValueError('Wrong public producer/event/checkout source')
    event_path = environment.get('GITHUB_EVENT_PATH')
    if not event_path:
        raise ValueError('Missing actual producer event')
    event = json.loads(Path(event_path).read_text())
    if event.get('repository', {}).get('full_name') != PUBLIC_REPOSITORY:
        raise ValueError('Producer event does not identify the reviewed public repository')
    if event.get('head_commit') and event['head_commit'].get('id') != head:
        raise ValueError('Producer event commit differs from captured checkout')
    pr = event.get('pull_request')
    if pr:
        if pr.get('base', {}).get('repo', {}).get('full_name') != PUBLIC_REPOSITORY or pr['head']['sha'] == head:
            raise ValueError('PR event must identify actual public merge source')
        require_sha(pr['head']['sha'])
        if environment.get('GITHUB_REF') != 'refs/pull/' + str(pr['number']) + '/merge':
            raise ValueError('PR source head is not the compiled merge checkout')
    return {key: environment[key] for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB')} | {
        'fixture': False, 'eventSha': head, 'eventSha256': digest(Path(event_path).read_bytes()),
        'sourceHeadSha': require_sha(pr['head']['sha']) if pr else head,
        'ref': environment.get('GITHUB_REF'), 'repository': PUBLIC_REPOSITORY}


def snapshot_path(root, environment):
    temporary = Path(environment['RUNNER_TEMP']).resolve()
    key = digest(canonical([str(root), environment['GITHUB_RUN_ID'],
                            environment['GITHUB_RUN_ATTEMPT'], environment['GITHUB_JOB']]))
    return temporary / ('release-acquisition-' + key + '.json')


def validate_snapshot(git, observation, context):
    if set(observation) != {'schema', 'mode', 'context', 'state', 'tags', 'peeled', 'tagMapSha256'}:
        raise ValueError('Malformed acquisition observation')
    if type(observation['schema']) is not int or observation['schema'] != 1 or observation['mode'] not in ('managed', 'normal', 'fixture'):
        raise ValueError('Unsupported acquisition observation')
    if observation['context'] != context or observation['state'] != fingerprint(git):
        raise ValueError('Stale acquisition source/index/config/job context')
    if digest(canonical(observation['tags'])) != observation['tagMapSha256']:
        raise ValueError('Tampered acquisition tag map')
    if verify_tags(git, observation['tags']) != observation['peeled']:
        raise ValueError('Acquired tag peeling changed')
    if context.get('sourceHeadSha'):
        head = context['sourceHeadSha']
        if head != observation['state']['head']:
            parents = git.text('rev-list', '--parents', '-n', '1', 'HEAD').split()
            if head not in parents[1:]:
                raise ValueError('PR head does not belong to compiled merge')
    return observation


def read_snapshot(root, environment=None):
    git = Git(root, environment)
    inspect_config(git)
    head = require_sha(git.text('rev-parse', 'HEAD'))
    path = snapshot_path(git.root, git.environment)
    if not path.is_file() or path.is_symlink():
        raise ValueError('Missing producer acquisition; resolver never auto-fetches')
    observation = json.loads(path.read_text())
    context = job_context(git.environment, git.root, head, fixture=observation.get('mode') == 'fixture')
    return git, validate_snapshot(git, observation, context)


def prepare(root, mode, environment=None, run=subprocess.run):
    if mode not in ('managed', 'normal', 'fixture'):
        raise ValueError('Unknown acquisition mode')
    git = Git(root, environment, run)
    if mode == 'managed' and not git.environment.get('CODEQL_EXTRACTOR_JAVA_TRAP_DIR', '').strip():
        raise ValueError('Nonempty extractor marker required before any preparation')
    # Check context before config/includes or any network operation.
    head = require_sha(git.text('rev-parse', 'HEAD'))
    context = job_context(git.environment, git.root, head, fixture=mode == 'fixture')
    if context.get('sourceHeadSha') and context['sourceHeadSha'] != head:
        parents = re.findall(r'^parent ([0-9a-f]{40})$', git.text('cat-file', '-p', head), re.M)
        if context['sourceHeadSha'] not in parents:
            raise ValueError('PR head does not identify a captured merge parent')
    origin = inspect_config(git)
    if mode != 'fixture' and origin not in (PUBLIC_URL, PUBLIC_URL[:-4]):
        raise ValueError('Only exact anonymous public checkout origin is supported')
    if mode == 'fixture':
        origin_path = Path(origin).resolve()
        if not origin_path.is_relative_to(Path(git.environment['RUNNER_TEMP']).resolve()):
            raise ValueError('Fixture origin must be isolated in runner temp')
        # Only explicit fixture acquisition may read a local ephemeral origin.
        git.env['GIT_ALLOW_PROTOCOL'] = 'file'
    before = fingerprint(git)
    reject_shortcuts(git)
    path = snapshot_path(git.root, git.environment)
    if path.exists():
        observation = json.loads(path.read_text())
        if observation.get('mode') != mode:
            raise ValueError('Acquisition mode changed in the same job/root')
        return validate_snapshot(git, observation, context)
    destination = origin if mode == 'fixture' else PUBLIC_URL
    advertisement = lambda: release_refs(git.text('ls-remote', '--tags', '--refs', destination))
    if mode == 'fixture':
        # protocol.allow=never also governs the explicit local fixture transport.
        original_run = git.run
        def fixture_run(argv, **kwargs):
            return original_run(argv[:1] + ['-c', 'protocol.file.allow=always'] + argv[1:], **kwargs)
        git.run = fixture_run
    tags = advertisement()
    existing = local_tags(git)
    if any(ref not in tags or tags[ref] != oid for ref, oid in existing.items()):
        raise ValueError('Conflicting or unexpected local release refs')
    if mode == 'managed':
        options = ['fetch', '--no-tags', '--no-prune', '--no-write-fetch-head', '--no-auto-maintenance',
                   '--no-write-commit-graph', '--refmap=']
        if git.text('rev-parse', '--is-shallow-repository') == 'true':
            git.call(*options, '--unshallow', destination, head)
        else:
            complete_graph(git, [head])
        for ref, oid in tags.items():
            if ref not in existing:
                git.call(*options, destination, oid + ':' + ref)
    # Normal full checkout and fixture clone must already have the advertised
    # objects/tags; acquisition does not repair their discovery failures.
    peeled = verify_tags(git, tags)
    if advertisement() != tags:
        raise ValueError('Advertised release refs moved during acquisition')
    after = fingerprint(git)
    if before != after:
        raise ValueError('Preparation changed protected source/index/config/ref state')
    observation = dict(schema=1, mode=mode, context=context, state=after, tags=tags,
                       peeled=peeled, tagMapSha256=digest(canonical(tags)))
    validate_snapshot(git, observation, context)
    path.write_bytes(canonical(observation) + b'\n')
    return observation


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--mode', choices=('managed', 'normal', 'fixture'), default='managed')
    args = parser.parse_args()
    try:
        observation = prepare(args.repo, args.mode)
        print(json.dumps({'acquisition': observation['mode'], 'head': observation['state']['head'],
                          'tree': observation['state']['tree'], 'tagMapSha256': observation['tagMapSha256'],
                          'preservedSourceIndexConfig': True}, sort_keys=True))
    except (ValueError, KeyError, OSError, json.JSONDecodeError):
        # Deliberately do not emit exception/config/transport values.
        print('Exact-source acquisition failed; no fallback or credential diagnostics', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
