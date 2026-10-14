#!/usr/bin/env bash
# Exact-source, secret-free unsigned fixtures only. Never invoke on a local host.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf '%s\n' 'Hosted runner only' >&2; exit 1; }
: "${RUNNER_TEMP:?}" "${GITHUB_WORKSPACE:?}" "${ANDROID_HOME:?}" "${GRADLE_USER_HOME:?}"
export GIT_OPTIONAL_LOCKS=0
export GIT_NO_LAZY_FETCH=1
export GIT_NO_REPLACE_OBJECTS=1
# Freeze the producer evidence destination before the first preparation call.
# These named logs survive an early managed refusal; no scratch/auth tree upload.
fixture_evidence=$(python3 -B - <<'PYCODE'
import os, sys
from pathlib import Path
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import host_scratch, no_symlink_path
host_scratch(os.environ)
root = no_symlink_path(os.environ['GITHUB_WORKSPACE'])
assert root == Path.cwd() and root.is_dir()
for path in (root/'evidence', root/'evidence/release-fixtures'):
    if not path.exists():
        path.mkdir(mode=0o700)
    no_symlink_path(path)
    assert path.is_dir() and path.stat().st_uid == os.getuid()
print(root/'evidence/release-fixtures')
PYCODE
)
export fixture_evidence
original_status=0
phase=setup
completed_phases=()
finalize_fixtures() {
  local status=$?
  trap - EXIT TERM
  if (( original_status != 0 )); then status=$original_status; fi
  local finalizer=0
  python3 -B - "$fixture_evidence" "$status" "$phase" "${completed_phases[@]}" <<'PYCODE' || finalizer=$?
import json, os, re, stat, sys
from pathlib import Path
# Fixed allowlist/depth/count/size: two one-APK/seven-inspection inventories,
# named current graph/installation receipts and finite phase logs only.
try:
    root = Path(sys.argv[1])
    assert root.is_absolute() and not any(p.is_symlink() for p in (root, *root.parents))
    phases = {'setup', 'managed-source', 'managed-ref', 'managed-bootstrap', 'transport-contracts',
              'managed-clean', 'managed-assemble', 'git-contracts', 'rc', 'stable', 'tagged-dev'}
    assert sys.argv[3] in phases and all(p in phases for p in sys.argv[4:])
    files = {'boundary.txt', 'phase-status.json', 'git-contracts.json', 'transport-contracts.json', 'tagged-dev.json'}
    files |= {p + '.log' for p in phases}
    receipts = {'build-identity.json', 'resolved-toolchain.json', 'strict-release.log', 'rerun.json',
                'installed-toolchain.json', 'build-tools-source.properties', 'build-tools-package.xml'}
    inspections = {'inventory.json', 'permissions.txt', 'manifest.txt', 'backup.txt', 'extraction.txt',
                   'resources.txt', 'backup-resources.json', 'unsigned.txt'}
    count = total = 0
    for directory, dirs, names in os.walk(root, followlinks=False):
        relative = Path(directory).relative_to(root)
        assert len(relative.parts) <= 1
        for name in dirs:
            assert relative == Path('.') and name in {'payload-rc', 'payload-stable', 'graph-rc', 'graph-stable'}
            info = (Path(directory)/name).lstat()
            assert stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
        for name in names:
            path = Path(directory)/name
            info = path.lstat()
            assert stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
            apk = re.fullmatch(r'codexbar-mobile-[0-9A-Za-z.+-]+-release-c[1-9][0-9]*-g[0-9a-f]{12}-unsigned\.apk', name)
            if relative == Path('.'):
                assert name in files
            elif relative.name.startswith('graph-'):
                assert name in receipts
            else:
                assert name in inspections or apk
            limit = 256*1024*1024 if apk else 64*1024*1024 if name.endswith('.log') else 4*1024*1024
            assert info.st_size <= limit
            count += 1; total += info.st_size
            assert count <= 48 and total <= 1024*1024*1024
    path = root/'phase-status.json'
    # Current status is truthful; missing future phase material is never created.
    data = json.dumps({'exit': int(sys.argv[2]), 'phase': sys.argv[3], 'completedCommands': sys.argv[4:],
                       'boundary': 'nonofficial-unsigned-fixtures', 'sdkBindingHeld': True,
                       'managedAdmissionHeld': True}, sort_keys=True) + '\n'
    fd = os.open(path, os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(data)
except (AssertionError, ValueError, OSError):
    print('Fixture evidence finalization rejected', file=sys.stderr)
    raise SystemExit(1) from None
PYCODE
  if (( status == 0 && finalizer != 0 )); then status=$finalizer; fi
  exit "$status"
}
trap finalize_fixtures EXIT
trap 'if (( original_status != 0 )); then exit "$original_status"; else exit 143; fi' TERM
phase_command() {
  local capture=$1
  local named_phase=$2
  shift 2
  python3 -B -c '
import os, stat, subprocess, sys, time
from pathlib import Path
try:
    root, cwd, phase, capture = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4] == "capture"
    phases = {"setup", "managed-source", "managed-ref", "managed-bootstrap", "transport-contracts",
              "managed-clean", "managed-assemble", "git-contracts", "rc", "stable", "tagged-dev"}
    if (phase not in phases or not root.is_absolute() or not cwd.is_absolute()
            or any(p.is_symlink() for p in (root, *root.parents, cwd, *cwd.parents))):
        raise ValueError("Unsafe phase log boundary")
    directory = os.open(root, os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        fd = os.open(phase+".log", os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW, 0o600, dir_fd=directory)
    finally:
        os.close(directory)
    with os.fdopen(fd, "wb") as log:
        with subprocess.Popen(sys.argv[5:], cwd=cwd, stdout=None if capture else log, stderr=log) as child:
            while child.poll() is None:
                if os.fstat(log.fileno()).st_size > 64*1024*1024:
                    child.kill(); result = child.wait()
                    print("Fixture phase capture rejected", file=sys.stderr)
                    raise SystemExit((result if result >= 0 else 128-result) or 1)
                time.sleep(0.05)
            result = child.returncode
    raise SystemExit(result if result >= 0 else 128-result)
except (OSError, ValueError):
    print("Fixture phase capture rejected", file=sys.stderr)
    raise SystemExit(1) from None
' "$fixture_evidence" "${FIXTURE_PHASE_CWD:-$PWD}" "$named_phase" "$capture" "$@"
}
run_phase() {
  phase=$1
  shift
  local status=0
  if phase_command all "$phase" "$@"; then
    completed_phases+=("$phase")
  else
    status=$?
    original_status=$status
  fi
  return "$status"
}
capture_phase() {
  phase_command capture "$@"
}
scratch=$(mktemp -d "$RUNNER_TEMP/release-identity-fixtures.XXXXXX")
source_sha=$(git rev-parse HEAD)
# Use the acquired real PR source head for the representative managed clone.
# Raw producer event/SHA/ref are retained; no event is manufactured or rewritten.
phase=managed-source
managed_source_sha=$(capture_phase managed-source python3 -B - <<'PYCODE'
import sys
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import read_snapshot
from pathlib import Path
_, observation = read_snapshot(Path.cwd())
context = observation['context']
print(context['sourceHeadSha'] if context['checkoutRole'] == 'normal_pr_merge' else observation['state']['head'])
PYCODE
)
phase=managed-ref
managed_scanning_ref=$(capture_phase managed-ref python3 -B - <<'PYCODE'
import sys
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import read_snapshot
from pathlib import Path
_, observation = read_snapshot(Path.cwd())
context = observation['context']
print(context['producerEventRef'].removesuffix('/merge') + '/head' if context['checkoutRole'] == 'normal_pr_merge' else context['producerEventRef'])
PYCODE
)
# A fixed-public shallow clone also exercises settings' managed entry before
# actual clean/assemble. Its anonymous environment/config is isolated by helper.
mkdir "$scratch/managed"
run_phase managed-bootstrap python3 -B - "$scratch/managed" "$managed_source_sha" <<'PYCODE'
import sys
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import Git, PUBLIC_URL, safe_environment, TRANSPORT_OVERRIDES
import subprocess
from pathlib import Path
root = Path(sys.argv[1])
base = root.parent
# Only these existing hosted synthetic-repository constructors may write Git.
# Production checkout readers and transport dispatchers remain separate.
class FixtureGit(Git):
    def call(self, *args, input=None):
        if args and args[0] in ('rev-parse', 'ls-files', 'ls-tree', 'for-each-ref', 'rev-list', 'cat-file'):
            return super().call(*args, input=input)
        assert self.root.is_relative_to(base) and args and args[0] in ('init', 'config', 'add', 'commit', 'checkout', 'merge', 'tag', 'remote', 'fetch', '-c')
        env = safe_environment({'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C'})
        env.update(HOME=str(base/'fixture-empty-home'), XDG_CONFIG_HOME=str(base/'fixture-empty-home'), GIT_ALLOW_PROTOCOL='file:https')
        (base/'fixture-empty-home').mkdir(mode=0o700, exist_ok=True)
        argv = ['git', '-C', str(self.root)]
        for guard in (*TRANSPORT_OVERRIDES, 'protocol.file.allow=always'):
            argv += ['-c', guard]
        result = subprocess.run(argv + list(args), env=env, input=input, capture_output=True, timeout=60, check=False)
        if result.returncode:
            raise ValueError('Hosted synthetic Git construction refused')
        return result.stdout
git = FixtureGit(root)
git.call('init')
git.call('remote', 'add', 'origin', PUBLIC_URL)
git.call('fetch', '--depth=1', '--no-tags', '--no-write-fetch-head', '--no-auto-maintenance',
         '--no-write-commit-graph', '--refmap=', PUBLIC_URL, sys.argv[2] + ':refs/heads/fixture-source')
git.call('checkout', '--detach', sys.argv[2])
# Synthetic persisted header proves that preparation resets headers without
# rewriting their bytes; no real token or helper is installed.
git.call('config', '--local', 'http.https://github.com/.extraheader', 'Authorization: synthetic-fixture-only')
PYCODE
# H1 observes only guarded synthetic/public material, not wire headers. Internal
# mechanics controls consume an already admitted NORMAL source observation;
# they never synthesize a managed event or accept a managed acquisition snapshot.
run_phase transport-contracts python3 -B - "$scratch" <<'PYCODE'
import ctypes, hashlib, json, os, re, select, stat, struct, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import (Git, PUBLIC_URL, TRANSPORT_OVERRIDES, _PrivateTransport, _PrivateReader,
    _SourceStore, _closure, _validated_pack, _object_metadata, _object_contents, fingerprint,
    inspect_config, read_snapshot, local_tags, verify_tags, safe_environment, AcquisitionError)
def closed_failure(kind, error, trace):
    print(error.receipt() if isinstance(error, AcquisitionError) else 'HOSTED_TRANSPORT_CONTROL_REJECTED', file=sys.stderr)
sys.excepthook = closed_failure
assert os.environ.get('GITHUB_ACTIONS') == 'true'
base = Path(sys.argv[1])
producer, acquired = read_snapshot(Path(os.environ['GITHUB_WORKSPACE']))
assert not acquired['context']['admissionHeld'] and acquired['mode'] == 'normal'
head = acquired['state']['head']
protected = fingerprint(producer)
canaries = base/'public-canaries'
canaries.mkdir(mode=0o700)
watched = list(producer.config_files) + [base/'managed/.git/config']
for name in ('include', 'global', 'system', '.netrc', 'cookie', 'client-cert', 'client-key', 'helper', 'askpass'):
    path = canaries/name
    path.write_bytes(b'PUBLIC SYNTHETIC CANARY; never an authentication value\n')
    watched.append(path)

# Synthetic helper/askpass sentinels are configured only in the owned hosted
# clone, never in producer configuration or an actual credential file.
import shlex
marker = canaries/'sentinel-invoked'
for name in ('helper', 'askpass'):
    path = canaries/name
    path.write_text('#!/bin/sh\nprintf invoked > ' + shlex.quote(str(marker)) + '\nexit 1\n')
    path.chmod(0o700)
(canaries/'.netrc').write_text('machine github.com login PUBLIC_SYNTHETIC password PUBLIC_SYNTHETIC\n')
(canaries/'global').write_text('[credential]\n helper = !' + shlex.quote(str(canaries/'helper')) + '\n[core]\n askPass = ' + str(canaries/'askpass') + '\n')
(canaries/'system').write_text('[include]\n path = ' + str(canaries/'include') + '\n')
# Deliberate hostile PUBLIC selectors exist only in the guarded fixture host
# input. The network child must discard them before any native Git init.
producer.environment.update(HOME=str(canaries), GIT_CONFIG_GLOBAL=str(canaries/'global'),
    GIT_CONFIG_SYSTEM=str(canaries/'system'), GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='http.extraHeader',
    GIT_CONFIG_VALUE_0='PUBLIC_SYNTHETIC_HEADER', GIT_ASKPASS=str(canaries/'askpass'))
synthetic_env = safe_environment({'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C'})
for key, value in (('credential.helper', '!' + shlex.quote(str(canaries/'helper'))), ('core.askPass', str(canaries/'askpass'))):
    subprocess.run(['git', '-C', str(base/'managed'), 'config', '--local', key, value],
                   env=synthetic_env, capture_output=True, timeout=60, check=True)

class Observer:
    # No tracing executable/settings. libc only exposes the bounded inotify API.
    OPEN, ACCESS, LOST, OVERFLOW = 0x20, 0x1, 0x800 | 0x400 | 0x8000, 0x4000
    phases = ('INIT_TRANSPORT', 'INIT_VALIDATOR', 'ADVERTISEMENT_BEFORE', 'FETCH_EXACT', 'ADVERTISEMENT_AFTER')
    def __init__(self):
        self.libc = ctypes.CDLL(None, use_errno=True)
        self.libc.inotify_init1.argtypes = [ctypes.c_int]
        self.libc.inotify_init1.restype = ctypes.c_int
        self.libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
        self.libc.inotify_add_watch.restype = ctypes.c_int
        self.fd = self.libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
        if self.fd < 0:
            raise ValueError('Observer unavailable')
        self.identities, self.watches, self.armed, self.observed = {}, set(), None, []
        for path in watched:
            info = path.lstat()
            assert stat.S_ISREG(info.st_mode) and info.st_nlink == 1
            self.identities[path] = (info.st_dev, info.st_ino)
            wd = self.libc.inotify_add_watch(self.fd, os.fsencode(path), self.OPEN | self.ACCESS | self.LOST)
            if wd < 0 or wd in self.watches:
                raise ValueError('Observer watch unavailable')
            self.watches.add(wd)
        self.identity = (os.fstat(self.fd).st_dev, os.fstat(self.fd).st_ino)
    def healthy(self):
        if (os.fstat(self.fd).st_dev, os.fstat(self.fd).st_ino) != self.identity:
            raise ValueError('Observer replaced')
        for path, expected in self.identities.items():
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode) or (info.st_dev, info.st_ino) != expected:
                raise ValueError('Canary replaced')
    def decode(self, raw):
        result, offset = [], 0
        while offset < len(raw):
            if len(raw) - offset < 16:
                raise ValueError('Observer incomplete record')
            wd, mask, _, length = struct.unpack_from('iIII', raw, offset)
            offset += 16 + length
            if offset > len(raw) or wd not in self.watches or mask & (self.LOST | self.OVERFLOW):
                raise ValueError('Observer loss/overflow')
            if not mask & (self.OPEN | self.ACCESS):
                raise ValueError('Observer unknown event')
            result.append((wd, mask))
            if len(result) > 4096:
                raise ValueError('Observer record budget')
        return result
    def events(self):
        self.healthy()
        result, total, start = [], 0, time.monotonic()
        while select.select([self.fd], [], [], 0)[0]:
            raw = os.read(self.fd, 65536)
            total += len(raw)
            if not raw or total > 1024*1024 or time.monotonic()-start > 5:
                raise ValueError('Observer drain budget')
            result.extend(self.decode(raw))
            if len(result) > 4096:
                raise ValueError('Observer record budget')
        self.healthy()
        return result
    def calibrate(self):
        if self.events():
            raise ValueError('Unconsumed observer events')
        with (canaries/'include').open('rb') as stream:
            stream.read(1)
        if not self.events():
            raise ValueError('Observer calibration failed')
    def arm(self, phase):
        if self.armed is not None or phase not in self.phases or phase != self.phases[len(self.observed)]:
            raise ValueError('Unexpected observed child')
        if self.events():
            raise ValueError('Unconsumed observer events')
        self.calibrate()
        self.armed = phase
    def drain(self, phase):
        if self.armed != phase or self.events():
            raise ValueError('Child accessed original/public canary')
        self.observed.append(phase)
        self.armed = None
        self.calibrate()
    def close(self):
        os.close(self.fd)

