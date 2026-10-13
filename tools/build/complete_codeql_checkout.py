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
import stat

PUBLIC_REPOSITORY = 'leugenea/codexbar-mobile'
PUBLIC_URL = 'https://github.com/' + PUBLIC_REPOSITORY + '.git'
SHA = re.compile(r'[0-9a-f]{40}')
VERSION = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'
TAG = re.compile(r'refs/tags/v' + VERSION + r'(?:-rc\.[1-9][0-9]*)?')
MAX_SECONDS = 300


STAGES = frozenset("""
    HOST_CONTEXT EVENT_KIND EVENT_PAYLOAD
    EVENT_REF_SHA CHECKOUT_RELATION SCRATCH_ROOT
    CONFIG_POLICY PROTECTED_SOURCE_INDEX_STATE OBJECT_GRAPH
    ANONYMOUS_TRANSPORT RELEASE_TAG_MAP SNAPSHOT_REUSE
    OBSERVATION_WRITE
""".split())
CATEGORIES = frozenset("""
    EVENT_ROLE_NORMAL_PR_MERGE EVENT_ROLE_MANAGED_PR_HEAD EVENT_ROLE_UNSUPPORTED
    EVENT_PR_MISSING EVENT_PR_REPOSITORY_MISMATCH EVENT_REF_MISMATCH
    EVENT_PRODUCER_SHA_MATCHES_PR_HEAD EVENT_PRODUCER_SHA_MATCHES_EVENT_MERGE EVENT_PRODUCER_SHA_MATCHES_NEITHER
    CHECKOUT_HEAD_MISMATCH CHECKOUT_PARENT_MISMATCH SCRATCH_INVALID
    CONFIG_INCLUDE_REJECTED CONFIG_OTHER_UNSAFE TRANSPORT_SETUP_FAILED
    TRANSPORT_REFUSED TRANSPORT_TIMED_OUT GRAPH_INCOMPLETE
    TAG_MAP_MISMATCH SNAPSHOT_STATE_OR_CONTEXT_MISMATCH EVENT_KIND_PULL_REQUEST
    EVENT_KIND_PUSH EVENT_KIND_WORKFLOW_DISPATCH EVENT_KIND_DYNAMIC
    EVENT_KIND_OTHER EVENT_REF_PR_HEAD EVENT_REF_PR_MERGE
    EVENT_REF_BRANCH EVENT_REF_OTHER EVENT_PR_PRESENT
    CONFIG_INCLUDE_CREDENTIAL_ONLY_RECOGNIZED CONFIG_INCLUDE_OTHER
""".split())


class AcquisitionError(ValueError):
    def __init__(self, stage, category, observations=(), message='Exact-source acquisition rejected'):
        if stage not in STAGES or category not in CATEGORIES or any(item not in CATEGORIES for item in observations):
            raise ValueError('Invalid fixed diagnostic classification')
        super().__init__(message)
        self.stage, self.category = stage, category
        self.observations = tuple(sorted(set(observations)))

    def receipt(self):
        return 'ACQUISITION_DIAGNOSTIC ' + ' '.join((self.stage, self.category, *self.observations))


def no_symlink_path(path):
    path = Path(path).absolute()
    if '..' in path.parts:
        raise ValueError('Parent traversal forbidden')
    if any(item.is_symlink() for item in (path, *path.parents)):
        raise ValueError('Symlink path forbidden')
    return path


