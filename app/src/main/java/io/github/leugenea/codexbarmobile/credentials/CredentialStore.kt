package io.github.leugenea.codexbarmobile.credentials

import io.github.leugenea.codexbarmobile.transport.CancellationHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** No raw error messages, metadata or Throwable chains cross this boundary. */
enum class CredentialFailure {
    MISSING, CORRUPT, KEY_LOST, FAILED_WRITE, CANCELLED, STALE_GENERATION
}

sealed interface CredentialResult<out T> {
    class Success<T>(val value: T) : CredentialResult<T> {
        override fun toString(): String = "CredentialResult.Success(redacted)"
    }
    data class Failure(val category: CredentialFailure) : CredentialResult<Nothing>
}

/**
 * Synchronous storage contract, not a runtime owner or refresh/logout coordinator.
 * openSession invalidates the preceding local owner, including all staged writes.
 * read/replace are generation-bound. delete invalidates BEFORE attempting durable removal;
 * even cancelled/failed deletion never makes that generation readable/writable again.
 * Success of replace means complete durable replacement. Runtime publication still
 * requires a current generation/command; an admitted old commit may settle during logout.
 */
interface CredentialStore {
    fun openSession(): SessionGeneration
    /** Runtime capability only: no decrypt/read or rotation-marker mutation. */
    fun isActive(generation: SessionGeneration): Boolean
    fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope>
    fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope>
    /** Non-I/O revocation. Decorators delegate to the actual store, never duplicate a ticket. */
    fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion>
    fun delete(generation: SessionGeneration): CredentialResult<Unit>
    /** Replace only after durable removal; reject an intervening newly opened generation. */
    fun replaceSession(generation: SessionGeneration): CredentialResult<SessionGeneration>
    /** Protected adapters persist an uncertainty marker before sending a refresh. */
    fun beginRotation(generation: SessionGeneration): CredentialResult<Unit> = CredentialResult.Success(Unit)
    /** Clear only after a complete durable write or a documented transient failure. */
    fun finishRotation(generation: SessionGeneration): CredentialResult<Unit> = CredentialResult.Success(Unit)
}

/** An exactly-once durable removal reserved before runtime retirement; complete outside the lane. */
interface CredentialDeletion {
    fun complete(): CredentialResult<Unit>
}

/**
 * A6 implements protected I/O, not A3. All methods return categorical outcomes.
 * prepare must not change the committed envelope and may run concurrently with deletion.
 * commit has a generation-checked irreversible admission, separate from blocking I/O.
 * Deletion revokes the capability before I/O; the process owner prevents new work
 * until its captured removal completes. An already admitted commit settles before
 * deletion on the store's I/O monitor, never behind a runtime/cancellation monitor.
 * Staging/admission failures preserve the previous envelope. An irreversible rename
 * followed by a durability-barrier failure may leave either complete envelope, never
 * a partial token pair; a refresh owner must quarantine that uncertain outcome.
 * Success means durable completion, not queued I/O or an in-memory file-map update.
 * The process session owner is the only runtime caller of the production slot.
 */
interface CredentialPersistence {
    fun read(): CredentialResult<CredentialEnvelope>
    fun prepare(envelope: CredentialEnvelope): CredentialResult<PreparedCredentialWrite>
    fun delete(): CredentialResult<Unit>
}

interface PreparedCredentialWrite {
    fun commit(): CredentialResult<Unit>
    /** Idempotent staging cleanup only; must never delete/recreate the committed slot. */
    fun discard()
}

/**
 * One cancellation capability per replace call; do not share or reuse it across calls.
 * Cancellation wins before commit admission; once commit starts, its durable outcome wins.
 * There is no callback delivery: A2 TerminalDelivery is transport/deadline-specific, so it
 * is not reused for storage. A one-shot atomic decision arbitrates cancellation against commit admission; cancellation
 * never blocks behind an already admitted irreversible write.
 */
class CredentialCancellation : CancellationHandle {
    private val decision = java.util.concurrent.atomic.AtomicInteger()
    internal fun isCancelled(): Boolean = decision.get() == CANCELLED

    internal fun <T> commit(action: () -> CredentialResult<T>): CredentialResult<T> =
        if (decision.compareAndSet(PENDING, ADMITTED)) action()
        else CredentialResult.Failure(CredentialFailure.CANCELLED)

    override fun cancel() { decision.compareAndSet(PENDING, CANCELLED) }

    private companion object {
        const val PENDING = 0
        const val ADMITTED = 1
        const val CANCELLED = 2
    }
}

/** The process scope owns blocking staging; a cancelled waiter never waits for its I/O child. */
internal suspend fun CredentialStore.replaceAsync(
    scope: CoroutineScope, dispatcher: CoroutineDispatcher, envelope: CredentialEnvelope,
    cancellation: CredentialCancellation,
): CredentialResult<CredentialEnvelope> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancellation.cancel() }
    scope.launch(dispatcher) { continuation.resume(replace(envelope, cancellation)) }
}
