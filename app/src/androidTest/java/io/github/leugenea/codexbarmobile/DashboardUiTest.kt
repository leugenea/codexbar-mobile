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
        fixture.transport.advance(2_279_000) // 08:37:59: Updated keeps minutes and floors seconds.
        launch().use { scenario ->
            login()
            for (kind in listOf(WindowKind.FIVE_HOUR, WindowKind.WEEKLY)) assertCurrentDrawing(recordedHistory(owner, kind), 1, 0, 1)
            compose.onNodeWithTag("history-select-FIVE_HOUR").assertDoesNotExist()
            compose.onNodeWithTag("history-page-cursor").assertDoesNotExist()
            val before = fixture.transport.counts()
            capture.capture("dashboard-main-portrait-light", scenario, fixture, "history-chart-five-hour-pace", WindowKind.FIVE_HOUR)
            capture.capture("dashboard-weekly-portrait-light", scenario, fixture, "history-chart-weekly-pace", WindowKind.WEEKLY)
            val weekly = compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("banked-disclosure-title").performScrollTo().assertIsDisplayed().assertHasNoClickAction()
            assertTrue(weekly.height > 0)
            compose.onNodeWithTag("banked-disclosure-item-0").assertDoesNotExist()
            compose.onNodeWithTag("banked-disclosure-header").assertDoesNotExist()
            compose.onNodeWithTag("banked-disclosure-title").assertTextEquals("Banked resets (0)")
            compose.onNodeWithTag("banked-disclosure-status").assertTextEquals("No retained items")
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
            compose.onNodeWithTag("history-chart-five-hour-pace").assertTextEquals("67.6% in reserve")
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
            compose.onNodeWithTag("connect").assertDoesNotExist()
            compose.onNodeWithTag("open-account-settings").assertTextEquals("Account settings")
            compose.onNodeWithTag("live-refresh").assertDoesNotExist()
            openDetailRoute(compose, owner, DashboardRoute.CONNECTION_DETAILS)
            compose.onNodeWithTag("read-usage").performScrollTo().assertIsNotEnabled()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            returnDashboard(compose, owner)
            assertEquals(0L, fixture.transport.requests.get())
            login()
            compose.onNodeWithTag("connect").assertDoesNotExist()
            compose.onNodeWithTag("live-refresh").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("account-name-edit").assertDoesNotExist()
            compose.onNodeWithTag("remove-account").assertDoesNotExist()
            val before = fixture.transport.counts()
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            assertConnectedAccountActions()
            returnDashboard(compose, owner)
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            assertConnectedAccountActions()
            assertEquals(before, fixture.transport.counts())
        }
        fixture.freshRuntime()
        launch().use {
            await("dormant restored phase") { owner.state.value.phase == ConnectionPhase.RESTORED }
            val before = fixture.transport.counts()
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            assertConnectedAccountActions() // RESTORED has no AuthState.Connected projection yet.
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("dormant stored facts") { recordedHistory(owner).storage?.lastAdmitted?.ordinal == 1L }
            returnDashboard(compose, owner)
            compose.onNodeWithTag("banked-disclosure-title").performScrollTo().assertTextEquals("Banked resets (count unknown)")
            compose.onNodeWithTag("banked-disclosure-header").assertDoesNotExist()
            assertEquals(before, fixture.transport.counts())
            compose.onNodeWithTag("dashboard-no-info-FIVE_HOUR").assertExists()
        }
    }

    @Test fun emptyDashboardOpensAccountSettingsWithoutInlineManagementOrProviderRequests() {
        launch().use {
            compose.onNodeWithTag("account-settings-required").performScrollTo()
                .assertTextEquals("Add an account in Account settings to view usage.").assertIsDisplayed()
            for (tag in listOf("connect", "cancel-connect", "remove-account", "account-name-edit", "device-code", "open-browser"))
                compose.onNodeWithTag(tag).assertDoesNotExist()
            compose.onNodeWithTag("open-account-settings").performScrollTo().assertTextEquals("Account settings").performClick()
            compose.onNodeWithTag("account-settings").assertExists()
            assertConnectionHasNoDiagnosticDump(compose)
            compose.onNodeWithTag("connect").performScrollTo().assertTextEquals("Add account").assertIsEnabled()
            compose.onNodeWithTag("cancel-connect").assertDoesNotExist()
            compose.onNodeWithTag("account-name-edit").assertDoesNotExist()
            compose.onNodeWithTag("remove-account").performScrollTo().assertIsNotEnabled()
            assertEquals(0L, fixture.transport.requests.get())
            click("details-back")
            compose.onNodeWithTag("dashboard").assertExists()
            openDetailRoute(compose, owner, DashboardRoute.CONNECTION_DETAILS)
            for (tag in listOf("connect", "cancel-connect", "remove-account", "account-name-edit", "device-code", "open-browser"))
                compose.onNodeWithTag(tag).assertDoesNotExist()
            compose.onNodeWithTag("open-account-settings").performScrollTo().performClick()
            compose.onNodeWithTag("account-settings").assertExists()
            assertConnectionHasNoDiagnosticDump(compose)
            assertEquals(0L, fixture.transport.requests.get())
        }
    }

    @Test fun fiveHourFractionalResetPairPopulatesDashboardButBeyondToleranceRemainsUnavailable() {
        fixture.transport.advance(250)
        fixture.transport.usageBody = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12.375,"reset_at":1800003600,"reset_after_seconds":3600}}}"""
        launch().use {
            login()
            val current = recordedHistory(owner)
            val source = current.storage!!.entries.single().windows.single { it.kind == WindowKind.FIVE_HOUR }
            assertEquals(ResetProvenance.ABSOLUTE, source.reset.provenance)
            assertEquals(java.time.Instant.ofEpochSecond(1_800_003_600), source.point!!.segment.window!!.resetAt)
            assertEquals(java.time.Instant.ofEpochSecond(1_800_003_600, 250000000), source.reset.facts!!.relativeDerived.value)
            assertEquals(source.point.segment.window, owner.currentHistoryDisplayContext()!!.periods.fiveHour.window)
            assertCurrentDrawing(current, 1, 0, 1)
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertDoesNotExist()
            compose.onNodeWithTag("live-five-hour-reset-relative").assertTextEquals("Next reset in 59 minutes")
            compose.onNodeWithTag("dashboard-no-info-FIVE_HOUR").assertDoesNotExist()
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("history-chart-five-hour-pace").assertTextEquals("67.6% in reserve")
            fixture.transport.usageBody = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12.375,"reset_at":1800003600,"reset_after_seconds":3659}}}"""
            fixture.transport.advance(1000); owner.readUsage()
            await("60.25-second reset discrepancy removes current five-hour identity and shows dashboard absence") {
                owner.currentHistoryDisplayContext()?.periods?.fiveHour?.window == null && !owner.state.value.refresh.refreshing &&
                    compose.onAllNodesWithTag("dashboard-no-info-FIVE_HOUR").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertDoesNotExist()
            compose.onNodeWithTag("dashboard-no-info-FIVE_HOUR").assertTextEquals("No current information.")
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
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
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertDoesNotExist()
            compose.onNodeWithTag("live-five-hour-reset-relative").assertTextEquals("Next reset in 59 minutes")
            compose.onNodeWithTag("dashboard-window-warning-FIVE_HOUR").assertTextEquals("Some latest usage fields are malformed.")
            compose.onNodeWithTag("dashboard-no-info-FIVE_HOUR").assertDoesNotExist()
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("history-chart-five-hour-pace").assertTextEquals("72.8% in reserve")
        }
    }

    @Test fun bankedTableUsesReportedCountAndRetainsEveryExpiryAndUnknownRow() {
        fixture.transport.inventoryBody = INVENTORY
        launch().use { scenario ->
            login()
            compose.onNodeWithTag("banked-disclosure-item-0").assertExists()
            val before = fixture.transport.counts()
            compose.onNodeWithTag("banked-disclosure-toggle").assertDoesNotExist()
            compose.onNodeWithTag("banked-disclosure-title").performScrollTo().assertTextEquals("Banked resets (7)").assertHasNoClickAction()
            compose.onNodeWithTag("banked-disclosure-header", useUnmergedTree = true)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            for (header in listOf("Expires", "Remaining")) compose.onNode(
                hasText(header) and hasAnyAncestor(hasTestTag("banked-disclosure-header")), useUnmergedTree = true)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            compose.onNodeWithTag("banked-disclosure-status").assertTextEquals("Inventory differs from reported counts")
            compose.onNodeWithText("Inventory is partial").assertExists()
            for (index in 0..2) compose.onNodeWithTag("banked-disclosure-item-$index").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("banked-disclosure-expiry-0", useUnmergedTree = true).performScrollTo().assertTextEquals("Jan 15, 09:00")
            compose.onNodeWithTag("banked-disclosure-expiry-1", useUnmergedTree = true).performScrollTo().assertTextEquals("Jan 15, 07:00")
            compose.onNodeWithTag("banked-disclosure-expiry-2", useUnmergedTree = true).performScrollTo().assertTextEquals("—")
            compose.onNodeWithTag("banked-disclosure-remaining-1", useUnmergedTree = true).performScrollTo().assertTextEquals("Expired")
            compose.onNodeWithTag("banked-disclosure-remaining-2", useUnmergedTree = true).performScrollTo().assertTextEquals("Unknown")
            compose.onNodeWithTag("banked-disclosure-remaining-0", useUnmergedTree = true).assertTextEquals("1 hour")
            compose.onNodeWithTag("banked-disclosure-item-0")
                .assertContentDescriptionEquals("Banked reset 1, expires Jan 15, 09:00, 1 hour remaining")
            compose.onNodeWithTag("banked-disclosure-item-1")
                .assertContentDescriptionEquals("Banked reset 2, expired Jan 15, 07:00")
            compose.onNodeWithTag("banked-disclosure-item-2")
                .assertContentDescriptionEquals("Banked reset 3, expiry unknown, state Unknown")
            assertBankedTableReadable()
            capture.capture("dashboard-banked-table-portrait-light", scenario, fixture, "banked-disclosure-title")
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
            compose.onNodeWithTag("drawer-ACCOUNT_SETTINGS").assertTextEquals("Account settings").assertIsDisplayed()
            compose.onAllNodes(hasTestTag("drawer-LOCAL_HISTORY_DETAILS") or hasTestTag("drawer-CONNECTION_DETAILS") or hasTestTag("drawer-ACCOUNT_SETTINGS")).assertCountEquals(3)
            val connection = compose.onNodeWithTag("drawer-CONNECTION_DETAILS").fetchSemanticsNode().boundsInRoot
            val account = compose.onNodeWithTag("drawer-ACCOUNT_SETTINGS").fetchSemanticsNode().boundsInRoot
            val sheet = compose.onNodeWithTag("details-drawer").fetchSemanticsNode().boundsInRoot
            assertTrue("Account settings is the bottom drawer item", account.top > connection.bottom && account.bottom <= sheet.bottom)
            assertTrue("The account item is anchored to the drawer bottom", sheet.bottom - account.bottom <= 24 * scenarioDensity(scenario))
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
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("account-name-edit").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("remove-account").performScrollTo().assertIsEnabled()
            pressActivityBack(scenario, "Account settings Back")
            compose.onNodeWithTag("dashboard").assertExists()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            scenario.recreate(); awaitConnectionScreen(compose, owner)
            compose.onNodeWithTag("dashboard").assertExists()
        }
    }

    @Test fun dashboardDarkLandscapeAndLargeFontKeepAccessibleTargetsAndCompleteText() {
        fixture.transport.advance(2_279_000) // Same minute-precision observation as portrait chart captures.
        fixture.transport.inventoryBody = INVENTORY
        settings.apply(true, 2f)
        launch(dark = true, font = 2f, landscape = true).use { scenario ->
            login()
            listOf("dashboard-observed-at", "live-five-hour-reset-relative", "history-chart-five-hour-title", "history-chart-weekly-title",
                "history-chart-five-hour-first", "history-chart-five-hour-last", "history-chart-five-hour-pace").forEach {
                HistoryAcceptanceCapture(compose).label(it)
            }
            listOf("open-drawer", "live-refresh").forEach {
                val node = compose.onNodeWithTag(it).performScrollTo().assertHasClickAction().fetchSemanticsNode()
                assertTrue(node.size.height >= 48 * scenarioDensity(scenario))
            }
            assertBankedTableReadable()
            HistoryAcceptanceCapture(compose).label("banked-disclosure-title")
            capture.capture("dashboard-main-landscape-dark-large-font", scenario, fixture, "history-chart-five-hour-pace", WindowKind.FIVE_HOUR)
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            HistoryAcceptanceCapture(compose).label("account-name")
            compose.onNodeWithTag("connect").assertDoesNotExist()
            compose.onNodeWithTag("cancel-connect").assertDoesNotExist()
            listOf("account-name-edit", "remove-account").forEach {
                val node = compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed().assertHasClickAction().fetchSemanticsNode()
                assertTrue(node.size.height >= 48 * scenarioDensity(scenario))
            }
        }
        settings.apply(false, 2f)
        launch(font = 2f).use { scenario ->
            assertBankedTableReadable()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("large-font stored page") { recordedHistory(owner).storage != null }
            capture.capture("dashboard-local-history-details-large-font", scenario, fixture, "history-page-cursor")
        }
    }

    private fun assertConnectedAccountActions() {
        compose.onNodeWithTag("account-settings").assertExists()
        compose.onNodeWithTag("connect").assertDoesNotExist()
        compose.onNodeWithTag("cancel-connect").assertDoesNotExist()
        compose.onNodeWithTag("account-name-edit").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("remove-account").performScrollTo().assertIsEnabled()
    }

    private fun assertBankedTableReadable() {
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        for (index in 0..2) {
            compose.onNodeWithTag("banked-disclosure-item-$index").assert(!hidden)
            for (column in listOf("expiry", "remaining")) {
                val node = compose.onNodeWithTag("banked-disclosure-$column-$index", useUnmergedTree = true)
                HistoryAcceptanceCapture(compose).layout(node.performScrollTo().assert(hidden))
            }
            compose.onNodeWithTag("banked-disclosure-state-$index", useUnmergedTree = true).assertDoesNotExist()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                hasAnyAncestor(hasTestTag("banked-disclosure-item-$index")), useUnmergedTree = true).assertCountEquals(2)
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
    private fun click(tag: String) {
        if (tag == "connect") openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
        compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle()
        if (tag == "connect") returnDashboard(compose, owner)
    }
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
