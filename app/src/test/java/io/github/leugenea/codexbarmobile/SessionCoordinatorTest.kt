package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Entirely synthetic; positive callback/staging gates, no sockets or token-clock guesses. */
class SessionCoordinatorTest {
    @Test fun concurrentUnauthorizedReadersShareOneRefreshAndUseRotatedTokenNextTime() = runBlocking {
        Harness().use { h ->
            val first = async(Dispatchers.Unconfined) { h.refresh() }
            val second = async(Dispatchers.Unconfined) { h.refresh() }
            assertEquals(1, h.fake.calls.size)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            val request = h.fake.calls.single().request as ProviderHttpRequest.FormPost
            assertEquals(setOf("grant_type", "refresh_token", "client_id"), request.fields.keys)
            assertEquals("refresh_token", text(request.fields.getValue("grant_type")))
            assertEquals(text(h.initial.refreshToken!!), text(request.fields.getValue("refresh_token")))
            assertEquals(AuthProtocol.CLIENT_ID, text(request.fields.getValue("client_id")))
            assertEquals("https://auth.openai.com/oauth/token", request.url.toString())
            h.reply()
            val ready = first.await() as SessionResult.Ready
            assertEquals("SessionResult.Ready(redacted)", ready.toString())
            val updated = ready.envelope
            assertSame(updated, (second.await() as SessionResult.Ready).envelope)
            assertSame(updated, h.persistence.durable)
            assertSame(updated, (h.session.snapshot() as SessionResult.Ready).envelope)
            val next = async(Dispatchers.Unconfined) { h.session.refresh(updated, h.session.deadline()) }
            assertEquals("synthetic-rotated-refresh", text((h.fake.calls.last().request as ProviderHttpRequest.FormPost).fields.getValue("refresh_token")))
            h.reply()
            assertTrue(next.await() is SessionResult.Ready)
        }
    }

    @Test fun completeEnvelopeIsDurableBeforeRotationBecomesObservable() = runBlocking {
        Harness().use { h ->
            val pending = async(Dispatchers.Unconfined) { h.refresh() }
            val gate = ControlledGate()
            h.persistence.stagingGate = gate
            ControlledWorker { h.reply() }.use { worker ->
                try {
                    gate.awaitEntered()
                    assertFalse(pending.isCompleted)
                    assertSame(h.initial, h.persistence.durable)
                    assertSame(h.initial, (h.session.snapshot() as SessionResult.Ready).envelope)
                } finally { gate.release() }
                worker.result()
            }
            assertSame(h.persistence.durable, (pending.await() as SessionResult.Ready).envelope)
        }
    }

    @Test fun logoutDuringUncooperativeRotationStagingCannotResurrectCredentials() = runBlocking {
        Harness().use { h ->
            val pending = async(Dispatchers.Unconfined) { h.refresh() }
            val gate = ControlledGate()
            h.persistence.stagingGate = gate
            ControlledWorker { h.reply() }.use { worker ->
                try {
                    gate.awaitEntered()
                    h.session.retire()
                    assertNull(h.persistence.durable)
                } finally { gate.release() }
                worker.result()
            }
            assertEquals(SessionResult.Failed(SessionProblem.STALE), pending.await())
            assertNull(h.persistence.durable)
            assertTrue(h.session.snapshot() is SessionResult.Failed)
            assertEquals(1, h.persistence.commitCount)
        }
    }

    @Test fun logoutDuringNetworkRefreshCancelsAndRejectsLateResult() = runBlocking {
        Harness().use { h ->
            val pending = async(Dispatchers.Unconfined) { h.refresh() }
            h.session.retire()
            assertTrue(h.fake.calls.single().cancelled)
            h.reply()
            assertEquals(SessionResult.Failed(SessionProblem.STALE), pending.await())
            assertNull(h.persistence.durable)
        }
    }

    @Test fun accountReplacementRejectsOldGenerationAndLateRefreshCannotDeleteNewSession() = runBlocking {
        Harness().use { h ->
            val pending = async(Dispatchers.Unconfined) { h.refresh() }
            h.session.retire()
            val newer = syntheticEnvelope(h.store.openSession(), "new-account")
            h.store.replace(newer, CredentialCancellation())
            h.session.adopt(newer)
            h.reply()
            assertEquals(SessionResult.Failed(SessionProblem.STALE), pending.await())
            assertSame(newer, (h.session.snapshot() as SessionResult.Ready).envelope)
            assertSame(newer, h.persistence.durable)
            assertEquals(SessionResult.Failed(SessionProblem.STALE), h.refresh())
            h.session.requireReauthorization(h.initial)
            assertSame(newer, h.persistence.durable)
        }
    }

