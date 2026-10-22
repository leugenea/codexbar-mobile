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
import java.time.Instant

internal data class HistoryImporter(val busy: Boolean, val start: () -> Unit)

/** Unconditional root registration survives a picker returning to a recreated dashboard. */
@Composable
internal fun historyImporter(controller: ConnectionController): HistoryImporter {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val state = controller.historyImporterState
    val busy by state.picker.busy.collectAsState()
    val selection by state.confirmation.collectAsState()
    val importing by state.importing.collectAsState()
    val result by state.result.collectAsState()
    LaunchedEffect(result) {
        result?.let { if (state.result.compareAndSet(it, null)) importMessage(context, it) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        state.picker.finish(scope) { target ->
            if (uri != null && target != null) {
                try {
                    val text = readHistoryDocument(context, uri)
                    state.confirmation.value = HistoryImportSelection(target, text)
                } catch (_: HistoryImportTooLarge) {
                    importMessage(context, HistoryImportOutcome.TooLarge)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    importMessage(context, HistoryImportOutcome.Invalid)
                }
            }
        }
    }
    selection?.let { chosen ->
        HistoryImportConfirmation(onDismiss = { state.confirmation.value = null }, onConfirm = {
            state.confirmation.value = null
            state.picker.finish(scope) {
                controller.importHistory(chosen.target, chosen.text, Instant.now())
            }
        })
    }
    return HistoryImporter(busy || importing || selection != null) {
        state.picker.start(scope, prepare = { controller.historyImportTarget() },
            launch = { launcher.launch(arrayOf("application/json")) },
            failure = { importMessage(context, HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)) })
    }
}

@Composable
internal fun HistoryImportAction(importer: HistoryImporter, available: Boolean) {
    OutlinedButton(enabled = available && !importer.busy, onClick = importer.start,
        modifier = Modifier.fillMaxWidth().testTag("history-import")) { Text(stringResource(R.string.history_import)) }
}

@Composable
private fun HistoryImportConfirmation(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.history_import)) },
        text = { Text(stringResource(R.string.history_import_confirmation)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("history-import-confirm")) {
            Text(stringResource(R.string.history_import_replace))
        } }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("history-import-cancel")) {
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
