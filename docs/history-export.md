# Local history export, JSON v1

Open the drawer → **Full local history details** → **Export history**. Android's
Storage Access Framework (`ACTION_CREATE_DOCUMENT`, `application/json`) saves
`codexbar-history-v1.json` without a storage permission. Cancellation is silent;
success is reported only after the UTF-8 writer closes. A failed document provider
can leave a partial/empty destination. The app retains no URI access or export copy.

## Scope and privacy

This is history as stored: all retained entries in ascending durable ordinal order,
including status entries and gaps, plus continuation even after retention evicts
entries. It is not provider responses, chart projections or reconstructed history.
Exports contain sensitive usage/timing information, but no credentials, tokens,
authorization/device codes, raw payloads, account names/identifiers, plan data,
local credential-lifetime partition UUID, runtime generation or capability.
Clock epoch UUIDs identify comparable monotonic clock runs, not accounts/partitions.

## Contract and compatibility

This description and the checked-in [v1 fixture](../app/src/test/resources/history-export-v1.json)
are the format contract, independent of private SQLite encoding. The JVM test
`HistoryExportTest.checkedInV1FixtureDecodesToExpectedEntriesAndContinuation` guards
continued decoding of that fixture into the expected entries and continuation.
Production encoding/decoding uses `HistoryExport`, `HistoryJsonWriter` and
`HistoryJsonReader`.

* `formatVersion` is integer **1**, not the app version. Missing, malformed or
  unsupported versions are rejected.
* Changes within v1 are additive-only fields: never rename, remove or repurpose
  existing fields or change their types/meanings. Unknown object members are ignored
  at every level, including unknown cutoff keys; unknown enum values are rejected.
  Existing known members are required, with explicit `null` for nullable values.
* Future app versions must keep reading v1 and every earlier supported export
  version. Bump `formatVersion` only for incompatible changes, with a separate
  decoder path. A SQLite encoding change alone does not change this contract.
* Object order/whitespace are insignificant; array order matters. Breaks are a unique
  set serialized as an array. Enums use stable **names**, never Kotlin ordinals.
* Ordinals, durations, relative seconds and monotonic milliseconds are signed 64-bit
  JSON integers. Ordinals start at 1, high-water at 0; monotonic time is nonnegative
  or null. Consumers must not round these through JavaScript doubles.
* Percentages are exact `BigDecimal.toString()` **strings**, preserving scale/exponent,
  never rounded floats. Known percentages are in [0, 100]; storage decimal bounds
  remain 128 significant digits and 256 text characters.
* Instants are ISO-8601 `java.time.Instant` strings preserving nanoseconds, without
  locale/time-zone conversion. Metadata does not alter historical clocks.

### Top level

| Member | Meaning |
| --- | --- |
| `formatVersion` | Integer `1` |
| `appVersion` | Exporting installed app's version name, a string |
| `exportedAt` | Instant export preparation was requested, not observation time |
| `entries` | All retained stored entries, ascending by `ordinal` |
| `continuation` | Durable high-water, reducer cursor and retention bookkeeping |

### Entries and nested facts

Each entry has `ordinal`, `clock`, nullable `observedAt`, nullable `gap`, `windows`,
`slots`, nullable `allowed` and nullable `limitReached`. Gaps (`BACKGROUND` or
`READ_ERROR`) have no observed time/windows/slots. Other entries retain both periodic
window records and original slot records even without usable points.

Field objects contain `knowledge`, nullable `reason` and nullable `value`; non-null
values require `KNOWN`. Missing/malformed/unsupported facts never become zero/false.
Windows contain `kind`, `selection`, nullable `duration`/`percent` fields, `reset`,
nullable `point` and `baseline`. Slots contain `slot`, `knowledge`, nullable `reason`,
nullable classified `kind` and nullable `duration` field. Reset objects contain
`provenance` and nullable `facts`; facts contain `absolute`, `relativeSeconds` and
`relativeDerived` fields, `discrepant` and nullable `due`, without recomputation.

Points contain `ordinal`, `observedAt`, `clock`, exact `usedPercent`, `segment`,
`nominalStart` and `baseline`. Segments contain `kind`, `firstOrdinal`, nullable
`window`, unique `breaks` and `resetCause` (`UNKNOWN`). Window identities contain
`kind`, `durationSeconds` and `resetAt`; import supplies the destination partition
separately. Segment origins may precede retained entries, never invented endpoints.
Clocks contain UUID string `epoch` and integer-or-null `monotonicMillis`; never
subtract clocks from different epochs or treat an exported epoch as the current one.

### Continuation

`highWaterOrdinal` is the last admitted ordinal, including gaps/status/evicted entries,
not the retained maximum/count. `cursor.fiveHour` and `cursor.weekly` each contain
nullable `lastPoint`, nullable `lastCandidate`, `nominalStart` and nullable `pendingBreak`.
The tail may refer to evicted data and is not rebuilt from `entries`; the cursor's
internal last ordinal is restored from high-water.

Nullable `ageAnchor` (`{at, clock}`), `ageSuspended`, `cutoffs` (optional
`AGE_RETENTION`, `OBSERVATION_CAP`, `BYTE_CAP` keys holding highest evicted ordinals)
and nullable `lastClock` preserve retention continuation and clock anomalies.
Production validation checks ordering, high-water bounds, matching entry/point facts,
unique windows/slots/breaks, supported identity durations and cursor/segment invariants.

## Consistency and ownership

Preparation holds the existing SQLite I/O lock across continuation and all bounded
entry pages, off Main. Failed/revoked reads are failures, never empty history; the
controller rechecks lifetime/capability before handing off the detached snapshot.
Export makes no provider requests or history admissions.

The snapshot stays only in process-owner memory during SAF. The Activity Result
handler is registered at the navigation root, so Activity recreation can save the
same snapshot; process death loses it and a returned destination reports failure.
Destination I/O runs off Main. Import/replacement/confirmation is separate; decoding
alone performs no storage writes and requires an explicit destination partition.
