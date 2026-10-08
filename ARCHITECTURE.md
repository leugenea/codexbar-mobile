# Architecture

## Implemented application

One Android phone app module, `io.github.leugenea.codexbarmobile`, launches
`MainActivity` via MAIN/LAUNCHER. Compose Material3 renders four explicitly offline
previews: disconnected, frozen loading, simulated error and demo usage.

```text
MainActivity / rememberSaveable preview key
    -> OfflineShellState (pure synchronous transitions)
    -> Compose screens / string resources
    -> OfflineFixtures (fixed synthetic percentages, only in demo)
```

The saved key restores preview selection across Activity recreation; malformed or
unknown keys restore disconnected. It is not an account/session identity or a
persistent history store. Retry only selects the loading preview; displaying a
sample only selects demo. No timer, background request or live reset observation
runs. Resources label all samples and connection absence explicitly.

The source manifest requests INTERNET only, with `usesCleartextTraffic="false"`.
The exact merged/APK/installed allowlist also includes the existing AndroidX Core
signature-only `io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`;
it is not another platform/network permission. Gates reject any missing or extra
permission and any Network Security Config override. The manifest flag is sufficient
for the current API 26 minimum / API 37 target; moving to target API 38 or adding
a Network Security Config requires a separately reviewed cleartext policy update.
Debug/test builds do not opt into device cleartext; JVM local-server tests do not
need that opt-in. This replaces the historical no-INTERNET invariant, not the
offline demo boundary: preview actions still initiate no requests. Backup/extraction rules
exclude app data. The default offline preview initiates no provider requests.

## Initial native connection gate

A separate Connection gate screen is opt-in and never uses the offline fixtures:

```text
MainActivity / thin StateFlow observer
    -> NativeConnection.get(applicationContext) / one lazy process owner
    -> ConnectionController / process-lifetime SupervisorJob + IO scope
        -> A7 DeviceCodeAuthenticator -> A4 HttpTransportAdapter
        -> A10 SessionCoordinator -> selected token endpoint via A4
            -> A6 KeystoreCredentialStore (binding Unresolved)
        -> AuthenticatedProviderReader -> NativeFeasibilityReader -> A2 ReadPolicy + A4 JSON boundary
            -> A9 UsageResponseParser / BankedResetResponseParser -> A1 normalization
```

MainActivity builds a fixed ACTION_VIEW/BROWSABLE intent for the system browser.
The code is displayed from A7's owned memory, never saved or copied. One bounded
attempt survives Activity recreation, backgrounding into the browser and Activity
finish. Every Activity observes the same process-scoped controller; no ViewModel or
Activity coroutine owns authentication, refresh, reads or local deletion. The lazy
holder constructs from applicationContext only. `singleTask` reduces duplicate
launcher instances, but does not establish correctness: even multiple Activity
observers must share the holder. Manifest gates require the default app process and
reject component process overrides. Process death drops pending login; it is not
reconstructed from Bundle, an intent or a persisted code. The test-only holder reset
closes the old controller before clearing it and explicitly models a fresh runtime;
Activity finish/relaunch alone is not a restoration or process-death simulation.
The holder's factory setter, injected constructor and reset are marked
`@VisibleForTesting(otherwise = NONE)`; lint rejects production callers without
restricting the normal factory getter. Reset also requires an installed test factory.
Production construction stays private and uses the real transport/default clocks.

The only durable selector is a random local UUID under noBackupFilesDir; it selects
A6's encrypted slot and is not account/workspace identity. A fresh process owner may
restore credentials only as Unresolved, without automatic network requests. The
selected flow provides no authoritative token lifetime: there is no inferred TTL,
JWT inspection or expiry schedule. Key loss, corruption or an interrupted rotation
restores as re-auth required, never a usable old credential.

