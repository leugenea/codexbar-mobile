# Version identity and unsigned release boundaries

`VERSION` contains one canonical development target, initially `0.1.0`. Advance it
explicitly after publication. Normal CI always chooses `releaseChannel=dev`, even
at a release-tagged HEAD. Its version is `<target>-dev.<N>+g<actual checkout short SHA>`.
N counts reachable commits since the unique latest reachable stable ancestor;
without a stable ancestor it counts from inception. A genuinely counted zero at
a stable tag is valid; failed or incomplete discovery is never zero. Development
versionCode is the positive total reachable commit count in the isolated dev lineage.

The source namespace and full MainActivity class remain
`io.github.leugenea.codexbarmobile`. Debug package/default process is
`io.github.leugenea.codexbarmobile.dev`; production release remains
`io.github.leugenea.codexbarmobile`. Each variant requests exactly INTERNET and its
own AndroidX signature-only receiver permission. The source requests INTERNET only.
No-backup rules, cleartext-off, default process, singleTask and security behavior
remain intact. Source SHA/tree/channel metadata and Android version fields are
independently checked against the reviewed variant and acquired source identity.

## Acquisition and read-only resolution

Explicit hosted producer preparation uses `complete_codeql_checkout.py` before
Gradle. Normal Android jobs fetch complete history/tags and verify an anonymous
public advertisement against actual local objects. Managed CodeQL's existing
extractor-marker block instead completes its shallow checkout at the exact captured
immutable SHA, anonymously from the fixed public repository. It suppresses
persisted headers/helpers and unsafe config/environment behavior without rewriting
credentials/config/source/index. It can add only required objects, remove the
shallow boundary and create missing advertised canonical local release tag refs.
No moving tip, public tag write, settings change or identity fallback is used.

An internal runner-temp observation is bound to job/run/attempt/root, exact source,
HEAD/tree/raw and logical index/config/non-tag refs and acquired tag map. Later
configuration/resolution/reverification checks actual local objects/refs and all
protected inputs against it. It performs no new live-origin query. This is an
internal acquisition observation, not an official release admission or portable
trusted stamp. Missing, stale, tampered or incomplete evidence fails. Versioned
builds require committed source; no dirty-source bypass exists. The current
verification workflow is hosted; a local resolver never auto-fetches on failure.
Managed CodeQL's injected verification-off invocation is not strict Android
release evidence. The tracked Android graph uses strict dependency verification.

## Controlled RC and stable identity fixtures

RC is exactly `<target>-rc.N` with `v<target>-rc.N`, N positive; stable is exactly
`<target>` with `v<target>`. VERSION, immutable tag object/peeled commit, explicit
full source SHA and actual checkout must agree. A1 accepts these modes only in an
isolated hosted fixture repository/origin under runner temp, with explicit fixture
counter and prior fixture code/identity. A new code increases; rerun preserves the
entire identity tuple. RC and stable are distinct builds/codes.

Those tests allocate no official counter or accepted/distributed history. A future
single dedicated trusted release workflow owns positive monotonic official codes,
immutable admission and rerun continuity. CI run numbers, clocks, hashes and rc.N
are not official versionCodes. A valid fixture tuple is not signing authority.

## Unsigned inventory and future handoff

The existing build job runs actual `:app:assembleRelease` and `:app:lintRelease`,
then inspects the single AGP unsigned APK and output metadata with pinned SDK
Build Tools. It checks package/version/source/flags/permissions/components and
packaged backup resources. ZIP/JAR/signing-block absence and a specific unsigned
apksigner diagnostic must agree; arbitrary verification failure is rejected.

`unsigned-payload-v1` canonical JSON binds identity digest, actual source tree and
tracked paths/modes/blob/byte hashes, distinct compiled checkout and PR source head,
actual producer workflow/ref/SHA/job/run/attempt, strict log/toolchain digests,
prospective final name and unsigned size/hash plus exact inspection methods/evidence.
Paths, omissions, duplicates, inconsistent producer/source and changed bytes fail.
`distributionAuthorized` is false and `signedFinal` is null. No guessed API artifact
ID, official admission, certificate or signed-final checksum is supplied.

Normal CI release output is a **development packaging fixture** in the production
package, with a development-valued version/code. Hosted RC/stable outputs are
**release identity fixtures** using ephemeral local tags/counters. Both are unsigned
and non-distributable; neither is an admitted official signing input.

Issue146 consumes exact unsigned bytes/source/variant for runtime SBOM/notices.
Issue147 independently admits official source/ref/counter and consumes a frozen
unsigned inventory and attribution bundle. Alignment/signing produces distinct
final bytes and a new signed-final hash/size/certificate record linking the unsigned
input; it must not recompute counter or confuse unsigned and signed hashes. Owner148
chooses key custody/trust/activation (including local owner signing as an alternative);
owner149 supplies exact-candidate physical/live observations and byte-specific GO.
Parent9 publishes only those exact accepted final signed bytes afterward.

## Installation and updates

Development `.dev` can coexist with production and uses a separate UID/sandbox and
existing package-derived Keystore alias. CI debug certificates are development
identities, not promised update continuity. Unsigned release fixtures cannot be
installed or distributed as signed candidates. Synthetic hosted debug behavior,
CodeQL extraction and unsigned packaging are distinct evidence boundaries.

The old pre-isolation feedback debug APK has the production package ID with a debug
certificate. It is **not a production-signed upgrade**. A signature conflict does
not authorize automatic uninstall or credential/history/name export/copy/migration.
No other installed lineage is removed by the hosted debug inspection script.
Production RC/stable updates require the same owner production certificate and a
strictly increasing officially admitted code. Actual signed installation/update and
live-account proof remain later-unit and owner work; no new OAuth client/account
configuration is introduced here.
