# M1 early hosted toolchain checkpoint — not mergeable

This is **phase 1 of issue #2**, not completed M1. It supplies a runnable-source
Material 3 placeholder and the selected toolchain so a draft PR can expose
resolution/compilation/lint failures early. No Android build or native launch has
been performed locally. The four disconnected/loading/error/demo states and
meaningful JVM/native behavior tests are still pending. The two small Kotlin tests
are explicitly **linkage/compilation placeholders**, not final smoke evidence.

## Selected baseline

`docs/research/m0/toolchain.json` remains unchanged and authoritative: min 26,
compile/target 37, SDK `platforms;android-37.0` revision 2, Build Tools 36.0.0,
Temurin 17.0.20.1+1, Gradle 9.5.0, AGP 9.3.1, Kotlin/Compose compiler 2.4.20,
Coroutines 1.11.0, Compose BOM 2026.09.00/UI 1.12.1/Material3 1.4.0.
The explicit `release(37)`/minor level 0 SDK DSL selects the pinned 37.0 package.
AGP built-in Kotlin uses the documented higher-KGP root `buildscript` classpath;
there is deliberately no `org.jetbrains.kotlin.android` plugin.

Additional shell/test dependencies are exact: activity-compose 1.13.0,
JUnit 4.13.2, AndroidX test runner 1.7.0 and ext-junit 1.3.0. Their graph is
**not yet reviewed**. The diagnostic classpath inventory/assertions check loaded
KGP origin, actual resolved compiler/Compose compiler modules, AGP, Gradle, JDK,
Coroutines and UI/Material3; compile `--info` logs must also be reviewed. A version
string in build configuration is not a resolved/compiler-executed receipt.

**Stop condition:** M0 documents AGP issue 522845800 (JDK 17
`JavaDocParser` / `java.util.List.removeLast()` lint crash; fixed in AGP 9.3.2).
The selected 9.3.1/17 versions are retained. Hosted lint is mandatory and its
failure is preserved. If it fails or compatibility remains unresolved, stop for
an owner baseline decision; do not upgrade, suppress, ignore or weaken lint.

## First draft PR: diagnostic discovery, strict gate still red

The new workflow cannot initially be manually dispatched: GitHub requires its
`workflow_dispatch` definition to exist on the default branch. **Do not merge a
scaffold to enable dispatch.** Publishing the authorized draft PR triggers this
workflow via `pull_request`. While `gradle/m1-bootstrap-diagnostic` exists, that PR
run also performs explicit UNTRUSTED discovery in its ephemeral runner worktree.
Manual discovery is optional/default false and useful only after the workflow
has legitimately reached the default branch.

`gradle/verification-metadata.xml` is intentionally empty, not reviewed. The
normal mandatory command uses **strict committed verification**, does not write
metadata and must fail closed. A successful discovery step does not turn this
job green or establish acceptance. The strict and discovery invocations use
separate fresh Gradle homes and no Gradle cache restore. Permissions are
read-only contents; Actions are immutable SHA pins, checkout does not persist
credentials, and the runner is GitHub-hosted Ubuntu 24.04.

Discovery first resolves debug/test/compiler/lint configurations, then runs lint
early, then assembles/compiles/tests the graph, and observes the eventual
connected-test task graph with `--dry-run`. It writes SHA-256 verification
metadata ONLY in the runner worktree. Each command's exit status and log are
saved; an early lint failure does not discard discovered metadata or hide its
failure. No emulator/native test is run by this checkpoint.

Parent read-only commands after publishing the draft PR:

```sh
gh run list --repo leugenea/codexbar-mobile --workflow m1-toolchain.yml \
  --branch feat/2-m1-bootstrap --json databaseId,headSha,status,conclusion
gh run view RUN_ID --repo leugenea/codexbar-mobile --json headSha,jobs
gh run download RUN_ID --repo leugenea/codexbar-mobile \
  --name m1-toolchain-RUN_ID-ATTEMPT --dir TASK_EVIDENCE_DIRECTORY
```

