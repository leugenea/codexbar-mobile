package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.auth.SyntheticAuth
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual owner/B2/auth/recorder topology with virtual time; native SQLite proof is separate. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundRefreshTest {
    @Test fun backgroundOnlyRecordsWithoutDisplayAuthorityPollingOrTicks() = runTest { fixture {
        val target = target()
        val result = owner.refreshInBackground(target) as BackgroundRefreshOutcome.Completed
        assertEquals(HistoryRecordingOutcome.Stored(ObservationId(1)), result.history)
        assertNull(result.observations.usage.error)
        assertEquals(1, points().size)
        assertNull(owner.currentHistoryDisplayContext())
        assertEquals(HistoryReadiness.UNAVAILABLE, snapshot.readiness)
        assertTrue(snapshot.points.isEmpty())
        assertEquals(HistoryAvailability.UNAVAILABLE, owner.historyAvailability)
        val evaluated = owner.state.value.refresh.evaluatedAt
        advance(900_000)
        assertEquals(2, gets.size)
        assertEquals(evaluated, owner.state.value.refresh.evaluatedAt)
        assertEquals(1, journal.admissions.size)
    } }

    @Test fun backgroundFirstManualOverlapSharesEndpointPairAndAppend() = runTest { overlap(true) }
    @Test fun manualFirstBackgroundOverlapSharesEndpointPairAndAppend() = runTest { overlap(false) }

    private suspend fun TestScope.overlap(backgroundFirst: Boolean) = fixture {
        val expected = target()
        heldPath = ReadOperation.USAGE.path
        visible(true)
        if (!backgroundFirst) read()
        val pending = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        assertEquals(1, owner.backgroundRefreshInterests())
        if (backgroundFirst) read()
        assertEquals(1, gets.size)
        gets.single().reply(usage)
        settle()
        val result = pending.await() as BackgroundRefreshOutcome.Completed
        assertEquals(HistoryRecordingOutcome.Stored(ObservationId(1)), result.history)
        assertEquals(2, gets.size)
        assertEquals(listOf(1L), journal.admissions.map { it.id.ordinal })
        assertEquals(1, snapshot.points.size)
    }

    @Test fun lastUiStopHidesAuthorityWithoutCancellingBackgroundConsumer() = runTest { fixture {
        val expected = target()
        visible(true)
        val display = owner.currentHistoryDisplayContext()
        heldPath = ReadOperation.USAGE.path
        read()
        val pending = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        assertEquals(1, owner.backgroundRefreshInterests())
        val held = gets.single()
        visible(false)
        assertFalse(held.cancelled)
        assertNull(owner.currentHistoryDisplayContext())
        assertNotNull(display)
        assertTrue(snapshot.points.isEmpty())
        held.reply(usage)
        settle()
        assertTrue((pending.await() as BackgroundRefreshOutcome.Completed).history is HistoryRecordingOutcome.Stored)
        assertEquals(1, points().size)
        assertEquals(2, gets.size)
        assertEquals(0, owner.backgroundRefreshInterests())
        assertEquals(HistoryAvailability.UNAVAILABLE, owner.historyAvailability)
    } }

    @Test fun cancellationOfOnlyBackgroundConsumerCancelsReadAndRejectsLateReply() = runTest { fixture {
        val expected = target()
        heldPath = ReadOperation.USAGE.path
        val pending = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        assertEquals(1, owner.backgroundRefreshInterests())
        val held = gets.single()
        pending.cancel(); settle()
        assertTrue(held.cancelled)
        assertEquals(0, owner.backgroundRefreshNetworkInterests())
        assertEquals(0, owner.backgroundRefreshInterests())
        held.reply(usage); settle()
        assertEquals(1, gets.size)
        assertTrue(journal.admissions.isEmpty())
        assertNull(owner.state.value.refresh.usage.success)
    } }

    @Test fun cancelledBackgroundConsumerPreservesVisibleUiConsumer() = runTest { fixture {
        val expected = target()
        visible(true)
        heldPath = ReadOperation.USAGE.path
        read()
        val pending = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        assertEquals(1, owner.backgroundRefreshInterests())
        assertEquals(1, owner.backgroundRefreshNetworkInterests())
        val held = gets.single()
        pending.cancel(); settle()
        assertEquals(0, owner.backgroundRefreshNetworkInterests())
        assertEquals(1, owner.backgroundRefreshInterests())
        assertFalse(held.cancelled)
        held.reply(usage); settle()
        assertEquals(0, owner.backgroundRefreshInterests())
        assertEquals(2, gets.size)
        assertEquals(1, snapshot.points.size)
        assertEquals(listOf(1L), journal.admissions.map { it.id.ordinal })
    } }

    @Test fun cancelledBackgroundConsumerPreservesIndependentBackgroundConsumer() = runTest { fixture {
        val expected = target()
        heldPath = ReadOperation.USAGE.path
        val first = test.backgroundScope.async { owner.refreshInBackground(expected) }
        val second = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        assertEquals(2, owner.backgroundRefreshInterests())
        assertEquals(2, owner.backgroundRefreshNetworkInterests())
        first.cancel(); settle()
        assertEquals(1, owner.backgroundRefreshNetworkInterests())
        assertEquals(2, owner.backgroundRefreshInterests())
        assertFalse(gets.single().cancelled)
        gets.single().reply(usage); settle()
        assertTrue((second.await() as BackgroundRefreshOutcome.Completed).history is HistoryRecordingOutcome.Stored)
        assertEquals(2, gets.size)
        assertEquals(1, points().size)
        assertEquals(0, owner.backgroundRefreshNetworkInterests())
        assertEquals(0, owner.backgroundRefreshInterests())
    } }

    @Test fun receiptWaitsForAdmittedStorageAndCancellationDoesNotDropAcceptedObservation() = runTest { fixture {
        val expected = target()
        storage.held = true
        val pending = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        assertEquals(1, storage.pending)
        assertFalse(owner.state.value.refresh.refreshing)
        assertFalse(pending.isCompleted)
        assertTrue(journal.admissions.isEmpty())
        assertEquals(1, owner.backgroundRefreshInterests())
        pending.cancel(); settle()
        assertEquals(1, owner.backgroundRefreshInterests()) // Process-owned append, not the cancelled waiter.
        storage.release(); settle()
        assertEquals(1, points().size)
        assertEquals(0, owner.backgroundRefreshInterests())
        assertEquals(2, gets.size)
    } }

    @Test fun appendAndPreappendFailuresHaveHonestNonSuccessReceipts() = runTest { fixture {
        val expected = target()
        journal.appendFailure = HistoryAppendOutcome.Unavailable(HistoryUnavailable.STORAGE_FULL)
        val failed = owner.refreshInBackground(expected) as BackgroundRefreshOutcome.Completed
        assertEquals(HistoryRecordingOutcome.Failed(HistoryRecorderProblem.WRITE_FAILURE, HistoryUnavailable.STORAGE_FULL), failed.history)
        assertNotNull(owner.state.value.refresh.usage.success)
        assertTrue(journal.entries.isEmpty())
        journal.readFailure = HistoryReadOutcome.Corrupt
        val unreadable = owner.refreshInBackground(expected) as BackgroundRefreshOutcome.Completed
        assertEquals(HistoryRecordingOutcome.Failed(HistoryRecorderProblem.READ_FAILURE), unreadable.history)
        assertEquals(1, journal.admissions.size)
        assertEquals(4, gets.size)
    } }

    @Test fun usageErrorIsNotAStoredMeasurementAndInventoryFailureDoesNotEraseStoredUsage() = runTest { fixture {
        val expected = target()
        usage = SyntheticAuth.response("{}", 403)
        val failed = owner.refreshInBackground(expected) as BackgroundRefreshOutcome.Completed
        assertEquals(ReadError.FORBIDDEN, failed.observations.usage.error)
        assertNull(failed.observations.inventory.error)
        assertEquals(HistoryRecordingOutcome.NotObserved, failed.history)
        assertTrue(journal.admissions.isEmpty())
        usage = SyntheticAuth.response(RecordingFixture.USAGE)
        inventory = SyntheticAuth.response("{}", 403)
        val partial = owner.refreshInBackground(expected) as BackgroundRefreshOutcome.Completed
        assertNull(partial.observations.usage.error)
        assertEquals(ReadError.FORBIDDEN, partial.observations.inventory.error)
        assertTrue(partial.history is HistoryRecordingOutcome.Stored)
        assertEquals(1, points().size)
    } }

    @Test fun coldAdmissionWaitsForProtectedRestorationAndRejectsDifferentLifetimeWithoutRequests() = runTest {
        val fixture = RecordingFixture(this)
        fixture.storage.held = true
        val expected = BackgroundRefreshTarget(fixture.journal.partition)
        val pending = backgroundScope.async { fixture.owner.refreshInBackground(expected) }
        try {
            runCurrent()
            assertEquals(ConnectionPhase.RESTORING, fixture.owner.state.value.phase)
            assertFalse(pending.isCompleted)
            assertTrue(fixture.gets.isEmpty())
            fixture.storage.release(); runCurrent()
            assertTrue((pending.await() as BackgroundRefreshOutcome.Completed).history is HistoryRecordingOutcome.Stored)
            val mismatch = fixture.owner.refreshInBackground(BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID())))
            assertEquals(BackgroundRefreshOutcome.Rejected(BackgroundRefreshRejection.STALE_LIFETIME), mismatch)
            assertEquals(2, fixture.gets.size)
            assertEquals(1, fixture.journal.admissions.size)
        } finally { fixture.finish() }
    }

    @Test fun removalReloginAndLateReplyCannotReuseOldEnrollment() = runTest { fixture {
        val expected = target()
        heldPath = ReadOperation.USAGE.path
        val pending = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        val held = gets.single()
        owner.signOut(); settle()
        assertEquals(ConnectionPhase.SIGNED_OUT, owner.state.value.phase)
        assertTrue(pending.await() is BackgroundRefreshOutcome.Rejected)
        assertTrue(held.cancelled)
        heldPath = null
        visible(true)
        owner.connect(); settle()
        // runCurrent drains ingress, not the authenticator's five-second virtual poll delay.
        assertEquals(ConnectionPhase.AUTHENTICATING, owner.state.value.phase)
        assertTrue(owner.state.value.auth is AuthState.AwaitingUser)
        assertNull(owner.backgroundRefreshTarget())
        awaitEnrollmentReady("successor login and first history append", ConnectionPhase.OBSERVED)
        val successor = target()
        assertNotEquals(expected, successor)
        assertEquals(listOf(1L), journal.entries.map { it.id.ordinal })
        val count = fake.calls.size
        held.reply(usage); settle()
        assertEquals(count, fake.calls.size)
        assertEquals(BackgroundRefreshOutcome.Rejected(BackgroundRefreshRejection.STALE_LIFETIME), owner.refreshInBackground(expected))
        assertEquals(count, fake.calls.size)
        assertEquals(1, points().size)
    } }

    @Test fun backgroundHonorsRetryBoundaryWithoutSchedulingAHiddenWakeup() = runTest { fixture {
        val expected = target()
        usage = SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(120_000))
        val pending = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        advance(30_000)
        assertTrue(pending.await() is BackgroundRefreshOutcome.Completed)
        val deferred = owner.refreshInBackground(expected)
        assertEquals(BackgroundRefreshOutcome.Rejected(BackgroundRefreshRejection.DEFERRED, 120_000), deferred)
        val count = gets.size
        advance(120_000)
        assertEquals(count, gets.size)
    } }

    @Test fun cancellingWorkerNeverRetiresIndependentAcceptedTokenRotation() = runTest { fixture {
        val expected = target()
        val before = (owner.session.snapshot() as SessionResult.Ready).envelope
        usage = SyntheticAuth.response("{}", 401)
        heldPath = "/oauth/token"
        val pending = test.backgroundScope.async { owner.refreshInBackground(expected) }
        settle()
        val rotation = fake.calls.last()
        assertEquals("/oauth/token", rotation.request.url.encodedPath)
        pending.cancel(); settle()
        assertFalse(rotation.cancelled)
        rotation.reply(SyntheticAuth.response(SyntheticAuth.TOKENS)); settle()
        val after = (owner.session.snapshot() as SessionResult.Ready).envelope
        assertNotSame(before, after)
        assertSame(before.generation, after.generation)
        assertEquals(1, gets.size)
        assertTrue(journal.entries.isEmpty())
        assertEquals(0, owner.backgroundRefreshInterests())
    } }

    @Test fun automaticBackgroundDoesNotUnlockRetainedTransientRefreshFailure() = runTest { fixture {
        val expected = target()
        usage = SyntheticAuth.response("{}", 401)
        val normal = fake.respond
        fake.respond = { call ->
            if (call.request.url.encodedPath == "/oauth/token") call.reply(SyntheticAuth.response("{}", 503))
            else normal(call)
        }
        owner.refreshInBackground(expected)
        owner.refreshInBackground(expected)
        assertEquals(1, fake.calls.count { it.request.url.encodedPath == "/oauth/token" })
        assertEquals(4, gets.size)
        assertTrue(journal.entries.isEmpty())
    } }

    @Test fun unavailableRestorationNeverEnrolsOrActivatesProviderRequests() = runTest {
        val fixture = RecordingFixture(this)
        fixture.journal.failRestore = true
        try {
            fixture.settle()
            assertNull(fixture.owner.backgroundRefreshTarget())
            val outcome = fixture.owner.refreshInBackground(BackgroundRefreshTarget(fixture.journal.partition))
            assertTrue(outcome is BackgroundRefreshOutcome.Rejected)
            assertTrue(fixture.gets.isEmpty())
            assertTrue(fixture.journal.admissions.isEmpty())
        } finally { fixture.finish() }
    }

    @Test fun terminalReauthorizationRevokesEnrollmentAndCannotReturnStoredSuccess() = runTest { fixture {
        val expected = target()
        usage = SyntheticAuth.response("{}", 401)
        val normal = fake.respond
        fake.respond = { call ->
            if (call.request.url.encodedPath == "/oauth/token")
                call.reply(SyntheticAuth.response("""{"error":"invalid_grant"}""", 400))
            else normal(call)
        }
        assertTrue(owner.refreshInBackground(expected) is BackgroundRefreshOutcome.Rejected)
        settle()
        assertEquals(ConnectionPhase.REAUTH_REQUIRED, owner.state.value.phase)
        assertNull(owner.backgroundRefreshTarget())
        val count = fake.calls.size
        assertTrue(owner.refreshInBackground(expected) is BackgroundRefreshOutcome.Rejected)
        assertEquals(count, fake.calls.size)
        assertTrue(journal.entries.isEmpty())
    } }

    private suspend fun RecordingFixture.target() = requireNotNull(owner.backgroundRefreshTarget()) {
        "capture checked enrollment: ${enrollmentDiagnostics()}"
    }

    private suspend fun RecordingFixture.awaitEnrollmentReady(step: String, phase: ConnectionPhase) {
        try { withTimeout(10_000) {
            owner.commandsSettled() // Ingress receipt alone is not login or durable append completion.
            owner.state.first { it.phase == phase && !it.refresh.refreshing }
            if (phase == ConnectionPhase.OBSERVED) owner.historySnapshots.first {
                it.readiness == HistoryReadiness.READY && it.partition != null && it.points.isNotEmpty()
            }
        } } catch (_: TimeoutCancellationException) {
            throw AssertionError("$step: ${enrollmentDiagnostics()}")
        }
        settle()
    }

    private fun RecordingFixture.enrollmentDiagnostics(): String {
        val state = owner.state.value
        return "last phase=${state.phase}, auth=${state.auth.javaClass.simpleName}, refreshing=${state.refresh.refreshing}, " +
            "session=${owner.session.snapshot().javaClass.simpleName}, coordinator=${owner.historyAvailability}, " +
            "readiness=${snapshot.readiness}, partition=${snapshot.partition}, points=${snapshot.points.size}, " +
            "journal=${journal.phase}, storagePending=${storage.pending}, requests=${fake.calls.size}, virtualMillis=${test.currentTime}"
    }

    private suspend fun TestScope.fixture(block: suspend RecordingFixture.() -> Unit) {
        val fixture = RecordingFixture(this)
        try {
            fixture.awaitEnrollmentReady("initial protected restoration", ConnectionPhase.RESTORED)
            fixture.block()
        } finally { fixture.finish() }
    }
}
