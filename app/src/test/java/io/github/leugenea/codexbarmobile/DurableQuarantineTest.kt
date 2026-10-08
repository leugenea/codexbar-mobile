package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The same delegating failed-write seam as native acceptance, with gated durable removals. */
class DurableQuarantineTest {
    @Test fun failedRotationDoesNotPublishReauthBeforeKeyAndFileAreDeleted() = gatedReauth(writeFailure = true)
    @Test fun terminalRefreshDoesNotPublishReauthBeforeKeyAndFileAreDeleted() = gatedReauth(writeFailure = false)

    private fun gatedReauth(writeFailure: Boolean) {
        Fixture().use { h ->
            val controller = h.controller()
            h.failWrite.set(writeFailure)
            h.fake.respond = { it.reply(SyntheticAuth.response(SyntheticAuth.TOKENS, if (writeFailure) 200 else 401)) }
            h.watch(controller, ConnectionPhase.REAUTH_REQUIRED)
            h.gated(controller, ConnectionPhase.REAUTH_REQUIRED) { controller.readUsage(refreshSession = true) }
            assertEquals(1, h.fake.calls.size)
            assertNull(controller.state.value.observations)
        }
    }

    @Test fun terminalGetDoesNotPublishReauthBeforeDurableDeletion() {
        Fixture().use { h ->
            val controller = h.controller()
            h.fake.respond = { call -> call.reply(if (call.request.url.encodedPath == "/oauth/token")
                SyntheticAuth.response(SyntheticAuth.TOKENS) else SyntheticAuth.response("{}", 401)) }
            h.watch(controller, ConnectionPhase.REAUTH_REQUIRED)
            h.gated(controller, ConnectionPhase.REAUTH_REQUIRED) { controller.readUsage() }
            assertNull(controller.state.value.observations)
        }
    }

