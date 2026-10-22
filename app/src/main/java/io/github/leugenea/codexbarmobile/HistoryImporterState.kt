package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.history.HistoryPartition
import io.github.leugenea.codexbarmobile.history.HistoryImportOutcome
import kotlinx.coroutines.flow.MutableStateFlow

/** A lifetime ticket, not a retained foreground capability or permission to target a successor. */
internal class HistoryImportTarget(val generation: SessionGeneration, val partition: HistoryPartition)
internal class HistoryImportSelection(val target: HistoryImportTarget, val text: String)
internal class HistoryImporterState {
    val picker = HistoryDocumentRequest<HistoryImportTarget>()
    val importing = MutableStateFlow(false)
    val result = MutableStateFlow<HistoryImportOutcome?>(null)
    val confirmation = MutableStateFlow<HistoryImportSelection?>(null)
}
