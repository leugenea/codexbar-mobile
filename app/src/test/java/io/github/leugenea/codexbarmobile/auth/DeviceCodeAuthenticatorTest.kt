package io.github.leugenea.codexbarmobile.auth

import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** All responses are original synthetic inputs. Virtual waits never sleep or contact a provider. */
class DeviceCodeAuthenticatorTest {
    @Test fun successfulSequencePersistsCompleteUnresolvedEnvelopeOnlyOnce() = Fixture().use { f ->
        assertSame(AuthState.Idle, f.auth.state.value)
        assertTrue(f.fake.calls.isEmpty())
        f.fake.success()
        val success = f.fake.respond
        f.fake.respond = { if (f.fake.calls.size != 3) success(it) }
        f.clock.gate = CompletableDeferred()
        val states = mutableListOf<AuthState>()
        val observer = f.scope.launch { f.auth.state.collect { states += it } }
        val job = f.start()
        assertTrue(job.isActive)
        assertTrue(f.auth.state.value is AuthState.AwaitingUser)
        f.clock.gate!!.complete(Unit)
        assertSame(AuthState.Exchanging, f.auth.state.value)
        f.fake.calls.last().reply(SyntheticAuth.response(SyntheticAuth.TOKENS))
        assertTrue(job.isCompleted)
        assertEquals(listOf(5_000L), f.clock.sleeps)
        assertEquals(3, f.fake.calls.size)
        assertTrue(states.any { it is AuthState.AwaitingUser })
        assertTrue(states.contains(AuthState.Exchanging))
        val connected = f.auth.state.value as AuthState.Connected
        val saved = f.persistence.saved!!
        assertSame(connected.generation, saved.generation)
        assertSame(AccountWorkspaceBinding.Unresolved, saved.accountWorkspace)
        assertEquals("synthetic-access", text(saved.accessToken))
        assertEquals("synthetic-refresh", text(saved.refreshToken!!))
        assertEquals(1, f.persistence.commits)
        assertEquals(1, f.persistence.discards)
        f.fake.calls.forEach { assertEquals(30_000L, it.deadline.expiresAtMillis - if (it === f.fake.calls.first()) 0L else 5_000L) }
        val diagnostics = states.joinToString() + saved + f.fake.calls.joinToString { it.request.toString() }
        SyntheticAuth.secrets.forEach { assertFalse(diagnostics.contains(it)) }
        f.auth.cancel()
        assertSame(connected, f.auth.state.value)
        observer.cancel()
    }

    @Test fun numericAndStringIntervalsDrivePollFloorAndFirstPollWait() {
        listOf("0" to 3_000L, "\"1\"" to 3_000L, "8" to 8_000L, "\"8\"" to 8_000L).forEach { (interval, wait) ->
            Fixture().use { f ->
                f.fake.respond = { call -> call.reply(SyntheticAuth.response(when (f.fake.calls.size) {
                    1 -> SyntheticAuth.DEVICE.dropLast(1) + ",\"interval\":$interval}"
                    2 -> SyntheticAuth.AUTHORIZATION
                    else -> SyntheticAuth.TOKENS
                })) }
                f.start()
                assertTrue(f.auth.state.value is AuthState.Connected)
                assertEquals(listOf(wait), f.clock.sleeps)
            }
        }
    }

