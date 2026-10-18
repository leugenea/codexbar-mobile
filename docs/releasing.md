# Guarded Android release runbook

## Inactive boundary

[Guarded release](../.github/workflows/release.yml) is **manual and default-off**.
Issue 147 supplies machinery and fixture verification, not production authority.
No production key, environment, repository variable, tag, Release or settings are
created by this change. Issue 148 owns custody/trust configuration, exact activation,
initial history reconciliation and actual publication approval. Physical-phone and
live-account acceptance, followed by explicit owner GO, remain separate.

The implemented proposed hosted boundary uses ordinary GitHub-hosted runners:

| Job | Capability | Admission |
| --- | --- | --- |
| `admit` | Contents read, Actions read; no environment/secrets | Exact dispatch on this repository's `main` controller |
| `remote-state` | **Contents write**, Actions read; controller-only GET helper; no key environment | Successful public admission |
| `unsigned` | Contents read, Actions read; strict release build/full lint/capsule | Complete remote-state verdict; prepare only |
| `sign` | Contents read, Actions read; protected signing environment; no Gradle/source execution | Prepare, admitted history and successful exact unsigned artifact |
| `publish` | Contents write, Actions read; separate protected publication environment; **no signing secrets** | Publish, exact retained candidate and complete remote-state verdict |

Draft listings require push access. `remote-state` therefore has a technically
write-capable job token even though its injected helper permits only GETs. A helper
allowlist does **not** restrict a compromised token at the GitHub platform level.
The owner must accept or reject this proposed inactive trust boundary in 148; no
PAT, App credential or hidden read-only-draft claim is substituted. The signer
rechecks accepted public history with its read-only token, not independent draft
absence; the draft-capable verdict comes from `remote-state` in the same serialized
operation. Official operations share one workflow-wide noncanceling mutex. This
is not a durable/FIFO queue: a newer pending dispatch can replace a pending one.
Concurrent administrator/out-of-band Release changes are outside that mutex and
require owner reconciliation, not blind automation.

## Public activation contract

Repository variables `RELEASE_RC_ACTIVATION` and `RELEASE_STABLE_ACTIVATION` each
contain one exact JSON authorization following
[release-activation.schema.json](../tools/build/schemas/release-activation.schema.json).
Unset, empty, duplicate/unknown keys, wrong types, invalid identifiers, stale run,
controller/source/tag/history, unknown API state and untrusted refs deny admission.
Stable never inherits an RC record. Only `workflow_dispatch` exists; no PR, tag,
push or workflow-run event signs/publishes. Dispatch inputs are **requests**, not
permission: `operation=prepare|publish`, `channel=rc|stable`, exact `tag` (without
`refs/tags/`) and lowercase full `source_sha`.

Each authorization pins these public fields (no production values are supplied):

- `schema=1`; `repository=leugenea/codexbar-mobile`; numeric `repositoryId`;
  numeric **dedicated** `workflowId`; `workflowPath=.github/workflows/release.yml`.
- Exact `controllerSha` and `controllerRef=refs/heads/main`, `operation`, numeric
  current `runId` and `runNumber`, `channel`, full `tagRef=refs/tags/v...`, peeled
  `sourceSha` and `sourceTree`, lowercase `certificateSha256` of the public DER
  signer certificate. The independent pin must not be discovered from the supplied
  private key or copied from the APK being admitted.
- `trustMode=hosted-environment`, `signingEnvironment`, `publicationEnvironment`.
  Both must already exist, require independent reviewers with self-review disabled,
  and allow exactly custom branch policy `main` (type `branch`). Missing environment
  inspection fails before any environment job is scheduled; this avoids GitHub's
  implicit creation of a missing, unprotected environment. The GET interface does
  not expose the UI administrator-bypass setting: the owner must audit that setting,
  reviewer identities and repository-admin custody separately in 148.
- `previous={"emptyHistory":true}` only for explicit owner-reconciled initial empty
  accepted/distributed history; otherwise exact `releaseId`, `versionCode` and
  `bundleManifestSha256` of the maximum accepted release across both channels.
