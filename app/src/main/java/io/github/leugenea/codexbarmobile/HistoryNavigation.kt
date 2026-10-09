package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind

/** Only one bounded page and its exclusive storage cursor; no accumulated pages or saved authority. */
internal data class HistoryNavigation(
    val kind: WindowKind = WindowKind.FIVE_HOUR,
    val after: ObservationId? = null,
) {
    val query get() = HistoryGraphQuery(limit = PAGE_LIMIT, after = after, kind = kind)

    fun next(source: HistoryGraphSnapshot): HistoryNavigation {
        if (source.query != query || !source.hasMore) return this
        val cursor = source.storage?.nextAfter ?: return this
        if (cursor.ordinal <= (after?.ordinal ?: 0)) return this
        return copy(after = cursor)
    }

    companion object {
        const val PAGE_LIMIT = 32
    }
}

/** One fresh descriptor read at the host boundary, independent of delayed snapshot collection. */
internal fun historyForDisplay(
    source: HistoryGraphSnapshot, query: HistoryGraphQuery,
): HistoryGraphSnapshot {
    val permission = source.displayPermission
    val current = permission?.current()
    if (current == null || current !== permission.context) return hiddenHistory(current, query)
    if (current.generation != null) {
        if (source.generation === current.generation && source.query == query) return source
        return hiddenHistory(current, query)
    }
    return unavailableHistory(source, query)
}

private fun hiddenHistory(current: HistoryDisplayContext?, query: HistoryGraphQuery) =
    HistoryGraphSnapshot(if (current?.generation != null) HistoryReadiness.LOADING else HistoryReadiness.UNAVAILABLE, query = query)

private fun unavailableHistory(source: HistoryGraphSnapshot, query: HistoryGraphQuery): HistoryGraphSnapshot {
    if (source.generation == null && source.readiness == HistoryReadiness.ERROR) {
        return HistoryGraphSnapshot(HistoryReadiness.ERROR, query = query,
            problem = source.problem, storageReason = source.storageReason, lostSamples = source.lostSamples)
    }
    return HistoryGraphSnapshot(HistoryReadiness.UNAVAILABLE, query = query)
}
