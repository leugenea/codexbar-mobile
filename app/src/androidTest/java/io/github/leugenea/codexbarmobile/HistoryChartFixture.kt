package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Synthetic admitted history, reduced/projected by production code, not drawn substitute data. */
internal object HistoryChartFixture {
    private val epoch = ClockEpoch(UUID(0, 860))
    val at: Instant = HistoryTextFixture.at

    // Deliberately after a fixed reset: real measurements remain drawable without a nominal
    // reference expanding this short time domain. This is not an inferred new quota window.
    fun page(gap: Boolean = false, correction: Boolean = false, changedReset: Boolean = false,
        endpoints: Boolean = false): List<HistoryEntry> {
        var cursor = HistoryCursor(HistoryTextFixture.partition)
        val entries = mutableListOf<HistoryEntry>()
        for (index in 0..3) {
            if (index == 2 && gap) {
                val result = reduce(cursor, HistoryEvent.Gap(HistoryGap.BACKGROUND), index * 60_000L)
                entries += result.entry
                cursor = result.cursor
            }
            val percent = when {
                endpoints -> listOf(0, 33, 66, 100)[index]
                correction && index >= 2 -> 10 + (index - 2) * 20
                else -> 20 + index * 20
            }
            val reset = at.epochSecond - if (changedReset && index >= 2) 7200 else 3600
            val input = Input.Value(WindowInput(Input.Value(18000L), Input.Value(BigDecimal(percent)), Input.Value(reset)))
            val usage = UsageNormalizer.normalize(UsageInput(input), at.plusSeconds(index * 60L))
            val result = reduce(cursor, HistoryEvent.Observed(usage), index * 60_000L + 1000)
            entries += result.entry
            cursor = result.cursor
        }
        return entries
    }

    private fun reduce(cursor: HistoryCursor, event: HistoryEvent, millis: Long): HistoryReduction.Applied =
        WindowHistory.append(cursor, HistoryAdmission(cursor.partition, cursor.nextId()!!,
            HistoryClock(epoch, millis), event)) as HistoryReduction.Applied

    /** Explicit malformed handcrafted geometry contract; not a normalized provider measurement. */
    fun invalidGeometry(value: BigDecimal): HistoryPlotSnapshot {
        val original = HistoryTextFixture.measured(weekly = false, reset = false)
        val window = original.windows.first { it.kind == WindowKind.FIVE_HOUR }
        val changed = window.copy(percent = Field(Knowledge.KNOWN, value), point = window.point!!.copy(usedPercent = value))
        return HistoryTextFixture.plot(listOf(HistoryEntry(original.partition, original.id, original.clock,
            original.observedAt, original.gap, listOf(changed), original.slots, original.allowed, original.limitReached)))
    }
}
