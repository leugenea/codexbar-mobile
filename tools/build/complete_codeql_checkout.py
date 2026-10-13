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
    EVENT_PRODUCER_SHA_MISSING EVENT_PRODUCER_SHA_INVALID EVENT_PRODUCER_SHA_MATCHES_COMPILED_HEAD EVENT_PRODUCER_SHA_DIFFERS_COMPILED_HEAD
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
        self.root = no_symlink_path(root)
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

    def call(self, *args, input=None):
        # Original dispatcher is local/read-only. No appended override can make
        # an original config/include context an anonymous network context.
        readers = ('config', 'rev-parse', 'ls-files', 'ls-tree', 'for-each-ref', 'rev-list', 'cat-file')
        if not args or args[0] not in readers or (args[0] == 'config' and '--list' not in args):
            raise AcquisitionError('CONFIG_POLICY', 'CONFIG_INCLUDE_REJECTED', self.observations)
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
            options = dict(env=self.env, capture_output=True, timeout=min(60, remaining), check=False)
            if input is not None:
                options['input'] = input
            result = self.run(argv + list(args), **options)
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
        if Path(relative).is_absolute() or '..' in Path(relative).parts:
            raise ValueError('Invalid tracked source path')
        file = no_symlink_path(git.root / relative)
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
    configs = {}
    for p in git.config_files:
        sha, size = _file_hash(p, MAX_METADATA)
        info = p.lstat()
        configs[str(no_symlink_path(p))] = dict(sha256=sha, size=size, mode=stat.S_IMODE(info.st_mode),
                                              device=info.st_dev, inode=info.st_ino)
    all_refs = git.text('for-each-ref', '--format=%(refname) %(objectname)')
    refs = '\n'.join(line for line in all_refs.splitlines() if not TAG.fullmatch(line.split()[0]))
    head_path = Path(git.text('rev-parse', '--git-path', 'HEAD'))
    if not head_path.is_absolute():
        head_path = git.root / head_path
    return dict(headFile=digest(head_path.read_bytes()), root=str(git.root), head=head, tree=tree, rawIndex=digest(index_path.read_bytes()),
                logicalIndex=digest(logical), trackedFiles=tracked, configs=configs,
                branch=head_path.read_text().strip(),
                nonTagRefs=digest(refs.encode()), gitMetadata=json.loads(canonical(_inventory(git.root/'.git'))))


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


