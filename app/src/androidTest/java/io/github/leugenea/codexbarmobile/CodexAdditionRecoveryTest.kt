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

    private fun uncertain(reconstruct: Boolean) {
        val sync = AtomicBoolean(false)
        val read = AtomicBoolean(false)
        val syncHits = AtomicInteger()
        val readHits = AtomicInteger()
        f.catalog = AccountCatalog.open(f.directory, { error("existing") }, beforeDirectorySync = {
            if (sync.get()) { syncHits.incrementAndGet(); throw IOException("replacement not synced") }
        }, beforeRead = {
            if (read.get()) { readHits.incrementAndGet(); throw IOException("replacement not readable") }
        })
        startEmpty()
        f.transport.heldPath = CodexAdditionFixture.EXCHANGE
        val handle = requireNotNull(f.begin("Synthetic uncertain"))
        val call = f.held(CodexAdditionFixture.EXCHANGE)
        val address = f.pending()
        sync.set(true); read.set(true)
        call.reply()
        f.until("actual replacement followed by failed readback and durability retry") { syncHits.get() > 0 && readHits.get() >= 2 }
        val raw = CatalogFile(File(f.directory, "account-catalog"))
        assertEquals(address.id, raw.read().selected) // Read bytes without introducing a directory sync.
        assertNull(f.owner.dashboard().value?.id)
        assertNull(f.owner.runtime(address.id))
        assertFalse(f.wait("cancel cannot turn uncertain replacement into destructive cleanup") { handle.cancel() })
        assertFalse(f.wait("unreadable admission still fenced") { handle.retryDurability() })
        assertTrue(f.key(address.credentialSlot))
        assertEquals(0L, f.transport.gets.get())
        read.set(false)
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
            sync.set(false)
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
