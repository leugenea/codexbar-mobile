package io.github.leugenea.codexbarmobile.usage

import io.github.leugenea.codexbarmobile.transport.ResponseBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Direct HTTP inventory, not the transformed app-server or purchased-credit protocol. */
object BankedResetResponseParser {
    fun parse(
        body: ResponseBody, usage: UsageObservation? = null,
        observedAt: Instant? = null, evaluatedAt: Instant? = null,
    ): PayloadResult<BankedResetObservation> = WhamJson.decode(body) { root ->
        BankedResetNormalizer.normalize(Input.Value(InventoryInput(
            availableCount = WhamJson.primitive(root["available_count"]),
            items = WhamJson.read(root["credits"]) { rows -> (rows as? JsonArray)?.map(::item) },
        )), usage, observedAt, evaluatedAt)
    }

    private fun item(value: JsonElement): Input<ResetItemInput> = WhamJson.read(value) {
        val root = it as? JsonObject ?: return@read null
        ResetItemInput(
            id = WhamJson.primitive(root["id"]),
            resetType = WhamJson.primitive(root["reset_type"]),
            status = WhamJson.primitive(root["status"]),
            grantedAt = WhamJson.primitive(root["granted_at"]),
            expiresAt = WhamJson.primitive(root["expires_at"]),
        )
    }
}
