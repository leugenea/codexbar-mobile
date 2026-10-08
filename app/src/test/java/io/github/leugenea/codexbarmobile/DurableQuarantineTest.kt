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
                    assertFalse(worker.isDone)
                    assertTrue(h.fake.calls.isEmpty())
                    assertTrue(h.keyExists.get())
                    assertTrue(h.target.exists())
                    h.laneAvailable()
                } finally { h.gate!!.release() }
                worker.result()
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
                    assertEquals(ConnectionPhase.SIGNING_OUT, controller.state.value.phase)
                    assertTrue(h.keyExists.get())
                    assertTrue(h.target.exists())
                    h.laneAvailable()
                } finally { h.gate!!.release() }
                refreshing.result()
                assertEquals(ConnectionPhase.FAILED, controller.state.value.phase)
                assertEquals(ConnectionProblem.STORAGE, controller.state.value.problem)
                assertEquals(1, h.deletes)
                assertFalse(h.target.exists())
                assertTrue(h.deletionAttempted.get())
                assertNull(controller.state.value.observations)
            }
        }
    }

    @Test fun successorRestoreWaitsOutsideRuntimeLaneAndKeepsItsPersistenceBinding() = runBlocking {
        Fixture().use { h ->
            val generation = h.slot.openSession()
            h.gate = ControlledGate()
            ControlledWorker { h.slot.delete(generation) }.use { deleting ->
                h.gate!!.awaitEntered()
                val successor = h.slot.openSession()
                val barrier = h.slot.ownership.deletionBarrier()!!
                ControlledWorker { h.slot.read(successor) }.use { reading ->
                    try {
                        withTimeout(5_000) { while (barrier.numberOfDependents == 0) delay(1) }
                        assertFalse(reading.isDone)
                        assertTrue(h.slot.isActive(successor))
                        assertFalse(h.slot.ownership.ifActive(successor) { fail("No request during deletion") })
                        h.laneAvailable()
                    } finally { h.gate!!.release() }
                    assertTrue(deleting.result() is CredentialResult.Success)
                    assertEquals(CredentialResult.Failure(CredentialFailure.MISSING), reading.result())
                    h.absent()
                    assertTrue(h.slot.replace(syntheticEnvelope(successor), CredentialCancellation()) is CredentialResult.Success)
                    assertTrue(h.slot.read(successor) is CredentialResult.Success)
                }
            }
        }
    }

    @Test fun displacedPendingReplacementCannotReopenOverANewerOwner() {
        Fixture().use { h ->
            val generation = h.slot.openSession()
            h.gate = ControlledGate()
            ControlledWorker { h.slot.replaceSession(generation) }.use { replacing ->
                lateinit var successor: SessionGeneration
                try {
                    h.gate!!.awaitEntered()
                    successor = h.slot.openSession()
                    assertTrue(h.slot.isActive(successor))
                    h.laneAvailable()
                } finally { h.gate!!.release() }
                assertEquals(CredentialResult.Failure(CredentialFailure.STALE_GENERATION), replacing.result())
                assertTrue(h.slot.isActive(successor))
                h.absent()
                assertTrue(h.slot.replace(syntheticEnvelope(successor), CredentialCancellation()) is CredentialResult.Success)
            }
        }
    }

    @Test fun quarantineAdmissionSurvivesSuccessorBetweenRetirementAndDeferredRemoval() = runBlocking {
        Fixture().use { h ->
            val controller = h.controller()
            h.failWrite.set(true)
            h.fake.respond = { it.reply(SyntheticAuth.response(SyntheticAuth.TOKENS)) }
            h.beforeRemoval = ControlledGate()
            h.watch(controller, ConnectionPhase.REAUTH_REQUIRED)
            ControlledWorker { controller.readUsage(refreshSession = true) }.use { refreshing ->
                h.beforeRemoval!!.awaitEntered()
                val successor = h.slot.openSession()
                val barrier = h.slot.ownership.deletionBarrier()!!
                ControlledWorker { h.slot.read(successor) }.use { restoring ->
                    try {
                        withTimeout(5_000) { while (barrier.numberOfDependents == 0) delay(1) }
                        assertEquals(ConnectionPhase.SIGNING_OUT, controller.state.value.phase)
                        assertEquals(0, h.deletes)
                        assertTrue(h.keyExists.get())
                        assertTrue(h.target.exists())
                        assertFalse(restoring.isDone)
                        assertFalse(h.slot.ownership.ifActive(successor) { fail("No admission before quarantine") })
                        h.laneAvailable()
                    } finally { h.beforeRemoval!!.release() }
                    refreshing.result()
                    assertEquals(CredentialResult.Failure(CredentialFailure.MISSING), restoring.result())
                    assertEquals(ConnectionPhase.REAUTH_REQUIRED, controller.state.value.phase)
                    h.absent()
                    h.observerFailures()
                    assertEquals(1, h.fake.calls.size)
                }
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
        var beforeRemoval: ControlledGate? = null
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
        val slot = CredentialSlotOwner(ProtectedCredentialPersistence(
            target, cipher, UUID(1, 2),
        ))
        private val initial = syntheticEnvelope(slot.openSession())
        private val adapter = object : CredentialStore by slot {
            override fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion> =
                when (val admitted = slot.admitDeletion(generation)) {
                    is CredentialResult.Failure -> admitted
                    is CredentialResult.Success -> CredentialResult.Success(object : CredentialDeletion {
                        override fun complete(): CredentialResult<Unit> {
                            beforeRemoval?.pause()
                            return admitted.value.complete()
                        }
                    })
                }
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

        fun controller(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): ConnectionController =
            ConnectionController(adapter, DeviceCodeAuthenticator(fake, adapter, clock, clock::pause),
                NativeFeasibilityReader(fake, clock, clock::pause), CoroutineScope(SupervisorJob() + dispatcher))
                .also { controllers += it }

        fun watch(controller: ConnectionController, terminal: ConnectionPhase) {
            observers.launch {
                controller.state.collect { state ->
                    if (state.phase == terminal) {
                        terminalStates.add(state)
                        try { absent(); laneAvailable() } catch (failure: Throwable) { failures.compareAndSet(null, failure) }
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
                    assertFalse(worker.isDone)
                    assertNotEquals(terminal, controller.state.value.phase)
                    assertTrue(terminalStates.isEmpty())
                    assertTrue(keyExists.get())
                    assertTrue(target.exists())
                    assertFalse(slot.isActive(generation))
                    assertFalse(slot.ownership.ifActive(generation) { fail("No stale request admission") })
                    laneAvailable()
                } finally { gate!!.release() }
                worker.result()
                assertEquals(terminal, controller.state.value.phase)
                assertFalse(terminalStates.isEmpty())
                observerFailures()
                absent()
            }
        }

        fun laneAvailable() {
            ControlledWorker { slot.ownership.serialized { true } }.use { assertTrue(it.result()) }
        }

        fun observerFailures() { failures.get()?.let { throw it } }
        fun absent() { assertEquals(1, deletes); assertFalse(keyExists.get()); assertFalse(target.exists()) }
        override fun close() { gate?.release(); beforeRemoval?.release(); controllers.forEach { it.close() }; observers.cancel() }
    }
}
