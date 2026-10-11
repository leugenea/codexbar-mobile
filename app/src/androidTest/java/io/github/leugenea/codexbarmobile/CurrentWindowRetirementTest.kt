package io.github.leugenea.codexbarmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.history.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentWindowRetirementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture = HistoryIsolationFixture()
    private val owner get() = fixture.owner
    @Before fun prepare() { fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun lateCurrentQueryCannotReplayRetiredAccountIntoProductionSuccessorChart() {
        await("initial owner idle") { owner.state.value.phase == ConnectionPhase.IDLE }
        owner.usageForeground(fixture.native.observer, true)
        owner.connect()
        await("initial admitted live usage") { owner.state.value.phase == ConnectionPhase.OBSERVED && !owner.state.value.refresh.refreshing &&
            owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            click("history-toggle")
            await("initial current graph ready") { owner.historySnapshots.value.query.currentOnly && owner.historySnapshots.value.points.size == 1 }
            val old = owner.historySnapshots.value
            val gate = fixture.holdNextRead("retired current weekly query")
            try {
                click("history-select-WEEKLY")
                await("old actual SQLite query held after read") { gate.reached() }
                owner.signOut()
                await("account removal retires current facts") { owner.state.value.phase == ConnectionPhase.SIGNED_OUT }
                assertNull(old.displayPermission!!.current())
                compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).assertDoesNotExist()
                fixture.transport.advance(1000)
                owner.connect()
                await("new account live facts received independently of old query") { owner.state.value.phase == ConnectionPhase.OBSERVED && !owner.state.value.refresh.refreshing }
                val publication = fixture.publications.size
                val retiredPage = (gate.receipt!!.outcome as HistoryReadOutcome.Ready).snapshot
                gate.release()
                await("successor current chart records and selects only its own measurement") {
                    owner.historySnapshots.value.let { it.query.currentOnly && it.query.window != null && it.points.size == 1 && it.partition != old.partition }
                }
                assertTrue(fixture.publications.drop(publication).none { it.storage === retiredPage })
                assertNoRetiredPartition(publication, requireNotNull(old.partition))
                assertNotEquals(old.query.window, owner.historySnapshots.value.query.window)
                assertEquals(listOf(1L), owner.historySnapshots.value.points.map { it.observation.ordinal })
                assertEquals(10L, fixture.transport.requests.get())
                assertEquals(4L, fixture.transport.gets.get())
            } finally { gate.release() }
        }
    }

    @Test fun lateSameAccountQueryCannotReplayOldResetAfterCurrentKeyRollover() {
        await("rollover owner idle") { owner.state.value.phase == ConnectionPhase.IDLE }
        owner.usageForeground(fixture.native.observer, true)
        owner.connect()
        await("rollover initial observation recorded") { owner.state.value.phase == ConnectionPhase.OBSERVED &&
            !owner.state.value.refresh.refreshing && owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner); click("history-toggle")
            await("rollover initial current graph ready") { owner.historySnapshots.value.query.currentOnly && owner.historySnapshots.value.points.size == 1 }
            val partition = owner.historySnapshots.value.partition
            val generation = owner.historySnapshots.value.generation
            val gate = fixture.holdNextRead("same-account preceding reset query")
            try {
                click("history-select-WEEKLY")
                await("preceding reset SQLite result held") { gate.reached() }
                fixture.transport.advance(1000)
                fixture.transport.usageBody = NavigationTransport.USAGE.replace("1800003600", "1800007200")
                owner.readUsage()
                await("fresh live reset advances requested current key while old query stays held") {
                    owner.historySnapshots.value.query.window?.resetAt?.epochSecond == 1_800_007_200L &&
                        owner.historySnapshots.value.query.currentOnly && !owner.state.value.refresh.refreshing
                }
                assertSame(generation, owner.historySnapshots.value.generation)
                assertNull(owner.historySnapshots.value.storage)
                compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).assertDoesNotExist()
                val publication = fixture.publications.size
                val oldPage = (gate.receipt!!.outcome as HistoryReadOutcome.Ready).snapshot
                gate.release()
                await("new current weekly measurements selected after held reset returns") {
                    owner.historySnapshots.value.let { it.query.window?.resetAt?.epochSecond == 1_800_007_200L &&
                        it.points.map { point -> point.observation.ordinal } == listOf(2L) }
                }
                assertEquals(partition, owner.historySnapshots.value.partition)
                assertSame(generation, owner.historySnapshots.value.generation)
                assertTrue(fixture.publications.drop(publication).none { it.storage === oldPage })
                assertNoEarlierReset(publication)
                assertEquals(7L, fixture.transport.requests.get())
                assertEquals(4L, fixture.transport.gets.get())
            } finally { gate.release() }
        }
    }

    @Test fun currentRetainedGraphKeepsStaleErrorLossAndRealRetentionExplicit() {
        fixture.limits = HistoryStorageLimits(observations = 3)
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            await("retention owner idle") { owner.state.value.phase == ConnectionPhase.IDLE }
            click("connect")
            await("retention initial observation written") { owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L && !owner.state.value.refresh.refreshing }
            fixture.native.seedLocalPage()
            fixture.failRead = true
            fixture.transport.advance(1000); owner.readUsage()
            await("actual read failure loses one current admission") { owner.historySnapshots.value.lostSamples == 1L && !owner.state.value.refresh.refreshing }
            fixture.failRead = false
            fixture.transport.usageStatus = 403
            fixture.transport.advance(900_000); owner.readUsage()
            await("retained live current usage becomes stale with independent inventory") {
                owner.historySnapshots.value.live.let { it.usage.stale && !it.inventory.stale &&
                    it.usage.error == io.github.leugenea.codexbarmobile.transport.ReadError.FORBIDDEN && !it.refreshing }
            }
            val requests = fixture.transport.counts()
            click("history-toggle")
            await("current graph selects retained rows despite loss and retention") {
                owner.historySnapshots.value.let { it.query.currentOnly && it.query.window != null &&
                    it.readiness == HistoryReadiness.ERROR && it.storage?.entries?.map { row -> row.id.ordinal } == listOf(33L, 34L, 35L) }
            }
            compose.onNodeWithTag("history-chart-five-hour-counts").assertTextEquals(
                "Drawn markers: 3 · Within-run connections: 2 · Nominal reference lines: 1")
            val source = owner.historySnapshots.value
            assertEquals(32L, source.storage!!.cutoffs.getValue(HistoryTruncation.OBSERVATION_CAP).ordinal)
            assertEquals(1L, source.lostSamples)
            currentText("Retained endpoint facts are stale.").performScrollTo().assertIsDisplayed()
            currentText("Latest endpoint error: forbidden").assertExists()
            currentText("Retention truncated history: observation cap").assertExists()
            compose.onNodeWithTag("history-loss").assertTextEquals("Samples lost or unconfirmed: 1; history is incomplete.")
            assertEquals(requests, fixture.transport.counts())
            assertEquals(35L, source.storage.lastAdmitted!!.ordinal)
        }
    }

    @Test fun knownCurrentPeriodWithLostOnlyAdmissionHasEmptyHistoryThenOneActualMarker() {
        fixture.failRead = true
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            await("empty owner idle") { owner.state.value.phase == ConnectionPhase.IDLE }
            click("connect")
            await("current live success with unconfirmed history admission") { owner.historySnapshots.value.lostSamples == 1L && !owner.state.value.refresh.refreshing }
            fixture.failRead = false
            click("history-toggle")
            await("known current period has real empty retained history with loss") {
                owner.historySnapshots.value.let { it.query.currentOnly && it.query.window != null &&
                    it.storage?.content == HistoryContent.EMPTY && it.readiness == HistoryReadiness.ERROR }
            }
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("history-chart-five-hour-counts").assertTextEquals(
                "Drawn markers: 0 · Within-run connections: 0 · Nominal reference lines: 0")
            assertEquals(1L, owner.historySnapshots.value.lostSamples)
            val requests = fixture.transport.requests.get()
            fixture.transport.advance(1000); owner.readUsage()
            await("one new actual admitted observation supplies a single marker") {
                owner.historySnapshots.value.let { it.query.currentOnly && it.points.map { point -> point.observation.ordinal } == listOf(2L) }
            }
            compose.onNodeWithTag("history-chart-five-hour-counts").assertTextEquals(
                "Drawn markers: 1 · Within-run connections: 0 · Nominal reference lines: 1")
            assertEquals(requests + 2, fixture.transport.requests.get())
            assertEquals(1L, owner.historySnapshots.value.lostSamples)
        }
    }

    private fun assertNoRetiredPartition(boundary: Int, retired: HistoryPartition) {
        val published = fixture.publications.drop(boundary).filter { it.storage != null }
        assertTrue("successor must publish actual storage facts", published.isNotEmpty())
        published.forEach { source ->
            assertNotEquals(retired, source.partition)
            assertTrue("every published row belongs to the successor partition", source.storage!!.entries.all { it.partition == source.partition })
        }
    }

    private fun assertNoEarlierReset(boundary: Int) {
        val published = fixture.publications.drop(boundary).filter { it.query.currentOnly && it.storage != null }
        assertTrue("successor reset must publish actual selected history", published.isNotEmpty())
        published.forEach { source ->
            assertEquals(1_800_007_200L, source.query.window!!.resetAt.epochSecond)
            assertEquals(listOf(2L), source.storage!!.entries.map { it.id.ordinal })
            assertTrue("every published measured point belongs to the fresh current key", source.points.all { it.segment.window == source.query.window })
        }
    }

    private fun currentText(value: String) = compose.onNode(hasAnyAncestor(hasTestTag("connection-history")) and hasText(value), useUnmergedTree = true)

    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle() }
    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(8_000, predicate) } catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: phase=${owner.state.value.phase}, query=${owner.historySnapshots.value.query}, readiness=${owner.historySnapshots.value.readiness}, points=${owner.historySnapshots.value.points.size}", error)
        }
    }
}
