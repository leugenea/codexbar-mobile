package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind

/** Only one bounded page and its exclusive storage cursor; no accumulated pages or saved authority. */
internal data class HistoryNavigation(
    val kind: WindowKind = WindowKind.FIVE_HOUR,
    val after: ObservationId? = null,
    val window: WindowIdentity? = null,
    val currentOnly: Boolean = false,
) {
    val query get() = HistoryGraphQuery(limit = PAGE_LIMIT, after = after, kind = kind, window = window, currentOnly = currentOnly)

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

/** Actual authority is read by the host, independently of delayed snapshot collection. */
internal fun historyForDisplay(source: HistoryGraphSnapshot, query: HistoryGraphQuery,
    current: HistoryDisplayContext? = source.displayPermission?.current()): HistoryGraphSnapshot {
    if (!currentContext(source, current)) return hiddenHistory(current, query)
    if (current!!.generation == null) return unavailableHistory(source, query)
    if (!currentSelection(source, current, HistoryPresentationRequest.Page(query))) return hiddenHistory(current, query)
    if (query.currentOnly && query.window == null) return currentPeriodUnavailable(source, query)
    if (!HistoryPresentationRequest.Page(query).matches(current.periods)) return hiddenHistory(null, query)
    return source
}

internal fun currentHistoryForDisplay(source: HistoryGraphSnapshot, expected: HistoryPresentationRequest.Current,
    kind: WindowKind, authority: HistoryDisplayContext?): HistoryGraphSnapshot {
    val query = expected.query(kind)
    if (!currentContext(source, authority)) return hiddenHistory(authority, query)
    if (authority!!.generation == null) return unavailableHistory(source, query)
    if (!currentSelection(source, authority, expected)) return hiddenHistory(authority, query)
    if (!expected.matches(authority.periods)) return hiddenHistory(null, query)
    if (query.window == null) return currentPeriodUnavailable(source, query)
    return currentGraph(source, query, source.currentPages?.forKind(kind))
}

private fun currentContext(source: HistoryGraphSnapshot, actual: HistoryDisplayContext?) =
    actual != null && actual === source.displayPermission?.context && source.displayPermission?.current() === actual

private fun currentSelection(source: HistoryGraphSnapshot, actual: HistoryDisplayContext, expected: HistoryPresentationRequest) =
    source.generation === actual.generation && source.selection === actual.selection && source.request == expected

private fun currentGraph(source: HistoryGraphSnapshot, query: HistoryGraphQuery, page: HistoryReadSnapshot?): HistoryGraphSnapshot {
    val readiness = if (source.readiness == HistoryReadiness.READY && page?.content == HistoryContent.EMPTY) HistoryReadiness.EMPTY else source.readiness
    return HistoryGraphSnapshot(readiness, source.generation, source.partition, query, page,
        source.problem, source.storageReason, source.lostSamples, source.live, source.displayPermission,
        source.request, source.selection)
}

private fun hiddenHistory(current: HistoryDisplayContext?, query: HistoryGraphQuery) =
    HistoryGraphSnapshot(if (current?.generation != null) HistoryReadiness.LOADING else HistoryReadiness.UNAVAILABLE, query = query)

private fun unavailableHistory(source: HistoryGraphSnapshot, query: HistoryGraphQuery): HistoryGraphSnapshot {
    if (source.generation == null && source.problem != null) {
        return HistoryGraphSnapshot(HistoryReadiness.ERROR, query = query,
            problem = source.problem, storageReason = source.storageReason, lostSamples = source.lostSamples)
    }
    return HistoryGraphSnapshot(HistoryReadiness.UNAVAILABLE, query = query)
}

private fun currentPeriodUnavailable(source: HistoryGraphSnapshot, query: HistoryGraphQuery) =
    HistoryGraphSnapshot(if (source.problem == null) HistoryReadiness.UNAVAILABLE else HistoryReadiness.ERROR,
        query = query, problem = source.problem, storageReason = source.storageReason, lostSamples = source.lostSamples, live = source.live)
