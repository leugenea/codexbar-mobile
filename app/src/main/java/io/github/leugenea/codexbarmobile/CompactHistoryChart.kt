package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.history.*
import java.time.Instant

/** Same measured/reference geometry and paint; compact equivalent, no admission/detail dump. */
@Composable
internal fun CompactHistoryChart(series: HistoryPlotSeries, display: HistoryGraphSnapshot, time: PhoneTimeConfiguration, now: Instant?) {
    val tag = if (series.kind == io.github.leugenea.codexbarmobile.usage.WindowKind.FIVE_HOUR) "history-chart-five-hour" else "history-chart-weekly"
    val drawing = historyChartDrawing(series)
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (drawing.markers.size == 1) R.string.dashboard_single_observation else R.string.dashboard_recorded_observations),
            Modifier.testTag("$tag-equivalent"), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.history_chart_legend), Modifier.testTag("$tag-legend"), style = MaterialTheme.typography.bodySmall)
        HistoryPercentPlot(drawing, tag)
        series.measuredDomain?.let {
            HistoryAxisTime(it.time.first, R.string.history_chart_time_first, time.zone, time.locale, "$tag-first")
            HistoryAxisTime(it.time.last, R.string.history_chart_time_last, time.zone, time.locale, "$tag-last")
        }
        CompactHistoryFacts(display, time, now)
        if (DashboardPresentation.incomplete(display)) Text(stringResource(R.string.dashboard_history_incomplete), Modifier.testTag("$tag-incomplete"))
        if (display.problem != null) Text(stringResource(R.string.dashboard_history_warning), Modifier.testTag("$tag-warning"))
        if (series.samples.any { it.measured?.position is PlotPosition.Unavailable }) Text(stringResource(R.string.dashboard_geometry_warning))
        if (series.samples.any { (it.measured?.position as? PlotPosition.Available)?.detail == PlotRenderDetail.NONZERO_UNDERFLOW })
            Text(stringResource(R.string.history_chart_underflow))
    }
}

@Composable
private fun CompactHistoryFacts(display: HistoryGraphSnapshot, time: PhoneTimeConfiguration, now: Instant?) {
    val entry = display.entries.lastOrNull { it.windows.any { window -> window.source.point != null } } ?: return
    val detail = HistoryTextPresentation.detail(entry, now, time.zone, time.locale)
    val selected = detail.windows.flatMap { it.facts }.filter { it.label in compactFactLabels }
    selected.forEach { fact ->
        val value = fact.state?.let { stringResource(historyStateResource(it)) } ?: fact.value ?: stringResource(R.string.history_unknown)
        HistoryTextPresentation.chunks(value).forEach { chunk -> Text(stringResource(R.string.history_fact, stringResource(fact.label), chunk)) }
    }
}

private val compactFactLabels = setOf(R.string.history_measured, R.string.history_point_time, R.string.history_delta,
    R.string.history_baseline_unavailable)