def refuses(action):
    try:
        action()
    except (ValueError, OSError, AcquisitionError):
        return True
    raise AssertionError('Required hosted negative accepted')

observer = Observer()
try:
    observer.calibrate()
    assert refuses(lambda: observer.decode(struct.pack('iIII', -1, observer.OVERFLOW, 0, 0)))
    assert refuses(lambda: observer.arm('UNEXPECTED_CHILD'))
    with (canaries/'include').open('rb') as stream:
        stream.read(1)
    assert refuses(lambda: observer.arm('INIT_TRANSPORT'))  # stale/unconsumed
    observer.calibrate()
    original_canary = (canaries/'client-key').read_bytes()
    observer.events()
    (canaries/'client-key').unlink()
    (canaries/'client-key').write_bytes(original_canary)
    assert refuses(observer.healthy)
finally:
    observer.close()
# Loss is calibrated as a refusal, not an empty event queue or an optional skip.
lost = Observer()
lost.calibrate()
lost.close()
assert refuses(lost.healthy)
replaced = Observer()
replacement = replaced.libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
assert replacement >= 0
os.dup2(replacement, replaced.fd)
os.close(replacement)
assert refuses(replaced.calibrate)
replaced.close()
sentinel = Observer()
sentinel.calibrate()
subprocess.run([str(canaries/'helper')], env=synthetic_env, capture_output=True, timeout=5, check=False)
assert marker.is_file() and refuses(lambda: sentinel.arm('INIT_TRANSPORT'))
marker.unlink()
sentinel.close()
observer = Observer()
observer.calibrate()
transport = _PrivateTransport(producer, observer=observer)
try:
    tags = transport.advertise('ADVERTISEMENT_BEFORE')
    transport.tags = tags
    transport.fetch(head, tags)
    closure = _closure(_PrivateReader(transport, 'T'), head, tags)
    pack, index = _validated_pack(transport, closure)
    if transport.advertise('ADVERTISEMENT_AFTER') != tags:
        raise ValueError('Fixed advertisement changed')
    assert observer.observed == list(observer.phases)
    # Hosted-only actual owned-descendant lifetime proof. Public synthetic Python
    # children exercise the executor, not its closed Git operation admission.
    # No such process/group probe runs in canonical offline controls.
    import sys
    for outcome in ('timeout', 'size', 'failure'):
        pidfile = transport.root/('group-' + outcome + '.pid')
        program = """import os, sys, time
path, outcome = sys.argv[1:]
pid = os.fork()
if pid == 0:
    with open(path, 'x') as stream:
        stream.write(str(os.getpid()))
    while True: time.sleep(0.02)
while not os.path.exists(path): time.sleep(0.01)
if outcome == 'failure': os._exit(1)
if outcome == 'size':
    os.write(2, b'X' * (4 * 1024 * 1024 + 1))
while True: time.sleep(0.02)
"""
        options = dict(env=transport.env, cwd=str(transport.root/'T'), timeout=0.5)
        try:
            code, output = transport._execute([sys.executable, '-I', '-c', program, str(pidfile), outcome], options, None)
        except (subprocess.TimeoutExpired, ValueError):
            assert outcome in ('timeout', 'size')
        else:
            assert outcome == 'failure' and code == 1
        assert transport.group_safe and pidfile.is_file()
        descendant = int(pidfile.read_text())
        try:
            os.kill(descendant, 0)
        except ProcessLookupError:
            pass
        else:
            raise ValueError('Owned native descendant survived executor refusal')
        pidfile.unlink()
    frozen_pack, frozen_index = pack.read_bytes(), index.read_bytes()
    # Native extra-object export negative. The additional object is synthesized
    # only in owned T; this command cannot be reached by the transport dispatcher.
    argv = ['git', '-C', str(transport.root/'T'), '--git-dir=' + str(transport.root/'T')]
    for guard in TRANSPORT_OVERRIDES:
        argv += ['-c', guard]
    extra = subprocess.run(argv + ['hash-object', '-w', '--stdin'], env=transport.env, input=b'PUBLIC synthetic extra object\n',
                           capture_output=True, timeout=60, check=True).stdout.decode().strip()
    assert re.fullmatch('[0-9a-f]{40}', extra) and extra not in closure['objects']
    extra_pack = transport.call('T', 'EXPORT', 'pack-objects', '--stdout', '-q', '--no-reuse-object', '--window=10',
                                '--depth=50', '--window-memory=32m', '--threads=1',
                                input=''.join(x+'\n' for x in sorted(set(closure['objects']) | {extra})).encode())
    parents = _PrivateReader(transport, 'T').text('rev-list', '--parents', head).splitlines()[0].split()[1:]
    assert parents  # known non-inception fixture input; no negative skip
    thin_code, thin = transport._execute(argv + ['pack-objects', '--stdout', '--thin', '--revs'],
        dict(env=transport.env, cwd=str(transport.root/'T'), timeout=min(60, producer.deadline-time.monotonic()),
             input=(head+'\n^'+parents[0]+'\n').encode()), None)
    assert thin_code == 0

