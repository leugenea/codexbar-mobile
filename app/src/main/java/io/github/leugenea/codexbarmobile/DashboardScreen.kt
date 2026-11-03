package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import io.github.leugenea.codexbarmobile.usage.UsageObservation

@Composable
internal fun DashboardScreen(state: ConnectionState, openAccountSettings: () -> Unit,
    source: HistoryGraphSnapshot, current: HistoryPresentationRequest.Current, authority: HistoryDisplayContext?, observer: HistoryUiObserver?) {
    val time = phoneTimeConfiguration()
    val usage = UsagePresentation.present(state, time.zone, time.locale)
    val five = currentHistoryForDisplay(source, current, WindowKind.FIVE_HOUR, authority)
    val weekly = currentHistoryForDisplay(source, current, WindowKind.WEEKLY, authority)
    SideEffect { observer?.projectedCurrent(source, five, weekly, state.refresh.evaluatedAt) }
    Column(Modifier.fillMaxWidth().testTag("dashboard"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DashboardStatus(state, usage, time)
        if (DashboardPresentation.loginVisible(state.phase)) AccountSettingsLink(openAccountSettings)
        DashboardWindow(usage.windows[0], five, time, usage.observation)
        DashboardWindow(usage.windows[1], weekly, time, usage.observation)
        BankedResetCard(BankedResetPresentation.present(state, time.zone, time.locale), time)
    }
}

@Composable
private fun DashboardStatus(state: ConnectionState, model: PresentedUsage, time: PhoneTimeConfiguration) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        DashboardPresentation.status(model.status)?.let { Text(stringResource(it), Modifier.testTag("dashboard-status")) }
        model.observation?.observedAt?.let { Text(stringResource(R.string.dashboard_updated, chartTimeLabel(it, time.zone, time.locale, truncateToHour = false)),
            Modifier.testTag("dashboard-observed-at"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        model.errorResource?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        DashboardPresentation.problem(state.problem)?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DashboardWindow(window: PresentedUsageWindow, display: HistoryGraphSnapshot, time: PhoneTimeConfiguration,
    observation: UsageObservation?) {
    val plot = remember { HistoryPlotCache() }.project(display)
    val series = if (window.tag == "live-five-hour") plot.fiveHour else plot.weekly
    val hasChart = remember(series) { DashboardPresentation.hasChart(series) }
    Card(Modifier.fillMaxWidth().testTag("dashboard-${series.kind.name}")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val chartTag = if (series.kind == WindowKind.FIVE_HOUR) "history-chart-five-hour" else "history-chart-weekly"
            FlowRow(Modifier.fillMaxWidth().testTag("$chartTag-heading"),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(window.labelResource), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.CenterVertically).testTag("$chartTag-title").semantics { heading() })
                if (hasChart) CompactHistoryPace(DashboardPresentation.paceComparison(display, observation, series.kind), time, chartTag,
                    Modifier.align(Alignment.CenterVertically))
            }
            window.percent?.let { Text(stringResource(R.string.live_percent, it), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("${window.tag}-percent")) }
            DashboardReset(window.reset, window.tag)
            if (hasChart) {
                DashboardPresentation.windowWarning(window.status, window.malformed)?.let {
                    Text(stringResource(it), Modifier.testTag("dashboard-window-warning-${series.kind.name}"), color = MaterialTheme.colorScheme.error)
                }
                CompactHistoryChart(series, display, time)
            } else Text(stringResource(DashboardPresentation.noInformation(display, window.status, window.malformed)), Modifier.testTag("dashboard-no-info-${series.kind.name}"))
        }
    }
}

@Composable
private fun DashboardReset(time: PresentedTime?, tag: String) {
    val known = time ?: return
    if (known.absolute == null) return
    if (known.state != TimeState.UNKNOWN_CLOCK && known.snapshot != TimeSnapshot.CLOCK_BEFORE_OBSERVATION) {
        val label = known.remaining?.let { stringResource(R.string.dashboard_next_reset, compactRemainingTimeLabel(it)) } ?: relativeLabel(known)
        val spoken = known.remaining?.let { stringResource(R.string.dashboard_next_reset, remainingTimeLabel(it)) } ?: label
        Text(label, Modifier.testTag("$tag-reset-relative").semantics { contentDescription = spoken })
    }
}

@Composable
internal fun compactRemainingTimeLabel(remaining: RemainingTime): String {
    val units = listOf(R.string.dashboard_days_short to remaining.days,
        R.string.dashboard_hours_short to remaining.hours.toLong(), R.string.dashboard_minutes_short to remaining.minutes.toLong())
        .filter { it.second > 0 }.map { (resource, value) -> stringResource(resource, value) }
    return if (units.isEmpty()) stringResource(R.string.dashboard_less_than_minute) else units.joinToString(" ")
}
