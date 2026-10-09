# Architecture

## Implemented application

One Android phone app module, `io.github.leugenea.codexbarmobile`, launches
`MainActivity` via MAIN/LAUNCHER. Compose Material3 renders four explicitly offline
previews: disconnected, frozen loading, simulated error and demo usage.

```text
MainActivity / rememberSaveable preview key
    -> OfflineShellState (pure synchronous transitions)
    -> Compose screens / string resources
    -> OfflineFixtures (fixed synthetic percentages, only in demo)
```

The saved key restores preview selection across Activity recreation; malformed or
unknown keys restore disconnected. It is not an account/session identity or a
persistent history store. Retry only selects the loading preview; displaying a
sample only selects demo. No timer, background request or live reset observation
runs. Resources label all samples and connection absence explicitly.

The source manifest requests INTERNET only, with `usesCleartextTraffic="false"`.
The exact merged/APK/installed allowlist also includes the existing AndroidX Core
signature-only `io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`;
it is not another platform/network permission. Gates reject any missing or extra
permission and any Network Security Config override. The manifest flag is sufficient
for the current API 26 minimum / API 37 target; moving to target API 38 or adding
a Network Security Config requires a separately reviewed cleartext policy update.
Debug/test builds do not opt into device cleartext; JVM local-server tests do not
need that opt-in. This replaces the historical no-INTERNET invariant, not the
offline demo boundary: preview actions still initiate no requests. Backup/extraction rules
exclude app data. The default offline preview initiates no provider requests.

## Native connection screen

A separate Connection screen is opt-in and never uses the offline fixtures:

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
The code is displayed from A7's owned memory, never saved or copied. One bounded
attempt survives Activity recreation, backgrounding into the browser and Activity
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
B1 alone supplies reset absolute/relative labels, including unknown/sub-hour/passed and
repeated-hour offsets. Status transitions use polite accessibility live regions; ordinary
countdowns do not repeatedly announce. One wrapping, vertically scrollable content tree
keeps actions reachable in landscape. Only the existing harmless tab/preview selection
is Bundle-restored; usage stays with the process owner across Activity recreation, not
with a saved credential or a persistent history store. C2's read-only banked-reset
section consumes B2's inventory cache through C1
`EntitlementPresentation`; `BankedResetPresentation` only adapts resource labels and
independent freshness/error metadata. Provider counts and comparison clocks are kept
verbatim, including discrepancies and expired-available rows. B1 supplies both expiry
labels, distinct from periodic reset. Row/count containers each own one accessibility
announcement; duplicate visible text is hidden from accessibility. No action callback,
activation/purchase request or new owner is introduced. Retirement discards every
entitlement fact; the existing A10/B2 generation path rejects late inventory results.
The gate retains explicit read/session-refresh controls and sign-in-again action.

B2's `UsageRefresh` is a subordinate scheduler in that same owner scope/lane, not
another session owner. The opt-in live gate observes `repeatOnLifecycle(STARTED)`;
identity-tagged observers keep one disappearing Activity from cancelling another
visible observer. Connect/manual read activates refresh for the current generation.
Protected restoration remains dormant until an explicit action, including when the
restored gate is opened: default offline previews and fresh-runtime restoration
remain no-network. After activation, foreground resume requests one bounded cycle;
manual/resume/connect triggers coalesce while a cycle is in flight. Foreground loss
cancels eligible reads, local ticks and polls but never owns token rotation or auth.
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

Every state reports binding UNRESOLVED / identity UNVERIFIED. Token receipt and HTTP
200 do not resolve account association. The owner accepted this single identity-unverified
session under Q1 and recorded A8 GO on 2026-10-07 in issue #48. CI uses fake transport
and synthetic protocol data; owner-operated physical-phone sign-in and actual
process-kill/relaunch are separate evidence boundaries, not established by parser
tests. A10 live refresh/rotation/logout still needs an exact-candidate owner check;
A8 sign-in alone does not close parent #4.

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
applicable break reasons are retained on immutable segment-start metadata. Graphs must
not join different segment IDs, including after pagination or retention removes the
segment's first real point. No artificial start/reset/endpoints or forward-filled points
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
journal, fixed 32-byte binding/privacy fence, pending binding replacement and lane lock.
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
lifetime and credentials; return re-adopts ACTIVE through storage with fresh runtime
capability. Cancel and holder shutdown also revoke without turning a clean lifetime into a
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
must keep the descriptor separate from measured points and must not join distinct
segment IDs, even for the same canonical window or a retained/paginated segment whose
origin is absent. Five-hour/weekly and same-kind reset changes stay independent; a
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
success, retained cache, failure, preview, local tick and analytical evaluation add no points.
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
query revision. Last-observer loss, cancel, logout/replacement and shutdown immediately
clear queued facts/views and retire publication authority; no held read/write can expose
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
  stays ordinal-ordered and single-partition; selection filters window facts on that page,
  not the storage cursor. A query is not a provider read or new observation. Latest query
  revision rejects held earlier-query publication. No invented tail/time-range API exists.
