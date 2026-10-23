package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Existing normalized/plot facts are the oracle; these adapters never infer quota/count/time. */
class DashboardPresentationTest {
    @Test fun reportedInventoryCountNeverFallsBackToSummaryRowsOrZero() {
        val unknowns = listOf<Field<Long>?>(null, Field(Knowledge.UNAVAILABLE), Field(Knowledge.MALFORMED), Field(Knowledge.UNSUPPORTED))
        unknowns.forEach { count ->
            val facts = PresentedEntitlements(EntitlementState.UNKNOWN, EntitlementSnapshotState.CURRENT_GENERATION,
                summaryAvailableCount = Field(Knowledge.KNOWN, 7L), reportedAvailableCount = count, inventoryRowCount = 3)
            assertNull(DashboardPresentation.inventoryCount(facts))
        }
        for (count in listOf(0L, 9L, Long.MAX_VALUE)) {
            val facts = PresentedEntitlements(EntitlementState.DISCREPANT, EntitlementSnapshotState.CURRENT_GENERATION,
                summaryAvailableCount = Field(Knowledge.KNOWN, 7L), reportedAvailableCount = Field(Knowledge.KNOWN, count), inventoryRowCount = 3)
            assertEquals(count, DashboardPresentation.inventoryCount(facts))
        }
    }

    @Test fun compactMissingChartAndPhaseActionsKeepTypedErrorsAndSinglePointTruth() {
        val absent = HistoryGraphSnapshot()
        assertFalse(DashboardPresentation.hasChart(HistoryPlotInputs.project(absent).fiveHour))
        assertEquals(R.string.dashboard_no_history, DashboardPresentation.noInformation(absent))
        assertEquals(R.string.dashboard_no_history_loading, DashboardPresentation.noInformation(HistoryGraphSnapshot(HistoryReadiness.LOADING)))
        val error = HistoryGraphSnapshot(HistoryReadiness.ERROR, problem = HistoryRecorderProblem.READ_FAILURE, lostSamples = 1)
        assertEquals(R.string.dashboard_no_history_error, DashboardPresentation.noInformation(error))
        assertEquals(R.string.dashboard_no_history_malformed, DashboardPresentation.noInformation(absent, UsageWindowStatus.AVAILABLE, malformed = true))
        assertEquals(R.string.dashboard_no_history_malformed_error, DashboardPresentation.noInformation(error, UsageWindowStatus.AVAILABLE, malformed = true))
        assertEquals(R.string.dashboard_no_history_malformed_loading, DashboardPresentation.noInformation(HistoryGraphSnapshot(HistoryReadiness.LOADING), UsageWindowStatus.AVAILABLE, malformed = true))
        assertEquals(R.string.dashboard_window_malformed, DashboardPresentation.windowWarning(UsageWindowStatus.AVAILABLE, malformed = true))
        assertNull(DashboardPresentation.windowWarning(UsageWindowStatus.AVAILABLE))
        assertTrue(DashboardPresentation.loginVisible(ConnectionPhase.REAUTH_REQUIRED))
        assertFalse(DashboardPresentation.loginVisible(ConnectionPhase.RESTORED))
        assertTrue(DashboardPresentation.cancelVisible(ConnectionPhase.AUTHENTICATING))
        assertFalse(DashboardPresentation.cancelVisible(ConnectionPhase.SIGNING_OUT))
    }

