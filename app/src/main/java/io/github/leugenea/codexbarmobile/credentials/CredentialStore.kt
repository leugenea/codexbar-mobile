package io.github.leugenea.codexbarmobile.credentials

import io.github.leugenea.codexbarmobile.transport.CancellationHandle

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
 * Synchronous mutation contract, not an executor or a refresh/logout coordinator.
 * openSession invalidates the preceding local owner, including all staged writes.
 * read/replace are generation-bound. delete invalidates BEFORE attempting durable removal;
 * even cancelled/failed deletion never makes that generation readable/writable again.
 * Success of replace is publication permission only after complete durable replacement.
 */
interface CredentialStore {
    /** Shared by every adapter and live runtime owner of this exact slot. */
    val ownership: SessionOwnership
    fun openSession(): SessionGeneration
    /** Atomic command admission: reserve removal before displacing the current owner. */
    fun admitCommandRemoval(replacement: Boolean): CredentialRemoval
    /** Runtime capability only: no decrypt/read or rotation-marker mutation. */
    fun isActive(generation: SessionGeneration): Boolean
    fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope>
    fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope>
    /** Non-I/O admission. Every decorator must delegate to this slot's actual kernel. */
    fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion>
    fun delete(generation: SessionGeneration): CredentialResult<Unit>
    /** Reserve replacement at deletion admission; return it only after durable removal and revalidation. */
    fun replaceSession(generation: SessionGeneration): CredentialResult<SessionGeneration>
    /** Protected adapters persist an uncertainty marker before sending a refresh. */
    fun beginRotation(generation: SessionGeneration): CredentialResult<Unit> = CredentialResult.Success(Unit)
    /** Clear only after a complete durable write or a documented transient failure. */
    fun finishRotation(generation: SessionGeneration): CredentialResult<Unit> = CredentialResult.Success(Unit)
}

/** An exactly-once durable removal reserved before runtime retirement; complete outside the lane. */
interface CredentialDeletion {
    /** Start the exactly-once runner independently of lifecycle waiters. */
    fun start()
    fun complete(): CredentialResult<Unit>
    suspend fun await(): CredentialResult<Unit>
}

/** Only an account-replacement command receives a successor capability. */
class CredentialRemoval(val deletion: CredentialDeletion, val successor: SessionGeneration?)

/**
 * A6 implements protected I/O, not A3. All methods return categorical outcomes.
 * prepare must not change the committed envelope and may run concurrently with deletion.
 * commit is generation-guarded on the store's serialized lane. Deletion reserves a
 * slot-wide barrier and invalidates its capability there, then removes key/file outside
 * the runtime lane. Durable operations wait outside that lane and recheck admission.
 * Staging/admission failures preserve the previous envelope. An irreversible rename
 * followed by a durability-barrier failure may leave either complete envelope, never
 * a partial token pair; a refresh owner must quarantine that uncertain outcome.
 * Success means durable completion, not queued I/O or an in-memory file-map update.
 * One shared ownership lane is required for every adapter targeting the same durable slot.
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
 * is not reused for storage. The lock here arbitrates cancellation against irreversible I/O.
 */
class CredentialCancellation : CancellationHandle {
    private var cancelled = false
    private var admitted = false

    internal fun isCancelled(): Boolean = synchronized(this) { cancelled }

    internal fun <T> commit(action: () -> CredentialResult<T>): CredentialResult<T> = synchronized(this) {
        if (cancelled) CredentialResult.Failure(CredentialFailure.CANCELLED) else {
            admitted = true
            action()
        }
    }

    override fun cancel() {
        synchronized(this) { if (!admitted) cancelled = true }
    }
}
