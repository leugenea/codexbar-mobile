package io.github.leugenea.codexbarmobile

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.res.Configuration
import android.security.NetworkSecurityPolicy
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real production Activity and asserts its UI, not a replacement setContent fixture. */
@RunWith(AndroidJUnit4::class)
class OfflineShellSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun launcherIsOfflineAndAllFourPreviewsRemainHonestThroughActions() {
        launch().use {
            compose.waitForIdle()
            awaitState("launcher default", Preview.Disconnected)
            assertIdentity()
            compose.onNodeWithText("No account connected")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            assertInstalledNetworkPolicy()
            compose.onNodeWithTag("show-fixture").performScrollTo().assertHasClickAction().performClick()
            awaitState("disconnected sample action", Preview.Demo)
            assertDemo()
            select(Preview.Loading)
            compose.onNodeWithText("Simulated loading only. No request is running. Choose when to show the sample.")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("show-fixture").performScrollTo().performClick()
            awaitState("finish loading sample", Preview.Demo)
            assertDemo()
            select(Preview.Error)
            compose.onNodeWithTag("retry-preview").performScrollTo().assertHasClickAction().performClick()
            awaitState("retry is only a loading preview", Preview.Loading)
            assertIdentity()
            select(Preview.Demo)
            assertDemo()
            compose.onNodeWithTag("reset-preview").performScrollTo().performClick()
            awaitState("reset removes sample", Preview.Disconnected)
            compose.onNodeWithText("Sample: 32% used").assertDoesNotExist()
            select(Preview.Disconnected)
        }
    }

    @Test
    fun everyPreviewSurvivesActivityRecreationWithoutAnAccount() {
        launch().use { scenario ->
            compose.waitForIdle()
            Preview.entries.forEach { preview ->
                select(preview)
                scenario.recreate()
                awaitState("recreate ${preview.savedKey}", preview)
                compose.onNodeWithTag("preview-${preview.savedKey}").performScrollTo().assertIsSelected()
                assertIdentity()
                if (preview == Preview.Demo) assertDemo()
            }
        }
    }

    @Test
    fun landscapeKeepsDisclaimerVisibleAndSampleActionsReachable() {
        launch().use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            var lastOrientation = Configuration.ORIENTATION_UNDEFINED
            boundedWait("landscape configuration", { "orientation=$lastOrientation" }) {
                scenario.onActivity { lastOrientation = it.resources.configuration.orientation }
                lastOrientation == Configuration.ORIENTATION_LANDSCAPE
            }
            awaitState("landscape default", Preview.Disconnected)
            select(Preview.Demo)
            compose.onNodeWithTag("usage-weekly").performScrollTo().assertIsDisplayed()
            assertIdentity()
            assertTextNotClipped("Unofficial · offline demo")
            assertTextNotClipped("Not authenticated · not connected")
            compose.onNodeWithTag("reset-preview").performScrollTo().assertIsDisplayed().performClick()
            awaitState("landscape reset", Preview.Disconnected)
            assertIdentity()
        }
    }

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals(MainActivity::class.java.name, requireNotNull(intent.component).className)
        return ActivityScenario.launch(intent)
    }

    private fun select(preview: Preview) {
        compose.onNodeWithTag("preview-${preview.savedKey}").performScrollTo().assertHasClickAction().performClick()
        awaitState("select ${preview.savedKey}", preview)
        compose.onNodeWithTag("preview-${preview.savedKey}").assertIsSelected()
        assertIdentity()
    }

    private fun assertIdentity() {
        compose.onNodeWithTag("demo-identity").assertIsDisplayed().assertTextEquals("Unofficial · offline demo")
        compose.onNodeWithTag("connection-identity").assertIsDisplayed().assertTextEquals("Not authenticated · not connected")
    }

    private fun assertDemo() {
        assertIdentity()
        compose.onNodeWithText("Demo usage · sample only").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sample: 32% used").performScrollTo().assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.32f, 0f..1f),
        )).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sample: 58% used").performScrollTo().assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.58f, 0f..1f),
        )).performScrollTo().assertIsDisplayed()
    }

    private fun assertTextNotClipped(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No native text layout for $text", layouts.isNotEmpty())
        layouts.forEach {
            // Keep the strict oracle; retain native dimensions if the hosted assertion fails.
            val geometry = "size=${it.size}; paragraph=${it.multiParagraph.width}x${it.multiParagraph.height}; " +
                "constraints=${it.layoutInput.constraints}; " +
                "overflowWidth=${it.didOverflowWidth}; overflowHeight=${it.didOverflowHeight}; " +
                "lines=${it.lineCount}; exceededMaxLines=${it.multiParagraph.didExceedMaxLines}; " +
                "density=${it.layoutInput.density.density}; fontScale=${it.layoutInput.density.fontScale}"
            assertFalse("Clipped native text: $text; $geometry", it.hasVisualOverflow)
        }
    }

    private fun awaitState(step: String, preview: Preview) {
        var lastState = "no state root"
        boundedWait(step, { lastState }) {
            lastState = Preview.entries.filter {
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "state-${it.savedKey}"))
                    .fetchSemanticsNodes().isNotEmpty()
            }.joinToString { it.savedKey }
            lastState == preview.savedKey
        }
        compose.onNodeWithTag("state-${preview.savedKey}").assertExists()
    }

    private fun boundedWait(step: String, lastState: () -> String, condition: () -> Boolean) {
        try {
            // v2 rule's scheduler advances here; no runBlocking/delay or UI-thread semantics calls.
            compose.waitUntil(timeoutMillis = 10_000, condition = condition)
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: timed out; last state=${lastState()}", error)
        }
    }

    private fun assertInstalledNetworkPolicy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertNotNull(info)
        assertEquals(
            "Installed APK must request exactly the approved permission set",
            setOf("android.permission.INTERNET",
                "io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            info.requestedPermissions.orEmpty().toSet(),
        )
        val receiver = context.packageManager.getPermissionInfo(
            "io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", 0,
        )
        assertEquals("Installed receiver permission must remain signature-only",
            PermissionInfo.PROTECTION_SIGNATURE, receiver.protectionLevel)
        assertEquals("Installed APK must disable cleartext", 0,
            context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        assertFalse("Effective global cleartext policy must be disabled",
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
        assertFalse("Local test servers must not broaden device cleartext policy",
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("localhost"))
    }
}
