package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import java.math.BigDecimal

/** Synthetic original admissions into the same owner's real SQLite, never plot/snapshot injection. */
internal object HistoryAcceptanceAdmissions {
    const val SPARSE_USAGE = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":20,"reset_at":1799996400}}}"""
    const val UNKNOWN_USAGE = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12.375},"secondary_window":{"limit_window_seconds":604800}}}"""

    fun sparse(fixture: HistoryNavigationFixture) {
        val seed = seed(fixture)
        append(seed, 2, 60, observation(seed, "40", 60, -3600))
        append(seed, 3, 90, HistoryEvent.Gap(HistoryGap.BACKGROUND))
        append(seed, 4, 120, observation(seed, "60", 120, -3600))
        append(seed, 5, 180, observation(seed, "10", 180, -3600))
        append(seed, 6, 240, observation(seed, "30", 240, -3600))
        append(seed, 7, 300, observation(seed, "50", 300, -7200))
    }

    fun deltas(fixture: HistoryNavigationFixture) {
        val seed = seed(fixture)
        append(seed, 2, 60, observation(seed, "80.33333333333333333333333333333333", 60, 3600))
        append(seed, 3, 120, observation(seed, "90", 120, 3600))
    }

    private data class Seed(val access: HistoryRuntimeAccess, val first: HistoryEntry)

    private fun seed(fixture: HistoryNavigationFixture): Seed {
        val current = fixture.owner
        val ready = current.session.snapshot() as SessionResult.Ready
        return Seed(requireNotNull(current.historyCapability(ready.envelope.generation)),
            current.historySnapshots.value.storage!!.entries.first())
    }

    private fun observation(seed: Seed, percent: String, seconds: Long, resetOffset: Long): HistoryEvent.Observed {
        val at = requireNotNull(seed.first.observedAt)
        val input = Input.Value(WindowInput(Input.Value(18000L), Input.Value(BigDecimal(percent)),
            Input.Value(at.epochSecond + resetOffset)))
        return HistoryEvent.Observed(UsageNormalizer.normalize(UsageInput(input), at.plusSeconds(seconds)))
    }

    private fun append(seed: Seed, ordinal: Long, seconds: Long, event: HistoryEvent) {
        val firstClock = seed.first.clock
        val clock = HistoryClock(firstClock.epoch, requireNotNull(firstClock.monotonicMillis) + seconds * 1000)
        val outcome = seed.access.append(HistoryAdmission(seed.access.partition, ObservationId(ordinal), clock, event))
        assertTrue("Synthetic assembled admission $ordinal: $outcome", outcome is HistoryAppendOutcome.Stored)
    }
}
