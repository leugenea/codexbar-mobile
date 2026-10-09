package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.UsageRefreshState
import io.github.leugenea.codexbarmobile.RefreshedEndpoint
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.time.Instant

/** Domain selection only. Pagination is admission order, never a guessed wall-clock range. */
internal data class HistoryGraphQuery(
    val limit: Int = 256,
    val after: ObservationId? = null,
    val kind: WindowKind? = null,
    val window: WindowIdentity? = null,
) {
    init { require(limit in 1..256) }
    fun storage(partition: HistoryPartition) = HistoryReadQuery(partition, limit, after)
    fun selects(source: HistoryWindow): Boolean =
        (kind == null || source.kind == kind) && (window == null || source.point?.segment?.window == window)
}

internal enum class HistoryReadiness { UNAVAILABLE, LOADING, EMPTY, READY, ERROR }
internal enum class HistoryRecorderProblem { STORAGE_UNAVAILABLE, READ_FAILURE, WRITE_FAILURE, CORRUPT, QUEUE_OVERFLOW, ORDINAL_CAPACITY }
internal enum class HistoryUnit { PERCENT, PERCENTAGE_POINTS, ELAPSED_SI_SECONDS }

/** Safe endpoint metadata only: no provider text/payload, and no synthesized attempt timestamp. */
internal data class HistoryEndpointMetadata(
    val sourceObservedAt: Instant? = null,
    val latestAttemptObservedAt: Instant? = null,
    val status: Int? = null,
    val error: ReadError? = null,
    val stale: Boolean = false,
)
internal data class HistoryLiveMetadata(
    val usage: HistoryEndpointMetadata = HistoryEndpointMetadata(),
    val inventory: HistoryEndpointMetadata = HistoryEndpointMetadata(),
    val refreshing: Boolean = false,
) {
    companion object {
        fun from(refresh: UsageRefreshState) = HistoryLiveMetadata(endpoint(refresh.usage), endpoint(refresh.inventory), refresh.refreshing)
        private fun endpoint(source: RefreshedEndpoint) = HistoryEndpointMetadata(source.success?.observedAt,
            source.attempt?.observedAt, source.attempt?.status, source.attempt?.error, source.stale)
    }
}

internal class HistoryGraphWindow(val source: HistoryWindow) {
    val reference: DistributionReference = EvenDistribution.reference(source)
    val comparison: UsedComparison = EvenDistribution.compare(source)
}
internal class HistoryGraphEntry(val source: HistoryEntry, windows: Collection<HistoryGraphWindow>) {
    val windows: List<HistoryGraphWindow> = immutableList(windows)
}

/**
 * Detached immutable bounded view. The nonpersistable generation must be compared by identity.
 * A saved snapshot is not permission to read a retired login. M4b consumes this protocol; it must
 * keep analytical descriptors apart from actual points and never join different segment IDs.
 */
internal class HistoryGraphSnapshot(
    val readiness: HistoryReadiness = HistoryReadiness.UNAVAILABLE,
    val generation: HistoryGeneration? = null,
    val partition: HistoryPartition? = null,
    val query: HistoryGraphQuery = HistoryGraphQuery(),
    val storage: HistoryReadSnapshot? = null,
    val problem: HistoryRecorderProblem? = null,
    val storageReason: HistoryUnavailable? = null,
    val lostSamples: Long = 0,
    val live: HistoryLiveMetadata = HistoryLiveMetadata(),
) {
    val usedUnit = HistoryUnit.PERCENT
    val baselineUnit = HistoryUnit.PERCENT
    val deltaUnit = HistoryUnit.PERCENTAGE_POINTS
    val durationUnit = HistoryUnit.ELAPSED_SI_SECONDS
    val entries: List<HistoryGraphEntry> = immutableList(storage?.entries.orEmpty().map { entry ->
        HistoryGraphEntry(entry, entry.windows.filter(query::selects).map(::HistoryGraphWindow))
    })
    /** All page gaps remain, even when selection removes every window on an entry. */
    val points: List<HistoryPoint> = immutableList(entries.flatMap { entry -> entry.windows.mapNotNull { it.source.point } })
    val hasMore: Boolean get() = storage?.hasMore == true
    val truncation: Set<HistoryTruncation> get() = storage?.truncation ?: emptySet()
}
