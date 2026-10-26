package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.auth.SyntheticAuth
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real owner lane and flights; virtual time is NOT OS scheduling evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicRefreshAdmissionTest {
    @Test fun dormantRestoreAndForegroundObservationNeverEnrollUntilExplicitActivation() = runTest {
        val enrolled = mutableListOf<BackgroundRefreshTarget?>()
        val f = RecordingFixture(this, enrollment = enrolled::add)
        try {
            f.settle(); f.visible(true)
            assertTrue(enrolled.isEmpty()); assertTrue(f.gets.isEmpty())
            f.read(); f.read()
            assertEquals(listOf(f.target(), f.target()), enrolled)
            f.owner.cancel(); f.settle()
            assertEquals(2, enrolled.size) // Cancel/STOP are not local account removal.
            f.owner.signOut(); f.settle()
            assertNull(enrolled.last())
        } finally { f.finish() }
    }

    @Test fun successfulInteractiveLoginEnrollsOnlyItsNewActiveLifetime() = runTest {
        val enrolled = mutableListOf<BackgroundRefreshTarget?>()
        val f = RecordingFixture(this, enrollment = enrolled::add)
        try {
            f.settle(); f.owner.signOut(); f.settle()
            f.visible(true); f.owner.connect(); f.settle()
            assertEquals(ConnectionPhase.AUTHENTICATING, f.owner.state.value.phase)
            advanceTimeBy(5_000); runCurrent()
            withTimeout(10_000) { f.owner.commandsSettled(); f.owner.backgroundRefreshTarget() }
            assertEquals(ConnectionPhase.OBSERVED, f.owner.state.value.phase)
            assertEquals(listOf(f.target()), enrolled.filterNotNull())
            assertEquals(2, f.gets.size)
        } finally { f.finish() }
    }

    @Test fun periodicRecentSuccessSkipsBothEndpointsAndExactFifteenMinuteBoundaryRuns() = runTest { fixture {
        val expected = target()
        assertTrue(periodic(expected) is BackgroundRefreshOutcome.Completed)
        assertEquals(2, gets.size)
        advance(899_999)
        assertEquals(BackgroundRefreshOutcome.Rejected(BackgroundRefreshRejection.RECENT_SUCCESS), periodic(expected))
        assertEquals(2, gets.size)
        advance(1)
        assertTrue(periodic(expected) is BackgroundRefreshOutcome.Completed)
        assertEquals(4, gets.size); assertEquals(2, points().size)
    } }

    @Test fun failedOrPartialEndpointDoesNotBecomeARecentSuccessfulCycle() = runTest { fixture {
        val expected = target()
        inventory = SyntheticAuth.response("{}", 403)
        assertTrue(periodic(expected) is BackgroundRefreshOutcome.Completed)
        inventory = SyntheticAuth.response("""{"available_count":0,"credits":[]}""")
        assertTrue(periodic(expected) is BackgroundRefreshOutcome.Completed)
        assertEquals(4, gets.size)
        assertEquals(2, points().size)
    } }

    @Test fun periodicManualOverlapInBothOrdersStillJoinsActualFlightOnce() = runTest {
        for (backgroundFirst in listOf(true, false)) fixture {
            val expected = target(); visible(true); heldPath = ReadOperation.USAGE.path
            if (!backgroundFirst) read()
            val pending = test.backgroundScope.async { periodic(expected) }; settle()
            assertEquals(1, owner.backgroundRefreshInterests())
            if (backgroundFirst) read()
            assertEquals(1, gets.size)
            gets.single().reply(usage); settle()
            assertTrue((pending.await() as BackgroundRefreshOutcome.Completed).history is HistoryRecordingOutcome.Stored)
            assertEquals(2, gets.size); assertEquals(1, points().size)
        }
    }

    @Test fun periodicRateLimitAndErrorsRemainBoundedWithoutHiddenPeriodicRetries() = runTest { fixture {
        val expected = target()
        usage = SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(120_000))
        val pending = test.backgroundScope.async { periodic(expected) }; settle(); advance(30_000)
        assertTrue(pending.await() is BackgroundRefreshOutcome.Completed)
        assertEquals(BackgroundRefreshOutcome.Rejected(BackgroundRefreshRejection.DEFERRED, 120_000), periodic(expected))
        val count = gets.size
        advance(900_000)
        assertEquals(count, gets.size)
        usage = SyntheticAuth.response("{}", 403)
        assertTrue(periodic(expected) is BackgroundRefreshOutcome.Completed)
        assertEquals(count + 2, gets.size)
        advance(900_000); assertEquals(count + 2, gets.size)
    } }

    @Test fun staleLifetimeIsRejectedBeforeRecentSuccessAndTerminalAuthCancelsEnrollment() = runTest {
        val enrolled = mutableListOf<BackgroundRefreshTarget?>()
        val f = RecordingFixture(this, enrollment = enrolled::add)
        try {
            f.settle(); f.read()
            val wrong = BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))
            assertEquals(BackgroundRefreshOutcome.Rejected(BackgroundRefreshRejection.STALE_LIFETIME), f.periodic(wrong))
            assertEquals(2, f.gets.size)
            f.usage = SyntheticAuth.response("{}", 401)
            val normal = f.fake.respond
            f.fake.respond = { call -> if (call.request.url.encodedPath == "/oauth/token")
                call.reply(SyntheticAuth.response("""{"error":"invalid_grant"}""", 400)) else normal(call) }
            f.advance(900_000)
            assertTrue(f.periodic(f.target()) is BackgroundRefreshOutcome.Rejected)
            f.settle(); assertNull(enrolled.last())
            assertEquals(ConnectionPhase.REAUTH_REQUIRED, f.owner.state.value.phase)
        } finally { f.finish() }
    }

    private suspend fun RecordingFixture.target() = requireNotNull(owner.backgroundRefreshTarget())
    private suspend fun RecordingFixture.periodic(target: BackgroundRefreshTarget) =
        owner.refreshInBackground(target, minimumSuccessAgeMillis = 900_000)
    private suspend fun TestScope.fixture(block: suspend RecordingFixture.() -> Unit) {
        val f = RecordingFixture(this)
        try { f.settle(); f.block() } finally { f.finish() }
    }
}
