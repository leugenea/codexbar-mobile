# Architecture

## Implemented application

One Android phone app module with namespace `io.github.leugenea.codexbarmobile` launches
`MainActivity` via MAIN/LAUNCHER directly into the real `ConnectionScreen` dashboard.
Disconnected, restoring/loading, reauthorization, error and unknown usage remain
honest projections of the one process owner; no sample model or preview route is shipped.

```text
MainActivity / StateFlow observer + identity-tagged STARTED lifecycle observer
    -> ConnectionScreen / dashboard, two drawer detail routes and Account settings
    -> NativeConnection.get(applicationContext) / one lazy process owner
```

Launch and Activity recreation always render this flow. There is no saved preview
or tab selector, and no legacy Bundle selection can switch the destination.
No storage migration is needed for the retired ephemeral UI choices.

The source manifest requests exactly INTERNET, ACCESS_NETWORK_STATE,
RECEIVE_BOOT_COMPLETED and WAKE_LOCK, with `usesCleartextTraffic="false"`.
Debug installs as `io.github.leugenea.codexbarmobile.dev`; release installs as
`io.github.leugenea.codexbarmobile`. Classes and native/capture identities retain the
namespace; package data is isolated without an upgrade/migration promise.
The exact merged/APK/installed allowlist also includes the existing AndroidX Core
signature-only `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`;
it is not another platform/network permission. Gates reject any missing or extra
permission and any Network Security Config override. The manifest flag is sufficient
for the current API 26 minimum / API 37 target; moving to target API 38 or adding
a Network Security Config requires a separately reviewed cleartext policy update.
Debug/test builds do not opt into device cleartext; JVM local-server tests do not
need that opt-in. Backup/extraction rules exclude app data. Fresh process-owner
construction/restoration remains dormant before explicit activation, even on launch.

Screenshots and ordinary Android Recents task previews use the normal platform policy
throughout the app, including login and dialogs. MainActivity does not set a secure
window flag or disable Recents screenshots; Compose dialogs inherit the parent window
policy. There is no capture preference. This does not change credential protection,
secret/log redaction, permissions, cleartext-off or backup/extraction exclusions.

## Native connection screen

The default Connection screen consumes the real owner state:

```text
MainActivity / thin StateFlow observer
    -> NativeConnection.get(applicationContext) / one lazy process owner
    -> ConnectionController / process-lifetime SupervisorJob + IO scope
        -> A7 DeviceCodeAuthenticator -> A4 HttpTransportAdapter
        -> A10 SessionCoordinator -> selected token endpoint via A4
            -> A6 KeystoreCredentialStore (binding Unresolved)
        -> AuthenticatedProviderReader -> NativeFeasibilityReader -> A2 ReadPolicy + A4 JSON boundary
            -> A9 UsageResponseParser / BankedResetResponseParser -> A1 normalization
```

MainActivity builds a fixed ACTION_VIEW/BROWSABLE intent for the system browser.
The user code is displayed from A7's owned memory and never saved. Copy code is an
explicit resource-backed TextButton beside that visible code; only its click writes
a plain-text clip through a write-only Android adapter. The description is marked
sensitive using the compatibility key identical to the API 33 constant on every API.
A polite resource-backed live region is recreated after each successful copy
without a duplicate toast.
The Main-thread click rejects an obsolete display identity; the controller repeats
identity checks against its projection and A7 plus A7's existing monotonic poll deadline
on its existing serial owner lane,
with no suspension between admission and write. Android clipboard writes do not
require Main: keeping that final operation on the owner lane prevents cancellation,
completion, expiry or replacement from interleaving with authorization. A captured
predecessor action never reacquires a successor code, even if its text is identical.
Expiry denies copy even while a suspended poll still leaves the old code displayed;
the poll budget, requests and scheduling are unchanged. No clipboard read, verifier/token
export, automatic write or saved copy status is added.
One bounded attempt survives Activity recreation, backgrounding into the browser and Activity
finish. Every Activity observes the same process-scoped controller; no ViewModel or
Activity coroutine owns authentication, refresh, reads or local deletion. The lazy
holder constructs from applicationContext only. `singleTask` reduces duplicate
launcher instances, but does not establish correctness: even multiple Activity
observers must share the holder. Manifest gates require the default app process and
reject component process overrides. Process death drops pending login; it is not
reconstructed from Bundle, an intent or a persisted code. The test-only holder reset
closes the old controller before clearing it and explicitly models a fresh runtime;
Activity finish/relaunch alone is not a restoration or process-death simulation.
The holder's factory setter, injected constructor and reset are marked
`@VisibleForTesting(otherwise = NONE)`; lint rejects production callers without
restricting the normal factory getter. Reset also requires an installed test factory.
Production construction stays private and uses the real transport/default clocks.

The only durable selector is a random local UUID under noBackupFilesDir; it selects
A6's encrypted slot and is not account/workspace identity. A fresh process owner may
restore credentials only as Unresolved, without automatic network requests. The
selected flow provides no authoritative token lifetime: there is no inferred TTL,
JWT inspection or expiry schedule. Key loss, corruption or an interrupted rotation
restores as re-auth required, never a usable old credential.

One process owner holds the store, authenticator and session. It serializes runtime
commands, request enqueue and state publication on `Dispatchers.IO.limitedParallelism(1)`.
No suspension separates a generation check from request enqueue. Blocking protected
I/O runs on the separate storage dispatcher and re-enters the owner lane for checked
publication. Commands are asynchronous; observers may enqueue commands but cannot
reenter the current mutation. The owner retires/cancels superseded work; there is no cross-controller ownership
registry, displaced Activity owner or shared Activity deletion barrier. Local sign-out
invalidates auth, reads, refresh and history capabilities and attempts both the A6 key/
ciphertext and history removal without a remote revocation request. Account replacement
settles the preceding combined durable removal before new authentication. Combined removal
precedes successful SIGNED_OUT or REAUTH_REQUIRED publication; partial failure and timeout
are storage failures, not deletion success.
The process scope, rather than an Activity lifecycle, owns this work. Generation and
cancellation checks reject late results and writes after logout or replacement. The
uncertainty marker keeps interrupted rotation fail-closed across a fresh runtime.

Busy Connect/read commands coalesce. Account replacement while a read is pending
is explicit Cancel or Sign out, then Connect; a busy Connect does not claim success.
Local removals are process-owned coroutine children. Runtime waiters suspend
cancellably with a five-second storage bound; timeout publishes FAILED/STORAGE,
not SIGNED_OUT or REAUTH_REQUIRED, and never releases a still-running removal.
Connect/read admission stays closed until the actual durable outcome. A completed
failed removal can be retried explicitly, never by silently restoring its old token.
The small `CredentialDeletion.complete()` ticket remains only for non-I/O revocation
and exactly-once A3 durable completion; it has no executor, future, process registry
or cross-owner arbitration. A3 uses a short generation/commit-admission monitor
and a separate I/O monitor: cancellation/revocation cannot block behind fsync, and
a previously admitted irreversible commit settles before deletion. The test-only
holder reset waits for old owner children before allowing a fresh owner.

After a successful selected exchange/persistence, the reader issues only the two
selected GET routes. Each logical read has a 30-second deadline and bounded,
cancellable A2 backoff. One usage/inventory read has at most one 401 refresh/retry
allowance across both endpoints; generic read 403 remains forbidden, not session expiry.
A10 coalesces concurrent refresh waiters for the same rejected envelope. Refresh uses
only the selected form POST `/oauth/token` with `grant_type=refresh_token`, current
`refresh_token` and selected `client_id`. A returned refresh token replaces the old one;
when absent/empty the pinned selected-source behavior retains the previous one. A
complete envelope is durably saved before new tokens become observable. An accepted
rotation is still settled if the waiting read expires before its continuation resumes;
the expired read cannot continue its endpoint retry or make the consumed token reusable.
Terminal selected-source refresh codes or refresh HTTP 401/403 clear the session and
require sign-in. Network, 5xx, malformed and 429 failures stay categorical/transient;
a later explicit action may retry, not an automatic refresh loop. A durable-write or
rotation-marker failure quarantines the session and requires sign-in; no possibly
consumed old token is reused. A non-secret, fsynced uncertainty marker under the owned
no-backup slot prevents interrupted or unsuccessfully deleted rotations from restoring
unsafe credentials. Successful complete-envelope persistence or a documented transient
result clears that marker. Explicit cancel or owner shutdown during an unfinished
refresh deletes its uncertain credentials; Activity finish does not shut down the owner.
Generation checks reject late writes/results after logout or
replacement; diagnostics expose categories, never token values or error descriptions.
The live usage and banked sections show duration-identified five-hour/weekly
percentages, reset/expiry labels, provider flags and banked counts, with independent
endpoint observation times. The former raw feasibility diagnostics dump is not
rendered; connection status, identity/lifecycle boundaries and connect/cancel/read/
session-refresh/sign-out controls remain. Both endpoints use the production A9
decoders; no independent A8 JSON fact extractor remains. Decoders retain missing/null/wrong-type knowledge and all
window/item siblings through A1, without treating M0 fixtures as provider guarantees.
Banked inventory also retains the `credits` row-container knowledge/reason and array
size independently of provider counts and individual row validity. The pure C1
`EntitlementPresentation` retains this field: absent/null map from A1 UNAVAILABLE to
UNKNOWN, with MISSING/PROVIDER_NULL reasons still distinct; wrong-type containers map
to MALFORMED. Invalid JSON remains an endpoint INVALID_RESPONSE/MALFORMED failure,
not an invented observation. A valid array with malformed rows stays KNOWN at the
container level and PARTIAL at the inventory level; C1 preserves the row failures.
EMPTY requires an explicitly empty array and a provider-reported zero; counts are
never recomputed, and discrepancies keep precedence while container facts remain
visible. Endpoint errors and stale-generation isolation keep their existing precedence.
Full normalized observations remain in memory; endpoint/result diagnostics redact
arbitrary provider strings and identities. B3's live Material 3 usage section consumes
B2's retained usage success, latest attempt, freshness and evaluation time. Its pure
`UsagePresentation` preserves exact decimal text (bounded plain expansion, exact
exponent notation for extreme scales) alongside approximate fractional bar semantics;
permission and limit flags remain independent. Duration-classified missing/ambiguous
windows never become zero, and malformed fields do not hide independently known facts.
The shared production root uses a Material3 `Surface` with the selected background
and inherited on-background content color in both light and system-dark configurations.
The live periodic-usage card uses 20dp content padding,
16dp grouping and 8dp window spacing, title roles and theme-default progress bars;
all percentages and reset/status facts still come from the normalized presentation.
The banked section remains independently grouped below it, in the same single scroll
container. The selected root/card/content/bar/track paint contract remains unchanged.
B1 alone supplies reset absolute/relative labels, including unknown/sub-minute/passed and
repeated-hour offsets. Relative durations floor the original elapsed time to whole minutes,
split into elapsed 24-hour days/hours/minutes and omit every zero unit. Positive durations
below one minute show "less than a minute"; the existing "remaining" wrapper is retained. Status transitions use polite accessibility live regions; ordinary
countdowns do not repeatedly announce. One wrapping, vertically scrollable content tree
keeps actions reachable in landscape. Usage stays with the process owner across
Activity recreation; no connection state, credential or obsolete UI selector is
Bundle-restored. C2's read-only banked-reset
section consumes B2's inventory cache through C1
`EntitlementPresentation`; `BankedResetPresentation` only adapts resource labels and
independent freshness/error metadata. Provider counts and comparison clocks are kept
verbatim, including discrepancies and expired-available rows. B1 supplies both expiry
labels, distinct from periodic reset. Row/count containers each own one accessibility
announcement; duplicate visible text is hidden from accessibility. No action callback,
activation/purchase request or new owner is introduced. Retirement discards every
entitlement fact; the existing A10/B2 generation path rejects late inventory results.
The gate retains explicit read/session-refresh controls and sign-in-again action.

A1 applies the owner-selected app precision policy to all window durations,
in either source slot: two valid absolute/relative-derived reset
instants are discrepant only when their full difference exceeds 60 seconds in either
direction. Exactly 60 seconds is accepted; 60 seconds plus one nanosecond is not.
This is an application policy, not a provider sampling, rounding or latency guarantee.
Accepted pairs keep the exact original absolute reset as the effective target; original
relative seconds, derived instant and observation clocks retain their precision.
Single-source, malformed/overflow and clock
handling are unchanged. B1 still formats/counts down from the original target.

