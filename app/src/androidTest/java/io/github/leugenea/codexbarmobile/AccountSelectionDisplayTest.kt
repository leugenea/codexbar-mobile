package io.github.leugenea.codexbarmobile

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/** Actual production root/Compose and delivered-page adversaries; hosted execution only. */
@RunWith(AndroidJUnit4::class)
class AccountSelectionDisplayTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: AccountSelectionFixture
    private val observer = HeldHistoryObserver()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val originalObserver = MainActivity.historyUiObserver

    @Before fun prepare() {
        fixture = AccountSelectionFixture(); fixture.prepare(); fixture.start(); fixture.installActivity()
        MainActivity.historyUiObserver = observer
    }
    @After fun cleanup() {
        observer.release()
        try { scenario?.close() }
        finally { MainActivity.historyUiObserver = originalObserver; fixture.cleanup() }
    }

    @Test fun actualHostRejectsAlreadyDeliveredAPageAndWarmPlotAcrossBThenEqualQueryA() {
        val selected = fixture.owner.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        fixture.settle(a)
        assertEquals(0L, fixture.aTransport.requests.get()); assertEquals(0L, fixture.bTransport.requests.get())
        fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.OBSERVED)
        await("A actual host delivered a bound page") {
            val pair = observer.currentFrame
            pair?.source?.partition == fixture.a.partition && pair.fiveHour.points.isNotEmpty() &&
                observer.deliverySource === pair.source
        }
        val firstRoot = selected.value!!
        val delivered = observer.currentFrame!!
        val request = delivered.source.request as HistoryPresentationRequest.Current
        val oldPage = delivered.fiveHour.storage!!
        val cache = HistoryPlotCache()
        val warm = cache.project(delivered.fiveHour)
        assertTrue(warm.fiveHour.samples.isNotEmpty())
        assertEquals(BigDecimal("12.375"), delivered.fiveHour.points.last().usedPercent)
        observer.hold()
        try {
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            await("actual B root rendered its live facts despite held history collection") {
                compose.onAllNodesWithTag("live-five-hour-percent").fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    ?.any { it.text.contains("62.5") } == true
            }
            val rootB = selected.value!!
            assertFalse(firstRoot.isCurrent()); assertTrue(rootB.isCurrent())
            val hiddenB = currentHistoryForDisplay(delivered.source, request, WindowKind.FIVE_HOUR, rootB.historyContext())
            assertNull(hiddenB.storage); assertTrue(hiddenB.points.isEmpty())
            assertTrue(cache.project(hiddenB).fiveHour.samples.isEmpty())
            assertSame(oldPage, delivered.fiveHour.storage) // The hostile page itself remains intact.
            assertSame(a, fixture.owner.settings(fixture.a.storage.id)!!.controller)
            val bRequests = fixture.bTransport.requests.get()
            assertEquals(2L, bRequests)
            // Return to exactly equal query/window/time keys, with a different accepted fact.
            fixture.aTransport.usageBody = NavigationTransport.USAGE.replace("12.375", "23.125")
            fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.OBSERVED)
            await("same-phase successor root reexecutes the actual host") {
                val pass = observer.currentFrame
                selected.value !== firstRoot && pass != null && pass.source !== delivered.source && pass.fiveHour.points.isEmpty()
            }
            val successor = selected.value!!
            assertSame(a, successor.controller); assertNotSame(firstRoot, successor)
            assertEquals(ConnectionPhase.OBSERVED, a.state.value.phase)
            assertEquals(ConnectionPhase.OBSERVED, b.state.value.phase)
            assertEquals(request, a.historySnapshots.value.request)
            assertNotSame(delivered.source.displayPermission!!.context, successor.historyContext())
            val hiddenA = currentHistoryForDisplay(delivered.source, request, WindowKind.FIVE_HOUR, successor.historyContext())
            assertNull(hiddenA.storage); assertTrue(cache.project(hiddenA).fiveHour.samples.isEmpty())
            // Scope negative text to the dashboard; a hidden historical diagnostic elsewhere is irrelevant.
            compose.onAllNodes(hasText("12.375", substring = true) and hasAnyAncestor(hasTestTag("dashboard")))
                .assertCountEquals(0)
            assertEquals(bRequests, fixture.bTransport.requests.get())
        } finally { observer.release() }
        await("positive fresh A source delivered to actual host") {
            val frame = observer.currentFrame
            frame?.source?.partition == fixture.a.partition && frame.source !== delivered.source &&
                frame.fiveHour.points.lastOrNull()?.usedPercent == BigDecimal("23.125") && observer.deliverySource === frame.source
        }
        val fresh = observer.currentFrame!!
        assertEquals(request, fresh.source.request)
        assertNotSame(delivered.source.displayPermission!!.context, fresh.source.displayPermission!!.context)
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), fresh.fiveHour.entries.map { it.source.id.ordinal })
        compose.onNodeWithTag("live-five-hour-percent").assertTextContains("23.125", substring = true)
    }

    @Test fun recreationAndCreatedSavedStateKeepRootIdentityWhileOldStopsCannotReleasePeerOrSuccessor() {
        val selected = fixture.owner.dashboard()
        val a = fixture.runtime(fixture.a); fixture.phase(a, ConnectionPhase.RESTORED)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        val peer = fixture.observer()
        fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.OBSERVED)
        await("initial native chart") { observer.currentFrame?.fiveHour?.points?.isNotEmpty() == true }
        val root = selected.value!!
        val generation = fixture.ready(a).generation
        val oldObserver = fixture.observer()
        val bundle = Bundle()
        try {
            scenario!!.moveToState(Lifecycle.State.CREATED)
            scenario!!.onActivity { activity ->
                assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState)
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, bundle)
            }
            fixture.settle(a)
            assertNotNull(a.currentHistoryDisplayContext()) // Independent STARTED peer still owns interest.
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            scenario!!.recreate()
            compose.waitForIdle()
            fixture.settle(a)
            val successorObserver = fixture.observer()
            try {
                fixture.stop(oldObserver); fixture.stop(oldObserver)
                fixture.settle(a)
                assertSame(root, selected.value)
                assertSame(generation, fixture.ready(a).generation)
                assertNotNull(a.currentHistoryDisplayContext())
                scenario!!.onActivity { assertSame(a, it.connection) }
                compose.onNodeWithTag("connection-screen").assertExists()
                compose.onNodeWithTag("live-five-hour-percent").assertTextContains("12.375", substring = true)
                assertEquals(1L, fixture.aTransport.counts()[io.github.leugenea.codexbarmobile.transport.ReadOperation.USAGE.path])
                assertEquals(1L, fixture.aTransport.counts()[io.github.leugenea.codexbarmobile.transport.ReadOperation.RESET_INVENTORY.path])
                assertEquals(0L, fixture.bTransport.requests.get())
            } finally { fixture.stop(successorObserver) }
        } finally { fixture.stop(oldObserver); fixture.stop(peer) }
    }

    private fun await(label: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 8_000, condition = condition) }
        catch (error: ComposeTimeoutException) {
            throw AssertionError("$label: last root=${fixture.owner.dashboard().value?.id}, " +
                "A=${fixture.runtime(fixture.a).state.value}, B=${fixture.runtime(fixture.b).state.value}, " +
                "delivered=${observer.deliverySource?.readiness}, projected=${observer.currentFrame?.fiveHour?.readiness}", error)
        }
    }
}
