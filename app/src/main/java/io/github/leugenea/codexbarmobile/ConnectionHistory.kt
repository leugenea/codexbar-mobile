package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Uses the connection screen's existing foreground registration, never activates a provider read. */
@Composable
internal fun ConnectionHistory(controller: ConnectionController, now: Instant?, observer: HistoryUiObserver? = null) {
    val source by if (observer == null) controller.historySnapshots.collectAsState() else
        remember(controller, observer) { observer.snapshots(controller.historySnapshots) }.collectAsState(HistoryGraphSnapshot())
    val generation = source.generation
    var kind by remember(controller, generation) { mutableStateOf(WindowKind.FIVE_HOUR) }
    var storedText by remember(controller) { mutableStateOf(false) }
    // This read is deliberately independent of delayed history snapshot delivery.
    val authority = controller.historySnapshots.value.displayPermission?.current()
    val period = authority?.periods?.forKind(kind) ?: CurrentHistoryPeriod(unavailable = BaselineEligibility.WINDOW_UNAVAILABLE)
    var after by remember(controller, generation, storedText, if (storedText) null else period.window) { mutableStateOf<ObservationId?>(null) }
    val navigation = HistoryNavigation(kind, after, if (storedText) null else period.window, !storedText)
    val query = navigation.query
    LaunchedEffect(controller, generation, query) {
        if (generation != null && controller.historySnapshots.value.generation === generation) controller.queryHistory(query)
    }
    val display = historyForDisplay(source, query)
    SideEffect { observer?.projected(source, display, now) }
    val plot = remember(display) { HistoryPlotInputs.project(display) }
    val time = phoneTimeConfiguration()
    Column(Modifier.fillMaxWidth().testTag("connection-history"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.history_navigation_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("history-navigation-title").semantics { heading() })
        HistoryWindowSelection(navigation) { kind = it.kind }
        OutlinedButton(onClick = { storedText = !storedText }, modifier = Modifier.testTag("history-stored-text")) {
            Text(stringResource(if (storedText) R.string.history_current_charts else R.string.history_stored_text))
        }
        if (!storedText) {
            Text(stringResource(R.string.history_current_policy), Modifier.testTag("history-current-policy"))
            period.unavailable?.let {
                Text(stringResource(R.string.history_current_unavailable, stringResource(historyStateResource(it))),
                    Modifier.testTag("history-current-unavailable"))
            }
        }
        Text(stringResource(R.string.history_navigation_policy))
        HistoryPageControls(navigation, display) { after = it }
        if (storedText) HistoryTextComponent(plot, now, time.zone, time.locale, kind = navigation.kind)
        else HistoryChartComponent(plot, authority?.periods?.evaluatedAt, time.zone, time.locale, kind = navigation.kind)
    }
}

/** UI-observation-only seam: tests may hold delivery, not the owner or its revocation slot. */
internal interface HistoryUiObserver {
    fun snapshots(source: StateFlow<HistoryGraphSnapshot>): Flow<HistoryGraphSnapshot>
    fun projected(source: HistoryGraphSnapshot, display: HistoryGraphSnapshot, now: Instant?)
}

@Composable
private fun HistoryWindowSelection(navigation: HistoryNavigation, change: (HistoryNavigation) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(WindowKind.FIVE_HOUR, WindowKind.WEEKLY).forEach { kind ->
            FilterChip(selected = navigation.kind == kind, onClick = { change(navigation.copy(kind = kind)) },
                label = { Text(stringResource(historyStateResource(kind))) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("history-select-${kind.name}"))
        }
    }
}

@Composable
private fun HistoryPageControls(navigation: HistoryNavigation, display: HistoryGraphSnapshot, change: (ObservationId?) -> Unit) {
    val query = navigation.query
    Text(stringResource(R.string.history_page_cursor, query.after?.ordinal ?: 0, query.limit),
        Modifier.testTag("history-page-cursor"))
    display.storage?.let { page ->
        Text(stringResource(R.string.history_page_ordinals, page.entries.firstOrNull()?.id?.ordinal?.toString()
            ?: stringResource(R.string.history_unknown), page.entries.lastOrNull()?.id?.ordinal?.toString()
            ?: stringResource(R.string.history_unknown)), Modifier.testTag("history-page-ordinals"))
    }
    OutlinedButton(onClick = { change(null) }, enabled = navigation.after != null,
        modifier = Modifier.fillMaxWidth().testTag("history-first-page")) {
        Text(stringResource(R.string.history_first_page))
    }
    OutlinedButton(onClick = { change(navigation.next(display).after) },
        enabled = display.generation != null && navigation.next(display) != navigation,
        modifier = Modifier.fillMaxWidth().testTag("history-next-page")) {
        Text(stringResource(R.string.history_next_page))
    }
}
