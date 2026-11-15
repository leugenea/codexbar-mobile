package io.github.leugenea.codexbarmobile

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.*
import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import java.io.File
import java.math.BigDecimal
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Two real protected/SQLite accounts, distinguished by facts, not provider/JWT identity. */
internal class AccountSelectionFixture {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    val directory = File(application.noBackupFilesDir, "synthetic-selection-${UUID.randomUUID()}").canonicalFile
    val context = object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = directory
    }
    private val slot = UUID.randomUUID()
    val aTransport = NavigationTransport()
    val bTransport = NavigationTransport().apply { usageBody = NavigationTransport.USAGE.replace("12.375", "62.5") }
    val transport = AuthTransport { request, deadline, terminal ->
        val token = (request as? ProviderHttpRequest.Get)?.bearer?.copyBytes()?.toString(Charsets.UTF_8)
        val target = if (token?.contains("-B-") == true) bTransport else aTransport
        target.execute(request, deadline, terminal)
    }
    lateinit var catalog: AccountCatalog
    lateinit var a: CatalogEntry
    lateinit var b: CatalogEntry
    lateinit var owner: AccountRuntimeOwner
    var decorateCredential: (UUID, CredentialStore) -> CredentialStore = { _, store -> store }
    var hooks: (File) -> HistoryStorageHooks = { object : HistoryStorageHooks {} }
    var decorateHistory: (File, HistoryLifetimeStorage) -> HistoryLifetimeStorage = { _, storage -> storage }
    data class AppendReceipt(val directory: File, val admission: HistoryAdmission, val result: HistoryAppendOutcome)
    val appends = java.util.concurrent.CopyOnWriteArrayList<AppendReceipt>()
    private val gates = mutableListOf<SelectionGate>()
    private val originalRootFactory = NativeConnection.rootFactory
    private var installed = false
    private val at = Instant.ofEpochSecond(1_800_000_000)

    fun prepare() {
        assertTrue(directory.mkdir())
        File(directory, "connection-session").writeText(slot.toString())
        catalog = AccountCatalog.open(directory, { slot })
        a = seed(AccountStorage(UUID.randomUUID(), slot, true), "A", "12.375")
        b = seed(AccountStorage.fresh(), "B", "62.5")
    }

    fun start(): AccountRuntimeOwner {
        owner = AccountRuntimeOwner(context, catalog, transport, aTransport.clock, aTransport::pause,
            credentials = { app, id -> decorateCredential(id, KeystoreCredentialStore(app, id)) },
            historyStorage = { path ->
                val storage = decorateHistory(path, SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(path, hooks = hooks(path))))
                object : HistoryLifetimeStorage by storage {
                    override fun restore() = observed(path, storage.restore())
                    override fun activate(partition: HistoryPartition) = observed(path, storage.activate(partition))
                }
            })
        return owner
    }

    private fun observed(path: File, outcome: HistoryLifetimeOutcome): HistoryLifetimeOutcome {
        if (outcome !is HistoryLifetimeOutcome.Bound) return outcome
        val native = outcome.access
        return HistoryLifetimeOutcome.Bound(object : HistoryRuntimeAccess by native {
            override fun append(admission: HistoryAdmission): HistoryAppendOutcome = native.append(admission).also {
                appends += AppendReceipt(path, admission, it)
            }
        })
    }

    fun installActivity() {
        NativeConnection.rootFactory = { owner }
        installed = true
        NativeConnection.resetForTests()
    }

    private fun seed(address: AccountStorage, label: String, percent: String): CatalogEntry {
        val store = KeystoreCredentialStore(context, address.credentialSlot)
        val active = store.openSession()
        assertTrue(store.replace(CredentialEnvelope(active,
            SensitiveValue.copyOf("synthetic-$label-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-$label-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
        val partition = SQLiteHistoryStore(address.historyDirectory(directory)).use { history ->
            val access = (history.createPartition() as HistoryAccessOutcome.Bound).access
            val input = WindowInput(Input.Value(18000L), Input.Value(BigDecimal(percent)), Input.Value(1_800_003_600L), Input.Missing)
            val usage = UsageNormalizer.normalize(UsageInput(Input.Value(input), Input.Missing), at)
            for (id in 1L..3L) assertTrue(access.append(HistoryAdmission(access.partition, ObservationId(id),
                HistoryClock(ClockEpoch(UUID(0, 99)), id * 1000), HistoryEvent.Observed(usage))) is HistoryAppendOutcome.Stored)
            assertTrue(history.writeName(access.partition, AccountDisplayName.from("Synthetic $label")))
            access.partition
        }
        return CatalogEntry(address, partition, "Synthetic $label", true).also { assertTrue(catalog.insert(it)) }
    }

    fun runtime(entry: CatalogEntry): ConnectionController = requireNotNull(owner.runtime(entry.storage.id))
    fun ready(controller: ConnectionController) = (controller.session.snapshot() as SessionResult.Ready).envelope
    fun phase(controller: ConnectionController, phase: ConnectionPhase) = receipt("phase $phase", controller) {
        controller.commandsSettled()
        controller.state.first { it.phase == phase && !it.refresh.refreshing }
    }
    fun settle(controller: ConnectionController) = receipt("commands and restoration", controller) {
        controller.commandsSettled(); controller.historyRestorationSettled()
    }
    fun select(entry: CatalogEntry) = receipt("durable selection ${entry.name}") { owner.select(entry.storage.id) }.also { assertTrue(it) }
    fun observer() = receipt("STARTED registration") { owner.registerForeground() }
    fun stop(observer: DashboardObserver) = receipt("STOP exact registration") { observer.stop() }
    fun gate() = SelectionGate().also { gates += it }

    fun <T> receipt(label: String, controller: ConnectionController? = null, block: suspend () -> T): T = runBlocking {
        try { withTimeout(8_000) { block() } }
        catch (error: TimeoutCancellationException) {
            throw AssertionError("$label: last state=${controller?.state?.value}, selected=${catalog.snapshot().selected}", error)
        }
    }
    fun await(label: String, predicate: () -> Boolean) = receipt(label) { while (!predicate()) yield() }

    fun bytes(entry: CatalogEntry): Map<String, List<Byte>> {
        assertTrue("Saved peer ciphertext remains present", KeystoreCredentialStore.file(context, entry.storage.credentialSlot).isFile)
        val roots = listOf(entry.storage.historyDirectory(directory),
            requireNotNull(KeystoreCredentialStore.file(context, entry.storage.credentialSlot).parentFile))
        return roots.flatMap { it.walkTopDown().filter(File::isFile).toList() }.associate {
            it.relativeTo(directory).path to it.readBytes().toList()
        }
    }
    fun keyExists(entry: CatalogEntry) = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .containsAlias(KeystoreCredentialStore.alias(context, entry.storage.credentialSlot))
    fun protectedReadback(entry: CatalogEntry): CredentialEnvelope {
        assertTrue("Peer key remains usable", keyExists(entry))
        val store = KeystoreCredentialStore(context, entry.storage.credentialSlot)
        return (store.read(store.openSession()) as CredentialResult.Success).value
    }
    fun assertRemoved(entry: CatalogEntry) {
        assertFalse(keyExists(entry))
        val protected = KeystoreCredentialStore(context, entry.storage.credentialSlot)
        assertTrue(protected.read(protected.openSession()) is CredentialResult.Failure)
        assertTrue(KeystoreCredentialStore.file(context, entry.storage.credentialSlot).parentFile!!.listFiles().orEmpty().isEmpty())
        val path = entry.storage.historyDirectory(directory)
        for (name in listOf("history.db", "history.db-journal", "history.db-wal", "history.db-shm",
            AccountNameFile.FILE_NAME, AccountNameFile.PENDING_NAME)) assertFalse(File(path, name).exists())
        assertNull(AccountCatalog.open(directory, { error("existing catalog") }).entry(entry.storage.id))
    }
    fun page(controller: ConnectionController) = receipt("actual page completion", controller) {
        controller.commandsSettled()
        controller.historySnapshots.first { it.storage?.lastAdmitted?.ordinal != null }
    }
    /** Independent original-directory readback: callers must first shut down its retained owner. */
    fun diskPage(entry: CatalogEntry) = readDiskPage(entry, entry.storage.historyDirectory(directory))

    /** Only for settled fixture writes, not an arbitrary concurrent SQLite backup. Keep the owner live. */
    fun settledDiskPage(entry: CatalogEntry): HistoryReadSnapshot {
        val state = runtime(entry).state.value
        assertEquals("Settled readback phase", ConnectionPhase.OBSERVED, state.phase)
        assertFalse("Settled readback endpoint cycle", state.refresh.refreshing)
        assertNotNull("Settled usage endpoint", state.refresh.usage.success)
        assertNotNull("Settled inventory endpoint", state.refresh.inventory.success)
        val receipts = appends.filter { it.directory == entry.storage.historyDirectory(directory) &&
            it.admission.partition == entry.partition && it.admission.event is HistoryEvent.Observed }
        assertTrue("Original observer must have completed an append", receipts.isNotEmpty())
        receipts.forEach { assertTrue("Original append ${it.admission.id}: ${it.result}", it.result is HistoryAppendOutcome.Stored) }
        val source = entry.storage.historyDirectory(directory)
        val before = historyArtifacts(source)
        assertTrue("Unsettled SQLite artifacts: ${before.keys}",
            before.keys.none { it in setOf("history.db-journal", "history.db-wal", "history.db-shm") })
        val copy = File(directory, "settled-history-readback-${UUID.randomUUID()}").canonicalFile
        check(source.parentFile == directory && copy.parentFile == directory && copy != source)
        assertTrue("Fresh detached readback directory", copy.mkdir())
        try {
            for (name in before.keys) {
                val original = File(source, name)
                val target = original.copyTo(File(copy, name))
                val originalStat = Os.stat(original.path); val copiedStat = Os.stat(target.path)
                assertFalse("Readback must not share the original inode: $name",
                    originalStat.st_dev == copiedStat.st_dev && originalStat.st_ino == copiedStat.st_ino)
            }
            assertEquals("Original artifact set/bytes changed during copy", before, historyArtifacts(source))
            assertEquals("Detached artifact set/bytes differ", before, historyArtifacts(copy))
            return readDiskPage(entry, copy)
        } finally {
            assertTrue("Delete only detached readback", copy.deleteRecursively())
            assertFalse("Detached readback removed", copy.exists())
        }
    }

    private fun historyArtifacts(path: File): Map<String, List<Byte>> = requireNotNull(path.listFiles()) {
        "Cannot inventory history directory $path"
    }.sortedBy { it.name }.associate { file ->
        check(file.isFile && file.canonicalFile == file.absoluteFile) { "Non-regular history artifact: $file" }
        file.name to file.readBytes().toList()
    }

    private fun readDiskPage(entry: CatalogEntry, path: File) = SQLiteHistoryStore(path).use { history ->
        val access = (history.adopt(entry.partition!!) as HistoryAccessOutcome.Bound).access
        (access.read(HistoryReadQuery(entry.partition, 32)) as HistoryReadOutcome.Ready).snapshot
    }

    fun cleanup() {
        gates.forEach { it.release() }
        aTransport.releaseHeld(); bTransport.releaseHeld()
        try {
            if (installed) NativeConnection.resetForTests()
            if (::owner.isInitialized) receipt("root shutdown") { owner.shutdown() }
        } finally {
            if (installed) NativeConnection.rootFactory = originalRootFactory
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            listOf(a, b).forEach { keys.deleteEntry(KeystoreCredentialStore.alias(context, it.storage.credentialSlot)) }
            check(directory.parentFile == application.noBackupFilesDir.canonicalFile && directory.name.startsWith("synthetic-selection-"))
            assertTrue(directory.deleteRecursively())
            assertFalse(directory.exists())
        }
    }
}

internal class SelectionGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    fun pause(label: String) {
        entered.countDown()
        check(released.await(15, TimeUnit.SECONDS)) { "$label: last state=held" }
    }
    fun await(label: String) { assertTrue("$label: last state=not reached", entered.await(8, TimeUnit.SECONDS)) }
    fun release() { released.countDown() }
}
