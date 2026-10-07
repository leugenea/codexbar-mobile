package io.github.leugenea.codexbarmobile.credentials

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal interface CredentialFile {
    fun exists(): Boolean
    fun read(): ByteArray
    fun stage(bytes: ByteArray): PreparedCredentialWrite
    fun delete()
}

/** Same-directory rename after checked fsync; unlike AtomicFile, failures are not just logged. */
internal class AtomicCredentialFile(
    internal val target: File,
    private val beforeRename: () -> Unit = {},
) : CredentialFile {
    override fun exists(): Boolean = target.exists()

    override fun read(): ByteArray = target.inputStream().use { input ->
        val bytes = ByteArray(CredentialBinaryFormat.MAX_FILE + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        require(size <= CredentialBinaryFormat.MAX_FILE)
        bytes.copyOf(size)
    }

    override fun stage(bytes: ByteArray): PreparedCredentialWrite {
        val directory = target.parentFile!!
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
        val temporary = File.createTempFile("credential-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
        return Replacement(temporary)
    }

    private inner class Replacement(private val temporary: File) : PreparedCredentialWrite {
        private var consumed = false
        override fun commit(): CredentialResult<Unit> {
            if (consumed) return CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
            consumed = true
            return try {
                beforeRename()
                if (!temporary.renameTo(target)) throw IOException()
                CredentialResult.Success(Unit)
            } catch (_: Exception) {
                CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
            }
        }
        override fun discard() { consumed = true; temporary.delete() }
    }

    override fun delete() {
        if (target.exists() && !target.delete()) throw IOException()
    }
}
