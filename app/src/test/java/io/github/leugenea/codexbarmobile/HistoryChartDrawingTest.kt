package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.append
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.at
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.epoch
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.partition
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.usage
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.window
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Deterministic renderer boundary tests. No Android rendering or local execution claim. */
class HistoryChartDrawingTest {
    @Test fun singlePointAndNominalEndpointsHaveSeparateDrawingRoles() {
        val result = append()
        val series = plot(listOf(result.entry)).fiveHour
        val drawing = historyChartDrawing(series)
        assertEquals(listOf(series.samples.single().measured!!.position), drawing.markers)
        assertTrue(drawing.measured.isEmpty())
        assertEquals(listOf(HistoryChartLine(series.references.single().start as PlotPosition.Available,
            series.references.single().end as PlotPosition.Available)), drawing.nominal)
        assertEquals(1, drawing.markers.size)
        assertEquals(0.8, drawing.markers.single().x, 0.0)
    }

    @Test fun straightEdgesFollowOnlyTheSuppliedRunAndKeepEveryMarker() {
        val first = append()
        val second = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        val series = plot(listOf(first.entry, second.entry)).fiveHour
        val drawing = historyChartDrawing(series)
        assertEquals(2, drawing.markers.size)
        assertEquals(listOf(HistoryChartLine(drawing.markers[0], drawing.markers[1])), drawing.measured)
        assertEquals(1, drawing.nominal.size)
        assertEquals(at.plusSeconds(60), series.samples.last().measured!!.source.observedAt)
    }

    @Test fun gapCorrectionResetAndOrdinalHoleNeverBecomeAnEdge() {
        val first = append()
        val gap = WindowHistory.append(first.cursor, HistoryAdmission(partition, ObservationId(2),
            HistoryClock(epoch, 31_000), HistoryEvent.Gap(HistoryGap.BACKGROUND))) as HistoryReduction.Applied
        val afterGap = append(gap.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        val corrected = append(first.cursor, usage(window(Input.Value(BigDecimal("3"))), observedAt = at.plusSeconds(60)), 61_000)
        val reset = append(first.cursor, usage(window(absolute = Input.Value(at.epochSecond + 7200)),
            observedAt = at.plusSeconds(60)), 61_000)
        val hole = append(afterGap.cursor, usage(observedAt = at.plusSeconds(120)), 121_000)
        for (entries in listOf(listOf(first.entry, gap.entry, afterGap.entry),
            listOf(first.entry, corrected.entry), listOf(first.entry, reset.entry), listOf(first.entry, hole.entry))) {
            val drawing = historyChartDrawing(plot(entries).fiveHour)
            assertEquals(2, drawing.markers.size)
            assertTrue(drawing.measured.isEmpty())
        }
    }

    @Test fun pageRetentionAndSnapshotBoundariesCannotAcquireAPrecedingEdge() {
        val first = append()
        val second = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        for (query in listOf(HistoryGraphQuery(after = ObservationId(1)), HistoryGraphQuery())) {
            val source = plot(listOf(second.entry), query)
            val drawing = historyChartDrawing(source.fiveHour)
            assertEquals(1, drawing.markers.size)
            assertTrue(drawing.measured.isEmpty())
            assertEquals(ObservationId(1), source.fiveHour.segments.single().source.id.first)
        }
        assertTrue(historyChartDrawing(plot(listOf(first.entry)).fiveHour).measured.isEmpty())
    }

    @Test fun unkeyedAndUnknownGeometryStayIsolatedWithoutZeroOrBridge() {
        val first = append(usage = usage(window(absolute = Input.Missing)))
        val second = append(first.cursor, usage(window(absolute = Input.Missing), observedAt = at.plusSeconds(60)), 61_000)
        val drawing = historyChartDrawing(plot(listOf(first.entry, second.entry)).fiveHour)
        assertEquals(2, drawing.markers.size)
        assertTrue(drawing.measured.isEmpty())
        assertTrue(drawing.nominal.isEmpty())
        for (source in listOf(plot(emptyList()), HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.LOADING)))) {
            assertEquals(HistoryChartDrawing(emptyList(), emptyList(), emptyList()), historyChartDrawing(source.fiveHour))
        }
    }

    @Test fun invalidHandcraftedPositionsFailClosedInsteadOfBeingClampedOrSkipped() {
        val original = plot(listOf(append().entry)).fiveHour
        val point = original.samples.single().measured!!
        for (position in listOf(PlotPosition.Unavailable(PlotRenderProblem.DECIMAL_CAPACITY),
            available(Double.NaN, 0.5), available(0.5, Double.POSITIVE_INFINITY), available(-0.1, 0.5),
            available(0.5, 1.1))) {
            val bad = HistoryPlotPoint(point.source, position)
            val series = HistoryPlotSeries(original.kind, original.content, original.measuredDomain, original.deltaDomain,
                original.samples, listOf(HistoryPlotSegment(point.source.segment, listOf(bad))), emptyList(), emptyList())
            assertThrows(IllegalArgumentException::class.java) { historyChartDrawing(series) }
        }
    }

    @Test fun coincidentFractionsAndNonzeroUnderflowNeverMergeOrRoundExactFacts() {
        val original = plot(listOf(append().entry)).fiveHour
        val point = original.samples.single().measured!!
        val position = PlotPosition.Available(0.5, 0.0, PlotRenderDetail.NONZERO_UNDERFLOW)
        val one = HistoryPlotPoint(point.source.copy(usedPercent = BigDecimal("1E-10000")), position)
        val two = HistoryPlotPoint(point.source.copy(observation = ObservationId(2), usedPercent = BigDecimal("2E-10000")), position)
        val series = HistoryPlotSeries(original.kind, original.content, original.measuredDomain, original.deltaDomain,
            emptyList(), listOf(HistoryPlotSegment(point.source.segment, listOf(one, two))), emptyList(), emptyList())
        val drawing = historyChartDrawing(series)
        assertEquals(listOf(position, position), drawing.markers)
        assertEquals(1, drawing.measured.size)
        assertEquals("1E-10000", one.exactPercentText)
        assertEquals("2E-10000", two.exactPercentText)
    }

    private fun available(x: Double, y: Double) = PlotPosition.Available(x, y, PlotRenderDetail.APPROXIMATE)

    private fun plot(entries: List<HistoryEntry>, query: HistoryGraphQuery = HistoryGraphQuery()): HistoryPlotSnapshot {
        val page = HistoryReadSnapshot(query.storage(partition), entries, entries.lastOrNull()?.id, false, emptySet(), emptyMap())
        return HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.READY, HistoryGeneration(), partition, query, page))
    }
}
