package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.usage.BankedResetNormalizer
import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.Input
import io.github.leugenea.codexbarmobile.usage.InventoryInput
import io.github.leugenea.codexbarmobile.usage.Knowledge
import io.github.leugenea.codexbarmobile.usage.Reason
import io.github.leugenea.codexbarmobile.usage.ResetItemInput
import io.github.leugenea.codexbarmobile.usage.ResetTime
import io.github.leugenea.codexbarmobile.usage.UsageInput
import io.github.leugenea.codexbarmobile.usage.UsageNormalizer
import io.github.leugenea.codexbarmobile.usage.WindowInput
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Synthetic fixed instants; expected date/hour/count/state values are independent literals. */
class TimePresentationTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private fun known(text: String) = Field(Knowledge.KNOWN, Instant.parse(text))
    private fun present(
        text: String, at: Instant? = now, observed: Instant? = now,
        zone: ZoneId = utc, locale: Locale = Locale.US,
        kind: TimeKind = TimeKind.PERIODIC_RESET, stale: Boolean = false,
    ) = TimePresentation.present(known(text), kind, observed, at, zone, locale, staleSnapshot = stale)

    @Test fun absoluteAndRelativeUseTheOriginalInstantAndExplicitZone() {
        val target = known("2026-10-09T14:59:59.999999999Z")
        val clock = Clock.fixed(now, ZoneId.of("Asia/Tokyo"))
        val cases = listOf(
            Triple("UTC", AbsoluteTime("Oct 9, 2026", "14:00", null), Locale.US),
            Triple("America/Los_Angeles", AbsoluteTime("Oct 9, 2026", "07:00", null), Locale.US),
            Triple("Asia/Tokyo", AbsoluteTime("Oct 9, 2026", "23:00", null), Locale.US),
            Triple("Pacific/Kiritimati", AbsoluteTime("10 Oct 2026", "04:00", null), Locale.UK),
        )
        for ((zone, absolute, locale) in cases) {
            val result = TimePresentation.presentWithClock(target, TimeKind.PERIODIC_RESET, now, clock, ZoneId.of(zone), locale)
            assertEquals(absolute, result.absolute)
            assertEquals(RemainingTime(1, 14), result.remaining)
            assertEquals(TimeState.FUTURE, result.state)
            assertEquals(TimeKind.PERIODIC_RESET, result.kind)
            assertEquals(TimeSnapshot.OBSERVED, result.snapshot)
            assertEquals(Knowledge.KNOWN, result.targetKnowledge)
            assertNull(result.targetReason)
        }
    }

    @Test fun localizedDateDoesNotDependOnTheDefaultLocale() {
        val result = present("2026-10-09T14:20:00Z", locale = Locale.GERMANY)
        assertEquals(AbsoluteTime("09.10.2026", "14:00", null), result.absolute)
        assertEquals(RemainingTime(1, 14), result.remaining)
    }

    @Test fun dayHourAndSubsecondBoundariesFloorElapsedHours() {
        val cases = listOf(
            Triple("2026-10-08T00:00:00.000000001Z", TimeState.LESS_THAN_HOUR, null),
            Triple("2026-10-08T00:59:59.999999999Z", TimeState.LESS_THAN_HOUR, null),
            Triple("2026-10-08T01:00:00Z", TimeState.FUTURE, RemainingTime(0, 1)),
            Triple("2026-10-08T01:59:59.999999999Z", TimeState.FUTURE, RemainingTime(0, 1)),
            Triple("2026-10-08T23:59:59.999999999Z", TimeState.FUTURE, RemainingTime(0, 23)),
            Triple("2026-10-09T00:00:00Z", TimeState.FUTURE, RemainingTime(1, 0)),
            Triple("2026-10-10T14:59:59.999999999Z", TimeState.FUTURE, RemainingTime(2, 14)),
        )
        for ((target, state, remaining) in cases) {
            val result = present(target)
            assertEquals(target, state, result.state)
            assertEquals(target, remaining, result.remaining)
        }
    }

    @Test fun identicalHourLabelsDoNotDetermineWhetherTheInstantPassed() {
        val at = Instant.parse("2026-10-08T00:30:00Z")
        val future = present("2026-10-08T00:30:00.000000001Z", at)
        val equal = present("2026-10-08T00:30:00Z", at)
        val past = present("2026-10-08T00:29:59.999999999Z", at)
        for (result in listOf(future, equal, past)) {
            assertEquals(AbsoluteTime("Oct 8, 2026", "00:00", null), result.absolute)
            assertNull(result.remaining)
        }
        assertEquals(TimeState.LESS_THAN_HOUR, future.state)
        assertEquals(TimeState.AWAITING_REFRESH, equal.state)
        assertEquals(TimeState.AWAITING_REFRESH, past.state)
        assertEquals(TimeSnapshot.STALE, equal.snapshot)
    }

    @Test fun entitlementExpiresAtEqualityWithoutBecomingAReset() {
        val result = present("2026-10-08T00:00:00Z", kind = TimeKind.ENTITLEMENT_EXPIRY)
        assertEquals(TimeState.EXPIRED, result.state)
        assertEquals(TimeKind.ENTITLEMENT_EXPIRY, result.kind)
        assertEquals(TimeSnapshot.OBSERVED, result.snapshot)
        assertEquals(AbsoluteTime("Oct 8, 2026", "00:00", null), result.absolute)
        assertNull(result.remaining)
        assertEquals(TimeState.EXPIRED, present("2026-10-07T23:59:59Z", kind = TimeKind.ENTITLEMENT_EXPIRY).state)
        assertEquals(TimeState.LESS_THAN_HOUR, present("2026-10-08T00:01:00Z", kind = TimeKind.ENTITLEMENT_EXPIRY).state)
    }

    @Test fun springForwardUsesElapsedTimeNotTheMissingLocalHour() {
        val result = present(
            "2026-03-08T07:30:00Z", Instant.parse("2026-03-08T06:30:00Z"),
            Instant.parse("2026-03-08T06:00:00Z"), ZoneId.of("America/New_York"),
        )
        assertEquals(AbsoluteTime("Mar 8, 2026", "03:00", null), result.absolute)
        assertEquals(RemainingTime(0, 1), result.remaining)
        assertEquals(TimeState.FUTURE, result.state)
    }

    @Test fun bothOccurrencesOfFallBackHourShowTheirActualUtcOffset() {
        val zone = ZoneId.of("America/New_York")
        val at = Instant.parse("2026-11-01T04:30:00Z")
        val first = present("2026-11-01T05:30:00Z", at, at, zone)
        val second = present("2026-11-01T06:30:00Z", at, at, zone)
        assertEquals(AbsoluteTime("Nov 1, 2026", "01:00", "-04:00"), first.absolute)
        assertEquals(AbsoluteTime("Nov 1, 2026", "01:00", "-05:00"), second.absolute)
        assertEquals(RemainingTime(0, 1), first.remaining)
        assertEquals(RemainingTime(0, 2), second.remaining)
        val subHour = present("2026-11-01T06:30:00Z", Instant.parse("2026-11-01T05:45:00Z"), at, zone)
        assertEquals(TimeState.LESS_THAN_HOUR, subHour.state)
        assertEquals("-05:00", subHour.absolute?.utcOffset)
    }

    @Test fun fallBackTransitionBoundaryAndTheFollowingHourAreDistinct() {
        val zone = ZoneId.of("America/New_York")
        val at = Instant.parse("2026-11-01T04:00:00Z")
        val transition = present("2026-11-01T06:00:00Z", at, at, zone)
        val afterOverlap = present("2026-11-01T07:00:00Z", at, at, zone)
        assertEquals(AbsoluteTime("Nov 1, 2026", "01:00", "-05:00"), transition.absolute)
        assertEquals(AbsoluteTime("Nov 1, 2026", "02:00", null), afterOverlap.absolute)
        assertEquals(RemainingTime(0, 2), transition.remaining)
        assertEquals(RemainingTime(0, 3), afterOverlap.remaining)
    }

    @Test fun repeatedHourWithZeroOffsetUsesNumericUtcOffsetNotZ() {
        val zone = ZoneId.of("Europe/London")
        val at = Instant.parse("2026-10-24T23:30:00Z")
        val first = present("2026-10-25T00:30:00Z", at, at, zone)
        val second = present("2026-10-25T01:30:00Z", at, at, zone)
        assertEquals(AbsoluteTime("Oct 25, 2026", "01:00", "+01:00"), first.absolute)
        assertEquals(AbsoluteTime("Oct 25, 2026", "01:00", "+00:00"), second.absolute)
        assertEquals(RemainingTime(0, 1), first.remaining)
        assertEquals(RemainingTime(0, 2), second.remaining)
    }

    @Test fun halfHourFallBackLabelsTheWholeDisplayedHourAsAmbiguous() {
        val zone = ZoneId.of("Australia/Lord_Howe")
        val at = Instant.parse("2026-04-04T12:00:00Z")
        val beforeOverlap = present("2026-04-04T14:15:00Z", at, at, zone)
        val inOverlap = present("2026-04-04T15:15:00Z", at, at, zone)
        assertEquals(AbsoluteTime("Apr 5, 2026", "01:00", "+11:00"), beforeOverlap.absolute)
        assertEquals(AbsoluteTime("Apr 5, 2026", "01:00", "+10:30"), inOverlap.absolute)
        assertEquals(RemainingTime(0, 2), beforeOverlap.remaining)
        assertEquals(RemainingTime(0, 3), inOverlap.remaining)
    }

    @Test fun dstDaysAreTwentyFourElapsedHoursNotCalendarDateDifferences() {
        val zone = ZoneId.of("America/New_York")
        val spring = present("2026-03-09T04:00:00Z", Instant.parse("2026-03-08T05:00:00Z"), null, zone)
        val fall = present("2026-11-02T05:00:00Z", Instant.parse("2026-11-01T04:00:00Z"), null, zone)
        assertEquals(AbsoluteTime("Mar 9, 2026", "00:00", null), spring.absolute)
        assertEquals(RemainingTime(0, 23), spring.remaining)
        assertEquals(AbsoluteTime("Nov 2, 2026", "00:00", null), fall.absolute)
        assertEquals(RemainingTime(1, 1), fall.remaining)
        assertEquals(TimeSnapshot.UNKNOWN, spring.snapshot)
    }

    @Test fun explicitStaleSnapshotsKeepTheFutureTargetAndCountdown() {
        val result = present("2026-10-09T14:00:00Z", stale = true)
        assertEquals(TimeSnapshot.STALE, result.snapshot)
        assertEquals(TimeState.FUTURE, result.state)
        assertEquals(AbsoluteTime("Oct 9, 2026", "14:00", null), result.absolute)
        assertEquals(RemainingTime(1, 14), result.remaining)
        val unknown = TimePresentation.present(
            Field<Instant>(Knowledge.UNAVAILABLE, reason = Reason.MISSING), TimeKind.PERIODIC_RESET,
            null, null, utc, Locale.US, staleSnapshot = true,
        )
        assertEquals(TimeSnapshot.STALE, unknown.snapshot)
        assertEquals(TimeState.UNKNOWN_TARGET, unknown.state)
    }

    @Test fun rolloverOnlyMarksSnapshotStaleAndNeverInventsANextResetOrZeroUsage() {
        val observation = UsageNormalizer.normalize(
            UsageInput(primary = Input.Value(WindowInput(
                Input.Value(18000), Input.Value(83), Input.Value(Instant.parse("2026-10-08T01:00:00Z").epochSecond),
            ))), now, now,
        )
        val window = observation.fiveHour.candidates.single()
        val before = TimePresentation.presentReset(window.reset, observation.observedAt, now, utc, Locale.US)
        val crossed = TimePresentation.presentReset(
            window.reset, observation.observedAt, Instant.parse("2026-10-08T01:00:00Z"), utc, Locale.US,
        )
        val muchLater = TimePresentation.presentReset(
            window.reset, observation.observedAt, Instant.parse("2026-10-10T00:00:00Z"), utc, Locale.US,
        )
        assertEquals(TimeState.FUTURE, before.state)
        for (result in listOf(crossed, muchLater)) {
            assertEquals(TimeState.AWAITING_REFRESH, result.state)
            assertEquals(TimeSnapshot.STALE, result.snapshot)
            assertEquals(AbsoluteTime("Oct 8, 2026", "01:00", null), result.absolute)
            assertNull(result.remaining)
        }
        assertEquals(BigDecimal("83"), window.usedPercent.value)
        assertEquals(false, window.reset.due)
        assertSame(window, observation.fiveHour.candidates.single())
    }

    @Test fun wallClockJumpReevaluatesWithoutStateOrMonotonicTimeAssumptions() {
        val target = "2026-10-08T02:00:00Z"
        val forward = present(target, Instant.parse("2026-10-08T03:00:00Z"))
        val backward = present(target, Instant.parse("2026-10-07T23:00:00Z"))
        val recovered = present(target)
        assertEquals(TimeState.AWAITING_REFRESH, forward.state)
        assertEquals(TimeSnapshot.STALE, forward.snapshot)
        assertEquals(TimeState.FUTURE, backward.state)
        assertEquals(RemainingTime(0, 3), backward.remaining)
        assertEquals(TimeSnapshot.CLOCK_BEFORE_OBSERVATION, backward.snapshot)
        assertEquals(RemainingTime(0, 2), recovered.remaining)
        assertEquals(TimeSnapshot.OBSERVED, recovered.snapshot)
        for (result in listOf(forward, backward, recovered)) {
            assertEquals(AbsoluteTime("Oct 8, 2026", "02:00", null), result.absolute)
        }
    }

    @Test fun missingObservationDoesNotPreventKnownAbsoluteOrRelativeTime() {
        val result = present("2026-10-08T02:00:00Z", observed = null)
        assertEquals(TimeSnapshot.UNKNOWN, result.snapshot)
        assertEquals(TimeState.FUTURE, result.state)
        assertEquals(RemainingTime(0, 2), result.remaining)
    }

    @Test fun unknownClockNeverFallsBackToSystemTimeOrInfiniteLifetime() {
        val target = known("2026-10-09T14:20:00Z")
        val result = TimePresentation.presentWithClock(target, TimeKind.ENTITLEMENT_EXPIRY, now, null, utc, Locale.US)
        assertEquals(TimeState.UNKNOWN_CLOCK, result.state)
        assertEquals(TimeSnapshot.UNKNOWN, result.snapshot)
        assertEquals(AbsoluteTime("Oct 9, 2026", "14:00", null), result.absolute)
        assertNull(result.remaining)
        assertEquals(result, TimePresentation.present(target, TimeKind.ENTITLEMENT_EXPIRY, now, null, utc, Locale.US))
    }

    @Test fun unknownMalformedUnsupportedAndInconsistentFieldsDoNotChooseATarget() {
        val fields = listOf(
            Field<Instant>(Knowledge.UNAVAILABLE, reason = Reason.MISSING),
            Field<Instant>(Knowledge.UNAVAILABLE, reason = Reason.PROVIDER_NULL),
            Field<Instant>(Knowledge.UNAVAILABLE, reason = Reason.CLOCK_REQUIRED),
            Field<Instant>(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE),
            Field<Instant>(Knowledge.MALFORMED, reason = Reason.OUT_OF_RANGE),
            Field<Instant>(Knowledge.UNSUPPORTED, reason = Reason.UNSUPPORTED_FORMAT),
            Field<Instant>(Knowledge.KNOWN),
            Field(Knowledge.UNSUPPORTED, Instant.parse("2026-10-09T00:00:00Z"), Reason.UNSUPPORTED_FORMAT),
        )
        for (field in fields) {
            val result = TimePresentation.present(field, TimeKind.ENTITLEMENT_EXPIRY, now, now, utc, Locale.US)
            assertEquals(TimeState.UNKNOWN_TARGET, result.state)
            assertNull(result.absolute)
            assertNull(result.remaining)
            assertEquals(field.knowledge, result.targetKnowledge)
            assertEquals(field.reason, result.targetReason)
            assertEquals(TimeSnapshot.OBSERVED, result.snapshot)
        }
    }

    @Test fun conflictingResetInstantsNeverSelectTheAbsoluteOrRelativeWinner() {
        val reset = ResetTime(
            known("2026-10-08T01:00:00Z"), Field(Knowledge.KNOWN, 7200L),
            known("2026-10-08T02:00:00Z"), discrepant = true, due = null,
        )
        val result = TimePresentation.presentReset(reset, now, Instant.parse("2026-10-08T03:00:00Z"), utc, Locale.US)
        assertEquals(TimeState.DISCREPANT, result.state)
        assertEquals(TimeSnapshot.OBSERVED, result.snapshot)
        assertNull(result.absolute)
        assertNull(result.remaining)
        assertEquals(Instant.parse("2026-10-08T01:00:00Z"), reset.absolute.value)
        assertEquals(Instant.parse("2026-10-08T02:00:00Z"), reset.relativeDerived.value)
        val clockResult = TimePresentation.presentWithClock(
            reset.absolute, TimeKind.PERIODIC_RESET, null, null, utc, Locale.US,
            targetDiscrepant = true, staleSnapshot = true,
        )
        assertEquals(TimeState.DISCREPANT, clockResult.state)
        assertEquals(TimeSnapshot.STALE, clockResult.snapshot)
    }

    @Test fun a1RelativeDerivationIsReusedAndUnavailableClocksStayUnavailable() {
        val observation = UsageNormalizer.normalize(
            UsageInput(primary = Input.Value(WindowInput(resetAfterSeconds = Input.Value(7200)))), now, now,
        )
        val result = TimePresentation.presentReset(observation.slots[0].window.value!!.reset, now, now, utc, Locale.US)
        assertEquals(AbsoluteTime("Oct 8, 2026", "02:00", null), result.absolute)
        assertEquals(RemainingTime(0, 2), result.remaining)
        val unavailable = UsageNormalizer.normalize(
            UsageInput(primary = Input.Value(WindowInput(resetAfterSeconds = Input.Value(7200)))), null, now,
        )
        val unknown = TimePresentation.presentReset(unavailable.slots[0].window.value!!.reset, null, now, utc, Locale.US)
        assertEquals(TimeState.UNKNOWN_TARGET, unknown.state)
        assertEquals(Reason.CLOCK_REQUIRED, unknown.targetReason)
        assertNull(unknown.absolute)
        assertNull(unknown.remaining)
    }

    @Test fun resetAdapterPreservesBadAbsoluteReasonWhenThereIsNoValidDerivedInstant() {
        val missing = Field<Instant>(Knowledge.UNAVAILABLE, reason = Reason.MISSING)
        val reset = ResetTime(
            Field(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE),
            Field(Knowledge.UNAVAILABLE, reason = Reason.MISSING), missing, false, null,
        )
        val malformed = TimePresentation.presentReset(reset, now, now, utc, Locale.US, staleSnapshot = true)
        assertEquals(Knowledge.MALFORMED, malformed.targetKnowledge)
        assertEquals(Reason.WRONG_TYPE, malformed.targetReason)
        assertEquals(TimeState.UNKNOWN_TARGET, malformed.state)
        assertEquals(TimeSnapshot.STALE, malformed.snapshot)
        val incompleteKnown = reset.copy(absolute = Field(Knowledge.KNOWN))
        assertEquals(TimeState.UNKNOWN_TARGET, TimePresentation.presentReset(incompleteKnown, now, now, utc, Locale.US).state)
        val incompleteDerived = reset.copy(absolute = missing, relativeDerived = Field(Knowledge.KNOWN))
        assertEquals(TimeState.UNKNOWN_TARGET, TimePresentation.presentReset(incompleteDerived, now, now, utc, Locale.US).state)
    }

    @Test fun a1ExpiryProjectionIgnoresCachedExpiredFlagAndKeepsProviderInventoryUnchanged() {
        val inventory = BankedResetNormalizer.normalize(Input.Value(InventoryInput(
            Input.Value(1), Input.Value(listOf(Input.Value(ResetItemInput(
                status = Input.Value("available"), expiresAt = Input.Value("2026-10-08T01:00:00Z"),
            )))),
        )), observedAt = now, evaluatedAt = now)
        val item = inventory.items.single().value!!
        val result = TimePresentation.present(
            item.expiresAt, TimeKind.ENTITLEMENT_EXPIRY, inventory.observedAt,
            Instant.parse("2026-10-08T02:00:00Z"), utc, Locale.US,
        )
        assertEquals(TimeState.EXPIRED, result.state)
        assertEquals(TimeSnapshot.STALE, result.snapshot)
        assertEquals(false, item.locallyExpired)
        assertEquals("available", item.providerStatus.value)
        assertEquals(1L, inventory.reportedAvailableCount.value)
    }

    @Test fun unrepresentableLocalDatesAreUnknownNotCrashesOrMadeUpDates() {
        val result = TimePresentation.present(
            Field(Knowledge.KNOWN, Instant.MAX), TimeKind.PERIODIC_RESET, now, now, utc, Locale.US,
        )
        assertEquals(TimeState.UNKNOWN_TARGET, result.state)
        assertNull(result.absolute)
        assertNull(result.remaining)
    }

    @Test fun repeatedCallsWithFixedInputsAreDeterministicAndResourceReady() {
        val target = known("2026-10-09T14:59:59Z")
        val clock = CountingClock(now)
        val first = TimePresentation.presentWithClock(target, TimeKind.ENTITLEMENT_EXPIRY, now, clock, utc, Locale.US)
        assertEquals(1, clock.reads)
        val second = TimePresentation.presentWithClock(target, TimeKind.ENTITLEMENT_EXPIRY, now, clock, utc, Locale.US)
        assertEquals(2, clock.reads)
        assertEquals(first, second)
        assertEquals(TimeKind.ENTITLEMENT_EXPIRY, first.kind)
        assertEquals(RemainingTime(1, 14), first.remaining)
        assertEquals(1L, first.remaining!!.days)
        assertEquals(14, first.remaining.hours)
        assertEquals("Oct 9, 2026", first.absolute!!.date)
        assertEquals("14:00", first.absolute.hour)
        assertNull(first.absolute.utcOffset)
        assertEquals(R.string.time_absolute, first.absolute.formatResource)
        assertEquals(R.string.time_absolute_offset, AbsoluteTime("Nov 1, 2026", "01:00", "-05:00").formatResource)
        assertEquals(R.plurals.time_days, TimePresentation.daysPluralResource)
        assertEquals(R.plurals.time_hours, TimePresentation.hoursPluralResource)
        assertEquals(R.string.time_days_hours, TimePresentation.relativeFormatResource)
        assertEquals(R.string.time_remaining, TimeState.FUTURE.labelResource)
        assertEquals(R.string.time_less_than_hour, TimeState.LESS_THAN_HOUR.labelResource)
        assertEquals(R.string.time_awaiting_refresh, TimeState.AWAITING_REFRESH.labelResource)
        assertEquals(R.string.time_expired, TimeState.EXPIRED.labelResource)
        assertEquals(R.string.time_unknown_target, TimeState.UNKNOWN_TARGET.labelResource)
        assertEquals(R.string.time_unknown_clock, TimeState.UNKNOWN_CLOCK.labelResource)
        assertEquals(R.string.time_discrepant, TimeState.DISCREPANT.labelResource)
        assertEquals(R.string.time_snapshot_observed, TimeSnapshot.OBSERVED.labelResource)
        assertEquals(R.string.time_snapshot_unknown, TimeSnapshot.UNKNOWN.labelResource)
        assertEquals(R.string.time_snapshot_stale, TimeSnapshot.STALE.labelResource)
        assertEquals(R.string.time_clock_before_observation, TimeSnapshot.CLOCK_BEFORE_OBSERVATION.labelResource)
    }

    private class CountingClock(private val fixed: Instant) : Clock() {
        var reads = 0
        override fun instant(): Instant { reads++; return fixed }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(fixed, zone)
    }
}
