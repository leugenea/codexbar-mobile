package io.github.leugenea.codexbarmobile.auth

import io.github.leugenea.codexbarmobile.transport.CancellationHandle
import io.github.leugenea.codexbarmobile.transport.ProviderHttpRequest
import io.github.leugenea.codexbarmobile.transport.ReadDeadline
import io.github.leugenea.codexbarmobile.transport.TransportResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Production wiring is AuthTransport(adapter::execute). */
fun interface AuthTransport {
    fun execute(request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit): CancellationHandle
}

/** Enqueue runs on the session owner's serial dispatcher; cancellation reaches even a late handle. */
internal suspend fun AuthTransport.await(request: ProviderHttpRequest, deadline: ReadDeadline): TransportResult =
    suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val delivered = AtomicBoolean()
        val handle = execute(request, deadline) { result ->
            if (delivered.compareAndSet(false, true)) continuation.resume(result)
        }
        continuation.invokeOnCancellation { handle.cancel() }
    }