One process owner holds the store, authenticator and session. It serializes runtime
commands, request enqueue and state publication on `Dispatchers.IO.limitedParallelism(1)`.
No suspension separates a generation check from request enqueue. Blocking protected
I/O runs on the separate storage dispatcher and re-enters the owner lane for checked
publication. Commands are asynchronous; observers may enqueue commands but cannot
reenter the current mutation. The owner retires/cancels superseded work; there is no cross-controller ownership
registry, displaced Activity owner or shared Activity deletion barrier. Local sign-out
invalidates auth, reads and refresh and deletes the A6 key and ciphertext without a
remote revocation request. Account replacement deletes the preceding durable pair
before new authentication. Durable removal precedes successful SIGNED_OUT or
REAUTH_REQUIRED publication; removal failures are storage failures, not success.
The process scope, rather than an Activity lifecycle, owns this work. Generation and
cancellation checks reject late results and writes after logout or replacement. The
uncertainty marker keeps interrupted rotation fail-closed across a fresh runtime.

Busy Connect/read commands coalesce. Account replacement while a read is pending
is explicit Cancel or Sign out, then Connect; a busy Connect does not claim success.
Local removals are process-owned coroutine children. Runtime waiters suspend
cancellably with a five-second storage bound; timeout publishes FAILED/STORAGE,
not SIGNED_OUT or REAUTH_REQUIRED, and never releases a still-running removal.
Connect/read admission stays closed until the actual durable outcome. A completed
failed removal can be retried explicitly, never by silently restoring its old token.
The small `CredentialDeletion.complete()` ticket remains only for non-I/O revocation
and exactly-once A3 durable completion; it has no executor, future, process registry
or cross-owner arbitration. A3 uses a short generation/commit-admission monitor
and a separate I/O monitor: cancellation/revocation cannot block behind fsync, and
a previously admitted irreversible commit settles before deletion. The test-only
holder reset waits for old owner children before allowing a fresh owner.

After a successful selected exchange/persistence, the reader issues only the two
selected GET routes. Each logical read has a 30-second deadline and bounded,
cancellable A2 backoff. One usage/inventory read has at most one 401 refresh/retry
allowance across both endpoints; generic read 403 remains forbidden, not session expiry.
A10 coalesces concurrent refresh waiters for the same rejected envelope. Refresh uses
only the selected form POST `/oauth/token` with `grant_type=refresh_token`, current
`refresh_token` and selected `client_id`. A returned refresh token replaces the old one;
when absent/empty the pinned selected-source behavior retains the previous one. A
complete envelope is durably saved before new tokens become observable. An accepted
rotation is still settled if the waiting read expires before its continuation resumes;
the expired read cannot continue its endpoint retry or make the consumed token reusable.
Terminal selected-source refresh codes or refresh HTTP 401/403 clear the session and
require sign-in. Network, 5xx, malformed and 429 failures stay categorical/transient;
a later explicit action may retry, not an automatic refresh loop. A durable-write or
rotation-marker failure quarantines the session and requires sign-in; no possibly
consumed old token is reused. A non-secret, fsynced uncertainty marker under the owned
no-backup slot prevents interrupted or unsuccessfully deleted rotations from restoring
unsafe credentials. Successful complete-envelope persistence or a documented transient
result clears that marker. Explicit cancel or owner shutdown during an unfinished
refresh deletes its uncertain credentials; Activity finish does not shut down the owner.
Generation checks reject late writes/results after logout or
replacement; diagnostics expose categories, never token values or error descriptions.
The narrow projection
shows validated duration-identified five-hour/weekly percentages, resets, provider
flags, banked counts and UTC expiry facts, with independent endpoint observation
times. Both endpoints now use the production A9 decoders; no independent A8 JSON
fact extractor remains. Decoders retain missing/null/wrong-type knowledge and all
window/item siblings through A1, without treating M0 fixtures as provider guarantees.
Banked inventory also retains the `credits` row-container knowledge/reason and array
size independently of provider counts and individual row validity. The pure C1
`EntitlementPresentation` retains this field: absent/null map from A1 UNAVAILABLE to
UNKNOWN, with MISSING/PROVIDER_NULL reasons still distinct; wrong-type containers map
to MALFORMED. Invalid JSON remains an endpoint INVALID_RESPONSE/MALFORMED failure,
not an invented observation. A valid array with malformed rows stays KNOWN at the
container level and PARTIAL at the inventory level; C1 preserves the row failures.
EMPTY requires an explicitly empty array and a provider-reported zero; counts are
never recomputed, and discrepancies keep precedence while container facts remain
visible. Endpoint errors and stale-generation isolation keep their existing precedence.
Full normalized observations remain in memory; endpoint/result diagnostics redact
arbitrary provider strings and identities. B3's live Material 3 usage section consumes
B2's retained usage success, latest attempt, freshness and evaluation time. Its pure
`UsagePresentation` preserves exact decimal text (bounded plain expansion, exact
exponent notation for extreme scales) alongside approximate fractional bar semantics;
permission and limit flags remain independent. Duration-classified missing/ambiguous
windows never become zero, and malformed fields do not hide independently known facts.
B1 alone supplies reset absolute/relative labels, including unknown/sub-hour/passed and
repeated-hour offsets. Status transitions use polite accessibility live regions; ordinary
countdowns do not repeatedly announce. One wrapping, vertically scrollable content tree
keeps actions reachable in landscape. Only the existing harmless tab/preview selection
is Bundle-restored; usage stays with the process owner across Activity recreation, not
with a saved credential or a persistent history store. C2's read-only banked-reset
section consumes B2's inventory cache through C1
`EntitlementPresentation`; `BankedResetPresentation` only adapts resource labels and
independent freshness/error metadata. Provider counts and comparison clocks are kept
verbatim, including discrepancies and expired-available rows. B1 supplies both expiry
labels, distinct from periodic reset. Row/count containers each own one accessibility
announcement; duplicate visible text is hidden from accessibility. No action callback,
activation/purchase request or new owner is introduced. Retirement discards every
entitlement fact; the existing A10/B2 generation path rejects late inventory results.
The gate retains explicit read/session-refresh controls and sign-in-again action.

