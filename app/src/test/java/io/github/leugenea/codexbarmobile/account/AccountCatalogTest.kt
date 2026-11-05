package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.HistoryPartition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

class AccountCatalogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val slot = UUID(0, 17)
    private val partition = HistoryPartition(UUID(0, 23))
    private fun open(root: File, fault: () -> Unit = {}) = AccountCatalog.open(root, { slot }, fault)
    private fun named(text: String) = AccountNameRead.Ready(AccountDisplayName.from(text))
    private fun other(name: String?) = CatalogEntry(AccountStorage.fresh(), HistoryPartition(UUID.randomUUID()), name, true)

    @Test fun migrationIsIdempotentAndLocationsDoNotReuseTheHistoryPartitionOrCredentialSlot() {
        val root = temporary.newFolder()
        val catalog = open(root)
        assertTrue(catalog.snapshot().entries.isEmpty())
        val entry = catalog.adopt(partition, named("Synthetic personal"))
        val restarted = open(root)
        assertEquals(entry, restarted.adopt(partition, named("Synthetic personal")))
        assertEquals(listOf(entry), restarted.snapshot().entries)
        assertEquals(entry.storage.id, restarted.snapshot().selected)
        assertEquals(slot, entry.storage.credentialSlot)
        assertNotEquals(slot, entry.storage.id)
        assertNotEquals(partition.value, entry.storage.id)
        assertEquals(File(root, "usage-history"), entry.storage.historyDirectory(root))
        val fresh = AccountStorage.fresh()
        assertEquals(File(root, "usage-history-${fresh.id}"), fresh.historyDirectory(root))
        assertNotEquals(slot, fresh.credentialSlot)
        assertNotEquals(entry.storage.historyDirectory(root), fresh.historyDirectory(root))
    }

    @Test fun conflictingNamesRejectWithoutMergingAndRenameKeepsTheOpaqueIdentity() {
        val root = temporary.newFolder()
        val catalog = open(root)
        val legacy = catalog.adopt(partition, named("Synthetic personal"))
        val work = other("Synthetic work")
        assertTrue(catalog.insert(work))
        val before = catalog.snapshot()
        assertFalse(catalog.insert(other("Synthetic personal")))
        assertFalse(catalog.insert(work.copy(name = "Synthetic duplicate ID")))
        assertFalse(catalog.insert(other("Synthetic different ID").copy(storage = work.storage.copy(id = UUID.randomUUID()))))
        assertFalse(catalog.reserveRename(legacy.storage.id, AccountDisplayName.from(" Synthetic work ")))
        assertEquals(before, open(root).snapshot())
        assertTrue(catalog.reserveRename(legacy.storage.id, AccountDisplayName.from("Synthetic renamed")))
        assertFalse(catalog.insert(other("Synthetic renamed")))
        assertFalse(catalog.insert(other("Synthetic personal")))
        assertFalse(catalog.reserveRename(work.storage.id, AccountDisplayName.from("Synthetic third")))
        catalog.finishRename(legacy.storage.id)
        val renamed = open(root).legacyEntry()!!
        assertEquals(legacy.copy(name = "Synthetic renamed"), renamed)
        assertTrue(catalog.insert(other("Synthetic personal")))
        assertEquals(before.selected, catalog.snapshot().selected)
    }

    @Test fun unnamedAndUnavailableLegacyNamesDoNotInventAUserKey() {
        for (name in listOf(AccountNameRead.Ready(null), AccountNameRead.Unavailable)) {
            val root = temporary.newFolder()
            val catalog = open(root)
            val entry = catalog.adopt(partition, name)
            assertNull(entry.name)
            assertEquals(name is AccountNameRead.Ready, entry.nameAvailable)
            assertTrue(catalog.reserveRename(entry.storage.id, AccountDisplayName.from("Synthetic repaired")))
            catalog.finishRename(entry.storage.id)
            assertTrue(catalog.reserveRename(entry.storage.id, null))
            catalog.finishRename(entry.storage.id)
            assertNull(open(root).legacyEntry()!!.name)
            assertTrue(open(root).legacyEntry()!!.nameAvailable)
        }
    }

    @Test fun failedMigrationCommitNeverPublishesAnEntryAndCanRestartWithoutDiscardingLegacyFiles() {
        val root = temporary.newFolder()
        open(root)
        val legacy = File(root, "legacy-sentinel").apply { writeText("synthetic retained data") }
        val catalog = open(root) { throw IOException("synthetic interruption") }
        try { catalog.adopt(partition, named("Synthetic legacy")); fail("Expected interrupted catalog commit") }
        catch (_: IOException) { }
        assertTrue(catalog.snapshot().entries.isEmpty())
        assertTrue(open(root).snapshot().entries.isEmpty())
        val restarted = open(root)
        val entry = restarted.adopt(partition, named("Synthetic legacy"))
        assertEquals(entry, open(root).legacyEntry())
        assertEquals("synthetic retained data", legacy.readText())
        assertEquals(setOf("catalog"), File(root, "account-catalog").list()!!.toSet())
    }

    @Test fun deletionReceiptOutlivesTheHistoryRemovalUntilTheCombinedBarrier() {
        val root = temporary.newFolder()
        val catalog = open(root)
        val entry = catalog.adopt(partition, named("Synthetic legacy"))
        catalog.beginDeletion()
        val restarted = open(root)
        val receipt = restarted.snapshot().deleting
        assertNotNull(receipt)
        assertEquals(entry, restarted.legacyEntry())
        assertFalse(restarted.reserveRename(entry.storage.id, AccountDisplayName.from("Synthetic rejected")))
        assertTrue(restarted.completeDeletion(receipt))
        assertTrue(restarted.completeDeletion(null))
        assertNull(open(root).snapshot().deleting)
        assertTrue(open(root).snapshot().entries.isEmpty())
        assertNull(open(root).snapshot().selected)
        assertNotEquals(entry.storage.id, restarted.adopt(partition, named("Synthetic new login")).storage.id)
        restarted.beginDeletion()
        val successor = restarted.snapshot()
        assertFalse(restarted.completeDeletion(receipt))
        assertEquals(successor, open(root).snapshot())
    }

    @Test fun corruptCatalogAndAbandonedStagingNeverBecomeAnEmptySuccessfulRead() {
        val root = temporary.newFolder()
        open(root).adopt(partition, named("Synthetic legacy"))
        val file = File(root, "account-catalog/catalog")
        val original = file.readBytes()
        File(file.parentFile, "catalog-abandoned.tmp").writeBytes(byteArrayOf(1, 2))
        assertEquals(1, open(root).snapshot().entries.size)
        for (invalid in listOf(byteArrayOf(1), original + byteArrayOf(0), original.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() })) {
            file.writeBytes(invalid)
            try { open(root); fail("Corrupt catalog must not restore as empty") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(invalid, file.readBytes())
        }
        file.writeBytes(original)
        assertEquals(1, open(root).snapshot().entries.size)
    }
}