B2's `UsageRefresh` is a subordinate scheduler in that same owner scope/lane, not
another session owner. The default Activity observes `repeatOnLifecycle(STARTED)`;
identity-tagged observers keep one disappearing Activity from cancelling another
visible observer. Connect/manual read activates refresh for the current generation.
Protected restoration in a fresh owner remains dormant until an explicit action,
including on default launch. A background caller may supply only its previously
enrolled continuing history lifetime, as described below; holder construction alone does not activate reads.
An already activated process owner becomes foreground eligible on Activity launch
without a tab click. Foreground launch/recreation/resume requests one bounded cycle;
manual/resume/connect triggers coalesce while a cycle is in flight. Foreground loss
cancels reads only without an independent background consumer, and always stops local
ticks and polls; it never owns token rotation or auth.
A10's independent rotation settlement and durable quarantine rules are unchanged;
only an explicit manual read unlocks a retained transient token-refresh failure,
never an automatic poll or lifecycle-resume trigger.

Visible polling admits no earlier than 60 seconds after the preceding cycle's
admission; each endpoint operation has its own 30-second logical deadline. A2's
deferred Retry-After monotonic boundary survives in the endpoint outcome and holds
later triggers/polls (conservatively the whole cycle) until that boundary. Usage and
inventory publish independently, each retaining latest outcome, last successful
observation and monotonic successful clock. Errors do not replace successes; stale
starts at 15 minutes without that endpoint succeeding. A local one-second tick only
reevaluates freshness/evaluation time; B1 projects reset countdowns on demand and
retains percentages after reset passes. No tick requests I/O, invents zero, rolls a
reset forward or adds history. Owner retirement synchronously invalidates this cache;
old-generation/cancelled completions cannot publish or enqueue a successor read.

The dashboard's pull-down gesture replaces the Refresh usage button (also removed
from connection details). Material3 1.4.0's `Modifier.pullToRefresh` surrounds the
existing full-height vertical scroll container only on the dashboard; its `enabled`
flag uses the existing usage presentation's refresh eligibility, so disconnected,
reauthorization and busy states consume no pull scroll. The Material3 indicator
tracks the owner's entire `refreshing` cycle, including independent inventory
completion and errors, rather than a gesture-owned timer. A custom accessibility
Refresh action invokes the same `controller.readUsage()` command when eligible.
Short/empty content still has a scrollable viewport; details navigation, account
settings, controller ownership, coalescing and cache/history contracts are unchanged.

Every state reports binding UNRESOLVED / identity UNVERIFIED. Token receipt and HTTP
200 do not resolve account association. The owner accepted this single identity-unverified
session under Q1 and recorded A8 GO on 2026-10-07 in issue #48. CI uses fake transport
and synthetic protocol data; owner-operated physical-phone sign-in and actual
process-kill/relaunch are separate evidence boundaries, not established by parser
tests. A10 live refresh/rotation/logout still needs an exact-candidate owner check;
A8 sign-in alone does not close parent #4.

## History JSON export and import

The local-history detail route exports all retained entries plus coherent continuation
state through SAF, off Main, without credentials/account metadata or the local
partition UUID. The versioned production encoder/decoder, privacy boundary and
forward-compatibility contract are documented in [history export v1](docs/history-export.md).
Import uses JSON-only SAF OpenDocument, then explicit replace confirmation. A
process-memory lifetime ticket cannot target a successor login. Bounded UTF-8 input
is strictly validated and rebound to the current partition. The store's existing I/O
lock spans one SQLite transaction replacing entries, high-water, reducer and retention
continuation; age/count/byte eviction is inside the transaction. Revoked capabilities
fail before irreversible admission. The recorder barriers its admitted read/append
pair and holds queued observations across replacement, preventing a pre-import N+1
from leaking past the imported cursor. Confirmed work belongs to the process owner,
which rereads the active presentation after success even if its Activity waiter is
cancelled. Decoding alone does not write storage. Registered JVM/native tests cover
these contracts; source/parser checks do not establish hosted runtime acceptance.

## Pure usage-history contract (M4a-1)

`history/WindowHistory` consumes A1's normalized `UsageObservation` and field knowledge;
there is no new parser, slot interpretation, account inference, B1 formatting or B2
refresh integration. `HistoryPartition` is an opaque durable **local credential-lifetime**
UUID, not verified provider-account identity. `HistoryGeneration` is a separate,
identity-compared runtime capability, never persisted. M4a-4 owns allocating/adopting
and revoking those values: rotation and clean restoration retain the lifetime; logout,
replacement, terminal re-auth/quarantine/key loss delete it; later sign-in never joins it.

Each admitted event has a contiguous partition-scoped `ObservationId` (durable ordinal),
including background/error/status events. A store serializes admission and commits the
immutable entry, constant-sized reducer cursor and high-water ordinal together. Retry
uses the **same ID**, is a no-op even after retention eviction, and cannot change a
measurement. A new admission uses the next ordinal; gaps in ordinal order are rejected,
failed admissions consume nothing, and `nextId() == null` means `Long.MAX_VALUE` capacity
is exhausted, never wrapped. This is append order, not wall-clock order. No refresh,
countdown or analytical calculation is itself an observed measurement.

Candidate window identity is exactly partition + A1 duration-classified kind + supported
SI-second duration + exact known non-discrepant **absolute** reset. Primary/secondary
is only source provenance. Relative-derived resets remain explicitly relative-derived,
not absolute identity; unknown/malformed/unsupported/discrepant reset facts stay typed.
New weekly observations accepted by the 60-second app policy can therefore use their
exact original absolute reset as identity. Nearby absolute resets remain distinct;
no stored observation is rewritten or previously recorded discrepancy reinterpreted.
A malformed relative sibling does not erase an independently known non-discrepant
absolute reset or a known percentage. Missing/ambiguous/unsupported-duration selections
produce status, never a guessed periodic point. An unambiguous known percentage with
unusable reset produces an **unkeyed isolated** real point; unknown observation time
preserves numeric knowledge but produces no timestamped point. Plan text, account hints
and banked-reset identities are not retained. Permission and limit flags stay independent.

Segments are distinct from candidate windows. Reset changes split with unknown cause;
a decrease for the same candidate splits as a correction, not a proven rollover/manual
or banked reset. The exact provider `BigDecimal` and actual `observedAt` never change.
Background/error/field/ambiguity/unkeyed/unknown-time gaps split the next keyed segment.
The cursor keeps only the last keyed point and the latest explicit pending gap reason,
so a gap cannot erase correction confidence or conceal a later same-window decrease.
All intervening statuses remain separate entries. Interval **>120 seconds**, wall time
that is non-increasing, same-epoch non-increasing/unavailable monotonic time, or absolute
wall/monotonic delta disagreement **>5 seconds** split continuity. Durations are compared
without conversion to overflowing nanosecond/millisecond scalars. A fresh `ClockEpoch`
splits by default; monotonic values are **never subtracted across epochs**. Multiple
applicable break reasons are retained on immutable segment-start metadata. Segment IDs retain provenance even after pagination or retention removes the
segment's first real point; issue 184 charts connect the shown points across these runs. No artificial start/reset/endpoints or forward-filled points
are admitted.

Baseline availability is orthogonal to measured history. `NominalStartConfidence` says
**nominal full quota**, not an observed quota start. A correction makes that same
candidate's nominal start uncertain even after gaps; a different candidate starts a new
nominal assumption. `BaselineEligibility` distinguishes no window/ambiguous/unknown
percent or observation time, unkeyed reset, unverified/discontinuous clock, before nominal
start, after reset, uncertain correction and time-range overflow. The supported nominal
interval includes both endpoints; no clamping or baseline points are implemented here.
M4a-3 alone implements the approved analytical reference: with elapsed SI duration D,
reset R and nominal start S = R - D, B(t) = 100 * (t - S) / D within [S, R], and observed
used percent minus B(t) in percentage points. Division uses DECIMAL128; exact percentages
are stored without rounding. Five-hour and weekly references stay independent. This is
not prediction, a ratio, a derivative, projected exhaustion or an alarm.

`HistoryStore` is a blocking partition-scoped port. M4a-2 implements framework SQLite under
`noBackupFilesDir`, bounded by 30 days / 100,000 admitted observations / 32 MiB, with no
new dependency. Reads are explicitly limited, partition-filtered and ordinal-ordered;
detached snapshots expose immutable points, segments, page content, pagination and typed
age/count/byte truncation. Empty/status-only/measured **page content** is not a baseline
availability claim. Corruption/unavailability never masquerades as empty history;
delete success means durable removal of the named partition's records/cursor/high-water.
Retention does not reset high-water. M4a-4 revokes runtime sampling before deleting and
reports deletion failure honestly. M4a-5 admits only real current-generation foreground
observations, supplies clock provenance/gaps and publishes this read contract without
allowing history failures to replace or block live quota. Credential lifecycle,
refresh admission and graph rendering remain downstream; M4a-3 now supplies the pure
numerical reference described below.

`WindowHistoryTest` and `HistoryContractTest` use original synthetic normalized inputs,
literal boundary expectations and a clearly labeled in-memory port illustration. They
cover identity/slot moves, immutable decimals, ambiguity, isolated invalid-reset facts,
correction/gap/clock rules, ordinal overflow/idempotency and detached bounded read models.
They do not establish native persistence or integration. All new handwritten production
classes remain in the unchanged >=90% compatible JVM/native INSTRUCTION denominator;
actual JVM execution and whole-app coverage are hosted gates, not compilation claims.

## Bounded native history persistence (M4a-2)

`SQLiteHistoryStore.open(applicationContext)` owns one blocking serialized I/O lane,
with a file lock rejecting a second adapter for the same directory. It is not a
session/controller; the live gate constructs it under the existing process owner. The single durable
binding matches the existing single local credential slot: `createPartition()` generates
a fresh opaque UUID, while `adopt(partition)` only accepts the continuing ACTIVE binding
and revokes the preceding runtime capability. Only the returned `HistoryAccess` implements
the UUID-only port; cross-partition and stale-capability operations are rejected.

Schema v1 stores observations plus a constant-sized cursor/high-water/retention state.
The entry and next state commit in one FULL-synchronous rollback-journal transaction.
The bounded binary encoding retains decimal scale/exponents, instants/nanoseconds,
A1 categorical field knowledge, reset provenance and stable segment origin IDs. CRCs
detect accidental damaged encodings, not malicious tampering or authenticity. Blobs are
limited to 16 KiB, decimal precision to 128 digits, slots/windows to two; oversized
samples fail explicitly without consuming an ID. The native page limit is at most 256
entries plus one pagination lookahead; higher requests return CAPACITY.

Count/byte maintenance evicts oldest admitted rows while preserving high-water/cursor.
Age eviction uses only consecutive same-epoch observation wall/monotonic clocks with
positive elapsed time and at most five seconds skew. Unknown-time statuses are not
age-inferred; count/bytes still apply. The last admitted clock is persisted even for
timestamp-less statuses, so they cannot hide monotonic/epoch anomalies. An anomaly or new epoch persistently suspends age
eviction for that lifetime; no wall-only cold-start clock is trusted. Aging cannot run
on open without a fresh trustworthy observation pair. Each applied bound retains its
highest removed ordinal across reopen; age-unknown statuses may remain below that cutoff.

All history files are under `noBackupFilesDir/usage-history`: database, DELETE rollback
journal, fixed 32-byte binding/privacy fence, pending binding replacement, lane lock and
the bounded local account-name sidecar/pending replacement described below.
Memory temporaries and primary-key-ordered queries avoid external sort files. The database
uses 4096-byte pages with a conservative one-quarter directory allocation after 64 KiB
control reserve, leaving headroom for rollback pages/sector headers and control replacement.
Freed pages are reused: no WAL or VACUUM/second database. Byte maintenance may therefore
evict before 100,000 observations; ceilings are maxima, not guaranteed capacity.

`revoke()` retires runtime access without removing a continuing lifetime. `beginDelete()`
immediately fences access and returns an idempotent blocking settlement ticket. Completion
first fsyncs a DELETING binding, then removes the database artifacts and fsyncs the directory,
then settles EMPTY. An already irreversible-admitted write settles ahead of deletion on
the lane; queued stale writes cannot resurrect it. A pending durable fence is cleaned on
reopen before adoption/creation. Unknown/deleted UUIDs are never created by adoption, so a
fixed current/retired fence replaces an unbounded blacklist. A failed ticket retains the
barrier; explicit `quarantineAndDelete()` may retry. Missing/damaged/interrupted control
data fails closed. Database corruption and unsupported versions are typed, not empty;
only explicit quarantine discards corrupt/unsupported data, preserving the privacy fence.

