package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.history.SyntheticHistory.append
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.at
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.epoch
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.partition
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.usage
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.window
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.usage.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Original synthetic admitted facts; execution and compatible coverage are hosted-only gates. */
class HistoryPlotInputsTest {
    @Test fun lateMeasurementsAndObservedDeltasStaySeparateFromNominalAnalyticalEndpoints() {
        val first = append(usage = usage(secondary = weeklyInput()))
        val graph = graph(listOf(first.entry))
        val plot = HistoryPlotInputs.project(graph)
        assertSame(graph, plot.source)
        val five = plot.fiveHour
        val measured = five.samples.single().measured!!
        assertSame(graph.entries.single().windows.first().source.point, measured.source)
        assertEquals("12.375", measured.exactPercentText)
        assertEquals(at.toString(), measured.exactObservedAtText)
        assertEquals(HistoryPlotContent.SINGLE_POINT, five.content)
        assertEquals(listOf(ObservationId(1)), five.segments.single().points.map { it.source.observation })
        val reference = five.references.single()
        assertEquals(PlotReferenceMeaning.NOMINAL_EVEN_DISTRIBUTION, reference.meaning)
        assertSame((five.samples.single().window.reference as DistributionReference.Available).series, reference.source)
        assertEquals(Instant.ofEpochSecond(1_799_985_600), reference.source.nominalStartAt)
        assertEquals(Instant.ofEpochSecond(1_800_003_600), reference.source.end.at)
        assertEquals(PlotPosition.Available(0.0, 0.0, PlotRenderDetail.APPROXIMATE), reference.start)
        assertEquals(PlotPosition.Available(1.0, 1.0, PlotRenderDetail.APPROXIMATE), reference.end)
        assertSame(measured.source.segment, five.deltaSegments.single().source)
        assertEquals(0.8, position(measured.position).x, 0.0)
        assertEquals(0.12375, position(measured.position).y, 0.0)
        val delta = five.samples.single().delta!!
        assertSame(five.samples.single().window.comparison, delta.source)
        assertEquals("-67.625", delta.exactText)
        assertEquals(at, delta.observedAt)
        assertEquals(HistoryUnit.PERCENTAGE_POINTS, delta.unit)
        assertEquals(0.161875, position(delta.position).y, 0.0)
        assertEquals(HistoryUnit.PERCENT, five.measuredDomain!!.value.unit)
        assertEquals(HistoryUnit.PERCENT, reference.unit)
        assertEquals(HistoryUnit.PERCENTAGE_POINTS, five.deltaDomain!!.value.unit)
        assertEquals(WindowKind.WEEKLY, plot.weekly.kind)
        assertEquals("12.500", plot.weekly.samples.single().delta!!.exactText)
        assertNotEquals(five.references.single().source.window, plot.weekly.references.single().source.window)
        assertEquals(1, plot.weekly.segments.single().points.size)
    }

    @Test fun independentEqualReadingsFormOneRunWithoutRoundingOrRepeatedReferences() {
        val original = BigDecimal("12.37500000000000000001")
        val first = append(usage = usage(window(Input.Value(original)), weeklyInput()))
        val next = append(first.cursor, usage(window(Input.Value(original)), weeklyInput(), at.plusSeconds(60)), 61_000)
        val plot = HistoryPlotInputs.project(graph(listOf(first.entry, next.entry)))
        for (series in listOf(plot.fiveHour, plot.weekly)) {
            assertEquals(HistoryPlotContent.MULTIPLE_POINTS, series.content)
            assertEquals(listOf(1L, 2L), series.segments.single().points.map { it.source.observation.ordinal })
            assertEquals(2, series.deltaSegments.single().points.size)
            assertEquals(1, series.references.size)
            assertEquals(at.plusSeconds(60), series.samples.last().delta!!.observedAt)
        }
        assertSame(original, plot.fiveHour.samples.last().measured!!.source.usedPercent)
        assertEquals("12.37500000000000000001", plot.fiveHour.samples.last().measured!!.exactPercentText)
    }

