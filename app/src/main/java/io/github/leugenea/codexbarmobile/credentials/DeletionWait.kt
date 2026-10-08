package io.github.leugenea.codexbarmobile.credentials

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Waiter deadlines never cancel the durable runner or authorize reopening the slot. */
internal const val STORAGE_WAIT_MILLIS = 30_000L

internal suspend fun <T : Any> CompletableFuture<T>.awaitStorage(timeoutMillis: Long = STORAGE_WAIT_MILLIS): T? =
    withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val registration = whenComplete { value, failure ->
                if (failure == null) continuation.resume(value) else continuation.resumeWithException(failure)
            }
            continuation.invokeOnCancellation { registration.cancel(false) }
        }
    }

/** Legacy synchronous storage callers have a bounded, interruptible wait, never join(). */
internal fun <T : Any> CompletableFuture<T>.waitStorage(timeoutMillis: Long = STORAGE_WAIT_MILLIS): T? = try {
    get(timeoutMillis, TimeUnit.MILLISECONDS)
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    null
} catch (_: java.util.concurrent.TimeoutException) {
    null
}
