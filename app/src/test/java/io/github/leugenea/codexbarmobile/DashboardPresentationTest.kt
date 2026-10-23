package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Existing normalized/plot facts are the oracle; these adapters never infer quota/count/time. */
class DashboardPresentationTest {
    @Test fun reportedInventoryCountNeverFallsBackToSummaryRowsOrZero() {
        val unknowns = listOf<Field<Long>?>(null, Field(Knowledge.UNAVAILABLE), Field(Knowledge.MALFORMED), Field(Knowledge.UNSUPPORTED))
        unknowns.forEach { count ->
            val facts = PresentedEntitlements(EntitlementState.UNKNOWN, EntitlementSnapshotState.CURRENT_GENERATION,
                summaryAvailableCount = Field(Knowledge.KNOWN, 7L), reportedAvailableCount = count, inventoryRowCount = 3)
            assertNull(DashboardPresentation.inventoryCount(facts))
        }
        for (count in listOf(0L, 9L, Long.MAX_VALUE)) {
            val facts = PresentedEntitlements(EntitlementState.DISCREPANT, EntitlementSnapshotState.CURRENT_GENERATION,
                summaryAvailableCount = Field(Knowledge.KNOWN, 7L), reportedAvailableCount = Field(Knowledge.KNOWN, count), inventoryRowCount = 3)
            assertEquals(count, DashboardPresentation.inventoryCount(facts))
        }
    }

    @Test fun compactMissingChartAndPhaseActionsKeepTypedErrorsAndSinglePointTruth() {
        val absent = HistoryGraphSnapshot()
        assertFalse(DashboardPresentation.hasChart(HistoryPlotInputs.project(absent).fiveHour))
        assertEquals(R.string.dashboard_no_history, DashboardPresentation.noInformation(absent))
        assertEquals(R.string.dashboard_no_history_loading, DashboardPresentation.noInformation(HistoryGraphSnapshot(HistoryReadiness.LOADING)))
        val error = HistoryGraphSnapshot(HistoryReadiness.ERROR, problem = HistoryRecorderProblem.READ_FAILURE, lostSamples = 1)
        assertEquals(R.string.dashboard_no_history_error, DashboardPresentation.noInformation(error))
        assertEquals(R.string.dashboard_no_history_malformed, DashboardPresentation.noInformation(absent, UsageWindowStatus.AVAILABLE, malformed = true))
        assertEquals(R.string.dashboard_no_history_malformed_error, DashboardPresentation.noInformation(error, UsageWindowStatus.AVAILABLE, malformed = true))
        assertEquals(R.string.dashboard_no_history_malformed_loading, DashboardPresentation.noInformation(HistoryGraphSnapshot(HistoryReadiness.LOADING), UsageWindowStatus.AVAILABLE, malformed = true))
        assertEquals(R.string.dashboard_window_malformed, DashboardPresentation.windowWarning(UsageWindowStatus.AVAILABLE, malformed = true))
        assertNull(DashboardPresentation.windowWarning(UsageWindowStatus.AVAILABLE))
        assertTrue(DashboardPresentation.loginVisible(ConnectionPhase.REAUTH_REQUIRED))
        assertFalse(DashboardPresentation.loginVisible(ConnectionPhase.RESTORED))
        assertTrue(DashboardPresentation.cancelVisible(ConnectionPhase.AUTHENTICATING))
        assertFalse(DashboardPresentation.cancelVisible(ConnectionPhase.SIGNING_OUT))
    }

