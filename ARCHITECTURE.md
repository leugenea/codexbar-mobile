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

The manifest has no INTERNET permission. Backup/extraction rules exclude app
data. There is no transport, OAuth client, provider parser or credential store.
These safeguards describe this shell, not an audited future authentication system.

## Verification boundaries

- `app/src/test`: JVM state transitions, restore behavior and percentage validation.
- `app/src/androidTest`: real production Activity/launcher, installed INTERNET
  permission absence, preview actions, recreation and landscape UI checks.
- `tools/build`: scaffold/workflow/toolchain/strict dependency contracts, manifests,
  JUnit and compatible class-ID/freshness/denominator coverage validation.
- `tools/research`: offline fixture/schema contracts; not a production provider parser.
- `tools/metrics`: informational production Kotlin complexity/duplication reporting.
- `tools/policy`: script/supply-chain policy and public-readiness contracts.

Coverage minimum: **90%** JaCoCo **INSTRUCTION** over the JVM + instrumented union,
not a sum of percentages or test counts. Handwritten Activity/Compose/lambda code
remains in scope. Commands, cache policy and all-outcome failure artifacts are
specified in [CONTRIBUTING](CONTRIBUTING.md); metrics in [code quality](docs/code-quality.md).

## Research versus future implementation

[Historical M0 research](docs/research/m0.md), schemas and attributed fixtures
are offline research inputs, not shipped provider support. The app does not load
those research JSON files. Locally authored demo values are a separate UI fixture.
Source/test observations and sanitized owner reports do not establish Android
OAuth suitability, account/workspace identity or permission to use private services.

M3 credential/auth/storage/transport work must separately establish authorization,
Android lifecycle and refresh/logout safety, storage and redaction boundaries,
read-endpoint contracts and hosted native proof. History, graphs, further providers,
signing and distribution remain future scope, not hidden components of this shell.
See [SECURITY](SECURITY.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
