# M2 debug coverage and hosted runbook

Current owner amendment: **2026-10-06 — at least 90% JaCoCo INSTRUCTION
COVEREDRATIO**, superseding the historical 95% target. This is not a line,
statement, branch, or JVM-only target. Historical M0/M1 evidence is unchanged.
This document specifies the gate; only exact-head hosted execution and inspected
artifacts establish that the candidate passes it.

## Preserved ladder and stable results

Every pull request, main push, and manual run retains M0 research and both M1 jobs.
There are no path filters, suite filters, new SDK/toolchain pins, permission
expansions, cache redesigns, or metadata-generation steps. M1's strict checkpoint
still runs assembly, lint, both test compilations, the complete JVM suite, and the
native-test APK. M1's native job retains its API 36 Google APIs x86_64 `pixel_2`,
libpulse prerequisite, KVM requirement, bounded readiness and cleanup, setup
Action SHAs, dependency/wrapper cache and diagnostic-only screenshot.

The native job now executes this **one Gradle task graph**:

```sh
./gradlew --no-daemon --dependency-verification strict \
  --no-build-cache --no-configuration-cache --rerun-tasks \
  --stacktrace --info --console=plain -I tools/build/toolchain.init.gradle \
  :app:m1ToolchainCheckpoint :app:compileDebugUnitTestKotlin \
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest :app:jacocoDebugCoverageVerification
```

`m2PrepareDebugCoverage` deletes previous debug datasets/reports/JUnit outputs and
writes checkout/source-head/run/attempt/start context before either suite.
`m2DebugCoverageInputs` depends on both full suites and consumes the public AGP
`ScopedArtifacts.Scope.PROJECT` / `ScopedArtifact.CLASSES` artifact (JARs **and**
directories). This artifact is pre-JaCoCo instrumentation and includes final
project bytecode; it does not rely on donor `tmp/kotlin-classes/debug` or on a
package include pattern. AGP 9.4.1 built-in Kotlin uses
`intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`, but that path is
an observation, not our class discovery API.

The report and verifier consume the identical copied class snapshot and the
literal JVM `outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`
plus **all** native `outputs/code_coverage/debugAndroidTest/connected/**/*.ec`.
JaCoCo merges probes by compatible class ID (logical union, not sum of separate
report ratios). No hidden second test invocation is used. XML and HTML are emitted
by `jacocoDebugReport`; `jacocoDebugCoverageVerification` applies the Ant
INSTRUCTION/COVEREDRATIO 0.90 rule and an independent fail-closed XML validation.

Check names remain `M1 strict toolchain checkpoint` and `M1 native offline smoke`.
The additional **`M2 Android result`** runs with `if: always()` and requires both
mandatory job conclusions to be exactly `success`. Failure, skipped, cancelled,
empty and unknown conclusions all fail. It neither downloads code nor needs a
checkout/token. Offline tests execute the workflow's actual shell truth table.
The separate M0 check stays mandatory as before; the M2 result aggregates the two
jobs within the Android workflow, not results from a different workflow.

## Denominator, exclusions and fail-closed checks

Every project `.class` is analyzed by the exact pinned JaCoCo analyzer and receives
name, source filename, class ID, instruction count, original-byte SHA256 and
snapshot path in `inventory.json`. This includes `MainActivity`, Compose
associated bytecode/`ComposableSingletons`, lambdas, companions, Kotlin data and
enum classes. There are **no handwritten production exclusions**.

Only these Android platform-generated identities are excluded if present:

| Identity | Reason |
| --- | --- |
| `R`, `R$*` | Resource identifier tables emitted by Android tooling, not application logic. |
| `BuildConfig` | Android build-constant class, when generation is enabled. |
| `Manifest`, `Manifest$*` | Android-generated manifest/permission constants, if emitted. |

Each encountered exclusion is recorded. An identity whose SourceFile matches a
handwritten Java/Kotlin source under `src/main` is rejected instead of excluded.
There is no `*Activity*`, package, lambda, Compose or `ComposableSingletons`
exclusion. JaCoCo intrinsically filters compiler patterns (including supported
Kotlin/Compose patterns); these are analyzer behavior, not repository exclusions.
Zero-instruction classes remain in both the inventory and XML (their zero-valued
counters are omitted by JaCoCo). Every inventory class must be represented exactly
once; per-class and total counters must equal the analyzer denominator. The
complete snapshot is also analyzed together so cross-class Kotlin inline-fragment
accounting uses the same CoverageBuilder behavior as the real report.

