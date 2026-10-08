package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Literal UI projections over synthetic A1/B2 observations; B1 owns all reset arithmetic. */
class UsagePresentationTest {
    @Test fun everyConnectionPhaseHasAnExplicitStatusAndNeverLeaksRetiredFacts() {
        val expected = mapOf(
            ConnectionPhase.RESTORING to UsageScreenStatus.LOADING,
            ConnectionPhase.IDLE to UsageScreenStatus.DISCONNECTED,
            ConnectionPhase.AUTHENTICATING to UsageScreenStatus.LOADING,
            ConnectionPhase.READING to UsageScreenStatus.CURRENT,
            ConnectionPhase.OBSERVED to UsageScreenStatus.CURRENT,
            ConnectionPhase.RESTORED to UsageScreenStatus.CURRENT,
            ConnectionPhase.CANCELLED to UsageScreenStatus.DISCONNECTED,
            ConnectionPhase.SIGNING_OUT to UsageScreenStatus.LOADING,
            ConnectionPhase.SIGNED_OUT to UsageScreenStatus.DISCONNECTED,
            ConnectionPhase.REAUTH_REQUIRED to UsageScreenStatus.REAUTHORIZE,
            ConnectionPhase.FAILED to UsageScreenStatus.ERROR,
        )
        val retained = refresh(observation())
        expected.forEach { (phase, status) ->
            val model = present(ConnectionState(phase, refresh = retained))
            assertEquals(phase.name, status, model.status)
            val connected = phase in setOf(ConnectionPhase.READING, ConnectionPhase.OBSERVED, ConnectionPhase.RESTORED)
            assertEquals(connected, model.windows.first().percent != null)
            assertEquals(connected && phase != ConnectionPhase.READING, model.refreshEnabled)
        }
    }

    @Test fun initialLoadingDormantAndUnavailableDoNotInventZeroUsage() {
        val dormant = present(ConnectionState(ConnectionPhase.RESTORED))
        assertEquals(UsageScreenStatus.UNAVAILABLE, dormant.status)
        assertTrue(dormant.refreshEnabled)
        val loading = present(ConnectionState(ConnectionPhase.READING, refresh = UsageRefreshState(refreshing = true)))
        assertEquals(UsageScreenStatus.LOADING, loading.status)
        assertTrue(loading.refreshing)
        assertFalse(loading.refreshEnabled)
        loading.windows.forEach { assertNull(it.percent); assertNull(it.progress); assertNull(it.reset) }
        val unavailable = model(UsageNormalizer.normalize(UsageInput(), NOW, NOW))
        assertEquals(UsageScreenStatus.CURRENT, unavailable.status)
        assertEquals(UsageWindowStatus.UNAVAILABLE, unavailable.windows.first().status)
        assertEquals(R.string.live_flag_unknown, unavailable.allowedResource)
    }

    @Test fun fractionalPrecisionAndLocaleAreRetainedWithoutIntegerRounding() {
        val usage = observation(percent = BigDecimal("12.345678901234567890"))
        val english = model(usage).windows.first()
        assertEquals("12.345678901234567890", english.percent)
        assertEquals(0.12345679f, english.progress!!, 0.00000001f)
        assertEquals(UsageWindowStatus.AVAILABLE, english.status)
        val german = UsagePresentation.present(ConnectionState(ConnectionPhase.OBSERVED, refresh = refresh(usage)), ZoneOffset.UTC, Locale.GERMANY)
        assertEquals("12,345678901234567890", german.windows.first().percent)
    }

    @Test fun extremeFractionExponentsStayExactWithoutPlainStringExpansionOrScaleOverflow() {
        listOf("1E-100000000", "1E-2147483647", "0E-2147483647").forEach { text ->
            val window = model(observation(percent = BigDecimal(text))).windows.first()
            assertEquals(text, window.percent)
            assertEquals(0f, window.progress!!, 0f)
            assertEquals(UsageWindowStatus.AVAILABLE, window.status)
        }
    }

    @Test fun weeklyOnlyPrimaryIsLabelledWeeklyAndFiveHourStaysUnavailable() {
        val usage = observation(seconds = 604800)
        val windows = model(usage).windows
        assertEquals(R.string.live_five_hour, windows[0].labelResource)
        assertEquals("live-five-hour", windows[0].tag)
        assertEquals(UsageWindowStatus.UNAVAILABLE, windows[0].status)
        assertNull(windows[0].percent)
        assertEquals(R.string.live_weekly, windows[1].labelResource)
        assertEquals("live-weekly", windows[1].tag)
        assertEquals("12.5", windows[1].percent)
        assertEquals(0.125f, windows[1].progress!!, 0f)
    }

    @Test fun missingResetKeepsUsageButNeverInventsADate() {
        val window = model(observation(reset = Input.Missing)).windows.first()
        assertEquals("12.5", window.percent)
        assertEquals(TimeState.UNKNOWN_TARGET, window.reset!!.state)
        assertNull(window.reset.absolute)
        assertNull(window.reset.remaining)
    }

