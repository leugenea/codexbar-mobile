package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Reusable bounded page component. The host supplies scrolling and optional kind selection.
 * Exact facts, reset context and observed-only delta have ONE accessible owner: existing text.
 */
@Composable
internal fun HistoryChartComponent(
    plot: HistoryPlotSnapshot, now: Instant?, zone: ZoneId, locale: Locale, modifier: Modifier = Modifier,
    kind: WindowKind? = null,
) {
    Column(modifier.fillMaxWidth().testTag("history-charts"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (kind == null || kind == WindowKind.FIVE_HOUR) HistoryWindowChart(plot.fiveHour, zone, locale, "history-chart-five-hour")
        if (kind == null || kind == WindowKind.WEEKLY) HistoryWindowChart(plot.weekly, zone, locale, "history-chart-weekly")
        HistoryTextComponent(plot, now, zone, locale, kind = kind)
    }
}

@Composable
private fun HistoryWindowChart(series: HistoryPlotSeries, zone: ZoneId, locale: Locale, tag: String) {
    val drawing = historyChartDrawing(series)
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_chart_title, stringResource(historyStateResource(series.kind))),
                Modifier.testTag("$tag-title").semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(historyStateResource(series.content)), Modifier.testTag("$tag-content"))
            Text(stringResource(R.string.history_chart_legend), Modifier.testTag("$tag-legend"))
            Text(stringResource(R.string.history_chart_counts, drawing.observations.size, drawing.measured.size, drawing.nominal.size),
                Modifier.testTag("$tag-counts"))
            HistoryGeometryNotes(series, tag)
            val domain = series.measuredDomain
            if (domain == null) {
                Text(stringResource(R.string.history_chart_no_geometry), Modifier.testTag("$tag-no-geometry"))
            } else {
                HistoryPercentPlot(drawing, tag)
                HistoryAxisTimes(domain.time.first, domain.time.last, zone, locale, tag)
            }
        }
    }
}

@Composable
private fun HistoryGeometryNotes(series: HistoryPlotSeries, tag: String) {
    val positions = series.samples.mapNotNull { it.measured?.position }
    positions.filterIsInstance<PlotPosition.Unavailable>().map { it.reason }.distinct().forEach { problem ->
        val resource = when (problem) {
            PlotRenderProblem.DECIMAL_CAPACITY -> R.string.history_chart_decimal_capacity
            PlotRenderProblem.OUTSIDE_VALUE_DOMAIN -> R.string.history_chart_outside_domain
        }
        Text(stringResource(resource), Modifier.testTag("$tag-geometry-${problem.name}"))
    }
    if (positions.filterIsInstance<PlotPosition.Available>().any { it.detail == PlotRenderDetail.NONZERO_UNDERFLOW }) {
        Text(stringResource(R.string.history_chart_underflow), Modifier.testTag("$tag-underflow"))
    }
    if (series.references.isEmpty()) Text(stringResource(R.string.history_chart_no_baseline), Modifier.testTag("$tag-no-baseline"))
}

@Composable
internal fun HistoryAxisTimes(first: Instant, last: Instant, zone: ZoneId, locale: Locale, tag: String, showPrefixes: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
        HistoryAxisTime(first, R.string.history_chart_time_first, zone, locale, "$tag-first", Modifier.weight(1f), TextAlign.Left, showPrefixes)
        Spacer(Modifier.width(12.dp))
        HistoryAxisTime(last, R.string.history_chart_time_last, zone, locale, "$tag-last", Modifier.weight(1f), TextAlign.Right, showPrefixes)
    }
}

@Composable
private fun HistoryAxisTime(at: Instant, resource: Int, zone: ZoneId, locale: Locale,
    tag: String, modifier: Modifier, align: TextAlign, showPrefix: Boolean) {
    val label = chartTimeLabel(at, zone, locale)
    Text(if (showPrefix) stringResource(resource, label) else label, modifier.testTag(tag), textAlign = align)
}

/** Shared yearless locale skeleton with the existing phone zone and repeated-hour offsets.
 * Axes default to containing-hour labels; Updated disables truncateToHour to floor only
 * seconds/nanos and retain the observation minute (08:37:59 becomes 08:37, never 08:38).
 * Source instants stay exact; second-precision details use TimePresentation.timestamp.
 */
@Composable
internal fun chartTimeLabel(at: Instant, zone: ZoneId, locale: Locale, truncateToHour: Boolean = true): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdHHmm")
    return TimePresentation.chartTime(at, zone, locale, pattern, truncateToHour) ?: stringResource(R.string.time_unknown_target)
}

@Composable
internal fun HistoryPercentPlot(drawing: HistoryChartDrawing, tag: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.height(176.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf(100, 50, 0).forEach { value -> Text(stringResource(R.string.history_chart_percent, value)) }
        }
        Canvas(Modifier.weight(1f).height(176.dp).testTag("$tag-canvas").semantics { hideFromAccessibility() }) {
            drawChart(drawing, colors.primary, colors.onSurfaceVariant, colors.outlineVariant)
        }
    }
}

// Inset keeps line caps and singleton ticks visible. Fractions are never clamped.
private fun DrawScope.chartOffset(position: PlotPosition.Available): Offset {
    val inset = 8.dp.toPx()
    return Offset(inset + position.x.toFloat() * (size.width - 2 * inset),
        inset + (1f - position.y.toFloat()) * (size.height - 2 * inset))
}

private fun DrawScope.drawChart(drawing: HistoryChartDrawing, measured: Color, nominal: Color, grid: Color) {
    listOf(0.0, 0.5, 1.0).forEach { y ->
        val detail = PlotRenderDetail.APPROXIMATE
        drawLine(grid, chartOffset(PlotPosition.Available(0.0, y, detail)),
            chartOffset(PlotPosition.Available(1.0, y, detail)), 1.dp.toPx())
    }
    val inset = 8.dp.toPx()
    clipRect(inset, inset, size.width - inset, size.height - inset) {
        drawing.trend?.let { line ->
            drawLine(measured.copy(alpha = 0.45f), chartOffset(line.start), chartOffset(line.end), 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
    drawing.nominal.forEach { line -> drawLine(nominal, chartOffset(line.start), chartOffset(line.end), 2.dp.toPx(), pathEffect = dash) }
    drawing.measured.forEach { line -> drawLine(measured, chartOffset(line.start), chartOffset(line.end), 2.dp.toPx(), cap = StrokeCap.Round) }
    // No point markers. Only isolated runs need a minimal 2dp-long, 2dp-wide tick.
    drawing.isolated.forEach { point ->
        val center = chartOffset(point)
        drawLine(measured, center - Offset(1.dp.toPx(), 0f), center + Offset(1.dp.toPx(), 0f),
            2.dp.toPx(), cap = StrokeCap.Round)
    }
}
