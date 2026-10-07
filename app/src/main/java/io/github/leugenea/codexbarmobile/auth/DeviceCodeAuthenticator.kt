package io.github.leugenea.codexbarmobile.auth

import io.github.leugenea.codexbarmobile.credentials.CredentialCancellation
import io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope
import io.github.leugenea.codexbarmobile.credentials.CredentialFailure
import io.github.leugenea.codexbarmobile.credentials.CredentialResult
import io.github.leugenea.codexbarmobile.credentials.CredentialStore
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ProviderHttpRequest
import io.github.leugenea.codexbarmobile.transport.ReadDeadline
import io.github.leugenea.codexbarmobile.transport.RetryAfter
import io.github.leugenea.codexbarmobile.transport.SystemTransportClock
import io.github.leugenea.codexbarmobile.transport.TransportClock
import io.github.leugenea.codexbarmobile.transport.TransportFailure
import io.github.leugenea.codexbarmobile.transport.TransportResult
import io.github.leugenea.codexbarmobile.transport.saturatedAdd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One connected local session. start replaces/cancels the preceding auth owner; cancel
 * retires only an in-flight attempt, not stored credentials (A10 owns local logout).
 * A8 supplies a lifecycle-owned worker scope and manual system-browser UI. No traffic
 * starts at construction. Scope cancellation also clears the in-memory display code.
 */