    @Test fun knownResetUsesB1AbsoluteRelativeSubHourAndAwaitingRefreshWithoutZeroing() {
        val usage = observation(reset = Input.Value(TARGET.epochSecond))
        val time = model(usage).windows.first().reset!!
        assertEquals(AbsoluteTime("Oct 9, 2026", "14:00", null), time.absolute)
        assertEquals(RemainingTime(1, 14), time.remaining)
        val near = present(ConnectionState(ConnectionPhase.OBSERVED, refresh = refresh(usage).copy(evaluatedAt = TARGET.minusSeconds(1))))
        assertEquals(TimeState.LESS_THAN_HOUR, near.windows.first().reset!!.state)
        val passed = present(ConnectionState(ConnectionPhase.OBSERVED, refresh = refresh(usage).copy(evaluatedAt = TARGET)))
        assertEquals(TimeState.AWAITING_REFRESH, passed.windows.first().reset!!.state)
        assertEquals(TimeSnapshot.STALE, passed.windows.first().reset!!.snapshot)
        assertEquals("12.5", passed.windows.first().percent)
    }

    @Test fun timeProjectionRetainsRepeatedHourOffsetsAndUnknownClock() {
        val usage = observation(reset = Input.Value(Instant.parse("2026-11-01T06:30:00Z").epochSecond))
        val state = ConnectionState(ConnectionPhase.OBSERVED, refresh = refresh(usage))
        val time = UsagePresentation.present(state, ZoneId.of("America/New_York"), Locale.US).windows.first().reset!!
        assertEquals(AbsoluteTime("Nov 1, 2026", "01:00", "-05:00"), time.absolute)
        val unknown = present(ConnectionState(ConnectionPhase.OBSERVED, refresh = refresh(usage).copy(evaluatedAt = null)))
        assertEquals(TimeState.UNKNOWN_CLOCK, unknown.windows.first().reset!!.state)
        assertNotNull(unknown.windows.first().reset!!.absolute)
    }

    @Test fun exhaustedAndZeroBarsNeverGrantOrDenyProviderPermission() {
        val exhausted = model(observation(BigDecimal("100.00"), allowed = Input.Value(true), limit = Input.Value(false)))
        assertEquals("100.00", exhausted.windows.first().percent)
        assertEquals(1f, exhausted.windows.first().progress!!, 0f)
        assertEquals(UsageWindowStatus.EXHAUSTED, exhausted.windows.first().status)
        assertEquals(R.string.live_allowed, exhausted.allowedResource)
        assertEquals(R.string.live_limit_not_reached, exhausted.limitResource)
        val zero = model(observation(BigDecimal.ZERO, allowed = Input.Value(false), limit = Input.Value(true)))
        assertEquals("0", zero.windows.first().percent)
        assertEquals(UsageWindowStatus.AVAILABLE, zero.windows.first().status)
        assertEquals(R.string.live_not_allowed, zero.allowedResource)
        assertEquals(R.string.live_limit_reached, zero.limitResource)
    }

    @Test fun staleErrorAndRefreshingAreIndependentAndRetainSuccessfulNumbers() {
        val success = refresh(observation()).usage
        val attempt = EndpointObservation(ReadOperation.USAGE, error = ReadError.FORBIDDEN)
        val state = ConnectionState(ConnectionPhase.OBSERVED, refresh = UsageRefreshState(
            success.copy(attempt = attempt, stale = true), evaluatedAt = NOW, refreshing = true))
        val model = present(state)
        assertEquals(UsageScreenStatus.ERROR, model.status)
        assertEquals(R.string.live_forbidden, model.errorResource)
        assertTrue(model.stale)
        assertTrue(model.refreshing)
        assertFalse(model.refreshEnabled)
        assertEquals("12.5", model.windows.first().percent)
        assertEquals(TimeSnapshot.STALE, model.windows.first().reset!!.snapshot)
        val stale = present(ConnectionState(ConnectionPhase.OBSERVED, refresh = refresh(observation()).copy(usage = success.copy(stale = true))))
        assertEquals(UsageScreenStatus.STALE, stale.status)
    }

    @Test fun everyReadErrorHasExplicitCopyAndReauthorizationIsDistinct() {
        val expected = mapOf(
            ReadError.REAUTHORIZE to R.string.live_reauthorize, ReadError.FORBIDDEN to R.string.live_forbidden,
            ReadError.RATE_LIMITED to R.string.live_rate_limited, ReadError.TRANSIENT to R.string.live_transient,
            ReadError.INVALID_RESPONSE to R.string.live_invalid_response, ReadError.BODY_TOO_LARGE to R.string.live_invalid_response,
            ReadError.CANCELLED to R.string.live_cancelled, ReadError.DEADLINE_EXCEEDED to R.string.live_deadline,
            ReadError.OPERATION_NOT_ALLOWED to R.string.live_operation_unsupported,
        )
        expected.forEach { (error, resource) ->
            val attempt = EndpointObservation(ReadOperation.USAGE, error = error, notBeforeMillis = 120000)
            val model = present(ConnectionState(ConnectionPhase.OBSERVED, refresh = UsageRefreshState(usage = RefreshedEndpoint(attempt = attempt))))
            assertEquals(resource, model.errorResource)
            assertEquals(if (error == ReadError.REAUTHORIZE) UsageScreenStatus.REAUTHORIZE else UsageScreenStatus.ERROR, model.status)
            assertTrue(model.refreshEnabled)
            assertNull(model.windows.first().percent)
        }
    }