    @Test fun compactChartEquivalentUsesOriginalExactFactsAndIndependentReferences() {
        val entry = SyntheticHistory.append().entry
        val query = HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR)
        val page = HistoryReadSnapshot(query.storage(entry.partition), listOf(entry), entry.id, false)
        val source = HistoryGraphSnapshot(HistoryReadiness.READY, query = query, storage = page)
        val series = HistoryPlotInputs.project(source).fiveHour
        val drawing = historyChartDrawing(series)
        assertTrue(DashboardPresentation.hasChart(series))
        assertEquals(1, drawing.markers.size); assertTrue(drawing.measured.isEmpty()); assertEquals(1, drawing.nominal.size)
        val detail = HistoryTextPresentation.detail(source.entries.single(), SyntheticHistory.at, java.time.ZoneOffset.UTC, java.util.Locale.US)
        assertEquals("12.375", detail.windows.single().facts.single { it.label == R.string.history_measured }.value)
        assertEquals("-67.625", detail.windows.single().facts.single { it.label == R.string.history_delta }.value)
        assertSame(entry.windows.first().point, series.samples.single().measured!!.source)
    }

    @Test fun paceLineUsesOverReserveAndOnPaceWithoutChangingExactComparison() {
        val source = BigDecimal("-67.625")
        assertEquals(PresentedPace(R.string.dashboard_reserve, "67.6"), DashboardPresentation.pace(source, Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "9.3"), DashboardPresentation.pace(BigDecimal("9.3333333333333333333333"), Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_on_pace), DashboardPresentation.pace(BigDecimal("0E-32"), Locale.US))
        assertEquals(BigDecimal("-67.625"), source)
    }

    @Test fun paceLineHalfTenthBoundariesRoundAwayFromZero() {
        for ((value, resource) in listOf("0.05" to R.string.dashboard_over_pace, "-0.05" to R.string.dashboard_reserve)) {
            assertEquals(PresentedPace(resource, "0.1"), DashboardPresentation.pace(BigDecimal(value), Locale.US))
        }
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "1.3"), DashboardPresentation.pace(BigDecimal("1.25"), Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_reserve, "1.3"), DashboardPresentation.pace(BigDecimal("-1.25"), Locale.US))
    }

    @Test fun paceLineNearZeroAndAdjacentBoundaryValuesNeverShowSignedZero() {
        for (value in listOf("0", "0.049999999999", "-0.049999999999", "0.0001", "-0.0001")) {
            assertEquals(value, PresentedPace(R.string.dashboard_on_pace), DashboardPresentation.pace(BigDecimal(value), Locale.US))
        }
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "0.1"), DashboardPresentation.pace(BigDecimal("0.050000000001"), Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_reserve, "0.1"), DashboardPresentation.pace(BigDecimal("-0.050000000001"), Locale.US))
    }

    @Test fun paceLineKeepsOneDecimalAndLocaleSeparator() {
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "10.0"), DashboardPresentation.pace(BigDecimal("9.99"), Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_reserve, "12,4"), DashboardPresentation.pace(BigDecimal("-12.35"), Locale.GERMANY))
    }

    @Test fun chartTimeLabelsUseYearlessLocalePatternsAndContainingHour() {
        val at = Instant.parse("2027-01-15T08:37:12.123456789Z")
        assertEquals("Jan 15, 08:00", TimePresentation.chartTime(at, ZoneOffset.UTC, Locale.US, "MMM d, HH:mm"))
        assertEquals("15. Jan., 09:00", TimePresentation.chartTime(at, ZoneId.of("Europe/Berlin"), Locale.GERMANY, "d. MMM, HH:mm"))
        assertEquals("Jan 15, 00:00", TimePresentation.chartTime(at, ZoneId.of("America/Los_Angeles"), Locale.US, "MMM d, HH:mm"))
        assertEquals(Instant.parse("2027-01-15T08:37:12.123456789Z"), at)
    }

    @Test fun chartTimeLabelsRetainFullAndPartialRepeatedHourOffsets() {
        val ny = ZoneId.of("America/New_York")
        assertEquals("Nov 1, 01:00 UTC-04:00", TimePresentation.chartTime(Instant.parse("2026-11-01T05:30:00Z"), ny, Locale.US, "MMM d, HH:mm"))
        assertEquals("Nov 1, 01:00 UTC-05:00", TimePresentation.chartTime(Instant.parse("2026-11-01T06:30:00Z"), ny, Locale.US, "MMM d, HH:mm"))
        assertEquals("Apr 5, 01:00 UTC+11:00", TimePresentation.chartTime(Instant.parse("2026-04-04T14:15:00Z"),
            ZoneId.of("Australia/Lord_Howe"), Locale.US, "MMM d, HH:mm"))
    }

    @Test fun chartTimeLabelsKeepUnrepresentableInstantsUnknown() {
        assertNull(TimePresentation.chartTime(Instant.MAX, ZoneOffset.UTC, Locale.US, "MMM d, HH:mm"))
        assertNull(TimePresentation.chartTime(Instant.MIN, ZoneId.of("America/Los_Angeles"), Locale.US, "MMM d, HH:mm"))
    }
}
