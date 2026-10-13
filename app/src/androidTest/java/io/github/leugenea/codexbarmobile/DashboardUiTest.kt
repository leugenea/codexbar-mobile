package io.github.leugenea.codexbarmobile

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Activity/default owner and isolated SQLite/Keystore; hosted-only synthetic campaign. */
@RunWith(AndroidJUnit4::class)
class DashboardUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val isolation = HistoryIsolationFixture()
    private val fixture get() = isolation.native
    private val settings = HistoryAcceptanceSettings()
    private val owner get() = fixture.owner
    private val capture = DashboardCapture(compose)
    @Before fun prepare() { capture.install(); isolation.prepare(); owner.usageForeground(fixture.observer, true); settleConnectionCommands(compose, owner, "dashboard fixture foreground") }
    @After fun cleanup() { try { isolation.cleanup() } finally { try { capture.restore() } finally { settings.restore() } } }

    @Test fun dashboardShowsBothCurrentWindowsWithoutHistoryNavigationAndRetainsMeasuredReferencePixels() {
        launch().use { scenario ->
            login()
            for (kind in listOf(WindowKind.FIVE_HOUR, WindowKind.WEEKLY)) assertCurrentDrawing(recordedHistory(owner, kind), 1, 0, 1)
            compose.onNodeWithTag("history-select-FIVE_HOUR").assertDoesNotExist()
            compose.onNodeWithTag("history-page-cursor").assertDoesNotExist()
            val before = fixture.transport.counts()
            capture.capture("dashboard-main-portrait-light", scenario, fixture, "history-chart-five-hour-equivalent", WindowKind.FIVE_HOUR)
            capture.capture("dashboard-weekly-portrait-light", scenario, fixture, "history-chart-weekly-equivalent", WindowKind.WEEKLY)
            val weekly = compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("banked-disclosure-toggle").performScrollTo().assertIsDisplayed()
            assertTrue(weekly.height > 0)
            compose.onNodeWithTag("banked-disclosure-item-0").assertDoesNotExist()
            assertEquals(before, fixture.transport.counts())
        }
    }

    @Test fun dashboardMissingEmptySingleAndFailedHistoryHaveOneHonestLinePerWindow() {
        launch().use {
            for (kind in listOf("FIVE_HOUR", "WEEKLY")) compose.onAllNodesWithTag("dashboard-no-info-$kind").assertCountEquals(1)
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(0L, fixture.transport.requests.get())
            isolation.failRead = true
            click("connect")
            await("accepted live values with failed preappend") { owner.historySnapshots.value.lostSamples == 1L && !owner.state.value.refresh.refreshing }
            for (kind in listOf("FIVE_HOUR", "WEEKLY")) compose.onAllNodesWithTag("dashboard-no-info-$kind").assertCountEquals(1)
            assertNotNull(owner.currentHistoryDisplayContext()!!.periods.fiveHour.window)
            isolation.failRead = false
            owner.queryHistory(HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR))
            settleConnectionCommands(compose, owner, "explicit empty query and main pair reobservation")
            await("known current identity but real empty history with loss") {
                owner.historySnapshots.value.currentPages?.fiveHour?.content == HistoryContent.EMPTY
            }
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
            assertCurrentDrawing(recordedHistory(owner), 0, 0, 0)
            fixture.transport.advance(1000); owner.readUsage()
            await("first actual successful measurement after durable loss gap") { recordedHistory(owner).points.map { it.observation.ordinal } == listOf(2L) && !owner.state.value.refresh.refreshing }
            compose.onNodeWithTag("history-chart-five-hour-equivalent").assertTextEquals("One recorded observation · no observed trend")
            assertCurrentDrawing(recordedHistory(owner), 1, 0, 1)
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("actual initial gap and subsequent measurement retained in Page") {
                owner.historySnapshots.value.storage?.entries?.map { it.id.ordinal } == listOf(1L, 2L)
            }
            assertEquals(HistoryGap.READ_ERROR, owner.historySnapshots.value.storage!!.entries.first().gap)
            assertNull(owner.historySnapshots.value.storage!!.entries.last().gap)
            returnDashboard(compose, owner)
            fixture.transport.usageBody = HistoryAcceptanceAdmissions.UNKNOWN_USAGE
            fixture.transport.advance(1000); owner.readUsage()
            await("unknown reset cannot fall back to previous graph") { owner.currentHistoryDisplayContext()?.periods?.fiveHour?.window == null && !owner.state.value.refresh.refreshing }
            for (kind in listOf("FIVE_HOUR", "WEEKLY")) compose.onAllNodesWithTag("dashboard-no-info-$kind").assertCountEquals(1)
            compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).assertDoesNotExist()

        }
    }

    @Test fun dashboardActionsFollowConnectionPhaseAndDormantNavigationDoesNotRead() {
        launch().use {
            compose.onNodeWithTag("connect").assertTextEquals("Login")
            compose.onNodeWithTag("live-refresh").assertDoesNotExist()
            openDetailRoute(compose, owner, DashboardRoute.CONNECTION_DETAILS)
            compose.onNodeWithTag("read-usage").performScrollTo().assertIsNotEnabled()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            returnDashboard(compose, owner)
            assertEquals(0L, fixture.transport.requests.get())
            login()
            compose.onNodeWithTag("connect").assertDoesNotExist()
            compose.onNodeWithTag("live-refresh").performScrollTo().assertIsEnabled()
        }
        fixture.freshRuntime()
        launch().use {
            await("dormant restored phase") { owner.state.value.phase == ConnectionPhase.RESTORED }
            val before = fixture.transport.counts()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("dormant stored facts") { recordedHistory(owner).storage?.lastAdmitted?.ordinal == 1L }
            returnDashboard(compose, owner)
            click("banked-disclosure-toggle"); click("banked-disclosure-toggle")
            assertEquals(before, fixture.transport.counts())
            compose.onNodeWithTag("dashboard-no-info-FIVE_HOUR").assertExists()
        }
    }

    @Test fun dashboardKnownPercentPreservesMalformedResetAndIndependentCurrentSiblingWarning() {
        fixture.transport.usageBody = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":7.25,"reset_at":false}}}"""
        launch().use {
            click("connect")
            await("known percent with malformed reset admitted") { owner.state.value.phase == ConnectionPhase.OBSERVED && !owner.state.value.refresh.refreshing }
            compose.onNodeWithTag("live-five-hour-percent").assertTextEquals("7.25% used")
            compose.onAllNodesWithTag("dashboard-no-info-FIVE_HOUR").assertCountEquals(1)
            compose.onNodeWithTag("dashboard-no-info-FIVE_HOUR").assertTextEquals("No current information · latest usage malformed.")
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("dashboard-window-warning-FIVE_HOUR").assertDoesNotExist()
            assertNull(owner.currentHistoryDisplayContext()!!.periods.fiveHour.window)
            fixture.transport.usageBody = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":7.25,"reset_at":1800003600,"reset_after_seconds":false}}}"""
            fixture.transport.advance(1000); owner.readUsage()
            await("independent known current reset and actual malformed-sibling measurement") {
                recordedHistory(owner).points.map { it.observation.ordinal } == listOf(2L) && !owner.state.value.refresh.refreshing
            }
            assertEquals(java.time.Instant.parse("2027-01-15T09:00:00Z"), owner.currentHistoryDisplayContext()!!.periods.fiveHour.window!!.resetAt)
            compose.onNodeWithTag("live-five-hour-percent").assertTextEquals("7.25% used")
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertTextEquals("Reset: Jan 15, 2027, 09:00")
            compose.onNodeWithTag("dashboard-window-warning-FIVE_HOUR").assertTextEquals("Some latest usage fields are malformed.")
            compose.onNodeWithTag("dashboard-no-info-FIVE_HOUR").assertDoesNotExist()
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("history-chart-five-hour-equivalent").assertTextEquals("One recorded observation · no observed trend")
        }
    }

    @Test fun bankedDisclosureUsesReportedCountAndRetainsEveryExpiryAndUnknownRow() {
        fixture.transport.inventoryBody = INVENTORY
        launch().use { scenario ->
            login()
            compose.onNodeWithTag("banked-disclosure-item-0").assertDoesNotExist()
            val before = fixture.transport.counts()
            click("banked-disclosure-toggle")
            compose.onNodeWithTag("banked-disclosure-toggle").assertTextContains("Banked resets (7)")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            for (index in 0..2) compose.onNodeWithTag("banked-disclosure-item-$index").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("banked-disclosure-expiry-0", useUnmergedTree = true).performScrollTo().assertTextEquals("Jan 15, 2027, 09:00")
            compose.onNodeWithTag("banked-disclosure-remaining-1", useUnmergedTree = true).performScrollTo().assertTextEquals("Entitlement expired")
            capture.capture("dashboard-banked-expanded-portrait-light", scenario, fixture, "banked-disclosure-status")
            openDetailRoute(compose, owner, DashboardRoute.CONNECTION_DETAILS)
            compose.onNodeWithTag("banked-inventory-text", useUnmergedTree = true).performScrollTo().assertTextEquals("Inventory reports: 7 available banked resets")
            compose.onNodeWithTag("banked-container").performScrollTo().assertExists()
            capture.capture("dashboard-connection-details-landscape-dark", landscapeDark(scenario), fixture, "banked-container")
            assertEquals(before, fixture.transport.counts())
        }
    }

    @Test fun drawerHasExactTwoLabelsBackPrecedenceAndDetailsReturnToDashboard() {
        settings.apply(true, 1f)
        launch(dark = true).use { scenario ->
            login()
            click("open-drawer")
            compose.onNodeWithTag("drawer-LOCAL_HISTORY_DETAILS").assertTextEquals("Full local history details")
            compose.onNodeWithTag("drawer-CONNECTION_DETAILS").assertTextEquals("Full live connection details")
            compose.onAllNodes(hasTestTag("drawer-LOCAL_HISTORY_DETAILS") or hasTestTag("drawer-CONNECTION_DETAILS")).assertCountEquals(2)
            capture.capture("dashboard-drawer-portrait-dark", scenario, fixture, "drawer-LOCAL_HISTORY_DETAILS")
            pressActivityBack(scenario, "open drawer Back")
            awaitDrawerClosed()
            compose.onNodeWithTag("dashboard").assertExists()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            pressActivityBack(scenario, "local history details Back")
            await("details Back returns dashboard") { compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("connection-history").assertDoesNotExist()
            compose.onNodeWithTag("dashboard").assertExists()
            openDetailRoute(compose, owner, DashboardRoute.CONNECTION_DETAILS)
            click("details-back"); compose.onNodeWithTag("dashboard").assertExists()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            scenario.recreate(); awaitConnectionScreen(compose, owner)
            compose.onNodeWithTag("dashboard").assertExists()
        }
    }

    @Test fun dashboardDarkLandscapeAndLargeFontKeepAccessibleTargetsAndCompleteText() {
        settings.apply(true, 2f)
        launch(dark = true, font = 2f, landscape = true).use { scenario ->
            login()
            listOf("history-chart-five-hour-title", "history-chart-weekly-title", "history-chart-five-hour-equivalent").forEach {
                HistoryAcceptanceCapture(compose).label(it)
            }
            listOf("open-drawer", "live-refresh", "banked-disclosure-toggle").forEach {
                val node = compose.onNodeWithTag(it).performScrollTo().assertHasClickAction().fetchSemanticsNode()
                assertTrue(node.size.height >= 48 * scenarioDensity(scenario))
            }
            capture.capture("dashboard-main-landscape-dark-large-font", scenario, fixture, "history-chart-five-hour-equivalent", WindowKind.FIVE_HOUR)
        }
        settings.apply(false, 2f)
        launch(font = 2f).use { scenario ->
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("large-font stored page") { recordedHistory(owner).storage != null }
            capture.capture("dashboard-local-history-details-large-font", scenario, fixture, "history-page-cursor")
        }
    }

    private fun launch(dark: Boolean = false, font: Float = 1f, landscape: Boolean = false): ActivityScenario<MainActivity> {
        settings.apply(dark, font)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try { settings.observe(compose, scenario, landscape, dark, font); awaitConnectionScreen(compose, owner) }
        catch (error: Throwable) { scenario.close(); throw error }
        return scenario
    }
    private fun landscapeDark(scenario: ActivityScenario<MainActivity>): ActivityScenario<MainActivity> {
        settings.apply(true, 1f); settings.observe(compose, scenario, true, true, 1f)
        openDetailRoute(compose, owner, DashboardRoute.CONNECTION_DETAILS)
        return scenario
    }
    private fun scenarioDensity(scenario: ActivityScenario<MainActivity>): Float {
        var density = 0f; scenario.onActivity { density = it.resources.displayMetrics.density }; return density
    }
    private fun login() {
        click("connect")
        await("accepted real pair and independent inventory") { owner.state.value.phase == ConnectionPhase.OBSERVED &&
            !owner.state.value.refresh.refreshing && recordedHistory(owner).points.size == 1 }
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle() }
    private fun pressActivityBack(scenario: ActivityScenario<MainActivity>, step: String) {
        var focused = false
        await("$step: Activity window focused", { "focused=$focused; ${owner.state.value}" }) {
            scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
            focused
        }
        // Read View state on Main, then inject real system Back on the instrumentation thread.
        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private fun awaitDrawerClosed() {
        var lastAssertion: AssertionError? = null
        await("Back closes drawer before leaving dashboard", { "drawer=$lastAssertion; ${owner.state.value}" }) {
            try { compose.onNodeWithTag("details-drawer").assertIsNotDisplayed(); true }
            catch (error: AssertionError) { lastAssertion = error; false }
        }
        compose.onNodeWithTag("details-drawer").assertIsNotDisplayed()
    }
    private fun await(step: String, lastState: () -> Any? = {
        "${owner.state.value}, request=${owner.historySnapshots.value.request}, ready=${owner.historySnapshots.value.readiness}"
    }, predicate: () -> Boolean) {
        try { compose.waitUntil(10_000, predicate) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: last=${lastState()}", error) }
    }
    companion object {
        const val INVENTORY = """{"available_count":7,"credits":[{"reset_type":"codex_rate_limits","status":"available","expires_at":"2027-01-15T09:00:00Z"},{"reset_type":"codex_rate_limits","status":"available","expires_at":"2027-01-15T07:00:00Z"},null]}"""
    }
}
