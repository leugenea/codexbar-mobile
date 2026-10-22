package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.serialization.json.*

/** Explicit allowlist: no reflection, credentials, account metadata or partition UUID. */
internal class HistoryJsonWriter {
    private fun text(value: Any?): JsonElement = value?.let { JsonPrimitive(it.toString()) } ?: JsonNull
    private fun number(value: Long?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun boolean(value: Boolean?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun <T> optional(value: T?, encode: (T) -> JsonElement): JsonElement = value?.let(encode) ?: JsonNull
    private fun <T> field(value: Field<T>, encode: (T) -> JsonElement): JsonObject = buildJsonObject {
        put("knowledge", text(value.knowledge)); put("reason", text(value.reason)); put("value", optional(value.value, encode))
    }

    fun clock(value: HistoryClock): JsonObject = buildJsonObject {
        put("epoch", text(value.epoch.value)); put("monotonicMillis", number(value.monotonicMillis))
    }

    fun entry(value: HistoryEntry): JsonObject = buildJsonObject {
        put("ordinal", value.id.ordinal); put("clock", clock(value.clock)); put("observedAt", text(value.observedAt))
        put("gap", text(value.gap)); put("windows", JsonArray(value.windows.map(::window)))
        put("slots", JsonArray(value.slots.map(::slot)))
        put("allowed", optional(value.allowed) { field(it, ::boolean) })
        put("limitReached", optional(value.limitReached) { field(it, ::boolean) })
    }

    private fun identity(value: WindowIdentity): JsonObject = buildJsonObject {
        put("kind", text(value.kind)); put("durationSeconds", value.durationSeconds); put("resetAt", text(value.resetAt))
    }

    fun segment(value: HistorySegment): JsonObject = buildJsonObject {
        put("kind", text(value.id.kind)); put("firstOrdinal", value.id.first.ordinal)
        put("window", optional(value.window, ::identity)); put("breaks", JsonArray(value.breaks.map(::text)))
        put("resetCause", text(value.resetCause))
    }

    fun point(value: HistoryPoint): JsonObject = buildJsonObject {
        put("ordinal", value.observation.ordinal); put("observedAt", text(value.observedAt)); put("clock", clock(value.clock))
        put("usedPercent", text(value.usedPercent)); put("segment", segment(value.segment))
        put("nominalStart", text(value.nominalStart)); put("baseline", text(value.baseline))
    }

    private fun tail(value: WindowCursor): JsonObject = buildJsonObject {
        put("lastPoint", optional(value.lastPoint, ::point)); put("lastCandidate", optional(value.lastCandidate, ::identity))
        put("nominalStart", text(value.nominalStart)); put("pendingBreak", text(value.pendingBreak))
    }

    private fun reset(value: HistoryReset): JsonObject = buildJsonObject {
        put("provenance", text(value.provenance))
        put("facts", optional(value.facts) { facts -> buildJsonObject {
            put("absolute", field(facts.absolute, ::text)); put("relativeSeconds", field(facts.relativeSeconds, ::number))
            put("relativeDerived", field(facts.relativeDerived, ::text)); put("discrepant", facts.discrepant)
            put("due", boolean(facts.due))
        } })
    }

    private fun window(value: HistoryWindow): JsonObject = buildJsonObject {
        put("kind", text(value.kind)); put("selection", text(value.selection))
        put("duration", optional(value.duration) { field(it, ::number) })
        put("percent", optional(value.percent) { field(it, ::text) }); put("reset", reset(value.reset))
        put("point", optional(value.point, ::point)); put("baseline", text(value.baseline))
    }

    private fun slot(value: HistorySlot): JsonObject = buildJsonObject {
        put("slot", text(value.slot)); put("knowledge", text(value.knowledge)); put("reason", text(value.reason))
        put("kind", text(value.kind)); put("duration", optional(value.duration) { field(it, ::number) })
    }

    fun state(value: HistoryDiskState): JsonObject = buildJsonObject {
        put("highWaterOrdinal", value.cursor.lastOrdinal)
        put("cursor", buildJsonObject {
            put("fiveHour", tail(value.cursor.fiveHour)); put("weekly", tail(value.cursor.weekly))
        })
        put("ageAnchor", optional(value.anchor) { buildJsonObject {
            put("at", text(it.at)); put("clock", clock(it.clock))
        } })
        put("ageSuspended", value.ageSuspended)
        put("cutoffs", buildJsonObject { value.cutoffs.forEach { (reason, cutoff) -> put(reason.name, cutoff.ordinal) } })
        put("lastClock", optional(value.lastClock, ::clock))
    }
}
