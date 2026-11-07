package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.HistoryPartition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.CRC32

class AccountSelectionCatalogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val slot = UUID(0, 41)
    private fun catalogAt(root: File) = AccountCatalog.open(root, { slot })
    private fun entry(name: String) = CatalogEntry(AccountStorage.fresh(), HistoryPartition(UUID.randomUUID()), name, true)
    private fun pair(catalog: AccountCatalog): Pair<CatalogEntry, CatalogEntry> {
        val a = entry("Synthetic A"); val b = entry("Synthetic B")
        assertTrue(catalog.insert(a)); assertTrue(catalog.insert(b))
        assertTrue(catalog.select(a.storage.id))
        return a to b
    }

    @Test fun exactSelectionIsDurableIdempotentAndUnknownAddressDoesNotWrite() {
        val root = temporary.newFolder()
        val catalog = catalogAt(root)
        assertNull(catalog.snapshot().selected)
        assertFalse(catalog.select(UUID.randomUUID()))
        val (a, b) = pair(catalog)
        assertTrue(catalog.select(b.storage.id))
        assertEquals(b.storage.id, catalogAt(root).snapshot().selected)
        val file = File(root, "account-catalog/catalog")
        val committed = file.readBytes()
        val publication = catalog.snapshots.value
        assertTrue(catalog.select(b.storage.id))
        assertFalse(catalog.select(UUID.randomUUID()))
        assertSame(publication, catalog.snapshots.value)
        assertArrayEquals(committed, file.readBytes())
        assertEquals(listOf(a, b), catalogAt(root).snapshot().entries)
    }

    @Test fun actualRemovalChoosesStableCatalogOrderNotNameOrCredentialHealthAndEmptyMeansNone() {
        val root = temporary.newFolder()
        val catalog = catalogAt(root)
        val (a, b) = pair(catalog)
        val c = entry("AAA sorts before both").copy(partition = null)
        assertTrue(catalog.insert(c))
        assertTrue(catalog.select(b.storage.id))
        assertTrue(catalog.completeDeletion(catalog.beginDeletion(a.storage.id)))
        assertEquals(b.storage.id, catalog.snapshot().selected) // Removing a peer does not select.
        assertTrue(catalog.completeDeletion(catalog.beginDeletion(b.storage.id)))
        assertEquals(c.storage.id, catalogAt(root).snapshot().selected)
        assertTrue(catalog.completeDeletion(catalog.beginDeletion(c.storage.id)))
        assertNull(catalogAt(root).snapshot().selected)
        assertTrue(catalogAt(root).snapshot().entries.isEmpty())
    }

    @Test fun retainedReauthorizationReconstructsItsDispositionAndDropsOnlyOldPartitionAuthority() {
        val root = temporary.newFolder()
        val catalog = catalogAt(root)
        val (a, b) = pair(catalog)
        val pending = catalog.beginDeletion(a.storage.id, CleanupDisposition.RETAIN)
        val restored = catalogAt(root)
        assertEquals(pending, restored.snapshot().deletions.single())
        // A caller cannot reinterpret a pending receipt from its new phase or selection.
        assertEquals(pending, restored.beginDeletion(a.storage.id, CleanupDisposition.REMOVE))
        assertFalse(restored.completeDeletion(pending.copy(disposition = CleanupDisposition.REMOVE)))
        assertTrue(restored.completeDeletion(pending))
        assertEquals(a.copy(partition = null), catalogAt(root).entry(a.storage.id))
        assertEquals(a.storage.id, catalogAt(root).snapshot().selected)
        assertEquals(b, catalogAt(root).entry(b.storage.id))
        assertFalse(restored.completeDeletion(pending))
        val freshPartition = HistoryPartition(UUID.randomUUID())
        assertEquals(a.copy(partition = freshPartition), restored.reconcile(a.storage.id, freshPartition, AccountNameRead.Ready(null)))
        assertThrows(IllegalStateException::class.java) {
            restored.reconcile(a.storage.id, a.partition, AccountNameRead.Ready(null))
        }
    }

    @Test fun selectionBeforeReplacementFailureCannotPublishAndRetryConfirmsTheOldFile() {
        val root = temporary.newFolder()
        val (a, b) = pair(catalogAt(root))
        var fail = true
        val catalog = AccountCatalog.open(root, { slot }, beforeRename = { if (fail) throw IOException("before replace") })
        val bytes = File(root, "account-catalog/catalog").readBytes()
        assertThrows(IOException::class.java) { catalog.select(b.storage.id) }
        assertEquals(a.storage.id, catalog.snapshots.value.selected)
        assertArrayEquals(bytes, File(root, "account-catalog/catalog").readBytes())
        fail = false
        assertTrue(catalog.select(b.storage.id))
        assertEquals(b.storage.id, catalog.snapshots.value.selected)
        assertEquals(b.storage.id, catalogAt(root).snapshot().selected)
    }

    @Test fun unreadablePostReplaceOutcomeFencesEveryFollowingWriteUntilConfirmedReadbackAndSync() {
        val root = temporary.newFolder()
        val (a, b) = pair(catalogAt(root))
        var failRead = false; var failSync = false; var writes = 0
        val catalog = AccountCatalog.open(root, { slot }, beforeRename = { writes++ },
            beforeDirectorySync = { if (failSync) throw IOException("after replace") },
            beforeRead = { if (failRead) throw IOException("readback unavailable") })
        failRead = true; failSync = true
        assertThrows(IOException::class.java) { catalog.select(b.storage.id) }
        assertEquals(a.storage.id, catalog.snapshots.value.selected)
        assertEquals(b.storage.id, catalogAt(root).snapshot().selected) // Replacement actually happened.
        val peer = entry("Synthetic newer peer")
        assertTrue(catalogAt(root).insert(peer)) // A newer durable snapshot must never be overwritten by old RAM.
        val newerBytes = File(root, "account-catalog/catalog").readBytes()
        assertThrows(IOException::class.java) { catalog.insert(entry("Synthetic fenced")) }
        assertThrows(IOException::class.java) { catalog.select(a.storage.id) }
        assertEquals(1, writes)
        assertArrayEquals(newerBytes, File(root, "account-catalog/catalog").readBytes())
        failRead = false
        assertThrows(IOException::class.java) { catalog.select(a.storage.id) } // Readable is not yet synced.
        assertEquals(1, writes)
        assertEquals(a.storage.id, catalog.snapshots.value.selected)
        failSync = false
        assertTrue(catalog.select(b.storage.id)) // Idempotent confirmation, no replacement.
        assertEquals(1, writes)
        assertEquals(peer, catalog.entry(peer.storage.id))
        assertEquals(b.storage.id, catalog.snapshots.value.selected)
        assertArrayEquals(newerBytes, File(root, "account-catalog/catalog").readBytes())
    }

    @Test fun initialIdentityReadbackCannotBypassUnconfirmedDirectoryDurabilityOnRetry() {
        val root = temporary.newFolder()
        catalogAt(root)
        var failSync = true; var writes = 0
        val catalog = AccountCatalog.open(root, { slot }, beforeRename = { writes++ },
            beforeDirectorySync = { if (failSync) throw IOException("initial identity sync") })
        val partition = HistoryPartition(UUID(0, 71))
        val name = AccountNameRead.Ready(AccountDisplayName.from("Synthetic initial"))
        assertThrows(IOException::class.java) { catalog.adopt(partition, name) }
        val replaced = catalogAt(root).legacyEntry()!!
        assertTrue(catalog.snapshots.value.entries.isEmpty())
        assertThrows(IOException::class.java) { catalog.adopt(partition, name) }
        assertTrue(catalog.snapshots.value.entries.isEmpty())
        assertEquals(1, writes)
        failSync = false
        assertEquals(replaced, catalog.adopt(partition, name))
        assertEquals(replaced.storage.id, catalog.snapshots.value.selected)
        assertEquals(1, writes)
    }

    @Test fun retainedAppliedFinalizationUsesOnlyOriginalTicketAndNeverRewritesANewerPeer() {
        val root = temporary.newFolder()
        val (a, b) = pair(catalogAt(root))
        var failSync = false; var writes = 0
        val catalog = AccountCatalog.open(root, { slot }, beforeRename = { writes++ },
            beforeDirectorySync = { if (failSync) throw IOException("retained settlement sync") })
        val receipt = catalog.beginDeletion(a.storage.id, CleanupDisposition.RETAIN)
        val settle = catalog.deletionSettlement(receipt)
        failSync = true
        assertFalse(settle())
        assertEquals(a.copy(partition = null), catalog.entry(a.storage.id))
        assertEquals(a.storage.id, catalog.snapshots.value.selected)
        assertFalse(settle())
        failSync = false
        val peerReceipt = catalog.beginDeletion(b.storage.id)
        val successorReceipt = catalog.beginDeletion(a.storage.id, CleanupDisposition.REMOVE)
        val bytes = File(root, "account-catalog/catalog").readBytes()
        val replacements = writes
        assertFalse(catalog.deletionSettlement(receipt)())
        assertTrue(settle())
        assertEquals(replacements, writes)
        assertArrayEquals(bytes, File(root, "account-catalog/catalog").readBytes())
        assertEquals(listOf(peerReceipt, successorReceipt), catalog.snapshot().deletions)
        assertTrue(catalog.completeDeletion(successorReceipt))
        assertEquals(b.storage.id, catalog.snapshot().selected)
    }

    @Test fun renameOnlyUncertainFinalizationRetainsBothReservationsUntilItsExistingRetrySettles() {
        val root = temporary.newFolder()
        val (a, b) = pair(catalogAt(root))
        var failSync = false
        val catalog = AccountCatalog.open(root, { slot }, beforeDirectorySync = { if (failSync) throw IOException("rename sync") })
        assertTrue(catalog.reserveRename(a.storage.id, AccountDisplayName.from("Synthetic renamed")))
        failSync = true
        assertThrows(IOException::class.java) { catalog.finishRename(a.storage.id) }
        assertEquals(CatalogRename(a.storage.id, "Synthetic renamed"), catalog.snapshot().rename)
        assertEquals(a, catalog.reconcile(a.storage.id, a.partition, AccountNameRead.Ready(null)))
        assertEquals(b, catalog.entry(b.storage.id))
        failSync = false
        // The existing recovery path first syncs the already-applied intent, without replaying an old snapshot.
        assertTrue(catalog.select(a.storage.id))
        assertNull(catalog.snapshot().rename)
        assertEquals("Synthetic renamed", catalog.entry(a.storage.id)!!.name)
        assertTrue(catalog.insert(entry("Synthetic A")))
    }

    @Test fun legacyV1AndV2ReceiptsAlwaysRemoveAndCorruptSelectedReferenceNeverRepairsItself() {
        for (version in listOf(0x41433100, 0x41433200)) {
            val root = temporary.newFolder()
            val catalog = catalogAt(root)
            val a = catalog.adopt(HistoryPartition(UUID(0, 51)), AccountNameRead.Ready(AccountDisplayName.from("Synthetic legacy")))
            val operation = UUID(0, 61)
            legacyWire(root, version, a, operation, a.storage.id)
            val restored = catalogAt(root)
            val receipt = restored.snapshot().deletions.single()
            assertEquals(CatalogDeletion(a.storage.id, operation, CleanupDisposition.REMOVE), receipt)
            assertEquals(receipt, restored.beginDeletion(a.storage.id, CleanupDisposition.RETAIN))
            assertTrue(restored.completeDeletion(receipt))
            assertTrue(catalogAt(root).snapshot().entries.isEmpty())
            legacyWire(root, version, a, operation, UUID(0, 999))
            val bytes = File(root, "account-catalog/catalog").readBytes()
            assertThrows(IllegalArgumentException::class.java) { catalogAt(root) }
            assertArrayEquals(bytes, File(root, "account-catalog/catalog").readBytes())
        }
    }

    /** Independently encode the old layouts, never derive a legacy fixture from the new writer. */
    private fun legacyWire(root: File, version: Int, entry: CatalogEntry, operation: UUID, selected: UUID) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            fun uuid(id: UUID) { out.writeLong(id.mostSignificantBits); out.writeLong(id.leastSignificantBits) }
            out.writeInt(version); uuid(slot); out.writeBoolean(true); uuid(selected); out.writeInt(1)
            uuid(entry.storage.id); uuid(slot); out.writeBoolean(true)
            out.writeBoolean(true); uuid(entry.partition!!.value); out.writeUTF("CODEX")
            out.writeBoolean(true); out.writeUTF("Synthetic legacy"); out.writeBoolean(true)
            out.writeBoolean(false)
            if (version == 0x41433100) out.writeBoolean(true)
            else { out.writeInt(1); out.writeBoolean(true); uuid(entry.storage.id) }
            uuid(operation)
        }
        val bytes = payload.toByteArray()
        DataOutputStream(File(root, "account-catalog/catalog").outputStream()).use { out ->
            out.write(bytes); out.writeLong(CRC32().apply { update(bytes) }.value)
        }
    }
}
