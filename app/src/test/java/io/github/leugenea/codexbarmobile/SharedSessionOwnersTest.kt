package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Two live owners, synthetic I/O; the real shared kernel arbitrates every capability/write. */
class SharedSessionOwnersTest {
    @Test fun anotherOwnersLogoutWhileUsageIsHeldRejectsInventoryPublicationAndNewReads() = runBlocking {
        heldUsage(replace = false)
    }

    @Test fun anotherOwnersReplacementWhileUsageIsHeldRejectsInventoryPublicationAndNewReads() = runBlocking {
        heldUsage(replace = true)
    }

    private suspend fun heldUsage(replace: Boolean) {
        Fixture().use { h ->
            val old = h.controller()
            val initial = (old.session.snapshot() as SessionResult.Ready).envelope
            val states = mutableListOf<ConnectionState>()
            val observer = h.scope.launch(Dispatchers.Unconfined) { old.state.collect { states += it } }
            try {
                old.readUsage()
                val held = h.fake.calls.single()
                val newer = h.controller()
                assertEquals(ConnectionPhase.RESTORED, newer.state.value.phase)
                if (replace) newer.connect() else newer.signOut()
                assertEquals(if (replace) ConnectionPhase.OBSERVED else ConnectionPhase.SIGNED_OUT, newer.state.value.phase)
                val durable = h.persistence.durable
                val requests = h.fake.calls.size
                held.reply(SyntheticAuth.response("{}"))
                assertEquals("No old inventory GET", requests, h.fake.calls.size)
                assertEquals(ConnectionPhase.REAUTH_REQUIRED, old.state.value.phase)
                assertNull(old.state.value.observations)
                assertTrue(states.none { it.phase == ConnectionPhase.OBSERVED })
                old.readUsage()
                old.readUsage(refreshSession = true)
                assertEquals("No newly admitted old-generation request", requests, h.fake.calls.size)
                assertEquals(SessionResult.Failed(SessionProblem.STALE), old.session.snapshot())
                assertEquals(CredentialResult.Failure(CredentialFailure.STALE_GENERATION),
                    h.slot.replace(initial, CredentialCancellation()))
                assertSame("Late owner cannot change durable replacement/logout", durable, h.persistence.durable)
                if (replace) assertTrue(h.slot.isActive(durable!!.generation)) else assertNull(durable)
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test fun independentSlotLogoutInvalidatesReaderWithoutRetiringItsCoordinator() = runBlocking {
        Fixture().use { h ->
            val initial = h.persistence.durable!!
            val session = SessionCoordinator(h.slot, h.fake, h.scope, h.clock)
            session.adopt(initial)
            val reader = AuthenticatedProviderReader(session, NativeFeasibilityReader(h.fake, h.clock, h.clock::pause))
            val reading = async(Dispatchers.Unconfined) { reader.read() }
            try {
                assertEquals(1, h.fake.calls.size)
                assertTrue(h.slot.delete(h.slot.openSession()) is CredentialResult.Success)
                h.fake.calls.single().reply(SyntheticAuth.response("{}"))
                assertFalse(reading.await().successful)
                assertEquals(1, h.fake.calls.size)
                assertFalse(reader.read().successful)
                assertEquals(1, h.fake.calls.size)
                assertNull(h.persistence.durable)
            } finally { reading.cancelAndJoin(); session.retire() }
        }
    }

    @Test fun heldInventoryCannotPublishAfterAnotherOwnerDeletesTheSlot() = runBlocking {
        Fixture().use { h ->
            h.fake.respond = { call -> if (call.request.url.encodedPath == ReadOperation.USAGE.path) call.reply(SyntheticAuth.response("{}")) }
            val old = h.controller()
            old.readUsage()
            assertEquals(2, h.fake.calls.size)
            val newer = h.controller()
            newer.signOut()
            h.fake.calls.last().reply(SyntheticAuth.response("{}"))
            assertEquals(ConnectionPhase.REAUTH_REQUIRED, old.state.value.phase)
            assertNull(old.state.value.observations)
            assertEquals(2, h.fake.calls.size)
        }
    }

    @Test fun displacedRotationCannotPersistOrReturnTokensAfterReplacement() = runBlocking {
        Fixture().use { h ->
            val initial = h.persistence.durable!!
            val session = SessionCoordinator(h.slot, h.fake, h.scope, h.clock)
            session.adopt(initial)
            val pending = async(Dispatchers.Unconfined) { session.refresh(initial, session.deadline()) }
            val replacement = syntheticEnvelope(h.slot.openSession(), "replacement")
            assertTrue(h.slot.replace(replacement, CredentialCancellation()) is CredentialResult.Success)
            val commits = h.persistence.commitCount
            h.fake.calls.single().reply(SyntheticAuth.response(SyntheticAuth.TOKENS))
            assertEquals(SessionResult.Failed(SessionProblem.STALE), pending.await())
            assertEquals(SessionResult.Failed(SessionProblem.STALE), session.refresh(initial, session.deadline()))
            assertEquals(1, h.fake.calls.size)
            assertEquals(commits, h.persistence.commitCount)
            assertSame(replacement, h.persistence.durable)
            session.retire()
        }
    }

    @Test fun displacementDuringBackoffCannotAdmitAnOldGenerationRetry() = runBlocking {
        Fixture().use { h ->
            val gate = CompletableDeferred<Unit>()
            h.clock.gate = gate
            h.fake.respond = { it.reply(SyntheticAuth.response("{}", 429)) }
            val old = h.controller()
            old.readUsage()
            assertEquals(1, h.fake.calls.size)
            h.slot.delete(h.slot.openSession())
            gate.complete(Unit)
            assertEquals(1, h.fake.calls.size)
            assertEquals(ConnectionPhase.REAUTH_REQUIRED, old.state.value.phase)
            assertNull(old.state.value.observations)
        }
    }

    @Test fun displacedRestoredOwnerCannotAdmitItsFirstReadOrExplicitRefresh() {
        Fixture().use { h ->
            val old = h.controller()
            val newer = h.controller()
            newer.signOut()
            old.readUsage()
            old.readUsage(refreshSession = true)
            assertEquals(ConnectionPhase.REAUTH_REQUIRED, old.state.value.phase)
            assertNull(old.state.value.observations)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun held401CannotRefreshTheDisplacedOwnersToken() {
        Fixture().use { h ->
            val old = h.controller()
            old.readUsage()
            h.controller().signOut()
            h.fake.calls.single().reply(SyntheticAuth.response("{}", 401))
            assertEquals(1, h.fake.calls.size)
            assertEquals(ConnectionPhase.REAUTH_REQUIRED, old.state.value.phase)
            assertNull(old.state.value.observations)
        }
    }

    @Test fun delayedRefreshWaiterCannotAcceptPreviouslySettledTokensAfterDisplacement() = runBlocking {
        Fixture().use { h ->
            val queue = java.util.ArrayDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
            }
            val initial = h.persistence.durable!!
            val session = SessionCoordinator(h.slot, h.fake, h.scope, h.clock)
            session.adopt(initial)
            val waiting = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { session.refresh(initial, session.deadline()) }
            try {
                h.fake.calls.single().reply(SyntheticAuth.response(SyntheticAuth.TOKENS))
                assertFalse("Waiter is still queued after durable rotation", waiting.isCompleted)
                assertNotSame(initial, h.persistence.durable)
                val replacement = syntheticEnvelope(h.slot.openSession(), "replacement")
                h.slot.replace(replacement, CredentialCancellation())
                while (queue.isNotEmpty()) queue.removeFirst().run()
                assertEquals(SessionResult.Failed(SessionProblem.STALE), waiting.await())
                assertSame(replacement, h.persistence.durable)
            } finally {
                waiting.cancel()
                while (queue.isNotEmpty()) queue.removeFirst().run()
                waiting.join()
                session.retire()
            }
        }
    }

    @Test fun aDisplacedLoginCannotPollExchangeOrPublishConnectedAfterHeldCodeResponse() {
        Fixture().use { h ->
            h.fake.respond = {}
            val old = h.controller()
            old.connect()
            val heldCode = h.fake.calls.single()
            val newer = h.controller()
            newer.signOut()
            heldCode.reply(SyntheticAuth.response(SyntheticAuth.DEVICE))
            assertEquals(1, h.fake.calls.size)
            assertEquals(ConnectionPhase.FAILED, old.state.value.phase)
            assertEquals(AuthState.Failed(AuthStage.USERCODE, AuthFailure.STALE_OWNER), old.state.value.auth)
            assertNull(old.state.value.observations)
            assertNull(h.persistence.durable)
            assertEquals(1, h.persistence.commitCount)
        }
    }

    @Test fun aNewOwnerBetweenReplacementDeletionAndAuthStartCannotBeDisplacedByTheDelayedLogin() {
        Fixture().use { h ->
            val old = h.controller()
            h.afterReplacement = {
                h.afterReplacement = null
                h.controller().signOut()
            }
            old.connect()
            assertTrue("Delayed auth must not reopen the newer owner's slot", h.fake.calls.isEmpty())
            assertEquals(ConnectionPhase.FAILED, old.state.value.phase)
            assertEquals(AuthState.Failed(AuthStage.USERCODE, AuthFailure.STALE_OWNER), old.state.value.auth)
            assertNull(h.persistence.durable)
        }
    }

    @Test fun failedReplacementDeletionCannotAllocateAnAuthCapabilityOrAdmitRequests() {
        Fixture().use { h ->
            val old = h.controller()
            h.persistence.deleteFailure = CredentialFailure.FAILED_WRITE
            old.connect()
            assertEquals(ConnectionPhase.FAILED, old.state.value.phase)
            assertEquals(ConnectionProblem.STORAGE, old.state.value.problem)
            assertTrue(h.fake.calls.isEmpty())
            assertFalse(h.slot.isActive(h.persistence.durable!!.generation))
        }
    }

    /** Rebind at the persistence seam as protected restoration does; never bypass kernel.read. */
    private class Fixture : AutoCloseable {
        val persistence = FakeCredentialPersistence()
        private var binding: SessionGeneration? = null
        private val rebound = object : CredentialPersistence by persistence {
            override fun read(): CredentialResult<CredentialEnvelope> = when (val saved = persistence.read()) {
                is CredentialResult.Failure -> saved
                is CredentialResult.Success -> CredentialResult.Success(CredentialEnvelope(
                    requireNotNull(binding), saved.value.accessToken, saved.value.refreshToken,
                ))
            }
        }
        private val kernel = SerializedCredentialStore(rebound, activate = { binding = it }, removalExecutor = java.util.concurrent.Executor { it.run() })
        val slot = object : CredentialStore by kernel {
            override fun openSession() = kernel.openSession().also { binding = it }
            override fun replaceSession(generation: SessionGeneration) = kernel.replaceSession(generation).also {
                if (it is CredentialResult.Success) binding = it.value
            }
        }
        var afterReplacement: (() -> Unit)? = null
        val fake = AuthFake()
        val clock = AuthClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val controllers = mutableListOf<ConnectionController>()
        init {
            val initial = syntheticEnvelope(slot.openSession())
            check(slot.replace(initial, CredentialCancellation()) is CredentialResult.Success)
            fake.respond = { call ->
                if (fake.calls.size != 1) call.reply(SyntheticAuth.response(when (call.request.url.encodedPath) {
                    "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
                    "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
                    "/oauth/token" -> SyntheticAuth.TOKENS
                    else -> "{}"
                }))
            }
        }
        fun controller(): ConnectionController {
            val adapter = object : CredentialStore by slot {
                override fun admitCommandRemoval(replacement: Boolean): CredentialRemoval {
                    val command = slot.admitCommandRemoval(replacement)
                    val deletion = object : CredentialDeletion by command.deletion {
                        override suspend fun await() = command.deletion.await().also { afterReplacement?.invoke() }
                    }
                    return CredentialRemoval(deletion, command.successor)
                }
            }
            return ConnectionController(adapter, DeviceCodeAuthenticator(fake, adapter, clock, clock::pause),
                NativeFeasibilityReader(fake, clock, clock::pause),
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).also { controllers += it }
        }
        override fun close() { controllers.forEach { it.close() }; scope.cancel() }
    }
}
