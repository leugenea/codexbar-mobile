package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.Knowledge
import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

/** Retains M4a-1 eligibility verbatim; arithmetic/input capacity never becomes an invented value. */
sealed interface DistributionUnavailable {
    data class Ineligible(val eligibility: BaselineEligibility) : DistributionUnavailable
    enum class InvalidFact : DistributionUnavailable { DURATION, RESET, IDENTITY, PERCENT, DECIMAL_CAPACITY }
}

sealed interface DistributionReference {
    data class Available(val series: EvenDistributionReference) : DistributionReference
    data class Unavailable(val reason: DistributionUnavailable) : DistributionReference
}

sealed interface BaselineEvaluation {
    /** Analytical percent at a supplied instant, NOT a HistoryPoint or observed usage. */
    data class Available(val at: Instant, val percent: BigDecimal) : BaselineEvaluation
    data class Unavailable(val reason: DistributionUnavailable) : BaselineEvaluation
}

sealed interface UsedComparison {
    data class Available(
        val point: HistoryPoint,
        val reference: EvenDistributionReference,
        val baselinePercent: BigDecimal,
        /** Signed percentage points: exact provider used percent minus the analytical percent. */
        val deltaPercentagePoints: BigDecimal,
    ) : UsedComparison
    /** The source retains field knowledge/provenance, including a real but ineligible point. */
    data class Unavailable(val source: HistoryWindow, val reason: DistributionUnavailable) : UsedComparison
}

/**
 * An independent graph-consumer descriptor for ONE canonical window and segment.
 * Endpoints are analytical only. No measured series, timer, interpolation or forward fill.
 * Explicit Instant/Clock arguments are analytical evaluation requests, not clock observations;
 * source observation trust comes exclusively from M4a-1 eligibility.
 */
class EvenDistributionReference internal constructor(
    val window: WindowIdentity,
    val segment: SegmentIdentity,
    val nominalStartAt: Instant,
) {
    val start = BaselineEvaluation.Available(nominalStartAt, BigDecimal.ZERO)
    val end = BaselineEvaluation.Available(window.resetAt, BigDecimal("100"))

    fun evaluate(clock: Clock): BaselineEvaluation = evaluate(clock.instant())

    fun evaluate(at: Instant?): BaselineEvaluation {
        if (at == null) return unavailable(BaselineEligibility.UNKNOWN_OBSERVATION_TIME)
        if (at < nominalStartAt) return unavailable(BaselineEligibility.BEFORE_NOMINAL_START)
        if (at > window.resetAt) return unavailable(BaselineEligibility.AFTER_RESET)
        if (at == nominalStartAt) return start
        if (at == window.resetAt) return end
        val elapsed = Duration.between(nominalStartAt, at)
        val wholeSeconds = BigDecimal.valueOf(elapsed.seconds)
        val seconds = if (elapsed.nano == 0) wholeSeconds
            else wholeSeconds.add(BigDecimal.valueOf(elapsed.nano.toLong(), 9))
        // Multiply first, divide once with DECIMAL128 (34 digits, HALF_EVEN). Never round provider usage.
        val percent = seconds.multiply(BigDecimal("100"))
            .divide(BigDecimal.valueOf(window.durationSeconds), MathContext.DECIMAL128)
        return BaselineEvaluation.Available(at, percent)
    }

    private fun unavailable(reason: BaselineEligibility): BaselineEvaluation.Unavailable =
        BaselineEvaluation.Unavailable(DistributionUnavailable.Ineligible(reason))
}

/** Pure Q5 math over admitted M4a-1 facts; never accepts banked inventory or UI evaluation time. */
object EvenDistribution {
    // Work bound for exact subtraction, NOT a storage/provider rounding rule. Zero needs no expansion.
    private const val MAX_ARITHMETIC_DIGITS = 1024

