# Contributing

## Scope and workflow

Work from a GitHub issue; use bounded sub-issues for distinct delivery units.
Keep repository prose, code comments, issue/PR text and commit messages in English.
Discuss behavior changes before expanding scope. Add meaningful tests for behavior
changes; TDD/test-first or deliberately failing CI is not required.

Keep changes small and obtain independent full-diff review. Merge only the
reviewed exact head with all applicable hosted checks green, using squash merge;
verify all applicable main workflows and artifacts at the exact merge SHA.
Documentation-only PRs are not exempt. Store history and review evidence in
GitHub issues/PRs, not committed receipts or generated reports.

Never post tokens, passwords, device codes or raw account data. Share only
sanitized diagnostics, synthetic fixtures, safe reproduction steps and CI links.
Use [SECURITY](SECURITY.md) for vulnerabilities, not public issues.

## Current toolchain

The build files and workflows, not the historical M0 selection, are authoritative:
Gradle 9.8.0 (checksum-pinned wrapper), AGP 9.4.1, Kotlin/Compose compiler 2.4.20,
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

## Hosted runtime and coverage

[Android CI](.github/workflows/android.yml) uses hosted Ubuntu 24.04, isolated SDKs
and separate Gradle user homes. The basic dependency cache is enabled; fork PRs
have read-only cache access. Strict verification remains mandatory on cache hits.
Build/configuration caches are disabled and acceptance tasks rerun.

Build job command (the init script defines the observation task):

```sh
./gradlew --no-daemon --dependency-verification strict --no-build-cache \
  --no-configuration-cache --rerun-tasks --stacktrace --info --continue \
  -I tools/build/toolchain.init.gradle :app:verifyResolvedToolchain \
  :app:lintDebug :app:assembleDebug :app:compileDebugUnitTestKotlin \
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:assembleDebugAndroidTest
python3 tools/build/verify_manifests.py
python3 tools/build/verify_test_reports.py jvm
```

The workflow requires KVM; its native script prepares API 36
`google_apis;x86_64` phone infrastructure,
waits for ADB/boot readiness, then invokes JVM/native tests and
`:app:jacocoDebugReport` / `:app:jacocoDebugCoverageVerification`; it checks native
JUnit results and records phase outcomes. See the script for its hosted-only
command and timeout environment, rather than inventing a local device procedure.

The script permits **one bounded retry** only after passing JVM/native suites,
with ADB-offline evidence at collection and empty or truncated native coverage
from the same checkout/run/attempt. It preserves the failed graph's data and
logs, waits for device readiness, then reruns the complete JVM/native/coverage
graph within the original 15-minute graph budget. It never combines coverage
across attempts or retries failing tests, timeouts, other invalid datasets or a
coverage-threshold failure. See [the retry guard](tools/build/native_coverage_retry.py).

Coverage minimum: **90%** JaCoCo **INSTRUCTION** over the compatible JVM +
instrumented execution-data union. JaCoCo 0.8.15 analyzes the complete debug
project-class snapshot, including handwritten Activity/Compose/lambda/companion
code. Only Android-generated R/BuildConfig/Manifest identities are excluded;
there are no handwritten-code exclusions. Class IDs, probe counts, freshness,
nonzero denominator and XML/inventory consistency are validated. Never lower the
threshold or broaden exclusions. Historical 95% statements in M0 remain historical
and are superseded for current acceptance by the 0.90 rule in [app/build.gradle](app/build.gradle).

Both Android jobs upload all-outcome artifacts:
`android-build-<run_id>-<run_attempt>` and `android-instrumented-<run_id>-<run_attempt>`,
retained for seven days. Inspect SDK/toolchain/build logs, lint/JUnit reports,
merged manifests and debug APKs; native artifacts also contain emulator/logcat,
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
PR workflows and tests must not use secrets or live credentials.

See [code-quality policy](docs/code-quality.md) and the
[metric chart](https://leugenea.github.io/codexbar-mobile/dev/bench/).
Metrics are informational, not coverage or merge-policy substitutes. Exact check
names and owner-only configuration recommendations are in
[repository protection](docs/repository-protection.md).