M4a-4 schedules and settles deletion before closing the adapter, keeps successor
credential-lifetime admission blocked on failure, and gates adoption by its protected
credential lifetime. Runtime reservation alone is not a durable deletion receipt. If
the filesystem refuses every fence write and removal, restart durability cannot be
promised; failure is reported, never successful deletion. The adapter does not block or
replace live auth/quota. The adapter itself adds no dependency, encryption, identity
inference, sample timer, controller, baseline math or graph UI.

`HistoryStoreContractTest` covers the pure bounded codec, trustworthy-clock policy and
explicitly synthetic journal failure contracts. `HistoryPersistenceTest` covers actual
SQLite files/transactions, fresh-adapter reopen, capability isolation, retirement, held
commits/deletion, retention/count/physical budgets, interrupted maintenance, synthetic
SQLite-full errors, corruption/version handling and explicit recovery. Its mandatory
cases are checked by the unchanged fail-closed native report gate. Native execution and
whole-app >=90% compatible JVM/native INSTRUCTION coverage remain hosted acceptance,
not a source-compilation claim; neither is exempted or excluded.

## Durable credential-lifetime binding (M4a-4)

`HistoryLifetimeCoordinator` is subordinate to `ConnectionController`, not another owner,
scope, credential slot, account selector or sampler. `SQLiteHistoryLifetimeStorage` is its
thin blocking adapter. Runtime `SessionGeneration` identity maps to a separate opaque
history partition, never to the stable connection-session selector or token contents.
The A6 credential slot/encrypted envelope and history SQLite schema stay unchanged. The
existing 32-byte history binding adds a STAGED phase after the existing phase ordinals.

Fresh authentication starts only after captured credential and history deletion tickets
actually settle. History is staged before device authentication can save credentials;
checked protected read/adoption activates it before a capability is published. Rotation
retains the partition. A fresh holder restores ACTIVE only after clean protected credential
restoration. STAGED is discarded/unavailable, EMPTY is unavailable, and missing/corrupt/
pending control never guesses a partition. A DELETING receipt asks the process owner to
remove both stores even when protected credentials otherwise look clean. Missing protected
credentials purge orphan history. Key loss, corruption and A10 terminal/quarantine paths
reserve the same process-owned cleanup. No provider identity is inferred: UNVERIFIED and
UNRESOLVED remain unchanged.

Runtime retirement revokes captured reads/appends immediately without waiting on SQLite
I/O. The last existing B2 foreground observer leaving revokes history ports but retains the
lifetime and credentials, unless admitted background work still owns storage interest.
In that case UI display authority is still revoked immediately, while the single storage
port remains only through actual completion. Otherwise return re-adopts ACTIVE through storage
with fresh runtime capability. Cancel and holder shutdown also revoke without turning a clean lifetime into a
new login. Activities still only observe/command the process owner and do not delete it.
An already irreversible-admitted append can finish, but removal fences and deletes it
before terminal success. Already-captured reads recheck authority before returning.

Credential and history deletion run independently off the owner lane, so held/failed
history I/O cannot skip key/file removal or block command/timeout notification. The owner
awaits both real outcomes within its bounded waiter; timeout leaves both tickets owned and
admission closed. A settled failed removal can be retried explicitly. History tombstones/
quarantine persist through reopen; no successor is admitted over unsettled cleanup. Old
exactly-once tickets cannot delete a later partition. A filesystem refusing every fence and
removal remains an honestly reported failure, not an unconditional privacy guarantee.

`historyCapability(generation)` and categorical `historyAvailability` are the downstream
M4a-5/#8 seams. They expose no token/slot-derived UUID and perform no sampling or UI work.
Original synthetic `HistoryLifetimeCoordinatorTest` uses the actual owner/A3/auth/A10
coroutine topology with held storage. `HistoryLifetimeTest` uses actual Keystore + SQLite,
Activity recreation/finish, last-observer retirement, clean fresh-holder restore, rotation,
relogin, key loss/corruption/uncertain rotation, actual failed credential-file removal,
held/failed combined deletion and explicit binding crash cuts. Fresh-holder/close/reopen fixtures are not literal process-death proof.
The native report validator requires every new case, with omission/class-spoof/failure/
skip contracts; all existing A10 and history-store mandatory cases stay required. All new
handwritten code remains in the unchanged >=90% compatible JVM/native INSTRUCTION union;
compilation alone does not establish runtime or coverage acceptance.

## Local account display name

The Account settings screen shows the single connected/restored account's user-authored local label or
**Unnamed local account**, with provider identity still explicitly unverified. This is
not provider-account association, provider-side profile modification, a credential or
multi-account selection. Unreadable name storage has a distinct **name unavailable**
fallback rather than claiming that an inaccessible stored name is absent.

`AccountDisplayName` normalizes Unicode whitespace to spaces, removes control/format
characters and unpaired surrogates, bounds input to 80 Unicode code points, and trims
on save. Blank saves and the explicit Clear action remove the label. The editable draft
preserves spaces while typing. The header occupies at most two lines with ellipsis;
its semantics retain the complete bounded label. The labeled single-line editor states
the limit and blank behavior and exposes Save, Clear and Cancel. Draft/open-dialog state
uses `remember`, never Bundle restoration; committed text is restored only from storage.

The name shares the existing durable credential-lifetime history partition. An ACTIVE
binding following protected credential adoption is required to read or write it. Clean
fresh-owner restoration and token rotation preserve the partition/name. Foreground
history-port retirement retains the name and its account-lifetime edit authority; Cancel
and holder shutdown revoke the runtime editor without deleting a clean durable name.
Logout, replacement, missing/corrupt/key-lost credentials, interrupted removal and terminal
reauthorization revoke the editor immediately and delete the sidecar through the same
combined credential/history cleanup. New login cannot start over unsettled removal.
If history lifetime storage is unavailable, naming is unavailable too; it never invents
a second identity or storage owner to bypass that contract.

`AccountNameEdit` captures the exact runtime `SessionGeneration` and partition and is
identity-compared against the current editor on the owner lane and subordinate I/O lane.
The callback never reacquires successor permission. Blocking writes and their publication
are generation-checked; writes are ordered, process-owned children and do not block the
command lane. An already admitted write may settle during retirement, but deletion waits
on the existing history I/O monitor and removes it before terminal success. Pausing a
foreground history read capability does not authorize a different account editor.

`AccountNameFile` stores a versioned bounded UTF-8 record containing the opaque partition
and name under `usage-history/account-name` (at most 342 bytes). Pending writes are
fsynced and renamed, then the existing storage adapter fsyncs the directory. Both files
participate in the directory budget and fenced deletion; SQLite schema v1, history sample
encoding and the encrypted credential envelope are unchanged. Missing files mean unnamed;
wrong lifetime, oversized/truncated/invalid UTF-8/noncanonical text or interrupted pending
files mean unavailable. Name failures leave auth/quota intact, show a categorical error,
and permit explicit retry when lifetime authority exists. This metadata is sandbox-private,
not encrypted or forensically erased, and inherits the existing no-backup policy.

Name/state/edit diagnostics are redacted. No name is added to transport requests, logs,
credentials, saved-instance state or provider fixtures. Original synthetic
labels are confined to regression tests. JVM `AccountNameFileTest` exercises normalization,
bounded parsing, persistence/reopen, clear and write failures; `AccountNameLifetimeTest`
exercises the real process-owner topology, rotation/restoration, foreground retention,
held admitted writes, stale editors, replacement and failure/quarantine cleanup.
Four mandatory `AccountNameLifecycleTest` cases use Compose waits, the actual Activity,
isolated Keystore/SQLite and synthetic transport for edit/accessibility/bounds, recreation,
fresh-owner storage restoration, deletion/stale callbacks and damaged-name/key-loss recovery.
The report/retry fixtures require those cases without adding capture targets. These are
authored test contracts: actual native execution, lint and compatible whole-handwritten
>=90% INSTRUCTION coverage remain hosted gates; no process-death or live-provider claim
is implied by compilation or fresh-holder restoration.

## Explicit local account removal

Account settings exposes **Remove account from this device**, not immediate
sign-out. A Material 3 `AlertDialog` names saved sign-in credentials, local usage
history and the local display name as the local removal scope, and explicitly
excludes OpenAI/ChatGPT account deletion and subscription cancellation. Cancel,
Back/outside dismissal and Activity recreation discard the memory-only confirmation
without issuing an owner command. The action remains available for a current or
restored account and failed storage cleanup, and is disabled while signing out.

`AccountRemoval` is an opaque nonpersistable identity permission, not another
session or teardown owner. The dialog captures the current process owner's permission;
owner retirement and required history cleanup replace it before clearing facts.
The existing `signOut` command checks captured identity on its serial lane before
any mutation, then uses the unchanged combined credential/session/history teardown.
An old callback cannot reacquire successor permission, even when its UI delivery or
command is delayed. Dialog state is keyed by controller, permission and eligibility;
ordinary reads/rotation do not replace account-level authority. Unconditional internal
sign-out callers retain the same owner path and semantics.

The name already participates in fenced durable history deletion: both sidecar and
pending file are removed and the directory is fsynced before terminal success. No
UI storage deletion was added. Partial failure/timeout remains FAILED/STORAGE,
with inaccessible facts retired and successor admission blocked by actual cleanup.
A fresh confirmation can retry the existing removal machinery; replaying an old
confirmation cannot silently retry or erase a later login.

Three added `ProcessSessionOwnerTest` methods cover production-default-lane admission,
ordinary rotation, queued replacement, duplicate confirmation and credential failure
retry. `AccountRemovalLifetimeTest` covers combined synthetic cleanup, admitted name
writes, failed history removal, cancel invalidation and fresh-owner isolation. Five
mandatory `AccountRemovalLifecycleTest` cases exercise the production Activity/default
factory with isolated actual Keystore/SQLite, local-scope/accessibility text, cancel/Back/
recreation, restored/current removal, actual name-artifact deletion failure and fresh
confirmed retry, captured dialog permission replay at the owner boundary and late provider
replies. The same-phase replacement case holds the rendered phase constant and verifies
permission replacement alone dismisses the dialog. Existing mandatory
connection/banked tests now traverse confirmation; the held SQLite-query/provider-reply
isolation case does too. All inherited test identities/captures remain required. Actual
native execution, lint and compatible whole-handwritten >=90% INSTRUCTION coverage
remain hosted acceptance, not source or compile-only proof.

## Even-distribution reference and observed comparison (M4a-3)

`history/EvenDistribution` is a pure consumer of M4a-1 `HistoryWindow`, not another
normalizer/reducer or a sampling owner. `reference(source)` returns an independent
`EvenDistributionReference` descriptor tagged with the exact canonical window and
segment identities, or typed unavailability. `compare(source)` returns the original
immutable real point, its descriptor, baseline percent and signed delta in **percentage
points**, or the unchanged source plus a typed reason. Existing `BaselineEligibility`
is authoritative and survives verbatim as `DistributionUnavailable.Ineligible`.
The source retains reset provenance, duration/percent knowledge, nominal-start
confidence and clock/correction metadata; these are not replaced by arithmetic.
Inconsistent handcrafted identity/reset/duration/percent facts are separately rejected
as `InvalidFact`, not promoted to known quota.

For supported elapsed SI-second duration D (18,000 five-hour; 604,800 weekly),
non-discrepant absolute reset R and nominal full-quota assumption:

```text
S = R - D                         nominal analytical start, not an observed quota start
B(t) = 100 * elapsedSeconds(S,t) / D       percent, only for S <= t <= R
Delta = original usedPercent - B(observedAt)    signed percentage points
B(S) = 0; B(R) = 100               analytical endpoints, never invented measurements
```

`Duration` seconds plus its nanosecond fraction are converted exactly to `BigDecimal`.
The numerator is multiplied by 100 exactly, then divided **once** with `DECIMAL128`
(34 significant digits, HALF_EVEN); endpoint values are exact 0/100. Delta subtraction
is exact against that computed decimal. Neither provider percent nor storage is rounded.
Exact subtraction is bounded to 1,024 coefficient digits and absolute scale <=1,024
for nonzero measured values. Larger operands return explicit `DECIMAL_CAPACITY`, not
rounded usage, zero or an estimate. Extreme-scale zero is a safe zero arithmetic operand
only; its original measurement object/scale remains untouched. No plain-string expansion,
Double arithmetic or unbounded decimal-scale alignment is needed.

