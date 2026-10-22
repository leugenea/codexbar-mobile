package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.Input
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.UUID

class HistoryImportTest {
    private val at = SyntheticHistory.at

    @Test fun exportImportRestoresExactRowsContinuationAndNextSegmentWithoutNewBreak() {
        val source = ImportMemoryStorage().seed(3)
        val target = ImportMemoryStorage().seed(1)
        assertEquals(HistoryImportOutcome.Imported, target.importHistory(source.text(), at.plusSeconds(3)))
        assertEquals(source.text(), target.text())
        val prior = target.state.cursor.fiveHour.lastPoint!!
        val appended = target.append(SyntheticHistory.admission(target.state.cursor,
            SyntheticHistory.usage(observedAt = at.plusSeconds(3)), 4000)) as HistoryAppendOutcome.Stored
        assertEquals(4L, appended.entry.id.ordinal)
        assertEquals(prior.segment.id, appended.entry.windows.first().point!!.segment.id)
        assertEquals(source.state.anchor, HistoryImport.decode(source.text(), target.partition).state.anchor)
        val rebound = HistoryImport.decode(source.text(), HistoryPartition(UUID(11, 22)))
        assertTrue(rebound.entries.all { it.partition == rebound.state.cursor.partition })
        assertEquals(rebound.state.cursor.partition, rebound.state.cursor.fiveHour.lastPoint!!.segment.id.partition)
    }

    @Test fun checkedInV1FixtureAndUnknownFieldsImportWithRetainedOrigins() {
        val fixture = javaClass.getResource("/history-export-v1.json")!!.readText()
        val root = Json.parseToJsonElement(fixture).jsonObject
        val additive = JsonObject(root + ("future" to JsonPrimitive("ignored"))).toString()
        val target = ImportMemoryStorage().seed(2)
        target.importHistory(additive, at)
        assertEquals(listOf(7L), target.entries.map { it.id.ordinal })
        assertEquals(7L, target.state.cursor.lastOrdinal)
        assertEquals(BreakReason.READ_ERROR, target.state.cursor.fiveHour.pendingBreak)
        assertEquals(HistoryEvictionCutoff(6), target.state.cutoffs[HistoryTruncation.BYTE_CAP])
    }

    @Test fun importAppliesAgeCountAndByteRetentionWithoutChangingHighWaterOrCursor() {
        val source = ImportMemoryStorage().seed(5).document()
        val ancient = source.entries.first()
        val old = HistoryEntry(ancient.partition, ancient.id, ancient.clock, at.minus(Duration.ofDays(31)), null,
            ancient.windows.map { window -> window.copy(point = window.point?.copy(observedAt = at.minus(Duration.ofDays(31)))) },
            ancient.slots, ancient.allowed, ancient.limitReached)
        val document = source.copy(entries = listOf(old) + source.entries.drop(1))
        val retained = ImportMemoryStorage()
        HistoryImport.replace(document, retained, HistoryStorageLimits(observations = 2), at.plusSeconds(5))
        assertEquals(listOf(4L, 5L), retained.entries.map { it.id.ordinal })
        assertEquals(HistoryEvictionCutoff(1), retained.state.cutoffs[HistoryTruncation.AGE_RETENTION])
        assertEquals(HistoryEvictionCutoff(3), retained.state.cutoffs[HistoryTruncation.OBSERVATION_CAP])
        assertEquals(source.state.cursor, retained.state.cursor)
        val bounded = ImportMemoryStorage().apply { pages = 10 }
        HistoryImport.replace(source, bounded, HistoryStorageLimits(), at.plusSeconds(5))
        assertTrue(bounded.entries.size < source.entries.size)
        assertTrue(HistoryTruncation.BYTE_CAP in bounded.state.cutoffs)
        assertEquals(5L, bounded.state.cursor.lastOrdinal)
        assertArrayEquals(HistoryEncoding.state(source.state.copy(cutoffs = bounded.state.cutoffs)), HistoryEncoding.state(bounded.state))
    }

