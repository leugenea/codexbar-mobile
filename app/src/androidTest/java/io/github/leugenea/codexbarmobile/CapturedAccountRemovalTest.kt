package io.github.leugenea.codexbarmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real irreversible protected writes and SQLite admissions; one existing root/cleanup owner. */
@RunWith(AndroidJUnit4::class)
class CapturedAccountRemovalTest {
    private lateinit var fixture: AccountSelectionFixture
    @Before fun prepare() { fixture = AccountSelectionFixture(); fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun capturedRemovalWaitsForAcceptedProtectedRotationAfterPeerCompletes() = overlap(protected = true, peerFirst = true)
    @Test fun capturedRemovalSettlesAcceptedProtectedRotationBeforeHeldPeerCompletes() = overlap(protected = true, peerFirst = false)
    @Test fun capturedRemovalWaitsForAdmittedSQLiteAppendAfterPeerCompletes() = overlap(protected = false, peerFirst = true)
    @Test fun capturedRemovalSettlesAdmittedSQLiteAppendBeforeHeldPeerCompletes() = overlap(protected = false, peerFirst = false)

    private fun overlap(protected: Boolean, peerFirst: Boolean) {
        val held = fixture.gate()
        val armed = AtomicBoolean(true)
        if (protected) fixture.decorateCredential = { id, store ->
            if (id != fixture.a.storage.credentialSlot) store else protectedStore(held)
        } else fixture.hooks = { path -> object : HistoryStorageHooks {
            override fun afterWriteAdmission() {
                if (path == fixture.a.storage.historyDirectory(fixture.directory) && armed.compareAndSet(true, false))
                    held.pause("original A irreversible SQLite admission")
            }
        } }
        val root = fixture.start(); val selected = root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val observer = fixture.observer(); fixture.settle(a)
        val original = fixture.ready(a)
        val confirmation = a.accountRemoval
        val peerEnvelope = fixture.ready(b)
        val peerCiphertext = KeystoreCredentialStore.file(fixture.context, fixture.b.storage.credentialSlot).readBytes()
        if (!peerFirst) fixture.bTransport.heldPath = ReadOperation.USAGE.path
        a.readUsage(refreshSession = protected)
        try {
            held.await(if (protected) "A real protected commit admitted" else "A actual SQLite append admitted")
            fixture.select(fixture.b)
            if (peerFirst) completedPeer(b)
            else fixture.await("independent B request held") { fixture.bTransport.liveHeldCall(ReadOperation.USAGE.path) != null }
            val peerBeforeRemoval = fixture.bytes(fixture.b)
            val peerCounts = fixture.bTransport.counts()
            a.signOut(confirmation)
            fixture.settle(a)
            assertEquals(ConnectionPhase.SIGNING_OUT, a.state.value.phase)
            assertFalse(a.session.snapshot() is SessionResult.Ready)
            assertNull(a.historyCapability(original.generation))
            assertEquals(fixture.b.storage.id, selected.value!!.id)
            assertEquals(peerBeforeRemoval, fixture.bytes(fixture.b))
            assertEquals(peerCounts, fixture.bTransport.counts())
            held.release()
            fixture.phase(a, ConnectionPhase.SIGNED_OUT)
            fixture.assertRemoved(fixture.a)
            assertTrue(AccountCatalog.open(fixture.directory, { error("existing") }).snapshot().deletions.isEmpty())
            assertEquals(fixture.b, AccountCatalog.open(fixture.directory, { error("existing") }).entry(fixture.b.storage.id))
            if (!protected) {
                val append = fixture.appends.single { it.admission.partition == fixture.a.partition && it.admission.event is HistoryEvent.Observed }
                assertEquals(ObservationId(4), append.admission.id)
                assertTrue(append.result is HistoryAppendOutcome.Stored)
            }
            if (!peerFirst) {
                val request = requireNotNull(fixture.bTransport.liveHeldCall(ReadOperation.USAGE.path))
                assertFalse(request.cancelled)
                request.reply(); completedPeer(b)
            }
            val peerAfter = fixture.bytes(fixture.b)
            val countsAfter = fixture.bTransport.counts()
            a.signOut(confirmation); a.connect(confirmation); a.readUsage()
            fixture.settle(a)
            assertEquals(peerAfter, fixture.bytes(fixture.b))
            assertEquals(countsAfter, fixture.bTransport.counts())
            assertSame(peerEnvelope, fixture.ready(b))
            assertArrayEquals(peerCiphertext, KeystoreCredentialStore.file(fixture.context, fixture.b.storage.credentialSlot).readBytes())
            assertArrayEquals(peerEnvelope.accessToken.copyBytes(), fixture.protectedReadback(fixture.b).accessToken.copyBytes())
            assertEquals(listOf(1L, 2L, 3L, 4L), fixture.settledDiskPage(fixture.b).entries.map { it.id.ordinal })
            assertEquals(1L, countsAfter[ReadOperation.USAGE.path])
            assertEquals(1L, countsAfter[ReadOperation.RESET_INVENTORY.path])
            assertEquals(if (protected) 0L else 1L, fixture.aTransport.counts()[ReadOperation.USAGE.path] ?: 0L)
            fixture.assertRemoved(fixture.a)
        } finally { held.release(); fixture.bTransport.releaseHeld(); fixture.stop(observer) }
    }

    /** The same production protected persistence/file/cipher with a hook after irreversible admission. */
    private fun protectedStore(commit: SelectionGate): CredentialStore {
        val id = fixture.a.storage.credentialSlot
        val target = KeystoreCredentialStore.file(fixture.context, id)
        val persistence = ProtectedCredentialPersistence(AtomicCredentialFile(target, beforeRename = {
            commit.pause("A accepted protected commit before native rename")
        }), AndroidCredentialCipher(KeystoreCredentialStore.alias(fixture.context, id)), id,
            File(target.parentFile, "rotation-pending"))
        val store = SerializedCredentialStore(persistence, persistence::activate)
        return object : CredentialStore by store {
            override fun beginRotation(generation: SessionGeneration) = persistence.rotation(generation, true)
            override fun finishRotation(generation: SessionGeneration) = persistence.rotation(generation, false)
        }
    }

    @Test fun finalCatalogFailureKeepsExactCapturedRemovalPendingAndDoesNotReadOrOverwritePeer() {
        val finalizationEntered = AtomicInteger()
        val armed = AtomicBoolean(false)
        fixture.catalog = AccountCatalog.open(fixture.directory, { error("existing catalog") }, beforeRename = {
            if (armed.get() && fixture.catalog.snapshot().deletions.isNotEmpty()) {
                finalizationEntered.incrementAndGet()
                throw IOException("synthetic captured-removal catalog replacement failure")
            }
        })
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
        val peer = fixture.bytes(fixture.b)
        val counts = fixture.bTransport.counts()
        val peerEnvelope = fixture.ready(b)
        val confirmation = a.accountRemoval
        armed.set(true)
        a.signOut(confirmation); fixture.phase(a, ConnectionPhase.FAILED)
        assertTrue("Actual final-catalog cutpoint entered", finalizationEntered.get() > 0)
        assertEquals(ConnectionProblem.STORAGE, a.state.value.problem)
        assertFalse(a.session.snapshot() is SessionResult.Ready)
        val ticket = AccountCatalog.open(fixture.directory, { error("existing") }).snapshot().deletions.single()
        assertEquals(fixture.a.storage.id, ticket.entry)
        assertEquals(CleanupDisposition.REMOVE, ticket.disposition)
        assertFalse(fixture.keyExists(fixture.a))
        assertFalse(KeystoreCredentialStore.file(fixture.context, fixture.a.storage.credentialSlot).exists())
        assertEquals(peer, fixture.bytes(fixture.b)); assertEquals(counts, fixture.bTransport.counts())
        a.signOut(confirmation); fixture.settle(a)
        assertEquals(ConnectionPhase.FAILED, a.state.value.phase)
        assertEquals(ticket, AccountCatalog.open(fixture.directory, { error("existing") }).snapshot().deletions.single())
        armed.set(false)
        a.signOut(a.accountRemoval); fixture.phase(a, ConnectionPhase.SIGNED_OUT)
        fixture.assertRemoved(fixture.a)
        assertEquals(fixture.b.storage.id, fixture.catalog.snapshot().selected)
        assertEquals(fixture.b, AccountCatalog.open(fixture.directory, { error("existing") }).entry(fixture.b.storage.id))
        assertSame(peerEnvelope, fixture.ready(b))
        assertEquals(peer, fixture.bytes(fixture.b)); assertEquals(counts, fixture.bTransport.counts())
        assertArrayEquals(peerEnvelope.accessToken.copyBytes(), fixture.protectedReadback(fixture.b).accessToken.copyBytes())
    }

    private fun completedPeer(controller: ConnectionController) {
        fixture.phase(controller, ConnectionPhase.OBSERVED)
        fixture.await("B exact native observation completion") { fixture.appends.any {
            it.admission.partition == fixture.b.partition && it.admission.event is HistoryEvent.Observed &&
                it.admission.id == ObservationId(4) && it.result is HistoryAppendOutcome.Stored
        } }
        val counts = fixture.bTransport.counts()
        assertEquals(1L, counts[ReadOperation.USAGE.path])
        assertEquals(1L, counts[ReadOperation.RESET_INVENTORY.path])
    }
}