Descriptor evaluation requires an explicit `Instant` (null means unknown time) or
injected `Clock`; there is no system-clock default. These are analytical evaluation
requests, not new measurements or an independent clock-trust inference. M4a-1's
observation eligibility is required first: unavailable/ambiguous window, unknown percent
or observation time, unknown/malformed/unsupported/relative-only/discrepant reset or
duration, unverified/discontinuous observation clock, uncertain correction start and
time-range overflow remain unavailable. Queries before S or after R are separately
unavailable, never clamped. B1 remains the sole shared reset/expiry/time formatter;
locale, timezone and daylight-saving changes cannot change elapsed SI-second math.

Fixed synthetic examples (same values in the literal JVM oracles):

| Window / explicit time | Baseline percent | Exact measured percent | Delta, percentage points |
| --- | --- | --- | --- |
| Five-hour R = epoch second 1,800,003,600; S = 1,799,985,600; t = 1,800,000,000 | 80 | 12.375 | -67.625 |
| Same window, t = S + 9,000 seconds | 50 | 100 | +50 |
| Same window, t = S + 9,000 seconds | 50 | 12.37500000000000000001 | -37.62499999999999999999 |
| Same window, t = S + 45.125 seconds | 0.2506944444444444444444444444444444 | not supplied | not a measurement |
| Weekly R = 1,800,604,800; S = 1,800,000,000; t = S + 302,400.25 seconds | 50.00004133597883597883597883597884 | not supplied | not a measurement |

A late first measurement stays at its actual `observedAt`. Evaluating analytical
endpoints or later Clock ticks never adds/moves measured points or advances an earlier
delta: `compare` has **no now/tick argument**. Empty history stays empty. Graph consumers
must keep the descriptor separate from measured points and retain distinct
segment IDs, even for the same canonical window or a retained/paginated segment whose
origin is absent; the issue 184 renderer connects shown measurements across those runs. Five-hour/weekly and same-kind reset changes stay independent; a
correction's uncertain start is never replaced with its first visible point. Reset
cause stays UNKNOWN. Banked summary, inventory and expiry are not inputs to this API;
they cannot reset a reference or establish rollover/manual activation.

`EvenDistributionTest` adds only original synthetic JVM cases for literal values,
subseconds/nanoseconds, endpoints, out-of-range/unknown facts, corrected starts, clock
provenance, extreme scale/capacity/Instant bounds, SI-time/DST invariance, independent
window/segment identities and immutable late/empty/paginated history. There is no new
native case, dependency, persistence/session/refresh integration, rendering, ratio,
derivative, prediction, exhaustion projection or alarm. All new handwritten bytecode
remains in the unchanged >=90% compatible JVM/native INSTRUCTION denominator. Source
compilation is not JVM execution, pinned Android build or exact-run coverage acceptance;
those gates remain hosted.

## Admitted foreground recording and M4b contract (M4a-5)

`ConnectionController` admits sampling in the actual successful accepted USAGE delivery
callback, after B2's cycle cancellation/epoch check and the captured controller revision /
`SessionGeneration` check. `HistoryObservationAdmission` is an identity-bearing one-cycle
in-memory ticket with an exactly-once delivery gate, not a StateFlow emission identity,
percentage comparison or persisted account ID. The worker allocates the contiguous durable
`ObservationId` from the store's high-water. One real endpoint observation supplies both
independently classified window facts; missing/unknown windows stay status, not invented
points. Equal-percent independent reads still get distinct ordinals. Usage success is
recorded before inventory completes and remains valid when inventory fails. Inventory-only
success, retained cache, failure, local tick and analytical evaluation add no points.
Endpoint wall `observedAt` and its paired `receivedAtMillis` are captured before decoding;
storage completion and graph query time never replace them.

`UsageHistoryRecorder` is subordinate to the same owner lane/scope, with no independent
network, timer or lifecycle owner. One storage worker performs blocking read/append/read
on the separate storage dispatcher; at most 16 observations wait behind it. The waiting
bound includes observations delivered while a continuing lifetime's runtime capability is
being re-adopted. Such work captures the prior opaque lifetime partition and exact session /
lifecycle admission; it can attach only to the matching fresh capability. Failed adoption
discards it explicitly. Already-failed durable observations are never replayed or retried.
A worker rechecks revocation before read and append, and the native store remains the
irreversible-write authority. Publication rechecks the identity-bound runtime token and
query revision. Last-observer loss without background interest, cancel, logout/replacement and shutdown immediately
clear queued facts/views and retire publication authority; UI authority is also retired on STOP
when background storage is retained. No held read/write can expose
another login. An already irreversible-admitted commit may finish, but existing combined
removal deletes it before terminal success. Fresh runtime reads are local and dormant:
restoration or opening a history view never activates B2 provider requests.

Background and accepted usage errors mark the next actual observation with existing typed
BACKGROUND/READ_ERROR gap entries. Overflow and ordinary storage failure report fixed
`HistoryRecorderProblem` categories, plus a lost/unconfirmed-observation counter, and mark
a READ_ERROR discontinuity before the next successfully admitted measurement. This counter
is an acknowledgement/loss diagnostic, not a claim that uncertain I/O physically lost a
committed row. There is no invented retry measurement, interpolation or off-screen backfill.
Read errors do not masquerade as empty; successful durable rows remain available in a bounded
page alongside a sticky runtime error until fresh capability adoption. Queue work cannot
block the owner lane or require another provider request. Existing B2 manual/connect/resume,
>=60-second cadence, Retry-After, coalescing, independent endpoint clocks and dormant restore
semantics are unchanged. Storage ceilings/no-backup/corruption/deletion remain M4a-2/-4's
contract, not new recorder policy.

M4b consumes `historySnapshots: StateFlow<HistoryGraphSnapshot>` and submits domain-only
`queryHistory(HistoryGraphQuery)`; it must not redefine sampling, identity, segmentation or
math. The snapshot is detached and immutable, with:

- UNAVAILABLE / LOADING / EMPTY / READY / ERROR readiness; EMPTY means an actually empty
  storage page, while status-only or a window-filtered page may have zero measured points.
  Failure categories, storage reason and lost/unconfirmed count are separate from page data.
- Identity-compared nonpersistable `HistoryGeneration` plus the opaque durable partition.
  A retained snapshot is not authority for a retired login; new UI work uses the current
  generation. Logout clears the view, query and endpoint metadata before any successor.
- `HistoryGraphQuery(limit=1..256, after=exclusive ordinal, kind?, window?)`. Storage pagination
  stays ordinal-ordered and single-partition. Reusable/text queries select facts on
  that page; production `currentOnly` queries use the bounded matching scan described
  below, with an exact authoritative window key. A query is not a provider read or new observation. Latest query
  revision rejects held earlier-query publication. No invented tail/time-range API exists.
- The original `HistoryReadSnapshot`: entries, high-water, next-after/has-more, EMPTY /
  STATUS_ONLY / MEASUREMENTS content, retention truncation and ordinal eviction cutoffs.
  All page gap entries survive window selection. Actual points retain admission order even
  for backwards wall time; the chart orders paint by time without rewriting discontinuities.
- Each selected `HistoryGraphWindow` retains its immutable source facts, segment/window
  identity, break reasons, UNKNOWN reset cause, typed baseline eligibility and nominal-start
  confidence; `EvenDistribution.reference` / `compare` supply separate analytical reference
  and observed-only comparison, never synthesized measured endpoints.
- Explicit PERCENT units for measured/reference values, PERCENTAGE_POINTS for delta and
  ELAPSED_SI_SECONDS for duration. Nominal full-quota start is an assumption, not proof.
  Unknown/conflicting/corrected/out-of-interval facts keep typed absence. The established
  DECIMAL128 math above remains authoritative; no prediction, derivative or alarm is added.
- Safe independent usage/inventory source-observed clocks, latest-attempt observed clocks
  (null if the failed endpoint supplied none), HTTP status/error, stale flags and cycle
  refreshing metadata. Ticks may update this metadata but cannot move points or deltas.
  No provider text, raw payload or credential/account hint is exposed by the graph contract.

Synthetic handoff examples: a five-hour success at epoch second 1,800,000,000 with 12.375
percent and reset 1,800,003,600 stores ordinal 1 and delta -67.625 percentage points. A new
successful read at equal percent stores another actual point, not a replay. A subsequent
usage error plus inventory success retains those points and the old usage source clock;
its next usage success carries a READ_ERROR gap. A clean fresh runtime restores the same
partition without a GET, and its next real observation starts a NEW_CLOCK_EPOCH segment.
A later sign-in after complete logout starts at ordinal 1 in a different partition.

`UsageHistoryRecorderTest` executes the actual controller/B2/reader/parser/reducer/math
with original synthetic transport, virtual time and held storage dispatch. It counts
actual provider requests and append IDs, covering partial endpoints, equal percentages,
coalescing/ticks, cadence/Retry-After, observer/cancel/logout retirement, queue overflow,
categorical failures, corrections/reset/unknown/clock facts and bounded queries.
`HistoryProjectionTest` supplements immutable selection/baseline/truncation/units,
exactly-once ticket replay and capacity/failure contracts. `UsageHistoryIntegrationTest`
uses the production-default owner factory, real Keystore and framework SQLite in a test-owned
application/no-backup context. It covers observation -> persistence -> fresh-runtime dormant
restore -> isolated read, relogin/late callbacks, partial endpoints, held pre/post-admission
writes, categorical native read failure, and Compose-v2 lifecycle/recreation/finish retirement.
Fresh-runtime restoration is not literal process death. The mandatory native validator adds
these cases without removing the preceding 72. Native execution, exact-head independent
review and >=90% compatible JVM/native INSTRUCTION coverage remain hosted gates; source or
cached-toolchain compilation alone cannot establish those outcomes. #8 owns graph/UI/
accessibility; #9 owns assembled release/live acceptance, with #69 separate.

## Owner-scoped background refresh admission (issue 194)

`ConnectionController.backgroundRefreshTarget()` captures the existing opaque ACTIVE
history partition only after protected credential/history restoration. This is a local
credential-lifetime enrollment value, not the connection-slot UUID, token contents or
verified provider identity. `refreshInBackground(expected)` awaits restoration and checks
that exact continuing partition together with the current owner revision and runtime
`SessionGeneration`. A stale enrollment cannot activate a replacement login, missing
credentials or terminal reauthorization. Ordinary construction, launch and restoration
remain network-dormant. That seam itself adds no scheduling; issue 195's adapter below consumes it.

The suspend ingress uses the same `NativeConnection.get(applicationContext)` owner and
existing B2 flight, A10 session, transport and recorder/store. Foreground/background and
background/background overlap share the actual endpoint cycle and exactly-once recording
ticket. Background interest is not a foreground observer: it does not activate one-second
ticks, visible polling or explicit-user `retryTransient()`. A retained Retry-After boundary
returns a categorical DEFERRED rejection with its process-local monotonic boundary instead
of scheduling a hidden wakeup. An already retiring flight is also an honest rejection.

`BackgroundRefreshOutcome.Completed` reports the actual independent endpoint outcomes
and this admission's `HistoryRecordingOutcome`: acknowledged Stored ordinal, categorical
Failed, NotObserved for a usage error, or Revoked. Completed is not itself a success flag;
inventory success cannot turn failed usage into a stored measurement. The recorder carries
the completion through its bounded waiting/append queue and settles it from the actual
read/append outcome. A worker-idle join, command marker or `refreshing == false` is never a
durable success receipt. The existing UI refreshing indicator still follows endpoint work,
so it can finish before a held history append is acknowledged.

The caller owns only its network interest. Cancellation detaches that caller and cancels
the flight only without another UI/background consumer; it never calls global Cancel,
Sign out or shutdown. A subordinate process child retains storage through an already
accepted observation's real settlement, even if its caller is cancelled. The last storage
interest releases the port when no UI remains. A10's independent accepted rotation child
and quarantine/deletion rules are unchanged. Logout/replacement/terminal reauthorization
synchronously retire the interest generation and recorder binding; old releases cannot
decrement a successor's interest or acknowledge predecessor data as successor success.

UI display permission is separate from that retained storage activity. STOP immediately
clears the display slot, payload and endpoint metadata even while background work persists;
return installs a fresh UI context/selection. Background-only recording publishes no page
or display authority, and UI export/import-target admission still requires an actual
foreground observer. Storage schema, retention, durable ordinal admission and the normal
foreground accepted-observation path remain unchanged.

