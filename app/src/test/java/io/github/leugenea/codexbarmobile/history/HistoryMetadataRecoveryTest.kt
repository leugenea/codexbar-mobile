package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.account.AccountDisplayName
import io.github.leugenea.codexbarmobile.auth.SyntheticAuth
import io.github.leugenea.codexbarmobile.credentials.ControlledGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Owner/lane interleavings; native catalog/Keystore persistence is covered separately. */
class HistoryMetadataRecoveryTest {
    @Test fun firstForegroundRetriesOnlyMetadataWithoutReplacingHealthyHistoryOrActivatingNetwork() = runBlocking {
        val journal = LifetimeJournal().apply { failNameRead = true }
        LifetimeFixture(journal).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val access = h.capability()
            val edit = h.owner.accountName.value!!.edit
            journal.failNameRead = false
            journal.name = AccountDisplayName.from("Synthetic recovered")
            h.owner.usageForeground(Any(), true)
            settle(h, "first foreground metadata completion")
            assertEquals(1, journal.metadataCalls)
            assertEquals(1, journal.restoreCalls)
            assertSame(access, h.capability())
            assertSame(edit, h.owner.accountName.value!!.edit)
            assertEquals("Synthetic recovered", h.owner.accountName.value!!.name!!.text)
            assertFalse(h.owner.accountName.value!!.storageFailed)
            assertTrue(h.fake.calls.isEmpty())
            assertTrue(journal.admissions.isEmpty())
        }
    }

    @Test fun foregroundRegisteredDuringInitialRestoreGetsItsMetadataContinuationWithoutNetworkActivation() = runBlocking {
        val gate = ControlledGate()
        val journal = LifetimeJournal().apply { failNameRead = true; restoreGate = gate }
        LifetimeFixture(journal).use { h ->
            try {
                gate.awaitEntered()
                h.owner.usageForeground(Any(), true)
                settleCommands(h, "foreground arrives during initial restore")
            } finally { gate.release() }
            h.phase(ConnectionPhase.RESTORED)
            settle(h, "initial restore foreground metadata continuation")
            assertEquals(1, journal.metadataCalls)
            assertEquals(1, journal.restoreCalls)
            assertTrue(h.owner.accountName.value!!.storageFailed)
            assertEquals(HistoryAvailability.AVAILABLE, h.owner.historyAvailability)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun heldMetadataRetryKeepsThePostReturnRecordingThroughItsSuccessorRebind() = runBlocking {
        val journal = LifetimeJournal().apply { failNameRead = true }
        LifetimeFixture(journal).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val access = h.capability()
            val edit = h.owner.accountName.value!!.edit
            val gate = ControlledGate().also { journal.metadataGate = it }
            val observer = Any()
            val inventory = CompletableDeferred<io.github.leugenea.codexbarmobile.auth.AuthFake.Call>()
            lateinit var recording: Deferred<BackgroundRefreshOutcome>
            try {
                h.owner.usageForeground(observer, true)
                gate.awaitEntered()
                h.owner.usageForeground(observer, true)
                settleCommands(h, "coalesced metadata admissions")
                assertEquals(1, journal.metadataCalls)
                assertSame(access, h.capability())
                assertTrue(h.fake.calls.isEmpty())
                h.owner.usageForeground(observer, false)
                settleCommands(h, "last observer leaves during held metadata")
                assertEquals(HistoryAvailability.UNAVAILABLE, h.owner.historyAvailability)
                h.owner.usageForeground(observer, true)
                settleCommands(h, "return joins held metadata before quota admission")
                assertNull(h.owner.historyCapability(edit!!.generation))
                h.fake.respond = { call ->
                    if (call.request.url.encodedPath.endsWith("/usage")) call.reply(SyntheticAuth.response(RecordingFixture.USAGE))
                    else inventory.complete(call)
                }
                h.owner.readUsage()
                val inventoryCall = bounded(h, "quota read admission while metadata held") { inventory.await() }
                recording = async { h.owner.refreshInBackground(BackgroundRefreshTarget(journal.partition)) }
                // The worker retains the original flight before awaiting the metadata continuation.
                bounded(h, "background joins the admitted quota flight") {
                    while (h.owner.backgroundRefreshInterests() != 1) yield()
                }
                inventoryCall.reply(SyntheticAuth.response("{}"))
                h.phase(ConnectionPhase.OBSERVED)
                assertTrue(journal.admissions.isEmpty())
                journal.failNameRead = false
            } finally { gate.release() }
            settle(h, "held metadata owner completion")
            val result = bounded(h, "original quota flight recording") { recording.await() } as BackgroundRefreshOutcome.Completed
            assertEquals(HistoryRecordingOutcome.Stored(ObservationId(2)), result.history)
            assertEquals(2, h.fake.calls.size)
            // STOP/return contributes a gap, not a second USAGE observation.
            assertEquals(listOf(HistoryEvent.Gap(HistoryGap.BACKGROUND),
                HistoryEvent.Observed(requireNotNull(result.observations.usage.usage))), journal.admissions.map { it.event })
            assertEquals(listOf(ObservationId(1), ObservationId(2)), journal.admissions.map { it.id })
            assertEquals(journal.admissions.map { it.id }, journal.entries.map { it.id })
            assertEquals(listOf(HistoryGap.BACKGROUND, null), journal.entries.map { it.gap })
            assertEquals(1, journal.metadataCalls)
            assertEquals(2, journal.restoreCalls)
            assertEquals(access.partition, h.capability().partition)
            assertNotSame(access, h.capability())
            assertSame(edit, h.owner.accountName.value!!.edit)
        }
    }

    @Test fun foregroundReturnDuringHeldMetadataRetryRebindsOnceAfterTheOriginalOwnerCompletion() = runBlocking {
        heldMetadataLifecycle(returnBeforeCompletion = true)
    }

    @Test fun foregroundLossDuringHeldMetadataRetryCannotRepublishRuntimeUntilActualReturn() = runBlocking {
        heldMetadataLifecycle(returnBeforeCompletion = false)
    }

    private suspend fun heldMetadataLifecycle(returnBeforeCompletion: Boolean) {
        val journal = LifetimeJournal().apply { failNameRead = true }
        LifetimeFixture(journal).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val access = h.capability()
            val edit = h.owner.accountName.value!!.edit
            val observer = Any()
            val gate = ControlledGate().also { journal.metadataGate = it }
            try {
                h.owner.usageForeground(observer, true)
                gate.awaitEntered()
                h.owner.usageForeground(observer, false)
                settleCommands(h, "foreground loss during metadata I/O")
                assertEquals(HistoryAvailability.UNAVAILABLE, h.owner.historyAvailability)
                if (returnBeforeCompletion) {
                    h.owner.usageForeground(observer, true)
                    settleCommands(h, "foreground return joins metadata I/O")
                }
                assertEquals(1, journal.metadataCalls)
                journal.failNameRead = false
            } finally { gate.release() }
            settle(h, "metadata completion after lifecycle transition")
            if (!returnBeforeCompletion) {
                assertEquals(HistoryAvailability.UNAVAILABLE, h.owner.historyAvailability)
                assertNull(h.owner.currentHistoryDisplayContext())
                assertEquals(1, journal.restoreCalls)
                h.owner.usageForeground(observer, true)
                settle(h, "actual return restores runtime")
            }
            assertEquals(HistoryAvailability.AVAILABLE, h.owner.historyAvailability)
            assertEquals(2, journal.restoreCalls)
            assertEquals(1, journal.metadataCalls)
            assertNotSame(access, h.capability())
            assertSame(edit, h.owner.accountName.value!!.edit)
            assertFalse(h.owner.accountName.value!!.storageFailed)
            assertTrue(h.fake.calls.isEmpty())
            assertEquals(0, journal.deletions)
        }
    }

    @Test fun signOutDuringHeldMetadataRetryCannotPublishTheRetiredNameOrAcquireASuccessor() = runBlocking {
        val journal = LifetimeJournal().apply { failNameRead = true }
        LifetimeFixture(journal).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val edit = h.owner.accountName.value!!.edit!!
            val gate = ControlledGate().also { journal.metadataGate = it }
            try {
                h.owner.usageForeground(Any(), true)
                gate.awaitEntered()
                h.owner.signOut()
                settleCommands(h, "retire generation during metadata I/O")
                assertNull(h.owner.accountName.value)
                assertFalse(h.owner.session.snapshot() is SessionResult.Ready)
                journal.failNameRead = false
            } finally { gate.release() }
            h.phase(ConnectionPhase.SIGNED_OUT)
            settle(h, "retired metadata completion")
            assertNull(h.owner.accountName.value)
            assertNull(h.persistence.durable)
            assertEquals(1, journal.deletions)
            h.owner.renameAccount(edit, "Synthetic stale")
            settleCommands(h, "stale editor after metadata retirement")
            assertEquals(0, journal.nameWrites)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    private suspend fun settle(h: LifetimeFixture, label: String) = bounded(h, label) {
        h.owner.commandsSettled()
        h.owner.historyRestorationSettled()
    }
    private suspend fun settleCommands(h: LifetimeFixture, label: String) = bounded(h, label) { h.owner.commandsSettled() }
    private suspend fun <T> bounded(h: LifetimeFixture, label: String, receipt: suspend () -> T): T =
        try { withTimeout(5_000) { receipt() } }
        catch (_: TimeoutCancellationException) {
            throw AssertionError("$label: state=${h.owner.state.value}, name=${h.owner.accountName.value}, history=${h.owner.historyAvailability}")
        }
}
