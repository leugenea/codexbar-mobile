package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.WindowKind

/** Ascending bounded batches, one high-water and independent selected pages with real lookahead. */
internal object CurrentHistoryRead {
    fun read(access: HistoryRuntimeAccess, query: HistoryGraphQuery, revoked: () -> Boolean): HistoryReadOutcome {
        val window = query.window ?: return HistoryReadOutcome.Unavailable(HistoryUnavailable.READ_FAILURE)
        if (window.partition != access.partition) return HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        val selected = Selection(query)
        val failure = scan(access, query.after, listOf(selected), revoked)
        return failure ?: HistoryReadOutcome.Ready(selected.result(access.partition))
    }

    fun readCurrent(access: HistoryRuntimeAccess, request: HistoryPresentationRequest.Current,
        revoked: () -> Boolean): CurrentHistoryReadOutcome {
        val five = request.query(WindowKind.FIVE_HOUR).takeIf { it.window != null }?.let { Selection(it, latest = true) }
        val weekly = request.query(WindowKind.WEEKLY).takeIf { it.window != null }?.let { Selection(it, latest = true) }
        val selections = listOfNotNull(five, weekly)
        if (selections.any { it.query.window!!.partition != access.partition })
            return CurrentHistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        val failure = if (selections.isEmpty()) null else scan(access, null, selections, revoked)
        return currentResult(failure, CurrentHistoryPages(five?.result(access.partition), weekly?.result(access.partition),
            five?.latestEntry, weekly?.latestEntry))
    }

    private fun currentResult(failure: HistoryReadOutcome?, pages: CurrentHistoryPages): CurrentHistoryReadOutcome = when (failure) {
        is HistoryReadOutcome.Unavailable -> CurrentHistoryReadOutcome.Unavailable(failure.reason)
        HistoryReadOutcome.Corrupt -> CurrentHistoryReadOutcome.Corrupt
        else -> CurrentHistoryReadOutcome.Ready(pages)
    }

    /** Whole-window cards retain bounded selected entries and independent latest pace provenance. */
    private class Selection(val query: HistoryGraphQuery, val latest: Boolean = false) {
        // Grow with actual matches; the whole-window ceiling is not an upfront allocation.
        val selected = ArrayList<HistoryEntry>()
        var highWater: ObservationId? = null
        var last: HistoryReadSnapshot? = null
        var latestEntry: HistoryEntry? = null
        val complete get() = selected.size > query.limit
        fun accept(page: HistoryReadSnapshot, admitted: ObservationId?) {
            highWater = admitted
            last = page
            if (complete && !latest) return
            for (entry in page.entries) {
                if (entry.id.ordinal > (admitted?.ordinal ?: 0)) break
                if (entry.windows.any(query::selects)) retain(entry)
                if (complete && !latest) break
            }
        }
        private fun retain(entry: HistoryEntry) {
            latestEntry = entry
            if (!complete) selected.add(entry)
        }
        fun result(partition: HistoryPartition) = HistoryReadSnapshot(query.storage(partition), selected.take(query.limit),
            highWater, complete, last?.truncation.orEmpty(), last?.cutoffs.orEmpty())
    }

    private class Scan(val startAfter: ObservationId?) {
        var after = startAfter
        var examined = 0
        var highWater: ObservationId? = null
        private var initialized = false
        fun advance(page: HistoryReadSnapshot) {
            if (!initialized) { highWater = page.lastAdmitted; initialized = true }
            examined += page.entries.size
            after = page.nextAfter
        }
        fun exhausted(page: HistoryReadSnapshot) = !page.hasMore || page.nextAfter!!.ordinal >= (highWater?.ordinal ?: 0)
    }

    /** startAfter and each original result query are independent of the evolving native cursor. */
    private fun scan(access: HistoryRuntimeAccess, startAfter: ObservationId?, selections: List<Selection>,
        revoked: () -> Boolean): HistoryReadOutcome? {
        require(selections.size in 1..2)
        val scan = Scan(startAfter)
        while (scan.examined < HistoryLimits.MAX_OBSERVATIONS) {
            if (revoked()) return HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
            val limit = minOf(256, HistoryLimits.MAX_OBSERVATIONS - scan.examined)
            val outcome = access.read(HistoryReadQuery(access.partition, limit, scan.after))
            if (outcome !is HistoryReadOutcome.Ready) return outcome
            val page = outcome.snapshot
            scan.advance(page)
            selections.forEach { it.accept(page, scan.highWater) }
            if (selections.all { it.complete && !it.latest } || scan.exhausted(page)) return null
        }
        return HistoryReadOutcome.Unavailable(HistoryUnavailable.CAPACITY)
    }
}
