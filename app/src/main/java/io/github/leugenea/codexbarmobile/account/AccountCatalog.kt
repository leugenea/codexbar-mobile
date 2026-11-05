package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.HistoryPartition
import java.io.File
import java.util.UUID

/** Local addressing only; neither a provider identity nor a runtime capability. */
internal data class AccountStorage(val id: UUID, val credentialSlot: UUID, val legacy: Boolean = false) {
    fun historyDirectory(root: File) = File(root, if (legacy) "usage-history" else "usage-history-$id")
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
internal data class CatalogSnapshot(val legacySlot: UUID, val entries: List<CatalogEntry> = emptyList(),
    val selected: UUID? = null, val rename: CatalogRename? = null, val deleting: UUID? = null) {
    override fun toString() = "CatalogSnapshot(redacted)"
}

/** One process-owner storage lane. A rename intent reserves both keys until sidecar settlement. */
internal class AccountCatalog private constructor(private val file: CatalogFile, initial: CatalogSnapshot) {
    private var state = initial
    @Synchronized fun snapshot() = state.copy(entries = state.entries.toList())

    @Synchronized fun adopt(partition: HistoryPartition?, name: AccountNameRead): CatalogEntry {
        check(state.deleting == null)
        val previous = legacyEntry()
        if (previous != null) {
            check(partition == null || previous.partition == partition)
            return previous
        }
        val ready = name as? AccountNameRead.Ready
        val entry = CatalogEntry(AccountStorage(UUID.randomUUID(), state.legacySlot, true), partition,
            ready?.name?.text, ready != null)
        check(insert(entry))
        return entry
    }

    /** Internal storage enrollment only; does not construct or activate another account runtime. */
    @Synchronized fun insert(entry: CatalogEntry): Boolean {
        if (state.entries.any { it.storage.id == entry.storage.id || it.storage.credentialSlot == entry.storage.credentialSlot }) return false
        if (conflicts(entry.storage.id, entry.type, entry.name)) return false
        check(entry.name == null || AccountDisplayName.from(entry.name)?.text == entry.name)
        check(!entry.storage.legacy || legacyEntry() == null)
        val selected = if (entry.storage.legacy && state.selected == null) entry.storage.id else state.selected
        commit(state.copy(entries = state.entries + entry, selected = selected))
        return true
    }

    @Synchronized fun legacyEntry() = state.entries.singleOrNull { it.storage.legacy }

    @Synchronized fun reserveRename(id: UUID, name: AccountDisplayName?): Boolean {
        if (state.deleting != null) return false
        val entry = state.entries.singleOrNull { it.storage.id == id } ?: return false
        if (state.rename != null || conflicts(id, entry.type, name?.text)) return false
        commit(state.copy(rename = CatalogRename(id, name?.text)))
        return true
    }

    @Synchronized fun finishRename(id: UUID) {
        val pending = requireNotNull(state.rename)
        check(pending.entry == id)
        val entries = state.entries.map { if (it.storage.id == id) it.copy(name = pending.name, nameAvailable = true) else it }
        commit(state.copy(entries = entries, rename = null))
    }

    @Synchronized fun beginDeletion() {
        if (state.deleting == null) commit(state.copy(deleting = UUID.randomUUID()))
    }

    /** Called only after the process owner's combined credential/history deletion barrier. */
    @Synchronized fun completeDeletion(receipt: UUID?): Boolean = try {
        if (state.deleting != receipt) false else {
            val id = legacyEntry()?.storage?.id
            if (receipt != null) commit(state.copy(entries = state.entries.filterNot { it.storage.legacy },
                selected = state.selected.takeUnless { it == id },
                rename = state.rename.takeUnless { it?.entry == id }, deleting = null))
            true
        }
    } catch (_: Exception) { false }

    private fun conflicts(id: UUID, type: AccountType, name: String?): Boolean {
        if (name == null) return false // Unnamed legacy/candidate state has no user key yet.
        if (state.entries.any { it.storage.id != id && it.type == type && it.name == name }) return true
        val pending = state.rename ?: return false
        val owner = state.entries.single { it.storage.id == pending.entry }
        return pending.entry != id && owner.type == type && pending.name == name
    }

    private fun commit(next: CatalogSnapshot) {
        // Re-read after an uncertain rename/fsync outcome; never overwrite it from stale memory.
        try { file.write(next); state = next }
        catch (failure: Exception) { state = file.read(); throw failure }
    }

    companion object {
        fun open(root: File, legacySession: () -> UUID, beforeRename: () -> Unit = {}): AccountCatalog {
            val file = CatalogFile(File(root, "account-catalog"), beforeRename)
            val initial = if (file.exists()) file.read() else CatalogSnapshot(legacySession()).also(file::write)
            return AccountCatalog(file, initial)
        }
    }
}