    @Test fun readinessPageContentAndFilteredEmptyAreIndependent() {
        for (readiness in HistoryReadiness.entries) {
            val noPage = HistoryPlotInputs.project(HistoryGraphSnapshot(readiness))
            assertEquals(readiness, noPage.source.readiness)
            assertEquals(HistoryPlotContent.NO_PAGE, noPage.fiveHour.content)
            assertNull(noPage.fiveHour.measuredDomain)
            assertTrue(noPage.fiveHour.references.isEmpty())
        }
        val empty = HistoryPlotInputs.project(graph(emptyList(), readiness = HistoryReadiness.EMPTY))
        assertEquals(HistoryPlotContent.EMPTY_PAGE, empty.fiveHour.content)
        assertEquals(HistoryContent.EMPTY, empty.source.storage!!.content)
        val unknown = append(usage = usage(observedAt = null))
        val status = HistoryPlotInputs.project(graph(listOf(unknown.entry)))
        assertEquals(HistoryPlotContent.STATUS_ONLY, status.fiveHour.content)
        assertEquals(BigDecimal("12.375"), status.fiveHour.samples.single().window.source.percent!!.value)
        assertNull(status.fiveHour.samples.single().measured)
        val observed = append()
        val filter = HistoryGraphQuery(window = SyntheticHistory.five(observed).point!!.segment.window!!.copy(resetAt = at))
        val filtered = HistoryPlotInputs.project(graph(listOf(observed.entry), query = filter))
        assertEquals(HistoryPlotContent.FILTERED_EMPTY, filtered.fiveHour.content)
        assertEquals(HistoryContent.MEASUREMENTS, filtered.source.storage!!.content)
        assertTrue(filtered.fiveHour.samples.isEmpty())
        val selected = HistoryPlotInputs.project(graph(listOf(observed.entry), query = HistoryGraphQuery(kind = WindowKind.FIVE_HOUR)))
        assertEquals(HistoryPlotContent.FILTERED_EMPTY, selected.weekly.content)
        assertEquals(HistoryPlotContent.SINGLE_POINT, selected.fiveHour.content)
    }

    @Test fun gapOnlyPageAndFilteredOutWindowsStillExposeAllGapEntries() {
        val first = append()
        val gap = gap(first, HistoryGap.READ_ERROR)
        val onlyGap = HistoryPlotInputs.project(graph(listOf(gap.entry)))
        assertEquals(HistoryPlotContent.STATUS_ONLY, onlyGap.fiveHour.content)
        assertEquals(HistoryGap.READ_ERROR, onlyGap.source.entries.single().source.gap)
        val filter = HistoryGraphQuery(window = SyntheticHistory.five(first).point!!.segment.window!!.copy(resetAt = at))
        val filtered = HistoryPlotInputs.project(graph(listOf(first.entry, gap.entry), query = filter))
        assertEquals(HistoryPlotContent.FILTERED_EMPTY, filtered.fiveHour.content)
        assertEquals(HistoryGap.READ_ERROR, filtered.source.entries.last().source.gap)
        assertEquals(2, filtered.source.entries.size)
        assertTrue(filtered.fiveHour.segments.isEmpty())
    }

    @Test fun errorsLossEndpointClocksAndEveryRetentionBoundAreRetainedVerbatim() {
        val observed = append()
        val live = HistoryLiveMetadata(HistoryEndpointMetadata(at, null, 503, ReadError.TRANSIENT, true),
            HistoryEndpointMetadata(at.plusSeconds(1), at.plusSeconds(2), 200), true)
        val cutoffs = HistoryTruncation.entries.associateWith { HistoryEvictionCutoff(1) }
        val page = HistoryReadSnapshot(HistoryReadQuery(partition, 1), listOf(observed.entry), ObservationId(8),
            true, HistoryTruncation.entries, cutoffs)
        val source = HistoryGraphSnapshot(HistoryReadiness.ERROR, HistoryGeneration(), partition,
            HistoryGraphQuery(limit = 1), page, HistoryRecorderProblem.WRITE_FAILURE, HistoryUnavailable.STORAGE_FULL, Long.MAX_VALUE, live)
        val plot = HistoryPlotInputs.project(source)
        assertSame(source, plot.source)
        assertSame(source.generation, plot.source.generation)
        assertEquals(partition, plot.source.partition)
        assertEquals(HistoryReadiness.ERROR, plot.source.readiness)
        assertEquals(HistoryRecorderProblem.WRITE_FAILURE, plot.source.problem)
        assertEquals(HistoryUnavailable.STORAGE_FULL, plot.source.storageReason)
        assertEquals(Long.MAX_VALUE, plot.source.lostSamples)
        assertTrue(plot.source.hasMore)
        assertSame(page, plot.source.storage)
        assertEquals(ObservationId(1), plot.source.storage!!.nextAfter)
        assertEquals(ObservationId(8), plot.source.storage.lastAdmitted)
        assertEquals(HistoryTruncation.entries.toSet(), plot.source.truncation)
        assertEquals(cutoffs, plot.source.storage.cutoffs)
        assertSame(live, plot.source.live)
        assertNull(plot.source.live.usage.latestAttemptObservedAt)
        assertEquals(HistoryPlotContent.SINGLE_POINT, plot.fiveHour.content)
    }

