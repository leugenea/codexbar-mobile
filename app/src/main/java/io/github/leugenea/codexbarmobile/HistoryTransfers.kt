package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.history.HistoryImportOutcome
import io.github.leugenea.codexbarmobile.history.HistoryPartition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** A lifetime ticket, not a retained foreground capability or permission to target a successor. */
internal class HistoryImportTarget(val generation: SessionGeneration, val partition: HistoryPartition)
internal class HistoryImportPicker(val selection: DashboardSelection, val target: HistoryImportTarget, val label: String?)
internal class HistoryImportSelection(val picker: HistoryImportPicker, val text: String)
internal class DetachedHistoryExport(val text: String, val label: String)

/** One process-memory slot per launcher, independent of dashboard and Activity lifetimes. */
internal class HistoryTransfers {
    // Preparation/launch uses the Activity's scope; a returned result no longer needs the Activity.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val export = HistoryDocumentRequest<DetachedHistoryExport>()
    val importPicker = HistoryDocumentRequest<HistoryImportPicker>()
    val importing = MutableStateFlow(false)
    val confirmation = MutableStateFlow<HistoryImportSelection?>(null)

    fun confirm(displayed: HistoryImportSelection, report: (HistoryImportOutcome) -> Unit) {
        val picker = displayed.picker
        if (!confirmation.compareAndSet(displayed, null) || !picker.selection.isCurrent()) return
        importing.value = true
        scope.launch {
            try {
                report(picker.selection.controller.importHistory(picker.target, displayed.text,
                    Instant.now(), picker.selection::isCurrent))
            } finally { importing.value = false }
        }
    }

    fun close() = scope.cancel()
}
