package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.HistoryPartition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class AccountCatalogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val slot = UUID(0, 17)
    private val partition = HistoryPartition(UUID(0, 23))
    private fun open(root: File, fault: () -> Unit = {}) = AccountCatalog.open(root, { slot }, fault)
    private fun named(text: String) = AccountNameRead.Ready(AccountDisplayName.from(text))
    private fun other(name: String?) = CatalogEntry(AccountStorage.fresh(), HistoryPartition(UUID.randomUUID()), name, true)

    @Test fun applicationRootAliasCreatesAndReopensTheSameCatalog() {
        val root = temporary.newFolder().canonicalFile
        val alias = File(temporary.root, "root-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertNotEquals(alias.absoluteFile, alias.canonicalFile)
        val catalog = open(alias)
        val entry = catalog.adopt(partition, named("Synthetic alias"))
        assertEquals(listOf(entry), open(root).snapshot().entries)
        assertEquals(catalog.snapshot(), open(alias).snapshot())
        assertEquals(entry.storage.id, open(root).snapshot().selected)
    }

    @Test fun applicationRootResolutionDoesNotPermitRedirectingTheCatalogChild() {
        val root = temporary.newFolder().canonicalFile
        val redirected = temporary.newFolder().canonicalFile
        Files.createSymbolicLink(File(root, "account-catalog").toPath(), redirected.toPath())
        assertThrows(IllegalArgumentException::class.java) { open(root) }
        assertFalse(File(redirected, "catalog").exists())
    }

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

    @Test fun unknownPartitionReconcilesDurablyWithoutRebindingIdentityOrKnownKeys() {
        val root = temporary.newFolder()
        val catalog = open(root)
        val unknown = catalog.adopt(null, AccountNameRead.Unavailable)
        val recovered = catalog.adopt(partition, named("Synthetic recovered"))
        assertEquals(unknown.storage, recovered.storage)
        assertEquals(unknown.type, recovered.type)
        assertEquals(partition, recovered.partition)
        assertEquals("Synthetic recovered", recovered.name)
        assertTrue(recovered.nameAvailable)
        assertEquals(recovered, open(root).legacyEntry())
        assertEquals(unknown.storage.id, open(root).snapshot().selected)
        assertEquals(recovered, catalog.adopt(null, AccountNameRead.Unavailable))
        val before = catalog.snapshot()
        assertThrows(IllegalStateException::class.java) {
            catalog.adopt(HistoryPartition(UUID.randomUUID()), named("Synthetic rebound"))
        }
        assertEquals(before, open(root).snapshot())

        val known = open(temporary.newFolder())
        val keyed = known.adopt(null, named("Synthetic reserved"))
        assertEquals(keyed.copy(partition = partition), known.adopt(partition, named("Synthetic unexpected")))
    }

    @Test fun unknownPartitionReconciliationRejectsAConflictingKeyWithoutChangingEitherEntry() {
        val root = temporary.newFolder()
        val catalog = open(root)
        catalog.adopt(null, AccountNameRead.Unavailable)
        assertTrue(catalog.insert(other("Synthetic occupied")))
        val before = catalog.snapshot()
        assertThrows(IllegalStateException::class.java) { catalog.adopt(partition, named("Synthetic occupied")) }
        assertEquals(before, open(root).snapshot())
        val recovered = catalog.adopt(partition, AccountNameRead.Unavailable)
        assertNull(recovered.name)
        assertFalse(recovered.nameAvailable)
        assertEquals(before.entries.first().storage, recovered.storage)
        assertEquals(before.selected, open(root).snapshot().selected)
    }

    @Test fun interruptedUnknownPartitionReconciliationCanRetryTheSameEntry() {
        val root = temporary.newFolder()
        val unknown = open(root).adopt(null, AccountNameRead.Unavailable)
        var fail = true
        val catalog = open(root) { if (fail) throw IOException("synthetic reconciliation interruption") }
        assertThrows(IOException::class.java) { catalog.adopt(partition, named("Synthetic recovered")) }
        assertEquals(unknown, open(root).legacyEntry())
        fail = false
        val recovered = catalog.adopt(partition, named("Synthetic recovered"))
        assertEquals(unknown.storage, recovered.storage)
        assertEquals(recovered, open(root).legacyEntry())
    }

    @Test fun nameRecoveryIsIndependentOfPartitionAndKnownUnnamedIsNotUnknown() {
        val unavailable = AccountNameRead.Unavailable
        val unnamed = AccountNameRead.Ready(null)
        for (knownPartition in listOf(null, partition)) {
            for (initial in listOf(named("Synthetic known"), unnamed, unavailable)) {
                for (read in listOf(named("Synthetic recovered"), unnamed, unavailable)) {
                    val root = temporary.newFolder()
                    val catalog = open(root)
                    val original = catalog.adopt(knownPartition, initial)
                    val recovered = catalog.adopt(partition, read)
                    val expected = if (initial is AccountNameRead.Ready) initial else read
                    assertEquals(original.storage, recovered.storage)
                    assertEquals(original.type, recovered.type)
                    assertEquals(partition, recovered.partition)
                    assertEquals((expected as? AccountNameRead.Ready)?.name?.text, recovered.name)
                    assertEquals(expected is AccountNameRead.Ready, recovered.nameAvailable)
                    assertEquals(recovered, open(root).legacyEntry())
                    assertEquals(original.storage.id, open(root).snapshot().selected)
                    assertEquals(recovered, catalog.adopt(partition, read))
                }
            }
        }
    }

    @Test fun pendingRenameOwnsUnknownNameOnBothSidesOfSidecarReplacement() {
        for (knownPartition in listOf(null, partition)) {
            for (intended in listOf(AccountDisplayName.from("Synthetic intended"), null)) {
                val root = temporary.newFolder()
                val catalog = open(root)
                val original = catalog.adopt(knownPartition, AccountNameRead.Unavailable)
                assertTrue(catalog.reserveRename(original.storage.id, intended))
                for (read in listOf(named("Synthetic old"), AccountNameRead.Ready(intended), AccountNameRead.Unavailable)) {
                    val recovered = catalog.adopt(partition, read)
                    assertEquals(original.copy(partition = partition), recovered)
                    assertEquals(CatalogRename(original.storage.id, intended?.text), open(root).snapshot().rename)
                }
                catalog.finishRename(original.storage.id)
                assertEquals(original.copy(partition = partition, name = intended?.text, nameAvailable = true), open(root).legacyEntry())
                assertEquals(original.storage.id, open(root).snapshot().selected)
            }
        }
    }

    @Test fun recoveredNameRejectsBothExistingAndPendingKeysWithoutMerging() {
        for (pending in listOf(false, true)) {
            val root = temporary.newFolder()
            val catalog = open(root)
            val original = catalog.adopt(partition, AccountNameRead.Unavailable)
            val saved = other(if (pending) "Synthetic other" else "Synthetic occupied")
            assertTrue(catalog.insert(saved))
            if (pending) assertTrue(catalog.reserveRename(saved.storage.id, AccountDisplayName.from("Synthetic occupied")))
            val before = catalog.snapshot()
            assertThrows(IllegalStateException::class.java) { catalog.adopt(partition, named("Synthetic occupied")) }
            assertEquals(before, open(root).snapshot())
            assertEquals(original, catalog.legacyEntry())
        }
    }

    @Test fun nameOnlyReconciliationRequiresCommitButExactIdentityDoesNotRewrite() {
        val root = temporary.newFolder()
        val original = open(root).adopt(partition, AccountNameRead.Unavailable)
        var fail = true
        val catalog = open(root) { if (fail) throw IOException("synthetic name reconciliation failure") }
        assertEquals(original, catalog.adopt(partition, AccountNameRead.Unavailable))
        assertThrows(IOException::class.java) { catalog.adopt(partition, named("Synthetic recovered")) }
        assertEquals(original, open(root).legacyEntry())
        fail = false
        val recovered = catalog.adopt(partition, named("Synthetic recovered"))
        fail = true
        assertEquals(recovered, catalog.adopt(partition, named("Synthetic other")))
        assertEquals(recovered, open(root).legacyEntry())
        assertEquals(original.storage, recovered.storage)
        assertEquals(original.storage.id, open(root).snapshot().selected)
        assertThrows(IllegalStateException::class.java) {
            catalog.adopt(HistoryPartition(UUID.randomUUID()), AccountNameRead.Unavailable)
        }
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
