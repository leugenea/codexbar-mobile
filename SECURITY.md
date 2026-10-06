# Security policy

## Reporting a vulnerability

Do not disclose vulnerabilities in public issues or PRs. Use GitHub private
vulnerability reporting: repository **Security → Report a vulnerability**, or
[the private reporting form](https://github.com/leugenea/codexbar-mobile/security/advisories/new).

**Private reporting is currently disabled.** The owner must enable it before this
policy is usable and before merge. If the form is unavailable, do not post the
vulnerability publicly: open only a non-sensitive request to enable private
reporting, with no exploit details or private data. No alternate private channel
or response-time guarantee is asserted here.

Once available, include affected commit/build, impact, minimal reproduction and
possible mitigation. Use synthetic or sanitized evidence. Never send tokens,
passwords, device codes or raw account data, including in private reports.

## Supported scope and current safeguards

Development fixes target `main`. There is no signed production release or promise
of maintained older releases. The current offline shell has no INTERNET permission,
provider traffic, authentication or credentials stored. Only a preview selection
is saved for Activity recreation. Manifest backup/extraction rules exclude app data;
that declaration is not proof of future encrypted credential storage.

PR workflows run on hosted runners with no live credentials; strict Gradle checksum
verification, full-SHA Action pins and hash-pinned Python inputs protect build
acquisition. These are limited safeguards, not a security certification.

M3 auth/credential/storage work is planned, not implemented. It must separately
validate provider authorization, account identity, refresh/logout, lifecycle,
backup exclusion, storage and safe diagnostics. Private-interface/terms risks in
[research](docs/research/m0.md) remain unresolved integration constraints.
