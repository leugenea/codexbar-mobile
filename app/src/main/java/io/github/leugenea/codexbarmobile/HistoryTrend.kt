package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.PlotPosition
import io.github.leugenea.codexbarmobile.history.PlotRenderDetail

/** Ordinary least squares of used percent against time (not observation ordinal).
 * Affine-normalized time/percent coordinates preserve the same fit. Centering avoids
 * subtracting large timestamp squares. Fit every shown observation, across run breaks;
 * draw across the entire plot time domain (window start..reset when known), clipping
 * the paint to the plot rectangle, not clamping the regression.
 */
internal fun historyTrend(points: List<PlotPosition.Available>): HistoryChartLine? {
    if (points.size < 2) return null
    val first = points.minOf { it.x }
    val last = points.maxOf { it.x }
    if (first == last) return null
    val meanX = points.sumOf { it.x } / points.size
    val meanY = points.sumOf { it.y } / points.size
    var covariance = 0.0
    var variance = 0.0
    for (point in points) {
        val dx = point.x - meanX
        covariance += dx * (point.y - meanY)
        variance += dx * dx
    }
    if (variance == 0.0) return null
    val slope = covariance / variance
    fun at(x: Double) = PlotPosition.Available(x, meanY + slope * (x - meanX), PlotRenderDetail.APPROXIMATE)
    return HistoryChartLine(at(0.0), at(1.0))
}
