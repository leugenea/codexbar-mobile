package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.*

/** Keeps the existing sidecar and catalog key coherent under the history owner's I/O lane. */
internal class CatalogHistoryStorage(
    private val storage: HistoryLifetimeStorage,
    private val catalog: AccountCatalog,
    private val address: AccountStorage? = null,
) : HistoryLifetimeStorage by storage {
    private fun entry() = address?.let { catalog.entry(it.id)?.also { entry -> check(entry.storage == it) } }
        ?: if (address == null) catalog.legacyEntry() else null
    private fun pending() = catalog.snapshot().deletions.any { it.entry == (address?.id ?: entry()?.storage?.id) }

    override fun activate(partition: HistoryPartition) = catalogued { storage.activate(partition) }
    override fun restore() = if (pending()) HistoryLifetimeOutcome.RemovalRequired else catalogued(storage::restore)

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
        val saved = entry()
        if (partition != null && saved?.partition == null && saved?.nameAvailable == true) {
            check(storage.writeName(partition, saved.name?.let(AccountDisplayName::from)))
        }
        val name = partition?.let(storage::readName) ?: AccountNameRead.Unavailable
        val entry = if (address == null) catalog.adopt(partition, name)
            else catalog.reconcile(address.id, partition, name)
        // Rename settlement is optional metadata; adoption/selection commits above still throw.
        recoverRename(entry)
    }

    override fun readName(partition: HistoryPartition): AccountNameRead = try {
        if (consistent(partition)) storage.readName(partition) else AccountNameRead.Unavailable
    } catch (_: Exception) { AccountNameRead.Unavailable }

    private fun consistent(partition: HistoryPartition): Boolean {
        val entry = entry() ?: return false
        if (entry.partition != partition || catalog.snapshot().rename?.entry == entry.storage.id) return false
        val name = storage.readName(partition)
        if (!entry.nameAvailable) return name is AccountNameRead.Unavailable
        return name is AccountNameRead.Ready && name.name?.text == entry.name
    }

    override fun writeName(partition: HistoryPartition, name: AccountDisplayName?): Boolean = try {
        val entry = entry()
        if (entry?.partition != partition || !recoverRename(entry)) false
        else if (!catalog.reserveRename(entry.storage.id, name)) false
        else recoverRename(entry)
    } catch (_: Exception) { false }

    private fun recoverRename(entry: CatalogEntry): Boolean {
        val pending = catalog.snapshot().rename ?: return true
        if (pending.entry != entry.storage.id) return true
        val partition = entry.partition ?: return false
        val name = pending.name?.let(AccountDisplayName::from)
        if (!storage.writeName(partition, name)) return false
        return try {
            catalog.finishRename(entry.storage.id)
            true
        } catch (_: Exception) { false }
    }

    override fun reserveDeletion(disposition: CleanupDisposition): HistoryDeletion {
        val id = address?.id ?: entry()?.storage?.id
        val ticket = storage.reserveDeletion()
        var settlement: (() -> Boolean)? = null
        var capturedDisposition = disposition
        return HistoryDeletion(catalogSettlement = { requireNotNull(settlement).invoke() },
            cleanupDisposition = { capturedDisposition }) {
            try {
                val receipt = catalog.beginDeletion(id, disposition)
                capturedDisposition = receipt.disposition
                settlement = catalog.deletionSettlement(receipt)
                ticket.complete()
            } catch (_: Exception) { HistoryDeleteOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE) }
        }
    }
}