class DeviceCodeAuthenticator(
    private val transport: AuthTransport,
    private val store: CredentialStore,
    private val clock: TransportClock = SystemTransportClock,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private var active: Attempt? = null

    fun start(scope: CoroutineScope, generation: SessionGeneration? = null): Job {
        val (previous, attempt) = synchronized(this) {
            val previous = active
            val owner = Attempt(generation ?: store.openSession())
            owner.job = scope.launch(start = CoroutineStart.LAZY) { run(owner) }
            active = owner
            mutableState.value = AuthState.RequestingCode
            previous to owner
        }
        // Job completion handlers can synchronously start/cancel another owner. No
        // ownership or state writes follow cancellation of the detached predecessor.
        stop(previous)
        attempt.job.invokeOnCompletion { finish(attempt, AuthState.Cancelled) }
        attempt.job.start()
        return attempt.job
    }

    fun cancel() {
        val previous = synchronized(this) {
            val owner = active ?: return
            active = null
            mutableState.value = AuthState.Cancelled
            owner
        }
        stop(previous)
    }

    private fun stop(owner: Attempt?) {
        if (owner == null) return
        owner.cancellation.cancel()
        owner.job.cancel()
    }

    private suspend fun run(owner: Attempt) {
        try {
            val device = usercode(owner)
            publish(owner, AuthState.AwaitingUser(device.code))
            owner.stage = AuthStage.POLL
            val authorization = poll(owner, device)
            owner.stage = AuthStage.EXCHANGE
            publish(owner, AuthState.Exchanging)
            val tokens = AuthProtocol.tokens(success(request(owner, AuthProtocol.exchangeRequest(authorization))).body)
            owner.stage = AuthStage.STORE
            persist(owner, tokens)
            finish(owner, AuthState.Connected(owner.generation))
        } catch (_: CancellationException) {
            finish(owner, AuthState.Cancelled)
        } catch (error: AuthAbort) {
            finish(owner, AuthState.Failed(owner.stage, error.category))
        } catch (_: Exception) {
            finish(owner, AuthState.Failed(owner.stage, AuthFailure.TRANSPORT))
        } finally {
            owner.cancellation.cancel()
        }
    }

    private suspend fun usercode(owner: Attempt): DeviceCode {
        var retries = 0
        while (true) {
            val result = response(request(owner, AuthProtocol.usercodeRequest()))
            if (result.status != 429) return AuthProtocol.device(success(result).body)
            if (retries == 3) throw AuthAbort(AuthFailure.RATE_LIMITED)
            retries++
            pause(retryDelay(result.retryAfter, retries))
        }
    }

    private fun retryDelay(retryAfter: RetryAfter, retry: Int): Long {
        val delay = when (retryAfter) {
            is RetryAfter.NotBefore -> retryAfter.monotonicMillis - clock.now().monotonicMillis
            else -> (1L shl retry) * 1_000L
        }
        return delay.coerceIn(1_000L, 60_000L)
    }

    private suspend fun poll(owner: Attempt, device: DeviceCode): AuthorizationCode {
        val end = saturatedAdd(clock.now().monotonicMillis, AuthProtocol.POLL_BUDGET_MILLIS)
        var consecutiveErrors = 0
        while (true) {
            pause(minOf(device.intervalMillis, remaining(end)))
            remaining(end)
            val result = request(owner, AuthProtocol.pollRequest(device), end)
            if (transient(result)) {
                consecutiveErrors++
                if (consecutiveErrors >= 6) throw AuthAbort(AuthFailure.NETWORK)
            } else {
                consecutiveErrors = 0
                val response = response(result)
                when (response.status) {
                    200 -> return AuthProtocol.authorization(response.body)
                    403, 404 -> Unit
                    else -> success(response)
                }
            }
        }
    }

    private fun transient(result: TransportResult): Boolean = result is TransportResult.Failure &&
        result.category in setOf(TransportFailure.NETWORK, TransportFailure.DEADLINE_EXCEEDED)

    private fun remaining(end: Long): Long {
        val remaining = end - clock.now().monotonicMillis
        if (remaining <= 0) throw AuthAbort(AuthFailure.DEADLINE)
        return remaining
    }

    private suspend fun request(owner: Attempt, request: ProviderHttpRequest, end: Long? = null): TransportResult {
        currentCoroutineContext().ensureActive()
        ensureOwner(owner)
        val duration = end?.let { minOf(remaining(it), ReadDeadline.MAX_DURATION_MILLIS) }
            ?: ReadDeadline.MAX_DURATION_MILLIS
        val deadline = ReadDeadline.after(clock.now(), duration)
        val result = transport.await(request, deadline)
        currentCoroutineContext().ensureActive()
        ensureOwner(owner)
        if (end != null) remaining(end)
        return if (deadline.isExpired(clock.now())) TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED) else result
    }

    private fun ensureOwner(owner: Attempt) {
        if (!store.isActive(owner.generation)) throw AuthAbort(AuthFailure.STALE_OWNER)
    }

    private fun response(result: TransportResult): TransportResult.Response = when (result) {
        is TransportResult.Response -> result
        is TransportResult.Failure -> throw when (result.category) {
            TransportFailure.NETWORK -> AuthAbort(AuthFailure.NETWORK)
            TransportFailure.DEADLINE_EXCEEDED -> AuthAbort(AuthFailure.DEADLINE)
            TransportFailure.CANCELLED -> CancellationException("Auth cancelled")
            else -> AuthAbort(AuthFailure.TRANSPORT)
        }
    }

    private fun success(result: TransportResult): TransportResult.Response {
        val response = response(result)
        when (response.status) {
            200 -> return response
            429 -> throw AuthAbort(AuthFailure.RATE_LIMITED)
            else -> throw AuthAbort(AuthFailure.HTTP_STATUS)
        }
    }

    private suspend fun persist(owner: Attempt, tokens: AuthTokens): Unit = suspendCancellableCoroutine { continuation ->
        // Cancellation registration precedes potentially blocking staging, not just its completion.
        continuation.invokeOnCancellation { owner.cancellation.cancel() }
        val envelope = CredentialEnvelope(owner.generation, tokens.access, tokens.refresh)
        when (val result = store.replace(envelope, owner.cancellation)) {
            is CredentialResult.Success -> continuation.resume(Unit)
            is CredentialResult.Failure -> continuation.resumeWith(Result.failure(storageFailure(result.category)))
        }
    }

    private fun storageFailure(category: CredentialFailure): Exception = when (category) {
        CredentialFailure.CANCELLED -> CancellationException("Auth cancelled")
        CredentialFailure.STALE_GENERATION -> AuthAbort(AuthFailure.STALE_OWNER)
        else -> AuthAbort(AuthFailure.STORAGE)
    }

    private suspend fun publish(owner: Attempt, state: AuthState) {
        currentCoroutineContext().ensureActive()
        ensureOwner(owner)
        synchronized(this) { if (active === owner) mutableState.value = state }
        // An undispatched observer may synchronously cancel or replace this attempt.
        currentCoroutineContext().ensureActive()
    }

    private fun finish(owner: Attempt, state: AuthState) {
        synchronized(this) {
            if (active !== owner) return
            active = null
            mutableState.value = if (state is AuthState.Connected && !store.isActive(owner.generation))
                AuthState.Failed(AuthStage.STORE, AuthFailure.STALE_OWNER) else state
        }
    }

    private class Attempt(val generation: SessionGeneration) {
        val cancellation = CredentialCancellation()
        var stage = AuthStage.USERCODE
        lateinit var job: Job
    }
}
