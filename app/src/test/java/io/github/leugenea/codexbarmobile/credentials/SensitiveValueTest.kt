package io.github.leugenea.codexbarmobile.credentials

import org.junit.Assert.*
import org.junit.Test

class SensitiveValueTest {
    @Test fun copiesCannotMutateSensitiveValueAndIdentityEqualityDoesNotCompareSecrets() {
        val marker = "synthetic-sensitive-marker"
        val bytes = marker.toByteArray()
        val value = SensitiveValue.copyOf(bytes)
        bytes.fill(0)
        val output = value.copyBytes()
        output.fill(0)
        assertEquals(marker, String(value.copyBytes()))
        assertTrue(value == value)
        assertFalse(value.equals(null))
        assertFalse(value.equals(marker))
        val sameContents = SensitiveValue.copyOf(marker.toByteArray())
        val otherContents = SensitiveValue.copyOf("synthetic-other-marker".toByteArray())
        assertFalse(value == sameContents)
        assertFalse(value == otherContents)
        assertEquals(0, value.hashCode())
        assertEquals(value.hashCode(), otherContents.hashCode())
        val mismatch = assertThrows(AssertionError::class.java) { assertEquals(value, sameContents) }
        assertFalse(mismatch.message.orEmpty().contains(marker))
        assertEquals("SensitiveValue(redacted)", "$value")
        assertFalse(listOf(value, otherContents).toString().contains(marker))
    }

    @Test fun envelopeResultsAndLocalBindingsHaveNoProviderIdentityOrSecretDiagnostics() {
        val store = SerializedCredentialStore(FakeCredentialPersistence())
        val generation = store.openSession()
        val envelope = syntheticEnvelope(generation)
        assertSame(generation, envelope.generation)
        assertSame(AccountWorkspaceBinding.Unresolved, envelope.accountWorkspace)
        assertNotNull(envelope.accessToken)
        assertNotNull(envelope.refreshToken)
        val success = CredentialResult.Success(envelope)
        assertSame(envelope, success.value)
        assertEquals("CredentialResult.Success(redacted)", "$success")
        assertEquals("CredentialEnvelope(redacted, accountWorkspace=unresolved)", "$envelope")
        assertEquals("SessionGeneration(local-only)", "$generation")
        assertEquals("LocalCredentialNamespace(local-only)", "${generation.namespace}")
        assertEquals("Unresolved", "${envelope.accountWorkspace}")
        val next = store.openSession()
        assertNotSame(generation, next)
        assertSame(generation.namespace, next.namespace)
        val foreign = SerializedCredentialStore(FakeCredentialPersistence()).openSession()
        assertNotSame(generation.namespace, foreign.namespace)
    }

    @Test fun categoricalFailuresCannotCarryMessagesOrUnsafeThrowableChains() {
        CredentialFailure.entries.forEach { category ->
            val result = CredentialResult.Failure(category)
            assertEquals(category, result.category)
            assertEquals(result, CredentialResult.Failure(category))
            assertEquals(result.hashCode(), CredentialResult.Failure(category).hashCode())
            assertFalse(result.toString().contains("synthetic-sensitive-marker"))
            assertFalse(Throwable::class.java.isAssignableFrom(result.javaClass))
            val fields = result.javaClass.declaredFields.filterNot {
                it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            assertEquals(listOf(CredentialFailure::class.java), fields.map { it.type })
        }
        assertNotEquals(CredentialResult.Failure(CredentialFailure.MISSING), CredentialResult.Failure(CredentialFailure.CORRUPT))
    }
}
