package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.EndpointObservation
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Original synthetic contract checks supplement (not replace) the integrated owner/native suites. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryProjectionTest {
    @Test fun boundedDetachedSelectionKeepsTypedAbsentBaselineGapsAndRetentionMetadata() {
        val applied = SyntheticHistory.append()
        val query = HistoryReadQuery(applied.entry.partition, 1)
        val entries = mutableListOf(applied.entry)
        val truncation = mutableSetOf(HistoryTruncation.OBSERVATION_CAP)
        val page = HistoryReadSnapshot(query, entries, ObservationId(2), true, truncation,
            mapOf(HistoryTruncation.OBSERVATION_CAP to HistoryEvictionCutoff(1)))
        val snapshot = HistoryGraphSnapshot(HistoryReadiness.READY, HistoryGeneration(), applied.entry.partition,
            HistoryGraphQuery(limit = 1), page)
        entries.clear(); truncation.clear()
        assertEquals(1, snapshot.points.size)
        assertTrue(snapshot.hasMore)
        assertEquals(setOf(HistoryTruncation.OBSERVATION_CAP), snapshot.truncation)
        assertEquals(1L, snapshot.storage!!.cutoffs[HistoryTruncation.OBSERVATION_CAP]!!.ordinal)
        assertEquals(HistoryUnit.PERCENT, snapshot.usedUnit)
        assertEquals(HistoryUnit.PERCENT, snapshot.baselineUnit)
        assertEquals(HistoryUnit.ELAPSED_SI_SECONDS, snapshot.durationUnit)
        val weekly = snapshot.entries.single().windows.single { it.source.kind == WindowKind.WEEKLY }
        assertEquals(DistributionUnavailable.Ineligible(BaselineEligibility.WINDOW_UNAVAILABLE),
            (weekly.reference as DistributionReference.Unavailable).reason)
        assertTrue(weekly.comparison is UsedComparison.Unavailable)
        val selected = HistoryGraphSnapshot(HistoryReadiness.READY, snapshot.generation, snapshot.partition,
            HistoryGraphQuery(limit = 1, window = snapshot.points.single().segment.window), page)
        assertEquals(1, selected.entries.single().windows.size)
        assertSame(snapshot.points.single(), selected.points.single())
        try { (snapshot.entries as MutableList).clear(); fail("immutable graph entries") } catch (_: UnsupportedOperationException) { }
        try { (snapshot.entries.first().windows as MutableList).clear(); fail("immutable graph windows") } catch (_: UnsupportedOperationException) { }
        val empty = HistoryGraphSnapshot(HistoryReadiness.EMPTY, storage = HistoryReadSnapshot(query, emptyList(), null, false))
        assertTrue(empty.points.isEmpty())
        assertFalse(empty.hasMore)
        assertTrue(empty.truncation.isEmpty())
    }

    @Test fun oneAdmissionTicketCannotReplayEvenWhenARealEqualPercentAdmissionFollows() = runTest {
        recorder { recorder, session, journal ->
            val admission = recorder.capture(session)
            val observation = EndpointObservation(ReadOperation.USAGE, observedAt = SyntheticHistory.at,
                usage = SyntheticHistory.usage(), receivedAtMillis = 1000)
            recorder.accept(admission, observation); runCurrent()
            recorder.accept(admission, observation); runCurrent()
            assertEquals(1, journal.admissions.size)
            recorder.accept(recorder.capture(session), observation); runCurrent()
            assertEquals(listOf(1L, 2L), journal.admissions.map { it.id.ordinal })
            assertEquals(2, recorder.state.value.points.size)
            val old = recorder.capture(session)
            recorder.retire()
            recorder.accept(old, observation); runCurrent()
            assertEquals(2, journal.admissions.size)
            assertTrue(recorder.state.value.points.isEmpty())
        }
    }

    @Test fun ordinalCapacityAndInvalidAppendAreTypedLossWithoutWrapOrRetry() = runTest {
        recorder { recorder, session, journal ->
            journal.cursor = journal.cursor.copy(lastOrdinal = Long.MAX_VALUE)
            recorder.accept(recorder.capture(session), EndpointObservation(ReadOperation.USAGE,
                usage = SyntheticHistory.usage(), receivedAtMillis = 1000)); runCurrent()
            assertEquals(HistoryRecorderProblem.ORDINAL_CAPACITY, recorder.state.value.problem)
            assertEquals(1L, recorder.state.value.lostSamples)
            assertTrue(journal.admissions.isEmpty())
        }
        recorder { recorder, session, journal ->
            journal.appendFailure = HistoryAppendOutcome.Rejected(AdmissionRejection.FIELD_CAPACITY)
            recorder.accept(recorder.capture(session), EndpointObservation(ReadOperation.USAGE,
                usage = SyntheticHistory.usage(), receivedAtMillis = 1000)); runCurrent()
            assertEquals(HistoryRecorderProblem.WRITE_FAILURE, recorder.state.value.problem)
            assertEquals(1, journal.admissions.size)
            journal.appendFailure = HistoryAppendOutcome.Corrupt
            recorder.accept(recorder.capture(session), EndpointObservation(ReadOperation.USAGE,
                usage = SyntheticHistory.usage(), receivedAtMillis = 1000)); runCurrent()
            assertEquals(HistoryRecorderProblem.CORRUPT, recorder.state.value.problem)
            assertEquals(2L, recorder.state.value.lostSamples)
            assertEquals(2, journal.admissions.size)
        }
    }

    @Test fun unsupportedQueryBoundsAreRejectedAndUnavailableQueriesDoNotCreateWork() = runTest {
        for (limit in listOf(0, 257)) {
            try { HistoryGraphQuery(limit); fail("bounded graph query") } catch (_: IllegalArgumentException) { }
        }
        val recorder = UsageHistoryRecorder(backgroundScope, StandardTestDispatcher(testScheduler))
        recorder.query(HistoryGraphQuery(kind = WindowKind.WEEKLY)); runCurrent()
        assertEquals(HistoryReadiness.UNAVAILABLE, recorder.state.value.readiness)
        assertTrue(recorder.state.value.points.isEmpty())
        recorder.settled()
    }

    private fun TestScope.recorder(block: (UsageHistoryRecorder, SessionGeneration, LifetimeJournal) -> Unit) {
        val journal = LifetimeJournal()
        val session = SerializedCredentialStore(FakeCredentialPersistence()).openSession()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val recorder = UsageHistoryRecorder(scope, StandardTestDispatcher(testScheduler))
        val access = (journal.storage().restore() as HistoryLifetimeOutcome.Bound).access
        recorder.foreground(true); recorder.bind(session, access); runCurrent()
        try { block(recorder, session, journal) }
        finally { recorder.retire(); scope.cancel(); runCurrent() }
    }
}
