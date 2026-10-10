package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.leugenea.codexbarmobile.account.*

/** Name and edit draft are never saved in a Bundle; storage owns the committed local label. */
@Composable
internal fun AccountNameHeader(state: AccountNameState?, rename: (AccountNameEdit, String) -> Unit) {
    if (state == null) return
    var editing by remember(state.edit) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("account-name-section")) {
        Text(state.name?.text ?: stringResource(if (state.storageFailed) R.string.account_name_unavailable else R.string.account_name_fallback),
            Modifier.fillMaxWidth().testTag("account-name").semantics { heading() },
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.account_name_local_only))
        if (state.storageFailed) Text(stringResource(R.string.account_name_storage_failed),
            Modifier.testTag("account-name-error").semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedButton(onClick = { editing = true }, enabled = state.edit != null,
            modifier = Modifier.testTag("account-name-edit")) { Text(stringResource(R.string.account_name_edit)) }
    }
    val edit = state.edit
    if (editing && edit != null) AccountNameDialog(state.name, { editing = false }) { input ->
        rename(edit, input)
        editing = false
    }
}

@Composable
private fun AccountNameDialog(name: AccountDisplayName?, dismiss: () -> Unit, save: (String) -> Unit) {
    var draft by remember { mutableStateOf(name?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.account_name_edit)) },
        text = {
            OutlinedTextField(value = draft, onValueChange = { draft = AccountDisplayName.draft(it) },
                label = { Text(stringResource(R.string.account_name_label)) },
                supportingText = { Text(stringResource(R.string.account_name_limit)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("account-name-input"))
        },
        confirmButton = {
            TextButton(onClick = { save(draft) }, modifier = Modifier.testTag("account-name-save")) {
                Text(stringResource(R.string.account_name_save))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { save("") }, modifier = Modifier.testTag("account-name-clear")) {
                    Text(stringResource(R.string.account_name_clear))
                }
                TextButton(onClick = dismiss, modifier = Modifier.testTag("account-name-cancel")) {
                    Text(stringResource(R.string.account_name_cancel))
                }
            }
        },
    )
}
