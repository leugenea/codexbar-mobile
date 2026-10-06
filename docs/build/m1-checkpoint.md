# M1 hosted toolchain checkpoint history

**Current implementation/build instructions:** [M1 offline shell](m1.md).
The four-state shell and behavior-bearing tests now replace the earlier runnable
placeholder. The exact independently audited metadata has been imported; see
[m1-integrity.md](m1-integrity.md) and its compact per-artifact source record.
Full candidate strict/native verification and independent review are still pending;
nothing in this history declares M1 complete or mergeable.

The sections below preserve **contemporaneous checkpoint wording**. References
to pending UI/tests or a “next checkpoint” describe that earlier source snapshot,
not the current shell implementation or permission to bypass its final gates.
M0 historical research and the approved M1 Gradle/AGP amendment remain intact.

## Third hosted checkpoint: toolchain stop/go, not final M1

Run **37461079663, attempt 1** checked out synthetic PR merge
`d7de7bf7d34e63aab9db5ba1fd9998a6fe966116` for source
`3bb5133a9ffe99bd62377d51a3813df46d793520`. The parent supplied verified
provenance/all **44** artifact hashes before this continuation. Discovery returned
resolution/lint/build/connected-dry-run **0**, and the actual lint XML contained
zero issues. The app/test APKs and both placeholder test source sets built.
The receipt observed Gradle **9.8.0**, AGP **9.4.1**, loaded KGP
**2.4.20-gradle96**, unchanged JDK **17.0.20.1+1**, and passed all version/compiler
assertions. This cleared the bounded toolchain stop/go for UI implementation,
not the known documented fully-supported-range caveat or native execution gate.

Strict verification still failed as expected for empty/unreviewed metadata.
The **364-component** discovery graph is evidence for independent audit, not
approved committed metadata. Native graph dry-run did not boot a device or run
smoke tests. The current candidate introduces no dependency-coordinate changes,
but its complete strict/native task execution still must establish graph
completeness and all final behavior gates after audited import.

---

# Historical phase-1 checkpoint notes

This was **phase 1 of issue #2**, not completed M1. It supplied a runnable-source
Material 3 placeholder and the selected toolchain so a draft PR could expose
resolution/compilation/lint failures early. No Android build or native launch was
performed locally. At that snapshot, the four disconnected/loading/error/demo
states and meaningful JVM/native behavior tests were pending. The two small
Kotlin tests were explicitly **linkage/compilation placeholders**, not smoke
acceptance, and have now been removed in favor of the full shell's actual tests.

## Current M1 pins and historical M0 baseline

`docs/research/m0/toolchain.json` is preserved as historical M0 evidence. The
owner-approved **October 6, 2026** M1 amendment in
`docs/build/m1-toolchain-amendment.json` supersedes **only Gradle and AGP** with
**9.8.0 / 9.4.1**. Root plugin and wrapper build pins are checked against that
receipt, and the hosted inventory asserts the actual loaded/resolved versions.
The unchanged inputs remain: min 26,
compile/target 37, SDK `platforms;android-37.0` revision 2, Build Tools 36.0.0,
Temurin 17.0.20.1+1, Kotlin/Compose compiler 2.4.20,
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

**Compatibility is still a hosted gate, not an assumption.** AGP 9.4 documents
API 37, minimum Gradle 9.6.0, Build Tools 36.0.0 and JDK 17. Kotlin 2.4.20's
current table lists fully supported maxima Gradle 9.7.0 / AGP 9.3.1; it permits
newer releases with caveats. This candidate is outside that fully supported
range. Keep Kotlin/compiler 2.4.20 and the loaded-KGP/all-artifact assertions;
stop on unresolved incompatibility rather than silently changing another pin.
M0's issue 522845800 (`JavaDocParser` / `List.removeLast()` on JDK 17) remains
historical risk evidence; run 37449844957 below did not observe that crash.
The owner override does not weaken mandatory lint or permit suppressions.

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

This section records the **historical 9.5.0 / 9.3.1** setup diagnosis and fixes;
it is not a current Gradle/AGP selection or proof of the amended toolchain.

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
The then-next hosted candidate had to prove target loading, the complete receipt and
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

Those setup corrections left the M0 pins, SDK DSL, lint severity, metadata,
diagnostic marker/route and mandatory workflow tasks unchanged. The subsequent
owner-approved version amendment below is explicit, not a rewrite of M0 evidence.

