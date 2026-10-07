package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Original synthetic protocol data, no sockets or live credentials. */
class ConnectionControllerTest {
    private val usage = """{"rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"limit_window_seconds":604800,"used_percent":5,"reset_at":1791756793},"secondary_window":null},"rate_limit_reset_credits":{"available_count":2},"ignored":"synthetic-private-label"}"""
    private val inventory = """{"available_count":2,"credits":[{"expires_at":"2026-10-22T20:31:56.833553Z"},{"expires_at":null}]}"""

    @Test
    fun connectionPersistsUnresolvedAndReadsExactlySelectedRoutesWithSeparateClocks() = runBlocking {
        Harness().use { h ->
            h.connect()
            val state = h.controller.state.value
            val facts = requireNotNull(state.observations)
            assertEquals(ConnectionPhase.OBSERVED, state.phase)
            assertNull(state.problem)
            assertEquals(AccountWorkspaceBinding.Unresolved, h.persistence.durable!!.accountWorkspace)
            assertEquals(listOf("/backend-api/wham/usage", "/backend-api/wham/rate-limit-reset-credits"),
                h.fake.calls.map { it.request }.filterIsInstance<ProviderHttpRequest.Get>().map { it.url.encodedPath })
            assertTrue(h.fake.calls.filter { it.request is ProviderHttpRequest.Get }.all {
                it.request.url.scheme == "https" && it.request.url.host == "chatgpt.com" &&
                    it.deadline.expiresAtMillis - h.clock.millis <= 30_000
            })
            assertTrue(facts.usage.observedAt!! < facts.inventory.observedAt!!)
            assertEquals(SelectionState.UNAVAILABLE, facts.usage.usage!!.fiveHour.state)
            assertEquals(BigDecimal(5), facts.usage.usage.weekly.candidates.single().usedPercent.value)
            assertEquals(2L, facts.inventory.availableCount!!.value)
            assertEquals(Knowledge.UNAVAILABLE, facts.inventory.expiries[1].knowledge)
            assertFalse(state.toString().contains("synthetic"))
            assertFalse(facts.toString().contains("synthetic-private-label"))
            h.controller.signOut()
            assertEquals(ConnectionPhase.SIGNED_OUT, h.controller.state.value.phase)
            assertNull(h.persistence.durable)
            assertEquals(5, h.fake.calls.size) // local logout never sends a revoke.
        }
    }

    @Test
    fun cancelAndNewAttemptDiscardLateAuthAndReadResults() = runBlocking {
        Harness().use { h ->
            h.fake.respond = {}
            h.controller.connect()
            val old = h.fake.calls.single()
            h.controller.connect() // coalesces while busy
            assertEquals(1, h.fake.calls.size)
            h.controller.cancel()
            assertTrue(old.cancelled)
            old.reply(SyntheticAuth.response(SyntheticAuth.DEVICE))
            assertEquals(ConnectionPhase.CANCELLED, h.controller.state.value.phase)
            assertNull(h.persistence.durable)
            h.respondNormally()
            h.controller.connect()
            assertEquals(ConnectionPhase.OBSERVED, h.controller.state.value.phase)
            val count = h.fake.calls.size
            h.fake.respond = { call -> if (call.request is ProviderHttpRequest.Get) Unit else h.respond(call) }
            h.controller.connect()
            val read = h.fake.calls.last()
            h.controller.signOut()
            assertTrue(read.cancelled)
            read.reply(SyntheticAuth.response(usage))
            assertEquals(ConnectionPhase.SIGNED_OUT, h.controller.state.value.phase)
            assertNull(h.controller.state.value.observations)
            assertTrue(h.fake.calls.size > count)
        }
    }

