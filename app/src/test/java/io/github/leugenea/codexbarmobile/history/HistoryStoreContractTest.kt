package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.history.SyntheticHistory.admission
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.append
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.at
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.epoch
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.partition
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.usage
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.window
import io.github.leugenea.codexbarmobile.usage.*
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Original synthetic values; codec/policy/failure contracts, NOT a native SQLite substitute. */
class HistoryStoreContractTest {
    @Test fun binaryRoundTripPreservesAllFieldKnowledgeProvenancePointsAndConstantCursor() {
        val sources = listOf(Input.Missing, Input.Null, Input.Invalid, window(),
            window(Input.Value(BigDecimal("12.37500000000000000001")), relative = Input.Invalid),
            window(absolute = Input.Missing, relative = Input.Value(60L)), window(absolute = Input.Invalid),
            window(relative = Input.Value(2L)), window(duration = Input.Value(604800L)),
            window(duration = Input.Value(3600L)), window(Input.Null))
        for (source in sources) {
            val applied = append(usage = usage(primary = source, secondary = window(duration = Input.Value(604800L))))
            val encoded = HistoryEncoding.entry(applied.entry)
            val decoded = HistoryEncoding.entry(encoded)
            assertArrayEquals(encoded, HistoryEncoding.entry(decoded))
            assertEquals(applied.entry.id, decoded.id)
            assertEquals(applied.entry.observedAt, decoded.observedAt)
            val state = HistoryDiskState(applied.cursor, HistoryAgeAnchor(at, HistoryClock(epoch, 1000)), true,
                HistoryTruncation.entries.associateWith { HistoryEvictionCutoff(1) })
            assertArrayEquals(HistoryEncoding.state(state), HistoryEncoding.state(HistoryEncoding.state(HistoryEncoding.state(state))))
        }
        val gap = WindowHistory.append(HistoryCursor(partition), HistoryAdmission(partition, ObservationId(1),
            HistoryClock(epoch, null), HistoryEvent.Gap(HistoryGap.READ_ERROR))) as HistoryReduction.Applied
        assertEquals(HistoryGap.READ_ERROR, HistoryEncoding.entry(HistoryEncoding.entry(gap.entry)).gap)
        val unclocked = append(usage = usage(observedAt = null), monotonic = null)
        assertNull(HistoryEncoding.entry(HistoryEncoding.entry(unclocked.entry)).observedAt)
    }

    @Test fun encodingRetainsExactScaleExtremeExponentNanosecondAndOrdinalInsteadOfNormalizing() {
        for (percent in listOf(BigDecimal("0.000"), BigDecimal("1E-2147483647"), BigDecimal("100.0000"))) {
            val result = append(HistoryCursor(partition, Long.MAX_VALUE - 1),
                usage(window(Input.Value(percent)), observedAt = at.plusNanos(7)), Long.MAX_VALUE)
            val decoded = HistoryEncoding.entry(HistoryEncoding.entry(result.entry))
            assertEquals(percent, decoded.windows.first().point!!.usedPercent)
            assertEquals(at.plusNanos(7), decoded.observedAt)
            assertEquals(Long.MAX_VALUE, decoded.id.ordinal)
            assertNull(HistoryEncoding.state(HistoryEncoding.state(HistoryDiskState(result.cursor))).cursor.nextId())
        }
    }

