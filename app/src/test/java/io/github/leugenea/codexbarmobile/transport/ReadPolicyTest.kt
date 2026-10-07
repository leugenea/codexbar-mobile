package io.github.leugenea.codexbarmobile.transport

import org.junit.Assert.*
import org.junit.Test

class ReadPolicyTest {
    private val clock = VirtualTransportClock()
    private val policy = ReadPolicy(clock, ReadBackoff { it * 1000L })
    private val request = ReadRequest(ReadOperation.USAGE, ReadDeadline.after(clock.now()))

    @Test fun allSevenM0ReadPolicyVectorsRetainTheirMeanings() {
        // Semantic adaptation, not captured wire data: docs/research/m0/fixtures/policy-vectors.json.
        // The independent original M0 oracle is tools/research/contract.py read_outcome.
        val cases = listOf(
            Vector("read-401", 401, false, ReadAction.SERIALIZED_REFRESH_AND_RETRY, null),
            Vector("terminal-401", 401, true, ReadAction.FINISH, ReadError.REAUTHORIZE),
            Vector("read-403", 403, false, ReadAction.FINISH, ReadError.FORBIDDEN),
            Vector("read-429", 429, false, ReadAction.RETRY_AT, ReadError.RATE_LIMITED),
            Vector("network-failure", null, false, ReadAction.RETRY_AT, ReadError.TRANSIENT),
            Vector("read-503", 503, false, ReadAction.RETRY_AT, ReadError.TRANSIENT),
            Vector("read-ok", 200, false, ReadAction.SUCCEED, null),
        )
        assertEquals(7, cases.map { it.id }.distinct().size)
        cases.forEach { vector ->
            val input = vector.status?.let { response(it) } ?: TransportResult.Failure(TransportFailure.NETWORK)
            val decision = policy.evaluate(request, ReadAttempt(vector.refreshed), input)
            assertEquals(vector.id, vector.action, decision.action)
            assertEquals(vector.id, vector.error, decision.error)
        }
    }

    @Test fun unauthorizedBudgetIsConsumedAcrossRefreshAndReadRetries() {
        val first = evaluate(response(401))
        assertTrue(first.next.refreshRequested)
        assertEquals(ReadAction.SERIALIZED_REFRESH_AND_RETRY, first.action)
        val transient = evaluate(response(503), first.next)
        assertTrue(transient.next.refreshRequested)
        val repeated = evaluate(response(401), transient.next)
        assertEquals(ReadError.REAUTHORIZE, repeated.error)
        assertEquals(ReadAction.FINISH, repeated.action)
    }

    @Test fun rateLimitKeepsCredentialStateAndRespectsHeaderAndBackoffFloor() {
        val decision = evaluate(response(429, RetryAfter.NotBefore(6000)), ReadAttempt(true))
        assertEquals(6000L, decision.notBeforeMillis)
        assertTrue(decision.next.refreshRequested)
        assertEquals(ReadError.RATE_LIMITED, decision.error)
        assertEquals(ReadAction.RETRY_AT, decision.action)
        assertEquals(2000L, evaluate(response(429, RetryAfter.NotBefore(1000))).notBeforeMillis)
        assertEquals(2000L, evaluate(response(503)).notBeforeMillis)
    }

    @Test fun beyondOrExactlyAtDeadlineDefersWithoutEarlyRetry() {
        listOf(31_000L, 31_001L, Long.MAX_VALUE).forEach { at ->
            val decision = evaluate(response(429, RetryAfter.NotBefore(at)))
            assertEquals(ReadAction.DEFER, decision.action)
            assertEquals(at, decision.notBeforeMillis)
            assertEquals(ReadAttempt(), decision.next)
        }
        assertEquals(ReadAction.RETRY_AT, evaluate(response(429, RetryAfter.NotBefore(30_999))).action)
    }

    @Test fun malformedRetryAfterOrBackoffDefersSafely() {
        val malformed = evaluate(response(429, RetryAfterParser.parse("synthetic-sensitive-marker", clock.now())))
        assertEquals(ReadAction.DEFER, malformed.action)
        assertNull(malformed.notBeforeMillis)
        assertFalse(malformed.toString().contains("synthetic-sensitive-marker"))
        val badBackoff = ReadPolicy(clock, ReadBackoff { -1 })
        assertEquals(ReadAction.DEFER, badBackoff.evaluate(request, ReadAttempt(), response(503)).action)
    }