    @Test fun compactChartEquivalentUsesOriginalExactFactsAndIndependentReferences() {
        val entry = SyntheticHistory.append().entry
        val query = HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR)
        val page = HistoryReadSnapshot(query.storage(entry.partition), listOf(entry), entry.id, false)
        val source = HistoryGraphSnapshot(HistoryReadiness.READY, query = query, storage = page)
        val series = HistoryPlotInputs.project(source).fiveHour
        val drawing = historyChartDrawing(series)
        assertTrue(DashboardPresentation.hasChart(series))
        assertEquals(1, drawing.observations.size); assertTrue(drawing.measured.isEmpty()); assertEquals(1, drawing.nominal.size)
        val detail = HistoryTextPresentation.detail(source.entries.single(), SyntheticHistory.at, java.time.ZoneOffset.UTC, java.util.Locale.US)
        assertEquals("12.375", detail.windows.single().facts.single { it.label == R.string.history_measured }.value)
        assertEquals("-67.625", detail.windows.single().facts.single { it.label == R.string.history_delta }.value)
        assertSame(entry.windows.first().point, series.samples.single().measured!!.source)
    }

    @Test fun paceLineUsesOverReserveAndOnPaceWithoutChangingExactComparison() {
        val source = BigDecimal("-67.625")
        assertEquals(PresentedPace(R.string.dashboard_reserve, "67.6"), DashboardPresentation.pace(source, Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "9.3"), DashboardPresentation.pace(BigDecimal("9.3333333333333333333333"), Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_on_pace), DashboardPresentation.pace(BigDecimal("0E-32"), Locale.US))
        assertEquals(BigDecimal("-67.625"), source)
    }

    @Test fun compactPaceBeyondFirst32ObservationsMatchesAcceptedHeadlineAndClock() {
        val start = Instant.parse("2027-01-15T01:00:00Z")
        val reset = Instant.parse("2027-01-15T05:00:00Z")
        val observations = List(33) { index ->
            SyntheticHistory.usage(SyntheticHistory.window(percent = Input.Value(BigDecimal(if (index == 32) "50" else "20")),
                absolute = Input.Value(reset.epochSecond)), observedAt = start.plusSeconds(index * 60L))
        }
        val display = currentDisplays(observations).first
        val accepted = acceptedUsage(observations.last())
        assertSame(observations.last(), accepted.observation)
        assertEquals("50", accepted.windows.first().percent)
        assertEquals(TimePresentation.timestamp(observations.last().observedAt, ZoneOffset.UTC, Locale.US), accepted.observedAt)
        assertEquals((1L..33L).toList(), display.entries.map { it.source.id.ordinal })
        assertFalse(display.hasMore)
        assertEquals(observations.last().observedAt, display.points.last().observedAt)
        assertEquals(ObservationId(33), display.latestCurrent!!.id)
        val comparison = DashboardPresentation.paceComparison(display, accepted.observation, WindowKind.FIVE_HOUR) as UsedComparison.Available
        assertEquals(observations.last().observedAt, comparison.point.observedAt)
        assertEquals(BigDecimal("50"), comparison.point.usedPercent)
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "19.3"), DashboardPresentation.pace(comparison.deltaPercentagePoints, Locale.US))
    }

    @Test fun compactPaceKeepsIndependentlyAgingFiveHourAndWeeklyObservationsSeparate() {
        for (aging in listOf(WindowKind.FIVE_HOUR, WindowKind.WEEKLY)) {
            val observations = List(320) { index ->
                val five = if (aging == WindowKind.FIVE_HOUR && index >= 318) Input.Missing else SyntheticHistory.window()
                val weekly = if (aging == WindowKind.WEEKLY && index >= 318) Input.Missing else
                    SyntheticHistory.window(duration = Input.Value(604800L))
                SyntheticHistory.usage(five, weekly, SyntheticHistory.at.plusSeconds(index.toLong()))
            }
            val (five, weekly) = currentDisplays(observations)
            val old = if (aging == WindowKind.FIVE_HOUR) five else weekly
            val fresh = if (aging == WindowKind.FIVE_HOUR) weekly else five
            val freshKind = if (aging == WindowKind.FIVE_HOUR) WindowKind.WEEKLY else WindowKind.FIVE_HOUR
            assertEquals(ObservationId(318), old.latestCurrent!!.id)
            assertEquals(observations[317].observedAt, old.latestCurrent.observedAt)
            assertEquals(ObservationId(320), fresh.latestCurrent!!.id)
            assertEquals(observations.last().observedAt, fresh.latestCurrent.observedAt)
            assertEquals(318, old.entries.size); assertEquals(320, fresh.entries.size)
            val previous = DashboardPresentation.paceComparison(old, observations[317], aging) as UsedComparison.Available
            assertEquals(observations[317].observedAt, previous.point.observedAt)
            assertNull(DashboardPresentation.paceComparison(old, observations.last(), aging))
            val current = DashboardPresentation.paceComparison(fresh, observations.last(), freshKind) as UsedComparison.Available
            assertEquals(observations.last().observedAt, current.point.observedAt)
            assertEquals(PresentedPace(R.string.dashboard_reserve, if (freshKind == WindowKind.FIVE_HOUR) "69.4" else "87.1"),
                DashboardPresentation.pace(current.deltaPercentagePoints, Locale.US))
        }
    }

    @Test fun compactPaceNeverFallsBackWhenAcceptedComparisonIsMissingOrIneligible() {
        val first = SyntheticHistory.usage(SyntheticHistory.window(percent = Input.Value(BigDecimal("50"))))
        val missing = SyntheticHistory.usage(SyntheticHistory.window(percent = Input.Missing), observedAt = SyntheticHistory.at.plusSeconds(1))
        val display = currentDisplays(listOf(first, missing)).first
        val unavailable = DashboardPresentation.paceComparison(display, missing, WindowKind.FIVE_HOUR) as UsedComparison.Unavailable
        assertEquals(DistributionUnavailable.Ineligible(BaselineEligibility.UNKNOWN_PERCENT), unavailable.reason)
        assertNull(unavailable.source.point)
        assertNull(DashboardPresentation.paceComparison(display, first, WindowKind.FIVE_HOUR))
        assertNull(DashboardPresentation.paceComparison(display, null, WindowKind.FIVE_HOUR))
        assertNull(DashboardPresentation.paceComparison(display, missing.copy(observedAt = null), WindowKind.FIVE_HOUR))
        val corrected = SyntheticHistory.usage(SyntheticHistory.window(percent = Input.Value(BigDecimal("20"))),
            observedAt = SyntheticHistory.at.plusSeconds(1))
        val correctedDisplay = currentDisplays(listOf(first, corrected)).first
        val correction = DashboardPresentation.paceComparison(correctedDisplay, corrected, WindowKind.FIVE_HOUR) as UsedComparison.Unavailable
        assertEquals(DistributionUnavailable.Ineligible(BaselineEligibility.UNCERTAIN_CORRECTION), correction.reason)
        assertNull(DashboardPresentation.paceComparison(correctedDisplay, first.copy(observedAt = corrected.observedAt), WindowKind.FIVE_HOUR))
        assertNull(DashboardPresentation.paceComparison(correctedDisplay, corrected.copy(observedAt = corrected.observedAt!!.plusSeconds(1)), WindowKind.FIVE_HOUR))
        assertNull(DashboardPresentation.paceComparison(HistoryGraphSnapshot(), corrected, WindowKind.FIVE_HOUR))
    }

    @Test fun paceLineHalfTenthBoundariesRoundAwayFromZero() {
        for ((value, resource) in listOf("0.05" to R.string.dashboard_over_pace, "-0.05" to R.string.dashboard_reserve)) {
            assertEquals(PresentedPace(resource, "0.1"), DashboardPresentation.pace(BigDecimal(value), Locale.US))
        }
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "1.3"), DashboardPresentation.pace(BigDecimal("1.25"), Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_reserve, "1.3"), DashboardPresentation.pace(BigDecimal("-1.25"), Locale.US))
    }

    @Test fun paceLineNearZeroAndAdjacentBoundaryValuesNeverShowSignedZero() {
        for (value in listOf("0", "0.049999999999", "-0.049999999999", "0.0001", "-0.0001")) {
            assertEquals(value, PresentedPace(R.string.dashboard_on_pace), DashboardPresentation.pace(BigDecimal(value), Locale.US))
        }
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "0.1"), DashboardPresentation.pace(BigDecimal("0.050000000001"), Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_reserve, "0.1"), DashboardPresentation.pace(BigDecimal("-0.050000000001"), Locale.US))
    }

    @Test fun paceLineKeepsOneDecimalAndLocaleSeparator() {
        assertEquals(PresentedPace(R.string.dashboard_over_pace, "10.0"), DashboardPresentation.pace(BigDecimal("9.99"), Locale.US))
        assertEquals(PresentedPace(R.string.dashboard_reserve, "12,4"), DashboardPresentation.pace(BigDecimal("-12.35"), Locale.GERMANY))
    }

    @Test fun bankedRowDetailsKeepMinutePrecisionAndIndependentStatesWithoutDuplicateAnnouncements() {
        for (remaining in listOf("less than a minute remaining", "2 hours 5 minutes remaining")) {
            assertEquals(remaining, DashboardPresentation.bankedRowDetails(remaining, null))
            assertEquals("$remaining, Unsupported item information",
                DashboardPresentation.bankedRowDetails(remaining, "Unsupported item information"))
        }
        assertEquals("Entitlement expired", DashboardPresentation.bankedRowDetails("Entitlement expired", "Entitlement expired"))
        assertEquals("Time unavailable, Item information unknown",
            DashboardPresentation.bankedRowDetails("Time unavailable", "Item information unknown"))
    }

    @Test fun chartTimeLabelsUseYearlessLocalePatternsAndContainingHour() {
        val at = Instant.parse("2027-01-15T08:37:12.123456789Z")
        assertEquals("Jan 15, 08:00", TimePresentation.chartTime(at, ZoneOffset.UTC, Locale.US, "MMM d, HH:mm"))
        assertEquals("15. Jan., 09:00", TimePresentation.chartTime(at, ZoneId.of("Europe/Berlin"), Locale.GERMANY, "d. MMM, HH:mm"))
        assertEquals("Jan 15, 00:00", TimePresentation.chartTime(at, ZoneId.of("America/Los_Angeles"), Locale.US, "MMM d, HH:mm"))
        assertEquals(Instant.parse("2027-01-15T08:37:12.123456789Z"), at)
    }

    @Test fun chartTimeLabelsRetainFullAndPartialRepeatedHourOffsets() {
        val ny = ZoneId.of("America/New_York")
        assertEquals("Nov 1, 01:00 UTC-04:00", TimePresentation.chartTime(Instant.parse("2026-11-01T05:30:00Z"), ny, Locale.US, "MMM d, HH:mm"))
        assertEquals("Nov 1, 01:00 UTC-05:00", TimePresentation.chartTime(Instant.parse("2026-11-01T06:30:00Z"), ny, Locale.US, "MMM d, HH:mm"))
        assertEquals("Apr 5, 01:00 UTC+11:00", TimePresentation.chartTime(Instant.parse("2026-04-04T14:15:00Z"),
            ZoneId.of("Australia/Lord_Howe"), Locale.US, "MMM d, HH:mm"))
    }

    @Test fun chartTimeLabelsKeepUnrepresentableInstantsUnknown() {
        assertNull(TimePresentation.chartTime(Instant.MAX, ZoneOffset.UTC, Locale.US, "MMM d, HH:mm"))
        assertNull(TimePresentation.chartTime(Instant.MIN, ZoneId.of("America/Los_Angeles"), Locale.US, "MMM d, HH:mm"))
    }

    @Test fun compactWholeWindowLastPointEndLabelAndTrendReachLatestObservationBeyond32() {
        val start = Instant.parse("2027-01-15T01:00:00Z")
        val reset = Instant.parse("2027-01-15T05:00:00Z")
        val observations = List(320) { index ->
            SyntheticHistory.usage(SyntheticHistory.window(percent = Input.Value(BigDecimal(index).divide(BigDecimal(4))),
                absolute = Input.Value(reset.epochSecond)), observedAt = start.plusSeconds(index * 30L))
        }
        val display = currentDisplays(observations).first
        val series = HistoryPlotInputs.project(display).fiveHour
        val last = observations.last().observedAt!!
        assertEquals(320, series.samples.size)
        assertEquals(last, series.samples.last().measured!!.source.observedAt)
        assertEquals(start, series.measuredDomain!!.time.first)
        assertEquals(last, series.measuredDomain.time.last)
        assertEquals("Jan 15, 03:00", TimePresentation.chartTime(series.measuredDomain.time.last, ZoneOffset.UTC, Locale.US, "MMM d, HH:mm"))
        val drawing = historyChartDrawing(series)
        assertEquals(319, drawing.measured.size)
        assertTrue(drawing.isolated.isEmpty())
        assertEquals(1.0, drawing.observations.last().x, 0.0)
        assertEquals(0.0, drawing.trend!!.start.x, 0.0)
        assertEquals(1.0, drawing.trend.end.x, 0.0)
        assertEquals(0.7975, drawing.trend.end.y, 1e-12)
        val reference = series.references.single()
        assertEquals(0.0, (reference.start as PlotPosition.Available).x, 0.0)
        assertEquals(1.0, (reference.end as PlotPosition.Available).x, 0.0)
        assertSame(display.latestCurrent!!.windows.first().point, series.samples.last().measured!!.source)
    }

    private fun acceptedUsage(usage: UsageObservation): PresentedUsage {
        val endpoint = EndpointObservation(io.github.leugenea.codexbarmobile.transport.ReadOperation.USAGE,
            status = 200, observedAt = usage.observedAt, usage = usage)
        val refresh = UsageRefreshState(usage = RefreshedEndpoint(endpoint, endpoint, 0), evaluatedAt = usage.observedAt)
        return UsagePresentation.present(ConnectionState(ConnectionPhase.OBSERVED, refresh = refresh), ZoneOffset.UTC, Locale.US)
    }

    private fun currentDisplays(observations: List<UsageObservation>): Pair<HistoryGraphSnapshot, HistoryGraphSnapshot> {
        var cursor = HistoryCursor(SyntheticHistory.partition)
        val entries = observations.map { usage ->
            val elapsed = java.time.Duration.between(observations.first().observedAt, usage.observedAt).toMillis()
            SyntheticHistory.append(cursor, usage, 1000 + elapsed).also { cursor = it.cursor }.entry
        }
        val first = observations.first()
        val request = HistoryPresentationRequest.Current(
            WindowHistory.identity(SyntheticHistory.partition, first.fiveHour, WindowKind.FIVE_HOUR),
            WindowHistory.identity(SyntheticHistory.partition, first.weekly, WindowKind.WEEKLY))
        val pages = (CurrentHistoryRead.readCurrent(ReadAccess(entries), request) { false } as CurrentHistoryReadOutcome.Ready).pages
        val generation = HistoryGeneration()
        val context = HistoryDisplayContext(generation).apply {
            periods = CurrentHistoryPeriods(CurrentHistoryPeriod(request.fiveHour), CurrentHistoryPeriod(request.weekly))
            selection = HistoryDisplaySelection(request)
        }
        val source = HistoryGraphSnapshot(HistoryReadiness.READY, generation, SyntheticHistory.partition,
            displayPermission = HistoryDisplayPermission(context, HistoryDisplayAuthority { context }),
            request = request, selection = context.selection, currentPages = pages)
        return currentHistoryForDisplay(source, request, WindowKind.FIVE_HOUR, context) to
            currentHistoryForDisplay(source, request, WindowKind.WEEKLY, context)
    }

    private class ReadAccess(private val entries: List<HistoryEntry>) : HistoryRuntimeAccess {
        override val partition = SyntheticHistory.partition
        override fun read(query: HistoryReadQuery): HistoryReadOutcome {
            val remaining = entries.filter { it.id.ordinal > (query.after?.ordinal ?: 0) }
            return HistoryReadOutcome.Ready(HistoryReadSnapshot(query, remaining.take(query.limit), entries.last().id,
                remaining.size > query.limit))
        }
        override fun append(admission: HistoryAdmission): HistoryAppendOutcome = error("Read-only fixture")
        override fun delete(partition: HistoryPartition): HistoryDeleteOutcome = error("Read-only fixture")
        override fun revoke() = Unit
    }
}
