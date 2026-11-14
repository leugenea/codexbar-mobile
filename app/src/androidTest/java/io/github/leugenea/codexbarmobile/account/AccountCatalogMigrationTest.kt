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
        runtimeGates.forEach { it.release() }
        runtimeOwners.forEach { attempt { stop(it) } }
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
        try { withTimeout(5_000) {
            owner.commandsSettled()
            owner.state.first { it.phase == expected && !it.refresh.refreshing }
        } }
        catch (_: TimeoutCancellationException) { throw AssertionError("catalog phase $expected: last state=${owner.state.value}") }
    }
    private fun awaitName(owner: ConnectionController, expected: String?, failed: Boolean = false) = runBlocking {
        try { withTimeout(5_000) { owner.accountName.first { it != null && it.storageFailed == failed && it.name?.text == expected } } }
        catch (_: TimeoutCancellationException) { throw AssertionError("catalog name settlement: last state=${owner.accountName.value}") }
    }
    private fun <T> settled(owner: ConnectionController, step: String, receipt: suspend () -> T): T = runBlocking {
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
            history = HistoryLifetimeCoordinator(observed))
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
    @Test fun renameFinalizationFailureKeepsAnAlreadyCataloguedRestorationUsable() = requiredMetadataCommitFailure(true)

    private fun requiredMetadataCommitFailure(rename: Boolean) {
        seed()
        val ciphertext = credentialFile.readBytes()
        val initial = if (rename) AccountNameRead.Ready(AccountDisplayName.from("Synthetic legacy")) else AccountNameRead.Unavailable
        val original = catalog().adopt(partition, initial)
        if (rename) assertTrue(catalog().reserveRename(original.storage.id, AccountDisplayName.from("Synthetic intended")))
        val before = catalog().snapshot()
        val failedCommit = CompletableDeferred<Unit>()
        val blocked = AccountCatalog.open(root, { slot }, beforeRename = {
            failedCommit.complete(Unit)
            throw IOException("synthetic required metadata commit failure")
        })
        val owner = catalogOwner(blocked, SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory)))
        settled(owner, "required metadata beforeRename fault entered") { failedCommit.await() }
        phase(owner, if (rename) ConnectionPhase.RESTORED else ConnectionPhase.FAILED)
        assertEquals(rename, owner.session.snapshot() is SessionResult.Ready)
        if (rename) {
            assertTrue(owner.accountName.value!!.storageFailed)
            assertReserved(original, "Synthetic intended")
        }
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

    @Test fun laterNameCommitFailureOnFirstForegroundRetiresReadySession() = laterMetadataCommitFailure(false)
    @Test fun laterNameCommitFailureOnBackgroundReturnRetiresReadySession() = laterMetadataCommitFailure(true)
    @Test fun liveRenameFinalizationErrorKeepsQuotaAndReservationsAcrossFirstForeground() = liveRenameFinalizationFailure(false)
    @Test fun liveRenameFinalizationErrorKeepsQuotaAndReservationsAcrossBackgroundReturn() = liveRenameFinalizationFailure(true)

    private fun laterMetadataCommitFailure(backgroundReturn: Boolean) {
        seed()
        val ciphertext = credentialFile.readBytes()
        val retained = export()
        val pending = File(historyDirectory, "account-name.pending")
        pending.writeBytes(byteArrayOf(1))
        val failCommit = java.util.concurrent.atomic.AtomicBoolean()
        val failedCommit = CompletableDeferred<Unit>()
        val accountCatalog = AccountCatalog.open(root, { slot }, beforeRename = {
            if (failCommit.get()) {
                failedCommit.complete(Unit)
                throw IOException("synthetic later required catalog commit failure")
            }
        })
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
        val before = catalog().snapshot()
        if (backgroundReturn) {
            owner.usageForeground(observer, false)
            settled(owner, "later catalog failure foreground loss") { owner.commandsSettled() }
            assertEquals(HistoryAvailability.UNAVAILABLE, owner.historyAvailability)
        }
        assertTrue(pending.delete())
        failCommit.set(true)
        owner.usageForeground(observer, true)
        settled(owner, "later required metadata beforeRename fault entered") { failedCommit.await() }
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
        awaitName(next, "Synthetic legacy")
        stop(next)
        assertSidecar("Synthetic legacy")
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertEquals(retained, export())
        assertEquals(0L, transport.requests.get())
    }

    private fun liveRenameFinalizationFailure(backgroundReturn: Boolean) {
        seed()
        val ciphertext = credentialFile.readBytes()
        val retained = export()
        val failCommit = java.util.concurrent.atomic.AtomicBoolean()
        val failedReplay = CompletableDeferred<Unit>()
        val failedCommit = CompletableDeferred<Unit>()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val accountCatalog = AccountCatalog.open(root, { slot }, beforeRename = {
            // Reservation commits; fail only finalization after the real sidecar write.
            if (failCommit.get() && catalog().snapshot().rename != null) {
                failedCommit.complete(Unit)
                if (attempts.incrementAndGet() > 1) failedReplay.complete(Unit)
                throw IOException("synthetic rename-only finalization failure")
            }
        })
        val owner = catalogOwner(accountCatalog, SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory)))
        phase(owner, ConnectionPhase.RESTORED)
        val original = catalog().legacyEntry()!!
        val editor = owner.accountName.value!!.edit!!
        val ready = owner.session.snapshot() as SessionResult.Ready
        val access = owner.historyCapability(ready.envelope.generation)!!
        val observer = Any()
        if (backgroundReturn) {
            owner.usageForeground(observer, true)
            settled(owner, "rename finalization foreground setup") { owner.commandsSettled() }
        }
        val beforeSidecar = File(historyDirectory, "account-name").readBytes()
        failCommit.set(true)
        owner.renameAccount(editor, "Synthetic intended")
        settled(owner, "rename finalization beforeRename fault entered") { failedCommit.await() }
        awaitName(owner, "Synthetic legacy", failed = true)
        assertTrue("Finalization fault ran after its durable reservation", attempts.get() > 0)
        assertFalse(beforeSidecar.contentEquals(File(historyDirectory, "account-name").readBytes()))
        assertSame(access, owner.historyCapability(ready.envelope.generation))
        assertTrue(owner.session.snapshot() is SessionResult.Ready)
        assertReserved(original, "Synthetic intended")
        // A different requested key cannot replace an unsettled operation.
        owner.renameAccount(editor, "Synthetic later")
        settled(owner, "rename retry reached the same failed finalization") { failedReplay.await() }
        assertReserved(original, "Synthetic intended")
        if (backgroundReturn) {
            owner.usageForeground(observer, false)
            settled(owner, "rename finalization foreground loss") { owner.commandsSettled() }
        }
        owner.usageForeground(observer, true)
        settled(owner, "rename-only recovery remains usable") {
            owner.commandsSettled(); owner.historyRestorationSettled()
        }
        assertTrue(owner.session.snapshot() is SessionResult.Ready)
        assertTrue(owner.accountName.value!!.storageFailed)
        assertSame(editor, owner.accountName.value!!.edit)
        assertEquals(HistoryAvailability.AVAILABLE, owner.historyAvailability)
        assertReserved(original, "Synthetic intended")
        settled(owner, "rename-only failure retains history") {
            assertEquals(retained, owner.exportHistory("synthetic", at))
        }
        owner.readUsage(); phase(owner, ConnectionPhase.OBSERVED)
        assertEquals(2L, transport.gets.get())
        assertNotNull(owner.state.value.refresh.usage.success?.usage)
        failCommit.set(false)
        owner.renameAccount(editor, "Synthetic later")
        awaitName(owner, "Synthetic later")
        assertNull(catalog().snapshot().rename)
        assertEquals(original.copy(name = "Synthetic later"), catalog().legacyEntry())
        stop(owner)
        assertArrayEquals(ciphertext, credentialFile.readBytes())
        assertProtectedReadback()
        assertSidecar("Synthetic later")
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
        assertTrue(catalog().snapshot().deletions.isEmpty())
        assertNull(catalog().snapshot().selected)
    }

    @Test fun failedStagingStillCommitsUnknownCatalogSelectionBeforeTheFirstUsablePublication() = stagingFailure(false)

    @Test fun failedStagingCannotPublishWhenCatalogCommitFailsAfterProtectedAuthentication() = stagingFailure(true)

    private fun stagingFailure(failCatalog: Boolean) = runBlocking {
        File(root, "connection-session").writeText(slot.toString())
        val failCommit = java.util.concurrent.atomic.AtomicBoolean()
        val failedCommit = CompletableDeferred<Unit>()
        val accountCatalog = AccountCatalog.open(root, { slot }, beforeRename = {
            if (failCommit.get()) {
                failedCommit.complete(Unit)
                throw IOException("synthetic post-auth catalog failure")
            }
        })
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
            history = HistoryLifetimeCoordinator(CatalogHistoryStorage(interrupted, accountCatalog)))
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
            if (failCatalog) settled(owner, "post-auth adoption beforeRename fault entered") { failedCommit.await() }
            phase(owner, if (failCatalog) ConnectionPhase.FAILED else ConnectionPhase.OBSERVED)
            assertEquals("Only the armed commit fault ran", failCatalog, failedCommit.isCompleted)
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
        val blocked = AccountCatalog.open(root, { slot }, beforeRename = { throw IOException("synthetic interruption") })
        val store = credentials()
        val storage = CatalogHistoryStorage(SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory)), blocked)
        val owner = ConnectionController(store, DeviceCodeAuthenticator(transport, store, transport.clock, transport::pause),
            NativeFeasibilityReader(transport, transport.clock, transport::pause), CoroutineScope(SupervisorJob() + Dispatchers.IO),
            history = HistoryLifetimeCoordinator(storage)).also { owners += it }
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
        assertTrue(catalog().snapshot().deletions.isEmpty())
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
    private val runtimeOwners = mutableListOf<AccountRuntimeOwner>()
    private val runtimeGates = mutableListOf<RuntimeGate>()
    private fun gate() = RuntimeGate().also { runtimeGates += it }
    private fun runtimeOwner(fake: io.github.leugenea.codexbarmobile.auth.AuthTransport = transport,
        clock: io.github.leugenea.codexbarmobile.transport.TransportClock = transport.clock,
        metadata: AccountCatalog = catalog(),
        decorate: (UUID, CredentialStore) -> CredentialStore = { _, store -> store },
        history: (File) -> HistoryLifetimeStorage = { SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(it)) },
    ) = AccountRuntimeOwner(context, metadata, fake, clock, transport::pause,
        credentials = { app, id -> decorate(id, KeystoreCredentialStore(app, id)) }, historyStorage = history)
        .also { runtimeOwners += it }
    private fun pair(): Pair<CatalogEntry, CatalogEntry> {
        seed()
        val a = catalog().adopt(partition, AccountNameRead.Ready(AccountDisplayName.from("Synthetic legacy")))
        val b = independentEntry().also { assertTrue(catalog().insert(it)) }
        return a to b
    }
    private fun bytes(entry: CatalogEntry): Map<String, List<Byte>> {
        val files = entry.storage.historyDirectory(root).walkTopDown().filter { it.isFile }.map {
            it.relativeTo(root).path to it.readBytes().toList()
        }.toMap()
        val credential = KeystoreCredentialStore.file(context, entry.storage.credentialSlot)
        return files + (credential.relativeTo(root).path to credential.readBytes().toList())
    }
    private fun ready(owner: ConnectionController) = (owner.session.snapshot() as SessionResult.Ready).envelope
    private fun stop(root: AccountRuntimeOwner) = runBlocking {
        try { withTimeout(5_000) { root.shutdown() } }
        catch (_: TimeoutCancellationException) { throw AssertionError("catalog runtime root shutdown: last state=shutdown incomplete") }
    }

    @Test fun twoProtectedRuntimesShareOnlyTheProcessLaneAndKeepStableObserverAndShutdownOwnership() = runBlocking {
        val (a, b) = pair()
        val beforeA = bytes(a); val beforeB = bytes(b)
        val rootOwner = runtimeOwner()
        val lookups = List(8) { async(Dispatchers.Default) { rootOwner.runtime(a.storage.id)!! } }
        val clients = try { withTimeout(5_000) { lookups.awaitAll() } }
        catch (_: TimeoutCancellationException) {
            throw AssertionError("concurrent A runtime lookups: last state=${lookups.count { it.isCompleted }}/8 completed")
        }
        val first = clients.first(); val second = rootOwner.runtime(b.storage.id)!!
        phase(first, ConnectionPhase.RESTORED); phase(second, ConnectionPhase.RESTORED)
        clients.forEach { assertSame(first, it) }
        assertSame(first, rootOwner.legacy())
        assertSame(second, rootOwner.runtime(b.storage.id))
        assertNull(rootOwner.runtime(UUID.randomUUID()))
        assertNotSame(ready(first).generation, ready(second).generation)
        assertEquals("Synthetic legacy", first.accountName.value!!.name!!.text)
        assertEquals("Synthetic work", second.accountName.value!!.name!!.text)
        val observerA = Any(); val observerB = Any()
        first.usageForeground(observerA, true); first.usageForeground(observerB, true)
        first.usageForeground(observerA, false)
        settled(first, "A observer commands") { first.commandsSettled() }
        settled(first, "A observer history restoration") { first.historyRestorationSettled() }
        assertEquals(0L, transport.requests.get())
        assertEquals(beforeA, bytes(a)); assertEquals(beforeB, bytes(b))
        val firstGeneration = ready(first).generation
        val peer = ready(second)
        stop(first) // A subordinate close must leave the root/peer usable.
        assertSame(peer, ready(second))
        val bTarget = settled(second, "B background target after A shutdown") { second.backgroundRefreshTarget() }!!
        val bResult = settled(second, "B background recording after A shutdown") {
            second.refreshInBackground(bTarget)
        } as BackgroundRefreshOutcome.Completed
        assertEquals(HistoryRecordingOutcome.Stored(ObservationId(1)), bResult.history)
        phase(second, ConnectionPhase.OBSERVED)
        assertEquals(2L, transport.requests.get())
        assertEquals(beforeA, bytes(a))
        stop(rootOwner)
        val restarted = runtimeOwner()
        val restoredA = restarted.runtime(a.storage.id)!!; val restoredB = restarted.runtime(b.storage.id)!!
        phase(restoredA, ConnectionPhase.RESTORED); phase(restoredB, ConnectionPhase.RESTORED)
        assertNotSame(firstGeneration, ready(restoredA).generation)
        assertEquals(beforeA, bytes(a))
        assertEquals(2L, transport.requests.get())
        val continued = settled(restoredB, "B background recording after reconstruction") {
            restoredB.refreshInBackground(bTarget)
        } as BackgroundRefreshOutcome.Completed
        assertEquals(HistoryRecordingOutcome.Stored(ObservationId(2)), continued.history)
        assertEquals(4L, transport.requests.get())
        assertEquals(beforeA, bytes(a))
        stop(restarted)
        SQLiteHistoryStore(b.storage.historyDirectory(root)).use { store ->
            val access = (store.adopt(b.partition!!) as HistoryAccessOutcome.Bound).access
            val page = (access.read(HistoryReadQuery(b.partition, 10)) as HistoryReadOutcome.Ready).snapshot
            assertEquals(listOf(1L, 2L), page.entries.map { it.id.ordinal })
            assertEquals(ObservationId(2), page.lastAdmitted)
            assertEquals("Synthetic work", (store.readName(b.partition) as AccountNameRead.Ready).name!!.text)
        }
    }

    @Test fun retainedSlotGuardRejectsASecondOwnerAfterCatalogReaddressingWithoutTouchingThePeer() {
        val (a, b) = pair()
        val metadata = catalog()
        val rootOwner = AccountRuntimeOwner(context, metadata, transport, transport.clock, transport::pause)
            .also { runtimeOwners += it }
        val first = rootOwner.runtime(a.storage.id)!!; val peer = rootOwner.runtime(b.storage.id)!!
        phase(first, ConnectionPhase.RESTORED); phase(peer, ConnectionPhase.RESTORED)
        val peerEnvelope = ready(peer); val peerBytes = bytes(b)
        first.signOut(); phase(first, ConnectionPhase.SIGNED_OUT)
        val rebound = CatalogEntry(AccountStorage(UUID.randomUUID(), a.storage.credentialSlot), null, "Synthetic rebound", true)
        assertTrue(metadata.insert(rebound))
        assertThrows(IllegalStateException::class.java) { rootOwner.runtime(rebound.storage.id) }
        assertNull(rootOwner.runtime(a.storage.id))
        assertSame(first, rootOwner.legacy()); assertSame(peer, rootOwner.runtime(b.storage.id))
        assertSame(peerEnvelope, ready(peer)); assertEquals(peerBytes, bytes(b))
        assertFalse(credentialFile.exists()); assertEquals(0L, transport.requests.get())
    }

    @Test fun detachedRotationWaiterKeepsTheOriginalProtectedRuntimeAndNeverWritesThePeer() = runBlocking {
        val (a, b) = pair()
        val fake = SyntheticSamplingTransport().apply { heldPath = "/oauth/token" }
        val write = gate()
        val rootOwner = runtimeOwner(fake, fake.clock, decorate = { id, store ->
            if (id != a.storage.credentialSlot) store else object : CredentialStore by store {
                override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> {
                    write.pause("A token write")
                    return store.replace(envelope, cancellation)
                }
            }
        })
        val first = rootOwner.runtime(a.storage.id)!!; val peer = rootOwner.runtime(b.storage.id)!!
        phase(first, ConnectionPhase.RESTORED); phase(peer, ConnectionPhase.RESTORED)
        val original = ready(first); val peerEnvelope = ready(peer); val beforePeer = bytes(b)
        val observer = Any()
        first.usageForeground(observer, true)
        settled(first, "A rotation observer attached") { first.commandsSettled() }
        first.readUsage(refreshSession = true)
        val sent = fake.awaitHeld("A rotation sent")
        assertTrue(File(credentialFile.parentFile, "rotation-pending").exists())
        try {
            first.usageForeground(observer, false)
            settled(first, "A rotation observer detached") { first.commandsSettled() }
            assertSame(first, rootOwner.runtime(a.storage.id))
            assertSame(original, ready(first))
            phase(first, ConnectionPhase.OBSERVED) // Actual cancelled read completion, before token settlement.
            sent.reply(fake.tokens)
            write.await("A accepted token persistence")
            assertSame(peerEnvelope, ready(peer)); assertEquals(beforePeer, bytes(b))
            assertTrue(File(credentialFile.parentFile, "rotation-pending").exists())
        } finally { write.release() }
        awaitRuntime("A rotation published after detached waiter", { first.session.snapshot().toString() }) {
            (first.session.snapshot() as? SessionResult.Ready)?.let { it.envelope !== original } == true
        }
        val target = settled(first, "A background target after rotation") { first.backgroundRefreshTarget() }!!
        val result = settled(first, "A background recording after rotation") { first.refreshInBackground(target) }
        assertTrue(result is BackgroundRefreshOutcome.Completed)
        assertEquals(HistoryRecordingOutcome.Stored(ObservationId(5)), (result as BackgroundRefreshOutcome.Completed).history)
        assertSame(original.generation, ready(first).generation)
        assertNotSame(original, ready(first))
        assertSame(peerEnvelope, ready(peer)); assertEquals(beforePeer, bytes(b))
        assertEquals(3L, fake.requests.get()) // One token POST and exactly two GETs.
        stop(rootOwner)
        val readback = credentials(); val restored = readback.read(readback.openSession()) as CredentialResult.Success
        assertArrayEquals("synthetic-sampling-access".toByteArray(), restored.value.accessToken.copyBytes())
        assertFalse(File(credentialFile.parentFile, "rotation-pending").exists())
        SQLiteHistoryStore(a.storage.historyDirectory(root)).use { store ->
            val access = (store.adopt(a.partition!!) as HistoryAccessOutcome.Bound).access
            val page = (access.read(HistoryReadQuery(a.partition, 10)) as HistoryReadOutcome.Ready).snapshot
            assertEquals(listOf(2L, 3L, 4L, 5L), page.entries.map { it.id.ordinal })
            assertEquals(HistoryGap.BACKGROUND, page.entries[2].gap)
            assertTrue(page.entries[2].windows.isEmpty())
            assertNull(page.entries[3].gap)
            assertEquals(ObservationId(5), page.lastAdmitted)
            assertEquals(listOf(WindowKind.FIVE_HOUR, WindowKind.WEEKLY), page.entries[3].windows.map { it.kind })
            assertEquals(BigDecimal("12.375"), page.entries[3].windows.first().point!!.usedPercent)
            assertNull(page.entries[3].windows.last().point)
        }
        assertEquals(beforePeer, bytes(b))
    }

    @Test fun healthyReplacementCapturesRemoveBeforeRotationQuarantineAndPublishesUnnamedSuccessor() {
        val (a, b) = pair()
        val peerBytes = bytes(b)
        val fake = SyntheticSamplingTransport().apply { heldPath = "/oauth/token" }
        val credentialBarrier = gate(); val historyBarrier = gate()
        val rootOwner = runtimeOwner(fake, fake.clock, decorate = { id, store ->
            if (id != a.storage.credentialSlot) store else object : CredentialStore by store {
                override fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion> {
                    val admitted = store.admitDeletion(generation)
                    if (admitted !is CredentialResult.Success) return admitted
                    return CredentialResult.Success(object : CredentialDeletion {
                        override fun complete(): CredentialResult<Unit> {
                            credentialBarrier.pause("replacement protected deletion")
                            return admitted.value.complete()
                        }
                    })
                }
            }
        }, history = { directory ->
            val native = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(directory))
            if (directory != historyDirectory) native else object : HistoryLifetimeStorage by native {
                override fun reserveDeletion(disposition: CleanupDisposition): HistoryDeletion {
                    val ticket = native.reserveDeletion()
                    return HistoryDeletion { historyBarrier.pause("replacement history deletion"); ticket.complete() }
                }
            }
        })
        val first = rootOwner.legacy(); val peer = rootOwner.runtime(b.storage.id)!!
        phase(first, ConnectionPhase.RESTORED); phase(peer, ConnectionPhase.RESTORED)
        val predecessor = ready(first); val edit = first.accountName.value!!.edit!!
        val ciphertext = credentialFile.readBytes()
        val observer = Any()
        first.usageForeground(observer, true)
        settled(first, "replacement foreground attached") { first.commandsSettled() }
        first.readUsage(refreshSession = true)
        val late = fake.awaitHeld("replacement accepted rotation")
        first.usageForeground(observer, false)
        phase(first, ConnectionPhase.OBSERVED) // Detach the read waiter, not the accepted rotation.
        assertSame(predecessor, ready(first))
        fake.heldPath = null
        first.connect()
        try {
            settled(first, "healthy replacement command captured intent") { first.commandsSettled() }
            credentialBarrier.await("replacement protected barrier entered")
            historyBarrier.await("replacement history barrier entered")
            val receipt = catalog().snapshot().deletions.single()
            assertEquals(a.storage.id, receipt.entry)
            assertEquals(CleanupDisposition.REMOVE, receipt.disposition)
            assertEquals(a, catalog().entry(a.storage.id))
            assertArrayEquals(ciphertext, credentialFile.readBytes())
            assertTrue(File(historyDirectory, "account-name").isFile)
            assertFalse(first.session.snapshot() is SessionResult.Ready)
            assertNull(first.historyCapability(predecessor.generation))
            assertEquals(1L, fake.requests.get()) // No successor authentication before both barriers.
            assertEquals(peerBytes, bytes(b))
            first.usageForeground(observer, true)
            settled(first, "replacement foreground restored while cleanup is held") { first.commandsSettled() }
        } finally { credentialBarrier.release(); historyBarrier.release() }
        phase(first, ConnectionPhase.OBSERVED)
        val successor = catalog().legacyEntry()!!
        assertNotEquals(a.storage.id, successor.storage.id)
        assertNotEquals(a.partition, successor.partition)
        assertNull(successor.name)
        assertNull(first.accountName.value!!.name)
        assertNotSame(edit, first.accountName.value!!.edit)
        assertFalse(File(historyDirectory, "account-name").exists())
        assertFalse(File(historyDirectory, "account-name.pending").exists())
        assertTrue(catalog().snapshot().deletions.isEmpty())
        val saved = credentialFile.readBytes()
        late.reply(fake.tokens)
        first.renameAccount(edit, "Synthetic stale replacement edit")
        settled(first, "replacement rejects predecessor callbacks") { first.commandsSettled() }
        assertArrayEquals(saved, credentialFile.readBytes())
        assertNull(first.accountName.value!!.name)
        assertEquals(peerBytes, bytes(b))
        assertEquals(6L, fake.requests.get()) // Old rotation plus one new auth and two quota endpoints.
    }

    @Test fun reauthorizationReconnectRetainsCatalogIdentityNameAndSelection() {
        val (a, b) = pair()
        val peerBytes = bytes(b)
        val fake = SyntheticSamplingTransport().apply {
            tokens = io.github.leugenea.codexbarmobile.transport.TransportResult.Response.bounded(401, "{}".toByteArray())
        }
        val firstRoot = runtimeOwner(fake, fake.clock)
        val first = firstRoot.legacy()
        phase(first, ConnectionPhase.RESTORED)
        val old = ready(first)
        first.readUsage(refreshSession = true); phase(first, ConnectionPhase.REAUTH_REQUIRED)
        assertEquals(a.copy(partition = null), catalog().entry(a.storage.id))
        assertEquals(a.storage.id, catalog().snapshot().selected)
        assertFalse(credentialFile.exists())
        assertFalse(File(historyDirectory, "account-name").exists())
        assertNull(first.historyCapability(old.generation))
        assertEquals(peerBytes, bytes(b))
        stop(firstRoot)
        val restarted = runtimeOwner(fake, fake.clock)
        val reauth = restarted.legacy(); phase(reauth, ConnectionPhase.REAUTH_REQUIRED)
        assertEquals(1L, fake.requests.get()) // Retained restoration stays dormant.
        fake.tokens = io.github.leugenea.codexbarmobile.transport.TransportResult.Response.bounded(200,
            """{"access_token":"synthetic-reauthorized-access","refresh_token":"synthetic-reauthorized-refresh"}""".toByteArray())
        reauth.connect(); phase(reauth, ConnectionPhase.OBSERVED)
        val saved = catalog().entry(a.storage.id)!!
        assertEquals(a.storage, saved.storage)
        assertNotEquals(a.partition, saved.partition)
        assertEquals(a.name, saved.name)
        assertEquals(a.storage.id, catalog().snapshot().selected)
        assertEquals(a.name, reauth.accountName.value!!.name!!.text)
        assertTrue(catalog().snapshot().deletions.isEmpty())
        assertEquals(peerBytes, bytes(b))
        assertEquals(6L, fake.requests.get())
        stop(restarted)
        SQLiteHistoryStore(historyDirectory).use { history ->
            assertTrue(history.adopt(saved.partition!!) is HistoryAccessOutcome.Bound)
            assertEquals(a.name, (history.readName(saved.partition) as AccountNameRead.Ready).name!!.text)
        }
        val protected = credentials()
        val readback = protected.read(protected.openSession()) as CredentialResult.Success
        assertArrayEquals("synthetic-reauthorized-access".toByteArray(), readback.value.accessToken.copyBytes())
        assertEquals(peerBytes, bytes(b))
    }

    @Test fun explicitRemovalSettlesPendingRetainTicketBeforeReservingRemove() {
        val (a, b) = pair()
        val metadata = catalog()
        val peerBytes = bytes(b)
        val retainedBarrier = gate(); val removedBarrier = gate()
        val reservations = java.util.concurrent.atomic.AtomicInteger()
        val fake = SyntheticSamplingTransport().apply {
            tokens = io.github.leugenea.codexbarmobile.transport.TransportResult.Response.bounded(401, "{}".toByteArray())
        }
        val rootOwner = runtimeOwner(fake, fake.clock, metadata = metadata, history = { directory ->
            val native = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(directory))
            if (directory != historyDirectory) native else object : HistoryLifetimeStorage by native {
                override fun reserveDeletion(disposition: CleanupDisposition): HistoryDeletion {
                    val ticket = native.reserveDeletion()
                    val barrier = if (reservations.incrementAndGet() == 1) retainedBarrier else removedBarrier
                    return HistoryDeletion { barrier.pause("captured cleanup barrier"); ticket.complete() }
                }
            }
        })
        val first = rootOwner.legacy(); phase(first, ConnectionPhase.RESTORED)
        first.readUsage(refreshSession = true)
        try {
            retainedBarrier.await("terminal retained history barrier entered")
            val original = catalog().snapshot().deletions.single()
            assertEquals(CleanupDisposition.RETAIN, original.disposition)
            assertEquals(a.storage.id, original.entry)
            val confirmation = first.accountRemoval
            first.signOut(confirmation)
            settled(first, "explicit removal admitted during retained cleanup") { first.commandsSettled() }
            assertEquals(listOf(original), catalog().snapshot().deletions)
            assertEquals(a, catalog().entry(a.storage.id))
            retainedBarrier.release()
            removedBarrier.await("new explicit removal history barrier entered")
            val next = catalog().snapshot().deletions.single()
            assertNotEquals(original.operation, next.operation)
            assertEquals(a.storage.id, next.entry)
            assertEquals(CleanupDisposition.REMOVE, next.disposition)
            assertEquals(a.copy(partition = null), catalog().entry(a.storage.id))
            assertEquals(a.storage.id, catalog().snapshot().selected)
            val pending = catalog().snapshot()
            assertFalse(metadata.completeDeletion(original))
            assertEquals(pending, catalog().snapshot())
            first.signOut(confirmation)
            settled(first, "old confirmation cannot reinterpret the new REMOVE ticket") { first.commandsSettled() }
            assertEquals(pending, catalog().snapshot())
            assertFalse(first.session.snapshot() is SessionResult.Ready)
            assertFalse(credentialFile.exists())
            assertEquals(peerBytes, bytes(b))
            assertEquals(2, reservations.get())
            assertEquals(1L, fake.requests.get())
        } finally { retainedBarrier.release(); removedBarrier.release() }
        phase(first, ConnectionPhase.SIGNED_OUT)
        assertEquals(listOf(b), catalog().snapshot().entries)
        assertEquals(b.storage.id, catalog().snapshot().selected)
        assertTrue(catalog().snapshot().deletions.isEmpty())
        assertFalse(File(historyDirectory, "account-name").exists())
        assertEquals(peerBytes, bytes(b))
    }

    @Test fun overlappingTerminalReceiptsCompleteOnlyTheirCapturedEntryInEitherBarrierOrder() = runBlocking {
        for (aFirst in listOf(true, false)) {
            val (a, b) = pair()
            val holdA = gate(); val holdB = gate()
            val fake = SyntheticSamplingTransport().apply { tokens = io.github.leugenea.codexbarmobile.transport.TransportResult.Response.bounded(401, "{}".toByteArray()) }
            val rootOwner = runtimeOwner(fake, fake.clock, history = { directory ->
                val native = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(directory))
                val hold = if (directory == a.storage.historyDirectory(root)) holdA else holdB
                object : HistoryLifetimeStorage by native {
                    override fun reserveDeletion(disposition: io.github.leugenea.codexbarmobile.account.CleanupDisposition): HistoryDeletion {
                        val ticket = native.reserveDeletion()
                        return HistoryDeletion { hold.pause("captured history barrier"); ticket.complete() }
                    }
                }
            })
            val first = rootOwner.runtime(a.storage.id)!!; val second = rootOwner.runtime(b.storage.id)!!
            phase(first, ConnectionPhase.RESTORED); phase(second, ConnectionPhase.RESTORED)
            first.readUsage(refreshSession = true); second.readUsage(refreshSession = true)
            try {
                holdA.await("A history held"); holdB.await("B history held")
                val receipts = catalog().snapshot().deletions
                assertEquals(setOf(a.storage.id, b.storage.id), receipts.map { it.entry }.toSet())
                val released = if (aFirst) first else second
                val heldEntry = if (aFirst) b else a
                if (aFirst) holdA.release() else holdB.release()
                phase(released, ConnectionPhase.REAUTH_REQUIRED)
                assertEquals(listOf(receipts.single { it.entry == heldEntry.storage.id }), catalog().snapshot().deletions)
                assertNotNull(catalog().entry(heldEntry.storage.id))
            } finally { holdA.release(); holdB.release() }
            phase(first, ConnectionPhase.REAUTH_REQUIRED); phase(second, ConnectionPhase.REAUTH_REQUIRED)
            assertEquals(listOf(a.copy(partition = null), b.copy(partition = null)), catalog().snapshot().entries)
            assertEquals(a.storage.id, catalog().snapshot().selected)
            assertTrue(catalog().snapshot().deletions.isEmpty())
            assertFalse(KeystoreCredentialStore.file(context, a.storage.credentialSlot).exists())
            assertFalse(KeystoreCredentialStore.file(context, b.storage.credentialSlot).exists())
            assertEquals(2L, fake.requests.get())
            stop(rootOwner)
            root.deleteRecursively(); assertTrue(root.mkdir())
        }
    }

    @Test fun keyLossAndRotationQuarantineReconstructOnlyTheCapturedAccountWithPeerBytesIntact() {
        for (legacyFailed in listOf(true, false)) for (keyLost in listOf(true, false)) {
            val (a, b) = pair()
            val failed = if (legacyFailed) a else b
            val peerEntry = if (legacyFailed) b else a
            if (keyLost) keys().deleteEntry(KeystoreCredentialStore.alias(context, failed.storage.credentialSlot))
            else File(KeystoreCredentialStore.file(context, failed.storage.credentialSlot).parentFile, "rotation-pending").writeBytes(byteArrayOf(1))
            val peerBytes = bytes(peerEntry)
            var failHistory = true
            val firstRoot = runtimeOwner(history = { directory ->
                val native = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(directory))
                if (directory != failed.storage.historyDirectory(root)) native else object : HistoryLifetimeStorage by native {
                    override fun reserveDeletion(disposition: io.github.leugenea.codexbarmobile.account.CleanupDisposition): HistoryDeletion {
                        val ticket = native.reserveDeletion()
                        return HistoryDeletion {
                            val result = ticket.complete()
                            if (failHistory) HistoryDeleteOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE) else result
                        }
                    }
                }
            })
            val first = firstRoot.runtime(failed.storage.id)!!; val peer = firstRoot.runtime(peerEntry.storage.id)!!
            phase(first, ConnectionPhase.FAILED); phase(peer, ConnectionPhase.RESTORED)
            val receipt = catalog().snapshot().deletions.single()
            assertEquals(failed.storage.id, receipt.entry)
            assertEquals(peerBytes, bytes(peerEntry)); assertTrue(peer.session.snapshot() is SessionResult.Ready)
            stop(firstRoot); failHistory = false
            val reconstructed = runtimeOwner()
            val cleaned = reconstructed.runtime(failed.storage.id)!!; val continuing = reconstructed.runtime(peerEntry.storage.id)!!
            phase(cleaned, ConnectionPhase.REAUTH_REQUIRED); phase(continuing, ConnectionPhase.RESTORED)
            assertEquals(failed.copy(partition = null), catalog().entry(failed.storage.id))
            assertEquals(peerEntry, catalog().entry(peerEntry.storage.id))
            assertTrue(catalog().snapshot().deletions.isEmpty()); assertFalse(catalog().completeDeletion(receipt))
            assertEquals(peerBytes, bytes(peerEntry)); assertEquals(0L, transport.requests.get())
            stop(reconstructed)
            // Leave only the independent peer for the next synthetic iteration's seed.
            KeystoreCredentialStore(context, peerEntry.storage.credentialSlot).let { it.delete(it.openSession()) }
            root.deleteRecursively(); assertTrue(root.mkdir())
        }
    }

    @Test fun credentialBarrierAndReadableCredentialRestartKeepTheCapturedReceiptAndHealthyPeer() = runBlocking {
        for (legacyFailed in listOf(true, false)) {
            val (a, b) = pair()
            val failed = if (legacyFailed) a else b
            val peerEntry = if (legacyFailed) b else a
            val hold = gate()
            val firstRoot = runtimeOwner(decorate = { id, store ->
                if (id != failed.storage.credentialSlot) store else object : CredentialStore by store {
                    override fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion> {
                        val reserved = store.admitDeletion(generation)
                        if (reserved !is CredentialResult.Success) return reserved
                        return CredentialResult.Success(object : CredentialDeletion {
                            override fun complete(): CredentialResult<Unit> {
                                hold.pause("credential barrier before physical deletion")
                                return CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
                            }
                        })
                    }
                }
            })
            val first = firstRoot.runtime(failed.storage.id)!!; val peer = firstRoot.runtime(peerEntry.storage.id)!!
            phase(first, ConnectionPhase.RESTORED); phase(peer, ConnectionPhase.RESTORED)
            val peerBytes = bytes(peerEntry); val peerEnvelope = ready(peer)
            val protectedBefore = KeystoreCredentialStore.file(context, failed.storage.credentialSlot).readBytes()
            first.signOut()
            val receipt: CatalogDeletion
            try {
                hold.await("captured credential deletion")
                awaitRuntime("history deleted while credential barrier held", { catalog().snapshot().toString() }) {
                    catalog().snapshot().deletions.any { it.entry == failed.storage.id } &&
                        !File(failed.storage.historyDirectory(root), "history.db").exists()
                }
                receipt = catalog().snapshot().deletions.single()
                assertEquals(failed.storage.id, receipt.entry)
                assertNotNull(catalog().entry(failed.storage.id))
                assertArrayEquals(protectedBefore, KeystoreCredentialStore.file(context, failed.storage.credentialSlot).readBytes())
                assertSame(peerEnvelope, ready(peer)); assertEquals(peerBytes, bytes(peerEntry))
            } finally { hold.release() }
            phase(first, ConnectionPhase.FAILED); stop(firstRoot)
            val resumed = gate()
            val restarted = runtimeOwner(history = { directory ->
                val native = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(directory))
                if (directory != failed.storage.historyDirectory(root)) native else object : HistoryLifetimeStorage by native {
                    override fun reserveDeletion(disposition: io.github.leugenea.codexbarmobile.account.CleanupDisposition): HistoryDeletion {
                        val ticket = native.reserveDeletion()
                        return HistoryDeletion { resumed.pause("reconstructed history barrier"); ticket.complete() }
                    }
                }
            })
            val retry = restarted.runtime(failed.storage.id)!!; val continuing = restarted.runtime(peerEntry.storage.id)!!
            try {
                resumed.await("receipt reconstructed before terminal completion")
                phase(continuing, ConnectionPhase.RESTORED)
                assertEquals(listOf(receipt), catalog().snapshot().deletions)
                assertFalse(retry.session.snapshot() is SessionResult.Ready)
                assertEquals(peerBytes, bytes(peerEntry))
            } finally { resumed.release() }
            phase(retry, ConnectionPhase.REAUTH_REQUIRED)
            assertNull(catalog().entry(failed.storage.id)); assertEquals(peerEntry, catalog().entry(peerEntry.storage.id))
            assertTrue(catalog().snapshot().deletions.isEmpty()); assertFalse(catalog().completeDeletion(receipt))
            assertFalse(KeystoreCredentialStore.file(context, failed.storage.credentialSlot).exists())
            assertEquals(peerBytes, bytes(peerEntry)); assertEquals(0L, transport.requests.get())
            stop(restarted)
            KeystoreCredentialStore(context, peerEntry.storage.credentialSlot).let { it.delete(it.openSession()) }
            root.deleteRecursively(); assertTrue(root.mkdir())
        }
    }

    @Test fun appliedCatalogFinalizationFailureRecoversThroughTheSameLiveControllerWithoutRestart() {
        val (a, b) = pair()
        val failSync = java.util.concurrent.atomic.AtomicBoolean(false)
        val syncFailures = java.util.concurrent.atomic.AtomicInteger()
        val metadata = AccountCatalog.open(root, { slot }, beforeDirectorySync = {
            if (failSync.get() && catalog().entry(a.storage.id) == null) {
                syncFailures.incrementAndGet()
                throw IOException("synthetic directory sync failure after A replacement")
            }
        })
        val rootOwner = runtimeOwner(metadata = metadata)
        val first = rootOwner.runtime(a.storage.id)!!; val peer = rootOwner.runtime(b.storage.id)!!
        phase(first, ConnectionPhase.RESTORED); phase(peer, ConnectionPhase.RESTORED)
        val peerEnvelope = ready(peer); val peerBytes = bytes(b)
        failSync.set(true)
        first.signOut(); phase(first, ConnectionPhase.FAILED)
        assertEquals(ConnectionProblem.STORAGE, first.state.value.problem)
        assertEquals(1, syncFailures.get())
        assertFalse(credentialFile.exists())
        assertFalse(File(historyDirectory, "history.db").exists())
        assertNull(metadata.entry(a.storage.id))
        assertTrue(catalog().snapshot().deletions.isEmpty()) // Replacement really happened.
        assertFalse(first.session.snapshot() is SessionResult.Ready)
        assertSame(first, rootOwner.legacy()); assertSame(peerEnvelope, ready(peer))
        assertEquals(peerBytes, bytes(b)); assertEquals(0L, transport.requests.get())

        first.signOut(); phase(first, ConnectionPhase.FAILED)
        assertEquals(ConnectionProblem.STORAGE, first.state.value.problem)
        assertEquals(2, syncFailures.get()) // Same ticket reached the durability retry, not an absent-receipt rejection.
        failSync.set(false)
        first.signOut(); phase(first, ConnectionPhase.SIGNED_OUT)
        assertSame(first, rootOwner.legacy()); assertSame(peerEnvelope, ready(peer))
        assertEquals(listOf(b), catalog().snapshot().entries)
        assertEquals(peerBytes, bytes(b)); assertEquals(0L, transport.requests.get())
        first.connect(); phase(first, ConnectionPhase.OBSERVED)
        val successor = metadata.legacyEntry()!!
        assertNotEquals(a.storage.id, successor.storage.id)
        assertSame(first, rootOwner.runtime(successor.storage.id))
        assertNull(rootOwner.runtime(a.storage.id))
        assertTrue(first.session.snapshot() is SessionResult.Ready)
        assertTrue(credentialFile.exists()); assertTrue(catalog().snapshot().deletions.isEmpty())
        assertSame(peerEnvelope, ready(peer)); assertEquals(peerBytes, bytes(b))
        assertEquals(5L, transport.requests.get()) // User-code, auth poll, token exchange and two GETs.
    }

    @Test fun lateCompletedLegacyTicketCannotFinalizeASuccessorReceiptOrTheIndependentPeer() {
        val (a, b) = pair()
        val peerBytes = bytes(b)
        val failSync = java.util.concurrent.atomic.AtomicBoolean(false)
        val metadata = AccountCatalog.open(root, { slot }, beforeDirectorySync = {
            if (failSync.get()) throw IOException("synthetic directory sync failure after replacement")
        })
        val protected = credentials()
        val firstGeneration = protected.openSession()
        SQLiteHistoryStore(historyDirectory).use { native ->
            val storage = CatalogHistoryStorage(SQLiteHistoryLifetimeStorage(native), metadata)
            val first = storage.reserveDeletion()
            assertTrue(first.complete().successful())
            assertTrue(protected.delete(firstGeneration) is CredentialResult.Success)
            val oldReceipt = metadata.snapshot().deletions.single()
            assertEquals(a.storage.id, oldReceipt.entry)
            failSync.set(true)
            assertFalse(first.completeCatalog())
            assertNull(metadata.entry(a.storage.id))
            assertTrue(catalog().snapshot().deletions.isEmpty())
            failSync.set(false)
            val nextGeneration = protected.openSession()
            assertTrue(protected.replace(CredentialEnvelope(nextGeneration,
                SensitiveValue.copyOf("synthetic-successor-access".toByteArray()),
                SensitiveValue.copyOf("synthetic-successor-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
            val nextPartition = (native.createPartition() as HistoryAccessOutcome.Bound).access.partition
            val successor = metadata.adopt(nextPartition, AccountNameRead.Ready(null))
            assertNotEquals(a.storage.id, successor.storage.id)
            val next = storage.reserveDeletion()
            assertTrue(next.complete().successful())
            val successorReceipt = metadata.snapshot().deletions.single()
            assertEquals(successor.storage.id, successorReceipt.entry)
            assertNotEquals(oldReceipt.operation, successorReceipt.operation)
            val pending = catalog().snapshot()
            failSync.set(true)
            assertFalse(first.completeCatalog()) // Absence alone cannot acknowledge a still-failing fsync.
            assertEquals(pending, catalog().snapshot())
            failSync.set(false)
            assertTrue(first.completeCatalog()) // Applied old ticket syncs current state, never its old snapshot.
            assertTrue(first.completeCatalog()) // Its own cached completion, never the new receipt.
            assertEquals(listOf(successorReceipt), catalog().snapshot().deletions)
            assertNotNull(catalog().entry(successor.storage.id))
            assertTrue(credentialFile.exists()) // The successor credential barrier has not completed.
            assertEquals(peerBytes, bytes(b)); assertEquals(b, catalog().entry(b.storage.id))
            assertTrue(protected.delete(nextGeneration) is CredentialResult.Success)
            assertTrue(next.completeCatalog())
            assertEquals(listOf(b), catalog().snapshot().entries)
            assertTrue(catalog().snapshot().deletions.isEmpty())
            assertEquals(peerBytes, bytes(b)); assertEquals(0L, transport.requests.get())
        }
    }

    private suspend fun awaitRuntime(step: String, last: () -> String, predicate: () -> Boolean) {
        try { withTimeout(5_000) { while (!predicate()) yield() } }
        catch (_: TimeoutCancellationException) { throw AssertionError("$step: last state=${last()}") }
    }

    private class RuntimeGate {
        private val entered = java.util.concurrent.CountDownLatch(1)
        private val released = java.util.concurrent.CountDownLatch(1)
        fun pause(step: String) {
            entered.countDown()
            check(released.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "$step: last state=held" }
        }
        fun await(step: String) { assertTrue("$step: last state=not-entered", entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        fun release() { released.countDown() }
    }

}