finally:
    transport.close()
    observer.close()
assert fingerprint(producer) == protected and not marker.exists()

# Each native validator control gets exactly two fresh stores; its independent V
# starts empty. Only EXPORT is injected with retained real native pack bytes.
# This is fault injection, not a source/admission or network success switch.
class FrozenExport(_PrivateTransport):
    def call(self, repo, phase, *args, **kwargs):
        if phase == 'EXPORT':
            return self.control_bytes
        return super().call(repo, phase, *args, **kwargs)
corrupt = bytearray(frozen_pack)
corrupt[0] ^= 1
corrupt[-20:] = hashlib.sha1(corrupt[:-20]).digest()
for label, data in (('valid', frozen_pack), ('extra', extra_pack), ('incomplete-thin', thin), ('corrupt', bytes(corrupt))):
    private = FrozenExport(producer)
    private.control_bytes, private.tags = data, tags
    try:
        if label == 'valid':
            _validated_pack(private, closure)
        else:
            assert refuses(lambda: _validated_pack(private, closure))
    finally:
        private.close()

# Real no-clobber publication/readback fixture, including captured shallow LAST.
# Clone construction is secret-free and local-only; no managed event is made.
fixture = base/'publication-source'
env = safe_environment({'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C'})
env.update(HOME=str(canaries), XDG_CONFIG_HOME=str(canaries), GIT_ALLOW_PROTOCOL='file')
argv = ['git']
for guard in (*TRANSPORT_OVERRIDES, 'protocol.file.allow=always'):
    argv += ['-c', guard]
