package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test

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
        assertTrue(DashboardPresentation.incomplete(error))
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
}
