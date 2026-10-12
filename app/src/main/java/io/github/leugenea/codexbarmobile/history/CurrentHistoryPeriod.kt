package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.UsageRefreshState
import io.github.leugenea.codexbarmobile.usage.SelectionState
import io.github.leugenea.codexbarmobile.usage.WindowKind
import io.github.leugenea.codexbarmobile.usage.UsageObservation
import java.time.DateTimeException
import java.time.Instant

/** Evaluation uses B2's live cache and clock, never historical observations or a system clock. */
internal data class CurrentHistoryPeriod(val window: WindowIdentity? = null, val unavailable: BaselineEligibility? = null)

/** Only safe keys, typed absence and the evaluation instant cross the display authority boundary. */
internal data class CurrentHistoryPeriods(
    val fiveHour: CurrentHistoryPeriod = CurrentHistoryPeriod(unavailable = BaselineEligibility.WINDOW_UNAVAILABLE),
    val weekly: CurrentHistoryPeriod = CurrentHistoryPeriod(unavailable = BaselineEligibility.WINDOW_UNAVAILABLE),
    val evaluatedAt: Instant? = null,
) {
    fun forKind(kind: WindowKind): CurrentHistoryPeriod = when (kind) {
        WindowKind.FIVE_HOUR -> fiveHour
        WindowKind.WEEKLY -> weekly
        else -> CurrentHistoryPeriod(unavailable = BaselineEligibility.WINDOW_UNAVAILABLE)
    }
}

internal fun currentHistoryPeriods(partition: HistoryPartition?, refresh: UsageRefreshState) = CurrentHistoryPeriods(
    currentHistoryPeriod(partition, refresh, WindowKind.FIVE_HOUR),
    currentHistoryPeriod(partition, refresh, WindowKind.WEEKLY), refresh.evaluatedAt)

internal fun currentHistoryPeriod(partition: HistoryPartition?, refresh: UsageRefreshState, kind: WindowKind): CurrentHistoryPeriod {
    if (partition == null) return CurrentHistoryPeriod(unavailable = BaselineEligibility.WINDOW_UNAVAILABLE)
    val now = refresh.evaluatedAt ?: return CurrentHistoryPeriod(unavailable = BaselineEligibility.CLOCK_UNVERIFIED)
    val usage = refresh.usage.success?.usage ?: return CurrentHistoryPeriod(unavailable = BaselineEligibility.WINDOW_UNAVAILABLE)
    val observed = usage.observedAt ?: return CurrentHistoryPeriod(unavailable = BaselineEligibility.UNKNOWN_OBSERVATION_TIME)
    if (now < observed) return CurrentHistoryPeriod(unavailable = BaselineEligibility.CLOCK_DISCONTINUITY)
    val selection = selection(usage, kind) ?: return CurrentHistoryPeriod(unavailable = BaselineEligibility.WINDOW_UNAVAILABLE)
    if (selection.state == SelectionState.UNAVAILABLE) return CurrentHistoryPeriod(unavailable = BaselineEligibility.WINDOW_UNAVAILABLE)
    if (selection.state == SelectionState.AMBIGUOUS) return CurrentHistoryPeriod(unavailable = BaselineEligibility.AMBIGUOUS_WINDOW)
    val identity = WindowHistory.identity(partition, selection, kind)
        ?: return CurrentHistoryPeriod(unavailable = BaselineEligibility.UNKEYED_RESET)
    return qualifyCurrentPeriod(identity, now)
}

private fun selection(usage: UsageObservation, kind: WindowKind) = when (kind) {
    WindowKind.FIVE_HOUR -> usage.fiveHour
    WindowKind.WEEKLY -> usage.weekly
    else -> null
}

private fun qualifyCurrentPeriod(identity: WindowIdentity, now: Instant): CurrentHistoryPeriod = try {
    when {
        now >= identity.resetAt -> CurrentHistoryPeriod(unavailable = BaselineEligibility.AFTER_RESET)
        now < identity.resetAt.minusSeconds(identity.durationSeconds) -> CurrentHistoryPeriod(unavailable = BaselineEligibility.BEFORE_NOMINAL_START)
        else -> CurrentHistoryPeriod(identity)
    }
} catch (_: DateTimeException) {
    CurrentHistoryPeriod(unavailable = BaselineEligibility.TIME_RANGE_OVERFLOW)
}
