package io.github.leugenea.codexbarmobile.credentials

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.R
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Synthetic fixtures with the REAL AndroidKeyStore. No Activity/process-death claim. */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = UUID.randomUUID()
    private val target = KeystoreCredentialStore.file(context, session)
    private val alias = KeystoreCredentialStore.alias(context, session)
    private val store = KeystoreCredentialStore(context, session)

    @After fun cleanup() {
        try { keys().deleteEntry(alias) }
        finally { target.parentFile?.deleteRecursively() }
    }

    @Test fun realKeystoreRoundTripFreshInstanceAndRandomizedIv() {
        val generation = store.openSession()
        success(store.replace(envelope(generation), CredentialCancellation()))
        val first = CredentialBinaryFormat.sealed(target.readBytes(), session)
        val restored = success(KeystoreCredentialStore(context, session).read(generation))
        assertTokens(restored)
        assertSame(generation, restored.generation)
        assertSame(AccountWorkspaceBinding.Unresolved, restored.accountWorkspace)
        // Independent adapter/kernel models durable readback, NOT real process termination.
        val fresh = owner()
        assertTokens(success(fresh.read(fresh.openSession())))
        success(store.replace(envelope(generation), CredentialCancellation()))
        val second = CredentialBinaryFormat.sealed(target.readBytes(), session)
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        val key = keys().getKey(alias, null)
        assertEquals("AES", key.algorithm)
        assertNull(key.encoded)
        assertEquals("AES/GCM/NoPadding", javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").algorithm)
    }

    @Test fun missingKeystoreKeyFailsClosedWithoutRecreationOverCiphertext() {
        val generation = store.openSession()
        success(store.replace(envelope(generation), CredentialCancellation()))
        val bytes = target.readBytes()
        keys().deleteEntry(alias)
        failure(CredentialFailure.KEY_LOST, store.read(generation))
        failure(CredentialFailure.KEY_LOST, store.replace(envelope(generation), CredentialCancellation()))
        val restored = owner()
        val restoredGeneration = restored.openSession()
        failure(CredentialFailure.KEY_LOST, restored.read(restoredGeneration))
        failure(CredentialFailure.KEY_LOST, restored.replace(envelope(restoredGeneration), CredentialCancellation()))
        assertFalse(keys().containsAlias(alias))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun realKeystoreRejectsCiphertextIvMetadataAndCrossSessionSubstitution() {
        val generation = store.openSession()
        success(store.replace(envelope(generation), CredentialCancellation()))
        val original = target.readBytes()
        // Header version/provider/pending binding/local generation, IV and GCM tag.
        for (offset in listOf(7, 8, 9, 27, 28, original.lastIndex)) {
            target.writeBytes(original.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() })
            failure(CredentialFailure.CORRUPT, store.read(generation))
        }
        for (invalid in listOf(byteArrayOf(0), original + byteArrayOf(0), ByteArray(CredentialBinaryFormat.MAX_FILE + 1))) {
            target.writeBytes(invalid)
            failure(CredentialFailure.CORRUPT, store.read(generation))
        }
        target.writeBytes(original)
        val other = UUID.randomUUID()
        val foreign = ProtectedCredentialPersistence(AtomicCredentialFile(target), AndroidCredentialCipher(alias), other)
        val owner = CredentialSlotOwner(foreign)
        failure(CredentialFailure.CORRUPT, owner.read(owner.openSession()))
        // A changed but structurally valid authenticated binding also fails GCM authentication.
        val alteredHeader = CredentialBinaryFormat.header(other)
        val sealed = CredentialBinaryFormat.sealed(original, session)
        try {
            AndroidCredentialCipher(alias).decrypt(sealed, alteredHeader)
            fail("Expected authenticated local-session rejection")
        } catch (_: javax.crypto.AEADBadTagException) { }
        assertTokens(success(store.read(generation)))
    }

    @Test fun injectedAtomicFileFailurePreservesCommittedCredentials() {
        var fail = false
        val owner = owner { if (fail) throw IOException() }
        val generation = owner.openSession()
        success(owner.replace(envelope(generation), CredentialCancellation()))
        val bytes = target.readBytes()
        fail = true
        failure(CredentialFailure.FAILED_WRITE, owner.replace(envelope(generation, 9), CredentialCancellation()))
        assertArrayEquals(bytes, target.readBytes())
        assertTokens(success(owner.read(generation)))
        assertFalse(target.parentFile!!.list()!!.any { it.startsWith("credential-") })
    }

    @Test fun localLogoutDeletesOwnedKeystoreKeyAndCiphertextAndInvalidatesOwner() {
        val generation = store.openSession()
        success(store.replace(envelope(generation), CredentialCancellation()))
        assertTrue(keys().containsAlias(alias))
        assertTrue(target.exists())
        success(store.delete(generation))
        assertFalse(keys().containsAlias(alias))
        assertFalse(target.exists())
        failure(CredentialFailure.STALE_GENERATION, store.read(generation))
        failure(CredentialFailure.STALE_GENERATION, store.replace(envelope(generation), CredentialCancellation()))
        val fresh = owner()
        failure(CredentialFailure.MISSING, fresh.read(fresh.openSession()))
    }

    @Test fun ciphertextUsesNoBackupDirectoryAndBothBackupFormatsExcludeAllAppData() {
        val generation = store.openSession()
        success(store.replace(envelope(generation), CredentialCancellation()))
        assertTrue(target.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/"))
        assertFalse(context.filesDir.canonicalPath == target.parentFile!!.canonicalPath)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(setOf("full-backup-content"), exclusions(R.xml.backup_rules).keys)
        assertEquals(setOf("cloud-backup", "device-transfer"), exclusions(R.xml.data_extraction_rules).keys)
    }

    private fun exclusions(resource: Int): Map<String, Set<String>> {
        val expected = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        val sections = mutableMapOf<String, MutableSet<String>>()
        context.resources.getXml(resource).use { parser ->
            var section = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "full-backup-content", "cloud-backup", "device-transfer" -> {
                            section = parser.name
                            assertNull(sections.put(section, mutableSetOf()))
                        }
                        "data-extraction-rules" -> Unit
                        "exclude" -> {
                            assertEquals(".", parser.getAttributeValue(null, "path"))
                            val domain = parser.getAttributeValue(null, "domain")
                            assertTrue(sections.getValue(section).add(domain))
                        }
                        else -> fail("Unexpected backup rule")
                    }
                }
                parser.next()
            }
        }
        sections.values.forEach { assertEquals(expected, it) }
        return sections
    }

    private fun owner(beforeRename: () -> Unit = {}) = CredentialSlotOwner(ProtectedCredentialPersistence(
        AtomicCredentialFile(target, beforeRename), AndroidCredentialCipher(alias), session,
    ))
    private fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun envelope(generation: SessionGeneration, first: Byte = 1) = CredentialEnvelope(
        generation, SensitiveValue.copyOf(byteArrayOf(first, 2, 3)), SensitiveValue.copyOf(byteArrayOf(4, 5)),
    )
    private fun assertTokens(envelope: CredentialEnvelope) {
        assertArrayEquals(byteArrayOf(1, 2, 3), envelope.accessToken.copyBytes())
        assertArrayEquals(byteArrayOf(4, 5), envelope.refreshToken!!.copyBytes())
    }
    private fun failure(category: CredentialFailure, result: CredentialResult<*>) { assertEquals(CredentialResult.Failure(category), result) }
    private fun <T> success(result: CredentialResult<T>): T { assertTrue(result is CredentialResult.Success); return (result as CredentialResult.Success).value }
}
