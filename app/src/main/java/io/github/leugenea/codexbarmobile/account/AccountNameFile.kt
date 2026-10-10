package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.HistoryPartition
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Bounded non-secret sidecar. The history storage lane owns admission, fsync and deletion. */
internal class AccountNameFile(directory: File) {
    private val target = File(directory, FILE_NAME)
    private val pending = File(directory, PENDING_NAME)

    fun read(partition: HistoryPartition): AccountNameRead {
        if (pending.exists()) return AccountNameRead.Unavailable
        if (!target.exists()) return AccountNameRead.Ready(null)
        if (target.length() !in 23L..MAX_BYTES.toLong()) return AccountNameRead.Unavailable
        return try {
            DataInputStream(target.inputStream()).use { decode(it, partition) }
        } catch (_: Exception) { AccountNameRead.Unavailable }
    }

    private fun decode(input: DataInputStream, partition: HistoryPartition): AccountNameRead {
        if (input.readInt() != MAGIC) return AccountNameRead.Unavailable
        if (UUID(input.readLong(), input.readLong()) != partition.value) return AccountNameRead.Unavailable
        val size = input.readUnsignedShort()
        if (size !in 1..320) return AccountNameRead.Unavailable
        val bytes = ByteArray(size).also(input::readFully)
        if (input.read() != -1) return AccountNameRead.Unavailable
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val name = AccountDisplayName.from(text)
        return if (name?.text == text) AccountNameRead.Ready(name) else AccountNameRead.Unavailable
    }

    fun write(partition: HistoryPartition, name: AccountDisplayName?) {
        if (name == null) { remove(target); remove(pending); return }
        FileOutputStream(pending).use { stream ->
            val bytes = name.text.toByteArray(Charsets.UTF_8)
            DataOutputStream(stream).apply {
                writeInt(MAGIC); writeLong(partition.value.mostSignificantBits); writeLong(partition.value.leastSignificantBits)
                writeShort(bytes.size); write(bytes); flush()
            }
            stream.fd.sync()
        }
        if (!pending.renameTo(target)) throw IOException()
    }

    private fun remove(file: File) { if (file.exists() && !file.delete()) throw IOException() }

    companion object {
        const val FILE_NAME = "account-name"
        const val PENDING_NAME = "account-name.pending"
        const val MAX_BYTES = 342
        private const val MAGIC = 0x414E3100
    }
}
