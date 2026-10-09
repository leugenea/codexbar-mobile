# CodexBar Mobile

An independent, unofficial Android phone project. It is not affiliated with or
endorsed by OpenAI or CodexBar. **The app retains an offline preview and includes
an owner-operated native connection feasibility gate, not supported provider access.**

## What works today

- Disconnected, frozen loading, simulated error and demo-usage previews.
- Hand-authored sample percentages, clearly labeled as samples.
- Preview selection restored across Activity recreation; preview actions never request provider data.
- A separate connection gate: connect/cancel, fixed system-browser device login,
  Keystore-backed local storage and sign-out, and only the two selected read-only routes.
- Serialized session refresh/rotation with durable save before publication, bounded
  401 recovery, re-auth on terminal/write failures, and generation-isolated local logout.
- Explicit stored-session read/refresh controls; no invented token TTL.
- Live Material 3 usage windows with precise provider percentages and fractional bars,
  five-hour/weekly duration labels (including weekly-only), independent permission/limit
  flags, missing/error/stale/exhausted states and accessible status announcements.
- Local absolute reset date/hour plus relative remainder, including `<1h` and awaiting
  refresh; passed resets never zero usage. Absolute labels truncate to the containing
  local hour; elapsed days/hours are floored from the original instant, not that label.
- Foreground refresh after explicit activation: coalesced connect/resume/manual triggers,
  polling no faster than 60 seconds, bounded cancellable reads and server Retry-After
  deferral. Local countdown/freshness ticks do not request provider data.
- Read-only banked resets with provider summary/inventory counts, per-item status and
  simultaneous local absolute/relative expiry (including `<1h` and expired items).
  Empty, unknown with reasons, unsupported, inaccessible, malformed and conflicting
  inventory remain distinct; stale/error inventory retains its own successful clock.
  Counts are never recomputed from rows. No activation or purchase action exists.
- Safe numeric/date/status observations with independent endpoint clocks.
- Android launcher, Compose UI, JVM state tests and hosted synthetic native tests.

The connection gate reports **binding UNRESOLVED / identity UNVERIFIED**. Receiving
tokens or HTTP 200 does not establish account/workspace association. The owner accepted
this single identity-unverified session and recorded A8 GO on 2026-10-07 in issue #48.
Refresh/rotation/local-logout verification on the exact candidate remains a separate
owner gate; CI uses only fake transport and synthetic data. Pending login lives only in memory: Activity
recreation and finish/relaunch share one process-owned bounded attempt; no Activity
owns or cancels it. Process death requires restarting login.
Restored credentials remain unresolved and cause no automatic provider requests.

Banked resets are count-only: no separate amount/unit is established or needed under
the owner decision. Purchased credit balances are not banked resets. Missing/null or
unsupported availability, status or expiry remain visible limitations, never zero or
infinite lifetime. Summary counts/clocks are the C1 facts captured for the inventory
comparison, not a recomputed current total; retained inventory may be older than usage.
Refresh progress describes B2's shared read cycle, not an invented per-endpoint worker.
A dependency-free framework SQLite history adapter implements the local usage-history
contract, fed by accepted foreground usage delivery under the existing process owner. It uses a no-backup directory with 30-day
trusted-observation aging, 100,000-observation and 32 MiB complete-directory ceilings,
bounded reads and visible eviction metadata. It retains exact numeric/time facts, not
credentials, plan text or provider identity; history is sandbox-private, not Keystore-encrypted.
Local deletion is logical removal, not forensic erasure. The hosted test contract requires
close/fresh-adapter persistence proof, not process-death or backup-extraction proof.
The existing process owner now binds history to a separate opaque local credential-lifetime
partition. Fresh sign-in gets new isolation; clean protected restoration and token rotation
retain it. Logout, replacement, terminal re-auth, key loss and corrupt restoration revoke
history immediately and attempt both protected credential and history cleanup. Full local
deletion succeeds only when both durable outcomes succeed; failure/timeout remains an
explicit storage failure, with no successor admission over unsettled cleanup. Cancel/holder
shutdown retain a clean lifetime; the last foreground observer leaving revokes its runtime
history capability without deleting it. A returning observer gets a fresh capability for the
same continuing partition. Identity remains UNVERIFIED: there is no provider-account join.
Real accepted USAGE observations are now recorded only while a live observer is visible.
Different successful reads at equal percent remain distinct; cache replay, inventory-only
success, errors, local ticks and analytical baseline queries add no measured points.
This is sparse foreground history, not a continuous record: background/error/field/clock
gaps remain explicit, with no retrospective backfill. Bounded durable work reports lost or
unconfirmed samples and categorical storage failures without replacing live quota.
An immutable bounded history/query contract supplies measured segments and separate
nominal even-distribution descriptors; graph rendering is still separate work. The native
contract exercises actual Keystore/SQLite with synthetic transport and fresh-runtime
restoration, not literal process death or verified provider identity. There is no
verified-account history, background refresh service, widget
or signed production release. CI debug APKs are test outputs, not signed release
deliverables. Minimum Android version is Android 8.0 (API 26).

The app declares INTERNET, with cleartext traffic explicitly disabled. Offline
previews make no provider traffic. Explicit connection is the only entry into the
selected auth flow and two quota/reset GET endpoints; no activation/purchase, account
header, desktop impersonation or extra route is used.

Future provider work is gated separately. The [research](docs/research/m0.md)
discusses private interfaces, OAuth-client authorization, provider terms and
distribution risk. An observed endpoint or an upstream open-source license does
not grant service access or promise that a mobile integration will be supported.

## Build and contribute

Start with [CONTRIBUTING](CONTRIBUTING.md) for toolchain setup, quick checks,
strict dependency updates and hosted acceptance. With the documented JDK/SDK
prerequisites, a local debug build is:

```sh
./gradlew --no-daemon --dependency-verification strict :app:assembleDebug
```

Use GitHub Actions for emulator/instrumented tests; do not run them on the NAS.
Documentation-only changes still receive all declared PR gates.

- [Architecture and test boundaries](ARCHITECTURE.md)
- [Agent development rules](AGENTS.md)
- [Security and private reporting](SECURITY.md)
- [Third-party notices and open attribution gaps](THIRD_PARTY_NOTICES.md)
- [Code-quality measurement](docs/code-quality.md) and the
  [complexity/duplication chart](https://leugenea.github.io/codexbar-mobile/dev/bench/)
- [Current repository protections and recommendations](docs/repository-protection.md)

## License

[MIT](LICENSE), Copyright (c) 2026 Luckyanets Eugene. Upstream copied material
retains its own licenses and notices; see [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md).