B2's `UsageRefresh` is a subordinate scheduler in that same owner scope/lane, not
another session owner. The opt-in live gate observes `repeatOnLifecycle(STARTED)`;
identity-tagged observers keep one disappearing Activity from cancelling another
visible observer. Connect/manual read activates refresh for the current generation.
Protected restoration remains dormant until an explicit action, including when the
restored gate is opened: default offline previews and fresh-runtime restoration
remain no-network. After activation, foreground resume requests one bounded cycle;
manual/resume/connect triggers coalesce while a cycle is in flight. Foreground loss
cancels eligible reads, local ticks and polls but never owns token rotation or auth.
A10's independent rotation settlement and durable quarantine rules are unchanged;
only an explicit manual read unlocks a retained transient token-refresh failure,
never an automatic poll or lifecycle-resume trigger.

Visible polling admits no earlier than 60 seconds after the preceding cycle's
admission; each endpoint operation has its own 30-second logical deadline. A2's
deferred Retry-After monotonic boundary survives in the endpoint outcome and holds
later triggers/polls (conservatively the whole cycle) until that boundary. Usage and
inventory publish independently, each retaining latest outcome, last successful
observation and monotonic successful clock. Errors do not replace successes; stale
starts at 15 minutes without that endpoint succeeding. A local one-second tick only
reevaluates freshness/evaluation time; B1 projects reset countdowns on demand and
retains percentages after reset passes. No tick requests I/O, invents zero, rolls a
reset forward or adds history. Owner retirement synchronously invalidates this cache;
old-generation/cancelled completions cannot publish or enqueue a successor read.

Every state reports binding UNRESOLVED / identity UNVERIFIED. Token receipt and HTTP
200 do not resolve account association. The owner accepted this single identity-unverified
session under Q1 and recorded A8 GO on 2026-10-07 in issue #48. CI uses fake transport
and synthetic protocol data; owner-operated physical-phone sign-in and actual
process-kill/relaunch are separate evidence boundaries, not established by parser
tests. A10 live refresh/rotation/logout still needs an exact-candidate owner check;
A8 sign-in alone does not close parent #4.

