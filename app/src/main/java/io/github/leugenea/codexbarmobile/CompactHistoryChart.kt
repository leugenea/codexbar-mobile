package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.history.*

/** Same measured/reference geometry and paint; compact equivalent, no admission/detail dump. */
@Composable
internal fun CompactHistoryChart(series: HistoryPlotSeries, display: HistoryGraphSnapshot, time: PhoneTimeConfiguration) {
    val tag = if (series.kind == io.github.leugenea.codexbarmobile.usage.WindowKind.FIVE_HOUR) "history-chart-five-hour" else "history-chart-weekly"
    val drawing = remember(series) { historyChartDrawing(series) }
    val unavailableGeometry = remember(series) { series.samples.any { it.measured?.position is PlotPosition.Unavailable } }
    val underflow = remember(series) {
        series.samples.any { (it.measured?.position as? PlotPosition.Available)?.detail == PlotRenderDetail.NONZERO_UNDERFLOW }
    }
    val legend = stringResource(R.string.history_chart_legend)
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryPercentPlot(drawing, tag, Modifier.testTag("$tag-plot").semantics { contentDescription = legend })
        series.measuredDomain?.let { HistoryAxisTimes(it.time.first, it.time.last, time.zone, time.locale, tag, showPrefixes = false) }
        if (display.problem != null) Text(stringResource(R.string.dashboard_history_warning), Modifier.testTag("$tag-warning"))
        if (unavailableGeometry) Text(stringResource(R.string.dashboard_geometry_warning))
        if (underflow) Text(stringResource(R.string.history_chart_underflow))
    }
}

@Composable
internal fun CompactHistoryPace(comparison: UsedComparison?, time: PhoneTimeConfiguration, tag: String, modifier: Modifier = Modifier) {
    val label = compactPaceLabel(comparison, time)
    Text(label, modifier.testTag("$tag-pace"), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun compactPaceLabel(comparison: UsedComparison?, time: PhoneTimeConfiguration): String {
    return when (comparison) {
        null -> stringResource(R.string.history_baseline_unavailable)
        is UsedComparison.Available -> {
            val pace = DashboardPresentation.pace(comparison.deltaPercentagePoints, time.locale)
            stringResource(pace.resource, pace.amount.orEmpty())
        }
        is UsedComparison.Unavailable -> {
            val reason = when (val unavailable = comparison.reason) {
                is DistributionUnavailable.Ineligible -> unavailable.eligibility
                is DistributionUnavailable.InvalidFact -> unavailable
            }
            stringResource(R.string.history_fact, stringResource(R.string.history_baseline_unavailable),
                stringResource(historyStateResource(reason)))
        }
    }
}