subprocess.run(argv + ['clone', '--no-tags', '--no-local', '--depth=1', producer.root.as_uri(), str(fixture)],
               env=env, capture_output=True, timeout=60, check=True)
subprocess.run(argv + ['-C', str(fixture), 'config', 'remote.origin.url', PUBLIC_URL], env=env, capture_output=True, timeout=60, check=True)
local = Git(fixture)
inspect_config(local)
assert local.text('rev-parse', 'HEAD') == head
store = _SourceStore(local)
material = base/'retained-native-material'
material.mkdir(mode=0o700)
p = material/('pack-'+frozen_pack[-20:].hex()+'.pack')
i = p.with_suffix('.idx')
p.write_bytes(frozen_pack); i.write_bytes(frozen_index)
try:
    assert store.shallow is not None and store.boundaries <= set(closure['objects'])
    store.import_pack(p, i)
    assert (fixture/'.git/shallow').exists()
    metadata = _object_metadata(local, closure['objects'])
    assert _object_contents(local, metadata) == closure['objects']
    existing = local_tags(local)
    store.publish_tags(tags, existing)
    assert (fixture/'.git/shallow').exists()
    store.retire_shallow(closure)
    assert verify_tags(local, tags) == closure['peeled']
    store.cleanup()
    assert not store.owned and not (fixture/'.git/shallow').exists()
    # Validated pair reuse is byte-exact, with no second copy or source repair.
    store.import_pack(p, i)
    store.protected()
    original = i.read_bytes()
    i.write_bytes(original+b'PUBLIC changed validation material')
    assert refuses(lambda: store.import_pack(p, i))
    i.write_bytes(original)
