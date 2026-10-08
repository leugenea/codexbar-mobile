package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*

internal enum class SessionProblem { REAUTHORIZE, STORAGE, TRANSIENT, RATE_LIMITED, MALFORMED, STALE }
internal sealed interface SessionResult {
    class Ready(val envelope: CredentialEnvelope) : SessionResult {
        override fun toString() = "SessionResult.Ready(redacted)"
    }
    data class Failed(val problem: SessionProblem) : SessionResult
}

/** All mutations/request admission run on the process owner's serial coroutine dispatcher. */
internal class SessionCoordinator(
    private val store: CredentialStore,
    private val transport: AuthTransport,
    private val scope: CoroutineScope,
    private val clock: TransportClock = SystemTransportClock,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val invalidated: () -> Unit = {},
) {
    @Volatile private var current: CredentialEnvelope? = null
    @Volatile private var problem = SessionProblem.STALE
    private var flight: Flight? = null
    private var removal: Deferred<CredentialResult<Unit>>? = null

    fun adopt(envelope: CredentialEnvelope) {
        current = envelope.takeIf { store.isActive(it.generation) }
        problem = SessionProblem.STALE
        flight = null
    }

    /** Observation only: never mutates the owner or performs protected I/O. */
    fun snapshot(): SessionResult {
        val envelope = current
        return if (envelope != null && store.isActive(envelope.generation)) SessionResult.Ready(envelope)
        else SessionResult.Failed(problem)
    }

    fun accepts(envelope: CredentialEnvelope): Boolean = current?.generation === envelope.generation && store.isActive(envelope.generation)

    suspend fun request(envelope: CredentialEnvelope, request: ProviderHttpRequest, deadline: ReadDeadline): TransportResult {
        currentCoroutineContext().ensureActive()
        if (!accepts(envelope)) return TransportResult.Failure(TransportFailure.CANCELLED)
        val result = transport.await(request, deadline)
        currentCoroutineContext().ensureActive()
        return if (accepts(envelope)) result else TransportResult.Failure(TransportFailure.CANCELLED)
    }

    /** A sent refresh is uncertain. Its deletion lives in the owner scope, not in a read waiter. */
    fun retire() {
        val detached = flight
        flight = null
        current = null
        problem = SessionProblem.STALE
        if (detached != null && !detached.result.isCompleted) {
            detached.write.cancel()
            quarantine(detached.envelope.generation)
            detached.job.cancel()
            detached.result.complete(SessionResult.Failed(SessionProblem.STALE))
        }
    }

    suspend fun requireReauthorization(rejected: CredentialEnvelope) {
        if (current !== rejected) return
        current = null
        problem = SessionProblem.REAUTHORIZE
        quarantine(rejected.generation)
        invalidated()
        awaitRemoval()
    }

    private fun quarantine(generation: SessionGeneration) {
        when (val admitted = store.admitDeletion(generation)) {
            is CredentialResult.Failure -> Unit // A newer lifecycle command already revoked this capability.
            is CredentialResult.Success -> {
                val previous = removal
                removal = scope.async(storageDispatcher) {
                    previous?.await()
                    admitted.value.complete()
                }
            }
        }
    }

    /** Cancellable wait; a timeout never cancels removal or grants new request/write admission. */
    suspend fun awaitRemoval(): Boolean = withTimeoutOrNull(STORAGE_WAIT_MILLIS) {
        removal?.await() !is CredentialResult.Failure
    } ?: false

    suspend fun awaitShutdown() { removal?.join() }

    fun removalPending(): Boolean = removal?.isCompleted == false
    suspend fun removalFailed(): Boolean = removal?.await() is CredentialResult.Failure
    fun retryRemoval() { check(!removalPending()); removal = null }
    fun deadline(): ReadDeadline = ReadDeadline.after(clock.now())

    suspend fun refresh(rejected: CredentialEnvelope, deadline: ReadDeadline): SessionResult {
        if (deadline.isExpired(clock.now())) return SessionResult.Failed(SessionProblem.TRANSIENT)
        val active = (snapshot() as? SessionResult.Ready)?.envelope ?: return SessionResult.Failed(problem)
        if (active.generation !== rejected.generation) return SessionResult.Failed(SessionProblem.STALE)
        if (active !== rejected) return SessionResult.Ready(active)
        val pending = flight?.takeIf { it.envelope === rejected } ?: launchRefresh(rejected, deadline)
        val remaining = deadline.expiresAtMillis - clock.now().monotonicMillis
        if (remaining <= 0) return SessionResult.Failed(SessionProblem.TRANSIENT)
        val settled = withTimeoutOrNull(remaining) { pending.result.await() } ?: SessionResult.Failed(SessionProblem.TRANSIENT)
        return settleWaiter(settled, deadline)
    }

    private fun settleWaiter(result: SessionResult, deadline: ReadDeadline): SessionResult = when {
        result is SessionResult.Ready && !accepts(result.envelope) -> SessionResult.Failed(SessionProblem.STALE)
        deadline.isExpired(clock.now()) -> SessionResult.Failed(SessionProblem.TRANSIENT)
        else -> result
    }

    private fun launchRefresh(envelope: CredentialEnvelope, deadline: ReadDeadline): Flight {
        val owner = Flight(envelope)
        owner.job = scope.launch(start = CoroutineStart.LAZY) { runRefresh(owner, deadline) }
        flight = owner
        owner.job.invokeOnCompletion { owner.result.complete(SessionResult.Failed(SessionProblem.STALE)) }
        owner.job.start()
        return owner
    }

    private suspend fun runRefresh(owner: Flight, deadline: ReadDeadline) {
        val result = try {
            requestRefresh(owner, deadline)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: AuthAbort) { SessionResult.Failed(SessionProblem.MALFORMED)
        } catch (_: Exception) { SessionResult.Failed(SessionProblem.TRANSIENT) }
        if (flight !== owner || current !== owner.envelope || !store.isActive(owner.envelope.generation)) return
        val settled = settleRefresh(owner, result)
        if (flight === owner) owner.result.complete(settled)
    }

    private suspend fun settleRefresh(owner: Flight, result: SessionResult): SessionResult {
        when (result) {
            is SessionResult.Ready -> current = result.envelope
            is SessionResult.Failed -> if (result.problem in setOf(SessionProblem.REAUTHORIZE, SessionProblem.STORAGE)) {
                current = null
                problem = result.problem
                quarantine(owner.envelope.generation)
                invalidated()
                if (!awaitRemoval()) { problem = SessionProblem.STORAGE; return SessionResult.Failed(SessionProblem.STORAGE) }
            }
        }
        // Retain failures for this exact token until the next explicit read.
        return result
    }

    fun retryTransient() {
        if (flight?.result?.isCompleted == true && current === flight?.envelope) flight = null
    }

    private suspend fun requestRefresh(owner: Flight, deadline: ReadDeadline): SessionResult {
        val refresh = owner.envelope.refreshToken ?: return SessionResult.Failed(SessionProblem.REAUTHORIZE)
        currentCoroutineContext().ensureActive()
        if (!accepts(owner.envelope)) return SessionResult.Failed(SessionProblem.STALE)
        val prepared = storage { store.beginRotation(owner.envelope.generation) }
        if (prepared is CredentialResult.Failure) return SessionResult.Failed(prepared.sessionProblem())
        if (!accepts(owner.envelope)) return SessionResult.Failed(SessionProblem.STALE)
        val response = try {
            transport.await(AuthProtocol.refreshRequest(refresh), deadline)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { return transient(owner, SessionProblem.TRANSIENT) }
        currentCoroutineContext().ensureActive()
        if (!accepts(owner.envelope)) return SessionResult.Failed(SessionProblem.STALE)
        return refreshResponse(owner, response, refresh)
    }

    private suspend fun refreshResponse(owner: Flight, response: TransportResult, refresh: SensitiveValue): SessionResult {
        if (response !is TransportResult.Response) return transient(owner, SessionProblem.TRANSIENT)
        if (response.status == 429) return transient(owner, SessionProblem.RATE_LIMITED)
        if (response.status >= 500) return transient(owner, SessionProblem.TRANSIENT)
        if (AuthProtocol.terminalRefresh(response)) return SessionResult.Failed(SessionProblem.REAUTHORIZE)
        if (response.status != 200) return transient(owner, SessionProblem.TRANSIENT)
        val tokens = try { AuthProtocol.refreshedTokens(response.body, refresh)
        } catch (_: AuthAbort) { return transient(owner, SessionProblem.MALFORMED) }
        return persist(owner, tokens)
    }

    private suspend fun transient(owner: Flight, category: SessionProblem): SessionResult = storage {
        if (store.finishRotation(owner.envelope.generation) is CredentialResult.Success) SessionResult.Failed(category)
        else SessionResult.Failed(SessionProblem.STORAGE)
    }

    private suspend fun persist(owner: Flight, tokens: AuthTokens): SessionResult {
        val updated = CredentialEnvelope(owner.envelope.generation, tokens.access, tokens.refresh)
        val saved = store.replaceAsync(scope, storageDispatcher, updated, owner.write)
        return when {
            saved is CredentialResult.Failure -> SessionResult.Failed(saved.sessionProblem())
            storage { store.finishRotation(updated.generation) } is CredentialResult.Failure -> SessionResult.Failed(SessionProblem.STORAGE)
            else -> SessionResult.Ready(updated)
        }
    }

    private suspend fun <T> storage(action: () -> T): T = scope.async(storageDispatcher) { action() }.await()

    private fun CredentialResult.Failure.sessionProblem() =
        if (category == CredentialFailure.STALE_GENERATION) SessionProblem.STALE else SessionProblem.STORAGE

    private class Flight(val envelope: CredentialEnvelope) {
        val result = CompletableDeferred<SessionResult>()
        val write = CredentialCancellation()
        lateinit var job: Job
    }

    private companion object {
        const val STORAGE_WAIT_MILLIS = 5_000L
    }
}
