package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*

internal data class HistoryChartLine(val start: PlotPosition.Available, val end: PlotPosition.Available)
internal data class HistoryChartDrawing(
    val observations: List<PlotPosition.Available>,
    val measured: List<HistoryChartLine>,
    val nominal: List<HistoryChartLine>,
    val isolated: List<PlotPosition.Available> = emptyList(),
    val trend: HistoryChartLine? = null,
)

/** One continuous time-ordered polyline through the shown renderable measurements.
 * Runs retain provenance, not paint breaks. A lone observation gets a line-width tick.
 * Reference endpoints never become observations. No cross-snapshot cache lives here.
 */
internal fun historyChartDrawing(series: HistoryPlotSeries): HistoryChartDrawing {
    val observations = series.segments.flatMap { it.points }.sortedBy { it.source.observedAt }
        .map { chartPosition(it.position) }
    return HistoryChartDrawing(
        observations,
        observations.zipWithNext { first, last -> HistoryChartLine(first, last) },
        series.references.map { HistoryChartLine(chartPosition(it.start), chartPosition(it.end)) },
        if (observations.size == 1) observations else emptyList(),
        historyTrend(observations),
    )
}

/** Invalid handcrafted measured geometry is rejected, never silently clamped. */
private fun chartPosition(position: PlotPosition): PlotPosition.Available {
    require(position is PlotPosition.Available)
    require(position.x.isFinite() && position.y.isFinite())
    require(position.x in 0.0..1.0 && position.y in 0.0..1.0)
    return position
}
