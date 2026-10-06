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

## First hosted diagnosis and bounded setup corrections

Run **37447243358, attempt 1**, checked out PR merge
`787cbf70e3702c2b718618c6011b6e414086b316` for candidate
`e346eaec27d92d30419061251f8abf31574879f4`. All four discovery commands
failed. These failures do **not** establish the documented lint incompatibility:

- Strict failed at the AGP plugin-marker POM, as expected with intentionally empty
  committed verification metadata. That gate remains unchanged/fail-closed.
- Inventory failed at init-script line 21 for `debugAndroidTestCompileClasspath`:
  AGP's tested-variant self-project exposes multiple `debugApiElements` artifact
  types. Filtering module identifiers **after** resolving all artifacts is too
  late. The inventory now uses Gradle's documented module-only `ArtifactView`
  component filter **before** artifact selection. It retains configuration
  attributes, non-lenient resolution, every existing configuration and all
  version/SHA assertions. Real AGP compile/package tasks still select self-project
  artifacts; no native/test configuration is omitted.
- Lint, build and connected graph observation failed during task-dependency
  calculation: `Failed to find target with hash string 'android-37.0'`. Lint and
  compilation never executed. The exact independently downloaded platform ZIP
  matches M0 SHA-1 and contains **no `package.xml`**. Its unmodified
  `source.properties` has `AndroidVersion.ApiLevel=37.0`. AGP 9.3.1 consumes sdklib
  32.3.1, whose legacy `AndroidVersionHelper.create` calls `Integer.parseInt` on
  that field and returns null for `37.0`; `LocalSdk.scanPlatforms` skips it.
  Normal SDK installation writes local `package.xml` from the repository record.
  The installer had only unpacked/moved the ZIP and omitted this step.

`write_sdk_package_metadata.py` supplies that missing bounded metadata step for
**only the two selected M0 packages**. It retrieves the official repository v4
record, validates exact selected archive URL/checksum algorithm/checksum,
revision and stable platform details, and writes local metadata following the
published `InstallerUtil.writePackageXml` field mapping. It preserves the license
and QName namespace bindings and drops remote-only archive/channel fields.
Neither SDK payload nor `source.properties` is edited. A changed upstream
revision/archive is a hard error, not permission to install a newer package.
The full retrieved repository XML and both installed metadata records are saved
in hash-bound evidence. Using an additional sdkmanager distribution here would
add a separately pinned executable/download without removing the need to guard
against a later revision of the same package path; this adapter keeps the exact
already-verified payload archives and contains no package-selection framework.

Offline regression fixtures are explicitly synthetic. The generated metadata
from the **actual retrieved Google records** also passed XML Schema validation
against SDK/repository XSDs extracted from AGP's published sdklib/repository
32.3.1 JARs (local import locations only). This proves schema/metadata shape, not
an executed SDK load. No local SDK install, Gradle build or emulator was run.
The next hosted candidate must prove target loading, the complete receipt and
actual lint execution. Both 2.4.0 and 2.4.20 compiler downloads appear in the first
resolution log; the existing all-row 2.4.20 assertion is retained and any actual
receipt mismatch must be investigated, not hidden by filtering versions.

Primary implementation sources inspected (exact versions, not `main`):

- `https://dl.google.com/android/repository/repository2-4.xml` — selected remote
  records, stable API `37.0`, extension 22/base SDK and original archive checksums.
- `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.1/gradle-9.3.1-sources.jar`
  — `SdkDirectLoadingStrategy.kt` (`package.xml` platform load),
  `VariantDependenciesBuilder.java` (tested-app self-project dependency),
  `VariantDependencies.kt` (AGP's artifact views).
- `https://dl.google.com/dl/android/maven2/com/android/tools/sdklib/32.3.1/sdklib-32.3.1-sources.jar`
  — `AndroidVersionHelper.java`, `LocalSdk.java`, `AndroidTargetManager.java`,
  `DetailsTypes.java` (supported string major/minor API metadata).
- `https://dl.google.com/dl/android/maven2/com/android/tools/repository/32.3.1/repository-32.3.1-sources.jar`
  — `AbstractInstaller.java`, `InstallerUtil.java`, `LocalRepoLoaderImpl.java`.
- `https://docs.gradle.org/9.5.0/userguide/artifact_views.html` — documented
  `ModuleComponentIdentifier` component filter, without leniency/reselection.

The M0 baseline, compile SDK DSL, lint severity, verification metadata, diagnostic
marker/route and all mandatory workflow tasks are unchanged. A real
`JavaDocParser`/`List.removeLast()` lint crash or unresolved selected baseline
compatibility still triggers the owner stop condition above.

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