finally:
    store.cleanup()
    store.close_descriptors()
# Finite publication fault injection on ephemeral clones only. Never repair a
# foreign lock/index/anchor or roll back validated final objects after refusal.
publication_negatives, publication_residuals = [], {}
for label in ('partial-pair', 'linked-pair', 'tag-conflict', 'foreign-tag-lock', 'index-drift',
              'keep-replacement', 'shallow-drift', 'anchor-replacement'):
    root = base/('publication-'+label)
    subprocess.run(argv + ['clone', '--no-tags', '--no-local', '--depth=1', producer.root.as_uri(), str(root)],
                   env=env, capture_output=True, timeout=60, check=True)
    subprocess.run(argv + ['-C', str(root), 'config', 'remote.origin.url', PUBLIC_URL],
                   env=env, capture_output=True, timeout=60, check=True)
    target_pack = root/'.git/objects/pack'/p.name
    target_index = target_pack.with_suffix('.idx')
    if label == 'partial-pair':
        target_pack.write_bytes(frozen_pack); target_pack.chmod(0o444)
    if label == 'linked-pair':
        link_material = base/('linked-material-'+label)
        link_material.mkdir()
        link_pack, link_index = link_material/p.name, link_material/i.name
        link_pack.write_bytes(frozen_pack); link_index.write_bytes(frozen_index)
        target_pack.hardlink_to(link_pack); target_index.hardlink_to(link_index)
    if label == 'tag-conflict':
        (root/'.git/refs/tags/v0.0.0').write_text(head+'\n')
    target = Git(root); inspect_config(target)
    if label == 'linked-pair':
        assert refuses(lambda: _SourceStore(target))
        publication_negatives.append(label)
        continue
    held = _SourceStore(target)
    synthetic_tags = {'refs/tags/v0.0.0': head}
    try:
        if label == 'partial-pair':
            assert refuses(lambda: held.import_pack(p, i)) and not target_index.exists()
        elif label == 'tag-conflict':
            # Established fixture tag is not an absent-ref publication target.
            existing_tag = root/'.git/refs/tags/v0.0.0'
            assert refuses(lambda: held.publish_tags(synthetic_tags, {}))
            assert existing_tag.read_text() == head+'\n'
        elif label == 'foreign-tag-lock':
            lock = root/'.git/refs/tags/v0.0.0.lock'
            lock.write_bytes(b'PUBLIC foreign lock, never stolen')
            assert refuses(lambda: held.publish_tags(synthetic_tags, {}))
            assert lock.read_bytes() == b'PUBLIC foreign lock, never stolen'
        else:
            held.import_pack(p, i)
            assert target_pack.read_bytes() == frozen_pack and target_index.read_bytes() == frozen_index
            if label == 'index-drift':
                (root/'.git/index').write_bytes(b'PUBLIC injected index drift')
                assert refuses(held.protected)
            elif label == 'keep-replacement':
                keep = root/'.git/objects/pack'/p.with_suffix('.keep').name
                replacement = root/'.git/objects/pack/controlled-replacement.tmp'
                replacement.write_bytes(b'PUBLIC different owned-inode fault')
                os.replace(replacement, keep)
                assert refuses(held.cleanup) and keep.read_bytes() == b'PUBLIC different owned-inode fault'
            elif label == 'shallow-drift':
                shallow = root/'.git/shallow'
                shallow.write_text(head+'\n'+parents[0]+'\n')
                assert refuses(lambda: held.retire_shallow(closure)) and shallow.exists()
            else:
                (root/'.git').rename(root/'.git-detached')
                (root/'.git').mkdir()
                assert refuses(held.anchors)
        publication_negatives.append(label)
    finally:
        # Refused cleanup remains a hard failure boundary of the fixture input;
        # final pairs/tags are never deleted or moved by cleanup/publication code.
        try:
            held.cleanup()
        except (ValueError, OSError, AcquisitionError):
            publication_residuals[label] = 'CLEANUP_REFUSED_NO_ROLLBACK'
        else:
            publication_residuals[label] = 'OWNED_TEMPORARIES_REMOVED'
        held.close_descriptors()
        assert (publication_residuals[label] == 'CLEANUP_REFUSED_NO_ROLLBACK') == (label in
            ('foreign-tag-lock', 'index-drift', 'keep-replacement', 'shallow-drift', 'anchor-replacement'))
