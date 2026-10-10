package io.github.leugenea.codexbarmobile

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/** The dialog is ephemeral and its callback never reacquires successor permission. */
@Composable
internal fun AccountRemovalAction(phase: ConnectionPhase, controller: ConnectionController) {
    val removal by controller.accountRemovalPermissions.collectAsState()
    val enabled = phase !in setOf(ConnectionPhase.RESTORING, ConnectionPhase.SIGNING_OUT,
        ConnectionPhase.SIGNED_OUT, ConnectionPhase.REAUTH_REQUIRED, ConnectionPhase.IDLE)
    var confirming by remember(controller, removal, enabled) { mutableStateOf(false) }
    OutlinedButton(onClick = { confirming = true }, enabled = enabled,
        modifier = Modifier.testTag("remove-account")) {
        Text(stringResource(R.string.account_remove))
    }
    if (confirming && enabled) AccountRemovalDialog({ confirming = false }) {
        controller.signOut(removal)
        confirming = false
    }
}

@Composable
private fun AccountRemovalDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("remove-account-dialog"),
        title = { Text(stringResource(R.string.account_remove_title)) },
        text = { Text(stringResource(R.string.account_remove_scope)) },
        confirmButton = {
            TextButton(onClick = confirm, modifier = Modifier.testTag("remove-account-confirm")) {
                Text(stringResource(R.string.account_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.testTag("remove-account-cancel")) {
                Text(stringResource(R.string.account_remove_cancel))
            }
        },
    )
}
