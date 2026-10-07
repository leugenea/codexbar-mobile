package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.auth.await
import io.github.leugenea.codexbarmobile.credentials.SensitiveValue
import io.github.leugenea.codexbarmobile.transport.*
import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant

/** Full decoded observations are in memory only; diagnostics never expose provider text. */
internal class EndpointObservation(
    val operation: ReadOperation,
    val status: Int? = null,
    val observedAt: Instant? = null,
    val error: ReadError? = null,
    val usage: UsageObservation? = null,
    val inventory: BankedResetObservation? = null,
) {
    // Keep the A8 screen's allowlisted facts without maintaining a second JSON parser.
    val availableCount: Field<Long>? get() = inventory?.reportedAvailableCount
    val expiries: List<Field<Instant>> get() = inventory?.items.orEmpty().map {
        it.value?.expiresAt ?: Field(it.knowledge, reason = it.reason)
    }
    override fun toString(): String = "EndpointObservation(operation=$operation, status=$status, observedAt=$observedAt, error=$error, payload=redacted)"
}
internal data class FeasibilityObservations(val usage: EndpointObservation, val inventory: EndpointObservation) {
    val successful: Boolean get() = usage.error == null && inventory.error == null
}

/** A2 owns read policy, A4 owns I/O/JSON syntax, A9 decodes through A1 validation. */
internal class NativeFeasibilityReader(
    private val transport: AuthTransport,
    private val clock: TransportClock = SystemTransportClock,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val policy = ReadPolicy(clock, ReadBackoff { retry -> retry * 1_000L })

    suspend fun read(bearer: SensitiveValue): FeasibilityObservations {
        val usage = endpoint(ReadOperation.USAGE, bearer)
        return FeasibilityObservations(usage, endpoint(ReadOperation.RESET_INVENTORY, bearer, usage.usage))
    }

    private suspend fun endpoint(
        operation: ReadOperation, bearer: SensitiveValue, usage: UsageObservation? = null,
    ): EndpointObservation {
        val request = ReadRequest(operation, ReadDeadline.after(clock.now()))
        var attempt = ReadAttempt()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val result = transport.await(ProviderHttpRequest.Get(url(operation), bearer), request.deadline)
                currentCoroutineContext().ensureActive()
                val receivedAt = clock.now().wall
                val decision = policy.evaluate(request, attempt, result)
                val status = (result as? TransportResult.Response)?.status
                when (decision.action) {
                    ReadAction.SUCCEED -> return project(operation, result as TransportResult.Response, receivedAt, usage)
                    ReadAction.RETRY_AT -> {
                        attempt = decision.next
                        pause((requireNotNull(decision.notBeforeMillis) - clock.now().monotonicMillis).coerceAtLeast(0))
                    }
                    ReadAction.SERIALIZED_REFRESH_AND_RETRY -> return EndpointObservation(operation, status, error = ReadError.REAUTHORIZE)
                    else -> return EndpointObservation(operation, status, error = decision.error)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return EndpointObservation(operation, error = ReadError.INVALID_RESPONSE)
        }
    }

    private fun project(
        operation: ReadOperation, response: TransportResult.Response, observed: Instant, usage: UsageObservation?,
    ): EndpointObservation = when (operation) {
        ReadOperation.USAGE -> decoded(operation, response.status,
            UsageResponseParser.parse(response.body, observed, observed)) { observation ->
            EndpointObservation(operation, response.status, observation.observedAt, usage = observation)
        }
        ReadOperation.RESET_INVENTORY -> decoded(operation, response.status,
            BankedResetResponseParser.parse(response.body, usage, observed, observed)) { observation ->
            EndpointObservation(operation, response.status, observation.observedAt, inventory = observation)
        }
    }

    private fun <T> decoded(
        operation: ReadOperation, status: Int, result: PayloadResult<T>, project: (T) -> EndpointObservation,
    ): EndpointObservation = when (result) {
        is PayloadResult.Decoded -> project(result.observation)
        is PayloadResult.Failure -> EndpointObservation(operation, status, error = ReadError.INVALID_RESPONSE)
    }

    internal companion object {
        fun url(operation: ReadOperation) = ("https://chatgpt.com" + operation.path).toHttpUrl()
    }
}
