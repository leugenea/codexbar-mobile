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
                    compose.onNodeWithTag("banked-disclosure-toggle").performScrollTo().performClick()
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
            val cases = listOf(59L to "less than a minute", 60L to "1 minute",
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
                compose.onNodeWithTag("live-five-hour-reset-relative").performScrollTo().assertTextEquals("Reset: $duration remaining")
                compose.onNodeWithTag("banked-disclosure-toggle").performScrollTo().performClick()
                compose.onNodeWithTag("banked-disclosure-remaining-0", useUnmergedTree = true).performScrollTo()
                    .assertTextEquals("$duration remaining")
                compose.onNodeWithTag("banked-disclosure-toggle").performScrollTo().performClick()
                openDetailRoute(compose, owner, DashboardRoute.CONNECTION_DETAILS)
                compose.onNodeWithTag("live-five-hour-reset-relative").performScrollTo().assertTextEquals("Reset: $duration remaining")
                compose.onNodeWithTag("banked-item-0-relative", useUnmergedTree = true).performScrollTo()
                    .assertTextEquals("Banked entitlement expiry: $duration remaining")
                returnDashboard(compose, owner)
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
