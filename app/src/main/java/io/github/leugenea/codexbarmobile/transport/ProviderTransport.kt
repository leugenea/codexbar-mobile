package io.github.leugenea.codexbarmobile.transport

/** Exactly the two owner-selected read operations; no arbitrary URL or header input. */
enum class ReadOperation(val path: String) {
    USAGE("/backend-api/wham/usage"),
    RESET_INVENTORY("/backend-api/wham/rate-limit-reset-credits");

    val method: String get() = "GET"
}

data class ReadRequest(val operation: ReadOperation, val deadline: ReadDeadline)

/** No message, raw metadata or Throwable/cause fields are permitted here. */
enum class TransportFailure {
    NETWORK, INVALID_RESPONSE, BODY_TOO_LARGE, CANCELLED, DEADLINE_EXCEEDED
}

/** Bounded, immutable ownership; decoding is explicit and diagnostics are redacted. */
class ResponseBody private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size
    fun copyBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "ResponseBody(redacted)"

    companion object {
        // Local safety limit, not a claim about the provider's response schema/size.
        const val MAX_BYTES = 1_048_576

        internal fun boundedCopy(bytes: ByteArray): ResponseBody? =
            if (bytes.size <= MAX_BYTES) ResponseBody(bytes.copyOf()) else null
    }
}

sealed interface TransportResult {
    data class Failure(val category: TransportFailure) : TransportResult

    class Response private constructor(
        val status: Int,
        val body: ResponseBody,
        val retryAfter: RetryAfter,
    ) : TransportResult {
        override fun toString(): String = "Response(status=$status, body=redacted)"

        companion object {
            /** A4 must also cap streaming reads BEFORE allocating the input buffer. */
            fun bounded(status: Int, bytes: ByteArray, retryAfter: RetryAfter = RetryAfter.Missing): TransportResult {
                if (status !in 100..599) return Failure(TransportFailure.INVALID_RESPONSE)
                val body = ResponseBody.boundedCopy(bytes) ?: return Failure(TransportFailure.BODY_TOO_LARGE)
                return Response(status, body, retryAfter)
            }
        }
    }
}

fun interface CancellationHandle {
    fun cancel()
}

/**
 * Injected offline seam, not a network implementation. A4 owns authorization and sockets.
 * Implementations must deliver exactly one terminal callback (including cancellation),
 * close owned resources on cancellation/deadline, and schedule deadline checks even when
 * no response arrives. Callbacks may be synchronous. Never forward raw transport failures.
 */
fun interface ProviderTransport {
    fun read(request: ReadRequest, terminal: (TransportResult) -> Unit): CancellationHandle
}
