package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** Two UI clients, one actual production-default owner/lane. No competing slot coordinators. */
class ProcessSessionOwnerTest {
    @Test fun eitherCommanderLogoutDuringHeldReadCannotPublishOrEnqueueOldInventory() = runBlocking {
        for (second in listOf(false, true)) Fixture().use { h ->
            h.restored()
            val held = h.hold(ReadOperation.USAGE.path)
            h.first.owner.readUsage()
            val request = held.awaitCall()
            h.commander(second).owner.signOut()
            h.phase(ConnectionPhase.SIGNED_OUT)
            val terminal = h.owner.state.value
            assertTrue(request.cancelled)
            assertNull(h.persistence.durable)
            assertNull(terminal.observations)
            request.reply(SyntheticAuth.response("{}"))
            h.owner.commandsSettled()
            assertSame(terminal, h.owner.state.value)
            assertEquals(1, h.fake.calls.size)
            h.assertShared()
        }
    }

    @Test fun eitherCommanderReplacementRejectsLateReadAfterFreshLogin() = runBlocking {
        for (second in listOf(false, true)) Fixture().use { h ->
            h.restored()
            val held = h.hold(ReadOperation.USAGE.path)
            h.first.owner.readUsage()
            val request = held.awaitCall()
            // Replacement is explicit logout + connect: busy Connect coalesces, never
            // pretends an existing read was replaced before its revocation command.
            h.commander(second).owner.signOut()
            h.phase(ConnectionPhase.SIGNED_OUT)
            h.respondNormally()
            h.commander(second).owner.connect()
            h.phase(ConnectionPhase.OBSERVED)
            val terminal = h.owner.state.value
            val durable = h.persistence.durable
            val count = h.fake.calls.size
            request.reply(SyntheticAuth.response("{}", 401))
            h.owner.commandsSettled()
            assertTrue(request.cancelled)
            assertSame(terminal, h.owner.state.value)
            assertSame(durable, h.persistence.durable)
            assertEquals(count, h.fake.calls.size)
            assertNotNull(durable)
            h.assertShared()
        }
    }

    @Test fun eitherCommanderLogoutDuringHeldRefreshCancelsAndNeverUsesConsumedTokenAgain() = runBlocking {
        for (second in listOf(false, true)) Fixture().use { h ->
            h.restored()
            val held = h.hold("/oauth/token")
            h.first.owner.readUsage(refreshSession = true)
            val request = held.awaitCall()
            h.commander(second).owner.signOut()
            h.phase(ConnectionPhase.SIGNED_OUT)
            request.reply(SyntheticAuth.response(ROTATED))
            h.owner.commandsSettled()
            assertTrue(request.cancelled)
            assertNull(h.persistence.durable)
            assertEquals(1, h.fake.calls.size)
            assertEquals(ConnectionPhase.SIGNED_OUT, h.owner.state.value.phase)
            h.assertShared()
        }
    }

    @Test fun heldDeletionFromEitherCommanderHasNoPrematureTerminalAndRejectsBusyCommands() = runBlocking {
        for (second in listOf(false, true)) Fixture().use { h ->
            h.restored()
            val gate = h.holdDeletion()
            try {
                h.commander(second).owner.signOut()
                gate.awaitEntered()
                h.owner.commandsSettled()
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertNotNull(h.persistence.durable)
                val before = h.fake.calls.size
                h.first.owner.connect()
                h.second.owner.cancel()
                h.first.owner.browserFailed()
                h.second.owner.readUsage(refreshSession = true)
                h.owner.commandsSettled()
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertEquals(before, h.fake.calls.size)
                assertFalse(h.first.states.any { it.phase == ConnectionPhase.SIGNED_OUT })
                assertFalse(h.second.states.any { it.phase == ConnectionPhase.REAUTH_REQUIRED })
            } finally { gate.release() }
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertEquals(1, h.persistence.deleteCount)
            h.assertShared()
        }
    }

