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
system-browser device-code flow, the same selected token endpoint for session refresh,
and two read-only WHAM endpoints only. The native gate is not authoritative
account/workspace association or a supported client.

One lazy process-scoped owner controls bounded login/read/refresh work. Activities
only observe and submit commands; recreation, backgrounding and finish do not stop
the process-owned work. The holder uses applicationContext only and retains no
Activity. Launcher singleTask is defense in depth, not the ownership boundary. Device codes stay in owned memory, never Bundle/SavedStateHandle/clipboard,
logs or exception diagnostics. The Activity sets FLAG_SECURE. Process death drops a
pending login; encrypted credentials may restore only as unresolved, without automatic
requests. Credentials use A6 AndroidKeyStore AES-GCM and an atomic no-backup ciphertext
slot; its random local selector is also under noBackupFilesDir, not provider identity.
Sign-out cancels owned auth/read/refresh work, immediately revokes history access, and
attempts both slot key/ciphertext removal and local history deletion in process-owned
storage children. It does not revoke remote sessions. Account replacement invalidates the
old generation and settles both durable removals before new authentication. Late results cannot
publish old account data or persist into a newer generation. Combined durable deletion
completes before SIGNED_OUT/REAUTH_REQUIRED; partial failures and cancellable wait timeouts report
STORAGE failure without permitting new traffic or writes over pending removal. Backup/extraction rules exclude app data.
Native fake tests and the owner's physical-phone session are separate evidence boundaries.

Refresh is serialized per session. Complete rotated credentials are durably saved
before publication. A non-secret, fsynced uncertainty marker in the no-backup slot
fails closed on restart during rotation or failed deletion. A write/marker failure
after the server may have consumed a token quarantines the session and requires sign-in;
the old token is never a fallback for a failed durable rotation. Terminal selected-source
refresh errors and refresh HTTP 401/403 require re-auth. Network, 5xx, malformed and 429
outcomes remain transient under the selected contract, with no fabricated quota values.
Transient outcomes cannot establish whether a remote token was consumed; a later
provider rejection can still require sign-in. Cancelling an unfinished refresh removes
uncertain local credentials. An accepted successful rotation is settled even when its
read waiter has expired; it does not authorize an expired endpoint retry. There is no
invented token TTL or remote-revocation API. Diagnostic objects expose only categories
and redact arbitrary provider error descriptions, token pairs and response bodies.

PR workflows run on hosted runners with no live credentials; strict Gradle checksum
verification, full-SHA Action pins and hash-pinned Python inputs protect build
acquisition. These are limited safeguards, not a security certification.

The gate reports binding UNRESOLVED and identity UNVERIFIED; token receipt and HTTP
200 do not establish identity. The owner accepted the single identity-unverified
session under Q1 and recorded A8 GO on 2026-10-07 (issue #48). A10 refresh/rotation,
re-auth and local logout require their own exact-candidate hosted and safe owner live
checks; initial sign-in is not proof of refresh or parent #4 completion. Never infer
workspace from tokens or add another endpoint/protocol to resolve it without approval.
The standalone history adapter stores only bounded normalized numeric/time/categorical
facts and opaque local credential-lifetime/clock UUIDs. It stores no credentials,
provider/account identity, plan text or raw response. History is framework SQLite under
`noBackupFilesDir/usage-history`, excluded by the existing cloud/D2D rules; the owner
accepted sandbox-private history without Keystore encryption. Its subordinate lifetime
coordinator and bounded foreground recorder are wired to the one process session owner;
no graph presentation is implemented. Only accepted USAGE deliveries may supply samples;
cache/tick/inventory/baseline publications do not. Queue loss and read/write failures are
categorical, not raw exceptions; late durable work cannot publish a retired generation.
The ceilings are 30-day trusted
observation aging, 100,000 observations and 32 MiB for the entire owned directory,
including rollback journal/control replacements. Reads/fields are also bounded; unknown
or anomalous clocks suspend aging without disabling count/byte bounds.

History access is partition- and runtime-capability-scoped. Retirement revokes access;
irreversible removal fsyncs a fixed external privacy fence before deleting database
records/cursor/high-water. Reopen cleans a pending fence before admitting a successor.
Corrupt, missing or interrupted control data does not silently create a new history;
explicit quarantine/recovery discards database artifacts but never re-adopts a deleted
partition. Unknown schema versions are reported, not silently migrated. Deletion is
logical app-visible removal, not forensic erasure, encrypted-at-rest storage or remote
revocation. Runtime reservation is not durable completion: the process lifetime
owner settles the captured ticket alongside credential cleanup and reports storage failure.
History's fixed binding file stages a new opaque partition before a fresh credential save;
activation requires checked durable adoption. Only ACTIVE bindings restore with clean
protected credentials. STAGED/orphan/interrupted control never guesses linkage; it is
unavailable or discarded. A DELETING receipt on fresh-owner restoration requires combined
cleanup even if the credential slot still looks clean. The stable connection-session UUID
and credential envelope/format are unchanged and never used to infer history identity.
Token equality, JWT claims and account hints are not inspected or persisted. Ordinary
cancel/shutdown retain a clean lifetime but revoke runtime authority; foreground retirement
also revokes held ports without deleting history. Returning observers re-adopt with fresh
runtime authority. An already irreversible-admitted append may settle, but combined deletion
removes it before successful terminal publication. Late reads and stale appends are rejected;
an exactly-once old deletion ticket cannot delete a successor. If all filesystem writes
and removals fail, no unconditional restart/privacy guarantee is possible. Numeric/time
history may still reveal usage patterns to someone with app-sandbox access. Native
close/reopen fixtures do not establish literal process death, OS backup extraction or
physical-device deletion behavior.

Private-interface/terms risks in
[research](docs/research/m0.md) remain unresolved integration constraints.
