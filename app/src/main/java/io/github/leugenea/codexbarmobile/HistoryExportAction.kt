package io.github.leugenea.codexbarmobile

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Process-memory only: retain a user-requested snapshot across SAF/Activity recreation, never Bundle it. */
internal class HistoryExportRequest {
    var pending: String? = null
    val busy = MutableStateFlow(false)
    fun clear() { pending = null; busy.value = false }
}

internal data class HistoryExporter(val busy: Boolean, val start: () -> Unit)

/** Register at the navigation root, even when recreation returns to the dashboard. */
@Composable
internal fun historyExporter(controller: ConnectionController): HistoryExporter {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val request = controller.historyExportRequest
    val busy by request.busy.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val captured = request.pending
        request.pending = null
        scope.launch {
            try {
                if (uri != null) exportMessage(context, writeHistoryDocument(context, uri, captured))
            } finally {
                request.clear()
            }
        }
    }
    return HistoryExporter(busy) {
        if (!request.busy.value) {
            request.busy.value = true
            scope.launch {
                try {
                    request.pending = prepareHistoryDocument(context, controller)
                    if (request.pending == null) exportMessage(context, false)
                    else launcher.launch("codexbar-history-v1.json")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    request.clear()
                    exportMessage(context, false)
                } finally {
                    if (request.pending == null) request.clear()
                }
            }
        }
    }
}

@Composable
internal fun HistoryExportAction(exporter: HistoryExporter) {
    OutlinedButton(enabled = !exporter.busy, onClick = exporter.start,
        modifier = Modifier.fillMaxWidth().testTag("history-export")) { Text(stringResource(R.string.history_export)) }
}

private suspend fun prepareHistoryDocument(context: Context, controller: ConnectionController): String? = withContext(Dispatchers.IO) {
    try {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        controller.exportHistory(version, Instant.now())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

internal suspend fun writeHistoryDocument(context: Context, uri: Uri, document: String?): Boolean = withContext(Dispatchers.IO) {
    if (document == null) return@withContext false
    try {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(document) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private fun exportMessage(context: Context, successful: Boolean) {
    Toast.makeText(context, if (successful) R.string.history_export_success else R.string.history_export_failure, Toast.LENGTH_SHORT).show()
}
