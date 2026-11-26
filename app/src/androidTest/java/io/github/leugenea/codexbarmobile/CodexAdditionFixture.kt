package io.github.leugenea.codexbarmobile

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class CodexAdditionFixture {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    val directory = File(application.noBackupFilesDir.canonicalFile, "synthetic-add-${UUID.randomUUID()}")
    val context = object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = directory
    }
    val legacy = UUID.randomUUID()
    val transport = NavigationTransport()
    private val originalFactory = NativeConnection.rootFactory
    private val keys = ConcurrentHashMap.newKeySet<UUID>()
    private val gates = mutableListOf<SelectionGate>()
    val constructed = ConcurrentHashMap<UUID, Int>()
    val writes = ConcurrentHashMap<UUID, SessionGeneration>()
    val enrollment = java.util.concurrent.CopyOnWriteArrayList<BackgroundRefreshTarget?>()
    val append = ConcurrentHashMap<HistoryPartition, HistoryAppendOutcome>()
    @Volatile var denyPoll = false
    var protect: (UUID) -> CredentialStore = { KeystoreCredentialStore(context, it) }
    var history: (File) -> HistoryLifetimeStorage = { SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(it)) }
    var beforeAdditionDisplay: () -> Unit = {}
    lateinit var catalog: AccountCatalog
    lateinit var owner: AccountRuntimeOwner
    private var installed = false

    init {
        check(directory.mkdir())
        catalog = AccountCatalog.open(directory, { legacy })
    }

    fun start(openCatalog: (() -> AccountCatalog)? = null) {
        val network = AuthTransport { request, deadline, terminal ->
            if (denyPoll && request.url.encodedPath == POLL) {
                terminal(TransportResult.Response.bounded(401, byteArrayOf()))
                CancellationHandle {}
            } else transport.execute(request, deadline, terminal)
        }
        owner = if (openCatalog == null) {
            AccountRuntimeOwner(context, catalog, network, transport.clock, transport::pause, { { enrollment.add(it) } },
                credentials = { _, slot -> track(slot) }, historyStorage = { observingHistory(it) },
                beforeAdditionDisplay = { beforeAdditionDisplay() })
        } else NativeConnection.createOwner(context, network, transport.clock, transport::pause, openCatalog = openCatalog)
        NativeConnection.rootFactory = { owner }
        installed = true
        owner.dashboard()
    }

    private fun track(slot: UUID): CredentialStore {
        keys.add(slot)
        constructed.merge(slot, 1, Int::plus)
        val target = protect(slot)
        return object : CredentialStore by target {
            override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> =
                target.replace(envelope, cancellation).also { if (it is CredentialResult.Success) writes[slot] = envelope.generation }
        }
    }

    private fun observingHistory(directory: File): HistoryLifetimeStorage {
        val target = history(directory)
        return object : HistoryLifetimeStorage by target {
            override fun activate(partition: HistoryPartition): HistoryLifetimeOutcome {
                val result = target.activate(partition)
                if (result !is HistoryLifetimeOutcome.Bound) return result
                val access = object : HistoryRuntimeAccess by result.access {
                    override fun append(admission: HistoryAdmission): HistoryAppendOutcome = result.access.append(admission).also {
                        if (admission.event is HistoryEvent.Observed) append[partition] = it
                    }
                }
                return HistoryLifetimeOutcome.Bound(access)
            }
        }
    }

    fun <T> wait(label: String, block: suspend () -> T): T = runBlocking {
        try { withTimeout(12_000) { block() } }
        catch (timeout: TimeoutCancellationException) {
            throw AssertionError("$label; catalog=${catalog.snapshots.value}, counts=${transport.counts()}", timeout)
        }
    }
    fun until(label: String, test: () -> Boolean) = wait(label) { while (!test()) yield() }
    fun gate() = SelectionGate().also(gates::add)
    fun disk() = AccountCatalog.open(directory, { error("catalog must exist") }).snapshot()
    fun begin(name: String) = wait("begin Add") { NativeConnection.beginAddition(context, name, AccountType.CODEX) }
    fun pending() = requireNotNull(disk().candidate).also { keys.add(it.credentialSlot) }
    fun result(handle: CodexAddition) = wait("Add result") { handle.result() }
    fun runtime(id: UUID) = requireNotNull(owner.runtime(id))
    fun ready(controller: ConnectionController) = (controller.session.snapshot() as SessionResult.Ready).envelope
    fun observe(controller: ConnectionController) = wait("completed usage and inventory") {
        controller.state.first {
            it.phase == ConnectionPhase.OBSERVED && !it.refresh.refreshing &&
                it.refresh.usage.success != null && it.refresh.inventory.success != null
        }
    }
    fun held(path: String): NavigationTransport.HeldCall {
        var call: NavigationTransport.HeldCall? = null
        until("request admitted: $path") { transport.liveHeldCall(path).also { call = it } != null }
        return requireNotNull(call)
    }
    fun key(slot: UUID): Boolean = keyStore().containsAlias(KeystoreCredentialStore.alias(context, slot))
    fun protected(address: AccountStorage): CredentialEnvelope = KeystoreCredentialStore(context, address.credentialSlot).let {
        (it.read(it.openSession()) as CredentialResult.Success).value
    }
    fun bytes(address: AccountStorage): Map<String, List<Byte>> =
        listOf(address.historyDirectory(directory), KeystoreCredentialStore.file(context, address.credentialSlot).parentFile!!)
            .flatMap { it.listFiles().orEmpty().toList() }.associate { it.relativeTo(directory).path to it.readBytes().toList() }

    fun removed(address: AccountStorage) {
        assertFalse(key(address.credentialSlot))
        assertFalse(KeystoreCredentialStore.file(context, address.credentialSlot).parentFile!!.exists())
        assertFalse(address.historyDirectory(directory).exists())
        assertFalse(disk().entries.any { it.storage == address })
    }

    fun stop() {
        if (installed) { NativeConnection.resetForTests(); installed = false }
        if (::owner.isInitialized) wait("process owner shutdown") { owner.shutdown() }
    }

    fun cleanup() {
        gates.forEach(SelectionGate::release)
        transport.releaseHeld()
        try { stop() } finally {
            NativeConnection.rootFactory = originalFactory
            val store = keyStore()
            keys.forEach { store.deleteEntry(KeystoreCredentialStore.alias(context, it)) }
            check(directory.parentFile == application.noBackupFilesDir.canonicalFile && directory.name.startsWith("synthetic-add-"))
            check(directory.deleteRecursively() && !directory.exists())
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        const val POLL = "/api/accounts/deviceauth/token"
        const val EXCHANGE = "/oauth/token"
    }
}