    @Test fun correctionsAndResetChangesSplitMeasuredRunsWithoutGuessingResetCause() {
        val first = append(usage = usage(secondary = weeklyInput()))
        val corrected = append(first.cursor, usage(window(Input.Value(BigDecimal("9"))), weeklyInput(), at.plusSeconds(60)), 61_000)
        val changed = append(corrected.cursor, usage(window(absolute = Input.Value(1_800_003_720L)), weeklyInput(), at.plusSeconds(120)), 121_000)
        val plot = HistoryPlotInputs.project(graph(listOf(first.entry, corrected.entry, changed.entry)))
        assertEquals(listOf(1, 1, 1), plot.fiveHour.segments.map { it.points.size })
        assertEquals(setOf(BreakReason.PERCENT_CORRECTION), plot.fiveHour.segments[1].source.breaks)
        assertEquals(setOf(BreakReason.WINDOW_CHANGED), plot.fiveHour.segments[2].source.breaks)
        assertTrue(plot.fiveHour.segments.all { it.source.resetCause == ResetCause.UNKNOWN })
        assertEquals(2, plot.fiveHour.references.size)
        assertEquals(2, plot.fiveHour.deltaSegments.size)
        assertUnavailable(plot.fiveHour.samples[1], BaselineEligibility.UNCERTAIN_CORRECTION)
        assertEquals(3, plot.weekly.segments.single().points.size)
        assertEquals(1, plot.weekly.references.size)
    }

