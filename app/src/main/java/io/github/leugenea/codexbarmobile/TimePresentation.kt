package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.Knowledge
import io.github.leugenea.codexbarmobile.usage.Reason
import io.github.leugenea.codexbarmobile.usage.ResetTime
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class TimeKind { PERIODIC_RESET, ENTITLEMENT_EXPIRY }

enum class TimeState(val labelResource: Int) {
    FUTURE(R.string.time_remaining),
    LESS_THAN_HOUR(R.string.time_less_than_hour),
    AWAITING_REFRESH(R.string.time_awaiting_refresh),
    EXPIRED(R.string.time_expired),
    UNKNOWN_TARGET(R.string.time_unknown_target),
    UNKNOWN_CLOCK(R.string.time_unknown_clock),
    DISCREPANT(R.string.time_discrepant),
}

/** OBSERVED does not promise freshness: no maximum snapshot age is inferred here. */
enum class TimeSnapshot(val labelResource: Int) {
    OBSERVED(R.string.time_snapshot_observed),
    UNKNOWN(R.string.time_snapshot_unknown),
    STALE(R.string.time_snapshot_stale),
    CLOCK_BEFORE_OBSERVATION(R.string.time_clock_before_observation),
}

/** Date and hour are localized data, not UI wording. Offset is signed ISO numeric data. */
data class AbsoluteTime(val date: String, val hour: String, val utcOffset: String?) {
    val formatResource: Int
        get() = if (utcOffset == null) R.string.time_absolute else R.string.time_absolute_offset
}

/** Elapsed 24-hour days, NOT calendar days. Consumers pluralize both units, including zero. */
data class RemainingTime(val days: Long, val hours: Int)

data class PresentedTime(
    val kind: TimeKind,
    val state: TimeState,
    val absolute: AbsoluteTime?,
    val remaining: RemainingTime?,
    val snapshot: TimeSnapshot,
    val targetKnowledge: Knowledge,
    val targetReason: Reason?,
)

/**
 * Shared, time-only reset/expiry projection; no global clocks/locales/zones or I/O.
 *
 * Absolute hour labels truncate minutes/seconds for display only (resources explicitly
 * say "hour precision"). Relative time uses the ORIGINAL epoch instant: floor positive
 * elapsed duration to whole hours, then divide by 24. 0 < duration < 1h is LESS_THAN_HOUR;
 * equality is already passed. Never infer another reset, change usage or provider counts.
 *
 * Unknown evaluation time still permits a known absolute date; unknown/discrepant targets
 * do not choose a date or infinite lifetime. Field knowledge/reasons survive projection.
 * A1's cached due/locallyExpired flags are not used: every call reevaluates the instant.
 *
 * Snapshot staleness is explicit from the caller OR a target crossed since observation.
 * No age cutoff/cadence is chosen. Missing observation/clock means UNKNOWN; backwards
 * clocks are explicitly labelled. A caller may reevaluate with a new zone/now without a read.
 * Labels/format/plural IDs are resource-ready, but rendering needs no arithmetic fork.
 */
object TimePresentation {
    val daysPluralResource: Int = R.plurals.time_days
    val hoursPluralResource: Int = R.plurals.time_hours
    val relativeFormatResource: Int = R.string.time_days_hours

    fun present(
        target: Field<Instant>, kind: TimeKind, observedAt: Instant?, now: Instant?,
        zone: ZoneId, locale: Locale, targetDiscrepant: Boolean = false, staleSnapshot: Boolean = false,
    ): PresentedTime {
        val instant = if (target.knowledge == Knowledge.KNOWN && !targetDiscrepant) target.value else null
        val absolute = instant?.let { absolute(it, zone, locale) }
        val state = state(instant, absolute, kind, now, targetDiscrepant)
        val remaining = if (state == TimeState.FUTURE) remaining(instant!!, now!!) else null
        return PresentedTime(
            kind, state, absolute, remaining, snapshot(instant, observedAt, now, staleSnapshot),
            target.knowledge, target.reason,
        )
    }

    /** Reads the injected clock exactly once; null is an unknown clock, not a system fallback. */
    fun presentWithClock(
        target: Field<Instant>, kind: TimeKind, observedAt: Instant?, clock: Clock?,
        zone: ZoneId, locale: Locale, targetDiscrepant: Boolean = false, staleSnapshot: Boolean = false,
    ): PresentedTime = present(target, kind, observedAt, clock?.instant(), zone, locale, targetDiscrepant, staleSnapshot)

    /** A1 owns relative derivation and conflict detection; do not derive from reset-after again. */
    fun presentReset(
        reset: ResetTime, observedAt: Instant?, now: Instant?, zone: ZoneId, locale: Locale,
        staleSnapshot: Boolean = false,
    ): PresentedTime {
        val target = when {
            reset.absolute.knowledge == Knowledge.KNOWN && reset.absolute.value != null -> reset.absolute
            reset.relativeDerived.knowledge == Knowledge.KNOWN && reset.relativeDerived.value != null -> reset.relativeDerived
            reset.absolute.knowledge != Knowledge.UNAVAILABLE -> reset.absolute
            else -> reset.relativeDerived
        }
        return present(target, TimeKind.PERIODIC_RESET, observedAt, now, zone, locale, reset.discrepant, staleSnapshot)
    }

    private fun state(
        target: Instant?, absolute: AbsoluteTime?, kind: TimeKind, now: Instant?, discrepant: Boolean,
    ): TimeState = when {
        discrepant -> TimeState.DISCREPANT
        target == null || absolute == null -> TimeState.UNKNOWN_TARGET
        now == null -> TimeState.UNKNOWN_CLOCK
        target <= now -> if (kind == TimeKind.PERIODIC_RESET) TimeState.AWAITING_REFRESH else TimeState.EXPIRED
        Duration.between(now, target) < Duration.ofHours(1) -> TimeState.LESS_THAN_HOUR
        else -> TimeState.FUTURE
    }

    private fun remaining(target: Instant, now: Instant): RemainingTime {
        val hours = Duration.between(now, target).toHours()
        return RemainingTime(hours / 24, (hours % 24).toInt())
    }

    private fun snapshot(target: Instant?, observed: Instant?, now: Instant?, stale: Boolean): TimeSnapshot = when {
        stale -> TimeSnapshot.STALE
        observed == null || now == null -> TimeSnapshot.UNKNOWN
        now < observed -> TimeSnapshot.CLOCK_BEFORE_OBSERVATION
        target != null && observed < target && target <= now -> TimeSnapshot.STALE
        else -> TimeSnapshot.OBSERVED
    }

    private fun absolute(target: Instant, zone: ZoneId, locale: Locale): AbsoluteTime? = try {
        val local = target.atZone(zone)
        val hour = local.toLocalDateTime().withMinute(0).withSecond(0).withNano(0)
        // Inspect overlap intervals, not only the exact minute: a partial-hour
        // fall-back can make the hour label ambiguous even when this minute is unique.
        val hourEnd = hour.plusHours(1)
        val repeated = listOf(
            zone.rules.previousTransition(target.plusNanos(1)),
            zone.rules.nextTransition(target.minusNanos(1)),
        ).filterNotNull().any {
            it.isOverlap && it.dateTimeAfter < hourEnd && it.dateTimeBefore > hour
        }
        AbsoluteTime(
            local.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
            hour.format(DateTimeFormatter.ofPattern("HH:mm", locale)),
            if (repeated) {
                if (local.offset.totalSeconds == 0) "+00:00" else local.offset.id
            } else null,
        )
    } catch (_: DateTimeException) {
        // Even a positive Instant can lie outside the representable local-date range.
        null
    }
}
