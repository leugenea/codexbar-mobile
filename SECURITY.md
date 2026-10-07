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
with cleartext traffic explicitly disabled in every app build. The separate offline
preview still makes no provider traffic. Explicit connect enters the selected
system-browser device-code flow and two read-only WHAM endpoints only. The initial
native gate is not authoritative account/workspace association or a supported client.

A retained Activity ViewModel owns one bounded login/read worker; it does not hold the
Activity. Device codes stay in owned memory, never Bundle/SavedStateHandle/clipboard,
logs or exception diagnostics. The Activity sets FLAG_SECURE. Process death drops a
pending login; encrypted credentials may restore only as unresolved, without automatic
requests. Credentials use A6 AndroidKeyStore AES-GCM and an atomic no-backup ciphertext
slot; its random local selector is also under noBackupFilesDir, not provider identity.
Sign-out cancels owned work and deletes the slot key and ciphertext locally; it does
not revoke remote sessions. Backup/extraction rules exclude app data. Native fake tests
and the later owner's physical-phone session are separate evidence boundaries.

PR workflows run on hosted runners with no live credentials; strict Gradle checksum
verification, full-SHA Action pins and hash-pinned Python inputs protect build
acquisition. These are limited safeguards, not a security certification.

The initial gate always reports binding UNRESOLVED and NOT_GO; token receipt and HTTP
200 do not establish identity. Complete refresh/rotation/re-auth and broader usage work
remain blocked by the owner live gate. Never infer workspace from tokens or add another
endpoint/protocol to resolve it without approval. Private-interface/terms risks in
[research](docs/research/m0.md) remain unresolved integration constraints.
