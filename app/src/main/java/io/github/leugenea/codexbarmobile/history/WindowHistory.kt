package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.Knowledge
import io.github.leugenea.codexbarmobile.usage.ResetTime
import io.github.leugenea.codexbarmobile.usage.SelectionState
import io.github.leugenea.codexbarmobile.usage.UsageObservation
import io.github.leugenea.codexbarmobile.usage.UsageWindow
import io.github.leugenea.codexbarmobile.usage.WindowKind
import io.github.leugenea.codexbarmobile.usage.WindowSelection
import java.time.DateTimeException
import java.time.Duration

sealed interface HistoryReduction {
    data class Applied(val entry: HistoryEntry, val cursor: HistoryCursor) : HistoryReduction
    data class AlreadyAdmitted(val id: ObservationId) : HistoryReduction
    data class Rejected(val reason: AdmissionRejection) : HistoryReduction
}

enum class AdmissionRejection { PARTITION_MISMATCH, ORDINAL_GAP }

/** Pure constant-space append reducer; the store serializes and persists entry + cursor atomically. */
object WindowHistory {
    private val untrustworthyClockBreaks = setOf(BreakReason.NON_INCREASING_WALL_TIME,
        BreakReason.NON_INCREASING_MONOTONIC_TIME, BreakReason.WALL_MONOTONIC_DIVERGENCE)
    fun append(cursor: HistoryCursor, admission: HistoryAdmission): HistoryReduction {
        if (cursor.partition != admission.partition) return HistoryReduction.Rejected(AdmissionRejection.PARTITION_MISMATCH)
        if (admission.id.ordinal <= cursor.lastOrdinal) return HistoryReduction.AlreadyAdmitted(admission.id)
        if (admission.id.ordinal != cursor.lastOrdinal + 1) return HistoryReduction.Rejected(AdmissionRejection.ORDINAL_GAP)
        return when (val event = admission.event) {
            is HistoryEvent.Gap -> gap(cursor, admission, event.reason)
            is HistoryEvent.Observed -> observed(cursor, admission, event.usage)
        }
    }

    private fun gap(cursor: HistoryCursor, admission: HistoryAdmission, reason: HistoryGap): HistoryReduction.Applied {
        val breakReason = when (reason) {
            HistoryGap.BACKGROUND -> BreakReason.BACKGROUND
            HistoryGap.READ_ERROR -> BreakReason.READ_ERROR
        }
        val entry = HistoryEntry(admission.partition, admission.id, admission.clock, null, reason,
            emptyList(), emptyList(), null, null)
        val next = cursor.copy(lastOrdinal = admission.id.ordinal,
            fiveHour = interrupted(cursor.fiveHour, breakReason), weekly = interrupted(cursor.weekly, breakReason))
        return HistoryReduction.Applied(entry, next)
    }

    private fun observed(cursor: HistoryCursor, admission: HistoryAdmission, usage: UsageObservation): HistoryReduction.Applied {
        val five = window(cursor.fiveHour, admission, usage, usage.fiveHour, WindowKind.FIVE_HOUR)
        val weekly = window(cursor.weekly, admission, usage, usage.weekly, WindowKind.WEEKLY)
        val slots = usage.slots.map { slot ->
            HistorySlot(slot.slot, slot.window.knowledge, slot.window.reason, slot.window.value?.kind,
                slot.window.value?.durationSeconds)
        }
        val entry = HistoryEntry(admission.partition, admission.id, admission.clock, usage.observedAt, null,
            listOf(five.first, weekly.first), slots, usage.allowed, usage.limitReached)
        val next = HistoryCursor(cursor.partition, admission.id.ordinal, five.second, weekly.second)
        return HistoryReduction.Applied(entry, next)
    }

