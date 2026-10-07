package io.github.leugenea.codexbarmobile.transport

enum class ReadError {
    REAUTHORIZE, FORBIDDEN, RATE_LIMITED, TRANSIENT, INVALID_RESPONSE,
    BODY_TOO_LARGE, CANCELLED, DEADLINE_EXCEEDED, OPERATION_NOT_ALLOWED
}

enum class ReadAction { SUCCEED, SERIALIZED_REFRESH_AND_RETRY, RETRY_AT, DEFER, FINISH }

/** Consume the returned state for the same logical operation, including after refresh. */
data class ReadAttempt(val refreshRequested: Boolean = false, val retries: Int = 0) {
    init {
        require(retries >= 0) { "Invalid retry count" }
    }
}

data class ReadDecision(
    val action: ReadAction,
    val next: ReadAttempt,
    val error: ReadError? = null,
    val notBeforeMillis: Long? = null,
)

fun interface ReadBackoff {
    /** Nonnegative delay for retry 1 or 2; invalid delays fail closed by deferring. */
    fun delayMillis(retryNumber: Int): Long
}

/**
 * Adapted semantics: docs/research/m0/fixtures/policy-vectors.json (seven synthetic
 * vectors) and tools/research/contract.py read_outcome. No refresh/retry execution here:
 * A10 must serialize refresh per session and persist rotation; B2 owns later cadence.
 * Errors preserve credentials/last success except explicit REAUTHORIZE. No quota inferred.
 */
class ReadPolicy(
    private val clock: TransportClock,
    private val backoff: ReadBackoff,
    operations: Set<ReadOperation> = ReadOperation.entries.toSet(),
    private val maxRetries: Int = 2,
) {
    private val operations = operations.toSet()

    init {
        require(maxRetries in 0..2) { "Invalid retry budget" }
    }

    fun evaluate(request: ReadRequest, attempt: ReadAttempt, result: TransportResult): ReadDecision {
        val now = clock.now()
        if (request.operation !in operations) return finish(attempt, ReadError.OPERATION_NOT_ALLOWED)
        if (result is TransportResult.Failure) return failure(request, attempt, result.category, now)
        if (request.deadline.isExpired(now)) return finish(attempt, ReadError.DEADLINE_EXCEEDED)
        return response(request, attempt, result as TransportResult.Response, now)
    }

    private fun response(
        request: ReadRequest, attempt: ReadAttempt, response: TransportResult.Response, now: TransportTime,
    ): ReadDecision = when (response.status) {
        200 -> ReadDecision(ReadAction.SUCCEED, attempt)
        401 -> unauthorized(attempt)
        403 -> finish(attempt, ReadError.FORBIDDEN)
        429 -> retry(request, attempt, ReadError.RATE_LIMITED, response.retryAfter, now)
        else -> retry(request, attempt, ReadError.TRANSIENT, response.retryAfter, now)
    }

    private fun unauthorized(attempt: ReadAttempt): ReadDecision = if (attempt.refreshRequested)
        finish(attempt, ReadError.REAUTHORIZE)
    else ReadDecision(ReadAction.SERIALIZED_REFRESH_AND_RETRY, attempt.copy(refreshRequested = true))

    private fun failure(
        request: ReadRequest, attempt: ReadAttempt, failure: TransportFailure, now: TransportTime,
    ): ReadDecision = when (failure) {
        TransportFailure.NETWORK -> {
            if (request.deadline.isExpired(now)) finish(attempt, ReadError.DEADLINE_EXCEEDED)
            else retry(request, attempt, ReadError.TRANSIENT, RetryAfter.Missing, now)
        }
        TransportFailure.INVALID_RESPONSE -> finish(attempt, ReadError.INVALID_RESPONSE)
        TransportFailure.BODY_TOO_LARGE -> finish(attempt, ReadError.BODY_TOO_LARGE)
        TransportFailure.CANCELLED -> finish(attempt, ReadError.CANCELLED)
        TransportFailure.DEADLINE_EXCEEDED -> finish(attempt, ReadError.DEADLINE_EXCEEDED)
    }

    private fun retry(
        request: ReadRequest, attempt: ReadAttempt, error: ReadError, header: RetryAfter, now: TransportTime,
    ): ReadDecision {
        val at = retryTime(header, attempt, now)
        if (attempt.retries >= maxRetries || at == null || at >= request.deadline.expiresAtMillis)
            return ReadDecision(ReadAction.DEFER, attempt, error, at)
        return ReadDecision(ReadAction.RETRY_AT, attempt.copy(retries = attempt.retries + 1), error, at)
    }

    private fun retryTime(header: RetryAfter, attempt: ReadAttempt, now: TransportTime): Long? {
        if (header == RetryAfter.Invalid) return null
        val delay = backoff.delayMillis(attempt.retries.coerceAtMost(1) + 1)
        if (delay < 0) return null
        val floor = saturatedAdd(now.monotonicMillis, delay)
        return if (header is RetryAfter.NotBefore) maxOf(floor, header.monotonicMillis) else floor
    }

    private fun finish(attempt: ReadAttempt, error: ReadError): ReadDecision =
        ReadDecision(ReadAction.FINISH, attempt, error)
}
