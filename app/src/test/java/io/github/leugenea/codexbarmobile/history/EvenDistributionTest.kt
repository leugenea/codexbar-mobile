package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.history.SyntheticHistory.append
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.at
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.epoch
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.five
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.partition
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.usage
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.weekly
import io.github.leugenea.codexbarmobile.history.SyntheticHistory.window
import io.github.leugenea.codexbarmobile.usage.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Original synthetic facts and literal numerical oracles; no live/provider-account data. */
class EvenDistributionTest {
    @Test fun fiveHourAnalyticalEndpointsAndMidpointUseAnExplicitFixedClock() {
        val reference = series(five(append()))
        assertEquals(Instant.ofEpochSecond(1_799_985_600), reference.nominalStartAt)
        assertEquals(BaselineEvaluation.Available(Instant.ofEpochSecond(1_799_985_600), BigDecimal.ZERO), reference.start)
        assertEquals(BaselineEvaluation.Available(Instant.ofEpochSecond(1_800_003_600), BigDecimal("100")), reference.end)
        assertSame(reference.start, reference.evaluate(Clock.fixed(reference.nominalStartAt, ZoneOffset.UTC)))
        assertSame(reference.end, reference.evaluate(Clock.fixed(reference.window.resetAt, ZoneOffset.UTC)))
        assertPercent("50", reference.evaluate(Clock.fixed(Instant.ofEpochSecond(1_799_994_600), ZoneOffset.UTC)))
    }

