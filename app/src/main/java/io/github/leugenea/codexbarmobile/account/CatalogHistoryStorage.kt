package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.*

/** Keeps the existing sidecar and catalog key coherent under the history owner's I/O lane. */
internal class CatalogHistoryStorage(
    private val storage: HistoryLifetimeStorage,
    private val catalog: AccountCatalog,
) : HistoryLifetimeStorage by storage {
    override fun activate(partition: HistoryPartition) = catalogued { storage.activate(partition) }
    override fun restore() = if (catalog.snapshot().deleting != null) HistoryLifetimeOutcome.RemovalRequired else catalogued(storage::restore)

    private fun catalogued(read: () -> HistoryLifetimeOutcome): HistoryLifetimeOutcome {
        val result = read()
        if (result == HistoryLifetimeOutcome.RemovalRequired) return result
        val bound = result as? HistoryLifetimeOutcome.Bound
        return try {
            reconcile(bound?.access?.partition)
            result
        } catch (_: Exception) {
            bound?.access?.revoke()
            HistoryLifetimeOutcome.CatalogUnavailable
        }
    }

    override fun reconcileMetadata(partition: HistoryPartition): Boolean = try {
        reconcile(partition)
        true
    } catch (_: Exception) { false }

    private fun reconcile(partition: HistoryPartition?) {
        val name = partition?.let(storage::readName) ?: AccountNameRead.Unavailable
        val entry = catalog.adopt(partition, name)
        // False replay means optional metadata is pending; required catalog commits still throw.
        recoverRename(entry)
    }

    override fun readName(partition: HistoryPartition): AccountNameRead = try {
        if (consistent(partition)) storage.readName(partition) else AccountNameRead.Unavailable
    } catch (_: Exception) { AccountNameRead.Unavailable }

    private fun consistent(partition: HistoryPartition): Boolean {
        val entry = catalog.legacyEntry() ?: return false
        if (entry.partition != partition || catalog.snapshot().rename != null) return false
        val name = storage.readName(partition)
        if (!entry.nameAvailable) return name is AccountNameRead.Unavailable
        return name is AccountNameRead.Ready && name.name?.text == entry.name
    }

    override fun writeName(partition: HistoryPartition, name: AccountDisplayName?): Boolean = try {
        val entry = catalog.legacyEntry()
        if (entry?.partition != partition || !recoverRename(entry)) false
        else if (!catalog.reserveRename(entry.storage.id, name)) false
        else recoverRename(entry)
    } catch (_: Exception) { false }

    private fun recoverRename(entry: CatalogEntry): Boolean {
        val pending = catalog.snapshot().rename ?: return true
        if (pending.entry != entry.storage.id) return false
        val partition = entry.partition ?: return false
        val name = pending.name?.let(AccountDisplayName::from)
        if (!storage.writeName(partition, name)) return false
        catalog.finishRename(entry.storage.id)
        return true
    }

    override fun reserveDeletion(): HistoryDeletion {
        val ticket = storage.reserveDeletion()
        return HistoryDeletion {
            try {
                catalog.beginDeletion()
                ticket.complete()
            } catch (_: Exception) { HistoryDeleteOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE) }
        }
    }
}
