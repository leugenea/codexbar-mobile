package io.github.leugenea.codexbarmobile.credentials

import io.github.leugenea.codexbarmobile.ConnectionController
import io.github.leugenea.codexbarmobile.ConnectionPhase
import io.github.leugenea.codexbarmobile.ConnectionProblem
import io.github.leugenea.codexbarmobile.ConnectionState
import io.github.leugenea.codexbarmobile.NativeFeasibilityReader
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.auth.AuthClock
import io.github.leugenea.codexbarmobile.auth.AuthProtocol
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.auth.await
import io.github.leugenea.codexbarmobile.transport.CancellationHandle
import io.github.leugenea.codexbarmobile.transport.ReadDeadline
import io.github.leugenea.codexbarmobile.transport.TransportFailure
import io.github.leugenea.codexbarmobile.transport.TransportResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real asynchronous kernel runner; only the persistence I/O seam is synthetic. */
class DeletionWaitTest {
    @Test fun cancellingARemovalWaiterDoesNotCancelOrReleaseTheDurableRunner() = runBlocking {
        Fixture().use { h ->
            val command = h.store.admitCommandRemoval(replacement = false)
            command.deletion.start()
            h.gate.awaitEntered()
            val root = SupervisorJob()
            val scope = CoroutineScope(root + Dispatchers.Unconfined)
            val waiter = scope.launch { command.deletion.await(); fail("Cancelled waiter cannot report success") }
            try {
                assertFalse(waiter.isCompleted)
                root.cancel()
                withTimeout(5_000) { root.join() }
                assertTrue(waiter.isCompleted)
                assertTrue(waiter.isCancelled)
                assertNotNull(h.store.ownership.deletionBarrier())
                assertEquals(1, h.attempts.get())
                assertNotNull(h.persistence.durable)
            } finally { h.gate.release() }
            assertTrue(command.deletion.await() is CredentialResult.Success)
            assertNull(h.persistence.durable)
            assertNull(h.store.ownership.deletionBarrier())
            assertEquals(1, h.attempts.get())
        }
    }

    @Test fun timedOutRemovalWaiterFailsClosedUntilTheActualOutcome() = runBlocking {
        Fixture(waitMillis = 50).use { h ->
            val command = h.store.admitCommandRemoval(replacement = true)
            command.deletion.start()
            h.gate.awaitEntered()
            val barrier = requireNotNull(h.store.ownership.deletionBarrier())
            try {
                assertEquals(CredentialResult.Failure(CredentialFailure.FAILED_WRITE), command.deletion.await())
                assertSame(barrier, h.store.ownership.deletionBarrier())
                assertFalse(barrier.isDone)
                assertFalse(h.store.ownership.ifActive(command.successor!!) { fail("No request after timeout") })
                assertNotNull(h.persistence.durable)
                assertEquals(1, h.attempts.get())
            } finally { h.gate.release() }
            withTimeout(5_000) { while (!barrier.isDone) yield() }
            assertTrue(command.deletion.await() is CredentialResult.Success)
            assertEquals(1, h.attempts.get())
            assertNull(h.persistence.durable)
        }
    }

    @Test fun timedOutRestorationCannotReadOrWriteAheadOfDeletion() = runBlocking {
        Fixture(waitMillis = 50).use { h ->
            val command = h.store.admitCommandRemoval(replacement = false)
            command.deletion.start()
            h.gate.awaitEntered()
            val successor = h.store.openSession()
            val barrier = requireNotNull(h.store.ownership.deletionBarrier())
            try {
                val result = h.store.ownership.awaitSettled { h.store.read(successor) }
                assertEquals(CredentialResult.Failure(CredentialFailure.FAILED_WRITE), result)
                assertEquals(0, h.reads.get())
                assertEquals(CredentialResult.Failure(CredentialFailure.FAILED_WRITE),
                    h.store.replace(syntheticEnvelope(successor), CredentialCancellation()))
                assertSame(barrier, h.store.ownership.deletionBarrier())
                assertFalse(barrier.isDone)
                assertEquals(1, h.persistence.commitCount)
                assertNotNull(h.persistence.durable)
            } finally { h.gate.release() }
            withTimeout(5_000) { while (!barrier.isDone) yield() }
            assertEquals(CredentialResult.Failure(CredentialFailure.MISSING), h.store.read(successor))
        }
    }

    @Test fun chainedRunnersWaitWithoutOccupyingAnExecutorThreadAndRunExactlyOnce() = runBlocking {
        Fixture().use { h ->
            val first = h.store.admitCommandRemoval(replacement = false)
            first.deletion.start()
            h.gate.awaitEntered()
            val second = h.store.admitCommandRemoval(replacement = false)
            second.deletion.start()
            second.deletion.start()
            try {
                assertEquals(1, h.attempts.get())
                assertFalse(h.store.ownership.deletionBarrier()!!.isDone)
                assertTrue(h.store.ownership.serialized { true })
            } finally { h.gate.release() }
            assertTrue(second.deletion.await() is CredentialResult.Success)
            assertTrue(first.deletion.await() is CredentialResult.Success)
            assertTrue(second.deletion.complete() is CredentialResult.Success)
            assertEquals(2, h.attempts.get())
            assertEquals(2, h.persistence.deleteCount)
            assertNull(h.persistence.durable)
        }
    }

