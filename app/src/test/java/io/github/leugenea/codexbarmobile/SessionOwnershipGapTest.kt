package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline post-validation scheduling gaps, not held-network-response tests. The delegated
 * store queries the real kernel and parks only AFTER that query has released its lock.
 * Returning that previously true hint must not authorize publication or transport execute:
 * the shared ownership sequencer must independently revalidate at the final effect.
 */
class SessionOwnershipGapTest {
    @Test fun logoutAfterPublicationValidationCannotEmitOldObservations() = publicationGap(false)
    @Test fun replacementAfterPublicationValidationCannotEmitOldObservations() = publicationGap(true)
    @Test fun logoutAfterGetValidationCannotExecuteOldRequest() = admissionGap(Operation.GET, false)
    @Test fun replacementAfterGetValidationCannotExecuteOldRequest() = admissionGap(Operation.GET, true)
    @Test fun logoutAfterRefreshValidationCannotExecuteOldRequest() = admissionGap(Operation.REFRESH, false)
    @Test fun replacementAfterRefreshValidationCannotExecuteOldRequest() = admissionGap(Operation.REFRESH, true)
    @Test fun logoutAfterUsercodeValidationCannotExecuteOldRequest() = admissionGap(Operation.AUTH, false)
    @Test fun replacementAfterUsercodeValidationCannotExecuteOldRequest() = admissionGap(Operation.AUTH, true)
    @Test fun logoutAfterPollValidationCannotExecuteOldRequest() = admissionGap(Operation.POLL, false)
    @Test fun replacementAfterPollValidationCannotExecuteOldRequest() = admissionGap(Operation.POLL, true)
    @Test fun logoutAfterExchangeValidationCannotExecuteOldRequest() = admissionGap(Operation.EXCHANGE, false)
    @Test fun replacementAfterExchangeValidationCannotExecuteOldRequest() = admissionGap(Operation.EXCHANGE, true)

