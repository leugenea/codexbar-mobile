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
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

internal data class HistoryExporter(val busy: Boolean, val start: () -> Unit)

/** Register at the navigation root, even when recreation returns to the dashboard. */
@Composable
internal fun historyExporter(selection: DashboardSelection, transfers: HistoryTransfers, owner: HistoryPickerOwner): HistoryExporter {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = selection.controller
    val request = transfers.export
    val busy by request.busy.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        request.finish(owner, transfers.scope) { captured ->
            if (uri != null) exportMessage(context, captured.label, writeHistoryDocument(context, uri, captured.text))
        }
    }
    return HistoryExporter(busy) start@{
        if (!selection.isCurrent()) return@start
        val name = controller.accountName.value
        val label = name?.name?.text ?: context.getString(
            if (name?.storageFailed == true) R.string.account_name_unavailable else R.string.account_name_fallback)
        val generation = (controller.session.snapshot() as? SessionResult.Ready)?.envelope?.generation
        request.start(owner, scope, prepare = {
            prepareHistoryDocument(context, controller) { selection.isCurrent() && it === generation }
                ?.let { DetachedHistoryExport(it, label) }
        }, launch = { launcher.launch("codexbar-history-v1.json") }, failure = { exportMessage(context, label, false) })
    }
}

@Composable
internal fun HistoryExportAction(exporter: HistoryExporter) {
    OutlinedButton(enabled = !exporter.busy, onClick = exporter.start,
        modifier = Modifier.fillMaxWidth().testTag("history-export")) { Text(stringResource(R.string.history_export)) }
}

private suspend fun prepareHistoryDocument(context: Context, controller: ConnectionController,
    canPrepare: (SessionGeneration) -> Boolean): String? = withContext(Dispatchers.IO) {
    try {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        controller.exportHistory(version, Instant.now(), canPrepare)
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

private fun exportMessage(context: Context, label: String, successful: Boolean) {
    val message = context.getString(if (successful) R.string.history_export_account_success else R.string.history_export_account_failure, label)
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