    private fun window(
        cursor: WindowCursor, admission: HistoryAdmission, usage: UsageObservation,
        selection: WindowSelection, kind: WindowKind,
    ): Pair<HistoryWindow, WindowCursor> {
        val source = unambiguousSource(selection, kind)
        val reset = selectionReset(source, selection)
        val absent = unavailable(usage, selection, source)
        if (absent != null) {
            val fact = HistoryWindow(kind, selection.state, source?.durationSeconds, source?.usedPercent, reset, null, absent)
            return fact to interrupted(cursor, missingBreak(absent))
        }
        val candidate = candidate(admission.partition, source!!, reset)
        val breaks = breaks(cursor, admission, usage, source, candidate)
        val confidence = confidence(cursor, candidate, breaks)
        val baseline = eligibility(usage, admission.clock, candidate, confidence, breaks)
        val segment = if (breaks.isEmpty()) cursor.lastPoint!!.segment else HistorySegment(
            SegmentIdentity(admission.partition, kind, admission.id), candidate, breaks)
        val point = HistoryPoint(admission.id, usage.observedAt!!, admission.clock, source.usedPercent.value!!,
            segment, confidence, baseline)
        val fact = HistoryWindow(kind, selection.state, source.durationSeconds, source.usedPercent, reset, point, baseline)
        val next = if (candidate == null) interrupted(cursor, BreakReason.UNKEYED_RESET)
            else WindowCursor(point, candidate, confidence)
        return fact to next
    }

    private fun unambiguousSource(selection: WindowSelection, kind: WindowKind): UsageWindow? {
        if (selection.state == SelectionState.AMBIGUOUS) return null
        if (selection.state == SelectionState.UNAVAILABLE) return null
        val source = selection.candidates.singleOrNull() ?: return null
        if (source.kind != kind) return null
        val seconds = when (kind) {
            WindowKind.FIVE_HOUR -> 18000L
            WindowKind.WEEKLY -> 604800L
            else -> return null
        }
        return source.takeIf { it.durationSeconds.knowledge == Knowledge.KNOWN && it.durationSeconds.value == seconds }
    }

    private fun unavailable(
        usage: UsageObservation, selection: WindowSelection, source: UsageWindow?,
    ): BaselineEligibility? = when {
        selection.state == SelectionState.AMBIGUOUS -> BaselineEligibility.AMBIGUOUS_WINDOW
        source == null -> BaselineEligibility.WINDOW_UNAVAILABLE
        usage.observedAt == null -> BaselineEligibility.UNKNOWN_OBSERVATION_TIME
        source.usedPercent.knowledge != Knowledge.KNOWN -> BaselineEligibility.UNKNOWN_PERCENT
        source.usedPercent.value == null -> BaselineEligibility.UNKNOWN_PERCENT
        else -> null
    }

    private fun missingBreak(reason: BaselineEligibility): BreakReason = when (reason) {
        BaselineEligibility.AMBIGUOUS_WINDOW -> BreakReason.AMBIGUOUS_WINDOW
        BaselineEligibility.UNKNOWN_OBSERVATION_TIME -> BreakReason.UNKNOWN_OBSERVATION_TIME
        else -> BreakReason.FIELD_GAP
    }

    private fun interrupted(cursor: WindowCursor, reason: BreakReason): WindowCursor =
        cursor.copy(pendingBreak = reason)

    private fun selectionReset(source: UsageWindow?, selection: WindowSelection): HistoryReset {
        if (source != null) return HistoryReset(resetProvenance(source.reset), source.reset)
        val provenance = if (selection.state == SelectionState.AMBIGUOUS) ResetProvenance.AMBIGUOUS else ResetProvenance.UNKNOWN
        return HistoryReset(provenance, null)
    }

    private fun resetProvenance(reset: ResetTime): ResetProvenance = when {
        reset.discrepant -> ResetProvenance.DISCREPANT
        reset.absolute.knowledge == Knowledge.KNOWN && reset.absolute.value != null -> ResetProvenance.ABSOLUTE
        reset.absolute.knowledge == Knowledge.MALFORMED -> ResetProvenance.MALFORMED
        reset.absolute.knowledge == Knowledge.UNSUPPORTED -> ResetProvenance.UNSUPPORTED
        reset.relativeDerived.knowledge == Knowledge.KNOWN && reset.relativeDerived.value != null -> ResetProvenance.RELATIVE_DERIVED
        reset.relativeDerived.knowledge == Knowledge.MALFORMED -> ResetProvenance.MALFORMED
        reset.relativeDerived.knowledge == Knowledge.UNSUPPORTED -> ResetProvenance.UNSUPPORTED
        else -> ResetProvenance.UNKNOWN
    }

