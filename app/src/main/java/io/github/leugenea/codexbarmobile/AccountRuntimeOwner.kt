package io.github.leugenea.codexbarmobile

import android.content.Context
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.CredentialStore
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.TransportClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.UUID

/** Retains concrete Codex stacks; addressing never retires, selects or activates a peer. */
internal class AccountRuntimeOwner(
    context: Context,
    private val catalog: AccountCatalog?,
    private val transport: AuthTransport,
    private val clock: TransportClock,
    private val pause: suspend (Long) -> Unit,
    private val legacyEnrollment: (BackgroundRefreshTarget?) -> Unit = {},
    private val credentials: (Context, UUID) -> CredentialStore = ::KeystoreCredentialStore,
    private val historyStorage: (File) -> HistoryLifetimeStorage = { SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(it)) },
) {
    // Retain only application-scoped context. Native fixtures supply a storage-isolating
    // ContextWrapper here, so preserve Context rather than casting it to Application.
    private val applicationContext: Context = context.applicationContext
    private val root = applicationContext.noBackupFilesDir.canonicalFile
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutations = Dispatchers.IO.limitedParallelism(1)
    private val runtimes = mutableMapOf<UUID, ConnectionController>()
    private val slots = mutableSetOf<UUID>()
    private var legacyController: ConnectionController? = null
    @Volatile private var closing = false
    private val selected = kotlinx.coroutines.flow.MutableStateFlow<DashboardSelection?>(null)
    private val observers = mutableSetOf<Any>()
    private var selectionWatch: Job? = null

    /** Dormant observation; the original compatibility ingress remains independent. */
    @Synchronized fun dashboard(): kotlinx.coroutines.flow.StateFlow<DashboardSelection?> {
        if (selected.value == null) display(catalog?.snapshots?.value)
        if (selectionWatch == null) selectionWatch = scope.launch(mutations) { catalog?.snapshots?.collect { display(it) } }
        return selected
    }

    /** Successor display and network admission follow confirmed durable selection, never precede it. */
    suspend fun select(id: UUID): Boolean = scope.async(mutations) {
        if (closing) return@async false
        dashboard()
        val committed = scope.async { catalog?.select(id) == true }
        if (!try { committed.await() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }) return@async false
        display(catalog?.snapshots?.value)
        selected.value?.takeIf { it.id == id }?.let { activate(it).join() }
        true
    }.await()

    /** Addressed management does not select, acquire foreground interest or request the provider. */
    fun settings(id: UUID): AccountSettingsHandle? = runtime(id)?.let { AccountSettingsHandle(id, it) }

    suspend fun registerForeground(): DashboardObserver = withContext(mutations) {
        check(!closing)
        dashboard()
        val token = Any()
        observers.add(token)
        selected.value?.controller?.usageForeground(token, true)
        DashboardObserver { withContext(mutations) {
            if (observers.remove(token)) selected.value?.controller?.usageForeground(token, false)
        } }
    }

    private fun display(snapshot: CatalogSnapshot?) {
        if (closing) return
        val id = snapshot?.selected
        val controller = displayController(snapshot) ?: return
        if (snapshot !== catalog?.snapshots?.value) return
        val previous = selected.value
        if (previous != null && previous.id == id && previous.controller === controller) return
        // Capture actual-removal provenance before any asynchronous activation/restoration.
        val removed = previous?.id != null && snapshot?.entries?.none { it.storage.id == previous.id } == true
        val next = DashboardSelection(id, controller, ::currentProjection)
        selected.value = next
        transferDisplay(previous?.controller, next)
        if (removed && id != null) activate(next)
    }

    private fun displayController(snapshot: CatalogSnapshot?): ConnectionController? {
        val id = snapshot?.selected ?: return legacy()
        val entry = snapshot.entries.single { it.storage.id == id }
        return synchronized(this) {
            runtimes[id] ?: if (entry.storage.legacy) legacy() else runtime(id)
        }
    }

    private fun transferDisplay(previous: ConnectionController?, next: DashboardSelection) {
        val changed = previous !== next.controller
        if (changed) previous?.let { old ->
            observers.forEach { old.usageForeground(it, false, retainStorage = true) }
            old.dashboardHidden()
        }
        next.controller.dashboardDisplay(next::isCurrent)
        if (changed) observers.forEach { next.controller.usageForeground(it, true) }
    }

    private fun currentProjection() = selected.value.takeUnless { closing }

    private fun activate(target: DashboardSelection): Job =
        scope.launch(mutations) { target.controller.requestSelectedUsage(target::isCurrent) }

    /** Compatibility ingress, including the empty installation before legacy adoption. */
    @Synchronized fun legacy(): ConnectionController {
        check(!closing)
        return legacyController ?: create(null).also { legacyController = it }
    }

    /** Exact immutable catalog address. Unknown/removed IDs never acquire another runtime. */
    @Synchronized fun runtime(id: UUID): ConnectionController? {
        check(!closing)
        val entry = catalog?.entry(id) ?: return null
        return runtimes.getOrPut(id) { if (entry.storage.legacy) legacy() else create(entry.storage) }
    }

    private fun create(address: AccountStorage?): ConnectionController {
        val knownSlot = address?.credentialSlot ?: catalog?.snapshot()?.legacySlot
        val slot = knownSlot ?: UUID.randomUUID()
        check(slots.add(slot)) { "Credential slot already owned" }
        val store = credentials(applicationContext, slot)
        val directory = address?.historyDirectory(root) ?: File(root, "usage-history")
        val storage = historyStorage(directory)
        val history = if (catalog == null) storage else CatalogHistoryStorage(storage, catalog, address)
        // Production scheduling still consumes only the compatibility ingress. Multi-entry
        // consumers must supply exact entry/lifetime admission in the scheduler integration.
        val enrollment = if (address == null) legacyEnrollment else { _: BackgroundRefreshTarget? -> }
        return ConnectionController(store, DeviceCodeAuthenticator(transport, store, clock, pause),
            NativeFeasibilityReader(transport, clock, pause), scope, storageReady = knownSlot != null,
            mutationDispatcher = mutations, refreshClock = clock,
            history = HistoryLifetimeCoordinator(history), backgroundEnrollment = enrollment,
            savedAccount = { if (address == null) catalog?.legacyEntry() != null else catalog?.entry(address.id) != null })
    }

    suspend fun shutdown() {
        val controllers = synchronized(this) {
            closing = true
            (runtimes.values + listOfNotNull(legacyController)).distinct()
        }
        selectionWatch?.cancelAndJoin()
        controllers.forEach { it.close() }
        controllers.forEach { it.shutdown() }
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
