package io.github.leugenea.codexbarmobile.history

import java.time.DateTimeException
import java.time.Instant

/** Full-window geometry does not depend on nominal-reference/pace eligibility or point count.
 * A known single window spans reset minus duration..reset, including a lone observation.
 * Unknown/mixed bounds, overflowing starts and unscoped legacy out-of-window measurements
 * fall back to the existing points/reference range (a single instant maps to center).
 * An authoritative current key always fixes the domain; out-of-window facts keep typed
 * unavailable geometry and exact text, not a wider axis. Empty history still has no axis.
 * Window identity comes from the immutable query/page, both part of HistoryPlotCache's key.
 */
internal fun historyPlotTimeDomain(window: WindowIdentity?, observed: List<Instant>,
    references: List<EvenDistributionReference>, authoritative: Boolean = false): PlotTimeDomain? {
    if (observed.isEmpty()) return null
    val full = window?.let(::windowTimeDomain)
    if (full != null && (authoritative || observed.all { it >= full.first && it <= full.last })) return full
    return HistoryPlotCoordinates.timeDomain(observed + references.flatMap { listOf(it.start.at, it.end.at) })
}

private fun windowTimeDomain(window: WindowIdentity): PlotTimeDomain? = try {
    if (window.durationSeconds <= 0) null
    else PlotTimeDomain(window.resetAt.minusSeconds(window.durationSeconds), window.resetAt)
} catch (_: DateTimeException) {
    null
}
