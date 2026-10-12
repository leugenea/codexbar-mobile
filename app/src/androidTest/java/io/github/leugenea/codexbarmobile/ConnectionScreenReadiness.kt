package io.github.leugenea.codexbarmobile

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Real default screen readiness and queued owner completion; never a replacement tab click. */
internal fun awaitConnectionScreen(compose: ComposeTestRule, controller: ConnectionController) {
    try {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("connection-screen")).fetchSemanticsNodes().size == 1 &&
                (compose.onAllNodes(hasTestTag("dashboard-status")).fetchSemanticsNodes().size == 1 ||
                    compose.onAllNodes(hasTestTag("connection-history")).fetchSemanticsNodes().size == 1 ||
                    compose.onAllNodes(hasTestTag("connection-details")).fetchSemanticsNodes().size == 1) &&
                controller.state.value.phase != ConnectionPhase.RESTORING
        }
        compose.waitForIdle()
        settleConnectionCommands(compose, controller, "default screen foreground registration")
        compose.onNodeWithTag("connection-screen").assertIsDisplayed()

    } catch (error: ComposeTimeoutException) {
        throw AssertionError("Default connection screen: last state=${controller.state.value}", error)
    }
}

/** Advance Compose's scheduler while waiting for the serial owner receipt, including STOP cleanup. */
internal fun settleConnectionCommands(compose: ComposeTestRule, controller: ConnectionController, step: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val receipt = scope.async { controller.commandsSettled() }
    try {
        compose.waitUntil(timeoutMillis = 5_000) { receipt.isCompleted }
        runBlocking { receipt.await() }
    } catch (error: ComposeTimeoutException) {
        throw AssertionError("$step: last state=${controller.state.value}", error)
    } finally { scope.cancel() }
}
