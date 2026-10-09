package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.auth.SyntheticAuth
import io.github.leugenea.codexbarmobile.transport.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/** Synthetic integration: actual controller/B2/reader/parser/reducer/math, not StateFlow sample counting. */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageHistoryRecorderTest {
    @Test fun acceptedEqualPercentReadsHaveDistinctIdsButCoalescingTicksAndQueriesDoNotSample() = runTest { fixture {
        visible(true)
        assertEquals(HistoryReadiness.EMPTY, snapshot.readiness)
        assertEquals(0, gets.size)
        heldPath = ReadOperation.USAGE.path
        read(); val held = gets.single()
        owner.readUsage(); owner.readUsage(); owner.connect(); settle()
        assertEquals(1, gets.size)
        held.reply(usage); settle()
        assertEquals(2, gets.size)
        assertEquals(listOf(1L), journal.admissions.map { it.id.ordinal })
        val first = snapshot
        assertEquals(Instant.ofEpochSecond(1_800_000_000), first.points.single().observedAt)
        assertEquals(BigDecimal("12.375"), first.points.single().usedPercent)
        val comparison = first.entries.first().windows.first().comparison as UsedComparison.Available
        assertEquals(BigDecimal("80"), comparison.baselinePercent)
        assertEquals(BigDecimal("-67.625"), comparison.deltaPercentagePoints)
        advance(1_000)
        owner.queryHistory(HistoryGraphQuery(kind = WindowKind.FIVE_HOUR)); settle()
        assertEquals(2, gets.size)
        assertEquals(1, journal.admissions.size)
        heldPath = null
        read()
        assertEquals(4, gets.size)
        assertEquals(listOf(1L, 2L), journal.admissions.map { it.id.ordinal })
        assertEquals(listOf(BigDecimal("12.375"), BigDecimal("12.375")), points().map { it.usedPercent })
        assertEquals(1, first.points.size)
        assertEquals(2, snapshot.points.size)
        assertSame(first.generation, snapshot.generation)
        try { (first.points as MutableList).clear(); fail("immutable points") } catch (_: UnsupportedOperationException) { }
    } }

    @Test fun usageDeliveryPersistsBeforeInventoryCompletionAndPartialFailuresNeverReplayCache() = runTest { fixture {
        visible(true); heldPath = ReadOperation.RESET_INVENTORY.path; read()
        assertEquals(1, points().size)
        assertTrue(owner.state.value.refresh.refreshing)
        assertEquals(2, gets.size)
        gets.last().reply(SyntheticAuth.response("{}", 403)); settle()
        assertEquals(ReadError.FORBIDDEN, snapshot.live.inventory.error)
        assertEquals(1, journal.admissions.size)
        heldPath = null
        usage = SyntheticAuth.response("{}", 403)
        inventory = SyntheticAuth.response("""{"available_count":0,"credits":[]}""")
        advance(1_000); read()
        assertEquals(1, points().size)
        assertEquals(1, journal.admissions.size)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), snapshot.live.usage.sourceObservedAt)
        assertEquals(Instant.ofEpochSecond(1_800_000_001), snapshot.live.inventory.sourceObservedAt)
        assertEquals(ReadError.FORBIDDEN, snapshot.live.usage.error)
        assertNull(snapshot.live.usage.latestAttemptObservedAt)
        usage = SyntheticAuth.response(RecordingFixture.USAGE)
        inventory = SyntheticAuth.response("{}", 403)
        advance(1_000); read()
        assertEquals(listOf(1L, 2L, 3L), journal.admissions.map { it.id.ordinal })
        assertEquals(HistoryGap.READ_ERROR, journal.entries[1].gap)
        assertTrue(BreakReason.READ_ERROR in points().last().segment.breaks)
        assertEquals(2, points().size)
        assertEquals(6, gets.size)
    } }

    @Test fun dormantRestoreOffscreenReadAndInventoryOnlyNeverInventMeasurements() = runTest { fixture {
        advance(120_000)
        assertEquals(0, fake.calls.size)
        assertEquals(0, gets.size)
        read() // B2's historical unmanaged manual admission remains valid, but no visible sampler.
        assertEquals(2, gets.size)
        assertTrue(journal.admissions.isEmpty())
        usage = SyntheticAuth.response("{}", 403)
        visible(true) // Previously activated B2 resumes exactly one real cycle.
        assertEquals(4, gets.size)
        assertTrue(journal.admissions.isEmpty())
        assertTrue(points().isEmpty())
        assertEquals(HistoryReadiness.EMPTY, snapshot.readiness)
    } }

    @Test fun lastObserverLossAndCancelRejectHeldResponsesAndWritesWithoutBackfill() = runTest { fixture {
        visible(true); read()
        val first = snapshot.generation
        heldPath = ReadOperation.USAGE.path; read()
        val old = gets.last()
        visible(false)
        assertTrue(old.cancelled)
        assertEquals(HistoryReadiness.UNAVAILABLE, snapshot.readiness)
        assertTrue(snapshot.points.isEmpty())
        advance(120_001)
        assertEquals(3, gets.size)
        old.reply(usage); settle()
        assertEquals(1, points().size)
        heldPath = null; visible(true)
        assertNotSame(first, snapshot.generation)
        assertEquals(2, points().size)
        assertEquals(HistoryGap.BACKGROUND, journal.entries[1].gap)
        assertTrue(BreakReason.BACKGROUND in points().last().segment.breaks)
        assertTrue(BreakReason.INTERVAL_EXCEEDED in points().last().segment.breaks)
        storage.held = true
        read(); assertEquals(1, storage.pending)
        owner.cancel(); settle()
        assertEquals(ConnectionPhase.CANCELLED, owner.state.value.phase)
        assertTrue(snapshot.points.isEmpty())
        storage.release(); settle()
        assertEquals(2, points().size)
        assertTrue(snapshot.points.isEmpty())
    } }

    @Test fun queueOverflowIsBoundedExplicitLossAndNextObservationBreaksWithoutRetry() = runTest { fixture(capacity = 1) {
        visible(true)
        storage.held = true
        read(); read(); read()
        assertEquals(6, gets.size)
        assertEquals(1, storage.pending)
        assertEquals(HistoryRecorderProblem.QUEUE_OVERFLOW, snapshot.problem)
        assertEquals(1L, snapshot.lostSamples)
        assertTrue(journal.admissions.isEmpty())
        storage.release(); settle()
        assertEquals(2, points().size)
        assertEquals(listOf(1L, 2L), journal.admissions.map { it.id.ordinal })
        advance(1_000); read()
        assertEquals(8, gets.size)
        assertEquals(3, points().size)
        assertEquals(HistoryGap.READ_ERROR, journal.entries[2].gap)
        assertTrue(BreakReason.READ_ERROR in points().last().segment.breaks)
        assertEquals(1L, snapshot.lostSamples)
    } }

    @Test fun logoutWhileDurableWorkIsHeldCannotPublishOrResurrectIntoNewLogin() = runTest { fixture {
        visible(true); read()
        val oldPartition = snapshot.partition
        storage.held = true
        read(); owner.signOut(); settle()
        assertEquals(ConnectionPhase.SIGNING_OUT, owner.state.value.phase)
        assertTrue(snapshot.points.isEmpty())
        assertEquals(HistoryReadiness.UNAVAILABLE, snapshot.readiness)
        storage.release(); settle()
        assertEquals(ConnectionPhase.SIGNED_OUT, owner.state.value.phase)
        assertTrue(journal.entries.isEmpty())
        owner.connect(); settle(); advance(5_000)
        assertEquals(ConnectionPhase.OBSERVED, owner.state.value.phase)
        assertNotEquals(oldPartition, snapshot.partition)
        assertEquals(1, points().size)
        assertEquals(listOf(1L), journal.entries.map { it.id.ordinal })
    } }

    @Test fun categoricalWriteReadCorruptionAndThrownFailuresKeepLiveQuotaUsable() = runTest { fixture {
        visible(true)
        journal.appendFailure = HistoryAppendOutcome.Unavailable(HistoryUnavailable.STORAGE_FULL)
        read()
        assertEquals(ConnectionPhase.OBSERVED, owner.state.value.phase)
        assertNotNull(owner.state.value.refresh.usage.success)
        assertEquals(HistoryRecorderProblem.WRITE_FAILURE, snapshot.problem)
        assertEquals(HistoryUnavailable.STORAGE_FULL, snapshot.storageReason)
        assertEquals(1L, snapshot.lostSamples)
        assertTrue(points().isEmpty())
        journal.appendFailure = null
        advance(1_000); read()
        assertEquals(HistoryGap.READ_ERROR, journal.entries.first().gap)
        assertEquals(1, points().size)
        journal.readFailure = HistoryReadOutcome.Corrupt
        owner.queryHistory(HistoryGraphQuery(limit = 1)); settle()
        assertEquals(HistoryReadiness.ERROR, snapshot.readiness)
        assertEquals(HistoryRecorderProblem.CORRUPT, snapshot.problem)
        assertTrue(snapshot.points.isEmpty())
        journal.readFailure = null; journal.throwRead = true
        owner.queryHistory(HistoryGraphQuery()); settle()
        assertEquals(HistoryUnavailable.IO_FAILURE, snapshot.storageReason)
        assertEquals(2, journal.entries.size)
        assertEquals(4, gets.size)
    } }

    @Test fun realCorrectionsResetUnknownAndClockDiscontinuitiesKeepMathAndSegmentsTruthful() = runTest { fixture {
        visible(true); read()
        advance(1_000)
        usage = SyntheticAuth.response(RecordingFixture.USAGE.replace("12.375", "10.125")); read()
        assertTrue(BreakReason.PERCENT_CORRECTION in points().last().segment.breaks)
        assertEquals(NominalStartConfidence.UNCERTAIN_CORRECTION, points().last().nominalStart)
        assertTrue(snapshot.entries.last().windows.first().comparison is UsedComparison.Unavailable)
        usage = SyntheticAuth.response(RecordingFixture.USAGE.replace("1800003600", "1800007200"))
        advance(1_000); read()
        assertTrue(BreakReason.WINDOW_CHANGED in points().last().segment.breaks)
        assertEquals(NominalStartConfidence.NOMINAL_FULL_QUOTA, points().last().nominalStart)
        wallOffset = -10_000
        advance(1_000); read()
        assertTrue(BreakReason.NON_INCREASING_WALL_TIME in points().last().segment.breaks)
        assertTrue(BreakReason.WALL_MONOTONIC_DIVERGENCE in points().last().segment.breaks)
        usage = SyntheticAuth.response("{}")
        advance(1_000); read()
        assertEquals(BaselineEligibility.WINDOW_UNAVAILABLE, journal.entries.last().windows.first().baseline)
        assertEquals(4, points().size)
        assertEquals(10, gets.size)
    } }

    @Test fun orderedBoundedQueriesExposePaginationSelectionAndIndependentStaleness() = runTest { fixture {
        visible(true); read(); advance(1_000); read()
        owner.queryHistory(HistoryGraphQuery(limit = 1)); settle()
        assertTrue(snapshot.hasMore)
        assertEquals(listOf(1L), snapshot.storage!!.entries.map { it.id.ordinal })
        owner.queryHistory(HistoryGraphQuery(limit = 1, after = ObservationId(1), kind = WindowKind.WEEKLY)); settle()
        assertFalse(snapshot.hasMore)
        assertTrue(snapshot.points.isEmpty())
        assertEquals(listOf(2L), snapshot.storage!!.entries.map { it.id.ordinal })
        assertEquals(HistoryUnit.PERCENTAGE_POINTS, snapshot.deltaUnit)
        usage = SyntheticAuth.response("{}", 403)
        inventory = SyntheticAuth.response("{}", 403)
        advance(899_000)
        assertFalse(snapshot.live.usage.stale)
        advance(1_000)
        assertTrue(snapshot.live.usage.stale)
        assertTrue(snapshot.live.inventory.stale)
        assertEquals(ReadError.FORBIDDEN, snapshot.live.usage.error)
        assertEquals(2, journal.admissions.size)
    } }

    @Test fun automaticCadenceAndRetryAfterRemainB2OwnedWithNoHistoryRetryWork() = runTest { fixture {
        visible(true); read()
        advance(59_999); assertEquals(2, gets.size)
        advance(1); assertEquals(4, gets.size)
        assertEquals(2, points().size)
        usage = SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(180_000))
        read(); assertEquals(6, gets.size)
        visible(false); visible(true); read()
        usage = SyntheticAuth.response(RecordingFixture.USAGE)
        advance(119_999); assertEquals(6, gets.size)
        advance(1); assertEquals(8, gets.size)
        assertEquals(3, points().size)
        assertTrue(BreakReason.BACKGROUND in points().last().segment.breaks)
    } }

    @Test fun heldQueriesRejectEarlierSelectionAndLogoutResultsWithoutExtraProviderWork() = runTest { fixture {
        visible(true); read(); advance(1_000); read()
        storage.held = true
        owner.queryHistory(HistoryGraphQuery(limit = 1)); settle()
        assertEquals(1, storage.pending)
        assertEquals(HistoryReadiness.LOADING, snapshot.readiness)
        owner.queryHistory(HistoryGraphQuery(limit = 1, after = ObservationId(1), kind = WindowKind.FIVE_HOUR)); settle()
        storage.release(); settle()
        assertEquals(listOf(2L), snapshot.points.map { it.observation.ordinal })
        assertEquals(4, gets.size)
        storage.held = true
        owner.queryHistory(HistoryGraphQuery()); settle()
        assertEquals(1, storage.pending)
        owner.signOut(); settle()
        assertEquals(HistoryReadiness.UNAVAILABLE, snapshot.readiness)
        assertTrue(snapshot.points.isEmpty())
        storage.release(); settle()
        assertEquals(ConnectionPhase.SIGNED_OUT, owner.state.value.phase)
        assertTrue(snapshot.points.isEmpty())
        assertTrue(journal.entries.isEmpty())
        assertEquals(4, gets.size)
    } }

    @Test fun acceptedResumeWhileRuntimeReadoptionIsHeldKeepsCapturedPartitionWithoutRetry() = runTest { fixture {
        visible(true); read()
        val partition = snapshot.partition
        visible(false)
        storage.held = true
        advance(1_000); visible(true)
        assertEquals(4, gets.size)
        assertEquals(1, storage.pending)
        assertEquals(1, points().size)
        assertEquals(0L, snapshot.lostSamples)
        storage.release(); settle()
        assertEquals(partition, snapshot.partition)
        assertEquals(listOf(1L, 2L, 3L), journal.entries.map { it.id.ordinal })
        assertEquals(HistoryGap.BACKGROUND, journal.entries[1].gap)
        assertEquals(2, points().size)
        assertEquals(Instant.ofEpochSecond(1_800_000_001), points().last().observedAt)
        assertEquals(0L, snapshot.lostSamples)
        assertEquals(4, gets.size)
    } }

    private suspend fun TestScope.fixture(capacity: Int = 16, block: RecordingFixture.() -> Unit) {
        val h = RecordingFixture(this, capacity)
        runCurrent()
        try { h.block() } finally { h.finish() }
    }
}
