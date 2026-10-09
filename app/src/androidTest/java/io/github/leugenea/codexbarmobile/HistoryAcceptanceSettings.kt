package io.github.leugenea.codexbarmobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

/** Hosted phone settings, restored even on failure; never replaces the production composition. */
internal class HistoryAcceptanceSettings {
    private var originalNight: String? = null
    private var originalFont: String? = null

    fun apply(dark: Boolean, font: Float) {
        if (originalNight == null) {
            originalNight = Regex("Night mode: (auto|no|yes)").find(shell("cmd uimode night"))?.groupValues?.get(1)
            originalFont = shell("settings get system font_scale")
        }
        requireNotNull(originalNight) { "Cannot restore hosted night mode" }
        require(originalFont == "null" || originalFont!!.toFloatOrNull() != null)
        shell("cmd uimode night ${if (dark) "yes" else "no"}")
        shell("settings put system font_scale $font")
    }

    fun restore() {
        val night = originalNight
        val font = originalFont
        originalNight = null
        originalFont = null
        try { if (night != null) shell("cmd uimode night $night") }
        finally {
            if (font == "null") shell("settings delete system font_scale")
            else if (font != null) shell("settings put system font_scale $font")
        }
    }

    fun observe(compose: ComposeTestRule, scenario: ActivityScenario<MainActivity>, landscape: Boolean,
        dark: Boolean, font: Float) {
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        var last = "not observed"
        try {
            compose.waitUntil(8_000) {
                var ready = false
                scenario.onActivity {
                    val config = it.resources.configuration
                    val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    last = "orientation=${config.orientation}, dark=$night, fontScale=${config.fontScale}"
                    ready = config.orientation == expected && night == dark && config.fontScale == font
                }
                ready
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Production Activity effective configuration: $last", error)
        }
        compose.waitForIdle()
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }
}
