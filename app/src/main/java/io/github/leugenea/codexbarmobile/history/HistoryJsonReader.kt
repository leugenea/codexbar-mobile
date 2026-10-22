package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Unknown object members are ignored. Known members must retain their v1 types. */
internal class HistoryJsonReader(private val partition: HistoryPartition) {
    private fun JsonObject.value(key: String): JsonElement = getValue(key)
    private fun JsonObject.obj(key: String): JsonObject = value(key).jsonObject
    private fun JsonObject.string(key: String): String = value(key).jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.long(key: String): Long = value(key).jsonPrimitive.let { require(!it.isString); it.long }
    private fun JsonObject.bool(key: String): Boolean = value(key).jsonPrimitive.let { require(!it.isString); it.boolean }
    private fun <T> JsonObject.optional(key: String, read: (JsonElement) -> T): T? = value(key).takeUnless { it == JsonNull }?.let(read)
    private inline fun <reified T : Enum<T>> JsonObject.enum(key: String): T = enumValueOf(string(key))
    private fun instant(value: JsonElement): Instant = Instant.parse(value.jsonPrimitive.let { require(it.isString); it.content })
    private fun decimal(value: JsonElement): BigDecimal = value.jsonPrimitive.let { require(it.isString); BigDecimal(it.content) }
    private fun readLong(value: JsonElement): Long = value.jsonPrimitive.let { require(!it.isString); it.long }
    private fun readBool(value: JsonElement): Boolean = value.jsonPrimitive.let { require(!it.isString); it.boolean }
    private fun <T> field(value: JsonElement, read: (JsonElement) -> T): Field<T> = value.jsonObject.let {
        Field(it.enum<Knowledge>("knowledge"), it.optional("value", read), it.optional("reason") { enumValueOf<Reason>(it.jsonPrimitive.content) })
    }

    private fun clock(value: JsonElement): HistoryClock = value.jsonObject.let {
        HistoryClock(ClockEpoch(UUID.fromString(it.string("epoch"))), it.optional("monotonicMillis", ::readLong))
    }

    fun entry(value: JsonObject): HistoryEntry = HistoryEntry(partition, ObservationId(value.long("ordinal")),
        clock(value.value("clock")), value.optional("observedAt", ::instant),
        value.optional("gap") { enumValueOf<HistoryGap>(it.jsonPrimitive.content) },
        value.value("windows").jsonArray.map { window(it.jsonObject) },
        value.value("slots").jsonArray.map { slot(it.jsonObject) },
        value.optional("allowed") { field(it, ::readBool) }, value.optional("limitReached") { field(it, ::readBool) })

    private fun identity(value: JsonElement): WindowIdentity = value.jsonObject.let {
        WindowIdentity(partition, it.enum("kind"), it.long("durationSeconds"), instant(it.value("resetAt")))
    }

    private fun segment(value: JsonObject): HistorySegment {
        require(value.string("resetCause") == ResetCause.UNKNOWN.name)
        val breaks = value.value("breaks").jsonArray.map { enumValueOf<BreakReason>(it.jsonPrimitive.content) }
        require(breaks.distinct().size == breaks.size)
        return HistorySegment(SegmentIdentity(partition, value.enum("kind"), ObservationId(value.long("firstOrdinal"))),
            value.optional("window", ::identity), breaks)
    }

    private fun point(value: JsonElement): HistoryPoint = value.jsonObject.let {
        HistoryPoint(ObservationId(it.long("ordinal")), instant(it.value("observedAt")), clock(it.value("clock")),
            decimal(it.value("usedPercent")), segment(it.obj("segment")), it.enum("nominalStart"), it.enum("baseline"))
    }

    private fun tail(value: JsonObject): WindowCursor = WindowCursor(value.optional("lastPoint", ::point),
        value.optional("lastCandidate", ::identity), value.enum("nominalStart"),
        value.optional("pendingBreak") { enumValueOf<BreakReason>(it.jsonPrimitive.content) })

    private fun reset(value: JsonObject): HistoryReset = HistoryReset(value.enum("provenance"), value.optional("facts") { element ->
        element.jsonObject.let { ResetTime(field(it.value("absolute"), ::instant), field(it.value("relativeSeconds"), ::readLong),
            field(it.value("relativeDerived"), ::instant), it.bool("discrepant"), it.optional("due", ::readBool)) }
    })

    private fun window(value: JsonObject): HistoryWindow = HistoryWindow(value.enum("kind"), value.enum("selection"),
        value.optional("duration") { field(it, ::readLong) }, value.optional("percent") { field(it, ::decimal) },
        reset(value.obj("reset")), value.optional("point", ::point), value.enum("baseline"))

    private fun slot(value: JsonObject): HistorySlot = HistorySlot(value.enum("slot"), value.enum("knowledge"),
        value.optional("reason") { enumValueOf<Reason>(it.jsonPrimitive.content) },
        value.optional("kind") { enumValueOf<WindowKind>(it.jsonPrimitive.content) }, value.optional("duration") { field(it, ::readLong) })

    fun state(value: JsonObject): HistoryDiskState {
        val cursor = value.obj("cursor")
        val cutoffs = value.obj("cutoffs")
        return HistoryDiskState(HistoryCursor(partition, value.long("highWaterOrdinal"), tail(cursor.obj("fiveHour")), tail(cursor.obj("weekly"))),
            value.optional("ageAnchor") { it.jsonObject.let { HistoryAgeAnchor(instant(it.value("at")), clock(it.value("clock"))) } },
            value.bool("ageSuspended"), HistoryTruncation.entries.mapNotNull { reason ->
                cutoffs[reason.name]?.let { reason to HistoryEvictionCutoff(readLong(it)) }
            }.toMap(), value.optional("lastClock", ::clock))
    }
}
