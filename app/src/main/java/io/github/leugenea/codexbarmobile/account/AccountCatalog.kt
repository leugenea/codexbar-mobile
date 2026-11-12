package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.HistoryPartition
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Local addressing only; neither a provider identity nor a runtime capability. */
internal data class AccountStorage(val id: UUID, val credentialSlot: UUID, val legacy: Boolean = false) {
    fun historyDirectory(root: File) = File(root.canonicalFile, if (legacy) "usage-history" else "usage-history-$id")
    companion object {
        fun fresh() = AccountStorage(UUID.randomUUID(), UUID.randomUUID())
    }
}

internal enum class AccountType { CODEX }
internal data class CatalogEntry(val storage: AccountStorage, val partition: HistoryPartition?,
    val name: String?, val nameAvailable: Boolean, val type: AccountType = AccountType.CODEX) {
    override fun toString() = "CatalogEntry(redacted)"
}
internal data class CatalogRename(val entry: UUID, val name: String?) {
    override fun toString() = "CatalogRename(redacted)"
}
internal enum class CleanupDisposition { REMOVE, RETAIN }
internal data class CatalogDeletion(val entry: UUID?, val operation: UUID,
    val disposition: CleanupDisposition = CleanupDisposition.REMOVE)
internal data class CatalogSnapshot(val legacySlot: UUID, val entries: List<CatalogEntry> = emptyList(),
    val selected: UUID? = null, val rename: CatalogRename? = null, val deletions: List<CatalogDeletion> = emptyList()) {
    override fun toString() = "CatalogSnapshot(redacted)"
}

/** One process-owner storage lane. A rename intent reserves both keys until sidecar settlement. */
internal class AccountCatalog private constructor(private val file: CatalogFile, initial: CatalogSnapshot) {
    private var state = initial
    private enum class Uncertainty { NONE, REQUIRED_IDENTITY, OPTIONAL_RENAME }
    private var uncertainty = Uncertainty.NONE
    private val published = MutableStateFlow(initial)
    val snapshots: StateFlow<CatalogSnapshot> = published
    @Synchronized fun snapshot() = state.copy(entries = state.entries.toList())

    @Synchronized fun adopt(partition: HistoryPartition?, name: AccountNameRead): CatalogEntry {
        confirmIdentity()
        check(state.deletions.none { it.entry == legacyEntry()?.storage?.id })
        val previous = legacyEntry()
        if (previous != null) return reconcile(previous, partition, name)
        val ready = name as? AccountNameRead.Ready
        val entry = CatalogEntry(AccountStorage(UUID.randomUUID(), state.legacySlot, true), partition,
            ready?.name?.text, ready != null)
        check(insert(entry))
        return entry
    }

    /** Independently fill unknown metadata; never rebind an established history partition. */
    private fun reconcile(previous: CatalogEntry, partition: HistoryPartition?, name: AccountNameRead): CatalogEntry {
        check(partition == null || previous.partition == null || previous.partition == partition)
        val entry = reconcileName(previous, name).copy(partition = previous.partition ?: partition)
        if (entry == previous) return previous
        confirmCommit()
        val confirmed = requireNotNull(this.entry(previous.storage.id))
        if (confirmed != previous) return reconcile(confirmed, partition, name)
        check(!conflicts(entry.storage.id, entry.type, entry.name))
        commit(state.copy(entries = state.entries.map { if (it.storage.id == entry.storage.id) entry else it }))
        return entry
    }

    private fun reconcileName(previous: CatalogEntry, name: AccountNameRead): CatalogEntry {
        // During rename only journal settlement may change the key, on either side of the sidecar write.
        if (previous.nameAvailable || state.rename?.entry == previous.storage.id) return previous
        val ready = name as? AccountNameRead.Ready ?: return previous
        return previous.copy(name = ready.name?.text, nameAvailable = true)
    }

    /** Internal storage enrollment only; does not construct or activate another account runtime. */
    @Synchronized fun insert(entry: CatalogEntry): Boolean {
        confirmCommit()
        if (state.entries.any { it.storage.id == entry.storage.id || it.storage.credentialSlot == entry.storage.credentialSlot }) return false
        if (conflicts(entry.storage.id, entry.type, entry.name)) return false
        check(entry.name == null || AccountDisplayName.from(entry.name)?.text == entry.name)
        check(!entry.storage.legacy || legacyEntry() == null)
        val selected = if (entry.storage.legacy && state.selected == null) entry.storage.id else state.selected
        commit(state.copy(entries = state.entries + entry, selected = selected))
        return true
    }

    @Synchronized fun entry(id: UUID) = state.entries.singleOrNull { it.storage.id == id }

    @Synchronized fun reconcile(id: UUID, partition: HistoryPartition?, name: AccountNameRead): CatalogEntry {
        confirmIdentity()
        check(state.deletions.none { it.entry == id })
        return reconcile(requireNotNull(entry(id)), partition, name)
    }

    @Synchronized fun legacyEntry() = state.entries.singleOrNull { it.storage.legacy }

    @Synchronized fun reserveRename(id: UUID, name: AccountDisplayName?): Boolean {
        confirmCommit()
        if (state.deletions.any { it.entry == id }) return false
        val entry = state.entries.singleOrNull { it.storage.id == id } ?: return false
        if (state.rename != null || conflicts(id, entry.type, name?.text)) return false
        commit(state.copy(rename = CatalogRename(id, name?.text)))
        return true
    }

