package io.github.leugenea.codexbarmobile

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** #83 assembled phone evidence only. Inherited ownership/lifecycle adversaries remain unchanged. */
@RunWith(AndroidJUnit4::class)
class HistoryAssembledAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val settings = HistoryAcceptanceSettings()
    private val fixture = HistoryNavigationFixture()
    private val capture get() = HistoryAcceptanceCapture(compose)
    private val five = "history-chart-five-hour"
    private var routes = emptyMap<String, Long>()

    @Before fun prepare() { fixture.prepare() }
    @After fun cleanup() {
        var failure: Throwable? = null
        try { fixture.cleanup() } catch (error: Throwable) { failure = error }
        try { settings.restore() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    @Test fun freshInstallAndSingleObservationExposeNoInventedTrendAndObservedOnlySignedDeltas() {
        launch().use { scenario ->
            await("fresh isolated owner settled without credentials") { fixture.owner.state.value.phase == ConnectionPhase.IDLE }
            openHistory()
            compose.onNodeWithTag("history-readiness").assertTextEquals("unavailable")
            compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("history-details-toggle").assertDoesNotExist()
            compose.onNodeWithTag("$five-content").assertTextEquals("No history page available; usage is not zero")
            noFact("Measured used percent (exact %): 0")
            compose.onNodeWithTag("$five-counts").assertTextEquals("Drawn markers: 0 · Within-run connections: 0 · Nominal reference lines: 0")
            capture.label("$five-no-geometry")
            compose.onNodeWithTag("history-navigation-title").performScrollTo()
            capture.capture("history-assembled-fresh-portrait-light", scenario, fixture,
                listOf("connection-tab", "history-select-FIVE_HOUR", "history-select-WEEKLY"))
            assertEquals(0L, fixture.transport.requests.get())
        }
        seedDormant()
        launch(dark = true).use { scenario ->
            openHistory(); page(WindowKind.FIVE_HOUR, 0, 1)
            chartLabels("Drawn markers: 1 · Within-run connections: 0 · Nominal reference lines: 1")
            captureCanvas("history-assembled-single-portrait-dark", scenario)
            click("history-details-toggle")
            fact("Measured used percent (exact %): 12.375")
            fact("Nominal baseline at observation (analytical %): 80")
            fact("Observed-only delta (percentage points): -67.625")
            fact("Absolute reset source (exact UTC): 2027-01-15T09:00:00Z")
            fact("Measurement observed at (exact UTC): 2027-01-15T08:00:00Z")
            singleFact("Observed-only delta (percentage points): -67.625")
            noFact("Observed-only delta (percentage points): 0")
            click("history-select-WEEKLY"); page(WindowKind.WEEKLY, 0, 1)
            click("history-details-toggle")
            fact("Measured used percent (exact %): 87.5")
            fact("Observed-only delta (percentage points): -11.90476190476190476190476190476190")
            click("history-select-FIVE_HOUR"); page(WindowKind.FIVE_HOUR, 0, 1)
            HistoryAcceptanceAdmissions.deltas(fixture)
            fixture.owner.queryHistory(HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR))
            page(WindowKind.FIVE_HOUR, 0, 3)
            click("history-details-toggle")
            click("history-next")
            fact("Observed-only delta (percentage points): 0E-32")
            click("history-next")
            fact("Observed-only delta (percentage points): 9.33333333333333333333333333333333")
            singleFact("Observed-only delta (percentage points): 9.33333333333333333333333333333333")
            dormant()
        }
    }

    @Test fun sparseGapCorrectionAndChangedResetRemainSeparatedInActualLandscapeDarkPixelsAndDetails() {
        fixture.transport.usageBody = HistoryAcceptanceAdmissions.SPARSE_USAGE
        seedDormant { HistoryAcceptanceAdmissions.sparse(it) }
        launch(landscape = true, dark = true).use { scenario ->
            openHistory(); page(WindowKind.FIVE_HOUR, 0, 7)
            chartLabels("Drawn markers: 6 · Within-run connections: 2 · Nominal reference lines: 0")
            captureCanvas("history-assembled-breaks-landscape-dark", scenario)
            click("history-details-toggle")
            detail(1, 7, "Measured used percent (exact %): 20")
            click("history-next"); detail(2, 7, "Measured used percent (exact %): 40")
            click("history-next"); detail(3, 7, "Gap reason (unknown means no recorded reason): background")
            noFact("Measured used percent (exact %): 0")
            click("history-next"); detail(4, 7, "Segment break: background")
            click("history-next"); detail(5, 7, "Segment break: used-percent correction; cause unknown")
            fact("Baseline and delta unavailable: uncertain correction")
            click("history-next"); detail(6, 7, "Measured used percent (exact %): 30")
            click("history-next"); detail(7, 7, "Segment break: window changed; reset cause unknown")
            fact("Absolute reset source (exact UTC): 2027-01-15T06:00:00Z")
            compose.onNodeWithTag("history-next").performScrollTo().assertIsNotEnabled()
            click("history-previous"); detail(6, 7, "Measured used percent (exact %): 30")
            dormant()
        }
    }

    @Test fun unknownResetAndUnknownPercentAtActualLargeFontKeepCompleteAccessibleFactsAndReachableControls() {
        fixture.transport.usageBody = HistoryAcceptanceAdmissions.UNKNOWN_USAGE
        seedDormant()
        launch(font = 2f).use { scenario ->
            openHistory(); page(WindowKind.FIVE_HOUR, 0, 1)
            effectiveFont(2f)
            chartLabels("Drawn markers: 1 · Within-run connections: 0 · Nominal reference lines: 0")
            capture.label("$five-no-baseline")
            captureCanvas("history-assembled-unknown-large-font", scenario)
            click("history-details-toggle")
            fact("Measured used percent (exact %): 12.375")
            fact("Baseline and delta unavailable: unkeyed reset")
            fact("Absolute reset source (exact UTC): unknown · not zero")
            singleFact("Measured used percent (exact %): 12.375")
            compose.onNodeWithTag("history-previous").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("history-next").performScrollTo().assertIsNotEnabled()
            click("history-select-WEEKLY"); page(WindowKind.WEEKLY, 0, 1)
            compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("history-chart-weekly-counts").assertTextEquals(
                "Drawn markers: 0 · Within-run connections: 0 · Nominal reference lines: 0")
            click("history-details-toggle")
            fact("Measured used percent (exact %): unknown · not zero")
            fact("Baseline and delta unavailable: used percent unknown")
            noFact("Measured used percent (exact %): 0")
            click("history-toggle")
            compose.onNodeWithTag("read-usage").performScrollTo().assertIsEnabled()
            dormant()
        }
    }

    @Test fun truncatedPageLandscapeLightKeepsHonestLocalCursorAndEveryBoundedDetailReachable() {
        seedDormant { it.seedLocalPage() }
        launch(landscape = true).use { scenario ->
            openHistory(); page(WindowKind.FIVE_HOUR, 0, 32)
            fact("More admitted entries exist beyond this page. This component does not fetch them; this is not complete history.")
            click("history-next-page"); page(WindowKind.FIVE_HOUR, 32, 3)
            compose.onNodeWithTag("history-page-ordinals").assertTextEquals("Retained page ordinals: 33 to 35")
            compose.onNodeWithTag("history-next-page").performScrollTo().assertIsNotEnabled()
            noFact("More admitted entries exist beyond this page. This component does not fetch them; this is not complete history.")
            capture.label("history-page-cursor")
            capture.label("history-page-ordinals")
            compose.onNodeWithTag("history-first-page").performScrollTo()
            capture.capture("history-assembled-page-landscape-light", scenario, fixture,
                listOf("connection-tab", "history-first-page", "history-next-page"))
            click("history-details-toggle")
            detail(1, 3, "Admission ordinal: 33")
            click("history-next"); detail(2, 3, "Admission ordinal: 34")
            click("history-next"); detail(3, 3, "Admission ordinal: 35")
            compose.onNodeWithTag("history-next").performScrollTo().assertIsNotEnabled()
            click("history-first-page"); page(WindowKind.FIVE_HOUR, 0, 32)
            compose.onNodeWithTag("history-first-page").performScrollTo().assertIsNotEnabled()
            assertEquals(35L, fixture.owner.historySnapshots.value.storage!!.lastAdmitted!!.ordinal)
            dormant()
        }
    }

    private fun launch(landscape: Boolean = false, dark: Boolean = false, font: Float = 1f): ActivityScenario<MainActivity> {
        settings.apply(dark, font)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            settings.observe(compose, scenario, landscape, dark, font)
            scenario.onActivity { assertSame(fixture.owner, it.connection) }
        } catch (error: Throwable) {
            try { scenario.close() } catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
            throw error
        }
        return scenario
    }

    private fun seedDormant(append: (HistoryNavigationFixture) -> Unit = {}) {
        await("initial isolated default owner") { fixture.owner.state.value.phase == ConnectionPhase.IDLE }
        fixture.owner.usageForeground(fixture.observer, true)
        fixture.owner.connect()
        await("original synthetic usage observation persisted") {
            fixture.owner.state.value.phase == ConnectionPhase.OBSERVED && !fixture.owner.state.value.refresh.refreshing &&
                fixture.owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L
        }
        append(fixture)
        routes = fixture.transport.counts()
        assertEquals(5L, fixture.transport.requests.get())
        assertEquals(2L, fixture.transport.gets.get())
        fixture.freshRuntime()
        await("fresh owner dormant restore (not process death)") { fixture.owner.state.value.phase == ConnectionPhase.RESTORED }
    }

    private fun openHistory() { click("connection-tab"); click("history-toggle") }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag != "connection-tab") node.performScrollTo()
        node.assertHasClickAction().performClick()
        compose.waitForIdle()
    }

    private fun page(kind: WindowKind, after: Long, count: Int) {
        await("integrated page $kind after=$after entries=$count") {
            val source = fixture.owner.historySnapshots.value
            source.query == HistoryGraphQuery(32, after.takeIf { it > 0 }?.let(::ObservationId), kind) &&
                source.readiness == HistoryReadiness.READY && source.storage?.entries?.size == count
        }
        compose.onNodeWithTag("history-page-cursor").assertTextEquals("Exclusive admission cursor: $after · Page limit: 32")
    }

    private fun chartLabels(counts: String) {
        compose.onNodeWithTag("$five-counts").assertTextEquals(counts)
        listOf("$five-title", "$five-legend", "$five-counts", "$five-first", "$five-last", "history-legend",
            "history-assumption", "history-sparse").forEach(capture::label)
        compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    private fun captureCanvas(name: String, scenario: ActivityScenario<MainActivity>) {
        compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        capture.capture(name, scenario, fixture, listOf("connection-tab"), chart = true)
    }

    private fun effectiveFont(expected: Float) {
        val layout = capture.layout(compose.onNodeWithTag("history-navigation-title").performScrollTo())
        assertEquals(expected, layout.layoutInput.density.fontScale)
        assertTrue(layout.layoutInput.density.density > 0)
    }

    private fun detail(position: Int, total: Int, value: String) {
        compose.onNodeWithTag("history-detail-position").assertTextEquals("Page entry $position of $total · admission order, not time order")
        fact(value)
    }

    private fun historyScope() = hasAnyAncestor(hasTestTag("connection-history"))
    private fun text(value: String) = compose.onAllNodes(historyScope() and hasText(value), useUnmergedTree = true).onFirst()
    private fun fact(value: String) { capture.layout(text(value).performScrollTo()) }
    private fun singleFact(value: String) {
        compose.onAllNodes(historyScope() and hasText(value), useUnmergedTree = true).assertCountEquals(1)
    }
    private fun noFact(value: String) {
        compose.onAllNodes(historyScope() and hasText(value), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun dormant() {
        assertEquals(routes, fixture.transport.counts())
        assertEquals(ConnectionPhase.RESTORED, fixture.owner.state.value.phase)
        assertEquals(5L, fixture.transport.requests.get())
        assertEquals(2L, fixture.transport.gets.get())
    }

    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(8_000, predicate) }
        catch (error: ComposeTimeoutException) {
            val source = fixture.owner.historySnapshots.value
            throw AssertionError("$step: last phase=${fixture.owner.state.value.phase}, readiness=${source.readiness}, query=${source.query}, entries=${source.storage?.entries?.size}", error)
        }
    }
}
