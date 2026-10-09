package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class HistoryTextPresentationTest {
    private val zone = ZoneId.of("UTC")

    @Test fun exactFactsRemainSeparateFromAnalyticalComparison() {
        val entry = SyntheticHistory.append().entry
        val detail = detail(entry)
        val five = detail.windows.first()
        assertEquals("12.375", five.facts.single { it.label == R.string.history_measured }.value)
        assertEquals("12.375", entry.windows.first().percent!!.value.toString())
        assertTrue(five.facts.any { it.label == R.string.history_delta && it.value != null })
        assertTrue(five.facts.any { it.state == NominalStartConfidence.NOMINAL_FULL_QUOTA })
        assertTrue(five.facts.any { it.state == BreakReason.FIRST_OBSERVATION })
        assertTrue(five.facts.any { it.state == ResetCause.UNKNOWN })
        assertNotNull(five.reset!!.absolute)
        assertTrue(detail.facts.any { it.label == R.string.history_allowed && it.value == "false" })
        assertTrue(detail.facts.any { it.label == R.string.history_limit && it.value == "true" })
    }

    @Test fun typedAbsenceAndAllResetSourcesSurviveWithoutInventedValues() {
        val usage = SyntheticHistory.usage(primary = SyntheticHistory.window(percent = Input.Null,
            absolute = Input.Value(1_800_003_600L), relative = Input.Value(99L)))
        val result = SyntheticHistory.append(usage = usage)
        val five = detail(result.entry).windows.first()
        assertTrue(five.facts.any { it.state == Knowledge.UNAVAILABLE })
        assertTrue(five.facts.any { it.state == Reason.PROVIDER_NULL })
        assertTrue(five.facts.any { it.state == BaselineEligibility.UNKNOWN_PERCENT })
        assertEquals("99", five.facts.single { it.label == R.string.history_reset_seconds }.value)
        assertNotNull(five.facts.single { it.label == R.string.history_reset_derived }.value)
        assertFalse(five.facts.any { it.label == R.string.history_delta })
        assertEquals(TimeState.DISCREPANT, five.reset!!.state)
    }

    @Test fun gapAndUnknownTimestampRemainDistinctFromZeroMeasurement() {
        val gap = (WindowHistory.append(HistoryCursor(SyntheticHistory.partition), HistoryAdmission(
            SyntheticHistory.partition, ObservationId(1), HistoryClock(SyntheticHistory.epoch, null),
            HistoryEvent.Gap(HistoryGap.READ_ERROR))) as HistoryReduction.Applied).entry
        val shown = detail(gap)
        assertTrue(shown.windows.isEmpty())
        assertTrue(shown.facts.any { it.state == HistoryGap.READ_ERROR })
        assertNull(shown.facts.single { it.label == R.string.history_observed }.value)
        assertNull(shown.facts.single { it.label == R.string.history_allowed }.value)
    }

    @Test fun chunkedExactDataIsNeverRoundedExpandedOrOmitted() {
        for (text in listOf("1E-2147483647", "0E+2147483647", "9".repeat(4096), "")) {
            val parts = HistoryTextPresentation.chunks(text)
            assertEquals(text, parts.joinToString(""))
            assertTrue(parts.all { it.length <= HistoryTextPresentation.CHUNK_CHARACTERS })
        }
    }

    @Test fun everyTypedLabelUsesAResourceAndInvalidFactStatesRemainTyped() {
        val states: List<Enum<*>> = HistoryReadiness.entries + HistoryPlotContent.entries + WindowKind.entries +
            Knowledge.entries + Reason.entries + SelectionState.entries + Slot.entries + ResetProvenance.entries +
            NominalStartConfidence.entries + BaselineEligibility.entries + ResetCause.entries + BreakReason.entries +
            HistoryGap.entries + HistoryTruncation.entries + HistoryRecorderProblem.entries + HistoryUnavailable.entries +
            io.github.leugenea.codexbarmobile.transport.ReadError.entries + DistributionUnavailable.InvalidFact.entries
        assertEquals(states.toSet(), historyStateResources.keys)
        states.forEach { assertTrue(historyStateResource(it) != 0) }
        val original = SyntheticHistory.append().entry.windows.first()
        val changed = original.copy(duration = Field(Knowledge.KNOWN, 1L))
        val entry = SyntheticHistory.append().entry
        val replaced = HistoryEntry(entry.partition, entry.id, entry.clock, entry.observedAt, entry.gap,
            listOf(changed), entry.slots, entry.allowed, entry.limitReached)
        assertTrue(detail(replaced).windows.single().facts.any { it.state == DistributionUnavailable.InvalidFact.DURATION })
    }

    private fun detail(entry: HistoryEntry): HistoryTextDetail = HistoryTextPresentation.detail(
        HistoryGraphEntry(entry, entry.windows.map(::HistoryGraphWindow)), SyntheticHistory.at, zone, Locale.US)
}
