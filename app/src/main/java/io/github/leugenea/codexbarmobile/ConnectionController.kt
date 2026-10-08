package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.CredentialRemoval
import io.github.leugenea.codexbarmobile.credentials.awaitStorage
import io.github.leugenea.codexbarmobile.credentials.CredentialDeletion
import io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope
import io.github.leugenea.codexbarmobile.credentials.CredentialResult
import io.github.leugenea.codexbarmobile.credentials.CredentialFailure
import io.github.leugenea.codexbarmobile.credentials.CredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import io.github.leugenea.codexbarmobile.credentials.SequencedStateFlow
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.CancellationHandle
import kotlinx.coroutines.flow.StateFlow
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
    suspend fun await(): Boolean = kotlinx.coroutines.withTimeoutOrNull(
        io.github.leugenea.codexbarmobile.credentials.STORAGE_WAIT_MILLIS,
    ) { snapshot()?.await(); true } ?: false
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
    private val ownership = store.ownership
    private val mutableState = SequencedStateFlow(ownership, ConnectionState(ConnectionPhase.RESTORING))
    private var binding: CancellationHandle? = null
    val state: StateFlow<ConnectionState> = mutableState
    internal val session = reader.session(store, scope)
    private val authenticatedReader = AuthenticatedProviderReader(session, reader)
    private var work: Job? = null
    private var revision = 0L
    private var closed = false
    private var deleting = false
    private val storageReady = storageReady

    init {
        work = scope.launch {
            if (!deletions.await()) {
                publish(0, ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE))
                return@launch
            }
            val generation = ownership.serialized {
                if (closed || revision != 0L) return@launch
                if (storageReady) store.openSession().also(::bind) else null
            }
            val restored = if (generation != null) ownership.awaitSettled { store.read(generation) } else
                CredentialResult.Failure(CredentialFailure.CORRUPT)
            val quarantined = quarantineRestore(generation, restored)
            val phase = if (!quarantined) ConnectionPhase.FAILED else when (restored) {
                is CredentialResult.Success -> ConnectionPhase.RESTORED
                is CredentialResult.Failure -> when (restored.category) {
                    CredentialFailure.MISSING -> ConnectionPhase.IDLE
                    CredentialFailure.KEY_LOST, CredentialFailure.CORRUPT -> if (storageReady) ConnectionPhase.REAUTH_REQUIRED else ConnectionPhase.FAILED
                    else -> ConnectionPhase.FAILED
                }
            }
            ownership.serialized {
                if (!closed && revision == 0L && restored is CredentialResult.Success) session.adopt(restored.value)
                publish(0, ConnectionState(phase, problem = if (phase in setOf(ConnectionPhase.FAILED, ConnectionPhase.REAUTH_REQUIRED)) ConnectionProblem.STORAGE else null))
            }
        }
    }

    private suspend fun quarantineRestore(generation: SessionGeneration?, restored: CredentialResult<CredentialEnvelope>): Boolean {
        if (generation == null || restored !is CredentialResult.Failure ||
            restored.category !in setOf(CredentialFailure.KEY_LOST, CredentialFailure.CORRUPT)) return true
        val admitted = ownership.serialized {
            if (closed || revision != 0L) return false
            binding?.cancel()
            binding = null
            store.admitDeletion(generation)
        }
        // Restore remains busy until unsafe ciphertext/key removal (or the tombstone) settles.
        return admitted is CredentialResult.Success && admitted.value.await() is CredentialResult.Success
    }

    fun connect() {
        ownership.serialized {
            if (closed || deleting || !storageReady || mutableState.value.busy) return
            val owner = retire()
            val replacement = store.admitCommandRemoval(replacement = true)
            bind(requireNotNull(replacement.successor))
            // The slot already owns its runner; this Activity owns only the login waiter.
            val next = scope.launch(start = CoroutineStart.LAZY) { login(owner, replacement) }
            work = next
            mutableState.value = ConnectionState(ConnectionPhase.AUTHENTICATING)
            ownership.afterLane { if (!closed && revision == owner) next.start() }
        }
    }

    private suspend fun login(owner: Long, replacement: CredentialRemoval) {
        val deleted = if (deletions.await()) replacement.deletion.await() else
            CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
        if (deleted is CredentialResult.Failure) {
            publish(owner, ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE))
            return
        }
        val generation = requireNotNull(replacement.successor)
        if (!startAuthentication(owner, generation, kotlinx.coroutines.currentCoroutineContext())) return
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

    private fun startAuthentication(owner: Long, generation: SessionGeneration, context: kotlin.coroutines.CoroutineContext): Boolean =
        ownership.serialized {
            if (closed || revision != owner || !ownership.isActive(generation)) return false
            ownership.defer {
                ownership.serialized {
                    if (!closed && revision == owner && ownership.isActive(generation))
                        authenticator.start(CoroutineScope(context), generation)
                }
            }
            true
        }

    private suspend fun connected(owner: Long, terminal: AuthState.Connected) {
        when (val credentials = ownership.awaitSettled { store.read(terminal.generation) }) {
            is CredentialResult.Failure -> publish(owner, ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE))
            is CredentialResult.Success -> {
                ownership.serialized {
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
        ownership.serialized {
            if (closed || deleting || mutableState.value.busy) return
            val active = session.snapshot() as? SessionResult.Ready
            if (active == null) {
                if (mutableState.value.phase in setOf(ConnectionPhase.RESTORED, ConnectionPhase.OBSERVED))
                    publish(revision, displaced())
                return
            }
            revision++
            val owner = revision
            work?.let { job -> ownership.afterLane { job.cancel() } }
            session.retryTransient()
            val next = scope.launch(start = CoroutineStart.LAZY) {
                val refreshed = if (refreshSession) session.refresh(active.envelope, session.deadline()) else null
                observe(owner, AuthState.Connected(active.envelope.generation), refreshed as? SessionResult.Failed)
            }
            work = next
            publish(owner, ConnectionState(ConnectionPhase.READING, AuthState.Connected(active.envelope.generation)))
            ownership.defer { if (!closed && revision == owner) next.start() }
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
        ownership.serialized {
            if (closed || deleting || mutableState.value.phase == ConnectionPhase.RESTORING) return
            retire()
            mutableState.value = ConnectionState(ConnectionPhase.CANCELLED)
        }
    }

    fun browserFailed() {
        ownership.serialized {
            if (closed || deleting || mutableState.value.phase == ConnectionPhase.RESTORING) return
            retire()
            mutableState.value = ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.BROWSER)
        }
    }

    /** Local only. Opening a new capability invalidates any old/staging auth write. */
    fun signOut() {
        ownership.serialized {
            if (closed || deleting) return
            if (!storageReady) {
                commitState(ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE))
                return
            }
            val owner = retire()
            deleting = true
            // Reserve barrier/capability before displacement. An old worker can never
            // turn this captured command into deletion of a later login.
            val admitted = store.admitCommandRemoval(replacement = false).deletion
            val completion = deletions.admit()
            // The kernel queued durable work before callback-capable lifecycle effects.
            ownership.afterLane { delete(owner, admitted, completion) }
            mutableState.value = ConnectionState(ConnectionPhase.SIGNING_OUT)
            // Preserve reentrant busy-admission observers after the runner is enqueued;
            // the separate Activity barrier makes their restoration suspension-safe.
            mutableState.notifyWithoutDeletionWait()
        }
    }

    private fun delete(owner: Long, admitted: CredentialDeletion, completion: CompletableDeferred<Unit>) {
        // Starting durable work and ending the Activity waiter are separate ownership paths.
        admitted.start()
        val next = scope.launch {
            val result = admitted.await()
            ownership.serialized {
                deleting = false
                publish(owner, ConnectionState(if (result is CredentialResult.Success) ConnectionPhase.SIGNED_OUT
                    else ConnectionPhase.FAILED, problem = if (result is CredentialResult.Failure) ConnectionProblem.STORAGE else null))
            }
        }
        ownership.serialized { if (!closed && revision == owner) work = next }
        // Only the real slot outcome releases restoration, not cancellation of this waiter.
        ownership.defer { completion.complete(Unit) }
    }

    private fun retire(): Long {
        binding?.cancel()
        binding = null
        revision++
        work?.let { job -> ownership.afterLane { job.cancel() } }
        authenticator.cancel()
        session.retire()
        return revision
    }

    private fun publish(owner: Long, state: ConnectionState) {
        val generation = (state.auth as? AuthState.Connected)?.generation
        // A hint is never publication permission. The value is committed on the shared lane.
        val hint = generation?.let(store::isActive) ?: true
        ownership.serialized {
            if (closed || revision != owner) return
            val stale = !hint || (generation != null && !ownership.isActive(generation)) ||
                (state.phase == ConnectionPhase.RESTORED && session.snapshot() is SessionResult.Failed)
            commitState(if (stale) displaced() else state)
        }
    }

    private fun commitState(state: ConnectionState) {
        mutableState.value = state
        // A timeout is truthful failure, not permission to clear a still-running removal.
        if (state.phase == ConnectionPhase.FAILED && state.problem == ConnectionProblem.STORAGE)
            mutableState.notifyWithoutDeletionWait()
    }

    private fun bind(generation: SessionGeneration) {
        binding?.cancel()
        binding = ownership.onDisplaced(generation) {
            if (closed || deleting) return@onDisplaced
            revision++
            val detached = work
            val owner = revision
            val terminal = if (mutableState.value.phase == ConnectionPhase.AUTHENTICATING)
                ConnectionState(ConnectionPhase.FAILED, authenticator.displacedState(), problem = ConnectionProblem.AUTH)
                else displaced()
            // Revoke credentials/observations immediately, but durable quarantine authorizes
            // the terminal re-auth state. The barrier also defers reentrant observer wakeups.
            val barrier = ownership.deletionBarrier()
            mutableState.value = if (barrier != null) ConnectionState(ConnectionPhase.SIGNING_OUT) else terminal
            ownership.afterLane {
                detached?.cancel()
                if (barrier == null) return@afterLane
                scope.launch {
                    val succeeded = barrier.awaitStorage(ownership.storageWaitMillis)
                    val settled = if (succeeded == true) terminal else
                        ConnectionState(ConnectionPhase.FAILED, problem = ConnectionProblem.STORAGE)
                    ownership.serialized {
                        if (!closed && revision == owner) commitState(settled)
                    }
                }
            }
        }
    }

    private fun displaced() = ConnectionState(ConnectionPhase.REAUTH_REQUIRED, problem = ConnectionProblem.AUTH)

    fun close() {
        ownership.serialized {
            if (closed) return
            closed = true
            retire()
            mutableState.value = ConnectionState(ConnectionPhase.CANCELLED)
            ownership.afterLane { scope.cancel() }
        }
    }
}
