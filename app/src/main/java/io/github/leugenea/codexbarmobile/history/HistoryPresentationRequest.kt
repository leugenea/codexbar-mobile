package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.WindowKind

/** One finite presentation interest on the recorder lane, never a provider command. */
internal sealed interface HistoryPresentationRequest {
    data object Idle : HistoryPresentationRequest
    data class Page(val query: HistoryGraphQuery) : HistoryPresentationRequest
    data class Current(val fiveHour: WindowIdentity?, val weekly: WindowIdentity?, val limit: Int = HistoryLimits.MAX_OBSERVATIONS) : HistoryPresentationRequest {
        init {
            require(limit in 1..HistoryLimits.MAX_OBSERVATIONS)
            require(fiveHour == null || fiveHour.kind == WindowKind.FIVE_HOUR)
            require(weekly == null || weekly.kind == WindowKind.WEEKLY)
        }
        fun query(kind: WindowKind) = HistoryGraphQuery(limit, kind = kind,
            window = if (kind == WindowKind.FIVE_HOUR) fiveHour else weekly, currentOnly = true)
        fun matches(periods: CurrentHistoryPeriods) = fiveHour == periods.fiveHour.window && weekly == periods.weekly.window
    }
}

/** Identity distinguishes an explicit reread or route round-trip to an equal request. Never saved. */
internal class HistoryRequestGeneration
internal class HistoryDisplaySelection(val request: HistoryPresentationRequest, val generation: HistoryRequestGeneration = HistoryRequestGeneration())

internal class CurrentHistoryPages(val fiveHour: HistoryReadSnapshot?, val weekly: HistoryReadSnapshot?,
    val latestFiveHour: HistoryEntry? = null, val latestWeekly: HistoryEntry? = null) {
    fun forKind(kind: WindowKind) = if (kind == WindowKind.FIVE_HOUR) fiveHour else weekly
    fun latestForKind(kind: WindowKind) = if (kind == WindowKind.FIVE_HOUR) latestFiveHour else latestWeekly
}

internal sealed interface CurrentHistoryReadOutcome {
    data class Ready(val pages: CurrentHistoryPages) : CurrentHistoryReadOutcome
    data class Unavailable(val reason: HistoryUnavailable) : CurrentHistoryReadOutcome
    data object Corrupt : CurrentHistoryReadOutcome
}

internal fun HistoryPresentationRequest.matches(periods: CurrentHistoryPeriods): Boolean = when (this) {
    is HistoryPresentationRequest.Current -> matches(periods)
    is HistoryPresentationRequest.Page -> !query.currentOnly || query.kind?.let { query.window == periods.forKind(it).window } == true
    HistoryPresentationRequest.Idle -> true
}
