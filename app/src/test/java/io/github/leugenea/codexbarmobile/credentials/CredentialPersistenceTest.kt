package io.github.leugenea.codexbarmobile.credentials

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = UUID(1, 2)

    @Test fun binaryRoundTripsBothAbsentAndPresentRefreshWithoutDisclosingSecrets() {
        val generation = SerializedCredentialStore(FakeCredentialPersistence()).openSession()
        for (refresh in listOf(null, SensitiveValue.copyOf(byteArrayOf(4, 5)))) {
            val original = CredentialEnvelope(generation, SensitiveValue.copyOf(byteArrayOf(1, 2, 3)), refresh)
            val decoded = CredentialBinaryFormat.envelope(CredentialBinaryFormat.payload(original), generation)
            assertArrayEquals(byteArrayOf(1, 2, 3), decoded.accessToken.copyBytes())
            if (refresh == null) assertNull(decoded.refreshToken)
            else assertArrayEquals(refresh.copyBytes(), decoded.refreshToken!!.copyBytes())
            assertSame(generation, decoded.generation)
            assertSame(AccountWorkspaceBinding.Unresolved, decoded.accountWorkspace)
            assertFalse(decoded.toString().contains("1, 2, 3"))
        }
    }

    @Test fun parserRejectsEveryTruncationTrailingDataAndOverflowingLengths() {
        val generation = SerializedCredentialStore(FakeCredentialPersistence()).openSession()
        val payload = CredentialBinaryFormat.payload(envelope(generation))
        for (length in 0 until payload.size) rejected { CredentialBinaryFormat.envelope(payload.copyOf(length), generation) }
        rejected { CredentialBinaryFormat.envelope(payload + byteArrayOf(0), generation) }
        for (length in listOf(Int.MIN_VALUE, -2, 0, CredentialBinaryFormat.MAX_TOKEN + 1, Int.MAX_VALUE)) {
            val bad = payload.copyOf().also { ByteBuffer.wrap(it).putInt(length) }
            rejected { CredentialBinaryFormat.envelope(bad, generation) }
        }
        for (length in listOf(-2, 0, Int.MAX_VALUE)) {
            val bad = payload.copyOf().also { ByteBuffer.wrap(it).putInt(7, length) }
            rejected { CredentialBinaryFormat.envelope(bad, generation) }
        }
        rejected { CredentialBinaryFormat.payload(CredentialEnvelope(generation, SensitiveValue.copyOf(byteArrayOf()), null)) }
        rejected { CredentialBinaryFormat.payload(CredentialEnvelope(generation, SensitiveValue.copyOf(ByteArray(65537)), null)) }
        rejected { CredentialBinaryFormat.payload(CredentialEnvelope(generation, SensitiveValue.copyOf(byteArrayOf(1)), SensitiveValue.copyOf(byteArrayOf()))) }
    }

    @Test fun fileRejectsBadVersionProviderBindingSessionIvLengthAndTrailingBytes() {
        val record = SealedCredential(ByteArray(12), ByteArray(16))
        val valid = CredentialBinaryFormat.file(session, record)
        val decoded = CredentialBinaryFormat.sealed(valid, session)
        assertArrayEquals(record.iv, decoded.iv)
        assertArrayEquals(record.ciphertext, decoded.ciphertext)
        for (size in 0 until valid.size) rejected { CredentialBinaryFormat.sealed(valid.copyOf(size), session) }
        for (index in listOf(0, 7, 8, 9, 10, 27, 43)) {
            val bad = valid.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            rejected { CredentialBinaryFormat.sealed(bad, session) }
        }
        rejected { CredentialBinaryFormat.sealed(valid, UUID(3, 4)) }
        rejected { CredentialBinaryFormat.sealed(valid + byteArrayOf(0), session) }
        rejected { CredentialBinaryFormat.sealed(ByteArray(CredentialBinaryFormat.MAX_FILE + 1), session) }
        rejected { CredentialBinaryFormat.file(session, SealedCredential(ByteArray(11), ByteArray(16))) }
        rejected { CredentialBinaryFormat.file(session, SealedCredential(ByteArray(12), ByteArray(15))) }
        rejected { CredentialBinaryFormat.file(session, SealedCredential(ByteArray(12), ByteArray(CredentialBinaryFormat.MAX_FILE))) }
    }

    @Test fun failedAtomicReplacementPreservesEntirePreviousEnvelopeAndCleansStaging() {
        var fail = false
        val target = File(temporary.root, "slot/value.bin")
        val file = AtomicCredentialFile(target) { if (fail) throw IOException() }
        val owner = protectedStore(ProtectedCredentialPersistence(file, FakeCipher(), session))
        val generation = owner.openSession()
        success(owner.replace(envelope(generation), CredentialCancellation()))
        val before = target.readBytes()
        fail = true
        failure(CredentialFailure.FAILED_WRITE, owner.replace(envelope(generation, 8), CredentialCancellation()))
        assertArrayEquals(before, target.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), success(owner.read(generation)).accessToken.copyBytes())
        assertEquals(listOf("value.bin"), target.parentFile!!.list()!!.toList())
        val staged = file.stage(byteArrayOf(9))
        staged.discard(); staged.discard()
        failure(CredentialFailure.FAILED_WRITE, staged.commit())
        failure(CredentialFailure.FAILED_WRITE, staged.commit())
    }

    @Test fun keyLossTamperAndReadFailuresNeverRecreateAKeyOverExistingCiphertext() {
        val cipher = FakeCipher()
        val target = File(temporary.root, "slot.bin")
        val owner = protectedStore(ProtectedCredentialPersistence(AtomicCredentialFile(target), cipher, session))
        val generation = owner.openSession()
        failure(CredentialFailure.MISSING, owner.read(generation))
        success(owner.replace(envelope(generation), CredentialCancellation()))
        cipher.lost = true
        failure(CredentialFailure.KEY_LOST, owner.read(generation))
        failure(CredentialFailure.KEY_LOST, owner.replace(envelope(generation, 8), CredentialCancellation()))
        assertEquals(1, cipher.creations)
        cipher.lost = false; cipher.tampered = true
        failure(CredentialFailure.CORRUPT, owner.read(generation))
        target.writeBytes(byteArrayOf(0))
        failure(CredentialFailure.CORRUPT, owner.read(generation))
        target.writeBytes(ByteArray(CredentialBinaryFormat.MAX_FILE + 1))
        failure(CredentialFailure.CORRUPT, owner.read(generation))
    }

    @Test fun logoutInvalidatesBeforeAttemptingBothRemovalsEvenIfOneFails() {
        val cipher = FakeCipher().apply { failDelete = true }
        val target = File(temporary.root, "slot.bin")
        val persistence = ProtectedCredentialPersistence(AtomicCredentialFile(target), cipher, session)
        val owner = protectedStore(persistence)
        val generation = owner.openSession()
        success(owner.replace(envelope(generation), CredentialCancellation()))
        failure(CredentialFailure.FAILED_WRITE, owner.delete(generation))
        assertFalse(target.exists())
        failure(CredentialFailure.STALE_GENERATION, owner.read(generation))
        failure(CredentialFailure.STALE_GENERATION, persistence.read())
        failure(CredentialFailure.STALE_GENERATION, persistence.prepare(envelope(generation)))
    }

    @Test fun actualStagingRaceRejectsStaleGenerationAndNeverPublishesRotation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = File(temporary.root, "slot.bin")
        val actual = AtomicCredentialFile(target)
        var blocked = false
        val file = object : CredentialFile by actual {
            override fun stage(bytes: ByteArray): PreparedCredentialWrite {
                val staged = actual.stage(bytes)
                if (blocked) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return staged
            }
        }
        val owner = protectedStore(ProtectedCredentialPersistence(file, FakeCipher(), session))
        val generation = owner.openSession()
        success(owner.replace(envelope(generation), CredentialCancellation()))
        val before = target.readBytes()
        blocked = true
        val result = AtomicReference<CredentialResult<CredentialEnvelope>>()
        val worker = Thread { result.set(owner.replace(envelope(generation, 9), CredentialCancellation())) }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.openSession()
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        failure(CredentialFailure.STALE_GENERATION, result.get())
        assertArrayEquals(before, target.readBytes())
        assertEquals(listOf("slot.bin"), temporary.root.list()!!.toList())
    }

    @Test fun stageAndDeleteFileErrorsAreCategoricalAndFailedCommitCannotBeReused() {
        val target = File(temporary.root, "parent").apply { writeText("synthetic") }
        val bad = AtomicCredentialFile(File(target, "slot.bin"))
        val owner = protectedStore(ProtectedCredentialPersistence(bad, FakeCipher(), session))
        val generation = owner.openSession()
        failure(CredentialFailure.FAILED_WRITE, owner.replace(envelope(generation), CredentialCancellation()))
        val directory = File(temporary.root, "directory").apply { mkdir(); File(this, "child").writeText("synthetic") }
        rejected { AtomicCredentialFile(directory).delete() }
        val destination = File(temporary.root, "destination")
        val file = AtomicCredentialFile(destination)
        val staged = file.stage(byteArrayOf(1))
        destination.mkdir(); File(destination, "child").writeText("synthetic")
        failure(CredentialFailure.FAILED_WRITE, staged.commit())
        failure(CredentialFailure.FAILED_WRITE, staged.commit())
        staged.discard()
    }

    @Test fun rotationMarkerBlocksRestorationUntilWholeEnvelopeIsDurable() {
        val target = File(temporary.root, "slot/value.bin")
        val marker = File(target.parentFile, "rotation-pending")
        val persistence = ProtectedCredentialPersistence(AtomicCredentialFile(target), FakeCipher(), session, marker)
        val owner = protectedStore(persistence)
        val generation = owner.openSession()
        success(owner.replace(envelope(generation), CredentialCancellation()))
        success(owner.beginRotation(generation))
        assertTrue("Rotation does not invalidate its admitted runtime capability", owner.isActive(generation))
        assertTrue(marker.isFile)
        failure(CredentialFailure.CORRUPT, owner.read(generation))
        val restarted = owner.openSession()
        assertFalse(owner.isActive(generation))
        assertTrue(owner.isActive(restarted))
        failure(CredentialFailure.CORRUPT, owner.read(restarted))
        failure(CredentialFailure.STALE_GENERATION, owner.finishRotation(generation))
        assertTrue(marker.exists())
        success(owner.replace(envelope(restarted, 9), CredentialCancellation()))
        failure(CredentialFailure.CORRUPT, owner.read(restarted))
        success(owner.finishRotation(restarted))
        assertFalse(marker.exists())
        assertArrayEquals(byteArrayOf(9, 2, 3), success(owner.read(restarted)).accessToken.copyBytes())
    }

    @Test fun failedDeletionOfBothKeyAndCiphertextLeavesDurableRestorationTombstone() {
        val target = File(temporary.root, "slot/value.bin")
        val actual = AtomicCredentialFile(target)
        val file = object : CredentialFile by actual {
            override fun delete() { throw IOException() }
        }
        val cipher = FakeCipher().apply { failDelete = true }
        val marker = File(target.parentFile, "rotation-pending")
        val owner = protectedStore(ProtectedCredentialPersistence(file, cipher, session, marker))
        val generation = owner.openSession()
        success(owner.replace(envelope(generation), CredentialCancellation()))
        assertFalse(marker.exists())
        failure(CredentialFailure.FAILED_WRITE, owner.delete(generation))
        assertTrue(target.isFile)
        assertTrue(marker.isFile)
        val restarted = protectedStore(ProtectedCredentialPersistence(actual, cipher, session, marker))
        failure(CredentialFailure.CORRUPT, restarted.read(restarted.openSession()))
    }

    @Test fun rotationMarkAndClearFailuresAreCategoricalAndNeverClearAnotherOwner() {
        val target = File(temporary.root, "slot/value.bin")
        val marker = File(target.parentFile, "rotation-pending")
        val owner = protectedStore(ProtectedCredentialPersistence(AtomicCredentialFile(target), FakeCipher(), session, marker))
        val generation = owner.openSession()
        success(owner.replace(envelope(generation), CredentialCancellation()))
        val foreign = SerializedCredentialStore(FakeCredentialPersistence()).openSession()
        failure(CredentialFailure.STALE_GENERATION, owner.beginRotation(foreign))
        success(owner.beginRotation(generation))
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        File(marker, "synthetic").writeText("synthetic")
        failure(CredentialFailure.FAILED_WRITE, owner.finishRotation(generation))
        failure(CredentialFailure.CORRUPT, owner.beginRotation(generation))
        failure(CredentialFailure.FAILED_WRITE, owner.delete(generation))
        assertTrue(marker.exists())
    }

    private fun envelope(generation: SessionGeneration, first: Byte = 1) = CredentialEnvelope(
        generation, SensitiveValue.copyOf(byteArrayOf(first, 2, 3)), SensitiveValue.copyOf(byteArrayOf(4, 5)),
    )
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected strict rejection") } catch (_: IllegalArgumentException) {
        } catch (_: java.nio.BufferUnderflowException) { } catch (_: IOException) { }
    }
    private fun failure(category: CredentialFailure, result: CredentialResult<*>) { assertEquals(CredentialResult.Failure(category), result) }
    private fun <T> success(result: CredentialResult<T>): T { assertTrue(result is CredentialResult.Success); return (result as CredentialResult.Success).value }

    private class FakeCipher : CredentialCipher {
        var lost = false
        var tampered = false
        var failDelete = false
        var creations = 0
        override fun encrypt(plaintext: ByteArray, aad: ByteArray, existingFile: Boolean): SealedCredential {
            if (lost && existingFile) throw CredentialKeyLost()
            if (!existingFile) creations++
            return SealedCredential(ByteArray(12), plaintext.copyOf() + ByteArray(16))
        }
        override fun decrypt(sealed: SealedCredential, aad: ByteArray): ByteArray {
            if (lost) throw CredentialKeyLost()
            if (tampered) throw AEADBadTagException()
            return sealed.ciphertext.copyOf(sealed.ciphertext.size - 16)
        }
        override fun deleteKey() { if (failDelete) throw IOException(); lost = true }
    }
}
