package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.EndpointObservation
import io.github.leugenea.codexbarmobile.HistoryNavigation
import io.github.leugenea.codexbarmobile.historyForDisplay
import io.github.leugenea.codexbarmobile.credentials.LocalCredentialNamespace
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Real recorder transitions with synthetic storage/observations; hosted JVM execution only. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryDisplayAuthorityTest {
    @Test fun nullDiagnosticIdentityCannotReplayAcrossRetireBoundSuccessorAndNullRebind() = runTest {
        val recorder = recorder()
        val session = session()
        recorder.foreground(true)
        recorder.bind(session, null)
        repeat(2) { admit(recorder, session) }
        val old = recorder.state.value
        assertNull(old.generation)
        assertEquals(2L, shown(old).lostSamples)
        val admission = recorder.capture(session)
        recorder.retire()
        assertHidden(old, HistoryReadiness.UNAVAILABLE)
        val access = LifetimeJournal().Access(SyntheticHistory.partition)
        recorder.bind(session, access)
        assertHidden(old, HistoryReadiness.LOADING)
        runCurrent()
        recorder.retire()
        assertHidden(old, HistoryReadiness.UNAVAILABLE)
        recorder.bind(session(), null)
        assertHidden(old, HistoryReadiness.UNAVAILABLE)
        val fresh = recorder.state.value
        assertNotSame(old.displayPermission!!.context, fresh.displayPermission!!.context)
        assertEquals(0L, fresh.lostSamples)
        recorder.accept(admission, observation())
        assertEquals(0L, recorder.state.value.lostSamples)
        assertEquals(HistoryRecorderProblem.STORAGE_UNAVAILABLE, shown(fresh).problem)
    }

    @Test fun backgroundRevokesNullContextButResumeRetainsItsLossWithoutReplayingOldContext() = runTest {
        val recorder = recorder()
        val session = session()
        recorder.foreground(true)
        recorder.bind(session, null)
        val admission = recorder.capture(session)
        recorder.accept(admission, observation())
        recorder.accept(admission, observation()) // Exactly-once delivery still owns accounting.
        assertEquals(1L, recorder.state.value.lostSamples)
        val old = recorder.state.value
        recorder.foreground(false)
        assertHidden(old, HistoryReadiness.UNAVAILABLE)
        recorder.foreground(true)
        recorder.bind(session, null)
        val resumed = recorder.state.value
        assertEquals(1L, shown(resumed).lostSamples)
        assertHidden(old, HistoryReadiness.UNAVAILABLE)
        recorder.accept(admission, observation())
        assertEquals(1L, recorder.state.value.lostSamples)
        admit(recorder, session)
        assertEquals(2L, shown(recorder.state.value).lostSamples)
    }

    @Test fun nullAccessWithExistingBindingKeepsItsContextAndBoundWorkerAdmission() = runTest {
        val recorder = recorder()
        val session = session()
        val journal = LifetimeJournal()
        recorder.foreground(true)
        recorder.bind(session, journal.Access(journal.partition))
        runCurrent()
        val initial = recorder.state.value
        recorder.bind(session, null)
        val diagnostic = recorder.state.value
        assertSame(initial.generation, diagnostic.generation)
        assertSame(initial.displayPermission!!.context, diagnostic.displayPermission!!.context)
        assertSame(diagnostic, historyForDisplay(diagnostic, diagnostic.query))
        admit(recorder, session)
        runCurrent()
        assertEquals(1, journal.admissions.size)
        assertEquals(1, recorder.state.value.points.size)
        assertSame(initial.generation, recorder.state.value.generation)
    }

    @Test fun sameSlotRevokesHeldWorkerBeforeReadAndAllowsSuccessorDrain() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val storage = RecordingDispatcher(lane)
        storage.held = true
        val recorder = UsageHistoryRecorder(backgroundScope, storage)
        val journal = LifetimeJournal()
        val calls = mutableListOf<HistoryReadQuery>()
        val access = object : HistoryRuntimeAccess by journal.Access(journal.partition) {
            override fun read(query: HistoryReadQuery): HistoryReadOutcome {
                calls += query
                return HistoryReadOutcome.Unavailable(HistoryUnavailable.IO_FAILURE)
            }
        }
        try {
            recorder.foreground(true)
            recorder.bind(session(), access)
            runCurrent()
            assertEquals(1, storage.pending)
            val old = recorder.state.value
            recorder.retire()
            assertHidden(old, HistoryReadiness.UNAVAILABLE)
            recorder.bind(session(), journal.Access(journal.partition))
            assertHidden(old, HistoryReadiness.LOADING)
            storage.release()
            runCurrent()
            assertTrue("retired worker must reject before touching the storage port", calls.isEmpty())
            assertEquals(HistoryReadiness.EMPTY, recorder.state.value.readiness)
            assertSame(recorder.state.value, historyForDisplay(recorder.state.value, recorder.state.value.query))
        } finally { storage.release() }
    }

    private fun TestScope.recorder() = UsageHistoryRecorder(backgroundScope, StandardTestDispatcher(testScheduler))
    private fun session() = SessionGeneration(LocalCredentialNamespace())
    private fun observation() = EndpointObservation(ReadOperation.USAGE, observedAt = SyntheticHistory.at,
        usage = SyntheticHistory.usage(), receivedAtMillis = 1000)
    private fun admit(recorder: UsageHistoryRecorder, session: SessionGeneration) = recorder.accept(recorder.capture(session), observation())
    private fun shown(source: HistoryGraphSnapshot) = historyForDisplay(source, HistoryNavigation().query)
    private fun assertHidden(source: HistoryGraphSnapshot, readiness: HistoryReadiness) {
        val display = shown(source)
        assertEquals(readiness, display.readiness)
        assertNull(display.storage)
        assertNull(display.partition)
        assertNull(display.generation)
        assertNull(display.problem)
        assertEquals(0L, display.lostSamples)
        assertTrue(display.points.isEmpty())
    }
}
