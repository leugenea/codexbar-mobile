package io.github.leugenea.codexbarmobile

import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Reachable semantics and original source precision, through actual drawer routes and phone effects. */
@RunWith(AndroidJUnit4::class)
class DashboardCopyAndTimeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture = HistoryNavigationFixture()
    private val owner get() = fixture.owner
    @Before fun prepare() { fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun noReachableTextOrSemanticsUsesRemovedTimePhraseWhileOriginalInstantsRemainExact() {
        fixture.transport.inventoryBody = DashboardUiTest.INVENTORY
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            returnDashboard(compose, owner)
            await("original precision source recorded") { recordedHistory(owner).points.size == 1 && !owner.state.value.refresh.refreshing }
            val original = owner.state.value.refresh.usage.success
            val reset = original!!.usage!!.fiveHour.candidates.single().reset.absolute.value
            for (route in listOf(DashboardRoute.DASHBOARD, DashboardRoute.LOCAL_HISTORY_DETAILS, DashboardRoute.CONNECTION_DETAILS)) {
                if (route == DashboardRoute.DASHBOARD) {
                    returnDashboard(compose, owner)
                    compose.onNodeWithTag("banked-disclosure-title").performScrollTo().assertHasNoClickAction()
                } else openDetailRoute(compose, owner, route)
                if (route == DashboardRoute.LOCAL_HISTORY_DETAILS) {
                    await("stored exact detail page") { recordedHistory(owner).storage != null }
                    compose.onNodeWithTag("history-details-toggle").performScrollTo().performClick()
                }
                compose.onAllNodes(removedPhrase(), useUnmergedTree = true).assertCountEquals(0)
                assertSame(original, owner.state.value.refresh.usage.success)
                assertEquals(reset, owner.state.value.refresh.usage.success!!.usage!!.fiveHour.candidates.single().reset.absolute.value)
            }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun dashboardAndDisclosureFollowPhoneZoneLocaleAndTicksWithoutRequests() {
        if (Build.VERSION.SDK_INT >= 33) PhoneTimeEvidence(compose, fixture).run(history = false, dashboard = true)
    }

    @Test fun minutePrecisionMatchesDashboardWindowDetailsAndBankedExpiry() {
        fixture.transport.advance(2_279_000)
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            returnDashboard(compose, owner)
            await("initial usage settled") { owner.state.value.refresh.usage.success != null && !owner.state.value.refresh.refreshing }
            assertEquals(java.time.Instant.parse("2027-01-15T08:37:59Z"), owner.state.value.refresh.usage.success!!.usage!!.observedAt)
            compose.onNodeWithTag("dashboard-observed-at").assertTextEquals("Updated Jan 15, 08:37")
            val cases = listOf(Triple(59L, "less than a minute", "<1m"), Triple(60L, "1 minute", "1m"),
                Triple(7500L, "2 hours 5 minutes", "2h 5m"), Triple(17999L, "4 hours 59 minutes", "4h 59m"),
                Triple(86700L, "1 day 5 minutes", "1d 5m"), Triple(259200L, "3 days", "3d"),
                Triple(183840L, "2 days 3 hours 4 minutes", "2d 3h 4m"))
            for ((seconds, duration, compact) in cases) {
                val target = fixture.transport.clock.now().wall!!.plusSeconds(seconds)
                fixture.transport.usageBody = NavigationTransport.USAGE.replace("1800003600", target.epochSecond.toString())
                fixture.transport.inventoryBody = """{"available_count":1,"credits":[{"id":"synthetic-minute-expiry",
                    "reset_type":"codex_rate_limits","status":"available","granted_at":"2026-10-01T00:00:00Z","expires_at":"$target"}]}"""
                val previous = owner.state.value.refresh.inventory.attempt
                owner.readUsage()
                await("minute-precision usage and inventory settled") {
                    owner.state.value.refresh.inventory.attempt !== previous && !owner.state.value.refresh.refreshing
                }
                compose.onNodeWithTag("live-five-hour-reset-relative").performScrollTo().assertTextEquals("Resets in $compact").assertContentDescriptionEquals("Resets in $duration")
                compose.onNodeWithTag("banked-disclosure-title").performScrollTo().assertHasNoClickAction()
                compose.onNodeWithTag("banked-disclosure-remaining-0", useUnmergedTree = true).performScrollTo()
                    .assertTextEquals(duration)
                openDetailRoute(compose, owner, DashboardRoute.CONNECTION_DETAILS)
                compose.onNodeWithTag("live-five-hour-reset-relative").performScrollTo().assertTextEquals("Reset: $duration remaining")
                compose.onNodeWithTag("banked-item-0-relative", useUnmergedTree = true).performScrollTo()
                    .assertTextEquals("Banked entitlement expiry: $duration remaining")
                returnDashboard(compose, owner)
            }
            assertBankedNonKnownRows()
        }
    }

    private fun assertBankedNonKnownRows() {
        val dated = """{"reset_type":"codex_rate_limits","status":"available","expires_at":"2027-01-18T08:00:00Z"}"""
        val cases = listOf(
            Triple(dated.replace("available", "synthetic-unsupported"), "Jan 18, 08:00", "Unsupported"),
            Triple(dated.replace("\"available\"", "null"), "Jan 18, 08:00", "Unknown"),
            Triple(dated.replace("\"2027-01-18T08:00:00Z\"", "false"), "—", "Malformed"),
            Triple("null", "—", "Unknown"),
        )
        for ((row, expiry, state) in cases) {
            fixture.transport.inventoryBody = """{"available_count":1,"credits":[$row]}"""
            val previous = owner.state.value.refresh.inventory.attempt
            owner.readUsage()
            await("$state row with $expiry expiry settled") {
                owner.state.value.refresh.inventory.attempt !== previous && !owner.state.value.refresh.refreshing
            }
            compose.onNodeWithTag("banked-disclosure-expiry-0", useUnmergedTree = true).performScrollTo().assertTextEquals(expiry)
            compose.onNodeWithTag("banked-disclosure-remaining-0", useUnmergedTree = true).assertTextEquals(state)
            compose.onNodeWithTag("banked-disclosure-state-0", useUnmergedTree = true).assertDoesNotExist()
            val expiryFact = if (expiry == "—") "expiry unknown" else "expires $expiry"
            compose.onNodeWithTag("banked-disclosure-item-0")
                .assertContentDescriptionEquals("Banked reset 1, $expiryFact, state $state")
        }
    }

    @Test fun simplifiedWindowsKeepOneTopObservationYearlessAxisAndReadablePace() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            returnDashboard(compose, owner)
            await("initial current measurements settled") { recordedHistory(owner).points.size == 1 && !owner.state.value.refresh.refreshing }
            val measured = "Updated Jan 15, 08:00"
            compose.onNodeWithTag("dashboard-status").assertDoesNotExist()
            compose.onAllNodesWithText("Last reported usage", useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithText(measured, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithTag("dashboard-observed-at").performScrollTo().assertTextEquals(measured)
            assertCompactChartCopy("history-chart-five-hour", "Jan 15, 04:00")
            assertCompactChartCopy("history-chart-weekly", "Jan 8, 09:00")
            val cards = hasAnyAncestor(hasTestTag("dashboard-FIVE_HOUR")) or hasAnyAncestor(hasTestTag("dashboard-WEEKLY"))
            compose.onAllNodes(cards and hasText("Measurement observed at", substring = true), useUnmergedTree = true).assertCountEquals(0)
            for (removed in listOf("Measured used percent", "Observed-only delta", "Recorded observation", "Recorded history is incomplete")) {
                compose.onAllNodes(hasText(removed, substring = true), useUnmergedTree = true).assertCountEquals(0)
            }
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertDoesNotExist()
            compose.onNodeWithTag("live-five-hour-reset-relative").assertTextEquals("Resets in 1h")
            val first = compose.onNodeWithTag("history-chart-five-hour-first").performScrollTo()
                .assertTextEquals("Jan 15, 04:00").fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithTag("history-chart-five-hour-last")
                .assertTextEquals("Jan 15, 09:00").fetchSemanticsNode().boundsInRoot
            assertTrue("Start is left of end", first.right <= last.left)
            assertEquals("Time labels share one row", first.top, last.top, 0f)
            assertTrue("Time labels are below the chart", compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.bottom <= first.top)
            val pace = compose.onNodeWithTag("history-chart-five-hour-pace")
            pace.performScrollTo().assertTextEquals("67.6% in reserve")
                .assert(hasAnyAncestor(hasTestTag("history-chart-five-hour-heading")))
            compose.onAllNodes(hasTestTag("history-chart-five-hour-pace") and
                hasAnyAncestor(hasTestTag("history-chart-five-hour")), useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithTag("history-chart-five-hour-pace", useUnmergedTree = true).assertCountEquals(1)
            for ((percent, label) in listOf("80.0056" to "On pace", "90" to "Over pace by 10.0%", "1" to "Baseline and delta unavailable: uncertain correction")) {
                val previous = owner.state.value.refresh.usage.attempt
                fixture.transport.advance(1000)
                fixture.transport.usageBody = NavigationTransport.USAGE.replace("12.375", percent)
                owner.readUsage()
                await("$label admitted and current chart delivered") {
                    owner.state.value.refresh.usage.attempt !== previous && !owner.state.value.refresh.refreshing &&
                        compose.onAllNodes(hasTestTag("history-chart-five-hour-pace") and hasText(label)).fetchSemanticsNodes().size == 1
                }
                pace.performScrollTo().assertTextEquals(label)
            }
            val accepted = owner.state.value.refresh.usage.success
            val previous = owner.state.value.refresh.usage.attempt
            fixture.transport.usageStatus = 503
            fixture.transport.advance(60_000)
            owner.readUsage()
            await("failed attempt retains accepted observation clock") {
                owner.state.value.refresh.usage.attempt !== previous && !owner.state.value.refresh.refreshing
            }
            assertSame(accepted, owner.state.value.refresh.usage.success)
            compose.onNodeWithTag("dashboard-observed-at").performScrollTo().assertTextEquals(measured)
            compose.onNodeWithTag("dashboard-status").assertTextEquals("Unable to update usage")
        }
    }

    @Test fun compactResetRetainsPassedStateAndDoesNotInventUnavailableCountdowns() {
        fixture.transport.advance(1000)
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            returnDashboard(compose, owner)
            await("known initial reset accepted") { owner.state.value.refresh.usage.success != null && !owner.state.value.refresh.refreshing }
            val now = fixture.transport.clock.now().wall.epochSecond
            val cases = listOf(
                "\"reset_at\":$now" to "Reset passed · awaiting refresh",
                "\"reset_at\":${now - 60}" to "Reset passed · awaiting refresh",
                "" to null, "\"reset_at\":null" to null, "\"reset_at\":false" to null,
                "\"reset_at\":\"unsupported\"" to null,
                "\"reset_at\":1800003600,\"reset_after_seconds\":4000" to null,
            )
            for ((reset, expected) in cases) {
                val suffix = if (reset.isEmpty()) "" else ",$reset"
                fixture.transport.usageBody = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":7.25$suffix}}}"""
                val previous = owner.state.value.refresh.usage.attempt
                owner.readUsage()
                await("reset case $reset settled") { owner.state.value.refresh.usage.attempt !== previous && !owner.state.value.refresh.refreshing }
                compose.onNodeWithTag("live-five-hour-percent").assertTextEquals("7.25% used")
                compose.onNodeWithTag("live-five-hour-reset-absolute").assertDoesNotExist()
                val relative = compose.onNodeWithTag("live-five-hour-reset-relative")
                if (expected == null) relative.assertDoesNotExist() else relative.assertTextEquals(expected)
            }
            // A local backwards clock must not turn the original reset into a longer countdown.
            fixture.transport.usageBody = NavigationTransport.USAGE
            val previous = owner.state.value.refresh.usage.attempt
            owner.readUsage()
            await("restored known reset accepted") { owner.state.value.refresh.usage.attempt !== previous && !owner.state.value.refresh.refreshing }
            fixture.transport.advance(-1000)
            await("backwards clock reaches local evaluation") { owner.state.value.refresh.evaluatedAt == fixture.transport.clock.now().wall }
            compose.onNodeWithTag("live-five-hour-reset-relative").assertDoesNotExist()
        }
    }

    private fun assertCompactChartCopy(tag: String, first: String) {
        val legend = "Solid line: measured observations connected in time order; single observation: short tick\n" +
            "Faint short dashes: least-squares usage trend when available\n" +
            "Long dashes: nominal analytical full-quota reference, not a forecast"
        compose.onNodeWithTag("$tag-legend", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText(legend, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("$tag-plot").assertContentDescriptionEquals(legend)
            .assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        val capture = HistoryAcceptanceCapture(compose)
        for ((suffix, expected) in listOf("first" to first, "last" to "Jan 15, 09:00")) {
            val description = if (suffix == "first") "Time axis start: $expected" else "Time axis end: $expected"
            val node = compose.onNodeWithTag("$tag-$suffix").performScrollTo().assertTextEquals(expected)
                .assertContentDescriptionEquals(description)
                .assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasContentDescription(description),
                useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodesWithText(description, useUnmergedTree = true).assertCountEquals(0)
            assertEquals("Normal-font axis labels fit one line", 1, capture.layout(node).lineCount)
        }
    }

    private fun removedPhrase() = SemanticsMatcher("removed precision phrase in visible text or accessible equivalent") { node ->
        val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
        val descriptions = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
        val state = node.config.getOrElse(SemanticsProperties.StateDescription) { "" }
        "$text $descriptions $state".contains("hour precision")
    }
    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(10_000, predicate) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: last=${owner.state.value}, request=${owner.historySnapshots.value.request}", error) }
    }
}
