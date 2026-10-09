package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Original synthetic admitted facts. Not provider/account or continuous-history evidence. */
internal object HistoryTextFixture {
    val at: Instant = Instant.parse("2026-10-09T00:00:00.123456789Z")
    val partition = HistoryPartition(UUID(0, 85))
    private val epoch = ClockEpoch(UUID(0, 86))

    fun measured(percent: String = "12.345678901234567890", weekly: Boolean = true, reset: Boolean = true): HistoryEntry {
        val primary = window(percent, 18000L, reset)
        val secondary = if (weekly) window("87.50", 604800L, reset) else Input.Missing
        val usage = UsageNormalizer.normalize(UsageInput(primary, secondary,
            allowed = Input.Value(false), limitReached = Input.Value(true)), at)
        val admission = HistoryAdmission(partition, ObservationId(1), HistoryClock(epoch, 1000), HistoryEvent.Observed(usage))
        return (WindowHistory.append(HistoryCursor(partition), admission) as HistoryReduction.Applied).entry
    }

    private fun window(percent: String, seconds: Long, reset: Boolean): Input<WindowInput> = Input.Value(WindowInput(
        Input.Value(seconds), Input.Value(BigDecimal(percent)),
        if (reset) Input.Value(at.epochSecond + 3600) else Input.Missing,
    ))

    fun correctionPage(): List<HistoryEntry> {
        val firstUsage = UsageNormalizer.normalize(UsageInput(window("12.5", 18000, true)), at)
        val first = append(HistoryCursor(partition), firstUsage, 1000)
        val secondUsage = UsageNormalizer.normalize(UsageInput(window("3.00", 18000, true)), at.plusSeconds(60))
        val second = append(first.cursor, secondUsage, 61000)
        return listOf(first.entry, second.entry)
    }

    private fun append(cursor: HistoryCursor, usage: UsageObservation, monotonic: Long): HistoryReduction.Applied =
        WindowHistory.append(cursor, HistoryAdmission(partition, cursor.nextId()!!,
            HistoryClock(epoch, monotonic), HistoryEvent.Observed(usage))) as HistoryReduction.Applied

    fun gap(): HistoryEntry = (WindowHistory.append(HistoryCursor(partition), HistoryAdmission(partition,
        ObservationId(1), HistoryClock(epoch, 1000), HistoryEvent.Gap(HistoryGap.BACKGROUND))) as HistoryReduction.Applied).entry

    fun plot(entries: List<HistoryEntry> = listOf(measured()), readiness: HistoryReadiness = HistoryReadiness.READY,
        query: HistoryGraphQuery = HistoryGraphQuery(), problem: HistoryRecorderProblem? = null,
        reason: HistoryUnavailable? = null, loss: Long = 0, more: Boolean = false,
        truncation: Set<HistoryTruncation> = emptySet()): HistoryPlotSnapshot {
        val page = HistoryReadSnapshot(query.storage(partition), entries, entries.lastOrNull()?.id, more, truncation,
            truncation.associateWith { HistoryEvictionCutoff(1) })
        return HistoryPlotInputs.project(HistoryGraphSnapshot(readiness, HistoryGeneration(), partition, query,
            page, problem, reason, loss, HistoryLiveMetadata(
                usage = HistoryEndpointMetadata(at, at.plusSeconds(1), 403,
                    io.github.leugenea.codexbarmobile.transport.ReadError.FORBIDDEN, stale = true), refreshing = true)))
    }

    fun second(entry: HistoryEntry): HistoryEntry = HistoryEntry(partition, ObservationId(2), entry.clock,
        entry.observedAt, entry.gap, entry.windows, entry.slots, entry.allowed, entry.limitReached)
}
