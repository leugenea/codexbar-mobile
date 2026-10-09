package io.github.leugenea.codexbarmobile

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/** Match the retired dump's formats, not facts legitimately owned by live/banked/history UI. */
internal fun assertConnectionHasNoDiagnosticDump(compose: ComposeTestRule) {
    compose.onNodeWithTag("connection-screen").assertExists()
    compose.onNodeWithTag("live-usage").assertExists()
    val connection = hasAnyAncestor(hasTestTag("connection-screen"))
    val dump = SemanticsMatcher("Retired feasibility diagnostic format") { node ->
        val labels = listOfNotNull(
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text },
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("\n"),
            node.config.getOrNull(SemanticsProperties.StateDescription),
        )
        labels.any { label -> label.lineSequence().any { line -> diagnosticPrefixes.any(line::startsWith) } }
    }
    compose.onAllNodes(connection and dump, useUnmergedTree = true).assertCountEquals(0)
}

private val diagnosticPrefixes = listOf(
    "USAGE · HTTP ",
    "RESET_INVENTORY · HTTP ",
    "Five-hour (18000 s): ",
    "Weekly (604800 s): ",
    "Duration ",
    "Provider allowed ",
    "Usage banked available_count: ",
    "Inventory banked available_count: ",
    "Banked expiry: ",
)
