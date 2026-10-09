package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.credentials.LocalCredentialNamespace
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.WindowKind
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HistoryNavigationTest {
    @Test fun firstAndNextPagesUseOnlyTheBoundedExclusiveStorageCursor() {
        val navigation = HistoryNavigation()
        assertEquals(HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR), navigation.query)
        val first = source(navigation.query, more = true)
        val next = navigation.next(first)
        assertEquals(ObservationId(32), next.after)
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
        assertEquals(32, page.entries.size)
        assertTrue(page.hasMore)
        val next = navigation.next(page)
        assertEquals(ObservationId(32), next.after)
        assertEquals(WindowKind.WEEKLY, next.kind)
        assertEquals(32, next.query.limit)
    }

    @Test fun supersededQueryMissingCursorAndNonadvancingCursorCannotNavigate() {
        val navigation = HistoryNavigation()
        assertSame(navigation, navigation.next(source(HistoryGraphQuery(kind = WindowKind.WEEKLY), more = true)))
        val empty = HistoryReadSnapshot(navigation.query.storage(SyntheticHistory.partition), emptyList(), null, false)
        assertSame(navigation, navigation.next(HistoryGraphSnapshot(query = navigation.query, storage = empty)))
        val later = navigation.copy(after = ObservationId(2))
        // Legal matching pages with hasMore always advance. Missing/nonadvancing
        // cursors are rejected by storage construction, not navigation arrangements.
        assertSame(later, later.next(source(later.query, more = false)))
        assertEquals(ObservationId(34), later.next(source(later.query, more = true)).after)
    }

    @Test fun impossibleMissingAndNonadvancingStorageCursorsAreRejectedAtConstruction() {
        val query = HistoryNavigation().query.storage(SyntheticHistory.partition)
        assertThrows(IllegalArgumentException::class.java) {
            HistoryReadSnapshot(query, emptyList(), ObservationId(5), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryReadSnapshot(query.copy(after = ObservationId(2)),
                listOf(SyntheticHistory.append().entry), ObservationId(5), true)
        }
    }

    @Test fun onlyMatchingCurrentRuntimeGenerationAndSelectionExposeDetachedFacts() {
        val navigation = HistoryNavigation()
        val issuer = DisplayIssuer()
        val source = source(navigation.query, more = true, issuer = issuer)
        assertSame(source, historyForDisplay(source, navigation.query))
        val changedQuery = historyForDisplay(source, navigation.copy(kind = WindowKind.WEEKLY).query)
        issuer.context = HistoryDisplayContext(HistoryGeneration())
        val oldGeneration = historyForDisplay(source, navigation.query)
        assertHidden(oldGeneration, HistoryReadiness.LOADING)
        assertHidden(changedQuery, HistoryReadiness.LOADING)
        assertEquals(WindowKind.WEEKLY, changedQuery.query.kind)
    }

    @Test fun retirementRemovesEveryMeasurementAndIndependentEndpointClock() {
        val navigation = HistoryNavigation()
        val issuer = DisplayIssuer()
        val old = source(navigation.query, more = true, readiness = HistoryReadiness.ERROR, lostSamples = 3, issuer = issuer)
        issuer.context = null
        val hidden = historyForDisplay(old, navigation.query)
        assertHidden(hidden, HistoryReadiness.UNAVAILABLE)
        assertEquals(HistoryLiveMetadata(), hidden.live)
        assertNull(hidden.partition)
        assertFalse(hidden.hasMore)
        assertEquals(0L, hidden.lostSamples)
    }

    @Test fun currentRecorderWithoutStorageCapabilityKeepsLossButRetirementClearsIt() = runTest {
        val recorder = UsageHistoryRecorder(backgroundScope, StandardTestDispatcher(testScheduler))
        val session = SessionGeneration(LocalCredentialNamespace())
        recorder.foreground(true)
        recorder.bind(session, null)
        val observation = EndpointObservation(ReadOperation.USAGE,
            observedAt = SyntheticHistory.at, usage = SyntheticHistory.usage(), receivedAtMillis = 1000)
        recorder.metadata(UsageRefreshState(usage = RefreshedEndpoint(success = observation),
            inventory = RefreshedEndpoint(success = EndpointObservation(ReadOperation.RESET_INVENTORY,
                observedAt = SyntheticHistory.at.plusSeconds(1)))))
        repeat(2) {
            recorder.accept(recorder.capture(session), observation)
        }
        val error = recorder.state.value
        assertNull(error.generation)
        assertEquals(2L, error.lostSamples)
        assertNotEquals(HistoryLiveMetadata(), error.live)
        val query = HistoryNavigation(kind = WindowKind.WEEKLY).query
        val shown = historyForDisplay(error, query)
        assertHidden(shown, HistoryReadiness.ERROR)
        assertEquals(query, shown.query)
        assertEquals(HistoryRecorderProblem.STORAGE_UNAVAILABLE, shown.problem)
        assertEquals(2L, shown.lostSamples)
        assertNull(shown.partition)
        assertEquals(HistoryLiveMetadata(), shown.live)
        recorder.retire()
        val retired = historyForDisplay(error, query)
        assertHidden(retired, HistoryReadiness.UNAVAILABLE)
        assertEquals(0L, retired.lostSamples)
    }

    @Test fun unavailableStorageErrorsStayCategoricalRatherThanEmptyOrZero() {
        val navigation = HistoryNavigation()
        val issuer = DisplayIssuer(null)
        val error = HistoryGraphSnapshot(HistoryReadiness.ERROR, problem = HistoryRecorderProblem.STORAGE_UNAVAILABLE,
            storageReason = HistoryUnavailable.IO_FAILURE, displayPermission = issuer.permission())
        val shown = historyForDisplay(error, navigation.query)
        assertHidden(shown, HistoryReadiness.ERROR)
        assertEquals(error.problem, shown.problem)
        assertEquals(error.storageReason, shown.storageReason)
        val current = source(navigation.query, more = false, readiness = HistoryReadiness.ERROR)
        assertSame(current, historyForDisplay(current, navigation.query))
    }

    @Test fun detachedFactsWithoutIssuerPermissionNeverAuthorizeThemselves() {
        val navigation = HistoryNavigation()
        val unauthorized = HistoryGraphSnapshot(HistoryReadiness.ERROR, HistoryGeneration(),
            lostSamples = 7, problem = HistoryRecorderProblem.WRITE_FAILURE)
        val hidden = historyForDisplay(unauthorized, navigation.query)
        assertHidden(hidden, HistoryReadiness.UNAVAILABLE)
        assertEquals(0L, hidden.lostSamples)
        assertNull(hidden.problem)
    }

    private fun source(query: HistoryGraphQuery, more: Boolean, readiness: HistoryReadiness = HistoryReadiness.READY,
        lostSamples: Long = 0, issuer: DisplayIssuer = DisplayIssuer()): HistoryGraphSnapshot {
        var cursor = HistoryCursor(SyntheticHistory.partition, lastOrdinal = query.after?.ordinal ?: 0)
        // A real store fills the limit before its pagination lookahead reports more.
        val retained = List(if (more) query.limit + 1 else 1) {
            SyntheticHistory.append(cursor).also { cursor = it.cursor }.entry
        }
        val storage = HistoryReadSnapshot(query.storage(cursor.partition), retained.take(query.limit), retained.last().id, more)
        return HistoryGraphSnapshot(readiness, issuer.context!!.generation, cursor.partition, query, storage, lostSamples = lostSamples,
            live = HistoryLiveMetadata(HistoryEndpointMetadata(SyntheticHistory.at), HistoryEndpointMetadata(SyntheticHistory.at.plusSeconds(1))),
            displayPermission = issuer.permission())
    }

    /** Explicit synthetic issuer for pure projection cases, not recorder runtime evidence. */
    private class DisplayIssuer(generation: HistoryGeneration? = HistoryGeneration()) : HistoryDisplayAuthority {
        var context: HistoryDisplayContext? = HistoryDisplayContext(generation)
        override fun current() = context
        fun permission() = HistoryDisplayPermission(requireNotNull(context), this)
    }

    private fun assertHidden(source: HistoryGraphSnapshot, readiness: HistoryReadiness) {
        assertEquals(readiness, source.readiness)
        assertNull(source.storage)
        assertNull(source.generation)
        assertTrue(source.entries.isEmpty())
        assertTrue(source.points.isEmpty())
    }
}
