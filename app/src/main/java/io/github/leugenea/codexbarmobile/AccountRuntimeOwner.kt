package io.github.leugenea.codexbarmobile

import android.content.Context
import io.github.leugenea.codexbarmobile.AdditionResult.Rejected
import io.github.leugenea.codexbarmobile.BackgroundRefreshRejection.RETIRING
import io.github.leugenea.codexbarmobile.BackgroundRefreshRejection.STALE_LIFETIME
import io.github.leugenea.codexbarmobile.BackgroundRefreshRejection.STORAGE_UNAVAILABLE
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.CredentialStore
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.TransportClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Retains concrete Codex stacks; addressing never retires, selects or activates a peer. */
internal class AccountRuntimeOwner(
    context: Context,
    initialCatalog: AccountCatalog?,
    private val transport: AuthTransport,
    private val clock: TransportClock,
    private val pause: suspend (Long) -> Unit,
    private val enrollment: (BackgroundRefreshTarget, Boolean) -> Unit = { _, _ -> },
    private val credentials: (Context, UUID) -> CredentialStore = ::KeystoreCredentialStore,
    private val historyStorage: (File) -> HistoryLifetimeStorage = { SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(it)) },
    private val openCatalog: (() -> AccountCatalog)? = null,
    private val beforeAdditionDisplay: () -> Unit = {},
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
    private var unavailableLegacy: ConnectionController? = null
    @Volatile private var catalog = initialCatalog ?: tryOpenCatalog()
    @Volatile private var closing = false
    private val selected = kotlinx.coroutines.flow.MutableStateFlow<DashboardSelection?>(null)
    private val observers = mutableSetOf<Any>()
    private var selectionWatch: Job? = null
    private val artifacts = AdditionArtifacts(applicationContext, root)
    private val recovery = Mutex()
    @Volatile private var addition: PendingAddition? = null
    private var additionActivation: AdditionActivation? = null

    init {
        if (catalog?.snapshots?.value?.candidate != null) scope.launch(mutations) { recoverAddition() }
    }

    /** Returns no handle for an invalid/conflicting name or an occupied/fenced Add slot. */
    suspend fun beginAddition(input: String, type: AccountType): CodexAddition? {
        val name = AccountDisplayName.from(input) ?: return null
        // The process owns the transaction even when the caller detaches after submitting it.
        return scope.async(mutations) { reserveAddition(name, type) }.await()
    }

    private suspend fun reserveAddition(name: AccountDisplayName, type: AccountType): CodexAddition? = recovery.withLock {
        val metadata = catalog ?: return@withLock null
        if (closing || addition != null || !removeOrphan(metadata)) return@withLock null
        if (closing) return@withLock null
        val address = try { metadata.reserveAddition(name, type) } catch (_: Exception) {
            removeOrphan(metadata)
            null
        } ?: return@withLock null
        dashboard()
        val handle = CodexAddition(this)
        val controller = try { create(address, pendingAddition = true) } catch (_: Exception) { null }
        if (controller == null) {
            removeOrphan(metadata, address)
            return@withLock null
        }
        val pending = PendingAddition(address, controller, handle)
        addition = pending
        pending.job = scope.launch(mutations, start = CoroutineStart.UNDISPATCHED) { runAddition(metadata, pending, name, type) }
        handle
    }

    private suspend fun runAddition(metadata: AccountCatalog, pending: PendingAddition, name: AccountDisplayName, type: AccountType) {
        val outcome = try {
            when (val prepared = pending.controller.prepareAddition(name, pending.handle::report)) {
                is PreparedAddition.Ready -> {
                    withContext(Dispatchers.IO) { artifacts.sync(pending.address) }
                    admitAddition(metadata, pending, prepared, name, type)
                }
                PreparedAddition.AuthFailure -> Rejected.AUTH
                PreparedAddition.StorageFailure -> Rejected.STORAGE
            }
        } catch (cancelled: CancellationException) {
            pending.cancellation ?: Rejected.CANCELLED
        } catch (_: Exception) {
            Rejected.STORAGE
        }
        if (outcome != null) {
            pending.cancellation = outcome
            if (pending.handle.auth.value !is AuthState.Failed) pending.handle.report(AuthState.Idle)
            withContext(NonCancellable) { rejectAddition(metadata, pending, outcome) }
        }
    }

    private fun admitAddition(metadata: AccountCatalog, pending: PendingAddition, prepared: PreparedAddition.Ready,
        name: AccountDisplayName, type: AccountType): Rejected? {
        val entry = CatalogEntry(pending.address, prepared.partition, name.text, true, type)
        pending.commit = AdditionCommit(entry, prepared)
        pending.handle.report(AuthState.Idle)
        val admitted = try { metadata.admitAddition(entry) } catch (_: Exception) {
            // May already be installed. Current readback + durability, not the exception, decide.
            return settleAddition(metadata, pending)
        }
        if (!admitted) return Rejected.CONFLICT
        acceptAddition(pending, prepared)
        return null
    }

    private fun settleAddition(metadata: AccountCatalog, pending: PendingAddition): Rejected? {
        val commit = requireNotNull(pending.commit)
        val confirmed = try { metadata.confirmAddition() } catch (_: Exception) { return null }
        if (confirmed.entries.none { it.storage == commit.entry.storage }) return Rejected.STORAGE
        acceptAddition(pending, commit.prepared)
        return null
    }

    @Synchronized private fun acceptAddition(pending: PendingAddition, prepared: PreparedAddition.Ready) {
        runtimes[pending.address.id] = pending.controller
        addition = null
        pending.controller.acceptAddition(prepared)
        additionActivation = AdditionActivation(requireNotNull(pending.commit).entry, pending.controller, prepared.envelope.generation)
        val snapshot = catalog?.snapshots?.value
        beforeAdditionDisplay()
        display(snapshot)
        pending.handle.complete(AdditionResult.Added(pending.address.id))
    }

    private suspend fun rejectAddition(metadata: AccountCatalog, pending: PendingAddition, rejection: Rejected) {
        // If native close failed, release the drained runtime but keep the durable owner for retry.
        val closed = runCatching { pending.controller.shutdown() }.isSuccess
        if (closed) removeOrphan(metadata, pending.address)
        synchronized(this) {
            slots.remove(pending.address.credentialSlot)
            addition = null
        }
        pending.handle.complete(rejection)
    }

    /** Startup/explicit retry. Uncertain live admission keeps its original runtime, never a second one. */
    suspend fun recoverAddition(expected: CodexAddition? = null): Boolean = scope.async(mutations) {
        recovery.withLock {
            if (closing) return@withLock false
            val pending = addition
            if (expected != null && pending?.handle !== expected) return@withLock false
            val metadata = catalog ?: recoverCatalog() ?: return@withLock false
            if (pending == null) return@withLock removeOrphan(metadata)
            if (pending.job?.isCompleted != true || pending.commit == null) return@withLock false
            settleAddition(metadata, pending)?.let { rejectAddition(metadata, pending, it) }
            addition == null && metadata.snapshots.value.candidate == null
        }
    }.await()

    private fun tryOpenCatalog(): AccountCatalog? {
        return try { openCatalog?.invoke() } catch (_: Exception) { null }
    }

    /** A scheduled recovery can publish storage without opening a dashboard or peer runtime. */
    private fun recoverCatalog(observeDashboard: Boolean = true): AccountCatalog? {
        val confirmed = tryOpenCatalog() ?: return null
        synchronized(this) {
            if (closing) return null
            catalog = confirmed
            if (observeDashboard) dashboard()
        }
        return confirmed
    }

    private suspend fun removeOrphan(metadata: AccountCatalog, expected: AccountStorage? = null): Boolean {
        return try {
            val address = metadata.confirmAddition().candidate
            if (address == null) true
            else if (expected != null && address != expected) false
            else if (!withContext(Dispatchers.IO) { artifacts.remove(address) }) false
            else metadata.discardAddition(address)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun cancelAddition(handle: CodexAddition, expected: AuthState.AwaitingUser? = null): Boolean = withContext(mutations) {
        val pending = addition?.takeIf { !closing && it.handle === handle && it.commit == null && it.cancellation == null }
            ?: return@withContext false
        if (expected != null && url(pending, expected) == null) return@withContext false
        pending.cancellation = if (expected == null) Rejected.CANCELLED else Rejected.BROWSER
        pending.handle.report(AuthState.Idle)
        pending.job?.cancel()
        true
    }

    suspend fun additionUrl(handle: CodexAddition, expected: AuthState.AwaitingUser): String? = withContext(mutations) {
        addition?.takeIf { it.handle === handle }?.let { url(it, expected) }
    }

    private fun url(pending: PendingAddition, expected: AuthState.AwaitingUser): String? =
        if (!closing && pending.handle.auth.value === expected) pending.controller.verificationForAddition(expected) else null

    private class PendingAddition(val address: AccountStorage, val controller: ConnectionController, val handle: CodexAddition) {
        var job: Job? = null
        var commit: AdditionCommit? = null
        var cancellation: Rejected? = null
    }

    private class AdditionCommit(val entry: CatalogEntry, val prepared: PreparedAddition.Ready)

    private class AdditionActivation(val entry: CatalogEntry, val controller: ConnectionController,
        val generation: SessionGeneration) {
        fun matches(snapshot: CatalogSnapshot?): Boolean {
            snapshot ?: return false
            val id = entry.storage.id
            return snapshot.selected == id && snapshot.deletions.none { it.entry == id } &&
                snapshot.entries.any { it.storage == entry.storage && it.partition == entry.partition }
        }
    }

    /** Dormant observation; the original compatibility ingress remains independent. */
    @Synchronized fun dashboard(): kotlinx.coroutines.flow.StateFlow<DashboardSelection?> {
        if (selectionWatch == null) {
            val metadata = catalog
            display(metadata?.snapshots?.value)
            if (metadata != null) selectionWatch = scope.launch(mutations) { watchSelection(metadata) }
        }
        return selected
    }

    private suspend fun watchSelection(metadata: AccountCatalog) {
        metadata.snapshots.collect {
            val pending = addition
            if (pending?.commit != null && it.candidate != pending.address) recoverAddition()
            display(metadata.snapshots.value)
        }
    }

    /** Successor display and network admission follow confirmed durable selection, never precede it. */
    suspend fun select(id: UUID): Boolean = scope.async(mutations) {
        if (closing || addition?.address?.id == id) return@async false
        dashboard()
        val committed = scope.async { catalog?.select(id) == true }
        if (!try { committed.await() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }) return@async false
        val activation = display(catalog?.snapshots?.value)
        selected.value?.takeIf { it.id == id }?.let { (activation ?: activate(it)).join() }
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

    @Synchronized private fun display(snapshot: CatalogSnapshot?): Job? {
        if (closing) return null
        val id = snapshot?.selected
        val controller = displayController(snapshot) ?: return null
        if (snapshot !== catalog?.snapshots?.value) return null
        val previous = selected.value
        if (previous != null && previous.id == id && previous.controller === controller)
            return activateAddition(snapshot, previous)
        val next = DashboardSelection(id, controller, ::currentProjection)
        selected.value = next
        transferDisplay(previous?.controller, next)
        return activateAddition(snapshot, next) ?: activateAfterRemoval(snapshot, next, previous)
    }

    private fun activateAfterRemoval(snapshot: CatalogSnapshot?, next: DashboardSelection, previous: DashboardSelection?): Job? {
        // Capture actual-removal provenance before any asynchronous activation/restoration.
        val removed = previous?.id != null && snapshot?.entries?.none { it.storage.id == previous.id } == true
        return if (removed && next.id != null) activate(next, enroll = false) else null
    }

    private fun activateAddition(snapshot: CatalogSnapshot?, next: DashboardSelection): Job? {
        val pending = additionActivation ?: return null
        // Only a current projection consumes the one shot; unrelated metadata may replace a snapshot.
        additionActivation = null
        if (next.controller !== pending.controller || !pending.matches(snapshot)) return null
        return scope.launch(mutations) {
            next.controller.requestSelectedUsage(enroll = false) {
                next.isCurrent() && pending.matches(catalog?.snapshots?.value) &&
                    (next.controller.session.snapshot() as? SessionResult.Ready)?.envelope?.generation === pending.generation
            }
        }
    }

    private fun displayController(snapshot: CatalogSnapshot?): ConnectionController? {
        val id = snapshot?.selected ?: return legacy()
        if (addition?.address?.id == id) return null
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

    private fun activate(target: DashboardSelection, enroll: Boolean = true): Job =
        scope.launch(mutations) { target.controller.requestSelectedUsage(enroll, target::isCurrent) }

    /** Resolve from the catalog, never from display. Old WorkSpecs have only the partition UUID. */
    suspend fun refreshInBackground(target: BackgroundRefreshTarget, minimumSuccessAgeMillis: Long): BackgroundRefreshOutcome =
        withContext(mutations) {
            if (closing) return@withContext BackgroundRefreshOutcome.Rejected(RETIRING)
            val metadata = catalog ?: recoverCatalog(observeDashboard = false)
                ?: return@withContext BackgroundRefreshOutcome.Rejected(STORAGE_UNAVAILABLE)
            try { metadata.confirmBackgroundIdentity() } catch (_: Exception) {
                return@withContext BackgroundRefreshOutcome.Rejected(STORAGE_UNAVAILABLE)
            }
            val snapshot = metadata.backgroundSnapshot(target.partition)
                ?: return@withContext BackgroundRefreshOutcome.Rejected(STORAGE_UNAVAILABLE)
            refreshFromSnapshot(metadata, snapshot, target, minimumSuccessAgeMillis)
        }

    private suspend fun refreshFromSnapshot(metadata: AccountCatalog, snapshot: CatalogSnapshot,
        target: BackgroundRefreshTarget, minimumSuccessAgeMillis: Long): BackgroundRefreshOutcome {
        val matches = snapshot.entries.filter { it.partition == target.partition }
        if (matches.size > 1) return BackgroundRefreshOutcome.Rejected(STORAGE_UNAVAILABLE)
        val entry = matches.singleOrNull()
        if (entry == null && knownStaleUnmatched(snapshot)) return BackgroundRefreshOutcome.Rejected(STALE_LIFETIME)
        val controller = backgroundRuntime(snapshot, entry) ?: return BackgroundRefreshOutcome.Rejected(STORAGE_UNAVAILABLE)
        val receipt = entry?.let { saved -> snapshot.deletions.singleOrNull { it.entry == saved.storage.id } }
        if (receipt != null) {
            controller.recoverBackgroundDeletion(receipt.disposition) {
                !closing && metadata.backgroundSnapshot(target.partition)?.deletions?.contains(receipt) == true
            }
            // Only the durable settlement of the exact receipt may cancel this WorkSpec.
            return BackgroundRefreshOutcome.Rejected(RETIRING)
        }
        val outcome = controller.refreshInBackground(target, minimumSuccessAgeMillis) {
            !closing && backgroundBinding(metadata, target, entry?.storage)
        }
        if (outcome == BackgroundRefreshOutcome.Rejected(STALE_LIFETIME) && !canCancel(metadata, target, entry?.storage))
            return BackgroundRefreshOutcome.Rejected(STORAGE_UNAVAILABLE)
        return outcome
    }

    private fun knownStaleUnmatched(snapshot: CatalogSnapshot): Boolean =
        snapshot.entries.all { it.partition != null } && snapshot.entries.any { it.storage.legacy }

    private fun canCancel(metadata: AccountCatalog, target: BackgroundRefreshTarget, address: AccountStorage?): Boolean {
        val snapshot = metadata.backgroundSnapshot(target.partition) ?: return false
        return snapshot.entries.none { it.partition == target.partition &&
            (it.storage != address || snapshot.deletions.any { receipt -> receipt.entry == it.storage.id }) }
    }

    @Synchronized private fun backgroundRuntime(snapshot: CatalogSnapshot, entry: CatalogEntry?): ConnectionController? {
        if (closing) return null
        if (entry != null) return runtime(entry.storage.id)
        if (snapshot.entries.any { !it.storage.legacy && it.partition == null })
            return null
        // Worker-first upgrade: only the compatibility slot can acquire an unrepresented
        // historical binding. Restore/adopt it without displaying or activating any account.
        return legacy()
    }

    private fun backgroundBinding(metadata: AccountCatalog, target: BackgroundRefreshTarget, address: AccountStorage?): Boolean {
        val snapshot = metadata.backgroundSnapshot(target.partition) ?: return false
        val entry = snapshot.entries.singleOrNull { it.partition == target.partition } ?: return false
        return (if (address == null) entry.storage.legacy else entry.storage == address) &&
            snapshot.deletions.none { it.entry == entry.storage.id } && addition?.address != entry.storage
    }

    /** Compatibility ingress, including the empty installation before legacy adoption. */
    @Synchronized fun legacy(): ConnectionController {
        check(!closing)
        if (catalog == null) return unavailableLegacy ?: create(null).also { unavailableLegacy = it }
        return legacyController ?: create(null).also { legacyController = it }
    }

    /** Exact immutable catalog address. Unknown/removed IDs never acquire another runtime. */
    @Synchronized fun runtime(id: UUID): ConnectionController? {
        check(!closing)
        if (addition?.address?.id == id) return null
        val entry = catalog?.entry(id) ?: return null
        return runtimes.getOrPut(id) { if (entry.storage.legacy) legacy() else create(entry.storage) }
    }

    @Synchronized private fun create(address: AccountStorage?, pendingAddition: Boolean = false): ConnectionController {
        val metadata = catalog
        val knownSlot = address?.credentialSlot ?: metadata?.snapshot()?.legacySlot
        val slot = knownSlot ?: UUID.randomUUID()
        check(slot !in slots) { "Credential slot already owned" }
        val store = credentials(applicationContext, slot)
        val directory = address?.historyDirectory(root) ?: File(root, "usage-history")
        val history = metadata?.let {
            HistoryLifetimeCoordinator(CatalogHistoryStorage(historyStorage(directory), it, address) { partition ->
                enrollment(BackgroundRefreshTarget(partition), false)
            })
        }
        return ConnectionController(store, DeviceCodeAuthenticator(transport, store, clock, pause),
            NativeFeasibilityReader(transport, clock, pause), scope, storageReady = knownSlot != null,
            mutationDispatcher = mutations, refreshClock = clock,
            history = history,
            backgroundEnrollment = { target ->
                if (!closing && metadata != null && backgroundBinding(metadata, target, address)) enrollment(target, true)
            },
            savedAccount = { if (address == null) metadata?.legacyEntry() != null else metadata?.entry(address.id) != null },
            pendingAddition = pendingAddition).also { slots.add(slot) }
    }


    suspend fun shutdown() {
        val pending = withContext(mutations) {
            closing = true
            recovery.withLock { addition?.also { if (it.commit == null) it.job?.cancel() } }
        }
        pending?.job?.join()
        if (pending != null && addition === pending) {
            try { pending.controller.shutdown() } finally {
                addition = null
                pending.handle.complete(Rejected.STORAGE)
            }
        }
        val controllers = synchronized(this) {
            closing = true
            (runtimes.values + listOfNotNull(legacyController, unavailableLegacy)).distinct()
        }
        selectionWatch?.cancelAndJoin()
        controllers.forEach { it.close() }
        controllers.forEach { it.shutdown() }
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
