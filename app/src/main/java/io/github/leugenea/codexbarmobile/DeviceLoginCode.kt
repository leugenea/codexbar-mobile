package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.auth.AuthState
import kotlinx.coroutines.launch

/** No saver/selection container: only this explicit action may export the visible user code. */
@Composable
internal fun DeviceLoginCode(awaiting: AuthState.AwaitingUser, controller: ConnectionController) {
    val context = LocalContext.current
    val clipboard = remember(context) { AndroidDeviceCodeClipboard(context) }
    val scope = rememberCoroutineScope()
    var copied by remember(controller, awaiting) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(awaiting.userCode.copyBytes().toString(Charsets.UTF_8), Modifier.weight(1f).testTag("device-code"))
        TextButton(onClick = {
            // Main-thread UI check; the owner repeats it atomically with the actual write.
            if (controller.state.value.auth === awaiting) scope.launch {
                copied = controller.copyDeviceCode(awaiting, clipboard)
            }
        }, modifier = Modifier.testTag("copy-device-code")) {
            Text(stringResource(R.string.device_code_copy))
        }
    }
    if (copied) Text(stringResource(R.string.device_code_copied),
        Modifier.testTag("device-code-copied").semantics { liveRegion = LiveRegionMode.Polite })
}
