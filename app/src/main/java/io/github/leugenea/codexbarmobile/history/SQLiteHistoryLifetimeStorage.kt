package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.account.AccountDisplayName

/** Thin adapter, no new session owner or storage schema. */
internal class SQLiteHistoryLifetimeStorage(private val store: SQLiteHistoryStore) : HistoryLifetimeStorage {
    override fun stage() = store.stagePartition()
    override fun activate(partition: HistoryPartition) = store.activatePartition(partition).lifetimeOutcome()
    override fun restore() = store.restorePartition()
    override fun readName(partition: HistoryPartition) = store.readName(partition)
    override fun writeName(partition: HistoryPartition, name: AccountDisplayName?) = store.writeName(partition, name)
    override fun reserveDeletion() = store.reserveDeletion()
    override fun close() = store.close()
}

internal fun HistoryAccessOutcome.lifetimeOutcome(): HistoryLifetimeOutcome = when (this) {
    is HistoryAccessOutcome.Bound -> HistoryLifetimeOutcome.Bound(access)
    else -> HistoryLifetimeOutcome.Unavailable
}
