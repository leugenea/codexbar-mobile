package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.auth.AuthState

/** Account commands stay on the existing process owner; this route owns only presentation. */
@Composable
internal fun AccountSettingsScreen(state: ConnectionState, controller: ConnectionController, openBrowser: () -> Unit) {
    val name by controller.accountName.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("account-settings"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AccountNameHeader(name, controller::renameAccount)
        Text(stringResource(R.string.gate_identity))
        Text(stringResource(R.string.gate_state, state.phase.name, state.problem?.name ?: "—"), Modifier.testTag("account-settings-state"))
        AccountSignIn(state, controller, openBrowser)
        AccountRemovalAction(state.phase, controller)
    }
}

@Composable
private fun AccountSignIn(state: ConnectionState, controller: ConnectionController, openBrowser: () -> Unit) {
    val awaiting = state.auth as? AuthState.AwaitingUser
    if (awaiting != null) {
        Text(awaiting.verificationUrl, Modifier.testTag("verification-url"))
        DeviceLoginCode(awaiting, controller)
        Button(onClick = openBrowser, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open-browser")) {
            Text(stringResource(R.string.gate_browser))
        }
    }
    if (state.phase !in setOf(ConnectionPhase.RESTORED, ConnectionPhase.READING, ConnectionPhase.OBSERVED)) {
        Button(onClick = controller::connect, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connect")) {
            Text(stringResource(if (state.phase == ConnectionPhase.REAUTH_REQUIRED) R.string.gate_reauth else R.string.account_add))
        }
    }
    if (state.phase == ConnectionPhase.AUTHENTICATING) {
        OutlinedButton(onClick = controller::cancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel-connect")) {
            Text(stringResource(R.string.gate_cancel))
        }
    }
}

@Composable
internal fun AccountSettingsLink(open: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.account_settings_required), Modifier.testTag("account-settings-required"))
        Button(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open-account-settings")) {
            Text(stringResource(R.string.account_settings_title))
        }
    }
}