    private fun publicationGap(replace: Boolean) {
        Fixture(Operation.PUBLICATION).use { h ->
            val old = h.controller()
            val initial = (old.session.snapshot() as SessionResult.Ready).envelope
            val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
            // Record through this exact observer, including any transient stale emission.
            val observer = h.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                old.state.collect { states += it }
            }
            try {
                old.readUsage()
                assertEquals(1, h.fake.calls.size)
                h.fake.calls.single().reply(SyntheticAuth.response("{}"))
                assertEquals(2, h.fake.calls.size)
                h.gate.arm(initial.generation)
                h.withWorker({ h.fake.calls[1].reply(SyntheticAuth.response("{}")) }) {
                    h.gate.awaitEntered()
                    assertEquals(ConnectionPhase.READING, old.state.value.phase)
                    assertTrue(h.kernel.isActive(initial.generation))
                    val newer = h.displace(replace)
                    val saved = h.durableSnapshot()
                    assertFalse(h.kernel.isActive(initial.generation))
                    h.gate.release()
                    it.awaitFinished()
                    assertEquals("Old owner must report displacement without another command",
                        ConnectionPhase.REAUTH_REQUIRED, old.state.value.phase)
                    assertNull(old.state.value.observations)
                    assertTrue("No stale OBSERVED emission, even transiently",
                        synchronized(states) { states.none { state -> state.phase == ConnectionPhase.OBSERVED } })
                    h.assertRejectedOwner(old, initial)
                    h.assertDurableUnchanged(saved, replace, newer)
                }
            } finally {
                h.gate.release()
                observer.cancel()
            }
        }
    }

    private fun admissionGap(operation: Operation, replace: Boolean) {
        Fixture(operation).use { h ->
            val old = h.controller()
            val initial = (old.session.snapshot() as SessionResult.Ready).envelope
            val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
            val observer = h.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                old.state.collect { states += it }
            }
            val auth = if (operation.authStage != null)
                DeviceCodeAuthenticator(h.fake, h.slot, h.clock, h.clock::pause) else null
            val authStates = Collections.synchronizedList(mutableListOf<AuthState>())
            val authObserver = auth?.let { authenticator ->
                h.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    authenticator.state.collect { authStates += it }
                }
            }
            h.replySynchronously()
            h.gate.arm(initial.generation)
            try {
                h.withWorker({
                    if (auth != null) {
                        val job = auth.start(h.scope, initial.generation)
                        runBlocking { withTimeout(5_000) { job.join() } }
                    } else old.readUsage(refreshSession = operation == Operation.REFRESH)
                }) {
                    h.gate.awaitEntered()
                    assertTrue(h.kernel.isActive(initial.generation))
                    assertEquals("Only requests preceding the selected admission were sent",
                        operation.precedingRequests, h.fake.calls.size)
                    if (operation == Operation.REFRESH) assertTrue(h.rotationPrepared.get())
                    val newer = h.displace(replace)
                    val saved = h.durableSnapshot()
                    assertFalse(h.kernel.isActive(initial.generation))
                    h.gate.release()
                    it.awaitFinished()
                    assertEquals("No old-generation transport execute after displacement",
                        saved.requests, h.fake.calls.size)
                    if (auth != null) {
                        assertEquals("Auth must truthfully reject its displaced owner",
                            AuthState.Failed(requireNotNull(operation.authStage), AuthFailure.STALE_OWNER), auth.state.value)
                        assertTrue("No stale Connected auth emission",
                            synchronized(authStates) { authStates.none { state -> state is AuthState.Connected } })
                    } else {
                        assertEquals(ConnectionPhase.REAUTH_REQUIRED, old.state.value.phase)
                    }
                    assertNull(old.state.value.observations)
                    assertTrue("No old OBSERVED emission",
                        synchronized(states) { states.none { state -> state.phase == ConnectionPhase.OBSERVED } })
                    h.assertRejectedOwner(old, initial)
                    h.assertDurableUnchanged(saved, replace, newer)
                }
            } finally {
                h.gate.release()
                auth?.cancel()
                authObserver?.cancel()
                observer.cancel()
            }
        }
    }

    private enum class Operation(
        val precedingRequests: Int = 0,
        val authStage: AuthStage? = null,
        val authRequestHint: Int = 0,
    ) {
        PUBLICATION,
        GET,
        REFRESH,
        AUTH(authStage = AuthStage.USERCODE, authRequestHint = 1),
        POLL(precedingRequests = 1, authStage = AuthStage.POLL, authRequestHint = 3),
        EXCHANGE(precedingRequests = 2, authStage = AuthStage.EXCHANGE, authRequestHint = 5),
    }

    private class GapGate(private val operation: Operation) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val armed = AtomicBoolean(false)
        private val generation = AtomicReference<SessionGeneration?>()
        private val selectedWorker = AtomicReference<Thread?>()
        private var authRequestHints = 0 // Accessed only on the selected old worker.

        fun arm(owner: SessionGeneration) { generation.set(owner); armed.set(true) }

        // Coroutine debug mode changes the name, never this explicitly registered identity.
        fun selectWorker() { selectedWorker.set(Thread.currentThread()) }

        fun afterValidation(owner: SessionGeneration, valid: Boolean, rotationPrepared: Boolean) {
            if (!valid || !armed.get() || generation.get() !== owner ||
                Thread.currentThread() !== selectedWorker.get()) return
            val stack = Thread.currentThread().stackTrace
            fun at(type: String, method: String) = stack.any {
                it.className.endsWith(type) && it.methodName == method
            }
            val selected = when (operation) {
                Operation.PUBLICATION -> at("ConnectionController", "publish")
                Operation.GET -> at("NativeFeasibilityReader", "request")
                Operation.REFRESH -> rotationPrepared && at("SessionCoordinator", "requestRefresh")
                else -> {
                    // Each request has a pre-admission and a post-response ensureOwner hint.
                    // Ignore state-publication hints; select pre-admission usercode/poll/exchange.
                    if (at("DeviceCodeAuthenticator", "ensureOwner") && at("DeviceCodeAuthenticator", "request")) {
                        authRequestHints++
                        authRequestHints == operation.authRequestHint
                    } else false
                }
            }
            if (selected && armed.compareAndSet(true, false)) {
                entered.countDown()
                check(released.await(5, TimeUnit.SECONDS)) { "Post-validation ${operation.name} release timed out" }
            }
        }

        fun awaitEntered() {
            check(entered.await(5, TimeUnit.SECONDS)) {
                "Did not reach post-validation ${operation.name} hint outside the ownership sequencer; coordinate seam with writer"
            }
        }
        fun release() = released.countDown()
    }

    private class Worker(action: () -> Unit) {
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread({
            try { action() } catch (error: Throwable) { failure.set(error) }
        }, OLD_WORKER).apply { isDaemon = true; start() }

        fun awaitFinished() {
            thread.join(5_000)
            check(!thread.isAlive) { "Post-validation worker did not finish" }
            failure.get()?.let { throw it }
        }
    }

    private data class DurableSnapshot(
        val envelope: CredentialEnvelope?, val requests: Int,
        val prepares: Int, val commits: Int, val deletes: Int,
    )

    private class Fixture(operation: Operation) : AutoCloseable {
        val persistence = FakeCredentialPersistence()
        private var binding: SessionGeneration? = null
        // Model protected restoration's rebinding without bypassing kernel.read.
        private val rebound = object : CredentialPersistence by persistence {
            override fun read(): CredentialResult<CredentialEnvelope> = when (val saved = persistence.read()) {
                is CredentialResult.Failure -> saved
                is CredentialResult.Success -> CredentialResult.Success(CredentialEnvelope(
                    requireNotNull(binding), saved.value.accessToken, saved.value.refreshToken,
                ))
            }
        }
        val kernel = SerializedCredentialStore(rebound, activate = { binding = it }, removalExecutor = java.util.concurrent.Executor { it.run() })
        val gate = GapGate(operation)
        val rotationPrepared = AtomicBoolean(false)
        val slot = object : CredentialStore by kernel {
            override fun openSession() = kernel.openSession().also { binding = it }
            override fun replaceSession(generation: SessionGeneration) = kernel.replaceSession(generation).also {
                if (it is CredentialResult.Success) binding = it.value
            }
            override fun beginRotation(generation: SessionGeneration) = kernel.beginRotation(generation).also {
                rotationPrepared.set(it is CredentialResult.Success)
            }
            override fun isActive(generation: SessionGeneration): Boolean {
                val valid = kernel.isActive(generation)
                gate.afterValidation(generation, valid, rotationPrepared.get())
                return valid // Real answer, intentionally allowed to age while parked.
            }
        }
        val fake = AuthFake()
        val clock = AuthClock()
        private val coroutineFailure = AtomicReference<Throwable?>()
        private val handler = CoroutineExceptionHandler { _, error -> coroutineFailure.compareAndSet(null, error) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
        private val controllers = mutableListOf<ConnectionController>()

        init {
            check(slot.replace(syntheticEnvelope(slot.openSession()), CredentialCancellation()) is CredentialResult.Success)
        }

        fun controller() = ConnectionController(slot,
            DeviceCodeAuthenticator(fake, slot, clock, clock::pause),
            NativeFeasibilityReader(fake, clock, clock::pause),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)).also {
                controllers += it
                assertEquals(ConnectionPhase.RESTORED, it.state.value.phase)
            }

        fun replySynchronously() {
            fake.respond = { call -> call.reply(SyntheticAuth.response(when (call.request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
                "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
                "/oauth/token" -> SyntheticAuth.TOKENS
                else -> "{}"
            })) }
        }

        fun displace(replace: Boolean): ConnectionController {
            val newer = controller()
            replySynchronously()
            if (replace) newer.connect() else newer.signOut()
            assertEquals("Displacement must finish while old validation is parked",
                if (replace) ConnectionPhase.OBSERVED else ConnectionPhase.SIGNED_OUT, newer.state.value.phase)
            return newer
        }

        fun durableSnapshot() = DurableSnapshot(persistence.durable, fake.calls.size,
            persistence.prepareCount, persistence.commitCount, persistence.deleteCount)

        fun assertRejectedOwner(old: ConnectionController, initial: CredentialEnvelope) {
            assertEquals(SessionResult.Failed(SessionProblem.STALE), old.session.snapshot())
            old.readUsage()
            old.readUsage(refreshSession = true)
            assertEquals(ConnectionPhase.REAUTH_REQUIRED, old.state.value.phase)
            assertNull(old.state.value.observations)
            assertEquals(CredentialResult.Failure(CredentialFailure.STALE_GENERATION),
                slot.replace(initial, CredentialCancellation()))
        }

        fun assertDurableUnchanged(saved: DurableSnapshot, replace: Boolean, newer: ConnectionController) {
            assertEquals("No extra old requests, including subsequent read/refresh", saved.requests, fake.calls.size)
            assertSame("Late owner cannot alter deletion/replacement", saved.envelope, persistence.durable)
            assertEquals("No stale staging", saved.prepares, persistence.prepareCount)
            assertEquals("No stale durable commit", saved.commits, persistence.commitCount)
            assertEquals("No stale delete", saved.deletes, persistence.deleteCount)
            if (replace) {
                assertNotNull(saved.envelope)
                assertTrue(kernel.isActive(requireNotNull(saved.envelope).generation))
                assertEquals(ConnectionPhase.OBSERVED, newer.state.value.phase)
                assertTrue(newer.state.value.observations?.successful == true)
            } else {
                assertNull(saved.envelope)
                assertEquals(ConnectionPhase.SIGNED_OUT, newer.state.value.phase)
            }
        }

        fun withWorker(action: () -> Unit, assertions: (Worker) -> Unit) {
            val worker = Worker { gate.selectWorker(); action() }
            try { assertions(worker) } finally {
                gate.release()
                worker.awaitFinished() // Rethrow thread failures, including gate assertions.
                coroutineFailure.get()?.let { throw it }
            }
        }

        override fun close() {
            gate.release()
            try { controllers.forEach { it.close() } } finally { scope.cancel() }
            coroutineFailure.get()?.let { throw it }
        }
    }

    private companion object { const val OLD_WORKER = "old-post-validation-effect" }
}
