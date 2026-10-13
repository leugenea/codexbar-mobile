#!/usr/bin/env bash
# Exact-source, secret-free unsigned fixtures only. Never invoke on a local host.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf '%s\n' 'Hosted runner only' >&2; exit 1; }
: "${RUNNER_TEMP:?}" "${GITHUB_WORKSPACE:?}" "${ANDROID_HOME:?}" "${GRADLE_USER_HOME:?}"
export GIT_OPTIONAL_LOCKS=0
export GIT_NO_LAZY_FETCH=1
export GIT_NO_REPLACE_OBJECTS=1
scratch=$(mktemp -d "$RUNNER_TEMP/release-identity-fixtures.XXXXXX")
source_sha=$(git rev-parse HEAD)
# Use the acquired real PR source head for the representative managed clone.
# Raw producer event/SHA/ref are retained; no event is manufactured or rewritten.
managed_source_sha=$(python3 -B - <<'PYCODE'
import sys
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import read_snapshot
from pathlib import Path
_, observation = read_snapshot(Path.cwd())
context = observation['context']
print(context['sourceHeadSha'] if context['checkoutRole'] == 'normal_pr_merge' else observation['state']['head'])
PYCODE
)
managed_scanning_ref=$(python3 -B - <<'PYCODE'
import sys
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import read_snapshot
from pathlib import Path
_, observation = read_snapshot(Path.cwd())
context = observation['context']
print(context['producerEventRef'].removesuffix('/merge') + '/head' if context['checkoutRole'] == 'normal_pr_merge' else context['producerEventRef'])
PYCODE
)
mkdir -p evidence/release-fixtures
# A fixed-public shallow clone also exercises settings' managed entry before
# actual clean/assemble. Its anonymous environment/config is isolated by helper.
mkdir "$scratch/managed"
python3 -B - "$scratch/managed" "$managed_source_sha" <<'PYCODE'
import sys
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import Git, PUBLIC_URL
from pathlib import Path
root = Path(sys.argv[1])
git = Git(root)
git.call('init')
git.call('remote', 'add', 'origin', PUBLIC_URL)
git.call('fetch', '--depth=1', '--no-tags', '--no-write-fetch-head', '--no-auto-maintenance',
         '--no-write-commit-graph', '--refmap=', PUBLIC_URL, sys.argv[2] + ':refs/heads/fixture-source')
