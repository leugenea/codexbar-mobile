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
MainActivity / Activity ViewModelStore
    -> ConnectionOwner / worker scope (no Activity or saved-state reference)
    -> ConnectionController
        -> A7 DeviceCodeAuthenticator -> A4 HttpTransportAdapter
        -> A10 SessionCoordinator -> selected token endpoint via A4
            -> A6 KeystoreCredentialStore (binding Unresolved)
        -> AuthenticatedProviderReader -> NativeFeasibilityReader -> A2 ReadPolicy + A4 JSON boundary
            -> A9 UsageResponseParser / BankedResetResponseParser -> A1 normalization
```

MainActivity builds a fixed ACTION_VIEW/BROWSABLE intent for the system browser.
The code is displayed from A7's owned memory, never saved or copied. One bounded
attempt survives Activity recreation and backgrounding into the browser. Finishing
the Activity closes its ViewModel and cancels work. Process death drops pending
login; it is not reconstructed from Bundle, an intent or a persisted code.

The only durable selector is a random local UUID under noBackupFilesDir; it selects
A6's encrypted slot and is not account/workspace identity. A fresh owner may restore
credentials only as Unresolved, without automatic network requests. The selected flow
provides no authoritative token lifetime: there is no inferred TTL, JWT inspection or
expiry schedule. Key loss, corruption or an interrupted rotation restores as re-auth
required, never a usable old credential. Local sign-out invalidates/cancels auth, read
and refresh work and deletes the A6 key and ciphertext; it makes no remote revocation
request. Its admitted removal is owned by the slot's independent I/O runner, not by
an Activity coroutine; Activity finish cancels runtime waiters immediately without
dropping removal. A shared deletion barrier prevents a new Activity owner from
restoring or signing in ahead of an already admitted logout. Account replacement
retires the preceding generation and deletes its durable credentials before new auth.
Logout/replacement command admission reserves the barrier and optional replacement
capability before displacing any live owner, including a command from a stale controller.
Ordinary restoration-only displacement does not reserve or delete durable credentials.
Durable key/file removal then runs outside the runtime lane. Runners are exactly-once
and chain through completion callbacks, without parking a thread on a predecessor.
Restoration and deletion waiters suspend cancellably, with a 30-second storage-wait
bound; legacy synchronous storage callers have an interruptible bounded wait. Timeout
reports and notifies truthful storage failure even while removal is held, but neither
clears the barrier nor claims durable success. Only
the actual removal outcome opens storage/request admission. Runtime cancellation is
never queued behind I/O; terminal re-auth/sign-out publication waits for that outcome.
Rebinding never waits for I/O, and a displaced replacement cannot reopen over its
successor. The delayed
authenticator uses only its still-active admitted capability, after durable deletion.
All adapters and runtime owners for that slot share one process-lifetime ownership
sequencer. The registry retains the slot owner strongly (without an Activity/Context)
so a cancelled controller or garbage collection cannot manufacture a second kernel
while an independent removal runner is still alive.
Displacement, generation validation plus transport enqueue, and committed observable-state
assignment execute on that lane; `isActive` snapshots alone never authorize an effect.
Displacement proactively clears old observations and cancels the exact detached jobs.
Synchronous transport completions, job cancellation and StateFlow collector wakeups run
outside the lane, not under a storage/runtime lock. A delayed wakeup reads the latest
committed state; an observer snapshot already admitted before displacement remains an
in-flight delivery, not permission for another request, durable write or publication.
This runtime arbitration never decrypts credentials or clears a pending-rotation marker.
A displaced read owner requires sign-in again and discards its observations.
Quarantine immediately revokes its capability and clears credentials/observations,
but stays busy until durable deletion completes: REAUTH_REQUIRED and SIGNED_OUT
cannot authorize UI completion ahead of key/file removal. Deletion-time observer
wakeups are deferred until the barrier settles, but detached-job/scope cancellation
runs immediately outside the lane. Durable runners are queued before cancellation
callbacks, so a reentrant callback cannot prevent removal from starting. Local logout's
busy SIGNING_OUT admission remains observable after its durable runner is queued;
its Activity barrier suspends a reentrant new owner's restoration. A failed removal
is conservatively a storage failure, not a successful quarantine/logout publication.
Key-loss/corrupt/interrupted-rotation restoration likewise removes the unsafe pair
before publishing re-auth.

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
result clears that marker. Cancel/finish during an unfinished refresh deletes its
uncertain credentials. Generation checks reject late writes/results after logout or
replacement; diagnostics expose categories, never token values or error descriptions.
The narrow projection
shows validated duration-identified five-hour/weekly percentages, resets, provider
flags, banked counts and UTC expiry facts, with independent endpoint observation
times. Both endpoints now use the production A9 decoders; no independent A8 JSON
fact extractor remains. Decoders retain missing/null/wrong-type knowledge and all
window/item siblings through A1, without treating M0 fixtures as provider guarantees.
Full normalized observations remain in memory; endpoint/result diagnostics redact
arbitrary provider strings and identities. The screen still renders only its existing
allowlisted numeric/date facts, not B/C presentation. The existing gate has explicit
read/session-refresh controls and a minimal sign-in-again action; no periodic traffic.

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
- `CredentialPersistenceTest`: uncertainty marker, fresh-owner rejection, deletion
  failure quarantine and generation-safe marker operations with synthetic persistence.
- `ConnectionLifecycleTest`: real Activity intent seam, recreation/background/finish,
  saved Bundle/redacted diagnostics, synthetic exchange/rotation through real A6,
  fresh-owner restoration, key loss/corruption/interrupted rotation, re-auth UI,
  failed-rotation-write quarantine and local deletion with late-refresh rejection.
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
Authoritative association is unavailable and is not inferred. Usage polish and
automatic refresh remain separate B/C work; B2 must consume the session/repository API
rather than reimplement token rotation or login.
History, graphs, further providers, signing and distribution remain future scope.
See [SECURITY](SECURITY.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