Missing exec/ec, empty or duplicate class inventories, empty/no-compatible
production execution records, different class IDs for the same name, conflicting
probe counts, stale files/sessions, wrong checkout/head/run/attempt, changed input
hashes, stale XML, absent/duplicate/wrong counters, changed/zero denominator, or a
ratio below 0.90 fail with diagnostics. The integer comparison is
`covered * 10 >= (covered + missed) * 9`; rounded percentage displays never decide
the gate. Session timestamps permit two seconds of host/device scheduling skew,
not previous-run execution data. Old outputs are removed before testing.

## Phase receipts and all-outcome evidence

`evidence/native/m2-phase-outcomes.json` separates boot, full JVM,
instrumentation, report, and verification outcomes. Real after-task observations
(`M2_TASK_OUTCOME`) distinguish terminal success/failure from start banners,
UP_TO_DATE/NO_SOURCE/skipped and not-run. A failed coverage verifier after passing
connected tests remains **instrumentation SUCCESS / verification FAILED**. The
original graph exit is preserved; cleanup/parser failures cannot turn it green.
The complete M1 JVM/native JUnit and manifest validators still run even when a
later coverage phase failed.

The existing `m1-native-RUN_ID-ATTEMPT` artifact is uploaded with `if: always()`:

- `evidence/`: source/PR head/base/event/checkout identity, actual run/attempt,
  setup/device/boot/logcat/emulator/cleanup receipts, graph log and phase outcomes;
- `app/build/test-results/` and `outputs/androidTest-results/`: **native-job JVM**
  XML and unfiltered connected JUnit, not just the earlier checkpoint suite;
- `app/build/reports/jacoco/jacocoDebugReport/`: XML and full HTML tree;
- `app/build/outputs/unit_test_code_coverage/` and `outputs/code_coverage/`:
  original exec and ec;
- `app/build/m2-coverage/`: context, full class-ID/instruction/SHA inventory,
  resolved coverage-tool artifact hashes, validated inputs, class snapshot, and
  successful XML verification receipt when produced;
- existing APKs, merged manifests, build/lint/test reports and diagnostics.

`evidence/artifact-files.sha256.json` binds actual files including coverage data
and inventory. Verify it and provenance before reading counters. Earlier failures
may legitimately leave later artifacts absent; that never satisfies coverage.
The native job's all-outcome binder emits a NOT_RUN receipt even when setup failed
before the native script started. Artifacts are not a signed release or a gallery.

## JaCoCo pin and independently audited delta

**JaCoCo 0.8.15** was selected from the complete Maven Central stable version lists
on 2026-10-06 (published 2026-06-04), not from the donor's 0.8.13 or Maven's
`latest` field alone. Java 17 is supported. Kotlin-specific analyzer filters are
present, but no explicit Kotlin 2.4.20 compatibility guarantee was found: actual
AGP 9.4.1/Kotlin 2.4.20/JDK 17 execution is still the acceptance boundary.

Both `android.testCoverage.jacocoVersion` and `jacoco.toolVersion` are pinned.
The inspected AGP 9.4.1 `DependencyConfigurator.configureJacocoTransforms` still
requests its hard-coded 0.8.14 default; a narrow `org.jacoco` resolution rule pins
the actual agent/Ant/analyzer binaries to 0.8.15 without patching AGP internals.
The inventory asserts the loaded analyzer version and all resolved coverage
configuration JaCoCo versions and hashes, making this adaptation observable.

[`m2-coverage-integrity.json`](m2-coverage-integrity.json) records exact artifact
identities, original Central URLs, freshly computed SHA256, publisher comparisons
(including sidecar HTTP status/values), trust tiers, selected version metadata
hashes, and agent-runtime byte equality. The allowed delta is **9 components /
17 identity-checksum pairs**, added to the unchanged **364/602 M1 base**. Tests
reconstruct and hash the byte-identical original XML and preserve M1's historical
semantic digest, audit records, original tests and trust tiers. Full union is
373 components / 619 pairs, semantic SHA256
`ba94604993d8d03d66d6e8b48fcdeb258aec36b62c3e851da32a6bed064434a3`.

