# Contributing

## Scope and workflow

Work from a GitHub issue; use bounded sub-issues for distinct delivery units.
Keep repository prose, code comments, issue/PR text and commit messages in English.
Discuss behavior changes before expanding scope. Add meaningful tests for behavior
changes; TDD/test-first or deliberately failing CI is not required.

Keep changes small. Run quick checks, publish the candidate, and wait for all
applicable hosted checks to pass on the exact head before commissioning independent
read-only full-diff review. The strict required status-check policy requires PR
branches to be up to date with `main` before merge. After updating a branch, repeat
quick checks, publication, green exact-head hosted checks and independent review.
Merge only the reviewed exact head with all applicable hosted checks green, using
squash merge; verify all applicable main workflows and artifacts at the exact merge SHA.
Documentation-only PRs are not exempt. Store history and review evidence in
GitHub issues/PRs, not committed receipts or generated reports.

Never post tokens, passwords, device codes or raw account data. Share only
sanitized diagnostics, synthetic fixtures, safe reproduction steps and CI links.
Use [SECURITY](SECURITY.md) for vulnerabilities, not public issues.

## Current toolchain

The build files and workflows, not the historical M0 selection, are authoritative:
Gradle 9.8.1 (checksum-pinned wrapper), AGP 9.4.1, Kotlin/Compose compiler 2.4.20,
Compose BOM 2026.09.00 / UI 1.12.1 / Material3 1.4.0, coroutines 1.11.0 and
Temurin JDK 17. AGP uses built-in Kotlin with the explicit higher KGP classpath.
Compile/target SDK is 37 (minor 0), minimum SDK 26, Build Tools 36.0.0.
Hosted setup uses command-line tools 22.0 (build ID 15859902); JDK patch and named
SDK/emulator package revisions are observed rather than historical archive pins.
Set `JAVA_HOME` and `ANDROID_HOME` to compatible installations, or use ignored
`local.properties` for `sdk.dir`. Accept the SDK's applicable license terms.

## Quick local checks

From the root, in Bash, with Linux x86_64 Python 3.13, uv, actionlint 1.7.12 and
ShellCheck 0.11.0 on PATH (or select binaries with `ACTIONLINT`/`SHELLCHECK`):

```sh
uv venv -p /usr/bin/python3.13 ../codexbar-check-venv
uv pip install --python ../codexbar-check-venv/bin/python --require-hashes --no-deps \
  -r .github/requirements-policy.txt -r tools/research/requirements.txt \
  -r .github/requirements-metrics.txt
CHECK_PYTHON=../codexbar-check-venv/bin/python bash tools/check.sh
git diff --check
```

[tools/check.sh](tools/check.sh) runs build/research/metrics/policy Python contracts,
public readiness/link checks, pinned Dependabot schema validation, shell parsing,
ShellCheck and actionlint. It does not build Android or install an SDK/emulator.
It fetches the pinned schema unless `DEPENDABOT_SCHEMA` supplies a checksum-valid
offline copy. Hash locks cover the supported wheel platform, not every platform.

CI-first is the default. Gradle build, lint and JVM tests are allowed locally when
useful; do not repeat a heavy local ladder merely to publish a draft. With the
JDK/SDK installed, this non-emulator invocation is available:

```sh
./gradlew --no-daemon --dependency-verification strict \
  :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

**Android emulators and instrumented tests run only in hosted CI, never on the NAS.**
Do not run [run-hosted-native-smoke.sh](tools/build/run-hosted-native-smoke.sh) locally.

## Network policy

The app source requests INTERNET only. Merged manifests, APKs and installed app
packages must request exactly INTERNET plus the existing AndroidX Core signature-only
receiver permission; no extra permission is accepted. Cleartext is explicitly off
in every app build, with no debug/test override. JVM local-server fixtures do not
need device cleartext access. The permission enables only the selected auth/usage routes. A fresh process owner
restores credentials without requests; an explicit action activates foreground reads.
The real connection/live flow is the default launcher and recreation destination.
An already activated owner may resume a bounded read cycle on Activity STARTED.

Both hosted graphs generate `processReleaseManifest` as well as the debug manifest;
the shared gate requires both generated policies. The build job additionally assembles
the unsigned release APK and runs full `lintRelease` with strict dependency verification.
App variant manifest overlays and destructive/protected-policy merge directives
are rejected. The native script checks the debug APK, binds its SHA-256, reinstalls
that same file after AGP test cleanup, requires a package-specific `pm path`, checks
actual `dumpsys` requested permissions, then uninstalls with a recorded status.

## Hosted runtime and coverage

[Android CI](.github/workflows/android.yml) uses hosted Ubuntu 24.04, isolated SDKs
and separate Gradle user homes. The pinned setup-gradle basic provider persists
`caches/` and `wrapper/`, including the default local task-output cache at
`$GRADLE_USER_HOME/caches/build-cache-1`. No remote cache service is configured.
Fork PRs remain read-only. Same-repository PR writes are scoped to their PR merge
ref; GitHub does not let them overwrite or restore into the base branch's cache.
Strict dependency verification remains mandatory on hits. The basic provider keys
on a [hash of Gradle build files](https://github.com/gradle/actions/blob/3f5f9adaf7d9fecd50b5935e54106014257a94e6/sources/src/cache-service-basic.ts#L160-L168)
with intentionally [no restore keys](https://github.com/gradle/actions/blob/3f5f9adaf7d9fecd50b5935e54106014257a94e6/sources/src/cache-service-basic.ts#L25-L39):
the first run after dependency/build-script changes is cold. Exact hits remain
immutable seeds, not accumulating archives; inspect saves and actual task hits
before claiming a warm-run gain.

`org.gradle.caching=true` enables local output reuse too, including clean builds,
branches and worktrees sharing the same Gradle user home. CI explicitly passes
`--build-cache`. Ordinary compile, dex, resource and packaging tasks retain normal
cacheability.
The app applies [always-execute.gradle](tools/build/always-execute.gradle) to force
fresh JVM/native tests, coverage preparation/collection/report/verification, resolved
toolchain observation and **all lint tasks**: both up-to-date reuse and build-cache
reuse are disabled for these tasks. Their terminal-state listener rejects skipped,
no-source or reused gates locally as well as in CI. Only AGP-disabled
`lintVital[Report]<Variant>` may be skipped when its matching, enforced full lint
partner is enabled in the same graph; vital lint still cannot reuse outputs.
Global `--rerun-tasks` is forbidden in the hosted graphs because it would defeat
output reuse. Configuration
cache remains disabled (`--no-configuration-cache`) for the outcome listeners and
init-script observation.

The explicit [CodeQL workflow](.github/workflows/codeql.yml) must compile sources,
not restore compiled task outputs. Its manual Java/Kotlin build runs `clean`, both
app assemblies and debug JVM/native test compilation with `--no-build-cache`.
It verifies actual compiler task execution before analysis. When `CODEQL_EXTRACTOR_JAVA_TRAP_DIR` is present,
[settings.gradle](settings.gradle) disables the local task-output cache for that
traced build; ordinary builds keep output reuse. CodeQL dependency/wrapper caching
remains enabled and is distinct from task-output caching. No remote build cache is
configured; any future backend must also avoid output reuse during extraction.
The marker comes from the released extractor, not a documented GitHub workflow
option: extractor changes require hosted warm-cache validation; static source
contracts cannot prove the marker's continued identity or successful extraction.

`build` and `instrumented` run independently in parallel; `Android CI result`
still requires both to succeed, including the native union-coverage gate. The
build job retains its strict lint/JVM gate. The native job must resolve class outputs
and run JVM + native tests in one Gradle graph so `.exec` and `.ec` share the same
class IDs; it consumes no build-job artifacts. Both graphs retain their compilation
tasks, now eligible for cache reuse; JVM-test duplication remains, but the build
job is off the native critical path.

Both graphs keep full `--info` output in `evidence/strict.log` and
`evidence/native/strict-connected.log` (plus each native attempt's copy), before
filtering known cache-disabled and library-manifest informational lines, plus exact
artifact-transform `Caching not enabled.` two-line blocks, from the console.
Warnings, errors, task/test output and coverage outcomes remain visible.
[verify_gradle_execution.py](tools/build/verify_gradle_execution.py) requires every
expected verification/observation receipt to be `SUCCESS` and rejects reuse/skip
headers, including lint analysis/report tasks. Missing, duplicate or malformed
receipts fail closed. It checks each native attempt **before** retry eligibility;
only a `FAILED` collection receipt in a failed graph may explain absent downstream
report/verification receipts; an unrelated graph failure is not an exemption.
No gate may reuse outputs. Existing retry/JUnit checks still forbid failed suites.
Evidence parsers and the bounded coverage retry read the unfiltered logs. Gradle/
timeout exit codes take precedence over pipeline helpers; evidence/filter failures
also fail the job. Checkout v7.0.1 and upload-artifact v7.0.2 use Node 24 with full
SHA pins; credential persistence remains disabled and existing artifact inputs
(including hidden-file exclusion, retention and unique Android names) are unchanged.

Build job command (the workflow additionally preserves individual pipeline exit codes;
the init script defines the observation task):

```sh
set -euo pipefail
mkdir -p evidence
./gradlew --no-daemon --dependency-verification strict --build-cache \
  --no-configuration-cache --stacktrace --info --console=plain --continue \
  -I tools/build/toolchain.init.gradle :app:verifyResolvedToolchain \
  :app:lintDebug :app:assembleDebug :app:lintRelease :app:assembleRelease :app:processReleaseManifest :app:compileDebugUnitTestKotlin \
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:assembleDebugAndroidTest \
  2>&1 | tee evidence/strict.log | bash tools/build/filter-gradle-console.sh
