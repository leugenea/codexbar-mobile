package io.github.leugenea.codexbarmobile

import android.app.LocaleManager
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.test.filters.SdkSuppress
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** Real production Activity, process owner, A9/B2 state and protected store; synthetic data only. */
@RunWith(AndroidJUnit4::class)
@RequiresApi(33)
@SdkSuppress(minSdkVersion = 33)
class LiveUsageScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalFactory = NativeConnection.factory
    private val originalLocale = Locale.getDefault()
    private val originalZone = TimeZone.getDefault()
    private lateinit var originalAppLocales: LocaleList
    private val clock = ScreenClock()
    private val fake = ScreenTransport()
    private val owner get() = NativeConnection.get(context)

    @Before fun installSyntheticSession() {
        Locale.setDefault(Locale.US)
        val localeManager = context.getSystemService(LocaleManager::class.java)
        originalAppLocales = localeManager.applicationLocales
        localeManager.applicationLocales = LocaleList(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        NativeConnection.factory = { NativeConnection.create(it, fake, clock) { kotlinx.coroutines.delay(it) } }
        NativeConnection.resetForTests()
        val store = store()
        assertTrue(store.delete(store.openSession()) is CredentialResult.Success)
        val generation = store.openSession()
        assertTrue(store.replace(CredentialEnvelope(generation,
            SensitiveValue.copyOf("synthetic-b3-native-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-b3-native-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
    }

    @After fun removeSyntheticSession() {
        try {
            NativeConnection.resetForTests()
            val store = store()
            assertTrue(store.delete(store.openSession()) is CredentialResult.Success)
        } finally {
            NativeConnection.factory = originalFactory
            context.getSystemService(LocaleManager::class.java).applicationLocales = originalAppLocales
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun fractionalWeeklyOnlyAndMissingResetHaveExactAccessibleSemantics() {
        fake.usage = response(weekly("12.345678901234567890"))
        launchLive().use {
            read()
            text("live-weekly-percent", "12.345678901234567890% used")
            progress("live-weekly", 0.12345679f, "Weekly window · 12.345678901234567890% used", "Provider-reported usage")
            text("live-five-hour-status", "Window or usage percentage unavailable · not zero")
            compose.onNodeWithTag("live-five-hour-progress").assertDoesNotExist()
            text("live-weekly-reset-relative", "Reset: Time unavailable")
            compose.onNodeWithTag("live-weekly-reset-absolute").assertDoesNotExist()
            compose.onNodeWithTag("live-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }
    }

    @Test fun manualRefreshRetainsValuesAndAnnouncesRefreshingThenError() {
        launchLive().use {
            read()
            fake.hold = true
            click("live-refresh")
            waitFor("manual refresh reaches synthetic usage") { fake.held.size == 1 && owner.state.value.refresh.refreshing }
            text("live-refreshing", "Refreshing · last successful values retained")
            compose.onNodeWithTag("live-refreshing").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithTag("live-refresh").assertIsNotEnabled()
            text("live-five-hour-percent", "12.5% used")
            fake.held.single()(response("{}", 403))
            waitFor("manual error settles") { !owner.state.value.refresh.refreshing && owner.state.value.refresh.usage.attempt?.error == ReadError.FORBIDDEN }
            text("live-error", "Usage access forbidden · this does not imply session expiry or zero quota")
            compose.onNodeWithTag("live-error").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            text("live-five-hour-percent", "12.5% used")
            assertEquals(4, fake.gets.get())
        }
    }

    @Test fun staleAndPassedResetReevaluateLocallyWithoutZeroOrNetwork() {
        fake.usage = response(fiveHour("47.25", reset = ",\"reset_after_seconds\":500"))
        launchLive().use {
            read()
            text("live-five-hour-reset-relative", "Reset: <1h remaining")
            val requests = fake.gets.get()
            clock.advance(500_000)
            waitFor("local reset evaluation crosses original instant") {
                owner.state.value.refresh.evaluatedAt == clock.now().wall
            }
            text("live-five-hour-reset-relative", "Reset: Reset passed · awaiting refresh")
            compose.onNodeWithTag("live-five-hour-reset-relative").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            text("live-five-hour-percent", "47.25% used")
            clock.advance(400_000)
            waitFor("B2 local tick marks usage stale") { owner.state.value.refresh.usage.stale }
            text("live-status", "Stale usage · last successful values retained")
            text("live-stale", "Usage has not succeeded for at least 15 minutes. These values may be out of date.")
            text("live-five-hour-percent", "47.25% used")
            assertEquals(requests, fake.gets.get())
        }
    }

    @Test fun exhaustedBarDoesNotOverrideAllowedAndMalformedSiblingsRemainVisible() {
        fake.usage = response(fiveHour("100.00"))
        launchLive().use {
            read()
            progress("live-five-hour", 1f, "Five-hour window · 100.00% used", "Usage bar exhausted · permission is reported separately")
            text("live-allowed", "Provider permission: allowed")
            text("live-limit", "Provider limit status: limit not reached")
            fake.usage = response(fiveHour("0", allowed = false, reached = true))
            read()
            progress("live-five-hour", 0f, "Five-hour window · 0% used", "Provider-reported usage")
            text("live-allowed", "Provider permission: not allowed")
            text("live-limit", "Provider limit status: limit reached")
            fake.usage = response(fiveHour("7.25", reset = ",\"reset_at\":false"))
            read()
            text("live-five-hour-percent", "7.25% used")
            text("live-five-hour-warning", "Some window fields are malformed. Independently known usage and reset information are retained.")
            text("live-five-hour-reset-relative", "Reset: Time unavailable")
            fake.usage = response(fiveHour("false", reset = ",\"reset_after_seconds\":500"))
            read()
            text("live-five-hour-status", "Malformed window or usage percentage · no value inferred")
            compose.onNodeWithTag("live-five-hour-progress").assertDoesNotExist()
            text("live-five-hour-reset-relative", "Reset: <1h remaining")
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertExists()
        }
    }

    @Test fun knownResetShowsSimultaneousLabelsAndRepeatedHourOffset() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        clock.wall = Instant.parse("2026-11-01T05:00:00Z")
        fake.usage = response(fiveHour("12.5", reset = ",\"reset_at\":1793514600"))
        launchLive().use {
            read()
            text("live-five-hour-reset-absolute", "Reset: Nov 1, 2026, 01:00 UTC-05:00 (hour precision)")
            text("live-five-hour-reset-relative", "Reset: 0 days 1 hour remaining")
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertExists()
            compose.onNodeWithTag("live-five-hour-reset-relative").assertExists()
        }
    }

    @Test fun landscapeLongCopyHasNoNativeTextOverflow() {
        fake.usage = response(fiveHour("12.345678901234567890", reset = ",\"reset_after_seconds\":500"))
        launchLive().use { scenario ->
            read()
            val previous = owner.state.value.refresh.usage.attempt
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            var orientation = Configuration.ORIENTATION_UNDEFINED
            waitFor("real landscape configuration") {
                scenario.onActivity { orientation = it.resources.configuration.orientation }
                orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            waitFor("landscape resumed refresh settled") {
                owner.state.value.refresh.usage.attempt !== previous && !owner.state.value.refresh.refreshing
            }
            listOf("live-identity", "live-rounding", "live-five-hour-status", "live-five-hour-percent",
                "live-five-hour-reset-relative", "live-five-hour-reset-absolute").forEach(::noOverflow)
            fake.usage = response("{}", 403)
            read()
            noOverflow("live-error")
            noOverflow("live-status")
            compose.onNodeWithTag("live-refresh").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun recreationRestoresLiveTabAndOwnerAndOfflinePreviewStaysSeparate() {
        launchLive().use { scenario ->
            read()
            val shared = owner
            val previous = shared.state.value.refresh.usage.attempt
            scenario.recreate()
            waitFor("recreated live root and resumed cycle settle") {
                owner.state.value.refresh.usage.attempt !== previous &&
                owner.state.value.phase == ConnectionPhase.OBSERVED && !owner.state.value.refresh.refreshing &&
                    compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "live-usage")).fetchSemanticsNodes().isNotEmpty()
            }
            scenario.onActivity { assertSame(shared, it.connection) }
            text("live-five-hour-percent", "12.5% used")
            click("offline-tab")
            waitFor("offline root replaces live data") {
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "state-disconnected")).fetchSemanticsNodes().isNotEmpty()
            }
            settleOwnerCommands()
            val before = fake.gets.get()
            click("preview-demo")
            text("demo-identity", "Unofficial · offline demo", scroll = false)
            compose.onNodeWithTag("live-usage").assertDoesNotExist()
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("state-demo").assertExists()
            assertEquals(before, fake.gets.get())
        }
    }

    @Test fun disconnectedAndReauthorizationHaveAccessibleStatusAndNoInventedUsage() {
        val store = store()
        assertTrue(store.delete(store.openSession()) is CredentialResult.Success)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            waitFor("no synthetic credentials restores disconnected") { owner.state.value.phase == ConnectionPhase.IDLE }
            click("connection-tab")
            text("live-status", "Disconnected · use the connection controls below to sign in")
            compose.onNodeWithTag("live-refresh").assertIsNotEnabled()
            compose.onNodeWithTag("live-five-hour-progress").assertDoesNotExist()
            assertEquals(0, fake.gets.get())
        }
        NativeConnection.resetForTests()
        // A real missing-key restoration exercises the owner's durable fail-closed path.
        val generation = store.openSession()
        assertTrue(store.replace(CredentialEnvelope(generation,
            SensitiveValue.copyOf("synthetic-b3-native-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-b3-native-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(KeystoreCredentialStore.alias(context, NativeConnection.session(context)))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            waitFor("missing key publishes durable reauthorization") { owner.state.value.phase == ConnectionPhase.REAUTH_REQUIRED }
            click("connection-tab")
            text("live-status", "Authorization required · sign in again using the controls below")
            compose.onNodeWithTag("live-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithTag("live-weekly-progress").assertDoesNotExist()
            compose.onNodeWithTag("connect").performScrollTo().assertTextEquals("Sign in again")
            assertEquals(0, fake.gets.get())
        }
    }

    private fun launchLive(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        waitFor("effective rendered US resource locale") {
            var locale: Locale? = null
            scenario.onActivity { locale = it.resources.configuration.locales[0] }
            locale == Locale.US
        }
        waitFor("restored dormant synthetic session") { owner.state.value.phase == ConnectionPhase.RESTORED }
        assertEquals(0, fake.gets.get())
        click("connection-tab")
        text("live-status", "Usage unavailable · refresh to request an observation")
        return scenario
    }

    private fun read() {
        val previous = owner.state.value.refresh.usage.attempt
        click("live-refresh")
        waitFor("requested usage cycle settles") {
            owner.state.value.refresh.usage.attempt !== previous && !owner.state.value.refresh.refreshing
        }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag !in setOf("offline-tab", "connection-tab")) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    private fun text(tag: String, expected: String, scroll: Boolean = true) {
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.assertTextEquals(expected)
    }

    private fun progress(tag: String, fraction: Float, description: String, status: String) {
        compose.onNodeWithTag("$tag-progress").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(fraction, 0f..1f)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
    }

    private fun noOverflow(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No native text layout for $tag", layouts.isNotEmpty())
        layouts.forEach { assertFalse("$tag: size=${it.size}, constraints=${it.layoutInput.constraints}", it.hasVisualOverflow) }
    }

    private fun waitFor(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 10_000, condition = condition) }
        catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: phase=${owner.state.value.phase}, refresh=${owner.state.value.refresh}", error)
        }
        compose.waitForIdle()
    }
    private fun settleOwnerCommands() {
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) { owner.commandsSettled() }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Offline foreground-loss command did not settle: ${owner.state.value}", error)
        }
        waitFor("offline cancellation is complete") { !owner.state.value.refresh.refreshing }
    }

    private fun store() = KeystoreCredentialStore(context, NativeConnection.session(context))

    private class ScreenClock : TransportClock {
        @Volatile var wall: Instant = Instant.parse("2026-10-08T00:00:00Z")
        val monotonic = AtomicLong()
        override fun now() = TransportTime(wall, monotonic.get())
        fun advance(millis: Long) { wall = wall.plusMillis(millis); monotonic.addAndGet(millis) }
    }

    private class ScreenTransport : AuthTransport {
        @Volatile var usage: TransportResult = response(fiveHour("12.5"))
        @Volatile var hold = false
        val gets = AtomicInteger()
        val held = CopyOnWriteArrayList<(TransportResult) -> Unit>()
        override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline,
            terminal: (TransportResult) -> Unit): CancellationHandle {
            check(request is ProviderHttpRequest.Get) { "Unexpected synthetic method" }
            gets.incrementAndGet()
            when (request.url.encodedPath) {
                ReadOperation.USAGE.path -> if (hold) held += terminal else terminal(usage)
                ReadOperation.RESET_INVENTORY.path -> terminal(response("""{"available_count":0,"credits":[]}"""))
                else -> error("Unexpected synthetic path")
            }
            return CancellationHandle {}
        }
    }

    private companion object {
        fun fiveHour(percent: String, allowed: Boolean = true, reached: Boolean = false, reset: String = "") =
            """{"rate_limit":{"allowed":$allowed,"limit_reached":$reached,"primary_window":{"limit_window_seconds":18000,"used_percent":$percent$reset}}}"""
        fun weekly(percent: String) = """{"rate_limit":{"primary_window":{"limit_window_seconds":604800,"used_percent":$percent},"secondary_window":null}}"""
        fun response(body: String, status: Int = 200) = TransportResult.Response.bounded(status, body.toByteArray())
    }
}