    @Test fun backgroundReadFailureFieldStatusAndUnkeyedPointsNeverBridgeRuns() {
        for (reason in HistoryGap.entries) {
            val first = append(usage = usage(secondary = weeklyInput()))
            val gap = gap(first, reason)
            val next = append(gap.cursor, usage(secondary = weeklyInput(), observedAt = at.plusSeconds(60)), 61_000)
            val plot = HistoryPlotInputs.project(graph(listOf(first.entry, gap.entry, next.entry)))
            for (series in listOf(plot.fiveHour, plot.weekly)) {
                assertEquals(listOf(1, 1), series.segments.map { it.points.size })
                assertEquals(listOf(1, 1), series.deltaSegments.map { it.points.size })
                assertEquals(2, series.references.size)
            }
        }
        val first = append()
        val unknown = append(first.cursor, usage(window(Input.Null), observedAt = at.plusSeconds(30)), 31_000)
        val next = append(unknown.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        val plot = HistoryPlotInputs.project(graph(listOf(first.entry, unknown.entry, next.entry)))
        assertEquals(listOf(1, 1), plot.fiveHour.segments.map { it.points.size })
        assertNull(plot.fiveHour.samples[1].measured)
        assertUnavailable(plot.fiveHour.samples[1], BaselineEligibility.UNKNOWN_PERCENT)
        val unkeyed = append(usage = usage(window(absolute = Input.Null)))
        val unkeyedNext = append(unkeyed.cursor, usage(window(absolute = Input.Null), observedAt = at.plusSeconds(60)), 61_000)
        val isolated = HistoryPlotInputs.project(graph(listOf(unkeyed.entry, unkeyedNext.entry)))
        assertEquals(listOf(1, 1), isolated.fiveHour.segments.map { it.points.size })
        assertTrue(isolated.fiveHour.segments.all { it.source.window == null })
        assertTrue(isolated.fiveHour.references.isEmpty())
    }

    @Test fun backwardsAndEqualWallTimesRemainInAdmissionOrderAndCannotJoin() {
        val first = append()
        for (time in listOf(at.minusSeconds(1), at)) {
            val next = append(first.cursor, usage(observedAt = time), 2000)
            val plot = HistoryPlotInputs.project(graph(listOf(first.entry, next.entry)))
            assertEquals(listOf(at, time), plot.fiveHour.samples.map { it.measured!!.source.observedAt })
            assertEquals(listOf(1L, 2L), plot.fiveHour.samples.map { it.measured!!.source.observation.ordinal })
            assertEquals(listOf(1, 1), plot.fiveHour.segments.map { it.points.size })
            assertTrue(BreakReason.NON_INCREASING_WALL_TIME in plot.fiveHour.segments.last().source.breaks)
            // Defensive geometry boundary even for a handcrafted source reusing an old segment.
            val reused = replace(next.entry, SyntheticHistory.five(next).copy(point = SyntheticHistory.five(next).point!!.copy(
                segment = SyntheticHistory.five(first).point!!.segment)))
            assertEquals(2, HistoryPlotInputs.project(graph(listOf(first.entry, reused))).fiveHour.segments.size)
        }
    }

    @Test fun retainedPaginatedOriginAndOrdinalHolesNeverInventOrReconnectMissingPoints() {
        val first = append()
        val next = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        val last = append(next.cursor, usage(observedAt = at.plusSeconds(120)), 121_000)
        val query = HistoryGraphQuery(limit = 1, after = ObservationId(1))
        val page = HistoryReadSnapshot(query.storage(partition), listOf(next.entry), ObservationId(3), true,
            listOf(HistoryTruncation.AGE_RETENTION), mapOf(HistoryTruncation.AGE_RETENTION to HistoryEvictionCutoff(1)))
        val plot = HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.READY, query = query, storage = page))
        val run = plot.fiveHour.segments.single()
        assertEquals(ObservationId(1), run.source.id.first)
        assertEquals(listOf(ObservationId(2)), run.points.map { it.source.observation })
        assertEquals(at.plusSeconds(60), run.points.single().source.observedAt)
        assertEquals(ObservationId(1), plot.fiveHour.references.single().source.segment.first)
        assertTrue(plot.source.hasMore)
        val otherPage = HistoryPlotInputs.project(graph(listOf(last.entry), query = HistoryGraphQuery(after = ObservationId(2))))
        assertEquals(listOf(ObservationId(3)), otherPage.fiveHour.segments.single().points.map { it.source.observation })
        val hole = HistoryPlotInputs.project(graph(listOf(first.entry, last.entry)))
        assertEquals(listOf(1, 1), hole.fiveHour.segments.map { it.points.size })
        assertEquals(listOf(1, 1), hole.fiveHour.deltaSegments.map { it.points.size })
    }

    @Test fun unknownConflictingAndOutOfIntervalBaselinesKeepTypedAbsenceAndExactFacts() {
        val inputs = listOf(window(absolute = Input.Invalid), window(relative = Input.Value(59L)),
            window(absolute = Input.Missing, relative = Input.Value(60L)))
        for (input in inputs) {
            val result = append(usage = usage(input))
            val sample = HistoryPlotInputs.project(graph(listOf(result.entry))).fiveHour.samples.single()
            assertSame(SyntheticHistory.five(result), sample.window.source)
            assertNotNull(sample.measured)
            assertUnavailable(sample, BaselineEligibility.UNKEYED_RESET)
        }
        for (eligibility in BaselineEligibility.entries.filter { it != BaselineEligibility.ELIGIBLE }) {
            val result = append()
            val entry = replace(result.entry, SyntheticHistory.five(result).copy(baseline = eligibility))
            val series = HistoryPlotInputs.project(graph(listOf(entry))).fiveHour
            assertUnavailable(series.samples.single(), eligibility)
            assertTrue(series.references.isEmpty())
            assertTrue(series.deltaSegments.isEmpty())
            assertEquals(1, series.segments.single().points.size)
        }
        val noTime = append(usage = usage(observedAt = null))
        assertUnavailable(HistoryPlotInputs.project(graph(listOf(noTime.entry))).fiveHour.samples.single(),
            BaselineEligibility.UNKNOWN_OBSERVATION_TIME)
    }

    @Test fun geometryFailureIsExplicitAndCannotBridgeRenderableNeighbors() {
        val first = append()
        val middle = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        val last = append(middle.cursor, usage(observedAt = at.plusSeconds(120)), 121_000)
        val original = SyntheticHistory.five(middle)
        val extreme = BigDecimal("0." + "1".repeat(1025))
        val entry = replace(middle.entry, original.copy(percent = Field(Knowledge.KNOWN, extreme),
            point = original.point!!.copy(usedPercent = extreme)))
        val plot = HistoryPlotInputs.project(graph(listOf(first.entry, entry, last.entry)))
        val sample = plot.fiveHour.samples[1]
        assertSame(extreme, sample.measured!!.source.usedPercent)
        assertEquals(extreme.toString(), sample.measured.exactPercentText)
        assertEquals(PlotPosition.Unavailable(PlotRenderProblem.DECIMAL_CAPACITY), sample.measured.position)
        assertEquals(DistributionUnavailable.InvalidFact.DECIMAL_CAPACITY,
            (sample.window.comparison as UsedComparison.Unavailable).reason)
        assertEquals(3, plot.fiveHour.samples.size)
        assertEquals(listOf(1, 1), plot.fiveHour.segments.map { it.points.size })
        assertEquals(listOf(1, 1), plot.fiveHour.deltaSegments.map { it.points.size })
        for (value in listOf(BigDecimal("-1"), BigDecimal("1E+10000"), BigDecimal(BigInteger.ONE, Int.MIN_VALUE))) {
            val bad = replace(first.entry, SyntheticHistory.five(first).copy(percent = Field(Knowledge.KNOWN, value),
                point = SyntheticHistory.five(first).point!!.copy(usedPercent = value)))
            val series = HistoryPlotInputs.project(graph(listOf(bad))).fiveHour
            assertEquals(HistoryPlotContent.SINGLE_POINT, series.content)
            assertEquals(PlotPosition.Unavailable(PlotRenderProblem.OUTSIDE_VALUE_DOMAIN), series.samples.single().measured!!.position)
            assertSame(value, series.samples.single().measured!!.source.usedPercent)
            assertTrue(series.segments.isEmpty())
        }
    }

    @Test fun snapshotMetadataTicksCannotMoveMeasurementsComparisonsOrNominalEndpoints() {
        val admitted = append()
        val original = graph(listOf(admitted.entry))
        val tick = HistoryGraphSnapshot(HistoryReadiness.READY, original.generation, original.partition,
            original.query, original.storage, live = HistoryLiveMetadata(HistoryEndpointMetadata(at, at, 200, stale = true), refreshing = true))
        val before = HistoryPlotInputs.project(original).fiveHour
        val after = HistoryPlotInputs.project(tick).fiveHour
        assertSame(before.samples.single().measured!!.source, after.samples.single().measured!!.source)
        assertEquals(before.samples.single().measured!!.position, after.samples.single().measured!!.position)
        assertEquals(before.samples.single().delta!!.exactText, after.samples.single().delta!!.exactText)
        assertEquals(before.samples.single().delta!!.observedAt, after.samples.single().delta!!.observedAt)
        assertEquals(before.references.single().source.nominalStartAt, after.references.single().source.nominalStartAt)
        assertEquals(before.references.single().source.end, after.references.single().source.end)
        assertEquals(1, after.segments.single().points.size)
        assertTrue(tick.live.refreshing)
        assertTrue(tick.live.usage.stale)
    }

    @Test fun sameHandcraftedSegmentCannotJoinDifferentWindowIdentityOrUnkeyedMeasurements() {
        val first = append()
        val next = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        val old = SyntheticHistory.five(first).point!!.segment
        val nextWindow = SyntheticHistory.five(next)
        val changed = HistorySegment(old.id, old.window!!.copy(resetAt = at), old.breaks)
        val changedEntry = replace(next.entry, nextWindow.copy(point = nextWindow.point!!.copy(segment = changed)))
        val plot = HistoryPlotInputs.project(graph(listOf(first.entry, changedEntry)))
        assertEquals(listOf(1, 1), plot.fiveHour.segments.map { it.points.size })
        assertEquals(DistributionUnavailable.InvalidFact.RESET,
            (plot.fiveHour.samples.last().window.reference as DistributionReference.Unavailable).reason)
        val unkeyed = HistorySegment(old.id, null, listOf(BreakReason.UNKEYED_RESET))
        val firstEntry = replace(first.entry, SyntheticHistory.five(first).copy(point = SyntheticHistory.five(first).point!!.copy(segment = unkeyed)))
        val secondEntry = replace(next.entry, nextWindow.copy(point = nextWindow.point.copy(segment = unkeyed)))
        val isolated = HistoryPlotInputs.project(graph(listOf(firstEntry, secondEntry)))
        assertEquals(listOf(1, 1), isolated.fiveHour.segments.map { it.points.size })
        assertTrue(isolated.fiveHour.references.isEmpty())
        assertEquals(0.0, position(isolated.fiveHour.samples.first().measured!!.position).x, 0.0)
        assertEquals(1.0, position(isolated.fiveHour.samples.last().measured!!.position).x, 0.0)
    }

    @Test fun collectionsAreDetachedImmutableAndTheAcceptedPageIsBounded() {
        val entries = mutableListOf<HistoryEntry>()
        var cursor = HistoryCursor(partition)
        repeat(256) { index ->
            val admitted = append(cursor, usage(secondary = weeklyInput(), observedAt = at.plusSeconds(index * 60L)), 1000 + index * 60_000L)
            entries.add(admitted.entry)
            cursor = admitted.cursor
        }
        val source = graph(entries)
        entries.clear()
        val plot = HistoryPlotInputs.project(source)
        for (series in listOf(plot.fiveHour, plot.weekly)) {
            assertEquals(256, series.samples.size)
            assertEquals(256, series.segments.single().points.size)
            assertImmutable(series.samples)
            assertImmutable(series.segments)
            assertImmutable(series.segments.single().points)
            assertImmutable(series.deltaSegments)
            assertImmutable(series.deltaSegments.first().points)
            assertImmutable(series.references)
        }
        val tooLargeForQuery = graph(source.storage!!.entries, query = HistoryGraphQuery(limit = 255), storageLimit = 256)
        assertThrows(IllegalArgumentException::class.java) { HistoryPlotInputs.project(tooLargeForQuery) }
        val duplicate = replace(source.storage.entries.first(), source.storage.entries.first().windows.first(), duplicate = true)
        assertThrows(IllegalArgumentException::class.java) { HistoryPlotInputs.project(graph(listOf(duplicate))) }
    }

    private fun graph(
        entries: List<HistoryEntry>, query: HistoryGraphQuery = HistoryGraphQuery(),
        readiness: HistoryReadiness = HistoryReadiness.READY, storageLimit: Int = query.limit,
    ): HistoryGraphSnapshot = HistoryGraphSnapshot(readiness, query = query,
        storage = HistoryReadSnapshot(HistoryReadQuery(partition, storageLimit, query.after), entries, entries.lastOrNull()?.id, false))

    private fun weeklyInput() = window(Input.Value(BigDecimal("62.500")), Input.Value(604800L), Input.Value(1_800_302_400L))

    private fun gap(previous: HistoryReduction.Applied, reason: HistoryGap): HistoryReduction.Applied =
        WindowHistory.append(previous.cursor, HistoryAdmission(partition, previous.cursor.nextId()!!,
            HistoryClock(epoch, 30_000), HistoryEvent.Gap(reason))) as HistoryReduction.Applied

    private fun position(source: PlotPosition) = source as PlotPosition.Available

    private fun assertUnavailable(sample: HistoryPlotSample, eligibility: BaselineEligibility) {
        val reason = DistributionUnavailable.Ineligible(eligibility)
        assertEquals(DistributionReference.Unavailable(reason), sample.window.reference)
        assertEquals(reason, (sample.window.comparison as UsedComparison.Unavailable).reason)
        assertNull(sample.delta)
    }

    private fun assertImmutable(values: List<*>) {
        assertThrows(UnsupportedOperationException::class.java) { (values as MutableList<*>).clear() }
    }

    private fun replace(entry: HistoryEntry, five: HistoryWindow, duplicate: Boolean = false): HistoryEntry =
        HistoryEntry(entry.partition, entry.id, entry.clock, entry.observedAt, entry.gap,
            if (duplicate) listOf(five, five) else entry.windows.map { if (it.kind == WindowKind.FIVE_HOUR) five else it },
            entry.slots, entry.allowed, entry.limitReached)
}
