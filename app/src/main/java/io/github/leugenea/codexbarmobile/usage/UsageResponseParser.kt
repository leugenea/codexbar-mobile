package io.github.leugenea.codexbarmobile.usage

import io.github.leugenea.codexbarmobile.transport.ResponseBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Selected WHAM usage fields only; M0 mocks are evidence, not provider guarantees. */
object UsageResponseParser {
    fun parse(
        body: ResponseBody, observedAt: Instant? = null, evaluatedAt: Instant? = null,
    ): PayloadResult<UsageObservation> = WhamJson.decode(body) { root ->
        val limits = root["rate_limit"]
        UsageNormalizer.normalize(UsageInput(
            primary = window(WhamJson.child(limits, "primary_window")),
            secondary = window(WhamJson.child(limits, "secondary_window")),
            allowed = WhamJson.primitive(WhamJson.child(limits, "allowed")),
            limitReached = WhamJson.primitive(WhamJson.child(limits, "limit_reached")),
            planType = WhamJson.primitive(root["plan_type"]),
            bankedAvailableCount = WhamJson.primitive(WhamJson.child(root["rate_limit_reset_credits"], "available_count")),
        ), observedAt, evaluatedAt)
    }

    private fun window(value: JsonElement?): Input<WindowInput> = WhamJson.read(value) {
        val root = it as? JsonObject ?: return@read null
        WindowInput(
            WhamJson.primitive(root["limit_window_seconds"]), WhamJson.primitive(root["used_percent"]),
            WhamJson.primitive(root["reset_at"]), WhamJson.primitive(root["reset_after_seconds"]),
        )
    }
}