## Verification boundaries

- `app/src/test`: JVM state transitions, restore behavior and percentage validation.
- `app/src/androidTest`: real production Activity/launcher, exact installed permission
  allowlist and effective cleartext-off policy, preview actions, recreation and
  landscape UI checks.
- `tools/build`: scaffold/workflow/toolchain/strict dependency contracts, manifests,
  JUnit and compatible class-ID/freshness/denominator coverage validation.
- `tools/research`: offline fixture/schema contracts; not a production provider parser.
- `tools/metrics`: informational production Kotlin complexity/duplication reporting.
- `tools/policy`: script/supply-chain policy and public-readiness contracts.
- `UsageResponseParserTest` / `BankedResetResponseParserTest`: unchanged attributed
  M0 wire mocks/vectors plus original strict-type, precision, sibling, clock and
  discrepancy mutations. `NativeFeasibilityReaderTest` verifies both mixed-success
  orders through the same production decoders without inventing an atomic snapshot.
- `ConnectionControllerTest`: no-network orchestration, cancellation/stale results,
  storage/browser/read failures, strict allowlisted projection and bounded backoff.
- `SessionCoordinatorTest` / `AuthenticatedProviderReaderTest`: controlled concurrent
  refresh, durable-before-publication, write-failure quarantine, cancellation/replacement,
  terminal/transient classification, accepted rotation across deadline expiry and one
  shared 401 allowance; existing A2 policy-vector tests remain the read-policy oracle.
- `ProcessSessionOwnerTest`: two observer/commander clients of the production-default
  owner lane; held reads/refresh/removal, late results, bounded cancellation, timeout
  notifications without barrier bypass, repeated logout and observer detachment.
- `DurableQuarantineTest`: held protected key/file removal across refresh/write/marker
  failure, terminal GET, corrupt restore, replacement and logout; durable terminal
  states and both real removal attempts are preserved.
- `CredentialPersistenceTest`: uncertainty marker, fresh-owner rejection, deletion
  failure quarantine and generation-safe marker operations with synthetic persistence.
- `ConnectionLifecycleTest`: real Activity intent seam, process-owner retention across
  recreation/background/finish/relaunch, explicit holder-reset cancellation,
  saved Bundle/redacted diagnostics, synthetic exchange/rotation through real A6,
  fresh-runtime restoration, key loss/corruption/interrupted rotation, re-auth UI,
  failed-rotation-write quarantine, shared-holder callers and local deletion/replacement
  with late-read/refresh rejection. Mandatory historical test names remain unchanged;
  their assertions exercise the process-owner contract, not multiple competing controllers.
  These hosted fakes are not live system-browser sign-in or process-death proof.

Coverage minimum: **90%** JaCoCo **INSTRUCTION** over the JVM + instrumented union,
not a sum of percentages or test counts. Handwritten Activity/Compose/lambda code
remains in scope. Commands, cache policy and all-outcome failure artifacts are
specified in [CONTRIBUTING](CONTRIBUTING.md); metrics in [code quality](docs/code-quality.md).

## Research versus future implementation

[Historical M0 research](docs/research/m0.md), schemas and attributed fixtures
are offline research inputs, not shipped provider support. The app does not load
those research JSON files. Locally authored demo values are a separate UI fixture.
Offline previews do not initiate requests. The separate native connection gate has its own
JVM and hosted fake Activity/browser/Keystore scenarios; it does not use the demo
values. Public sources and successful token receipt still do not establish Android
account/workspace association or service distribution permission.

The native gate implements selected connect, explicit two-read/session-refresh,
rotation/re-auth and local-delete boundaries with production usage/inventory decoding.
Authoritative association is unavailable and is not inferred. B3 renders periodic
usage windows through B2/B1; C2 renders banked-reset counts/status/expiry through C1,
with unknown facts and purchased-balance separation explicit.
B2 consumes the session/repository API rather than reimplementing token rotation or login.
History, graphs, further providers, signing and distribution remain future scope.
See [SECURITY](SECURITY.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
