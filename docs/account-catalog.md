# Local Codex account catalog

The catalog introduces durable **local** entry addressing, not multiple active runtimes.
The existing process owner still uses only the original credential slot and history
location. There is no new account list, sign-in path, dashboard switch or scheduler policy.
Account settings navigation does not select an account. The later dashboard dropdown
and all-enrolled-account scheduling are separate integration work.

## Identity and locations

`noBackupFilesDir/account-catalog/catalog` stores versioned, checksummed, non-secret
metadata, atomically replaced and directory-synced before publication. It is a sibling
of `credentials` and `usage-history`, never an extra artifact inside the strict history
directory. Interrupted temporary catalog files are not committed state.

Each entry has an immutable random internal ID, account type (`CODEX`), explicit
credential-slot reference, history-directory reference and the continuing history
partition when available. These are distinct from the identity-compared runtime
credential/history generations. The legacy entry retains `credentials/<slot>/value.bin`,
the exact Android Keystore alias and authenticated credential header/AAD, and the
existing `usage-history` directory. Neither protected bytes nor the history/name formats
are migrated. New internal storage references use independently generated slot IDs and
sibling `usage-history-<entry ID>` directories with the same existing stores/formats;
allocating a reference does not authenticate or activate another runtime.

The user key is the exact validated `(local name, account type)` pair. Existing alias
validation, whitespace handling and Unicode limits remain unchanged. A conflicting key
is rejected without replacing, merging or selecting another entry. Rename changes the
key, not the internal ID, credential slot or history partition. Unnamed legacy state has
no user key until named; unreadable name state stays unavailable, not an invented label
or provider identity. A previously known key remains reserved if its sidecar becomes
unreadable, until an explicit successful rename repairs it. No token/JWT comparison or
real-provider-account deduplication is performed; distinct local aliases may represent
the same real account.

## Migration and recovery

Catalog creation retains the original `connection-session` selector. An existing catalog
and selector must agree. If both catalog and selector are absent but credential/history
artifacts remain, restoration fails without treating them as a fresh installation.
Catalog corruption is a storage failure, not an empty catalog, and does not delete the
legacy installation.

The ordinary protected-credential restoration runs first. Successful history restoration
then adopts its existing partition and name into at most one legacy entry, committing
that entry and the initial selected ID **before** session adoption/publication. Repeated
restoration reuses the committed ID. A catalog commit failure prevents adoption without
erasing valid credentials/history; a later holder may retry migration. Unavailable
history remains unavailable and does not become an invented empty partition or block
otherwise valid live quota reads: the catalog records that the partition/name is unknown.
It grants no history capability. When the same retained history becomes readable, an
unknown partition/name is reconciled independently and durably into the same entry and
selected ID, including name-only recovery with an already known partition. Known unnamed
is not unknown. An already known partition cannot be rebound. Login staging failure
still goes through checked history restoration and catalog adoption before publishing
usable credentials.
A catalog failure at that boundary retains protected credentials for a later retry,
without publishing a usable session. Missing credentials, corrupt ciphertext, key loss and a
pending rotation marker retain the existing combined fail-closed cleanup behavior.
Ordinary successful restoration remains dormant and sends no provider requests.
The first foreground registration also retries unavailable name metadata when history
is already healthy; a real foreground return or fresh holder retries the same durable
association. These continuations use the existing generation-bound storage lane. A
metadata-only retry retains the healthy runtime port and live editor rather than
rebinding history or activating quota reads. Repeated triggers share in-flight recovery.
A required catalog commit failure during later recovery retires an already usable
runtime as FAILED/STORAGE, preventing further reads or stale name publication. Settled
credentials/history remain available to a fresh holder; the existing unfinished-token-
rotation quarantine and deletion policy is unchanged.

Deletion writes a catalog receipt before erasing history. That receipt survives even if
history is already physically gone while credentials remain readable. Restart requires
combined removal, not restoration. Only the process owner's successful credential AND
history deletion barrier removes the catalog association/selection and clears the receipt.
A failed or interrupted barrier remains pending. No fallback selects an unrelated entry.

## Rename transaction

The existing history storage lane also owns catalog/name settlement. It first persists a
rename intent, reserving both the old and intended keys, then writes and syncs the existing
partition-bound name sidecar, then commits the new catalog key and clears the intent.
Restart replays an outstanding intent against that same partition before publishing its
name. An explicit retry with the same live editor also settles the prior intent first,
then reserves the requested key; it cannot discard or overwrite an unsettled intent.
Failure never reports successful settlement; both keys stay reserved while pending.
An unsuccessful sidecar replay leaves the name unavailable but does not block otherwise
valid credentials or revoke healthy history access: the entry, selection and reservation
are already durable. Unavailable history still grants no history capability. A later
checked restore or explicit retry can settle the same intent. While pending, sidecar
reads never import a name over the journal, even when the prior name was unknown.
Rename-only finalization failure, including after a successful sidecar write, reports
a save error without revoking otherwise healthy credentials, quota or history access.
The retained intent reserves the old and intended keys; another rename must first
settle that operation through the existing explicit retry or metadata recovery.
Initial catalog adoption/selection and required identity reconciliation commits remain
fail-closed. No name is published as settled before successful finalization.
The captured generation/partition edit authority and deletion barriers remain unchanged.

Synthetic JVM and hosted Android fixtures cover catalog conflicts/atomic interruption,
real Keystore credential readback, real SQLite records/reducer/high-water/retention
continuation, sidecar readback and interrupted rename/deletion. Holder reconstruction is
not evidence of actual process death or live-provider account identity.
