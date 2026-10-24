package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Issue 184 geometry regressions; exact source facts and run provenance stay unchanged. */
class HistoryWindowChartTest {
    @Test fun interiorObservationsUseFullWindowAndTrendEndpointsNotMeasurementEdges() {
        val first = SyntheticHistory.append()
        val next = SyntheticHistory.append(first.cursor,
            SyntheticHistory.usage(observedAt = SyntheticHistory.at.plusSeconds(900)), 901_000)
        val series = plot(listOf(first.entry, next.entry))
        assertEquals(PlotTimeDomain(Instant.ofEpochSecond(1_799_985_600), Instant.ofEpochSecond(1_800_003_600)),
            series.measuredDomain!!.time)
        val drawing = historyChartDrawing(series)
        assertEquals(listOf(0.8, 0.85), drawing.observations.map { it.x })
        assertEquals(0.0, drawing.trend!!.start.x, 0.0)
        assertEquals(1.0, drawing.trend.end.x, 0.0)
        assertEquals(1, drawing.measured.size)
        assertEquals(2, series.segments.size) // Interval break is retained as provenance, not a paint gap.
    }

    @Test fun singleKeyedPointWithoutBaselineStillHasFullWindowAxisAndVisibleTick() {
        val result = SyntheticHistory.append()
        val entry = result.entry
        val window = entry.windows.first { it.kind == WindowKind.FIVE_HOUR }
        val unavailable = window.copy(baseline = BaselineEligibility.CLOCK_UNVERIFIED)
        val changed = HistoryEntry(entry.partition, entry.id, entry.clock, entry.observedAt, entry.gap,
            entry.windows.map { if (it.kind == window.kind) unavailable else it }, entry.slots, entry.allowed, entry.limitReached)
        val series = plot(listOf(changed))
        assertTrue(series.references.isEmpty())
        assertTrue(DashboardPresentation.hasChart(series))
        assertEquals(PlotTimeExtent.SPAN, series.measuredDomain!!.time.extent)
        val drawing = historyChartDrawing(series)
        assertEquals(0.8, drawing.isolated.single().x, 0.0)
        assertNull(drawing.trend)
        assertTrue(drawing.measured.isEmpty())
    }

    @Test fun unknownBoundsFallBackToMeasuredRangeIncludingCenteredSingleton() {
        val first = SyntheticHistory.append(usage = SyntheticHistory.usage(SyntheticHistory.window(absolute = Input.Missing)))
        val next = SyntheticHistory.append(first.cursor,
            SyntheticHistory.usage(SyntheticHistory.window(absolute = Input.Missing), observedAt = SyntheticHistory.at.plusSeconds(60)), 61_000)
        val singleton = plot(listOf(first.entry))
        assertEquals(0.5, historyChartDrawing(singleton).isolated.single().x, 0.0)
        val series = plot(listOf(first.entry, next.entry))
        assertEquals(PlotTimeDomain(SyntheticHistory.at, SyntheticHistory.at.plusSeconds(60)), series.measuredDomain!!.time)
        assertEquals(listOf(0.0, 1.0), historyChartDrawing(series).observations.map { it.x })
    }

    @Test fun continuousPolylineSortsAcrossBackwardsRunsWithoutRewritingAdmissionOrder() {
        val first = SyntheticHistory.append()
        val before = SyntheticHistory.append(first.cursor,
            SyntheticHistory.usage(observedAt = SyntheticHistory.at.minusSeconds(60)), 61_000)
        val series = plot(listOf(first.entry, before.entry))
        assertEquals(listOf(SyntheticHistory.at, SyntheticHistory.at.minusSeconds(60)),
            series.samples.map { it.measured!!.source.observedAt })
        val drawing = historyChartDrawing(series)
        assertTrue(drawing.observations[0].x < drawing.observations[1].x)
        assertEquals(listOf(HistoryChartLine(drawing.observations[0], drawing.observations[1])), drawing.measured)
    }

    @Test fun overflowAndOutOfWindowLegacyFactsFallBackWithoutClampingOrCrashing() {
        val identity = SyntheticHistory.append().entry.windows.first().point!!.segment.window!!
        val observed = listOf(SyntheticHistory.at, SyntheticHistory.at.plusSeconds(60))
        val expected = PlotTimeDomain(observed.first(), observed.last())
        for (window in listOf(identity.copy(resetAt = Instant.MIN), identity.copy(resetAt = observed.first().minusSeconds(1)),
            identity.copy(durationSeconds = 0))) {
            assertEquals(expected, historyPlotTimeDomain(window, observed, emptyList()))
        }
        assertNull(historyPlotTimeDomain(identity, emptyList(), emptyList()))
        val outside = identity.copy(resetAt = observed.first().minusSeconds(1))
        val current = historyPlotTimeDomain(outside, observed, emptyList(), authoritative = true)!!
        assertEquals(outside.resetAt, current.last)
        assertEquals(PlotPosition.Unavailable(PlotRenderProblem.OUTSIDE_TIME_DOMAIN),
            HistoryPlotCoordinates.position(HistoryPlotDomain(current, HistoryPlotCoordinates.percent), observed.first(), java.math.BigDecimal.TEN))
    }

    private fun plot(entries: List<HistoryEntry>): HistoryPlotSeries {
        val page = HistoryReadSnapshot(HistoryReadQuery(SyntheticHistory.partition, 256), entries, entries.last().id, false)
        return HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.READY, storage = page)).fiveHour
    }
}
