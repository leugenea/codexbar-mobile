package io.github.leugenea.codexbarmobile.history

/** Fixed owner-approved retention ceilings; platform enforcement belongs to M4a-2. */
object HistoryLimits {
    const val RETENTION_DAYS = 30L
    const val MAX_OBSERVATIONS = 100_000
    const val MAX_BYTES = 32L * 1024 * 1024
}

data class HistoryReadQuery(
    val partition: HistoryPartition,
    val limit: Int,
    /** Exclusive durable ordinal, NOT a wall-clock cursor (wall time can go backwards). */
    val after: ObservationId? = null,
) {
    init { require(limit in 1..HistoryLimits.MAX_OBSERVATIONS) }
}

enum class HistoryTruncation { AGE_RETENTION, OBSERVATION_CAP, BYTE_CAP }
enum class HistoryContent { EMPTY, STATUS_ONLY, MEASUREMENTS }
enum class HistoryUnavailable {
    IO_FAILURE, CLOSED, PARTITION_REVOKED, CAPACITY, READ_FAILURE, WRITE_FAILURE,
    STORAGE_FULL, UNSUPPORTED_SCHEMA,
}

/** Highest ordinal evicted by this bound; age-unknown status entries may survive below it. */
data class HistoryEvictionCutoff(val ordinal: Long) {
    init { require(ordinal > 0) }
}

/**
 * Bounded, detached, ascending-ordinal, single-partition snapshot. Segment starts refer to
 * actual observations, including when the first point of a segment was evicted or paged out.
 * A graph must not join across segment IDs or manufacture that missing first point.
 * Content describes this page only; EMPTY is independent of baseline availability.
 */
class HistoryReadSnapshot(
    val query: HistoryReadQuery,
    entries: Collection<HistoryEntry>,
    val lastAdmitted: ObservationId?,
    val hasMore: Boolean,
    truncation: Collection<HistoryTruncation> = emptySet(),
    cutoffs: Map<HistoryTruncation, HistoryEvictionCutoff> = emptyMap(),
) {
    val entries: List<HistoryEntry> = immutableList(entries)
    val truncation: Set<HistoryTruncation> = immutableSet(truncation)
    val cutoffs: Map<HistoryTruncation, HistoryEvictionCutoff> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(cutoffs))
    val nextAfter: ObservationId? = this.entries.lastOrNull()?.id
    val content: HistoryContent = content(this.entries)

    init {
        require(this.entries.size <= query.limit)
        require(this.entries.all { it.partition == query.partition })
        require(this.entries.zipWithNext().all { (before, after) -> before.id.ordinal < after.id.ordinal })
        require(this.entries.all { it.id.ordinal > (query.after?.ordinal ?: 0) })
        require(this.entries.all { it.id.ordinal <= (lastAdmitted?.ordinal ?: 0) })
        require(!hasMore || this.entries.isNotEmpty())
    }

    private fun content(entries: List<HistoryEntry>): HistoryContent = when {
        entries.isEmpty() -> HistoryContent.EMPTY
        entries.any { entry -> entry.windows.any { it.point != null } } -> HistoryContent.MEASUREMENTS
        else -> HistoryContent.STATUS_ONLY
    }
}

sealed interface HistoryReadOutcome {
    data class Ready(val snapshot: HistoryReadSnapshot) : HistoryReadOutcome
    data class Unavailable(val reason: HistoryUnavailable) : HistoryReadOutcome
    data object Corrupt : HistoryReadOutcome
}

sealed interface HistoryAppendOutcome {
    class Stored(val entry: HistoryEntry, truncation: Collection<HistoryTruncation> = emptySet()) : HistoryAppendOutcome {
        val truncation: Set<HistoryTruncation> = immutableSet(truncation)
    }
    /** Also returned for evicted ordinals; retries never restore a removed observation. */
    data class AlreadyAdmitted(val id: ObservationId) : HistoryAppendOutcome
    data class Rejected(val reason: AdmissionRejection) : HistoryAppendOutcome
    data class Unavailable(val reason: HistoryUnavailable) : HistoryAppendOutcome
    data object Corrupt : HistoryAppendOutcome
}

sealed interface HistoryDeleteOutcome {
    data object Deleted : HistoryDeleteOutcome
    data object AlreadyAbsent : HistoryDeleteOutcome
    data class Unavailable(val reason: HistoryUnavailable) : HistoryDeleteOutcome
    data object Corrupt : HistoryDeleteOutcome
}

/**
 * Blocking I/O; the native adapter serializes it, and callers keep it off Main. No session owner.
 *
 * Append is serialized per partition, runs WindowHistory, and durably commits its entry,
 * cursor and high-water ordinal atomically. Only contiguous ordinals are admitted: read
 * lastAdmitted and use the successor for a NEW event, retry the SAME ID after uncertain I/O.
 * Every ordinal <= high-water was admitted, including status/gap entries; retries are
 * idempotent even after retention eviction. No update/replacement of measured points.
 * Rejected/failed admissions do not consume an ordinal; Long.MAX_VALUE has no successor.
 *
 * Read never crosses partitions, sorts by ordinal (not timestamp) and honors the limit.
 * Retention deletion preserves high-water and cursor; report every applied retention bound.
 * The port's empty snapshot is distinct from failure. The native capability layer rejects
 * unadopted/revoked partitions before I/O; a newly created bound partition reads empty.
 * Explicit delete removes records, cursor and high-water ONLY for the named partition.
 * Successful deletion means durable removal; failures are typed, not a thrown raw exception.
 * M4a-4 revokes the runtime capability before deletion and never reuses a deleted partition.
 * A history failure never replaces or blocks the live quota result (M4a-5).
 */
interface HistoryStore {
    fun append(admission: HistoryAdmission): HistoryAppendOutcome
    fun read(query: HistoryReadQuery): HistoryReadOutcome
    fun delete(partition: HistoryPartition): HistoryDeleteOutcome
}
