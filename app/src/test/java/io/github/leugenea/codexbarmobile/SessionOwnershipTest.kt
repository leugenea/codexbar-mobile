package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Sequencer contracts, including callbacks that would deadlock if run under its lock. */
class SessionOwnershipTest {
    @Test fun staleAdmissionAndRegistrationFailClosedWithoutAnEffect() {
        val store = SerializedCredentialStore(FakeCredentialPersistence())
        val stale = store.openSession()
        val current = store.openSession()
        var effects = 0
        assertFalse(store.ownership.ifActive(stale) { effects++ })
        store.ownership.onDisplaced(stale) { effects++ }.cancel()
        assertEquals(1, effects)
        assertTrue(store.ownership.ifActive(current) { effects++ })
        assertEquals(2, effects)
    }

    @Test fun deferredEffectsDrainOutsideTheLaneEvenWhenAnotherEffectThrows() {
        val lane = SessionOwnership()
        var ran = false
        lane.serialized {
            lane.defer { throw IllegalStateException("synthetic cancellation failure") }
            lane.defer {
                onOtherThread { lane.serialized { ran = true } }
            }
            assertFalse(ran)
        }
        assertTrue(ran)
    }

    @Test fun notificationsReadLatestCommittedStateInsteadOfDelayedOldValues() = runBlocking {
        val lane = SessionOwnership()
        val state = SequencedStateFlow(lane, 0)
        val observed = mutableListOf<Int>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val collector = scope.launch { state.collect { observed += it } }
        try {
            lane.serialized {
                lane.defer { onOtherThread { state.value = 2 } }
                state.value = 1
                assertEquals(1, state.value)
                assertEquals(listOf(0), observed)
            }
            assertEquals(2, state.value)
            assertEquals(listOf(2), state.replayCache)
            assertEquals(listOf(0, 2), observed)
            state.value = 2
            assertEquals(listOf(0, 2), observed)
            assertEquals(2, state.first())
        } finally { collector.cancelAndJoin(); scope.cancel() }
    }

    @Test fun undispatchedObserverCanDisplaceOnAnotherThreadWithoutHoldingTheSlotLane() = runBlocking {
        val lane = SessionOwnership()
        val state = SequencedStateFlow(lane, 0)
        var callbackReached = false
        val collector = launch(Dispatchers.Unconfined) {
            state.collect {
                if (it == 1) {
                    callbackReached = true
                    onOtherThread { lane.serialized { state.value = 2 } }
                }
            }
        }
        try {
            state.value = 1
            assertTrue(callbackReached)
            assertEquals(2, state.value)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun throwingTransportCancellationStillSettlesAwaitAndOtherRevocations() = runBlocking {
        val store = SerializedCredentialStore(FakeCredentialPersistence())
        val generation = store.openSession()
        var cancelled = false
        var late: ((TransportResult) -> Unit)? = null
        val transport = AuthTransport { _, _, terminal ->
            late = terminal
            CancellationHandle { cancelled = true; throw IllegalStateException("synthetic cancellation failure") }
        }
        val awaiting = async(Dispatchers.Unconfined) {
            transport.await(AuthProtocol.usercodeRequest(), ReadDeadline.after(AuthClock().now()), store.ownership, generation)
        }
        var revoked = false
        store.ownership.onDisplaced(generation) { store.ownership.defer { revoked = true } }
        store.openSession()
        assertTrue(cancelled)
        assertTrue(revoked)
        assertEquals(TransportResult.Failure(TransportFailure.CANCELLED), awaiting.await())
        late!!.invoke(SyntheticAuth.response(SyntheticAuth.DEVICE))
        assertEquals(TransportResult.Failure(TransportFailure.CANCELLED), awaiting.await())
    }

    @Test fun staleAuthStartCannotCancelTheLiveSuccessor() = runBlocking {
        val store = SerializedCredentialStore(FakeCredentialPersistence())
        val stale = store.openSession()
        val generation = store.openSession()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = AuthFake()
        val auth = DeviceCodeAuthenticator(fake, store)
        try {
            val current = auth.start(scope, generation)
            assertEquals(1, fake.calls.size)
            val rejected = auth.start(scope, stale)
            rejected.join()
            assertTrue(rejected.isCancelled)
            assertFalse(current.isCancelled)
            assertFalse(fake.calls.single().cancelled)
            assertEquals(AuthState.RequestingCode, auth.state.value)
        } finally { auth.cancel(); scope.cancel() }
    }

    @Test fun completedAuthStateIsProactivelyRevokedOnSlotDisplacement() = runBlocking {
        val store = SerializedCredentialStore(FakeCredentialPersistence())
        val fake = AuthFake()
        val clock = AuthClock()
        fake.respond = { call -> call.reply(SyntheticAuth.response(when (call.request.url.encodedPath) {
            "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
            "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
            else -> SyntheticAuth.TOKENS
        })) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val auth = DeviceCodeAuthenticator(fake, store, clock, clock::pause)
        try {
            auth.start(scope).join()
            assertTrue(auth.state.value is AuthState.Connected)
            store.delete(store.openSession())
            assertEquals(AuthState.Failed(AuthStage.STORE, AuthFailure.STALE_OWNER), auth.state.value)
        } finally { auth.cancel(); scope.cancel() }
    }

    private fun onOtherThread(action: () -> Unit) {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({
            try { action() } catch (error: Throwable) { failure.set(error) } finally { finished.countDown() }
        }, "ownership-callback-diagnostic").apply { isDaemon = true; start() }
        check(finished.await(5, TimeUnit.SECONDS)) { "Callback retained the slot lane" }
        thread.join(5_000)
        failure.get()?.let { throw it }
    }
}
