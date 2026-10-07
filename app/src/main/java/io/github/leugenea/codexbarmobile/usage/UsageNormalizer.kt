package io.github.leugenea.codexbarmobile.usage

import java.time.Instant

object UsageNormalizer {
    fun normalize(input: UsageInput, observedAt: Instant? = null, evaluatedAt: Instant? = null): UsageObservation {
        val slots = listOf(
            slot(Slot.PRIMARY, input.primary, observedAt, evaluatedAt),
            slot(Slot.SECONDARY, input.secondary, observedAt, evaluatedAt),
        )
        return UsageObservation(
            observedAt, select(slots, 18000), select(slots, 604800), slots,
            PrimitiveNormalizer.boolean(input.allowed), PrimitiveNormalizer.boolean(input.limitReached),
            PrimitiveNormalizer.text(input.planType), PrimitiveNormalizer.integer(input.bankedAvailableCount),
        )
    }

    private fun slot(
        slot: Slot, input: Input<WindowInput>, observedAt: Instant?, evaluatedAt: Instant?,
    ): SlotResult = SlotResult(slot, PrimitiveNormalizer.read(input) {
        val duration = PrimitiveNormalizer.integer(it.durationSeconds, 1)
        PrimitiveNormalizer.known(UsageWindow(
            slot, kind(duration), duration, PrimitiveNormalizer.percent(it.usedPercent),
            reset(it, observedAt, evaluatedAt),
        ))
    })

    private fun kind(duration: Field<Long>): WindowKind = when (duration.value) {
        18000L -> WindowKind.FIVE_HOUR
        604800L -> WindowKind.WEEKLY
        null -> WindowKind.UNKNOWN
        else -> WindowKind.UNSUPPORTED
    }

    private fun select(slots: List<SlotResult>, seconds: Long): WindowSelection {
        val candidates = slots.mapNotNull { it.window.value }.filter { it.durationSeconds.value == seconds }
        val state = when {
            candidates.size > 1 -> SelectionState.AMBIGUOUS
            candidates.isEmpty() -> SelectionState.UNAVAILABLE
            malformed(candidates.single()) -> SelectionState.MALFORMED
            else -> SelectionState.KNOWN
        }
        return WindowSelection(state, candidates)
    }

    private fun malformed(window: UsageWindow): Boolean = listOf(
        window.usedPercent.knowledge, window.reset.absolute.knowledge,
        window.reset.relativeSeconds.knowledge, window.reset.relativeDerived.knowledge,
    ).any { it == Knowledge.MALFORMED }

    private fun reset(input: WindowInput, observedAt: Instant?, evaluatedAt: Instant?): ResetTime {
        val absolute = PrimitiveNormalizer.epoch(input.resetAt)
        val relative = PrimitiveNormalizer.integer(input.resetAfterSeconds)
        val derived = derive(relative, observedAt)
        val conflict = absolute.value != null && derived.value != null && absolute.value != derived.value
        // Conflict has no single trustworthy instant to evaluate; retain both originals.
        val effective = if (conflict) null else absolute.value ?: derived.value
        val due = effective?.let { instant -> evaluatedAt?.let { instant <= it } }
        return ResetTime(absolute, relative, derived, conflict, due)
    }

    private fun derive(relative: Field<Long>, observedAt: Instant?): Field<Instant> {
        val seconds = relative.value ?: return Field(relative.knowledge, reason = relative.reason)
        if (observedAt == null) return Field(Knowledge.UNAVAILABLE, reason = Reason.CLOCK_REQUIRED)
        return PrimitiveNormalizer.instant { observedAt.plusSeconds(seconds) }
    }
}