    @Test fun failedWriteAfterServerRotationQuarantinesOldTokenInsteadOfRetryingIt() = runBlocking {
        for (prepare in listOf(true, false)) Harness().use { h ->
            if (prepare) h.persistence.prepareFailure = CredentialFailure.FAILED_WRITE
            else h.persistence.commitFailure = CredentialFailure.FAILED_WRITE
            val pending = async(Dispatchers.Unconfined) { h.refresh() }
            h.reply()
            assertEquals(SessionResult.Failed(SessionProblem.STORAGE), pending.await())
            assertNull(h.persistence.durable)
            assertEquals(SessionResult.Failed(SessionProblem.STORAGE), h.refresh())
            assertEquals(1, h.fake.calls.size)
        }
    }

    @Test fun rotationMarkerAdmissionFailureRequiresReauthWithoutSendingAToken() = runBlocking {
        Harness(decorate = { actual -> object : CredentialStore by actual {
            override fun beginRotation(generation: SessionGeneration) = CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
        } }).use { h ->
            assertEquals(SessionResult.Failed(SessionProblem.STORAGE), h.refresh())
            assertTrue(h.fake.calls.isEmpty())
            assertNull(h.persistence.durable)
            assertEquals(CredentialResult.Failure(CredentialFailure.STALE_GENERATION), h.store.read(h.initial.generation))
        }
    }

    @Test fun failedMarkerClearQuarantinesBothRotatedAndTransientEnvelopes() = runBlocking {
        for (response in listOf(SyntheticAuth.response(ROTATED), SyntheticAuth.response("{}", 503))) {
            Harness(decorate = { actual -> object : CredentialStore by actual {
                override fun finishRotation(generation: SessionGeneration) = CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
            } }).use { h ->
                val pending = async(Dispatchers.Unconfined) { h.refresh() }
                h.fake.calls.single().reply(response)
                assertEquals(SessionResult.Failed(SessionProblem.STORAGE), pending.await())
                assertNull(h.persistence.durable)
                assertEquals(SessionResult.Failed(SessionProblem.STORAGE), h.refresh())
                assertEquals(1, h.fake.calls.size)
            }
        }
    }

    @Test fun failedQuarantineDeletionStillRetiresRuntimeCapability() = runBlocking {
        Harness().use { h ->
            h.persistence.prepareFailure = CredentialFailure.FAILED_WRITE
            h.persistence.deleteFailure = CredentialFailure.FAILED_WRITE
            val pending = async(Dispatchers.Unconfined) { h.refresh() }
            h.reply()
            assertEquals(SessionResult.Failed(SessionProblem.STORAGE), pending.await())
            assertEquals(CredentialResult.Failure(CredentialFailure.STALE_GENERATION), h.store.read(h.initial.generation))
            assertEquals(SessionResult.Failed(SessionProblem.STORAGE), h.refresh())
        }
    }

    @Test fun sourceBackedTerminalRefreshFailuresRequireReauthorizationWithoutQuota() = runBlocking {
        val cases = listOf(
            401 to "not-json", 403 to "{}",
            400 to """{"error":"invalid_grant","error_description":"synthetic-private-description"}""",
            400 to """{"error":{"code":"refresh_token_reused","message":"synthetic-private-description"}}""",
            400 to """{"error":{"type":"invalid_token"}}""",
            400 to """{"error":{"code":"invalid_request"}}""",
        )
        for ((status, body) in cases) Harness().use { h ->
            val pending = async(Dispatchers.Unconfined) { h.refresh() }
            h.fake.calls.single().reply(SyntheticAuth.response(body, status))
            assertEquals(SessionResult.Failed(SessionProblem.REAUTHORIZE), pending.await())
            assertNull(h.persistence.durable)
            assertFalse(h.session.snapshot().toString().contains("synthetic"))
            assertEquals(SessionResult.Failed(SessionProblem.REAUTHORIZE), h.refresh())
        }
    }

    @Test fun networkServerMalformedAndRateLimitFailuresRemainTransientAndShared() = runBlocking {
        val cases = listOf(
            TransportResult.Failure(TransportFailure.NETWORK) to SessionProblem.TRANSIENT,
            SyntheticAuth.response("{}", 503) to SessionProblem.TRANSIENT,
            SyntheticAuth.response("""{"error":"invalid_grant"}""", 503) to SessionProblem.TRANSIENT,
            SyntheticAuth.response("{}", 429) to SessionProblem.RATE_LIMITED,
            SyntheticAuth.response("not-json") to SessionProblem.MALFORMED,
            SyntheticAuth.response("{}") to SessionProblem.MALFORMED,
            SyntheticAuth.response("{}", 400) to SessionProblem.TRANSIENT,
        )
        for ((reply, expected) in cases) Harness().use { h ->
            val first = async(Dispatchers.Unconfined) { h.refresh() }
            val second = async(Dispatchers.Unconfined) { h.refresh() }
            h.fake.calls.single().reply(reply)
            assertEquals(SessionResult.Failed(expected), first.await())
            assertEquals(first.await(), second.await())
            assertSame(h.initial, h.persistence.durable)
            assertSame(h.initial, (h.session.snapshot() as SessionResult.Ready).envelope)
            assertEquals(first.await(), h.refresh())
            assertEquals(1, h.fake.calls.size)
            h.session.retryTransient()
            val retry = async(Dispatchers.Unconfined) { h.refresh() }
            assertEquals(2, h.fake.calls.size)
            h.reply()
            assertTrue(retry.await() is SessionResult.Ready)
        }
    }

