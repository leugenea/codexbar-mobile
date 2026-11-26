package io.github.leugenea.codexbarmobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.leugenea.codexbarmobile.history.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HistoryImporter(val busy: Boolean, val start: () -> Unit)

/** Unconditional root registration survives a picker returning to a recreated dashboard. */
@Composable
internal fun historyImporter(selection: DashboardSelection, transfers: HistoryTransfers): HistoryImporter {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = selection.controller
    val busy by transfers.importPicker.busy.collectAsState()
    val confirmation by transfers.confirmation.collectAsState()
    val importing by transfers.importing.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        transfers.importPicker.finish(transfers.scope) { picker ->
            if (uri != null && picker.selection.isCurrent()) readImportSelection(context, transfers, picker, uri)
        }
    }
    val displayed = confirmation
    LaunchedEffect(selection, displayed) {
        if (displayed != null && !displayed.picker.selection.isCurrent())
            transfers.confirmation.compareAndSet(displayed, null)
    }
    if (displayed != null && displayed.picker.selection === selection && selection.isCurrent()) {
        HistoryImportConfirmation(displayed.picker.label,
            onDismiss = { transfers.confirmation.compareAndSet(displayed, null) },
            onConfirm = { transfers.confirm(displayed) { importMessage(context, it) } })
    }
    return HistoryImporter(busy || importing || confirmation != null) start@{
        if (!selection.isCurrent() || transfers.importing.value || transfers.confirmation.value != null) return@start
        val label = controller.accountName.value?.name?.text
        val generation = (controller.session.snapshot() as? SessionResult.Ready)?.envelope?.generation
        transfers.importPicker.start(scope, prepare = {
            controller.historyImportTarget { selection.isCurrent() && it === generation }
                ?.takeIf { selection.isCurrent() }?.let { HistoryImportPicker(selection, it, label) }
        }, launch = { launcher.launch(arrayOf("application/json")) }, failure = {
            if (selection.isCurrent()) importMessage(context, HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED))
        })
    }
}

private suspend fun readImportSelection(context: Context, transfers: HistoryTransfers, picker: HistoryImportPicker, uri: Uri) {
    try {
        val text = readHistoryDocument(context, uri)
        if (picker.selection.isCurrent()) transfers.confirmation.value = HistoryImportSelection(picker, text)
    } catch (_: HistoryImportTooLarge) {
        if (picker.selection.isCurrent()) importMessage(context, HistoryImportOutcome.TooLarge)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (picker.selection.isCurrent()) importMessage(context, HistoryImportOutcome.Invalid)
    }
}

@Composable
internal fun HistoryImportAction(importer: HistoryImporter, available: Boolean) {
    OutlinedButton(enabled = available && !importer.busy, onClick = importer.start,
        modifier = Modifier.fillMaxWidth().testTag("history-import")) { Text(stringResource(R.string.history_import)) }
}

@Composable
private fun HistoryImportConfirmation(label: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.history_import)) },
        text = { Text(if (label == null) stringResource(R.string.history_import_confirmation)
            else stringResource(R.string.history_import_account_confirmation, label)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("history-import-confirm").observeAccountAction(onConfirm)) {
            Text(stringResource(R.string.history_import_replace))
        } }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("history-import-cancel").observeAccountAction(onDismiss)) {
            Text(stringResource(R.string.history_import_cancel))
        } })
}

internal suspend fun readHistoryDocument(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    if (uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > HistoryImport.MAX_INPUT_BYTES) throw HistoryImportTooLarge()
    }
    val stream = requireNotNull(context.contentResolver.openInputStream(uri))
    stream.use(HistoryImport::read)
}

internal fun historyImportMessage(outcome: HistoryImportOutcome): Int = when (outcome) {
    HistoryImportOutcome.Imported -> R.string.history_import_success
    HistoryImportOutcome.Invalid -> R.string.history_import_invalid
    HistoryImportOutcome.UnsupportedVersion -> R.string.history_import_unsupported
    HistoryImportOutcome.TooLarge -> R.string.history_import_too_large
    is HistoryImportOutcome.Unavailable -> R.string.history_import_unavailable
}

private fun importMessage(context: Context, outcome: HistoryImportOutcome) {
    Toast.makeText(context, historyImportMessage(outcome), Toast.LENGTH_SHORT).show()
}
