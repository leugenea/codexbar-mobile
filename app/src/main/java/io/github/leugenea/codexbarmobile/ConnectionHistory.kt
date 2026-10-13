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

/** Standalone stored host for the observation seam; production hoists one source at its root. */
@Composable
internal fun ConnectionHistory(controller: ConnectionController, now: Instant?, observer: HistoryUiObserver? = null) {
    val source = collectedHistory(controller, observer)
    val authority = controller.currentHistoryDisplayContext()
    var kind by remember(controller, source.generation) { mutableStateOf(WindowKind.FIVE_HOUR) }
    var after by remember(controller, source.generation) { mutableStateOf<ObservationId?>(null) }
    val navigation = HistoryNavigation(kind, after)
    val request = HistoryPresentationRequest.Page(navigation.query)
    ObserveHistoryRequest(controller, authority, request, source.request)
    val display = historyForDisplay(source, navigation.query, authority)
    SideEffect { observer?.projected(source, display, now) }
    StoredHistoryContent(navigation, display, now, { kind = it.kind }, { after = it })
}

@Composable
internal fun collectedHistory(controller: ConnectionController, observer: HistoryUiObserver?): HistoryGraphSnapshot {
    val source by if (observer == null) controller.historySnapshots.collectAsState() else
        remember(controller, observer) { observer.snapshots(controller.historySnapshots) }.collectAsState(HistoryGraphSnapshot())
    return source
}

@Composable
internal fun ObserveHistoryRequest(controller: ConnectionController, authority: HistoryDisplayContext?, request: HistoryPresentationRequest, observed: HistoryPresentationRequest) {
    LaunchedEffect(controller, authority, request, observed) {
        authority?.let { controller.observeHistory(request, it) }
    }
}

@Composable
internal fun StoredHistoryContent(navigation: HistoryNavigation, display: HistoryGraphSnapshot, now: Instant?,
    changeKind: (HistoryNavigation) -> Unit, changePage: (ObservationId?) -> Unit) {
    val time = phoneTimeConfiguration()
    val plot = remember(display) { HistoryPlotInputs.project(display) }
    Column(Modifier.fillMaxWidth().testTag("connection-history"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.history_navigation_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("history-navigation-title").semantics { heading() })
        HistoryWindowSelection(navigation, changeKind)
        Text(stringResource(R.string.history_navigation_policy))
        HistoryPageControls(navigation, display, changePage)
        HistoryTextComponent(plot, now, time.zone, time.locale, kind = navigation.kind)
    }
}

/** UI-observation-only seam: tests may hold delivery, not the owner or its revocation slot. */
internal interface HistoryUiObserver {
    fun snapshots(source: StateFlow<HistoryGraphSnapshot>): Flow<HistoryGraphSnapshot>
    fun projected(source: HistoryGraphSnapshot, display: HistoryGraphSnapshot, now: Instant?)
    fun projectedCurrent(source: HistoryGraphSnapshot, fiveHour: HistoryGraphSnapshot, weekly: HistoryGraphSnapshot, now: Instant?) {
        projected(source, fiveHour, now)
    }
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