    @Test
    fun authStorageBrowserAndDeleteFailuresStayNotGoAndRedacted() = runBlocking {
        Harness().use { h ->
            h.persistence.prepareFailure = CredentialFailure.FAILED_WRITE
            h.connect()
            assertEquals(ConnectionProblem.AUTH, h.controller.state.value.problem)
            assertNull(h.persistence.durable)
            h.persistence.prepareFailure = null
            h.fake.respond = {}
            h.controller.connect()
            val pending = h.fake.calls.last()
            h.controller.browserFailed()
            assertTrue(pending.cancelled)
            assertEquals(ConnectionProblem.BROWSER, h.controller.state.value.problem)
            h.persistence.deleteFailure = CredentialFailure.FAILED_WRITE
            h.controller.signOut()
            assertEquals(ConnectionProblem.STORAGE, h.controller.state.value.problem)
            assertTrue(h.controller.state.value.toString().contains("UNVERIFIED"))
            h.controller.close()
            h.controller.close()
            h.controller.connect()
            h.controller.signOut()
            h.controller.cancel()
            h.controller.browserFailed()
            assertEquals(ConnectionPhase.CANCELLED, h.controller.state.value.phase)
        }
    }

    @Test
    fun restoredMissingCorruptAndUnavailableStorageNeverStartTraffic() = runBlocking {
        for (failure in listOf(null, CredentialFailure.MISSING, CredentialFailure.CORRUPT)) {
            val fake = AuthFake()
            val persistence = FakeCredentialPersistence()
            val store = object : CredentialStore {
                private val delegate = SerializedCredentialStore(persistence)
                override fun openSession() = delegate.openSession()
                override fun isActive(generation: SessionGeneration) = delegate.isActive(generation)
                override fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope> =
                    failure?.let { CredentialResult.Failure(it) } ?: CredentialResult.Success(syntheticEnvelope(generation))
                override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation) = delegate.replace(envelope, cancellation)
                override fun delete(generation: SessionGeneration) = delegate.delete(generation)
                override fun replaceSession(generation: SessionGeneration) = delegate.replaceSession(generation)
            }
            val controller = ConnectionController(store, DeviceCodeAuthenticator(fake, store), NativeFeasibilityReader(fake),
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
            assertEquals(when (failure) { null -> ConnectionPhase.RESTORED; CredentialFailure.MISSING -> ConnectionPhase.IDLE
                else -> ConnectionPhase.REAUTH_REQUIRED }, controller.state.value.phase)
            assertTrue(fake.calls.isEmpty())
            controller.close()
        }
        Harness(ready = false).use { h ->
            assertEquals(ConnectionProblem.STORAGE, h.controller.state.value.problem)
            h.controller.connect()
            h.controller.signOut()
            assertTrue(h.fake.calls.isEmpty())
            assertEquals(ConnectionProblem.STORAGE, h.controller.state.value.problem)
        }
    }

    @Test
    fun generationReadFailureIsCategoricalAndDoesNotRequestEndpoints() = runBlocking {
        Harness().use { h ->
            h.persistence.readFailure = CredentialFailure.KEY_LOST
            h.connect()
            assertEquals(ConnectionProblem.STORAGE, h.controller.state.value.problem)
            assertTrue(h.fake.calls.none { it.request is ProviderHttpRequest.Get })
        }
    }

