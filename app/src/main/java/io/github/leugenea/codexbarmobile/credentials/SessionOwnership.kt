package io.github.leugenea.codexbarmobile.credentials

import io.github.leugenea.codexbarmobile.transport.CancellationHandle
import java.util.concurrent.locks.ReentrantLock

/**
 * One sequencer per durable slot. Runtime mutation, displacement and the non-suspending
 * transport start share this lane. Never await a response here. Revocation mutations are
 * internal, callback-free operations; job cancellation and observer delivery are deferred.
 * A deletion barrier revokes runtime admission first, without retaining this lane across
 * key/file I/O. Publication stays queued until the outcome; lifecycle cancellation does not.
 */
class SessionOwnership(internal val storageWaitMillis: Long = STORAGE_WAIT_MILLIS) {
    private val lock = ReentrantLock()
    private val pending = ThreadLocal<MutableList<() -> Unit>>()
    private var active: SessionGeneration? = null
    private val revocations = mutableMapOf<SessionGeneration, MutableList<() -> Unit>>()
    private var deletion: Deletion? = null

    internal class Deletion(val previous: java.util.concurrent.CompletableFuture<Boolean>?) {
        val completion = java.util.concurrent.CompletableFuture<Boolean>()
    }
    private val afterDeletion = mutableListOf<() -> Unit>()

    /** Legacy synchronous storage callers wait with a bound, outside the runtime lane. */
    internal inline fun <T> settled(
        generation: SessionGeneration? = null,
        action: () -> CredentialResult<T>,
    ): CredentialResult<T> {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(storageWaitMillis)
        while (true) {
            val pending = serialized {
                if (generation != null && !isActive(generation)) return CredentialResult.Failure(CredentialFailure.STALE_GENERATION)
                val barrier = deletionBarrier()
                if (barrier == null) return action()
                barrier
            }
            val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (pending.waitStorage(remaining.coerceAtLeast(0)) == null) return CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
        }
    }

    /** Coroutine callers suspend, cancel promptly, and revalidate on the lane after waking. */
    internal suspend fun <T> awaitSettled(action: () -> CredentialResult<T>): CredentialResult<T> =
        kotlinx.coroutines.withTimeoutOrNull(storageWaitMillis) { awaitLane(action) }
            ?: CredentialResult.Failure(CredentialFailure.FAILED_WRITE)

    private suspend fun <T> awaitLane(action: () -> CredentialResult<T>): CredentialResult<T> {
        while (true) {
            val barrier = serialized {
                val barrier = deletionBarrier()
                if (barrier == null) return action()
                barrier
            }
            if (barrier.awaitStorage(storageWaitMillis) == null) return CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
        }
    }

    /** Command admission creates its barrier BEFORE displacing any live controller. */
    internal fun beginCommandRemoval(successor: SessionGeneration?): Deletion = serialized {
        val admitted = Deletion(deletionBarrier())
        deletion = admitted
        activate(successor)
        admitted
    }

    @PublishedApi internal fun deletionBarrier() = deletion?.completion

    internal fun beginDeletion(generation: SessionGeneration, successor: SessionGeneration?): Deletion? = serialized {
        if (active !== generation) return@serialized null
        beginCommandRemoval(successor)
    }

    internal fun finishDeletion(admitted: Deletion, succeeded: Boolean) {
        val effects = serialized {
            if (deletion !== admitted) emptyList() else {
                deletion = null
                afterDeletion.toList().also { afterDeletion.clear() }
            }
        }
        // Neither waiters nor observer/cancellation callbacks run under the runtime lane.
        admitted.completion.complete(succeeded)
        effects.forEach(::deliver)
    }

    private fun deliver(action: () -> Unit) {
        val held = serialized {
            if (deletion == null) false else { afterDeletion.add(action); true }
        }
        if (!held) try { action() } catch (_: Exception) { /* Drain every effect. */ }
    }

    inline fun <T> serialized(action: () -> T): T {
        enter()
        try { return action() } finally { leave() }
    }

    @PublishedApi internal fun enter() {
        lock.lock()
        if (lock.holdCount == 1) pending.set(mutableListOf())
    }

    @PublishedApi internal fun leave() {
        val effects = if (lock.holdCount == 1) pending.get().also { pending.remove() } else null
        lock.unlock()
        effects?.forEach { effect ->
            try { effect() } catch (_: Exception) { /* Drain every admitted effect. */ }
        }
    }

    fun defer(action: () -> Unit) {
        if (lock.isHeldByCurrentThread) requireNotNull(pending.get()).add { deliver(action) } else deliver(action)
    }

    /** Durable runners are enqueued before callback-capable lifecycle/observer effects. */
    internal fun deferRemoval(action: () -> Unit) {
        if (lock.isHeldByCurrentThread) requireNotNull(pending.get()).add(0, action) else action()
    }

    /** Lifecycle cancellation and busy-admission signals never wait behind durable I/O. */
    internal fun afterLane(action: () -> Unit) {
        if (lock.isHeldByCurrentThread) requireNotNull(pending.get()).add(action) else action()
    }

    fun isActive(generation: SessionGeneration): Boolean = serialized { active === generation }

    fun ifActive(generation: SessionGeneration, action: () -> Unit): Boolean = serialized {
        if (active !== generation || deletion != null) false else { action(); true }
    }

    internal fun activate(generation: SessionGeneration?) {
        serialized {
            val old = active
            active = generation
            revocations.remove(old)?.forEach { it() }
        }
    }

    /** Only trusted, non-suspending state mutations belong here; defer all callbacks. */
    internal fun onDisplaced(generation: SessionGeneration, mutation: () -> Unit): CancellationHandle = serialized {
        if (active === generation) revocations.getOrPut(generation) { mutableListOf() }.add(mutation)
        else mutation()
        CancellationHandle { serialized { revocations[generation]?.remove(mutation) } }
    }
}
