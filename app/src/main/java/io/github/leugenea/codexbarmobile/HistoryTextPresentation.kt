package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.Field
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Resource-ready exact facts, not a second quota/reference calculation. */
internal data class HistoryTextFact(val label: Int, val value: String? = null, val state: Enum<*>? = null)
internal data class HistoryTextWindow(
    val kind: Enum<*>, val facts: List<HistoryTextFact>, val reset: PresentedTime?,
)
internal data class HistoryTextDetail(val facts: List<HistoryTextFact>, val windows: List<HistoryTextWindow>)

internal object HistoryTextPresentation {
    // Bound each accessibility node, never truncate or round the original value.
    const val CHUNK_CHARACTERS = 120
    fun chunks(text: String): List<String> = text.chunked(CHUNK_CHARACTERS)

    fun detail(entry: HistoryGraphEntry, now: Instant?, zone: ZoneId, locale: Locale): HistoryTextDetail {
        val source = entry.source
        val facts = mutableListOf(
            HistoryTextFact(R.string.history_observation, source.id.ordinal.toString()),
            HistoryTextFact(R.string.history_observed, source.observedAt?.toString()),
            HistoryTextFact(R.string.history_gap, state = source.gap),
        )
        facts += field(R.string.history_allowed, source.allowed)
        facts += field(R.string.history_limit, source.limitReached)
        source.slots.forEach { slot ->
            facts += HistoryTextFact(R.string.history_slot, state = slot.slot)
            facts += HistoryTextFact(R.string.history_knowledge, state = slot.knowledge)
            slot.reason?.let { facts += HistoryTextFact(R.string.history_reason, state = it) }
            facts += HistoryTextFact(R.string.history_kind, state = slot.kind)
            facts += field(R.string.history_duration, slot.duration)
        }
        return HistoryTextDetail(facts, entry.windows.map { window(it, source.observedAt, now, zone, locale) })
    }

    private fun window(source: HistoryGraphWindow, observed: Instant?, now: Instant?, zone: ZoneId, locale: Locale): HistoryTextWindow {
        val window = source.source
        val facts = mutableListOf(HistoryTextFact(R.string.history_selection, state = window.selection))
        facts += field(R.string.history_measured, window.percent)
        facts += field(R.string.history_duration, window.duration)
        facts += HistoryTextFact(R.string.history_reset_provenance, state = window.reset.provenance)
        window.reset.facts?.let { reset ->
            facts += field(R.string.history_reset_exact, reset.absolute)
            facts += field(R.string.history_reset_seconds, reset.relativeSeconds)
            facts += field(R.string.history_reset_derived, reset.relativeDerived)
        }
        window.point?.let { point -> facts += pointFacts(point) }
        facts += comparisonFacts(source.comparison)
        val reset = window.reset.facts?.let { TimePresentation.presentReset(it, observed, now, zone, locale) }
        return HistoryTextWindow(window.kind, facts, reset)
    }

    private fun pointFacts(point: HistoryPoint): List<HistoryTextFact> = buildList {
        add(HistoryTextFact(R.string.history_point_time, point.observedAt.toString()))
        add(HistoryTextFact(R.string.history_segment, point.segment.id.first.ordinal.toString()))
        add(HistoryTextFact(R.string.history_nominal_confidence, state = point.nominalStart))
        add(HistoryTextFact(R.string.history_eligibility, state = point.baseline))
        add(HistoryTextFact(R.string.history_reset_cause, state = point.segment.resetCause))
        point.segment.breaks.forEach { add(HistoryTextFact(R.string.history_break, state = it)) }
    }

    private fun comparisonFacts(comparison: UsedComparison): List<HistoryTextFact> = when (comparison) {
        is UsedComparison.Available -> listOf(
            HistoryTextFact(R.string.history_baseline_value, comparison.baselinePercent.toString()),
            HistoryTextFact(R.string.history_delta, comparison.deltaPercentagePoints.toString()),
            HistoryTextFact(R.string.history_nominal_start, comparison.reference.nominalStartAt.toString()),
            HistoryTextFact(R.string.history_nominal_end, comparison.reference.window.resetAt.toString()),
        )
        is UsedComparison.Unavailable -> listOf(HistoryTextFact(R.string.history_baseline_unavailable,
            state = unavailableState(comparison.reason)))
    }

    private fun unavailableState(reason: DistributionUnavailable): Enum<*> = when (reason) {
        is DistributionUnavailable.Ineligible -> reason.eligibility
        is DistributionUnavailable.InvalidFact -> reason
    }

    private fun field(label: Int, field: Field<*>?): List<HistoryTextFact> = buildList {
        add(HistoryTextFact(label, field?.value?.toString()))
        field?.let { add(HistoryTextFact(R.string.history_knowledge, state = it.knowledge)) }
        field?.reason?.let { add(HistoryTextFact(R.string.history_reason, state = it)) }
    }
}