def host_scratch(environment):
    value = environment.get('RUNNER_TEMP')
    try:
        if not isinstance(value, str) or not value or not Path(value).is_absolute():
            raise ValueError('Invalid host scratch')
        path = no_symlink_path(value)
        if not path.is_dir():
            raise ValueError('Missing host scratch')
        return path
    except (ValueError, OSError) as error:
        raise AcquisitionError('SCRATCH_ROOT', 'SCRATCH_INVALID') from error


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
        self.includes = []
        self.mode = "normal"
        self.stage = "HOST_CONTEXT"
        self.observations = ()

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
                if self.includes:
                    # Original local includes cannot be disabled before Git reads
                    # them by appending -c values. Recognition is never admission.
                    raise AcquisitionError('CONFIG_POLICY', 'CONFIG_INCLUDE_REJECTED',
                                           ('CONFIG_INCLUDE_CREDENTIAL_ONLY_RECOGNIZED',))
                with tempfile.TemporaryDirectory(prefix='anonymous-git-', dir=host_scratch(self.environment)) as empty_home:
                    network_env = dict(self.env, HOME=empty_home, XDG_CONFIG_HOME=empty_home)
                    result = self.run(argv + list(args), env=network_env, capture_output=True,
                                      timeout=min(60, remaining), check=False)
            else:
                result = self.run(argv + list(args), env=self.env, capture_output=True,
                                  timeout=min(60, remaining), check=False)
        except subprocess.TimeoutExpired as error:
            raise AcquisitionError('ANONYMOUS_TRANSPORT' if args and args[0] in ('fetch', 'ls-remote') else self.stage,
                                   'TRANSPORT_TIMED_OUT', message='Git operation timed out; no credential diagnostics retained') from error
        except OSError as error:
            raise AcquisitionError('ANONYMOUS_TRANSPORT' if args and args[0] in ('fetch', 'ls-remote') else self.stage,
                                   'TRANSPORT_SETUP_FAILED', message='Git operation failed; no credential diagnostics retained') from error
        if result.returncode:
            # Git diagnostics can contain URL/config/header values. Fail without
            # forwarding stdout/stderr, argv or exception strings into build logs.
            raise AcquisitionError('ANONYMOUS_TRANSPORT' if args and args[0] in ('fetch', 'ls-remote') else self.stage,
                                   'TRANSPORT_REFUSED', message='Git operation refused; no alternate ref or retry')
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


def validate_config(entries, runner_temp, *, mode="normal", root=None):
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
            if mode != 'managed' or root is None:
                raise AcquisitionError('CONFIG_POLICY', 'CONFIG_INCLUDE_REJECTED', ('CONFIG_INCLUDE_OTHER',))
            expected = {('includeif.gitdir:' + item + '.path').lower() for item in (
                str(Path(root) / '.git'), str(Path(root) / '.git/worktrees/*'),
                '/github/workspace/.git', '/github/workspace/.git/worktrees/*')}
            path = Path(value)
            temporary = host_scratch({'RUNNER_TEMP': str(runner_temp)})
            if (key not in expected or not path.is_absolute() or path.parent != temporary
                    or not re.fullmatch(r'git-credentials-[A-Za-z0-9-]+\.config', path.name)):
                raise AcquisitionError('CONFIG_POLICY', 'CONFIG_INCLUDE_REJECTED', ('CONFIG_INCLUDE_OTHER',))
            no_symlink_path(path)
            if not path.is_file():
                raise AcquisitionError('CONFIG_POLICY', 'CONFIG_INCLUDE_REJECTED', ('CONFIG_INCLUDE_OTHER',))
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
    if includes:
        keys = [key for key, _ in entries if key.startswith('includeif.')]
        if len(keys) != 4 or len(set(keys)) != 4 or len(set(includes)) != 1:
            raise AcquisitionError('CONFIG_POLICY', 'CONFIG_INCLUDE_REJECTED', ('CONFIG_INCLUDE_OTHER',))
    return sorted(set(headers)), sorted(set(includes))


def inspect_config(git):
    entries = config_entries(git.call('config', '--local', '--no-includes', '--null', '--list'))
    headers, includes = validate_config(entries, host_scratch(git.environment), mode=git.mode, root=git.root)
    for path in includes:
        # Query names only with includes disabled. Credential values are neither
        # retrieved nor supplied to anonymous transport; hash original bytes only.
        keys = git.call('config', '--file', str(path), '--no-includes', '--null', '--name-only', '--list').decode().split('\0')
        keys = [key.lower() for key in keys if key]
        permitted = {'http.https://github.com/.extraheader', 'http.https://github.com/' + PUBLIC_REPOSITORY + '.extraheader',
                     'http.https://github.com/' + PUBLIC_REPOSITORY + '.git.extraheader'}
        if not keys or len(set(keys)) != len(keys) or not set(keys) <= permitted:
            raise AcquisitionError('CONFIG_POLICY', 'CONFIG_INCLUDE_REJECTED', ('CONFIG_INCLUDE_OTHER',))
        headers += keys
    git.includes = includes
    if includes:
        git.observations = (*git.observations, 'CONFIG_INCLUDE_CREDENTIAL_ONLY_RECOGNIZED')
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


