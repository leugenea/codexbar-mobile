package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test

class CurrentHistoryReadTest {
    @Test fun currentMeasurementsBehindArchiveFilledPagesUseRealSelectedLookaheadAndBoundedReads() {
        val rows = rows(600, 5)
        val access = Access(rows)
        val query = query(rows.last(), 2)
        val first = (CurrentHistoryRead.read(access, query) { false } as HistoryReadOutcome.Ready).snapshot
        assertEquals(listOf(601L, 602L), first.entries.map { it.id.ordinal })
        assertTrue(first.hasMore)
        assertEquals(ObservationId(602), first.nextAfter)
        assertEquals(listOf(null, ObservationId(256), ObservationId(512)), access.reads.map { it.after })
        assertTrue(access.reads.all { it.limit <= 256 })
        val next = (CurrentHistoryRead.read(access, query.copy(after = first.nextAfter)) { false } as HistoryReadOutcome.Ready).snapshot
        assertEquals(query.copy(after = first.nextAfter).storage(access.partition), next.query)
        assertEquals(listOf(603L, 604L), next.entries.map { it.id.ordinal })
        assertTrue(next.hasMore)
        val end = (CurrentHistoryRead.read(access, query.copy(after = next.nextAfter)) { false } as HistoryReadOutcome.Ready).snapshot
        assertEquals(query.copy(after = next.nextAfter).storage(access.partition), end.query)
        assertEquals(listOf(605L), end.entries.map { it.id.ordinal })
        assertFalse(end.hasMore)
        assertSame(rows.last(), end.entries.single())
    }