    @Test fun logoutDoesNotPublishSignedOutBeforeDurableDeletion() {
        Fixture().use { h ->
            val controller = h.controller()
            h.watch(controller, ConnectionPhase.SIGNED_OUT)
            h.gated(controller, ConnectionPhase.SIGNED_OUT, controller::signOut)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun replacementCannotAdmitAuthBeforePreviousKeyAndFileAreDeleted() {
        Fixture().use { h ->
            val controller = h.controller()
            h.fake.respond = { call -> call.reply(SyntheticAuth.response(when (call.request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
                "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
                "/oauth/token" -> SyntheticAuth.TOKENS
                else -> "{}"
            })) }
            h.gate = ControlledGate()
            ControlledWorker { controller.connect() }.use { worker ->
                try {
                    h.gate!!.awaitEntered()
                    worker.result() // Admission returns independently of held deletion I/O.
                    assertTrue(h.fake.calls.isEmpty())
                    assertTrue(h.keyExists.get())
                    assertTrue(h.target.exists())
                    h.laneAvailable()
                } finally { h.gate!!.release() }
                worker.result()
                runBlocking { awaitPhase(controller, ConnectionPhase.OBSERVED) }
                assertEquals(ConnectionPhase.OBSERVED, controller.state.value.phase)
                assertEquals(1, h.deletes)
                assertTrue(h.keyExists.get()) // Only the newly stored pair may recreate it.
            }
        }
    }

    @Test fun corruptRestoreDoesNotPublishReauthBeforeDurableDeletion() = runBlocking {
        Fixture().use { h ->
            h.target.writeBytes(byteArrayOf(0))
            h.gate = ControlledGate()
            val controller = h.controller(Dispatchers.Default)
            try {
                h.gate!!.awaitEntered()
                assertEquals(ConnectionPhase.RESTORING, controller.state.value.phase)
                assertTrue(h.keyExists.get())
                assertTrue(h.target.exists())
                h.laneAvailable()
            } finally { h.gate!!.release() }
            withTimeout(5_000) { controller.state.first { it.phase == ConnectionPhase.REAUTH_REQUIRED } }
            h.absent()
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun failedQuarantineRemovalPublishesStorageFailureOnlyAfterItsDurableAttempt() {
        Fixture().use { h ->
            val controller = h.controller()
            h.failWrite.set(true)
            h.failDelete.set(true)
            h.fake.respond = { it.reply(SyntheticAuth.response(SyntheticAuth.TOKENS)) }
            h.gate = ControlledGate()
            ControlledWorker { controller.readUsage(refreshSession = true) }.use { refreshing ->
                try {
                    h.gate!!.awaitEntered()
                    assertEquals(ConnectionPhase.READING, controller.state.value.phase)
                    assertTrue(h.keyExists.get())
                    assertTrue(h.target.exists())
                    h.laneAvailable()
                } finally { h.gate!!.release() }
                refreshing.result()
                runBlocking { awaitPhase(controller, ConnectionPhase.FAILED) }
                assertEquals(ConnectionPhase.FAILED, controller.state.value.phase)
                assertEquals(ConnectionProblem.STORAGE, controller.state.value.problem)
                assertEquals(1, h.deletes)
                assertFalse(h.target.exists())
                assertTrue(h.deletionAttempted.get())
                assertNull(controller.state.value.observations)
            }
        }
    }

    @Test fun admittedDeletionIsExactlyOnceEvenAfterAReplacementCapabilityAppears() {
        Fixture().use { h ->
            val generation = h.slot.openSession()
            val admitted = (h.slot.admitDeletion(generation) as CredentialResult.Success).value
            val successor = h.slot.openSession()
            assertTrue(h.slot.isActive(successor))
            assertTrue(h.keyExists.get())
            assertEquals(0, h.deletes)
            assertTrue(admitted.complete() is CredentialResult.Success)
            assertTrue(admitted.complete() is CredentialResult.Success)
            h.absent()
            assertEquals(CredentialResult.Failure(CredentialFailure.MISSING), h.slot.read(successor))
        }
    }

    /** Synthetic durable-file seam; native acceptance retains real AtomicCredentialFile/fsync. */
    private class MemoryCredentialFile : CredentialFile {
        private val bytes = AtomicReference<ByteArray?>()
        override fun exists(): Boolean = bytes.get() != null
        override fun read(): ByteArray = requireNotNull(bytes.get()).copyOf()
        fun writeBytes(value: ByteArray) { bytes.set(value.copyOf()) }
        override fun stage(bytes: ByteArray): PreparedCredentialWrite {
            val staged = bytes.copyOf() // Protected persistence wipes the supplied staging buffer.
            return object : PreparedCredentialWrite {
                override fun commit(): CredentialResult<Unit> { writeBytes(staged); return CredentialResult.Success(Unit) }
                override fun discard() = Unit
            }
        }
        override fun delete() { bytes.set(null) }
    }

    private class Fixture : AutoCloseable {
        val target = MemoryCredentialFile()
        val keyExists = AtomicBoolean()
        val deletionAttempted = AtomicBoolean()
        val failWrite = AtomicBoolean()
        val failDelete = AtomicBoolean()
        var gate: ControlledGate? = null
        var deletes = 0
        private val cipher = object : CredentialCipher {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray, existingFile: Boolean): SealedCredential {
                keyExists.set(true)
                return SealedCredential(ByteArray(12), plaintext.copyOf() + ByteArray(16))
            }
            override fun decrypt(sealed: SealedCredential, aad: ByteArray): ByteArray =
                sealed.ciphertext.copyOf(sealed.ciphertext.size - 16)
            override fun deleteKey() {
                deletes++
                deletionAttempted.set(true)
                gate?.pause()
                if (failDelete.get()) throw java.io.IOException("synthetic deletion failure")
                keyExists.set(false)
            }
        }
        private val persistence = ProtectedCredentialPersistence(target, cipher, UUID(1, 2))
        val slot = protectedStore(persistence)
        private val initial = syntheticEnvelope(slot.openSession())
        private val adapter = object : CredentialStore by slot {
            override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> =
                if (failWrite.get()) CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
                else slot.replace(envelope, cancellation)
        }
        val fake = AuthFake()
        private val clock = AuthClock()
        private val controllers = mutableListOf<ConnectionController>()
        private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val failures = AtomicReference<Throwable?>()
        private val terminalStates = ConcurrentLinkedQueue<ConnectionState>()

        init { check(slot.replace(initial, CredentialCancellation()) is CredentialResult.Success) }

        fun controller(
            dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
        ): ConnectionController =
            ConnectionController(adapter, DeviceCodeAuthenticator(fake, adapter, clock, clock::pause),
                NativeFeasibilityReader(fake, clock, clock::pause), scope)
                .also { controllers += it; if (gate == null) runBlocking { awaitPhase(it, ConnectionPhase.RESTORED) } }

        fun observe(
            controller: ConnectionController,
            assertion: (ConnectionState) -> Unit = {},
        ): ConcurrentLinkedQueue<ConnectionState> {
            val states = ConcurrentLinkedQueue<ConnectionState>()
            observers.launch(start = CoroutineStart.UNDISPATCHED) {
                controller.state.collect { state ->
                    try { assertion(state) } catch (failure: Throwable) { failures.compareAndSet(null, failure) }
                    states.add(state)
                }
            }
            return states
        }

        fun watch(controller: ConnectionController, terminal: ConnectionPhase) {
            observers.launch {
                controller.state.collect { state ->
                    if (state.phase == terminal) {
                        try { absent() } catch (failure: Throwable) { failures.compareAndSet(null, failure) }
                        terminalStates.add(state)
                    }
                }
            }
        }

        fun gated(controller: ConnectionController, terminal: ConnectionPhase, action: () -> Unit) {
            val generation = (controller.session.snapshot() as SessionResult.Ready).envelope.generation
            gate = ControlledGate()
            ControlledWorker(action).use { worker ->
                try {
                    try { gate!!.awaitEntered() } catch (timeout: IllegalStateException) {
                        throw AssertionError("Deletion not reached: state=${controller.state.value}, requests=${fake.calls.map { it.request.url.encodedPath }}, workerDone=${worker.isDone}", timeout)
                    }
                    worker.result() // Admission returns independently of held deletion I/O.
                    assertNotEquals(terminal, controller.state.value.phase)
                    assertTrue(terminalStates.isEmpty())
                    assertTrue(keyExists.get())
                    assertTrue(target.exists())
                    assertFalse(slot.isActive(generation))
                    laneAvailable()
                } finally { gate!!.release() }
                worker.result()
                runBlocking {
                    withTimeout(5_000) { controller.state.first { it.phase == terminal } }
                    withTimeout(5_000) { while (terminalStates.isEmpty()) delay(1) }
                }
                assertEquals(terminal, controller.state.value.phase)
                assertFalse(terminalStates.isEmpty())
                observerFailures()
                absent()
            }
        }

        fun laneAvailable() {
            val controller = controllers.last()
            runBlocking { withTimeout(2_000) { controller.commandsSettled() } }
        }

        fun observerFailures() { failures.get()?.let { throw it } }
        fun absent() { assertEquals(1, deletes); assertFalse(keyExists.get()); assertFalse(target.exists()) }
        override fun close() { gate?.release(); controllers.forEach { runBlocking { withTimeout(5_000) { it.shutdown() } } }; observers.cancel() }
    }
}

private suspend fun awaitPhase(controller: ConnectionController, phase: ConnectionPhase) {
    withTimeout(5_000) { controller.state.first { it.phase == phase } }
}
