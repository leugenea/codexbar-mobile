package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.credentials.syncDirectory
import io.github.leugenea.codexbarmobile.history.HistoryPartition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32

/** Versioned non-secret metadata, beside (never inside) the strict history directory. */
internal class CatalogFile(private val directory: File, private val beforeRename: () -> Unit = {}) {
    private val target = File(directory, "catalog")
    fun exists() = target.exists()

    fun read(): CatalogSnapshot {
        val bytes = target.readBytes()
        require(bytes.size >= 8)
        val payload = bytes.copyOf(bytes.size - 8)
        val checksum = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 8, 8)).readLong()
        require(CRC32().apply { update(payload) }.value == checksum)
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val version = input.readInt()
            require(version == MAGIC || version == LEGACY_MAGIC)
            val slot = input.uuid()
            val selected = if (input.readBoolean()) input.uuid() else null
            val count = input.readInt().also { require(it >= 0) }
            val entries = mutableListOf<CatalogEntry>()
            repeat(count) { entries += input.entry() }
            val rename = if (input.readBoolean()) CatalogRename(input.uuid(), input.name()) else null
            val deletions = input.deletions(version, entries)
            require(input.read() == -1)
            CatalogSnapshot(slot, entries, selected, rename, deletions).also(::validate)
        }
    }

    fun write(state: CatalogSnapshot) {
        validate(state)
        check(directory.isDirectory || directory.mkdirs())
        require(directory.canonicalFile == directory.absoluteFile)
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            output.writeInt(MAGIC); output.uuid(state.legacySlot)
            output.writeBoolean(state.selected != null); state.selected?.let { output.uuid(it) }
            output.writeInt(state.entries.size)
            state.entries.forEach { output.entry(it) }
            output.writeBoolean(state.rename != null)
            state.rename?.let { output.uuid(it.entry); output.name(it.name) }
            output.writeInt(state.deletions.size)
            state.deletions.forEach { receipt ->
                output.writeBoolean(receipt.entry != null); receipt.entry?.let { output.uuid(it) }
                output.uuid(receipt.operation)
            }
        }
        val bytes = payload.toByteArray()
        val temporary = File.createTempFile("catalog-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                DataOutputStream(stream).apply { writeLong(CRC32().apply { update(bytes) }.value); flush() }
                stream.fd.sync()
            }
            beforeRename()
            check(temporary.renameTo(target))
            syncDirectory(directory)
            syncDirectory(directory.parentFile!!)
        } finally { temporary.delete() }
    }

    private fun validate(state: CatalogSnapshot) {
        require(state.entries.map { it.storage.id }.distinct().size == state.entries.size)
        require(state.entries.map { it.storage.credentialSlot }.distinct().size == state.entries.size)
        val keyed = state.entries.filter { it.name != null }
        require(keyed.map { it.type to it.name }.distinct().size == keyed.size)
        require(state.entries.count { it.storage.legacy } <= 1)
        state.entries.forEach { validateEntry(it, state.legacySlot) }
        require(state.selected == null || state.entries.any { it.storage.id == state.selected })
        state.rename?.let { validateRename(it, state.entries) }
        require(state.deletions.map { it.entry }.distinct().size == state.deletions.size)
        require(state.deletions.map { it.operation }.distinct().size == state.deletions.size)
        require(state.deletions.all { it.entry == null || state.entries.any { entry -> entry.storage.id == it.entry } })
    }

    private fun validateEntry(entry: CatalogEntry, slot: UUID) {
        require(!entry.storage.legacy || entry.storage.credentialSlot == slot)
        require(entry.nameAvailable || entry.name == null)
        require(entry.name == null || AccountDisplayName.from(entry.name)?.text == entry.name)
    }

    private fun validateRename(rename: CatalogRename, entries: List<CatalogEntry>) {
        val entry = entries.single { it.storage.id == rename.entry }
        require(rename.name == null || AccountDisplayName.from(rename.name)?.text == rename.name)
        require(rename.name == null || entries.none {
            it.storage.id != rename.entry && it.type == entry.type && it.name == rename.name
        })
    }

    private fun DataInputStream.deletions(version: Int, entries: List<CatalogEntry>): List<CatalogDeletion> {
        if (version == LEGACY_MAGIC) {
            if (!readBoolean()) return emptyList()
            return listOf(CatalogDeletion(entries.singleOrNull { it.storage.legacy }?.storage?.id, uuid()))
        }
        val count = readInt().also { require(it in 0..entries.size + 1) }
        return List(count) { CatalogDeletion(if (readBoolean()) uuid() else null, uuid()) }
    }

    private fun DataInputStream.entry(): CatalogEntry {
        val storage = AccountStorage(uuid(), uuid(), readBoolean())
        val partition = if (readBoolean()) HistoryPartition(uuid()) else null
        require(readUTF() == AccountType.CODEX.name)
        return CatalogEntry(storage, partition, name(), readBoolean())
    }
    private fun DataOutputStream.entry(entry: CatalogEntry) {
        uuid(entry.storage.id); uuid(entry.storage.credentialSlot); writeBoolean(entry.storage.legacy)
        writeBoolean(entry.partition != null); entry.partition?.let { uuid(it.value) }
        writeUTF(entry.type.name); name(entry.name); writeBoolean(entry.nameAvailable)
    }
    private fun DataInputStream.uuid() = UUID(readLong(), readLong())
    private fun DataOutputStream.uuid(value: UUID) { writeLong(value.mostSignificantBits); writeLong(value.leastSignificantBits) }
    private fun DataInputStream.name(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.name(value: String?) { writeBoolean(value != null); value?.let(::writeUTF) }

    companion object {
        private const val LEGACY_MAGIC = 0x41433100
        private const val MAGIC = 0x41433200
    }
}
