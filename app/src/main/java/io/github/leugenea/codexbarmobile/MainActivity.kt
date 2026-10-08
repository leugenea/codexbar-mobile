package io.github.leugenea.codexbarmobile

import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import io.github.leugenea.codexbarmobile.auth.AuthState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    internal lateinit var connection: ConnectionController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Activities observe the process owner; destruction never closes its work.
        connection = NativeConnection.get(applicationContext)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                // Only harmless preview/navigation choices are Bundle-restored, never connection state.
                var savedPreview by rememberSaveable { mutableStateOf(Preview.Disconnected.savedKey) }
                var connectionTab by rememberSaveable { mutableStateOf(false) }
                val connectionState by connection.state.collectAsState()
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { connectionTab = false }, modifier = Modifier.testTag("offline-tab")) {
                            Text(stringResource(R.string.offline_tab))
                        }
                        OutlinedButton(onClick = { connectionTab = true }, modifier = Modifier.testTag("connection-tab")) {
                            Text(stringResource(R.string.connection_tab))
                        }
                    }
                    if (connectionTab) ConnectionScreen(connectionState, connection, ::openVerificationBrowser)
                    else OfflineShell(
                        state = OfflineShellState.restore(savedPreview),
                        onStateChange = { savedPreview = it.preview.savedKey },
                    )
                }
            }
        }
    }

    internal fun openVerificationBrowser() {
        val awaiting = connection.state.value.auth as? AuthState.AwaitingUser ?: return
        val intent = Intent(Intent.ACTION_VIEW, awaiting.verificationUrl.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            browserLauncher(this, intent)
        } catch (_: ActivityNotFoundException) {
            connection.browserFailed()
        } catch (_: SecurityException) {
            connection.browserFailed()
        }
    }

    internal companion object {
        var browserLauncher: (MainActivity, Intent) -> Unit = { activity, intent -> activity.startActivity(intent) }
    }
}

@Composable
private fun OfflineShell(state: OfflineShellState, onStateChange: (OfflineShellState) -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.shell_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.demo_identity),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag("demo-identity"),
                )
                Text(
                    stringResource(R.string.connection_identity),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().testTag("connection-identity"),
                )
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.preview_heading), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() })
            // Two short equal-width rows wrap at large font sizes, unlike a single rigid toolbar.
            listOf(listOf(Preview.Disconnected, Preview.Loading), listOf(Preview.Error, Preview.Demo)).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { preview ->
                        FilterChip(
                            selected = state.preview == preview,
                            onClick = { onStateChange(state.select(preview)) },
                            label = { Text(stringResource(preview.labelResource())) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("preview-${preview.savedKey}"),
                        )
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth().testTag("state-${state.preview.savedKey}")) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(state.preview.titleResource()),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                    )
                    when (state.preview) {
                        Preview.Disconnected -> {
                            Text(stringResource(R.string.disconnected_detail))
                            Button(onClick = { onStateChange(state.showFixture()) }, modifier = Modifier.testTag("show-fixture")) {
                                Text(stringResource(R.string.show_fixture))
                            }
                        }
                        Preview.Loading -> {
                            // A deterministic preview: no spinner timer, delayed transition or network request.
                            Text(stringResource(R.string.loading_detail))
                            Button(onClick = { onStateChange(state.showFixture()) }, modifier = Modifier.testTag("show-fixture")) {
                                Text(stringResource(R.string.finish_preview))
                            }
                        }
                        Preview.Error -> {
                            Text(stringResource(R.string.error_detail))
                            Button(onClick = { onStateChange(state.retryPreview()) }, modifier = Modifier.testTag("retry-preview")) {
                                Text(stringResource(R.string.retry_preview))
                            }
                        }
                        Preview.Demo -> {
                            Text(stringResource(R.string.fixture_detail))
                            val usage = requireNotNull(state.demoUsage)
                            UsageSample(R.string.five_hour_sample, usage.fiveHourUsedPercent, "usage-five-hour")
                            UsageSample(R.string.weekly_sample, usage.weeklyUsedPercent, "usage-weekly")
                            Text(stringResource(R.string.reset_sample), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { onStateChange(state.resetPreview()) },
                modifier = Modifier.fillMaxWidth().testTag("reset-preview"),
            ) {
                Text(stringResource(R.string.reset_preview))
            }
            Text(stringResource(R.string.no_live_features), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UsageSample(label: Int, usedPercent: Int, tag: String) {
    Column(modifier = Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.sample_used_percent, usedPercent))
        LinearProgressIndicator(progress = { usedPercent / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

private fun Preview.labelResource(): Int = when (this) {
    Preview.Disconnected -> R.string.preview_disconnected
    Preview.Loading -> R.string.preview_loading
    Preview.Error -> R.string.preview_error
    Preview.Demo -> R.string.preview_demo
}

private fun Preview.titleResource(): Int = when (this) {
    Preview.Disconnected -> R.string.disconnected_title
    Preview.Loading -> R.string.loading_title
    Preview.Error -> R.string.error_title
    Preview.Demo -> R.string.fixture_title
}
