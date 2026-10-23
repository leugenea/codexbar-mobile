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
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            openDetailRoute(compose, owner, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            returnDashboard(compose, owner)
            await("initial usage settled") { owner.state.value.refresh.usage.success != null && !owner.state.value.refresh.refreshing }
            val cases = listOf(59L to "less than a minute", 60L to "1 minute", 7500L to "2 hours 5 minutes",
                86700L to "1 day 5 minutes", 259200L to "3 days")
            for ((seconds, duration) in cases) {
                val target = fixture.transport.clock.now().wall!!.plusSeconds(seconds)
                fixture.transport.usageBody = NavigationTransport.USAGE.replace("1800003600", target.epochSecond.toString())
                fixture.transport.inventoryBody = """{"available_count":1,"credits":[{"id":"synthetic-minute-expiry",
                    "reset_type":"codex_rate_limits","status":"available","granted_at":"2026-10-01T00:00:00Z","expires_at":"$target"}]}"""
                val previous = owner.state.value.refresh.inventory.attempt
                owner.readUsage()
                await("minute-precision usage and inventory settled") {
                    owner.state.value.refresh.inventory.attempt !== previous && !owner.state.value.refresh.refreshing
                }
                compose.onNodeWithTag("live-five-hour-reset-relative").performScrollTo().assertTextEquals("Next reset in $duration")
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
            val measured = "Measurement observed at (second precision): Jan 15, 2027, 8:00:00\u202fAM"
            compose.onNodeWithTag("dashboard-status").assertTextEquals("Last reported usage")
            compose.onAllNodesWithText(measured, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithTag("dashboard-observed-at").performScrollTo().assertTextEquals(measured)
            val cards = hasAnyAncestor(hasTestTag("dashboard-FIVE_HOUR")) or hasAnyAncestor(hasTestTag("dashboard-WEEKLY"))
            compose.onAllNodes(cards and hasText("Measurement observed at", substring = true), useUnmergedTree = true).assertCountEquals(0)
            for (removed in listOf("Measured used percent", "Observed-only delta", "Recorded observation", "Recorded history is incomplete")) {
                compose.onAllNodes(hasText(removed, substring = true), useUnmergedTree = true).assertCountEquals(0)
            }
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertDoesNotExist()
            compose.onNodeWithTag("live-five-hour-reset-relative").assertTextEquals("Next reset in 1 hour")
            val first = compose.onNodeWithTag("history-chart-five-hour-first").performScrollTo()
                .assertTextEquals("Time axis start: Jan 15, 04:00").fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithTag("history-chart-five-hour-last")
                .assertTextEquals("Time axis end: Jan 15, 09:00").fetchSemanticsNode().boundsInRoot
            assertTrue("Start is left of end", first.right <= last.left)
            assertEquals("Time labels share one row", first.top, last.top, 0f)
            assertTrue("Time labels are below the chart", compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.bottom <= first.top)
            val pace = compose.onNodeWithTag("history-chart-five-hour-pace")
            pace.performScrollTo().assertTextEquals("67.6% in reserve")
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
