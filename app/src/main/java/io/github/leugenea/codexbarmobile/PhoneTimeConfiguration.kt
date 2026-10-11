package io.github.leugenea.codexbarmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.ZoneId
import java.util.Locale

internal data class PhoneTimeConfiguration(val zone: ZoneId, val locale: Locale)

/** UI-only invalidation. A zone broadcast must reach skipped compositions even without B2 ticks.
 * Resource configuration supplies the effective rendering locale. Resume resamples a zone changed
 * while the screen was stopped; disposal removes both observers. No owner command or timer.
 */
@Composable
internal fun phoneTimeConfiguration(): PhoneTimeConfiguration {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locale = LocalConfiguration.current.locales[0]
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { zone = ZoneId.systemDefault() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) zone = ZoneId.systemDefault()
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(observer)
        // Close the sample/register race, including a newly opened history screen.
        zone = ZoneId.systemDefault()
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return PhoneTimeConfiguration(zone, locale)
}