Seventeen mandatory `BackgroundRefreshTest` JVM methods use the actual owner/B2/recorder
topology and virtual time for overlap, cancellation, durable outcomes, dormant/mismatched
restoration, Retry-After, no hidden ticks/polls, replacement, terminal auth and independent
rotation settlement. Twelve added mandatory `UsageHistoryIntegrationTest` methods use
actual isolated Keystore/SQLite and the production-default owner lane, real pre/post-write
admission gates, Activity STOP, cold-holder restoration and late replies. The fail-closed
report/retry contracts retain all inherited identities and require these additions,
including duplicate rejection; captures, permissions, dependencies and the >=90% whole-
handwritten compatible INSTRUCTION coverage policy are unchanged. These are authored test
contracts, not a claim of local JVM/native execution. Exact-head hosted compilation,
lint, JVM/native/coverage and independent review remain acceptance gates. Fresh-holder
restoration is not process-death/reboot proof; OS scheduling, force-stop limits and physical
device battery/periodicity acceptance belong to #195 and parent #193.

## Ordinary periodic background work (issue 195)

`PeriodicUsageRefresh` is a minimal WorkManager 2.12.0 adapter, not another owner.
Successful explicit sign-in or manual read enrolls a unique KEEP periodic request
named for the existing opaque history lifetime. Its only input is that canonical
UUID, never the credential-slot selector, token, provider identity or display name.
WorkManager alone persists enrollment; there is no parallel preference/registry/store.
Repeated activation preserves the request ID and timing. Removal and terminal cleanup
cancel the refresh tag; a stale cold worker cancels only its own lifetime's unique
name. Cancel/Activity STOP/holder shutdown do not delete a clean continuing enrollment.

The ordinary interval and initial delay are 15 minutes, with connected-network and
battery-not-low constraints, but no charging/idle requirement. There is no expedited
work, foreground service, exact refresh alarm, exemption or persistent process timer.
`PeriodicUsageRefreshWorker` obtains the same singleton NativeConnection and awaits
issue 194's exact-target endpoint/durable-history receipt. Coroutine cancellation
escapes unchanged and releases only its own interest. It neither creates another
controller nor registers a fake UI observer or retries auth as a user.

Periodic-only admission skips a completed cycle when both latest endpoints succeeded
less than 15 monotonic minutes ago; manual behavior and issue 194's default admission
remain unchanged. In-flight requests still join in either order. This freshness cache
and Retry-After boundary are process-local, not persisted wall-clock promises across
reboot. Expected transient/offline/storage/rate-limit failures consume this opportunity
and wait for the next period, never WorkManager's default short `Result.retry()` loop.
The existing bounded A2 retries still apply inside one endpoint operation. Scheduler
`Result.success()` acknowledges only the opportunity, not endpoint or history success.

Seven mandatory JVM admission cases and nine native `PeriodicUsageRefreshTest` cases
extend, rather than replace, issue 194's isolation/receipt/rotation regressions. The
native contract distinguishes TestDriver constraint/time control and direct worker
mapping/cancellation from actual minimized real-WorkManager/JobService execution:
the latter disables only that ID's Greedy delayed tracker, ages its test enqueue
timestamp and forces its registered ordinary JobScheduler job, then checks the held
owner ingress, RUNNING state, actual SQLite settlement and fresh-holder local reread.
It does not call `doWork()` to claim OS dispatch, shorten the delivered interval or
prove natural 15-minute timing. All registry/count/retry fail-closed controls retain
the inherited cases. Local source/tooling checks are not hosted native/lint/coverage
acceptance. Parent 193 still requires physical-phone timing/battery observation.

The exact dependency, manifest/Startup/security surface, backup boundaries and honest
process-loss/reboot/force-stop limitations are in [background refresh](docs/background-refresh.md).

## Bounded truthful plot inputs (M4b-1)

`history/HistoryPlotInputs.project` is a pure page-local consumer of the detached
`HistoryGraphSnapshot`; it adds no query, sampling, clock, controller, storage or
Compose behavior. Immutable five-hour and weekly series each retain selected
window facts/statuses in admission order. The original snapshot remains attached,
including every page gap, runtime generation/partition, readiness, query, endpoint
metadata, storage content, pagination, retention cutoffs and lost/unconfirmed count.
A plot snapshot is not permission to use a retired generation.

Plot content distinguishes no page, actually empty page, filtered-empty,
status-only, single measurement and multiple measurements independently of
readiness/error/baseline availability. The adapter rejects oversized handcrafted
pages or duplicate selected kinds per admission instead of silently losing facts;
accepted input is bounded to the existing query limit of at most 256 per kind.
Page-local measured and delta runs preserve original segment/window identities,
breaks and UNKNOWN reset cause. They never bridge a different segment/window,
unkeyed point, non-increasing wall time, ordinal hole, intervening gap/status or
unavailable geometry. No run authorizes joining to a run from a different page,
retained snapshot or generation; an absent segment origin is not manufactured.

Analytical descriptors/endpoints are separate from measured runs and tagged
NOMINAL_EVEN_DISTRIBUTION, never measured samples or forecasts. Existing
`HistoryGraphWindow.reference` / `comparison` from `EvenDistribution` are reused,
including all typed absence. Delta geometry/text stays at the actual `observedAt`
and in PERCENTAGE_POINTS; no tick/now argument can advance a comparison.

Each kind's exact time domain includes its real points and available analytical
endpoints; no visible sample is relocated to a nominal start. Rendering-only
fractions use fixed 0..100 PERCENT and -100..100 PERCENTAGE_POINTS value domains.
A single-instant span maps to horizontal center without inventing elapsed time.
Duration seconds plus nanoseconds avoid scalar overflow across Instant.MIN..MAX.
Double fractions are explicitly approximate: coincident fractions do not merge
observations, nonzero decimal underflow is marked, and out-of-range or >1,024-digit
values have typed unavailable geometry, never silent clamping or zero facts.
Original BigDecimal/Instant objects and exact exponent-notation numeric/ISO instant
text remain available even when geometry cannot be drawn. Renderer pixel layout,
localized B1 time labels and accessibility semantics belong to the component and
navigation layers described below, not this pure adapter.

`HistoryPlotInputsTest` and `HistoryPlotCoordinatesTest` add original deterministic
synthetic JVM oracles for both kinds, metadata/content, identity/gaps/corrections,
late and paginated/retained points, exact decimals, rendering extremes, boundedness
and immutability. No native case, dependency, exclusion or coverage threshold changes;
JVM execution, all 77 mandatory native regressions and unchanged >=90% compatible
JVM/native INSTRUCTION coverage remain hosted acceptance gates.

## Reusable textual history equivalent

`HistoryTextComponent` consumes `HistoryPlotSnapshot` without controller ownership.
The production connection view supplies its current-generation bounded page. It
shows independent five-hour/weekly content and readiness,
exact measured percent, observed-only percentage-point delta, typed baseline
absence, recorder/storage/loss/retention/page metadata, and independent endpoint
clocks. Nominal full-quota reference copy explicitly says assumption, not measured
usage or exhaustion prediction. Reset labels reuse B1 with caller-supplied time,
zone and locale; observation/detail clocks use its localized second-precision projection,
while original nanoseconds and exact identity fields remain in the domain. This
component owns no timer, query, quota math or network call.

The host supplies scrolling. A collapsed entry selector retains the complete
admitted page, including filtered-entry slot status and timestamp-less gaps; previous
and next controls expose one entry at a time. Exact exponent notation is preserved.
Values longer than 120 characters are split into ordered, individually accessible
text parts without omission. Plain text has one semantics owner, no giant merged
content description and no live countdown announcements. Canvas rendering and
production navigation are separate layers described below.

`HistoryTextPresentationTest` covers exact/typed presentation; eight mandatory
`HistoryTextComponentTest` cases add native labels, semantics, controls and text
layout plus synthetic portrait/light, landscape/dark and large-font captures.
The native artifact upload includes AGP additional-test-output PNGs and observed
configuration metadata; its fail-closed collector requires all declared captures.
Capture collection is not visual approval: independent actual-image inspection and
exact-head hosted native/coverage evidence remain required. All preceding 77 native
cases, dependencies, permissions and the coverage denominator/threshold are retained.

## Reusable per-window chart component

`HistoryChartComponent` renders the M4b-1 five-hour/weekly percent geometry beside
that unchanged textual equivalent; the caller supplies a detached page, B1 locale/
zone/evaluation context and scrolling. Its optional kind selector keeps reusable
both-kind rendering available while the production connection view shows one kind.
`HistoryChartDrawing` maps all shown renderable points into one time-ordered solid
measured polyline, without point markers. A single observation remains a minimal line-width tick. Separate
nominal analytical endpoints retain long dashes; a least-squares usage trend uses
semi-transparent short dashes, never measured endpoints or a forecast.
Shape/line-style labels do not rely on color alone. No smoothing, clamping, fake
measured start/reset point, quota calculation or cross-page cache is added; sorting is paint-only.

The fixed percent axis and B1 containing-hour time-domain labels wrap outside the
Canvas; localized source timestamps, reset context, original measurements and
observed-only percentage-point delta remain in reachable existing details. Typed decimal-capacity/
out-of-domain geometry and nonzero approximate underflow have explicit resource copy.
Missing time geometry shows no invented axes or zero. The decorative Canvas is hidden
from accessibility; plain labels and existing bounded text retain their own semantics,
with no duplicated exact-value announcement or countdown live region.

Seven deterministic `HistoryChartDrawingTest` cases cover drawing roles and boundary
preservation. Eight mandatory `HistoryChartComponentTest` cases exercise production
Canvas pixels, dashed gaps, singleton-tick/straight-edge visibility, continuous run bridges,
labels/semantics/layout and reachable details across phone portrait/landscape,
light/dark and doubled font scale. Six synthetic chart PNGs extend the existing
collector without removing its four textual PNGs or 85 inherited native identities.
The collector validates decoded pixels/configuration and observed root-contained
Canvas metadata, not visual approval. Hosted execution, compatible whole-app >=90%
INSTRUCTION coverage and independent exact-candidate actual-image inspection remain
acceptance gates; compilation alone proves none of them. Owner/navigation integration
and the conditional assembled acceptance contract are described below.

## Production bounded history navigation (M4b-3a)

The Connection screen defaults to the compact dashboard; the modal drawer contains
**Full local history details**, **Full live connection details**, and **Account settings**
anchored at its bottom. Account settings alone hosts local naming, device sign-in
(including reauthorization, cancellation, code copy and browser launch), and confirmed
local removal. The dashboard and connection details instead point disconnected users
to Account settings with an open action; usage/session refresh controls stay in place.
Account settings has no history-page interest and navigation never starts provider I/O.
Back closes an open drawer first;
Back/up from details returns dashboard. Recreation resets these ephemeral UI
choices to dashboard/closed, without replacing the process owner. A route
may remain within the same Activity across account revocation;
it is only ephemeral presentation state, and actual authority fences all facts.
This does not promise an account-change navigation reset. Stored history
retains exact text, kind selection and real exclusive-cursor pagination; full
connection details retains the sanitized live/banked/status/clock facts and
advanced controls. No credentials, payloads or permissions enter saved state.

Production hoists one collected `historySnapshots` source and one local request
effect in `ConnectionRoute`. `observeHistory(request, expectedContext)` checks the
actual recorder context and exact current periods on the owner lane. Equal UI
observation retains the token/page and does not scan. Explicit legacy
`queryHistory(HistoryGraphQuery)` always requests a local Page reread, including
equal values: new revision/token, cleared payload and one coalesced pending bit.
With no binding it retains the request and publishes unavailable without a store
read or invented authority. No navigation/disclosure command activates B2; the
Activity's existing directly attached STARTED observer is unchanged.

The production entry defaults to current-only five-hour/weekly charts. The bound
recorder projects normalized live `UsageRefreshState` into safe period keys and
typed absence in its existing display context. The existing evaluation clock and the exact `WindowHistory.identity` duration/
absolute-reset rule qualify the active period. Missing/ambiguous/unkeyed live
facts, lost/backwards evaluation clocks, before-start or expired periods show
unavailable rather than choosing an archived reset. Stale successes and independent
endpoint errors retain their existing metadata. All-window reset tolerance and absolute
originals remain the normalizer's policy, never a second graph normalization.

Current queries carry `currentOnly` plus the exact partition/kind/duration/reset key.
`CurrentHistoryRead` scans existing ascending bounded store pages, using actual
exclusive cursors and an initial admitted high-water fence. It examines at most the
existing 100,000-observation retention ceiling, holds only one read batch plus a
selected page and one real matching lookahead, and retains exact entries, ordinal
holes, segments and retention/cutoffs. Current measurements remain reachable after
archived pages. `hasMore` needs a matching retained lookahead; failed/corrupt/resource
reads stay typed errors. A missing current identity performs no store read. No
SQLite schema, retention, recorder admission, provider cadence or storage port changes.

