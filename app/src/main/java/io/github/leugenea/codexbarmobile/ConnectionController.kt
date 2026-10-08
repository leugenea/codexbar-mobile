package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

internal enum class ConnectionPhase { RESTORING, IDLE, AUTHENTICATING, READING, OBSERVED, RESTORED, CANCELLED, SIGNING_OUT, SIGNED_OUT, REAUTH_REQUIRED, FAILED }
internal enum class ConnectionProblem { STORAGE, AUTH, READ, BROWSER }

/** Never serialize this state. Diagnostics deliberately omit even the in-memory code. */
internal class ConnectionState(
    val phase: ConnectionPhase,
    val auth: AuthState = AuthState.Idle,
    val observations: FeasibilityObservations? = null,
    val problem: ConnectionProblem? = null,
    val refresh: UsageRefreshState = UsageRefreshState(),
) {
    val busy: Boolean get() = phase in setOf(ConnectionPhase.RESTORING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.READING, ConnectionPhase.SIGNING_OUT)
    override fun toString(): String = "ConnectionState(phase=$phase, binding=UNRESOLVED, identity=UNVERIFIED, problem=$problem)"
}

/**
 * The one process-wide session owner. Every command, request admission and publication
 * runs on one serial coroutine dispatcher. Protected I/O suspends off that dispatcher.
 * Activities only observe/submit commands; finishing the last Activity has no side effect.
 */
