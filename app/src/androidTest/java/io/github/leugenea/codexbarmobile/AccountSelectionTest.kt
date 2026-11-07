package io.github.leugenea.codexbarmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AccountSelectionTest {
    private lateinit var fixture: AccountSelectionFixture
    @Before fun prepare() { fixture = AccountSelectionFixture(); fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun passiveRestorationSettingsAndObserversAreDormantWhileExplicitSelectionRequestsBothEndpoints() {
        val root = fixture.start()
        val selected = root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val generationA = fixture.ready(a).generation; val generationB = fixture.ready(b).generation
        val first = fixture.observer(); val second = fixture.observer()
        fixture.settle(a)
        val beforeA = fixture.bytes(fixture.a)
        assertSame(a, root.settings(fixture.a.storage.id)!!.controller)
        assertNull(root.settings(UUID.randomUUID()))
        assertEquals(0L, fixture.aTransport.requests.get()); assertEquals(0L, fixture.bTransport.requests.get())
        fixture.select(fixture.b)
        fixture.phase(b, ConnectionPhase.OBSERVED)
        fixture.await("B exact durable append") { fixture.appends.any { it.directory == fixture.b.storage.historyDirectory(fixture.directory) && it.admission.id == ObservationId(4) } }
        val projectionB = selected.value!!
        assertEquals(fixture.b.storage.id, projectionB.id)
        assertSame(b, projectionB.controller)
        assertSame(a, root.settings(fixture.a.storage.id)!!.controller)
        assertSame(projectionB, selected.value)
        assertEquals(beforeA, fixture.bytes(fixture.a))
        assertCounts(fixture.bTransport, usage = 1, inventory = 1)
        fixture.stop(first) // The second Activity still owns its separate registration.
        fixture.settle(b)
        assertNotNull(b.currentHistoryDisplayContext())
        fixture.select(fixture.a)
        fixture.phase(a, ConnectionPhase.OBSERVED)
        fixture.await("A BACKGROUND and USAGE appends") { fixture.appends.any { it.directory == fixture.a.storage.historyDirectory(fixture.directory) && it.admission.id == ObservationId(5) } }
        assertSame(generationA, fixture.ready(a).generation); assertSame(generationB, fixture.ready(b).generation)
        val returned = selected.value!!
        fixture.select(fixture.a)
        fixture.phase(a, ConnectionPhase.OBSERVED)
        assertSame(returned, selected.value) // Same selection keeps display identity, explicit refresh remains explicit.
        assertFalse(fixture.receipt("invalid selection") { root.select(UUID.randomUUID()) })
        assertSame(returned, selected.value)
        assertCounts(fixture.aTransport, usage = 2, inventory = 2)
        fixture.stop(first) // Delayed duplicate STOP cannot release the second or a successor.
        fixture.settle(a); assertNotNull(a.currentHistoryDisplayContext())
        fixture.stop(second); fixture.settle(a)
        assertNull(a.currentHistoryDisplayContext())
        fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
        assertCounts(fixture.bTransport, usage = 2, inventory = 2) // Explicit selection is immediate even without a visible host.
    }

    @Test fun heldOldUsageCannotDispatchInventoryOrPublishAfterEqualQueryABAReturn() {
        val root = fixture.start(); val selected = root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val observer = fixture.observer(); fixture.settle(a)
        val original = fixture.ready(a)
        fixture.aTransport.heldPath = ReadOperation.USAGE.path
        fixture.select(fixture.a)
        fixture.await("original A usage dispatch") { fixture.aTransport.liveHeldCall(ReadOperation.USAGE.path) != null }
        val old = fixture.aTransport.liveHeldCall(ReadOperation.USAGE.path)!!
        val initialRoot = selected.value!!
        try {
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            fixture.await("original A flight cancelled") { old.cancelled }
            assertFalse(initialRoot.isCurrent())
            fixture.aTransport.heldPath = null
            fixture.aTransport.usageBody = NavigationTransport.USAGE.replace("12.375", "23.125")
            fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.OBSERVED)
            fixture.await("fresh A exact observation") { fixture.appends.any { it.admission.partition == fixture.a.partition && it.admission.id == ObservationId(5) } }
            val beforeLate = a.state.value.refresh.usage.success
            old.reply()
            fixture.settle(a)
            assertSame(beforeLate, a.state.value.refresh.usage.success)
            assertEquals(BigDecimal("23.125"), a.state.value.refresh.usage.success!!.usage!!.fiveHour.candidates.single().usedPercent.value)
            assertNotSame(initialRoot, selected.value)
            assertSame(original, fixture.ready(a))
            assertCounts(fixture.aTransport, usage = 2, inventory = 1)
            assertCounts(fixture.bTransport, usage = 1, inventory = 1)
            val receipts = fixture.appends.filter { it.admission.partition == fixture.a.partition }
            assertEquals(listOf(4L, 5L), receipts.map { it.admission.id.ordinal })
            assertEquals(HistoryEvent.Gap(HistoryGap.BACKGROUND), receipts[0].admission.event)
            assertTrue(receipts[1].admission.event is HistoryEvent.Observed)
            receipts.forEach { assertTrue(it.result is HistoryAppendOutcome.Stored) }
        } finally { old.reply(); fixture.stop(observer) }
    }

    @Test fun acceptedRotationPersistsOnlyOriginalAAfterDisplayTransfersToB() {
        val write = fixture.gate()
        fixture.decorateCredential = { id, store ->
            if (id != fixture.a.storage.credentialSlot) store else object : CredentialStore by store {
                override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> {
                    write.pause("original A accepted protected write")
                    return store.replace(envelope, cancellation)
                }
            }
        }
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val observer = fixture.observer(); fixture.settle(a)
        val original = fixture.ready(a)
        val beforeCiphertext = KeystoreCredentialStore.file(fixture.context, fixture.a.storage.credentialSlot).readBytes()
        fixture.aTransport.heldPath = "/oauth/token"
        a.readUsage(refreshSession = true)
        fixture.await("A refresh dispatched") { fixture.aTransport.liveHeldCall("/oauth/token") != null }
        val rotation = fixture.aTransport.liveHeldCall("/oauth/token")!!
        var peerBytes: Map<String, List<Byte>>? = null
        try {
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            fixture.await("B original admission completed") { fixture.appends.any { it.admission.partition == fixture.b.partition } }
            peerBytes = fixture.bytes(fixture.b); val peerEnvelope = fixture.ready(b)
            rotation.reply(); write.await("A protected token persistence")
            assertSame(original, fixture.ready(a))
            assertSame(peerEnvelope, fixture.ready(b)); assertEquals(peerBytes, fixture.bytes(fixture.b))
            assertEquals(fixture.b.storage.id, root.dashboard().value!!.id)
        } finally { rotation.reply(); write.release() }
        fixture.await("A rotation independent owner settlement") { (a.session.snapshot() as? SessionResult.Ready)?.envelope !== original }
        assertSame(original.generation, fixture.ready(a).generation)
        assertEquals(peerBytes, fixture.bytes(fixture.b))
        val protected = KeystoreCredentialStore(fixture.context, fixture.a.storage.credentialSlot)
        val saved = protected.read(protected.openSession()) as CredentialResult.Success
        assertArrayEquals("synthetic-navigation-access".toByteArray(), saved.value.accessToken.copyBytes())
        assertFalse(beforeCiphertext.contentEquals(KeystoreCredentialStore.file(fixture.context, fixture.a.storage.credentialSlot).readBytes()))
        assertEquals(1L, fixture.aTransport.counts()["/oauth/token"])
        assertCounts(fixture.aTransport, usage = 0, inventory = 0)
        fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.OBSERVED)
        assertCounts(fixture.aTransport, usage = 1, inventory = 1)
        fixture.stop(observer)
    }

    @Test fun deselectionBeforeIrreversibleAppendKeepsOriginalNativeCapability() = appendAcrossSwitch("before")
    @Test fun deselectionAfterIrreversibleAppendAdmissionKeepsOriginalNativeCapability() = appendAcrossSwitch("admitted")
    @Test fun deselectionAfterCommitKeepsOriginalAppendReceiptAndPeerIsolation() = appendAcrossSwitch("committed")

    private fun appendAcrossSwitch(boundary: String) {
        val gate = fixture.gate(); val armed = AtomicBoolean(true)
        fixture.hooks = { directory -> object : HistoryStorageHooks {
            private fun hold(at: String) {
                if (directory == fixture.a.storage.historyDirectory(fixture.directory) && boundary == at && armed.compareAndSet(true, false)) gate.pause("A append $at")
            }
            override fun beforeWriteAdmission() = hold("before")
            override fun afterWriteAdmission() = hold("admitted")
            override fun afterCommit() = hold("committed")
        } }
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val observer = fixture.observer(); fixture.settle(a)
        val generationA = fixture.ready(a).generation
        val accessA = a.historyCapability(generationA)
        var peerBytes: Map<String, List<Byte>>? = null
        a.readUsage()
        try {
            gate.await("A real SQLite $boundary boundary")
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            fixture.await("B own append completed") { fixture.appends.any { it.admission.partition == fixture.b.partition } }
            peerBytes = fixture.bytes(fixture.b)
            assertSame(accessA, a.historyCapability(generationA))
            assertNull(a.currentHistoryDisplayContext())
            assertTrue(fixture.appends.none { it.admission.partition == fixture.a.partition })
        } finally { gate.release() }
        fixture.await("original A append completion receipt") { fixture.appends.any { it.admission.partition == fixture.a.partition } }
        val original = fixture.appends.single { it.admission.partition == fixture.a.partition }
        assertEquals(ObservationId(4), original.admission.id)
        assertTrue(original.admission.event is HistoryEvent.Observed)
        assertTrue(original.result is HistoryAppendOutcome.Stored)
        assertEquals(ObservationId(4), (original.result as HistoryAppendOutcome.Stored).entry.id)
        assertEquals(peerBytes, fixture.bytes(fixture.b))
        assertSame(generationA, fixture.ready(a).generation)
        assertCounts(fixture.aTransport, 1, 1); assertCounts(fixture.bTransport, 1, 1)
        fixture.stop(observer)
        fixture.receipt("shutdown before independent native readback") { root.shutdown() }
        assertEquals(listOf(1L, 2L, 3L, 4L), fixture.diskPage(fixture.a).entries.map { it.id.ordinal })
        assertEquals(listOf(1L, 2L, 3L, 4L), fixture.diskPage(fixture.b).entries.map { it.id.ordinal })
        assertEquals(BigDecimal("12.375"), fixture.diskPage(fixture.a).entries.last().windows.first().point!!.usedPercent)
        assertEquals(BigDecimal("62.5"), fixture.diskPage(fixture.b).entries.last().windows.first().point!!.usedPercent)
    }

    @Test fun exactEnrolledAInterestSurvivesSelectedBWithoutSelectedLookupOrGlobalCancellation() = workerAcrossSwitch(bFirst = true)
    @Test fun exactEnrolledASettlementCanPrecedeHeldSelectedBWithoutReleasingBInterest() = workerAcrossSwitch(bFirst = false)

    private fun workerAcrossSwitch(bFirst: Boolean) {
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val observer = fixture.observer(); fixture.settle(a)
        val target = fixture.receipt("exact enrolled A target", a) { a.backgroundRefreshTarget() }!!
        fixture.aTransport.heldPath = ReadOperation.USAGE.path
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val work = scope.async { a.refreshInBackground(target) }
        try {
            fixture.await("worker A dispatched") { fixture.aTransport.liveHeldCall(ReadOperation.USAGE.path) != null }
            val call = fixture.aTransport.liveHeldCall(ReadOperation.USAGE.path)!!
            if (!bFirst) fixture.bTransport.heldPath = ReadOperation.USAGE.path
            fixture.select(fixture.b)
            if (bFirst) fixture.phase(b, ConnectionPhase.OBSERVED)
            else fixture.await("B independent foreground dispatch") { fixture.bTransport.liveHeldCall(ReadOperation.USAGE.path) != null }
            assertFalse(call.cancelled)
            assertSame(a, root.legacy()) // Compatibility worker retains its original ingress.
            call.reply()
            val outcome = fixture.receipt("original worker A endpoint and recording outcome", a) { work.await() } as BackgroundRefreshOutcome.Completed
            assertEquals(HistoryRecordingOutcome.Stored(ObservationId(5)), outcome.history)
            val aReceipts = fixture.appends.filter { it.admission.partition == fixture.a.partition }
            assertEquals(listOf(4L, 5L), aReceipts.map { it.admission.id.ordinal })
            assertEquals(HistoryEvent.Gap(HistoryGap.BACKGROUND), aReceipts[0].admission.event)
            assertTrue(aReceipts[1].admission.event is HistoryEvent.Observed)
            assertEquals(fixture.b.storage.id, root.dashboard().value!!.id)
            if (!bFirst) {
                val foreground = fixture.bTransport.liveHeldCall(ReadOperation.USAGE.path)!!
                assertFalse(foreground.cancelled)
                assertCounts(fixture.bTransport, 1, 0)
                foreground.reply(); fixture.phase(b, ConnectionPhase.OBSERVED)
            }
            assertCounts(fixture.aTransport, 1, 1); assertCounts(fixture.bTransport, 1, 1)
        } finally {
            fixture.aTransport.releaseHeld(); fixture.bTransport.releaseHeld()
            fixture.receipt("worker A teardown") { work.cancelAndJoin() }; scope.cancel(); fixture.stop(observer)
        }
    }

    @Test fun reauthorizationRetainsCurrentSavedAAndActualRemovalActivatesDormantB() {
        val root = fixture.start(); val selection = root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val observer = fixture.observer(); fixture.settle(a)
        val peer = fixture.bytes(fixture.b)
        val invalidated = fixture.ready(a).generation
        fixture.aTransport.usageStatus = 401
        fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.REAUTH_REQUIRED)
        assertEquals(fixture.a.copy(partition = null), fixture.catalog.entry(fixture.a.storage.id))
        assertEquals(fixture.a.storage.id, selection.value!!.id)
        assertFalse(a.session.snapshot() is SessionResult.Ready)
        assertNull(a.historyCapability(invalidated))
        assertFalse(java.io.File(fixture.a.storage.historyDirectory(fixture.directory), "history.db").exists())
        assertTrue(fixture.catalog.snapshot().deletions.isEmpty())
        assertFalse(KeystoreCredentialStore.file(fixture.context, fixture.a.storage.credentialSlot).exists())
        assertEquals(peer, fixture.bytes(fixture.b)); assertEquals(0L, fixture.bTransport.requests.get())
        a.signOut(); fixture.phase(a, ConnectionPhase.SIGNED_OUT)
        fixture.await("automatic stable fallback published") { selection.value?.id == fixture.b.storage.id }
        fixture.phase(b, ConnectionPhase.OBSERVED)
        assertNull(fixture.catalog.entry(fixture.a.storage.id))
        assertEquals(fixture.b.storage.id, fixture.catalog.snapshot().selected)
        assertCounts(fixture.bTransport, 1, 1)
        fixture.stop(observer)
    }

    @Test fun selectionSuspendedInARestorationCannotDispatchAfterBHasWon() {
        val restoring = fixture.gate()
        fixture.decorateHistory = { path, storage ->
            if (path != fixture.a.storage.historyDirectory(fixture.directory)) storage else object : HistoryLifetimeStorage by storage {
                override fun restore(): HistoryLifetimeOutcome { restoring.pause("A dormant restoration"); return storage.restore() }
            }
        }
        assertTrue(fixture.catalog.select(fixture.b.storage.id))
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selecting = scope.async { root.select(fixture.a.storage.id) }
        try {
            restoring.await("A restore before explicit selection activation")
            fixture.await("A selection committed before held activation") { root.dashboard().value?.id == fixture.a.storage.id }
            fixture.phase(b, ConnectionPhase.RESTORED)
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            assertEquals(0L, fixture.aTransport.requests.get())
            assertEquals(fixture.b.storage.id, root.dashboard().value!!.id)
            restoring.release()
            assertTrue(fixture.receipt("superseded A selection admission", a) { selecting.await() })
            fixture.phase(a, ConnectionPhase.RESTORED)
            assertEquals(0L, fixture.aTransport.requests.get())
            assertCounts(fixture.bTransport, 1, 1)
        } finally { restoring.release(); fixture.receipt("superseded selection teardown") { selecting.cancelAndJoin() }; scope.cancel() }
    }

    @Test fun retainedInventoryCallbackCannotOverwriteFreshAOrBThroughEqualTimeKeyABA() {
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val observer = fixture.observer(); fixture.settle(a)
        fixture.aTransport.heldPath = ReadOperation.RESET_INVENTORY.path
        fixture.select(fixture.a)
        fixture.await("original A inventory dispatch after accepted usage") { fixture.aTransport.liveHeldCall(ReadOperation.RESET_INVENTORY.path) != null }
        val old = fixture.aTransport.liveHeldCall(ReadOperation.RESET_INVENTORY.path)!!
        try {
            fixture.await("original A usage append") { fixture.appends.any { it.admission.partition == fixture.a.partition && it.admission.id == ObservationId(4) } }
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            fixture.await("old A inventory cancellation") { old.cancelled }
            val peer = b.state.value.refresh.inventory.success
            fixture.aTransport.heldPath = null
            fixture.aTransport.inventoryBody = """{"available_count":3,"credits":[]}"""
            fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.OBSERVED)
            val fresh = a.state.value.refresh.inventory.success
            assertEquals(3L, fresh!!.inventory!!.reportedAvailableCount.value)
            old.reply(); fixture.settle(a)
            assertSame(fresh, a.state.value.refresh.inventory.success)
            assertSame(peer, b.state.value.refresh.inventory.success)
            assertCounts(fixture.aTransport, 2, 2); assertCounts(fixture.bTransport, 1, 1)
            fixture.await("fresh A usage original append") { fixture.appends.any { it.admission.partition == fixture.a.partition && it.admission.id == ObservationId(6) } }
            val receipts = fixture.appends.filter { it.admission.partition == fixture.a.partition }
            assertEquals(listOf(4L, 5L, 6L), receipts.map { it.admission.id.ordinal })
            assertTrue(receipts[0].admission.event is HistoryEvent.Observed)
            assertEquals(HistoryEvent.Gap(HistoryGap.BACKGROUND), receipts[1].admission.event)
            assertTrue(receipts[2].admission.event is HistoryEvent.Observed)
        } finally { old.reply(); fixture.stop(observer) }
    }

    @Test fun failedOrUnreadableSelectionCommitCannotGrantBDisplayOrProviderAuthority() {
        var failReplace = false; var failSync = false; var failRead = false
        fixture.catalog = AccountCatalog.open(fixture.directory, { error("existing catalog") },
            beforeRename = { if (failReplace) throw java.io.IOException("selection before replace") },
            beforeDirectorySync = { if (failSync) throw java.io.IOException("selection after replace") },
            beforeRead = { if (failRead) throw java.io.IOException("selection readback unavailable") })
        val root = fixture.start(); val selected = root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val observer = fixture.observer(); fixture.settle(a)
        val original = selected.value
        try {
            failReplace = true
            assertFalse(fixture.receipt("pre-replace selection failure") { root.select(fixture.b.storage.id) })
            assertSame(original, selected.value)
            failReplace = false
            assertTrue(fixture.catalog.select(fixture.a.storage.id)) // Confirm the failed old-file outcome without activating quotas.
            failSync = true; failRead = true
            assertFalse(fixture.receipt("post-replace unreadable selection") { root.select(fixture.b.storage.id) })
            assertSame(original, selected.value)
            assertFalse(fixture.receipt("fenced second selection") { root.select(fixture.b.storage.id) })
            assertSame(original, selected.value)
            assertEquals(0L, fixture.bTransport.requests.get())
            assertEquals(fixture.b.storage.id, AccountCatalog.open(fixture.directory, { error("existing") }).snapshot().selected)
            failRead = false; failSync = false
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            assertEquals(fixture.b.storage.id, selected.value!!.id)
            assertCounts(fixture.bTransport, 1, 1)
            assertEquals(0L, fixture.aTransport.requests.get())
        } finally { failRead = false; failReplace = false; failSync = false; fixture.stop(observer) }
    }

    private fun assertCounts(transport: NavigationTransport, usage: Long, inventory: Long) {
        assertEquals(usage, transport.counts()[ReadOperation.USAGE.path] ?: 0L)
        assertEquals(inventory, transport.counts()[ReadOperation.RESET_INVENTORY.path] ?: 0L)
    }
}
