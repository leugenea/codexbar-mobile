package io.github.leugenea.codexbarmobile.history

import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.Instant

/** Rendering arithmetic only. Original BigDecimal/Instant facts and M4a-3 math stay untouched. */
internal object HistoryPlotCoordinates {
    private const val MAX_RENDER_DIGITS = 1024
    val percent = PlotValueDomain(0, 100, HistoryUnit.PERCENT)
    val delta = PlotValueDomain(-100, 100, HistoryUnit.PERCENTAGE_POINTS)

    fun timeDomain(instants: Collection<Instant>): PlotTimeDomain? {
        if (instants.isEmpty()) return null
        return PlotTimeDomain(instants.min(), instants.max())
    }

    fun position(domain: HistoryPlotDomain, at: Instant, value: BigDecimal): PlotPosition {
        if (value.precision() > MAX_RENDER_DIGITS) return PlotPosition.Unavailable(PlotRenderProblem.DECIMAL_CAPACITY)
        val range = domain.value
        if (value < BigDecimal.valueOf(range.minimum.toLong()) || value > BigDecimal.valueOf(range.maximum.toLong())) {
            return PlotPosition.Unavailable(PlotRenderProblem.OUTSIDE_VALUE_DOMAIN)
        }
        require(at >= domain.time.first && at <= domain.time.last)
        val approximate = value.toDouble()
        val y = (approximate - range.minimum) / (range.maximum.toDouble() - range.minimum)
        val detail = if (value.signum() != 0 && approximate == 0.0) PlotRenderDetail.NONZERO_UNDERFLOW
            else PlotRenderDetail.APPROXIMATE
        return PlotPosition.Available(timeFraction(domain.time, at), y, detail)
    }

    private fun timeFraction(domain: PlotTimeDomain, at: Instant): Double {
        if (domain.extent == PlotTimeExtent.SINGLE_INSTANT) return 0.5
        if (at == domain.first) return 0.0
        if (at == domain.last) return 1.0
        // Duration seconds fit Long even across Instant.MIN..MAX; toNanos/toMillis would overflow.
        val elapsed = seconds(Duration.between(domain.first, at))
        val span = seconds(Duration.between(domain.first, domain.last))
        return elapsed.divide(span, MathContext.DECIMAL128).toDouble()
    }

    private fun seconds(duration: Duration): BigDecimal = BigDecimal.valueOf(duration.seconds)
        .add(BigDecimal.valueOf(duration.nano.toLong(), 9))
}