## Second hosted diagnosis and owner-approved M1 amendment

Run **37449844957, attempt 1**, checked out PR merge
`7639c374e07c90085e83c21ac6be885f5c473e11` for source candidate
`19b8466a675981db780c6fb24a054ca6e4b97e7b`. All **44** artifact-manifest hashes
were verified before interpreting the evidence. Discovery returned resolution 0,
lint 1, build 0 and connected dry-run 0. The 9.5.0 / 9.3.1 receipt observed loaded
KGP 2.4.20 and passed all compiler/version assertions. Both test source sets,
app/test APK packaging and the JVM linkage placeholder ran; no native test ran.
Lint executed and reported exactly four errors, not the historical parser crash:

- `AndroidGradlePluginVersion`: Gradle 9.8.0 available. The owner's reply
  **“Давай”** approves Gradle 9.8.0 / AGP 9.4.1 only. Gradle's current-release
  record and exact `v9.8.0` release commit agree; Google Maven's largest numeric
  stable AGP is 9.4.1, despite a `release` field naming 9.5.0-alpha08.
- `DataExtractionRules`: keep `allowBackup=false`, add explicit legacy full-backup
  rules and Android 12+ cloud-backup/device-transfer rules excluding all nine
  supported app-data domains at `.` (normal/device-protected roots, files,
  databases, preferences and external files). This is declarative backup policy;
  no credential store exists and backup/restore behavior is not runtime-proven.
  No cross-platform transfer/export or platform-specific identity is configured.
- `ObsoleteSdkInt`: move the adaptive icon from redundant `mipmap-anydpi-v26`
  to `mipmap-anydpi`, valid at min SDK 26.
- `MonochromeLauncherIcon`: the single default adaptive icon now carries the
  existing foreground drawable as its monochrome layer; remove the redundant v33
  copy. AOSP API 26's inflater ignores unrecognized child tags; API 33 recognizes
  `monochrome`. This source check is not native launcher/tint acceptance.

The amendment and wrapper-source receipt record independently retrieved official
sources/hashes. The wrapper JAR matches the separately published Gradle SHA-256,
both scripts are byte-exact official release sources (including batch CRLF),
and the AGP POM matches Google's published SHA-256 and SHA-1. POM integrity is
not binary or complete dependency-graph trust. The Gradle distribution SHA-256
is pinned; the distribution itself will be downloaded/verified by hosted CI.

**Required next checkpoint:** publish this diagnostic candidate, bind/download
the new exact-head/attempt artifact, verify its manifest, and review new loaded
KGP/compiler 2.4.20, JDK/SDK receipts, lint, compilation and command exit statuses.
Keep empty/unreviewed committed metadata and the diagnostic marker until the
new **9.8.0 / 9.4.1 graph** is independently audited. Do not copy the old 9.3.1
graph or call discovery PASS strict acceptance. The mandatory strict gate is
still expected red. Only reviewed new-graph metadata plus fresh-home strict
replay can clear it. Full UI states/tests and native smoke remain subsequent M1
work; no gate is disabled, baselined or reduced here.

Additional primary references checked on October 6, 2026:

- Gradle release/current, wrapper/distribution checksums and exact release-source
  URLs: `docs/build/m1-toolchain-amendment.json`, `docs/build/wrapper-source.json`.
- AGP compatibility: https://developer.android.com/build/releases/agp-9-4-0-release-notes
- Kotlin supported ranges/caveats: https://kotlinlang.org/docs/gradle-configure-project.html
- Legacy/new backup syntax and domains: https://developer.android.com/identity/data/autobackup
- AOSP API 26/33 `AdaptiveIconDrawable.inflateLayers`:
  `https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/graphics/java/android/graphics/drawable/AdaptiveIconDrawable.java`,
  `https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/graphics/java/android/graphics/drawable/AdaptiveIconDrawable.java`.
- AOSP API 31 backup-domain/path parsing:
  `https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/app/backup/FullBackup.java`.

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
   originals; record provenance and investigate discrepancies. M0's retained POM
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
- Gradle verification bootstrap/trust limitations: https://docs.gradle.org/9.8.0/userguide/dependency_verification.html
- Dispatch default-branch requirement: https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- AndroidX supplemental exact versions: official Google Maven metadata/POMs,
  `https://dl.google.com/dl/android/maven2/`.

Existing M0 documentation, fixtures, tests and workflow are unchanged.
