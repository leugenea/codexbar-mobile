package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.history.SyntheticHistory.append
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.at
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.partition
import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.Knowledge
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Rendering fractions have literal oracles; exact sources never depend on Double geometry. */
class HistoryPlotCoordinatesTest {
    @Test fun noTimeAndSingleInstantDomainsDoNotInventDurationOrEndpoints() {
        assertNull(HistoryPlotCoordinates.timeDomain(emptyList()))
        val exact = at.plusNanos(1)
        val time = HistoryPlotCoordinates.timeDomain(listOf(exact, exact))!!
        assertEquals(PlotTimeExtent.SINGLE_INSTANT, time.extent)
        assertEquals(exact, time.first)
        assertEquals(exact, time.last)
        assertPosition(0.5, 0.12375, position(time, exact, BigDecimal("12.375")))
        assertThrows(IllegalArgumentException::class.java) { PlotTimeDomain(exact, at) }
        assertThrows(IllegalArgumentException::class.java) { PlotValueDomain(1, 1, HistoryUnit.PERCENT) }
    }

    @Test fun fullInstantRangeMapsFiniteEndpointsAndAnInteriorWithoutScalarOverflow() {
        val domain = PlotTimeDomain(Instant.MIN, Instant.MAX)
        assertEquals(PlotTimeExtent.SPAN, domain.extent)
        assertPosition(0.0, 0.0, position(domain, Instant.MIN, BigDecimal.ZERO))
        assertPosition(1.0, 1.0, position(domain, Instant.MAX, BigDecimal("100")))
        val middle = position(domain, Instant.EPOCH, BigDecimal("50"))
        assertTrue(middle.x in 0.49..0.51)
        assertEquals(0.5, middle.y, 0.0)
        assertTrue(middle.x.isFinite())
        assertTrue(middle.y.isFinite())
        val chosen = HistoryPlotCoordinates.timeDomain(listOf(Instant.MAX, Instant.EPOCH, Instant.MIN))!!
        assertEquals(domain, chosen)
    }

    @Test fun nanosecondSpanAndSubsecondTimesKeepExactDomainWithoutRoundingToMillis() {
        val domain = PlotTimeDomain(at, at.plusNanos(2))
        assertPosition(0.0, 0.0, position(domain, at, BigDecimal.ZERO))
        assertPosition(0.5, 0.5, position(domain, at.plusNanos(1), BigDecimal("50")))
        assertPosition(1.0, 1.0, position(domain, at.plusNanos(2), BigDecimal("100")))
        val fraction = PlotTimeDomain(at.plusNanos(250_000_000), at.plusSeconds(1).plusNanos(250_000_000))
        assertPosition(0.25, 0.25, position(fraction, at.plusNanos(500_000_000), BigDecimal("25")))
    }

    @Test fun valueEndpointsAndSignedDeltaUseSeparateFiniteUnitDomains() {
        val domain = HistoryPlotDomain(PlotTimeDomain(at, at), HistoryPlotCoordinates.delta)
        for ((value, y) in listOf("-100" to 0.0, "0" to 0.5, "100" to 1.0, "-67.625" to 0.161875)) {
            val point = HistoryPlotCoordinates.position(domain, at, BigDecimal(value)) as PlotPosition.Available
            assertPosition(0.5, y, point)
        }
        assertEquals(HistoryUnit.PERCENTAGE_POINTS, domain.value.unit)
        assertEquals(PlotPosition.Unavailable(PlotRenderProblem.OUTSIDE_VALUE_DOMAIN),
            HistoryPlotCoordinates.position(domain, at, BigDecimal("-100.0001")))
        assertEquals(PlotPosition.Unavailable(PlotRenderProblem.OUTSIDE_VALUE_DOMAIN),
            HistoryPlotCoordinates.position(domain, at, BigDecimal("100.0001")))
    }