assert len(publication_negatives) == 8
assert fingerprint(producer) == protected
Path(os.environ['fixture_evidence'], 'transport-contracts.json').write_text(json.dumps({
    'normalSourceSha': head, 'normalSourceTree': acquired['state']['tree'],
    'nativeGit': '2.55.0', 'observerPhases': list(observer.phases),
    'observerNegatives': ['overflow', 'loss', 'replacement', 'watcher-replacement', 'unconsumed', 'unexpected-child', 'helper-invocation'],
    'nativeValidation': ['valid', 'extra', 'incomplete-thin', 'corrupt'],
    'publication': ['shallow-last', 'actual-object-readback', 'absent-tags', 'pair-reuse', 'different-pair-refusal'],
    'publicationNegatives': publication_negatives, 'publicationResiduals': publication_residuals, 'producerPreserved': True, 'managedAdmission': False, 'fileAccessIsWireHeaderProof': False
}, sort_keys=True)+'\n')
PYCODE
FIXTURE_PHASE_CWD="$scratch/managed" run_phase managed-clean env CODEQL_EXTRACTOR_JAVA_TRAP_DIR="$scratch/codeql-marker" \
  CODE_SCANNING_REF="$managed_scanning_ref" GITHUB_WORKSPACE="$scratch/managed" timeout --signal=TERM --kill-after=30s 10m ./gradlew --no-daemon --dependency-verification strict \
  --no-configuration-cache --console=plain clean
FIXTURE_PHASE_CWD="$scratch/managed" run_phase managed-assemble env CODEQL_EXTRACTOR_JAVA_TRAP_DIR="$scratch/codeql-marker" \
  CODE_SCANNING_REF="$managed_scanning_ref" GITHUB_WORKSPACE="$scratch/managed" timeout --signal=TERM --kill-after=30s 10m ./gradlew --no-daemon --dependency-verification strict \
  --no-configuration-cache --console=plain assemble