    private fun candidate(partition: HistoryPartition, source: UsageWindow, reset: HistoryReset): WindowIdentity? {
        if (reset.provenance != ResetProvenance.ABSOLUTE) return null
        return WindowIdentity(partition, source.kind, source.durationSeconds.value!!, source.reset.absolute.value!!)
    }

    private fun breaks(
        cursor: WindowCursor, admission: HistoryAdmission, usage: UsageObservation,
        source: UsageWindow, candidate: WindowIdentity?,
    ): Set<BreakReason> {
        val reasons = linkedSetOf<BreakReason>()
        cursor.pendingBreak?.let { reasons.add(it) }
        if (cursor.lastCandidate == null) reasons.add(BreakReason.FIRST_OBSERVATION)
        if (candidate == null) reasons.add(BreakReason.UNKEYED_RESET)
        if (candidate != null && cursor.lastCandidate != null && candidate != cursor.lastCandidate) reasons.add(BreakReason.WINDOW_CHANGED)
        val previous = cursor.lastPoint
        if (previous != null) {
            if (candidate != null && candidate == previous.segment.window && source.usedPercent.value!! < previous.usedPercent) {
                reasons.add(BreakReason.PERCENT_CORRECTION)
            }
            reasons.addAll(clockBreaks(previous, admission.clock, usage.observedAt!!))
        }
        return reasons
    }

    private fun clockBreaks(previous: HistoryPoint, clock: HistoryClock, at: java.time.Instant): Set<BreakReason> {
        val reasons = linkedSetOf<BreakReason>()
        val wall = Duration.between(previous.observedAt, at)
        if (wall <= Duration.ZERO) reasons.add(BreakReason.NON_INCREASING_WALL_TIME)
        if (wall > Duration.ofSeconds(120)) reasons.add(BreakReason.INTERVAL_EXCEEDED)
        if (clock.epoch != previous.clock.epoch) {
            reasons.add(BreakReason.NEW_CLOCK_EPOCH)
            return reasons // Never subtract monotonic values from different runtimes.
        }
        val old = previous.clock.monotonicMillis
        val current = clock.monotonicMillis
        if (old == null || current == null) {
            reasons.add(BreakReason.MONOTONIC_UNAVAILABLE)
            return reasons
        }
        val monotonic = Duration.ofMillis(current).minus(Duration.ofMillis(old))
        if (monotonic <= Duration.ZERO) reasons.add(BreakReason.NON_INCREASING_MONOTONIC_TIME)
        if (wall.minus(monotonic).abs() > Duration.ofSeconds(5)) reasons.add(BreakReason.WALL_MONOTONIC_DIVERGENCE)
        return reasons
    }

    private fun confidence(
        cursor: WindowCursor, candidate: WindowIdentity?, breaks: Set<BreakReason>,
    ): NominalStartConfidence = when {
        candidate == null -> NominalStartConfidence.UNAVAILABLE
        BreakReason.PERCENT_CORRECTION in breaks -> NominalStartConfidence.UNCERTAIN_CORRECTION
        candidate == cursor.lastCandidate -> cursor.nominalStart
        else -> NominalStartConfidence.NOMINAL_FULL_QUOTA
    }

    /** Eligibility only; M4a-3 owns DECIMAL128 B(t) and delta, not this reducer. */
    private fun eligibility(
        usage: UsageObservation, clock: HistoryClock, candidate: WindowIdentity?,
        confidence: NominalStartConfidence, breaks: Set<BreakReason>,
    ): BaselineEligibility {
        if (candidate == null) return BaselineEligibility.UNKEYED_RESET
        if (confidence == NominalStartConfidence.UNCERTAIN_CORRECTION) return BaselineEligibility.UNCERTAIN_CORRECTION
        if (clock.monotonicMillis == null) return BaselineEligibility.CLOCK_UNVERIFIED
        if (breaks.any { it in untrustworthyClockBreaks }) return BaselineEligibility.CLOCK_DISCONTINUITY
        return try {
            val start = candidate.resetAt.minusSeconds(candidate.durationSeconds)
            when {
                usage.observedAt!! < start -> BaselineEligibility.BEFORE_NOMINAL_START
                usage.observedAt > candidate.resetAt -> BaselineEligibility.AFTER_RESET
                else -> BaselineEligibility.ELIGIBLE
            }
        } catch (_: DateTimeException) {
            BaselineEligibility.TIME_RANGE_OVERFLOW
        }
    }
}
