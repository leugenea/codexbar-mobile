package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Original synthetic wire fixtures; same A2 policy and A9 decoders as production. */
class AuthenticatedProviderReaderTest {
    @Test fun usage401RefreshesOnceAndBothReadsUseDurablyRotatedBearer() = runBlocking {
        Harness().use { h ->
            var usageRequests = 0
            h.fake.respond = { call ->
                val reply = when (call.request) {
                    is ProviderHttpRequest.FormPost -> SyntheticAuth.response(ROTATED)
                    else -> if (call.request.url.encodedPath == ReadOperation.USAGE.path && usageRequests++ == 0)
                        SyntheticAuth.response("{}", 401) else SyntheticAuth.response("{}")
                }
                h.clock.millis++
                call.reply(reply)
            }
            val facts = h.reader.read()
            assertTrue(facts.successful)
            assertEquals(1, h.fake.calls.count { it.request is ProviderHttpRequest.FormPost })
            assertEquals(2, usageRequests)
            val gets = h.fake.calls.map { it.request }.filterIsInstance<ProviderHttpRequest.Get>()
            assertEquals("synthetic-access-initial", text(gets.first().bearer))
            assertEquals(listOf("synthetic-rotated-access", "synthetic-rotated-access"), gets.drop(1).map { text(it.bearer) })
            assertSame(h.persistence.durable, (h.session.snapshot() as SessionResult.Ready).envelope)
            assertTrue(facts.usage.observedAt!! < facts.inventory.observedAt!!)
        }
    }

    @Test fun second401RequiresReauthorizationAndNeverRequestsASecondRefresh() = runBlocking {
        Harness().use { h ->
            h.fake.respond = { call -> call.reply(SyntheticAuth.response(
                if (call.request is ProviderHttpRequest.FormPost) ROTATED else "{}",
                if (call.request is ProviderHttpRequest.Get) 401 else 200)) }
            val facts = h.reader.read()
            assertEquals(ReadError.REAUTHORIZE, facts.usage.error)
            assertEquals(ReadError.REAUTHORIZE, facts.inventory.error)
            assertNull(facts.usage.usage)
            assertNull(h.persistence.durable)
            assertEquals(3, h.fake.calls.size)
            assertEquals(1, h.fake.calls.count { it.request is ProviderHttpRequest.FormPost })
        }
    }

    @Test fun oneRefreshBudgetSpansUsageAndInventory() = runBlocking {
        Harness().use { h ->
            var usageRequests = 0
            h.fake.respond = { call ->
                if (call.request is ProviderHttpRequest.FormPost) call.reply(SyntheticAuth.response(ROTATED))
                else call.reply(SyntheticAuth.response("{}", if (call.request.url.encodedPath == ReadOperation.USAGE.path && usageRequests++ > 0) 200 else 401))
            }
            val facts = h.reader.read()
            assertEquals(ReadError.REAUTHORIZE, facts.usage.error)
            assertEquals(1, h.fake.calls.count { it.request is ProviderHttpRequest.FormPost })
            assertNull(facts.usage.usage)
        }
    }

    @Test fun allSevenResearchPolicyMeaningsExecuteThroughAuthenticatedReader() = runBlocking {
        // Exact semantic cases in policy-vectors.json, without modifying attributed fixtures.
        val vectors = listOf(
            Triple(401, 200, null), Triple(401, 401, ReadError.REAUTHORIZE),
            Triple(403, 200, ReadError.FORBIDDEN), Triple(429, 200, ReadError.RATE_LIMITED),
            Triple(null, 200, ReadError.TRANSIENT), Triple(503, 200, ReadError.TRANSIENT),
            Triple(200, 200, null),
        )
        for ((status, refreshStatus, error) in vectors) Harness().use { h ->
            var usageRequests = 0
            h.fake.respond = { call ->
                val reply = when {
                    call.request is ProviderHttpRequest.FormPost -> SyntheticAuth.response(ROTATED, refreshStatus)
                    status == null -> TransportResult.Failure(TransportFailure.NETWORK)
                    status == 401 && usageRequests++ > 0 -> SyntheticAuth.response("{}")
                    else -> SyntheticAuth.response("{}", status, RetryAfter.NotBefore(40_000))
                }
                h.clock.millis++
                call.reply(reply)
            }
            val facts = h.reader.read()
            assertEquals(error, facts.usage.error)
            assertEquals(error == ReadError.REAUTHORIZE, h.session.snapshot() is SessionResult.Failed)
            if (error != null) assertNull(facts.usage.usage)
        }
    }

