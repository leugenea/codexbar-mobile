package io.github.leugenea.codexbarmobile.auth

import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.credentials.SessionOwnership
import io.github.leugenea.codexbarmobile.transport.CancellationHandle
import io.github.leugenea.codexbarmobile.transport.ProviderHttpRequest
import io.github.leugenea.codexbarmobile.transport.ReadDeadline
import io.github.leugenea.codexbarmobile.transport.TransportFailure
import io.github.leugenea.codexbarmobile.transport.TransportResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Production wiring is AuthTransport(adapter::execute); A4 owns honest User-Agent/TLS/deadlines. */
fun interface AuthTransport {
    fun execute(request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit): CancellationHandle
}

/** Register cancellation even when a synchronous callback cancels before handle return. */
internal suspend fun AuthTransport.await(
    request: ProviderHttpRequest, deadline: ReadDeadline,
    ownership: SessionOwnership? = null, generation: SessionGeneration? = null,
): TransportResult = suspendCancellableCoroutine { continuation ->
    if (ownership != null && generation != null) {
        val call = OwnedAwait(ownership, generation, continuation)
        continuation.invokeOnCancellation { call.cancel() }
        call.start(this, request, deadline)
    } else {
        val delivered = AtomicBoolean()
        val handle = execute(request, deadline) { result ->
            if (delivered.compareAndSet(false, true)) continuation.resume(result)
        }
        continuation.invokeOnCancellation { handle.cancel() }
    }
}

/** Only execute's non-suspending enqueue is inside the lane, never callback/resumption. */
private class OwnedAwait(
    private val ownership: SessionOwnership,
    private val generation: SessionGeneration,
    private val continuation: CancellableContinuation<TransportResult>,
) {
    private var handle: CancellationHandle? = null
    private var binding: CancellationHandle? = null
    private var settled = false

    fun start(transport: AuthTransport, request: ProviderHttpRequest, deadline: ReadDeadline) {
        ownership.serialized {
            if (settled || !continuation.isActive) return
            if (!ownership.isActive(generation)) {
                complete(TransportResult.Failure(TransportFailure.CANCELLED))
                return
            }
            binding = ownership.onDisplaced(generation) { cancel() }
            handle = transport.execute(request, deadline) { result -> ownership.defer { complete(result) } }
        }
    }

    fun cancel() {
        ownership.serialized {
            if (settled) return
            settled = true
            binding?.cancel()
            ownership.defer {
                try { handle?.cancel() } finally {
                    if (continuation.isActive) continuation.resume(TransportResult.Failure(TransportFailure.CANCELLED))
                }
            }
        }
    }

    private fun complete(result: TransportResult) {
        ownership.serialized {
            if (settled) return
            settled = true
            binding?.cancel()
            val accepted = if (ownership.isActive(generation)) result
                else TransportResult.Failure(TransportFailure.CANCELLED)
            ownership.defer { continuation.resume(accepted) }
        }
    }
}
