package io.github.leugenea.codexbarmobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Real drag at the scroll boundary, not an accessibility callback masquerading as a gesture. */
internal fun pullDashboardRefresh(compose: ComposeTestRule) {
    val scroll = compose.onNodeWithTag("connection-screen")
    val offset = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -offset) }
    compose.waitForIdle()
    compose.onNodeWithTag("dashboard-refresh").performTouchInput {
        swipe(Offset(centerX, height * 0.1f), Offset(centerX, height * 0.9f), durationMillis = 600)
    }
    compose.waitForIdle()
}

internal fun assertDashboardRefreshAction(compose: ComposeTestRule, enabled: Boolean) {
    val node = compose.onNodeWithTag("dashboard-refresh")
    if (enabled) {
        node.assertIsEnabled().assert(SemanticsMatcher("One accessible refresh action") {
            it.config[SemanticsActions.CustomActions].map { action -> action.label } == listOf("Refresh")
        })
    } else node.assertIsNotEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
}