**Browse stored text history** exposes the original ordinal pages as text only;
archived periods have no chart route. `HistoryNavigation` retains one kind, current
key/mode and exclusive cursor, with a limit of 32. First/next controls use only real
selected-page cursors; mode/current-key changes reset the cursor. Current kind changes select a different
key; stored-text kind changes preserve the original ordinal cursor. There is no
page stack or accumulated history. Current chart projection reuses the existing
EvenDistribution, plot inputs, renderer and phone-local time labels, preserving
a continuous measured polyline/singleton tick and eligible dashed analytical references.

Selection and detail state are in-memory `remember`, not Bundle/saved snapshots.
The recorder's one volatile display-context slot replaces its private Binding
revocation flag: workers and the host use the same authority, not a mirrored flow.
Each snapshot carries only a read-only nonpersistable permission retaining an opaque
context (with nullable bound generation, safe current period keys and evaluation
time) and the slot, never provider text, controller/storage or credentials. Replacement revokes the predecessor before publication; invalidation
clears the slot before publishing unavailable. A foreground bind without storage
has a distinct diagnostic context, still with null generation. Its current ERROR
loss survives selection changes, but its retired context cannot replay even across
null → successor → null. Existing null-access binds preserve any current Binding;
background/resume loss retention and retirement loss reset are unchanged.

On each actual `ConnectionHistory` execution, one independent descriptor read
immediately before projection authorizes context/generation identity and requested
query equality. Current queries additionally recheck the latest live period through
that context, independently of delayed collection; rollover/expiry/clock loss cannot
authorize the previous key. Ordinary StateFlow collection still schedules UI updates; it is not
render permission. Missing/revoked permission scrubs all facts and loss, showing
loading only for a current bound successor, otherwise unavailable. This is a host
composition-time check, not a frame/child/hardware atomic-erasure promise. No
mutable authority is marked Stable/Immutable or cached by remember. A superseded
selection has no page while awaiting its own query. Retirement hides measurements
and endpoint clocks, and new adoption resets navigation. Metadata-only
ticks do not collapse exact details: details reset on generation, query or storage
page identity instead of every detached metadata snapshot. No measured point,
comparison, baseline or provider fact is recalculated by navigation.

Three additional mandatory `HistoryDisplayAuthorityTest` cases run that actual
host with recorder-produced facts, a held UI-only delivery collector and changed
evaluation input. They require the exact retained source on the challenged host
pass after an owner receipt, reject retired bound/null contexts, then prove only
successor data/loss and unchanged dormant request counts. The observer seam cannot
mutate owner/storage authority or hold the entire Compose scheduler. These extend
the 98 native identities without changing the twelve captures. Hosted execution
and fresh lint/coverage/image review remain acceptance, not source-level proof.

Five mandatory `HistoryNavigationTest` instrumentation cases exercise the actual
MainActivity entry, window/page controls, dormant restore and zero new request
counts, logout/relogin deletion, recreation/foreground retirement and independent
endpoint errors. They use a route-dispatched synthetic transport and the production
owner factory with actual Keystore/SQLite under a test-owned application/no-backup
root. Page arrangement adds explicitly synthetic local admissions; it is not
provider-sampling evidence. Compose-v2 waits advance composition rather than
blocking its scheduler. Two production-entry PNGs extend, not replace, the ten
component PNGs and all 93 preceding native identities. Collection binds source/run
identity, observed configuration, bounded page/request metadata and native Canvas
bounds; it does not establish visual approval. JVM navigation projections and
fail-closed report/capture parser controls supplement the native cases. Exact-head
hosted runtime/coverage and independent actual-image review remain required;
the following integration matrix completes #90, and #83 retains assembled
accessibility acceptance. There is no signed release or live-device claim.

## Integrated query and lifecycle isolation (M4b-3b)

`HistoryIsolationTest` extends the production-entry contract with five mandatory
hosted cases, plus one new actual-host held-collection case in
`HistoryDisplayAuthorityTest`, without changing application code or adding another owner. Window
and ordinal-page tests exercise both an older SQLite result held before publication
and an older page already published when the successor query is held. Actual query
command receipts, captured partition/cursor/limit and observed successor publication
bound each rejection; navigation never admits a provider request or observation.
Exact retained SQLite-result identity makes rejection discriminating even when
window queries share a physical storage cursor. Separately, the held UI collector
retains the exact previously published source across window/page changes; a reached
host re-execution must scrub it after authoritative successor query publication
while the same display context remains current. This is not a retirement-only oracle.

A fixture delegates to actual framework SQLite and holds only its detached read
result after the read completes. It retains the production constructor's single
process-owner lane, dispatchers, recorder and B2 scheduler, injecting synthetic
transport/clock and test-owned Keystore/no-backup storage. It does not add a controller
or storage API. Late usage and inventory replies deliberately ignore cancellation:
logout removes both stores before relogin, and only the new partition's ordinal-one
measurement, independent metadata and reported inventory survive. STOP/RESUME and
Activity recreation positively establish a usable fresh capability while the old
read is held, then require rejection of the predecessor and successor-only pages.
Fresh-holder restoration here remains restoration from app storage, not process-death
or live-provider evidence.

The inherited default-factory dormant restore and local-navigation cases count every transport
call; the new local query/lifecycle cases additionally compare all five route counters
(user-code, auth poll, token exchange, usage and inventory) and unchanged high-water.
A synthetic tighter count ceiling plus an actual SQLite read fault exercises retained
rows, loss acknowledgement, retention cutoff, usage error/staleness and independently
fresh inventory through the real history entry. These diagnostics do not become empty
or current measurements. Current/retired null-context loss remains covered by the
unchanged held-collection host-reexecution cases from M4b-3a.

The fail-closed JUnit registry and coverage-transport retry fixtures retain all 101
inherited identities and require six additional identities. The twelve existing
captures (ten reusable component, two injected production-entry) remain unchanged;
no new visual acceptance class or mandatory screenshot is introduced. Compilation
and static parser controls are not native runtime proof. Independent exact-head
review, hosted runtime/lint/compatible whole-handwritten >=90% INSTRUCTION coverage
and actual-image inspection remain required; #83 and diagnostics removal #69 stay
separate.

## Current-period integration (issue 134)

`CurrentHistoryPeriodTest` and `CurrentHistoryReadTest` cover normalized slot-independent
identity, exact all-window reset policy, expiry/unknown/ambiguous/discrepant/backwards clocks,
fresh authority despite held delivery, selection beyond archived pages, real lookahead,
ordinal-hole isolation, empty/retention/errors and exact values. `CurrentWindowHistoryTest`
and `CurrentWindowRetirementTest` add actual MainActivity/production-owner current charts,
300 archived admissions before current measurements, measured/reference light/dark paint,
reset/expiry/unavailable/dormant behavior, unchanged request/high-water counts and a held
SQLite completions across account replacement and same-account reset rollover,
and current empty/stale/error/loss/retention states without synthetic measurements. These are source contracts; hosted execution,
lint, >=90% compatible handwritten instruction coverage and image inspection remain gates.

The inherited navigation/isolation/delayed-collection suites now explicitly enter stored
text history for arbitrary ordinal browsing. Their cursor, exact textual facts, endpoint
metadata, lifecycle/security/isolation and no-request contracts remain required. The
integrated chart and assembled single capture use a live active identity. Expired sparse
and unknown-reset assembled captures now show stored text, with no archived Canvas; their
arbitrary-period paint expectations are replaced by the new current light/dark campaign.
Reusable component fixtures still cover arbitrary input geometry. All 19 existing gallery
names/configurations remain required, plus two current production captures (21 total).
The collector adds exact current key/selected-ordinal receipts and registered default
Material3 measured/reference targets, retaining physical bounds, +/-12 color tolerance,
PNG decode/sample equality, source/run/attempt/configuration and fail-closed uniqueness.

## Assembled history acceptance and original-parent map (M4b-4)

This source map retains the assembled foundation with issue-134 current-period
dispositions described above. The original #83 work was an evidence-only roll-up,
not a second implementation of the pure projection,
chart, text or owner/navigation contracts. `HistoryAssembledAcceptanceTest` enters the
unmodified production `MainActivity`, uses the same default process owner with actual
isolated no-backup SQLite/Keystore and synthetic transport, and arranges additional
explicitly synthetic admitted rows when necessary. It never replaces the composition
root, creates another lifecycle owner or changes sampling/math/storage/network policy.

The original three #8 criteria map as follows; this describes required oracles, not
an assertion that a newly authored test or screenshot has already passed:

| Original criterion | Inherited source and evidence contract | Missing assembled evidence supplied by #83 |
| --- | --- | --- |
| Actual measurements versus even-distribution baseline, with units/reset context | `EvenDistribution` and `HistoryPlotInputs` preserve exact observed-only comparisons and separate analytical endpoints; `HistoryChartDrawing` and chart/text components label percent versus percentage points and reuse B1 reset formatting. `EvenDistributionTest`, `HistoryPlotInputsTest`, `HistoryChartDrawingTest`, `HistoryTextPresentationTest` and component native cases retain their deterministic, layout, semantics and actual-pixel oracles. | `freshInstallAndSingleObservationExposeNoInventedTrendAndObservedOnlySignedDeltas` requires a real-entry single marker, painted dashed reference plus unpainted dash gaps, exact negative/zero/positive observed-only deltas, UTC observation/reset facts and both window selections. |
| Fresh installs and sparse/unknown history remain truthful; no fake trend | Pure plot tests and native component cases distinguish no/empty/status-only/single/multiple pages, unknown baseline/geometry, and breaks. Navigation/isolation tests retain bounded pagination, storage loss/retention and independent endpoint errors. | Fresh entry has unavailable facts, no Canvas, no detail/zero and no requests. `sparseGapCorrectionAndChangedResetRemainSeparatedInActualLandscapeDarkPixelsAndDetails` preserves reachable expired gap/correction/reset text with no archived Canvas. The current light/dark campaign above verifies five observations, four solid edges and two painted run bridges for the authoritative period. `unknownResetAndUnknownPercentAtActualLargeFontKeepCompleteAccessibleFactsAndReachableControls` preserves the unkeyed known point and unknown-percent facts as stored text, with no current or archived Canvas/zero. `truncatedPageLandscapeLightKeepsHonestLocalCursorAndEveryBoundedDetailReachable` requires explicit more-page copy, exclusive cursor 32, only ordinals 33..35, all three reachable entries, disabled terminal controls and unchanged admission high-water. |
| Hosted phone layout/theme/readability/accessibility | Ten inherited synthetic reusable-component captures establish their component scopes, not integrated reachability; two inherited production-entry captures establish basic navigation. All owner/query/lifecycle/privacy adversaries remain required, not duplicated here. | Four new mandatory native methods add five representative real-entry viewports: fresh portrait/light, single portrait/dark, gap/correction/reset landscape/dark, unknown portrait/light at effective font scale 2, and bounded-page landscape/light. Native text-layout complete-line/ellipsis/extent checks, full versus independently clipped bounds, root containment, hidden decorative Canvas, exact-fact ownership and click/reachable-control assertions accompany them. Actual effective resource configuration is checked against native text-layout density/font scale, not inferred from filenames/requested settings. |

The phone fixture changes hosted night/font settings and restores them on failure;
orientation changes use the real Activity. The unchanged production theme reads the
effective system configuration. Each viewport is intentionally scrollable: the native
driver checks complete labels/details after reaching them rather than claiming an entire
history page fits in one image. The campaign is not a full configuration Cartesian product,
a blanket contrast certification or a physical-device accessibility audit.

The four assembled identities remain mandatory alongside all inherited and new
current-period identities. The collector retains the twelve earlier history captures
and five assembled viewports, adds two current viewports and keeps both live-usage
images, observed API/configuration, exact owner/page/request identities, per-file
hashes and checkout/source-head/run/attempt provenance. The assembled single and
two current Canvas captures retain independent unclipped/clipped bounds; registered Bitmap pixel samples
are compared again with bounded decoded RGB/RGBA PNG pixels on the host, so metadata or
filenames alone cannot establish painted observations/edges, run bridges or dash gaps.
Explicitly synthetic parser controls reject omitted/failed/errored/skipped methods,
missing images, configuration drift, clipping, false paint receipts and changed PNG pixels.

Final original-parent acceptance remains conditional on independent full-diff review,
exact-head hosted compilation/lint/JVM/native execution, compatible whole-handwritten
>=90% INSTRUCTION union, independent inspection of every actual required PNG, and the
applicable exact-merge main reports/artifacts. Compilation and source/parser checks are
not runtime or visual approval. Activity recreation and fresh-owner storage restoration
remain precisely those scopes, not process-death, reboot, live-provider, signed release
or instantaneous physical-pixel erasure proof. Diagnostics removal #69 and M5 remain
separate; this roll-up does not waive either.