- Publish only: `candidate` pins originating prepare `runId`, `runAttempt`, exact
  signed `artifactId`, Actions ZIP `artifactSha256`, and `bundleManifestSha256`.
  It does not allocate a second versionCode.

The first inactive dispatch can reveal its run ID/number; after independent owner
approval, activation can pin that run and it can be rerun. A rerun keeps its original
code; a new run/controller/source/tag is not authorized by that record. Controller
SHA must still equal current `main`; source must be accepted `main` history with
successful exact-source main push runs of Android, repository policy, research and
advanced CodeQL. Arbitrary green workflows/PR runs do not authorize source.

GitHub's variable context supplies the authorization to each job; parsing it again
is not a live Variables REST read (that interface needs a separate Variables
permission unavailable in this workflow). **For revocation, remove activation AND
cancel in-flight runs/deny pending environment approvals.** Do not promise removal
alone instantly revokes a job's already evaluated context, nor that it reverses a
completed signature/publication. New runs and reruns require their own current
matching authorization. Variables are non-secret, but not assumed anonymously
readable: publish the approved public record in owner evidence; admitted runs also
include validated public fields in their summary. Never print secret/context dumps.

Before a real activation the owner must verify protections/settings without changing
required PR contexts, select the exact workflow identity/certificate/custody model,
reconcile initial history and explicitly authorize the proposed GET-job capability.
This source change is not acceptance of those decisions.

## Official code and accepted history

Prepare uses **only the dedicated workflow's GitHub `run_number`**, independently
joined through the run API to repository/workflow/event/main/controller/run identity.
It must be 1..2100000000. No offset, clock, hash, ordinary Android CI number,
caller counter, mutable counter branch or separate service is used. Failed,
inactive and publish runs leave legal gaps. Workflow deletion/recreation/counter
reset is not automatically admitted. RC and stable require separate builds/runs/codes.
`VERSION`, tag, channel and clean source still use the existing version resolver.

Before allocation and publication, all pages of draft-aware Release history are
read. Accepted published RC **and** stable assets are actually downloaded, their
signed APK certificate/version/security/payload and complete legal/SBOM inventory
reverified, and source/tag/tree/prepare run joined independently. No `/latest`
allocator, filename-only claim or API digest-only proof is accepted. Every relevant
Release must be recognized; malformed, partial, inaccessible, duplicate-code,
conflicting-certificate or missing-anchor history stops the operation. A new code
must exceed all accepted codes. Retained official signed candidates from the dedicated
workflow also reserve their tag: a different prepare run cannot allocate again for
that tag. This reads only that workflow's run/artifact identity, not a general CI
lineage graph. Expired unresolved candidate bytes stop admission for owner
reconciliation; accepted published origins are verified from Release assets instead.
Publishing an older prepared candidate after a
newer accepted code is refused. Existing exact accepted candidates are read-only
no-ops; a new run for that tag conflicts instead of rebuilding/replacing it.

Once a candidate is accepted or distributed, record it in this official Release
history before allocating another. Out-of-band distribution, deletion or loss of
history requires owner reconciliation. This is ordinary repository/history trust,
not deletion-proof archival or protection against a malicious repository admin.
The stable source-backed legal corpus is reused; no historical-source genealogy or
second attribution pipeline is introduced. Retaining compatibility with accepted
legal materials is part of reviewing future catalog changes.

## Prepare, retained bytes and custody

After approved exact activation, prepare compiles selected source in a separate,
credential-free job with strict dependency verification/full release lint. It creates
and re-derives the existing unsigned capsule and retains
`unsigned-<run_id>-<run_attempt>` for seven days. Sign independently matches the
exact Actions artifact ID/digest/origin and unsigned bundle digest/source. Controller
helpers, not selected source/Gradle, execute in the signing environment.

