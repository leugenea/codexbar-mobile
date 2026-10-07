package io.github.leugenea.codexbarmobile.credentials

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CredentialStoreContractTest {
    private val fake = FakeCredentialPersistence()
    private val store = SerializedCredentialStore(fake)
    private val generation = store.openSession()
    private val original = syntheticEnvelope(generation)

    @Test fun missingCorruptAndKeyLostReadsAreDistinctAndFailClosed() {
        failure(CredentialFailure.MISSING, store.read(generation))
        listOf(CredentialFailure.CORRUPT, CredentialFailure.KEY_LOST, CredentialFailure.CANCELLED).forEach {
            fake.durable = original
            fake.readFailure = it
            failure(it, store.read(generation))
        }
    }

    @Test fun replacementIsPublishedOnlyAfterDurableWholeEnvelopeCommit() {
        installOriginal()
        val rotated = syntheticEnvelope(generation, "rotated")
        gatedReplacement(rotated) { worker, _ ->
            assertFalse(worker.isDone)
            assertSame(original, stored())
            assertSame(original, fake.durable)
            assertEquals(0, fake.commitCount - 1)
            fake.beforeCommit = {
                assertSame(original, fake.durable)
                assertFalse(worker.isDone)
            }
        }
        assertSame(rotated, stored())
        assertSame(rotated, fake.durable)
        assertEquals(2, fake.commitCount)
        assertEquals(2, fake.discardCount)
        assertNotSame(original.accessToken, stored().accessToken)
        assertNotSame(original.refreshToken, stored().refreshToken)
    }

    @Test fun failedRotationDoesNotPublishReplacementOrPartiallyReplaceTokens() {
        installOriginal()
        fake.commitFailure = CredentialFailure.FAILED_WRITE
        failure(CredentialFailure.FAILED_WRITE, replace(syntheticEnvelope(generation, "failed")))
        assertSame(original, fake.durable)
        assertSame(original, stored())
        assertEquals(2, fake.discardCount)
        // A10 must separately quarantine a possibly provider-consumed old refresh token.
    }

    @Test fun absentRefreshTokenReplacesEntireEnvelopeRatherThanRetainingOldRefreshToken() {
        installOriginal()
        val replacement = syntheticEnvelope(generation, "access-only", refresh = false)
        assertSame(replacement, success(replace(replacement)))
        assertNull(stored().refreshToken)
        assertSame(replacement.accessToken, stored().accessToken)
    }

    @Test fun deletionWinsAgainstUncooperativeStagingAndLateWriterCannotRecreateSession() {
        installOriginal()
        gatedReplacement(syntheticEnvelope(generation, "late"), expected = CredentialFailure.STALE_GENERATION) { _, _ ->
            assertTrue(store.delete(generation) is CredentialResult.Success)
            assertNull(fake.durable)
            failure(CredentialFailure.STALE_GENERATION, store.read(generation))
        }
        assertNull(fake.durable)
        assertEquals(1, fake.commitCount)
        assertEquals(1, fake.deleteCount)
        failure(CredentialFailure.STALE_GENERATION, replace(original))
        failure(CredentialFailure.STALE_GENERATION, store.delete(generation))
        assertEquals(2, fake.prepareCount)
    }

    @Test fun failedAndCancelledDeletesInvalidateOwnerEvenWhenOldDurableBytesRemain() {
        listOf(CredentialFailure.FAILED_WRITE, CredentialFailure.CANCELLED, CredentialFailure.KEY_LOST).forEach { category ->
            val owner = store.openSession()
            val envelope = syntheticEnvelope(owner, "delete-failure")
            fake.durable = envelope
            fake.deleteFailure = category
            failure(category, store.delete(owner))
            assertSame(envelope, fake.durable)
            failure(CredentialFailure.STALE_GENERATION, store.read(owner))
            failure(CredentialFailure.STALE_GENERATION, replace(envelope))
            failure(CredentialFailure.STALE_GENERATION, store.read(store.openSession()))
        }
        assertEquals(0, fake.prepareCount)
    }

    @Test fun failedDeletionAlsoRejectsAlreadyStagedLateWrite() {
        installOriginal()
        fake.deleteFailure = CredentialFailure.FAILED_WRITE
        gatedReplacement(syntheticEnvelope(generation, "late-after-failed-delete"), expected = CredentialFailure.STALE_GENERATION) { _, _ ->
            failure(CredentialFailure.FAILED_WRITE, store.delete(generation))
        }
        assertSame(original, fake.durable)
        assertEquals(1, fake.commitCount)
        failure(CredentialFailure.STALE_GENERATION, store.read(generation))
    }

    @Test fun sessionReplacementRejectsStagedOldCredentialsAndOldDeletionCannotEraseNewOwner() {
        installOriginal()
        lateinit var successor: SessionGeneration
        gatedReplacement(syntheticEnvelope(generation, "superseded"), expected = CredentialFailure.STALE_GENERATION) { _, _ ->
            successor = store.openSession()
            failure(CredentialFailure.STALE_GENERATION, store.read(successor))
            failure(CredentialFailure.STALE_GENERATION, store.read(generation))
        }
        val next = syntheticEnvelope(successor, "new-session")
        assertSame(next, success(replace(next)))
        failure(CredentialFailure.STALE_GENERATION, store.delete(generation))
        failure(CredentialFailure.STALE_GENERATION, replace(original))
        assertSame(next, success(store.read(successor)))
        assertSame(next, fake.durable)
        assertEquals(0, fake.deleteCount)
    }

    @Test fun foreignNamespaceCannotReadWriteOrDeleteAnotherStoresSlot() {
        installOriginal()
        val foreign = SerializedCredentialStore(FakeCredentialPersistence()).openSession()
        failure(CredentialFailure.STALE_GENERATION, store.read(foreign))
        failure(CredentialFailure.STALE_GENERATION, replace(syntheticEnvelope(foreign)))
        failure(CredentialFailure.STALE_GENERATION, store.delete(foreign))
        assertSame(original, fake.durable)
        assertEquals(1, fake.prepareCount)
        assertEquals(0, fake.deleteCount)
    }

    @Test fun cancellationBeforeStagingDoesNotWriteAndRepeatedCancellationIsSafe() {
        val cancellation = CredentialCancellation()
        cancellation.cancel()
        cancellation.cancel()
        failure(CredentialFailure.CANCELLED, store.replace(original, cancellation))
        assertEquals(0, fake.prepareCount)
        assertNull(fake.durable)
    }

    @Test fun cancellationDuringStagingDiscardsLateWriteAndPreservesCompletePriorEnvelope() {
        installOriginal()
        gatedReplacement(syntheticEnvelope(generation, "cancelled"), expected = CredentialFailure.CANCELLED) { _, cancellation ->
            cancellation.cancel()
            cancellation.cancel()
            assertSame(original, stored())
        }
        assertSame(original, fake.durable)
        assertEquals(1, fake.commitCount)
        assertEquals(2, fake.discardCount)
    }

    @Test fun cancellationAtFinalCommitAdmissionPreventsIrreversibleSideEffects() {
        val cancellation = CredentialCancellation()
        cancellation.cancel()
        var committed = false
        failure(CredentialFailure.CANCELLED, cancellation.commit { committed = true; CredentialResult.Success(Unit) })
        assertFalse(committed)
    }

    @Test fun commitAdmissionWinsCancellationAndDurableResultIsNotMisreportedAsCancelled() {
        val cancellation = CredentialCancellation()
        fake.beforeCommit = { cancellation.cancel() }
        assertSame(original, success(store.replace(original, cancellation)))
        cancellation.cancel()
        assertFalse(cancellation.isCancelled())
        assertSame(original, stored())
    }

    @Test fun stagingFailureCannotOverwriteCancellationOrReplacementDecision() {
        fake.prepareFailure = CredentialFailure.FAILED_WRITE
        gatedReplacement(original, expected = CredentialFailure.CANCELLED) { _, cancellation -> cancellation.cancel() }
        fake.stagingGate = null
        failure(CredentialFailure.FAILED_WRITE, replace(original))
        gatedReplacement(original, expected = CredentialFailure.STALE_GENERATION) { _, _ -> store.openSession() }
        assertNull(fake.durable)
        assertEquals(0, fake.commitCount)
    }

    @Test fun unsafeReadExceptionsAreSanitizedWithoutThrowableChains() {
        fake.readException = IllegalStateException("synthetic-sensitive-marker")
        failure(CredentialFailure.CORRUPT, store.read(generation))
        fake.readException = CancellationException("synthetic-sensitive-marker")
        failure(CredentialFailure.CANCELLED, store.read(generation))
    }

    @Test fun unsafeStagingAndCommitExceptionsNeverPublishOrExposeTheirMessages() {
        installOriginal()
        listOf(IllegalStateException("synthetic-sensitive-marker"), CancellationException("synthetic-sensitive-marker")).forEach { exception ->
            val expected = if (exception is CancellationException) CredentialFailure.CANCELLED else CredentialFailure.FAILED_WRITE
            fake.prepareException = exception
            failure(expected, replace(syntheticEnvelope(generation, "stage-exception")))
            fake.prepareException = null
            fake.commitException = exception
            failure(expected, replace(syntheticEnvelope(generation, "commit-exception")))
            assertSame(original, fake.durable)
        }
    }

    @Test fun unsafeDeletionExceptionStillInvalidatesSessionBeforeIo() {
        installOriginal()
        fake.beforeDelete = { failure(CredentialFailure.STALE_GENERATION, store.read(generation)) }
        fake.deleteException = IllegalStateException("synthetic-sensitive-marker")
        failure(CredentialFailure.FAILED_WRITE, store.delete(generation))
        failure(CredentialFailure.STALE_GENERATION, store.read(generation))
        assertSame(original, fake.durable)
    }

    @Test fun cleanupExceptionDoesNotUndoDurableSuccessOrLeakUnsafeDiagnostics() {
        fake.discardException = IllegalStateException("synthetic-sensitive-marker")
        assertSame(original, success(replace(original)))
        assertSame(original, stored())
        assertEquals(1, fake.discardCount)
    }

    private fun installOriginal() {
        assertSame(original, success(replace(original)))
    }

    private fun replace(envelope: CredentialEnvelope): CredentialResult<CredentialEnvelope> =
        store.replace(envelope, CredentialCancellation())

    private fun stored(): CredentialEnvelope = success(store.read(generation))

    private fun <T> success(result: CredentialResult<T>): T {
        assertTrue("Expected success, got $result", result is CredentialResult.Success)
        return (result as CredentialResult.Success).value
    }

    private fun failure(expected: CredentialFailure, result: CredentialResult<*>) {
        assertEquals(CredentialResult.Failure(expected), result)
        assertFalse(result.toString().contains("synthetic-sensitive-marker"))
    }

    private fun gatedReplacement(
        envelope: CredentialEnvelope,
        expected: CredentialFailure? = null,
        whileStaged: (ControlledWorker<CredentialResult<CredentialEnvelope>>, CredentialCancellation) -> Unit,
    ) {
        val gate = ControlledGate()
        val cancellation = CredentialCancellation()
        fake.stagingGate = gate
        ControlledWorker { store.replace(envelope, cancellation) }.use { worker ->
            try {
                gate.awaitEntered()
                whileStaged(worker, cancellation)
            } finally {
                gate.release()
            }
            val result = worker.result()
            if (expected == null) assertSame(envelope, success(result)) else failure(expected, result)
        }
    }
}