    @Test fun activeRestorationCannotEnqueueATransportRequestBehindHeldRemoval() = runBlocking {
        Fixture().use { h ->
            val command = h.store.admitCommandRemoval(replacement = false)
            h.gate.awaitEntered()
            val successor = h.store.openSession()
            val requests = AtomicInteger()
            val transport = AuthTransport { _, _, _ -> requests.incrementAndGet(); CancellationHandle {} }
            try {
                val waiter = async(Dispatchers.Unconfined) {
                    transport.await(AuthProtocol.usercodeRequest(), ReadDeadline.after(AuthClock().now()), h.store.ownership, successor)
                }
                assertTrue(waiter.isCompleted)
                assertEquals(TransportResult.Failure(TransportFailure.CANCELLED), waiter.await())
                assertEquals(0, requests.get())
                assertFalse(h.store.ownership.deletionBarrier()!!.isDone)
            } finally { h.gate.release() }
            assertTrue(command.deletion.await() is CredentialResult.Success)
        }
    }

    @Test fun displacedNetworkWaiterAndItsHandleCancelBeforeDurableRemovalReturns() = runBlocking {
        Fixture().use { h ->
            val generation = h.store.openSession()
            val cancellations = AtomicInteger()
            val transport = AuthTransport { _, _, _ -> CancellationHandle { cancellations.incrementAndGet() } }
            val waiter = async(Dispatchers.Unconfined) {
                transport.await(AuthProtocol.usercodeRequest(), ReadDeadline.after(AuthClock().now()), h.store.ownership, generation)
            }
            assertFalse(waiter.isCompleted)
            val command = h.store.admitCommandRemoval(replacement = false)
            h.gate.awaitEntered()
            try {
                assertEquals(TransportResult.Failure(TransportFailure.CANCELLED), withTimeout(5_000) { waiter.await() })
                assertEquals(1, cancellations.get())
                assertFalse(h.store.ownership.deletionBarrier()!!.isDone)
                assertNotNull(h.persistence.durable)
            } finally { h.gate.release() }
            assertTrue(command.deletion.await() is CredentialResult.Success)
        }
    }

    @Test fun timedOutRestorationNotifiesStorageFailureWhileRemovalIsStillHeld() = timeoutNotification(command = null)
    @Test fun timedOutLogoutNotifiesStorageFailureWhileRemovalIsStillHeld() = timeoutNotification(command = false)
    @Test fun timedOutReplacementNotifiesStorageFailureWhileRemovalIsStillHeld() = timeoutNotification(command = true)
    @Test fun timedOutDisplacedOwnerNotifiesStorageFailureWhileRemovalIsStillHeld() = timeoutNotification(command = false, displaced = true)

    private fun timeoutNotification(command: Boolean?, displaced: Boolean = false) = runBlocking {
        Fixture(waitMillis = 50).use { h ->
            val requests = AtomicInteger()
            val transport = AuthTransport { _, _, _ -> requests.incrementAndGet(); CancellationHandle {} }
            val root = SupervisorJob()
            if (command == null) h.store.admitCommandRemoval(replacement = false)
            val controller = ConnectionController(h.store, DeviceCodeAuthenticator(transport, h.store),
                NativeFeasibilityReader(transport), CoroutineScope(root + Dispatchers.Unconfined))
            val failed = CompletableDeferred<ConnectionState>()
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                controller.state.collect { state ->
                    if (state.phase == ConnectionPhase.FAILED && state.problem == ConnectionProblem.STORAGE) failed.complete(state)
                }
            }
            try {
                if (command != null) {
                    assertEquals(ConnectionPhase.RESTORED, controller.state.value.phase)
                    if (displaced) h.store.admitCommandRemoval(replacement = false)
                    else if (command) controller.connect() else controller.signOut()
                }
                h.gate.awaitEntered()
                val barrier = requireNotNull(h.store.ownership.deletionBarrier())
                val notified = withTimeout(2_000) { failed.await() }
                assertSame(controller.state.value, notified)
                assertFalse(notified.busy)
                assertNull(notified.observations)
                assertFalse(barrier.isDone)
                assertSame(barrier, h.store.ownership.deletionBarrier())
                assertNotNull(h.persistence.durable)
                assertEquals(0, requests.get())
                h.gate.release()
                withTimeout(2_000) { while (!barrier.isDone) delay(1) }
                assertNull(h.persistence.durable)
                assertEquals(ConnectionPhase.FAILED, controller.state.value.phase)
                assertEquals(0, requests.get())
            } finally {
                h.gate.release()
                observer.cancelAndJoin()
                controller.close()
                withTimeout(2_000) { root.join() }
            }
        }
    }

    private class Fixture(waitMillis: Long = 5_000) : AutoCloseable {
        val persistence = FakeCredentialPersistence()
        val gate = ControlledGate()
        val attempts = AtomicInteger()
        val reads = AtomicInteger()
        private var binding: SessionGeneration? = null
        private val counted = object : CredentialPersistence by persistence {
            override fun read(): CredentialResult<CredentialEnvelope> {
                reads.incrementAndGet()
                return when (val read = persistence.read()) {
                    is CredentialResult.Failure -> read
                    is CredentialResult.Success -> CredentialResult.Success(CredentialEnvelope(
                        requireNotNull(binding), read.value.accessToken, read.value.refreshToken,
                    ))
                }
            }
        }
        val store = SerializedCredentialStore(counted, activate = { binding = it }, storageWaitMillis = waitMillis)
        init {
            check(store.replace(syntheticEnvelope(store.openSession()), CredentialCancellation()) is CredentialResult.Success)
            persistence.beforeDelete = { if (attempts.incrementAndGet() == 1) gate.pause() }
        }
        override fun close() { gate.release() }
    }
}
