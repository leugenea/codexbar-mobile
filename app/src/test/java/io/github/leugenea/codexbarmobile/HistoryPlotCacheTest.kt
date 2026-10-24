package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.Input
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoryPlotCacheTest {
    @Test fun metadataTicksReuseWholeWindowGraphAndDrawingInputsWhileKeepingLiveMetadataAndPaceCurrent() {
        val fixture = Fixture()
        val cache = HistoryPlotCache()
        val weeklyCache = HistoryPlotCache()
        val original = cache.project(fixture.display())
        val weekly = weeklyCache.project(fixture.display(kind = WindowKind.WEEKLY))
        assertEquals(320, original.fiveHour.samples.size)
        assertEquals(320, weekly.weekly.samples.size)
        assertNotNull(historyChartDrawing(original.fiveHour).trend)
        assertNotNull(DashboardPresentation.paceComparison(original.source, fixture.observations[319], WindowKind.FIVE_HOUR))
        repeat(10) { tick ->
            val live = HistoryLiveMetadata(HistoryEndpointMetadata(SyntheticHistory.at,
                SyntheticHistory.at.plusSeconds(400L + tick), 503, stale = true), refreshing = tick % 2 == 0)
            val display = fixture.display(live = live, problem = HistoryRecorderProblem.READ_FAILURE, lost = tick.toLong())
            val plot = cache.project(display)
            // Stable series are the exact keys used by Compose to remember drawing/OLS.
            assertSame(original.fiveHour, plot.fiveHour)
            assertSame(weekly.weekly, weeklyCache.project(fixture.display(kind = WindowKind.WEEKLY, live = live)).weekly)
            assertSame(original.fiveHour.samples.first().window, plot.fiveHour.samples.first().window)
            assertSame(display, plot.source)
            assertSame(live, plot.source.live)
            assertEquals(HistoryRecorderProblem.READ_FAILURE, plot.source.problem)
            assertEquals(tick.toLong(), plot.source.lostSamples)
        }
        // Latest-admission provenance is not part of static geometry and cannot be frozen by it.
        val noLatest = fixture.withoutLatest()
        val unbound = cache.project(fixture.display(pages = noLatest))
        assertSame(original.fiveHour, unbound.fiveHour)
        assertNull(unbound.source.latestCurrent)
        assertNull(DashboardPresentation.paceComparison(unbound.source, fixture.observations[319], WindowKind.FIVE_HOUR))
    }

    @Test fun changedPageQueryGenerationPartitionRequestAndSelectionRebuildWholeWindowInputs() {
        val fixture = Fixture()
        val cache = HistoryPlotCache()
        val source = fixture.display()
        val original = cache.project(source)
        val changed = cache.project(fixture.display(pages = fixture.pages(count = 321)))
        assertNotSame(original.fiveHour, changed.fiveHour)
        assertEquals(321, changed.fiveHour.samples.size)
        assertEquals(source.query.window!!.resetAt, changed.fiveHour.measuredDomain!!.time.last)
        assertEquals(321, historyChartDrawing(changed.fiveHour).observations.size)
        // Even an equal-content replacement page is a new retained identity, not a deep-list key.
        val replacements = listOf(
            copyInputs(source, page = fixture.pages().fiveHour),
            copyInputs(source, query = source.query.copy(limit = source.query.limit - 1)),
            copyInputs(source, generation = HistoryGeneration()),
            copyInputs(source, partition = HistoryPartition(UUID(0, 99))),
            copyInputs(source, request = HistoryPresentationRequest.Page(source.query)),
            copyInputs(source, selection = HistoryDisplaySelection(source.request)),
        )
        for (replacement in replacements) {
            val before = cache.project(source)
            val after = cache.project(replacement)
            assertNotSame(before.fiveHour, after.fiveHour)
            assertSame(after.fiveHour, cache.project(replacement).fiveHour)
        }
    }

    @Test fun cachedCurrentInputsNeverBypassPeriodOrDisplayRevocation() {
        val fixture = Fixture()
        val cache = HistoryPlotCache()
        val source = fixture.source()
        assertTrue(DashboardPresentation.hasChart(cache.project(fixture.display(source = source)).fiveHour))
        fixture.context.periods = CurrentHistoryPeriods()
        val expired = cache.project(fixture.display(source = source))
        assertFalse(DashboardPresentation.hasChart(expired.fiveHour))
        assertNull(expired.source.storage)
        assertNull(expired.source.latestCurrent)
        fixture.context.periods = fixture.periods
        assertTrue(DashboardPresentation.hasChart(cache.project(fixture.display(source = source)).fiveHour))
        fixture.actual = null
        val retired = cache.project(fixture.display(source = source))
        assertFalse(DashboardPresentation.hasChart(retired.fiveHour))
        assertNull(retired.source.storage)
        assertNull(retired.source.latestCurrent)
        assertEquals(HistoryLiveMetadata(), retired.source.live)
    }

    @Test fun changedWindowBoundsInvalidateCachedGeometryWithoutChangingTheRetainedPage() {
        val fixture = Fixture()
        val cache = HistoryPlotCache()
        val source = fixture.display()
        val before = cache.project(source)
        val window = source.query.window!!
        val changed = copyInputs(source, query = source.query.copy(window = window.copy(resetAt = window.resetAt.plusSeconds(60))))
        assertNotSame(before.fiveHour, cache.project(changed).fiveHour)
        assertTrue(cache.project(changed).fiveHour.samples.isEmpty()) // Old points cannot fill a successor window.
        assertEquals(before.fiveHour.measuredDomain, cache.project(source).fiveHour.measuredDomain)
    }

    private fun copyInputs(source: HistoryGraphSnapshot, page: HistoryReadSnapshot? = source.storage,
        query: HistoryGraphQuery = source.query, generation: HistoryGeneration? = source.generation,
        partition: HistoryPartition? = source.partition, request: HistoryPresentationRequest = source.request,
        selection: HistoryDisplaySelection? = source.selection) =
        HistoryGraphSnapshot(source.readiness, generation, partition, query, page,
            request = request, selection = selection)

    private class Fixture {
        val observations = List(321) { index ->
            SyntheticHistory.usage(secondary = SyntheticHistory.window(duration = Input.Value(604800L),
                absolute = Input.Value(1_800_604_800L)), observedAt = SyntheticHistory.at.plusSeconds(index.toLong()))
        }
        private var cursor = HistoryCursor(SyntheticHistory.partition)
        private val entries = observations.mapIndexed { index, observation ->
            SyntheticHistory.append(cursor, observation, 1000L + index * 1000L).also { cursor = it.cursor }.entry
        }
        val request = HistoryPresentationRequest.Current(
            WindowHistory.identity(SyntheticHistory.partition, observations.first().fiveHour, WindowKind.FIVE_HOUR),
            WindowHistory.identity(SyntheticHistory.partition, observations.first().weekly, WindowKind.WEEKLY))
        val periods = CurrentHistoryPeriods(CurrentHistoryPeriod(request.fiveHour), CurrentHistoryPeriod(request.weekly))
        val context = HistoryDisplayContext(HistoryGeneration()).apply {
            periods = this@Fixture.periods
            selection = HistoryDisplaySelection(request)
        }
        var actual: HistoryDisplayContext? = context
        private val permission = HistoryDisplayPermission(context, HistoryDisplayAuthority { actual })
        private val retained = pages()

        fun withoutLatest() = CurrentHistoryPages(retained.fiveHour, retained.weekly)

        fun pages(count: Int = 320): CurrentHistoryPages {
            val selected = entries.take(count)
            val five = HistoryReadSnapshot(request.query(WindowKind.FIVE_HOUR).storage(SyntheticHistory.partition),
                selected, selected.last().id, false)
            val weekly = HistoryReadSnapshot(request.query(WindowKind.WEEKLY).storage(SyntheticHistory.partition),
                selected, selected.last().id, false)
            return CurrentHistoryPages(five, weekly, selected.last(), selected.last())
        }

        fun source(pages: CurrentHistoryPages = retained, live: HistoryLiveMetadata = HistoryLiveMetadata(),
            problem: HistoryRecorderProblem? = null, lost: Long = 0) =
            HistoryGraphSnapshot(HistoryReadiness.READY, context.generation, SyntheticHistory.partition,
                problem = problem, lostSamples = lost, live = live, displayPermission = permission,
                request = request, currentPages = pages)

        fun display(pages: CurrentHistoryPages = retained, live: HistoryLiveMetadata = HistoryLiveMetadata(),
            problem: HistoryRecorderProblem? = null, lost: Long = 0, kind: WindowKind = WindowKind.FIVE_HOUR,
            source: HistoryGraphSnapshot = source(pages, live, problem, lost)) =
            currentHistoryForDisplay(source, request, kind, actual)
    }
}