    @Test fun optionalRotationRetainsRefreshAndUntrustedExpiryNeverCreatesATtl() = runBlocking {
        Harness().use { h ->
            val pending = async(Dispatchers.Unconfined) { h.refresh() }
            h.fake.calls.single().reply(SyntheticAuth.response("""{"access_token":"synthetic-new-access","expires_in":1,"id_token":"synthetic-private"}"""))
            val updated = (pending.await() as SessionResult.Ready).envelope
            assertSame(h.initial.refreshToken, updated.refreshToken)
            h.clock.millis += 365L * 24 * 60 * 60 * 1000
            assertSame(updated, (h.session.snapshot() as SessionResult.Ready).envelope)
        }
    }

    @Test fun cancellingAWaiterDoesNotCancelTheSharedFlight() = runBlocking {
        Harness().use { h ->
            val cancelled = async(Dispatchers.Unconfined) { h.refresh() }
            val retained = async(Dispatchers.Unconfined) { h.refresh() }
            cancelled.cancelAndJoin()
            assertFalse(h.fake.calls.single().cancelled)
            h.reply()
            assertTrue(retained.await() is SessionResult.Ready)
        }
    }

    @Test fun expiredWaiterStillSettlesAcceptedRotationWithoutReusingConsumedToken() = runBlocking {
        Harness().use { h ->
            val deadline = h.session.deadline()
            val pending = async(Dispatchers.Unconfined) { h.session.refresh(h.initial, deadline) }
            h.clock.millis += 30_000
            assertEquals(SessionResult.Failed(SessionProblem.TRANSIENT), h.session.refresh(h.initial, deadline))
            assertEquals(1, h.fake.calls.size)
            h.reply() // Accepted transport result whose continuation resumes after its read deadline.
            assertEquals(SessionResult.Failed(SessionProblem.TRANSIENT), pending.await())
            val settled = (h.session.snapshot() as SessionResult.Ready).envelope
            assertSame(settled, h.persistence.durable)
            assertNotSame(h.initial, settled)
            assertEquals("synthetic-rotated-refresh", text(settled.refreshToken!!))
            h.session.retryTransient()
            val next = async(Dispatchers.Unconfined) { h.session.refresh(settled, h.session.deadline()) }
            val sent = (h.fake.calls.last().request as ProviderHttpRequest.FormPost).fields.getValue("refresh_token")
            assertEquals("synthetic-rotated-refresh", text(sent))
            h.reply()
            assertTrue(next.await() is SessionResult.Ready)
        }
    }

    @Test fun expiredReadCannotAdmitANewRefreshRequest() = runBlocking {
        Harness().use { h ->
            val deadline = h.session.deadline()
            h.clock.millis += 30_000
            assertEquals(SessionResult.Failed(SessionProblem.TRANSIENT), h.session.refresh(h.initial, deadline))
            assertTrue(h.fake.calls.isEmpty())
            assertSame(h.initial, h.persistence.durable)
        }
    }

    @Test fun missingRefreshTokenRequiresLoginWithoutRequests() = runBlocking {
        Harness(refresh = false).use { h ->
            assertEquals(SessionResult.Failed(SessionProblem.REAUTHORIZE), h.refresh())
            assertTrue(h.fake.calls.isEmpty())
            assertNull(h.persistence.durable)
        }
    }

    @Test fun transportExceptionsAreCategoricalAndNeverExposeTheirCause() = runBlocking {
        Harness().use { h ->
            h.fake.respond = { throw IllegalStateException("synthetic-private-description") }
            val result = h.refresh()
            assertEquals(SessionResult.Failed(SessionProblem.TRANSIENT), result)
            assertFalse(result.toString().contains("synthetic-private"))
            assertSame(h.initial, h.persistence.durable)
        }
    }

    private class Harness(refresh: Boolean = true, decorate: (CredentialStore) -> CredentialStore = { it }) : AutoCloseable {
        val fake = AuthFake()
        val clock = AuthClock()
        val persistence = FakeCredentialPersistence()
        val store = decorate(SerializedCredentialStore(persistence, removalExecutor = java.util.concurrent.Executor { it.run() }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = SessionCoordinator(store, fake, scope, clock)
        val initial = syntheticEnvelope(store.openSession(), refresh = refresh)
        init { store.replace(initial, CredentialCancellation()); session.adopt(initial) }
        suspend fun refresh() = session.refresh(initial, session.deadline())
        fun reply() = fake.calls.last().reply(SyntheticAuth.response(ROTATED))
        override fun close() { session.retire(); scope.cancel() }
    }

    private companion object {
        const val ROTATED = """{"access_token":"synthetic-rotated-access","refresh_token":"synthetic-rotated-refresh"}"""
        fun text(value: SensitiveValue) = value.copyBytes().toString(Charsets.UTF_8)
    }
}
