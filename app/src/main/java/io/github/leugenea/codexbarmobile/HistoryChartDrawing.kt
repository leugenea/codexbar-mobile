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

/** Only supplied runs authorize measured edges; a singleton gets a line-width tick.
 * Reference endpoints never become observations. No cross-snapshot cache lives here.
 */
internal fun historyChartDrawing(series: HistoryPlotSeries): HistoryChartDrawing {
    val runs = series.segments.map { run -> run.points.map { chartPosition(it.position) } }
    val observations = runs.flatten()
    return HistoryChartDrawing(
        observations,
        runs.flatMap { run -> run.zipWithNext { first, last -> HistoryChartLine(first, last) } },
        series.references.map { HistoryChartLine(chartPosition(it.start), chartPosition(it.end)) },
        runs.filter { it.size == 1 }.map { it.single() },
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
