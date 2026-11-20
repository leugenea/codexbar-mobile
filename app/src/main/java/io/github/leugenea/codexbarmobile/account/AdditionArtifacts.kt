package io.github.leugenea.codexbarmobile.account

import android.content.Context
import io.github.leugenea.codexbarmobile.credentials.AndroidCredentialCipher
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.credentials.syncDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Physical cleanup is authorized only by the current catalog reservation, after runtime drain. */
internal class AdditionArtifacts(private val context: Context, private val root: File) {
    private fun slot(address: AccountStorage) = KeystoreCredentialStore.file(context, address.credentialSlot).parentFile!!

    fun sync(address: AccountStorage) {
        val credential = slot(address)
        syncDirectory(credential)
        syncDirectory(credential.parentFile!!)
        syncDirectory(address.historyDirectory(root))
        syncDirectory(root)
    }

    fun remove(address: AccountStorage): Boolean {
        val key = runCatching { AndroidCredentialCipher(KeystoreCredentialStore.alias(context, address.credentialSlot)).deleteKey() }
        val credential = runCatching { removeDirectory(slot(address)) }
        val history = runCatching { removeDirectory(address.historyDirectory(root)) }
        return key.isSuccess && credential.isSuccess && history.isSuccess
    }

    private fun removeDirectory(directory: File) {
        // Reject redirected directories and unexpected nested structures. Never traverse them.
        require(directory.canonicalFile == directory.absoluteFile)
        if (Files.exists(directory.toPath(), NOFOLLOW_LINKS)) {
            require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
            requireNotNull(directory.listFiles()).forEach {
                require(!Files.isDirectory(it.toPath(), NOFOLLOW_LINKS))
                Files.delete(it.toPath())
            }
            Files.delete(directory.toPath())
        }
        directory.parentFile?.takeIf(File::isDirectory)?.let(::syncDirectory)
        syncDirectory(root)
    }
}
