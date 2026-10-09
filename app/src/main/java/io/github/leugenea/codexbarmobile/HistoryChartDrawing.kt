package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*

internal data class HistoryChartLine(val start: PlotPosition.Available, val end: PlotPosition.Available)
internal data class HistoryChartDrawing(
    val markers: List<PlotPosition.Available>,
    val measured: List<HistoryChartLine>,
    val nominal: List<HistoryChartLine>,
)

/** Only supplied page-local runs authorize edges. Reference endpoints never become markers.
 * No sorting, clipping, interpolation, quota math or cross-snapshot cache lives here.
 */
internal fun historyChartDrawing(series: HistoryPlotSeries): HistoryChartDrawing {
    val runs = series.segments.map { run -> run.points.map { chartPosition(it.position) } }
    return HistoryChartDrawing(
        runs.flatten(),
        runs.flatMap { run -> run.zipWithNext { first, last -> HistoryChartLine(first, last) } },
        series.references.map { HistoryChartLine(chartPosition(it.start), chartPosition(it.end)) },
    )
}

/** Invalid handcrafted geometry is rejected, never silently clamped into valid-looking usage. */
private fun chartPosition(position: PlotPosition): PlotPosition.Available {
    require(position is PlotPosition.Available)
    require(position.x.isFinite() && position.y.isFinite())
    require(position.x in 0.0..1.0 && position.y in 0.0..1.0)
    return position
}