    @Test fun malformedNewerAndOrdinalInvariantFilesLeaveExistingHistoryUntouched() {
        val target = ImportMemoryStorage().seed(2)
        val original = target.text()
        val source = ImportMemoryStorage().seed(3)
        val root = Json.parseToJsonElement(source.text()).jsonObject
        val entries = root.getValue("entries").jsonArray
        val mutations = listOf("{", source.text().replace("\"formatVersion\":1", "\"formatVersion\":2"),
            JsonObject(root + ("entries" to JsonArray(listOf(entries[0], entries[2])))).toString(),
            JsonObject(root + ("entries" to JsonArray(entries.reversed()))).toString(),
            source.text().replace("\"highWaterOrdinal\":3", "\"highWaterOrdinal\":2"))
        mutations.forEach { text ->
            assertThrows(Exception::class.java) { target.importHistory(text, at) }
            assertEquals(original, target.text())
        }
        assertThrows(UnsupportedHistoryVersion::class.java) { HistoryImport.decode(mutations[1], target.partition) }
    }

    @Test fun inconsistentCursorSegmentAndPendingBreakAreRejectedWithoutReplacement() {
        val source = ImportMemoryStorage().seed(2)
        val target = ImportMemoryStorage().seed(1)
        val original = target.text()
        val root = Json.parseToJsonElement(source.text()).jsonObject
        val wrongPoint = changed(root, listOf("continuation", "cursor", "fiveHour", "lastPoint", "usedPercent"), JsonPrimitive("99"))
        val current = source.entries.last()
        val lowerWindow = current.windows.first().copy(percent = current.windows.first().percent!!.copy(value = java.math.BigDecimal("1")),
            point = current.windows.first().point!!.copy(usedPercent = java.math.BigDecimal("1")))
        val lowered = HistoryEntry(source.partition, current.id, current.clock, current.observedAt, null,
            listOf(lowerWindow) + current.windows.drop(1), current.slots, current.allowed, current.limitReached)
        val impossibleCorrection = HistoryExport.encode(source.document().copy(entries = listOf(source.entries.first(), lowered),
            state = source.state.copy(cursor = source.state.cursor.copy(fiveHour = source.state.cursor.fiveHour.copy(lastPoint = lowerWindow.point)))))
        assertThrows(IllegalArgumentException::class.java) { HistoryImport.decode(impossibleCorrection, source.partition) }
        val wrongOrigin = changed(root, listOf("continuation", "cursor", "fiveHour", "lastPoint", "segment", "firstOrdinal"), JsonPrimitive(3))
        val gap = WindowHistory.append(source.state.cursor, HistoryAdmission(source.partition, ObservationId(3),
            HistoryClock(SyntheticHistory.epoch, 3000), HistoryEvent.Gap(HistoryGap.READ_ERROR))) as HistoryReduction.Applied
        val gapped = HistoryExport.encode(source.document().copy(entries = source.entries + gap.entry,
            state = HistoryRetention.advance(source.state, gap)))
        val missingBreak = changed(Json.parseToJsonElement(gapped).jsonObject,
            listOf("continuation", "cursor", "fiveHour", "pendingBreak"), JsonNull)
        listOf(wrongPoint, wrongOrigin, missingBreak).forEach { text ->
            assertThrows(Exception::class.java) { target.importHistory(text.toString(), at) }
            assertEquals(original, target.text())
        }
        assertEquals(HistoryImportOutcome.Imported, target.importHistory(gapped, at))
        val next = SyntheticHistory.append(gap.cursor, SyntheticHistory.usage(observedAt = at.plusSeconds(3)), 4000)
        val oldSegment = source.entries.first().windows.first().point!!.segment
        val joinedPoint = next.entry.windows.first().point!!.copy(segment = oldSegment)
        val joinedEntry = HistoryEntry(source.partition, next.entry.id, next.entry.clock, next.entry.observedAt, null,
            next.entry.windows.map { if (it.kind == oldSegment.id.kind) it.copy(point = joinedPoint) else it },
            next.entry.slots, next.entry.allowed, next.entry.limitReached)
        val joinedState = HistoryRetention.advance(HistoryRetention.advance(source.state, gap), next)
        val impossible = HistoryExport.encode(source.document().copy(entries = source.entries + gap.entry + joinedEntry,
            state = joinedState.copy(cursor = joinedState.cursor.copy(fiveHour = joinedState.cursor.fiveHour.copy(lastPoint = joinedPoint)))))
        assertThrows(IllegalArgumentException::class.java) { HistoryImport.decode(impossible, source.partition) }
    }

