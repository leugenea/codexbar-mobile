package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
                    assertEquals(ConnectionPhase.SIGNING_OUT, controller.state.value.phase)
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
                    awaitPhase(controller, ConnectionPhase.REAUTH_REQUIRED)
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

    @Test fun staleLogoutKeepsActiveOwnerBusyUntilDurableDeletion() = staleCommand(replacement = false, failedDeletion = false)
    @Test fun staleReplacementKeepsActiveOwnerBusyUntilDurableDeletion() = staleCommand(replacement = true, failedDeletion = false)
    @Test fun failedStaleLogoutReportsStorageToBothOwnersAfterDeletion() = staleCommand(replacement = false, failedDeletion = true)
    @Test fun failedStaleReplacementReportsStorageToBothOwnersAfterDeletion() = staleCommand(replacement = true, failedDeletion = true)

    @Test fun staleLogoutDisplacesObservedOwnerOnlyAfterDurableDeletion() = staleCommand(replacement = false, failedDeletion = false, observed = true)
    @Test fun staleReplacementDisplacesObservedOwnerOnlyAfterDurableDeletion() = staleCommand(replacement = true, failedDeletion = false, observed = true)
    @Test fun failedStaleLogoutReportsStorageToObservedOwnerAfterDeletion() = staleCommand(replacement = false, failedDeletion = true, observed = true)
    @Test fun failedStaleReplacementReportsStorageToObservedOwnerAfterDeletion() = staleCommand(replacement = true, failedDeletion = true, observed = true)

    private fun staleCommand(replacement: Boolean, failedDeletion: Boolean, observed: Boolean = false) = runBlocking {
        Fixture().use { h ->
            val stale = h.controller()
            val active = h.controller()
            awaitPhase(stale, ConnectionPhase.REAUTH_REQUIRED)
            awaitPhase(active, ConnectionPhase.RESTORED)
            if (observed) {
                h.fake.respond = { it.reply(SyntheticAuth.response("{}")) }
                active.readUsage()
                awaitPhase(active, ConnectionPhase.OBSERVED)
                assertNotNull(active.state.value.observations)
                // Only new command traffic is counted below, not these preparatory reads.
                h.fake.calls.clear()
            }
            val generation = (active.session.snapshot() as SessionResult.Ready).envelope.generation
            val released = AtomicBoolean()
            val barrier = AtomicReference<CompletableFuture<Boolean>?>()
            val activeStates = h.observe(active) { state ->
                if (state.phase in setOf(ConnectionPhase.REAUTH_REQUIRED, ConnectionPhase.FAILED)) {
                    assertTrue("Active terminal notification preceded deletion release", released.get())
                    assertTrue("Active terminal notification preceded durable outcome", barrier.get()?.isDone == true)
                    assertEquals(if (failedDeletion) ConnectionPhase.FAILED else ConnectionPhase.REAUTH_REQUIRED, state.phase)
                    if (failedDeletion) assertEquals(ConnectionProblem.STORAGE, state.problem)
                    assertEquals(1, h.deletes)
                    assertEquals(failedDeletion, h.keyExists.get())
                    assertFalse(h.target.exists())
                }
            }
            val commandStates = h.observe(stale)
            activeStates.clear() // Initial RESTORED is not a deletion-time notification.
            commandStates.clear() // The stale controller was already REAUTH_REQUIRED.
            // Hold the first auth reply so replacement cannot recreate the removed pair
            // before both the active terminal value and its notification are checked.
            val authRequest = CompletableDeferred<AuthFake.Call>()
            h.fake.respond = { call ->
                if (call.request.url.encodedPath == "/api/accounts/deviceauth/usercode") authRequest.complete(call)
                else call.reply(SyntheticAuth.response(when (call.request.url.encodedPath) {
                    "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
                    "/oauth/token" -> SyntheticAuth.TOKENS
                    else -> "{}"
                }))
            }
            h.failDelete.set(failedDeletion)
            h.gate = ControlledGate()
            ControlledWorker { if (replacement) stale.connect() else stale.signOut() }.use { command ->
                try {
                    h.gate!!.awaitEntered()
                    barrier.set(h.slot.ownership.serialized { h.slot.ownership.deletionBarrier()!! })
                    assertEquals(ConnectionPhase.SIGNING_OUT, active.state.value.phase)
                    assertTrue(active.state.value.busy)
                    assertNull(active.state.value.observations)
                    assertTrue(stale.state.value.busy)
                    // Wakeups may be deferred, but none may deliver a committed terminal.
                    assertTrue(activeStates.all { it.busy })
                    assertTrue(commandStates.all { it.busy })
                    assertFalse(barrier.get()!!.isDone)
                    assertTrue(h.keyExists.get())
                    assertTrue(h.target.exists())
                    assertFalse(h.slot.isActive(generation))
                    assertFalse(h.slot.ownership.ifActive(generation) { fail("No stale request admission") })
                    assertTrue(h.fake.calls.isEmpty())
                    h.laneAvailable()
                    h.observerFailures()
                } finally {
                    released.set(true)
                    h.gate!!.release()
                }
                command.result() // A command receipt is not a coroutine completion receipt.
                assertEquals(!failedDeletion, barrier.get()!!.get(2, TimeUnit.SECONDS))
                val activeTerminal = if (failedDeletion) ConnectionPhase.FAILED else ConnectionPhase.REAUTH_REQUIRED
                awaitPhase(active, activeTerminal)
                withTimeout(5_000) { while (activeStates.none { it.phase == activeTerminal }) delay(1) }
                assertNull(active.state.value.observations)
                if (failedDeletion) {
                    assertEquals(ConnectionProblem.STORAGE, active.state.value.problem)
                    awaitPhase(stale, ConnectionPhase.FAILED)
                    assertEquals(ConnectionProblem.STORAGE, stale.state.value.problem)
                    assertTrue(h.keyExists.get())
                    assertFalse(h.target.exists())
                    assertTrue(h.fake.calls.isEmpty())
                } else {
                    h.absent()
                    if (replacement) {
                        val request = withTimeout(5_000) { authRequest.await() }
                        assertEquals(ConnectionPhase.AUTHENTICATING, stale.state.value.phase)
                        request.reply(SyntheticAuth.response(SyntheticAuth.DEVICE))
                        awaitPhase(stale, ConnectionPhase.OBSERVED)
                        assertTrue(h.keyExists.get())
                        assertTrue(h.target.exists())
                    } else {
                        awaitPhase(stale, ConnectionPhase.SIGNED_OUT)
                        assertTrue(h.fake.calls.isEmpty())
                    }
                }
                val commandTerminal = stale.state.value.phase
                withTimeout(5_000) { while (commandStates.none { it.phase == commandTerminal }) delay(1) }
                assertEquals(1, h.deletes)
                assertNull(h.slot.ownership.serialized { h.slot.ownership.deletionBarrier() })
                h.observerFailures()
            }
        }
    }

    @Test fun successorCloseCancelsRestorationBeforeHeldDeletionCompletes() = cancelledSuccessor(closeController = true)
    @Test fun successorScopeCancellationJoinsBeforeHeldDeletionCompletes() = cancelledSuccessor(closeController = false)

    private fun cancelledSuccessor(closeController: Boolean) = runBlocking {
        Fixture().use { h ->
            val generation = h.slot.openSession()
            h.gate = ControlledGate()
            val root = SupervisorJob()
            val executor = Executors.newSingleThreadExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            val scope = CoroutineScope(root + dispatcher)
            try {
                // Positive coroutine/executor readiness, not a thread-name or sleep oracle.
                withTimeout(2_000) { scope.async { currentCoroutineContext().ensureActive(); true }.await() }
                ControlledWorker { h.slot.delete(generation) }.use { deleting ->
                    try {
                        h.gate!!.awaitEntered()
                        val barrier = h.slot.ownership.serialized { h.slot.ownership.deletionBarrier()!! }
                        val successor = h.controller(scope = scope)
                        val states = h.observe(successor)
                        withTimeout(2_000) { while (barrier.numberOfDependents == 0) delay(1) }
                        assertTrue(root.children.any())
                        assertEquals(ConnectionPhase.RESTORING, successor.state.value.phase)
                        assertFalse(successor.session.snapshot() is SessionResult.Ready)
                        if (closeController) successor.close() else scope.cancel()
                        // This must join with real deletion still held, not after finally releases it.
                        withTimeout(2_000) { root.join() }
                        assertTrue(root.isCancelled)
                        assertTrue(root.isCompleted)
                        assertTrue(root.children.none())
                        assertTrue(executor.submit<Boolean> { true }.get(2, TimeUnit.SECONDS))
                        assertFalse(deleting.isDone)
                        assertFalse(barrier.isDone)
                        assertSame(barrier, h.slot.ownership.serialized { h.slot.ownership.deletionBarrier() })
                        assertTrue(h.keyExists.get())
                        assertTrue(h.target.exists())
                        assertTrue(h.fake.calls.isEmpty())
                        assertFalse(successor.session.snapshot() is SessionResult.Ready)
                        assertTrue(states.all { it.phase in setOf(ConnectionPhase.RESTORING, ConnectionPhase.CANCELLED) })
                        assertTrue(successor.state.value.phase in setOf(ConnectionPhase.RESTORING, ConnectionPhase.CANCELLED))
                        h.laneAvailable()
                        h.gate!!.release()
                        assertTrue(deleting.result() is CredentialResult.Success)
                        assertTrue(barrier.get(2, TimeUnit.SECONDS))
                        assertNull(h.slot.ownership.serialized { h.slot.ownership.deletionBarrier() })
                        assertTrue(root.isCompleted)
                        assertFalse(successor.session.snapshot() is SessionResult.Ready)
                        assertTrue(states.all { it.phase in setOf(ConnectionPhase.RESTORING, ConnectionPhase.CANCELLED) })
                        assertTrue(h.fake.calls.isEmpty())
                        h.absent()
                        h.observerFailures()
                    } finally { h.gate!!.release() }
                }
            } finally {
                h.gate!!.release()
                root.cancel()
                withTimeout(5_000) { root.join() }
                dispatcher.close()
                executor.shutdownNow()
            }
        }
    }

    private suspend fun awaitPhase(controller: ConnectionController, phase: ConnectionPhase) {
        withTimeout(5_000) { controller.state.first { it.phase == phase } }
    }

    @Test fun closingLogoutWaiterLeavesItsIndependentDurableRunnerAndBarrierAlive() = runBlocking {
        Fixture(removalExecutor = SerializedCredentialStore.durableRemovals).use { h ->
            val root = SupervisorJob()
            val controller = h.controller(scope = CoroutineScope(root + Dispatchers.Unconfined))
            h.gate = ControlledGate()
            controller.signOut()
            h.gate!!.awaitEntered()
            val barrier = requireNotNull(h.slot.ownership.deletionBarrier())
            try {
                assertTrue("Logout waiter reached its real outcome future", barrier.numberOfDependents > 0)
                assertEquals(ConnectionPhase.SIGNING_OUT, controller.state.value.phase)
                controller.close()
                withTimeout(5_000) { root.join() }
                assertTrue(root.isCancelled)
                assertTrue(root.children.none())
                assertSame(barrier, h.slot.ownership.deletionBarrier())
                assertFalse(barrier.isDone)
                assertTrue(h.keyExists.get())
                assertTrue(h.target.exists())
                assertTrue(h.fake.calls.isEmpty())
                assertEquals(ConnectionPhase.CANCELLED, controller.state.value.phase)
            } finally { h.gate!!.release() }
            withTimeout(5_000) { while (!barrier.isDone) delay(1) }
            h.absent()
            assertEquals(ConnectionPhase.CANCELLED, controller.state.value.phase)
        }
    }

    @Test fun closingReplacementKeepsItsProcessSlotAndBarrierForANewController() = runBlocking {
        Fixture(removalExecutor = SerializedCredentialStore.durableRemovals).use { h ->
            val root = SupervisorJob()
            val old = h.controller(scope = CoroutineScope(root + Dispatchers.Unconfined))
            h.gate = ControlledGate()
            old.connect()
            h.gate!!.awaitEntered()
            val barrier = requireNotNull(h.slot.ownership.deletionBarrier())
            try {
                old.close()
                withTimeout(2_000) { root.join() }
                assertTrue(root.isCompleted)
                // The real production registry must not manufacture a second kernel after close.
                val retained = KeystoreCredentialStore.sharedOwner(h.slotPath) { error("Cannot replace a process slot") }
                assertSame(h.slot, retained)
                assertSame(barrier, retained.ownership.deletionBarrier())
                val fresh = h.controller()
                assertEquals(ConnectionPhase.RESTORING, fresh.state.value.phase)
                assertFalse(fresh.session.snapshot() is SessionResult.Ready)
                assertTrue(h.fake.calls.isEmpty())
                assertFalse(barrier.isDone)
                assertTrue(h.keyExists.get())
                assertTrue(h.target.exists())
                h.gate!!.release()
                awaitPhase(fresh, ConnectionPhase.IDLE)
                assertTrue(barrier.isDone)
                h.absent()
                assertTrue(h.fake.calls.isEmpty())
                assertEquals(ConnectionPhase.CANCELLED, old.state.value.phase)
            } finally { h.gate!!.release(); old.close() }
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

    private class Fixture(
        removalExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
    ) : AutoCloseable {
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
        val slotPath = "synthetic-slot/${UUID.randomUUID()}"
        val slot = KeystoreCredentialStore.sharedOwner(slotPath) {
            CredentialSlotOwner(ProtectedCredentialPersistence(
                target, cipher, UUID(1, 2),
            ), removalExecutor = removalExecutor)
        }
        private val initial = syntheticEnvelope(slot.openSession())
        private val adapter = object : CredentialStore by slot {
            override fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion> =
                when (val admitted = slot.admitDeletion(generation)) {
                    is CredentialResult.Failure -> admitted
                    is CredentialResult.Success -> CredentialResult.Success(object : CredentialDeletion by admitted.value {
                        override fun start() {
                            beforeRemoval?.pause()
                            admitted.value.start()
                        }
                        override fun complete(): CredentialResult<Unit> {
                            start()
                            return admitted.value.complete()
                        }
                        override suspend fun await(): CredentialResult<Unit> {
                            start()
                            return admitted.value.await()
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

        fun controller(
            dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
        ): ConnectionController =
            ConnectionController(adapter, DeviceCodeAuthenticator(fake, adapter, clock, clock::pause),
                NativeFeasibilityReader(fake, clock, clock::pause), scope)
                .also { controllers += it }

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
                        try { absent(); laneAvailable() } catch (failure: Throwable) { failures.compareAndSet(null, failure) }
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
            ControlledWorker { slot.ownership.serialized { true } }.use { assertTrue(it.result()) }
        }

        fun observerFailures() { failures.get()?.let { throw it } }
        fun absent() { assertEquals(1, deletes); assertFalse(keyExists.get()); assertFalse(target.exists()) }
        override fun close() { gate?.release(); beforeRemoval?.release(); controllers.forEach { it.close() }; observers.cancel() }
    }
}
