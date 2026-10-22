package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.HistoryImportTarget
import io.github.leugenea.codexbarmobile.credentials.LocalCredentialNamespace
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryImportControllerTest {
    @Test fun cancelledActivityWaiterKeepsProcessImportBusyResultAndRefreshedPresentation() = runTest {
        val memory = ImportMemoryStorage().seed(2)
        val journal = LifetimeJournal()
        val storage = object : HistoryLifetimeStorage by journal.storage() {
            override fun restore(): HistoryLifetimeOutcome = HistoryLifetimeOutcome.Bound(memory)
        }
        val fixture = RecordingFixture(this, lifetimeStorage = storage)
        try {
            fixture.visible(true)
            val ticket = backgroundScope.async { fixture.owner.historyImportTarget() }
            runCurrent()
            val target = requireNotNull(ticket.await())
            val text = ImportMemoryStorage().seed(5).text()
            fixture.storage.held = true
            val waiter = backgroundScope.async { fixture.owner.importHistory(target, text, SyntheticHistory.at) }
            runCurrent()
            assertTrue("Process work reached the held storage dispatcher", fixture.storage.pending > 0)
            assertTrue(fixture.owner.historyImporterState.importing.value)
            waiter.cancel(); runCurrent()
            assertTrue(fixture.owner.historyImporterState.importing.value)
            assertNull(fixture.owner.historyImporterState.result.value)
            fixture.storage.release(); runCurrent()
            assertFalse(fixture.owner.historyImporterState.importing.value)
            assertEquals(HistoryImportOutcome.Imported, fixture.owner.historyImporterState.result.value)
            assertEquals(5L, memory.state.cursor.lastOrdinal)
            assertEquals(ObservationId(5), fixture.snapshot.storage!!.lastAdmitted)
            assertEquals(0L, fixture.snapshot.lostSamples)
            val old = memory.text()
            val successor = HistoryImportTarget(SessionGeneration(LocalCredentialNamespace()), memory.partition)
            val rejected = backgroundScope.async { fixture.owner.importHistory(successor, text, SyntheticHistory.at) }
            runCurrent()
            assertEquals(HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), rejected.await())
            assertEquals(old, memory.text())
        } finally { fixture.finish() }
    }
}