    @Test fun malformedPartialTrailingAndOversizedBinaryDataNeverDecodesAsHistory() {
        val encoded = HistoryEncoding.entry(append().entry)
        for (size in listOf(0, 11, encoded.size - 1)) assertThrows(Exception::class.java) { HistoryEncoding.entry(encoded.copyOf(size)) }
        assertThrows(Exception::class.java) { HistoryEncoding.entry(encoded + 0) }
        val changed = encoded.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { HistoryEncoding.entry(changed) }
        assertThrows(Exception::class.java) { HistoryEncoding.state(encoded) }
        assertThrows(Exception::class.java) { HistoryEncoding.entry(ByteArray(HistoryEncoding.MAX_BLOB + 1)) }
        val tooPrecise = usage(window(Input.Value(BigDecimal("0." + "1".repeat(129)))))
        assertThrows(IllegalArgumentException::class.java) { HistoryEncoding.entry(append(usage = tooPrecise).entry) }
        val entry = append().entry
        val wrong = HistoryEntry(entry.partition, entry.id, entry.clock, entry.observedAt, null,
            listOf(entry.windows.first(), entry.windows.first(), entry.windows.first()), entry.slots, entry.allowed, entry.limitReached)
        assertThrows(IllegalArgumentException::class.java) { HistoryEncoding.entry(wrong) }
        val duplicateSlots = HistoryEntry(entry.partition, entry.id, entry.clock, entry.observedAt, null,
            entry.windows, listOf(entry.slots.first(), entry.slots.first()), entry.allowed, entry.limitReached)
        assertThrows(IllegalArgumentException::class.java) { HistoryEncoding.entry(duplicateSlots) }
        val duplicateWindows = HistoryEntry(entry.partition, entry.id, entry.clock, entry.observedAt, null,
            listOf(entry.windows.first(), entry.windows.first()), entry.slots, entry.allowed, entry.limitReached)
        assertThrows(IllegalArgumentException::class.java) { HistoryEncoding.entry(duplicateWindows) }
    }

