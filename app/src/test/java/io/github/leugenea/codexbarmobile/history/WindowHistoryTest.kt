package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.history.SyntheticHistory.append
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.at
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.epoch
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.five
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.partition
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.usage
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.weekly
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.window
import io.github.leugenea.codexbarmobile.usage.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class WindowHistoryTest {
    @Test fun weeklyOnlyMovesBetweenSlotsWithoutChangingWindowOrSegment() {
        val weeklyInput = window(duration = Input.Value(604800L), absolute = Input.Value(1_800_604_800L))
        val first = append(usage = usage(primary = weeklyInput))
        val second = append(first.cursor, usage(Input.Null, weeklyInput, at.plusSeconds(60)), 61_000)
        val point = weekly(second).point!!
        assertNull(five(first).point)
        assertEquals(SelectionState.UNAVAILABLE, five(second).selection)
        assertEquals(BigDecimal("12.375"), point.usedPercent)
        assertEquals(WindowIdentity(partition, WindowKind.WEEKLY, 604800, Instant.ofEpochSecond(1_800_604_800)), point.segment.window)
        assertSame(weekly(first).point!!.segment, point.segment)
        assertEquals(Slot.SECONDARY, second.entry.slots.last().slot)
        assertEquals(Reason.PROVIDER_NULL, second.entry.slots.first().reason)
        assertEquals(Field(Knowledge.KNOWN, false), second.entry.allowed)
        assertEquals(Field(Knowledge.KNOWN, true), second.entry.limitReached)
    }

    @Test fun exactDecimalsAndObservationTimeAreNeverRoundedOrForwardFilled() {
        val first = append(usage = usage(window(Input.Value(BigDecimal("12.37500000000000000001")))))
        val second = append(first.cursor, usage(window(Input.Value(BigDecimal("12.37500000000000000002"))), observedAt = at.plusSeconds(60)), 61_000)
        assertEquals(BigDecimal("12.37500000000000000001"), five(first).point!!.usedPercent)
        assertEquals(BigDecimal("12.37500000000000000002"), five(second).point!!.usedPercent)
        assertEquals(Instant.ofEpochSecond(1_800_000_060), five(second).point!!.observedAt)
        assertEquals(2L, second.entry.id.ordinal)
        assertSame(five(first).point!!.segment, five(second).point!!.segment)
        assertEquals(setOf(BreakReason.FIRST_OBSERVATION), five(first).point!!.segment.breaks)
    }

    @Test fun earlierAndLaterResetChangesNeverClaimManualOrBankedCause() {
        for (reset in listOf(1_800_003_599L, 1_800_003_601L)) {
            val first = append()
            val next = append(first.cursor, usage(window(absolute = Input.Value(reset)), observedAt = at.plusSeconds(60)), 61_000)
            val point = five(next).point!!
            assertEquals(setOf(BreakReason.WINDOW_CHANGED), point.segment.breaks)
            assertEquals(Instant.ofEpochSecond(reset), point.segment.window!!.resetAt)
            assertNotEquals(five(first).point!!.segment.id, point.segment.id)
            assertEquals(ResetCause.UNKNOWN, point.segment.resetCause)
            assertEquals(NominalStartConfidence.NOMINAL_FULL_QUOTA, point.nominalStart)
        }
    }

    @Test fun sameWindowDecreaseIsCorrectionAndKeepsBaselineUncertainUntilNewCandidate() {
        val first = append()
        val corrected = append(first.cursor, usage(window(Input.Value(BigDecimal("9.125"))), observedAt = at.plusSeconds(60)), 61_000)
        val later = append(corrected.cursor, usage(window(Input.Value(BigDecimal("10.75"))), observedAt = at.plusSeconds(120)), 121_000)
        assertEquals(five(first).point!!.segment.window, five(corrected).point!!.segment.window)
        assertEquals(setOf(BreakReason.PERCENT_CORRECTION), five(corrected).point!!.segment.breaks)
        assertEquals(BigDecimal("12.375"), five(first).point!!.usedPercent)
        assertEquals(BigDecimal("9.125"), five(corrected).point!!.usedPercent)
        assertEquals(NominalStartConfidence.UNCERTAIN_CORRECTION, five(later).point!!.nominalStart)
        assertEquals(BaselineEligibility.UNCERTAIN_CORRECTION, five(later).baseline)
        assertSame(five(corrected).point!!.segment, five(later).point!!.segment)
        val changed = append(later.cursor, usage(window(absolute = Input.Value(1_800_003_700L)), observedAt = at.plusSeconds(180)), 181_000)
        assertEquals(BaselineEligibility.ELIGIBLE, five(changed).baseline)
    }

    @Test fun durationsDoNotMigrateSamplesOrJoinFiveHourAndWeeklySeries() {
        val first = append()
        val next = append(first.cursor, usage(window(duration = Input.Value(604800L))), 61_000)
        assertNull(five(next).point)
        assertEquals(WindowKind.WEEKLY, weekly(next).point!!.segment.window!!.kind)
        assertEquals(604800L, weekly(next).point!!.segment.window!!.durationSeconds)
        assertNotEquals(five(first).point!!.segment.id, weekly(next).point!!.segment.id)
        val both = append(usage = usage(secondary = window(duration = Input.Value(604800L))))
        assertEquals(2, both.entry.windows.count { it.point != null })
        assertEquals(WindowKind.FIVE_HOUR, five(both).point!!.segment.id.kind)
        assertEquals(WindowKind.WEEKLY, weekly(both).point!!.segment.id.kind)
    }

    @Test fun missingNullAndWrongTypeFieldsRemainStatusNotZero() {
        val cases = listOf(Input.Missing to Reason.MISSING, Input.Null to Reason.PROVIDER_NULL, Input.Invalid to Reason.WRONG_TYPE)
        for ((input, reason) in cases) {
            val result = append(usage = usage(window(input)))
            assertNull(five(result).point)
            assertEquals(reason, five(result).percent!!.reason)
            assertEquals(BaselineEligibility.UNKNOWN_PERCENT, five(result).baseline)
        }
        for (slot in listOf(Input.Missing, Input.Null, Input.Invalid)) {
            val result = append(usage = usage(primary = slot))
            assertNull(five(result).point)
            assertEquals(SelectionState.UNAVAILABLE, five(result).selection)
            assertNull(result.entry.slots.first().kind)
        }
    }

    @Test fun unsupportedAndUnknownDurationNeverBecomePeriodicPoints() {
        for (duration in listOf(Input.Value(3600L), Input.Value(Long.MAX_VALUE), Input.Missing, Input.Invalid, Input.Null)) {
            val result = append(usage = usage(window(duration = duration)))
            assertTrue(result.entry.windows.all { it.point == null })
            assertEquals(BaselineEligibility.WINDOW_UNAVAILABLE, five(result).baseline)
            assertEquals(if (duration is Input.Value) WindowKind.UNSUPPORTED else WindowKind.UNKNOWN, result.entry.slots.first().kind)
        }
    }

    @Test fun ambiguousCandidatesNeverChooseEitherPercentOrReset() {
        val first = append()
        val result = append(first.cursor, usage(window(), window(Input.Invalid), at.plusSeconds(60)), 61_000)
        assertEquals(SelectionState.AMBIGUOUS, five(result).selection)
        assertEquals(BaselineEligibility.AMBIGUOUS_WINDOW, five(result).baseline)
        assertEquals(ResetProvenance.AMBIGUOUS, five(result).reset.provenance)
        assertNull(five(result).reset.facts)
        assertNull(five(result).point)
        assertNull(five(result).percent)
        val recovered = append(result.cursor, usage(observedAt = at.plusSeconds(120)), 121_000)
        assertEquals(setOf(BreakReason.AMBIGUOUS_WINDOW), five(recovered).point!!.segment.breaks)
        assertNotEquals(five(first).point!!.segment.id, five(recovered).point!!.segment.id)
    }

    @Test fun malformedMissingRelativeOnlyAndDiscrepantResetRetainIsolatedActualPercent() {
        val inputs = listOf(
            window(absolute = Input.Invalid) to ResetProvenance.MALFORMED,
            window(absolute = Input.Null) to ResetProvenance.UNKNOWN,
            window(absolute = Input.Missing, relative = Input.Value(60L)) to ResetProvenance.RELATIVE_DERIVED,
            window(relative = Input.Value(59L)) to ResetProvenance.DISCREPANT,
        )
        for ((input, provenance) in inputs) {
            val first = append(usage = usage(input))
            val second = append(first.cursor, usage(input, observedAt = at.plusSeconds(60)), 61_000)
            assertEquals(provenance, five(first).reset.provenance)
            assertEquals(BigDecimal("12.375"), five(first).point!!.usedPercent)
            assertEquals(BaselineEligibility.UNKEYED_RESET, five(first).baseline)
            assertNull(five(first).point!!.segment.window)
            assertNull(five(second).point!!.segment.window)
            assertNotEquals(five(first).point!!.segment.id, five(second).point!!.segment.id)
        }
    }

    @Test fun absoluteResetIdentityIsExactAndRetainsIndependentMalformedRelativeSibling() {
        val source = usage(window(relative = Input.Invalid))
        val result = append(usage = source)
        assertEquals(SelectionState.MALFORMED, five(result).selection)
        assertEquals(ResetProvenance.ABSOLUTE, five(result).reset.provenance)
        assertEquals(Reason.WRONG_TYPE, five(result).reset.facts!!.relativeSeconds.reason)
        assertEquals(Instant.ofEpochSecond(1_800_003_600), five(result).point!!.segment.window!!.resetAt)
        assertEquals(BaselineEligibility.ELIGIBLE, five(result).baseline)
        val window = source.fiveHour.candidates.single()
        val nanoReset = window.copy(reset = window.reset.copy(absolute = Field(Knowledge.KNOWN, Instant.ofEpochSecond(1_800_003_600, 1))))
        val next = append(result.cursor, source.copy(observedAt = at.plusSeconds(60), fiveHour = WindowSelection(SelectionState.KNOWN, listOf(nanoReset))), 61_000)
        assertEquals(setOf(BreakReason.WINDOW_CHANGED), five(next).point!!.segment.breaks)
    }

    @Test fun unknownObservationClockPreservesKnowledgeWithoutTimestampedMeasurement() {
        val first = append()
        val unknown = append(first.cursor, usage(observedAt = null), 61_000)
        assertNull(unknown.entry.observedAt)
        assertNull(five(unknown).point)
        assertEquals(BigDecimal("12.375"), five(unknown).percent!!.value)
        assertEquals(BaselineEligibility.UNKNOWN_OBSERVATION_TIME, five(unknown).baseline)
        val later = append(unknown.cursor, usage(observedAt = at.plusSeconds(120)), 121_000)
        assertEquals(setOf(BreakReason.UNKNOWN_OBSERVATION_TIME), five(later).point!!.segment.breaks)
    }

    @Test fun backgroundAndReadErrorsAreNonMeasurementEventsAndBreakBothSeries() {
        for ((gap, reason) in listOf(HistoryGap.BACKGROUND to BreakReason.BACKGROUND, HistoryGap.READ_ERROR to BreakReason.READ_ERROR)) {
            val first = append(usage = usage(secondary = window(duration = Input.Value(604800L))))
            val status = WindowHistory.append(first.cursor, HistoryAdmission(partition, ObservationId(2), HistoryClock(epoch, 20_000), HistoryEvent.Gap(gap))) as HistoryReduction.Applied
            assertEquals(gap, status.entry.gap)
            assertNull(status.entry.observedAt)
            assertTrue(status.entry.windows.isEmpty())
            assertNull(status.entry.allowed)
            val later = append(status.cursor, usage(secondary = window(duration = Input.Value(604800L)), observedAt = at.plusSeconds(60)), 61_000)
            assertEquals(setOf(reason), five(later).point!!.segment.breaks)
            assertEquals(setOf(reason), weekly(later).point!!.segment.breaks)
        }
    }

    @Test fun fieldAndUnkeyedGapsDoNotEraseCorrectionConfidenceOrJoinRecovery() {
        val first = append()
        val corrected = append(first.cursor, usage(window(Input.Value(4)), observedAt = at.plusSeconds(60)), 61_000)
        val invalid = append(corrected.cursor, usage(window(absolute = Input.Invalid), observedAt = at.plusSeconds(90)), 91_000)
        val missing = append(invalid.cursor, usage(window(Input.Null), observedAt = at.plusSeconds(100)), 101_000)
        val resumed = append(missing.cursor, usage(window(Input.Value(5)), observedAt = at.plusSeconds(120)), 121_000)
        assertEquals(NominalStartConfidence.UNCERTAIN_CORRECTION, five(resumed).point!!.nominalStart)
        assertEquals(BaselineEligibility.UNCERTAIN_CORRECTION, five(resumed).baseline)
        assertEquals(setOf(BreakReason.FIELD_GAP), five(resumed).point!!.segment.breaks)
        assertNotEquals(five(corrected).point!!.segment.id, five(resumed).point!!.segment.id)
        val recoveredReset = append(invalid.cursor, usage(window(Input.Value(5)), observedAt = at.plusSeconds(120)), 121_000)
        assertEquals(setOf(BreakReason.UNKEYED_RESET), five(recoveredReset).point!!.segment.breaks)
        assertNotNull(five(recoveredReset).point!!.segment.window)
    }

    @Test fun clockBoundariesUseExactIntervalsAndDoNotCompareEpochs() {
        data class Case(val seconds: Long, val millis: Long?, val epoch: ClockEpoch, val expected: Set<BreakReason>)
        val cases = listOf(
            Case(120, 121_000, epoch, emptySet()),
            Case(121, 122_000, epoch, setOf(BreakReason.INTERVAL_EXCEEDED)),
            Case(60, 56_000, epoch, emptySet()),
            Case(60, 55_999, epoch, setOf(BreakReason.WALL_MONOTONIC_DIVERGENCE)),
            Case(60, 66_000, epoch, emptySet()),
            Case(60, 66_001, epoch, setOf(BreakReason.WALL_MONOTONIC_DIVERGENCE)),
            Case(0, 1000, epoch, setOf(BreakReason.NON_INCREASING_WALL_TIME, BreakReason.NON_INCREASING_MONOTONIC_TIME)),
            Case(-1, 2000, epoch, setOf(BreakReason.NON_INCREASING_WALL_TIME)),
            Case(60, 1000, epoch, setOf(BreakReason.NON_INCREASING_MONOTONIC_TIME, BreakReason.WALL_MONOTONIC_DIVERGENCE)),
            Case(60, 0, ClockEpoch(UUID(0, 3)), setOf(BreakReason.NEW_CLOCK_EPOCH)),
            Case(60, null, epoch, setOf(BreakReason.MONOTONIC_UNAVAILABLE)),
        )
        for (case in cases) {
            val first = append()
            val next = append(first.cursor, usage(observedAt = at.plusSeconds(case.seconds)), case.millis, case.epoch)
            val point = five(next).point!!
            if (case.expected.isEmpty()) assertSame(five(first).point!!.segment, point.segment)
            else {
                assertEquals(case.toString(), case.expected, point.segment.breaks)
                assertNotEquals(five(first).point!!.segment.id, point.segment.id)
            }
        }
    }

    @Test fun subsecondIntervalAndSkewOverBoundBreakWithoutRounding() {
        val first = append()
        val interval = append(first.cursor, usage(observedAt = at.plusSeconds(120).plusNanos(1)), 121_000)
        assertEquals(setOf(BreakReason.INTERVAL_EXCEEDED), five(interval).point!!.segment.breaks)
        val skew = append(first.cursor, usage(observedAt = at.plusSeconds(65).plusNanos(1)), 61_000)
        assertEquals(setOf(BreakReason.WALL_MONOTONIC_DIVERGENCE), five(skew).point!!.segment.breaks)
    }

    @Test fun missingOrExtremeMonotonicFactsNeverOverflowOrBecomeTrustworthyBaseline() {
        val first = append(monotonic = null)
        assertEquals(BaselineEligibility.CLOCK_UNVERIFIED, five(first).baseline)
        val known = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        assertEquals(setOf(BreakReason.MONOTONIC_UNAVAILABLE), five(known).point!!.segment.breaks)
        val extreme = append(append(monotonic = Long.MAX_VALUE).cursor, usage(observedAt = at.plusSeconds(60)), 0)
        assertEquals(setOf(BreakReason.NON_INCREASING_MONOTONIC_TIME, BreakReason.WALL_MONOTONIC_DIVERGENCE), five(extreme).point!!.segment.breaks)
        assertEquals(BaselineEligibility.CLOCK_DISCONTINUITY, five(extreme).baseline)
        val far = append(append().cursor, usage(observedAt = Instant.MAX), Long.MAX_VALUE)
        assertTrue(BreakReason.INTERVAL_EXCEEDED in five(far).point!!.segment.breaks)
        assertEquals(Instant.MAX, five(far).point!!.observedAt)
    }

    @Test fun nominalEligibilityHasInclusiveEndpointsWithoutCreatingPointsAtThem() {
        val cases = listOf(
            at.minusSeconds(14_401) to BaselineEligibility.BEFORE_NOMINAL_START,
            at.minusSeconds(14_400) to BaselineEligibility.ELIGIBLE,
            at.plusSeconds(3600) to BaselineEligibility.ELIGIBLE,
            at.plusSeconds(3601) to BaselineEligibility.AFTER_RESET,
        )
        for ((time, expected) in cases) {
            val result = append(usage = usage(observedAt = time))
            assertEquals(expected, five(result).baseline)
            assertEquals(time, five(result).point!!.observedAt)
            assertEquals(BigDecimal("12.375"), five(result).point!!.usedPercent)
            assertEquals(1L, result.entry.id.ordinal)
        }
    }

    @Test fun handcraftedA1BoundaryKnowledgeRemainsUnkeyedOrUnavailableWithoutGuessing() {
        val source = usage()
        val original = source.fiveHour.candidates.single()
        fun with(window: UsageWindow, state: SelectionState = SelectionState.KNOWN) = source.copy(fiveHour = WindowSelection(state, listOf(window)))
        val unsupported = original.copy(reset = original.reset.copy(absolute = Field(Knowledge.UNSUPPORTED, reason = Reason.UNSUPPORTED_FORMAT)))
        assertEquals(ResetProvenance.UNSUPPORTED, five(append(usage = with(unsupported))).reset.provenance)
        val relativeBad = original.copy(reset = original.reset.copy(absolute = Field(Knowledge.UNAVAILABLE), relativeDerived = Field(Knowledge.MALFORMED)))
        assertEquals(ResetProvenance.MALFORMED, five(append(usage = with(relativeBad))).reset.provenance)
        val relativeUnsupported = relativeBad.copy(reset = relativeBad.reset.copy(relativeDerived = Field(Knowledge.UNSUPPORTED)))
        assertEquals(ResetProvenance.UNSUPPORTED, five(append(usage = with(relativeUnsupported))).reset.provenance)
        assertNull(five(append(usage = with(original.copy(kind = WindowKind.WEEKLY)))).point)
        assertNull(five(append(usage = with(original.copy(durationSeconds = Field(Knowledge.KNOWN, 60L))))).point)
        assertNull(five(append(usage = with(original.copy(usedPercent = Field(Knowledge.KNOWN))))).point)
        assertNull(five(append(usage = source.copy(fiveHour = WindowSelection(SelectionState.KNOWN, emptyList())))).point)
        val overflow = original.copy(reset = original.reset.copy(absolute = Field(Knowledge.KNOWN, Instant.MIN)))
        assertEquals(BaselineEligibility.TIME_RANGE_OVERFLOW, five(append(usage = with(overflow))).baseline)
    }
}
