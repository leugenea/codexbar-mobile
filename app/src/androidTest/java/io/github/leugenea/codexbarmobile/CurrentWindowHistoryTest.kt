package io.github.leugenea.codexbarmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.math.BigDecimal

/** Actual production entry and owner; original synthetic data in isolated SQLite/Keystore. */
@RunWith(AndroidJUnit4::class)
class CurrentWindowHistoryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture = HistoryNavigationFixture()
    private val settings = HistoryAcceptanceSettings()
    private val capture get() = HistoryAcceptanceCapture(compose)
    private val owner get() = fixture.owner
    private val five = "history-chart-five-hour"

    @Before fun prepare() { fixture.prepare() }
    @After fun cleanup() { try { fixture.cleanup() } finally { settings.restore() } }

    @Test fun currentPeriodBehindArchivedPagesHasExactMeasuredReferencePaintInProductionLight() = gallery(false)
    @Test fun currentPeriodBehindArchivedPagesHasExactMeasuredReferencePaintInProductionDark() = gallery(true)

    private fun gallery(dark: Boolean) {
        seedBehindArchives()
        settings.apply(dark, 1f)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settings.observe(compose, scenario, false, dark, 1f)
            val requests = fixture.transport.counts()
            openDashboard()
            page(listOf(301L, 302L, 304L, 305L, 306L))
            assertEquals(307L, recordedHistory(owner).storage!!.lastAdmitted!!.ordinal)
            assertCurrentDrawing(recordedHistory(owner), 5, 2, 2)
            listOf("$five-title", "$five-pace", "$five-first", "$five-last").forEach(capture::label)
            compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            capture.capture(if (dark) "history-current-dark-portrait" else "history-current-light-portrait", scenario, fixture, emptyList(), chart = true)
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("initial stored page for current exact facts") {
                recordedHistory(owner).query == HistoryNavigation().query && recordedHistory(owner).storage?.entries?.size == 32
            }
            repeat(9) {
                click("history-next-page")
                val after = (it + 1) * 32L
                await("stored cursor $after and completed page") {
                    recordedHistory(owner).query.after?.ordinal == after && recordedHistory(owner).storage != null
                }
            }
            click("history-details-toggle")
            repeat(12) { click("history-next") }
            fact("Admission ordinal: 301")
            fact("Measured used percent (exact %): 12.375")
            fact("Observed-only delta (percentage points): -69.29166666666666666666666666666667")
            click("history-next"); fact("Admission ordinal: 302"); fact("Measured used percent (exact %): 35")
            click("history-next"); click("history-next"); fact("Admission ordinal: 304"); fact("Segment break: background")
            click("history-next"); fact("Admission ordinal: 305"); fact("Measured used percent (exact %): 10"); fact("Baseline and delta unavailable: uncertain correction")
            click("history-next"); fact("Admission ordinal: 306"); fact("Measured used percent (exact %): 30")
            click("history-next"); fact("Admission ordinal: 307")
            compose.onNodeWithTag("history-next").performScrollTo().assertIsNotEnabled()
            returnDashboard(compose, owner)
            await("weekly pair visible") { recordedHistory(owner, WindowKind.WEEKLY).points.size == 1 }
            assertCurrentDrawing(recordedHistory(owner, WindowKind.WEEKLY), 1, 0, 1)
            assertEquals(requests, fixture.transport.counts())
            assertEquals(307L, recordedHistory(owner).storage!!.lastAdmitted!!.ordinal)
        }
    }

    @Test fun expiryChangedResetUnknownAndAmbiguousRetireCurrentChartsWithoutArchivedFallback() {
        login()
        ActivityScenario.launch(MainActivity::class.java).use {
            openDashboard(); page(listOf(1L))
            val original = recordedHistory(owner).query.window!!
            fixture.transport.advance(3_600_000)
            fixture.transport.usageStatus = 403
            fixture.transport.inventoryStatus = 200
            owner.readUsage()
            expired(original)
            compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("dashboard-no-info-FIVE_HOUR").assertExists()
            fixture.transport.usageStatus = 200
            fixture.transport.usageBody = usage(1_800_007_200L)
            owner.readUsage()
            currentReset(1_800_007_200L)
            assertNotEquals(original, recordedHistory(owner).query.window)
            assertTrue(recordedHistory(owner).points.all { it.segment.window == recordedHistory(owner).query.window })
            for ((index, body) in listOf(HistoryAcceptanceAdmissions.UNKNOWN_USAGE,
                """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":1,"reset_at":1800007200},"secondary_window":{"limit_window_seconds":18000,"used_percent":2,"reset_at":1800007200}}}""").withIndex()) {
                fixture.transport.advance(1000)
                fixture.transport.usageBody = body
                owner.readUsage()
                await("unknown or ambiguous current period cannot replay an earlier chart") {
                    !owner.state.value.refresh.refreshing && owner.state.value.refresh.usage.success?.observedAt?.epochSecond == 1_800_003_601L + index &&
                        recordedHistory(owner).query.window == null && recordedHistory(owner).storage == null
                }
                compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).assertDoesNotExist()
            }
        }
    }

    @Test fun dormantRestoreHasTextHistoryButNoCurrentChartAndOpeningAddsNoRequests() {
        login()
        fixture.freshRuntime()
        await("restored owner dormant") { owner.state.value.phase == ConnectionPhase.RESTORED }
        ActivityScenario.launch(MainActivity::class.java).use {
            val requests = fixture.transport.counts()
            openDashboard()
            await("current identity unavailable without live facts") { recordedHistory(owner).query.currentOnly && recordedHistory(owner).storage == null }
            compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).assertDoesNotExist()
            openDetailRoute(compose, owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
            await("archived facts reachable as text") { !recordedHistory(owner).query.currentOnly && recordedHistory(owner).storage?.entries?.size == 1 }
            click("history-details-toggle"); fact("Measured used percent (exact %): 12.375")
            compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(requests, fixture.transport.counts())
            assertEquals(1L, recordedHistory(owner).storage!!.lastAdmitted!!.ordinal)
        }
    }

    private fun expired(window: WindowIdentity) = await("existing evaluation clock expires the original period after the failed read settles") {
        val source = recordedHistory(owner)
        owner.state.value.refresh.evaluatedAt == window.resetAt && !source.live.refreshing &&
            source.live.usage.error == io.github.leugenea.codexbarmobile.transport.ReadError.FORBIDDEN &&
            source.query.currentOnly && source.query.window == null && source.storage == null
    }

    private fun currentReset(reset: Long) = await("changed reset identity has its own actual retained measurement") {
        val source = recordedHistory(owner)
        source.query.window?.resetAt?.epochSecond == reset && source.storage != null && source.points.size == 1 && !source.live.refreshing
    }

    private fun seedBehindArchives() {
        fixture.transport.usageBody = usage(1_799_999_000L)
        login()
        val access = capability()
        val first = recordedHistory(owner).storage!!.entries.single()
        val old = owner.state.value.refresh.usage.success!!.usage!!
        for (ordinal in 2L..300L) append(access, first, ordinal, ordinal - 1, HistoryEvent.Observed(old.copy(
            observedAt = old.observedAt!!.plusSeconds(ordinal - 1))))
        fixture.transport.advance(300_000)
        fixture.transport.usageBody = NavigationTransport.USAGE
        owner.readUsage()
        await("live current identity and real ordinal 301 recorded") { recordedHistory(owner).storage?.lastAdmitted?.ordinal == 301L && !owner.state.value.refresh.refreshing }
        val current = owner.state.value.refresh.usage.success!!.usage!!
        fun measured(percent: String, seconds: Long, reset: Long = 1_800_003_600L): HistoryEvent = HistoryEvent.Observed(
            UsageNormalizer.normalize(UsageInput(Input.Value(WindowInput(Input.Value(18000L), Input.Value(BigDecimal(percent)), Input.Value(reset)))),
                current.observedAt!!.plusSeconds(seconds - 300)))
        append(access, first, 302, 360, measured("35", 360))
        append(access, first, 303, 390, HistoryEvent.Gap(HistoryGap.BACKGROUND))
        append(access, first, 304, 420, measured("50", 420))
        append(access, first, 305, 900, measured("10", 900))
        append(access, first, 306, 960, measured("30", 960))
        append(access, first, 307, 1020, measured("90", 1020, 1_800_003_601L))
        fixture.transport.advance(720_000)
        await("existing local tick evaluates after the retained synthetic admissions") {
            owner.state.value.refresh.evaluatedAt?.epochSecond == 1_800_001_020L
        }
    }

    private fun append(access: HistoryRuntimeAccess, first: HistoryEntry, ordinal: Long, seconds: Long, event: HistoryEvent) {
        val result = access.append(HistoryAdmission(access.partition, ObservationId(ordinal),
            HistoryClock(first.clock.epoch, requireNotNull(first.clock.monotonicMillis) + seconds * 1000), event))
        assertTrue("synthetic retained admission $ordinal: $result", result is HistoryAppendOutcome.Stored)
    }

    private fun capability(): HistoryRuntimeAccess {
        val ready = owner.session.snapshot() as SessionResult.Ready
        return requireNotNull(owner.historyCapability(ready.envelope.generation))
    }

    private fun login() {
        await("initial owner ready") { owner.state.value.phase == ConnectionPhase.IDLE }
        owner.usageForeground(fixture.observer, true)
        owner.connect()
        await("real synthetic connect usage persisted") { owner.state.value.phase == ConnectionPhase.OBSERVED &&
            !owner.state.value.refresh.refreshing && recordedHistory(owner).storage?.lastAdmitted?.ordinal == 1L }
    }

    private fun openDashboard() { awaitConnectionScreen(compose, owner); returnDashboard(compose, owner) }
    private fun page(ordinals: List<Long>) = await("current bounded selected ordinals $ordinals") {
        recordedHistory(owner).let { source -> source.query.currentOnly && source.query.window != null &&
            source.readiness == HistoryReadiness.READY && source.storage?.entries?.map { it.id.ordinal } == ordinals }
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle() }
    private fun fact(value: String) { capture.layout(compose.onNode(hasAnyAncestor(hasTestTag("connection-history")) and hasText(value), useUnmergedTree = true).performScrollTo()) }
    private fun usage(reset: Long) = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12.375,"reset_at":$reset}}}"""
    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(8_000, predicate) } catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: phase=${owner.state.value.phase}, evaluated=${owner.state.value.refresh.evaluatedAt}, query=${recordedHistory(owner).query}, readiness=${recordedHistory(owner).readiness}, ordinals=${recordedHistory(owner).storage?.entries?.map { it.id.ordinal }}", error)
        }
    }
}
