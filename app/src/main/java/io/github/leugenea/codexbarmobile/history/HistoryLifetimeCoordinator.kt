package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.credentials.SessionGeneration

/** Blocking subordinate storage seam; scheduling belongs to the existing process session owner. */
internal interface HistoryLifetimeStorage : AutoCloseable {
    fun stage(): HistoryPartitionOutcome
    fun activate(partition: HistoryPartition): HistoryLifetimeOutcome
    fun restore(): HistoryLifetimeOutcome
    /** Non-I/O reservation, including when no runtime access was adopted. */
    fun reserveDeletion(): HistoryDeletion
}

internal sealed interface HistoryPartitionOutcome {
    data class Staged(val partition: HistoryPartition) : HistoryPartitionOutcome
    data class Unavailable(val reason: HistoryUnavailable) : HistoryPartitionOutcome
    data object Corrupt : HistoryPartitionOutcome
}

/** Revocable port, not a partition-only authority. */
interface HistoryRuntimeAccess : HistoryStore {
    val partition: HistoryPartition
    fun revoke()
}

internal sealed interface HistoryLifetimeOutcome {
    data class Bound(val access: HistoryRuntimeAccess) : HistoryLifetimeOutcome
    data object Unavailable : HistoryLifetimeOutcome
    data object RemovalRequired : HistoryLifetimeOutcome
}

internal enum class HistoryAvailability { UNAVAILABLE, AVAILABLE, STORAGE_FAILURE, REMOVAL_PENDING }

/**
 * No credential identifiers, token comparison or independent owner. A STAGED fence precedes
 * fresh credential persistence; only checked durable adoption activates it. Clean restoration
 * accepts ACTIVE only. Runtime generation identity and durable partition UUID are separate.
 */
internal class HistoryLifetimeCoordinator(private val storage: HistoryLifetimeStorage) : AutoCloseable {
    private val admission = Any()
    private val io = Any()
    private var generation: SessionGeneration? = null
    private var access: HistoryRuntimeAccess? = null
    private var staged: HistoryPartition? = null
    private var removal: HistoryDeletion? = null
    private var runtimeAllowed = true
    @Volatile var availability = HistoryAvailability.UNAVAILABLE
        private set

    /** Owner lane only; never waits for blocking storage. */
    fun open(generation: SessionGeneration) = synchronized(admission) {
        retire()
        this.generation = generation
        runtimeAllowed = true
    }

    fun stage(generation: SessionGeneration): Boolean = synchronized(io) storage@ {
        if (!accepts(generation)) return@storage false
        val result = storage.stage()
        synchronized(admission) admitted@ {
            if (!accepts(generation)) return@admitted false
            staged = (result as? HistoryPartitionOutcome.Staged)?.partition
            availability = if (staged == null) HistoryAvailability.STORAGE_FAILURE else HistoryAvailability.UNAVAILABLE
            staged != null
        }
    }

    fun activate(generation: SessionGeneration) = synchronized(io) {
        val partition = synchronized(admission) { staged.takeIf { accepts(generation) } }
        if (partition != null) adopt(generation, storage.activate(partition))
    }

    fun restore(generation: SessionGeneration) = synchronized(io) {
        if (accepts(generation)) adopt(generation, storage.restore())
    }

    @Volatile var requiresRemoval = false
        private set

    private fun adopt(generation: SessionGeneration, result: HistoryLifetimeOutcome) = synchronized(admission) {
        val bound = (result as? HistoryLifetimeOutcome.Bound)?.access
        if (!accepts(generation)) { bound?.revoke(); return@synchronized }
        requiresRemoval = result == HistoryLifetimeOutcome.RemovalRequired
        staged = null
        if (!runtimeAllowed) {
            bound?.revoke()
            availability = HistoryAvailability.UNAVAILABLE
            return@synchronized
        }
        access = bound
        availability = if (bound == null) HistoryAvailability.STORAGE_FAILURE else HistoryAvailability.AVAILABLE
    }

    /** Capture on the owner lane. Old reads/writes use a retired native capability, never a UUID. */
    fun capability(generation: SessionGeneration): HistoryRuntimeAccess? = synchronized(admission) {
        access.takeIf { accepts(generation) }
    }

    /** Last foreground observer left: retain lifetime/generation, revoke every held runtime port. */
    fun pauseRuntime() = synchronized(admission) {
        runtimeAllowed = false
        access?.revoke()
        access = null
        if (removal == null) availability = HistoryAvailability.UNAVAILABLE
    }

    fun resumeRuntime() = synchronized(admission) { runtimeAllowed = true }

    fun retire() = synchronized(admission) {
        access?.revoke()
        access = null
        generation = null
        staged = null
        availability = HistoryAvailability.UNAVAILABLE
    }

    /** Reservation is immediate; the owner schedules complete off-lane and awaits both stores. */
    fun beginDeletion(): HistoryDeletion = synchronized(admission) {
        retire()
        availability = HistoryAvailability.REMOVAL_PENDING
        removal ?: storage.reserveDeletion().let { reserved ->
            HistoryDeletion { synchronized(io) { settle(reserved.complete()) } }.also { removal = it }
        }
    }

    private fun settle(result: HistoryDeleteOutcome): HistoryDeleteOutcome = synchronized(admission) {
        availability = if (result.successful()) HistoryAvailability.UNAVAILABLE else HistoryAvailability.STORAGE_FAILURE
        if (result.successful()) { removal = null; requiresRemoval = false }
        result
    }

    fun retryDeletion() = synchronized(admission) {
        if (removal?.failed() == true) removal = null
    }

    private fun accepts(candidate: SessionGeneration): Boolean = synchronized(admission) {
        generation === candidate && removal == null
    }

    /** After the process owner has joined admitted storage work, never Activity teardown. */
    override fun close() { retire(); synchronized(io) { storage.close() } }
}

internal fun HistoryDeleteOutcome.successful(): Boolean =
    this == HistoryDeleteOutcome.Deleted || this == HistoryDeleteOutcome.AlreadyAbsent
