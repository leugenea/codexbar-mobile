package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.PlotPosition
import io.github.leugenea.codexbarmobile.history.PlotRenderDetail
import org.junit.Assert.*
import org.junit.Test

class HistoryTrendTest {
    @Test fun emptyObservationsHaveNoTrend() {
        assertNull(historyTrend(emptyList()))
    }

    @Test fun singleObservationHasNoTrend() {
        assertNull(historyTrend(listOf(point(0.5, 0.8))))
    }

    @Test fun twoObservationsFitExactlyAndExtendAcrossFullDomain() {
        val trend = historyTrend(listOf(point(0.2, 0.3), point(0.8, 0.7)))!!
        assertLine(trend, 0.0, 1.0 / 6.0, 1.0, 5.0 / 6.0)
    }

    @Test fun identicalTimesWithVerticalSpreadHaveNoTrend() {
        assertNull(historyTrend(listOf(point(0.4, 0.0), point(0.4, 0.5), point(0.4, 1.0))))
    }

    @Test fun flatObservationsFitAHorizontalLine() {
        assertLine(historyTrend(listOf(point(0.1, 0.6), point(0.3, 0.6), point(0.9, 0.6)))!!,
            0.0, 0.6, 1.0, 0.6)
    }

    @Test fun risingFitUsesTimeSpacingRatherThanObservationIndex() {
        // Unequal time intervals must not be replaced with ordinal spacing.
        assertLine(historyTrend(listOf(point(0.0, 0.0), point(0.25, 0.25), point(1.0, 1.0)))!!,
            0.0, 0.0, 1.0, 1.0)
    }

    @Test fun fallingFitHandlesUnsortedTimesWithoutChangingObservations() {
        val points = listOf(point(1.0, 0.1), point(0.0, 0.9), point(0.5, 0.5))
        assertLine(historyTrend(points)!!, 0.0, 0.9, 1.0, 0.1)
        assertEquals(1.0, points.first().x, 0.0)
    }

    @Test fun verticalSpreadAtRepeatedTimesFitsAllPointsWithoutClampingEndpoints() {
        assertLine(historyTrend(listOf(point(0.0, 0.2), point(0.5, 0.0), point(0.5, 1.0), point(1.0, 0.8)))!!,
            0.0, 0.2, 1.0, 0.8)
        // The fit can exceed the percent axis. The Canvas clips paint, not the regression.
        assertLine(historyTrend(listOf(point(0.0, 0.0), point(0.1, 1.0), point(1.0, 1.0)))!!,
            0.0, 0.4450549450549451, 1.0, 1.0494505494505495)
    }

    @Test fun fullWindowFitRemainsUnclampedForCanvasClipping() {
        val line = historyTrend(listOf(point(0.4, 0.2), point(0.6, 0.8)))!!
        assertLine(line, 0.0, -1.0, 1.0, 2.0)
        // Visible intersections of this same fit are (1/3, 0) and (2/3, 1).
        assertEquals(0.0, line.start.y + (line.end.y - line.start.y) / 3, 1e-12)
        assertEquals(1.0, line.start.y + (line.end.y - line.start.y) * 2 / 3, 1e-12)
    }

    private fun point(x: Double, y: Double) = PlotPosition.Available(x, y, PlotRenderDetail.APPROXIMATE)

    private fun assertLine(line: HistoryChartLine, x1: Double, y1: Double, x2: Double, y2: Double) {
        assertEquals(x1, line.start.x, 1e-12)
        assertEquals(y1, line.start.y, 1e-12)
        assertEquals(x2, line.end.x, 1e-12)
        assertEquals(y2, line.end.y, 1e-12)
    }
}
