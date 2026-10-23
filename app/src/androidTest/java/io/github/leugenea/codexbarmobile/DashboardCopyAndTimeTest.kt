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
