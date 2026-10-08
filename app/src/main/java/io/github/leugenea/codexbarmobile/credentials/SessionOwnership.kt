package io.github.leugenea.codexbarmobile.credentials

import io.github.leugenea.codexbarmobile.transport.CancellationHandle
import java.util.concurrent.locks.ReentrantLock

/**
 * One sequencer per durable slot. Runtime mutation, displacement and the non-suspending
 * transport start share this lane. Never await a response here. Revocation mutations are
 * internal, callback-free operations; job cancellation and observer delivery are deferred.
 */
class SessionOwnership {
    private val lock = ReentrantLock()
    private val pending = ThreadLocal<MutableList<() -> Unit>>()
    private var active: SessionGeneration? = null
    private val revocations = mutableMapOf<SessionGeneration, MutableList<() -> Unit>>()

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
            try { effect() } catch (_: Exception) { /* Other cleanup/notifications must still run. */ }
        }
    }

    fun defer(action: () -> Unit) {
        if (lock.isHeldByCurrentThread) requireNotNull(pending.get()).add(action) else action()
    }

    fun isActive(generation: SessionGeneration): Boolean = serialized { active === generation }

    fun ifActive(generation: SessionGeneration, action: () -> Unit): Boolean = serialized {
        if (active !== generation) false else { action(); true }
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
