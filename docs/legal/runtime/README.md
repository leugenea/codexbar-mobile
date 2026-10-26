# Preserved runtime attribution material

`tools/build/runtime-licenses.json` is a reviewed, exact-coordinate **candidate
catalog**, not a claim that every catalog entry ships. The release-only Gradle
observation selects the actual graph; APK observation selects observed and merged
contributors. Only their material is copied into the generated bundle's `legal/`
directory. Build/test dependencies and constraint-only BOMs are not runtime SBOM
components. Full original text and spelling are retained.

Generated `attribution/` compilations and extracted `candidate-*` notice/header
texts use LF line endings and one final newline; their catalog hashes bind those
formatted copies. No legal wording is changed. Full upstream copies retain their
original bytes. `.gitattributes` exempts only the final blank line in
`graphics/LLVM-License-and-exceptions.txt` and trailing space in
`psl/PublicSuffixList-LICENSE.txt` from their respective whitespace checks; all
other whitespace checks and corpus paths retain the normal gate.

## Evidence and scope

- `attribution/`: full copyright/license comment blocks and legal members from
  each named, versioned published source archive. Each text gives the source URL,
  source-archive SHA-256 and member associations. Shared Apache terms are preserved
  in `Apache-2.0.txt`. Source-set attribution can be broader than the selected Android
  payload; it is not an assertion that every source member survives packaging.
  WorkManager and its selected Room/framework-SQLite/Lifecycle/futures/tracing
  additions use these same versioned-source compilations. Their Android runtime
  artifacts add no separately bundled native SQLite engine; the actual release
  graph/APK still selects contributors, and work-testing is not a shipping input.
- `emoji2/`: Google/FlatBuffers Apache and Google 2008 BSD-3-Clause material, plus
  generated AOSP metadata headers. The catalog binds Emoji2's exact
  `libs/repackaged.jar` hash. The candidate FlatBuffers snapshot is explicitly
  **not** asserted to be the exact historical import. Standalone version is unknown.
- `graphics/`: exact graphics-path 1.0.1 native source headers and applicable
  Bionic inline-header BSD notices / LLVM license and exceptions from the
  configured NDK 25.2.9519653 scope. The catalog binds all four native members.
  It does not label the whole NDK or its notice-aggregate projects as shipped.
- `apache-imports/`: relevant candidate HttpComponents Core and Android/Harmony
  notice sections (including the Intel paragraph), preserved with truthful scope.
  OkHttp/Okio derivative source headers are retained in their attribution files.
  This does not assert an exact historical upstream import revision or that every
  project from an aggregate NOTICE ships. Unknown historical completeness alone
  is not represented as a demonstrated missing obligation or as legal clearance.
- `kotlin/`: stdlib copyright and the source-backed GWT/Guava, ThreeTenBP BSD and
  Boost license texts for known embedded source portions. The Kotlin compiler's
  root NOTICE describes its compiler distribution; it is not falsely presented
  as a shipped compiler component or blanket stdlib NOTICE.
- `coroutines/`, `serialization/`: full exact-version project LICENSE and library
  NOTICE texts, in addition to any published source headers.
- `unicode/`: full contemporaneous Unicode-3.0 license for OkHttp's generated
  Unicode IDNA 15.1.0 data, not an invented independent Maven dependency.
- `psl/`: full MPL-2.0, original NOTICE, exact corresponding source and versioned
  generator for `assets/PublicSuffixDatabase.list`. `SOURCE.txt` provides recipient
  directions. A pure test reproduces the cataloged binary hash from this source.

The owner-approved bounded policy requires exact selected artifact versions/hashes,
known embedded identities and preserved source-backed texts, not exhaustive upstream
archaeology or historical build reconstruction. A demonstrated conflict or a
specifically missing applicable mandatory notice/source obligation still blocks a
candidate. Updating a dependency requires fresh attribution review; a missing catalog
entry or mismatched known embedded member fails bundle creation. This material gives
no legal warranty, service-distribution permission or owner publication GO.