    @Test fun extremeScalesRemainExactWhileNonzeroUnderflowIsExplicitRenderingOnly() {
        val domain = PlotTimeDomain(at, at)
        for (scale in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            val zero = BigDecimal(BigInteger.ZERO, scale)
            val render = position(domain, at, zero)
            assertPosition(0.5, 0.0, render)
            assertEquals(PlotRenderDetail.APPROXIMATE, render.detail)
            val source = SyntheticHistory.five(append()).point!!.copy(usedPercent = zero)
            val plotted = HistoryPlotPoint(source, render)
            assertSame(zero, plotted.source.usedPercent)
            assertEquals(zero.toString(), plotted.exactPercentText)
            assertEquals(scale, plotted.source.usedPercent.scale())
        }
        for (value in listOf(BigDecimal("1E-10000"), BigDecimal(BigInteger.ONE, Int.MAX_VALUE))) {
            val render = position(domain, at, value)
            assertPosition(0.5, 0.0, render)
            assertEquals(PlotRenderDetail.NONZERO_UNDERFLOW, render.detail)
            assertTrue(value.signum() > 0)
        }
    }

    @Test fun renderCapacityAndOutOfRangeNeverReturnClampedOrInfiniteValues() {
        val domain = HistoryPlotDomain(PlotTimeDomain(at, at), HistoryPlotCoordinates.percent)
        for (value in listOf(BigDecimal("1E+10000"), BigDecimal(BigInteger.ONE, Int.MIN_VALUE), BigDecimal("-0.01"))) {
            assertEquals(PlotPosition.Unavailable(PlotRenderProblem.OUTSIDE_VALUE_DOMAIN),
                HistoryPlotCoordinates.position(domain, at, value))
        }
        val atBoundary = BigDecimal("0." + "1".repeat(1024))
        assertTrue(HistoryPlotCoordinates.position(domain, at, atBoundary) is PlotPosition.Available)
        val beyond = BigDecimal("0." + "1".repeat(1025))
        assertEquals(PlotPosition.Unavailable(PlotRenderProblem.DECIMAL_CAPACITY),
            HistoryPlotCoordinates.position(domain, at, beyond))
        assertThrows(IllegalArgumentException::class.java) { HistoryPlotCoordinates.position(domain, at.plusNanos(1), BigDecimal.ZERO) }
    }

    @Test fun exactComparisonCanSurviveItsOwnRenderCapacityAndCannotBecomeAZeroDelta() {
        val admitted = append()
        val original = SyntheticHistory.five(admitted)
        val exact = BigDecimal("1E-1024")
        val window = original.copy(percent = Field(Knowledge.KNOWN, exact), point = original.point!!.copy(usedPercent = exact))
        val entry = HistoryEntry(partition, admitted.entry.id, admitted.entry.clock, at, null,
            listOf(window), admitted.entry.slots, admitted.entry.allowed, admitted.entry.limitReached)
        val page = HistoryReadSnapshot(HistoryReadQuery(partition, 1), listOf(entry), ObservationId(1), false)
        val series = HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.READY, storage = page)).fiveHour
        val sample = series.samples.single()
        assertEquals(PlotRenderDetail.NONZERO_UNDERFLOW, (sample.measured!!.position as PlotPosition.Available).detail)
        assertEquals(PlotPosition.Unavailable(PlotRenderProblem.DECIMAL_CAPACITY), sample.delta!!.position)
        assertEquals(BigDecimal("-79." + "9".repeat(1024)), sample.delta.source.deltaPercentagePoints)
        assertEquals(sample.delta.source.deltaPercentagePoints.toString(), sample.delta.exactText)
        assertSame(exact, sample.measured.source.usedPercent)
        assertEquals(1, series.segments.size)
        assertTrue(series.deltaSegments.isEmpty())
        assertEquals(1, series.references.size)
    }

    private fun position(time: PlotTimeDomain, at: Instant, value: BigDecimal): PlotPosition.Available =
        HistoryPlotCoordinates.position(HistoryPlotDomain(time, HistoryPlotCoordinates.percent), at, value) as PlotPosition.Available

    private fun assertPosition(x: Double, y: Double, actual: PlotPosition.Available) {
        assertEquals(x, actual.x, 1E-15)
        assertEquals(y, actual.y, 1E-15)
        assertTrue(actual.x.isFinite())
        assertTrue(actual.y.isFinite())
    }
}