    @Test fun retriesAreBoundedAndDoNotResetRefreshBudget() {
        var attempt = ReadAttempt(true)
        repeat(2) {
            val decision = evaluate(response(503), attempt)
            assertEquals(ReadAction.RETRY_AT, decision.action)
            assertTrue(decision.next.refreshRequested)
            attempt = decision.next
        }
        assertEquals(2, attempt.retries)
        assertEquals(ReadAction.DEFER, evaluate(response(503), attempt).action)
        assertEquals(ReadAction.DEFER, evaluate(response(429), ReadAttempt(retries = Int.MAX_VALUE)).action)
        val noRetries = ReadPolicy(clock, ReadBackoff { 1000 }, maxRetries = 0)
        assertEquals(ReadAction.DEFER, noRetries.evaluate(request, ReadAttempt(), response(503)).action)
    }

    @Test fun selectedOperationConfigurationIsAnImmutableWhitelist() {
        val allowed = mutableSetOf(ReadOperation.USAGE)
        val restricted = ReadPolicy(clock, ReadBackoff { 1000 }, allowed)
        allowed.add(ReadOperation.RESET_INVENTORY)
        val inventory = request.copy(operation = ReadOperation.RESET_INVENTORY)
        assertEquals(ReadError.OPERATION_NOT_ALLOWED, restricted.evaluate(inventory, ReadAttempt(), response(200)).error)
        assertEquals(ReadAction.SUCCEED, restricted.evaluate(request, ReadAttempt(), response(200)).action)
        assertEquals(ReadAction.SUCCEED, policy.evaluate(inventory, ReadAttempt(), response(200)).action)
        assertEquals(listOf("/backend-api/wham/usage", "/backend-api/wham/rate-limit-reset-credits"), ReadOperation.entries.map { it.path })
        assertEquals(setOf("GET"), ReadOperation.entries.map { it.method }.toSet())
    }

    @Test fun deadlinesAndStructuredFailuresStayCategorical() {
        val mapping = mapOf(
            TransportFailure.INVALID_RESPONSE to ReadError.INVALID_RESPONSE,
            TransportFailure.BODY_TOO_LARGE to ReadError.BODY_TOO_LARGE,
            TransportFailure.CANCELLED to ReadError.CANCELLED,
            TransportFailure.DEADLINE_EXCEEDED to ReadError.DEADLINE_EXCEEDED,
        )
        mapping.forEach { (failure, expected) ->
            val decision = evaluate(TransportResult.Failure(failure))
            assertEquals(ReadAction.FINISH, decision.action)
            assertEquals(expected, decision.error)
            assertNull(decision.notBeforeMillis)
        }
        clock.advance(30_000)
        assertEquals(ReadError.DEADLINE_EXCEEDED, evaluate(response(200)).error)
        assertEquals(ReadError.DEADLINE_EXCEEDED, evaluate(TransportResult.Failure(TransportFailure.NETWORK)).error)
        assertEquals(ReadError.CANCELLED, evaluate(TransportResult.Failure(TransportFailure.CANCELLED)).error)
    }

    @Test fun backoffOverflowAndConfigurationErrorsFailClosed() {
        val overflowing = ReadPolicy(clock, ReadBackoff { Long.MAX_VALUE })
        val decision = overflowing.evaluate(request, ReadAttempt(), response(503))
        assertEquals(ReadAction.DEFER, decision.action)
        assertEquals(Long.MAX_VALUE, decision.notBeforeMillis)
        listOf(-1, 3).forEach { budget ->
            assertThrows(IllegalArgumentException::class.java) { ReadPolicy(clock, ReadBackoff { 0 }, maxRetries = budget) }
        }
        assertThrows(IllegalArgumentException::class.java) { ReadAttempt(retries = -1) }
    }

    @Test fun otherReadStatusesRemainTransientAndNeverBecomePlanOrQuotaValues() {
        listOf(201, 400, 404, 500, 599).forEach { status ->
            assertEquals(ReadError.TRANSIENT, evaluate(response(status)).error)
        }
        assertEquals(ReadError.FORBIDDEN, evaluate(response(403)).error)
    }

    private fun evaluate(result: TransportResult, attempt: ReadAttempt = ReadAttempt()): ReadDecision =
        policy.evaluate(request, attempt, result)

    private fun response(status: Int, retryAfter: RetryAfter = RetryAfter.Missing): TransportResult =
        TransportResult.Response.bounded(status, byteArrayOf(), retryAfter)

    private data class Vector(
        val id: String, val status: Int?, val refreshed: Boolean, val action: ReadAction, val error: ReadError?,
    )
}