Proposed signing-environment secret interface (all four required; **none configured**):
`ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEY_ALIAS`,
`ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_PASSWORD`. They are scoped
only to the signing step. Keystore/password files are private mode-0600 inside a
mode-0700 `RUNNER_TEMP` directory, passwords use SDK `file:` arguments, and private
tool output is suppressed. No alias discovery, default key or fallback generator
exists. Fixture key generation is in a different hosted-only helper.

AGP-aligned input is checked using `zipalign -c -P 16 -v 4`; it is not rewritten.
`apksigner sign` writes a separate APK, explicitly disables the unused v4 sidecar,
and actual SDK verification checks the independently pinned signer and all existing
package/version/security policy. The established final binder rechecks the accepted
unsigned source/capsule and complete non-signature payload, then re-derives and
verifies signed-byte SBOM/inventory. No post-sign mutation is permitted. Private
files are deleted before candidate upload; this claims scoped ephemeral files and
observed exclusion, not forensic secure erasure of runner storage.

Retained `signed-<prepare_run_id>-<prepare_run_attempt>` contains exactly six assets:

1. `codexbar-mobile-<versionName>-<versionCode>.apk` (signed, never unsigned).
2. `runtime.cdx.json` bound to that final APK hash.
3. `THIRD_PARTY_NOTICES.txt`.
4. `codexbar-mobile-<versionName>-<versionCode>-bundle.zip`: deterministic ZIP of
   **every** final bundle file, including full legal/notices/corresponding source.
5. `release-manifest.json`: source/build/controller/workflow/run/attempt/certificate,
   exact data-asset sizes/hashes and final bundle-manifest digest.
6. `SHA256SUMS`: every distributed file except itself; no circular self-hash.

The final bundle retains clearly labeled unsigned acceptance evidence, not the
unsigned APK. `distributionApproval=false` remains unchanged: byte/attribution
verification is not owner publication GO. No keys/passwords/debug APKs enter the
allowlist. Owner independent acceptance must record exact digests and artifact ID,
not just a retained artifact name. Expired/missing unaccepted artifacts require a
stop; they never authorize rebuilding accepted bytes. An already accepted exact
published candidate can instead be verified as a read-only no-op from its Release
assets, without requiring an expired Actions archive.

The owner must retain the signing identity across updates, verify its public pin,
keep an offline encrypted backup and test restoration privately. Never send keys,
passwords, tokens or device codes through chat, Git, logs, caches or artifacts.
Changing certificate/custody needs a separate reviewed owner decision, not fallback.

