package io.github.leugenea.codexbarmobile.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CredentialKeyLost : Exception()

/** No key bytes, raw platform errors, or secret-bearing exception chains escape the adapter. */
internal interface CredentialCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray, existingFile: Boolean): SealedCredential
    fun decrypt(sealed: SealedCredential, aad: ByteArray): ByteArray
    fun deleteKey()
}

internal class AndroidCredentialCipher(private val alias: String) : CredentialCipher {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(allowCreate: Boolean): SecretKey {
        val store = store()
        if (store.containsAlias(alias)) return store.getKey(alias, null) as? SecretKey ?: throw CredentialKeyLost()
        if (!allowCreate) throw CredentialKeyLost()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray, existingFile: Boolean): SealedCredential {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // No caller-supplied encryption IV: the platform generates a fresh randomized IV.
        cipher.init(Cipher.ENCRYPT_MODE, key(!existingFile))
        cipher.updateAAD(aad)
        return SealedCredential(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(sealed: SealedCredential, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    override fun deleteKey() { store().deleteEntry(alias) }
}
