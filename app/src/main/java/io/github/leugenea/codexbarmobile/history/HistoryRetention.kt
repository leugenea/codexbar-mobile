package io.github.leugenea.codexbarmobile.history

import java.time.Duration
import java.time.Instant

/** Smaller injectable ceilings are for deterministic synthetic tests, never bigger defaults. */
data class HistoryStorageLimits(
    val retentionDays: Long = HistoryLimits.RETENTION_DAYS,
    val observations: Int = HistoryLimits.MAX_OBSERVATIONS,
    val bytes: Long = HistoryLimits.MAX_BYTES,
    val readPage: Int = 256,
) {
    init {
        require(retentionDays in 1..HistoryLimits.RETENTION_DAYS)
        require(observations in 1..HistoryLimits.MAX_OBSERVATIONS)
        require(bytes in 262_144..HistoryLimits.MAX_BYTES)
        require(readPage in 1..256)
    }
    /** Reserve three DB lengths for rollback/sector headers, plus all fixed control artifacts. */
    internal val databasePages: Long get() = (bytes - 65_536) / (4 * 4096)
}

internal data class HistoryAgeAnchor(val at: Instant, val clock: HistoryClock)
internal data class HistoryDiskState(
    val cursor: HistoryCursor,
    val anchor: HistoryAgeAnchor? = null,
    val ageSuspended: Boolean = false,
    val cutoffs: Map<HistoryTruncation, HistoryEvictionCutoff> = emptyMap(),
    val lastClock: HistoryClock? = null,
)

/** No receipt/system time: age eviction is permitted only by consecutive verified observation clocks. */
internal object HistoryRetention {
    fun advance(previous: HistoryDiskState, applied: HistoryReduction.Applied): HistoryDiskState {
        val clock = applied.entry.clock
        val anchor = applied.entry.observedAt?.let { HistoryAgeAnchor(it, clock) }
        val changed = anchor?.let { after -> previous.anchor?.let { !trustworthy(it, after) } } == true
        val anomalous = changed || clockAnomaly(previous.lastClock, clock)
        return previous.copy(cursor = applied.cursor, anchor = anchor ?: previous.anchor,
            ageSuspended = previous.ageSuspended || anomalous, lastClock = clock)
    }

    private fun clockAnomaly(before: HistoryClock?, after: HistoryClock): Boolean {
        val current = after.monotonicMillis ?: return true
        if (before == null) return false
        val old = before.monotonicMillis ?: return true
        return before.epoch != after.epoch || current <= old
    }

    fun cutoff(previous: HistoryDiskState, next: HistoryDiskState, days: Long): Instant? {
        if (next.ageSuspended) return null
        val before = previous.anchor ?: return null
        val after = next.anchor ?: return null
        if (!trustworthy(before, after)) return null
        return try { after.at.minus(Duration.ofDays(days)) } catch (_: java.time.DateTimeException) { null }
    }

    private fun trustworthy(before: HistoryAgeAnchor, after: HistoryAgeAnchor): Boolean {
        if (before.clock.epoch != after.clock.epoch) return false
        val old = before.clock.monotonicMillis ?: return false
        val current = after.clock.monotonicMillis ?: return false
        val wall = Duration.between(before.at, after.at)
        val monotonic = Duration.ofMillis(current).minus(Duration.ofMillis(old))
        return wall > Duration.ZERO && monotonic > Duration.ZERO && wall.minus(monotonic).abs() <= Duration.ofSeconds(5)
    }

    fun removed(state: HistoryDiskState, reason: HistoryTruncation, ordinal: Long): HistoryDiskState {
        require(ordinal in 1..state.cursor.lastOrdinal)
        val last = state.cutoffs[reason]?.ordinal ?: 0
        return state.copy(cutoffs = state.cutoffs + (reason to HistoryEvictionCutoff(maxOf(last, ordinal))))
    }
}
