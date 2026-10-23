package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.EndpointObservation
import io.github.leugenea.codexbarmobile.HistoryNavigation
import io.github.leugenea.codexbarmobile.historyForDisplay
import io.github.leugenea.codexbarmobile.currentHistoryForDisplay
import io.github.leugenea.codexbarmobile.RefreshedEndpoint
import io.github.leugenea.codexbarmobile.UsageRefreshState
import io.github.leugenea.codexbarmobile.usage.WindowKind
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

    @Test fun heldJointFactsRejectEqualRequestAfterRouteRoundTrip() = runTest {
        val recorder = boundCurrent()
        val context = recorder.currentDisplayContext()!!
        val request = recorder.state.value.request as HistoryPresentationRequest.Current
        val old = recorder.state.value
        assertEquals(1, currentHistoryForDisplay(old, request, WindowKind.FIVE_HOUR, context).points.size)
        assertNotNull(currentHistoryForDisplay(old, request, WindowKind.FIVE_HOUR, context).latestCurrent)
        recorder.query(HistoryGraphQuery(32, kind = WindowKind.WEEKLY)); runCurrent()
        recorder.observe(request, context); runCurrent()
        assertNotSame(old.selection, recorder.state.value.selection)
        val challenged = currentHistoryForDisplay(old, request, WindowKind.FIVE_HOUR, context)
        assertNull(challenged.storage); assertTrue(challenged.points.isEmpty()); assertNull(challenged.latestCurrent)
        assertEquals(1, currentHistoryForDisplay(recorder.state.value, request, WindowKind.FIVE_HOUR, context).points.size)
        val token = recorder.state.value.selection
        recorder.observe(request, context); runCurrent()
        assertSame(token, recorder.state.value.selection)
    }

    @Test fun heldJointFactsRejectResetExpiryClockLossAndRetiredContexts() = runTest {
        val recorder = boundCurrent()
        val context = recorder.currentDisplayContext()!!
        val old = recorder.state.value
        val request = old.request as HistoryPresentationRequest.Current
        val observed = observation()
        val invalidTimes = listOf(request.fiveHour!!.resetAt, null, SyntheticHistory.at.minusNanos(1))
        for (now in invalidTimes) {
            recorder.metadata(UsageRefreshState(usage = RefreshedEndpoint(success = observed), evaluatedAt = now))
            assertNull(currentHistoryForDisplay(old, request, WindowKind.FIVE_HOUR, context).storage)
            assertNull(currentHistoryForDisplay(old, request, WindowKind.FIVE_HOUR, context).latestCurrent)
        }
        val changed = EndpointObservation(ReadOperation.USAGE, observedAt = SyntheticHistory.at, receivedAtMillis = 1000,
            usage = SyntheticHistory.usage(SyntheticHistory.window(absolute = io.github.leugenea.codexbarmobile.usage.Input.Value(1_800_007_200L))))
        recorder.metadata(UsageRefreshState(usage = RefreshedEndpoint(success = changed), evaluatedAt = SyntheticHistory.at))
        assertNull(currentHistoryForDisplay(old, request, WindowKind.FIVE_HOUR, context).storage)
        recorder.retire()
        assertNull(currentHistoryForDisplay(old, request, WindowKind.FIVE_HOUR, recorder.currentDisplayContext()).storage)
    }

    @Test fun currentNullDiagnosticSelectionCannotReplaySuccessorOrRetiredLoss() = runTest {
        val recorder = recorder()
        val session = session()
        recorder.foreground(true); recorder.bind(session, null)
        admit(recorder, session)
        val old = recorder.state.value
        recorder.query(HistoryGraphQuery(32, kind = WindowKind.WEEKLY))
        assertEquals(HistoryReadiness.UNAVAILABLE, recorder.state.value.readiness)
        assertNotSame(old.selection, recorder.state.value.selection)
        assertEquals(1L, shown(old).lostSamples)
        recorder.retire(); assertHidden(old, HistoryReadiness.UNAVAILABLE)
        recorder.bind(session(), LifetimeJournal().Access(SyntheticHistory.partition)); runCurrent()
        assertHidden(old, HistoryReadiness.LOADING)
        recorder.retire(); recorder.bind(session(), null)
        assertHidden(old, HistoryReadiness.UNAVAILABLE)
    }

    private fun TestScope.boundCurrent(): UsageHistoryRecorder {
        val recorder = recorder()
        val session = session()
        val journal = LifetimeJournal()
        recorder.foreground(true); recorder.bind(session, journal.Access(journal.partition)); runCurrent()
        recorder.metadata(UsageRefreshState(usage = RefreshedEndpoint(success = observation()), evaluatedAt = SyntheticHistory.at))
        admit(recorder, session); runCurrent()
        val context = recorder.currentDisplayContext()!!
        recorder.observe(HistoryPresentationRequest.Current(context.periods.fiveHour.window, context.periods.weekly.window), context)
        runCurrent()
        return recorder
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