## Live usage visual acceptance

`LiveUsageScreenTest.productionLiveLightPaintAndExactUsageResetCapture` and
`productionLiveDarkPaintAndLargeFontRemainReadable` enter the actual `MainActivity`
with its default process owner, isolated real Keystore and synthetic transport.
They require inherited root text paint, selected root/card/content/bar/track pixels,
exact 12.5% and 76.25% semantics and independently projected absolute/relative reset
labels; the weekly pair differs by 30 seconds and retains the original absolute
target under the app precision policy. The existing weekly-only/missing-reset
case also covers fractional receipt time and a difference greater than 60 seconds.
The dark case also checks effective font scale 2, complete native line
extents and reachable refresh/history controls. Existing landscape, malformed,
unavailable, stale/error, exhausted, banked and lifecycle cases remain
mandatory; this adds no duplicate lifecycle campaign.

The existing collector keeps all 17 history images and additionally requires
`live-usage-portrait-light` and `live-usage-portrait-dark`. Live metadata identifies
the actual screen, production Activity, observed US resource locale, density/font
scale and visible facts. Registered samples refer to the exact saved root bitmap
and native owner bounds; the host compares decoded PNG bytes with each receipt.
API 36 and gallery-wide density constraints remain in force, with separate live
screen/locale identities. Native execution, lint, compatible whole-handwritten
>=90% INSTRUCTION union, independent exact-source review and individual actual-PNG
inspection remain hosted/delivery gates; source/compile/parser checks do not prove
runtime paint or visual acceptance. The actual reported phone reset cause remains unknown.

All reachable absolute clocks use the shared pure `TimePresentation` with the current
phone zone and effective resource locale. Reset/expiry/axis labels retain containing-hour
precision and original-instant countdowns; independent source/detail clocks explicitly
state second precision without changing source nanoseconds. Conflicting absolute and
derived source facts remain separately labeled, while the effective reset stays
unavailable. Unrepresentable local dates show unknown, never raw ISO or epoch fallback.
`phoneTimeConfiguration` observes system zone broadcasts and resamples on resume; the
resource configuration invalidates locale rendering. Both observers are disposed with
the visible UI and issue no owner command, request or timer. Production Activity native
cases exercise live/banked/history zone, locale and recreation with fixed source facts
and actual transport counts; all existing image identities and guards remain required.

## Default-launch regression contract

`ConnectionLaunchSmokeTest` enters the production launcher with an isolated real
Keystore/SQLite and synthetic transport. Its disconnected, recreation/legacy-Bundle
and landscape cases retain the approved installed platform plus AndroidX signature-only permissions,
cleartext-off and capture-enabled window assertions and real connection/history reachability.
Ordinary full-display `UiAutomation.takeScreenshot()` captures are checked against
registered native text pixels (including foreground glyphs, not merely a nonblank
background) at launch and after navigation/recreation. Pending-login, connected and
confirmation-dialog cases use the same assertion. These platform PNGs are separate
from the unchanged Compose paint/layout gallery; neither gallery is Recents evidence.
Hosted screenshot execution and actual Recents/physical-phone checks remain separate
verification boundaries.
Its activated-owner relaunch case acknowledges foreground loss and command completion,
holds the automatically resumed usage operation, rejects duplicate admissions and
requires exactly one usage/inventory pair plus one real history observation after the
background gap. Fresh-owner dormant restoration remains covered by live/history/account
and scheduler tests. `UsageRefreshLifecycleTest` retains operation-provenance-based
Home/recreation cancellation and coalescing; raw Activity START counts are not request
counts. Stale-generation transport/write/privacy oracles pause through actual STOP
and resume through STARTED instead of selecting a removed screen.

All 19 meaningful capture names/configurations remain required. Production captures
now require `visibleRoots = ["connection-screen"]`; only the retired tab is removed
from `visibleControls`. Chart viewports can have an explicitly empty control list,
while navigation/page viewports still require their exact real controls. Missing root
or control receipts fail closed. Independent geometry, expected colors, +/-12 channel
tolerance and actual saved-PNG sample equality remain unchanged. Removal changes the
integrated layout, so individual actual-image inspection remains a hosted acceptance
gate, alongside runtime/lint/coverage and independent full-candidate review.

## Dashboard presentation and joint observation (issue 142)

The dashboard renders both exact current five-hour and weekly sections without a
kind selector. Known percentages and B1 minute-precision `Next reset in` labels precede
the compact charts; there is no separate absolute-reset line in these cards. Missing
current identity or actual renderable retained history has one concise no-information
line per window, no empty Canvas or analytical-only substitute. A single observation
remains one minimal tick without an invented observed trend. `CompactHistoryChart` reuses the
existing plot/drawing/axis/paint primitives and original observed comparison for its
compact accessible equivalent. The dashboard alone hides the three-line legend from
visible text and exposes the same explanation as the percent plot's content description.
Its axis labels have no visible start/end prefixes and fit a single line at normal font;
large-font labels may wrap without clipping. The top observation line is `Updated` plus
the same yearless locale skeleton as chart clocks, retaining the observation minute
and flooring seconds/nanos (08:37:59 displays 08:37, never 08:38). The shared
`chartTimeLabel` keeps the phone zone and existing repeated-hour offset policy;
only axes retain containing-hour display. Original instants and second-precision
detail clocks remain unchanged. Full chart
details keep their visible legend and prefixed axes. Each window keeps one UI-local plot cache, keyed by
retained storage-page identity, generation, partition, query, request and selection;
no retained-entry list comparison occurs on metadata ticks. Snapshot analytics are
lazy, so fresh metadata wrappers do not rebuild comparisons. Stable immutable series
key remembered drawing/OLS and geometry warnings. The host still checks actual
revocation/period authority on every pass, and current metadata/latest-entry pace
binding is never cached. Detailed admission/clock/cursor/counter text stays in stored details. No alternative math,
normalization, inferred zero, old-period fallback or accumulated archive is added.

`HistoryPresentationRequest` has fixed Idle/Page/Current cases; Current contains
only two nullable exact window keys and a bounded limit (production100000). The joint
`CurrentHistoryRead` scan starts at null; the compatible single-current delegate
starts at query.after and retains the original query in its returned storage page.
Both share ascending batches<=256, an initial high-water and a total100000 examined
ceiling. Independent selected buffers retain every matching entry through that
high-water, bounded by the same100000 admitted observations across both windows.
Current requests also retain one latest matching entry per window for pace binding;
paginated detail requests still stop at their matching lookahead. A later failed
batch fails the joint result rather than publishing a healthy partial success. The
native storage port/schema/retention,
16 waiting observations, one worker and provider cadence are unchanged.

The existing volatile display context now holds one identity-bearing request
selection. Binding/context, revision/token and fresh exact period checks reject
held reads and held collected snapshots, including a route round-trip to an equal
query. Collected state triggers the host pass; the actual revocation-slot accessor
is its permission. No StateFlow.value read in composition grants authority. This
is host-pass publication safety, not atomic removal of already-drawn pixels. Real
accepted Work retains preappend/high-water/gap/append ordering across route changes;
admission failure accounting is separate from superseded presentation outcomes.
Idle drops page interest while keeping actual recording and shared diagnostics.

The banked-reset card is always visible below both graph sections, with the same
Material Card, 20dp padding, 12dp spacing and titleLarge heading as the usage windows.
Its title uses only known inventory reportedAvailableCount; unknown is not zero and
summary or row counts never substitute. The wrapping, weighted two-column table
shows yearless chart-format expiry and B1 minute-precision remaining time, with
subtle row dividers and end-aligned remaining values. Every retained item, including
malformed/expired rows, keeps C1 state; one row owns its expiry/remaining/state
accessibility announcement, while decorative headers and duplicate text are hidden.
Empty inventory has a short status line instead of a table. Independent stale,
partial and inventory-state notes stay inside the card; summary/count/container/
issues/clocks stay in full connection details. Local ticks and phone zone/locale
effects issue no provider request or admission. Absolute labels remove
the precision suffix only; original instants, containing-hour formatting, elapsed
countdown math and recognized weekly inclusive60-second conflict policy remain.

New mandatory dashboard JVM/native identities extend all existing campaigns;
seven actual dashboard/disclosure/drawer/detail capture identities extend the
21 prior gallery identities. Existing exact facts, pixels, clipping, security,
privacy, lifetime, admission and pagination assertions remain required through
the new routes. Native collection/appearance, hosted lint/JVM/instrumented execution,
compatible whole-handwritten>=90% INSTRUCTION union and APK acceptance are pending
until their exact candidate evidence is independently reviewed. No signed-release,
live-provider or merge approval follows from source/static tooling.

## Continuous full-window charts and linear trend (issues 174, 184)

Compact and shared detail chart drawing now connects every shown renderable measurement
in timestamp order with one solid 2dp polyline, including gaps/corrections and different
measurement runs. Original admission order, segment IDs, break reasons, measurements,
comparison eligibility and exact textual facts remain unchanged in the pure projection;
those runs now describe provenance rather than paint breaks. No cross-page accumulation,
fake measured endpoints, interpolation, smoothing or point markers are introduced. A
single measured point remains a minimal 2dp-long, 2dp-wide round-capped tick with no trend.

For one known usable window, the shared x domain is reset minus its SI-second duration
through the exact reset, independent of the measured range, point count or availability
of a nominal reference/pace comparison. Axis labels reuse B1's containing-hour format,
phone zone/locale and repeated-hour offsets. Points remain at their proportional actual
times. An authoritative current key always fixes the axis; any out-of-window observations
keep typed unavailable geometry and exact text, never moved times or an expanded axis.
Unknown/mixed bounds, unrepresentable starts and unscoped legacy observations outside a
candidate's interval retain the existing points/reference-range fallback; singleton
fallbacks use horizontal center. Empty history never becomes an analytical-only chart.
This also gives keyed singletons without baseline eligibility a full-window axis.

`HistoryTrend` fits ordinary least squares over all shown renderable observations,
using actual time rather than ordinal spacing. Affine-normalized coordinates and centered
covariance/variance preserve the fit without large timestamp squares. Fewer than two
points or zero x spread have no trend. The fit extends across the entire plot domain
(0..1, window start..reset when known) with 45% opacity and 4dp/4dp dashes. The existing
Canvas plot rectangle clips the trend to 0..100 percent; endpoints are not clamped and
the fit is not recomputed from clipped points. Nominal long-dashed references remain
separate analytical descriptors, not forecasts or measured points.

Current requests continue selecting all matching retained entries, with the same joint
ascending scan, native batches<=256, initial high-water and 100000 examined-entry ceiling.
No downsampling, schema/retention change or provider cadence change is introduced.
Latest-admitted-entry provenance remains separate for #177 pace; detail Page requests
and their 32-entry ordinal pagination are unchanged. #179's per-window cache still keys
on retained page identity, generation, partition, query, request and selection: window
duration/reset are immutable members of those keys, not new clock-tick inputs. Authority
and latest-entry pace are checked outside the cache on every host pass.

JVM regressions cover continuous edges across runs, time ordering, interior/full-window
mapping, unknown-bound fallback, a keyed singleton with unavailable baseline, full-domain
OLS endpoints and unchanged snapshot/cache/pace authority. Native source contracts add an
actual dashboard singleton with full-window labels and tick pixels, positive run-bridge
pixels and full-window translucent trend extension/clipping. Capture identities remain;
connection counts and positive bridge receipts deliberately replace negative gap receipts.
Mandatory report/retry registries and literal budgets are updated. Native execution,
Kotlin compilation, lint, compatible >=90% coverage and actual-image review remain hosted
acceptance gates, not claims established by quick static tooling.

## Simplified usage window cards (issue 173)

The dashboard's last-reported-usage status owns one localized second-precision
measurement-observed-at label, projected from the retained accepted usage source.
It is not repeated inside either window card, cannot switch to a failed attempt's
clock, and is hidden with retired/disconnected facts. The compact cards omit the
recorded-observation count/no-trend copy, repeated exact measured percentage,
raw observed-only delta and incomplete-history sentence. Storage/problem and
unknown-pace states remain explicit; full local-history text retains exact source
facts, deltas and loss/retention diagnostics, and full connection details retains
its independently labeled absolute/relative reset facts.

