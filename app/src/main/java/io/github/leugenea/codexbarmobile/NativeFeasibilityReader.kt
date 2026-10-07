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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigDecimal
import java.time.Instant

/** Strictly allowlisted facts, never the body, provider identifiers or arbitrary strings. */
internal data class EndpointObservation(
    val operation: ReadOperation,
    val status: Int? = null,
    val observedAt: Instant? = null,
    val error: ReadError? = null,
    val usage: UsageObservation? = null,
    val availableCount: Field<Long>? = null,
    val expiries: List<Field<Instant>> = emptyList(),
)
internal data class FeasibilityObservations(val usage: EndpointObservation, val inventory: EndpointObservation) {
    val successful: Boolean get() = usage.error == null && inventory.error == null
}

/**
 * Narrow first-gate projection, not A9's complete provider DTO decoder. A1 owns numeric,
 * duration/reset and UTC validation. A2 owns status/deadline/backoff policy, A4 owns I/O.
 */
internal class NativeFeasibilityReader(
    private val transport: AuthTransport,
    private val clock: TransportClock = SystemTransportClock,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val policy = ReadPolicy(clock, ReadBackoff { retry -> retry * 1_000L })

    suspend fun read(bearer: SensitiveValue): FeasibilityObservations = FeasibilityObservations(
        endpoint(ReadOperation.USAGE, bearer), endpoint(ReadOperation.RESET_INVENTORY, bearer),
    )

    private suspend fun endpoint(operation: ReadOperation, bearer: SensitiveValue): EndpointObservation {
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
                    ReadAction.SUCCEED -> return project(operation, result as TransportResult.Response, receivedAt)
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

    private fun project(operation: ReadOperation, response: TransportResult.Response, observed: Instant): EndpointObservation {
        val root = (JsonBoundary.parse(response.body) as? JsonResult.Tree)?.value as? JsonObject
            ?: return EndpointObservation(operation, response.status, error = ReadError.INVALID_RESPONSE)
        return when (operation) {
            ReadOperation.USAGE -> EndpointObservation(operation, response.status, observed, usage = usage(root, observed))
            ReadOperation.RESET_INVENTORY -> EndpointObservation(operation, response.status, observed,
                availableCount = PrimitiveNormalizer.integer(primitive(root["available_count"])), expiries = expiries(root))
        }
    }

    private fun usage(root: JsonObject, observed: Instant): UsageObservation {
        val limits = root["rate_limit"]
        val summary = root["rate_limit_reset_credits"]
        return UsageNormalizer.normalize(UsageInput(
            primary = window(child(limits, "primary_window")),
            secondary = window(child(limits, "secondary_window")),
            allowed = primitive(child(limits, "allowed")),
            limitReached = primitive(child(limits, "limit_reached")),
            bankedAvailableCount = primitive(child(summary, "available_count")),
        ), observed, observed)
    }

    private fun window(value: JsonElement?): Input<WindowInput> = when (value) {
        null -> Input.Missing
        JsonNull -> Input.Null
        is JsonObject -> Input.Value(WindowInput(
            primitive(value["limit_window_seconds"]), primitive(value["used_percent"]),
            primitive(value["reset_at"]), primitive(value["reset_after_seconds"]),
        ))
        else -> Input.Invalid
    }

    private fun child(parent: JsonElement?, key: String): JsonElement? = when (parent) {
        null -> null
        JsonNull -> JsonNull
        is JsonObject -> parent[key]
        else -> JsonArray(emptyList()) // Invalid marker, never coerced to missing/null.
    }

    private fun primitive(value: JsonElement?): Input<Any> = when (value) {
        null -> Input.Missing
        JsonNull -> Input.Null
        is JsonPrimitive -> if (value.isString) Input.Value(value.content) else scalar(value.content)
        else -> Input.Invalid
    }

    private fun scalar(value: String): Input<Any> = when (value) {
        "true" -> Input.Value(true)
        "false" -> Input.Value(false)
        else -> try { Input.Value(BigDecimal(value)) } catch (_: NumberFormatException) { Input.Invalid }
    }

    private fun expiries(root: JsonObject): List<Field<Instant>> = when (val credits = root["credits"]) {
        is JsonArray -> credits.map { PrimitiveNormalizer.utc(primitive(child(it, "expires_at"))) }
        else -> emptyList()
    }

    internal companion object {
        fun url(operation: ReadOperation) = ("https://chatgpt.com" + operation.path).toHttpUrl()
    }
}