def job_context(environment, root, head, *, mode='normal', _capture=None):
    if mode not in ('normal', 'managed', 'fixture'):
        raise AcquisitionError('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
    if _capture is None:
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
    # Private diagnostic capture is shallow-safe and occurs before unsupported
    # event rejection, but after actual host/event/ref input guards. It confers no
    # source role, publishes no failed observation and performs no acquisition.
    if mode == 'managed':
        if not environment.get('CODEQL_EXTRACTOR_JAVA_TRAP_DIR', '').strip():
            reject('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
        scanning = environment.get('CODE_SCANNING_REF')
        if not isinstance(scanning, str) or not re.fullmatch(r'refs/(?:pull/[1-9][0-9]*/head|heads/[A-Za-z0-9][A-Za-z0-9_./-]*)', scanning):
            reject('EVENT_REF_SHA', 'EVENT_REF_MISMATCH')
    if not isinstance(environment.get('GITHUB_REF', ''), str):
        reject('EVENT_REF_SHA', 'EVENT_REF_MISMATCH')
    if mode == 'fixture':
        if not root.is_relative_to(temporary):
            reject('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
    elif not environment.get('GITHUB_WORKSPACE') or no_symlink_path(environment['GITHUB_WORKSPACE']) != root:
        reject('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED')
    if _capture is not None:
        head = require_sha(_capture())
    raw_sha = environment.get('GITHUB_SHA')
    relation = ('EVENT_PRODUCER_SHA_MISSING' if raw_sha in (None, '') else
                'EVENT_PRODUCER_SHA_INVALID' if not isinstance(raw_sha, str) or not SHA.fullmatch(raw_sha) else
                'EVENT_PRODUCER_SHA_MATCHES_COMPILED_HEAD' if raw_sha == head else
                'EVENT_PRODUCER_SHA_DIFFERS_COMPILED_HEAD')
    observations = (*observations, relation)
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
        category = {'ANONYMOUS_TRANSPORT': 'TRANSPORT_SETUP_FAILED', 'CONFIG_POLICY': 'CONFIG_OTHER_UNSAFE', 'OBJECT_GRAPH': 'GRAPH_INCOMPLETE',
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
    # Host/event/ref validation precedes shallow-safe actual committed capture.
    # Unsupported dynamic tuples get one closed comparison, then refuse before
    # a successful context/snapshot or any outbound operation.
    git.stage = 'EVENT_PAYLOAD'
    def capture():
        git.stage = 'CONFIG_POLICY'
        captured_origin = inspect_config(git)
        if mode != 'fixture' and captured_origin not in (PUBLIC_URL, PUBLIC_URL[:-4]):
            raise ValueError('Only exact public source origin is supported')
        git.stage = 'PROTECTED_SOURCE_INDEX_STATE'
        head = require_sha(git.text('rev-parse', 'HEAD'))
        state = fingerprint(git)
        if state['head'] != head or fingerprint(git) != state:
            raise ValueError('Captured compiled source/index/config drift')
        return head
    context = job_context(git.environment, git.root, None, mode=mode, _capture=capture)
    head = context['compiledCheckoutSha']
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
    git.stage = 'OBJECT_GRAPH'
    store = _SourceStore(git)
    try:
        git.stage = 'ANONYMOUS_TRANSPORT'
        transport = _PrivateTransport(git, fixture_origin=origin if mode == 'fixture' else None)
        try:
            git.stage = 'RELEASE_TAG_MAP'
            tags = transport.advertise('ADVERTISEMENT_BEFORE')
            existing = local_tags(git)
            if any(ref not in tags or tags[ref] != oid for ref, oid in existing.items()):
                raise ValueError('Conflicting or unexpected local release refs')
            if mode == 'managed':
                git.stage = 'OBJECT_GRAPH'
                peeled = _ingest(git, transport, store, head, tags, existing, context)
            else:
                # Full normal/ephemeral checkouts must already be complete; no
                # implicit repair or original object negotiation is performed.
                git.stage = 'OBJECT_GRAPH'
                peeled = verify_tags(git, tags)
                git.stage = 'RELEASE_TAG_MAP'
                if transport.advertise('ADVERTISEMENT_AFTER') != tags:
                    raise ValueError('Advertised release refs moved')
                store.protected()
        finally:
            transport.close()
        store.cleanup()
        git.stage = 'PROTECTED_SOURCE_INDEX_STATE'
        after = fingerprint(git)
        if {k:v for k,v in before.items() if k != 'gitMetadata'} != {k:v for k,v in after.items() if k != 'gitMetadata'}:
            raise ValueError('Preparation changed protected source/index/config/ref state')
        store.protected()
        _context_still_current(git, context)
        observation = dict(schema=1, mode=mode, context=context, state=after, tags=tags,
                           peeled=peeled, tagMapSha256=digest(canonical(tags)))
        validate_snapshot(git, observation, context)
        git.stage = 'OBSERVATION_WRITE'
        publish_observation(path, observation)
        # Observation creation cannot authorize a late source/config/auth drift.
        store.protected()
        _context_still_current(git, context)
        validate_snapshot(git, read_observation(path), context)
        return observation
    finally:
        try:
            store.cleanup()
        finally:
            store.close_descriptors()


# Private transport has no original Git metadata or inherited authentication
# inputs. Local readers may parse the original includes; NETWORK children cannot.
TRANSPORT_OVERRIDES = (
    'credential.helper=', 'core.askPass=/bin/false', 'core.fsmonitor=false',
    'core.hooksPath=/dev/null', 'core.commitGraph=false', 'http.extraHeader=',
    'http.followRedirects=false', 'http.proxy=', 'http.cookieFile=',
    'http.saveCookies=false', 'http.sslVerify=true', 'http.sslCertPasswordProtected=false',
    'gc.auto=0', 'core.logAllRefUpdates=false', 'maintenance.auto=false',
    'fetch.writeCommitGraph=false', 'protocol.allow=never', 'protocol.https.allow=always')
MAX_PACK = 256 * 1024 * 1024
MAX_SCRATCH = 2 * 1024 * 1024 * 1024
MAX_OBJECT = 32 * 1024 * 1024
MAX_INFLATED = 2 * 1024 * 1024 * 1024
MAX_METADATA = 4 * 1024 * 1024
MAX_OBJECTS = 1000000


def _same_file(left, right):
    return all(getattr(left, key) == getattr(right, key) for key in
               ('st_dev', 'st_ino', 'st_mode', 'st_nlink', 'st_uid', 'st_gid', 'st_size', 'st_mtime_ns', 'st_ctime_ns'))


def _regular(path, limit=None):
    path = no_symlink_path(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or (limit is not None and info.st_size > limit):
            raise ValueError('Unsupported regular nonshared file')
        data = bytearray()
        while True:
            part = os.read(fd, 1024 * 1024)
            if not part:
                break
            data.extend(part)
            if limit is not None and len(data) > limit:
                raise ValueError('File size limit')
        if not _same_file(os.fstat(fd), info) or not _same_file(os.stat(path, follow_symlinks=False), info):
            raise ValueError('File changed during read')
        return bytes(data), (info.st_dev, info.st_ino, stat.S_IMODE(info.st_mode))
    finally:
        os.close(fd)


def _file_hash(path, limit=MAX_PACK):
    # Raw byte/trailer binding, not a pack parser; native index-pack validates
    # structure, object hashes, delta closure and strict object constraints.
    fd = os.open(no_symlink_path(path), os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > limit:
            raise ValueError('Unsupported bounded material')
        result = hashlib.sha256()
        while part := os.read(fd, 1024 * 1024):
            result.update(part)
        if not _same_file(os.fstat(fd), info) or not _same_file(os.stat(path, follow_symlinks=False), info):
            raise ValueError('Material changed')
        return result.hexdigest(), info.st_size
    finally:
        os.close(fd)


def _directory(path):
    # Retain a descriptor and identity, then compare the path at every boundary.
    parent, name = observation_parent(Path(path) / '.anchor')
    return parent, os.fstat(parent)


def _anchor(path, fd, captured):
    current = os.fstat(fd)
    visible = os.stat(path, follow_symlinks=False)
    if ((current.st_dev, current.st_ino) != (captured.st_dev, captured.st_ino)
            or (visible.st_dev, visible.st_ino) != (captured.st_dev, captured.st_ino)
            or not stat.S_ISDIR(visible.st_mode)):
        raise ValueError('Directory anchor drift')
    no_symlink_path(path)


def _inventory(path):
    """No-follow metadata inventory, including inode/mode and every file byte."""
    result, recorded = {}, 2
    def retain(relative, row):
        nonlocal recorded
        recorded += len(canonical(relative)) + len(canonical(row)) + 2
        if recorded > MAX_METADATA or len(result) >= MAX_OBJECTS:
            raise ValueError('Protected metadata inventory budget')
        result[relative] = row
    def visit(directory, prefix):
        fd, captured = _directory(directory)
        try:
            for name in sorted(os.listdir(fd)):
                relative = prefix + name
                info = os.stat(name, dir_fd=fd, follow_symlinks=False)
                target = directory / name
                if stat.S_ISDIR(info.st_mode):
                    retain(relative, ('directory', info.st_dev, info.st_ino, stat.S_IMODE(info.st_mode)))
                    visit(target, relative + '/')
                elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
                    sha, size = _file_hash(target, MAX_SCRATCH)
                    retain(relative, ('file', info.st_dev, info.st_ino, stat.S_IMODE(info.st_mode), size, sha))
                else:
                    raise ValueError('Linked/shared/nonregular metadata')
            _anchor(directory, fd, captured)
        finally:
            os.close(fd)
    visit(Path(path), '')
    return result


class _PrivateTransport:
    """Exactly two descriptor-anchored owned stores. Never an original dispatcher.

    Observer is an internal hosted-test boundary, not an admission or CLI option.
    The injected subprocess boundary is for PURE offline transcripts only.
    """
    def __init__(self, git, *, fixture_origin=None, observer=None):
        self.git, self.observer = git, observer
        self.destination = PUBLIC_URL if fixture_origin is None else str(fixture_origin)
        self.fixture = fixture_origin is not None
        temporary = host_scratch(git.environment)
        if temporary.is_relative_to(git.root) or (self.fixture and (git.mode != 'fixture' or not no_symlink_path(self.destination).is_relative_to(temporary))):
            raise ValueError('Private transport scratch/origin boundary')
        self.parent_fd, self.parent_anchor = _directory(temporary)
        self.parent = temporary
        name = 'release-transport-' + os.urandom(16).hex()
        os.mkdir(name, 0o700, dir_fd=self.parent_fd)
        self.root = temporary / name
        self.root_fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=self.parent_fd)
        self.root_anchor = os.fstat(self.root_fd)
        self.closed = False
        self.stores = {}
        self.files = 0
        self.material = {}
        try:
            for name in ('home', 'template', 'T', 'V'):
                os.mkdir(name, 0o700, dir_fd=self.root_fd)
            self.env = safe_environment({'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C'})
            self.env.update(HOME=str(self.root/'home'), XDG_CONFIG_HOME=str(self.root/'home'))
            if self.fixture:
                self.env['GIT_ALLOW_PROTOCOL'] = 'file'
            self.assert_anchors()
            # The runtime primitive is closed to the observed/native source
            # version, not inferred from documentation or quietly upgraded.
            if self.call('T', 'VERSION', '--version').decode().strip() != 'git version 2.55.0':
                raise AcquisitionError('ANONYMOUS_TRANSPORT', 'TRANSPORT_SETUP_FAILED')
            for repo, phase in (('T', 'INIT_TRANSPORT'), ('V', 'INIT_VALIDATOR')):
                self.call(repo, phase, 'init', '--bare', '--object-format=sha1', '--ref-format=files',
                          '--initial-branch=acquisition', '--template=' + str(self.root/'template'), str(self.root/repo))
                fd, anchor = _directory(self.root/repo)
                self.stores[repo] = (fd, anchor)
                entries = config_entries(self.call(repo, 'READ', 'config', '--local', '--no-includes', '--null', '--list'))
                if dict(entries) != {'core.repositoryformatversion': '0', 'core.filemode': 'true', 'core.bare': 'true'} or len(entries) != 3:
                    raise ValueError('Unknown fresh bare metadata')
                self.material[repo] = {name: _regular(self.root/repo/name, MAX_METADATA) for name in ('config', 'HEAD')}
                if self.call(repo, 'READ', 'rev-parse', '--show-object-format').strip() != b'sha1' or self.call(repo, 'READ', 'rev-parse', '--show-ref-format').strip() != b'files':
                    raise ValueError('Unsupported private storage format')
            if self.call('V', 'READ', 'cat-file', '--batch-check', '--batch-all-objects').strip():
                raise ValueError('Validator object store is not initially empty')
        except BaseException:
            self.close()
            raise

    def assert_anchors(self):
        _anchor(self.parent, self.parent_fd, self.parent_anchor)
        _anchor(self.root, self.root_fd, self.root_anchor)
        if stat.S_IMODE(os.fstat(self.root_fd).st_mode) != 0o700 or os.fstat(self.root_fd).st_uid != os.getuid():
            raise ValueError('Unowned private transport tree')
        for repo, (fd, info) in self.stores.items():
            _anchor(self.root/repo, fd, info)
        for empty in ('home', 'template'):
            if list((self.root/empty).iterdir()):
                raise ValueError('Private home/template not empty')
        for repo in self.stores:
            for relative in ('shallow', 'info/grafts', 'objects/info/alternates', 'objects/info/http-alternates', 'refs/replace'):
                if os.path.lexists(self.root/repo/relative):
                    raise ValueError('Private graph shortcut')
            if list((self.root/repo/'objects/pack').glob('*.promisor')):
                raise ValueError('Private promisor pack')
        for repo, files in self.material.items():
            for name, material in files.items():
                if _regular(self.root/repo/name, MAX_METADATA) != material:
                    raise ValueError('Private metadata drift')

    def disk_budget(self):
        total = 0
        for directory, dirs, files in os.walk(self.root, followlinks=False):
            for name in dirs + files:
                info = os.stat(Path(directory)/name, follow_symlinks=False)
                if not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
                    raise ValueError('Unknown private material')
                if name.endswith('.pack') and info.st_size > MAX_PACK:
                    raise ValueError('Compressed pack limit')
                total += info.st_size if stat.S_ISREG(info.st_mode) else 0
                if total > MAX_SCRATCH:
                    raise ValueError('Monitored private disk limit')
        if time.monotonic() >= self.git.deadline:
            raise ValueError('Acquisition total deadline')

    def _execute(self, argv, options, output):
        if self.git.run is not subprocess.run:
            result = self.git.run(argv, **options, capture_output=True)
            if len(result.stdout) > MAX_PACK or len(result.stderr) > MAX_METADATA:
                raise ValueError('Injected output limit')
            if output is not None:
                output.write_bytes(result.stdout)
            return result.returncode, result.stdout
        # Native stdout/stderr never accumulate unbounded in Python pipes. Poll
        # bounded private regular files/disk; overshoot and native transient heap
        # are not claimed as hard ceilings. No inherited auth/config env is used.
        self.files += 1
        out_path = output or self.root/('_io-' + str(self.files) + '.out')
        err_path = self.root/('_io-' + str(self.files) + '.err')
        in_path = self.root/('_io-' + str(self.files) + '.in')
        fds, identities = [], {}
        try:
            for path in (out_path, err_path, in_path):
                fds.append(os.open(path, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600))
                info = os.fstat(fds[-1]); identities[path] = (info.st_dev, info.st_ino)
            with os.fdopen(os.dup(fds[2]), 'wb') as source:
                source.write(options.get('input') or b'')
                source.flush()
            os.lseek(fds[2], 0, os.SEEK_SET)
            started = time.monotonic()
            with subprocess.Popen(argv, cwd=options['cwd'], env=options['env'], stdin=fds[2], stdout=fds[0], stderr=fds[1]) as child:
                try:
                    while child.poll() is None:
                        self.assert_anchors()
                        self.disk_budget()
                        if (os.fstat(fds[0]).st_size > MAX_PACK or os.fstat(fds[1]).st_size > MAX_METADATA):
                            raise ValueError('Private output limit')
                        if time.monotonic() - started >= options['timeout']:
                            raise subprocess.TimeoutExpired(argv, options['timeout'])
                        time.sleep(0.02)
                except BaseException:
                    child.kill()
                    child.wait()
                    raise
            if os.fstat(fds[0]).st_size > MAX_PACK or os.fstat(fds[1]).st_size > MAX_METADATA:
                raise ValueError('Private output limit')
            data = b''
            if output is None:
                os.lseek(fds[0], 0, os.SEEK_SET)
                data = os.read(fds[0], MAX_PACK + 1)
            return child.returncode, data
        finally:
            for fd in fds:
                os.close(fd)
            for path in (err_path, in_path, out_path if output is None else None):
                if path is not None and path in identities:
                    info = path.lstat()
                    if not stat.S_ISREG(info.st_mode) or (info.st_dev, info.st_ino) != identities[path]:
                        raise ValueError('Private output cleanup refused')
                    path.unlink()

    def call(self, repo, phase, *args, input=None, output=None):
        self.assert_anchors()
        if repo not in ('T', 'V'):
            raise ValueError('Unknown private store')
        command = args[0] if args else None
        phases = {'VERSION': '--version', 'INIT_TRANSPORT': 'init', 'INIT_VALIDATOR': 'init',
                  'ADVERTISEMENT_BEFORE': 'ls-remote', 'ADVERTISEMENT_AFTER': 'ls-remote',
                  'FETCH_EXACT': 'fetch', 'EXPORT': 'pack-objects', 'INDEX': 'index-pack'}
        if phase in phases:
            if command != phases[phase]:
                raise ValueError('Unexpected private operation')
        elif phase != 'READ' or command not in ('config', 'rev-parse', 'rev-list', 'for-each-ref', 'cat-file'):
            raise ValueError('Unexpected private reader')
        self.validate_operation(repo, phase, args, input, output)
        overrides = list(TRANSPORT_OVERRIDES)
        if self.fixture:
            overrides += ['protocol.file.allow=always']
        if phase == 'FETCH_EXACT':
            overrides += ['fetch.fsckObjects=true', 'fetch.unpackLimit=0']
        directory = str(self.root/repo)
        argv = ['git', '-C', directory, '--git-dir=' + directory]
        for override in overrides:
            argv += ['-c', override]
        argv += list(args)
        remaining = self.git.deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('Acquisition total deadline')
        options = dict(env=self.env.copy(), cwd=directory, timeout=min(60, remaining), check=False)
        if input is not None:
            options['input'] = input
        watched = phase in ('INIT_TRANSPORT', 'INIT_VALIDATOR', 'ADVERTISEMENT_BEFORE', 'FETCH_EXACT', 'ADVERTISEMENT_AFTER')
        if self.observer is not None and watched:
            self.observer.arm(phase)
        try:
            code, data = self._execute(argv, options, output)
        except subprocess.TimeoutExpired:
            raise AcquisitionError('ANONYMOUS_TRANSPORT', 'TRANSPORT_TIMED_OUT') from None
        except OSError:
            raise AcquisitionError('ANONYMOUS_TRANSPORT', 'TRANSPORT_SETUP_FAILED') from None
        finally:
            if self.observer is not None and watched:
                self.observer.drain(phase)
        self.assert_anchors()
        self.disk_budget()
        if code:
            raise AcquisitionError('ANONYMOUS_TRANSPORT' if watched else 'OBJECT_GRAPH', 'TRANSPORT_REFUSED')
        return data

    def validate_operation(self, repo, phase, args, input, output):
        # Narrow internal operation domain: callers cannot introduce original
        # config files, moving refs, filters, transport rewrites or arbitrary Git.
        valid = False
        if phase == 'VERSION':
            valid = args == ('--version',)
        elif phase in ('INIT_TRANSPORT', 'INIT_VALIDATOR'):
            valid = args == ('init', '--bare', '--object-format=sha1', '--ref-format=files',
                             '--initial-branch=acquisition', '--template=' + str(self.root/'template'), str(self.root/repo))
        elif phase.startswith('ADVERTISEMENT_'):
            valid = repo == 'T' and args == ('ls-remote', '--tags', '--refs', self.destination)
        elif phase == 'FETCH_EXACT':
            prefix = ('fetch', '--atomic', '--no-tags', '--no-prune', '--no-write-fetch-head', '--no-auto-maintenance',
                      '--no-write-commit-graph', '--no-recurse-submodules', '--refmap=', self.destination)
            refs = args[len(prefix):]
            valid = (repo == 'T' and args[:len(prefix)] == prefix and 1 <= len(refs) <= 1025
                     and re.fullmatch(r'[0-9a-f]{40}:refs/acquisition/source', refs[0]) is not None
                     and all(re.fullmatch(r'[0-9a-f]{40}:refs/acquisition/tags/' + str(i).zfill(6), ref)
                             for i, ref in enumerate(refs[1:], 1)))
        elif phase == 'EXPORT':
            valid = repo == 'T' and args == ('pack-objects', '--stdout', '-q', '--no-reuse-object', '--window=10',
                                             '--depth=50', '--window-memory=32m', '--threads=1')
        elif phase == 'INDEX':
            if len(args) == 10:
                pack, index = Path(args[-1]), Path(args[-2])
                valid = (repo == 'V' and args[:8] == ('index-pack', '--strict', '--check-self-contained-and-connected',
                         '--no-rev-index', '--threads=1', '--max-input-size=' + str(MAX_PACK), '--object-format=sha1', '-o')
                         and pack.parent == self.root/'V/objects/pack' and index == pack.with_suffix('.idx')
                         and re.fullmatch(r'pack-[0-9a-f]{40}\.pack', pack.name) is not None)
        elif phase == 'READ':
            valid = (args == ('config', '--local', '--no-includes', '--null', '--list')
                     or args in (('rev-parse', '--show-object-format'), ('rev-parse', '--show-ref-format'),
                                 ('rev-parse', '--is-shallow-repository'), ('cat-file', '--batch-check', '--batch-all-objects'))
                     or (len(args) == 2 and args[0] == 'rev-parse' and re.fullmatch(r'[0-9a-f]{40}\^\{tree\}', args[1]))
                     or (len(args) == 3 and args[0] == 'cat-file' and args[1] in ('-t', 'commit', 'tree', 'blob', 'tag') and SHA.fullmatch(args[2]))
                     or args in (('cat-file', '--batch'), ('cat-file', '--batch-check'))
                     or (args[:4] == ('rev-list', '--objects', '--no-object-names', '--missing=print') and len(args) > 4 and all(SHA.fullmatch(a) for a in args[4:]))
                     or (args[:2] == ('rev-list', '--parents') and len(args) > 2 and all(SHA.fullmatch(a) for a in args[2:])))
        if input is not None:
            ids = input.decode('ascii').splitlines()
            valid = valid and (phase == 'EXPORT' or args in (('cat-file', '--batch'), ('cat-file', '--batch-check')))
            valid = valid and bool(ids) and len(ids) <= MAX_OBJECTS and ids == sorted(set(ids)) and all(SHA.fullmatch(x) for x in ids)
        if output is not None:
            valid = False  # No arbitrary output path interface in the selected calls.
        if not valid:
            raise ValueError('Unexpected private command/input')

    def advertise(self, phase):
        raw = self.call('T', phase, 'ls-remote', '--tags', '--refs', self.destination)
        if len(raw) > 1024 * 1024:
            raise ValueError('Advertisement limit')
        tags = release_refs(raw.decode('utf-8'))
        if len(tags) > 1024:
            raise ValueError('Advertised ref limit')
        return tags

    def fetch(self, head, tags):
        refs = [require_sha(head) + ':refs/acquisition/source']
        refs += [require_sha(oid) + ':refs/acquisition/tags/' + str(i).zfill(6)
                 for i, oid in enumerate(tags.values(), 1)]
        self.call('T', 'FETCH_EXACT', 'fetch', '--atomic', '--no-tags', '--no-prune', '--no-write-fetch-head',
                  '--no-auto-maintenance', '--no-write-commit-graph', '--no-recurse-submodules', '--refmap=',
                  self.destination, *refs)

    def close(self):
        if self.closed:
            return
        self.assert_anchors()
        # Owned private tree only. Refuse links/unknown material, and retain an
        # honest cleanup-refusal residual rather than traversing a substituted tree.
        def remove(directory):
            fd, anchor = _directory(directory)
            try:
                for name in os.listdir(fd):
                    info = os.stat(name, dir_fd=fd, follow_symlinks=False)
                    if stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid():
                        remove(directory/name)
                        os.rmdir(name, dir_fd=fd)
                    elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid():
                        os.unlink(name, dir_fd=fd)
                    else:
                        raise ValueError('Private cleanup refused')
                _anchor(directory, fd, anchor)
            finally:
                os.close(fd)
        self.material.clear()
        remove(self.root)
        _anchor(self.root, self.root_fd, self.root_anchor)
        _anchor(self.parent, self.parent_fd, self.parent_anchor)
        os.rmdir(self.root.name, dir_fd=self.parent_fd)
        for fd, _ in self.stores.values():
            os.close(fd)
        os.close(self.root_fd)
        os.close(self.parent_fd)
        self.closed = True


class _PrivateReader:
    def __init__(self, transport, repo):
        self.transport, self.repo = transport, repo
    def call(self, *args, input=None):
        return self.transport.call(self.repo, 'READ', *args, input=input)
    def text(self, *args):
        return self.call(*args).decode().strip()


def _object_metadata(reader, ids=None):
    args = ('cat-file', '--batch-check', '--batch-all-objects') if ids is None else ('cat-file', '--batch-check')
    raw = reader.call(*args, **({} if ids is None else {'input': ''.join(x + '\n' for x in sorted(ids)).encode()}))
    if len(raw) > MAX_METADATA:
        raise ValueError('Object metadata limit')
    result, total = {}, 0
    for line in raw.decode().splitlines():
        match = re.fullmatch(r'([0-9a-f]{40}) (commit|tree|blob|tag) ([0-9]+)', line)
        if not match or match[1] in result:
            raise ValueError('Missing/duplicate/unknown object metadata')
        size = int(match[3])
        total += size
        if size > MAX_OBJECT or total > MAX_INFLATED or len(result) >= MAX_OBJECTS:
            raise ValueError('Inflated object admission limit')
        result[match[1]] = (match[2], size)
    if not result or (ids is not None and set(result) != set(ids)):
        raise ValueError('Object metadata membership differs')
    return result


def _object_contents(reader, metadata):
    # Batched raw object IDs only. No filters/textconv, no path expressions.
    result, pending, size = {}, [], 0
    def consume(ids):
        raw = reader.call('cat-file', '--batch', input=''.join(x + '\n' for x in ids).encode())
        position = 0
        for oid in ids:
            end = raw.find(b'\n', position)
            kind, count = metadata[oid]
            if end < 0 or raw[position:end] != f'{oid} {kind} {count}'.encode():
                raise ValueError('Raw object header mismatch')
            data = raw[end + 1:end + 1 + count]
            position = end + 2 + count
            if len(data) != count or raw[position - 1:position] != b'\n' or hashlib.sha1(f'{kind} {count}\0'.encode() + data).hexdigest() != oid:
                raise ValueError('Raw object content/hash mismatch')
            result[oid] = (kind, count, digest(data))
        if position != len(raw):
            raise ValueError('Unexpected raw object output')
    for oid, (_, count) in sorted(metadata.items()):
        if pending and size + count + 80 > 64 * 1024 * 1024:
            consume(pending)
            pending, size = [], 0
        pending.append(oid)
        size += count + 80
    if pending:
        consume(pending)
    return result


def _closure(reader, head, tags):
    require_sha(head)
    if reader.text('rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Private history is shallow')
    roots, annotations, peeled = [head], set(), {}
    for ref, raw in tags.items():
        oid = raw
        chain = set()
        for _ in range(16):
            require_sha(oid)
            if oid in chain:
                raise ValueError('Annotation cycle')
            chain.add(oid)
            kind = reader.text('cat-file', '-t', oid)
            if kind == 'commit':
                peeled[ref] = oid
                roots.append(oid)
                break
            if kind != 'tag':
                raise ValueError('Release tag is not a commit annotation')
            annotations.add(oid)
            content = reader.call('cat-file', 'tag', oid)
            if len(content) > MAX_OBJECT:
                raise ValueError('Annotation size limit')
            target = re.findall(rb'^object ([0-9a-f]{40})$', content, re.M)
            if len(target) != 1:
                raise ValueError('Malformed raw annotation')
            oid = target[0].decode()
        else:
            raise ValueError('Annotation chain limit')
    raw = reader.text('rev-list', '--objects', '--no-object-names', '--missing=print', *roots)
    if len(raw.encode()) > MAX_METADATA:
        raise ValueError('Closure metadata limit')
    ids = raw.splitlines()
    if not ids or len(ids) > MAX_OBJECTS or any(not SHA.fullmatch(oid) for oid in ids):
        raise ValueError('Incomplete exact object closure')
    ids = set(ids) | annotations
    metadata = _object_metadata(reader, ids)
    if metadata[head][0] != 'commit' or any(metadata[x][0] != 'commit' for x in roots):
        raise ValueError('Root object type mismatch')
    parents = reader.text('rev-list', '--parents', *roots).splitlines()
    commits = {line.split()[0] for line in parents}
    if not commits or any(not set(line.split()[1:]) <= commits or not all(SHA.fullmatch(x) for x in line.split()) for line in parents):
        raise ValueError('Incomplete full parent graph')
    contents = _object_contents(reader, metadata)
    tree = require_sha(reader.text('rev-parse', head + '^{tree}'))
    if tree not in metadata or metadata[tree][0] != 'tree':
        raise ValueError('Root tree missing')
    return dict(head=head, tree=tree, objects=contents, peeled=peeled)


def _validated_pack(transport, closure):
    data = transport.call('T', 'EXPORT', 'pack-objects', '--stdout', '-q', '--no-reuse-object',
                          '--window=10', '--depth=50', '--window-memory=32m', '--threads=1',
                          input=''.join(oid + '\n' for oid in sorted(closure['objects'])).encode())
    if len(data) < 32 or len(data) > MAX_PACK or hashlib.sha1(data[:-20]).digest() != data[-20:]:
        raise ValueError('Exported pack byte/trailer binding')
    pack_id = data[-20:].hex()
    pack = transport.root/'V/objects/pack'/('pack-' + pack_id + '.pack')
    fd = os.open(pack, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
    del data
    index = pack.with_suffix('.idx')
    output = transport.call('V', 'INDEX', 'index-pack', '--strict', '--check-self-contained-and-connected',
                            '--no-rev-index', '--threads=1', '--max-input-size=' + str(MAX_PACK), '--object-format=sha1',
                            '-o', str(index), str(pack))
    if output.decode().strip() != pack_id:
        raise ValueError('Native pack identity mismatch')
    index_bytes, _ = _regular(index, MAX_PACK)
    if len(index_bytes) < 40 or index_bytes[-40:-20].hex() != pack_id or hashlib.sha1(index_bytes[:-20]).digest() != index_bytes[-20:]:
        raise ValueError('Index/pack trailer binding mismatch')
    validator = _PrivateReader(transport, 'V')
    actual = _object_metadata(validator)
    if set(actual) != set(closure['objects']) or _object_contents(validator, actual) != closure['objects']:
        raise ValueError('Independent exact object membership/content differs')
    verified = _closure(validator, closure['head'], transport.tags)
    if verified != closure:
        raise ValueError('Independent closure/root/tree/peeling differs')
    return pack, index


class _SourceStore:
    """Original repository is read by Git, published only through owned descriptors.

    Final effects are monotonic; a late refusal never deletes final objects/refs.
    Owned temporary cleanup is inode checked, with no lock stealing or repair.
    """
    def __init__(self, git):
        self.git, self.owned, self.allowed, self.removed = git, [], {}, set()
        self.path = no_symlink_path(git.root/'.git')
        if not self.path.is_dir():
            raise ValueError('Nonshared regular files Git layout required')
        self.fd, self.info = _directory(self.path)
        self.directories = {}
        try:
            common = Path(git.text('rev-parse', '--git-common-dir'))
            common = common if common.is_absolute() else git.root/common
            if no_symlink_path(common) != self.path:
                raise ValueError('Shared/common Git layout forbidden')
            if git.text('rev-parse', '--show-object-format') != 'sha1' or git.text('rev-parse', '--show-ref-format') != 'files':
                raise ValueError('Unsupported source object/ref format')
            entries = config_entries(git.call('config', '--local', '--no-includes', '--null', '--list'))
            if any(key.startswith('extensions.') or key == 'core.sharedrepository' for key, _ in entries):
                raise ValueError('Unsupported source layout extension')
            for relative in ('objects', 'objects/pack', 'refs', 'refs/tags'):
                self.directories[relative] = _directory(self.path/relative)
            self.initial = _inventory(self.path)
            self.source = {k:v for k,v in fingerprint(git).items() if k != 'gitMetadata'}
            if any(name.endswith(('.lock', '.promisor')) for name in self.initial):
                raise ValueError('Active lock or partial source storage')
            self.shallow = self.initial.get('shallow')
            self.boundaries = set()
            if self.shallow is not None:
                raw, _ = _regular(self.path/'shallow', MAX_METADATA)
                lines = raw.decode().splitlines()
                if not lines or len(set(lines)) != len(lines) or any(not SHA.fullmatch(x) for x in lines):
                    raise ValueError('Unknown captured shallow boundary')
                self.boundaries = set(lines)
        except BaseException:
            self.close_descriptors()
            raise

    def anchors(self):
        _anchor(self.path, self.fd, self.info)
        for relative, (fd, captured) in self.directories.items():
            _anchor(self.path/relative, fd, captured)

    def protected(self):
        self.anchors()
        actual = _inventory(self.path)
        expected = self.initial | self.allowed
        for relative in self.removed:
            expected.pop(relative)
        # Temporary files have an explicit inode-bound owned set; they cannot
        # authorize unrelated files or override any original protected entry.
        for relative, inode in self.owned:
            row = actual.pop(relative, None)
            if row is None or row[0] != 'file' or row[1:3] != inode:
                raise ValueError('Owned source temporary drift')
        if actual != expected or {k:v for k,v in fingerprint(self.git).items() if k != 'gitMetadata'} != self.source:
            raise ValueError('Forbidden original metadata/source/index/auth effect')
        self.anchors()

    def create(self, relative, data, mode):
        self.anchors()
        parent = str(Path(relative).parent)
        directory = self.fd if parent == '.' else self.directories[parent][0]
        fd = os.open(Path(relative).name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode, dir_fd=directory)
        info = os.fstat(fd)
        self.owned.append((relative, (info.st_dev, info.st_ino)))
        try:
            os.fchmod(fd, mode)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        except BaseException:
            raise
        return relative

    def unlink_owned(self, relative):
        inode = dict(self.owned)[relative]
        parent = str(Path(relative).parent)
        directory = self.fd if parent == '.' else self.directories[parent][0]
        info = os.stat(Path(relative).name, dir_fd=directory, follow_symlinks=False)
        if not stat.S_ISREG(info.st_mode) or (info.st_dev, info.st_ino) != inode:
            raise ValueError('Source cleanup refused')
        os.unlink(Path(relative).name, dir_fd=directory)
        self.owned.remove((relative, inode))

    def cleanup(self):
        self.anchors()
        for relative, _ in list(reversed(self.owned)):
            self.unlink_owned(relative)
        self.protected()

    def close_descriptors(self):
        for fd, _ in self.directories.values():
            os.close(fd)
        os.close(self.fd)

    def link_final(self, stage, target):
        self.anchors()
        parent = str(Path(target).parent)
        directory = self.fd if parent == '.' else self.directories[parent][0]
        inode = dict(self.owned)[stage]
        current = os.stat(Path(stage).name, dir_fd=directory, follow_symlinks=False)
        if not stat.S_ISREG(current.st_mode) or (current.st_dev, current.st_ino) != inode:
            raise ValueError('Staging substitution')
        os.link(Path(stage).name, Path(target).name, src_dir_fd=directory, dst_dir_fd=directory, follow_symlinks=False)
        final = os.stat(Path(target).name, dir_fd=directory, follow_symlinks=False)
        if (final.st_dev, final.st_ino) != inode:
            raise ValueError('Published inode mismatch')
        # Remove our extra link immediately. Final pair/tag is never rolled back.
        self.unlink_owned(stage)
        self.allowed[target] = _inventory(self.path)[target]
        os.fsync(directory)
        self.protected()

    def import_pack(self, pack, index):
        self.protected()
        names = ['objects/pack/' + p.name for p in (pack, index)]
        known = self.initial | self.allowed
        present = [name in known for name in names]
        if any(present):
            if not all(present) or any(_file_hash(self.path/name) != _file_hash(p)
                                       or known[name][3] != 0o444 for name, p in zip(names, (pack, index))):
                raise ValueError('Partial/different/linked pack collision')
            return
        self.create(names[0].removesuffix('.pack') + '.keep', b'Bounded exact-source acquisition\n', 0o600)
        # Same-directory exclusive staging/no-replace link; never rename over a
        # preexisting pair. Pack first/index last, modes0444, no partial repair.
        for target, material in zip(names, (pack, index)):
            raw, _ = _regular(material, MAX_PACK)
            stage = 'objects/pack/acquisition-' + os.urandom(16).hex() + '.tmp'
            self.create(stage, raw, 0o444)
            if _file_hash(self.path/stage) != _file_hash(material):
                raise ValueError('Staged pack/index differs')
            self.link_final(stage, target)

    def publish_tags(self, tags, existing):
        missing = {ref: oid for ref, oid in tags.items() if ref not in existing}
        if not missing:
            return
        self.create('packed-refs.lock', b'', 0o600)
        for ref, oid in missing.items():
            if not TAG.fullmatch(ref):
                raise ValueError('Noncanonical direct tag')
            self.create(ref + '.lock', (require_sha(oid) + '\n').encode(), 0o644)
        self.protected()
        if local_tags(self.git) != existing:
            raise ValueError('Tag map changed while locks held')
        for ref in missing:
            self.link_final(ref + '.lock', ref)
        self.unlink_owned('packed-refs.lock')
        self.protected()

    def retire_shallow(self, closure):
        if self.shallow is None:
            self.protected()
            return
        if not self.boundaries <= {oid for oid, row in closure['objects'].items() if row[0] == 'commit'}:
            raise ValueError('Unrelated shallow roots outside acquired closure')
        self.create('shallow.lock', b'', 0o600)
        self.protected()
        # Retire captured boundary LAST, after verified original object readback,
        # direct tag publication and independently complete parent closure.
        if _inventory(self.path).get('shallow') != self.shallow:
            raise ValueError('Captured shallow inode/bytes changed')
        os.unlink('shallow', dir_fd=self.fd)
        self.removed.add('shallow')
        os.fsync(self.fd)
        self.protected()


def _context_still_current(git, context):
    # Re-read actual event bytes and clean committed checkout, never substitute
    # a cached source head or accept a changed producer-input observation.
    current = job_context(git.environment, git.root, None, mode=git.mode,
                          _capture=lambda: fingerprint(git)['head'])
    require_admission(current)
    if current != context:
        raise ValueError('Producer event/source inputs changed during acquisition')


def _ingest(git, transport, store, head, tags, existing, context):
    transport.tags = tags
    transport.fetch(head, tags)
    reader = _PrivateReader(transport, 'T')
    closure = _closure(reader, head, tags)
    if closure['tree'] != require_sha(git.text('rev-parse', 'HEAD^{tree}')):
        raise ValueError('Fetched exact root tree differs')
    # No arbitrary extra roots are fetched. Foreign ref/shallow roots refuse.
    refs = git.text('for-each-ref', '--format=%(objectname)').splitlines()
    if not set(refs) <= set(closure['objects']) or not store.boundaries <= set(closure['objects']):
        raise ValueError('Unrelated original roots not covered')
    pack, index = _validated_pack(transport, closure)
    if transport.advertise('ADVERTISEMENT_AFTER') != tags:
        raise ValueError('Advertised tags moved')
    store.protected()
    _context_still_current(git, context)
    store.import_pack(pack, index)
    metadata = _object_metadata(git, closure['objects'])
    if _object_contents(git, metadata) != closure['objects']:
        raise ValueError('Original object content differs from independent store')
    store.publish_tags(tags, existing)
    store.retire_shallow(closure)
    if verify_tags(git, tags) != closure['peeled']:
        raise ValueError('Original complete graph/peeling readback differs')
    store.cleanup()
    return closure['peeled']


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