Each compact pace line uses the latest independently selected admitted window,
not an inferred comparison from chart geometry. Its observation instant and
original percent/duration/reset facts must match the same accepted usage source
that supplies the headline and top timestamp. Pending/missing/mismatched comparisons
show the existing unavailable state; a latest status-only or ineligible observation
never falls back to an older point. Existing eligibility and `EvenDistribution`
math supply the original observed comparison: `Over pace by N%`, `N% in reserve`,
or `On pace`. Display rounding is BigDecimal HALF_UP to
one decimal (ties away from zero); exactly +/-0.05 becomes +/-0.1, while magnitudes
below 0.05 display `On pace`, without negative zero. Decimal separators follow the
existing usage-percent locale policy. Neither measurements, DECIMAL128 comparison
math, storage nor chart drawing/points/lines/reference are changed.

Both chart hosts share a wrapping two-column row below the unchanged Canvas:
start at physical left, end at physical right. Android's locale-aware `MMMdHHmm`
skeleton supplies the yearless date/time pattern; B1 retains containing-hour display,
phone-zone conversion and repeated-hour numeric offsets. Original instants remain
exact. Unknown/unrepresentable dates remain unknown. The existing minute-flooring
and plural formatter supplies `Next reset in <remaining>` without a second suffix.

Mandatory JVM additions cover pace direction/rounding/locale and yearless
label/timezone/offset/unknown boundaries, plus latest-observation provenance beyond
32 entries, independently aging windows across storage batches, and unavailable/
status-only/correction cases. Issue174 below supersedes the compact first-page
chart limit while preserving that pace provenance.
One added native dashboard method covers
removed lines, the single top clock, left/right yearless labels and reserve/on/over/
unknown pace through actual accepted reads. Existing large-font/landscape label
checks and capture label receipts now target the readable pace line; no image or
pixel oracle is removed. Exact report registries and synthetic totals are updated.
These are authored contracts, not local Android runtime, lint or visual approval.

## Verification boundaries

- `app/src/test`: JVM state transitions, restore behavior and percentage validation.
- `app/src/androidTest`: real production Activity/launcher, exact installed permission
  allowlist, effective cleartext-off policy and capture-enabled windows, default connection flow,
  legacy-selection rejection, recreation, foreground coalescing and landscape UI checks.
- `tools/build`: scaffold/workflow/toolchain/strict dependency contracts, manifests,
  JUnit and compatible class-ID/freshness/denominator coverage validation.
- `tools/research`: offline fixture/schema contracts; not a production provider parser.
- `tools/metrics`: informational production Kotlin complexity/duplication reporting.
- `tools/policy`: script/supply-chain policy and public-readiness contracts.
- `UsageResponseParserTest` / `BankedResetResponseParserTest`: unchanged attributed
  M0 wire mocks/vectors plus original strict-type, precision, sibling, clock and
  discrepancy mutations. `NativeFeasibilityReaderTest` verifies both mixed-success
  orders through the same production decoders without inventing an atomic snapshot.
- `ConnectionControllerTest`: no-network orchestration, cancellation/stale results,
  storage/browser/read failures, strict allowlisted projection and bounded backoff.
- `SessionCoordinatorTest` / `AuthenticatedProviderReaderTest`: controlled concurrent
  refresh, durable-before-publication, write-failure quarantine, cancellation/replacement,
  terminal/transient classification, accepted rotation across deadline expiry and one
  shared 401 allowance; existing A2 policy-vector tests remain the read-policy oracle.
- `ProcessSessionOwnerTest`: two observer/commander clients of the production-default
  owner lane; held reads/refresh/removal, late results, bounded cancellation, timeout
  notifications without barrier bypass, repeated logout and observer detachment.
- `DurableQuarantineTest`: held protected key/file removal across refresh/write/marker
  failure, terminal GET, corrupt restore, replacement and logout; durable terminal
  states and both real removal attempts are preserved.
- `CredentialPersistenceTest`: uncertainty marker, fresh-owner rejection, deletion
  failure quarantine and generation-safe marker operations with synthetic persistence.
- `ConnectionLifecycleTest`: real Activity intent seam, process-owner retention across
  recreation/background/finish/relaunch, explicit holder-reset cancellation,
  saved Bundle/redacted diagnostics, synthetic exchange/rotation through real A6,
  fresh-runtime restoration, key loss/corruption/interrupted rotation, re-auth UI,
  failed-rotation-write quarantine, shared-holder callers and local deletion/replacement
  with late-read/refresh rejection. Mandatory historical test names remain unchanged;
  their assertions exercise the process-owner contract, not multiple competing controllers.
  These hosted fakes are not live system-browser sign-in or process-death proof.

Coverage minimum: **90%** JaCoCo **INSTRUCTION** over the JVM + instrumented union,
not a sum of percentages or test counts. Handwritten Activity/Compose/lambda code
remains in scope. Commands, cache policy and all-outcome failure artifacts are
specified in [CONTRIBUTING](CONTRIBUTING.md); metrics in [code quality](docs/code-quality.md).
The strict build/lint/JVM and instrumented/coverage jobs run in parallel, without
exchanging build-job artifacts; `Android CI result` still requires both successes.
The native graph retains JVM compilation/tests to bind JVM `.exec` and native
`.ec` to the same actual class bytes. Gradle build-cache reuse is enabled locally
and explicitly in CI; the pinned basic setup-gradle provider archives the user-home
`caches/` tree, including `build-cache-1`. Forks cannot write, and PR-scoped cache
writes cannot poison `main`'s cache scope. No remote cache is configured. Basic
archives are immutable seeds, not accumulated outputs after an exact hit. The
basic provider [hashes Gradle build files](https://github.com/gradle/actions/blob/3f5f9adaf7d9fecd50b5935e54106014257a94e6/sources/src/cache-service-basic.ts#L160-L168)
and intentionally has [no restore keys](https://github.com/gradle/actions/blob/3f5f9adaf7d9fecd50b5935e54106014257a94e6/sources/src/cache-service-basic.ts#L25-L39),
so the first run after dependency/build-script changes starts with a cold home.

The app-applied `always-execute.gradle` disables up-to-date and cache reuse for
JVM/native suites, coverage preparation/collection/report/verification, all lint
analysis/report tasks and toolchain observation, while compile/dex/resource/package
outputs remain reusable. Local terminal-state checks and hosted full-log evidence
checks reject reused/skipped/no-source gates; the native check precedes each retry.
Only AGP-disabled redundant vital lint report/text tasks may be skipped with their
same-variant enforced full lint partner enabled in the graph, never reused.
Configuration cache remains off because these listeners and the init observation
are not configuration-cache compatible. Cached compile/instrumentation outputs
replay identical bytes, so caching itself cannot change JaCoCo class IDs; actual
class-ID/probe/freshness/snapshot checks still fail closed on any drift. Preparation
deletes all coverage/test data and snapshots each attempt, not compiled outputs;
retry may reuse identical compilation but never failed-attempt execution data.

Its hosted API 36 emulator uses 4096 MiB of RAM. After
passing JVM/native suites, missing, empty or truncated native coverage permits
one full-graph retry only with ADB-offline collection evidence and matching
checkout/run/attempt identity. Missing JVM data cannot retry. Attempt 1 is archived;
no data is combined across attempts, and both attempts share a 25-minute graph budget.
The native script has a 42-minute wrapper and a 45-minute step limit for image setup,
boot and graph execution; the 60-minute job also covers SDK setup and evidence upload. History screenshot verification runs after the retry loop on the final
attempt and remains fail-closed. Full `--info` evidence (including every retry attempt)
remains unfiltered; only known informational console noise is suppressed. The
SHA-pinned checkout/upload-artifact actions use Node 24 without changing credential
or artifact policy.

## Research versus future implementation

[Historical M0 research](docs/research/m0.md), schemas and attributed fixtures
are offline research inputs, not shipped provider support. The app does not load
those research JSON files. Synthetic live-test transports and normalized research
fixtures remain test/research inputs, never a shipped sample screen. The native connection
gate has JVM and hosted fake Activity/browser/Keystore scenarios. Public sources and successful token receipt still do not establish Android
account/workspace association or service distribution permission.

The native gate implements selected connect, explicit two-read/session-refresh,
rotation/re-auth and local-delete boundaries with production usage/inventory decoding.
Authoritative association is unavailable and is not inferred. B3 renders periodic
usage windows through B2/B1; C2 renders banked-reset counts/status/expiry through C1,
with unknown facts and purchased-balance separation explicit.
B2 consumes the session/repository API rather than reimplementing token rotation or login.
Admitted foreground history sampling now feeds the adapter/lifetime binding and numerical
reference above. The local bounded graph/navigation implementation consumes that contract;
assembled acceptance retains the conditional gates above. Further providers, signing and
distribution remain separate future scope; sparse sampling is not verified-account or
continuous background history.
See [SECURITY](SECURITY.md) and [third-party notices](THIRD_PARTY_NOTICES.md).


## Hosted unsigned release boundary

VERSION and complete local Git history supply Gradle's explicit dev/RC/stable inputs.
Normal tagged checkouts remain dev; official fixture builds need exact clean tagged
source and increasing positive caller-supplied fixture counters. The separate
[guarded manual release workflow](docs/releasing.md) uses its own authenticated
GitHub run_number, static channel/certificate configuration and derived source/controller
admission with verified accepted history; unset configuration denies release. The strict build job adds real release assembly/full lint and independent
unsigned APK inspection/inventory/reverification. Temporary hosted clones exercise
actual tagged dev/RC/stable builds without public tags. Each job owns its checkout and
ordinary selected SDK setup; inventories are retained evidence, never build admission.
The advanced CodeQL workflow owns a full-history credential-free checkout and manually
compiles both app variants and debug test sources without task-output cache reuse.
Its analysis output, all existing debug/native/capture cases and compatible >=90%
whole-handwritten INSTRUCTION union still need exact-candidate hosted acceptance.
[CONTRIBUTING](CONTRIBUTING.md) defines inputs, artifact policy and the parent-owned
approved CodeQL migration. Production custody/activation/publication and owner GO
remain owner operations; controlled fixture signatures are not production evidence.

The release-only runtime observer additionally resolves the selected release graph
and original AAR/JAR hashes, then reconciles actual APK class definitions and payload
members. Merged manifest/resources are explicitly shared build outputs, not claimed
byte-identical source files. A bounded source-backed catalog includes known nested
Emoji2, graphics native/header, Kotlin source portions, Unicode IDNA and MPL PSL data;
unknown standalone versions and candidate historical notice scope stay explicit.
Deterministic CycloneDX 1.6, notice/source material, inventory and bundle manifests
bind the exact APK bytes. Current Gradle-input regeneration and actual SDK inspection
are hosted gates, not established by source/static tests. No app dependency or
production-class/coverage policy changes are required.

A separate final-signed reader verifies the independently expected certificate,
identity/security and unchanged complete non-signature payload, then creates new
sidecars bound to the final APK hash without mutating either APK or the accepted
unsigned capsule. It neither signs nor grants source/publication authority. Ordinary
trusted hosted Gradle builds remain the build boundary; no historical compiler-byte
reconstruction, generic decompiler or exhaustive notice-genealogy system is introduced.
See [capsule commands and limits](CONTRIBUTING.md#apk-bound-runtime-sbom-and-notices)
and [preserved attribution scope](docs/legal/runtime/README.md).

## Guarded signed release boundary

The manual controller separates static `RELEASE_CONFIG` validation and derived
source/run admission, draft-capable remote inventory, credential-free compilation,
protected existing-key signing and separately approved exact-byte publication.
The dedicated run_number is the prepare code; publish selects a successful prepare
run by ID and reverifies its retained artifact without rebuilding or reallocating.
Verified accepted RC/stable history supplies the floor, including derived empty
history. Missing/invalid configuration, disabled channels, unknown state, source
outside green main history, workflow-tree drift and unprotected environments deny.
Before environment approval the summary shows channel, tag, source SHA and code.
Approval is the owner's confirmation pause, not independent review or a per-commit
variable pin. Only the remote GET job and publisher request Contents write; the
owner accepts the former's real write-token capability for draft visibility. This
source change writes no production settings.

One additional secret-free hosted RC build uses a runner-temp disposable fixture key,
independent public DER pin, actual SDK verification, existing final binder and a
localhost mock of the same publisher protocol. Its exact downloaded signed APK is
installed/pulled/inspected/launched after debug observations on the same existing
emulator, outside all coverage retries; production-package cleanup preserves graph
failure precedence. No app source, dependency, UI/test identity, coverage denominator,
threshold or native retry/resource budget changes are made. Actual SDK/native timing
and evidence are hosted acceptance, not established by pure tooling tests. The
[owner runbook](docs/releasing.md) defines static configuration, owner confirmation,
byte allowlist/readback and partial-state recovery; production custody/configuration,
phone/live evidence and final GO remain owner-gated.
