package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.Knowledge
import io.github.leugenea.codexbarmobile.usage.SelectionState
import io.github.leugenea.codexbarmobile.usage.UsageObservation
import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

internal data class PresentedPace(val resource: Int, val amount: String? = null)

/** Compact labels/selectors only; existing usage, C1, B1 and graph projections own all facts. */
internal object DashboardPresentation {
    fun inventoryCount(facts: PresentedEntitlements): Long? =
        facts.reportedAvailableCount?.takeIf { it.knowledge == Knowledge.KNOWN }?.value

    /** Expiry owns an expired state when dated; otherwise the second fact owns the state/countdown. */
    fun bankedRowDetails(expiry: String, remaining: String?): String =
        listOfNotNull(expiry, remaining).joinToString(", ")

    /** Compact row states are separate from the unchanged card-level inventory notes. */
    fun bankedItemState(state: EntitlementState): Int = when (state) {
        EntitlementState.KNOWN, EntitlementState.UNKNOWN -> R.string.dashboard_banked_state_unknown
        EntitlementState.EMPTY -> R.string.dashboard_inventory_empty
        EntitlementState.UNSUPPORTED -> R.string.dashboard_banked_state_unsupported
        EntitlementState.INACCESSIBLE -> R.string.dashboard_banked_state_unavailable
        EntitlementState.MALFORMED -> R.string.dashboard_banked_state_malformed
        EntitlementState.EXPIRED -> R.string.dashboard_inventory_expired
        EntitlementState.DISCREPANT -> R.string.dashboard_banked_state_discrepant
    }

    fun hasChart(series: HistoryPlotSeries) = series.samples.any { it.measured?.position is PlotPosition.Available }

    fun noInformation(source: HistoryGraphSnapshot, status: UsageWindowStatus = UsageWindowStatus.UNAVAILABLE, malformed: Boolean = false): Int = when {
        malformed && source.problem != null -> R.string.dashboard_no_history_malformed_error
        malformed && source.readiness == HistoryReadiness.LOADING -> R.string.dashboard_no_history_malformed_loading
        malformed -> R.string.dashboard_no_history_malformed
        source.problem != null -> R.string.dashboard_no_history_error
        source.readiness == HistoryReadiness.LOADING -> R.string.dashboard_no_history_loading
        else -> missingWindow(status)
    }

    private fun missingWindow(status: UsageWindowStatus): Int = when (status) {
        UsageWindowStatus.MALFORMED -> R.string.dashboard_no_history_malformed
        UsageWindowStatus.AMBIGUOUS -> R.string.dashboard_no_history_ambiguous
        UsageWindowStatus.UNSUPPORTED -> R.string.dashboard_no_history_unsupported
        else -> R.string.dashboard_no_history
    }

    fun windowWarning(status: UsageWindowStatus, malformed: Boolean = false): Int? = when {
        malformed || status == UsageWindowStatus.MALFORMED -> R.string.dashboard_window_malformed
        status == UsageWindowStatus.UNSUPPORTED -> R.string.dashboard_window_unsupported
        else -> null
    }

    /** Compare only the accepted headline observation, never an older chart-page point. */
    fun paceComparison(display: HistoryGraphSnapshot, usage: UsageObservation?, kind: WindowKind): UsedComparison? {
        val observedAt = usage?.observedAt ?: return null
        val entry = display.latestCurrent ?: return null
        if (entry.observedAt != observedAt) return null
        val selection = if (kind == WindowKind.FIVE_HOUR) usage.fiveHour else usage.weekly
        if (selection.state == SelectionState.AMBIGUOUS) return null
        val current = selection.candidates.singleOrNull() ?: return null
        val window = entry.windows.singleOrNull { it.kind == kind } ?: return null
        return if (matchesHeadline(window, current)) EvenDistribution.compare(window) else null
    }

    private fun matchesHeadline(window: HistoryWindow, current: io.github.leugenea.codexbarmobile.usage.UsageWindow) =
        window.kind == current.kind && window.percent == current.usedPercent &&
            window.duration == current.durationSeconds && window.reset.facts == current.reset

    /** Display only: HALF_UP rounds ties away from zero to 0.1 percentage points.
     * Thus -0.05 and +0.05 show reserve/over pace; magnitudes below 0.05 are on pace.
     * The original observed comparison and provider measurement remain exact.
     */
    fun pace(delta: BigDecimal, locale: Locale): PresentedPace {
        val rounded = delta.setScale(1, RoundingMode.HALF_UP)
        if (rounded.signum() == 0) return PresentedPace(R.string.dashboard_on_pace)
        val amount = rounded.abs().toPlainString().replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
        return PresentedPace(if (rounded.signum() > 0) R.string.dashboard_over_pace else R.string.dashboard_reserve, amount)
    }

    fun status(status: UsageScreenStatus): Int = when (status) {
        UsageScreenStatus.DISCONNECTED -> R.string.dashboard_disconnected
        UsageScreenStatus.LOADING -> R.string.dashboard_loading
        UsageScreenStatus.UNAVAILABLE -> R.string.dashboard_unavailable
        UsageScreenStatus.CURRENT -> R.string.dashboard_current
        UsageScreenStatus.STALE -> R.string.dashboard_stale
        UsageScreenStatus.ERROR -> R.string.dashboard_error
        UsageScreenStatus.REAUTHORIZE -> R.string.dashboard_reauthorize
    }

    fun problem(problem: ConnectionProblem?): Int? = when (problem) {
        ConnectionProblem.STORAGE -> R.string.dashboard_storage_error
        ConnectionProblem.AUTH -> R.string.dashboard_auth_error
        ConnectionProblem.BROWSER -> R.string.dashboard_browser_error
        else -> null
    }

    fun loginVisible(phase: ConnectionPhase) = phase !in setOf(ConnectionPhase.RESTORED, ConnectionPhase.READING, ConnectionPhase.OBSERVED)
    fun cancelVisible(phase: ConnectionPhase) = phase in setOf(ConnectionPhase.AUTHENTICATING, ConnectionPhase.READING)

    fun entitlementState(state: EntitlementState): Int = when (state) {
        EntitlementState.KNOWN -> R.string.dashboard_inventory_known
        EntitlementState.EMPTY -> R.string.dashboard_inventory_empty
        EntitlementState.UNSUPPORTED -> R.string.dashboard_inventory_unsupported
        EntitlementState.UNKNOWN -> R.string.dashboard_inventory_unknown
        EntitlementState.INACCESSIBLE -> R.string.dashboard_inventory_unavailable
        EntitlementState.MALFORMED -> R.string.dashboard_inventory_malformed
        EntitlementState.EXPIRED -> R.string.dashboard_inventory_expired
        EntitlementState.DISCREPANT -> R.string.dashboard_inventory_discrepant
    }
}
