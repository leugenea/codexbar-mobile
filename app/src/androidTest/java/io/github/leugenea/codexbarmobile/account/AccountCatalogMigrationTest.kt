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
    private fun settled(owner: ConnectionController, step: String, receipt: suspend () -> Unit) = runBlocking {
        try { withTimeout(5_000) { receipt() } }
        catch (_: TimeoutCancellationException) {
            throw AssertionError("$step: state=${owner.state.value}, name=${owner.accountName.value}, history=${owner.historyAvailability}")
        }
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

    @Test fun transientHistoryLockKeepsTheUnknownEntryThenRecoversItsContinuingPartitionAndName() {
        seed()
        val ciphertext = credentialFile.readBytes()
        val before = export()
        lateinit var unknown: CatalogEntry
        lateinit var restored: ConnectionController
        SQLiteHistoryStore(historyDirectory).use { locked ->
            assertTrue(locked.adopt(partition) is HistoryAccessOutcome.Bound)
            val first = native(); phase(first, ConnectionPhase.RESTORED)
            unknown = catalog().legacyEntry()!!
            assertNull(unknown.partition)
            assertFalse(unknown.nameAvailable)
            assertEquals(unknown.storage.id, catalog().snapshot().selected)
            assertTrue(first.session.snapshot() is SessionResult.Ready)
            stop(first)
            restored = native(); phase(restored, ConnectionPhase.RESTORED)
            assertEquals(unknown, catalog().legacyEntry())
            assertEquals(HistoryAvailability.STORAGE_FAILURE, restored.historyAvailability)
        }
        restored.usageForeground(Any(), true)
        awaitName(restored, "Synthetic legacy")
        assertEquals(ConnectionPhase.RESTORED, restored.state.value.phase)
        assertEquals(HistoryAvailability.AVAILABLE, restored.historyAvailability)
        val recovered = catalog().legacyEntry()!!
        assertEquals(unknown.storage, recovered.storage)
        assertEquals(partition, recovered.partition)
        assertEquals(unknown.storage.id, catalog().snapshot().selected)
        assertEquals("Synthetic legacy", recovered.name)
        stop(restored)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertEquals(before, export())
        assertSidecar("Synthetic legacy")
        val next = native(); phase(next, ConnectionPhase.RESTORED)
        assertEquals(recovered, catalog().legacyEntry())
        assertEquals(0L, transport.requests.get())
        stop(next)
        SQLiteHistoryStore(historyDirectory).use { history ->
            val access = (history.adopt(partition) as HistoryAccessOutcome.Bound).access
            assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), access.append(admission(partition, 1)))
            assertTrue(access.append(admission(partition, 4)) is HistoryAppendOutcome.Stored)
            val page = (access.read(HistoryReadQuery(partition, 10)) as HistoryReadOutcome.Ready).snapshot
            assertEquals(ObservationId(4), page.lastAdmitted)
            assertEquals(ObservationId(1), page.entries.last().windows.first().point!!.segment.id.first)
        }
    }

    @Test fun knownPartitionRecoversInitiallyUnavailableAliasInTheContinuingOwner() = recoverNameOnly(false, false)
    @Test fun knownPartitionRecoversInitiallyUnavailableAliasAfterReconstruction() = recoverNameOnly(false, true)
    @Test fun knownPartitionRecoversInitiallyUnavailableUnnamedState() = recoverNameOnly(true, false)
    @Test fun knownPartitionRecoversUnavailableAliasOnActualBackgroundReturn() = recoverNameOnly(false, false, true)
    @Test fun knownPartitionRecoversUnavailableUnnamedStateOnActualBackgroundReturn() = recoverNameOnly(true, false, true)

    private fun recoverNameOnly(unnamed: Boolean, restart: Boolean, backgroundReturn: Boolean = false) {
        seed()
        if (unnamed) SQLiteHistoryStore(historyDirectory).use { history ->
            assertTrue(history.adopt(partition) is HistoryAccessOutcome.Bound)
            assertTrue(history.writeName(partition, null))
        }
        val ciphertext = credentialFile.readBytes()
        val before = export()
        // A regular interrupted pending file makes only the name unreadable, not history.
        val pending = File(historyDirectory, "account-name.pending").apply { writeBytes(byteArrayOf(1)) }
        var owner = native(); phase(owner, ConnectionPhase.RESTORED)
        val original = catalog().legacyEntry()!!
        assertEquals(partition, original.partition)
        assertFalse(original.nameAvailable)
        assertNotNull(owner.accountName.value!!.edit)
        assertTrue(owner.accountName.value!!.storageFailed)
        if (restart) {
            stop(owner)
            owner = native(); phase(owner, ConnectionPhase.RESTORED)
            assertEquals(original, catalog().legacyEntry())
        }
        val observer = Any()
        val edit = owner.accountName.value!!.edit
        if (backgroundReturn) {
            owner.usageForeground(observer, true)
            settled(owner, "unavailable metadata first foreground") {
                owner.commandsSettled(); owner.historyRestorationSettled()
            }
            owner.usageForeground(observer, false)
            settled(owner, "actual foreground loss") { owner.commandsSettled() }
            assertEquals(HistoryAvailability.UNAVAILABLE, owner.historyAvailability)
            assertTrue(owner.accountName.value!!.storageFailed)
        }
        assertTrue(pending.delete())
        owner.usageForeground(observer, true)
        val expected = if (unnamed) null else "Synthetic legacy"
        awaitName(owner, expected)
        assertSame(edit, owner.accountName.value!!.edit)
        assertEquals(HistoryAvailability.AVAILABLE, owner.historyAvailability)
        assertTrue(owner.session.snapshot() is SessionResult.Ready)
        val recovered = original.copy(name = expected, nameAvailable = true)
        assertEquals(recovered, catalog().legacyEntry())
        assertEquals(original.storage.id, catalog().snapshot().selected)
        stop(owner)
        val next = native(); phase(next, ConnectionPhase.RESTORED)
        assertEquals(recovered, catalog().legacyEntry())
        stop(next)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertEquals(before, export())
        assertSidecar(expected)
        assertEquals(0L, transport.requests.get())
    }

    @Test fun recoveredAliasConflictThroughTheWrapperPreservesBothEntriesAndSelection() {
        seed()
        SQLiteHistoryStore(historyDirectory).use { history ->
            assertTrue(history.adopt(partition) is HistoryAccessOutcome.Bound)
            assertTrue(history.writeName(partition, AccountDisplayName.from("Synthetic work")))
        }
        val before = export()
        val ciphertext = credentialFile.readBytes()
        val pending = File(historyDirectory, "account-name.pending").apply { writeBytes(byteArrayOf(1)) }
        val first = native(); phase(first, ConnectionPhase.RESTORED)
        val original = catalog().legacyEntry()!!
        assertEquals(partition, original.partition)
        assertFalse(original.nameAvailable)
        stop(first)
        val other = independentEntry()
        assertTrue(catalog().insert(other))
        val committed = catalog().snapshot()
        assertTrue(pending.delete())
        val blocked = native(); phase(blocked, ConnectionPhase.FAILED)
        assertFalse(blocked.session.snapshot() is SessionResult.Ready)
        assertEquals(committed, catalog().snapshot())
        stop(blocked)
        assertEquals(before, export())
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertIndependentEntry(other)
        assertEquals(0L, transport.requests.get())
    }

    @Test fun readableDifferentSidecarDoesNotReplaceAnAlreadyKnownCatalogKey() {
        seed()
        val first = native(); phase(first, ConnectionPhase.RESTORED)
        val original = catalog().legacyEntry()!!
        stop(first)
        SQLiteHistoryStore(historyDirectory).use { history ->
            assertTrue(history.adopt(partition) is HistoryAccessOutcome.Bound)
            assertTrue(history.writeName(partition, AccountDisplayName.from("Synthetic unexpected")))
        }
        val next = native(); phase(next, ConnectionPhase.RESTORED)
        assertTrue(next.session.snapshot() is SessionResult.Ready)
        assertTrue(next.accountName.value!!.storageFailed)
        assertNull(next.accountName.value!!.name)
        assertEquals(original, catalog().legacyEntry())
        assertFalse(catalog().insert(CatalogEntry(AccountStorage.fresh(), HistoryPartition(UUID.randomUUID()), original.name, true)))
        stop(next)
        assertProtectedReadback()
        assertEquals(0L, transport.requests.get())
    }

    private fun catalogOwner(accountCatalog: AccountCatalog, storage: HistoryLifetimeStorage,
        metadataCompletion: CompletableDeferred<Unit>? = null): ConnectionController {
        val catalogued = CatalogHistoryStorage(storage, accountCatalog)
        val observed = object : HistoryLifetimeStorage by catalogued {
            override fun reconcileMetadata(partition: HistoryPartition): Boolean =
                catalogued.reconcileMetadata(partition).also { metadataCompletion?.complete(Unit) }
        }
        val protected = credentials()
        return ConnectionController(protected,
            DeviceCodeAuthenticator(transport, protected, transport.clock, transport::pause),
            NativeFeasibilityReader(transport, transport.clock, transport::pause),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            history = HistoryLifetimeCoordinator(observed), accountCatalog = accountCatalog)
            .also { owners += it }
    }

    private fun assertReserved(original: CatalogEntry, intended: String?) {
        val durable = catalog()
        assertEquals(original, durable.legacyEntry())
        assertEquals(original.storage.id, durable.snapshot().selected)
        assertEquals(CatalogRename(original.storage.id, intended), durable.snapshot().rename)
        for (key in listOfNotNull(original.name, intended)) assertFalse(durable.insert(
            CatalogEntry(AccountStorage.fresh(), HistoryPartition(UUID.randomUUID()), key, true)))
    }

    @Test fun pendingRenameBeforeSidecarCommitKeepsRestoredCredentialsAndHealthyHistoryUsable() = pendingSidecar(false, false)
    @Test fun pendingRenameAfterSidecarCommitKeepsRestoredCredentialsAndHealthyHistoryUsable() = pendingSidecar(true, false)
    @Test fun pendingRenameDoesNotImportAnInitiallyUnknownOldNameBeforeSettlement() = pendingSidecar(false, true)
    @Test fun pendingRenameDoesNotImportAnInitiallyUnknownNameBeforeSettlement() = pendingSidecar(true, true)

    private fun pendingSidecar(replaced: Boolean, unknownName: Boolean) = runBlocking {
        seed()
        val ciphertext = credentialFile.readBytes()
        val pending = File(historyDirectory, "account-name.pending")
        if (unknownName) pending.writeBytes(byteArrayOf(1))
        val first = native(); phase(first, ConnectionPhase.RESTORED)
        val stale = first.accountName.value!!.edit!!
        val original = catalog().legacyEntry()!!
        assertEquals(!unknownName, original.nameAvailable)
        stop(first)
        if (unknownName) assertTrue(pending.delete())
        val intended = "Synthetic intended"
        assertTrue(catalog().reserveRename(original.storage.id, AccountDisplayName.from(intended)))
        if (replaced) SQLiteHistoryStore(historyDirectory).use { history ->
            assertTrue(history.adopt(partition) is HistoryAccessOutcome.Bound)
            assertTrue(history.writeName(partition, AccountDisplayName.from(intended)))
        }
        val beforeSidecar = File(historyDirectory, "account-name").readBytes()
        if (!unknownName) pending.writeBytes(byteArrayOf(1))
        val limits = HistoryStorageLimits(bytes = 262_144)
        // Fill the unformatted lane file without replacing its inode or bypassing its real lock.
        // Reads fit the minimum native directory budget; the extra sidecar allowance does not.
        val lane = File(historyDirectory, "lane")
        val padding = limits.bytes - historyDirectory.listFiles()!!.sumOf { it.length() } - 128
        assertTrue(padding > 0)
        java.io.RandomAccessFile(lane, "rw").use { it.setLength(padding) }
        val metadataCompletion = CompletableDeferred<Unit>()
        fun restored() = catalogOwner(catalog(), SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory, limits)), metadataCompletion)
        val interrupted = restored(); phase(interrupted, ConnectionPhase.RESTORED)
        assertTrue(interrupted.session.snapshot() is SessionResult.Ready)
        assertReserved(original, intended)
        stop(interrupted)
        val owner = restored(); phase(owner, ConnectionPhase.RESTORED)
        val unavailable = owner.accountName.value!!
        val edit = unavailable.edit!!
        assertTrue(unavailable.storageFailed)
        assertNull(unavailable.name)
        assertEquals(0L, transport.requests.get())
        owner.renameAccount(stale, "Synthetic stale")
        settled(owner, "pending-sidecar stale editor command") { owner.commandsSettled() }
        assertReserved(original, intended)
        val readyBefore = owner.session.snapshot() as SessionResult.Ready
        val accessBefore = owner.historyCapability(readyBefore.envelope.generation)!!
        owner.usageForeground(Any(), true)
        try { withTimeout(5_000) {
            metadataCompletion.await()
            owner.historyRestorationSettled()
        } } catch (_: TimeoutCancellationException) {
            throw AssertionError("pending-sidecar foreground reconciliation: state=${owner.state.value}, name=${owner.accountName.value}, history=${owner.historyAvailability}")
        }
        assertTrue(owner.accountName.value!!.storageFailed)
        assertSame(edit, owner.accountName.value!!.edit)
        assertSame(accessBefore, owner.historyCapability(readyBefore.envelope.generation))
        assertEquals(HistoryAvailability.AVAILABLE, owner.historyAvailability)
        val ready = owner.session.snapshot() as SessionResult.Ready
        assertNotNull(owner.historyCapability(ready.envelope.generation))
        owner.readUsage(); phase(owner, ConnectionPhase.OBSERVED)
        assertTrue(transport.gets.get() > 0)
        assertEquals(2L, transport.gets.get())
        assertNotNull(owner.state.value.refresh.usage.success?.usage)
        assertTrue(owner.session.snapshot() is SessionResult.Ready)
        assertReserved(original, intended)
        assertArrayEquals(beforeSidecar, File(historyDirectory, "account-name").readBytes())
        // Truncate in place: keep the existing locked inode and the same live editor.
        java.io.RandomAccessFile(lane, "rw").use { it.setLength(0) }
        if (!unknownName) assertTrue(pending.delete())
        owner.renameAccount(edit, intended)
        awaitName(owner, intended)
        assertSame(edit, owner.accountName.value!!.edit)
        assertEquals(original.copy(name = intended, nameAvailable = true), catalog().legacyEntry())
        assertNull(catalog().snapshot().rename)
        assertEquals(original.storage.id, catalog().snapshot().selected)
        stop(owner)
        assertSidecar(intended)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
    }

    @Test fun pendingRenameBeforeSidecarCommitSurvivesUnavailableHistoryAcrossRestart() = pendingHistory(false)
    @Test fun pendingRenameAfterSidecarCommitSurvivesUnavailableHistoryAcrossRestart() = pendingHistory(true)
    @Test fun pendingClearBeforeSidecarCommitSurvivesUnavailableHistoryAcrossRestart() = pendingHistory(false, true)
    @Test fun pendingClearAfterSidecarCommitSurvivesUnavailableHistoryAcrossRestart() = pendingHistory(true, true)

    private fun pendingHistory(replaced: Boolean, unnamed: Boolean = false) {
        seed()
        val ciphertext = credentialFile.readBytes()
        val original = catalog().adopt(partition, AccountNameRead.Ready(AccountDisplayName.from("Synthetic legacy")))
        val intended = if (unnamed) null else "Synthetic intended"
        val name = intended?.let(AccountDisplayName::from)
        assertTrue(catalog().reserveRename(original.storage.id, name))
        lateinit var owner: ConnectionController
        SQLiteHistoryStore(historyDirectory).use { locked ->
            assertTrue(locked.adopt(partition) is HistoryAccessOutcome.Bound)
            if (replaced) assertTrue(locked.writeName(partition, name))
            val first = native(); phase(first, ConnectionPhase.RESTORED)
            assertTrue(first.session.snapshot() is SessionResult.Ready)
            assertReserved(original, intended)
            stop(first)
            owner = native(); phase(owner, ConnectionPhase.RESTORED)
            assertTrue(owner.session.snapshot() is SessionResult.Ready)
            assertNull(owner.accountName.value!!.edit)
            assertTrue(owner.accountName.value!!.storageFailed)
            assertReserved(original, intended)
            assertEquals(0L, transport.requests.get())
        }
        owner.usageForeground(Any(), true)
        awaitName(owner, intended)
        assertEquals(HistoryAvailability.AVAILABLE, owner.historyAvailability)
        assertEquals(original.copy(name = intended), catalog().legacyEntry())
        assertEquals(original.storage.id, catalog().snapshot().selected)
        assertNull(catalog().snapshot().rename)
        stop(owner)
        val next = native(); phase(next, ConnectionPhase.RESTORED)
        stop(next)
        assertSidecar(intended)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertEquals(0L, transport.requests.get())
    }

    @Test fun nameRecoveryCommitFailureStillBlocksCredentialAdoption() = requiredMetadataCommitFailure(false)
    @Test fun renameFinalizationCommitFailureStillBlocksCredentialAdoption() = requiredMetadataCommitFailure(true)

    private fun requiredMetadataCommitFailure(rename: Boolean) {
        seed()
        val ciphertext = credentialFile.readBytes()
        val initial = if (rename) AccountNameRead.Ready(AccountDisplayName.from("Synthetic legacy")) else AccountNameRead.Unavailable
        val original = catalog().adopt(partition, initial)
        if (rename) assertTrue(catalog().reserveRename(original.storage.id, AccountDisplayName.from("Synthetic intended")))
        val before = catalog().snapshot()
        val blocked = AccountCatalog.open(root, { slot }) { throw IOException("synthetic required metadata commit failure") }
        val owner = catalogOwner(blocked, SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory)))
        phase(owner, ConnectionPhase.FAILED)
        assertFalse(owner.session.snapshot() is SessionResult.Ready)
        assertEquals(before, catalog().snapshot())
        assertEquals(0L, transport.requests.get())
        stop(owner)
        assertSidecar(if (rename) "Synthetic intended" else "Synthetic legacy")
        val next = native(); phase(next, ConnectionPhase.RESTORED)
        assertEquals(original.storage, catalog().legacyEntry()!!.storage)
        assertEquals(original.storage.id, catalog().snapshot().selected)
        assertTrue(catalog().legacyEntry()!!.nameAvailable)
        assertNull(catalog().snapshot().rename)
        stop(next)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertEquals(0L, transport.requests.get())
    }

    @Test fun laterNameCommitFailureOnFirstForegroundRetiresReadySession() = laterMetadataCommitFailure(false, false)
    @Test fun laterNameCommitFailureOnBackgroundReturnRetiresReadySession() = laterMetadataCommitFailure(false, true)
    @Test fun laterRenameFinalizationFailureOnFirstForegroundRetiresReadySession() = laterMetadataCommitFailure(true, false)
    @Test fun laterRenameFinalizationFailureOnBackgroundReturnRetiresReadySession() = laterMetadataCommitFailure(true, true)

    private fun laterMetadataCommitFailure(rename: Boolean, backgroundReturn: Boolean) {
        seed()
        val ciphertext = credentialFile.readBytes()
        val retained = export()
        val pending = File(historyDirectory, "account-name.pending")
        if (!rename) pending.writeBytes(byteArrayOf(1))
        val failCommit = java.util.concurrent.atomic.AtomicBoolean()
        val accountCatalog = AccountCatalog.open(root, { slot }) {
            if (failCommit.get()) throw IOException("synthetic later required catalog commit failure")
        }
        val owner = catalogOwner(accountCatalog, SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory)))
        phase(owner, ConnectionPhase.RESTORED)
        val ready = owner.session.snapshot() as SessionResult.Ready
        val access = owner.historyCapability(ready.envelope.generation)!!
        val editor = owner.accountName.value!!.edit!!
        val target = BackgroundRefreshTarget(partition)
        val observer = Any()
        if (backgroundReturn) {
            owner.usageForeground(observer, true)
            settled(owner, "later catalog failure foreground setup") {
                owner.commandsSettled(); owner.historyRestorationSettled()
            }
        }
        if (rename) {
            assertTrue(pending.mkdir())
            owner.renameAccount(editor, "Synthetic intended")
            awaitName(owner, "Synthetic legacy", failed = true)
        }
        val before = catalog().snapshot()
        if (backgroundReturn) {
            owner.usageForeground(observer, false)
            settled(owner, "later catalog failure foreground loss") { owner.commandsSettled() }
            assertEquals(HistoryAvailability.UNAVAILABLE, owner.historyAvailability)
        }
        assertTrue(pending.delete())
        failCommit.set(true)
        owner.usageForeground(observer, true)
        phase(owner, ConnectionPhase.FAILED)
        assertEquals(ConnectionProblem.STORAGE, owner.state.value.problem)
        assertFalse(owner.session.snapshot() is SessionResult.Ready)
        assertNull(owner.accountName.value)
        assertNull(owner.historyCapability(ready.envelope.generation))
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED),
            access.read(HistoryReadQuery(partition, 10)))
        owner.readUsage()
        owner.renameAccount(editor, "Synthetic stale")
        owner.usageForeground(observer, true)
        settled(owner, "retired catalog session rejects commands") { owner.commandsSettled() }
        settled(owner, "retired catalog session rejects worker") {
            assertEquals(BackgroundRefreshOutcome.Rejected(BackgroundRefreshRejection.STALE_LIFETIME),
                owner.refreshInBackground(target))
        }
        assertEquals(before, catalog().snapshot())
        assertEquals(0L, transport.requests.get())
        stop(owner)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertEquals(retained, export())
        val next = native(); phase(next, ConnectionPhase.RESTORED)
        assertEquals(before.entries.single().storage, catalog().legacyEntry()!!.storage)
        assertEquals(before.selected, catalog().snapshot().selected)
        assertNull(catalog().snapshot().rename)
        awaitName(next, if (rename) "Synthetic intended" else "Synthetic legacy")
        stop(next)
        assertSidecar(if (rename) "Synthetic intended" else "Synthetic legacy")
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertEquals(retained, export())
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

    @Test fun failedStagingStillCommitsUnknownCatalogSelectionBeforeTheFirstUsablePublication() = stagingFailure(false)

    @Test fun failedStagingCannotPublishWhenCatalogCommitFailsAfterProtectedAuthentication() = stagingFailure(true)

    private fun stagingFailure(failCatalog: Boolean) = runBlocking {
        File(root, "connection-session").writeText(slot.toString())
        val failCommit = java.util.concurrent.atomic.AtomicBoolean()
        val accountCatalog = AccountCatalog.open(root, { slot }) {
            if (failCommit.get()) throw IOException("synthetic post-auth catalog failure")
        }
        val storage = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory))
        var stageCalls = 0
        var restoreCalls = 0
        val interrupted = object : HistoryLifetimeStorage by storage {
            override fun restore(): HistoryLifetimeOutcome {
                restoreCalls++
                return storage.restore()
            }
            override fun stage(): HistoryPartitionOutcome {
                stageCalls++
                // Fail after the real durable STAGED fence, before returning its capability.
                assertTrue(storage.stage() is HistoryPartitionOutcome.Staged)
                failCommit.set(failCatalog)
                return HistoryPartitionOutcome.Unavailable(HistoryUnavailable.STORAGE_FULL)
            }
        }
        val protected = credentials()
        val owner = ConnectionController(protected,
            DeviceCodeAuthenticator(transport, protected, transport.clock, transport::pause),
            NativeFeasibilityReader(transport, transport.clock, transport::pause),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            history = HistoryLifetimeCoordinator(CatalogHistoryStorage(interrupted, accountCatalog)), accountCatalog = accountCatalog)
        owners += owner
        phase(owner, ConnectionPhase.IDLE)
        val publication = CompletableDeferred<CatalogSnapshot>()
        // Unconfined collection reads durable bytes at the first usable session publication.
        val witness = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            owner.state.first {
                if (owner.session.snapshot() !is SessionResult.Ready) false
                else { publication.complete(catalog().snapshot()); true }
            }
        }
        try {
            owner.connect()
            phase(owner, if (failCatalog) ConnectionPhase.FAILED else ConnectionPhase.OBSERVED)
            assertEquals(1, stageCalls)
            assertEquals(1, restoreCalls)
            assertEquals(HistoryAvailability.STORAGE_FAILURE, owner.historyAvailability)
            if (failCatalog) {
                assertFalse(publication.isCompleted)
                assertFalse(owner.session.snapshot() is SessionResult.Ready)
                assertTrue(catalog().snapshot().entries.isEmpty())
                assertNull(catalog().snapshot().selected)
                assertEquals(0L, transport.gets.get())
            } else {
                val published = withTimeout(5_000) { publication.await() }
                assertEquals(1, published.entries.size)
                assertEquals(published.entries.single().storage.id, published.selected)
                assertNull(published.entries.single().partition)
                assertFalse(published.entries.single().nameAvailable)
                assertEquals(published, catalog().snapshot())
            }
        } finally { witness.cancelAndJoin() }
        stop(owner)
        val ciphertext = credentialFile.readBytes()
        val result = credentials().let { it.read(it.openSession()) } as CredentialResult.Success
        assertArrayEquals("synthetic-navigation-access".toByteArray(), result.value.accessToken.copyBytes())
        assertArrayEquals("synthetic-navigation-refresh".toByteArray(), result.value.refreshToken!!.copyBytes())
        failCommit.set(false)
        val count = transport.requests.get()
        val restarted = native(); phase(restarted, ConnectionPhase.RESTORED)
        assertNotNull(catalog().legacyEntry())
        assertEquals(catalog().legacyEntry()!!.storage.id, catalog().snapshot().selected)
        assertEquals(count, transport.requests.get())
        assertArrayEquals(ciphertext, credentialFile.readBytes())
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

    @Test fun sidecarFailureCanBeRetriedThroughTheCatalogWithTheSameLiveEditor() {
        seed()
        val ciphertext = credentialFile.readBytes()
        val first = native(); phase(first, ConnectionPhase.RESTORED)
        val original = catalog().legacyEntry()!!
        val edit = first.accountName.value!!.edit!!
        var previous = "Synthetic legacy"
        for ((intended, retry) in listOf("Synthetic first" to "Synthetic first", "Synthetic pending" to "Synthetic final")) {
            val before = File(historyDirectory, "account-name").readBytes()
            // A non-file at the real pending sidecar path fails the native write budget check.
            val obstruction = File(historyDirectory, "account-name.pending")
            assertTrue(obstruction.mkdir())
            try {
                first.renameAccount(edit, intended)
                awaitName(first, previous, failed = true)
                val reserved = catalog()
                assertEquals(CatalogRename(original.storage.id, intended), reserved.snapshot().rename)
                for (key in listOf(previous, intended)) assertFalse(reserved.insert(
                    CatalogEntry(AccountStorage.fresh(), HistoryPartition(UUID.randomUUID()), key, true)))
                assertEquals(original.storage.id, reserved.snapshot().selected)
                assertArrayEquals(before, File(historyDirectory, "account-name").readBytes())
                assertTrue(first.session.snapshot() is SessionResult.Ready)
                assertEquals(ConnectionPhase.RESTORED, first.state.value.phase)
            } finally { assertTrue(obstruction.delete()) }
            first.renameAccount(edit, retry)
            awaitName(first, retry)
            assertSame(edit, first.accountName.value!!.edit)
            val committed = catalog()
            assertNull(committed.snapshot().rename)
            assertEquals(original.copy(name = retry), committed.legacyEntry())
            assertEquals(original.storage.id, committed.snapshot().selected)
            previous = retry
        }
        stop(first)
        assertSidecar("Synthetic final")
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
