package io.github.leugenea.codexbarmobile

import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
                val connectionState by connection.state.collectAsState()
                // Launch and recreation attach the same identity-tagged STARTED observer.
                // Fresh restored owners remain dormant; already activated owners may resume reads.
                ObserveUsageLifecycle(connection, lifecycle)
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        ConnectionScreen(connectionState, connection, ::openVerificationBrowser, historyUiObserver)
                    }
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
        @set:androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
        internal var historyUiObserver: HistoryUiObserver? = null
        var browserLauncher: (MainActivity, Intent) -> Unit = { activity, intent -> activity.startActivity(intent) }
    }
}

@Composable
private fun ObserveUsageLifecycle(controller: ConnectionController, lifecycle: Lifecycle) {
    LaunchedEffect(controller, lifecycle) {
        val observer = Any()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.usageForeground(observer, true)
            try { awaitCancellation() } finally { controller.usageForeground(observer, false) }
        }
    }
}
