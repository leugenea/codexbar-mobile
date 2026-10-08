package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.history.SyntheticHistory.admission
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.append
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.at
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.epoch
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.five
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.partition
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.usage
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class HistoryContractTest {
    @Test fun admissionsAreContiguousPartitionScopedAndIdempotentIncludingChangedRetryPayload() {
        val initial = HistoryCursor(partition)
        val first = append(initial)
        assertEquals(ObservationId(2), first.cursor.nextId())
        val retry = admission(first.cursor, usage(observedAt = at.plusSeconds(60)), id = ObservationId(1))
        assertEquals(HistoryReduction.AlreadyAdmitted(ObservationId(1)), WindowHistory.append(first.cursor, retry))
        assertEquals(HistoryReduction.Rejected(AdmissionRejection.ORDINAL_GAP),
            WindowHistory.append(initial, admission(initial, id = ObservationId(2))))
        val other = HistoryPartition(UUID(0, 9))
        assertEquals(HistoryReduction.Rejected(AdmissionRejection.PARTITION_MISMATCH),
            WindowHistory.append(first.cursor, retry.copy(partition = other)))
        assertEquals(1L, first.cursor.lastOrdinal)
        assertEquals(ObservationId(1), initial.nextId())
    }

    @Test fun durableOrdinalCapacityDoesNotWrapAndMaximumAdmissionRemainsRetryable() {
        val penultimate = HistoryCursor(partition, Long.MAX_VALUE - 1)
        val maximum = append(penultimate)
        assertEquals(ObservationId(Long.MAX_VALUE), maximum.entry.id)
        assertNull(maximum.cursor.nextId())
        assertEquals(HistoryReduction.AlreadyAdmitted(ObservationId(Long.MAX_VALUE)),
            WindowHistory.append(maximum.cursor, admission(maximum.cursor, id = ObservationId(Long.MAX_VALUE))))
        assertThrows(IllegalArgumentException::class.java) { ObservationId(0) }
        assertThrows(IllegalArgumentException::class.java) { ObservationId(-1) }
        assertThrows(IllegalArgumentException::class.java) { HistoryCursor(partition, -1) }
        assertThrows(IllegalArgumentException::class.java) { HistoryClock(epoch, -1) }
    }

    @Test fun runtimeCapabilitiesAreNotDurablePartitionOrEpochIdentities() {
        val generation = HistoryGeneration()
        assertSame(generation, generation)
        assertNotEquals(generation, HistoryGeneration())
        assertEquals(partition, HistoryPartition(UUID(0, 1)))
        assertEquals(epoch, ClockEpoch(UUID(0, 2)))
        assertNotEquals(partition.value, epoch.value)
    }

    @Test fun snapshotsSeparateEmptyStatusOnlyMeasurementsAndUnavailableBaselines() {
        val query = HistoryReadQuery(partition, 10)
        val empty = HistoryReadSnapshot(query, emptyList(), null, false)
        assertEquals(HistoryContent.EMPTY, empty.content)
        assertNull(empty.nextAfter)
        val status = append(usage = usage(observedAt = null)).entry
        assertEquals(HistoryContent.STATUS_ONLY, HistoryReadSnapshot(query, listOf(status), ObservationId(1), false).content)
        val afterReset = append(usage = usage(observedAt = at.plusSeconds(3601)))
        assertEquals(BaselineEligibility.AFTER_RESET, five(afterReset).baseline)
        assertEquals(HistoryContent.MEASUREMENTS,
            HistoryReadSnapshot(query, listOf(afterReset.entry), ObservationId(1), false).content)
        assertEquals(HistoryReadOutcome.Ready(empty).snapshot, empty)
    }

    @Test fun snapshotAndSegmentCollectionsAreDetachedAndRejectMutation() {
        val result = append()
        val entries = mutableListOf(result.entry)
        val reasons = mutableSetOf(HistoryTruncation.AGE_RETENTION)
        val snapshot = HistoryReadSnapshot(HistoryReadQuery(partition, 1), entries, ObservationId(2), true, reasons)
        entries.clear(); reasons.clear()
        assertEquals(listOf(result.entry), snapshot.entries)
        assertEquals(setOf(HistoryTruncation.AGE_RETENTION), snapshot.truncation)
        assertEquals(ObservationId(1), snapshot.nextAfter)
        assertTrue(snapshot.hasMore)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.entries as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.truncation as MutableSet<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.entry.windows as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.entry.slots as MutableList<*>).clear() }
        val breaks = mutableSetOf(BreakReason.BACKGROUND)
        val point = five(result).point!!
        val segment = HistorySegment(point.segment.id, point.segment.window, breaks)
        breaks.clear()
        assertEquals(setOf(BreakReason.BACKGROUND), segment.breaks)
        assertThrows(UnsupportedOperationException::class.java) { (segment.breaks as MutableSet<*>).clear() }
        val retained = mutableSetOf(HistoryTruncation.BYTE_CAP)
        val stored = HistoryAppendOutcome.Stored(result.entry, retained)
        retained.clear()
        assertEquals(result.entry, stored.entry)
        assertEquals(setOf(HistoryTruncation.BYTE_CAP), stored.truncation)
        assertThrows(UnsupportedOperationException::class.java) { (stored.truncation as MutableSet<*>).clear() }
    }

    @Test fun readBoundsRejectWrongPartitionDuplicateOrdinalAndDishonestPagination() {
        val first = append()
        val second = append(first.cursor, usage(observedAt = at.minusSeconds(60)), 61_000)
        val query = HistoryReadQuery(partition, 2)
        val ordered = HistoryReadSnapshot(query, listOf(first.entry, second.entry), ObservationId(2), false)
        assertEquals(listOf(1L, 2L), ordered.entries.map { it.id.ordinal })
        assertTrue(ordered.entries.first().observedAt!! > ordered.entries.last().observedAt!!)
        assertThrows(IllegalArgumentException::class.java) { HistoryReadQuery(partition, 0) }
        assertThrows(IllegalArgumentException::class.java) { HistoryReadQuery(partition, 100_001) }
        assertEquals(100_000, HistoryReadQuery(partition, 100_000).limit)
        fun invalid(query: HistoryReadQuery = HistoryReadQuery(partition, 2), entries: List<HistoryEntry> = emptyList(), last: ObservationId? = ObservationId(2), more: Boolean = false) =
            assertThrows(IllegalArgumentException::class.java) { HistoryReadSnapshot(query, entries, last, more) }
        invalid(entries = listOf(first.entry, first.entry))
        invalid(entries = listOf(second.entry, first.entry))
        invalid(query = HistoryReadQuery(partition, 1), entries = listOf(first.entry, second.entry))
        invalid(query = HistoryReadQuery(HistoryPartition(UUID(0, 9)), 2), entries = listOf(first.entry))
        invalid(query = HistoryReadQuery(partition, 2, ObservationId(1)), entries = listOf(first.entry))
        invalid(entries = listOf(second.entry), last = ObservationId(1))
        invalid(entries = listOf(first.entry), last = null)
        invalid(more = true)
    }

    @Test fun pureSyntheticPortExampleRetainsIdempotencyAcrossEvictionAndDeletesOnlyNamedPartition() {
        val store = SyntheticStore()
        val first = admission(HistoryCursor(partition))
        assertTrue(store.append(first) is HistoryAppendOutcome.Stored)
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), store.append(first))
        val second = first.copy(id = ObservationId(2))
        assertTrue(store.append(second) is HistoryAppendOutcome.Stored)
        val other = HistoryPartition(UUID(0, 9))
        store.append(first.copy(partition = other))
        store.evictFirst(partition)
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), store.append(first))
        val snapshot = (store.read(HistoryReadQuery(partition, 1)) as HistoryReadOutcome.Ready).snapshot
        assertEquals(listOf(2L), snapshot.entries.map { it.id.ordinal })
        assertEquals(ObservationId(2), snapshot.lastAdmitted)
        assertEquals(setOf(HistoryTruncation.OBSERVATION_CAP), snapshot.truncation)
        assertEquals(HistoryDeleteOutcome.Deleted, store.delete(partition))
        assertEquals(HistoryDeleteOutcome.AlreadyAbsent, store.delete(partition))
        assertEquals(HistoryContent.EMPTY, (store.read(HistoryReadQuery(partition, 1)) as HistoryReadOutcome.Ready).snapshot.content)
        assertEquals(listOf(1L), (store.read(HistoryReadQuery(other, 1)) as HistoryReadOutcome.Ready).snapshot.entries.map { it.id.ordinal })
        assertEquals(listOf(2L), snapshot.entries.map { it.id.ordinal }) // detached even after delete
        assertEquals(30L, HistoryLimits.RETENTION_DAYS)
        assertEquals(33_554_432L, HistoryLimits.MAX_BYTES)
    }

    @Test fun unavailableCorruptionAndDeletionFailureAreTypedNotEmptyOrSuccessful() {
        for (reason in HistoryUnavailable.entries) {
            assertEquals(reason, HistoryReadOutcome.Unavailable(reason).reason)
            assertEquals(reason, HistoryAppendOutcome.Unavailable(reason).reason)
            assertEquals(reason, HistoryDeleteOutcome.Unavailable(reason).reason)
        }
        val corrupt: HistoryReadOutcome = HistoryReadOutcome.Corrupt
        assertFalse(corrupt is HistoryReadOutcome.Ready)
        assertNotEquals(HistoryDeleteOutcome.Deleted, HistoryDeleteOutcome.Corrupt)
        assertNotEquals(HistoryAppendOutcome.Corrupt, HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)))
        val rejected = HistoryAppendOutcome.Rejected(AdmissionRejection.ORDINAL_GAP)
        assertEquals(AdmissionRejection.ORDINAL_GAP, rejected.reason)
    }

    /** Contract illustration only: no platform, disk, native persistence or retention claim. */
    private class SyntheticStore : HistoryStore {
        private val cursors = mutableMapOf<HistoryPartition, HistoryCursor>()
        private val records = mutableMapOf<HistoryPartition, MutableList<HistoryEntry>>()
        private val evicted = mutableSetOf<HistoryPartition>()

        override fun append(admission: HistoryAdmission): HistoryAppendOutcome {
            val cursor = cursors[admission.partition] ?: HistoryCursor(admission.partition)
            return when (val reduction = WindowHistory.append(cursor, admission)) {
                is HistoryReduction.Applied -> {
                    cursors[admission.partition] = reduction.cursor
                    records.getOrPut(admission.partition) { mutableListOf() }.add(reduction.entry)
                    HistoryAppendOutcome.Stored(reduction.entry)
                }
                is HistoryReduction.AlreadyAdmitted -> HistoryAppendOutcome.AlreadyAdmitted(reduction.id)
                is HistoryReduction.Rejected -> HistoryAppendOutcome.Rejected(reduction.reason)
            }
        }

        override fun read(query: HistoryReadQuery): HistoryReadOutcome {
            val matching = records[query.partition].orEmpty().filter { it.id.ordinal > (query.after?.ordinal ?: 0) }
            return HistoryReadOutcome.Ready(HistoryReadSnapshot(query, matching.take(query.limit),
                cursors[query.partition]?.lastOrdinal?.let { ObservationId(it) }, matching.size > query.limit,
                if (query.partition in evicted) setOf(HistoryTruncation.OBSERVATION_CAP) else emptySet()))
        }

        override fun delete(partition: HistoryPartition): HistoryDeleteOutcome {
            val removed = cursors.remove(partition)
            records.remove(partition); evicted.remove(partition)
            return if (removed == null) HistoryDeleteOutcome.AlreadyAbsent else HistoryDeleteOutcome.Deleted
        }

        fun evictFirst(partition: HistoryPartition) {
            records.getValue(partition).removeAt(0)
            evicted.add(partition)
        }
    }
}
