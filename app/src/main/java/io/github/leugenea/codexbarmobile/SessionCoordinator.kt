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

/** Single local session owner. No token clock or provider identity is inferred. */
internal class SessionCoordinator(
    private val store: CredentialStore,
    private val transport: AuthTransport,
    private val scope: CoroutineScope,
    private val clock: TransportClock = SystemTransportClock,
) {
    private var current: CredentialEnvelope? = null
    private var problem = SessionProblem.STALE
    private var flight: Flight? = null

    @Synchronized fun adopt(envelope: CredentialEnvelope) {
        retire()
        current = envelope
    }

    @Synchronized fun snapshot(): SessionResult {
        // Every live coordinator consults the shared slot, not its private cached envelope.
        // A pending rotation blocks protected restoration, but not this capability check.
        current?.let { if (!store.isActive(it.generation)) retire() }
        return current?.let(SessionResult::Ready) ?: SessionResult.Failed(problem)
    }

    fun accepts(envelope: CredentialEnvelope): Boolean =
        (snapshot() as? SessionResult.Ready)?.envelope?.generation === envelope.generation

    /** Retiring a sent refresh is uncertain: never restore a possibly consumed token. */
    @Synchronized fun retire() {
        val detached = flight
        flight = null
        current = null
        problem = SessionProblem.STALE
        detached?.takeUnless { it.result.isCompleted }?.let {
            it.write.cancel()
            it.job.cancel()
            store.delete(it.envelope.generation)
        }
    }

    @Synchronized fun requireReauthorization(rejected: CredentialEnvelope) {
        if (current !== rejected) return
        retire()
        problem = SessionProblem.REAUTHORIZE
        store.delete(rejected.generation)
    }

    fun deadline(): ReadDeadline = ReadDeadline.after(clock.now())

    suspend fun refresh(rejected: CredentialEnvelope, deadline: ReadDeadline): SessionResult {
        if (deadline.isExpired(clock.now())) return SessionResult.Failed(SessionProblem.TRANSIENT)
        val pending = synchronized(this) {
            val checked = snapshot()
            if (checked is SessionResult.Failed) return checked
            val active = (checked as SessionResult.Ready).envelope
            if (active.generation !== rejected.generation) return SessionResult.Failed(SessionProblem.STALE)
            if (active !== rejected) return SessionResult.Ready(active)
            flight?.takeIf { it.envelope === rejected } ?: launchRefresh(rejected, deadline)
        }
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
        // Install before start: synchronous transports/observers cannot create a second flight.
        owner.job = scope.launch(start = CoroutineStart.LAZY) { runRefresh(owner, deadline) }
        flight = owner
        owner.job.invokeOnCompletion { owner.result.complete(SessionResult.Failed(SessionProblem.STALE)) }
        owner.job.start()
        return owner
    }

    private suspend fun runRefresh(owner: Flight, deadline: ReadDeadline) {
        val result = try {
            requestRefresh(owner, deadline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AuthAbort) {
            SessionResult.Failed(SessionProblem.MALFORMED)
        } catch (_: Exception) {
            SessionResult.Failed(SessionProblem.TRANSIENT)
        }
        synchronized(this) {
            if (!accepts(owner.envelope) || flight !== owner || current !== owner.envelope) return
            when (result) {
                is SessionResult.Ready -> current = result.envelope
                is SessionResult.Failed -> if (result.problem in setOf(SessionProblem.REAUTHORIZE, SessionProblem.STORAGE)) {
                    current = null
                    problem = result.problem
                    store.delete(owner.envelope.generation)
                }
            }
            // Retain a settled failure for this exact token: concurrent readers share the
            // result rather than retrying an old refresh token. A new explicit read can retry.
            owner.result.complete(result)
        }
    }

    @Synchronized fun retryTransient() {
        if (flight?.result?.isCompleted == true && current === flight?.envelope) flight = null
    }

    private suspend fun requestRefresh(owner: Flight, deadline: ReadDeadline): SessionResult {
        val refresh = owner.envelope.refreshToken ?: return SessionResult.Failed(SessionProblem.REAUTHORIZE)
        currentCoroutineContext().ensureActive()
        if (!accepts(owner.envelope)) return SessionResult.Failed(SessionProblem.STALE)
        val prepared = store.beginRotation(owner.envelope.generation)
        if (prepared is CredentialResult.Failure) return SessionResult.Failed(prepared.sessionProblem())
        if (!accepts(owner.envelope)) return SessionResult.Failed(SessionProblem.STALE)
        val response = try {
            transport.await(AuthProtocol.refreshRequest(refresh), deadline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return transient(owner, SessionProblem.TRANSIENT)
        }
        currentCoroutineContext().ensureActive()
        if (!accepts(owner.envelope)) return SessionResult.Failed(SessionProblem.STALE)
        return refreshResponse(owner, response, refresh)
    }

    private suspend fun refreshResponse(owner: Flight, response: TransportResult, refresh: SensitiveValue): SessionResult {
        // Transport bounded response acceptance. Even if the waiter expires before resumption,
        // settle a received rotation durably: discarding it would make a consumed token reusable.
        if (response !is TransportResult.Response) return transient(owner, SessionProblem.TRANSIENT)
        if (response.status == 429) return transient(owner, SessionProblem.RATE_LIMITED)
        if (response.status >= 500) return transient(owner, SessionProblem.TRANSIENT)
        if (AuthProtocol.terminalRefresh(response)) return SessionResult.Failed(SessionProblem.REAUTHORIZE)
        if (response.status != 200) return transient(owner, SessionProblem.TRANSIENT)
        val tokens = try {
            AuthProtocol.refreshedTokens(response.body, refresh)
        } catch (_: AuthAbort) {
            return transient(owner, SessionProblem.MALFORMED)
        }
        return persist(owner, tokens)
    }

    private fun transient(owner: Flight, category: SessionProblem): SessionResult {
        // No usable rotation was received. These documented failures retain credentials.
        return if (store.finishRotation(owner.envelope.generation) is CredentialResult.Success)
            SessionResult.Failed(category) else SessionResult.Failed(SessionProblem.STORAGE)
    }

    private suspend fun persist(owner: Flight, tokens: AuthTokens): SessionResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { owner.write.cancel() }
        if (!accepts(owner.envelope)) {
            continuation.resumeWith(Result.success(SessionResult.Failed(SessionProblem.STALE)))
            return@suspendCancellableCoroutine
        }
        val updated = CredentialEnvelope(owner.envelope.generation, tokens.access, tokens.refresh)
        val saved = store.replace(updated, owner.write)
        val result = when {
            saved is CredentialResult.Failure -> SessionResult.Failed(saved.sessionProblem())
            store.finishRotation(updated.generation) is CredentialResult.Failure -> SessionResult.Failed(SessionProblem.STORAGE)
            else -> SessionResult.Ready(updated)
        }
        continuation.resumeWith(Result.success(result))
    }

    private fun CredentialResult.Failure.sessionProblem() =
        if (category == CredentialFailure.STALE_GENERATION) SessionProblem.STALE else SessionProblem.STORAGE

    private class Flight(val envelope: CredentialEnvelope) {
        val result = CompletableDeferred<SessionResult>()
        val write = CredentialCancellation()
        lateinit var job: Job
    }
}
