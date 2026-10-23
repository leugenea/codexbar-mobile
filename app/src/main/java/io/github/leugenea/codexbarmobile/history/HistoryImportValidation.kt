package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.WindowKind

/** Retention may leave ordinal holes, but only at/below a recorded eviction cutoff. */
internal object HistoryImportValidation {
    fun validate(document: HistoryExportDocument) {
        val high = document.state.cursor.lastOrdinal
        val cutoff = document.state.cutoffs.values.maxOfOrNull { it.ordinal } ?: 0L
        var previous = 0L
        document.entries.forEach { entry ->
            require(entry.id.ordinal - previous == 1L || entry.id.ordinal - 1 <= cutoff)
            previous = entry.id.ordinal
        }
        require(previous == high || high <= cutoff)
        val indexed = document.entries.associateBy { it.id }
        val segments = HashMap<SegmentIdentity, HistorySegment>()
        document.entries.flatMap { it.windows }.mapNotNull { it.point }.forEach { point ->
            validateSegment(point, indexed, segments)
        }
        validateTail(document.state.cursor.fiveHour, WindowKind.FIVE_HOUR, document, indexed, segments)
        validateTail(document.state.cursor.weekly, WindowKind.WEEKLY, document, indexed, segments)
        document.entries.lastOrNull()?.takeIf { it.id.ordinal == high }?.let { last ->
            document.state.lastClock?.let { require(it == last.clock) }
        }
    }

    private fun validateTail(tail: WindowCursor, kind: WindowKind, document: HistoryExportDocument,
        indexed: Map<ObservationId, HistoryEntry>, segments: MutableMap<SegmentIdentity, HistorySegment>) {
        validateTransitions(document, kind, tail)
        val point = tail.lastPoint
        val latest = document.entries.asReversed().firstNotNullOfOrNull { entry -> entry.windows.singleOrNull { it.kind == kind }?.point?.takeIf { it.segment.window != null } }
        require((point?.observation?.ordinal ?: 0) >= (latest?.observation?.ordinal ?: 0))
        if (point == null) return
        require(point.segment.window != null && tail.nominalStart == point.nominalStart)
        indexed[point.observation]?.let { entry ->
            val stored = entry.windows.singleOrNull { it.kind == kind }?.point
            require(stored != null && HistoryJsonWriter().point(stored) == HistoryJsonWriter().point(point))
        }
        validateSegment(point, indexed, segments)
    }

    private fun validateTransitions(document: HistoryExportDocument, kind: WindowKind, tail: WindowCursor) {
        var previous = 0L
        var pending: BreakReason? = null
        var known: WindowCursor? = WindowCursor()
        document.entries.forEach { entry ->
            if (entry.id.ordinal - previous != 1L) { pending = null; known = null }
            val point = entry.windows.singleOrNull { it.kind == kind }?.point
            val required = pending
            if (point != null && required != null) {
                require(point.segment.id.first == entry.id && required in point.segment.breaks)
            }
            known = advanceKnown(known, entry, kind)
            pending = interruption(entry, kind)
            previous = entry.id.ordinal
        }
        if (document.entries.isNotEmpty() && previous == document.state.cursor.lastOrdinal) require(tail.pendingBreak == pending)
    }

    private fun advanceKnown(cursor: WindowCursor?, entry: HistoryEntry, kind: WindowKind): WindowCursor? {
        val pending = interruption(entry, kind)
        val point = entry.windows.singleOrNull { it.kind == kind }?.point
            ?: return cursor?.copy(pendingBreak = pending)
        cursor?.let { validatePointTransition(it, point) }
        val candidate = point.segment.window ?: return cursor?.copy(pendingBreak = pending)
        return WindowCursor(point, candidate, point.nominalStart)
    }

    /** Reuse the reducer's exact correction/window/clock rules; do not invent a second policy. */
    private fun validatePointTransition(cursor: WindowCursor, point: HistoryPoint) {
        val candidate = point.segment.window
        val breaks = WindowHistory.breaks(cursor, point.clock, point.observedAt, point.usedPercent, candidate)
        val confidence = WindowHistory.confidence(cursor, candidate, breaks)
        if (breaks.isEmpty()) require(point.segment.id == cursor.lastPoint?.segment?.id)
        else require(point.segment.id.first == point.observation && point.segment.breaks == breaks)
        require(point.nominalStart == confidence)
        require(point.baseline == WindowHistory.eligibility(point.observedAt, point.clock, candidate, confidence, breaks))
    }

    private fun interruption(entry: HistoryEntry, kind: WindowKind): BreakReason? {
        entry.gap?.let { return if (it == HistoryGap.BACKGROUND) BreakReason.BACKGROUND else BreakReason.READ_ERROR }
        val window = entry.windows.single { it.kind == kind }
        window.point?.let { return if (it.segment.window == null) BreakReason.UNKEYED_RESET else null }
        return when (window.baseline) {
            BaselineEligibility.AMBIGUOUS_WINDOW -> BreakReason.AMBIGUOUS_WINDOW
            BaselineEligibility.UNKNOWN_OBSERVATION_TIME -> BreakReason.UNKNOWN_OBSERVATION_TIME
            else -> BreakReason.FIELD_GAP
        }
    }

    private fun validateSegment(point: HistoryPoint, indexed: Map<ObservationId, HistoryEntry>,
        segments: MutableMap<SegmentIdentity, HistorySegment>) {
        val segment = point.segment
        val writer = HistoryJsonWriter()
        val previous = segments.put(segment.id, segment)
        require(previous == null || writer.segment(previous) == writer.segment(segment))
        indexed[segment.id.first]?.let { origin ->
            val first = origin.windows.singleOrNull { it.kind == segment.id.kind }?.point
            require(first != null && writer.segment(first.segment) == writer.segment(segment))
        }
    }
}
