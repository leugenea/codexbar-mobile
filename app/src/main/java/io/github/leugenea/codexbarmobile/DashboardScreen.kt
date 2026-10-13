package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind

@Composable
internal fun DashboardScreen(state: ConnectionState, controller: ConnectionController, browser: () -> Unit,
    source: HistoryGraphSnapshot, current: HistoryPresentationRequest.Current, authority: HistoryDisplayContext?, observer: HistoryUiObserver?) {
    val time = phoneTimeConfiguration()
    val usage = UsagePresentation.present(state, time.zone, time.locale)
    val five = currentHistoryForDisplay(source, current, WindowKind.FIVE_HOUR, authority)
    val weekly = currentHistoryForDisplay(source, current, WindowKind.WEEKLY, authority)
    SideEffect { observer?.projectedCurrent(source, five, weekly, state.refresh.evaluatedAt) }
    Column(Modifier.fillMaxWidth().testTag("dashboard"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        DashboardStatus(state, usage)
        DashboardActions(state, usage, controller, browser)
        DashboardWindow(usage.windows[0], five, time, state.refresh.evaluatedAt)
        DashboardWindow(usage.windows[1], weekly, time, state.refresh.evaluatedAt)
        BankedResetDisclosure(BankedResetPresentation.present(state, time.zone, time.locale), authority)
    }
}

@Composable
private fun DashboardStatus(state: ConnectionState, model: PresentedUsage) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(DashboardPresentation.status(model.status)), Modifier.testTag("dashboard-status"))
        if (model.refreshing) Text(stringResource(R.string.dashboard_refreshing))
        model.errorResource?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        DashboardPresentation.problem(state.problem)?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DashboardActions(state: ConnectionState, model: PresentedUsage, controller: ConnectionController, browser: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val awaiting = state.auth as? AuthState.AwaitingUser
        if (awaiting != null) {
            DeviceLoginCode(awaiting, controller)
            Button(onClick = browser, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open-browser")) {
                Text(stringResource(R.string.gate_browser))
            }
        }
        if (DashboardPresentation.loginVisible(state.phase)) Button(onClick = controller::connect, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connect")) {
            Text(stringResource(if (state.phase == ConnectionPhase.REAUTH_REQUIRED) R.string.dashboard_reauthorize_action else R.string.dashboard_login))
        }
        if (!DashboardPresentation.loginVisible(state.phase)) Button(onClick = { controller.readUsage() }, enabled = model.refreshEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("live-refresh")) { Text(stringResource(R.string.live_refresh)) }
        if (DashboardPresentation.cancelVisible(state.phase)) OutlinedButton(onClick = controller::cancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel-connect")) { Text(stringResource(R.string.gate_cancel)) }
        AccountRemovalAction(state.phase, controller)
    }
}

@Composable
private fun DashboardWindow(window: PresentedUsageWindow, display: HistoryGraphSnapshot, time: PhoneTimeConfiguration, now: java.time.Instant?) {
    val plot = remember(display) { HistoryPlotInputs.project(display) }
    val series = if (window.tag == "live-five-hour") plot.fiveHour else plot.weekly
    Card(Modifier.fillMaxWidth().testTag("dashboard-${series.kind.name}")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(window.labelResource), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(if (series.kind == WindowKind.FIVE_HOUR) "history-chart-five-hour-title" else "history-chart-weekly-title").semantics { heading() })
            window.percent?.let { Text(stringResource(R.string.live_percent, it), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("${window.tag}-percent")) }
            DashboardReset(window.reset, window.tag)
            if (DashboardPresentation.hasChart(series)) {
                DashboardPresentation.windowWarning(window.status, window.malformed)?.let {
                    Text(stringResource(it), Modifier.testTag("dashboard-window-warning-${series.kind.name}"), color = MaterialTheme.colorScheme.error)
                }
                CompactHistoryChart(series, display, time, now)
            } else Text(stringResource(DashboardPresentation.noInformation(display, window.status, window.malformed)), Modifier.testTag("dashboard-no-info-${series.kind.name}"))
        }
    }
}

@Composable
private fun DashboardReset(time: PresentedTime?, tag: String) {
    val known = time ?: return
    val absolute = known.absolute ?: return
    Text(stringResource(R.string.live_reset_absolute, absoluteTimeLabel(absolute)), Modifier.testTag("$tag-reset-absolute"))
    if (known.state != TimeState.UNKNOWN_CLOCK && known.snapshot != TimeSnapshot.CLOCK_BEFORE_OBSERVATION)
        Text(stringResource(R.string.live_reset_relative, relativeLabel(known)), Modifier.testTag("$tag-reset-relative"))
}