    @Test fun usercode429UsesExactlyThreeRetriesWithCappedExponentialBackoff() = Fixture().use { f ->
        f.fake.respond = { it.reply(SyntheticAuth.response("unparsed synthetic throttle", 429)) }
        f.start()
        assertEquals(4, f.fake.calls.size)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), f.clock.sleeps)
        f.failed(AuthStage.USERCODE, AuthFailure.RATE_LIMITED)
        assertNull(f.persistence.saved)
    }

    @Test fun usercodeRetryAfterIsClampedAndMalformedOrAbsentFallsBack() {
        listOf(RetryAfter.NotBefore(0) to 1_000L, RetryAfter.NotBefore(Long.MAX_VALUE) to 60_000L,
            RetryAfter.NotBefore(12_345) to 12_345L, RetryAfter.Invalid to 2_000L, RetryAfter.Missing to 2_000L).forEach { (retry, wait) ->
            Fixture().use { f ->
                f.fake.success()
                val success = f.fake.respond
                f.fake.respond = { call -> if (f.fake.calls.size == 1) call.reply(SyntheticAuth.response("", 429, retry)) else success(call) }
                f.start()
                assertTrue(f.auth.state.value is AuthState.Connected)
                assertEquals(wait, f.clock.sleeps.first())
                assertEquals(4, f.fake.calls.size)
            }
        }
    }

    @Test fun pending403And404AreOnlyPollSuccessesAndResetNetworkErrorStreak() = Fixture().use { f ->
        val responses = ArrayDeque<TransportResult>()
        responses.add(SyntheticAuth.response(SyntheticAuth.DEVICE))
        repeat(5) { responses.add(TransportResult.Failure(TransportFailure.NETWORK)) }
        responses.add(SyntheticAuth.response("not JSON and not needed", 403))
        repeat(5) { responses.add(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED)) }
        responses.add(SyntheticAuth.response("pending", 404))
        responses.add(SyntheticAuth.response(SyntheticAuth.AUTHORIZATION))
        responses.add(SyntheticAuth.response(SyntheticAuth.TOKENS))
        f.fake.respond = { it.reply(responses.removeFirst()) }
        f.start()
        assertTrue(f.auth.state.value is AuthState.Connected)
        assertEquals(13, f.clock.sleeps.size)
        assertEquals(15, f.fake.calls.size)
    }

    @Test fun sixthConsecutiveTransientPollErrorIsTerminal() {
        listOf(TransportFailure.NETWORK, TransportFailure.DEADLINE_EXCEEDED).forEach { failure -> Fixture().use { f ->
            f.fake.respond = { call -> call.reply(if (f.fake.calls.size == 1) SyntheticAuth.response(SyntheticAuth.DEVICE)
                else TransportResult.Failure(failure)) }
            f.start()
            assertEquals(7, f.fake.calls.size)
            assertEquals(6, f.clock.sleeps.size)
            f.failed(AuthStage.POLL, AuthFailure.NETWORK)
        } }
    }

    @Test fun allNonPendingNon200StatusesAreTerminalAtEveryStage() {
        listOf(201, 204, 302, 400, 401, 403, 404, 409, 429, 500, 503).forEach { status ->
            AuthStage.entries.filter { it != AuthStage.STORE }.forEach { stage ->
                if (stage == AuthStage.POLL && status in listOf(403, 404)) return@forEach
                Fixture().use { f ->
                    f.fake.respond = { call ->
                        val currentStage = listOf(AuthStage.USERCODE, AuthStage.POLL, AuthStage.EXCHANGE)[minOf(f.fake.calls.size - 1, 2)]
                        if (currentStage == stage || (stage == AuthStage.USERCODE && f.fake.calls.size > 1))
                            call.reply(SyntheticAuth.response("ignored synthetic error body", status))
                        else call.reply(SyntheticAuth.response(if (currentStage == AuthStage.USERCODE) SyntheticAuth.DEVICE else SyntheticAuth.AUTHORIZATION))
                    }
                    f.start()
                    f.failed(stage, if (status == 429) AuthFailure.RATE_LIMITED else AuthFailure.HTTP_STATUS)
                    assertNull(f.persistence.saved)
                }
            }
        }
    }

    @Test fun nonTransientTransportFailuresNeverPollAgainOrPublishRawExceptions() {
        listOf(TransportFailure.INVALID_RESPONSE, TransportFailure.BODY_TOO_LARGE, TransportFailure.CANCELLED).forEach { failure ->
            Fixture().use { f ->
                f.fake.respond = { call -> call.reply(if (f.fake.calls.size == 1) SyntheticAuth.response(SyntheticAuth.DEVICE)
                    else TransportResult.Failure(failure)) }
                f.start()
                assertEquals(2, f.fake.calls.size)
                if (failure == TransportFailure.CANCELLED) assertSame(AuthState.Cancelled, f.auth.state.value)
                else f.failed(AuthStage.POLL, AuthFailure.TRANSPORT)
            }
        }
        Fixture().use { f ->
            f.fake.respond = { throw IllegalStateException("synthetic-verifier", RuntimeException("synthetic-access")) }
            val job = f.start()
            assertTrue(job.isCompleted)
            assertFalse(job.isCancelled)
            f.failed(AuthStage.USERCODE, AuthFailure.TRANSPORT)
        }
    }

    @Test fun usercodeAndExchangeNetworkOrRequestTimeoutDoNotRetry() {
        listOf(AuthStage.USERCODE, AuthStage.EXCHANGE).forEach { stage ->
            listOf(TransportFailure.NETWORK, TransportFailure.DEADLINE_EXCEEDED).forEach { failure -> Fixture().use { f ->
                f.fake.success()
                val success = f.fake.respond
                f.fake.respond = { call -> if ((stage == AuthStage.USERCODE && f.fake.calls.size == 1) ||
                    (stage == AuthStage.EXCHANGE && f.fake.calls.size == 3)) call.reply(TransportResult.Failure(failure)) else success(call) }
                f.start()
                f.failed(stage, if (failure == TransportFailure.NETWORK) AuthFailure.NETWORK else AuthFailure.DEADLINE)
                assertEquals(if (stage == AuthStage.USERCODE) 1 else 3, f.fake.calls.size)
            } }
        }
    }

    @Test fun fifteenMinuteMonotonicPollDeadlineStopsBeforeNextRequest() = Fixture().use { f ->
        f.fake.respond = { call -> call.reply(if (f.fake.calls.size == 1) SyntheticAuth.response(SyntheticAuth.DEVICE)
            else SyntheticAuth.response("pending", 404)) }
        f.start()
        f.failed(AuthStage.POLL, AuthFailure.DEADLINE)
        assertEquals(900_000L, f.clock.millis)
        assertEquals(180, f.clock.sleeps.size)
        assertEquals(180, f.fake.calls.size) // usercode + 179 polls; no poll at the deadline.
        assertEquals(900_000L, f.fake.calls.last().deadline.expiresAtMillis)
    }

    @Test fun oversizedIntervalExpiresWithoutEarlyPollOrOverflow() = Fixture().use { f ->
        f.fake.respond = { it.reply(SyntheticAuth.response(SyntheticAuth.DEVICE.dropLast(1) + ",\"interval\":\"999999999999999999999999\"}")) }
        f.start()
        assertEquals(1, f.fake.calls.size)
        assertEquals(listOf(900_000L), f.clock.sleeps)
        f.failed(AuthStage.POLL, AuthFailure.DEADLINE)
    }

    @Test fun lateSuccessCannotBeatPollOrRequestDeadline() {
        listOf(30_000L, 900_000L).forEach { elapsed -> Fixture().use { f ->
            f.fake.respond = { call -> when (f.fake.calls.size) {
                1 -> call.reply(SyntheticAuth.response(SyntheticAuth.DEVICE))
                2 -> { f.clock.millis += elapsed; call.reply(SyntheticAuth.response(SyntheticAuth.AUTHORIZATION)) }
                else -> call.reply(SyntheticAuth.response("terminal synthetic error", 400))
            } }
            f.start()
            if (elapsed == 900_000L) f.failed(AuthStage.POLL, AuthFailure.DEADLINE)
            else f.failed(AuthStage.POLL, AuthFailure.HTTP_STATUS)
            assertTrue(f.fake.calls.none { it.request is ProviderHttpRequest.FormPost })
        } }
        Fixture().use { f ->
            f.fake.respond = { call -> f.clock.millis += 30_000; call.reply(SyntheticAuth.response(SyntheticAuth.DEVICE)) }
            f.start()
            f.failed(AuthStage.USERCODE, AuthFailure.DEADLINE)
        }
    }

    @Test fun saturatedClockOriginStillHasBoundedDeadline() = Fixture().use { f ->
        f.clock.millis = Long.MAX_VALUE - 4_000L
        f.fake.respond = { it.reply(SyntheticAuth.response(SyntheticAuth.DEVICE)) }
        f.start()
        f.failed(AuthStage.POLL, AuthFailure.DEADLINE)
        assertEquals(Long.MAX_VALUE, f.clock.millis)
        assertEquals(listOf(4_000L), f.clock.sleeps)
        assertEquals(1, f.fake.calls.size)
    }

    @Test fun cancelDuringPollSleepClearsCodeAndStartsNoPoll() = Fixture().use { f ->
        f.clock.gate = CompletableDeferred()
        f.fake.respond = { it.reply(SyntheticAuth.response(SyntheticAuth.DEVICE)) }
        val job = f.start()
        assertTrue(job.isActive)
        assertTrue(f.auth.state.value is AuthState.AwaitingUser)
        assertEquals(1, f.clock.sleeps.size)
        f.auth.cancel()
        assertTrue(job.isCancelled)
        assertSame(AuthState.Cancelled, f.auth.state.value)
        f.clock.gate!!.complete(Unit)
        assertEquals(1, f.fake.calls.size)
        assertNull(f.persistence.saved)
    }

    @Test fun cancelDuring429BackoffPreventsRetry() = Fixture().use { f ->
        f.clock.gate = CompletableDeferred()
        f.fake.respond = { it.reply(SyntheticAuth.response("", 429)) }
        val job = f.start()
        assertEquals(listOf(2_000L), f.clock.sleeps)
        f.auth.cancel()
        assertTrue(job.isCancelled)
        f.clock.gate!!.complete(Unit)
        assertEquals(1, f.fake.calls.size)
        assertSame(AuthState.Cancelled, f.auth.state.value)
    }

    @Test fun cancelAtEachNetworkStageCancelsHandleAndRejectsLateCallback() {
        (1..3).forEach { held -> Fixture().use { f ->
            f.fake.success()
            val success = f.fake.respond
            f.fake.respond = { if (f.fake.calls.size != held) success(it) }
            val job = f.start()
            assertEquals(held, f.fake.calls.size)
            assertTrue(job.isActive)
            f.auth.cancel()
            val call = f.fake.calls.last()
            assertTrue(call.cancelled)
            call.reply(SyntheticAuth.response(if (held == 3) SyntheticAuth.TOKENS else SyntheticAuth.AUTHORIZATION))
            assertEquals(held, f.fake.calls.size)
            assertNull(f.persistence.saved)
            assertSame(AuthState.Cancelled, f.auth.state.value)
        } }
    }

    @Test fun cancellationBeforeTransportHandleReturnStillCancelsInstalledHandle() = Fixture().use { f ->
        f.fake.respond = { call -> f.auth.cancel(); call.reply(SyntheticAuth.response(SyntheticAuth.DEVICE)) }
        f.start()
        assertTrue(f.fake.calls.single().cancelled)
        assertSame(AuthState.Cancelled, f.auth.state.value)
        assertTrue(f.clock.sleeps.isEmpty())
    }

    @Test fun duplicateTransportCallbacksCannotCauseDuplicateExchangeOrCommit() = Fixture().use { f ->
        f.fake.success()
        val success = f.fake.respond
        f.fake.respond = { call -> success(call); success(call); call.reply(TransportResult.Failure(TransportFailure.NETWORK)) }
        f.start()
        assertTrue(f.auth.state.value is AuthState.Connected)
        assertEquals(3, f.fake.calls.size)
        assertEquals(1, f.persistence.commits)
    }

    @Test fun ownerReplacementAtEachNetworkStageCancelsOldWorkAndRejectsLateExchange() {
        (1..3).forEach { held -> Fixture().use { f ->
            f.fake.success()
            val success = f.fake.respond
            f.fake.respond = { if (f.fake.calls.size != held) success(it) }
            val oldJob = f.start()
            val oldCall = f.fake.calls.last()
            f.fake.success()
            f.start()
            assertTrue(oldJob.isCancelled)
            assertTrue(oldCall.cancelled)
            val connected = f.auth.state.value as AuthState.Connected
            val saved = f.persistence.saved
            oldCall.reply(SyntheticAuth.response(SyntheticAuth.TOKENS))
            assertSame(connected, f.auth.state.value)
            assertSame(saved, f.persistence.saved)
            assertEquals(1, f.persistence.commits)
            assertEquals(held + 3, f.fake.calls.size)
        } }
    }

    @Test fun ownerReplacementDuringPollSleepStopsOldPolling() = Fixture().use { f ->
        f.fake.success()
        f.clock.gate = CompletableDeferred()
        val oldJob = f.start()
        val oldGate = f.clock.gate!!
        f.clock.gate = null
        f.start()
        assertTrue(oldJob.isCancelled)
        assertTrue(f.auth.state.value is AuthState.Connected)
        oldGate.complete(Unit)
        assertEquals(4, f.fake.calls.size)
        assertEquals(1, f.persistence.commits)
    }

    @Test fun externallyReplacedCredentialGenerationRejectsLateExchangeInA3Kernel() = Fixture().use { f ->
        f.fake.success()
        val success = f.fake.respond
        f.fake.respond = { if (f.fake.calls.size != 3) success(it) }
        f.start()
        assertSame(AuthState.Exchanging, f.auth.state.value)
        f.store.openSession()
        f.fake.calls.last().reply(SyntheticAuth.response(SyntheticAuth.TOKENS))
        f.failed(AuthStage.EXCHANGE, AuthFailure.STALE_OWNER)
        assertNull(f.persistence.saved)
        assertEquals(0, f.persistence.commits)
    }

    @Test fun cancelOrOwnerReplacementDuringStagingCannotCommit() {
        listOf(false, true).forEach { replace -> Fixture().use { f ->
            f.fake.success()
            f.persistence.beforePrepare = { if (replace) f.store.openSession() else f.auth.cancel() }
            f.start()
            assertEquals(0, f.persistence.commits)
            assertEquals(1, f.persistence.discards)
            assertNull(f.persistence.saved)
            if (replace) f.failed(AuthStage.STORE, AuthFailure.STALE_OWNER) else assertSame(AuthState.Cancelled, f.auth.state.value)
        } }
    }

    @Test fun parentAndReturnedJobCancellationOwnRequestsAndDisplayState() {
        listOf(false, true).forEach { parent -> Fixture().use { f ->
            f.fake.success()
            val success = f.fake.respond
            f.fake.respond = { if (f.fake.calls.size != 3) success(it) }
            val job = f.start()
            if (parent) f.root.cancel() else job.cancel()
            assertTrue(f.fake.calls.last().cancelled)
            assertSame(AuthState.Cancelled, f.auth.state.value)
            f.fake.calls.last().reply(SyntheticAuth.response(SyntheticAuth.TOKENS))
            assertNull(f.persistence.saved)
        } }
        Fixture().use { f ->
            f.fake.success()
            f.clock.gate = CompletableDeferred()
            f.start()
            assertTrue(f.auth.state.value is AuthState.AwaitingUser)
            f.root.cancel()
            assertSame(AuthState.Cancelled, f.auth.state.value)
        }
    }

    @Test fun alreadyCancelledScopeDoesNotLeaveRequestingStateOrMakeTraffic() = Fixture().use { f ->
        f.root.cancel()
        val job = f.start()
        assertTrue(job.isCancelled)
        assertSame(AuthState.Cancelled, f.auth.state.value)
        assertTrue(f.fake.calls.isEmpty())
    }

    @Test fun parentCancellationDuringStagingUsesImmediateCredentialCancellationCapability() = Fixture().use { f ->
        f.fake.success()
        f.persistence.beforePrepare = { f.root.cancel() }
        f.start()
        assertNull(f.persistence.saved)
        assertEquals(0, f.persistence.commits)
        assertEquals(1, f.persistence.discards)
        assertSame(AuthState.Cancelled, f.auth.state.value)
    }

    @Test fun persistenceFailureAndCancellationAreCategorical() {
        CredentialFailure.entries.forEach { failure -> Fixture().use { f ->
            f.fake.success()
            f.persistence.failure = failure
            f.start()
            when (failure) {
                CredentialFailure.CANCELLED -> assertSame(AuthState.Cancelled, f.auth.state.value)
                CredentialFailure.STALE_GENERATION -> f.failed(AuthStage.STORE, AuthFailure.STALE_OWNER)
                else -> f.failed(AuthStage.STORE, AuthFailure.STORAGE)
            }
            assertNull(f.persistence.saved)
        } }
    }

    @Test fun malformedSuccessAtEachStageCannotReachPersistence() {
        listOf(AuthStage.USERCODE, AuthStage.POLL, AuthStage.EXCHANGE).forEach { stage ->
            listOf("{}", "null", "{", "[]", "{\"extra\":1,\"extra\":2}").forEach { body -> Fixture().use { f ->
                f.fake.success()
                val success = f.fake.respond
                val selected = stage.ordinal + 1
                f.fake.respond = { call -> if (f.fake.calls.size == selected) call.reply(SyntheticAuth.response(body)) else success(call) }
                f.start()
                f.failed(stage, AuthFailure.MALFORMED)
                assertEquals(selected, f.fake.calls.size)
                assertNull(f.persistence.saved)
            } }
        }
    }

    @Test fun exchangeWithoutRefreshTokenCannotPublishOrKeepEarlierRefresh() = Fixture().use { f ->
        f.fake.success()
        val success = f.fake.respond
        f.fake.respond = { call -> if (f.fake.calls.size == 3) call.reply(SyntheticAuth.response("""{"access_token":"synthetic-access"}""")) else success(call) }
        f.start()
        f.failed(AuthStage.EXCHANGE, AuthFailure.MALFORMED)
        assertNull(f.persistence.saved)
        assertEquals(0, f.persistence.commits)
    }

    @Test fun awaitingObserverCancellationCannotAdmitPoll() = Fixture().use { f ->
        f.fake.success()
        f.clock.gate = CompletableDeferred()
        var reached = false
        val observer = f.scope.launch { f.auth.state.collect { if (it is AuthState.AwaitingUser) { reached = true; f.auth.cancel() } } }
        f.start()
        assertTrue(reached)
        assertSame(AuthState.Cancelled, f.auth.state.value)
        assertEquals(1, f.fake.calls.size)
        assertEquals(listOf(5_000L), f.clock.sleeps)
        f.clock.gate!!.complete(Unit)
        assertEquals(1, f.fake.calls.size)
        observer.cancel()
    }

    @Test fun completionHandlerStartingOwnerDuringReplacementCannotOrphanNetworkWork() = Fixture().use { f ->
        val old = f.start()
        var nested: Job? = null
        old.invokeOnCompletion { nested = f.start() }
        val superseded = f.start()
        assertTrue(old.isCancelled)
        assertTrue(superseded.isCancelled)
        assertTrue(nested!!.isActive)
        assertEquals(2, f.fake.calls.size)
        assertSame(AuthState.RequestingCode, f.auth.state.value)
        f.auth.cancel()
        assertTrue(nested.isCancelled)
        assertTrue(f.fake.calls.all { it.cancelled })
        assertSame(AuthState.Cancelled, f.auth.state.value)
    }

    @Test fun completionHandlerStartingOwnerDuringCancelKeepsNewStateAndCancellationOwnership() = Fixture().use { f ->
        val old = f.start()
        var nested: Job? = null
        old.invokeOnCompletion { nested = f.start() }
        f.auth.cancel()
        assertTrue(old.isCancelled)
        assertTrue(nested!!.isActive)
        assertEquals(2, f.fake.calls.size)
        assertSame(AuthState.RequestingCode, f.auth.state.value)
        f.auth.cancel()
        assertTrue(nested.isCancelled)
        assertTrue(f.fake.calls.all { it.cancelled })
        assertSame(AuthState.Cancelled, f.auth.state.value)
    }

    private fun text(value: SensitiveValue) = value.copyBytes().toString(Charsets.UTF_8)

    private class Fixture : AutoCloseable {
        val root = SupervisorJob()
        val scope = CoroutineScope(root + Dispatchers.Unconfined)
        val clock = AuthClock()
        val fake = AuthFake()
        val persistence = AuthPersistence()
        val store = SerializedCredentialStore(persistence)
        val auth = DeviceCodeAuthenticator(fake, store, clock, clock::pause)
        fun start() = auth.start(scope)
        fun failed(stage: AuthStage, category: AuthFailure) {
            assertEquals(AuthState.Failed(stage, category), auth.state.value)
            SyntheticAuth.secrets.forEach { assertFalse(auth.state.value.toString().contains(it)) }
        }
        override fun close() { auth.cancel(); root.cancel() }
    }
}
