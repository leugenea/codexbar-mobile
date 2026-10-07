package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.CredentialResult
import io.github.leugenea.codexbarmobile.credentials.CredentialFailure
import io.github.leugenea.codexbarmobile.credentials.CredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class ConnectionPhase { RESTORING, IDLE, AUTHENTICATING, READING, OBSERVED, RESTORED, CANCELLED, SIGNING_OUT, SIGNED_OUT, REAUTH_REQUIRED, FAILED }
internal enum class ConnectionProblem { STORAGE, AUTH, READ, BROWSER }

/** Never serialize this state. Diagnostics deliberately omit even the in-memory code. */
internal class ConnectionState(
    val phase: ConnectionPhase,
    val auth: AuthState = AuthState.Idle,
    val observations: FeasibilityObservations? = null,
    val problem: ConnectionProblem? = null,
) {
    val busy: Boolean get() = phase in setOf(ConnectionPhase.RESTORING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.READING, ConnectionPhase.SIGNING_OUT)
    override fun toString(): String = "ConnectionState(phase=$phase, binding=UNRESOLVED, identity=UNVERIFIED, problem=$problem)"
}

/** A new Activity owner must settle a previously admitted local deletion before restore. */
internal class ConnectionDeletionBarrier {
    private var pending: CompletableDeferred<Unit>? = null
    @Synchronized fun admit(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { pending = it }
    suspend fun await() { snapshot()?.await() }
    @Synchronized private fun snapshot() = pending
}

/**
 * One bounded login and two selected reads with session-owned rotation; no periodic traffic. Commands invalidate publication before cancelling detached work.
 * The Activity's retained owner supplies a worker scope; no Activity or saved state here.
 */
internal class ConnectionController(
    private val store: CredentialStore,
    private val authenticator: DeviceCodeAuthenticator,
    private val reader: NativeFeasibilityReader,
    private val scope: CoroutineScope,
    storageReady: Boolean = true,
    private val deletions: ConnectionDeletionBarrier = ConnectionDeletionBarrier(),
) {
    private val mutableState = MutableStateFlow(ConnectionState(ConnectionPhase.RESTORING))
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    internal val session = reader.session(store, scope)
    private val authenticatedReader = AuthenticatedProviderReader(session, reader)
    private var work: Job? = null
    private var revision = 0L
    private var closed = false
    private var deleting = false
    private val storageReady = storageReady

    init {
        work = scope.launch {
            deletions.await()
            val generation = synchronized(this@ConnectionController) {
                if (closed || revision != 0L) return@launch
                if (storageReady) store.openSession() else null
            }
            val restored = if (generation != null) store.read(generation) else
                CredentialResult.Failure(CredentialFailure.CORRUPT)
            val phase = when (restored) {
                is CredentialResult.Success -> ConnectionPhase.RESTORED
                is CredentialResult.Failure -> when (restored.category) {
                    CredentialFailure.MISSING -> ConnectionPhase.IDLE
                    CredentialFailure.KEY_LOST, CredentialFailure.CORRUPT -> if (storageReady) ConnectionPhase.REAUTH_REQUIRED else ConnectionPhase.FAILED
                    else -> ConnectionPhase.FAILED
                }
            }
            synchronized(this@ConnectionController) {
                if (!closed && revision == 0L && restored is CredentialResult.Success) session.adopt(restored.value)
                publish(0, ConnectionState(phase, problem = if (phase in setOf(ConnectionPhase.FAILED, ConnectionPhase.REAUTH_REQUIRED)) ConnectionProblem.STORAGE else null))
            }
        }
    }

    fun connect() {
        synchronized(this) {
            if (closed || deleting || !storageReady || mutableState.value.busy) return
            val owner = retire()
            val replacement = store.openSession()
            val next = scope.launch(start = CoroutineStart.LAZY) { login(owner, replacement) }
            work = next
            mutableState.value = ConnectionState(ConnectionPhase.AUTHENTICATING)
            if (!closed && revision == owner) next.start()
        }
    }

    private suspend fun login(owner: Long, replacement: io.github.leugenea.codexbarmobile.credentials.SessionGeneration) {
        deletions.await()
        val admitted = store.replaceSession(replacement)
        if (admitted is CredentialResult.Failure) {
            publish(owner, ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE))
            return
        }
        val context = kotlinx.coroutines.currentCoroutineContext()
        synchronized(this) {
            if (closed || revision != owner) return
            authenticator.start(CoroutineScope(context), (admitted as CredentialResult.Success).value)
        }
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
        when (val credentials = store.read(terminal.generation)) {
            is CredentialResult.Failure -> publish(owner, ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE))
            is CredentialResult.Success -> {
                synchronized(this) {
                    if (closed || revision != owner) return
                    session.adopt(credentials.value)
                }
                publish(owner, ConnectionState(ConnectionPhase.READING, terminal))
                observe(owner, terminal)
            }
        }
    }

    /** Explicit owner action only; B2 will own automatic cadence. */
    fun readUsage(refreshSession: Boolean = false) {
        synchronized(this) {
            if (closed || deleting || mutableState.value.busy) return
            val active = session.snapshot() as? SessionResult.Ready
            if (active == null) {
                if (mutableState.value.phase in setOf(ConnectionPhase.RESTORED, ConnectionPhase.OBSERVED))
                    publish(revision, displaced())
                return
            }
            revision++
            val owner = revision
            work?.cancel()
            session.retryTransient()
            val next = scope.launch(start = CoroutineStart.LAZY) {
                val refreshed = if (refreshSession) session.refresh(active.envelope, session.deadline()) else null
                observe(owner, AuthState.Connected(active.envelope.generation), refreshed as? SessionResult.Failed)
            }
            work = next
            publish(owner, ConnectionState(ConnectionPhase.READING, AuthState.Connected(active.envelope.generation)))
            if (!closed && revision == owner) next.start()
        }
    }

    private suspend fun observe(owner: Long, auth: AuthState, refreshFailure: SessionResult.Failed? = null) {
        val facts = if (refreshFailure == null) authenticatedReader.read() else FeasibilityObservations(
            EndpointObservation(io.github.leugenea.codexbarmobile.transport.ReadOperation.USAGE, error = refreshFailure.problem.readError()),
            EndpointObservation(io.github.leugenea.codexbarmobile.transport.ReadOperation.RESET_INVENTORY, error = refreshFailure.problem.readError()),
        )
        val status = session.snapshot()
        val reauth = status is SessionResult.Failed
        publish(owner, ConnectionState(if (reauth) ConnectionPhase.REAUTH_REQUIRED else ConnectionPhase.OBSERVED,
            auth = if (reauth) AuthState.Idle else auth, observations = if (reauth) null else facts,
            problem = if (reauth) ConnectionProblem.AUTH else if (facts.successful) null else ConnectionProblem.READ))
    }

    fun cancel() {
        synchronized(this) {
            if (closed || deleting || mutableState.value.phase == ConnectionPhase.RESTORING) return
            retire()
            mutableState.value = ConnectionState(ConnectionPhase.CANCELLED)
        }
    }

    fun browserFailed() {
        synchronized(this) {
            if (closed || deleting || mutableState.value.phase == ConnectionPhase.RESTORING) return
            retire()
            mutableState.value = ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.BROWSER)
        }
    }

    /** Local only. Opening a new capability invalidates any old/staging auth write. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun signOut() {
        synchronized(this) {
            if (closed || deleting) return
            if (!storageReady) {
                mutableState.value = ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE)
                return
            }
            val owner = retire()
            deleting = true
            // Atomic admission ensures Activity finish cannot drop a requested local deletion
            // before its synchronous store operation starts. No network or suspension inside.
            // Capture the capability at admission: an old worker can never delete a newer login.
            val generation = store.openSession()
            val completion = deletions.admit()
            val next = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    val result = store.delete(generation)
                    synchronized(this@ConnectionController) {
                        deleting = false
                        publish(owner, ConnectionState(if (result is CredentialResult.Success) ConnectionPhase.SIGNED_OUT
                            else ConnectionPhase.FAILED, problem = if (result is CredentialResult.Failure) ConnectionProblem.STORAGE else null))
                    }
                } finally { completion.complete(Unit) }
            }
            // Admit the deletion before notification. A close/replacement observer cannot
            // cancel this non-suspending owned removal or bypass the restoration barrier.
            work = next
            next.invokeOnCompletion { completion.complete(Unit) }
            next.start()
            if (!closed && revision == owner && deleting)
                mutableState.value = ConnectionState(ConnectionPhase.SIGNING_OUT)
        }
    }

    private fun retire(): Long {
        revision++
        work?.cancel()
        authenticator.cancel()
        session.retire()
        return revision
    }

    private fun publish(owner: Long, state: ConnectionState) {
        synchronized(this) {
            if (closed || revision != owner) return
            val connected = state.auth as? AuthState.Connected
            val stale = connected?.let { !store.isActive(it.generation) } ?:
                (state.phase == ConnectionPhase.RESTORED && session.snapshot() is SessionResult.Failed)
            mutableState.value = if (stale) displaced() else state
        }
    }

    private fun displaced() = ConnectionState(ConnectionPhase.REAUTH_REQUIRED, problem = ConnectionProblem.AUTH)

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            retire()
            mutableState.value = ConnectionState(ConnectionPhase.CANCELLED)
            scope.cancel()
        }
    }
}
