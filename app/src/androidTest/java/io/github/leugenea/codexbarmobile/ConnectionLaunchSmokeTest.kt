package io.github.leugenea.codexbarmobile

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.res.Configuration
import android.os.Bundle
import android.security.NetworkSecurityPolicy
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.history.BreakReason
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Production launcher/security/lifecycle with an isolated real store and synthetic transport only. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ConnectionLaunchSmokeTest {
    private val checkpoint = LaunchForegroundCheckpoint()
    @get:Rule val compose = createEmptyComposeRule(effectContext = checkpoint.context)
    private val fixture = HistoryNavigationFixture()
    @Before fun prepare() { fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun launcherReachesRealDisconnectedScreenWithInstalledSecurityPolicy() {
        launch().use { scenario ->
            ready(ConnectionPhase.IDLE)
            compose.onNodeWithTag("live-status").assertTextEquals("Disconnected · use the connection controls below to sign in")
            compose.onNodeWithTag("live-refresh").assertIsNotEnabled()
            compose.onNodeWithTag("live-five-hour-progress").assertDoesNotExist()
            compose.onNodeWithTag("live-weekly-progress").assertDoesNotExist()
            assertRetiredSurfaceAbsent()
            assertInstalledNetworkPolicy()
            assertSecureWindow(scenario)
            compose.onNodeWithTag("connect").performScrollTo().assertIsDisplayed().assertHasClickAction()
            assertEquals(0L, fixture.transport.requests.get())
        }
    }

    @Test fun recreationIgnoresLegacySelectionAndKeepsRealDisconnectedOwner() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val legacy = LegacySelection()
        application.registerActivityLifecycleCallbacks(legacy)
        try {
            launch().use { scenario ->
                ready(ConnectionPhase.IDLE)
                val shared = fixture.owner
                for (selection in listOf("disconnected", "loading", "error", "demo", "future-invalid")) {
                    legacy.selection = selection
                    scenario.recreate()
                    ready(ConnectionPhase.IDLE)
                    scenario.onActivity { assertSame(shared, it.connection) }
                    assertSecureWindow(scenario)
                    assertRetiredSurfaceAbsent()
                    compose.onNodeWithTag("live-status").assertTextEquals("Disconnected · use the connection controls below to sign in")
                    assertEquals(0L, fixture.transport.requests.get())
                }
                assertEquals(5, legacy.saved)
                assertEquals(listOf("disconnected", "loading", "error", "demo", "future-invalid"), legacy.restored)
            }
        } finally { application.unregisterActivityLifecycleCallbacks(legacy) }
    }

    @Test fun landscapeKeepsRealIdentityAndConnectionHistoryControlsReachable() {
        launch().use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            var orientation = Configuration.ORIENTATION_UNDEFINED
            boundedWait("landscape configuration") {
                scenario.onActivity { orientation = it.resources.configuration.orientation }
                orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            ready(ConnectionPhase.IDLE)
            assertTextNotClipped("Unofficial · local connection")
            assertTextNotClipped("Identity unverified · one local session. Token receipt and HTTP 200 do not establish an account or workspace. No account identity is inferred.")
            compose.onNodeWithTag("connect").performScrollTo().assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithTag("history-toggle").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("connection-history").assertExists()
            compose.onNodeWithTag("history-readiness").performScrollTo().assertTextEquals("unavailable")
            compose.onNodeWithTag("history-toggle").performScrollTo().performClick()
            compose.onNodeWithTag("live-usage").assertExists()
            assertSecureWindow(scenario)
            assertEquals(0L, fixture.transport.requests.get())
        }
    }

    @Test fun defaultRelaunchResumesActivatedOwnerOnceAndDuplicateTriggersCoalesce() {
        fixture.mutationDispatcher = checkpoint.dispatcher
        fixture.transport.operationEpoch = { checkpoint.ownerEpoch }
        fixture.transport.usageAdmission = checkpoint::admitted
        val shared = fixture.owner
        // Constructing/restoring the holder before UI activation is dormant.
        boundedWait("fresh process owner settles before launch") { shared.state.value.phase == ConnectionPhase.IDLE }
        assertEquals(0L, fixture.transport.requests.get())
        launch().use {
            ready(ConnectionPhase.IDLE)
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            settleConnectionCommands(compose, shared, "connect admission")
            boundedWait("initial authenticated cycle and history admission finish") {
                shared.state.value.let { it.phase == ConnectionPhase.OBSERVED && !it.refresh.refreshing } &&
                    shared.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L
            }
            assertEquals(5L, fixture.transport.requests.get())
            assertEquals(2L, fixture.transport.gets.get())
        }
        boundedWait("finish retires foreground history authority") { shared.historySnapshots.value.generation == null }
        settleConnectionCommands(compose, shared, "finish foreground loss")
        val success = shared.state.value.refresh.usage.success
        fixture.transport.heldPath = ReadOperation.USAGE.path
        checkpoint.install(fixture.transport)
        try {
            launch().use { scenario ->
                awaitConnectionScreen(compose, shared)
                lateinit var activity: MainActivity
                scenario.onActivity { activity = it; assertSame(shared, it.connection) }
                boundedWait("default launch reaches a live, owner-attributed return checkpoint") {
                    var completed = false
                    // Fence actual lifecycle delivery only during the checkpoint. Earlier fast
                    // STOP/STARTs remain real events, with independent owner-lane provenance.
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        checkpoint.awaitOwner("foreground commands before launch checkpoint", shared) { shared.commandsSettled() }
                        val selected = fixture.transport.liveHeldCall(ReadOperation.USAGE.path)
                        if (selected != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                            checkpoint.isCurrent(selected.epoch)) {
                            val epoch = requireNotNull(selected.epoch)
                            try {
                                assertSame(shared, fixture.owner)
                                assertEquals("Launch/relaunch shares one constructed owner", 1, fixture.ownerCreations)
                                // Initial five requests plus THIS admitted return, never a subtraction
                                // from global totals or a budget inferred from observed calls.
                                checkpoint.assertCampaignCounts(epoch, 6L, 3L)
                                checkpoint.withoutForegroundOrigin { shared.readUsage(); shared.readUsage() }
                                checkpoint.awaitOwner("duplicate foreground triggers", shared) { shared.commandsSettled() }
                                assertSame("Reply must carry the exact still-live operation", selected,
                                    fixture.transport.liveHeldCall(ReadOperation.USAGE.path))
                                checkpoint.assertCampaignCounts(epoch, 6L, 3L)
                                selected.reply()
                                checkpoint.awaitOwner("released return settles and records its observation", shared) {
                                    combine(shared.state, shared.historySnapshots) { state, history ->
                                        state.phase == ConnectionPhase.OBSERVED && !state.refresh.refreshing &&
                                            state.refresh.usage.success !== success &&
                                            history.storage?.lastAdmitted?.ordinal == 3L
                                    }.first { it }
                                }
                                // State publication can precede finished()'s drain. The serial
                                // receipt must execute after that owner block, while Main stays fenced.
                                checkpoint.awaitOwner("released return completion commands", shared) { shared.commandsSettled() }
                                assertFalse("Settled return cannot leave another cycle active", shared.state.value.refresh.refreshing)
                                // Keep usage held: a sabotaged pending duplicate cannot escape as
                                // a fast completed pair before this positive settled checkpoint.
                                assertTrue("Returned observation records the BACKGROUND break",
                                    BreakReason.BACKGROUND in shared.historySnapshots.value.points.last().segment.breaks)
                                checkpoint.assertCampaignCounts(epoch, 7L, 4L)
                                assertEquals(1L, fixture.transport.counts().getValue("/oauth/token"))
                                checkpoint.completed(epoch)
                                completed = true
                            } finally { selected.reply() }
                        }
                    }
                    completed
                }
            }
            boundedWait("return finish retires history and cancels every live usage") {
                shared.historySnapshots.value.generation == null && !shared.state.value.refresh.refreshing &&
                    fixture.transport.liveHeldCall(ReadOperation.USAGE.path) == null
            }
            compose.waitForIdle()
            settleConnectionCommands(compose, shared, "all real return epochs stopped")
            checkpoint.assertStoppedEpochs()
        } finally {
            fixture.transport.releaseHeld()
            checkpoint.close()
        }
    }

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals(MainActivity::class.java.name, requireNotNull(intent.component).className)
        return ActivityScenario.launch(intent)
    }

    private fun ready(phase: ConnectionPhase) {
        awaitConnectionScreen(compose, fixture.owner)
        boundedWait("default screen $phase") { fixture.owner.state.value.phase == phase }
    }

    private fun assertRetiredSurfaceAbsent() {
        listOf("offline-tab", "connection-tab", "preview-disconnected", "preview-loading", "preview-error",
            "preview-demo", "show-fixture", "retry-preview", "reset-preview", "state-demo").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Sample: 32% used").assertDoesNotExist()
        compose.onNodeWithText("Sample: 58% used").assertDoesNotExist()
    }

    private fun assertSecureWindow(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            assertTrue("Real launcher keeps FLAG_SECURE", it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    }

    private fun assertTextNotClipped(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No native text layout for $text", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("Vertical overflow for $text", layout.didOverflowHeight)
            assertTrue(layout.lineCount > 0)
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue("Complete native line for $text", layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width)
            }
        }
    }

    private fun boundedWait(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 10_000, condition = condition) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: last state=${fixture.owner.state.value}", error) }
    }

    /** Unconsumed obsolete selections cannot route the recreated production composition. */
    private class LegacySelection : Application.ActivityLifecycleCallbacks {
        var selection = "demo"
        var saved = 0
        val restored = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun onActivityPostSaveInstanceState(activity: Activity, outState: Bundle) {
            if (activity !is MainActivity) return
            saved++
            // Include plain obsolete names and old saveable-registry value shapes. MainActivity
            // no longer consumes a selection or positional saveable entry of any identity.
            outState.putString("savedPreview", selection)
            outState.putBoolean("connectionTab", false)
            val values = Bundle().apply {
                putStringArrayList("savedPreview", arrayListOf(selection))
                putSerializable("connectionTab", arrayListOf(false))
            }
            val registry = outState.getBundle("androidx.lifecycle.BundlableSavedStateRegistry.key") ?: Bundle()
            registry.putBundle("SaveableStateRegistry:16908290", values)
            outState.putBundle("androidx.lifecycle.BundlableSavedStateRegistry.key", registry)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) savedInstanceState?.getString("savedPreview")?.let(restored::add)
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
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
