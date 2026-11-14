# Local Codex account catalog

The catalog provides durable **local** entry addressing. The single process holder retains
concrete entry-owned controller/session/credential/history/recorder stacks behind an internal
exact-entry ingress. Construction and restoration are dormant. The Activity observes the internal
persisted dashboard selection; the production worker keeps its original exact compatibility
runtime. There is no new account list, sign-in path, dropdown or scheduler policy.
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

Deletion writes an entry-and-operation-tagged catalog receipt before erasing history.
Independent terminal cleanups may overlap while their protected I/O is suspended, so the
same journal retains one receipt per entry. Each existing deletion ticket captures its own receipt;
each controller waiter captures that ticket and its deletion jobs before suspending.
Completion never looks up whichever operation is current afterward. Catalog v3 adds a captured
`RETAIN`/`REMOVE` disposition to each exact entry/operation receipt. It reads v1 and v2
receipts as `REMOVE`, preserving their original semantics and operation UUID; it never
guesses unavailable historical intent from the current phase or selection. A v1 receipt
targets only the legacy association (or the pre-adoption empty legacy slot). Every new
write uses v3; older applications cannot read this format.

That receipt survives even if history is already physically gone while credentials remain readable. Restart requires
combined removal, not restoration. Only the process owner's successful credential AND
history deletion barrier settles its captured disposition and clears the receipt.
`RETAIN` invalidates credentials/history and clears the old partition association, but keeps
the immutable address, valid local name and dashboard selection. Reauthorization is not a
request to choose a healthier peer. The old partition never authorizes the invalidated entry;
a later checked fresh adoption may bind a new partition and restore the retained local name
to its sidecar. `REMOVE` deletes the entry and, if selected, chooses the first remaining entry
in stable catalog order (or null when empty), without sorting labels or inspecting health.
A failed or interrupted barrier remains pending. Each ticket retains its own applied
finalization provenance in memory: if its exact receipt was present when completion
attempted replacement and readback confirms its removal after a directory-sync failure,
that same ticket retries directory durability. It never rewrites its old snapshot or
accepts an unrelated absent receipt; newer peer and successor changes remain untouched.
Failure remains a storage error until sync succeeds, without another persisted journal.
The existing controller removal path consumes this primitive. Captured management of saved
entries uses it directly; additive authentication remains a separate integration unit.

## Captured saved-account management

Existing settings render the controller's operation permission with its state. Reauthorization
and Cancel submit that displayed permission, and the serial owner checks it before retirement.
The permission is the existing opaque `AccountRemoval`, not a second generation or owner.
Ordinary quota updates, rotation and dashboard A → B → A do not renew it; retirement and
required cleanup do. A delayed reauthorization cannot become replacement of a healthy successor.
The UI Cancel additionally requires an unfinished authentication and a live displayed code
when one is present; completing that login does not authorize a late cancellation of its session.

Browser clicks retain the exact displayed `AwaitingUser` and controller. The owner reuses the
copy identity/generation/poll-deadline gate to return only the fixed verification URL. Activity
launch runs on Main and rechecks state/retirement after admission suspends; platform failure
returns only to that captured auth operation. Neither path looks up dashboard selection. Copy
and live rename retain their existing exact code/editor fences; there is no credentialless
catalog-only rename. No user-provided browser URL is accepted.

Removal confirmation also remains on its captured controller/permission. A saved retained
REAUTH_REQUIRED entry is removable without a usable credential, unlike an empty anonymous
installation. Dismissal sends no removal command, and the existing modal-open state rejects
an already captured confirmation after the modal was closed. Confirmation uses the original combined
protected/history/catalog barriers and RETAIN-then-REMOVE escalation; it does not reinterpret
an old ticket. Nonselected removal does not activate a peer. Selected removal applies the
stable immediate-request fallback below, including a dormant peer. No Add, public account
list/dropdown or all-account scheduler is supplied by these management controls.

The existing Connect, Cancel, browser and removal-confirm controls expose their exact
application callback through one internal non-action semantics property. Each control
uses the same immutable callback value for ordinary `onClick` and observation; no
accessibility action is replaced. Retaining that value observes displayed authority
without retaining a framework click wrapper that can detach or change its callback.

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

## Runtime ownership boundary

`AccountRuntimeOwner` serializes construction, retains controllers by immutable entry ID
and rejects competing slot ownership. Legacy bootstrap and exact legacy addressing share
one controller even when the ID was first adopted during restoration. All retained stacks
share one serial mutation dispatcher; protected I/O remains off-lane. Each controller owns
a child job, so its shutdown drains its own admitted work without cancelling a peer or the
root. Root shutdown closes admission before draining all retained stacks. Address lookup
never retires, adopts or activates a session; accepted rotation stays with its original
store and generation after a network waiter detaches.

The compatibility scheduler callback cancels only its captured continuing lifetime.
Nonlegacy runtime ingress has no production enrollment callback in this intermediate unit;
all-enrolled resolution and scheduling remain the scheduler integration's responsibility.
There is no selected-only worker routing or new public control.

## Dashboard selection and display authority

`AccountCatalog.select` accepts only a saved immutable address. The selected ID is
replaced and directory-synced before the process owner publishes successor display or
network authority. Idempotent selection keeps the same `DashboardSelection` identity
but still requests quotas as an explicit action. Actual selected-entry removal uses the
same immediate request policy, including a previously dormant remaining entry. Passive
construction, cold restoration and settings/history navigation never activate quotas.
`settings(id)` returns an independent addressed controller handle, without selecting it.

After a failed replacement or fsync, the catalog re-reads the actual file and fences the
next write until readback and current-directory sync succeed. Unreadable uncertainty
cannot be overwritten from stale memory. The observable catalog remains at its last
confirmed publication until that confirmation. Initial identity/selection failures are
fail-closed. Optional rename-finalization uncertainty keeps the existing old/new key
reservation and healthy quota access; its existing settlement path confirms the current
file instead of replaying an old snapshot or adding a rollback journal.

Each actual selection transition creates a fresh root identity in a `StateFlow`, including
A → B → equal-query A. MainActivity collects it and registers one exact STARTED lease per
lifecycle interval. Transfer removes only those captured foreground tokens and acquires
them on the selected retained controller. A late STOP is idempotent and cannot release a
peer or a newer registration. No switch calls connect, session retirement or adoption.
Accepted rotation, protected writes and admitted recorder work retain their original
controller, store and native capability. Deselection hides history without pausing or
rebinding that accepted storage; occasional missed observations remain noncritical.

History contexts capture their actual-host root check at issuance. The host checks both
the current root and recorder/request identity before using a delivered page or plot
cache. A valid account-local storage binding alone cannot make a predecessor root's page
visible again. Fresh root collection also invalidates same-phase Compose/strong-skipping
presentation; a plain latest-ID field is not the notification mechanism. These are
host-pass authorization checks, not atomic erasure of already-drawn pixels.

Native composition cases reuse actual protected Keystore and SQLite fixtures for two
preseeded entries, stable runtime identities, subordinate shutdown, detached rotation,
overlapping terminal cleanup and reconstruction across incomplete barriers. Auth transports
and faults are synthetic. Authored assertions and compilation are not native execution,
live-provider rotation or actual process-death proof; exact-head hosted CI remains required.
