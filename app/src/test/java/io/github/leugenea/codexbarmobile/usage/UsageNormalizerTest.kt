package io.github.leugenea.codexbarmobile.usage

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class UsageNormalizerTest {
    private val now = M0Fixtures.clock
    private fun value(value: Any) = M0Fixtures.value(value)
    private fun normalize(input: UsageInput, observed: Instant? = now, evaluated: Instant? = now) =
        UsageNormalizer.normalize(input, observed, evaluated)
    private fun window(result: UsageObservation) = result.fiveHour.candidates.single()

    @Test fun m0SyntheticUsageFactsRemainIndependent() {
        val cases = M0Fixtures.cases()
        assertEquals(17, cases.size)
        assertEquals(17, cases.map { it.name }.distinct().size)
        for (case in cases) {
            val result = normalize(case.input, evaluated = case.evaluatedAt)
            assertEquals(case.name, case.fivePercent, result.fiveHour.candidates.singleOrNull()?.usedPercent?.value?.toPlainString())
            assertEquals(case.name, case.weeklyPercent, result.weekly.candidates.singleOrNull()?.usedPercent?.value?.toPlainString())
            assertEquals(case.name, case.allowed, result.allowed.value)
            assertEquals(case.name, case.reached, result.limitReached.value)
        }
    }

    @Test fun weeklyMockUsesDurationNotSlotOrPlanName() {
        val result = normalize(M0Fixtures.weeklyMock(), evaluated = Instant.ofEpochSecond(1775000000))
        assertEquals(SelectionState.UNAVAILABLE, result.fiveHour.state)
        assertEquals(SelectionState.KNOWN, result.weekly.state)
        assertEquals(Slot.PRIMARY, result.weekly.candidates.single().slot)
        assertEquals(BigDecimal.ZERO, result.weekly.candidates.single().usedPercent.value)
        val swapped = normalize(M0Fixtures.usage(Input.Null, M0Fixtures.weeklyMock().primary))
        assertEquals(Slot.SECONDARY, swapped.weekly.candidates.single().slot)
        assertEquals(Reason.PROVIDER_NULL, swapped.slots.first().window.reason)
        assertEquals("future_plan", normalize(UsageInput(planType = value("future_plan"))).planType.value)
    }

    @Test fun missingNullAndInvalidSlotsAreNotInventedZeroWindows() {
        val result = normalize(M0Fixtures.usage(Input.Missing, Input.Null))
        assertEquals(listOf(Slot.PRIMARY, Slot.SECONDARY), result.slots.map { it.slot })
        assertEquals(Reason.MISSING, result.slots[0].window.reason)
        assertEquals(Reason.PROVIDER_NULL, result.slots[1].window.reason)
        assertTrue(result.fiveHour.candidates.isEmpty())
        assertTrue(result.weekly.candidates.isEmpty())
        assertEquals(Knowledge.MALFORMED, normalize(M0Fixtures.usage(Input.Invalid)).slots[0].window.knowledge)
        val missingPercent = normalize(UsageInput(primary = Input.Value(WindowInput(value(18000)))))
        assertEquals(Knowledge.UNAVAILABLE, window(missingPercent).usedPercent.knowledge)
        assertNull(window(missingPercent).usedPercent.value)
        val nullPercent = normalize(UsageInput(primary = Input.Value(WindowInput(value(18000), Input.Null))))
        assertEquals(Reason.PROVIDER_NULL, window(nullPercent).usedPercent.reason)
    }

    @Test fun duplicateRecognizedDurationIsAmbiguousEvenWithMalformedCandidate() {
        for (seconds in listOf(18000, 604800)) {
            val result = normalize(M0Fixtures.usage(M0Fixtures.window(8, seconds), M0Fixtures.window("bad", seconds)))
            val selection = if (seconds == 18000) result.fiveHour else result.weekly
            assertEquals(SelectionState.AMBIGUOUS, selection.state)
            assertEquals(2, selection.candidates.size)
            assertEquals(listOf(Slot.PRIMARY, Slot.SECONDARY), selection.candidates.map { it.slot })
        }
    }

    @Test fun fractionalPercentPreservesDecimalPrecisionAndDoesNotAuthorize() {
        for (percent in listOf(12.375, 12.375f, BigDecimal("12.37500000000000000001"))) {
            val input = M0Fixtures.usage(M0Fixtures.window(percent)).copy(allowed = value(false), limitReached = value(false))
            val result = normalize(input)
            assertEquals(BigDecimal(percent.toString()), window(result).usedPercent.value)
            assertEquals(false, result.allowed.value)
            assertEquals(false, result.limitReached.value)
        }
        for (percent in listOf(0.toByte(), 100.toShort(), 100L)) {
            assertEquals(BigDecimal(percent.toString()), window(normalize(M0Fixtures.usage(M0Fixtures.window(percent)))).usedPercent.value)
        }
    }

    @Test fun wrongTypesNonfiniteAndOutOfRangePercentPreserveValidSibling() {
        val bad = listOf(true, "42", -0.1, 100.1, Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Any())
        for (percent in bad) {
            val result = normalize(M0Fixtures.usage(M0Fixtures.window(percent), M0Fixtures.window(18, 604800)))
            assertEquals(SelectionState.MALFORMED, result.fiveHour.state)
            assertNull(window(result).usedPercent.value)
            assertEquals(SelectionState.KNOWN, result.weekly.state)
            assertEquals(BigDecimal(18), result.weekly.candidates.single().usedPercent.value)
        }
    }

    @Test fun malformedAndUnsupportedDurationsNeverGetSlotBasedIdentity() {
        for (seconds in listOf(true, "18000", -1, 0, 18000.5, Double.NaN, Long.MAX_VALUE, BigDecimal("9223372036854775808"))) {
            val result = normalize(M0Fixtures.usage(M0Fixtures.window(seconds = seconds), M0Fixtures.window(9, 604800)))
            assertEquals(SelectionState.UNAVAILABLE, result.fiveHour.state)
            assertEquals(BigDecimal(9), result.weekly.candidates.single().usedPercent.value)
            val duration = result.slots[0].window.value!!.durationSeconds
            if (seconds == Long.MAX_VALUE) {
                assertEquals(WindowKind.UNSUPPORTED, result.slots[0].window.value!!.kind)
            } else assertEquals(Knowledge.MALFORMED, duration.knowledge)
        }
        val future = normalize(M0Fixtures.usage(M0Fixtures.window(seconds = 3600)))
        assertEquals(WindowKind.UNSUPPORTED, future.slots[0].window.value!!.kind)
        val missing = normalize(UsageInput(primary = Input.Value(WindowInput())))
        assertEquals(WindowKind.UNKNOWN, missing.slots[0].window.value!!.kind)
    }

    @Test fun flagsPlanAndSummaryValidateIndependently() {
        val result = normalize(UsageInput(allowed = value(1), limitReached = value("true"), planType = value(false), bankedAvailableCount = value(true)))
        assertEquals(Knowledge.MALFORMED, result.allowed.knowledge)
        assertEquals(Knowledge.MALFORMED, result.limitReached.knowledge)
        assertEquals(Knowledge.MALFORMED, result.planType.knowledge)
        assertEquals(Knowledge.MALFORMED, result.bankedAvailableCount.knowledge)
        val absent = normalize(UsageInput(allowed = Input.Null, limitReached = Input.Invalid, planType = Input.Null))
        assertEquals(Reason.PROVIDER_NULL, absent.allowed.reason)
        assertEquals(Reason.WRONG_TYPE, absent.limitReached.reason)
        assertNull(absent.planType.value)
    }

    @Test fun absoluteAndRelativeResetRemainSeparateAndConflictVisible() {
        val result = normalize(M0Fixtures.usage(M0Fixtures.window(absolute = value(1791154859), relative = value(59))))
        val reset = window(result).reset
        assertEquals(now.plusSeconds(59), reset.absolute.value)
        assertEquals(59L, reset.relativeSeconds.value)
        assertEquals(now.plusSeconds(59), reset.relativeDerived.value)
        assertFalse(reset.discrepant)
        assertEquals(false, reset.due)
        val conflict = window(normalize(M0Fixtures.usage(M0Fixtures.window(absolute = value(1791154801), relative = value(62))))).reset
        assertTrue(conflict.discrepant)
        assertEquals(now.plusSeconds(1), conflict.absolute.value)
        assertEquals(now.plusSeconds(62), conflict.relativeDerived.value)
        assertNull(conflict.due)
    }

    @Test fun relativeOnlyRequiresUsageObservationNotEvaluationOrInventoryClock() {
        val input = M0Fixtures.usage(M0Fixtures.window(relative = value(59)))
        val unknown = normalize(input, observed = null)
        assertNull(unknown.observedAt)
        assertEquals(Reason.CLOCK_REQUIRED, window(unknown).reset.relativeDerived.reason)
        assertNull(window(unknown).reset.absolute.value)
        assertNull(window(unknown).reset.due)
        val known = window(normalize(input)).reset
        assertEquals(now.plusSeconds(59), known.relativeDerived.value)
        assertNull(known.absolute.value)
        assertNull(window(normalize(input, evaluated = null)).reset.due)
        assertNull(UsageNormalizer.normalize(input).observedAt)
    }

    @Test fun periodicPrecisionComparesFullInstantsInBothDirectionsAndSlotsWithoutChangingFacts() {
        val absolute = Instant.ofEpochSecond(1791331200)
        val cases = listOf(
            Triple(now, 176400L, false),
            Triple(now.plusNanos(250000000), 176400L, false),
            Triple(now.plusNanos(250000000), 176399L, false),
            Triple(now, 176459L, false),
            Triple(now, 176341L, false),
            Triple(now, 176460L, false),
            Triple(now, 176340L, false),
            Triple(now.plusNanos(1), 176460L, true),
            Triple(now.minusNanos(1), 176340L, true),
            Triple(now, 176461L, true),
            Triple(now, 176339L, true),
        )
        for (seconds in listOf(18000, 604800)) for (slot in Slot.entries) for ((observed, relative, conflict) in cases) {
            val input = M0Fixtures.window(BigDecimal("12.345678901234567890"), seconds,
                value(1791331200), value(relative))
            val result = normalize(if (slot == Slot.PRIMARY) M0Fixtures.usage(input, Input.Null)
                else M0Fixtures.usage(Input.Null, input), observed, observed)
            val selected = if (seconds == 18000) result.fiveHour else result.weekly
            val window = selected.candidates.single()
            val context = "duration=$seconds $slot observed=$observed relative=$relative"
            assertEquals(context, SelectionState.KNOWN, selected.state)
            assertEquals(context, slot, window.slot)
            assertEquals(context, observed, result.observedAt)
            assertEquals(context, BigDecimal("12.345678901234567890"), window.usedPercent.value)
            assertEquals(context, Field(Knowledge.KNOWN, absolute), window.reset.absolute)
            assertEquals(context, Field(Knowledge.KNOWN, relative), window.reset.relativeSeconds)
            assertEquals(context, Field(Knowledge.KNOWN, observed.plusSeconds(relative)), window.reset.relativeDerived)
            assertEquals(context, conflict, window.reset.discrepant)
            assertEquals(context, if (conflict) null else false, window.reset.due)
            assertEquals(context, SelectionState.UNAVAILABLE, (if (seconds == 18000) result.weekly else result.fiveHour).state)
        }
    }

    @Test fun resetPrecisionAlsoAppliesToUnsupportedAndUnusableDurations() {
        val durations = listOf(value(3600), value(604799), value(604801),
            Input.Missing, Input.Null, Input.Invalid, value("604800"), value(true),
            value(604800.5), value(-604800))
        val cases = listOf(0L to false, 1L to false, -1L to false,
            60_000_000_000L to false, -60_000_000_000L to false,
            60_000_000_001L to true, -60_000_000_001L to true)
        for (duration in durations) for ((nanos, conflict) in cases) {
            val result = normalize(UsageInput(primary = Input.Value(WindowInput(
                duration, value(22), value(1791331200), value(176400)))), now.plusNanos(nanos), now)
            val reset = result.slots.first().window.value!!.reset
            val context = "duration=$duration nanos=$nanos"
            assertEquals(context, conflict, reset.discrepant)
            assertEquals(context, if (conflict) null else false, reset.due)
            assertEquals(context, Instant.ofEpochSecond(1791331200), reset.absolute.value)
            assertEquals(context, now.plusNanos(nanos).plusSeconds(176400), reset.relativeDerived.value)
        }
    }

    @Test fun weeklyPrecisionPreservesSingleSourceBadFieldsAndUnknownClocks() {
        fun weekly(absolute: Input<Any>, relative: Input<Any>, observed: Instant? = now,
            evaluated: Instant? = now) = normalize(M0Fixtures.usage(M0Fixtures.window(
                22, 604800, absolute, relative)), observed, evaluated).weekly.candidates.single()
        val absoluteOnly = weekly(value(1791331200), Input.Missing, now.plusNanos(1))
        assertFalse(absoluteOnly.reset.discrepant)
        assertEquals(Reason.MISSING, absoluteOnly.reset.relativeSeconds.reason)
        assertEquals(Instant.ofEpochSecond(1791331200), absoluteOnly.reset.absolute.value)
        val relativeOnly = weekly(Input.Null, value(176400), now.plusNanos(1))
        assertEquals(Reason.PROVIDER_NULL, relativeOnly.reset.absolute.reason)
        assertEquals(Instant.ofEpochSecond(1791331200, 1), relativeOnly.reset.relativeDerived.value)
        assertFalse(relativeOnly.reset.discrepant)
        for (observed in listOf(null, Instant.EPOCH, Instant.ofEpochSecond(-1))) {
            val unknown = weekly(Input.Missing, value(176400), observed)
            assertEquals(Reason.CLOCK_REQUIRED, unknown.reset.relativeDerived.reason)
            assertNull(unknown.reset.due)
            val knownAbsolute = weekly(value(1791331200), value(176400), observed)
            assertEquals(Reason.CLOCK_REQUIRED, knownAbsolute.reset.relativeDerived.reason)
            assertEquals(Instant.ofEpochSecond(1791331200), knownAbsolute.reset.absolute.value)
            assertFalse(knownAbsolute.reset.discrepant)
        }
        for (bad in listOf(Input.Null, Input.Invalid, value(false), value("176400"), value(-1), value(0.5))) {
            val reset = weekly(value(1791331200), bad).reset
            assertNotEquals(Knowledge.KNOWN, reset.relativeSeconds.knowledge)
            assertFalse(reset.discrepant)
            assertEquals(Instant.ofEpochSecond(1791331200), reset.absolute.value)
        }
        for (bad in listOf(Input.Missing, Input.Null, Input.Invalid, value(false), value("1791331200"), value(0))) {
            val reset = weekly(bad, value(176400)).reset
            assertNotEquals(Knowledge.KNOWN, reset.absolute.knowledge)
            assertFalse(reset.discrepant)
            assertEquals(Instant.ofEpochSecond(1791331200), reset.relativeDerived.value)
        }
        val unknownEvaluation = weekly(value(1791331200), value(176400), evaluated = null)
        assertFalse(unknownEvaluation.reset.discrepant)
        assertNull(unknownEvaluation.reset.due)
        val elapsed = weekly(value(1791154800), value(59))
        assertFalse(elapsed.reset.discrepant)
        assertEquals(true, elapsed.reset.due) // Original absolute is due although derived is still future.
        assertEquals(BigDecimal(22), elapsed.usedPercent.value)
    }

    @Test fun weeklyPrecisionHandlesLargeInstantDifferencesAndDerivationOverflow() {
        val input = M0Fixtures.usage(M0Fixtures.window(22, 604800, value(1), value(0)))
        val distant = normalize(input, observed = Instant.MAX, evaluated = null).weekly.candidates.single().reset
        assertEquals(Instant.MAX, distant.relativeDerived.value)
        assertTrue(distant.discrepant) // Full Duration is safe even when nanosecond scalars would overflow.
        assertNull(distant.due)
        val overflow = normalize(input.copy(primary = M0Fixtures.window(22, 604800, value(1), value(1))),
            observed = Instant.MAX, evaluated = null).weekly.candidates.single().reset
        assertEquals(Knowledge.MALFORMED, overflow.relativeDerived.knowledge)
        assertFalse(overflow.discrepant)
        assertEquals(Instant.ofEpochSecond(1), overflow.absolute.value)
        assertNull(overflow.due)
    }

    @Test fun resetBoundaryRetainsPercentAndDoesNotRollInstantForward() {
        val input = M0Fixtures.usage(M0Fixtures.window(83, absolute = value(now.epochSecond)))
        val at = window(normalize(input))
        val later = window(normalize(input, evaluated = now.plusSeconds(604800)))
        assertEquals(true, at.reset.due)
        assertEquals(at, later)
        assertEquals(BigDecimal(83), later.usedPercent.value)
        val zeroRelative = window(normalize(M0Fixtures.usage(M0Fixtures.window(relative = value(0))))).reset
        assertEquals(true, zeroRelative.due)
        assertEquals(now, zeroRelative.relativeDerived.value)
    }

    @Test fun resetTypesRangesAndInstantOverflowAreCategorical() {
        for (bad in listOf(true, "1", -1, 0, 0.5, Double.NaN, Long.MAX_VALUE)) {
            val result = normalize(M0Fixtures.usage(M0Fixtures.window(absolute = value(bad))))
            assertEquals(SelectionState.MALFORMED, result.fiveHour.state)
            assertNull(window(result).reset.absolute.value)
            assertEquals(BigDecimal(22), window(result).usedPercent.value)
        }
        for (bad in listOf(false, "0", -1, 0.5, Long.MAX_VALUE)) {
            val result = normalize(M0Fixtures.usage(M0Fixtures.window(relative = value(bad))))
            assertEquals(SelectionState.MALFORMED, result.fiveHour.state)
        }
        val overflow = window(normalize(M0Fixtures.usage(M0Fixtures.window(relative = value(1))), observed = Instant.MAX)).reset
        assertEquals(Knowledge.MALFORMED, overflow.relativeDerived.knowledge)
        val nullable = window(normalize(M0Fixtures.usage(M0Fixtures.window(absolute = Input.Null, relative = Input.Null)))).reset
        assertEquals(Reason.PROVIDER_NULL, nullable.absolute.reason)
        assertEquals(Reason.PROVIDER_NULL, nullable.relativeSeconds.reason)
        assertNull(nullable.due)
    }

    @Test fun firstPositiveResetEpochAndZeroRelativeSecondsRemainKnown() {
        val first = Instant.ofEpochSecond(1, 1000)
        val input = M0Fixtures.usage(M0Fixtures.window(absolute = value(1), relative = value(0)))
        val result = normalize(input, observed = first, evaluated = first)
        val reset = window(result).reset
        assertEquals(first, result.observedAt)
        assertEquals(Instant.ofEpochSecond(1), reset.absolute.value)
        assertEquals(Knowledge.KNOWN, reset.absolute.knowledge)
        assertEquals(0L, reset.relativeSeconds.value)
        assertEquals(first, reset.relativeDerived.value)
        assertFalse(reset.discrepant)
        assertEquals(true, reset.due)
    }

    @Test fun invalidObservationClocksCannotAuthorizeRelativeResetDerivation() {
        val input = M0Fixtures.usage(M0Fixtures.window(relative = value(2)))
        for (clock in listOf(Instant.ofEpochSecond(-1), Instant.EPOCH, Instant.ofEpochSecond(0, 1000))) {
            val result = normalize(input, observed = clock)
            val reset = window(result).reset
            assertNull(result.observedAt)
            assertEquals(Reason.CLOCK_REQUIRED, reset.relativeDerived.reason)
            assertNull(reset.relativeDerived.value)
            assertNull(reset.due)
            assertEquals(2L, reset.relativeSeconds.value)
            assertEquals(BigDecimal(22), window(result).usedPercent.value)
        }
    }

    @Test fun invalidEvaluationClocksCannotAuthorizeResetDue() {
        val input = M0Fixtures.usage(M0Fixtures.window(absolute = value(1)))
        for (clock in listOf(Instant.ofEpochSecond(-1), Instant.EPOCH, Instant.ofEpochSecond(0, 1000))) {
            val result = normalize(input, evaluated = clock)
            val reset = window(result).reset
            assertEquals(now, result.observedAt)
            assertEquals(Instant.ofEpochSecond(1), reset.absolute.value)
            assertNull(reset.due)
            assertEquals(BigDecimal(22), window(result).usedPercent.value)
        }
        assertEquals(true, window(normalize(input, evaluated = Instant.ofEpochSecond(1))).reset.due)
    }

    @Test fun ownerReceiptKeepsUnknownObservationClockAndNumericFacts() {
        val input = UsageInput(
            primary = M0Fixtures.window(5, 604800, value(1791756793), value(488197)), secondary = Input.Null,
            allowed = value(true), limitReached = value(false), bankedAvailableCount = value(2),
        )
        val result = UsageNormalizer.normalize(input)
        val weekly = result.weekly.candidates.single()
        assertNull(result.observedAt)
        assertEquals(Instant.ofEpochSecond(1791756793), weekly.reset.absolute.value)
        assertEquals(488197L, weekly.reset.relativeSeconds.value)
        assertEquals(Reason.CLOCK_REQUIRED, weekly.reset.relativeDerived.reason)
        assertEquals(2L, result.bankedAvailableCount.value)
        assertEquals(SelectionState.UNAVAILABLE, result.fiveHour.state)
    }
}