    @Test fun cancellingReadWaiterIsBoundedWhileItsDurableQuarantineRemainsHeld() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val held = h.hold("/oauth/token")
            h.owner.readUsage(refreshSession = true)
            val request = held.awaitCall()
            val gate = h.holdDeletion()
            try {
                h.second.owner.cancel()
                gate.awaitEntered()
                withTimeout(2_000) { h.owner.commandsSettled() }
                assertTrue(request.cancelled)
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertNotNull(h.persistence.durable)
                h.first.owner.connect()
                h.owner.commandsSettled()
                assertEquals(1, h.fake.calls.size)
            } finally { gate.release() }
            h.phase(ConnectionPhase.CANCELLED)
            assertNull(h.persistence.durable)
            request.reply(SyntheticAuth.response(ROTATED))
            h.owner.commandsSettled()
            assertEquals(ConnectionPhase.CANCELLED, h.owner.state.value.phase)
        }
    }

    @Test fun timedOutLogoutNotifiesBothObserversWithoutClearingRealDeletion() = runBlocking {
        Fixture(waitMillis = 50).use { h ->
            h.restored()
            val gate = h.holdDeletion()
            try {
                h.second.owner.signOut()
                gate.awaitEntered()
                h.phase(ConnectionPhase.FAILED)
                withTimeout(2_000) {
                    while (h.first.states.none { it.problem == ConnectionProblem.STORAGE } ||
                        h.second.states.none { it.problem == ConnectionProblem.STORAGE }) delay(1)
                }
                assertNotNull(h.persistence.durable)
                h.first.owner.connect()
                h.second.owner.readUsage()
                h.owner.commandsSettled()
                assertTrue(h.fake.calls.isEmpty())
                assertFalse(h.first.states.any { it.phase == ConnectionPhase.SIGNED_OUT })
            } finally { gate.release() }
            h.awaitRemoved()
            h.owner.signOut()
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertEquals(1, h.persistence.deleteCount)
        }
    }

    @Test fun repeatedLogoutFromBothCommandersSharesHeldDeletionWithoutResurrection() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val gate = h.holdDeletion()
            try {
                h.first.owner.signOut()
                gate.awaitEntered()
                h.second.owner.signOut()
                h.owner.commandsSettled()
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertEquals(1, h.persistence.deleteCount)
            } finally { gate.release() }
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun detachOneObserverDoesNotCancelOwnedAuthAndItsPeerSeesCompletion() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val held = h.hold("/api/accounts/deviceauth/usercode")
            h.first.owner.connect()
            val request = held.awaitCall()
            h.first.detach()
            assertFalse(request.cancelled)
            h.respondNormally()
            request.reply(SyntheticAuth.response(SyntheticAuth.DEVICE))
            h.phase(ConnectionPhase.OBSERVED)
            assertNotNull(h.persistence.durable)
            assertSame(h.owner, h.second.owner)
        }
    }

    @Test fun queuedRestorationAndSignOutCannotReopenCredentialsOrTraffic() = runBlocking {
        Fixture().use { h ->
            h.owner.signOut() // May arrive before the off-lane restore result returns.
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertTrue(h.fake.calls.isEmpty())
            assertNull(h.owner.state.value.observations)
        }
    }

    @Test fun logoutAdmissionDoesNotBlockBehindAlreadyAdmittedAuthCommit() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val commit = ControlledGate()
            h.persistence.beforeCommit = commit::pause
            h.respondNormally()
            try {
                h.first.owner.connect()
                commit.awaitEntered()
                h.second.owner.signOut()
                withTimeout(2_000) { h.owner.commandsSettled() }
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertEquals(3, h.fake.calls.size)
                assertFalse(h.first.states.any { it.phase == ConnectionPhase.OBSERVED })
                assertFalse(h.second.states.any { it.phase == ConnectionPhase.SIGNED_OUT })
            } finally { commit.release() }
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertEquals(2, h.persistence.deleteCount) // Replacement then logout after admitted commit.
            assertEquals(3, h.fake.calls.size) // No GET from the superseded authentication.
        }
    }

    @Test fun completedFailedDeletionCanBeRetriedButNeverRestoresOldCredentials() = runBlocking {
        Fixture().use { h ->
            h.restored()
            h.persistence.deleteFailure = CredentialFailure.FAILED_WRITE
            h.second.owner.signOut()
            h.phase(ConnectionPhase.FAILED)
            assertEquals(ConnectionProblem.STORAGE, h.owner.state.value.problem)
            assertNotNull(h.persistence.durable)
            assertTrue(h.fake.calls.isEmpty())
            h.persistence.deleteFailure = null
            h.first.owner.signOut()
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertEquals(2, h.persistence.deleteCount)
        }
    }

    @Test fun replacementAfterCancelledRefreshNeverPublishesItsLateRotation() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val held = h.hold("/oauth/token")
            h.first.owner.readUsage(refreshSession = true)
            val request = held.awaitCall()
            h.second.owner.cancel()
            h.phase(ConnectionPhase.CANCELLED)
            h.respondNormally()
            h.first.owner.connect()
            h.phase(ConnectionPhase.OBSERVED)
            val terminal = h.owner.state.value
            val saved = h.persistence.durable
            val requests = h.fake.calls.size
            request.reply(SyntheticAuth.response(ROTATED))
            h.owner.commandsSettled()
            assertTrue(request.cancelled)
            assertSame(terminal, h.owner.state.value)
            assertSame(saved, h.persistence.durable)
            assertEquals(requests, h.fake.calls.size)
        }
    }

    @Test fun eitherCommanderRetiresHeldUsercodePollOrExchangeBeforeAnyOldFollowUp() = runBlocking {
        val paths = listOf("/api/accounts/deviceauth/usercode", "/api/accounts/deviceauth/token", "/oauth/token")
        for ((index, path) in paths.withIndex()) for (replace in listOf(false, true)) Fixture().use { h ->
            h.restored()
            val held = h.hold(path)
            h.first.owner.connect()
            val old = held.awaitCall()
            assertEquals(index + 1, h.fake.calls.size)
            h.second.owner.signOut()
            h.phase(ConnectionPhase.SIGNED_OUT)
            if (replace) {
                h.respondNormally()
                h.second.owner.connect()
                h.phase(ConnectionPhase.OBSERVED)
            }
            val terminal = h.owner.state.value
            val saved = h.persistence.durable
            val requests = h.fake.calls.size
            val body = when (path) {
                paths[0] -> SyntheticAuth.DEVICE
                paths[1] -> SyntheticAuth.AUTHORIZATION
                else -> SyntheticAuth.TOKENS
            }
            old.reply(SyntheticAuth.response(body))
            h.owner.commandsSettled()
            assertTrue(old.cancelled)
            assertSame(terminal, h.owner.state.value)
            assertSame(saved, h.persistence.durable)
            assertEquals(requests, h.fake.calls.size)
            if (replace) assertNotNull(saved) else assertNull(saved)
            h.assertShared()
        }
    }

    @Test fun delayedAlreadyAdmittedObserverCannotRestoreOldStateAfterPeerLogout() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delivered = CompletableDeferred<ConnectionState>()
            val observation = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val collector = observation.launch {
                h.owner.state.collect { state ->
                    if (state.phase == ConnectionPhase.OBSERVED) {
                        entered.complete(Unit)
                        release.await() // Already-admitted delivery, not a new owner publication.
                    }
                    if (state.phase == ConnectionPhase.SIGNED_OUT) delivered.complete(state)
                }
            }
            try {
                h.respondNormally()
                h.first.owner.connect()
                withTimeout(5_000) { entered.await() }
                h.second.owner.signOut()
                h.phase(ConnectionPhase.SIGNED_OUT)
                val terminal = h.owner.state.value
                val requests = h.fake.calls.size
                assertNull(terminal.observations)
                assertNull(h.persistence.durable)
                release.complete(Unit)
                assertSame(terminal, withTimeout(5_000) { delivered.await() })
                h.owner.readUsage()
                h.owner.readUsage(refreshSession = true)
                h.owner.commandsSettled()
                assertSame(terminal, h.owner.state.value)
                assertEquals(requests, h.fake.calls.size)
            } finally {
                release.complete(Unit)
                collector.cancelAndJoin()
                observation.cancel()
            }
        }
    }

    @Test fun confirmedRemovalSurvivesOrdinaryReadAndRotationButIsConsumedExactlyOnce() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val confirmation = h.owner.accountRemoval
            h.respondNormally()
            h.owner.readUsage(refreshSession = true)
            h.phase(ConnectionPhase.OBSERVED)
            assertSame(confirmation, h.owner.accountRemoval)
            h.second.owner.signOut(confirmation)
            h.phase(ConnectionPhase.SIGNED_OUT)
            val terminal = h.owner.state.value
            val requests = h.fake.calls.size
            h.first.owner.signOut(confirmation)
            h.owner.commandsSettled()
            assertSame(terminal, h.owner.state.value)
            assertEquals(requests, h.fake.calls.size)
            assertNull(h.persistence.durable)
            assertEquals(1, h.persistence.deleteCount)
        }
    }

    @Test fun queuedReplacementRejectsPredecessorConfirmationBeforeSuccessorPublication() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val predecessor = h.owner.accountRemoval
            h.respondNormally()
            h.first.owner.connect()
            h.second.owner.signOut(predecessor) // Must check permission at execution, not enqueue.
            h.phase(ConnectionPhase.OBSERVED)
            val terminal = h.owner.state.value
            val durable = h.persistence.durable
            val count = h.fake.calls.size
            h.owner.signOut(predecessor)
            h.owner.commandsSettled()
            assertNotSame(predecessor, h.owner.accountRemoval)
            assertSame(terminal, h.owner.state.value)
            assertSame(durable, h.persistence.durable)
            assertNotNull(durable)
            assertEquals(count, h.fake.calls.size)
            assertEquals(1, h.persistence.deleteCount) // Only replacement's predecessor cleanup.
        }
    }

    @Test fun failedConfirmedRemovalRequiresFreshPermissionAndRetriesTheSameTeardown() = runBlocking {
        Fixture().use { h ->
            h.restored()
            val confirmation = h.owner.accountRemoval
            h.persistence.deleteFailure = CredentialFailure.FAILED_WRITE
            h.owner.signOut(confirmation)
            h.phase(ConnectionPhase.FAILED)
            val failed = h.owner.state.value
            assertEquals(ConnectionProblem.STORAGE, failed.problem)
            assertNotNull(h.persistence.durable)
            h.persistence.deleteFailure = null
            h.owner.signOut(confirmation)
            h.owner.commandsSettled()
            assertSame(failed, h.owner.state.value)
            assertEquals(1, h.persistence.deleteCount)
            h.owner.signOut(h.owner.accountRemoval)
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertTrue(h.fake.calls.isEmpty())
            assertEquals(2, h.persistence.deleteCount)
        }
    }

    private class Commander(val owner: ConnectionController) {
        val states = ConcurrentLinkedQueue<ConnectionState>()
        private val observation = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        init { observation.launch(start = CoroutineStart.UNDISPATCHED) { owner.state.collect { states.add(it) } } }
        fun detach() = observation.cancel()
    }

    private class Held {
        private val reached = CompletableDeferred<AuthFake.Call>()
        fun capture(call: AuthFake.Call) { reached.complete(call) }
        suspend fun awaitCall() = withTimeout(5_000) { reached.await() }
    }

    private class Fixture(waitMillis: Long = 5_000) : AutoCloseable {
        val fake = AuthFake()
        val clock = AuthClock()
        val persistence = FakeCredentialPersistence()
        @Volatile private var binding: SessionGeneration? = null
        private val rebound = object : CredentialPersistence by persistence {
            override fun read(): CredentialResult<CredentialEnvelope> = persistence.durable?.let {
                CredentialResult.Success(CredentialEnvelope(requireNotNull(binding), it.accessToken, it.refreshToken))
            } ?: CredentialResult.Failure(CredentialFailure.MISSING)
        }
        private val store = SerializedCredentialStore(rebound, activate = { binding = it })
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val initial = syntheticEnvelope(store.openSession())
        private var deletionGate: ControlledGate? = null
        init { store.replace(initial, CredentialCancellation()) }
        val owner = ConnectionController(store, DeviceCodeAuthenticator(fake, store, clock, clock::pause),
            NativeFeasibilityReader(fake, clock, clock::pause), scope, storageWaitMillis = waitMillis)
        val first = Commander(owner)
        val second = Commander(owner)
        fun commander(second: Boolean) = if (second) this.second else first
        suspend fun restored() = phase(ConnectionPhase.RESTORED)
        suspend fun phase(phase: ConnectionPhase) {
            // OBSERVED publishes endpoint facts before B2's completion clears refreshing.
            // Identity/no-request oracles must start after that current-generation work.
            try {
                withTimeout(5_000) { owner.state.first {
                    it.phase == phase && (phase != ConnectionPhase.OBSERVED || !it.refresh.refreshing)
                } }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError("settled $phase: ${owner.state.value}, refreshing=${owner.state.value.refresh.refreshing}", error)
            }
        }
        suspend fun awaitRemoved() { withTimeout(5_000) { while (persistence.durable != null) delay(1) } }
        fun assertShared() { assertSame(first.owner, second.owner); assertSame(first.owner.state, second.owner.state) }
        fun hold(path: String): Held = Held().also { held ->
            fake.respond = { call -> if (call.request.url.encodedPath == path) held.capture(call) else respond(call) }
        }
        fun respondNormally() { fake.respond = ::respond }
        private fun respond(call: AuthFake.Call) {
            val body = when (call.request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
                "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
                "/oauth/token" -> SyntheticAuth.TOKENS
                else -> "{}"
            }
            call.reply(SyntheticAuth.response(body))
        }
        fun holdDeletion() = ControlledGate().also { gate ->
            deletionGate = gate
            persistence.beforeDelete = gate::pause
        }
        override fun close() {
            deletionGate?.release()
            first.detach()
            second.detach()
            runBlocking { withTimeout(5_000) { owner.shutdown() } }
        }
    }

    private companion object {
        const val ROTATED = """{"access_token":"synthetic-rotated-access","refresh_token":"synthetic-rotated-refresh"}"""
    }
}
