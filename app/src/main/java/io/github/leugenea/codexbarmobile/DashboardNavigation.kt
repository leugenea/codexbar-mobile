package io.github.leugenea.codexbarmobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import kotlinx.coroutines.launch

internal enum class DashboardRoute(val title: Int) {
    DASHBOARD(R.string.dashboard_title),
    LOCAL_HISTORY_DETAILS(R.string.drawer_history),
    CONNECTION_DETAILS(R.string.drawer_connection),
    ACCOUNT_SETTINGS(R.string.account_settings_title),
}

/** Only ephemeral presentation choices; no saved account, payload, cursor or capability. */
@Composable
internal fun ConnectionScreen(state: ConnectionState, controller: ConnectionController, openBrowser: () -> Unit,
    observer: HistoryUiObserver? = null) {
    var route by remember(controller) { mutableStateOf(DashboardRoute.DASHBOARD) }
    val drawer = remember(controller) { DrawerState(DrawerValue.Closed) }
    val scope = rememberCoroutineScope()
    val exporter = historyExporter(controller)
    val importer = historyImporter(controller)
    BackHandler(drawer.isOpen || route != DashboardRoute.DASHBOARD) {
        if (drawer.isOpen) scope.launch { drawer.close() } else route = DashboardRoute.DASHBOARD
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        DetailDrawer(route) { route = it; scope.launch { drawer.close() } }
    }) {
        ConnectionRoute(state, controller, route, openBrowser, observer, exporter, importer,
            { scope.launch { drawer.open() } }, { route = DashboardRoute.DASHBOARD },
            { route = DashboardRoute.ACCOUNT_SETTINGS })
    }
}

@Composable
private fun DetailDrawer(route: DashboardRoute, select: (DashboardRoute) -> Unit) {
    ModalDrawerSheet(Modifier.testTag("details-drawer")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            listOf(DashboardRoute.LOCAL_HISTORY_DETAILS, DashboardRoute.CONNECTION_DETAILS).forEach { destination ->
                NavigationDrawerItem(label = { Text(stringResource(destination.title)) },
                    selected = route == destination, onClick = { select(destination) },
                    modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp).testTag("drawer-${destination.name}"))
            }
        }
        NavigationDrawerItem(label = { Text(stringResource(R.string.account_settings_title)) },
            selected = route == DashboardRoute.ACCOUNT_SETTINGS, onClick = { select(DashboardRoute.ACCOUNT_SETTINGS) },
            modifier = Modifier.padding(12.dp).heightIn(min = 48.dp).testTag("drawer-ACCOUNT_SETTINGS"))
    }
}

@Composable
private fun ConnectionRoute(state: ConnectionState, controller: ConnectionController, route: DashboardRoute,
    openBrowser: () -> Unit, observer: HistoryUiObserver?, exporter: HistoryExporter, importer: HistoryImporter, openDrawer: () -> Unit, back: () -> Unit, openAccountSettings: () -> Unit) {
    val source = collectedHistory(controller, observer)
    // Collection triggers a pass; this actual volatile slot is the permission on that pass.
    val authority = controller.currentHistoryDisplayContext()
    val periods = authority?.periods ?: CurrentHistoryPeriods()
    var kind by remember(controller, source.generation) { mutableStateOf(WindowKind.FIVE_HOUR) }
    var after by remember(controller, source.generation, route) { mutableStateOf<ObservationId?>(null) }
    val navigation = HistoryNavigation(kind, after)
    val current = HistoryPresentationRequest.Current(periods.fiveHour.window, periods.weekly.window)
    val request = routeRequest(route, current, navigation)
    ObserveHistoryRequest(controller, authority, request, source.request)
    val scroll = key(route) { rememberScrollState() }
    Column(Modifier.fillMaxSize().testTag("connection-screen").verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RouteHeader(route, openDrawer, back)
        RouteContent(route, state, controller, openBrowser, openAccountSettings, source, authority, current, navigation, observer, exporter, importer,
            { kind = it.kind }, { after = it })
    }
}

private fun routeRequest(route: DashboardRoute, current: HistoryPresentationRequest.Current, navigation: HistoryNavigation): HistoryPresentationRequest =
    when (route) {
        DashboardRoute.DASHBOARD -> current
        DashboardRoute.LOCAL_HISTORY_DETAILS -> HistoryPresentationRequest.Page(navigation.query)
        DashboardRoute.CONNECTION_DETAILS, DashboardRoute.ACCOUNT_SETTINGS -> HistoryPresentationRequest.Idle
    }

@Composable
private fun RouteHeader(route: DashboardRoute, openDrawer: () -> Unit, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val menu = stringResource(R.string.dashboard_menu)
        IconButton(onClick = openDrawer, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("open-drawer").semantics { contentDescription = menu }) {
            Text("☰")
        }
        Text(stringResource(route.title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(top = 8.dp).semantics { heading() })
    }
    if (route != DashboardRoute.DASHBOARD) TextButton(onClick = back,
        modifier = Modifier.heightIn(min = 48.dp).testTag("details-back")) { Text(stringResource(R.string.dashboard_back)) }
}

@Composable
private fun RouteContent(route: DashboardRoute, state: ConnectionState, controller: ConnectionController,
    browser: () -> Unit, openAccountSettings: () -> Unit, source: HistoryGraphSnapshot, authority: HistoryDisplayContext?, current: HistoryPresentationRequest.Current,
    navigation: HistoryNavigation, observer: HistoryUiObserver?, exporter: HistoryExporter, importer: HistoryImporter, kind: (HistoryNavigation) -> Unit, page: (ObservationId?) -> Unit) {
    when (route) {
        DashboardRoute.DASHBOARD -> DashboardScreen(state, controller, openAccountSettings, source, current, authority, observer)
        DashboardRoute.LOCAL_HISTORY_DETAILS -> {
            val display = historyForDisplay(source, navigation.query, authority)
            SideEffect { observer?.projected(source, display, state.refresh.evaluatedAt) }
            StoredHistoryContent(navigation, display, state.refresh.evaluatedAt, kind, page)
            HistoryExportAction(exporter)
            HistoryImportAction(importer, authority?.generation != null && source.partition != null)
        }
        DashboardRoute.CONNECTION_DETAILS -> Column(Modifier.testTag("connection-details")) { ConnectionContent(state, controller, openAccountSettings) }
        DashboardRoute.ACCOUNT_SETTINGS -> AccountSettingsScreen(state, controller, browser)
    }
}