    @Test fun weeklyReferenceUses604800ElapsedSecondsAndLiteralFractionalValue() {
        val source = weekly(append(usage = usage(window(duration = Input.Value(604800L), absolute = Input.Value(1_800_604_800L)))))
        val reference = series(source)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), reference.nominalStartAt)
        assertPercent("0", reference.evaluate(Clock.fixed(at, ZoneOffset.UTC)))
        assertPercent("100", reference.evaluate(Clock.fixed(Instant.ofEpochSecond(1_800_604_800), ZoneOffset.UTC)))
        assertPercent("50.00004133597883597883597883597884", reference.evaluate(at.plusSeconds(302400).plusNanos(250_000_000)))
    }

    @Test fun fractionalSecondsAndSingleNanosecondAreNotTruncated() {
        val reference = series(five(append()))
        assertPercent("0.2506944444444444444444444444444444", reference.evaluate(reference.nominalStartAt.plusSeconds(45).plusNanos(125_000_000)))
        assertPercent("5.555555555555555555555555555555556E-12", reference.evaluate(reference.nominalStartAt.plusNanos(1)))
    }

    @Test fun nanoResetKeepsExactNominalStartAndElapsedMath() {
        val original = usage().fiveHour.candidates.single()
        val reset = Instant.ofEpochSecond(1_800_003_600, 500_000_001)
        val nanoWindow = original.copy(reset = original.reset.copy(absolute = Field(Knowledge.KNOWN, reset)))
        val source = five(append(usage = usage().copy(fiveHour = WindowSelection(SelectionState.KNOWN, listOf(nanoWindow)))))
        val reference = series(source)
        assertEquals(Instant.ofEpochSecond(1_799_985_600, 500_000_001), reference.nominalStartAt)
        assertEquals(reset, reference.end.at)
        assertPercent("25", reference.evaluate(Instant.ofEpochSecond(1_799_990_100, 500_000_001)))
    }

    @Test fun zeroHundredAndFractionalUsageHaveSignedPercentagePointDeltas() {
        val midpoint = Instant.ofEpochSecond(1_799_994_600)
        val cases = listOf("0" to "-50", "100" to "50", "12.37500000000000000001" to "-37.62499999999999999999")
        for ((used, delta) in cases) {
            val source = five(append(usage = usage(window(Input.Value(BigDecimal(used))), observedAt = midpoint)))
            val comparison = compared(source)
            assertEquals(BigDecimal("50"), comparison.baselinePercent)
            assertEquals(BigDecimal(delta), comparison.deltaPercentagePoints)
            assertSame(source.point, comparison.point)
            assertEquals(BigDecimal(used), comparison.point.usedPercent)
        }
    }

    @Test fun decimal128DivisionDoesNotRoundExactProviderUsageOrDeltaSubtraction() {
        val used = BigDecimal("12.375000000000000000000000000000000001")
        val source = five(append(usage = usage(window(Input.Value(used)), observedAt = Instant.ofEpochSecond(1_799_985_601))))
        val comparison = compared(source)
        assertEquals(BigDecimal("0.005555555555555555555555555555555556"), comparison.baselinePercent)
        assertEquals(BigDecimal("12.369444444444444444444444444444444445"), comparison.deltaPercentagePoints)
        assertSame(used, comparison.point.usedPercent)
        assertEquals(36, comparison.point.usedPercent.scale())
    }

    @Test fun realEndpointMeasurementsRemainMeasurementsWithTheirOwnUsage() {
        val cases = listOf(Instant.ofEpochSecond(1_799_985_600) to "12.375", Instant.ofEpochSecond(1_800_003_600) to "-87.625")
        for ((observed, delta) in cases) {
            val source = five(append(usage = usage(observedAt = observed)))
            val comparison = compared(source)
            assertEquals(BigDecimal(delta), comparison.deltaPercentagePoints)
            assertEquals(observed, comparison.point.observedAt)
            assertEquals(BigDecimal("12.375"), comparison.point.usedPercent)
            assertEquals(ObservationId(1), comparison.point.observation)
        }
    }

    @Test fun analyticalBeforeAfterAndNullAreUnavailableWithoutClamping() {
        val reference = series(five(append()))
        assertEvaluationUnavailable(BaselineEligibility.BEFORE_NOMINAL_START, reference.evaluate(reference.nominalStartAt.minusNanos(1)))
        assertEvaluationUnavailable(BaselineEligibility.AFTER_RESET, reference.evaluate(reference.window.resetAt.plusNanos(1)))
        assertEvaluationUnavailable(BaselineEligibility.UNKNOWN_OBSERVATION_TIME, reference.evaluate(null as Instant?))
        assertEvaluationUnavailable(BaselineEligibility.AFTER_RESET, reference.evaluate(Clock.fixed(Instant.MAX, ZoneOffset.UTC)))
    }

    @Test fun measuredBeforeAndAfterNominalIntervalStayRealButCannotBeCompared() {
        for ((observed, reason) in listOf(Instant.ofEpochSecond(1_799_985_600).minusNanos(1) to BaselineEligibility.BEFORE_NOMINAL_START,
            Instant.ofEpochSecond(1_800_003_600).plusNanos(1) to BaselineEligibility.AFTER_RESET)) {
            val source = five(append(usage = usage(observedAt = observed)))
            assertIneligible(source, reason)
            assertEquals(observed, source.point!!.observedAt)
            assertEquals(BigDecimal("12.375"), source.point.usedPercent)
        }
    }

    @Test fun lateFirstMeasurementAndAnalyticalTicksNeverAddOrMoveMeasuredPoints() {
        val admitted = append()
        val source = five(admitted)
        val before = compared(source)
        assertEquals(at, before.point.observedAt)
        assertEquals(BigDecimal("80"), before.baselinePercent)
        assertEquals(BigDecimal("-67.625"), before.deltaPercentagePoints)
        assertPercent("90", before.reference.evaluate(Clock.fixed(at.plusSeconds(1800), ZoneOffset.UTC)))
        assertSame(before.reference.start, before.reference.evaluate(before.reference.nominalStartAt))
        val after = compared(source)
        assertSame(before.point, after.point)
        assertEquals(before.baselinePercent, after.baselinePercent)
        assertEquals(before.deltaPercentagePoints, after.deltaPercentagePoints)
        assertEquals(1, admitted.entry.windows.count { it.point != null })
        assertEquals(1L, admitted.cursor.lastOrdinal)
    }

    @Test fun emptyHistoryStaysEmptyDespiteAnIndependentlyEvaluatedReference() {
        val snapshot = HistoryReadSnapshot(HistoryReadQuery(partition, 8), emptyList(), null, false)
        val reference = series(five(append()))
        assertPercent("80", reference.evaluate(Clock.fixed(at, ZoneOffset.UTC)))
        assertTrue(snapshot.entries.isEmpty())
        assertEquals(HistoryContent.EMPTY, snapshot.content)
        assertNull(snapshot.nextAfter)
    }

    @Test fun absentNullMalformedAndUnknownPercentageStayUnavailableWithOriginalKnowledge() {
        for (input in listOf(Input.Missing, Input.Null, Input.Invalid, Input.Value("12.5"))) {
            val source = five(append(usage = usage(window(percent = input))))
            assertIneligible(source, BaselineEligibility.UNKNOWN_PERCENT)
            assertNull(source.point)
            assertNotNull(source.percent!!.reason)
        }
        assertIneligible(five(append(usage = usage(observedAt = null))), BaselineEligibility.UNKNOWN_OBSERVATION_TIME)
    }

    @Test fun unknownMalformedUnsupportedAndRelativeOnlyResetCannotCreateAReference() {
        val inputs = listOf(window(absolute = Input.Missing), window(absolute = Input.Null), window(absolute = Input.Invalid),
            window(absolute = Input.Value("tomorrow")), window(absolute = Input.Missing, relative = Input.Value(3600L)),
            window(relative = Input.Value(3599L)))
        for (input in inputs) {
            val source = five(append(usage = usage(input)))
            assertIneligible(source, BaselineEligibility.UNKEYED_RESET)
            assertNotEquals(ResetProvenance.ABSOLUTE, source.reset.provenance)
            assertEquals(BigDecimal("12.375"), source.point!!.usedPercent)
        }
        val raw = usage().fiveHour.candidates.single()
        val unsupported = raw.copy(reset = raw.reset.copy(absolute = Field(Knowledge.UNSUPPORTED, reason = Reason.UNSUPPORTED_FORMAT)))
        val source = five(append(usage = usage().copy(fiveHour = WindowSelection(SelectionState.KNOWN, listOf(unsupported)))))
        assertEquals(ResetProvenance.UNSUPPORTED, source.reset.provenance)
        assertIneligible(source, BaselineEligibility.UNKEYED_RESET)
    }

    @Test fun malformedRelativeSiblingDoesNotEraseKnownAbsoluteReference() {
        val source = five(append(usage = usage(window(relative = Input.Invalid))))
        assertEquals(SelectionState.MALFORMED, source.selection)
        assertEquals(Reason.WRONG_TYPE, source.reset.facts!!.relativeSeconds.reason)
        assertEquals(BigDecimal("80"), compared(source).baselinePercent)
    }

    @Test fun absentAmbiguousAndUnsupportedDurationsNeverChooseAPeriodicBaseline() {
        for (duration in listOf(Input.Missing, Input.Null, Input.Invalid, Input.Value(3600L), Input.Value(Long.MAX_VALUE))) {
            assertIneligible(five(append(usage = usage(window(duration = duration)))), BaselineEligibility.WINDOW_UNAVAILABLE)
        }
        assertIneligible(five(append(usage = usage(primary = Input.Missing))), BaselineEligibility.WINDOW_UNAVAILABLE)
        assertIneligible(five(append(usage = usage(window(), window()))), BaselineEligibility.AMBIGUOUS_WINDOW)
    }

    @Test fun unknownMonotonicAndBackwardsOrSkewedClocksKeepM4a1Reasons() {
        assertIneligible(five(append(monotonic = null)), BaselineEligibility.CLOCK_UNVERIFIED)
        val first = append()
        val wallBackwards = append(first.cursor, usage(observedAt = at.minusSeconds(1)), 2000)
        val monotonicBackwards = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 0)
        val skewed = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 55_999)
        for (result in listOf(wallBackwards, monotonicBackwards, skewed)) {
            assertIneligible(five(result), BaselineEligibility.CLOCK_DISCONTINUITY)
        }
    }

    @Test fun correctionAndLaterSameCandidateCannotInventANewNominalStart() {
        val first = append()
        val corrected = append(first.cursor, usage(window(Input.Value(BigDecimal("9"))), observedAt = at.plusSeconds(60)), 61_000)
        val later = append(corrected.cursor, usage(window(Input.Value(BigDecimal("10"))), observedAt = at.plusSeconds(120)), 121_000)
        assertIneligible(five(corrected), BaselineEligibility.UNCERTAIN_CORRECTION)
        assertIneligible(five(later), BaselineEligibility.UNCERTAIN_CORRECTION)
        assertEquals(NominalStartConfidence.UNCERTAIN_CORRECTION, five(later).point!!.nominalStart)
        assertEquals(BigDecimal("80"), compared(five(first)).baselinePercent)
        val changed = append(later.cursor, usage(window(absolute = Input.Value(1_800_003_780L)), observedAt = at.plusSeconds(180)), 181_000)
        assertEquals(BigDecimal("80"), compared(five(changed)).baselinePercent)
        assertEquals(ResetCause.UNKNOWN, five(changed).point!!.segment.resetCause)
    }

    @Test fun fiveHourAndWeeklyReferencesAndDeltasAreIndependentInOneObservation() {
        val both = append(usage = usage(secondary = window(Input.Value(BigDecimal("62.500")), Input.Value(604800L), Input.Value(1_800_302_400L))))
        val fiveComparison = compared(five(both))
        val weeklyComparison = compared(weekly(both))
        assertEquals(BigDecimal("80"), fiveComparison.baselinePercent)
        assertEquals(BigDecimal("50"), weeklyComparison.baselinePercent)
        assertEquals(BigDecimal("12.500"), weeklyComparison.deltaPercentagePoints)
        assertEquals(WindowKind.FIVE_HOUR, fiveComparison.reference.window.kind)
        assertEquals(WindowKind.WEEKLY, weeklyComparison.reference.window.kind)
        assertNotEquals(fiveComparison.reference.segment, weeklyComparison.reference.segment)
    }

    @Test fun sameKindResetChangesAndGapSegmentsPreserveTheirExactIdentities() {
        val first = append()
        val changed = append(first.cursor, usage(window(absolute = Input.Value(1_800_003_660L)), observedAt = at.plusSeconds(60)), 61_000)
        val one = compared(five(first))
        val two = compared(five(changed))
        assertNotEquals(one.reference.window, two.reference.window)
        assertNotEquals(one.reference.segment, two.reference.segment)
        assertEquals(BigDecimal("80"), two.baselinePercent)
        assertEquals(setOf(BreakReason.WINDOW_CHANGED), two.point.segment.breaks)
        val gap = WindowHistory.append(first.cursor, HistoryAdmission(partition, ObservationId(2), HistoryClock(epoch, 30_000), HistoryEvent.Gap(HistoryGap.BACKGROUND))) as HistoryReduction.Applied
        val resumed = append(gap.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        val three = compared(five(resumed))
        assertEquals(one.reference.window, three.reference.window)
        assertNotEquals(one.reference.segment, three.reference.segment)
        assertEquals(setOf(BreakReason.BACKGROUND), three.point.segment.breaks)
        assertEquals(BigDecimal("80.33333333333333333333333333333333"), three.baselinePercent)
    }

    @Test fun retainedPageDoesNotReplaceSegmentOriginWithItsFirstVisiblePoint() {
        val first = append()
        val next = append(first.cursor, usage(observedAt = at.plusSeconds(60)), 61_000)
        val page = HistoryReadSnapshot(HistoryReadQuery(partition, 1, ObservationId(1)), listOf(next.entry), ObservationId(2), false)
        val visible = page.entries.single().windows.single { it.kind == WindowKind.FIVE_HOUR }
        val reference = series(visible)
        assertEquals(ObservationId(1), reference.segment.first)
        assertEquals(ObservationId(2), visible.point!!.observation)
        assertEquals(at.plusSeconds(60), visible.point.observedAt)
    }

    @Test fun bankedSummaryInventoryAndExpiryChangesNeverEnterTheReferenceMath() {
        val original = usage()
        val variants = listOf(original.copy(bankedAvailableCount = Field(Knowledge.KNOWN, 0)),
            original.copy(bankedAvailableCount = Field(Knowledge.KNOWN, 100)),
            original.copy(bankedAvailableCount = Field(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE)))
        for (variant in variants) {
            val comparison = compared(five(append(usage = variant)))
            assertEquals(BigDecimal("80"), comparison.baselinePercent)
            assertEquals(BigDecimal("-67.625"), comparison.deltaPercentagePoints)
            assertEquals(series(five(append())).window, comparison.reference.window)
        }
        // Inventory/expiry is a separate endpoint and is intentionally absent from the math API.
        val inventory = Input.Value(InventoryInput(Input.Value(1L), Input.Value(listOf(Input.Value(
            ResetItemInput(Input.Value("synthetic"), Input.Value("codex_rate_limits"), Input.Value("available"),
                expiresAt = Input.Value("2026-01-01T00:00:00Z")))))))
        val expired = BankedResetNormalizer.normalize(inventory, original, at, at)
        assertEquals(true, expired.items.single().value!!.locallyExpired)
        assertEquals(BigDecimal("-67.625"), compared(five(append(usage = original))).deltaPercentagePoints)
    }

    @Test fun timezoneAndDstTransitionsDoNotChangeElapsedSiSecondResults() {
        val reference = series(five(append()))
        val fixed = Instant.parse("2026-11-01T06:30:00Z")
        val reset = fixed.plusSeconds(9000)
        val dstReference = series(rekey(five(append()), WindowIdentity(partition, WindowKind.FIVE_HOUR, 18000, reset)))
        for (zone in listOf("UTC", "America/New_York", "Australia/Lord_Howe", "Asia/Kathmandu")) {
            assertPercent("50", dstReference.evaluate(Clock.fixed(fixed, ZoneId.of(zone))))
            assertPercent("80", reference.evaluate(Clock.fixed(at, ZoneId.of(zone))))
        }
        assertPercent("70", dstReference.evaluate(Instant.parse("2026-11-01T07:30:00Z")))
        assertPercent("30", dstReference.evaluate(Instant.parse("2026-11-01T05:30:00Z")))
    }

    @Test fun nominalStartOverflowAndExtremeEvaluationInstantsAreTypedUnavailable() {
        val source = five(append())
        val overflow = rekey(source, source.point!!.segment.window!!.copy(resetAt = Instant.MIN))
        assertIneligible(overflow, BaselineEligibility.TIME_RANGE_OVERFLOW)
        val maximum = rekey(source, source.point.segment.window!!.copy(resetAt = Instant.MAX))
        val reference = series(maximum)
        assertSame(reference.end, reference.evaluate(Instant.MAX))
        assertEvaluationUnavailable(BaselineEligibility.BEFORE_NOMINAL_START, reference.evaluate(Instant.MIN))
        assertComparisonReason(overflow, DistributionUnavailable.Ineligible(BaselineEligibility.TIME_RANGE_OVERFLOW))
    }

    @Test fun extremeScaleZerosAreSafeWithoutMutatingTheMeasurement() {
        for (scale in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            val zero = BigDecimal(BigInteger.ZERO, scale)
            val source = measuredPercent(five(append()), zero)
            val comparison = compared(source)
            assertEquals(BigDecimal("-80"), comparison.deltaPercentagePoints)
            assertSame(zero, comparison.point.usedPercent)
            assertEquals(scale, comparison.point.usedPercent.scale())
        }
    }

    @Test fun nonzeroExtremeScaleAndPrecisionReturnCapacityNotRoundedOrInventedDelta() {
        val source = five(append())
        for (decimal in listOf(BigDecimal(BigInteger.ONE, Int.MAX_VALUE), BigDecimal("1E-1025"),
            BigDecimal("0." + "1".repeat(1025)), BigDecimal("1." + "1".repeat(1024)))) {
            val oversized = measuredPercent(source, decimal)
            assertComparisonReason(oversized, DistributionUnavailable.InvalidFact.DECIMAL_CAPACITY)
            assertSame(decimal, oversized.point!!.usedPercent)
            assertEquals(BigDecimal("80"), (series(oversized).evaluate(at) as BaselineEvaluation.Available).percent)
        }
        val extremeLarge = measuredPercent(source, BigDecimal(BigInteger.ONE, Int.MIN_VALUE))
        assertComparisonReason(extremeLarge, DistributionUnavailable.InvalidFact.PERCENT)
    }

    @Test fun arithmeticCapacityBoundaryPreservesExact1024ScaleDecimal() {
        val used = BigDecimal("1E-1024")
        val source = measuredPercent(five(append()), used)
        val comparison = compared(source)
        assertEquals(BigDecimal("-79." + "9".repeat(1024)), comparison.deltaPercentagePoints)
        assertSame(used, comparison.point.usedPercent)
    }

    @Test fun malformedHandcraftedPercentDoesNotThrowOrMasqueradeAsKnownUsage() {
        val source = five(append())
        for (used in listOf(BigDecimal("-0.01"), BigDecimal("100.01"))) {
            assertComparisonReason(measuredPercent(source, used), DistributionUnavailable.InvalidFact.PERCENT)
        }
        for (percent in listOf<Field<BigDecimal>?>(null, Field(Knowledge.KNOWN), Field(Knowledge.MALFORMED), Field(Knowledge.KNOWN, BigDecimal("1")))) {
            assertComparisonReason(source.copy(percent = percent), DistributionUnavailable.InvalidFact.PERCENT)
        }
    }

    @Test fun authoritativeSourceAndPointEligibilityCannotBeOverriddenByAnalyticalEndpoints() {
        val source = five(append())
        for (eligibility in BaselineEligibility.entries.filter { it != BaselineEligibility.ELIGIBLE }) {
            assertIneligible(source.copy(baseline = eligibility), eligibility)
            assertIneligible(source.copy(point = source.point!!.copy(baseline = eligibility)), eligibility)
        }
        assertIneligible(source.copy(point = null), BaselineEligibility.UNKNOWN_OBSERVATION_TIME)
        assertIneligible(source.copy(point = source.point!!.copy(nominalStart = NominalStartConfidence.UNCERTAIN_CORRECTION)), BaselineEligibility.UNCERTAIN_CORRECTION)
        assertIneligible(source.copy(point = source.point.copy(nominalStart = NominalStartConfidence.UNAVAILABLE)), BaselineEligibility.UNKEYED_RESET)
        val unkeyed = source.point.copy(segment = HistorySegment(source.point.segment.id, null, emptySet()))
        assertIneligible(source.copy(point = unkeyed), BaselineEligibility.UNKEYED_RESET)
    }

    @Test fun handcraftedUnsupportedOrDiscrepantIdentityIsRejectedBeforeDivision() {
        val source = five(append())
        val identity = source.point!!.segment.window!!
        for (duration in listOf(0L, -1L, Long.MAX_VALUE, 3600L)) {
            assertReferenceReason(rekey(source, identity.copy(durationSeconds = duration)), DistributionUnavailable.InvalidFact.DURATION)
        }
        assertReferenceReason(rekey(source, identity.copy(kind = WindowKind.UNKNOWN)), DistributionUnavailable.InvalidFact.DURATION)
        for (duration in listOf<Field<Long>?>(null, Field(Knowledge.KNOWN), Field(Knowledge.UNSUPPORTED), Field(Knowledge.KNOWN, 1L))) {
            assertReferenceReason(source.copy(duration = duration), DistributionUnavailable.InvalidFact.DURATION)
        }
        val badSegments = listOf(source.point.segment.id.copy(partition = HistoryPartition(UUID(0, 99))),
            source.point.segment.id.copy(kind = WindowKind.WEEKLY))
        for (id in badSegments) {
            val point = source.point.copy(segment = HistorySegment(id, identity, emptySet()))
            assertReferenceReason(source.copy(point = point), DistributionUnavailable.InvalidFact.IDENTITY)
        }
        val weeklyKind = source.copy(kind = WindowKind.WEEKLY)
        assertReferenceReason(weeklyKind, DistributionUnavailable.InvalidFact.IDENTITY)
    }

    @Test fun handcraftedResetProvenanceKnowledgeAndMismatchCannotPromoteAnIdentity() {
        val source = five(append())
        val reset = source.reset.facts!!
        for (provenance in ResetProvenance.entries.filter { it != ResetProvenance.ABSOLUTE }) {
            assertReferenceReason(source.copy(reset = HistoryReset(provenance, reset)), DistributionUnavailable.InvalidFact.RESET)
        }
        assertReferenceReason(source.copy(reset = HistoryReset(ResetProvenance.ABSOLUTE, null)), DistributionUnavailable.InvalidFact.RESET)
        for (facts in listOf(reset.copy(discrepant = true), reset.copy(absolute = Field(Knowledge.UNAVAILABLE)),
            reset.copy(absolute = Field(Knowledge.KNOWN)), reset.copy(absolute = Field(Knowledge.KNOWN, at)))) {
            assertReferenceReason(source.copy(reset = HistoryReset(ResetProvenance.ABSOLUTE, facts)), DistributionUnavailable.InvalidFact.RESET)
        }
    }

    @Test fun handcraftedEligibleButOutOfIntervalPointIsNotSilentlyClampedByComparison() {
        val source = five(append())
        val point = source.point!!
        val before = point.copy(observedAt = Instant.ofEpochSecond(1_799_985_599))
        assertComparisonReason(source.copy(point = before), DistributionUnavailable.Ineligible(BaselineEligibility.BEFORE_NOMINAL_START))
        val after = point.copy(observedAt = Instant.ofEpochSecond(1_800_003_601))
        assertComparisonReason(source.copy(point = after), DistributionUnavailable.Ineligible(BaselineEligibility.AFTER_RESET))
    }

    private fun series(source: HistoryWindow): EvenDistributionReference =
        (EvenDistribution.reference(source) as DistributionReference.Available).series

    private fun compared(source: HistoryWindow): UsedComparison.Available =
        EvenDistribution.compare(source) as UsedComparison.Available

    private fun assertPercent(expected: String, result: BaselineEvaluation) {
        val actual = result as BaselineEvaluation.Available
        assertEquals(0, BigDecimal(expected).compareTo(actual.percent))
    }

    private fun assertEvaluationUnavailable(reason: BaselineEligibility, result: BaselineEvaluation) {
        assertEquals(BaselineEvaluation.Unavailable(DistributionUnavailable.Ineligible(reason)), result)
    }

    private fun assertIneligible(source: HistoryWindow, reason: BaselineEligibility) {
        val unavailable = DistributionUnavailable.Ineligible(reason)
        assertReferenceReason(source, unavailable)
        assertComparisonReason(source, unavailable)
    }

    private fun assertReferenceReason(source: HistoryWindow, reason: DistributionUnavailable) {
        assertEquals(DistributionReference.Unavailable(reason), EvenDistribution.reference(source))
    }

    private fun assertComparisonReason(source: HistoryWindow, reason: DistributionUnavailable) {
        val result = EvenDistribution.compare(source) as UsedComparison.Unavailable
        assertSame(source, result.source)
        assertEquals(reason, result.reason)
    }

    private fun measuredPercent(source: HistoryWindow, percent: BigDecimal): HistoryWindow = source.copy(
        percent = Field(Knowledge.KNOWN, percent), point = source.point!!.copy(usedPercent = percent))

    /** Deliberately handcrafted typed-boundary fixture, not a fabricated persisted observation. */
    private fun rekey(source: HistoryWindow, identity: WindowIdentity): HistoryWindow {
        val point = source.point!!
        val facts = source.reset.facts!!.copy(absolute = Field(Knowledge.KNOWN, identity.resetAt))
        return source.copy(duration = Field(Knowledge.KNOWN, identity.durationSeconds),
            reset = HistoryReset(ResetProvenance.ABSOLUTE, facts),
            point = point.copy(segment = HistorySegment(point.segment.id, identity, point.segment.breaks)))
    }
}
