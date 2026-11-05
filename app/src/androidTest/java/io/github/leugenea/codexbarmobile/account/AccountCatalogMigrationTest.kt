package io.github.leugenea.codexbarmobile.account

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

/** Hosted real Keystore/SQLite readback with synthetic data; holder restart is not process death. */
@RunWith(AndroidJUnit4::class)
class AccountCatalogMigrationTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(app.noBackupFilesDir, "synthetic-catalog-${UUID.randomUUID()}").canonicalFile
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
    }
    private val slot = UUID.randomUUID()
    private val transport = NavigationTransport()
    private val owners = mutableListOf<ConnectionController>()
    private val slots = mutableListOf(slot)
    private val at = Instant.ofEpochSecond(1_800_000_000)
    private lateinit var partition: HistoryPartition
    private val historyDirectory get() = File(root, "usage-history")
    private val credentialFile get() = KeystoreCredentialStore.file(context, slot)
    private fun credentials() = KeystoreCredentialStore(context, slot)
    private fun catalog() = AccountCatalog.open(root, { slot })

    @Before fun prepare() { assertTrue(root.mkdir()) }
    @After fun cleanup() {
        var failure: Throwable? = null
        fun attempt(work: () -> Unit) { try { work() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure!!.addSuppressed(problem)
        } }
        owners.forEach { attempt { stop(it) } }
        slots.forEach { id -> attempt { keys().deleteEntry(KeystoreCredentialStore.alias(context, id)) } }
        attempt { check(root.deleteRecursively() || !root.exists()) }
        failure?.let { throw it }
    }
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun stop(owner: ConnectionController) = runBlocking {
        try { withTimeout(5_000) { owner.shutdown() } }
        catch (_: TimeoutCancellationException) { throw AssertionError("catalog owner shutdown: last state=${owner.state.value}") }
    }
    private fun native(context: Context = this.context) = NativeConnection.create(context, transport, transport.clock, transport::pause).also {
        owners += it
        try { slots += NativeConnection.session(context) } catch (_: Exception) { /* Unreadable metadata is under test. */ }
    }
    private fun phase(owner: ConnectionController, expected: ConnectionPhase) = runBlocking {
        try { withTimeout(5_000) { owner.state.first { it.phase == expected && !it.refresh.refreshing } } }
        catch (_: TimeoutCancellationException) { throw AssertionError("catalog phase $expected: last state=${owner.state.value}") }
    }
    private fun awaitName(owner: ConnectionController, expected: String?, failed: Boolean = false) = runBlocking {
        try { withTimeout(5_000) { owner.accountName.first { it != null && it.storageFailed == failed && it.name?.text == expected } } }
        catch (_: TimeoutCancellationException) { throw AssertionError("catalog name settlement: last state=${owner.accountName.value}") }
    }
    private fun seed() {
        File(root, "connection-session").writeText(slot.toString())
        val store = credentials()
        val generation = store.openSession()
        assertTrue(store.replace(CredentialEnvelope(generation, SensitiveValue.copyOf("synthetic-catalog-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-catalog-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
        SQLiteHistoryStore(historyDirectory, HistoryStorageLimits(observations = 2)).use { history ->
            val access = (history.createPartition() as HistoryAccessOutcome.Bound).access
            partition = access.partition
            for (id in 1L..3L) assertTrue(access.append(admission(partition, id)) is HistoryAppendOutcome.Stored)
            assertTrue(history.writeName(partition, AccountDisplayName.from("Synthetic legacy")))
        }
    }
    private fun admission(partition: HistoryPartition, ordinal: Long): HistoryAdmission {
        val window = WindowInput(Input.Value(18000L), Input.Value(BigDecimal("12.37500000000000000001")),
            Input.Value(1_800_003_600L), Input.Missing)
        return HistoryAdmission(partition, ObservationId(ordinal), HistoryClock(ClockEpoch(UUID(0, 19)), ordinal * 1000),
            HistoryEvent.Observed(UsageNormalizer.normalize(UsageInput(Input.Value(window), Input.Missing), at.plusSeconds(ordinal))))
    }
    private fun export(): String = SQLiteHistoryStore(historyDirectory).use { history ->
        val access = (history.adopt(partition) as HistoryAccessOutcome.Bound).access
        access.exportHistory("synthetic", at)!!
    }
    private fun assertProtectedReadback() {
        val store = credentials()
        val result = store.read(store.openSession())
        assertTrue("continuing protected credential readback: $result", result is CredentialResult.Success)
        val envelope = (result as CredentialResult.Success).value
        assertArrayEquals("synthetic-catalog-access".toByteArray(), envelope.accessToken.copyBytes())
        assertArrayEquals("synthetic-catalog-refresh".toByteArray(), envelope.refreshToken!!.copyBytes())
        assertTrue(keys().containsAlias(KeystoreCredentialStore.alias(context, slot)))
    }
    private fun assertSidecar(text: String?) = SQLiteHistoryStore(historyDirectory).use { history ->
        assertTrue(history.adopt(partition) is HistoryAccessOutcome.Bound)
        val name = history.readName(partition)
        assertTrue(name is AccountNameRead.Ready)
        assertEquals(text, (name as AccountNameRead.Ready).name?.text)
    }

    private fun independentEntry(): CatalogEntry {
        val location = AccountStorage.fresh()
        slots += location.credentialSlot
        val store = KeystoreCredentialStore(context, location.credentialSlot)
        val generation = store.openSession()
        assertTrue(store.replace(CredentialEnvelope(generation, SensitiveValue.copyOf("synthetic-catalog-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-catalog-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
        return SQLiteHistoryStore(location.historyDirectory(root)).use { history ->
            val access = (history.createPartition() as HistoryAccessOutcome.Bound).access
            assertTrue(history.writeName(access.partition, AccountDisplayName.from("Synthetic work")))
            CatalogEntry(location, access.partition, "Synthetic work", true)
        }
    }

    private fun assertIndependentEntry(entry: CatalogEntry) {
        val store = KeystoreCredentialStore(context, entry.storage.credentialSlot)
        val result = store.read(store.openSession()) as CredentialResult.Success
        assertArrayEquals("synthetic-catalog-access".toByteArray(), result.value.accessToken.copyBytes())
        SQLiteHistoryStore(entry.storage.historyDirectory(root)).use { history ->
            val access = (history.adopt(entry.partition!!) as HistoryAccessOutcome.Bound).access
            assertEquals("Synthetic work", (history.readName(access.partition) as AccountNameRead.Ready).name!!.text)
            val snapshot = (access.read(HistoryReadQuery(access.partition, 10)) as HistoryReadOutcome.Ready).snapshot
            assertTrue(snapshot.entries.isEmpty())
            assertNull(snapshot.lastAdmitted)
        }
    }

    @Test fun installedLegacyCredentialsNameRecordsAndContinuationSurviveCatalogMigrationAndRepeatedRestoration() {
        seed()
        val ciphertext = credentialFile.readBytes()
        val before = export()
        val first = native(); phase(first, ConnectionPhase.RESTORED)
        val entry = catalog().legacyEntry()!!
        assertEquals(slot, entry.storage.credentialSlot)
        assertEquals(partition, entry.partition)
        assertEquals("Synthetic legacy", first.accountName.value!!.name!!.text)
        assertEquals(entry.storage.id, catalog().snapshot().selected)
        assertEquals(0L, transport.requests.get())
        stop(first)
        assertProtectedReadback()
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertEquals(before, export())
        assertSidecar("Synthetic legacy")
        val second = native(); phase(second, ConnectionPhase.RESTORED)
        assertEquals(entry, catalog().legacyEntry())
        assertEquals(1, catalog().snapshot().entries.size)
        assertEquals(0L, transport.requests.get())
        stop(second)
        SQLiteHistoryStore(historyDirectory).use { history ->
            val access = (history.adopt(partition) as HistoryAccessOutcome.Bound).access
            val page = (access.read(HistoryReadQuery(partition, 10)) as HistoryReadOutcome.Ready).snapshot
            assertEquals(listOf(2L, 3L), page.entries.map { it.id.ordinal })
            assertEquals(ObservationId(3), page.lastAdmitted)
            assertEquals(HistoryEvictionCutoff(1), page.cutoffs[HistoryTruncation.OBSERVATION_CAP])
            assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), access.append(admission(partition, 1)))
            assertTrue(access.append(admission(partition, 4)) is HistoryAppendOutcome.Stored)
            val continued = (access.read(HistoryReadQuery(partition, 10)) as HistoryReadOutcome.Ready).snapshot
            assertEquals(ObservationId(4), continued.lastAdmitted)
            assertEquals(ObservationId(1), continued.entries.last().windows.first().point!!.segment.id.first)
        }
        assertEquals(setOf("history.db", "binding", "lane", "account-name"), historyDirectory.list()!!.toSet())
    }

    @Test fun aliasedApplicationStorageRootMigratesAndRestoresTheSameProtectedLegacyAccount() {
        seed()
        val ciphertext = credentialFile.readBytes()
        val before = export()
        val aliased = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, ".")
        }
        assertNotEquals(aliased.noBackupFilesDir.absoluteFile, aliased.noBackupFilesDir.canonicalFile)
        assertEquals(slot, NativeConnection.session(aliased))
        val first = native(aliased); phase(first, ConnectionPhase.RESTORED)
        val entry = catalog().legacyEntry()!!
        assertEquals(slot, entry.storage.credentialSlot)
        assertEquals(partition, entry.partition)
        stop(first)
        val restored = native(); phase(restored, ConnectionPhase.RESTORED)
        assertEquals(entry, catalog().legacyEntry())
        stop(restored)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertEquals(before, export())
        assertSidecar("Synthetic legacy")
        assertEquals(0L, transport.requests.get())
    }

    @Test fun freshInstallStillSignsInAndRestoresDormantThroughTheCatalog() {
        val first = native(); phase(first, ConnectionPhase.IDLE)
        assertTrue(catalog().snapshot().entries.isEmpty())
        assertEquals(0L, transport.requests.get())
        first.connect(); phase(first, ConnectionPhase.OBSERVED)
        val entry = catalog().legacyEntry()!!
        assertNull(entry.name)
        assertTrue(entry.nameAvailable)
        val count = transport.requests.get()
        stop(first)
        val second = native(); phase(second, ConnectionPhase.RESTORED)
        assertEquals(entry, catalog().legacyEntry())
        assertEquals(count, transport.requests.get())
        second.signOut(); phase(second, ConnectionPhase.SIGNED_OUT)
        assertTrue(catalog().snapshot().entries.isEmpty())
        assertNull(catalog().snapshot().deleting)
        assertNull(catalog().snapshot().selected)
    }

    @Test fun restartCompletesRenameIntentOnEitherSideOfTheExistingNameFileCommit() {
        seed()
        val entry = catalog().adopt(partition, AccountNameRead.Ready(AccountDisplayName.from("Synthetic legacy")))
        val ciphertext = credentialFile.readBytes()
        assertTrue(catalog().reserveRename(entry.storage.id, AccountDisplayName.from("Synthetic first")))
        val first = native(); phase(first, ConnectionPhase.RESTORED)
        assertEquals("Synthetic first", first.accountName.value!!.name!!.text)
        stop(first); assertSidecar("Synthetic first")
        assertNull(catalog().snapshot().rename)
        assertTrue(catalog().reserveRename(entry.storage.id, AccountDisplayName.from("Synthetic second")))
        SQLiteHistoryStore(historyDirectory).use { history ->
            history.adopt(partition)
            assertTrue(history.writeName(partition, AccountDisplayName.from("Synthetic second")))
        }
        val second = native(); phase(second, ConnectionPhase.RESTORED)
        assertEquals("Synthetic second", second.accountName.value!!.name!!.text)
        stop(second); assertSidecar("Synthetic second")
        assertEquals(entry.storage, catalog().legacyEntry()!!.storage)
        assertNull(catalog().snapshot().rename)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertEquals(0L, transport.requests.get())
    }

    @Test fun existingRenameRejectsAnotherSavedKeyWithoutChangingEitherSidecarOrSelection() {
        seed()
        catalog().adopt(partition, AccountNameRead.Ready(AccountDisplayName.from("Synthetic legacy")))
        val other = independentEntry()
        assertTrue(catalog().insert(other))
        val selection = catalog().snapshot().selected
        val before = File(historyDirectory, "account-name").readBytes()
        val first = native(); phase(first, ConnectionPhase.RESTORED)
        first.renameAccount(first.accountName.value!!.edit!!, " Synthetic work ")
        awaitName(first, "Synthetic legacy", failed = true)
        assertEquals("Synthetic legacy", catalog().legacyEntry()!!.name)
        assertEquals(other, catalog().snapshot().entries.single { !it.storage.legacy })
        assertEquals(selection, catalog().snapshot().selected)
        assertArrayEquals(before, File(historyDirectory, "account-name").readBytes())
        first.renameAccount(first.accountName.value!!.edit!!, "Synthetic independent")
        awaitName(first, "Synthetic independent")
        stop(first); assertSidecar("Synthetic independent")
        assertEquals("Synthetic independent", catalog().legacyEntry()!!.name)
        assertProtectedReadback()
        assertIndependentEntry(other)
        assertEquals(0L, transport.requests.get())
    }

    @Test fun migrationCommitFailureBlocksAdoptionWithoutClearingValidLegacyData() {
        seed()
        catalog()
        val before = export()
        val ciphertext = credentialFile.readBytes()
        val blocked = AccountCatalog.open(root, { slot }, { throw IOException("synthetic interruption") })
        val store = credentials()
        val storage = CatalogHistoryStorage(SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory)), blocked)
        val owner = ConnectionController(store, DeviceCodeAuthenticator(transport, store, transport.clock, transport::pause),
            NativeFeasibilityReader(transport, transport.clock, transport::pause), CoroutineScope(SupervisorJob() + Dispatchers.IO),
            history = HistoryLifetimeCoordinator(storage), accountCatalog = blocked).also { owners += it }
        phase(owner, ConnectionPhase.FAILED)
        assertFalse(owner.session.snapshot() is SessionResult.Ready)
        assertTrue(catalog().snapshot().entries.isEmpty())
        stop(owner)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertEquals(before, export())
        val restored = native(); phase(restored, ConnectionPhase.RESTORED)
        assertEquals(1, catalog().snapshot().entries.size)
        assertEquals(0L, transport.requests.get())
    }

    @Test fun deletionReceiptWithAlreadyRemovedHistoryNeverRestoresStillReadableCredentials() {
        seed()
        catalog().adopt(partition, AccountNameRead.Ready(AccountDisplayName.from("Synthetic legacy")))
        catalog().beginDeletion()
        SQLiteHistoryStore(historyDirectory).use { assertTrue(it.reserveDeletion().complete().successful()) }
        assertProtectedReadback()
        val restored = native(); phase(restored, ConnectionPhase.REAUTH_REQUIRED)
        assertFalse(restored.session.snapshot() is SessionResult.Ready)
        assertFalse(credentialFile.exists())
        assertFalse(keys().containsAlias(KeystoreCredentialStore.alias(context, slot)))
        assertTrue(catalog().snapshot().entries.isEmpty())
        assertNull(catalog().snapshot().deleting)
        assertEquals(0L, transport.requests.get())
    }

    @Test fun missingCredentialsCleanOrphanedHistoryWithoutCreatingAnAccount() {
        seed()
        val store = credentials(); assertTrue(store.delete(store.openSession()) is CredentialResult.Success)
        val owner = native(); phase(owner, ConnectionPhase.IDLE)
        assertTrue(catalog().snapshot().entries.isEmpty())
        assertFalse(File(historyDirectory, "history.db").exists())
        assertEquals(0L, transport.requests.get())
    }

    @Test fun legacyHistoryDeletionFenceIsCompletedBeforeAnyCatalogEntryCanBePublished() {
        seed()
        val hooks = object : HistoryStorageHooks {
            override fun afterTombstone() { throw IOException("synthetic interrupted legacy deletion") }
        }
        SQLiteHistoryStore(historyDirectory, hooks = hooks).use {
            assertFalse(it.reserveDeletion().complete().successful())
        }
        val owner = native(); phase(owner, ConnectionPhase.REAUTH_REQUIRED)
        assertTrue(catalog().snapshot().entries.isEmpty())
        assertFalse(credentialFile.exists())
        assertEquals(0L, transport.requests.get())
    }

    @Test fun corruptCredentialsAreQuarantinedInsteadOfMigrated() { seed(); credentialFile.writeBytes(byteArrayOf(1)); rejectedCredentials() }
    @Test fun lostKeyIsQuarantinedInsteadOfMigrated() { seed(); keys().deleteEntry(KeystoreCredentialStore.alias(context, slot)); rejectedCredentials() }
    @Test fun rotationMarkerIsQuarantinedInsteadOfMigrated() {
        seed()
        val store = credentials(); assertTrue(store.beginRotation(store.openSession()) is CredentialResult.Success)
        rejectedCredentials()
    }
    private fun rejectedCredentials() {
        val owner = native(); phase(owner, ConnectionPhase.REAUTH_REQUIRED)
        assertFalse(owner.session.snapshot() is SessionResult.Ready)
        assertTrue(catalog().snapshot().entries.isEmpty())
        assertFalse(credentialFile.exists())
        assertFalse(File(historyDirectory, "history.db").exists())
        assertEquals(0L, transport.requests.get())
    }

    @Test fun catalogCorruptionOrMissingLegacySelectorCannotMakeValidLegacyDataFresh() {
        seed()
        val before = export()
        val ciphertext = credentialFile.readBytes()
        catalog()
        val target = File(root, "account-catalog/catalog")
        val committed = target.readBytes()
        target.writeBytes(byteArrayOf(1))
        val broken = native(); phase(broken, ConnectionPhase.FAILED); stop(broken)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertEquals(before, export())
        target.writeBytes(committed)
        assertTrue(target.delete())
        assertTrue(File(root, "connection-session").delete())
        val missing = native(); phase(missing, ConnectionPhase.FAILED); stop(missing)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertEquals(before, export())
        assertProtectedReadback()
        assertEquals(0L, transport.requests.get())
    }
}