def event_observations(environment, event):
    kind = {'pull_request': 'EVENT_KIND_PULL_REQUEST', 'push': 'EVENT_KIND_PUSH',
            'workflow_dispatch': 'EVENT_KIND_WORKFLOW_DISPATCH', 'dynamic': 'EVENT_KIND_DYNAMIC'}.get(environment.get('GITHUB_EVENT_NAME'), 'EVENT_KIND_OTHER')
    ref = environment.get('GITHUB_REF', '')
    ref_kind = ('EVENT_REF_PR_HEAD' if re.fullmatch(r'refs/pull/[1-9][0-9]*/head', ref) else
                'EVENT_REF_PR_MERGE' if re.fullmatch(r'refs/pull/[1-9][0-9]*/merge', ref) else
                'EVENT_REF_BRANCH' if ref.startswith('refs/heads/') else 'EVENT_REF_OTHER')
    pr = event.get('pull_request')
    present = isinstance(pr, dict) and bool(pr)
    relation = 'EVENT_PRODUCER_SHA_MATCHES_NEITHER'
    if present and environment.get('GITHUB_SHA') == pr.get('head', {}).get('sha'):
        relation = 'EVENT_PRODUCER_SHA_MATCHES_PR_HEAD'
    elif present and environment.get('GITHUB_SHA') == pr.get('merge_commit_sha'):
        relation = 'EVENT_PRODUCER_SHA_MATCHES_EVENT_MERGE'
    return (kind, ref_kind, 'EVENT_PR_PRESENT' if present else 'EVENT_PR_MISSING', relation)