# Small real Git graphs exercise inception/merge/ambiguity and stale/shallow
# rejection. They contain synthetic VERSION source only, never app/release proof.
run_phase git-contracts python3 -B - "$scratch" <<'PYCODE'
import json, os, sys
from pathlib import Path
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import Git, prepare, safe_environment, TRANSPORT_OVERRIDES
import subprocess
from release_identity import resolve_identity
base = Path(sys.argv[1])
# Only these existing hosted synthetic-repository constructors may write Git.
# Production checkout readers and transport dispatchers remain separate.
class FixtureGit(Git):
    def call(self, *args, input=None):
        if args and args[0] in ('rev-parse', 'ls-files', 'ls-tree', 'for-each-ref', 'rev-list', 'cat-file'):
            return super().call(*args, input=input)
        assert self.root.is_relative_to(base) and args and args[0] in ('init', 'config', 'add', 'commit', 'checkout', 'merge', 'tag', 'remote', 'fetch', '-c')
        env = safe_environment({'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C'})
        env.update(HOME=str(base/'fixture-empty-home'), XDG_CONFIG_HOME=str(base/'fixture-empty-home'), GIT_ALLOW_PROTOCOL='file:https')
        (base/'fixture-empty-home').mkdir(mode=0o700, exist_ok=True)
        argv = ['git', '-C', str(self.root)]
        for guard in (*TRANSPORT_OVERRIDES, 'protocol.file.allow=always'):
            argv += ['-c', guard]
        result = subprocess.run(argv + list(args), env=env, input=input, capture_output=True, timeout=60, check=False)
        if result.returncode:
            raise ValueError('Hosted synthetic Git construction refused')
        return result.stdout
origin = base/'graph-origin'
origin.mkdir()
git = FixtureGit(origin)
git.call('init')
git.call('config', 'user.name', 'Synthetic history fixture')
git.call('config', 'user.email', 'fixture@example.invalid')
(origin/'VERSION').write_text('0.1.0\n')
git.call('add', 'VERSION')
git.call('commit', '-m', 'Synthetic inception')
inception = git.text('rev-parse', 'HEAD')

def clone(name, shallow=False):
    root = base/name
    root.mkdir()
    target = FixtureGit(root)
    target.env['GIT_ALLOW_PROTOCOL'] = 'file'
    args = ['-c', 'protocol.file.allow=always', 'clone', '--no-local']
    if shallow:
        args += ['--depth=1']
    target.call(*args, origin.as_uri(), str(root))
    target.call('config', 'remote.origin.url', str(origin))
    return root

root = clone('inception-graph')
prepare(root, 'fixture')
identity = resolve_identity(root)
assert identity['devDistance'] == 1 and identity['versionCode'] == 1 and identity['stableAncestor'] is None
results = {'inception': identity}
# No completed observation can hide a different HEAD, even with identical source tree.
local = FixtureGit(root)
local.call('config', 'user.name', 'Synthetic history fixture')
local.call('config', 'user.email', 'fixture@example.invalid')
local.call('commit', '--allow-empty', '-m', 'Synthetic changed source identity')
try:
    resolve_identity(root)
except ValueError:
    results['staleSourceRejected'] = True
else:
    raise AssertionError('Stale acquisition accepted changed HEAD')
# A merge adds both reachable branch histories; a stable ancestor excludes its
# own ancestry rather than counting only the first-parent lane.
git.call('checkout', '-b', 'fixture-main')
git.call('commit', '--allow-empty', '-m', 'Synthetic main child')
main_child = git.text('rev-parse', 'HEAD')
git.call('checkout', '-b', 'fixture-side', inception)
git.call('commit', '--allow-empty', '-m', 'Synthetic side child')
side_child = git.text('rev-parse', 'HEAD')
git.call('checkout', 'fixture-main')
git.call('merge', '--no-ff', '-m', 'Synthetic merge', 'fixture-side')
git.call('tag', '-a', 'v0.0.1', '-m', 'Synthetic stable ancestor', inception)
root = clone('merge-graph')
prepare(root, 'fixture')
identity = resolve_identity(root)
assert identity['devDistance'] == 3 and identity['versionCode'] == 4 and identity['stableAncestor'] == inception
results['merge'] = identity
root = clone('shallow-graph', True)
try:
    prepare(root, 'fixture')
except ValueError:
    results['shallowRejected'] = True
else:
    raise AssertionError('Shallow graph accepted')
# Multiple incomparable stable maxima must fail instead of selecting by tag time.
git.call('tag', 'v0.0.2', main_child)
git.call('tag', 'v0.0.3', side_child)
root = clone('ambiguous-graph')
prepare(root, 'fixture')
try:
    resolve_identity(root)
except ValueError:
    results['ambiguousStableRejected'] = True
else:
    raise AssertionError('Ambiguous stable maxima accepted')
