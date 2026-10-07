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

internal enum class ConnectionPhase { RESTORING, IDLE, AUTHENTICATING, READING, OBSERVED, RESTORED, CANCELLED, SIGNING_OUT, SIGNED_OUT, FAILED }
internal enum class ConnectionProblem { STORAGE, AUTH, READ, BROWSER }

/** Never serialize this state. Diagnostics deliberately omit even the in-memory code. */
internal class ConnectionState(
    val phase: ConnectionPhase,
    val auth: AuthState = AuthState.Idle,
    val observations: FeasibilityObservations? = null,
    val problem: ConnectionProblem? = null,
) {
    val busy: Boolean get() = phase in setOf(ConnectionPhase.RESTORING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.READING, ConnectionPhase.SIGNING_OUT)
    override fun toString(): String = "ConnectionState(phase=$phase, binding=UNRESOLVED, gate=NOT_GO, problem=$problem)"
}

/** A new Activity owner must settle a previously admitted local deletion before restore. */
internal class ConnectionDeletionBarrier {
    private var pending: CompletableDeferred<Unit>? = null
    @Synchronized fun admit(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { pending = it }
    suspend fun await() { snapshot()?.await() }
    @Synchronized private fun snapshot() = pending
}

/**
 * The first gate only: one bounded login and two selected reads, no refresh/rotation or
 * periodic traffic. Commands invalidate publication before cancelling detached work.
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
                is CredentialResult.Failure -> if (restored.category == CredentialFailure.MISSING)
                    ConnectionPhase.IDLE else ConnectionPhase.FAILED
            }
            publish(0, ConnectionState(phase, problem = if (phase == ConnectionPhase.FAILED) ConnectionProblem.STORAGE else null))
        }
    }

    fun connect() {
        synchronized(this) {
            if (closed || deleting || !storageReady || mutableState.value.busy) return
            val owner = retire()
            mutableState.value = ConnectionState(ConnectionPhase.AUTHENTICATING)
            work = scope.launch(start = CoroutineStart.LAZY) { login(owner) }
            work!!.start()
        }
    }

    private suspend fun login(owner: Long) {
        deletions.await()
        val context = kotlinx.coroutines.currentCoroutineContext()
        synchronized(this) {
            if (closed || revision != owner) return
            authenticator.start(CoroutineScope(context))
        }
        val terminal = authenticator.state.first { auth ->
            publish(owner, ConnectionState(ConnectionPhase.AUTHENTICATING, auth))
            auth is AuthState.Connected || auth is AuthState.Failed || auth == AuthState.Cancelled
        }
        if (terminal !is AuthState.Connected) {
            publish(owner, ConnectionState(ConnectionPhase.FAILED, terminal, problem = ConnectionProblem.AUTH))
            return
        }
        publish(owner, ConnectionState(ConnectionPhase.READING, terminal))
        when (val credentials = store.read(terminal.generation)) {
            is CredentialResult.Failure -> publish(owner, ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE))
            is CredentialResult.Success -> {
                val facts = reader.read(credentials.value.accessToken)
                publish(owner, ConnectionState(ConnectionPhase.OBSERVED, auth = terminal, observations = facts,
                    problem = if (facts.successful) null else ConnectionProblem.READ))
            }
        }
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
            mutableState.value = ConnectionState(ConnectionPhase.SIGNING_OUT)
            // Atomic admission ensures Activity finish cannot drop a requested local deletion
            // before its synchronous store operation starts. No network or suspension inside.
            // Capture the capability at admission: an old worker can never delete a newer login.
            val generation = store.openSession()
            val completion = deletions.admit()
            work = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    val result = store.delete(generation)
                    synchronized(this@ConnectionController) {
                        deleting = false
                        publish(owner, ConnectionState(if (result is CredentialResult.Success) ConnectionPhase.SIGNED_OUT
                            else ConnectionPhase.FAILED, problem = if (result is CredentialResult.Failure) ConnectionProblem.STORAGE else null))
                    }
                } finally { completion.complete(Unit) }
            }
        }
    }

    private fun retire(): Long {
        revision++
        work?.cancel()
        authenticator.cancel()
        return revision
    }

    private fun publish(owner: Long, state: ConnectionState) {
        synchronized(this) { if (!closed && revision == owner) mutableState.value = state }
    }

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