def job_context(environment, root, head, *, mode='normal'):
    if mode not in ('normal', 'managed', 'fixture'):
        raise AcquisitionError('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
    require_sha(head)
    if environment.get('GITHUB_ACTIONS') != 'true':
        raise AcquisitionError('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
    for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB', 'GITHUB_EVENT_NAME', 'GITHUB_EVENT_PATH'):
        if not environment.get(key):
            raise AcquisitionError('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
    temporary = host_scratch(environment)
    if (not re.fullmatch(r'[1-9][0-9]*', environment['GITHUB_RUN_ID'])
            or not re.fullmatch(r'[1-9][0-9]*', environment['GITHUB_RUN_ATTEMPT'])
            or not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_-]*', environment['GITHUB_JOB'])):
        raise AcquisitionError('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
    if environment.get('GITHUB_REPOSITORY') != PUBLIC_REPOSITORY:
        raise AcquisitionError('EVENT_PAYLOAD', 'EVENT_PR_REPOSITORY_MISMATCH')
    try:
        event_bytes = Path(environment['GITHUB_EVENT_PATH']).read_bytes()
        event = json.loads(event_bytes)
        if not isinstance(event, dict):
            raise ValueError('Invalid event')
    except (ValueError, OSError) as error:
        raise AcquisitionError('EVENT_PAYLOAD', 'EVENT_PR_MISSING') from error
    try:
        observations = event_observations(environment, event)
    except (AttributeError, TypeError):
        raise AcquisitionError('EVENT_PAYLOAD', 'EVENT_PR_MISSING') from None
    def reject(stage, category):
        raise AcquisitionError(stage, category, observations)
    if event.get('repository', {}).get('full_name') != PUBLIC_REPOSITORY:
        reject('EVENT_PAYLOAD', 'EVENT_PR_REPOSITORY_MISMATCH')
    event_name = environment['GITHUB_EVENT_NAME']
    if event_name not in ('pull_request', 'push', 'workflow_dispatch'):
        reject('EVENT_KIND', 'EVENT_ROLE_UNSUPPORTED')
    try:
        producer_sha = require_sha(environment.get('GITHUB_SHA'))
    except ValueError:
        reject('EVENT_REF_SHA', 'CHECKOUT_HEAD_MISMATCH')
    ref = environment.get('GITHUB_REF', '')
    source_head, role, held = producer_sha, 'direct', False
    if event_name == 'pull_request':
        pr = event.get('pull_request')
        if not isinstance(pr, dict) or not pr:
            reject('EVENT_PAYLOAD', 'EVENT_PR_MISSING')
        number = pr.get('number')
        if type(number) is not int or number <= 0 or event.get('number') != number:
            reject('EVENT_PAYLOAD', 'EVENT_PR_MISSING')
        if pr.get('base', {}).get('repo', {}).get('full_name') != PUBLIC_REPOSITORY:
            reject('EVENT_PAYLOAD', 'EVENT_PR_REPOSITORY_MISMATCH')
        source_head = require_sha(pr.get('head', {}).get('sha'))
        if mode == 'managed':
            if not environment.get('CODEQL_EXTRACTOR_JAVA_TRAP_DIR', '').strip():
                reject('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
            if environment.get('CODE_SCANNING_REF') != 'refs/pull/' + str(number) + '/head':
                reject('EVENT_REF_SHA', 'EVENT_REF_MISMATCH')
            if head != source_head:
                reject('CHECKOUT_RELATION', 'CHECKOUT_HEAD_MISMATCH')
            role, held = 'managed_pr_head', True
            # No actual managed producer tuple has been adopted. These raw facts
            # are recognized for fixed diagnostics, never a success admission.
        else:
            if ref != 'refs/pull/' + str(number) + '/merge':
                reject('EVENT_REF_SHA', 'EVENT_REF_MISMATCH')
            if producer_sha == source_head or (mode != 'fixture' and head != producer_sha):
                reject('CHECKOUT_RELATION', 'CHECKOUT_HEAD_MISMATCH')
            role = 'normal_pr_merge'
    else:
        if event.get('pull_request') is not None:
            reject('EVENT_PAYLOAD', 'EVENT_ROLE_UNSUPPORTED')
        if not re.fullmatch(r'refs/heads/[A-Za-z0-9][A-Za-z0-9_./-]*', ref) or '..' in ref or '//' in ref or ref.endswith(('/', '.lock')):
            reject('EVENT_REF_SHA', 'EVENT_REF_MISMATCH')
        if event.get('ref') != ref:
            reject('EVENT_REF_SHA', 'EVENT_REF_MISMATCH')
        if event_name == 'push' and (ref != 'refs/heads/main' or event.get('after') != producer_sha
                                    or event.get('head_commit', {}).get('id') != producer_sha):
            reject('EVENT_REF_SHA', 'CHECKOUT_HEAD_MISMATCH')
        if mode != 'fixture' and head != producer_sha:
            reject('CHECKOUT_RELATION', 'CHECKOUT_HEAD_MISMATCH')
    if mode == 'fixture':
        if not root.is_relative_to(temporary):
            reject('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
        role = 'fixture'
    elif not environment.get('GITHUB_WORKSPACE') or Path(environment['GITHUB_WORKSPACE']).resolve() != root:
        reject('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
    return {key: environment[key] for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB')} | {
        'fixture': mode == 'fixture', 'mode': mode, 'checkoutRole': role, 'compiledCheckoutSha': head,
        'producerEventSha': producer_sha, 'producerEventRef': ref, 'eventName': event_name,
        'eventSha256': digest(event_bytes), 'sourceHeadSha': source_head, 'repository': PUBLIC_REPOSITORY,
        'scanningRef': environment.get('CODE_SCANNING_REF') if mode == 'managed' else None,
        'admissionHeld': held, 'observations': list(observations)}


def require_admission(context):
    if context['admissionHeld']:
        raise AcquisitionError('EVENT_REF_SHA', 'EVENT_ROLE_UNSUPPORTED',
                               (*context['observations'], 'EVENT_ROLE_MANAGED_PR_HEAD'))


def snapshot_path(root, environment):
    temporary = host_scratch(environment)
    key = digest(canonical([str(root), environment['GITHUB_RUN_ID'],
                            environment['GITHUB_RUN_ATTEMPT'], environment['GITHUB_JOB']]))
    return temporary / ('release-acquisition-' + key + '.json')


def validate_snapshot(git, observation, context):
    if set(observation) != {'schema', 'mode', 'context', 'state', 'tags', 'peeled', 'tagMapSha256'}:
        raise ValueError('Malformed acquisition observation')
    if type(observation['schema']) is not int or observation['schema'] != 1 or observation['mode'] not in ('managed', 'normal', 'fixture'):
        raise ValueError('Unsupported acquisition observation')
    require_admission(context)
    if observation['mode'] != context['mode']:
        raise ValueError("Observation mode differs from actual role")
    if canonical(observation['context']) != canonical(context) or canonical(observation['state']) != canonical(fingerprint(git)):
        raise ValueError('Stale acquisition source/index/config/job context')
    if digest(canonical(observation['tags'])) != observation['tagMapSha256']:
        raise ValueError('Tampered acquisition tag map')
    if verify_tags(git, observation['tags']) != observation['peeled']:
        raise ValueError('Acquired tag peeling changed')
    if context.get('checkoutRole') == 'normal_pr_merge':
        head = context['sourceHeadSha']
        if head != observation['state']['head']:
            parents = git.text('rev-list', '--parents', '-n', '1', 'HEAD').split()
            if head not in parents[1:]:
                raise ValueError('PR head does not belong to compiled merge')
    return observation


def read_snapshot(root, environment=None):
    git = Git(root, environment)
    # Observation mode is read without following links before config recognition.
    preliminary = read_observation(snapshot_path(git.root, git.environment))
    git.mode = preliminary.get("mode")
    inspect_config(git)
    head = require_sha(git.text('rev-parse', 'HEAD'))
    path = snapshot_path(git.root, git.environment)
    if not path.is_file() or path.is_symlink():
        raise ValueError('Missing producer acquisition; resolver never auto-fetches')
    observation = read_observation(path)
    git.mode = observation.get('mode')
    context = job_context(git.environment, git.root, head, mode=git.mode)
    require_admission(context)
    return git, validate_snapshot(git, observation, context)


def observation_parent(path):
    path = no_symlink_path(path)
    directory = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in path.parts[1:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        return directory, path.name
    except BaseException:
        os.close(directory)
        raise


def read_observation(path):
    directory, name = observation_parent(path)
    try:
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
    finally:
        os.close(directory)
    with os.fdopen(fd, 'rb') as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError('Observation must be a regular file')
        raw = stream.read(4 * 1024 * 1024 + 1)
    if len(raw) > 4 * 1024 * 1024:
        raise ValueError('Observation exceeds finite size')
    result = json.loads(raw)
    if not isinstance(result, dict):
        raise ValueError('Observation must be an object')
    return result


def publish_observation(path, observation):
    directory, name = observation_parent(path)
    try:
        # No ancestor or final link is followed, and existing entries cannot be
        # overwritten. Only a new internal scratch observation may be published.
        fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
    finally:
        os.close(directory)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(canonical(observation) + b'\n')


def prepare(root, mode, environment=None, run=subprocess.run):
    git = Git(root, environment, run)
    git.mode = mode
    try:
        return _prepare(git, mode)
    except AcquisitionError as error:
        raise AcquisitionError(error.stage, error.category, (*error.observations, *git.observations), str(error)) from error
    except (ValueError, KeyError, OSError, TypeError, AttributeError, json.JSONDecodeError) as error:
        category = {'CONFIG_POLICY': 'CONFIG_OTHER_UNSAFE', 'OBJECT_GRAPH': 'GRAPH_INCOMPLETE',
                    'RELEASE_TAG_MAP': 'TAG_MAP_MISMATCH', 'SNAPSHOT_REUSE': 'SNAPSHOT_STATE_OR_CONTEXT_MISMATCH',
                    'OBSERVATION_WRITE': 'SNAPSHOT_STATE_OR_CONTEXT_MISMATCH',
                    'PROTECTED_SOURCE_INDEX_STATE': 'SNAPSHOT_STATE_OR_CONTEXT_MISMATCH',
                    'CHECKOUT_RELATION': 'CHECKOUT_PARENT_MISMATCH'}.get(git.stage, 'EVENT_ROLE_UNSUPPORTED')
        raise AcquisitionError(git.stage, category, git.observations) from error


def _prepare(git, mode):
    if mode not in ('managed', 'normal', 'fixture'):
        raise ValueError('Unknown acquisition mode')
    if mode == 'managed' and not git.environment.get('CODEQL_EXTRACTOR_JAVA_TRAP_DIR', '').strip():
        raise ValueError('Nonempty extractor marker required before any preparation')
    # Check context before config/includes or any network operation.
    head = require_sha(git.text('rev-parse', 'HEAD'))
    git.stage = 'EVENT_PAYLOAD'
    context = job_context(git.environment, git.root, head, mode=mode)
    git.observations = tuple(context['observations'])
    git.stage = 'CHECKOUT_RELATION'
    if context['checkoutRole'] == 'normal_pr_merge':
        parents = re.findall(r'^parent ([0-9a-f]{40})$', git.text('cat-file', '-p', head), re.M)
        if context['sourceHeadSha'] not in parents:
            raise ValueError('PR head does not identify a captured merge parent')
    git.stage = 'CONFIG_POLICY'
    origin = inspect_config(git)
    require_admission(context)
    if mode != 'fixture' and origin not in (PUBLIC_URL, PUBLIC_URL[:-4]):
        raise ValueError('Only exact anonymous public checkout origin is supported')
    if mode == 'fixture':
        origin_path = Path(origin).resolve()
        if not origin_path.is_relative_to(Path(git.environment['RUNNER_TEMP']).resolve()):
            raise ValueError('Fixture origin must be isolated in runner temp')
        # Only explicit fixture acquisition may read a local ephemeral origin.
        git.env['GIT_ALLOW_PROTOCOL'] = 'file'
    git.stage = 'PROTECTED_SOURCE_INDEX_STATE'
    before = fingerprint(git)
    git.stage = 'OBJECT_GRAPH'
    reject_shortcuts(git)
    path = snapshot_path(git.root, git.environment)
    git.stage = 'SNAPSHOT_REUSE'
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise AcquisitionError('SNAPSHOT_REUSE', 'SNAPSHOT_STATE_OR_CONTEXT_MISMATCH')
    if path.exists():
        observation = read_observation(path)
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
    git.stage = 'RELEASE_TAG_MAP'
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
    git.stage = 'OBJECT_GRAPH'
    peeled = verify_tags(git, tags)
    git.stage = 'RELEASE_TAG_MAP'
    if advertisement() != tags:
        raise ValueError('Advertised release refs moved during acquisition')
    git.stage = 'PROTECTED_SOURCE_INDEX_STATE'
    after = fingerprint(git)
    if before != after:
        raise ValueError('Preparation changed protected source/index/config/ref state')
    observation = dict(schema=1, mode=mode, context=context, state=after, tags=tags,
                       peeled=peeled, tagMapSha256=digest(canonical(tags)))
    validate_snapshot(git, observation, context)
    git.stage = 'OBSERVATION_WRITE'
    publish_observation(path, observation)
    # Publication is inside the scratch boundary, and cannot hide a late drift.
    validate_snapshot(git, read_observation(path), context)
    return observation


class AcquisitionArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default error includes arbitrary supplied argv values.
        print(AcquisitionError('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED').receipt(), file=sys.stderr)
        raise SystemExit(2)


def main():
    parser = AcquisitionArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--mode', choices=('managed', 'normal', 'fixture'), default='managed')
    args = parser.parse_args()
    try:
        observation = prepare(args.repo, args.mode)
        print(json.dumps({'acquisition': observation['mode'], 'head': observation['state']['head'],
                          'tree': observation['state']['tree'], 'tagMapSha256': observation['tagMapSha256'],
                          'preservedSourceIndexConfig': True}, sort_keys=True))
    except AcquisitionError as error:
        print(error.receipt(), file=sys.stderr)
        raise SystemExit(1)
    except (ValueError, KeyError, OSError, TypeError, AttributeError, json.JSONDecodeError):
        # Deliberately do not emit exception/config/transport values.
        print('Exact-source acquisition failed; no fallback or credential diagnostics', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
