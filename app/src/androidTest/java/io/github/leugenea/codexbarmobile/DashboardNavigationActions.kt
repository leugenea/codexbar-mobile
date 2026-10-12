package io.github.leugenea.codexbarmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind

/** Idempotent route commands based on the live UI, never on asynchronous recorder query state. */
internal fun openDetailRoute(compose: ComposeTestRule, owner: ConnectionController, route: DashboardRoute) {
    val root = if (route == DashboardRoute.LOCAL_HISTORY_DETAILS) "connection-history" else "connection-details"
    if (compose.onAllNodesWithTag(root).fetchSemanticsNodes().isEmpty()) {
        compose.onNodeWithTag("open-drawer").performScrollTo().performClick()
        val label = if (route == DashboardRoute.LOCAL_HISTORY_DETAILS) "full local history details" else "full love connection details"
        compose.onNodeWithTag("drawer-${route.name}").assertTextEquals(label).performClick()
        try { compose.waitUntil(8_000) { compose.onAllNodesWithTag(root).fetchSemanticsNodes().size == 1 } }
        catch (error: ComposeTimeoutException) { throw AssertionError("Drawer route $route: last=${owner.state.value}, request=${owner.historySnapshots.value.request}", error) }
        compose.waitForIdle()
    }
    settleConnectionCommands(compose, owner, "$route effect owner receipt")
}

internal fun returnDashboard(compose: ComposeTestRule, owner: ConnectionController) {
    if (compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().isEmpty()) {
        compose.onNodeWithTag("details-back").performScrollTo().performClick()
        try { compose.waitUntil(8_000) { compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().size == 1 } }
        catch (error: ComposeTimeoutException) { throw AssertionError("Dashboard return: last=${owner.state.value}", error) }
        compose.waitForIdle()
    }
    settleConnectionCommands(compose, owner, "dashboard effect owner receipt")
}

internal fun toggleHistoryDetails(compose: ComposeTestRule, owner: ConnectionController) {
    if (compose.onAllNodesWithTag("connection-history").fetchSemanticsNodes().isEmpty())
        openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
    else returnDashboard(compose, owner)
}

/** Observed recorder data outside composition; the actual slot still fences the pair projection. */
internal fun recordedHistory(owner: ConnectionController, kind: WindowKind = WindowKind.FIVE_HOUR): HistoryGraphSnapshot =
    recordedHistory(owner.historySnapshots.value, kind)

internal fun recordedHistory(source: HistoryGraphSnapshot, kind: WindowKind = WindowKind.FIVE_HOUR): HistoryGraphSnapshot {
    val request = source.request as? HistoryPresentationRequest.Current ?: return source
    return currentHistoryForDisplay(source, request, kind, source.displayPermission?.current())
}

internal fun assertCurrentDrawing(source: HistoryGraphSnapshot, markers: Int, edges: Int, references: Int) {
    val plot = HistoryPlotInputs.project(source)
    val series = if (source.query.kind == WindowKind.WEEKLY) plot.weekly else plot.fiveHour
    val drawing = historyChartDrawing(series)
    org.junit.Assert.assertEquals(markers, drawing.markers.size)
    org.junit.Assert.assertEquals(edges, drawing.measured.size)
    org.junit.Assert.assertEquals(references, drawing.nominal.size)
}