# Existing target stable/RC refs must not collide with isolated producer fixtures.
git.call('tag', 'v0.1.0-rc.1')
git.call('tag', 'v0.1.0')
root = clone('tagged-graph')
prepare(root, 'fixture')
tagged = resolve_identity(root)
assert tagged['channel'] == 'dev' and tagged['devDistance'] == 0
results['taggedDevelopment'] = tagged
isolated = base/'collision-isolated-origin'
isolated.mkdir()
control = FixtureGit(isolated)
control.env['GIT_ALLOW_PROTOCOL'] = 'file'
control.call('-c', 'protocol.file.allow=always', 'clone', '--no-tags', '--no-local', '--bare', origin.as_uri(), str(isolated))
assert not control.text('for-each-ref', '--format=%(refname)', 'refs/tags')
control.call('tag', 'v0.1.0-rc.1')
control.call('tag', 'v0.1.0')
assert git.text('rev-parse', 'refs/tags/v0.1.0') == control.text('rev-parse', 'refs/tags/v0.1.0')
results['inheritedTargetTagsIsolated'] = True
(Path(os.environ['fixture_evidence'])/'git-contracts.json').write_text(json.dumps(results, sort_keys=True) + '\n')
PYCODE
# Fixture tag origin is local and confined to runner temp; it cannot satisfy the
# fixed-public managed preparation or official signing admission.
git clone --no-tags --no-local --bare "$GITHUB_WORKSPACE" "$scratch/origin.git"
git clone --no-tags --no-local "$scratch/origin.git" "$scratch/project"
git -C "$scratch/project" checkout --detach "$source_sha"
target=$(tr -d '\n' < VERSION)
# Tags are never written in the real worktree or public origin.
git --git-dir="$scratch/origin.git" tag "v$target-rc.1" "$source_sha"
git --git-dir="$scratch/origin.git" -c user.name='Hosted fixture' -c user.email='fixture@example.invalid' \
  tag -a "v$target" -m 'Ephemeral stable identity fixture' "$source_sha"
git -C "$scratch/project" fetch --no-auto-maintenance --no-write-fetch-head origin 'refs/tags/*:refs/tags/*'
cd "$scratch/project"
  python3 -B tools/build/complete_codeql_checkout.py --mode fixture
  printf '%s\n' '{"fixturePreviousCode":40}' > "$scratch/prior.json"
  for channel in rc stable; do
    code=41
    tag="v$target-rc.1"
    if [[ "$channel" == stable ]]; then code=42; tag="v$target"; fi
    phase=$channel
    run_phase "$channel" timeout --signal=TERM --kill-after=30s 10m ./gradlew --no-daemon --dependency-verification strict \
      --build-cache --no-configuration-cache --stacktrace --info --console=plain \
      -PreleaseChannel="$channel" -PreleaseTag="$tag" -PreleaseSourceSha="$source_sha" \
      -PreleaseCounter="$code" -PreleasePriorIdentity="$scratch/prior.json" \
      -I tools/build/toolchain.init.gradle :app:verifyResolvedToolchain \
      :app:writeBuildIdentity :app:lintRelease :app:assembleRelease
    python3 -B tools/build/verify_gradle_execution.py release "$fixture_evidence/$channel.log"
    cp "$fixture_evidence/$channel.log" evidence/strict-release.log
    mkdir "$fixture_evidence/graph-$channel"
    cp evidence/build-identity.json evidence/resolved-toolchain.json evidence/strict-release.log "$fixture_evidence/graph-$channel/"
    cp "$GITHUB_WORKSPACE/evidence/installed-toolchain.json" "$GITHUB_WORKSPACE/evidence/build-tools-source.properties" \
      "$GITHUB_WORKSPACE/evidence/build-tools-package.xml" "$fixture_evidence/graph-$channel/"
    python3 -B tools/build/verify_release_payload.py create --identity evidence/build-identity.json \
      --apk app/build/outputs/apk/release/app-release-unsigned.apk --output "$fixture_evidence/payload-$channel"
    cp evidence/build-identity.json "$scratch/prior.json"
    # Rerun identity must preserve the exact tuple/code, no counter allocation.
    python3 -B tools/build/release_identity.py resolve --channel "$channel" --tag "$tag" \
      --source-sha "$source_sha" --release-counter "$code" --prior-identity "$scratch/prior.json" --rerun \
      > "$fixture_evidence/graph-$channel/rerun.json"
    cmp evidence/build-identity.json "$fixture_evidence/graph-$channel/rerun.json" || \
      python3 -B - "$fixture_evidence/graph-$channel/rerun.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path('evidence/build-identity.json').read_text()) == json.loads(Path(sys.argv[1]).read_text())
PY
  done
  phase=tagged-dev
  # Normal dev remains dev even at a stable-tagged HEAD; this also proves the
  # genuine zero distance rather than a discovery fallback.
  python3 -B tools/build/release_identity.py resolve --channel dev > "$fixture_evidence/tagged-dev.json"
  python3 -B - "$fixture_evidence/tagged-dev.json" <<'PY'
import json, sys
from pathlib import Path
identity = json.loads(Path(sys.argv[1]).read_text())
assert identity['channel'] == 'dev' and identity['devDistance'] == 0
PY
cd "$GITHUB_WORKSPACE"
printf '%s\n' 'Controlled unsigned fixtures only; no official counter, signing or distribution' \
  > "$fixture_evidence/boundary.txt"
