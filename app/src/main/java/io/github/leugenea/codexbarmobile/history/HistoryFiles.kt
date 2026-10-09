package io.github.leugenea.codexbarmobile.history

import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.UUID

internal enum class HistoryBindingPhase { EMPTY, ACTIVE, DELETING, STAGED }
internal data class HistoryBinding(val phase: HistoryBindingPhase, val partition: HistoryPartition)
internal class HistoryStorageException(val reason: HistoryUnavailable) : IOException()
internal class HistoryCorruption : IOException()

/** Fixed-sized durable privacy fence outside SQLite; never discarded by database recovery. */
internal class HistoryFiles(val directory: File, private val limits: HistoryStorageLimits) : AutoCloseable {
    val database = File(directory, "history.db")
    private val control = File(directory, "binding")
    private val pending = File(directory, "binding.pending")
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    fun acquire() {
        if (lock != null) { checkBudget(); return }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
        require(directory.canonicalFile == directory.absoluteFile)
        val opened = FileOutputStream(File(directory, "lane"), true).channel
        try {
            lock = opened.tryLock() ?: throw HistoryStorageException(HistoryUnavailable.IO_FAILURE)
            channel = opened
        } catch (failure: Exception) {
            opened.close()
            throw failure
        }
        checkBudget()
    }

    fun readBinding(): HistoryBinding {
        if (pending.exists()) throw HistoryCorruption()
        if (!control.exists()) {
            if (containsDatabaseArtifacts()) throw HistoryCorruption()
            val empty = HistoryBinding(HistoryBindingPhase.EMPTY, HistoryPartition(UUID(0, 0)))
            writeBinding(empty)
            return empty
        }
        if (control.length() != 32L) throw HistoryCorruption()
        val payload = ByteArray(32)
        DataInputStream(FileInputStream(control)).use { it.readFully(payload); if (it.read() != -1) throw HistoryCorruption() }
        val checksum = DataInputStream(java.io.ByteArrayInputStream(payload, 24, 8)).readLong()
        if (checksum != java.util.zip.CRC32().apply { update(payload, 0, 24) }.value) throw HistoryCorruption()
        val input = DataInputStream(java.io.ByteArrayInputStream(payload, 0, 24))
        return input.use {
            if (it.readInt() != 0x48423100) throw HistoryCorruption()
            val phase = it.readInt()
            if (phase !in HistoryBindingPhase.entries.indices) throw HistoryCorruption()
            HistoryBinding(HistoryBindingPhase.entries[phase], HistoryPartition(UUID(it.readLong(), it.readLong())))
        }
    }

    fun writeBinding(binding: HistoryBinding) {
        checkBudget(32)
        val payload = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(payload).apply {
            writeInt(0x48423100); writeInt(binding.phase.ordinal)
            writeLong(binding.partition.value.mostSignificantBits); writeLong(binding.partition.value.leastSignificantBits)
        }
        FileOutputStream(pending).use { stream ->
            stream.write(payload.toByteArray())
            java.io.DataOutputStream(stream).apply {
                writeLong(java.util.zip.CRC32().apply { update(payload.toByteArray()) }.value); flush()
            }
            stream.fd.sync()
        }
        Os.rename(pending.path, control.path)
        syncDirectory()
    }

    fun discardDatabase() {
        databaseArtifacts.forEach { name ->
            val file = File(directory, name)
            if (file.exists() && !file.delete()) throw IOException()
        }
        if (pending.exists() && !pending.delete()) throw IOException()
        syncDirectory()
        if (databaseArtifacts.any { File(directory, it).exists() }) throw IOException()
    }

    fun containsDatabaseArtifacts(): Boolean = databaseArtifacts.any { File(directory, it).exists() }

    fun checkBudget(extra: Long = 0) {
        val files = directory.listFiles() ?: throw IOException()
        if (files.any { !it.isFile || it.canonicalFile != it.absoluteFile }) throw HistoryCorruption()
        if (files.any { it.name !in allowedArtifacts }) throw HistoryCorruption()
        if (files.sumOf { it.length() } + extra > limits.bytes) throw HistoryStorageException(HistoryUnavailable.STORAGE_FULL)
    }

    private fun syncDirectory() {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    override fun close() {
        try { lock?.release() } finally { lock = null; channel?.close(); channel = null }
    }

    companion object {
        private val databaseArtifacts = setOf("history.db", "history.db-journal", "history.db-wal", "history.db-shm")
        private val allowedArtifacts = databaseArtifacts + setOf("binding", "binding.pending", "lane")
    }
}