    @Synchronized fun finishRename(id: UUID) {
        val pending = requireNotNull(state.rename)
        check(pending.entry == id)
        confirmCommit()
        if (state.rename == null) { check(entry(id)?.name == pending.name); return }
        check(state.rename == pending)
        val entries = state.entries.map { if (it.storage.id == id) it.copy(name = pending.name, nameAvailable = true) else it }
        commit(state.copy(entries = entries, rename = null))
    }

    @Synchronized fun beginDeletion(id: UUID? = legacyEntry()?.storage?.id,
        disposition: CleanupDisposition = CleanupDisposition.REMOVE): CatalogDeletion {
        confirmCommit()
        require(id == null || entry(id) != null)
        state.deletions.singleOrNull { it.entry == id }?.let { return it }
        val receipt = CatalogDeletion(id, UUID.randomUUID(), disposition)
        commit(state.copy(deletions = state.deletions + receipt))
        return receipt
    }

    /** Retained by one history ticket; only its own attempted removal can authorize a sync retry. */
    fun deletionSettlement(receipt: CatalogDeletion): () -> Boolean {
        var attempted = false
        var applied = false
        return {
            synchronized(this) {
                try { confirmCommit() } catch (_: Exception) { return@synchronized false }
                if (applied || (attempted && receipt !in state.deletions)) syncDeletion()
                else {
                    val pending = receipt in state.deletions
                    attempted = pending
                    val complete = completeDeletion(receipt)
                    // commit re-reads an uncertain replacement before returning failure.
                    applied = pending && receipt !in state.deletions
                    complete
                }
            }
        }
    }

    private fun syncDeletion(): Boolean = try { file.sync(); true } catch (_: Exception) { false }

    /** Called only with the adapter's captured receipt after BOTH deletion barriers. */
    @Synchronized fun completeDeletion(receipt: CatalogDeletion?): Boolean = try {
        confirmCommit()
        if (receipt == null) state.deletions.isEmpty()
        else if (receipt !in state.deletions) false
        else {
            val id = receipt.entry
            val entries = settledEntries(receipt)
            val selected = if (state.selected == id && receipt.disposition == CleanupDisposition.REMOVE)
                entries.firstOrNull()?.storage?.id else state.selected
            commit(state.copy(entries = entries,
                selected = selected,
                rename = state.rename.takeUnless { it?.entry == id },
                deletions = state.deletions - receipt))
            true
        }
    } catch (_: Exception) { false }

    private fun settledEntries(receipt: CatalogDeletion): List<CatalogEntry> =
        if (receipt.disposition == CleanupDisposition.REMOVE) state.entries.filterNot { it.storage.id == receipt.entry }
        else state.entries.map { if (it.storage.id == receipt.entry) it.copy(partition = null) else it }

    /** Exact local address only. An idempotent explicit selection still confirms durability. */
    @Synchronized fun select(id: UUID): Boolean {
        confirmCommit()
        if (entry(id) == null) return false
        if (state.selected != id) commit(state.copy(selected = id))
        return true
    }

    private fun conflicts(id: UUID, type: AccountType, name: String?): Boolean {
        if (name == null) return false // Unnamed legacy/candidate state has no user key yet.
        if (state.entries.any { it.storage.id != id && it.type == type && it.name == name }) return true
        val pending = state.rename ?: return false
        val owner = state.entries.single { it.storage.id == pending.entry }
        return pending.entry != id && owner.type == type && pending.name == name
    }

    private fun commit(next: CatalogSnapshot) {
        // Re-read after an uncertain rename/fsync outcome; never overwrite it from stale memory.
        check(uncertainty == Uncertainty.NONE)
        val settlingRename = state.rename != null && next.rename == null && state.deletions == next.deletions
        try { file.write(next); state = next; published.value = next }
        catch (failure: Exception) {
            uncertainty = if (settlingRename) Uncertainty.OPTIONAL_RENAME else Uncertainty.REQUIRED_IDENTITY
            try {
                val readback = file.read()
                if (!settlingRename) state = readback
            } catch (_: Exception) { /* Fenced until confirmed readback. */ }
            throw failure
        }
    }

    private fun confirmIdentity() {
        if (uncertainty != Uncertainty.OPTIONAL_RENAME) confirmCommit()
    }

    /** Never admit a write from stale RAM or publish an unsynced replacement. */
    private fun confirmCommit() {
        if (uncertainty == Uncertainty.NONE) return
        val confirmed = file.read()
        file.sync()
        state = confirmed
        uncertainty = Uncertainty.NONE
        published.value = confirmed
    }

    companion object {
        fun open(root: File, legacySession: () -> UUID, beforeDirectorySync: () -> Unit = {},
            beforeRename: () -> Unit = {}, beforeRead: () -> Unit = {}): AccountCatalog {
            // Resolve the application root alias, not a redirected catalog child.
            val file = CatalogFile(File(root.canonicalFile, "account-catalog"), beforeRename, beforeDirectorySync, beforeRead)
            val initial = if (file.exists()) file.read() else CatalogSnapshot(legacySession()).also(file::write)
            return AccountCatalog(file, initial)
        }
    }
}