- The original `HistoryReadSnapshot`: entries, high-water, next-after/has-more, EMPTY /
  STATUS_ONLY / MEASUREMENTS content, retention truncation and ordinal eviction cutoffs.
  All page gap entries survive window selection. Actual points retain admission order even
  for backwards wall time; consumers never sort away a discontinuity or join segment IDs.
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
zone and locale; this component owns no timer, query, quota math or network call.

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
`HistoryChartDrawing` maps only supplied page-local runs into measured circles and
straight within-run edges. A single measurement remains a circle. Separate nominal
analytical endpoints produce dashed lines, never measured markers or forecasts.
Shape/line-style labels do not rely on color alone. No smoothing, clamping, fake
measured start/reset point, sorting, quota calculation or cross-page cache is added.

The fixed percent axis and B1 hour-precision time-domain labels wrap outside the
Canvas; exact UTC timestamps, reset context, original measurements and observed-only
percentage-point delta remain in reachable existing details. Typed decimal-capacity/
out-of-domain geometry and nonzero approximate underflow have explicit resource copy.
Missing time geometry shows no invented axes or zero. The decorative Canvas is hidden
from accessibility; plain labels and existing bounded text retain their own semantics,
with no duplicated exact-value announcement or countdown live region.

Seven deterministic `HistoryChartDrawingTest` cases cover drawing roles and boundary
preservation. Eight mandatory `HistoryChartComponentTest` cases exercise production
Canvas pixels, dashed gaps, marker/straight-edge visibility, absent run bridges,
labels/semantics/layout and reachable details across phone portrait/landscape,
light/dark and doubled font scale. Six synthetic chart PNGs extend the existing
collector without removing its four textual PNGs or 85 inherited native identities.
The collector validates decoded pixels/configuration and observed root-contained
Canvas metadata, not visual approval. Hosted execution, compatible whole-app >=90%
INSTRUCTION coverage and independent exact-candidate actual-image inspection remain
acceptance gates; compilation alone proves none of them. Owner/navigation integration
and the conditional assembled acceptance contract are described below.

## Production bounded history navigation (M4b-3a)

The existing Connection screen exposes **View local history** and a return control
for the live usage and authentication controls. `ConnectionHistory`
collects the same process owner's `historySnapshots` and sends only
`queryHistory(HistoryGraphQuery)`. It adds no observer registration, controller,
storage port, network action or sampling timer. The existing Connection-tab
STARTED lifecycle remains the only UI registration; opening dormant restored
history does not activate B2. Offline previews remain separate.

A five-hour/weekly selection renders the selected chart and its textual equivalent.
`HistoryNavigation` holds only the kind and one exclusive ordinal cursor, with a
fixed limit of 32 admitted entries. **First retained page** resets that cursor;
**Next admitted page** uses the storage page's `nextAfter` only when `hasMore` is
true. There is no page stack, accumulation, newest-first or time-range query.
Window selection preserves the storage cursor, and status/gap/filtered pages do
not redefine pagination. Explicit cursor/limit/retained-ordinal labels complement
the unchanged content, error, loss, retention and independent endpoint metadata.