    @Test
    fun rateLimitBackoffIsBoundedCancellableAnd401RefreshIsBoundedWithoutExpiring403() = runBlocking {
        Harness().use { h ->
            var attempts = 0
            h.fake.respond = { call ->
                if (call.request is ProviderHttpRequest.Get && call.request.url.encodedPath.endsWith("/usage") && attempts++ == 0)
                    call.reply(SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(h.clock.millis + 2_000)))
                else h.respond(call)
            }
            h.connect()
            assertTrue(h.clock.sleeps.contains(2_000L))
            assertNull(h.controller.state.value.problem)
        }
        for ((status, error) in listOf(401 to ReadError.REAUTHORIZE, 403 to ReadError.FORBIDDEN, 429 to ReadError.RATE_LIMITED)) {
            Harness().use { h ->
                h.fake.respond = { call ->
                    if (call.request is ProviderHttpRequest.Get) call.reply(SyntheticAuth.response("{}", status,
                        RetryAfter.NotBefore(h.clock.millis + 40_000))) else h.respond(call)
                }
                h.connect()
                if (status == 401) {
                    assertEquals(ConnectionPhase.REAUTH_REQUIRED, h.controller.state.value.phase)
                    assertEquals(ConnectionProblem.AUTH, h.controller.state.value.problem)
                    assertNull(h.controller.state.value.observations)
                    assertNull(h.persistence.durable)
                    assertEquals(6, h.fake.calls.size) // auth + usage + one refresh + one retry.
                } else {
                    assertEquals(error, h.controller.state.value.observations!!.usage.error)
                    assertEquals(ConnectionProblem.READ, h.controller.state.value.problem)
                    assertNotNull(h.persistence.durable) // generic 403 never expires credentials.
                    assertEquals(5, h.fake.calls.size)
                }
            }
        }
        Harness().use { h ->
            val backoff = CompletableDeferred<Unit>()
            h.fake.respond = { call ->
                if (call.request is ProviderHttpRequest.Get) {
                    h.clock.gate = backoff
                    call.reply(SyntheticAuth.response("{}", 429))
                } else h.respond(call)
            }
            h.controller.connect()
            assertEquals(ConnectionPhase.READING, h.controller.state.value.phase)
            h.controller.cancel()
            backoff.complete(Unit)
            assertEquals(ConnectionPhase.CANCELLED, h.controller.state.value.phase)
            assertEquals(4, h.fake.calls.size)
        }
    }

    @Test
    fun malformedAndPartialAllowlistProjectionNeverExportsUntrustedText() = runBlocking {
        val variants = listOf(
            "not-json", "[]", "{}", """{"rate_limit":null,"rate_limit_reset_credits":[]} """,
            """{"rate_limit":{"primary_window":false,"secondary_window":{"limit_window_seconds":18000,"used_percent":true,"reset_at":"bad"}},"rate_limit_reset_credits":{"available_count":-1}}""",
            """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":30},"secondary_window":{"limit_window_seconds":18000,"used_percent":40}}}""",
            """{"rate_limit":123}""",
        )
        for (text in variants) Harness().use { h ->
            h.fake.respond = { call -> if (call.request is ProviderHttpRequest.Get)
                call.reply(SyntheticAuth.response(text)) else h.respond(call) }
            h.connect()
            val facts = h.controller.state.value.observations!!
            assertFalse(facts.toString().contains("bad"))
            if (text == "not-json" || text == "[]") assertEquals(ReadError.INVALID_RESPONSE, facts.usage.error)
            else assertNotNull(facts.usage.usage)
        }
        Harness().use { h ->
            h.fake.respond = { call -> if (call.request is ProviderHttpRequest.Get)
                call.reply(SyntheticAuth.response("""{"available_count":true,"credits":[1,{},null,{"expires_at":false},{"expires_at":"unknown"}]}"""))
                else h.respond(call) }
            h.connect()
            val inventory = h.controller.state.value.observations!!.inventory
            assertEquals(Knowledge.MALFORMED, inventory.availableCount!!.knowledge)
            assertEquals(5, inventory.expiries.size)
            assertEquals(Knowledge.UNSUPPORTED, inventory.expiries.last().knowledge)
        }
    }

    @Test
    fun adapterExceptionsDeadlineAndRetryExhaustionAreSanitized() = runBlocking {
        Harness().use { h ->
            h.fake.respond = { call -> if (call.request is ProviderHttpRequest.Get) throw IllegalStateException("synthetic-private-label")
                else h.respond(call) }
            h.connect()
            assertEquals(ReadError.INVALID_RESPONSE, h.controller.state.value.observations!!.usage.error)
            assertFalse(h.controller.state.value.toString().contains("synthetic-private-label"))
        }
        Harness().use { h ->
            h.fake.respond = { call -> if (call.request is ProviderHttpRequest.Get) {
                h.clock.millis += 30_000
                call.reply(SyntheticAuth.response(usage))
            } else h.respond(call) }
            h.connect()
            assertEquals(ReadError.DEADLINE_EXCEEDED, h.controller.state.value.observations!!.usage.error)
        }
        Harness().use { h ->
            h.fake.respond = { call -> if (call.request is ProviderHttpRequest.Get) call.reply(SyntheticAuth.response("{}", 429))
                else h.respond(call) }
            h.connect()
            assertEquals(9, h.fake.calls.size) // three auth + bounded three attempts per read.
            assertEquals(ReadError.RATE_LIMITED, h.controller.state.value.observations!!.inventory.error)
        }
    }

    @Test
    fun queuedRestorationCannotReopenRetiredOwnerAndAdmittedLogoutSurvivesFinish() {
        val queue = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val persistence = FakeCredentialPersistence()
        val store = SerializedCredentialStore(persistence)
        val generation = store.openSession()
        val envelope = syntheticEnvelope(generation)
        store.replace(envelope, CredentialCancellation())
        val fake = AuthFake()
        val controller = ConnectionController(store, DeviceCodeAuthenticator(fake, store), NativeFeasibilityReader(fake),
            CoroutineScope(SupervisorJob() + dispatcher))
        assertEquals(ConnectionPhase.RESTORING, controller.state.value.phase)
        controller.signOut()
        controller.signOut()
        controller.cancel()
        controller.browserFailed()
        controller.connect()
        controller.close()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertNull(persistence.durable)
        assertEquals(1, persistence.deleteCount)
        assertTrue(fake.calls.isEmpty())
        assertEquals(ConnectionPhase.CANCELLED, controller.state.value.phase)
    }

    @Test
    fun replacementOwnerWaitsForAdmittedLogoutBeforeRestoringOrConnecting() = runBlocking {
        val queue = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        Harness().use { h ->
            h.connect()
            val deletions = ConnectionDeletionBarrier()
            val old = ConnectionController(h.store, DeviceCodeAuthenticator(h.fake, h.store, h.clock, h.clock::pause),
                NativeFeasibilityReader(h.fake, h.clock, h.clock::pause), CoroutineScope(SupervisorJob() + dispatcher),
                deletions = deletions)
            old.signOut()
            old.close()
            val newer = ConnectionController(h.store, DeviceCodeAuthenticator(h.fake, h.store, h.clock, h.clock::pause),
                NativeFeasibilityReader(h.fake, h.clock, h.clock::pause), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                deletions = deletions)
            try {
                assertEquals(ConnectionPhase.RESTORING, newer.state.value.phase)
                val before = h.fake.calls.size
                newer.cancel()
                newer.browserFailed()
                newer.connect()
                assertEquals(ConnectionPhase.RESTORING, newer.state.value.phase)
                assertEquals(before, h.fake.calls.size)
                while (queue.isNotEmpty()) queue.removeFirst().run()
                assertEquals(ConnectionPhase.IDLE, newer.state.value.phase)
                assertNull(h.persistence.durable)
                assertEquals(2, h.persistence.deleteCount) // initial replacement + admitted logout.
                newer.connect()
                assertEquals(ConnectionPhase.OBSERVED, newer.state.value.phase)
                assertNotNull(h.persistence.durable)
            } finally { newer.close() }
        }
    }

    @Test
    fun reentrantAuthenticationObserverCannotInvalidateAReplacementOwner() = runBlocking {
        Harness().use { h ->
            h.connect()
            val restoringStore = object : CredentialStore by h.store {
                override fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope> =
                    h.persistence.durable?.let { CredentialResult.Success(CredentialEnvelope(generation, it.accessToken, it.refreshToken)) }
                        ?: CredentialResult.Failure(CredentialFailure.MISSING)
            }
            var newer: ConnectionController? = null
            val observing = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                h.controller.state.collect { state ->
                    if (state.phase == ConnectionPhase.AUTHENTICATING && newer == null) {
                        h.controller.close()
                        newer = ConnectionController(restoringStore, DeviceCodeAuthenticator(h.fake, restoringStore, h.clock, h.clock::pause),
                            NativeFeasibilityReader(h.fake, h.clock, h.clock::pause), CoroutineScope(SupervisorJob() + Dispatchers.Default))
                        runBlocking { withTimeout(5_000) { newer.state.first { it.phase == ConnectionPhase.RESTORED } } }
                    }
                }
            }
            try {
                h.controller.connect()
                assertNotNull("Replacement observer reached", newer)
                val restored = newer!!.session.snapshot() as SessionResult.Ready
                assertTrue("Old publication tail cannot invalidate restored capability", h.store.finishRotation(restored.envelope.generation) is CredentialResult.Success)
                assertEquals(ConnectionPhase.CANCELLED, h.controller.state.value.phase)
                assertEquals(5, h.fake.calls.size)
                newer.connect()
                withTimeout(5_000) { newer.state.first { it.phase == ConnectionPhase.OBSERVED } }
                val generation = (newer.state.value.auth as AuthState.Connected).generation
                assertTrue(h.store.read(generation) is CredentialResult.Success)
            } finally { observing.cancelAndJoin(); newer?.close() }
        }
    }

    @Test
    fun reentrantSigningOutObserverCannotBypassAdmittedDeletionBarrier() = runBlocking {
        Harness().use { h ->
            val queue = java.util.ArrayDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
            }
            val barrier = ConnectionDeletionBarrier()
            val old = ConnectionController(h.store, DeviceCodeAuthenticator(h.fake, h.store, h.clock, h.clock::pause),
                NativeFeasibilityReader(h.fake, h.clock, h.clock::pause), CoroutineScope(SupervisorJob() + dispatcher), deletions = barrier)
            while (queue.isNotEmpty()) queue.removeFirst().run()
            old.connect()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            assertEquals(ConnectionPhase.OBSERVED, old.state.value.phase)
            var newer: ConnectionController? = null
            val observing = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                old.state.collect { state ->
                    if (state.phase == ConnectionPhase.SIGNING_OUT && newer == null) {
                        old.close()
                        newer = ConnectionController(h.store, DeviceCodeAuthenticator(h.fake, h.store, h.clock, h.clock::pause),
                            NativeFeasibilityReader(h.fake, h.clock, h.clock::pause), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), deletions = barrier)
                        assertEquals(ConnectionPhase.RESTORING, newer.state.value.phase)
                        newer.connect()
                    }
                }
            }
            try {
                old.signOut()
                assertNotNull("Deletion observer reached", newer)
                while (queue.isNotEmpty()) queue.removeFirst().run()
                assertNull(h.persistence.durable)
                assertEquals(ConnectionPhase.IDLE, newer!!.state.value.phase)
                newer.connect()
                assertEquals(ConnectionPhase.OBSERVED, newer.state.value.phase)
                val generation = (newer.state.value.auth as AuthState.Connected).generation
                assertTrue(h.store.read(generation) is CredentialResult.Success)
            } finally { observing.cancelAndJoin(); old.close(); newer?.close() }
        }
    }

    @Test
    fun explicitRefreshTransientFailureIsVisibleAndDoesNotStartReadsOrExpireSession() = runBlocking {
        Harness().use { h ->
            h.connect()
            val before = h.fake.calls.size
            h.fake.respond = { call -> call.reply(SyntheticAuth.response("{}", 429)) }
            h.controller.readUsage(refreshSession = true)
            val state = h.controller.state.value
            assertEquals(ConnectionPhase.OBSERVED, state.phase)
            assertEquals(ConnectionProblem.READ, state.problem)
            assertEquals(ReadError.RATE_LIMITED, state.observations!!.usage.error)
            assertNotNull(h.persistence.durable)
            assertEquals(before + 1, h.fake.calls.size)
            h.controller.readUsage()
            assertNotNull(h.persistence.durable)
        }
    }

    private inner class Harness(ready: Boolean = true) : AutoCloseable {
        val fake = AuthFake()
        val clock = AuthClock()
        val persistence = FakeCredentialPersistence()
        val store = SerializedCredentialStore(persistence)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ConnectionController(store, DeviceCodeAuthenticator(fake, store, clock, clock::pause),
            NativeFeasibilityReader(fake, clock, clock::pause), scope, ready)
        init { respondNormally() }
        fun respondNormally() { fake.respond = ::respond }
        fun respond(call: AuthFake.Call) {
            val body = when (call.request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
                "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
                "/oauth/token" -> SyntheticAuth.TOKENS
                "/backend-api/wham/usage" -> usage
                "/backend-api/wham/rate-limit-reset-credits" -> inventory
                else -> error("Unexpected synthetic route")
            }
            if (call.request is ProviderHttpRequest.Get) clock.millis++
            call.reply(SyntheticAuth.response(body))
        }
        fun connect() { controller.connect() }
        override fun close() { controller.close() }
    }
}
