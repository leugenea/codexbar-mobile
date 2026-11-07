package io.github.leugenea.codexbarmobile

import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import io.github.leugenea.codexbarmobile.auth.AuthState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private lateinit var dashboard: kotlinx.coroutines.flow.StateFlow<DashboardSelection?>
    internal val connection: ConnectionController get() = requireNotNull(dashboard.value).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activities observe the process owner; destruction never closes its work.
        dashboard = NativeConnection.dashboard(applicationContext)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val selection by dashboard.collectAsState()
                val root = requireNotNull(selection)
                val connectionState by root.controller.state.collectAsState()
                // Launch and recreation attach the same identity-tagged STARTED observer.
                // Fresh restored owners remain dormant; already activated owners may resume reads.
                ObserveUsageLifecycle(lifecycle) { NativeConnection.observeDashboard(applicationContext) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        key(root) {
                            ConnectionScreen(connectionState, root.controller, { openVerificationBrowser(root.controller) }, historyUiObserver, root)
                        }
                    }
                }
            }
        }
    }

    internal fun openVerificationBrowser() = openVerificationBrowser(connection)

    private fun openVerificationBrowser(target: ConnectionController) {
        val awaiting = target.state.value.auth as? AuthState.AwaitingUser ?: return
        val intent = Intent(Intent.ACTION_VIEW, awaiting.verificationUrl.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            browserLauncher(this, intent)
        } catch (_: ActivityNotFoundException) {
            target.browserFailed()
        } catch (_: SecurityException) {
            target.browserFailed()
        }
    }

    internal companion object {
        @set:androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
        internal var historyUiObserver: HistoryUiObserver? = null
        var browserLauncher: (MainActivity, Intent) -> Unit = { activity, intent -> activity.startActivity(intent) }
    }
}

@Composable
private fun ObserveUsageLifecycle(lifecycle: Lifecycle, register: suspend () -> DashboardObserver) {
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val observer = register()
            try { awaitCancellation() } finally { withContext(NonCancellable) { observer.stop() } }
        }
    }
}