    @Test fun retainedUnknownTimeHolesAndUnkeyedPointsDoNotInvalidateRealExports() {
        val source = ImportMemoryStorage().seed(1)
        val unkeyed = SyntheticHistory.admission(source.state.cursor, SyntheticHistory.usage(
            primary = SyntheticHistory.window(absolute = Input.Missing), observedAt = at.plusSeconds(1)), 2000)
        source.append(unkeyed)
        assertEquals(HistoryImportOutcome.Imported, ImportMemoryStorage().importHistory(source.text(), at))
        val sparse = source.document().copy(entries = source.entries.takeLast(1), state = source.state.copy(
            cutoffs = mapOf(HistoryTruncation.AGE_RETENTION to HistoryEvictionCutoff(1))))
        HistoryImport.decode(HistoryExport.encode(sparse), source.partition)
        val holes = ImportMemoryStorage()
        holes.append(HistoryAdmission(holes.partition, ObservationId(1), HistoryClock(SyntheticHistory.epoch, 1000), HistoryEvent.Gap(HistoryGap.READ_ERROR)))
        holes.append(SyntheticHistory.admission(holes.state.cursor, SyntheticHistory.usage(observedAt = at.plusSeconds(1)), 2000))
        holes.append(SyntheticHistory.admission(holes.state.cursor, SyntheticHistory.usage(observedAt = at.plusSeconds(2)), 3000))
        holes.entries.removeAt(1)
        holes.state = holes.state.copy(cutoffs = mapOf(HistoryTruncation.AGE_RETENTION to HistoryEvictionCutoff(2)))
        assertEquals(HistoryImportOutcome.Imported, ImportMemoryStorage().importHistory(holes.text(), at))
    }

    @Test fun interruptedReplacementRollsBackRowsHighWaterCursorAndRetentionTogether() {
        val source = ImportMemoryStorage().seed(5)
        val target = ImportMemoryStorage().seed(2)
        val original = target.text()
        var inserted = 0
        assertThrows(IOException::class.java) {
            HistoryImport.replace(source.document(), target, HistoryStorageLimits(observations = 3), at,
                afterInsert = { if (++inserted == 2) throw IOException("Synthetic import cut") })
        }
        assertEquals(2, inserted)
        assertEquals(original, target.text())
        assertThrows(IOException::class.java) {
            HistoryImport.replace(source.document(), target, HistoryStorageLimits(), at,
                beforeCommit = { throw IOException("Synthetic continuation cut") })
        }
        assertEquals(original, target.text())
    }

    @Test fun oversizedAndMalformedUtf8StreamsAreRejectedBeforeDecodeOrMutation() {
        val target = ImportMemoryStorage().seed(1)
        val original = target.text()
        val stream = object : InputStream() {
            var supplied = 0L
            override fun read(): Int = error("Use bounded chunks")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (supplied > HistoryImport.MAX_INPUT_BYTES) return -1
                buffer.fill(32, offset, offset + length)
                supplied += length
                return length
            }
        }
        assertThrows(HistoryImportTooLarge::class.java) { HistoryImport.read(stream) }
        assertTrue(stream.supplied <= HistoryImport.MAX_INPUT_BYTES + 8192)
        assertThrows(Exception::class.java) { HistoryImport.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }
        assertEquals(original, target.text())
    }

    private fun changed(value: JsonObject, path: List<String>, replacement: JsonElement): JsonObject {
        val key = path.first()
        val next = if (path.size == 1) replacement else changed(value.getValue(key).jsonObject, path.drop(1), replacement)
        return JsonObject(value + (key to next))
    }
}