The delta covers JaCoCo agent (ordinary and runtime-classifier JARs), Ant, core,
report and their POM/parent closure, and ASM asm/asm-commons/asm-tree/asm-bom
9.10.1. The existing ow2 1.5.1 parent is reused. Ant itself is provided scope,
agent.rt test scope: neither authorizes new runtime components.

Seven new artifacts matched publisher SHA256; ten JaCoCo artifacts have no
publisher SHA256 sidecar and matched **weaker published SHA1 plus freshly
retrieved original SHA256**. This is the same explicit weaker trust tier used by
M1, **not authenticated publisher identity or a signature claim**. No checksum
was trusted merely because Gradle generated it. No metadata-generation command
is used in normal CI. A POM-declared audited closure is not a real Gradle graph:
inspect the hosted exact resolved artifacts; any additional artifact blocks the
strict build until independently audited and reviewed.

Primary sources inspected:

- `https://repo1.maven.org/maven2/org/jacoco/org.jacoco.core/maven-metadata.xml`
  and agent/ant/report metadata; exact artifact/POM/sidecar URLs in the receipt;
- `https://www.jacoco.org/jacoco/trunk/doc/changes.html`;
- `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1-sources.jar`
  (`DependencyConfigurator`, `JacocoVersionUtils`, `JacocoConfigurations`,
  `KotlinCompileCreationAction`, native report and execution-data paths);
- `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle-api/9.4.1/gradle-api-9.4.1-sources.jar`
  (`ScopedArtifact.CLASSES`, `ScopedArtifactsOperation.toGet`, `TestCoverage`).

## Coverage sufficiency and delivery

The current two production Kotlin files have eight JVM model tests and three
native Activity/UI tests. Model boundary tests plus native state transitions,
fixture rendering and recreation are complementary, but Compose-associated
instructions make a source-only numeric forecast unreliable. No 90% result is
claimed here and no speculative behavior tests/refactor/exclusions were added.
If the literal hosted XML shows a deficit, rank missed instructions by
class/method, add meaningful tests for the measured behavior, and rerun the same
union gate. Never lower the ratio or replace the denominator to pass.

Quick local checks only (no local Gradle/SDK/JVM/emulator):

```sh
M1_CONTRACT_SCRATCH=/task-owned/scratch python3 -B -m unittest discover -s tools/build -p 'test_*.py' -v
bash -n tools/build/verify-hosted-toolchain.sh tools/build/run-hosted-native-smoke.sh
shellcheck tools/build/verify-hosted-toolchain.sh tools/build/run-hosted-native-smoke.sh
actionlint .github/workflows/m0-contract.yml .github/workflows/m1-toolchain.yml
git diff --check
```

The offline fixtures are explicitly synthetic parser/contract evidence, not
class-ID, execution, or coverage acceptance. No mandatory TDD/RED publication.
The parent publishes the frozen draft candidate, runs independent whole-candidate
review and complete hosted gates, inspects all phase/JUnit/tool/data/XML/hash
receipts, consolidates bounded fixes, and requires fresh exact-head approval and
CI. Only an authorized merge followed by exact-merge main workflow/artifact
readback can complete issue #14. Protection settings are owner-managed and are
not modified by this implementation.

## Donor reuse and retained MIT notice

Adapted from `leugenea/qmix`, committed clean main
`0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0`:

- `android/app/build.gradle.kts`: debug data/report/verification task seam →
  `app/build.gradle`; Groovy, target public AGP artifact API, 0.8.15, 0.90 and
  explicit freshness/class-ID/denominator checks; offline coverage fixtures.
- `.github/workflows/android.yml`, `result` job →
  `.github/workflows/m1-toolchain.yml`, `M2 Android result`; no routes or allowed
  skips, same target mandatory jobs; offline tests execute all outcome pairs.

No donor TV/Go/toolchain/95%/screenshots/complexity/clone/SBOM policy is imported.
The target owns future updates. JaCoCo's EPL and ASM's license are separate from
donor licensing; the MIT notice does not relicense dependency tooling.

> MIT License
>
> Copyright (c) 2026 QMix contributors
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.
