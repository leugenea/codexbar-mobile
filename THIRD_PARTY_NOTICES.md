# Third-party notices

Repository-authored code is [MIT](LICENSE), Copyright (c) 2026 Luckyanets Eugene.
That license does not replace upstream terms. This is a source/dependency-scope
notice, **not an APK-bound SBOM or completed distribution-license audit**. Preserve
upstream copyright, license and NOTICE material when distributing copied portions.

## Application runtime dependencies

The declared app dependencies below are runtime inputs; the BOM supplies version
constraints rather than executable code. Exact final APK contents and the complete
configuration-resolved transitive closure still need hosted verification.

| Component / selected version | Attribution | Declared license |
| --- | --- | --- |
| Compose BOM 2026.09.00 | Android Open Source Project / AndroidX contributors | Apache-2.0 |
| Compose UI 1.12.1, Material3 1.4.0 | Android Open Source Project / AndroidX contributors | Apache-2.0 |
| Activity Compose 1.13.0 | Android Open Source Project / AndroidX contributors | Apache-2.0 |
| kotlinx-coroutines-android / core-jvm 1.11.0 | JetBrains / Kotlin contributors | Apache-2.0 |
| Kotlin standard library (2.4.20 present in the acquisition graph) | JetBrains / Kotlin contributors | Apache-2.0; selected runtime version not yet established here |

Additional runtime candidate families in the reviewed acquisition graph include
Compose animation/foundation/runtime, AndroidX lifecycle/core/collection,
navigationevent/savedstate/window/profileinstaller/annotations and JSpecify.
AndroidX and Kotlin families declare Apache-2.0; JSpecify declares Apache-2.0.
Their presence in verification XML is not proof that each ships: XML also contains
parents, POMs, BOMs, multiple versions and build/test inputs. Before release,
resolve debug/release/Android-test configurations at the exact head and select
license/NOTICE texts against the actual APK, including embedded components.

## Build, test and repository tooling (not a release-runtime list)

| Component | Use / license findings |
| --- | --- |
| Gradle 9.8.0, AGP 9.4.1, Kotlin Gradle/Compose compiler 2.4.20 | Build/compiler; primary projects Apache-2.0 |
| Temurin / OpenJDK 17 | Build/JVM; GPL-2.0 with file-specific Classpath exception and additional notices |
| Android SDK, Build Tools, command-line tools, emulator and API 36 Google APIs image | SDK/component agreement terms; not blanket Apache-2.0 |
| JUnit 4.13.2 | JVM tests; EPL-1.0 |
| AndroidX Compose UI tests/manifest, test runner 1.7.0 and test-ext JUnit 1.3.0 | Native test APK/debug-only support; Apache-2.0 |
| JaCoCo 0.8.15 / ASM | Debug coverage; EPL-2.0 / BSD-3-Clause |
| Python and typing-extensions | CI validators; PSF / PSF-2.0 plus bundled notices |
| tree-sitter 0.25.2 / tree-sitter-kotlin 1.1.0 | Metrics-only wheels; MIT / MIT |
| jscpd 5.3.2 | Duplication tool; MIT primary project, bundled notices unresolved |
| jsonschema, attrs, jsonschema-specifications, referencing, rpds-py | Research/policy stack; MIT declarations, native bundled notices unresolved |
| PyYAML 6.0.3 | Policy parser; MIT, Ingy döt Net / Kirill Simonov; bundled LibYAML notices not fully audited |
| actionlint 1.7.12 / ShellCheck 0.11.0 | Workflow/shell tools; MIT / GPL-3.0; not APK dependencies |
| SchemaStore Dependabot schema | Validation input; Apache-2.0 at the pinned revision in repository policy |

Other acquisition-graph distinctions include JUnit5/Platform EPL-2.0;
protobuf/Hamcrest BSD-3-Clause; JNA LGPL-2.1/Apache-2.0 declarations (applicable
alternative needs distribution review); juniversalchardet MPL-1.1;
jopt-simple/checker-qual/slf4j MIT; JAXB/activation/istack/StAX EDL-1.0 and
parent-only EPL/GPL Classpath declarations; FastInfoset Apache-2.0/EDL-1.0.
These are not asserted release-runtime dependencies.

GitHub Actions are CI tooling. Pinned checkout, setup-java, setup-python,
upload/download-artifact, upload/deploy-pages and setup-android primary sources
are MIT. `gradle/actions/setup-gradle` has an MIT core/basic cache plus separately
bundled proprietary enhanced-cache files; current workflows select `basic`, not
a wholly MIT distribution. The benchmark Action declares MIT in package metadata,
but its pinned tree lacks a root LICENSE text; verification remains open.
JavaScript-bundle transitive notice completeness has not been audited.

## Borrowed QMix source

Adapted from `leugenea/qmix` committed main
`0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0`: metric implementations/tests,
coverage Gradle/workflow seams and the Action-line/pin scanner in repository
policy. Copyright (c) 2026 QMix contributors; MIT.

The full copyright/permission/warranty notice is retained in
[tools/metrics/LICENSE](tools/metrics/LICENSE) and inline in
[app/build.gradle](app/build.gradle) and
[repository_policy.py](tools/policy/repository_policy.py).
Public guides use the donor's section structure with Android phone-specific prose;
this project's readiness checker is separately authored, not copied donor code.

## Research fixtures

See the exact provenance and transformations in
[fixture attribution](docs/research/m0/fixtures/ATTRIBUTION.md).

- CodexBar weekly-only mock: Copyright (c) 2026 Peter Steinberger, MIT;
  [license retained](docs/research/m0/fixtures/licenses/CodexBar-MIT.txt).
- OpenAI Codex reset-details mock, modified to remove profile image/user IDs:
  Copyright 2025 OpenAI, Apache-2.0;
  [license](docs/research/m0/fixtures/licenses/Codex-Apache-2.0.txt) and
  [upstream NOTICE](docs/research/m0/fixtures/licenses/Codex-NOTICE.txt) retained.
  Its Ratatui acknowledgement does not claim copied Ratatui application code.
- Synthetic/policy vectors and schemas are locally authored research artifacts;
  sanitized owner-reported observations are not captured HTTP bodies or native
  Android acceptance. Research fixtures are not current production app inputs.

## Open attribution work

No guessed approvals: resolve the exact runtime/test closure and APK-bound notices;
audit bundled/shaded/native components in Actions, jscpd, rpds/PyYAML, JDK/SDK and
host tooling; retrieve/verify the benchmark Action's missing license text.

Exact-version POM license gaps remain for `javax.inject:javax.inject:1`,
`net.sf.kxml:kxml2:2.3.0`, Bouncy Castle `bcpkix-jdk18on`, `bcprov-jdk18on` and
`bcutil-jdk18on` 1.80.2, `org.jdom:jdom2:2.0.6`, and parent-only
`org.sonatype.oss:oss-parent:7` / `:9`. Missing POM declarations do not mean these
projects are unlicensed; verify their original archives/upstream terms before
making complete distribution claims. Dependency changes must update this scope
notice and any required upstream material; follow [CONTRIBUTING](CONTRIBUTING.md).
