package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.Input
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class HistoryExportTest {
    private val partition = SyntheticHistory.partition
    private val at = SyntheticHistory.at

    @Test fun emptyHistoryRoundTripsWithZeroHighWaterAndNoInventedEntries() {
        roundTrip(document(emptyList(), HistoryDiskState(HistoryCursor(partition))))
    }

    @Test fun statusOnlyRoundTripPreservesFieldKnowledgeSlotsAndIndependentFlags() {
        val reduced = SyntheticHistory.append(usage = SyntheticHistory.usage(
            primary = SyntheticHistory.window(percent = Input.Missing), observedAt = null))
        assertTrue(reduced.entry.windows.all { it.point == null })
        roundTrip(document(listOf(reduced.entry), HistoryDiskState(reduced.cursor)))
    }

    @Test fun measuredRoundTripKeepsExactDecimalScaleFractionalResetAndClock() {
        val usage = SyntheticHistory.usage(primary = SyntheticHistory.window(percent = Input.Value(BigDecimal("12.37500")),
            relative = Input.Value(3600L)), secondary = SyntheticHistory.window(duration = Input.Value(604800L)),
            observedAt = at.plusNanos(123_456_789))
        val first = SyntheticHistory.append(usage = usage)
        roundTrip(document(listOf(first.entry), HistoryDiskState(first.cursor,
            HistoryAgeAnchor(at.plusNanos(123), first.entry.clock), lastClock = first.entry.clock)))
    }

    @Test fun gapsAndMultipleSegmentsRoundTripWithEvictedOriginsAndContinuationTail() {
        val first = SyntheticHistory.append()
        val gap = WindowHistory.append(first.cursor, HistoryAdmission(partition, ObservationId(2),
            HistoryClock(SyntheticHistory.epoch, 2000), HistoryEvent.Gap(HistoryGap.READ_ERROR))) as HistoryReduction.Applied
        val next = SyntheticHistory.append(gap.cursor, SyntheticHistory.usage(observedAt = at.plusSeconds(2)), 3000)
        val correction = SyntheticHistory.append(next.cursor, SyntheticHistory.usage(
            primary = SyntheticHistory.window(percent = Input.Value(BigDecimal("1.00"))), observedAt = at.plusSeconds(3)), 4000)
        assertNotEquals(SyntheticHistory.five(next).point!!.segment.id, SyntheticHistory.five(correction).point!!.segment.id)
        val state = HistoryDiskState(correction.cursor, HistoryAgeAnchor(at, first.entry.clock), true,
            mapOf(HistoryTruncation.OBSERVATION_CAP to HistoryEvictionCutoff(1)), correction.entry.clock)
        roundTrip(document(listOf(gap.entry, next.entry, correction.entry), state))
    }

    @Test fun checkedInV1FixtureDecodesToExpectedEntriesAndContinuation() {
        val text = javaClass.getResource("/history-export-v1.json")!!.readText()
        val decoded = HistoryExport.decode(text, partition)
        val clock = HistoryClock(SyntheticHistory.epoch, 7000)
        val tail = WindowCursor(pendingBreak = BreakReason.READ_ERROR)
        val expected = document(listOf(HistoryEntry(partition, ObservationId(7), clock, null, HistoryGap.READ_ERROR,
            emptyList(), emptyList(), null, null)), HistoryDiskState(HistoryCursor(partition, 7, tail, tail),
            HistoryAgeAnchor(at, clock), true, mapOf(HistoryTruncation.BYTE_CAP to HistoryEvictionCutoff(6)), clock))
        assertDocument(expected, decoded)
    }

    @Test fun unknownFieldsAtEveryObjectLevelAreIgnoredAndDestinationPartitionIsExplicit() {
        val first = SyntheticHistory.append()
        val original = document(listOf(first.entry), HistoryDiskState(first.cursor))
        val withUnknown = unknown(Json.parseToJsonElement(HistoryExport.encode(original)))
        val target = HistoryPartition(UUID(99, 101))
        val decoded = HistoryExport.decode(withUnknown.toString(), target)
        assertEquals(target, decoded.state.cursor.partition)
        assertEquals(target, decoded.entries.single().partition)
        assertEquals(target, decoded.entries.single().windows.first().point!!.segment.id.partition)
        assertDocument(original, HistoryExport.decode(HistoryExport.encode(decoded), partition))
    }

    @Test fun newerVersionAndMalformedKnownFieldsAreRejectedWithoutStorageWrites() {
        val source = HistoryExport.encode(document(emptyList(), HistoryDiskState(HistoryCursor(partition))))
        assertThrows(IllegalArgumentException::class.java) { HistoryExport.decode(source.replace("\"formatVersion\":1", "\"formatVersion\":2"), partition) }
        assertThrows(Exception::class.java) { HistoryExport.decode("{", partition) }
        assertThrows(Exception::class.java) { HistoryExport.decode(source.replace("\"highWaterOrdinal\":0", "\"highWaterOrdinal\":-1"), partition) }
        for ((field, value) in listOf("formatVersion" to JsonPrimitive("1"), "appVersion" to JsonPrimitive(1),
            "appVersion" to JsonNull, "exportedAt" to JsonNull)) {
            val malformed = JsonObject(Json.parseToJsonElement(source).jsonObject + (field to value))
            assertThrows(Exception::class.java) { HistoryExport.decode(malformed.toString(), partition) }
        }
        val measured = SyntheticHistory.append()
        val root = Json.parseToJsonElement(HistoryExport.encode(document(listOf(measured.entry), HistoryDiskState(measured.cursor)))).jsonObject
        val impossibleTail = changed(root, listOf("continuation", "cursor", "fiveHour", "lastPoint", "segment", "firstOrdinal"), JsonPrimitive(1000))
        assertThrows(IllegalArgumentException::class.java) { HistoryExport.decode(impossibleTail.toString(), partition) }
    }

    @Test fun integerMembersRejectMalformedExponentAndNonIntegerTokens() {
        for (token in listOf("7e", "0.0", "01", "+1", "-01")) {
            assertIntegerTokensRejected { token }
        }
    }

    @Test fun integerMembersRejectExponentNotationIncludingPrecisionLoss() {
        assertIntegerTokensRejected { "${it}e0" }
        val source = HistoryExport.encode(document(emptyList(), HistoryDiskState(HistoryCursor(partition))))
        val rounded = source.replace("\"highWaterOrdinal\":0", "\"highWaterOrdinal\":9007199254740993e0")
        assertThrows(Exception::class.java) { HistoryExport.decode(rounded, partition) }
    }

    @Test fun integerMembersRejectQuotedIntegers() {
        assertIntegerTokensRejected { "\"$it\"" }
    }

    @Test fun integerMembersPreserveExactLongsAndRejectOverflow() {
        for (ordinal in listOf(9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val original = document(emptyList(), HistoryDiskState(HistoryCursor(partition, ordinal)))
            roundTrip(original)
            assertEquals(ordinal, HistoryExport.decode(HistoryExport.encode(original), partition).state.cursor.lastOrdinal)
        }
        assertIntegerTokensRejected { "9223372036854775808" }
        assertIntegerTokensRejected { "-9223372036854775809" }
    }

    @Test fun enumMembersRequireQuotedStringsIncludingNullableFieldsAndBreakArrays() {
        val token = Regex("\"[A-Z][A-Z_]*\"")
        for (source in typedSources()) {
            val matches = token.findAll(source).toList()
            assertTrue(matches.isNotEmpty())
            for (match in matches) {
                val malformed = source.replaceRange(match.range, match.value.removeSurrounding("\""))
                assertThrows("Unquoted enum ${match.value} at ${match.range}", Exception::class.java) {
                    HistoryExport.decode(malformed, partition)
                }
            }
        }
    }

    @Test fun explicitOutputAllowlistNeverIncludesSecretsAccountPlanOrLocalPartition() {
        val input = SyntheticHistory.usage().copy(planType = io.github.leugenea.codexbarmobile.usage.Field(
            io.github.leugenea.codexbarmobile.usage.Knowledge.KNOWN, "synthetic-plan-must-not-leak"))
        val reduced = SyntheticHistory.append(usage = input)
        val text = HistoryExport.encode(document(listOf(reduced.entry), HistoryDiskState(reduced.cursor)))
        assertFalse(text.contains("synthetic-plan-must-not-leak"))
        val keys = keys(Json.parseToJsonElement(text)).map { it.lowercase() }
        val forbidden = listOf("credential", "token", "account", "plan", "partition", "password", "secret", "authorization", "email")
        assertTrue(keys.none { key -> forbidden.any { it in key } })
        assertFalse(text.contains(partition.value.toString()))
        assertEquals(setOf("formatVersion", "appVersion", "exportedAt", "entries", "continuation"), Json.parseToJsonElement(text).jsonObject.keys)
    }

    private fun typedSources(): List<String> {
        val first = SyntheticHistory.append(usage = SyntheticHistory.usage(
            primary = SyntheticHistory.window(relative = Input.Value(3600L))))
        val gap = WindowHistory.append(first.cursor, HistoryAdmission(partition, ObservationId(2),
            HistoryClock(SyntheticHistory.epoch, 2000), HistoryEvent.Gap(HistoryGap.READ_ERROR))) as HistoryReduction.Applied
        val next = SyntheticHistory.append(gap.cursor, SyntheticHistory.usage(observedAt = at.plusSeconds(2)), 3000)
        val status = SyntheticHistory.append(next.cursor, SyntheticHistory.usage(
            primary = SyntheticHistory.window(percent = Input.Missing, relative = Input.Value(3600L)),
            observedAt = at.plusSeconds(3)), 4000)
        val state = HistoryDiskState(status.cursor, HistoryAgeAnchor(at, first.entry.clock), true,
            mapOf(HistoryTruncation.OBSERVATION_CAP to HistoryEvictionCutoff(1)), status.entry.clock)
        val rich = HistoryExport.encode(document(listOf(gap.entry, next.entry, status.entry), state))
        val fixture = Json.parseToJsonElement(javaClass.getResource("/history-export-v1.json")!!.readText()).toString()
        return listOf(rich, fixture).onEach { HistoryExport.decode(it, partition) }
    }

    private fun assertIntegerTokensRejected(replacement: (String) -> String) {
        val token = Regex("""(?<=:)-?(0|[1-9][0-9]*)(?=[,}])""")
        for (source in typedSources()) {
            val matches = token.findAll(source).toList()
            assertTrue(matches.isNotEmpty())
            for (match in matches) {
                val malformed = source.replaceRange(match.range, replacement(match.value))
                assertThrows("Invalid integer ${match.value} at ${match.range}", Exception::class.java) {
                    HistoryExport.decode(malformed, partition)
                }
            }
        }
    }

    private fun document(entries: List<HistoryEntry>, state: HistoryDiskState) = HistoryExportDocument("test-v1", at, entries, state)
    private fun roundTrip(document: HistoryExportDocument) {
        val encoded = HistoryExport.encode(document)
        val decoded = HistoryExport.decode(encoded, partition)
        assertDocument(document, decoded)
        assertEquals(encoded, HistoryExport.encode(decoded))
    }
    private fun assertDocument(expected: HistoryExportDocument, actual: HistoryExportDocument) {
        assertEquals(expected.appVersion, actual.appVersion)
        assertEquals(expected.exportedAt, actual.exportedAt)
        assertArrayEquals(HistoryEncoding.state(expected.state), HistoryEncoding.state(actual.state))
        assertEquals(expected.entries.size, actual.entries.size)
        expected.entries.zip(actual.entries).forEach { (a, b) -> assertArrayEquals(HistoryEncoding.entry(a), HistoryEncoding.entry(b)) }
    }
    private fun changed(value: JsonObject, path: List<String>, replacement: JsonElement): JsonObject {
        val key = path.first()
        val next = if (path.size == 1) replacement else changed(value.getValue(key).jsonObject, path.drop(1), replacement)
        return JsonObject(value + (key to next))
    }
    private fun unknown(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { unknown(it.value) } + ("futureField" to JsonPrimitive("ignored")))
        is JsonArray -> JsonArray(value.map(::unknown))
        else -> value
    }
    private fun keys(value: JsonElement): Set<String> = when (value) {
        is JsonObject -> value.keys + value.values.flatMap { keys(it) }
        is JsonArray -> value.flatMap { keys(it) }.toSet()
        else -> emptySet()
    }
}
