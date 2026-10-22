# Local history export, JSON v1

Open the drawer → **Full local history details** → **Export history**. Android's
Storage Access Framework (`ACTION_CREATE_DOCUMENT`, `application/json`) asks where
to save `codexbar-history-v1.json`. No storage permission is requested. Cancellation
leaves history unchanged and shows no success message. Storage/preparation failures
show a short failure message; success is reported only after the UTF-8 writer closes.
A document provider can leave a partial/empty destination on failure; retry to another
file if necessary. The app does not keep URI access or a private export-file copy.

## Scope and privacy

This is **history as stored**, not provider responses, chart projections, current
quota alone or a replay of the reducer. All retained entries are exported in ascending
durable ordinal order, including unknown-time/status entries and explicit gaps.
Evicted measurements are not reconstructed. Continuation is included even if no
entries survive retention. The export can contain sensitive usage/timing information;
choose and share its destination accordingly.

There are no credentials, access/refresh tokens, authorization/device codes, raw
provider payloads, account names/identifiers or plan data. The local credential-lifetime
partition UUID is deliberately absent from entries, window identities, segment IDs
and the cursor. Clock epoch UUIDs **are** included: they identify comparable monotonic
clock runs, not accounts or local credential partitions. No runtime generation or
capability is serialized.

## Contract and compatibility

The machine-readable shape is [history-export-v1.schema.json](history-export-v1.schema.json).
The checked-in [v1 fixture](../app/src/test/resources/history-export-v1.json) demonstrates
a retained gap and nonzero continuation after eviction. The production implementation
is `history/HistoryExport`, `HistoryJsonWriter` and `HistoryJsonReader`; it uses the
already-shipped kotlinx.serialization JSON tree library without reflection or new
dependencies. The public JSON schema is independent of the private SQLite binary
payload schema.

* `formatVersion` is the integer **1**, not the app version. The v1 decoder rejects
  missing, malformed or newer/unsupported versions before returning a document.
* All known members shown in the schema are required, including explicit `null`
  for unavailable optional values. Unknown object members are ignored at every level,
  including unknown cutoff keys. Unknown enum values are not unknown object members:
  they are rejected rather than reinterpreted.
* Future app versions must keep reading v1 and every earlier supported export
  version. An incompatible change requires a new `formatVersion` and a separate
  decoder path; changing SQLite schema does not change this file contract.
* Object order and whitespace are insignificant; array order is significant.
  Breaks are a unique set, serialized as an array. Enum **names**, not Kotlin enum
  ordinals, are the stable wire values.
* Ordinals, durations, relative seconds and monotonic milliseconds are signed 64-bit
  JSON integers (ordinals start at 1; high-water starts at 0; monotonic time is
  nonnegative or null). Consumers must not round them through JavaScript doubles.
* Percentages are exact `BigDecimal.toString()` **strings**, preserving scale and
  exponent, never rounded floats. Measured/known percentages are in [0, 100].
  The storage decimal bounds still apply (128 significant digits, 256 text characters).
* All instants are ISO-8601 `java.time.Instant` strings with full nanosecond precision;
  no locale/phone-zone conversion is applied. App/export metadata is a string app
  version and an export instant; neither affects historical observation clocks.

### Top level

| Member | Meaning |
| --- | --- |
| `formatVersion` | Integer `1` |
| `appVersion` | Exporting installed app's version name |
| `exportedAt` | Time export preparation was requested, not an observation time |
| `entries` | Every retained stored entry, ascending by `ordinal` |
| `continuation` | Durable high-water, reducer cursor and retention bookkeeping |

### Entries and nested facts

Each entry has `ordinal`, `clock`, nullable `observedAt`, nullable `gap`, `windows`,
`slots`, nullable `allowed` and nullable `limitReached`. Gap values are `BACKGROUND`
or `READ_ERROR`; gaps have no observed time, windows or slots. An observed/status
entry retains both periodic window records and both original slot records even when
no point is usable. `allowed` and `limitReached` are independent field facts.

A field object retains `knowledge`, nullable `reason` and nullable `value` independently.
A non-null value requires `KNOWN` knowledge. There is no conversion of missing/null,
malformed or unsupported data to zero/false. A window retains `kind`, `selection`,
nullable duration/percent fields, reset facts, nullable point and baseline eligibility.
A slot retains source `slot`, `knowledge`, nullable `reason`, nullable classified
`kind` and nullable duration field. Unknown/unsupported durations are not guessed.

A reset retains `provenance` and nullable `facts`. Facts preserve the `absolute`,
`relativeSeconds` and `relativeDerived` fields, `discrepant` and nullable `due` flag;
no effective reset is recomputed during serialization.

A point retains its own `ordinal`, `observedAt`, `clock`, exact `usedPercent`, segment,
`nominalStart` confidence and `baseline` eligibility. A segment has `kind`,
`firstOrdinal`, nullable `window`, unique `breaks` and `resetCause` (`UNKNOWN`). A
window identity contains only `kind`, `durationSeconds` and `resetAt`; the destination
partition is supplied separately by a future importer. Segment origins can precede
the earliest retained entry and must never be fabricated into measured endpoints.

A clock is `{ "epoch": "<UUID>", "monotonicMillis": <integer-or-null> }`. An importer
must not subtract monotonic clocks from different epochs or reinterpret an exported
epoch as its current process clock.

### Continuation

`highWaterOrdinal` is the last admitted ordinal, including gaps/status and evicted
entries. It is not the maximum retained ordinal or entry count. `cursor.fiveHour`
and `cursor.weekly` each contain nullable `lastPoint`, nullable `lastCandidate`,
`nominalStart` and nullable `pendingBreak`. The last point can refer to an evicted
entry; the tail is preserved verbatim, not rebuilt from `entries`. The cursor's
internal last ordinal is restored from `highWaterOrdinal`.

`ageAnchor` (nullable `{at, clock}`), `ageSuspended`, `cutoffs` (optional entries for
`AGE_RETENTION`, `OBSERVATION_CAP`, `BYTE_CAP`, each the highest evicted ordinal) and
nullable `lastClock` preserve the existing retention continuation, including clock
anomalies. This avoids silently changing recording/retention after future import.
The schema describes shape; production validation additionally checks ordering,
high-water bounds, matching entry/point facts, unique windows/slots/breaks, supported
identity durations and cursor/segment invariants.

## Consistency and ownership

Preparation uses the current revocable history capability on the existing storage
lane, off Main. One SQLite I/O lock covers the continuation read and every bounded
`HistoryStore.read(limit, after)` page. Appends and retention maintenance cannot
interleave and tear the snapshot. A failed/revoked page is export failure, never
empty history. The controller rechecks the captured lifetime/capability before
handing off the detached JSON. No provider/network reads or history admissions are
triggered by export.

The prepared snapshot is retained only in the process owner's memory while the
system picker is open. Its Activity Result handler is always registered at the
navigation root, so Activity recreation can return to the dashboard and still save
the same explicitly requested snapshot. Process death drops the in-memory snapshot;
a returned destination without it reports failure, not success. Backgrounding for
SAF does not acquire a new account's history. UTF-8 destination I/O runs on IO, not
Main. Import/replacement/confirmation is intentionally left to issue #168; decoding
alone performs no storage writes and requires an explicit destination partition.
