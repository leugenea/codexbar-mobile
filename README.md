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
- Safe numeric/date/status observations with independent endpoint clocks.
- Android launcher, Compose UI, JVM state tests and hosted synthetic native tests.

The connection gate always reports **binding UNRESOLVED / NOT_GO**. Receiving tokens
or HTTP 200 does not establish account/workspace association. Owner live verification
on a physical phone is a separate stop/go decision after merging the wiring; CI uses
only fake transport and synthetic data. Pending login lives only in memory: Activity
recreation retains one bounded attempt, while process death requires restarting login.
Restored credentials remain unresolved and cause no automatic provider requests.

There is no complete refresh/re-auth lifecycle, account history, graphs, live reset
countdown or signed production release. CI debug APKs are test outputs, not signed
release deliverables. Minimum Android version is Android 8.0 (API 26).

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
