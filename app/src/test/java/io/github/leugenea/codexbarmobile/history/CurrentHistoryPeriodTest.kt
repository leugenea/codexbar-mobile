package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CurrentHistoryPeriodTest {
    @Test fun normalizedDurationSelectionUsesExactAbsoluteIdentityAndExistingEvaluationClock() {
        val five = SyntheticHistory.window()
        val weekly = SyntheticHistory.window(duration = Input.Value(604800L), absolute = Input.Value(1_800_604_800L))
        val usage = SyntheticHistory.usage(weekly, five)
        val fiveIdentity = period(usage).window!!
        assertEquals(WindowIdentity(SyntheticHistory.partition, WindowKind.FIVE_HOUR, 18000, Instant.ofEpochSecond(1_800_003_600)), fiveIdentity)
        assertEquals(SyntheticHistory.append(usage = usage).entry.windows.first().point!!.segment.window, fiveIdentity)
        assertEquals(604800L, period(usage, kind = WindowKind.WEEKLY).window!!.durationSeconds)
        assertNull(period(usage, partition = null).window)
        val periods = currentHistoryPeriods(SyntheticHistory.partition, refresh(usage))
        assertEquals(fiveIdentity, periods.forKind(WindowKind.FIVE_HOUR).window)
        assertEquals(604800L, periods.forKind(WindowKind.WEEKLY).window!!.durationSeconds)
        assertNull(periods.forKind(WindowKind.UNKNOWN).window)
        assertEquals(SyntheticHistory.at, periods.evaluatedAt)
    }

    @Test fun expiryClockLossBeforeStartAmbiguityAndRelativeDiscrepantMalformedResetNeverFallback() {
        val usage = SyntheticHistory.usage()
        assertEquals(BaselineEligibility.CLOCK_DISCONTINUITY, period(usage, SyntheticHistory.at.minusSeconds(1)).unavailable)
        assertEquals(BaselineEligibility.AFTER_RESET, period(usage, Instant.ofEpochSecond(1_800_003_600)).unavailable)
        assertEquals(BaselineEligibility.CLOCK_UNVERIFIED, period(usage, null).unavailable)
        assertEquals(BaselineEligibility.BEFORE_NOMINAL_START, period(usage.copy(observedAt = SyntheticHistory.at.minusSeconds(20000)), SyntheticHistory.at.minusSeconds(18000)).unavailable)
        assertEquals(BaselineEligibility.AMBIGUOUS_WINDOW, period(SyntheticHistory.usage(secondary = SyntheticHistory.window())).unavailable)
        for (window in listOf(SyntheticHistory.window(absolute = Input.Missing, relative = Input.Value(3600L)),
            SyntheticHistory.window(absolute = Input.Value("invalid")), SyntheticHistory.window(relative = Input.Value(3601L)))) {
            assertEquals(BaselineEligibility.UNKEYED_RESET, period(SyntheticHistory.usage(window)).unavailable)
        }
    }

    @Test fun weeklyToleranceKeepsAbsoluteOriginalAndChangedResetGetsANewQueryKey() {
        val absolute = 1_800_003_600L
        fun weekly(relative: Long) = SyntheticHistory.usage(SyntheticHistory.window(duration = Input.Value(604800L), relative = Input.Value(relative)))
        assertEquals(Instant.ofEpochSecond(absolute), period(weekly(3660), kind = WindowKind.WEEKLY).window!!.resetAt)
        assertNull(period(weekly(3661), kind = WindowKind.WEEKLY).window)
        val old = period(SyntheticHistory.usage()).window
        val changed = period(SyntheticHistory.usage(SyntheticHistory.window(absolute = Input.Value(absolute + 1)))).window
        assertNotEquals(old, changed)
        assertNotEquals(HistoryGraphQuery(window = old, currentOnly = true), HistoryGraphQuery(window = changed, currentOnly = true))
    }

    @Test fun staleAndEndpointFailureRemainIndependentOfKnownUnexpiredIdentity() {
        val refresh = refresh(SyntheticHistory.usage()).let { it.copy(usage = it.usage.copy(stale = true,
            attempt = EndpointObservation(ReadOperation.USAGE, error = io.github.leugenea.codexbarmobile.transport.ReadError.FORBIDDEN))) }
        assertNotNull(currentHistoryPeriod(SyntheticHistory.partition, refresh, WindowKind.FIVE_HOUR).window)
        val metadata = HistoryLiveMetadata.from(refresh)
        assertTrue(metadata.usage.stale)
        assertEquals(io.github.leugenea.codexbarmobile.transport.ReadError.FORBIDDEN, metadata.usage.error)
    }

    @Test fun freshHostAuthorityRejectsHeldPeriodOnExpiryClockLossResetChangeAndAccountRetirement() {
        val retained = SyntheticHistory.append()
        val context = HistoryDisplayContext(HistoryGeneration())
        context.periods = currentHistoryPeriods(SyntheticHistory.partition, refresh(SyntheticHistory.usage()))
        var authority: HistoryDisplayContext? = context
        val permission = HistoryDisplayPermission(context, HistoryDisplayAuthority { authority })
        val query = HistoryGraphQuery(32, kind = WindowKind.FIVE_HOUR, window = retained.entry.windows.first().point!!.segment.window, currentOnly = true)
        val page = HistoryReadSnapshot(query.storage(SyntheticHistory.partition), listOf(retained.entry), retained.entry.id, false)
        val source = HistoryGraphSnapshot(HistoryReadiness.READY, context.generation, SyntheticHistory.partition, query, page, displayPermission = permission)
        assertSame(source, historyForDisplay(source, query))
        val initial = refresh(SyntheticHistory.usage())
        for (changed in listOf(initial.copy(evaluatedAt = query.window!!.resetAt), initial.copy(evaluatedAt = null),
            refresh(SyntheticHistory.usage(SyntheticHistory.window(absolute = Input.Value(1_800_003_601L)))))) {
            context.periods = currentHistoryPeriods(SyntheticHistory.partition, changed)
            val hidden = historyForDisplay(source, query)
            assertNull(hidden.storage)
            assertTrue(hidden.points.isEmpty())
            assertSame(context, permission.current())
        }
        authority = null
        assertNull(historyForDisplay(source, query).storage)
        authority = HistoryDisplayContext(HistoryGeneration())
        assertNull(historyForDisplay(source, query).storage)
    }

    private fun period(usage: UsageObservation, now: Instant? = SyntheticHistory.at,
        partition: HistoryPartition? = SyntheticHistory.partition, kind: WindowKind = WindowKind.FIVE_HOUR) =
        currentHistoryPeriod(partition, refresh(usage).copy(evaluatedAt = now), kind)

    private fun refresh(usage: UsageObservation) = UsageRefreshState(
        usage = RefreshedEndpoint(success = EndpointObservation(ReadOperation.USAGE, usage = usage, observedAt = usage.observedAt)),
        evaluatedAt = SyntheticHistory.at)
}
