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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
                val displayedAuth = connectionState.auth as? AuthState.AwaitingUser
                // Launch and recreation attach the same identity-tagged STARTED observer.
                // Fresh restored owners remain dormant; already activated owners may resume reads.
                ObserveUsageLifecycle(lifecycle) { NativeConnection.observeDashboard(applicationContext) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        key(root) {
                            ConnectionScreen(connectionState, root.controller, {
                                displayedAuth?.let { openVerificationBrowser(root.controller, it) }
                            }, historyUiObserver, root)
                        }
                    }
                }
            }
        }
    }

    internal fun openVerificationBrowser() {
        val target = connection
        (target.state.value.auth as? AuthState.AwaitingUser)?.let { openVerificationBrowser(target, it) }
    }

    internal fun openVerificationBrowser(target: ConnectionController, awaiting: AuthState.AwaitingUser) = lifecycleScope.launch {
        val url = target.verificationUrl(awaiting) ?: return@launch
        // Admission suspends off Main. Recheck publication/retirement before this synchronous
        // platform effect; never reacquire whichever attempt or dashboard is current now.
        val state = target.state.value
        if (state.auth !== awaiting || state.operation !== target.accountRemoval) return@launch
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            browserLauncher(this, intent)
        } catch (_: ActivityNotFoundException) {
            target.browserFailed(awaiting)
        } catch (_: SecurityException) {
            target.browserFailed(awaiting)
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