internal class ConnectionController(
    private val store: CredentialStore,
    private val authenticator: DeviceCodeAuthenticator,
    reader: NativeFeasibilityReader,
    private val scope: CoroutineScope,
    private val storageReady: Boolean = true,
    mutationDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val storageWaitMillis: Long = 5_000L,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    refreshClock: io.github.leugenea.codexbarmobile.transport.TransportClock = reader.clock,
) {
    private val ownerScope = CoroutineScope(scope.coroutineContext + mutationDispatcher)
    private val mutableState = MutableStateFlow(ConnectionState(ConnectionPhase.RESTORING))
    val state: StateFlow<ConnectionState> = mutableState
    internal val session = reader.session(store, ownerScope, storageDispatcher, ::sessionInvalidated)
    private val authenticatedReader = AuthenticatedProviderReader(session, reader)
    private var work: Job? = null
    private var revision = 0L
    private var generation: SessionGeneration? = null
    private var deletion: Deferred<CredentialResult<Unit>>? = null
    private var closed = false
    private val stopped = CompletableDeferred<Unit>()
    private val usageRefresh = UsageRefresh(ownerScope, refreshClock, ::refreshUsage, ::refreshChanged)

    init { work = ownerScope.launch { restore() } }

    private suspend fun restore() {
        if (!storageReady) { publish(0, storageFailure()); return }
        val restoredGeneration = store.openSession().also { generation = it }
        val restored = ownerScope.async(storageDispatcher) { store.read(restoredGeneration) }.await()
        if (revision != 0L || closed) return
        when (restored) {
            is CredentialResult.Success -> {
                session.adopt(restored.value)
                publish(0, ConnectionState(ConnectionPhase.RESTORED))
            }
            is CredentialResult.Failure -> restoreFailure(restored)
        }
    }

    private suspend fun restoreFailure(result: CredentialResult.Failure) {
        val phase = when (result.category) {
            CredentialFailure.MISSING -> ConnectionPhase.IDLE
            CredentialFailure.KEY_LOST, CredentialFailure.CORRUPT -> {
                removeCredentials()
                if (awaitDeletion()) ConnectionPhase.REAUTH_REQUIRED else ConnectionPhase.FAILED
            }
            else -> ConnectionPhase.FAILED
        }
        publish(0, ConnectionState(phase, problem = if (phase == ConnectionPhase.IDLE) null else ConnectionProblem.STORAGE))
    }

    private fun command(action: suspend () -> Unit) {
        ownerScope.launch { if (!closed) action() }
    }

    fun connect() = command {
        if (!storageReady || mutableState.value.busy || cleanupPending()) return@command
        val owner = retire()
        retryFailedDeletion()
        removeCredentials()
        mutableState.value = ConnectionState(ConnectionPhase.AUTHENTICATING)
        work = ownerScope.launch { login(owner) }
    }

    private suspend fun login(owner: Long) {
        if (!awaitDeletion()) { publish(owner, storageFailure()); return }
        if (!current(owner)) return
        val next = store.openSession().also { generation = it }
        authenticator.start(ownerScope, next)
        val terminal = authenticator.state.first { auth ->
            publish(owner, ConnectionState(ConnectionPhase.AUTHENTICATING, auth))
            auth is AuthState.Connected || auth is AuthState.Failed || auth == AuthState.Cancelled
        }
        if (terminal !is AuthState.Connected) {
            publish(owner, ConnectionState(ConnectionPhase.FAILED, terminal, problem = ConnectionProblem.AUTH))
            return
        }
        connected(owner, terminal)
    }

    private suspend fun connected(owner: Long, terminal: AuthState.Connected) {
        val credentials = ownerScope.async(storageDispatcher) { store.read(terminal.generation) }.await()
        if (!current(owner)) return
        when (credentials) {
            is CredentialResult.Failure -> {
                removeCredentials()
                awaitDeletion()
                publish(owner, storageFailure())
            }
            is CredentialResult.Success -> {
                session.adopt(credentials.value)
                publish(owner, ConnectionState(ConnectionPhase.RESTORED, terminal))
                usageRefresh.request()
            }
        }
    }

    fun readUsage(refreshSession: Boolean = false) = command {
        if (cleanupPending() || session.snapshot() !is SessionResult.Ready) return@command
        usageRefresh.request(refreshSession, explicit = true)
    }

    /** Observer identities keep one Activity from cancelling another visible Activity. */
    fun usageForeground(observer: Any, foreground: Boolean) = command {
        usageRefresh.foreground(observer, foreground)
    }

    private fun refreshChanged(refresh: UsageRefreshState) {
        val previous = mutableState.value
        val phase = if (previous.phase == ConnectionPhase.READING && !refresh.refreshing) ConnectionPhase.OBSERVED else previous.phase
        mutableState.value = ConnectionState(phase, previous.auth, refresh.observations, previous.problem, refresh)
    }

    private suspend fun refreshUsage(refreshSession: Boolean, explicit: Boolean, observed: (EndpointObservation) -> Unit) {
        if (cleanupPending()) return
        val active = session.snapshot() as? SessionResult.Ready ?: return
        val owner = revision
        val auth = AuthState.Connected(active.envelope.generation)
        if (explicit) session.retryTransient()
        publish(owner, ConnectionState(ConnectionPhase.READING, auth, usageRefresh.state.observations,
            refresh = usageRefresh.state))
        val result = if (refreshSession) session.refresh(active.envelope, session.deadline()) else null
        observe(owner, auth, observed, result as? SessionResult.Failed)
    }

    private suspend fun observe(owner: Long, auth: AuthState, observed: (EndpointObservation) -> Unit,
        refreshFailure: SessionResult.Failed? = null) {
        val facts = if (refreshFailure == null) authenticatedReader.read(observed) else FeasibilityObservations(
            EndpointObservation(io.github.leugenea.codexbarmobile.transport.ReadOperation.USAGE, error = refreshFailure.problem.readError()),
            EndpointObservation(io.github.leugenea.codexbarmobile.transport.ReadOperation.RESET_INVENTORY, error = refreshFailure.problem.readError()),
        ).also { observed(it.usage); observed(it.inventory) }
        if (!current(owner) || session.snapshot() is SessionResult.Failed) return
        publish(owner, observedState(auth, facts))
    }

    /** A10 terminal settlement must outlive the lifecycle-cancellable usage waiter. */
    private fun sessionInvalidated() {
        val owner = revision
        usageRefresh.reset()
        publish(owner, ConnectionState(ConnectionPhase.READING))
        ownerScope.launch {
            val removed = session.awaitRemoval()
            publish(owner, if (removed) ConnectionState(ConnectionPhase.REAUTH_REQUIRED, problem = ConnectionProblem.AUTH)
                else storageFailure())
        }
    }

    private fun observedState(auth: AuthState, facts: FeasibilityObservations) =
        ConnectionState(ConnectionPhase.OBSERVED, auth, usageRefresh.state.observations,
            problem = if (facts.successful) null else ConnectionProblem.READ, refresh = usageRefresh.state)

    fun cancel() = command { cancelTo(ConnectionState(ConnectionPhase.CANCELLED)) }
    fun browserFailed() = command { cancelTo(ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.BROWSER)) }

    private fun cancelTo(terminal: ConnectionState) {
        if (mutableState.value.phase in setOf(ConnectionPhase.RESTORING, ConnectionPhase.SIGNING_OUT)) return
        if (cleanupPending() && mutableState.value.phase == ConnectionPhase.FAILED) return
        val owner = retire()
        if (cleanupPending()) {
            mutableState.value = ConnectionState(ConnectionPhase.SIGNING_OUT)
            work = ownerScope.launch {
                publish(owner, if (awaitDeletion()) terminal else storageFailure())
            }
        } else mutableState.value = terminal
    }

    fun signOut() = command {
        if (!storageReady) { mutableState.value = storageFailure(); return@command }
        val owner = retire()
        retryFailedDeletion()
        removeCredentials()
        mutableState.value = ConnectionState(ConnectionPhase.SIGNING_OUT)
        work = ownerScope.launch {
            publish(owner, if (awaitDeletion()) ConnectionState(ConnectionPhase.SIGNED_OUT) else storageFailure())
        }
    }

    private suspend fun retryFailedDeletion() {
        if (cleanupPending()) return
        val state = mutableState.value
        if (state.phase != ConnectionPhase.FAILED || state.problem != ConnectionProblem.STORAGE) return
        // A timeout is not a durable failure. A successful ticket stays exactly once.
        if (deletion?.await() !is CredentialResult.Failure && !session.removalFailed()) return
        session.retryRemoval()
        deletion = null
        generation = store.openSession()
    }

    /** Reserve synchronously on the owner lane; the independent owner child performs I/O once. */
    private fun removeCredentials() {
        val active = generation ?: return
        val admitted = store.admitDeletion(active)
        generation = null
        if (admitted !is CredentialResult.Success) return
        val previous = deletion
        deletion = ownerScope.async(storageDispatcher) {
            previous?.await()
            admitted.value.complete()
        }
    }

    private suspend fun awaitDeletion(): Boolean {
        if (!session.awaitRemoval()) return false
        return withTimeoutOrNull(storageWaitMillis) { deletion?.await() !is CredentialResult.Failure } ?: false
    }

    private fun cleanupPending() = deletion?.isCompleted == false || session.removalPending()

    private fun retire(): Long {
        revision++
        usageRefresh.reset()
        work?.cancel()
        authenticator.cancel()
        session.retire()
        return revision
    }

    private fun current(owner: Long) = !closed && revision == owner
    private fun publish(owner: Long, state: ConnectionState) { if (current(owner)) mutableState.value = state }
    private fun storageFailure() = ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE)

    /** Test/process shutdown only, never Activity.onDestroy/ViewModel.onCleared. */
    fun close() = command {
        closed = true
        retire()
        usageRefresh.close()
        mutableState.value = ConnectionState(ConnectionPhase.CANCELLED)
        ownerScope.launch {
            // Do not clear a holder while an old deletion can still erase its successor.
            deletion?.join()
            session.awaitShutdown()
            stopped.complete(Unit)
            scope.cancel()
        }
    }

    internal suspend fun commandsSettled() {
        val receipt = CompletableDeferred<Unit>()
        ownerScope.launch { receipt.complete(Unit) }
        receipt.await()
    }

    internal suspend fun shutdown() {
        close()
        stopped.await()
        scope.coroutineContext[Job]?.join()
    }
}
