package io.github.leugenea.codexbarmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.history.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CodexAdditionRecoveryTest {
    private lateinit var f: CodexAdditionFixture
    @Before fun prepare() { f = CodexAdditionFixture() }
    @After fun cleanup() { f.cleanup() }

    @Test fun reconstructionRemovesOwnedKeyOnlyStagedProtectedAndActiveLabelCutsWithoutLogin() {
        (0..4).forEach { cut ->
            val metadata = AccountCatalog.open(f.directory, { error("existing") })
            val address = reserve(metadata)
            val path = address.historyDirectory(f.directory)
            if (cut >= 1) createKey(address)
            if (cut >= 2) createHistoryCut(path, cut == 4)
            if (cut >= 3) protect(address)
            if (cut == 1) {
                assertTrue(f.key(address.credentialSlot))
                assertFalse(KeystoreCredentialStore.file(f.context, address.credentialSlot).exists())
            }
            f.catalog = AccountCatalog.open(f.directory, { error("reconstruct") })
            f.start()
            f.until("startup cleaned owned disk cut $cut without an Add") { f.catalog.snapshots.value.candidate == null }
            f.removed(address)
            assertTrue(f.disk().entries.isEmpty())
            assertEquals(0L, f.transport.requests.get())
            f.stop()
        }
    }

    @Test fun incompleteCleanupRetainsExactAddressAndReleasesRuntimeUntilExplicitRetry() {
        startEmpty()
        f.transport.heldPath = CodexAdditionFixture.POLL
        val handle = requireNotNull(f.begin("Synthetic failed cleanup"))
        val poll = f.held(CodexAdditionFixture.POLL)
        val address = f.pending()
        val obstruction = File(address.historyDirectory(f.directory), "unexpected-child")
        assertTrue(obstruction.mkdir())
        assertTrue(f.wait("owned cancellation") { handle.cancel() })
        assertEquals(AdditionResult.Rejected.CANCELLED, f.result(handle))
        assertTrue(poll.cancelled)
        poll.reply()
        assertEquals(address, f.disk().candidate)
        assertNull(f.owner.runtime(address.id))
        f.stop()
        f.catalog = AccountCatalog.open(f.directory, { error("existing") })
        f.start()
        assertFalse(f.wait("checked reconstruction fails at actual directory obstruction") { NativeConnection.recoverAddition(f.context) })
        assertEquals(address, f.disk().candidate)
        assertNull(f.begin("Synthetic not admitted"))
        val calls = f.transport.requests.get()
        assertTrue(obstruction.delete())
        assertTrue(f.wait("explicit successful retry") { NativeConnection.recoverAddition(f.context) })
        f.removed(address)
        assertNull(f.disk().candidate)
        assertEquals(2L, calls)
        assertEquals(calls, f.transport.requests.get())
    }

    @Test fun strictStageActiveAndLabelFailuresDoNotFallThroughToInstalledAccountAvailability() {
        val hits = AtomicInteger()
        var cut = 0
        f.history = { path ->
            val real = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(path))
            object : HistoryLifetimeStorage by real {
                override fun stage(): HistoryPartitionOutcome {
                    val staged = real.stage()
                    if (cut != 0 || path.name == "usage-history") return staged
                    assertTrue(staged is HistoryPartitionOutcome.Staged)
                    hits.incrementAndGet()
                    return HistoryPartitionOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE)
                }
                override fun activate(partition: HistoryPartition): HistoryLifetimeOutcome {
                    if (cut != 1) return real.activate(partition)
                    assertTrue(File(path, "binding").exists())
                    hits.incrementAndGet()
                    return HistoryLifetimeOutcome.Unavailable
                }
                override fun writeName(partition: HistoryPartition, name: AccountDisplayName?): Boolean {
                    if (cut != 2) return real.writeName(partition, name)
                    assertTrue(real.readName(partition) is AccountNameRead.Ready)
                    hits.incrementAndGet()
                    return false
                }
            }
        }
        startEmpty()
        repeat(3) {
            cut = it
            val handle = requireNotNull(f.begin("Synthetic strict cut"))
            assertEquals(AdditionResult.Rejected.STORAGE, f.result(handle))
            assertEquals(it + 1, hits.get())
            assertNull(f.disk().candidate)
            assertTrue(f.disk().entries.isEmpty())
            assertNull(f.owner.dashboard().value?.id)
            assertEquals(0L, f.transport.gets.get())
            assertEquals((it * 3).toLong(), f.transport.requests.get())
        }
    }

    @Test fun cancellationDrainsRealProtectedWriteOnBothSidesOfItsIrreversibleDecision() {
        for (irreversible in listOf(false, true)) {
            val gate = f.gate()
            f.protect = { slot -> if (slot == f.legacy) KeystoreCredentialStore(f.context, slot) else protectedWithHold(slot, gate, irreversible) }
            if (!irreversible) startEmpty()
            val handle = requireNotNull(f.begin("Synthetic protected cut"))
            try {
                gate.await("real protected ${if (irreversible) "commit" else "prepared write"}")
                val candidate = f.pending()
                assertTrue(f.key(candidate.credentialSlot))
                val file = KeystoreCredentialStore.file(f.context, candidate.credentialSlot)
                assertTrue(file.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("credential-") })
                assertTrue(f.wait("cancellation while credential storage remains held") { handle.cancel() })
                assertFalse(file.exists())
                assertEquals(candidate, f.disk().candidate)
                assertNull(f.owner.runtime(candidate.id))
                gate.release()
                assertEquals(AdditionResult.Rejected.CANCELLED, f.result(handle))
                if (irreversible) assertNotNull(f.writes[candidate.credentialSlot])
                else assertNull(f.writes[candidate.credentialSlot])
                f.removed(candidate)
                assertNull(f.disk().candidate)
                assertEquals(0L, f.transport.gets.get())
            } finally { gate.release() }
        }
    }

    @Test fun beforeReplacementFailureRejectsAndCleansThePreparedActiveCandidate() {
        val hits = AtomicInteger()
        val armed = AtomicBoolean()
        f.catalog = AccountCatalog.open(f.directory, { error("existing") }, beforeRename = {
            if (armed.compareAndSet(true, false)) {
                hits.incrementAndGet()
                throw IOException("before candidate catalog replacement")
            }
        })
        startEmpty()
        f.transport.heldPath = CodexAdditionFixture.EXCHANGE
        val handle = requireNotNull(f.begin("Synthetic cannot replace"))
        val call = f.held(CodexAdditionFixture.EXCHANGE)
        val address = f.pending()
        armed.set(true)
        call.reply()
        assertEquals(AdditionResult.Rejected.STORAGE, f.result(handle))
        assertEquals(1, hits.get())
        assertNotNull(f.writes[address.credentialSlot])
        f.removed(address)
        assertNull(f.disk().candidate)
        assertTrue(f.disk().entries.isEmpty())
        assertEquals(0L, f.transport.gets.get())
    }

    @Test fun unsyncedReplacementStaysFencedThenHandsOffTheOriginalRuntimeWithoutRewriting() = uncertain(reconstruct = false)
    @Test fun replacedBeforeAcknowledgmentSurvivesProcessOwnerReconstructionWithoutIncidentalRead() = uncertain(reconstruct = true)

    @Test fun selectedPeerRebindsHistoryAndReadsUsageWhileReplacedAdditionIsFenced() {
        val fault = ReplacementFault()
        f.catalog = fault.open(f.directory)
        startEmpty()
        val observer = f.wait("A lifecycle START") { NativeConnection.observeDashboard(f.context) }
        val a = (f.result(requireNotNull(f.begin("Synthetic incumbent"))) as AdditionResult.Added).id
        val peer = f.runtime(a)
        f.observe(peer)
        val session = f.ready(peer)
        val enrollment = f.enrollment.toList()

        f.transport.heldPath = CodexAdditionFixture.EXCHANGE
        val handle = requireNotNull(f.begin("Synthetic uncertain successor"))
        val call = f.held(CodexAdditionFixture.EXCHANGE)
        val address = f.pending()
        fault.arm()
        call.reply()
        val raw = awaitReplacement(fault, address)

        withReboundPeer(observer, peer) {
            assertEquals(a, f.owner.dashboard().value?.id)
            assertSame(peer, f.owner.dashboard().value?.controller)
            assertEquals(enrollment, f.enrollment.toList())
            assertNull(f.owner.runtime(address.id))
            assertFalse(f.wait("catalog selection write stays fenced") { f.owner.select(a) })
            fault.read.set(false)
            assertFalse(f.wait("readable B remains fenced on failed directory sync") { handle.retryDurability() })
            assertEquals(address.id, raw.read().selected)
            assertEquals(a, f.catalog.snapshots.value.selected)
            assertTrue(f.key(address.credentialSlot))

            fault.sync.set(false)
            assertTrue(f.wait("recover B after the fault clears") { handle.retryDurability() })
            assertEquals(AdditionResult.Added(address.id), f.result(handle))
            assertEquals(address.id, f.owner.dashboard().value?.id)
            assertSame(f.writes[address.credentialSlot], f.ready(f.runtime(address.id)).generation)
            assertSame(session, f.ready(peer))
        }
    }

    @Test fun selectedPeerRebindsWhileReplacedReservationIsFenced() = candidateOnlyReplacement(discard = false)

    @Test fun selectedPeerRebindsWhileReplacedDiscardIsFenced() = candidateOnlyReplacement(discard = true)

    private fun candidateOnlyReplacement(discard: Boolean) {
        val fault = ReplacementFault()
        f.catalog = fault.open(f.directory)
        startEmpty()
        val observer = f.wait("A lifecycle START") { NativeConnection.observeDashboard(f.context) }
        val peer = f.owner.legacy()
        peer.connect()
        f.observe(peer)
        val enrollment = f.enrollment.toList()
        val raw = CatalogFile(File(f.directory, "account-catalog"))
        val saved = raw.read().entries
        val incumbent = saved.single()
        val a = incumbent.storage.id
        assertTrue(incumbent.storage.legacy)
        assertEquals(BackgroundRefreshTarget(requireNotNull(incumbent.partition)), enrollment.last())
        f.until("A is the selected saved account") { f.owner.dashboard().value?.id == a }
        val address = if (discard) cancelWithDiscardFault(fault) else {
            val requests = f.transport.requests.get()
            fault.sync.set(true)
            assertNull(f.begin("Synthetic failed reservation"))
            assertEquals(requests, f.transport.requests.get())
            requireNotNull(raw.read().candidate) // Replacement observed without supplying the missing sync.
        }
        assertTrue("catalog directory sync fault reached after candidate replacement", fault.syncHits.get() > 0)
        val disk = raw.read()
        assertEquals(if (discard) null else address, disk.candidate)
        assertEquals(if (discard) address else null, f.catalog.snapshots.value.candidate)
        assertEquals(saved, disk.entries)
        assertEquals(a, disk.selected)
        assertFalse(f.key(address.credentialSlot))
        assertFalse(address.historyDirectory(f.directory).exists())

        val requests = f.transport.requests.get()
        withReboundPeer(observer, peer) {
            assertEquals(a, f.owner.dashboard().value?.id)
            assertSame(peer, f.owner.dashboard().value?.controller)
            assertEquals(enrollment, f.enrollment.toList())
            assertNull(f.owner.runtime(address.id))
            assertFalse(f.wait("catalog selection stays fenced") { f.owner.select(a) })
            assertFalse(f.wait("candidate cleanup remains fenced") { NativeConnection.recoverAddition(f.context) })
            assertNull(f.begin("Synthetic blocked until recovery"))
            assertEquals(disk, raw.read())
            assertEquals(requests + 2, f.transport.requests.get()) // A's two reads, no candidate auth or quota.
            fault.sync.set(false)
            assertTrue(f.wait("explicit candidate recovery after directory sync resumes") { NativeConnection.recoverAddition(f.context) })
            assertNull(raw.read().candidate)
            assertEquals(saved, f.catalog.snapshots.value.entries)
            assertEquals(a, f.catalog.snapshots.value.selected)
            f.removed(address)
            assertEquals(requests + 2, f.transport.requests.get())
        }
    }

    private fun cancelWithDiscardFault(fault: ReplacementFault): AccountStorage {
        f.transport.heldPath = CodexAdditionFixture.POLL
        val handle = requireNotNull(f.begin("Synthetic cancelled before admission"))
        val poll = f.held(CodexAdditionFixture.POLL)
        val address = f.pending()
        fault.sync.set(true)
        assertTrue(f.wait("cancel B while poll is held") { handle.cancel() })
        assertEquals(AdditionResult.Rejected.CANCELLED, f.result(handle))
        assertTrue(poll.cancelled)
        poll.reply()
        return address
    }

    private fun withReboundPeer(observer: DashboardObserver, peer: ConnectionController, afterRebind: () -> Unit) {
        val session = f.ready(peer)
        val history = requireNotNull(peer.historyCapability(session.generation))
        f.until("A has a durable history measurement before STOP") { f.append[history.partition] is HistoryAppendOutcome.Stored }
        assertEquals(0, f.wait("no background lease can mask A's rebind") { peer.backgroundRefreshInterests() })
        f.wait("A last foreground STOP completes") { observer.stop(); peer.commandsSettled() }
        assertNull(peer.historyCapability(session.generation))
        f.transport.advance(60_001L)
        val next = f.wait("A lifecycle START") { NativeConnection.observeDashboard(f.context) }
        try {
            f.wait("A lifecycle START rebind completes") { peer.commandsSettled(); peer.historyRestorationSettled() }
            assertSame(session, f.ready(peer))
            val rebound = requireNotNull(peer.historyCapability(session.generation))
            assertNotSame(history, rebound)
            assertEquals(history.partition, rebound.partition)
            val restored = rebound.read(HistoryReadQuery(history.partition, limit = 10)) as HistoryReadOutcome.Ready
            assertEquals(HistoryContent.MEASUREMENTS, restored.snapshot.content)
            f.wait("fresh A quota and inventory succeed after rebind while B is fenced") {
                peer.state.first { (it.refresh.usage.successfulAtMillis ?: 0L) >= 60_001L &&
                    (it.refresh.inventory.successfulAtMillis ?: 0L) >= 60_001L && !it.refresh.refreshing }
            }
            afterRebind()
            assertSame(session, f.ready(peer))
        } finally { f.wait("release resumed foreground observer") { next.stop() } }
    }

    @Test fun coldOpenSyncFailureKeepsOwnedKeyPrivateUntilSameProcessExplicitRecovery() {
        val address = reserve(f.catalog)
        createKey(address)
        val raw = CatalogFile(File(f.directory, "account-catalog"))
        val syncAttempts = AtomicInteger()
        val blocked = AtomicBoolean(true)
        f.start(openCatalog = {
            AccountCatalog.open(f.directory, { error("existing") }, beforeDirectorySync = {
                syncAttempts.incrementAndGet()
                if (blocked.get()) throw IOException("cold-open directory sync failure")
            })
        })
        val dashboard = NativeConnection.dashboard(f.context)
        val unavailable = NativeConnection.get(f.context)
        f.wait("cold open exposes only unavailable compatibility display") {
            unavailable.state.first { it.phase == ConnectionPhase.FAILED && it.problem == ConnectionProblem.STORAGE }
        }
        assertSame(unavailable, dashboard.value?.controller)
        assertEquals(1, syncAttempts.get())
        assertFalse(f.wait("explicit recovery still fails while cold-open durability is blocked") {
            NativeConnection.recoverAddition(f.context)
        })
        assertEquals(2, syncAttempts.get())
        assertEquals(address, raw.read().candidate)
        assertTrue(f.key(address.credentialSlot))
        assertNull(NativeConnection.entry(f.context, address.id))
        assertNull(f.begin("Synthetic not admitted during bootstrap failure"))
        assertEquals(0L, f.transport.requests.get())

        blocked.set(false)
        assertTrue(f.wait("same singleton reopens and cleans its exact owned key") { NativeConnection.recoverAddition(f.context) })
        assertSame(dashboard, NativeConnection.dashboard(f.context))
        val available = NativeConnection.get(f.context)
        assertNotSame(unavailable, available)
        assertSame(available, dashboard.value?.controller)
        f.wait("recovered empty installation is usable and dormant") { available.state.first { it.phase == ConnectionPhase.IDLE } }
        assertNull(raw.read().candidate)
        assertTrue(raw.read().entries.isEmpty())
        assertFalse(f.key(address.credentialSlot))
        assertEquals(0L, f.transport.requests.get())
    }

    private fun awaitReplacement(fault: ReplacementFault, address: AccountStorage): CatalogFile {
        f.until("actual replacement followed by failed readback and durability retry") {
            fault.syncHits.get() > 0 && fault.readHits.get() >= 2
        }
        return CatalogFile(File(f.directory, "account-catalog")).also {
            assertEquals(address.id, it.read().selected) // Raw read must not supply a directory barrier.
        }
    }

    private class ReplacementFault {
        val sync = AtomicBoolean()
        val read = AtomicBoolean()
        val syncHits = AtomicInteger()
        val readHits = AtomicInteger()

        fun arm() { sync.set(true); read.set(true) }

        fun open(root: File) = AccountCatalog.open(root, { error("existing") }, beforeDirectorySync = {
            if (sync.get()) { syncHits.incrementAndGet(); throw IOException("replacement not synced") }
        }, beforeRead = {
            if (read.get()) { readHits.incrementAndGet(); throw IOException("replacement not readable") }
        })
    }

    private fun uncertain(reconstruct: Boolean) {
        val fault = ReplacementFault()
        f.catalog = fault.open(f.directory)
        startEmpty()
        f.transport.heldPath = CodexAdditionFixture.EXCHANGE
        val handle = requireNotNull(f.begin("Synthetic uncertain"))
        val call = f.held(CodexAdditionFixture.EXCHANGE)
        val address = f.pending()
        fault.arm()
        call.reply()
        awaitReplacement(fault, address)
        assertNull(f.owner.dashboard().value?.id)
        assertNull(f.owner.runtime(address.id))
        assertFalse(f.wait("cancel cannot turn uncertain replacement into destructive cleanup") { handle.cancel() })
        assertFalse(f.wait("unreadable admission still fenced") { handle.retryDurability() })
        assertTrue(f.key(address.credentialSlot))
        assertEquals(0L, f.transport.gets.get())
        fault.read.set(false)
        assertFalse(f.wait("readable but unsynced admission still fenced") { handle.retryDurability() })
        assertEquals(0L, f.transport.gets.get())
        assertNull(f.owner.runtime(address.id))
        val file = File(f.directory, "account-catalog/catalog")
        if (reconstruct) {
            f.stop()
            f.catalog = AccountCatalog.open(f.directory, { error("existing") })
            f.start()
            f.wait("committed before UI ack restores passively") { f.runtime(address.id).state.first { it.phase == ConnectionPhase.RESTORED } }
            assertEquals(address.id, f.owner.dashboard().value?.id)
            assertEquals(0L, f.transport.gets.get())
        } else {
            // A newer unrelated catalog write must survive confirmation; retry must not rewrite it.
            val newer = AccountCatalog.open(f.directory, { error("existing") })
            assertTrue(newer.reserveRename(address.id, AccountDisplayName.from("Synthetic later")))
            val bytes = file.readBytes()
            fault.sync.set(false)
            assertTrue(f.wait("confirm current catalog without replaying an older snapshot") { handle.retryDurability() })
            assertEquals(AdditionResult.Added(address.id), f.result(handle))
            assertEquals(address.id, f.owner.dashboard().value?.id)
            val controller = f.runtime(address.id)
            assertSame(f.writes[address.credentialSlot], f.ready(controller).generation)
            assertEquals(1, f.constructed[address.credentialSlot])
            f.observe(controller)
            assertEquals(2L, f.transport.gets.get())
            assertArrayEquals(bytes, file.readBytes())
        }
        assertNotNull(f.protected(address).refreshToken)
        assertEquals("Synthetic uncertain", f.runtime(address.id).accountName.value?.name?.text)
    }

    private fun startEmpty() {
        f.start()
        f.wait("finish bootstrap before arming Add cuts") { f.owner.legacy().state.first { it.phase == ConnectionPhase.IDLE } }
    }

    private fun reserve(metadata: AccountCatalog): AccountStorage {
        val address = requireNotNull(metadata.reserveAddition(requireNotNull(AccountDisplayName.from("Synthetic interrupted")), AccountType.CODEX))
        assertEquals(address, f.pending())
        return address
    }

    private fun createKey(address: AccountStorage) {
        AndroidCredentialCipher(KeystoreCredentialStore.alias(f.context, address.credentialSlot))
            .encrypt(byteArrayOf(3), CredentialBinaryFormat.header(address.credentialSlot), false)
    }

    private fun createHistoryCut(path: File, active: Boolean) = SQLiteHistoryStore(path).use { store ->
        val partition = (store.stagePartition() as HistoryPartitionOutcome.Staged).partition
        if (active) {
            assertTrue(store.activatePartition(partition) is HistoryAccessOutcome.Bound)
            assertTrue(store.writeName(partition, AccountDisplayName.from("Synthetic interrupted")))
        }
    }

    private fun protect(address: AccountStorage) {
        val store = KeystoreCredentialStore(f.context, address.credentialSlot)
        val envelope = CredentialEnvelope(store.openSession(), SensitiveValue.copyOf(byteArrayOf(1)), SensitiveValue.copyOf(byteArrayOf(2)))
        assertTrue(store.replace(envelope, CredentialCancellation()) is CredentialResult.Success)
    }

    private fun protectedWithHold(slot: java.util.UUID, gate: SelectionGate, irreversible: Boolean): CredentialStore {
        val target = KeystoreCredentialStore.file(f.context, slot)
        val atomic = AtomicCredentialFile(target, beforeRename = { if (irreversible) gate.pause("after irreversible decision") })
        val file = object : CredentialFile by atomic {
            override fun stage(bytes: ByteArray): PreparedCredentialWrite = atomic.stage(bytes).also {
                if (!irreversible) gate.pause("before irreversible decision")
            }
        }
        val persistence = ProtectedCredentialPersistence(file, AndroidCredentialCipher(KeystoreCredentialStore.alias(f.context, slot)), slot,
            File(target.parentFile, "rotation-pending"))
        return SerializedCredentialStore(persistence, persistence::activate)
    }
}