git.call('checkout', '--detach', sys.argv[2])
# Synthetic persisted header proves that preparation resets headers without
# rewriting their bytes; no real token or helper is installed.
git.call('config', '--local', 'http.https://github.com/.extraheader', 'Authorization: synthetic-fixture-only')
PYCODE
(
  cd "$scratch/managed"
  # Existing managed marker, isolated to this hosted subprocess, never local.
  export CODEQL_EXTRACTOR_JAVA_TRAP_DIR="$scratch/codeql-marker"
  CODE_SCANNING_REF="$managed_scanning_ref" GITHUB_WORKSPACE="$scratch/managed" timeout --signal=TERM --kill-after=30s 10m ./gradlew --no-daemon --dependency-verification strict \
    --no-configuration-cache --console=plain clean > "$scratch/managed-clean.log" 2>&1
  CODE_SCANNING_REF="$managed_scanning_ref" GITHUB_WORKSPACE="$scratch/managed" timeout --signal=TERM --kill-after=30s 10m ./gradlew --no-daemon --dependency-verification strict \
    --no-configuration-cache --console=plain assemble > "$scratch/managed-assemble.log" 2>&1
)
cp "$scratch/managed-clean.log" "$scratch/managed-assemble.log" evidence/release-fixtures/
# Small real Git graphs exercise inception/merge/ambiguity and stale/shallow
# rejection. They contain synthetic VERSION source only, never app/release proof.
python3 -B - "$scratch" <<'PYCODE'
import json, os, sys
from pathlib import Path
sys.path.insert(0, 'tools/build')
from complete_codeql_checkout import Git, prepare, safe_environment
from release_identity import resolve_identity
base = Path(sys.argv[1])
origin = base/'graph-origin'
origin.mkdir()
git = Git(origin)
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
    target = Git(root)
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
local = Git(root)
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
control = Git(isolated)
control.env['GIT_ALLOW_PROTOCOL'] = 'file'
control.call('-c', 'protocol.file.allow=always', 'clone', '--no-tags', '--no-local', '--bare', origin.as_uri(), str(isolated))
assert not control.text('for-each-ref', '--format=%(refname)', 'refs/tags')
control.call('tag', 'v0.1.0-rc.1')
control.call('tag', 'v0.1.0')
assert git.text('rev-parse', 'refs/tags/v0.1.0') == control.text('rev-parse', 'refs/tags/v0.1.0')
results['inheritedTargetTagsIsolated'] = True
Path('evidence/release-fixtures/git-contracts.json').write_text(json.dumps(results, sort_keys=True) + '\n')
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
(
  cd "$scratch/project"
  python3 -B tools/build/complete_codeql_checkout.py --mode fixture
  printf '%s\n' '{"fixturePreviousCode":40}' > "$scratch/prior.json"
  for channel in rc stable; do
    code=41
    tag="v$target-rc.1"
    if [[ "$channel" == stable ]]; then code=42; tag="v$target"; fi
    timeout --signal=TERM --kill-after=30s 10m ./gradlew --no-daemon --dependency-verification strict \
      --build-cache --no-configuration-cache --stacktrace --info --console=plain \
      -PreleaseChannel="$channel" -PreleaseTag="$tag" -PreleaseSourceSha="$source_sha" \
      -PreleaseCounter="$code" -PreleasePriorIdentity="$scratch/prior.json" \
      -I tools/build/toolchain.init.gradle :app:verifyResolvedToolchain \
      :app:writeBuildIdentity :app:lintRelease :app:assembleRelease \
      > "$scratch/$channel.log" 2>&1
    python3 -B tools/build/verify_gradle_execution.py release "$scratch/$channel.log"
    cp "$scratch/$channel.log" evidence/strict-release.log
    python3 -B tools/build/verify_release_payload.py create --identity evidence/build-identity.json \
      --apk app/build/outputs/apk/release/app-release-unsigned.apk --output "$scratch/payload-$channel"
    cp evidence/build-identity.json "$scratch/prior.json"
    # Rerun identity must preserve the exact tuple/code, no counter allocation.
    python3 -B tools/build/release_identity.py resolve --channel "$channel" --tag "$tag" \
      --source-sha "$source_sha" --release-counter "$code" --prior-identity "$scratch/prior.json" --rerun \
      > "$scratch/rerun-$channel.json"
    cmp evidence/build-identity.json "$scratch/rerun-$channel.json" || \
      python3 -B - "$scratch/rerun-$channel.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path('evidence/build-identity.json').read_text()) == json.loads(Path(sys.argv[1]).read_text())
PY
  done
  # Normal dev remains dev even at a stable-tagged HEAD; this also proves the
  # genuine zero distance rather than a discovery fallback.
  python3 -B tools/build/release_identity.py resolve --channel dev > "$scratch/tagged-dev.json"
  python3 -B - "$scratch/tagged-dev.json" <<'PY'
import json, sys
from pathlib import Path
identity = json.loads(Path(sys.argv[1]).read_text())
assert identity['channel'] == 'dev' and identity['devDistance'] == 0
PY
)
cp "$scratch/rc.log" "$scratch/stable.log" evidence/release-fixtures/
cp -R "$scratch/payload-rc" "$scratch/payload-stable" evidence/release-fixtures/
printf '%s\n' 'Controlled unsigned fixtures only; no official counter, signing or distribution' \
  > evidence/release-fixtures/boundary.txt
