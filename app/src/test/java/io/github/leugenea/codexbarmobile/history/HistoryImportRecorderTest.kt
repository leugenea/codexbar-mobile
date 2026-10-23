package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.EndpointObservation
import io.github.leugenea.codexbarmobile.credentials.LocalCredentialNamespace
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryImportRecorderTest {
    @Test fun importWaitsForPreImportNPlusOneThenQueuedRecordingUsesImportedHighWater() = runTest {
        for (importedCount in listOf(1, 5)) {
            val storage = ImportMemoryStorage().seed(2)
            val recorder = UsageHistoryRecorder(backgroundScope, StandardTestDispatcher(testScheduler))
            val session = SessionGeneration(LocalCredentialNamespace())
            recorder.foreground(true); recorder.bind(session, storage); runCurrent()
            storage.operations.clear()
            val text = ImportMemoryStorage().seed(importedCount).text()
            var replacement: Deferred<HistoryImportOutcome>? = null
            storage.afterRead = {
                // The worker has captured old N=2, but has not appended its admitted N+1 yet.
                replacement = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    recorder.importHistory(storage, text, SyntheticHistory.at.plusSeconds(10))
                }
                recorder.accept(recorder.capture(session), observation(20))
            }
            recorder.accept(recorder.capture(session), observation(10))
            runCurrent()
            assertEquals(HistoryImportOutcome.Imported, requireNotNull(replacement).await())
            runCurrent()
            assertEquals(listOf("append:3", "replace", "append:${importedCount + 1}"), storage.operations)
            assertEquals(importedCount + 1L, storage.state.cursor.lastOrdinal)
            assertEquals(0L, recorder.state.value.lostSamples)
            assertNull(recorder.state.value.problem)
            assertEquals(storage.state.cursor.lastOrdinal, recorder.state.value.storage!!.lastAdmitted!!.ordinal)
            recorder.retire()
        }
    }

    @Test fun revokedCapabilityRejectsImportWithoutChangingRowsOrContinuation() = runTest {
        val storage = ImportMemoryStorage().seed(2)
        val original = storage.text()
        val recorder = UsageHistoryRecorder(backgroundScope, StandardTestDispatcher(testScheduler))
        recorder.foreground(true)
        recorder.bind(SessionGeneration(LocalCredentialNamespace()), storage)
        runCurrent()
        storage.revoke()
        val outcome = recorder.importHistory(storage, ImportMemoryStorage().seed(1).text(), SyntheticHistory.at)
        assertEquals(HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), outcome)
        assertEquals(original, storage.text())
    }

    private fun observation(seconds: Long) = EndpointObservation(ReadOperation.USAGE,
        usage = SyntheticHistory.usage(observedAt = SyntheticHistory.at.plusSeconds(seconds)), receivedAtMillis = seconds * 1000)
}