Selection and detail state are in-memory `remember`, not Bundle/saved snapshots.
The recorder's one volatile display-context slot replaces its private Binding
revocation flag: workers and the host use the same authority, not a mirrored flow.
Each snapshot carries only a read-only nonpersistable permission retaining an opaque
context (with nullable bound generation) and the slot, never controller/storage or
credentials. Replacement revokes the predecessor before publication; invalidation
clears the slot before publishing unavailable. A foreground bind without storage
has a distinct diagnostic context, still with null generation. Its current ERROR
loss survives selection changes, but its retired context cannot replay even across
null → successor → null. Existing null-access binds preserve any current Binding;
background/resume loss retention and retirement loss reset are unchanged.

On each actual `ConnectionHistory` execution, one independent descriptor read
immediately before projection authorizes context/generation identity and requested
query equality. Ordinary StateFlow collection still schedules UI updates; it is not
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
MainActivity entry, window/page controls, dormant restore and offline request
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

The inherited default-factory dormant restore and offline cases count every transport
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

## Assembled history acceptance and original-parent map (M4b-4)

This is an evidence-only roll-up, not a second implementation of the pure projection,
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
| Fresh installs and sparse/unknown history remain truthful; no fake trend | Pure plot tests and native component cases distinguish no/empty/status-only/single/multiple pages, unknown baseline/geometry, and breaks. Navigation/isolation tests retain bounded pagination, storage loss/retention and independent endpoint errors. | Fresh entry has unavailable facts, no Canvas, no detail/zero and no requests. `sparseGapCorrectionAndChangedResetRemainSeparatedInActualLandscapeDarkPixelsAndDetails` requires six markers, two within-run edges and three unpainted boundaries plus reachable gap/correction/reset details. `unknownResetAndUnknownPercentAtActualLargeFontKeepCompleteAccessibleFactsAndReachableControls` preserves a real known point but unavailable baseline and a separate unknown-percent window with no Canvas/zero. `truncatedPageLandscapeLightKeepsHonestLocalCursorAndEveryBoundedDetailReachable` requires explicit more-page copy, exclusive cursor 32, only ordinals 33..35, all three reachable entries, disabled terminal controls and unchanged admission high-water. |
| Hosted phone layout/theme/readability/accessibility | Ten inherited synthetic reusable-component captures establish their component scopes, not integrated reachability; two inherited production-entry captures establish basic navigation. All owner/query/lifecycle/privacy adversaries remain required, not duplicated here. | Four new mandatory native methods add five representative real-entry viewports: fresh portrait/light, single portrait/dark, gap/correction/reset landscape/dark, unknown portrait/light at effective font scale 2, and bounded-page landscape/light. Native text-layout complete-line/ellipsis/extent checks, full versus independently clipped bounds, root containment, hidden decorative Canvas, exact-fact ownership and click/reachable-control assertions accompany them. Actual effective resource configuration is checked against native text-layout density/font scale, not inferred from filenames/requested settings. |

The phone fixture changes hosted night/font settings and restores them on failure;
orientation changes use the real Activity. The unchanged production theme reads the
effective system configuration. Each viewport is intentionally scrollable: the native
driver checks complete labels/details after reaching them rather than claiming an entire
history page fits in one image. The campaign is not a full configuration Cartesian product,
a blanket contrast certification or a physical-device accessibility audit.

The fail-closed JUnit/retry contracts require all 107 inherited identities plus four
assembled identities. The collector requires all twelve inherited captures plus the five
new declared captures, observed API/configuration, exact owner/page/request identities,
per-file hashes and checkout/source-head/run/attempt provenance. The three new Canvas
captures retain independent unclipped/clipped bounds; registered Bitmap pixel samples
are compared again with bounded decoded RGB/RGBA PNG pixels on the host, so metadata or
filenames alone cannot establish painted markers/edges, absent bridges or dash gaps.
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

## Verification boundaries

- `app/src/test`: JVM state transitions, restore behavior and percentage validation.
- `app/src/androidTest`: real production Activity/launcher, exact installed permission
  allowlist and effective cleartext-off policy, preview actions, recreation and
  landscape UI checks.
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

## Research versus future implementation

[Historical M0 research](docs/research/m0.md), schemas and attributed fixtures
are offline research inputs, not shipped provider support. The app does not load
those research JSON files. Locally authored demo values are a separate UI fixture.
Offline previews do not initiate requests. The separate native connection gate has its own
JVM and hosted fake Activity/browser/Keystore scenarios; it does not use the demo
values. Public sources and successful token receipt still do not establish Android
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
