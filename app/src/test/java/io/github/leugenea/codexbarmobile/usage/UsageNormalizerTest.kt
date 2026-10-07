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
        val conflict = window(normalize(M0Fixtures.usage(M0Fixtures.window(absolute = value(1791154801), relative = value(59))))).reset
        assertTrue(conflict.discrepant)
        assertEquals(now.plusSeconds(1), conflict.absolute.value)
        assertEquals(now.plusSeconds(59), conflict.relativeDerived.value)
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
