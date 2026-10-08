package io.github.leugenea.codexbarmobile.history

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface HistoryAccessOutcome {
    data class Bound(val access: HistoryAccess) : HistoryAccessOutcome
    data class Unavailable(val reason: HistoryUnavailable) : HistoryAccessOutcome
    data object Corrupt : HistoryAccessOutcome
}

/** Partition-scoped port: there is no unguarded UUID-only SQLite writer. */
class HistoryAccess internal constructor(
    private val owner: SQLiteHistoryStore,
    val partition: HistoryPartition,
    internal val generation: HistoryGeneration,
) : HistoryStore {
    private var deletion: HistoryDeletion? = null
    override fun append(admission: HistoryAdmission): HistoryAppendOutcome = owner.append(this, admission)
    override fun read(query: HistoryReadQuery): HistoryReadOutcome = owner.read(this, query)
    override fun delete(partition: HistoryPartition): HistoryDeleteOutcome {
        if (partition != this.partition) return HistoryDeleteOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        return beginDelete().complete()
    }
    /** Runtime retirement only: clean restoration may adopt the continuing durable lifetime. */
    fun revoke() = owner.revoke(this)
    /** Immediate runtime fence; caller schedules blocking, idempotent complete() on its storage lane. */
    @Synchronized fun beginDelete(): HistoryDeletion = deletion ?: owner.beginDelete(this).also { deletion = it }
}

/** Exactly one settlement per ticket. Failure is truthful; explicit administration may retry cleanup. */
class HistoryDeletion internal constructor(private val work: () -> HistoryDeleteOutcome) {
    @Volatile private var outcome: HistoryDeleteOutcome? = null
    @Synchronized fun complete(): HistoryDeleteOutcome = outcome ?: work().also { outcome = it }
    internal fun failed(): Boolean = outcome is HistoryDeleteOutcome.Unavailable || outcome == HistoryDeleteOutcome.Corrupt
}

/** Test gates/faults are operation tags, never thread-name or compiler-stack predicates. */
internal interface HistoryStorageHooks {
    fun beforeWriteAdmission() = Unit
    fun afterWriteAdmission() = Unit
    fun afterInsert() = Unit
    fun beforeCommit() = Unit
    fun afterCommit() = Unit
    fun afterTombstone() = Unit
    fun beforeRead() = Unit
    fun beforeMaintenanceCommit() = Unit
}

/**
 * One blocking serialized storage lane and one local credential-lifetime binding, not a session owner.
 * Creation allocates a fresh UUID; adoption NEVER creates an unknown/deleted UUID. A fixed control
 * fence replaces an unbounded tombstone blacklist. Session/credential orchestration belongs to #73.
 */