    @Test fun syntheticallyInterruptedCommitConsumesNoIdAndUncertainCommittedRetryNeverChangesMeasurement() {
        val journal = SyntheticJournal()
        val first = admission(HistoryCursor(partition))
        journal.failBefore = true
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), journal.append(first))
        assertEquals(0, journal.records.size)
        assertEquals(0L, journal.cursor().lastOrdinal)
        journal.failBefore = false; journal.failAfter = true
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), journal.append(first))
        assertEquals(1L, journal.cursor().lastOrdinal)
        assertEquals(1, journal.records.size)
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), journal.append(first.copy(event = HistoryEvent.Gap(HistoryGap.BACKGROUND))))
        assertEquals(BigDecimal("12.375"), HistoryEncoding.entry(journal.records.single()).windows.first().point!!.usedPercent)
    }

    @Test fun trustworthyObservationClockAllowsAgeCutoffButNoReceiptClockIsConsulted() {
        val first = append()
        val initial = HistoryDiskState(HistoryCursor(partition))
        val one = HistoryRetention.advance(initial, first)
        assertNull(HistoryRetention.cutoff(initial, one, 30))
        val later = append(first.cursor, usage(observedAt = at.plusSeconds(2_592_001)), 2_592_002_000)
        val two = HistoryRetention.advance(one, later)
        assertEquals(at.plusSeconds(1), HistoryRetention.cutoff(one, two, 30))
        assertFalse(two.ageSuspended)
        val removed = HistoryRetention.removed(two, HistoryTruncation.AGE_RETENTION, 1)
        assertEquals(mapOf(HistoryTruncation.AGE_RETENTION to HistoryEvictionCutoff(1)), removed.cutoffs)
        assertEquals(later.cursor.lastOrdinal, removed.cursor.lastOrdinal)
    }

    @Test fun rollbackForwardSkewMissingMonotonicAndNewEpochPersistentlySuspendAgeOnly() {
        val first = append()
        val initial = HistoryRetention.advance(HistoryDiskState(HistoryCursor(partition)), first)
        val anomalies = listOf(
            admission(first.cursor, usage(observedAt = at.minusSeconds(1)), 2000),
            admission(first.cursor, usage(observedAt = at.plusSeconds(2_592_001)), 2000),
            admission(first.cursor, usage(observedAt = at.plusSeconds(60)), null),
            admission(first.cursor, usage(observedAt = at.plusSeconds(60)), 2000, ClockEpoch(UUID(0, 44))),
            admission(first.cursor, usage(observedAt = at.plusSeconds(60)), 1000))
        for (event in anomalies) {
            val reduced = WindowHistory.append(first.cursor, event) as HistoryReduction.Applied
            val state = HistoryRetention.advance(initial, reduced)
            assertTrue(state.ageSuspended)
            assertNull(HistoryRetention.cutoff(initial, state, 30))
            assertTrue(HistoryEncoding.state(HistoryEncoding.state(state)).ageSuspended)
            val capped = HistoryRetention.removed(state, HistoryTruncation.OBSERVATION_CAP, 1)
            assertEquals(HistoryEvictionCutoff(1), capped.cutoffs[HistoryTruncation.OBSERVATION_CAP])
        }
        val unknown = append(first.cursor, usage(observedAt = null), 61_000)
        assertEquals(initial.anchor, HistoryRetention.advance(initial, unknown).anchor)
    }

    @Test fun missingWallTimeStillPersistsMonotonicAndEpochAnomaliesAcrossCodecReopen() {
        val first = append()
        val initial = HistoryRetention.advance(HistoryDiskState(HistoryCursor(partition)), first)
        val unclocked = append(first.cursor, usage(observedAt = null), 2000)
        val two = HistoryRetention.advance(initial, unclocked)
        assertFalse(two.ageSuspended)
        assertEquals(initial.anchor, two.anchor)
        val clocks = listOf(HistoryClock(epoch, null), HistoryClock(epoch, 1500),
            HistoryClock(ClockEpoch(UUID(0, 44)), 3000))
        for (clock in clocks) {
            val event = admission(unclocked.cursor, usage(observedAt = null)).copy(clock = clock)
            val reduced = WindowHistory.append(unclocked.cursor, event) as HistoryReduction.Applied
            val saved = HistoryRetention.advance(two, reduced)
            val restored = HistoryEncoding.state(HistoryEncoding.state(saved))
            assertTrue(restored.ageSuspended)
            assertEquals(clock, restored.lastClock)
            val future = append(restored.cursor, usage(observedAt = at.plusSeconds(2_592_001)), 2_592_002_000)
            assertNull(HistoryRetention.cutoff(restored, HistoryRetention.advance(restored, future), 30))
        }
    }

    @Test fun defaultAndInjectableBudgetsCannotBeExpandedAndCutoffsAreDetached() {
        val limits = HistoryStorageLimits()
        assertEquals(30L, limits.retentionDays); assertEquals(100_000, limits.observations)
        assertEquals(33_554_432L, limits.bytes); assertEquals(256, limits.readPage)
        assertTrue(limits.databasePages * 4 * 4096 + 65_536 <= limits.bytes)
        assertThrows(IllegalArgumentException::class.java) { HistoryStorageLimits(retentionDays = 31) }
        assertThrows(IllegalArgumentException::class.java) { HistoryStorageLimits(observations = 100_001) }
        assertThrows(IllegalArgumentException::class.java) { HistoryStorageLimits(bytes = 33_554_433) }
        assertThrows(IllegalArgumentException::class.java) { HistoryStorageLimits(readPage = 257) }
        val map = mutableMapOf(HistoryTruncation.BYTE_CAP to HistoryEvictionCutoff(1))
        val snapshot = HistoryReadSnapshot(HistoryReadQuery(partition, 1), emptyList(), ObservationId(1), false, map.keys, map)
        map.clear()
        assertEquals(HistoryEvictionCutoff(1), snapshot.cutoffs[HistoryTruncation.BYTE_CAP])
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.cutoffs as MutableMap<*, *>).clear() }
    }

    private class SyntheticJournal {
        var failBefore = false
        var failAfter = false
        private var state = HistoryEncoding.state(HistoryDiskState(HistoryCursor(partition)))
        var records = emptyList<ByteArray>()
            private set
        fun cursor(): HistoryCursor = HistoryEncoding.state(state).cursor
        fun append(admission: HistoryAdmission): HistoryAppendOutcome {
            return when (val reduction = WindowHistory.append(cursor(), admission)) {
                is HistoryReduction.AlreadyAdmitted -> HistoryAppendOutcome.AlreadyAdmitted(reduction.id)
                is HistoryReduction.Rejected -> HistoryAppendOutcome.Rejected(reduction.reason)
                is HistoryReduction.Applied -> {
                    val candidate = HistoryEncoding.state(HistoryDiskState(reduction.cursor))
                    val record = HistoryEncoding.entry(reduction.entry)
                    try {
                        if (failBefore) throw IOException()
                        state = candidate; records = records + listOf(record)
                        if (failAfter) throw IOException()
                        HistoryAppendOutcome.Stored(reduction.entry)
                    } catch (_: IOException) { HistoryAppendOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE) }
                }
            }
        }
    }
}