    @Test fun selectedOrdinalHolesCorrectionsAndResetBoundariesCannotCreateContiguousMeasuredEdges() {
        val rows = rows(1, 3).toMutableList()
        val changed = SyntheticHistory.append(HistoryCursor(SyntheticHistory.partition, 2),
            SyntheticHistory.usage(SyntheticHistory.window(absolute = Input.Value(1_800_003_601L)), observedAt = SyntheticHistory.at.plusSeconds(2)), 3000).entry
        rows[2] = changed
        val access = Access(rows)
        val query = query(rows.last(), 32)
        val page = (CurrentHistoryRead.read(access, query) { false } as HistoryReadOutcome.Ready).snapshot
        assertEquals(listOf(2L, 4L), page.entries.map { it.id.ordinal })
        val plot = HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.READY, query = query, storage = page))
        assertEquals(2, plot.fiveHour.samples.size)
        assertTrue(plot.fiveHour.segments.all { it.points.size == 1 })
        assertTrue(plot.fiveHour.samples.all { it.measured!!.source.usedPercent.toPlainString() == "12.375" })
    }

    @Test fun emptySelectionRetentionReadFailuresCorruptionAndRevocationRemainExplicit() {
        val rows = rows(300, 0)
        val access = Access(rows.drop(1), reportRetention = true)
        val identity = WindowHistory.identity(SyntheticHistory.partition, SyntheticHistory.usage().fiveHour, WindowKind.FIVE_HOUR)!!
        val query = HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR, window = identity, currentOnly = true)
        val empty = (CurrentHistoryRead.read(access, query) { false } as HistoryReadOutcome.Ready).snapshot
        assertEquals(HistoryContent.EMPTY, empty.content)
        assertEquals(setOf(HistoryTruncation.BYTE_CAP), empty.truncation)
        assertEquals(HistoryEvictionCutoff(1), empty.cutoffs[HistoryTruncation.BYTE_CAP])
        access.failure = HistoryReadOutcome.Corrupt
        assertSame(HistoryReadOutcome.Corrupt, CurrentHistoryRead.read(access, query) { false })
        access.failure = HistoryReadOutcome.Unavailable(HistoryUnavailable.IO_FAILURE)
        assertEquals(access.failure, CurrentHistoryRead.read(access, query) { false })
        val before = access.reads.size
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), CurrentHistoryRead.read(access, query) { true })
        assertEquals(before, access.reads.size)
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.READ_FAILURE), CurrentHistoryRead.read(access, query.copy(window = null)) { false })
    }

    @Test fun keyedUnknownPercentStatusIsRetainedWithoutInventedMeasurementOrReference() {
        val first = SyntheticHistory.append()
        val status = SyntheticHistory.append(first.cursor, SyntheticHistory.usage(SyntheticHistory.window(percent = Input.Missing),
            observedAt = SyntheticHistory.at.plusSeconds(1)), 2000)
        val next = SyntheticHistory.append(status.cursor, SyntheticHistory.usage(observedAt = SyntheticHistory.at.plusSeconds(2)), 3000)
        val query = query(first.entry, 32)
        val page = (CurrentHistoryRead.read(Access(listOf(first.entry, status.entry, next.entry)), query) { false } as HistoryReadOutcome.Ready).snapshot
        val graph = HistoryGraphSnapshot(HistoryReadiness.READY, query = query, storage = page)
        assertEquals(listOf(1L, 2L, 3L), graph.entries.map { it.source.id.ordinal })
        assertEquals(2, graph.points.size)
        val missing = graph.entries[1].windows.single()
        assertNull(missing.source.point)
        assertEquals(BaselineEligibility.UNKNOWN_PERCENT, missing.source.baseline)
        assertTrue(missing.reference is DistributionReference.Unavailable)
        val plot = HistoryPlotInputs.project(graph)
        assertEquals(listOf(1, 1), plot.fiveHour.segments.map { it.points.size })
        assertEquals(Knowledge.UNAVAILABLE, missing.source.percent!!.knowledge)
    }

    @Test fun jointCurrentScanFindsBothWindowsBehindArchivesWithIndependentLookahead() {
        val rows = jointRows()
        val access = Access(rows)
        val request = jointRequest(rows.last(), 2)
        val result = (CurrentHistoryRead.readCurrent(access, request) { false } as CurrentHistoryReadOutcome.Ready).pages
        assertEquals(listOf(601L, 602L), result.fiveHour!!.entries.map { it.id.ordinal })
        assertEquals(listOf(636L, 637L), result.weekly!!.entries.map { it.id.ordinal })
        assertTrue(result.fiveHour.hasMore); assertTrue(result.weekly.hasMore)
        assertNull(result.fiveHour.query.after); assertNull(result.weekly.query.after)
        assertEquals(ObservationId(640), result.fiveHour.lastAdmitted)
        assertEquals(result.fiveHour.lastAdmitted, result.weekly.lastAdmitted)
        assertSame(rows.last(), result.latestFiveHour); assertSame(rows.last(), result.latestWeekly)
        assertEquals(listOf(null, ObservationId(256), ObservationId(512)), access.reads.map { it.after })
        assertTrue(access.reads.all { it.limit <= 256 })
    }

    @Test fun jointCurrentScanMissingKeysStatusRetentionAndFailuresStayTyped() {
        val rows = jointRows().toMutableList()
        rows[638] = SyntheticHistory.append(HistoryCursor(SyntheticHistory.partition, lastOrdinal = 638),
            SyntheticHistory.usage(SyntheticHistory.window(), SyntheticHistory.window(percent = Input.Missing, duration = Input.Value(604800L)),
                SyntheticHistory.at.plusSeconds(638)), 639000).entry
        val access = Access(rows, reportRetention = true)
        val request = jointRequest(rows.last(), 32).copy(fiveHour = null)
        val result = (CurrentHistoryRead.readCurrent(access, request) { false } as CurrentHistoryReadOutcome.Ready).pages
        assertNull(result.fiveHour)
        assertEquals((636L..640L).toList(), result.weekly!!.entries.map { it.id.ordinal })
        assertEquals(HistoryEvictionCutoff(1), result.weekly.cutoffs[HistoryTruncation.BYTE_CAP])
        val graph = HistoryGraphSnapshot(HistoryReadiness.READY, query = request.query(WindowKind.WEEKLY), storage = result.weekly)
        assertEquals(4, graph.points.size)
        val unknown = graph.entries.single { it.source.id.ordinal == 639L }.windows.single()
        assertNull(unknown.source.point)
        assertEquals(BaselineEligibility.UNKNOWN_PERCENT, unknown.source.baseline)
        assertTrue(unknown.reference is DistributionReference.Unavailable)
        assertEquals(listOf(3, 1), HistoryPlotInputs.project(graph).weekly.segments.map { it.points.size })
        val before = access.reads.size
        val absent = (CurrentHistoryRead.readCurrent(access, request.copy(weekly = null)) { false } as CurrentHistoryReadOutcome.Ready).pages
        assertNull(absent.weekly); assertEquals(before, access.reads.size)
        access.failure = HistoryReadOutcome.Corrupt
        assertSame(CurrentHistoryReadOutcome.Corrupt, CurrentHistoryRead.readCurrent(access, request) { false })
        access.failure = HistoryReadOutcome.Unavailable(HistoryUnavailable.IO_FAILURE)
        assertEquals(CurrentHistoryReadOutcome.Unavailable(HistoryUnavailable.IO_FAILURE), CurrentHistoryRead.readCurrent(access, request) { false })
        // The first accumulator can have lookahead before a later batch fails. No partial success.
        val early = jointRows(denseFirst = true)
        val dense = jointRequest(early.last(), 2)
        val failing = object : HistoryRuntimeAccess by Access(early) {
            private val delegate = Access(early)
            override fun read(query: HistoryReadQuery): HistoryReadOutcome =
                if ((query.after?.ordinal ?: 0) >= 256) HistoryReadOutcome.Corrupt else delegate.read(query)
        }
        assertSame(CurrentHistoryReadOutcome.Corrupt, CurrentHistoryRead.readCurrent(failing, dense) { false })
    }

    @Test fun jointCurrentScanHasOneHighWaterCeilingAndRejectsRevocation() {
        val source = SyntheticHistory.append().entry
        val calls = mutableListOf<HistoryReadQuery>()
        val access = object : HistoryRuntimeAccess by Access(emptyList()) {
            override fun read(query: HistoryReadQuery): HistoryReadOutcome {
                calls += query
                val first = (query.after?.ordinal ?: 0) + 1
                val entries = List(query.limit) { offset -> HistoryEntry(source.partition, ObservationId(first + offset),
                    source.clock, null, HistoryGap.READ_ERROR, emptyList(), emptyList(), null, null) }
                return HistoryReadOutcome.Ready(HistoryReadSnapshot(query, entries, ObservationId(Long.MAX_VALUE), true))
            }
        }
        val request = jointRequest(jointRows().last(), 32)
        assertEquals(CurrentHistoryReadOutcome.Unavailable(HistoryUnavailable.CAPACITY), CurrentHistoryRead.readCurrent(access, request) { false })
        assertEquals(100_000, calls.sumOf { it.limit }); assertEquals(391, calls.size); assertEquals(160, calls.last().limit)
        calls.clear()
        assertEquals(CurrentHistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), CurrentHistoryRead.readCurrent(access, request) { true })
        assertTrue(calls.isEmpty())
        assertEquals(CurrentHistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), CurrentHistoryRead.readCurrent(access, request) { calls.isNotEmpty() })
        assertEquals(1, calls.size)
        val rows = jointRows()
        val changing = object : HistoryRuntimeAccess by Access(rows) {
            private val delegate = Access(rows)
            override fun read(query: HistoryReadQuery): HistoryReadOutcome {
                val ready = delegate.read(query) as HistoryReadOutcome.Ready
                val high = if (query.after == null) ObservationId(300) else ready.snapshot.lastAdmitted
                return HistoryReadOutcome.Ready(HistoryReadSnapshot(query, ready.snapshot.entries, high, ready.snapshot.hasMore))
            }
        }
        val result = (CurrentHistoryRead.readCurrent(changing, request) { false } as CurrentHistoryReadOutcome.Ready).pages
        assertEquals(ObservationId(300), result.fiveHour!!.lastAdmitted)
        assertEquals(result.fiveHour.lastAdmitted, result.weekly!!.lastAdmitted)
        assertTrue(result.fiveHour.entries.isEmpty()); assertTrue(result.weekly.entries.isEmpty())
        assertNull(result.latestFiveHour); assertNull(result.latestWeekly)
    }

    private fun jointRequest(entry: HistoryEntry, limit: Int) = HistoryPresentationRequest.Current(
        entry.windows.single { it.kind == WindowKind.FIVE_HOUR }.point!!.segment.window,
        entry.windows.single { it.kind == WindowKind.WEEKLY }.point!!.segment.window, limit)

    private fun jointRows(denseFirst: Boolean = false): List<HistoryEntry> {
        var cursor = HistoryCursor(SyntheticHistory.partition)
        return List(640) { index ->
            val five = SyntheticHistory.window(absolute = Input.Value(if (!denseFirst && index < 600) 1_799_999_000L else 1_800_003_600L))
            val weekly = SyntheticHistory.window(duration = Input.Value(604800L), absolute = Input.Value(if (index < 635) 1_799_999_000L else 1_800_003_600L))
            SyntheticHistory.append(cursor, SyntheticHistory.usage(five, weekly, SyntheticHistory.at.plusSeconds(index.toLong())),
                1000L + index * 1000).also { cursor = it.cursor }.entry
        }
    }

    private fun query(entry: HistoryEntry, limit: Int) = HistoryGraphQuery(limit, kind = WindowKind.FIVE_HOUR,
        window = entry.windows.first().point!!.segment.window, currentOnly = true)

    private fun rows(archived: Int, current: Int): List<HistoryEntry> {
        var cursor = HistoryCursor(SyntheticHistory.partition)
        return List(archived + current) { index ->
            val reset = if (index < archived) 1_799_999_000L else 1_800_003_600L
            SyntheticHistory.append(cursor, SyntheticHistory.usage(SyntheticHistory.window(absolute = Input.Value(reset)),
                observedAt = SyntheticHistory.at.plusSeconds(index.toLong())), 1000L + index * 1000).also { cursor = it.cursor }.entry
        }
    }

    private class Access(private val rows: List<HistoryEntry>, private val reportRetention: Boolean = false) : HistoryRuntimeAccess {
        override val partition = SyntheticHistory.partition
        val reads = mutableListOf<HistoryReadQuery>()
        var failure: HistoryReadOutcome? = null
        override fun read(query: HistoryReadQuery): HistoryReadOutcome {
            reads += query
            failure?.let { return it }
            val remaining = rows.filter { it.id.ordinal > (query.after?.ordinal ?: 0) }
            return HistoryReadOutcome.Ready(HistoryReadSnapshot(query, remaining.take(query.limit), rows.lastOrNull()?.id,
                remaining.size > query.limit, if (reportRetention) setOf(HistoryTruncation.BYTE_CAP) else emptySet(),
                if (reportRetention) mapOf(HistoryTruncation.BYTE_CAP to HistoryEvictionCutoff(1)) else emptyMap()))
        }
        override fun append(admission: HistoryAdmission): HistoryAppendOutcome = error("Read-only fixture")
        override fun delete(partition: HistoryPartition): HistoryDeleteOutcome = error("Read-only fixture")
        override fun revoke() = Unit
    }
}