**Owner-local alternative:** choosing this proposed workflow does not require owner
agreement to GitHub custody. Keep hosted activation unset if the owner chooses local
custody in 148. The same accepted unsigned APK/capsule and independent public pin can
be signed on the owner's private host with an explicit existing key, then processed
with the documented [bind-final / verify-final interface](../CONTRIBUTING.md#apk-bound-runtime-sbom-and-notices)
and `release_signing.package_assets`. Preserve the official originating counter and
accepted source; do not allocate a second local code or invent a successful hosted
prepare/artifact provenance. This initial workflow implements the hosted-environment
option only: local-custody operational admission/publication must be agreed in 148
before GO, rather than silently adding credentials or uploading private keys.

## Publish and recovery

Publish is a **new separately authorized dispatch** with a protected publication
environment, exact original successful prepare run/attempt/artifact/digests, and no
signing secret. It downloads and verifies the retained candidate without rebuild or
resign. Source tag must already exist at the approved peeled SHA; the publisher never
creates/moves tags. The source workflow subtree must equal current main's subtree:
GitHub Release API can require Workflows write for drift and `GITHUB_TOKEN` cannot
obtain it. A 403/404 is an authorization failure, not permission to try another source,
create blindly, add a PAT/App credential or bypass this check.

The protocol fully paginates Releases including drafts, selects exactly one matching
tag and operates by numeric Release/asset IDs. Absent state creates **draft only**,
with explicit source/name/candidate marker/prerelease, `make_latest=false` and
`generate_release_notes=false`. Existing drafts must match the exact candidate
manifest marker. Present allowlisted assets are actually downloaded and matched;
only missing assets in that matching mutable draft may be uploaded. Extras,
duplicates, starter state or differing bytes refuse writes. No delete, clobber,
repair of a published Release or adoption of an unrelated draft exists.

Every upload is downloaded/read back by its exact returned ID and relisted. Unknown
write outcomes trigger readback and retain failure, not blind POST retry. After
complete matching assets and fresh source/history admission, the exact draft is
patched to `draft=false`; RC uses `prerelease=true, make_latest=false`, stable uses
`prerelease=false, make_latest=true`. Final ID/flags/tag/source/assets and downloaded
bytes must match before success. Published/immutable exact complete state is
verification-only. Immutability settings are optional owner decisions, never assumed.

For a failed operation: retain the run/attempt, exact candidate IDs/digests and
sanitized error; inventory draft/published Release and asset IDs with an owner
push-capable session. Reconcile any timeout/502 starter or conflicting bytes
explicitly. Only an owned matching partial draft can resume; cleanup is an owner
operation, never an automated delete. Repin authorization after source/history or
controller changes. Never replace an accepted signed APK, move its tag, silently
regenerate a candidate, or treat an inaccessible/empty public-only list as proof of
no drafts. Stable publication requires its own authorization even after accepted RC.

## Controlled hosted evidence, not production GO

Ordinary secret-free Android CI builds one additional disposable RC in a temporary
clone before starting its existing emulator. A separate hosted-only helper generates
a temporary fixture key, exports DER to derive the independent public pin, signs
using the real pinned SDK and verifies final capsules/assets. It runs the actual
publisher protocol against localhost fixture transport (no real GitHub token/API
writes), downloads the exact signed APK, checks certificate/version negatives, and
removes private files before evidence upload. Evidence/cache inventories and evidence
bytes/archive members are checked for fixture secret/key exclusion. Gradle cache
contents are inventoried for private-file types; this is not forensic examination.

After existing debug checks/coverage/AGP cleanup, a mandatory observer on the **same
emulator** installs that exact mock-downloaded signed APK, checks production package
path/dump/version, pulls the actual base.apk, requires byte/hash equality and repeats
SDK inspection, then launches the real MainActivity and requires a bounded positive
resumed-Activity/default-process observation. It always uninstalls **production only**;
existing debug observation/cleanup and original graph-failure precedence remain.
No automatic debug uninstall/data migration, new emulator, UI hook, live account or
provider behavior is introduced. `.dev` and production retain isolated app data;
there is no promised debug-to-production upgrade or credential/history migration.

Inspect `evidence/signed-release-fixture/` (fixture-only certificate, signed bundle,
mock-downloaded allowlist, negative controls, private-file exclusion receipt) and
`evidence/native/release-installation/` (step statuses, path/dump/pull/launch/final
positive identity), plus `release-observation-exit.txt`. Inspect all inherited debug,
JVM/native/capture/whole-handwritten >=90% INSTRUCTION coverage evidence separately.
Missing reports are not success. Exact-head and exact-merge hosted execution must
validate actual latency within unchanged job/graph/boot/retry budgets. If the added
fixture cannot fit, propose moving the existing RC fixture rather than increasing
budgets blindly. Pure tooling mocks do not establish real SDK/native success.

### Primary interface references

- [GitHub contexts / run counters](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts)
- [Releases / draft visibility / workflow permission restriction](https://docs.github.com/en/rest/releases/releases)
- [Release assets / 200 or 302 / starter recovery](https://docs.github.com/en/rest/releases/assets)
- [Actions artifact identity/digest/expiry](https://docs.github.com/en/rest/actions/artifacts)
- [Environment inspection](https://docs.github.com/en/rest/deployments/environments)
- [Variables permissions](https://docs.github.com/en/rest/actions/variables)
- [apksigner](https://developer.android.com/tools/apksigner), [zipalign](https://developer.android.com/tools/zipalign)
- [JDK 17 keytool password-file and DER interfaces](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html)
