package io.github.leugenea.codexbarmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant

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
            recordedHistory(owner).storage?.lastAdmitted?.ordinal == 1L }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner)
            returnDashboard(compose, owner)
            await("initial current graph ready") { recordedHistory(owner).query.currentOnly && recordedHistory(owner).points.size == 1 }
            val old = recordedHistory(owner)
            val gate = fixture.holdNextRead("retired current weekly query")
            try {
                owner.queryHistory(HistoryGraphQuery(32, kind = WindowKind.WEEKLY, window = owner.currentHistoryDisplayContext()!!.periods.weekly.window, currentOnly = true))
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
                    recordedHistory(owner).let { it.query.currentOnly && it.query.window != null && it.points.size == 1 && it.partition != old.partition }
                }
                assertTrue(fixture.publications.drop(publication).none { it.storage === retiredPage })
                assertNoRetiredPartition(publication, requireNotNull(old.partition))
                assertNotEquals(old.query.window, recordedHistory(owner).query.window)
                assertEquals(listOf(1L), recordedHistory(owner).points.map { it.observation.ordinal })
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
            !owner.state.value.refresh.refreshing && recordedHistory(owner).storage?.lastAdmitted?.ordinal == 1L }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, owner); returnDashboard(compose, owner)
            await("rollover initial current graph ready") { recordedHistory(owner).query.currentOnly && recordedHistory(owner).points.size == 1 }
            val partition = recordedHistory(owner).partition
            val generation = recordedHistory(owner).generation
            val gate = fixture.holdNextRead("same-account preceding reset query")
            try {
                owner.queryHistory(HistoryGraphQuery(32, kind = WindowKind.WEEKLY, window = owner.currentHistoryDisplayContext()!!.periods.weekly.window, currentOnly = true))
                await("preceding reset SQLite result held") { gate.reached() }
                fixture.transport.advance(1000)
                fixture.transport.usageBody = NavigationTransport.USAGE.replace("1800003600", "1800007200")
                owner.readUsage()
                await("fresh live reset advances requested current key while old query stays held") {
                    recordedHistory(owner).query.window?.resetAt?.epochSecond == 1_800_007_200L &&
                        recordedHistory(owner).query.currentOnly && !owner.state.value.refresh.refreshing
                }
                assertSame(generation, recordedHistory(owner).generation)
                assertNull(recordedHistory(owner).storage)
                compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).assertDoesNotExist()
                val publication = fixture.publications.size
                val oldPage = (gate.receipt!!.outcome as HistoryReadOutcome.Ready).snapshot
                gate.release()
                await("new current weekly measurements selected after held reset returns") {
                    recordedHistory(owner).let { it.query.window?.resetAt?.epochSecond == 1_800_007_200L &&
                        it.points.map { point -> point.observation.ordinal } == listOf(2L) }
                }
                assertEquals(partition, recordedHistory(owner).partition)
                assertSame(generation, recordedHistory(owner).generation)
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
            await("retention initial observation written") { recordedHistory(owner).storage?.lastAdmitted?.ordinal == 1L && !owner.state.value.refresh.refreshing }
            fixture.native.seedLocalPage()
            fixture.failRead = true
            fixture.transport.advance(1000); owner.readUsage()
            await("actual read failure loses one current admission") { recordedHistory(owner).lostSamples == 1L && !owner.state.value.refresh.refreshing }
            fixture.failRead = false
            fixture.transport.usageStatus = 403
            fixture.transport.advance(900_000); owner.readUsage()
            await("retained live current usage becomes stale with independent inventory") {
                recordedHistory(owner).live.let { it.usage.stale && !it.inventory.stale &&
                    it.usage.error == io.github.leugenea.codexbarmobile.transport.ReadError.FORBIDDEN && !it.refreshing }
            }
            val requests = fixture.transport.counts()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("real retained stored page before returning to current pair") { recordedHistory(owner).storage?.entries?.size == 3 }
            returnDashboard(compose, owner)
            await("current graph selects retained rows despite loss and retention") {
                recordedHistory(owner).let { it.query.currentOnly && it.query.window != null &&
                    it.readiness == HistoryReadiness.ERROR && it.storage?.entries?.map { row -> row.id.ordinal } == listOf(33L, 34L, 35L) }
            }
            assertCurrentDrawing(recordedHistory(owner), 3, 2, 1)
            val source = recordedHistory(owner)
            assertEquals(32L, source.storage!!.cutoffs.getValue(HistoryTruncation.OBSERVATION_CAP).ordinal)
            assertEquals(1L, source.lostSamples)
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("retention details loaded") { recordedHistory(owner).storage?.entries?.size == 3 }
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
            await("current live success with unconfirmed history admission") { recordedHistory(owner).lostSamples == 1L && !owner.state.value.refresh.refreshing }
            fixture.failRead = false
            owner.queryHistory(HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR))
            settleConnectionCommands(compose, owner, "explicit empty local reread and pair reobservation")
            returnDashboard(compose, owner)
            await("known current period has real empty retained history with loss") {
                recordedHistory(owner).let { it.query.currentOnly && it.query.window != null &&
                    it.storage?.content == HistoryContent.EMPTY && it.readiness == HistoryReadiness.ERROR }
            }
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
            assertCurrentDrawing(recordedHistory(owner), 0, 0, 0)
            val window = WindowIdentity(requireNotNull(recordedHistory(owner).partition), WindowKind.FIVE_HOUR,
                18_000, Instant.ofEpochSecond(1_800_003_600L))
            val query = HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR, window = window, currentOnly = true)
            assertEquals(query, recordedHistory(owner).query)
            assertNull(recordedHistory(owner).storage!!.lastAdmitted)
            assertEquals(1L, recordedHistory(owner).lostSamples)
            val requests = fixture.transport.requests.get()
            fixture.transport.advance(1000); owner.readUsage()
            // The successful read-only empty query consumed workerGap without admitting a row.
            // This first actual durable observation therefore has ordinal one, not two.
            await("one new actual admitted observation supplies a single marker") {
                recordedHistory(owner).let { it.query == query && !it.live.refreshing &&
                    it.points.map { point -> point.observation.ordinal } == listOf(1L) }
            }
            val source = recordedHistory(owner)
            assertEquals(listOf(1L), source.storage!!.entries.map { it.id.ordinal })
            assertNull(source.storage.entries.single().gap)
            assertEquals(Instant.ofEpochSecond(1_800_000_001L), source.storage.entries.single().observedAt)
            assertEquals(1L, source.storage.lastAdmitted!!.ordinal)
            assertEquals(listOf(BigDecimal("12.375")), source.points.map { it.usedPercent })
            assertEquals(window, source.points.single().segment.window)
            assertEquals(HistoryReadiness.ERROR, source.readiness)
            assertEquals(HistoryRecorderProblem.READ_FAILURE, source.problem)
            assertCurrentDrawing(recordedHistory(owner), 1, 0, 1)
            assertEquals(requests + 2, fixture.transport.requests.get())
            assertEquals(1L, recordedHistory(owner).lostSamples)
        }
    }

    private fun assertNoRetiredPartition(boundary: Int, retired: HistoryPartition) {
        val published = fixture.publications.drop(boundary).flatMap { listOfNotNull(it.storage, it.currentPages?.fiveHour, it.currentPages?.weekly) }
        assertTrue("successor must publish actual storage facts", published.isNotEmpty())
        published.forEach { page ->
            assertNotEquals(retired, page.query.partition)
            assertTrue("every published row belongs to the successor partition", page.entries.all { it.partition == page.query.partition })
        }
    }

    private fun assertNoEarlierReset(boundary: Int) {
        val published = fixture.publications.drop(boundary).filter { it.request is HistoryPresentationRequest.Current && it.currentPages != null }
        assertTrue("successor reset must publish actual selected history", published.isNotEmpty())
        published.forEach { source ->
            val request = source.request as HistoryPresentationRequest.Current
            assertEquals(1_800_007_200L, request.fiveHour!!.resetAt.epochSecond)
            assertEquals(listOf(2L), source.currentPages!!.fiveHour!!.entries.map { it.id.ordinal })
            assertTrue("every raw published selected point belongs to the fresh current key", source.currentPages.fiveHour.entries.all { entry ->
                entry.windows.filter { it.kind == WindowKind.FIVE_HOUR }.all { it.point?.segment?.window == request.fiveHour }
            })
        }
    }

    private fun currentText(value: String) = compose.onNode(hasAnyAncestor(hasTestTag("connection-history")) and hasText(value), useUnmergedTree = true)

    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle() }
    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(8_000, predicate) } catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: phase=${owner.state.value.phase}, query=${recordedHistory(owner).query}, readiness=${recordedHistory(owner).readiness}, points=${recordedHistory(owner).points.size}", error)
        }
    }
}
