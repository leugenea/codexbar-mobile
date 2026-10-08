package io.github.leugenea.codexbarmobile.credentials

import io.github.leugenea.codexbarmobile.transport.CancellationHandle
import java.util.concurrent.locks.ReentrantLock

/**
 * One sequencer per durable slot. Runtime mutation, displacement and the non-suspending
 * transport start share this lane. Never await a response here. Revocation mutations are
 * internal, callback-free operations; job cancellation and observer delivery are deferred.
 * A deletion barrier revokes runtime admission first, without retaining this lane across
 * key/file I/O. Deletion-time effects stay queued until its durable outcome is known.
 */
class SessionOwnership {
    private val lock = ReentrantLock()
    private val pending = ThreadLocal<MutableList<() -> Unit>>()
    private var active: SessionGeneration? = null
    private val revocations = mutableMapOf<SessionGeneration, MutableList<() -> Unit>>()
    private var deletion: Deletion? = null

    internal class Deletion(val previous: java.util.concurrent.CompletableFuture<Boolean>?) {
        val completion = java.util.concurrent.CompletableFuture<Boolean>()
    }
    private val afterDeletion = mutableListOf<() -> Unit>()

    /** Wait outside the runtime lane, then recheck before admitting a durable operation. */
    internal inline fun <T> settled(action: () -> T): T {
        while (true) {
            val pending = serialized {
                val barrier = deletionBarrier()
                if (barrier == null) return action()
                barrier
            }
            pending.join()
        }
    }

    @PublishedApi internal fun deletionBarrier() = deletion?.completion

    internal fun beginDeletion(generation: SessionGeneration, successor: SessionGeneration?): Deletion? = serialized {
        if (active !== generation) return@serialized null
        val admitted = Deletion(deletionBarrier())
        deletion = admitted
        activate(successor)
        admitted
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

    /** Reserved removal and its queued busy-admission signal bypass the barrier outside the lane. */
    internal fun deferDeletion(action: () -> Unit) {
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
