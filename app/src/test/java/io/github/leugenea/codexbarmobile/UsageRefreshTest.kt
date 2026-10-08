package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import io.github.leugenea.codexbarmobile.usage.Completeness
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Virtual time drives the actual process owner, A2/A9 reads and cancellable fake transport. */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageRefreshTest {
    @Test fun restorationAndOfflinePreviewsAreDormantUntilAnExplicitRead() = runTest { fixture {
        assertEquals(ConnectionPhase.RESTORED, state.phase)
        visible(true)
        advance(120_000)
        assertEquals(0, fake.gets.size)
        assertNull(state.refresh.usage.success)
        manual()
        assertEquals(2, fake.gets.size)
        assertEquals(ConnectionPhase.OBSERVED, state.phase)
    } }

    @Test fun manualAndResumeCoalesceIntoOneInFlightCycle() = runTest { fixture {
        fake.heldPath = ReadOperation.USAGE.path
        visible(true)
        manual()
        val held = fake.gets.single()
        owner.readUsage(); owner.readUsage(refreshSession = true)
        owner.usageForeground(Any(), true)
        owner.connect()
        settle()
        assertEquals(1, fake.gets.size)
        assertEquals(1, fake.maximumHeld)
        held.reply(SyntheticAuth.response(USAGE))
        settle()
        assertEquals(2, fake.gets.size)
        assertFalse(state.refresh.refreshing)
        assertEquals(0, fake.refreshes)
    } }

    @Test fun visiblePollingStartsNoEarlierThanSixtySeconds() = runTest { fixture {
        visible(true); manual()
        advance(59_999)
        assertEquals(2, fake.gets.size)
        advance(1)
        assertEquals(4, fake.gets.size)
        assertEquals(listOf(0L, 0L, 60_000L, 60_000L), fake.gets.map { it.started })
        visible(false)
        advance(120_000)
        assertEquals(4, fake.gets.size)
        visible(true)
        assertEquals(6, fake.gets.size)
    } }

    @Test fun thirtySecondOperationDeadlineCancelsTransportAndPreservesSuccess() = runTest { fixture {
        visible(true); manual()
        val success = state.refresh.usage.success
        fake.heldPath = ReadOperation.USAGE.path
        manual()
        val held = fake.gets.last()
        advance(29_999)
        assertFalse(held.cancelled)
        assertTrue(state.refresh.refreshing)
        advance(1)
        assertTrue(held.cancelled)
        assertEquals(ReadError.DEADLINE_EXCEEDED, state.refresh.usage.attempt!!.error)
        assertSame(success, state.refresh.usage.success)
        assertEquals(0L, state.refresh.usage.successfulAtMillis)
        assertEquals(30_000L, state.refresh.inventory.successfulAtMillis)
        assertFalse(state.refresh.refreshing)
        assertEquals(4, fake.gets.size)
        assertEquals(30_000L, held.deadline.expiresAtMillis)
    } }

    @Test fun inventoryHasItsOwnThirtySecondDeadlineAndUsagePublishesBeforeItSettles() = runTest { fixture {
        visible(true); manual()
        val inventory = state.refresh.inventory.success
        advance(10_000)
        fake.heldPath = ReadOperation.RESET_INVENTORY.path
        manual()
        val held = fake.gets.last()
        assertEquals(10_000L, state.refresh.usage.successfulAtMillis)
        assertSame(inventory, state.refresh.inventory.success)
        advance(30_000)
        assertTrue(held.cancelled)
        assertEquals(40_000L, held.deadline.expiresAtMillis)
        assertEquals(ReadError.DEADLINE_EXCEEDED, state.refresh.inventory.attempt!!.error)
        assertEquals(0L, state.refresh.inventory.successfulAtMillis)
    } }

    @Test fun retryAfterDefersManualResumeAndPollUntilTheAdvertisedBoundary() = runTest { fixture {
        fake.usageResult = SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(120_000))
        visible(true); manual()
        assertEquals(120_000L, state.refresh.usage.attempt!!.notBeforeMillis)
        fake.usageResult = SyntheticAuth.response(USAGE)
        manual(); visible(false); visible(true)
        advance(119_999)
        assertEquals(2, fake.gets.size)
        assertEquals(ReadError.RATE_LIMITED, state.refresh.usage.attempt!!.error)
        assertNotNull(state.refresh.inventory.success)
        advance(1)
        assertEquals(4, fake.gets.size)
        assertEquals(120_000L, fake.gets[2].started)
        assertNull(state.refresh.usage.attempt!!.error)
    } }

    @Test fun usageRetryWaitSurvivesForegroundCancellationAndDefersResumeAndManual() = runTest {
        retryWaitSurvivesForegroundCancellation(ReadOperation.USAGE, 1)
    }

    @Test fun inventoryRetryWaitSurvivesForegroundCancellationAndDefersResumeAndManual() = runTest {
        retryWaitSurvivesForegroundCancellation(ReadOperation.RESET_INVENTORY, 2)
    }

    private suspend fun TestScope.retryWaitSurvivesForegroundCancellation(
        operation: ReadOperation, initialGets: Int,
    ) = fixture {
        val limited = SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(10_000))
        if (operation == ReadOperation.USAGE) fake.usageResult = limited else fake.inventoryResult = limited
        visible(true); manual()
        assertEquals(initialGets, fake.gets.size)
        assertTrue(state.refresh.refreshing)
        assertNull(state.refresh.inventory.attempt)
        advance(1_000)
        visible(false)
        assertFalse(state.refresh.refreshing)
        assertNull(state.refresh.inventory.attempt)
        fake.usageResult = SyntheticAuth.response(USAGE)
        fake.inventoryResult = SyntheticAuth.response(INVENTORY)
        fake.heldPath = ReadOperation.USAGE.path
        visible(true); manual(); manual()
        advance(8_999)
        assertEquals(initialGets, fake.gets.size)
        advance(1)
        assertEquals(initialGets + 1, fake.gets.size)
        val resumed = fake.gets.last()
        assertEquals(10_000L, resumed.started)
        manual()
        assertEquals(initialGets + 1, fake.gets.size)
        resumed.reply(SyntheticAuth.response(USAGE)); settle()
        assertEquals(initialGets + 2, fake.gets.size)
        assertFalse(state.refresh.refreshing)
        assertEquals(10_000L, state.refresh.usage.successfulAtMillis)
        assertEquals(10_000L, state.refresh.inventory.successfulAtMillis)
        assertEquals(0, fake.refreshes)
    }

    @Test fun replacingGenerationClearsAnUnfinishedRetryAfterBoundary() = runTest { fixture {
        fake.usageResult = SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(10_000))
        visible(true); manual()
        advance(1_000)
        visible(false)
        owner.signOut(); settle()
        assertEquals(ConnectionPhase.SIGNED_OUT, state.phase)
        assertNull(state.observations)
        assertNull(persistence.durable)
        fake.usageResult = SyntheticAuth.response(USAGE)
        visible(true)
        assertEquals(1, fake.gets.size)
        owner.connect(); settle(); advance(5_000)
        assertEquals(ConnectionPhase.OBSERVED, state.phase)
        assertEquals(listOf(0L, 6_000L, 6_000L), fake.gets.map { it.started })
        assertNotNull(persistence.durable)
        advance(4_000)
        assertEquals(3, fake.gets.size)
        assertEquals(6_000L, state.refresh.usage.successfulAtMillis)
    } }

    @Test fun deferredManualReadWithoutLifecycleObserversStillRunsAtTheBoundary() = runTest { fixture {
        fake.usageResult = SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(120_000))
        manual()
        assertEquals(2, fake.gets.size)
        fake.usageResult = SyntheticAuth.response(USAGE)
        manual()
        advance(119_999)
        assertEquals(2, fake.gets.size)
        advance(1)
        assertEquals(listOf(0L, 0L, 120_000L, 120_000L), fake.gets.map { it.started })
        advance(60_000)
        assertEquals(4, fake.gets.size)
    } }

    @Test fun deferredManualReadReplacesTheLaterAutomaticPollWakeup() = runTest { fixture {
        fake.usageResult = SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(10_000))
        visible(true); manual()
        advance(12_000)
        assertEquals(listOf(0L, 10_000L, 12_000L, 12_000L), fake.gets.map { it.started })
        assertEquals(14_000L, state.refresh.usage.attempt!!.notBeforeMillis)
        assertFalse(state.refresh.refreshing)
        fake.heldPath = ReadOperation.USAGE.path
        manual()
        advance(1_999)
        assertEquals(4, fake.gets.size)
        advance(1)
        assertEquals(5, fake.gets.size)
        assertEquals(14_000L, fake.gets.last().started)
    } }

    @Test fun usageAndInventoryBecomeStaleIndependentlyAtFifteenMinutes() = runTest { fixture {
        fake.heldPath = ReadOperation.RESET_INVENTORY.path
        visible(true); manual()
        val held = fake.gets.last()
        advance(10_000)
        held.reply(SyntheticAuth.response(INVENTORY)); settle()
        assertEquals(0L, state.refresh.usage.successfulAtMillis)
        assertEquals(10_000L, state.refresh.inventory.successfulAtMillis)
        fake.heldPath = null
        fake.usageResult = SyntheticAuth.response("{}", 403)
        fake.inventoryResult = SyntheticAuth.response("{}", 403)
        advance(889_999)
        assertFalse(state.refresh.usage.stale)
        advance(1)
        assertTrue(state.refresh.usage.stale)
        assertFalse(state.refresh.inventory.stale)
        assertEquals(ReadError.FORBIDDEN, state.refresh.usage.attempt!!.error)
        advance(10_000)
        assertTrue(state.refresh.inventory.stale)
        assertEquals(ReadError.FORBIDDEN, state.refresh.inventory.attempt!!.error)
    } }

    @Test fun mixedEndpointOutcomesPreserveEachLastSuccessfulObservation() = runTest { fixture {
        manual()
        val usage = state.refresh.usage.success
        fake.usageResult = SyntheticAuth.response("invalid", 200)
        fake.inventoryResult = SyntheticAuth.response("""{"available_count":2,"credits":[null]}""")
        advance(5_000); manual()
        assertSame(usage, state.refresh.usage.success)
        assertEquals(ReadError.INVALID_RESPONSE, state.observations!!.usage.error)
        assertEquals(BigDecimal(12), state.observations!!.usage.usage!!.fiveHour.candidates.single().usedPercent.value)
        assertEquals(Completeness.PARTIAL, state.refresh.inventory.success!!.inventory!!.completeness)
        assertEquals(5_000L, state.refresh.inventory.successfulAtMillis)
        val inventory = state.refresh.inventory.success
        fake.usageResult = SyntheticAuth.response(USAGE)
        fake.inventoryResult = SyntheticAuth.response("{}", 403)
        advance(5_000); manual()
        assertSame(inventory, state.refresh.inventory.success)
        assertEquals(10_000L, state.refresh.usage.successfulAtMillis)
        assertEquals(ReadError.FORBIDDEN, state.observations!!.inventory.error)
        assertEquals(ConnectionProblem.READ, state.problem)
    } }

    @Test fun replacementRejectsLateOldGenerationPublication() = runTest { fixture {
        visible(true)
        fake.heldPath = ReadOperation.USAGE.path
        manual()
        val old = fake.gets.single()
        owner.signOut(); settle()
        assertTrue(old.cancelled)
        assertNull(state.refresh.usage.success)
        fake.heldPath = null
        owner.connect(); settle(); advance(5_000)
        assertEquals(ConnectionPhase.OBSERVED, state.phase)
        val fresh = state.refresh.usage.success
        val count = fake.gets.size
        old.reply(SyntheticAuth.response("{}", 401)); settle()
        assertSame(fresh, state.refresh.usage.success)
        assertEquals(count, fake.gets.size)
        assertEquals(0, fake.refreshes)
        assertNotNull(persistence.durable)
    } }

    @Test fun foregroundLossCancelsReadAndResumeStartsOneBoundedCycle() = runTest { fixture {
        visible(true); manual()
        val success = state.refresh.usage.success
        fake.heldPath = ReadOperation.USAGE.path
        manual()
        val old = fake.gets.last()
        visible(false)
        assertTrue(old.cancelled)
        assertFalse(state.refresh.refreshing)
        assertSame(success, state.refresh.usage.success)
        assertTrue(owner.session.snapshot() is SessionResult.Ready)
        advance(120_000)
        assertEquals(3, fake.gets.size)
        visible(true); manual()
        assertEquals(4, fake.gets.size)
        val resumed = fake.gets.last()
        assertEquals(150_000L, resumed.deadline.expiresAtMillis)
        old.reply(SyntheticAuth.response("{}", 401)); settle()
        assertTrue(state.refresh.refreshing)
        advance(30_000)
        assertTrue(resumed.cancelled)
        assertFalse(state.refresh.refreshing)
        assertEquals(1, fake.maximumHeld)
    } }

    @Test fun removingOneOfTwoVisibleObserversDoesNotCancelTheirSharedRead() = runTest { fixture {
        val second = Any()
        visible(true); owner.usageForeground(second, true); settle()
        fake.heldPath = ReadOperation.USAGE.path
        manual(); val held = fake.gets.single()
        visible(false)
        assertFalse(held.cancelled)
        owner.usageForeground(second, false); settle()
        assertTrue(held.cancelled)
        owner.usageForeground(second, false); settle()
        assertEquals(1, fake.gets.size)
    } }

    @Test fun countdownTicksMakeNoRequestsAndPassedResetRetainsPercent() = runTest { fixture {
        visible(true); manual()
        val window = state.refresh.usage.success!!.usage!!.fiveHour.candidates.single()
        val first = state.refresh.reset(window, ZoneOffset.UTC, Locale.US)
        assertEquals(TimeState.LESS_THAN_HOUR, first.state)
        advance(2_000)
        val passed = state.refresh.reset(window, ZoneOffset.UTC, Locale.US)
        assertEquals(TimeState.AWAITING_REFRESH, passed.state)
        assertEquals(TimeSnapshot.STALE, passed.snapshot)
        assertEquals(BigDecimal(12), window.usedPercent.value)
        assertEquals(2, fake.gets.size)
        assertEquals(Instant.parse("2026-01-01T00:00:02Z"), state.refresh.evaluatedAt)
    } }

    @Test fun connectCompletedInBackgroundWaitsForTheLiveSurface() = runTest { fixture {
        visible(true); visible(false)
        owner.connect(); settle(); advance(5_000)
        assertEquals(ConnectionPhase.RESTORED, state.phase)
        assertEquals(0, fake.gets.size)
        visible(true)
        assertEquals(ConnectionPhase.OBSERVED, state.phase)
        assertEquals(2, fake.gets.size)
        assertEquals(0, fake.refreshes)
    } }

    @Test fun cancellationBeforeCycleEntryCannotWedgeResumeOrAccountReplacement() = runTest { fixture {
        visible(true)
        cancelBeforeEntry()
        assertEquals(0, fake.gets.size)
        assertFalse(state.refresh.refreshing)
        visible(true)
        assertEquals(2, fake.gets.size)
        owner.readUsage(); owner.signOut(); settle()
        assertEquals(ConnectionPhase.SIGNED_OUT, state.phase)
        assertNull(state.refresh.usage.success)
        assertEquals(2, fake.gets.size)
        owner.connect(); settle(); advance(5_000)
        assertEquals(ConnectionPhase.OBSERVED, state.phase)
        assertEquals(4, fake.gets.size)
    } }

    @Test fun failedQuarantineRemovalClearsRetainedSuccessAndStopsAutomaticWork() = runTest { fixture {
        visible(true); manual()
        assertNotNull(state.refresh.usage.success)
        persistence.commitFailure = CredentialFailure.FAILED_WRITE
        persistence.deleteFailure = CredentialFailure.FAILED_WRITE
        owner.readUsage(refreshSession = true); settle()
        assertEquals(ConnectionPhase.FAILED, state.phase)
        assertEquals(ConnectionProblem.STORAGE, state.problem)
        assertNull(state.observations)
        assertNull(state.refresh.usage.success)
        assertNull(state.refresh.inventory.success)
        advance(120_000)
        assertNull(state.observations)
        assertEquals(2, fake.gets.size)
        assertEquals(1, fake.refreshes)
    } }

    @Test fun automaticPollAndResumeDoNotUnlockTransientTokenRefreshFailures() = runTest { fixture {
        visible(true); manual()
        val success = state.refresh.usage.success
        fake.usageResult = SyntheticAuth.response("{}", 401)
        fake.refreshResult = SyntheticAuth.response("{}", 503)
        manual()
        assertEquals(1, fake.refreshes)
        advance(120_000)
        visible(false); visible(true)
        assertEquals(1, fake.refreshes)
        assertSame(success, state.refresh.usage.success)
        manual()
        assertEquals(2, fake.refreshes)
        assertEquals(ReadError.TRANSIENT, state.refresh.usage.attempt!!.error)
        assertTrue(owner.session.snapshot() is SessionResult.Ready)
    } }

    @Test fun repeatedUnauthorizedReadRetiresRetainedSuccessAfterRotation() = runTest { fixture {
        visible(true); manual()
        fake.usageResult = SyntheticAuth.response("{}", 401)
        manual()
        assertEquals(ConnectionPhase.REAUTH_REQUIRED, state.phase)
        assertNull(state.refresh.usage.success)
        assertNull(persistence.durable)
        advance(120_000)
        assertEquals(4, fake.gets.size)
        assertEquals(1, fake.refreshes)
    } }

    @Test fun successfulQuarantineSettlementSurvivesForegroundCancellation() = runTest {
        terminalRemovalSurvivesForegroundCancellation(failedRemoval = false, replyAfterCancellation = false)
    }

    @Test fun failedQuarantineSettlementSurvivesForegroundCancellation() = runTest {
        terminalRemovalSurvivesForegroundCancellation(failedRemoval = true, replyAfterCancellation = false)
    }

    @Test fun independentTerminalRefreshAfterWaiterCancellationRetiresUsage() = runTest {
        terminalRemovalSurvivesForegroundCancellation(failedRemoval = false, replyAfterCancellation = true)
    }

    private suspend fun TestScope.terminalRemovalSurvivesForegroundCancellation(
        failedRemoval: Boolean, replyAfterCancellation: Boolean,
    ) = fixture {
        visible(true); manual()
        fake.holdRefresh = true
        owner.readUsage(refreshSession = true); settle()
        assertNotNull(fake.pendingRefresh)
        storage.paused = true
        if (replyAfterCancellation) {
            visible(false)
            assertFalse(fake.pendingRefresh!!.cancelled)
        }
        fake.pendingRefresh!!.reply(SyntheticAuth.response("{}", 401)); settle()
        assertEquals(1, storage.pending)
        assertNotNull(persistence.durable)
        assertEquals(ConnectionPhase.READING, state.phase)
        assertNull(state.observations)
        assertNull(state.refresh.usage.success)
        assertFalse(state.refresh.refreshing)
        visible(false); visible(true)
        assertEquals(2, fake.gets.size)
        if (failedRemoval) persistence.deleteFailure = CredentialFailure.FAILED_WRITE
        storage.release(); settle()
        assertEquals(if (failedRemoval) ConnectionPhase.FAILED else ConnectionPhase.REAUTH_REQUIRED, state.phase)
        assertEquals(if (failedRemoval) ConnectionProblem.STORAGE else ConnectionProblem.AUTH, state.problem)
        assertEquals(1, persistence.deleteCount)
        assertNull(state.refresh.inventory.success)
        val settled = state.refresh
        advance(120_000)
        assertSame(settled, state.refresh)
        assertEquals(2, fake.gets.size)
        assertEquals(1, fake.refreshes)
    }

    private suspend fun TestScope.fixture(test: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this)
        runCurrent()
        try { fixture.test() } finally { fixture.owner.close(); runCurrent() }
    }

    private class Fixture(private val test: TestScope) {
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val clock = TransportClock {
            TransportTime(Instant.parse("2026-01-01T00:00:00Z").plusMillis(test.currentTime), test.currentTime)
        }
        val fake = RoutedTransport { test.currentTime }
        val persistence = FakeCredentialPersistence()
        private var bound: SessionGeneration? = null
        private val persisted = object : CredentialPersistence by persistence {
            override fun read(): CredentialResult<CredentialEnvelope> {
                val result = persistence.read()
                return if (result is CredentialResult.Success) CredentialResult.Success(
                    CredentialEnvelope(requireNotNull(bound), result.value.accessToken, result.value.refreshToken)) else result
            }
        }
        private val store = SerializedCredentialStore(persisted, activate = { bound = it })
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val observer = Any()
        init { persistence.durable = syntheticEnvelope(store.openSession()) }
        val storage = GatedDispatcher(dispatcher)
        val owner = ConnectionController(store, DeviceCodeAuthenticator(fake, store, clock, storageDispatcher = dispatcher),
            NativeFeasibilityReader(fake, clock), scope, mutationDispatcher = dispatcher,
            storageDispatcher = storage, refreshClock = clock)
        val state get() = owner.state.value
        fun settle() = test.runCurrent()
        fun advance(millis: Long) { test.advanceTimeBy(millis); settle() }
        fun visible(visible: Boolean) { owner.usageForeground(observer, visible); settle() }
        fun manual() { owner.readUsage(); settle() }
        fun cancelBeforeEntry() { owner.readUsage(); owner.usageForeground(observer, false); settle() }
    }

    /** Holds only storage dispatch, so virtual owner/lifecycle work stays runnable. */
    private class GatedDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var paused = false
        private val queued = mutableListOf<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()
        val pending get() = queued.size
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (paused) queued += context to block else delegate.dispatch(context, block)
        }
        fun release() {
            paused = false
            val admitted = queued.toList()
            queued.clear()
            admitted.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }

    private class RoutedTransport(private val now: () -> Long) : AuthTransport {
        val gets = mutableListOf<Call>()
        var heldPath: String? = null
        var usageResult = SyntheticAuth.response(USAGE)
        var inventoryResult = SyntheticAuth.response(INVENTORY)
        var refreshes = 0
        var refreshResult = SyntheticAuth.response(SyntheticAuth.TOKENS)
        var holdRefresh = false
        var pendingRefresh: Call? = null
        private var held = 0
        var maximumHeld = 0
        override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline,
            terminal: (TransportResult) -> Unit): CancellationHandle {
            val path = request.url.encodedPath
            val call = Call(now(), deadline, terminal) { held-- }
            if (request is ProviderHttpRequest.Get) {
                replyGet(path, call)
            } else {
                val refresh = isRefresh(request)
                if (refresh) refreshes++
                val body = when (path) {
                    "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
                    "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
                    "/oauth/token" -> SyntheticAuth.TOKENS
                    else -> error("Unexpected synthetic method/path")
                }
                if (refresh && holdRefresh) pendingRefresh = call
                else call.reply(if (refresh) refreshResult else SyntheticAuth.response(body))
            }
            return CancellationHandle { call.cancel() }
        }

        private fun replyGet(path: String, call: Call) {
            gets += call
            if (path == heldPath) { held++; maximumHeld = maxOf(maximumHeld, held); call.held = true }
            else call.reply(when (path) {
                ReadOperation.USAGE.path -> usageResult
                ReadOperation.RESET_INVENTORY.path -> inventoryResult
                else -> error("Unexpected synthetic GET path")
            })
        }

        private fun isRefresh(request: ProviderHttpRequest) = request.url.encodedPath == "/oauth/token" &&
            (request as ProviderHttpRequest.FormPost).fields["grant_type"]?.copyBytes()?.toString(Charsets.UTF_8) == "refresh_token"
    }

    private class Call(val started: Long, val deadline: ReadDeadline,
        private val terminal: (TransportResult) -> Unit, private val release: () -> Unit) {
        var held = false
        var cancelled = false
        fun cancel() { cancelled = true; releaseHeld() }
        fun reply(result: TransportResult) { releaseHeld(); terminal(result) }
        private fun releaseHeld() { if (held) { held = false; release() } }
    }

    private companion object {
        const val USAGE = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12,"reset_at":1767225602}},"rate_limit_reset_credits":{"available_count":2}}"""
        const val INVENTORY = """{"available_count":2,"credits":[]}"""
    }
}
