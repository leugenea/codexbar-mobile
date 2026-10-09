package io.github.leugenea.codexbarmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Basic #89 integration. Held-query/adversarial interleavings remain #90; no local execution. */
@RunWith(AndroidJUnit4::class)
class HistoryNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: HistoryNavigationFixture
    private val capture get() = HistoryNavigationCapture(compose)

    @Before fun prepare() { fixture = HistoryNavigationFixture(); fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun productionEntrySelectsWindowsAndNavigatesBoundedOrdinalPagesWithNativeCaptures() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openHistory()
            page(WindowKind.FIVE_HOUR, 0, 32)
            assertPage(1, 32, more = true)
            compose.onNodeWithTag("history-chart-weekly").assertDoesNotExist()
            captureNavigation(scenario)
            captureChart(scenario)
            click("history-select-WEEKLY")
            page(WindowKind.WEEKLY, 0, 32)
            compose.onNodeWithTag("history-chart-five-hour").assertDoesNotExist()
            click("history-details-toggle")
            historyText("Measured used percent (exact %): 87.5").performScrollTo().assertIsDisplayed()
            click("history-next-page")
            page(WindowKind.WEEKLY, 32, 3)
            assertPage(33, 35, more = false)
            compose.onNodeWithTag("history-next-page").assertIsNotEnabled()
            click("history-first-page")
            page(WindowKind.WEEKLY, 0, 32)
            compose.onNodeWithTag("history-first-page").assertIsNotEnabled()
            click("history-toggle")
            compose.onNodeWithTag("read-usage").performScrollTo().assertIsEnabled()
            assertDormantCounts()
        }
    }

    @Test fun dormantRestoredHistoryAndOfflinePreviewsNeverActivateTransportOrAddAdmissions() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            for (preview in listOf("disconnected", "loading", "error", "demo")) click("preview-$preview")
            click("reset-preview")
            assertDormantCounts()
            openHistory()
            page(WindowKind.FIVE_HOUR, 0, 32)
            click("history-next-page")
            page(WindowKind.FIVE_HOUR, 32, 3)
            click("history-select-WEEKLY")
            page(WindowKind.WEEKLY, 32, 3)
            assertEquals(35L, fixture.owner.historySnapshots.value.storage!!.lastAdmitted!!.ordinal)
            assertEquals(ConnectionPhase.RESTORED, fixture.owner.state.value.phase)
            assertDormantCounts()
            click("offline-tab")
            await("offline view retires history") { fixture.owner.historySnapshots.value.generation == null }
            assertDormantCounts()
        }
    }

    @Test fun logoutClearsVisibleHistoryAndReloginNeverDisplaysPreviousPartition() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openHistory()
            page(WindowKind.FIVE_HOUR, 0, 32)
            val previous = fixture.owner.historySnapshots.value.partition
            click("history-next-page")
            page(WindowKind.FIVE_HOUR, 32, 3)
            fixture.owner.signOut()
            phase(ConnectionPhase.SIGNED_OUT)
            await("logout hides retired page") { fixture.owner.historySnapshots.value.generation == null }
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("history-details-toggle").assertDoesNotExist()
            assertDeleted()
            click("history-toggle")
            click("connect")
            phase(ConnectionPhase.OBSERVED)
            await("new login records only its own admission") { fixture.owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
            click("history-toggle")
            page(WindowKind.FIVE_HOUR, 0, 1)
            assertNotEquals(previous, fixture.owner.historySnapshots.value.partition)
            assertPage(1, 1, more = false)
            click("history-details-toggle")
            historyText("Admission ordinal: 1").performScrollTo().assertIsDisplayed()
            assertEquals(10L, fixture.transport.requests.get())
            assertEquals(4L, fixture.transport.gets.get())
        }
    }

    @Test fun recreationAndForegroundLossUseTheSameDormantOwnerButFreshHistoryAuthority() {
        seedDormant()
        val current = fixture.owner
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openHistory()
            page(WindowKind.FIVE_HOUR, 0, 32)
            click("history-select-WEEKLY")
            page(WindowKind.WEEKLY, 0, 32)
            click("history-next-page")
            page(WindowKind.WEEKLY, 32, 3)
            val before = current.historySnapshots.value.generation
            scenario.recreate()
            compose.waitForIdle()
            await("recreation adopts fresh runtime authority") {
                val generation = current.historySnapshots.value.generation
                generation != null && generation !== before
            }
            scenario.onActivity { assertSame(current, it.connection) }
            compose.onNodeWithTag("history-toggle").assertTextEquals("View local history")
            click("history-toggle")
            page(WindowKind.FIVE_HOUR, 0, 32)
            val resumed = current.historySnapshots.value.generation
            scenario.moveToState(Lifecycle.State.CREATED)
            await("STOP clears current history authority") { current.historySnapshots.value.generation == null }
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            await("RESUME re-adopts without activation") {
                val generation = current.historySnapshots.value.generation
                generation != null && generation !== resumed
            }
            page(WindowKind.FIVE_HOUR, 0, 32)
            assertDormantCounts()
        }
        await("finish clears post-recreation authority") { current.historySnapshots.value.generation == null }
        assertTrue(KeystoreCredentialStore.file(fixture.context, NativeConnection.session(fixture.context)).isFile)
        assertDormantCounts()
    }

    @Test fun integratedHistoryRetainsIndependentEndpointFailuresAndNoNewSamplesFromLocalNavigation() {
        fixture.transport.inventoryStatus = 403
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            click("connection-tab")
            phase(ConnectionPhase.IDLE)
            click("connect")
            phase(ConnectionPhase.OBSERVED)
            await("initial usage persisted independently") { fixture.owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
            fixture.transport.usageStatus = 403
            fixture.transport.inventoryStatus = 200
            fixture.transport.advance(1000)
            fixture.owner.readUsage()
            await("independent inventory succeeds while usage fails") {
                val source = fixture.owner.historySnapshots.value
                source.live.usage.error == ReadError.FORBIDDEN && source.live.inventory.status == 200 && !source.live.refreshing
            }
            click("history-toggle")
            page(WindowKind.FIVE_HOUR, 0, 1)
            val source = fixture.owner.historySnapshots.value
            assertEquals(1L, source.storage!!.lastAdmitted!!.ordinal)
            assertNotEquals(source.live.usage.sourceObservedAt, source.live.inventory.sourceObservedAt)
            historyText("Latest endpoint error: forbidden").performScrollTo().assertIsDisplayed()
            historyText("Last successful source observation (exact UTC): 2027-01-15T08:00:00Z").assertExists()
            historyText("Last successful source observation (exact UTC): 2027-01-15T08:00:01Z").assertExists()
            click("history-select-WEEKLY")
            page(WindowKind.WEEKLY, 0, 1)
            assertEquals(7L, fixture.transport.requests.get())
            assertEquals(4L, fixture.transport.gets.get())
            click("history-toggle")
            compose.onNodeWithTag("live-refresh").performScrollTo().assertIsEnabled()
        }
    }

    private fun seedDormant() {
        phase(ConnectionPhase.IDLE)
        fixture.owner.usageForeground(fixture.observer, true)
        fixture.owner.connect()
        phase(ConnectionPhase.OBSERVED)
        await("synthetic seed's original accepted endpoint settled") { fixture.owner.historySnapshots.value.storage?.entries?.size == 1 }
        fixture.seedLocalPage()
        fixture.freshRuntime()
        phase(ConnectionPhase.RESTORED)
        assertDormantCounts()
    }

    private fun openHistory() { compose.waitForIdle(); click("connection-tab"); click("history-toggle") }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag !in setOf("connection-tab", "offline-tab")) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    private fun page(kind: WindowKind, after: Long, count: Int) {
        await("page $kind after=$after count=$count") {
            val source = fixture.owner.historySnapshots.value
            source.query == HistoryGraphQuery(HistoryNavigation.PAGE_LIMIT, after.takeIf { it > 0 }?.let(::ObservationId), kind)
                && source.generation != null && source.storage?.entries?.size == count
        }
        compose.onNodeWithTag("history-page-cursor").assertTextEquals("Exclusive admission cursor: $after · Page limit: 32")
        compose.onNodeWithTag("history-readiness").assertTextEquals("Admitted history page")
    }

    private fun assertPage(first: Long, last: Long, more: Boolean) {
        val source = fixture.owner.historySnapshots.value
        assertEquals((first..last).toList(), source.storage!!.entries.map { it.id.ordinal })
        assertEquals(more, source.hasMore)
        compose.onNodeWithTag("history-page-ordinals").assertTextEquals("Retained page ordinals: $first to $last")
    }

    private fun phase(expected: ConnectionPhase) = await("phase $expected") {
        fixture.owner.state.value.let { it.phase == expected && !it.refresh.refreshing }
    }

    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 8_000, condition = predicate) }
        catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: last phase=${fixture.owner.state.value.phase}, readiness=${fixture.owner.historySnapshots.value.readiness}, query=${fixture.owner.historySnapshots.value.query}", error)
        }
    }

    private fun historyText(value: String) = compose.onNode(hasAnyAncestor(hasTestTag("connection-history")) and hasText(value), useUnmergedTree = true)
    private fun assertDormantCounts() { assertEquals(5L, fixture.transport.requests.get()); assertEquals(2L, fixture.transport.gets.get()) }

    private fun assertDeleted() {
        val slot = NativeConnection.session(fixture.context)
        assertFalse(KeystoreCredentialStore.file(fixture.context, slot).exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreCredentialStore.alias(fixture.context, slot)))
        assertFalse(File(fixture.root, "usage-history/history.db").exists())
    }

    private fun captureNavigation(scenario: ActivityScenario<MainActivity>) {
        listOf("history-navigation-title", "history-page-cursor", "history-page-ordinals").forEach(capture::assertLabel)
        compose.onNodeWithTag("history-navigation-title").performScrollTo()
        capture.capture("history-integrated-navigation-portrait-light", scenario, fixture,
            listOf("connection-tab", "history-select-FIVE_HOUR", "history-select-WEEKLY", "history-first-page", "history-next-page"))
    }

    private fun captureChart(scenario: ActivityScenario<MainActivity>) {
        capture.assertLabel("history-chart-five-hour-title")
        compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        capture.capture("history-integrated-chart-portrait-light", scenario, fixture,
            listOf("connection-tab"), canvas = "history-chart-five-hour-canvas")
    }
}