    @Test fun logoutWhileReadWaitsDiscardsBothPayloadsAndLateCallbacks() = runBlocking {
        Harness().use { h ->
            val pending = async(Dispatchers.Unconfined) { h.reader.read() }
            assertEquals(1, h.fake.calls.size)
            h.session.retire()
            h.store.delete(h.initial.generation)
            h.fake.calls.single().reply(SyntheticAuth.response("{}"))
            val facts = pending.await()
            assertNull(facts.usage.usage)
            assertNull(facts.inventory.inventory)
            assertEquals(ReadError.CANCELLED, facts.usage.error)
            assertEquals(1, h.fake.calls.size)
        }
    }

    @Test fun late401FromOldEnvelopeCannotRevokeDurablyRotatedCredentials() = runBlocking {
        Harness().use { h ->
            val reading = async(Dispatchers.Unconfined) { h.reader.read() }
            val oldRead = h.fake.calls.single()
            val rotating = async(Dispatchers.Unconfined) { h.session.refresh(h.initial, h.session.deadline()) }
            h.fake.calls.last().reply(SyntheticAuth.response(ROTATED))
            val updated = (rotating.await() as SessionResult.Ready).envelope
            oldRead.reply(SyntheticAuth.response("{}", 401))
            // Reuses the completed rotation, not the consumed refresh token.
            assertEquals(3, h.fake.calls.size)
            h.fake.calls.last().reply(SyntheticAuth.response("{}", 401))
            // This second unauthorized response really used E2 and may retire it.
            assertEquals(ReadError.REAUTHORIZE, reading.await().usage.error)
            assertEquals(1, h.fake.calls.count { it.request is ProviderHttpRequest.FormPost })
            assertEquals(CredentialResult.Failure(CredentialFailure.STALE_GENERATION), h.store.read(updated.generation))
        }
    }

    @Test fun transientRefreshNeverFabricatesQuotaOrAutomaticallyReusesTheOldToken() = runBlocking {
        for (reply in listOf(SyntheticAuth.response("not-json"), SyntheticAuth.response("{}", 429),
            SyntheticAuth.response("{}", 503))) Harness().use { h ->
            h.fake.respond = { call -> call.reply(if (call.request is ProviderHttpRequest.FormPost) reply else SyntheticAuth.response("{}", 401)) }
            val facts = h.reader.read()
            assertNotNull(facts.usage.error)
            assertNull(facts.usage.usage)
            assertSame(h.initial, h.persistence.durable)
            assertEquals(1, h.fake.calls.count { it.request is ProviderHttpRequest.FormPost })
        }
    }

    private class Harness : AutoCloseable {
        val fake = AuthFake()
        val clock = AuthClock()
        val persistence = FakeCredentialPersistence()
        val store = SerializedCredentialStore(persistence)
        val initial = syntheticEnvelope(store.openSession())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = SessionCoordinator(store, fake, scope, clock, Dispatchers.Unconfined)
        val reader = AuthenticatedProviderReader(session, NativeFeasibilityReader(fake, clock, clock::pause))
        init {
            store.replace(initial, CredentialCancellation())
            session.adopt(initial)
            val original = fake.respond
            fake.respond = { call -> clock.millis++; original(call) }
        }
        override fun close() { session.retire(); scope.cancel() }
    }
    private companion object {
        const val ROTATED = """{"access_token":"synthetic-rotated-access","refresh_token":"synthetic-rotated-refresh"}"""
        fun text(value: SensitiveValue) = value.copyBytes().toString(Charsets.UTF_8)
    }
}
