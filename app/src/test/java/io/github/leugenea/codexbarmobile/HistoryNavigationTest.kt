package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.Assert.*
import org.junit.Test

class HistoryNavigationTest {
    @Test fun firstAndNextPagesUseOnlyTheBoundedExclusiveStorageCursor() {
        val navigation = HistoryNavigation()
        assertEquals(HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR), navigation.query)
        val first = source(navigation.query, more = true)
        val next = navigation.next(first)
        assertEquals(ObservationId(1), next.after)
        assertEquals(32, next.query.limit)
        assertEquals(WindowKind.FIVE_HOUR, next.kind)
        assertEquals(navigation, next.copy(after = null))
        assertSame(navigation, navigation.next(source(navigation.query, more = false)))
    }

    @Test fun selectingAnotherKindPreservesStorageCursorAndDoesNotAccumulatePages() {
        val navigation = HistoryNavigation(after = ObservationId(Long.MAX_VALUE))
        val weekly = navigation.copy(kind = WindowKind.WEEKLY)
        assertEquals(ObservationId(Long.MAX_VALUE), weekly.query.after)
        assertEquals(WindowKind.WEEKLY, weekly.query.kind)
        assertNull(weekly.query.window)
        assertEquals(HistoryReadQuery(SyntheticHistory.partition, 32, ObservationId(Long.MAX_VALUE)),
            weekly.query.storage(SyntheticHistory.partition))
    }

    @Test fun filteredPageWithNoMeasuredPointsStillAdvancesByAdmittedOrdinals() {
        val navigation = HistoryNavigation(kind = WindowKind.WEEKLY)
        val page = source(navigation.query, more = true)
        assertTrue(page.points.isEmpty())
        assertEquals(1, page.entries.size)
        assertTrue(page.hasMore)
        val next = navigation.next(page)
        assertEquals(ObservationId(1), next.after)
        assertEquals(WindowKind.WEEKLY, next.kind)
        assertEquals(32, next.query.limit)
    }

    @Test fun supersededQueryMissingCursorAndNonadvancingCursorCannotNavigate() {
        val navigation = HistoryNavigation()
        assertSame(navigation, navigation.next(source(HistoryGraphQuery(kind = WindowKind.WEEKLY), more = true)))
        val empty = HistoryReadSnapshot(navigation.query.storage(SyntheticHistory.partition), emptyList(), ObservationId(5), true)
        assertSame(navigation, navigation.next(HistoryGraphSnapshot(query = navigation.query, storage = empty)))
        val later = navigation.copy(after = ObservationId(2))
        assertSame(later, later.next(source(later.query, more = true)))
    }

    @Test fun onlyMatchingCurrentRuntimeGenerationAndSelectionExposeDetachedFacts() {
        val navigation = HistoryNavigation()
        val source = source(navigation.query, more = true)
        assertSame(source, historyForDisplay(source, source.generation, navigation.query))
        val oldGeneration = historyForDisplay(source, HistoryGeneration(), navigation.query)
        assertHidden(oldGeneration, HistoryReadiness.LOADING)
        val changedQuery = historyForDisplay(source, source.generation, navigation.copy(kind = WindowKind.WEEKLY).query)
        assertHidden(changedQuery, HistoryReadiness.LOADING)
        assertEquals(WindowKind.WEEKLY, changedQuery.query.kind)
    }

    @Test fun retirementRemovesEveryMeasurementAndIndependentEndpointClock() {
        val navigation = HistoryNavigation()
        val old = source(navigation.query, more = true)
        val hidden = historyForDisplay(old, null, navigation.query)
        assertHidden(hidden, HistoryReadiness.UNAVAILABLE)
        assertEquals(HistoryLiveMetadata(), hidden.live)
        assertNull(hidden.partition)
        assertFalse(hidden.hasMore)
        assertEquals(0, hidden.lostSamples)
    }

    @Test fun unavailableStorageErrorsStayCategoricalRatherThanEmptyOrZero() {
        val navigation = HistoryNavigation()
        val error = HistoryGraphSnapshot(HistoryReadiness.ERROR, problem = HistoryRecorderProblem.STORAGE_UNAVAILABLE,
            storageReason = HistoryUnavailable.IO_FAILURE)
        val shown = historyForDisplay(error, null, navigation.query)
        assertHidden(shown, HistoryReadiness.ERROR)
        assertEquals(error.problem, shown.problem)
        assertEquals(error.storageReason, shown.storageReason)
        val current = source(navigation.query, more = false, readiness = HistoryReadiness.ERROR)
        assertSame(current, historyForDisplay(current, current.generation, navigation.query))
    }

    private fun source(query: HistoryGraphQuery, more: Boolean, readiness: HistoryReadiness = HistoryReadiness.READY): HistoryGraphSnapshot {
        val entry = SyntheticHistory.append().entry
        val storage = HistoryReadSnapshot(query.storage(entry.partition), listOf(entry), ObservationId(5), more)
        return HistoryGraphSnapshot(readiness, HistoryGeneration(), entry.partition, query, storage,
            live = HistoryLiveMetadata(HistoryEndpointMetadata(SyntheticHistory.at), HistoryEndpointMetadata(SyntheticHistory.at.plusSeconds(1))))
    }

    private fun assertHidden(source: HistoryGraphSnapshot, readiness: HistoryReadiness) {
        assertEquals(readiness, source.readiness)
        assertNull(source.storage)
        assertNull(source.generation)
        assertTrue(source.entries.isEmpty())
        assertTrue(source.points.isEmpty())
    }
}
