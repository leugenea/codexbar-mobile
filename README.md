# CodexBar Mobile

An independent, unofficial Android phone project. It is not affiliated with or
endorsed by OpenAI or CodexBar. **The app is currently an offline shell, not a
working account or provider client.**

## What works today

- Disconnected, frozen loading, simulated error and demo-usage previews.
- Hand-authored sample percentages, clearly labeled as samples.
- Preview selection restored across Activity recreation; no account data store.
- Android launcher, Compose UI, JVM state tests and hosted native smoke tests.

There is no authentication, live provider connection, credential storage, account
history, graphs, live reset countdown or signed production release. CI debug APKs
are test outputs, not signed release deliverables. Minimum Android version is
Android 8.0 (API 26).

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
