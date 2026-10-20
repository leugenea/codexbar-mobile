# Third-party notices

Repository-authored code is [MIT](LICENSE), Copyright (c) 2026 Luckyanets Eugene.
That license does not replace upstream terms. This is a source/dependency-scope
notice, **not an APK-bound SBOM or completed distribution-license audit**. Preserve
upstream copyright, license and NOTICE material when distributing copied portions.

## Application runtime dependencies

Release attribution is produced by the [APK-bound pipeline](CONTRIBUTING.md#apk-bound-runtime-sbom-and-notices),
not by enumerating verification XML. The
[reviewed candidate catalog](tools/build/runtime-licenses.json) and
[preserved full texts](docs/legal/runtime/README.md) cover exact versions of the
AndroidX/Compose, Kotlin/coroutines/serialization, OkHttp/Okio and annotation inputs,
plus known nested, native and generated-data portions. A catalog entry is not proof
that it ships: build/test-only inputs and constraint-only BOMs are excluded from the
shipping SBOM. Only the fresh exact release graph and actual APK determine that list.

Every candidate bundle contains CycloneDX JSON 1.6, a complete observed runtime
inventory, `THIRD_PARTY_NOTICES.txt`, required `legal/` material and byte-bound
manifests. Preserve the entire matching capsule when later distributing the exact
signed APK. Changed dependencies require fresh source-backed attribution; unknown
components, changed known embedded hashes or missing applicable material block the
pipeline instead of producing guessed complete status.

Known embedded attribution includes Emoji2's relocated FlatBuffers (standalone
version unknown; full relevant Apache/Google BSD candidate headers retained),
graphics-path's native/header licenses, Kotlin stdlib's GWT/Guava/ThreeTenBP/Boost
portions, source-embedded Apache/Android portions, and OkHttp's Unicode IDNA data
and MPL-2.0 Public Suffix List. Relevant candidate ASF Core and Android/Harmony
notice sections are preserved with truthful scope; unrelated aggregate projects
are not presented as shipped libraries. This bounded policy does not promise
exhaustive historical genealogy or legal clearance.

OkHttp Android 5.5.0's exact corresponding PSL source, original NOTICE and full
MPL-2.0 are provided in [the local source material](docs/legal/runtime/psl/SOURCE.txt),
not a moving latest-source link. It reproduces `assets/PublicSuffixDatabase.list`
with the cataloged hash. Unicode IDNA 15.1.0 uses the separately preserved
Unicode-3.0 terms. The exact fork release source is
[lysine-dev/okhttp a94bdf152084d11acecd44dcd09ffef203f4f0aa](https://github.com/lysine-dev/okhttp/tree/a94bdf152084d11acecd44dcd09ffef203f4f0aa).
Unsigned capsules remain non-distributable signing inputs; final-signed binding is
not an owner publication or activation GO.

## Build, test and repository tooling (not a release-runtime list)

| Component | Use / license findings |
| --- | --- |
| Gradle 9.8.2, AGP 9.4.1, Kotlin Gradle/Compose compiler 2.4.20 | Build/compiler; primary projects Apache-2.0 |
| Temurin / OpenJDK 17 | Build/JVM; GPL-2.0 with file-specific Classpath exception and additional notices |
| Android SDK, Build Tools, command-line tools, emulator and API 36 Google APIs image | SDK/component agreement terms; not blanket Apache-2.0 |
| JUnit 4.13.2 | JVM tests; EPL-1.0 |
| MockWebServer3 5.5.0 / OkHttp JVM 5.5.0 | Localhost JVM-test support; Square and Lysine contributors; Apache-2.0; not release-runtime declarations |
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

No guessed approvals: execute and reverify the exact release-runtime capsule for
each candidate, including the scoped embedded attribution and corresponding source.
The separate build/test/host tooling audit remains open for Actions, jscpd,
rpds/PyYAML, JDK/SDK and host tooling; retrieve/verify the benchmark Action's missing
license text. Those unrelated graphs are not claimed as APK components.

Exact-version POM license gaps remain for `javax.inject:javax.inject:1`,
`net.sf.kxml:kxml2:2.3.0`, Bouncy Castle `bcpkix-jdk18on`, `bcprov-jdk18on` and
`bcutil-jdk18on` 1.80.2, `org.jdom:jdom2:2.0.6`, and parent-only
`org.sonatype.oss:oss-parent:7` / `:9`. Missing POM declarations do not mean these
projects are unlicensed; verify their original archives/upstream terms before
making complete distribution claims. Dependency changes must update this scope
notice and any required upstream material; follow [CONTRIBUTING](CONTRIBUTING.md).
