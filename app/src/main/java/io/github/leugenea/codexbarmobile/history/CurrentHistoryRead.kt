package io.github.leugenea.codexbarmobile.history

/** Stream bounded ascending store pages; retain only a selected page and one real lookahead. */
internal object CurrentHistoryRead {
    fun read(access: HistoryRuntimeAccess, query: HistoryGraphQuery, revoked: () -> Boolean): HistoryReadOutcome {
        val window = query.window ?: return HistoryReadOutcome.Unavailable(HistoryUnavailable.READ_FAILURE)
        if (window.partition != access.partition) return HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        return scan(access, query, revoked)
    }

    private class Scan(val query: HistoryGraphQuery) {
        val selected = ArrayList<HistoryEntry>(query.limit + 1)
        var after = query.after
        var examined = 0
        var highWater: ObservationId? = null
        private var initialized = false
        fun accept(page: HistoryReadSnapshot) {
            if (!initialized) { highWater = page.lastAdmitted; initialized = true }
            examined += page.entries.size
            select(page, query, highWater, selected)
            after = page.nextAfter
        }
        fun complete(page: HistoryReadSnapshot): Boolean = selected.size > query.limit || !page.hasMore ||
            page.nextAfter!!.ordinal >= (highWater?.ordinal ?: 0)
    }

    private fun scan(access: HistoryRuntimeAccess, query: HistoryGraphQuery, revoked: () -> Boolean): HistoryReadOutcome {
        val scan = Scan(query)
        while (scan.examined < HistoryLimits.MAX_OBSERVATIONS) {
            if (revoked()) return HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
            val limit = minOf(256, HistoryLimits.MAX_OBSERVATIONS - scan.examined)
            val outcome = access.read(HistoryReadQuery(access.partition, limit, scan.after))
            if (outcome !is HistoryReadOutcome.Ready) return outcome
            val page = outcome.snapshot
            scan.accept(page)
            if (scan.complete(page)) return result(access, query, scan.selected, scan.highWater, page)
        }
        // A real store cannot exceed the retention ceiling. Fail rather than guess continuation.
        return HistoryReadOutcome.Unavailable(HistoryUnavailable.CAPACITY)
    }

    private fun select(page: HistoryReadSnapshot, query: HistoryGraphQuery, highWater: ObservationId?, selected: MutableList<HistoryEntry>) {
        for (entry in page.entries) {
            if (entry.id.ordinal > (highWater?.ordinal ?: 0)) break
            if (entry.windows.any(query::selects)) selected.add(entry)
            if (selected.size > query.limit) break
        }
    }

    private fun result(access: HistoryRuntimeAccess, query: HistoryGraphQuery, selected: List<HistoryEntry>,
        highWater: ObservationId?, last: HistoryReadSnapshot): HistoryReadOutcome = HistoryReadOutcome.Ready(
        HistoryReadSnapshot(query.storage(access.partition), selected.take(query.limit), highWater,
            selected.size > query.limit, last.truncation, last.cutoffs))
}
