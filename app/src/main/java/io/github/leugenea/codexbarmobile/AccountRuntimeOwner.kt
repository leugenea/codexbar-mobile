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
    private var closing = false

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
            history = HistoryLifetimeCoordinator(history), backgroundEnrollment = enrollment)
    }

    suspend fun shutdown() {
        val controllers = synchronized(this) {
            closing = true
            (runtimes.values + listOfNotNull(legacyController)).distinct()
        }
        controllers.forEach { it.close() }
        controllers.forEach { it.shutdown() }
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
