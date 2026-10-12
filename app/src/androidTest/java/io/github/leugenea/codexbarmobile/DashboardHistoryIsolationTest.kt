package io.github.leugenea.codexbarmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real pair requests, held detached SQLite results and UI-only delivery on actual MainActivity. */
@RunWith(AndroidJUnit4::class)
class DashboardHistoryIsolationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture = HistoryIsolationFixture()
    private val observer = HeldHistoryObserver()
    private val owner get() = fixture.owner
    @Before fun prepare() { fixture.prepare(); MainActivity.historyUiObserver = observer }
    @After fun cleanup() {
        observer.release(); MainActivity.historyUiObserver = null
        fixture.cleanup()
    }

    @Test fun heldPairReadCannotPublishAcrossRouteSwitchAccountReplacementAndForegroundRetirement() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            login()
            val old = owner.historySnapshots.value
            val gate = fixture.holdNextRead("pair route/account/foreground predecessor")
            try {
                val context = owner.currentHistoryDisplayContext()!!
                owner.observeHistory((old.request as HistoryPresentationRequest.Current).copy(limit = 31), context)
                await("pair SQLite result held") { gate.reached() }
                openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
                fixture.settled("stored route supersedes held pair")
                assertNotSame(old.selection, owner.historySnapshots.value.selection)
                owner.signOut(); fixture.settled("pair predecessor account retirement")
                await("terminal local removal") { owner.state.value.phase == ConnectionPhase.SIGNED_OUT }
                assertNull(old.displayPermission!!.current())
                returnDashboard(compose, owner)
                owner.connect()
                await("successor live account while old read held") { owner.state.value.phase == ConnectionPhase.OBSERVED && !owner.state.value.refresh.refreshing }
                scenario.moveToState(Lifecycle.State.CREATED)
                fixture.settled("last observer retirement while pair held")
                assertNull(owner.currentHistoryDisplayContext())
                scenario.moveToState(Lifecycle.State.RESUMED)
                val boundary = fixture.publications.size
                gate.release()
                await("successor pair recorded") { recordedHistory(owner).points.size == 1 && recordedHistory(owner).partition != old.partition }
                val heldPage = (gate.receipt!!.outcome as HistoryReadOutcome.Ready).snapshot
                assertTrue(fixture.publications.drop(boundary).none { source ->
                    val pages = listOfNotNull(source.storage, source.currentPages?.fiveHour, source.currentPages?.weekly)
                    pages.any { page -> page.entries.any { entry -> heldPage.entries.any { it === entry } } }
                })
                assertEquals(listOf(2L), recordedHistory(owner).points.map { it.observation.ordinal })
                assertTrue(BreakReason.BACKGROUND in recordedHistory(owner).points.single().segment.breaks)
                assertEquals(0L, owner.historySnapshots.value.lostSamples)
            } finally { gate.release() }
        }
    }

    @Test fun heldPairCollectionCannotReplayPreviousPeriodAfterResetExpiryOrClockLoss() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val old = hold("period collector")
            try {
                fixture.transport.advance(3_600_000)
                fixture.transport.usageStatus = 403
                owner.readUsage()
                fixture.settled("expiry explicit read receipt")
                await("actual authority expires period") { owner.currentHistoryDisplayContext()?.periods?.fiveHour?.window == null && !owner.state.value.refresh.refreshing }
                challenge(old)
                fixture.transport.usageStatus = 200
                fixture.transport.usageBody = NavigationTransport.USAGE.replace("1800003600", "1800007200")
                fixture.transport.advance(1000); owner.readUsage()
                await("reset successor descriptor") { owner.currentHistoryDisplayContext()?.periods?.fiveHour?.window?.resetAt?.epochSecond == 1_800_007_200L && !owner.state.value.refresh.refreshing }
                challenge(old)
                // A clock earlier than the accepted observation invalidates current identity.
                fixture.transport.advance(-1_001)
                await("existing local evaluation detects backward clock") { owner.currentHistoryDisplayContext()?.periods?.fiveHour?.window == null }
                challenge(old)
            } finally { observer.release() }
            await("collector released to latest permission") { observer.currentPair?.first !== old }
        }
    }

    @Test fun heldPairCollectionCannotReplayEqualQueryAfterDetailsRoundTrip() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val old = hold("equal pair route ABA")
            val requests = fixture.transport.counts()
            try {
                openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
                fixture.settled("stored route command acknowledged")
                returnDashboard(compose, owner)
                fixture.settled("equal pair route return acknowledged")
                await("same pair value with new token") { owner.historySnapshots.value.request == old.request && owner.historySnapshots.value.selection !== old.selection }
                challenge(old)
                assertEquals(requests, fixture.transport.counts())
                assertEquals(1L, recordedHistory(owner).storage!!.lastAdmitted!!.ordinal)
            } finally { observer.release() }
            await("successor token delivered") { observer.currentPair?.first?.selection === owner.historySnapshots.value.selection && observer.currentPair?.second?.points?.size == 1 }
        }
    }

    @Test fun pairReadsRemainBoundedBehindArchivesAndCoalesceWithoutExtraAdmissions() {
        owner.usageForeground(fixture.native.observer, true)
        fixture.transport.usageBody = NavigationTransport.USAGE.replace("1800003600", "1799999000")
        owner.connect(); fixture.settled("archive seed connect")
        await("archive seed recorded") { recordedHistory(owner).storage?.lastAdmitted?.ordinal == 1L }
        fixture.native.seedLocalPage()
        val access = fixture.capability()
        val first = recordedHistory(owner).storage!!.entries.first()
        val archived = owner.state.value.refresh.usage.success!!.usage!!
        for (id in 36L..300L) append(access, first, id, archived)
        fixture.transport.advance(300_000)
        fixture.transport.usageBody = NavigationTransport.USAGE
        owner.readUsage()
        await("current observation after three hundred archive rows") { recordedHistory(owner).storage?.lastAdmitted?.ordinal == 301L && !owner.state.value.refresh.refreshing }
        val current = owner.state.value.refresh.usage.success!!.usage!!
        for (id in 302L..340L) append(access, first, id, current)
        fixture.transport.advance(40_000)
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            await("bounded pair behind seeded rows") { recordedHistory(owner).storage?.entries?.size == 32 }
            val context = owner.currentHistoryDisplayContext()!!
            val request = owner.historySnapshots.value.request as HistoryPresentationRequest.Current
            val token = owner.historySnapshots.value.selection
            val reads = fixture.reads.size
            val requests = fixture.transport.counts()
            repeat(4) { owner.observeHistory(request, context) }
            fixture.settled("equal pair observations coalesce without scan")
            assertSame(token, owner.historySnapshots.value.selection); assertEquals(reads, fixture.reads.size)
            assertTrue(recordedHistory(owner).hasMore)
            assertEquals(32, recordedHistory(owner, WindowKind.WEEKLY).points.size)
            assertTrue(recordedHistory(owner, WindowKind.WEEKLY).hasMore)
            assertEquals((301L..332L).toList(), recordedHistory(owner).storage!!.entries.map { it.id.ordinal })
            assertTrue(fixture.reads.all { it.query.limit <= 256 })
            assertEquals(340L, recordedHistory(owner).storage!!.lastAdmitted!!.ordinal)
            assertEquals(requests, fixture.transport.counts())
        }
    }

    private fun append(access: HistoryRuntimeAccess, first: HistoryEntry, ordinal: Long, usage: io.github.leugenea.codexbarmobile.usage.UsageObservation) {
        val seconds = ordinal - 1
        val result = access.append(HistoryAdmission(access.partition, ObservationId(ordinal),
            HistoryClock(first.clock.epoch, (first.clock.monotonicMillis ?: 0) + seconds * 1000),
            HistoryEvent.Observed(usage.copy(observedAt = first.observedAt!!.plusSeconds(seconds)))))
        assertTrue("synthetic bounded archive/current admission $ordinal: $result", result is HistoryAppendOutcome.Stored)
    }

    private fun login() {
        awaitConnectionScreen(compose, owner)
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        await("actual main pair and collected projection") { recordedHistory(owner).points.size == 1 && observer.currentPair?.second?.points?.size == 1 && !owner.state.value.refresh.refreshing }
    }
    private fun hold(step: String): HistoryGraphSnapshot {
        observer.hold()
        try {
            await("$step frozen delivery acknowledged by actual host") { observer.currentPair?.first === observer.deliverySource && observer.deliverySource != null }
            return observer.currentPair!!.first
        } catch (error: Throwable) { observer.release(); throw error }
    }
    private fun challenge(old: HistoryGraphSnapshot) {
        await("actual host challenged held source and scrubbed both children") {
            observer.currentPair?.let { it.first === old && it.second.storage == null && it.third.storage == null } == true
        }
        assertSame(old, observer.currentPair!!.first)
        compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).assertDoesNotExist()
    }
    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(10_000, predicate) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: phase=${owner.state.value.phase}, request=${owner.historySnapshots.value.request}, ready=${owner.historySnapshots.value.readiness}, pair=${observer.currentPair?.second?.readiness}", error) }
    }
}