class SQLiteHistoryStore internal constructor(
    directory: File,
    private val limits: HistoryStorageLimits = HistoryStorageLimits(),
    private val hooks: HistoryStorageHooks = object : HistoryStorageHooks {},
) : AutoCloseable {
    private val io = Any()
    private val admission = Any()
    private val files = HistoryFiles(directory, limits)
    private val schema = HistorySchema(files.database, limits)
    private var binding: HistoryBinding? = null
    private var active: HistoryGeneration? = null
    private var activePartition: HistoryPartition? = null
    private var removal: HistoryDeletion? = null
    private var closed = false

    fun createPartition(): HistoryAccessOutcome = operation(HistoryUnavailable.WRITE_FAILURE, ::accessFailure) {
        initialize()
        if (binding!!.phase != HistoryBindingPhase.EMPTY) return@operation HistoryAccessOutcome.Unavailable(HistoryUnavailable.CAPACITY)
        if (hasRemoval()) return@operation HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        val partition = HistoryPartition(UUID.randomUUID())
        changeBinding(HistoryBinding(HistoryBindingPhase.DELETING, partition))
        schema.open(partition, create = true)
        changeBinding(HistoryBinding(HistoryBindingPhase.ACTIVE, partition))
        files.checkBudget()
        bind(partition)
    }

    fun adopt(partition: HistoryPartition): HistoryAccessOutcome = operation(HistoryUnavailable.READ_FAILURE, ::accessFailure) {
        initialize()
        if (binding != HistoryBinding(HistoryBindingPhase.ACTIVE, partition) || hasRemoval()) {
            return@operation HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        }
        schema.open(partition, create = false)
        maintainCount(partition)
        bind(partition)
    }

    private fun bind(partition: HistoryPartition): HistoryAccessOutcome = synchronized(admission) {
        if (removal != null) return@synchronized HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        val generation = HistoryGeneration()
        active = generation; activePartition = partition
        HistoryAccessOutcome.Bound(HistoryAccess(this, partition, generation))
    }

    private fun initialize() {
        if (binding != null) return
        files.acquire()
        val disk = files.readBinding()
        if (disk.phase == HistoryBindingPhase.DELETING) {
            finishRemoval(disk.partition)
            return
        }
        if (disk.phase == HistoryBindingPhase.EMPTY && files.containsDatabaseArtifacts()) throw HistoryCorruption()
        binding = disk
    }

    internal fun append(access: HistoryAccess, value: HistoryAdmission): HistoryAppendOutcome =
        operation(HistoryUnavailable.WRITE_FAILURE, ::appendFailure) {
            if (!authorized(access, value.partition)) return@operation appendFailure(HistoryUnavailable.PARTITION_REVOKED)
            val previous = schema.state(access.partition)
            if (value.id.ordinal <= previous.cursor.lastOrdinal) return@operation HistoryAppendOutcome.AlreadyAdmitted(value.id)
            if (!bounded(value)) return@operation HistoryAppendOutcome.Rejected(AdmissionRejection.FIELD_CAPACITY)
            when (val reduced = WindowHistory.append(previous.cursor, value)) {
                is HistoryReduction.AlreadyAdmitted -> HistoryAppendOutcome.AlreadyAdmitted(reduced.id)
                is HistoryReduction.Rejected -> HistoryAppendOutcome.Rejected(reduced.reason)
                is HistoryReduction.Applied -> store(access, previous, reduced)
            }
        }

    private fun store(access: HistoryAccess, previous: HistoryDiskState, reduced: HistoryReduction.Applied): HistoryAppendOutcome {
        val payload = try { HistoryEncoding.entry(reduced.entry) } catch (_: HistoryFieldCapacity) {
            return HistoryAppendOutcome.Rejected(AdmissionRejection.FIELD_CAPACITY)
        } catch (_: IllegalArgumentException) {
            return HistoryAppendOutcome.Rejected(AdmissionRejection.INVALID_SAMPLE)
        }
        var next = HistoryRetention.advance(previous, reduced)
        val encoded = try { HistoryEncoding.state(next) } catch (_: IllegalArgumentException) {
            return HistoryAppendOutcome.Rejected(AdmissionRejection.FIELD_CAPACITY)
        }
        hooks.beforeWriteAdmission()
        if (!authorized(access, access.partition)) return appendFailure(HistoryUnavailable.PARTITION_REVOKED)
        // This operation is now irreversible-admitted. Revocation is nonblocking; deletion waits
        // behind this exact I/O lane and removes the completed commit before reporting success.
        hooks.afterWriteAdmission()
        reserveSpace(access.partition, payload.size + encoded.size)
        val retained = schema.state(access.partition)
        next = next.copy(cutoffs = retained.cutoffs)
        val cutoff = HistoryRetention.cutoff(previous, next, limits.retentionDays)
        next = schema.transaction {
            schema.insert(reduced.entry, payload); hooks.afterInsert()
            var state = next
            cutoff?.let { state = ageEviction(state, it) }
            val excess = schema.count() - limits.observations
            if (excess > 0) state = evict(state, HistoryTruncation.OBSERVATION_CAP, schema.oldestPrefix(excess))
            schema.writeState(state); hooks.beforeCommit()
            state
        }
        hooks.afterCommit()
        files.checkBudget()
        return HistoryAppendOutcome.Stored(reduced.entry, next.cutoffs.keys)
    }

    /** Pre-transaction headroom; evictions are separate durable maintenance, never consumed IDs. */
    private fun reserveSpace(partition: HistoryPartition, payloadBytes: Int) {
        files.checkBudget()
        val requiredPages = 8L + (payloadBytes + 4095) / 4096
        while (schema.spacePages() < requiredPages) {
            if (schema.count() == 0L) throw HistoryStorageException(HistoryUnavailable.STORAGE_FULL)
            schema.transaction {
                val state = schema.state(partition)
                val next = evict(state, HistoryTruncation.BYTE_CAP, schema.oldestPrefix(64))
                schema.writeState(next)
                hooks.beforeMaintenanceCommit()
            }
        }
    }

    private fun evict(state: HistoryDiskState, reason: HistoryTruncation, ordinal: Long): HistoryDiskState {
        if (ordinal == 0L) return state
        schema.removeThrough(ordinal)
        return HistoryRetention.removed(state, reason, ordinal)
    }

    private fun ageEviction(state: HistoryDiskState, cutoff: java.time.Instant): HistoryDiskState {
        val ordinal = schema.ageBefore(cutoff, state.cursor.partition)
        if (ordinal == 0L) return state
        schema.removeBefore(cutoff)
        return HistoryRetention.removed(state, HistoryTruncation.AGE_RETENTION, ordinal)
    }

    private fun maintainCount(partition: HistoryPartition) {
        val excess = schema.count() - limits.observations
        if (excess <= 0) return
        schema.transaction {
            val state = evict(schema.state(partition), HistoryTruncation.OBSERVATION_CAP, schema.oldestPrefix(excess))
            schema.writeState(state)
            hooks.beforeMaintenanceCommit()
        }
    }

    internal fun read(access: HistoryAccess, query: HistoryReadQuery): HistoryReadOutcome =
        operation(HistoryUnavailable.READ_FAILURE, ::readFailure) {
            if (!authorized(access, query.partition)) return@operation readFailure(HistoryUnavailable.PARTITION_REVOKED)
            if (query.limit > limits.readPage) return@operation readFailure(HistoryUnavailable.CAPACITY)
            hooks.beforeRead()
            val state = schema.state(access.partition)
            val (page, more) = schema.page(query, query.limit)
            if (!authorized(access, query.partition)) return@operation readFailure(HistoryUnavailable.PARTITION_REVOKED)
            val last = state.cursor.lastOrdinal.takeIf { it > 0 }?.let { ObservationId(it) }
            HistoryReadOutcome.Ready(HistoryReadSnapshot(query, page, last, more, state.cutoffs.keys, state.cutoffs))
        }

    private fun authorized(access: HistoryAccess, partition: HistoryPartition): Boolean = synchronized(admission) {
        !closed && removal == null && active === access.generation && activePartition == partition && access.partition == partition
    }
    private fun bounded(value: HistoryAdmission): Boolean {
        val event = value.event as? HistoryEvent.Observed ?: return true
        return event.usage.slots.size <= 2 && event.usage.fiveHour.candidates.size <= 2 && event.usage.weekly.candidates.size <= 2
    }
    private fun hasRemoval(): Boolean = synchronized(admission) { removal != null }
    internal fun revoke(access: HistoryAccess) = synchronized(admission) {
        if (active === access.generation) active = null
    }

    internal fun beginDelete(access: HistoryAccess): HistoryDeletion = synchronized(admission) {
        if (!authorized(access, access.partition)) return@synchronized HistoryDeletion { deleteFailure(HistoryUnavailable.PARTITION_REVOKED) }
        active = null
        HistoryDeletion { remove(access.partition) }.also { removal = it }
    }

    private fun remove(partition: HistoryPartition): HistoryDeleteOutcome = operation(HistoryUnavailable.WRITE_FAILURE, ::deleteFailure) {
        if (binding?.partition != partition) return@operation deleteFailure(HistoryUnavailable.PARTITION_REVOKED)
        changeBinding(HistoryBinding(HistoryBindingPhase.DELETING, partition))
        hooks.afterTombstone()
        finishRemoval(partition)
        synchronized(admission) { removal = null; activePartition = null }
        HistoryDeleteOutcome.Deleted
    }

    private fun finishRemoval(partition: HistoryPartition) {
        schema.close()
        files.discardDatabase()
        changeBinding(HistoryBinding(HistoryBindingPhase.EMPTY, partition))
        if (files.readBinding() != binding) throw IOException()
    }

    /** Explicit destructive recovery ONLY; no automatic migration or fabricated replacement samples. */
    fun quarantineAndDelete(): HistoryDeleteOutcome {
        val ticket = synchronized(admission) {
            active = null
            val pending = removal
            if (pending != null && !pending.failed()) pending else HistoryDeletion { quarantineStorage() }.also { removal = it }
        }
        return ticket.complete()
    }

    private fun quarantineStorage(): HistoryDeleteOutcome = operation(HistoryUnavailable.WRITE_FAILURE, ::deleteFailure) {
            files.acquire()
            val prior = try { files.readBinding() } catch (_: HistoryCorruption) {
                HistoryBinding(HistoryBindingPhase.DELETING, HistoryPartition(UUID(0, 0)))
            }
            binding = prior
            changeBinding(prior.copy(phase = HistoryBindingPhase.DELETING))
            hooks.afterTombstone()
            finishRemoval(prior.partition)
            synchronized(admission) { removal = null; activePartition = null }
            HistoryDeleteOutcome.Deleted
    }

    private fun changeBinding(value: HistoryBinding) {
        files.writeBinding(value)
        binding = value
    }

    private fun <T> operation(default: HistoryUnavailable, failure: (HistoryUnavailable?) -> T, work: () -> T): T = synchronized(io) {
        if (synchronized(admission) { closed }) return@synchronized failure(HistoryUnavailable.CLOSED)
        try { work() } catch (_: SQLiteDatabaseCorruptException) {
            failure(null)
        } catch (_: HistoryCorruption) {
            failure(null)
        } catch (_: SQLiteFullException) {
            failure(HistoryUnavailable.STORAGE_FULL)
        } catch (problem: HistoryStorageException) {
            failure(problem.reason)
        } catch (_: Exception) {
            failure(default)
        }
    }

    override fun close() {
        synchronized(admission) { closed = true; active = null }
        synchronized(io) { try { schema.close() } finally { files.close() } }
    }

    private fun accessFailure(reason: HistoryUnavailable?): HistoryAccessOutcome =
        if (reason == null) HistoryAccessOutcome.Corrupt else HistoryAccessOutcome.Unavailable(reason)
    private fun appendFailure(reason: HistoryUnavailable?): HistoryAppendOutcome =
        if (reason == null) HistoryAppendOutcome.Corrupt else HistoryAppendOutcome.Unavailable(reason)
    private fun readFailure(reason: HistoryUnavailable?): HistoryReadOutcome =
        if (reason == null) HistoryReadOutcome.Corrupt else HistoryReadOutcome.Unavailable(reason)
    private fun deleteFailure(reason: HistoryUnavailable?): HistoryDeleteOutcome =
        if (reason == null) HistoryDeleteOutcome.Corrupt else HistoryDeleteOutcome.Unavailable(reason)

    companion object {
        fun open(context: Context): SQLiteHistoryStore = SQLiteHistoryStore(
            File(context.applicationContext.noBackupFilesDir, "usage-history").canonicalFile)
    }
}
