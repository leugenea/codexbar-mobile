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

/** Same measured/reference geometry and paint; compact equivalent, no admission/detail dump. */
@Composable
internal fun CompactHistoryChart(series: HistoryPlotSeries, display: HistoryGraphSnapshot, time: PhoneTimeConfiguration) {
    val tag = if (series.kind == io.github.leugenea.codexbarmobile.usage.WindowKind.FIVE_HOUR) "history-chart-five-hour" else "history-chart-weekly"
    val drawing = historyChartDrawing(series)
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.history_chart_legend), Modifier.testTag("$tag-legend"), style = MaterialTheme.typography.bodySmall)
        HistoryPercentPlot(drawing, tag)
        series.measuredDomain?.let { HistoryAxisTimes(it.time.first, it.time.last, time.zone, time.locale, tag) }
        CompactHistoryPace(display, time, tag)
        if (display.problem != null) Text(stringResource(R.string.dashboard_history_warning), Modifier.testTag("$tag-warning"))
        if (series.samples.any { it.measured?.position is PlotPosition.Unavailable }) Text(stringResource(R.string.dashboard_geometry_warning))
        if (series.samples.any { (it.measured?.position as? PlotPosition.Available)?.detail == PlotRenderDetail.NONZERO_UNDERFLOW })
            Text(stringResource(R.string.history_chart_underflow))
    }
}

@Composable
private fun CompactHistoryPace(display: HistoryGraphSnapshot, time: PhoneTimeConfiguration, tag: String) {
    val window = display.entries.lastOrNull { it.windows.any { window -> window.source.point != null } }
        ?.windows?.singleOrNull() ?: return
    when (val comparison = window.comparison) {
        is UsedComparison.Available -> {
            val pace = DashboardPresentation.pace(comparison.deltaPercentagePoints, time.locale)
            Text(stringResource(pace.resource, pace.amount.orEmpty()), Modifier.testTag("$tag-pace"))
        }
        is UsedComparison.Unavailable -> {
            val reason = when (val unavailable = comparison.reason) {
                is DistributionUnavailable.Ineligible -> unavailable.eligibility
                is DistributionUnavailable.InvalidFact -> unavailable
            }
            Text(stringResource(R.string.history_fact, stringResource(R.string.history_baseline_unavailable),
                stringResource(historyStateResource(reason))), Modifier.testTag("$tag-pace"))
        }
    }
}