Bind the artifact to `provenance.json`, `checkout-sha.txt`, exact run/attempt and
candidate/PR merge revision. Use `artifact-files.sha256.json` to check every
uploaded evidence/build/report file. The APK remains diagnostic until strict
replay and native smoke pass. Inspect SDK source.properties (`Pkg.Revision`),
JDK release, actual resolved-toolchain JSON and all discovery exit statuses/logs.

## Independent integrity review and strict replay

1. Verify wrapper JAR independently against Gradle's published wrapper-JAR
   SHA-256, and scripts against the exact release commit in `wrapper-source.json`.
   The distribution checksum is separately pinned in wrapper properties.
2. Verify hosted JDK archive SHA-256 and SDK archive SHA-1 against unchanged M0
   selections; SDK upstream SHA-1 is not claimed to be SHA-256 security. The
   runner additionally records SHA-256 of those exact downloaded archives.
3. Audit **every** discovered component/artifact/metadata checksum and actual
   version for the complete selected graph, including plugins, compiler, AARs,
   JVM/native-test dependencies and lint. Compare to independent primary
   publication checksums/signatures where available and independently retrieved
   originals; record provenance and investigate discrepancies. M0's three POM
   hashes are useful cross-checks, not binary or whole-graph trust. A self-generated
   inventory, a duplicate mirror download or a successful build alone is not an
   independent review. Do not blindly copy discovery output into a reviewed file.
4. Commit reviewed metadata only after review, retire the phase-specific
   empty-metadata test in `tools/build/test_scaffold.py`, remove the diagnostic
   marker **and PR discovery route** before final review/merge. Keep only the
   default-false optional manual route if desired. Re-run strict on the exact
   amended candidate, with a genuinely fresh Gradle home.
5. Re-discover/audit any new dependencies or configurations introduced by the
   full M1 UI/tests; early placeholder metadata is not automatically complete
   final M1 metadata. Native/meaningful behavior acceptance still remains.

Literal hosted strict graph (the workflow performs this; **do not run on NAS**):

```sh
GRADLE_USER_HOME="$RUNNER_TEMP/m1-gradle-strict" ./gradlew \
  --no-daemon --dependency-verification strict --stacktrace --info --continue \
  -I tools/build/toolchain.init.gradle :app:m1ToolchainCheckpoint \
  :app:lintDebug :app:assembleDebug :app:compileDebugUnitTestKotlin \
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

`assembleDebugAndroidTest` resolves/packages the native test APK without a
device. `connectedDebugAndroidTest --dry-run` is graph evidence only. Actual
hosted connected smoke and the full UI/JVM tests belong to the subsequent M1
continuation; M2 extends this infrastructure for complete CI/coverage. This
workflow does not implement M2, Dependabot, public-repository completeness or
FIRST M3 native authentication.

## Cheap local checks only

```sh
python3 -B -m unittest discover -s tools/build -p 'test_*.py' -v
bash -n tools/build/install-hosted-toolchain.sh
shellcheck tools/build/install-hosted-toolchain.sh
actionlint .github/workflows/m0-contract.yml .github/workflows/m1-toolchain.yml
```

No runtime networking library, transport, INTERNET permission, provider call,
authentication, credential store or persistent history/graph is implemented.
The launcher text explicitly says unofficial, placeholder demo and not
authenticated. Source declarations do not replace inspection of the merged
manifest/APK or native launcher/accessibility execution.

## Primary references checked for this checkpoint

- AGP built-in Kotlin/higher KGP: https://developer.android.com/build/releases/agp-9-0-0-release-notes
- Kotlin migration/DSL: https://developer.android.com/build/migrate-to-built-in-kotlin
- Compile SDK release/minor API: https://developer.android.com/reference/tools/gradle-api/9.3/com/android/build/api/dsl/CompileSdkSpec
- Selected lint hazard: https://developer.android.com/build/releases/agp-9-3-0-release-notes
- Gradle verification bootstrap/trust limitations: https://docs.gradle.org/9.5.0/userguide/dependency_verification.html
- Dispatch default-branch requirement: https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- AndroidX supplemental exact versions: official Google Maven metadata/POMs,
  `https://dl.google.com/dl/android/maven2/`.

Existing M0 documentation, fixtures, tests and workflow are unchanged.
