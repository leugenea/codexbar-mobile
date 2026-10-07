# Security policy

## Reporting a vulnerability

GitHub private vulnerability reporting is enabled. Do not disclose vulnerabilities
in public issues or PRs. Use repository **Security → Report a vulnerability**, or
[the private reporting form](https://github.com/leugenea/codexbar-mobile/security/advisories/new).

If the form is unavailable, do not disclose the vulnerability publicly: open only
a non-sensitive issue asking how to report privately, with no exploit details or
private data. No response-time guarantee is asserted.

In your private report, include affected commit/build, impact, minimal reproduction
and possible mitigation. Use synthetic or sanitized evidence. Never send tokens,
passwords, device codes or raw account data, including in private reports.

## Supported scope and current safeguards

Development fixes target `main`. There is no signed production release or promise
of maintained older releases. The app declares INTERNET as network infrastructure,
with cleartext traffic explicitly disabled in every app build. The offline shell
still makes no provider traffic and has no authentication or credentials stored;
auth/usage features must land separately before provider traffic is enabled.
Only a preview selection is saved for Activity recreation. Manifest backup/extraction rules exclude app data;
that declaration is not proof of future encrypted credential storage.

PR workflows run on hosted runners with no live credentials; strict Gradle checksum
verification, full-SHA Action pins and hash-pinned Python inputs protect build
acquisition. These are limited safeguards, not a security certification.

M3 auth/credential/storage work is planned, not implemented. It must separately
validate provider authorization, account identity, refresh/logout, lifecycle,
backup exclusion, storage and safe diagnostics. Private-interface/terms risks in
[research](docs/research/m0.md) remain unresolved integration constraints.