    @Test fun inventoryErrorOrStalenessDoesNotRewriteUsageStatusOrItsClock() {
        val refreshed = refresh(observation())
        val inventory = RefreshedEndpoint(attempt = EndpointObservation(ReadOperation.RESET_INVENTORY, error = ReadError.FORBIDDEN), stale = true)
        val model = present(ConnectionState(ConnectionPhase.OBSERVED, refresh = refreshed.copy(inventory = inventory)))
        assertEquals(UsageScreenStatus.CURRENT, model.status)
        assertFalse(model.stale)
        assertNull(model.errorResource)
        assertEquals(RemainingTime(1, 14), model.windows.first().reset!!.remaining)
    }

    @Test fun malformedPercentageKeepsKnownResetAndMalformedResetKeepsKnownPercentage() {
        val badPercent = UsageNormalizer.normalize(UsageInput(primary = Input.Value(WindowInput(
            Input.Value(18000), Input.Invalid, Input.Value(TARGET.epochSecond)))), NOW, NOW)
        val percentage = model(badPercent).windows.first()
        assertEquals(UsageWindowStatus.MALFORMED, percentage.status)
        assertTrue(percentage.malformed)
        assertNull(percentage.percent)
        assertEquals(AbsoluteTime("Oct 9, 2026", "14:00", null), percentage.reset!!.absolute)
        val reset = model(observation(reset = Input.Invalid)).windows.first()
        assertTrue(reset.malformed)
        assertEquals("12.5", reset.percent)
        assertEquals(TimeState.UNKNOWN_TARGET, reset.reset!!.state)
        assertEquals(Knowledge.MALFORMED, reset.reset.targetKnowledge)
    }

    @Test fun ambiguousWindowsNeverChooseACandidateAndUnsupportedKnowledgeIsExplicit() {
        val window = WindowInput(Input.Value(18000), Input.Value(BigDecimal("7.25")))
        val ambiguous = model(UsageNormalizer.normalize(UsageInput(primary = Input.Value(window), secondary = Input.Value(window)), NOW, NOW)).windows.first()
        assertEquals(UsageWindowStatus.AMBIGUOUS, ambiguous.status)
        assertNull(ambiguous.percent)
        assertNull(ambiguous.reset)
        val source = observation().fiveHour.candidates.single()
        val unsupported = observation().copy(fiveHour = WindowSelection(SelectionState.KNOWN,
            listOf(source.copy(usedPercent = Field(Knowledge.UNSUPPORTED)))))
        assertEquals(UsageWindowStatus.UNSUPPORTED, model(unsupported).windows.first().status)
        val absent = observation().copy(fiveHour = WindowSelection(SelectionState.KNOWN, emptyList()))
        assertEquals(UsageWindowStatus.UNAVAILABLE, model(absent).windows.first().status)
    }

    @Test fun unknownMalformedAndUnsupportedFlagsAreNotGuessed() {
        val usage = observation()
        listOf(Knowledge.UNAVAILABLE to R.string.live_flag_unknown,
            Knowledge.MALFORMED to R.string.live_flag_malformed,
            Knowledge.UNSUPPORTED to R.string.live_flag_unsupported).forEach { (knowledge, resource) ->
            val model = model(usage.copy(allowed = Field(knowledge), limitReached = Field(knowledge)))
            assertEquals(resource, model.allowedResource)
            assertEquals(resource, model.limitResource)
        }
        assertEquals(R.string.live_flag_unknown, model(usage.copy(allowed = Field(Knowledge.KNOWN))).allowedResource)
    }

    private fun observation(percent: BigDecimal = BigDecimal("12.5"), seconds: Long = 18000,
        reset: Input<Any> = Input.Value(TARGET.epochSecond), allowed: Input<Any> = Input.Value(true),
        limit: Input<Any> = Input.Value(false)) = UsageNormalizer.normalize(UsageInput(
            primary = Input.Value(WindowInput(Input.Value(seconds), Input.Value(percent), reset)),
            allowed = allowed, limitReached = limit), NOW, NOW)

    private fun refresh(usage: UsageObservation): UsageRefreshState {
        val endpoint = EndpointObservation(ReadOperation.USAGE, status = 200, observedAt = NOW, usage = usage)
        return UsageRefreshState(usage = RefreshedEndpoint(endpoint, endpoint, 0), evaluatedAt = NOW)
    }
    private fun model(usage: UsageObservation) = present(ConnectionState(ConnectionPhase.OBSERVED, refresh = refresh(usage)))
    private fun present(state: ConnectionState) = UsagePresentation.present(state, ZoneOffset.UTC, Locale.US)

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-08T00:00:00Z")
        val TARGET: Instant = Instant.parse("2026-10-09T14:59:59Z")
    }
}
