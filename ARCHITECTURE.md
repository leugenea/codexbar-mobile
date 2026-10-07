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
        -> A6 KeystoreCredentialStore (binding Unresolved)
        -> NativeFeasibilityReader -> A2 ReadPolicy + A4 + A1 validation
```

MainActivity builds a fixed ACTION_VIEW/BROWSABLE intent for the system browser.
The code is displayed from A7's owned memory, never saved or copied. One bounded
attempt survives Activity recreation and backgrounding into the browser. Finishing
the Activity closes its ViewModel and cancels work. Process death drops pending
login; it is not reconstructed from Bundle, an intent or a persisted code.

The only durable selector is a random local UUID under noBackupFilesDir; it selects
A6's encrypted slot and is not account/workspace identity. A fresh owner may restore
credentials only as Unresolved, without automatic network requests. Local sign-out
invalidates/cancels work and deletes the A6 key and ciphertext; it makes no remote
revocation request. Its admitted synchronous deletion cannot be dropped by Activity
finish before the worker begins.

After a successful selected exchange/persistence, the reader issues only the two
selected GET routes. Each logical read has a 30-second deadline and bounded,
cancellable A2 backoff. A 401 reports reauthorization required without implementing
A10 refresh; generic 403 remains forbidden, not session expiry. The narrow projection
shows validated duration-identified five-hour/weekly percentages, resets, provider
flags, banked counts and UTC expiry facts, with independent endpoint observation
times. It is not A9's complete decoder or B/C presentation/refresh work.

Every state reports binding UNRESOLVED / NOT_GO. Token receipt and HTTP 200 do not
resolve account association. CI uses fake transport and synthetic protocol data;
owner-operated physical-phone sign-in, actual process-kill/relaunch and the live
stop/go decision remain separate gates before A9/A10/B/C work.

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
- `ConnectionControllerTest`: no-network orchestration, cancellation/stale results,
  storage/browser/read failures, strict allowlisted projection and bounded backoff.
- `ConnectionLifecycleTest`: real Activity intent seam, recreation/background/finish,
  saved Bundle/redacted diagnostics, synthetic exchange through real A6 and local deletion.
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

The native gate implements only the selected initial connect/two-read/local-delete
boundary. Complete refresh/re-auth, authoritative association, broad response decoding,
usage polish and automatic refresh remain separate blocked work. History, graphs,
further providers, signing and distribution remain future scope.
See [SECURITY](SECURITY.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
