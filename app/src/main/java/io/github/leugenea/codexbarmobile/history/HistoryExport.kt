package io.github.leugenea.codexbarmobile.history

import kotlinx.serialization.json.*
import java.time.Instant

/** Detached v1 data. Decode requires the destination lifetime; a local partition is never serialized. */
internal data class HistoryExportDocument(
    val appVersion: String,
    val exportedAt: Instant,
    val entries: List<HistoryEntry>,
    val state: HistoryDiskState,
)

/** Public-file contract, independent of the private SQLite/binary schema. */
internal object HistoryExport {
    private val json = Json

    fun encode(document: HistoryExportDocument): String {
        validate(document)
        val writer = HistoryJsonWriter()
        return buildJsonObject {
            put("formatVersion", 1)
            put("appVersion", document.appVersion)
            put("exportedAt", document.exportedAt.toString())
            put("entries", JsonArray(document.entries.map(writer::entry)))
            put("continuation", writer.state(document.state))
        }.toString()
    }

    fun decode(text: String, partition: HistoryPartition): HistoryExportDocument {
        val root = json.parseToJsonElement(text).jsonObject
        require(root.getValue("formatVersion").strictLong() == 1L) { "Unsupported history format version" }
        require(root.getValue("appVersion").jsonPrimitive.isString)
        require(root.getValue("exportedAt").jsonPrimitive.isString)
        val reader = HistoryJsonReader(partition)
        return HistoryExportDocument(root.getValue("appVersion").jsonPrimitive.content,
            Instant.parse(root.getValue("exportedAt").jsonPrimitive.content),
            root.getValue("entries").jsonArray.map { reader.entry(it.jsonObject) },
            reader.state(root.getValue("continuation").jsonObject)).also(::validate)
    }

    private fun validate(document: HistoryExportDocument) {
        val cursor = document.state.cursor
        require(document.entries.size <= HistoryLimits.MAX_OBSERVATIONS)
        require(document.entries.all { it.partition == cursor.partition && it.id.ordinal <= cursor.lastOrdinal })
        require(document.entries.zipWithNext().all { (a, b) -> a.id.ordinal < b.id.ordinal })
        // Reuse storage invariants, not its bytes or enum ordinals in the public format.
        document.entries.forEach { HistoryEncoding.entry(HistoryEncoding.entry(it)) }
        HistoryEncoding.state(HistoryEncoding.state(document.state))
        listOf(cursor.fiveHour, cursor.weekly).forEach { tail ->
            tail.lastPoint?.let { require(it.segment.id.first.ordinal <= it.observation.ordinal) }
        }
    }
}