    fun reference(source: HistoryWindow): DistributionReference {
        if (source.baseline != BaselineEligibility.ELIGIBLE) return ineligible(source.baseline)
        val point = source.point ?: return ineligible(BaselineEligibility.UNKNOWN_OBSERVATION_TIME)
        if (point.baseline != BaselineEligibility.ELIGIBLE) return ineligible(point.baseline)
        if (point.nominalStart == NominalStartConfidence.UNCERTAIN_CORRECTION) {
            return ineligible(BaselineEligibility.UNCERTAIN_CORRECTION)
        }
        if (point.nominalStart == NominalStartConfidence.UNAVAILABLE) return ineligible(BaselineEligibility.UNKEYED_RESET)
        val identity = point.segment.window ?: return ineligible(BaselineEligibility.UNKEYED_RESET)
        val invalid = invalidIdentity(source, identity, point.segment.id)
        if (invalid != null) return DistributionReference.Unavailable(invalid)
        return try {
            val start = identity.resetAt.minusSeconds(identity.durationSeconds)
            DistributionReference.Available(EvenDistributionReference(identity, point.segment.id, start))
        } catch (_: DateTimeException) {
            ineligible(BaselineEligibility.TIME_RANGE_OVERFLOW)
        }
    }

    /** Always evaluates the actual observedAt, never a tick/now supplied by a graph. */
    fun compare(source: HistoryWindow): UsedComparison {
        val reference = reference(source)
        if (reference is DistributionReference.Unavailable) return UsedComparison.Unavailable(source, reference.reason)
        val series = (reference as DistributionReference.Available).series
        val point = source.point!!
        val evaluation = series.evaluate(point.observedAt)
        if (evaluation is BaselineEvaluation.Unavailable) return UsedComparison.Unavailable(source, evaluation.reason)
        val invalid = invalidPercent(source, point.usedPercent)
        if (invalid != null) return UsedComparison.Unavailable(source, invalid)
        val baseline = (evaluation as BaselineEvaluation.Available).percent
        // Canonicalize only the arithmetic operand for extreme-scale zero, never the immutable measurement.
        val used = if (point.usedPercent.signum() == 0) BigDecimal.ZERO else point.usedPercent
        return try {
            UsedComparison.Available(point, series, baseline, used.subtract(baseline))
        } catch (_: ArithmeticException) {
            UsedComparison.Unavailable(source, DistributionUnavailable.InvalidFact.DECIMAL_CAPACITY)
        }
    }

    private fun ineligible(reason: BaselineEligibility): DistributionReference.Unavailable =
        DistributionReference.Unavailable(DistributionUnavailable.Ineligible(reason))

    private fun invalidIdentity(
        source: HistoryWindow, identity: WindowIdentity, segment: SegmentIdentity,
    ): DistributionUnavailable.InvalidFact? {
        if (!supportedDuration(source, identity)) return DistributionUnavailable.InvalidFact.DURATION
        if (source.kind != identity.kind || segment.kind != identity.kind || segment.partition != identity.partition) {
            return DistributionUnavailable.InvalidFact.IDENTITY
        }
        val reset = source.reset
        if (reset.provenance != ResetProvenance.ABSOLUTE) return DistributionUnavailable.InvalidFact.RESET
        val facts = reset.facts ?: return DistributionUnavailable.InvalidFact.RESET
        if (facts.discrepant || facts.absolute.knowledge != Knowledge.KNOWN || facts.absolute.value != identity.resetAt) {
            return DistributionUnavailable.InvalidFact.RESET
        }
        return null
    }

    private fun supportedDuration(source: HistoryWindow, identity: WindowIdentity): Boolean {
        val expected = when (identity.kind) {
            WindowKind.FIVE_HOUR -> 18000L
            WindowKind.WEEKLY -> 604800L
            else -> return false
        }
        return identity.durationSeconds == expected && source.duration?.knowledge == Knowledge.KNOWN &&
            source.duration.value == expected
    }

    private fun invalidPercent(source: HistoryWindow, used: BigDecimal): DistributionUnavailable.InvalidFact? {
        if (source.percent?.knowledge != Knowledge.KNOWN || source.percent.value != used) {
            return DistributionUnavailable.InvalidFact.PERCENT
        }
        if (used < BigDecimal.ZERO || used > BigDecimal("100")) return DistributionUnavailable.InvalidFact.PERCENT
        if (used.signum() == 0) return null
        if (used.scale().toLong() !in -MAX_ARITHMETIC_DIGITS.toLong()..MAX_ARITHMETIC_DIGITS.toLong()) {
            return DistributionUnavailable.InvalidFact.DECIMAL_CAPACITY
        }
        if (used.precision() > MAX_ARITHMETIC_DIGITS) return DistributionUnavailable.InvalidFact.DECIMAL_CAPACITY
        return null
    }
}
