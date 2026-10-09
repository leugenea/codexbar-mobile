package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Uses the connection screen's existing foreground registration, never activates a provider read. */
@Composable
internal fun ConnectionHistory(controller: ConnectionController, now: Instant?, observer: HistoryUiObserver? = null) {
    val source by if (observer == null) controller.historySnapshots.collectAsState() else
        remember(controller, observer) { observer.snapshots(controller.historySnapshots) }.collectAsState(HistoryGraphSnapshot())
    val generation = source.generation
    var navigation by remember(controller, generation) { mutableStateOf(HistoryNavigation()) }
    val query = navigation.query
    LaunchedEffect(controller, generation, query) {
        if (generation != null && controller.historySnapshots.value.generation === generation) controller.queryHistory(query)
    }
    val display = historyForDisplay(source, query)
    SideEffect { observer?.projected(source, display, now) }
    val plot = remember(display) { HistoryPlotInputs.project(display) }
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth().testTag("connection-history"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.history_navigation_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("history-navigation-title").semantics { heading() })
        HistoryWindowSelection(navigation) { navigation = it }
        Text(stringResource(R.string.history_navigation_policy))
        Text(stringResource(R.string.history_page_cursor, query.after?.ordinal ?: 0, query.limit),
            Modifier.testTag("history-page-cursor"))
        display.storage?.let { page ->
            Text(stringResource(R.string.history_page_ordinals, page.entries.firstOrNull()?.id?.ordinal?.toString()
                ?: stringResource(R.string.history_unknown), page.entries.lastOrNull()?.id?.ordinal?.toString()
                ?: stringResource(R.string.history_unknown)), Modifier.testTag("history-page-ordinals"))
        }
        OutlinedButton(onClick = { navigation = navigation.copy(after = null) }, enabled = navigation.after != null,
            modifier = Modifier.fillMaxWidth().testTag("history-first-page")) {
            Text(stringResource(R.string.history_first_page))
        }
        OutlinedButton(onClick = { navigation = navigation.next(display) },
            enabled = display.generation != null && navigation.next(display) != navigation,
            modifier = Modifier.fillMaxWidth().testTag("history-next-page")) {
            Text(stringResource(R.string.history_next_page))
        }
        HistoryChartComponent(plot, now, ZoneId.systemDefault(), locale, kind = navigation.kind)
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
