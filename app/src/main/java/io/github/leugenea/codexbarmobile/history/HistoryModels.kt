package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.Knowledge
import io.github.leugenea.codexbarmobile.usage.Reason
import io.github.leugenea.codexbarmobile.usage.ResetTime
import io.github.leugenea.codexbarmobile.usage.SelectionState
import io.github.leugenea.codexbarmobile.usage.Slot
import io.github.leugenea.codexbarmobile.usage.UsageObservation
import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** Local credential lifetime, NOT a provider account identifier. Allocated by M4a-4. */
data class HistoryPartition(val value: UUID)

/** Identity-compared, non-persistable admission capability; not a partition or clock epoch. */
class HistoryGeneration

/** Fresh on every process/clock restart. Persisted values may be compared, never subtracted. */
data class ClockEpoch(val value: UUID)

data class HistoryClock(val epoch: ClockEpoch, val monotonicMillis: Long?) {
    init { require(monotonicMillis == null || monotonicMillis >= 0) }
}

/** Contiguous durable admission ordinal within ONE partition, starting at 1 (including gaps). */
data class ObservationId(val ordinal: Long) {
    init { require(ordinal > 0) }
}

/** Canonical candidate identity excludes the source slot and any guessed reset cause. */
data class WindowIdentity(
    val partition: HistoryPartition,
    val kind: WindowKind,
    val durationSeconds: Long,
    val resetAt: Instant,
)

data class SegmentIdentity(val partition: HistoryPartition, val kind: WindowKind, val first: ObservationId)

enum class BreakReason {
    FIRST_OBSERVATION, WINDOW_CHANGED, PERCENT_CORRECTION, BACKGROUND, READ_ERROR,
    FIELD_GAP, AMBIGUOUS_WINDOW, UNKEYED_RESET, UNKNOWN_OBSERVATION_TIME,
    INTERVAL_EXCEEDED, NON_INCREASING_WALL_TIME, MONOTONIC_UNAVAILABLE,
    NON_INCREASING_MONOTONIC_TIME, WALL_MONOTONIC_DIVERGENCE, NEW_CLOCK_EPOCH,
}

enum class ResetProvenance { ABSOLUTE, RELATIVE_DERIVED, UNKNOWN, AMBIGUOUS, MALFORMED, UNSUPPORTED, DISCREPANT }

/** No arbitrarily selected facts when the periodic candidate is missing or ambiguous. */
data class HistoryReset(val provenance: ResetProvenance, val facts: ResetTime?)

/** NOMINAL is an analytical full-quota assumption, never proof of the actual quota start. */
enum class NominalStartConfidence { NOMINAL_FULL_QUOTA, UNCERTAIN_CORRECTION, UNAVAILABLE }

enum class BaselineEligibility {
    ELIGIBLE, UNKNOWN_OBSERVATION_TIME, WINDOW_UNAVAILABLE, AMBIGUOUS_WINDOW,
    UNKNOWN_PERCENT, UNKEYED_RESET, BEFORE_NOMINAL_START, AFTER_RESET,
    CLOCK_UNVERIFIED, CLOCK_DISCONTINUITY,
    UNCERTAIN_CORRECTION, TIME_RANGE_OVERFLOW,
}

/** The cause of every reset change remains unknown, including any banked/manual reset. */
enum class ResetCause { UNKNOWN }

class HistorySegment(
    val id: SegmentIdentity,
    val window: WindowIdentity?,
    breaks: Collection<BreakReason>,
) {
    val breaks: Set<BreakReason> = immutableSet(breaks)
    val resetCause: ResetCause = ResetCause.UNKNOWN
}

/** A real measurement only: no baseline endpoints, interpolation, clamping or rounding. */
data class HistoryPoint(
    val observation: ObservationId,
    val observedAt: Instant,
    val clock: HistoryClock,
    val usedPercent: BigDecimal,
    val segment: HistorySegment,
    val nominalStart: NominalStartConfidence,
    val baseline: BaselineEligibility,
)

/** A1 field knowledge survives even when no timestamped or keyed point can be admitted. */
data class HistoryWindow(
    val kind: WindowKind,
    val selection: SelectionState,
    val duration: Field<Long>?,
    val percent: Field<BigDecimal>?,
    val reset: HistoryReset,
    val point: HistoryPoint?,
    val baseline: BaselineEligibility,
)

/** Unknown/unsupported slot durations are evidence, not guessed periodic windows. */
data class HistorySlot(
    val slot: Slot,
    val knowledge: Knowledge,
    val reason: Reason?,
    val kind: WindowKind?,
    val duration: Field<Long>?,
)

sealed interface HistoryEvent {
    data class Observed(val usage: UsageObservation) : HistoryEvent
    data class Gap(val reason: HistoryGap) : HistoryEvent
}

enum class HistoryGap { BACKGROUND, READ_ERROR }

data class HistoryAdmission(
    val partition: HistoryPartition,
    val id: ObservationId,
    val clock: HistoryClock,
    val event: HistoryEvent,
)

/** No plan text, account hints, raw payloads, credentials or retained provider identities. */
class HistoryEntry(
    val partition: HistoryPartition,
    val id: ObservationId,
    val clock: HistoryClock,
    val observedAt: Instant?,
    val gap: HistoryGap?,
    windows: Collection<HistoryWindow>,
    slots: Collection<HistorySlot>,
    val allowed: Field<Boolean>?,
    val limitReached: Field<Boolean>?,
) {
    val windows: List<HistoryWindow> = immutableList(windows)
    val slots: List<HistorySlot> = immutableList(slots)
}

/** Constant-sized tail; never a growing in-memory history. Persist atomically with append. */
data class WindowCursor(
    val lastPoint: HistoryPoint? = null,
    val lastCandidate: WindowIdentity? = null,
    val nominalStart: NominalStartConfidence = NominalStartConfidence.UNAVAILABLE,
    val pendingBreak: BreakReason? = null,
)

data class HistoryCursor(
    val partition: HistoryPartition,
    val lastOrdinal: Long = 0,
    val fiveHour: WindowCursor = WindowCursor(),
    val weekly: WindowCursor = WindowCursor(),
) {
    init { require(lastOrdinal >= 0) }

    /** null means ordinal capacity exhausted; never wrap or reuse a prior admission ID. */
    fun nextId(): ObservationId? = if (lastOrdinal == Long.MAX_VALUE) null else ObservationId(lastOrdinal + 1)
}

internal fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <T> immutableSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