python3 tools/build/verify_gradle_execution.py build evidence/strict.log
python3 tools/build/verify_manifests.py
python3 tools/build/verify_test_reports.py jvm
```

The workflow requires KVM; its native script prepares API 36
`google_apis;x86_64` phone infrastructure with 4096 MiB of emulator RAM,
waits for ADB/boot readiness, then invokes JVM/native tests and
`:app:jacocoDebugReport` / `:app:jacocoDebugCoverageVerification`; it checks native
JUnit results and records phase outcomes. See the script for its hosted-only
command and timeout environment, rather than inventing a local device procedure.

The script permits **one bounded retry** only after passing JVM/native suites,
with ADB-offline evidence at collection and missing, empty or truncated native coverage
from the same checkout/run/attempt. It preserves the failed graph's data and
logs, waits for device readiness, then reruns the complete JVM/native/coverage
graph within the original 15-minute graph budget. It never combines coverage
across attempts or retries failing tests, timeouts, other invalid datasets or a
coverage-threshold failure. `coverage_gate.py prepare()` deletes execution data,
class snapshots and test/coverage reports before both suites on **each** attempt;
it leaves compilation/instrumentation outputs intact. Those outputs may legitimately
be up-to-date or restored byte-for-byte, but suites and every coverage gate execute
again. Attempt 2 never loads attempt 1's `.exec`/`.ec` or report. See
[the retry guard](tools/build/native_coverage_retry.py).

Coverage minimum: **90%** JaCoCo **INSTRUCTION** over the compatible JVM +
instrumented execution-data union. JaCoCo 0.8.15 analyzes the complete debug
project-class snapshot, including handwritten Activity/Compose/lambda/companion
code. Only Android-generated R/BuildConfig/Manifest identities are excluded;
there are no handwritten-code exclusions. Class IDs, probe counts, freshness,
nonzero denominator and XML/inventory consistency are validated. Cache hits replay
the exact compiled/instrumented bytes for their declared inputs/toolchain; they do
not regenerate class IDs. Both fresh suites consume those same graph outputs, and
`DebugCoverageInputs` analyzes the actual report-class snapshot. Any bytecode drift
still fails closed on class IDs/probe counts, freshness or snapshot hashes before
the report/90% check. Never lower the
threshold or broaden exclusions. Historical 95% statements in M0 remain historical
and are superseded for current acceptance by the 0.90 rule in [app/build.gradle](app/build.gradle).

Both Android jobs upload all-outcome artifacts:
`android-build-<run_id>-<run_attempt>` and `android-instrumented-<run_id>-<run_attempt>`,
retained for seven days. Inspect SDK/toolchain/build logs, lint/JUnit reports,
merged manifests and debug/unsigned release APKs; native artifacts also contain emulator/logcat,
connected-test results, exec/ec data and coverage XML/HTML/inventory/phase outputs.
Missing reports are not passing tests. A failure before instrumentation is an
infrastructure boundary, not runtime proof; passing native tests with failing
coverage is a separate coverage failure. Diagnostics/screenshots are not release
or live-provider evidence. Outputs stay in CI artifacts or external scratch.

## Dependency and supply-chain updates

1. Review every added/removed `(group, name, version, file, sha256)` pair in
   [gradle/verification-metadata.xml](gradle/verification-metadata.xml) against
   official publisher sources. Preserve module `file.name` to `file.url` variant
   mappings. Generated verification metadata is a candidate, not automatic trust.
   Do not use verification off/lenient, ignored artifacts or widened trust.
2. Run the dependency-integrity test (included in `tools/check.sh`) to see the
   candidate semantic digest and pair delta. After independent review of that
   delta, update `APPROVED_METADATA_SHA256` in
   [test_dependency_integrity.py](tools/build/test_dependency_integrity.py) in the
   same PR. If the delta needs new origin annotations, review the narrowly scoped
   annotation-policy change too; never assert authenticated publisher identity
   from a checksum alone. Keep review history in the PR, not a second inventory.
3. Wrapper updates also require the official distribution checksum. Review
   notices when dependencies change; require fresh exact-head strict build,
   JVM/native and union-coverage acceptance. **Dependabot Gradle PRs need this
   reviewed XML delta and digest update too**; expected initial failure is no bypass.
4. Pin executable Actions to full commit SHAs with accurate release comments.
   Keep Python requirements version- and hash-pinned, including transitives.
   Review official release-to-SHA mappings and wheel/archive hashes, not bot claims.

[Dependabot](.github/dependabot.yml) scans Actions, Gradle and both Python locations
weekly; minor/patch groups and separate majors do not imply automerge. SHA-pinned
Action version scans are not a promise of immediate advisory/security-update PRs.
PR workflows and tests must not use secrets or live credentials. The explicit advanced CodeQL workflow scans Actions, Java/Kotlin and Python; its code-scanning rule is separate
from required status checks.

See [code-quality policy](docs/code-quality.md) and the
[metric chart](https://leugenea.github.io/codexbar-mobile/dev/bench/).
Metrics are informational, not coverage or merge-policy substitutes. Exact check
names and owner-only configuration recommendations are in
[repository protection](docs/repository-protection.md).

## Version, packages and unsigned release preparation

[VERSION](VERSION) is the single development target, initially `0.1.0`.
[version_identity.py](tools/build/version_identity.py) supplies Gradle's version inputs.
Normal builds use `RELEASE_CHANNEL=dev` (also the default), even at an RC/stable tag.
The name is `<target>-dev.<reachable count since last stable ancestor>+g<12-digit checkout SHA>`.
With no stable ancestor the count is all commits reachable from HEAD. The nearest
stable ancestor is the one leaving the smallest reachable count; ties select the
highest stable version. A target before that ancestor is rejected. Shallow history,
malformed version-like `v[0-9]*` tags and conflicting official inputs fail with an
actionable error. Git reads local history only; there is no fetch or fallback.
Every compiling hosted checkout uses `fetch-depth: 0` and `persist-credentials: false`.
The compiled checkout SHA includes a PR's actual merge checkout, never a substituted head.

The namespace and Kotlin/test/capture class identities remain
`io.github.leugenea.codexbarmobile`. Debug's application ID is
`io.github.leugenea.codexbarmobile.dev`; release's is
`io.github.leugenea.codexbarmobile`. MainActivity is fully qualified in the source
manifest. Each package has its own AndroidX signature-only receiver permission.
These packages have isolated Android app data. This change does not uninstall,
migrate or promise upgrade compatibility with an earlier development install.

RC/stable builds require explicit `RELEASE_CHANNEL=rc|stable`, an exact
`RELEASE_TAG=v<target>-rc.<positive N>|v<target>` pointing at clean checkout HEAD,
and canonical integer `RELEASE_VERSION_CODE` (1..2100000000) strictly greater than
`RELEASE_PREVIOUS_VERSION_CODE` (0..2100000000). Development uses code 1 and rejects
all official tag/counter inputs. CI exercises official inputs only in disposable
hosted local clones: tagged dev, RC and stable actually assemble and inspect unsigned
APKs; source/tag/counter/shallow/malformed controls fail closed. No public tag or
production allocator is created. Later trusted allocation must prevent counter reuse
across official admissions and reruns; caller-supplied fixture counters are not that service.
Published stable tags must remain immutable.

The ordinary build job fully lints and assembles release, then
[release_artifact.py](tools/build/release_artifact.py) inspects its actual unsigned
bytes against independent checkout/version inputs. Permissions, signature-only
receiver, cleartext-off, backup-off, production debuggability, launcher, singleTask
and default-process policy must pass. The filename, byte size/SHA256, source SHA/tree,
run/attempt, build inputs and unsigned inspection are bound in `artifact-manifest.json`.
Immediate reverification repeats inspection and rejects byte or inventory drift.
Reports and manifests are evidence only; no job admits source or skips setup from them.
All outcomes are retained in the existing build artifact for seven days, including
`evidence/release/` and `evidence/release-fixtures/`. These APKs are non-distributable
signing inputs, not signed candidates or published releases.

Signing, production counters, candidate activation and publication remain separate
owner-gated work. A later signed candidate must be verified as actual signed bytes;
publication must use those exact accepted bytes, not an unreviewed rebuild. Physical
phone/live acceptance and an explicit owner GO remain necessary. Issue 145 provides
no key, secret, signing, public tag, Release or publication action.

For the default-to-advanced CodeQL rollout, the owner explicitly approved parent
disabling default setup after publishing the executable replacement and before
independent review, so advanced CodeQL can obtain green CI first. The parent has
read back `not-configured` and unchanged branch protections. The replacement was
published in [PR #151](https://github.com/leugenea/codexbar-mobile/pull/151).
This configuration change does not establish successful analysis or source approval.
After quick checks and publication of each correction, confirm all applicable
exact-head hosted checks, including `actions`, `python` and manual `java-kotlin`
analysis, pass before commissioning independent read-only complete-source review.
Then use the reviewed exact head for squash merge and verify exact-merge main
workflows and artifacts. Do not keep competing managed/advanced Java analyses running.
The default query suite and remote threat model are retained by omitting overrides.
The required status contexts and CodeQL error/high-or-higher protection remain unchanged.
