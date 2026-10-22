package io.github.leugenea.codexbarmobile.history

import org.junit.Assert.*
import java.time.Instant

/** Transactional memory adapter for the production replacement algorithm, not SQLite evidence. */
internal class ImportMemoryStorage : HistoryReplacementStorage, HistoryRuntimeAccess {
    override val partition = SyntheticHistory.partition
    var state = HistoryDiskState(HistoryCursor(partition))
    val entries = ArrayList<HistoryEntry>()
    val operations = ArrayList<String>()
    var pages = 100_000L
    var revoked = false
    var afterRead: (() -> Unit)? = null
    var afterInsert: () -> Unit = {}
    var beforeCommit: () -> Unit = {}

    override fun <T> transaction(block: () -> T): T {
        val saved = entries.toList()
        val savedState = state
        return try { block() } catch (problem: Exception) {
            entries.clear(); entries.addAll(saved); state = savedState
            throw problem
        }
    }
    override fun removeThrough(ordinal: Long) { entries.removeAll { it.id.ordinal <= ordinal } }
    override fun oldestPrefix(count: Long): Long = entries.take(count.toInt()).lastOrNull()?.id?.ordinal ?: 0
    override fun spacePages(): Long = pages - entries.size
    override fun insert(entry: HistoryEntry, payload: ByteArray) { entries.add(entry) }
    override fun writeState(state: HistoryDiskState) { this.state = state }
    override fun revoke() { revoked = true }
    override fun delete(partition: HistoryPartition): HistoryDeleteOutcome = error("Not used")
    override fun read(query: HistoryReadQuery): HistoryReadOutcome {
        if (revoked) return HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        val found = entries.filter { it.id.ordinal > (query.after?.ordinal ?: 0) }
        val snapshot = HistoryReadSnapshot(query, found.take(query.limit), state.cursor.lastOrdinal.takeIf { it > 0 }?.let(::ObservationId),
            found.size > query.limit, state.cutoffs.keys, state.cutoffs)
        afterRead?.also { afterRead = null }?.invoke()
        return HistoryReadOutcome.Ready(snapshot)
    }
    override fun append(admission: HistoryAdmission): HistoryAppendOutcome {
        operations += "append:${admission.id.ordinal}"
        return when (val reduced = WindowHistory.append(state.cursor, admission)) {
            is HistoryReduction.AlreadyAdmitted -> HistoryAppendOutcome.AlreadyAdmitted(reduced.id)
            is HistoryReduction.Rejected -> HistoryAppendOutcome.Rejected(reduced.reason)
            is HistoryReduction.Applied -> {
                entries.add(reduced.entry)
                state = HistoryRetention.advance(state, reduced)
                HistoryAppendOutcome.Stored(reduced.entry)
            }
        }
    }
    override fun importHistory(text: String, now: Instant): HistoryImportOutcome {
        if (revoked) return HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        val document = HistoryImport.decode(text, partition)
        operations += "replace"
        HistoryImport.replace(document, this, HistoryStorageLimits(), now, afterInsert, beforeCommit)
        return HistoryImportOutcome.Imported
    }
    fun document(): HistoryExportDocument = HistoryExportDocument("test", SyntheticHistory.at, entries.toList(), state)
    fun text(): String = HistoryExport.encode(document())
    fun seed(count: Int): ImportMemoryStorage = apply {
        repeat(count) { index ->
            assertTrue(append(SyntheticHistory.admission(state.cursor,
                SyntheticHistory.usage(observedAt = SyntheticHistory.at.plusSeconds(index.toLong())), (index + 1) * 1000L)) is HistoryAppendOutcome.Stored)
        }
    }
}
