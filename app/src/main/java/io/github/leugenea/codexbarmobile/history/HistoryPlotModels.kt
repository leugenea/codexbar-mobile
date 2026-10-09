package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.time.Instant

/** Page/selection content is orthogonal to readiness, baseline availability and recorder errors. */
internal enum class HistoryPlotContent { NO_PAGE, EMPTY_PAGE, FILTERED_EMPTY, STATUS_ONLY, SINGLE_POINT, MULTIPLE_POINTS }
internal enum class PlotTimeExtent { SINGLE_INSTANT, SPAN }
internal enum class PlotRenderProblem { DECIMAL_CAPACITY, OUTSIDE_VALUE_DOMAIN }
internal enum class PlotRenderDetail { APPROXIMATE, NONZERO_UNDERFLOW }
internal enum class PlotReferenceMeaning { NOMINAL_EVEN_DISTRIBUTION }

/** Fractions increase with time/value; the renderer chooses pixel size and vertical orientation. */
internal sealed interface PlotPosition {
    data class Available(val x: Double, val y: Double, val detail: PlotRenderDetail) : PlotPosition
    data class Unavailable(val reason: PlotRenderProblem) : PlotPosition
}

/** Exact endpoints. No epoch-millisecond/nanosecond scalar conversion or inferred clock trust. */
internal data class PlotTimeDomain(val first: Instant, val last: Instant) {
    init { require(first <= last) }
    val extent = if (first == last) PlotTimeExtent.SINGLE_INSTANT else PlotTimeExtent.SPAN
}
internal data class PlotValueDomain(val minimum: Int, val maximum: Int, val unit: HistoryUnit) {
    init { require(minimum < maximum) }
}
internal data class HistoryPlotDomain(val time: PlotTimeDomain, val value: PlotValueDomain)

/** Original measurement survives even when geometry is unavailable or two fractions coincide. */
internal class HistoryPlotPoint(val source: HistoryPoint, val position: PlotPosition) {
    // Exponent notation is exact and cannot expand an extreme scale into billions of zeros.
    val exactPercentText: String get() = source.usedPercent.toString()
    val exactObservedAtText: String get() = source.observedAt.toString()
}
internal class HistoryPlotDelta(val source: UsedComparison.Available, val position: PlotPosition) {
    val unit = HistoryUnit.PERCENTAGE_POINTS
    val observedAt: Instant get() = source.point.observedAt
    val exactText: String get() = source.deltaPercentagePoints.toString()
}

/** Includes typed unavailable reference/comparison and all field knowledge, not just renderable dots. */
internal class HistoryPlotSample(
    val entry: HistoryGraphEntry,
    val window: HistoryGraphWindow,
    val measured: HistoryPlotPoint?,
    val delta: HistoryPlotDelta?,
)

/** A page-local measured run, never a continuation instruction for another page or snapshot. */
internal class HistoryPlotSegment(val source: HistorySegment, points: Collection<HistoryPlotPoint>) {
    val points: List<HistoryPlotPoint> = immutableList(points)
}
internal class HistoryPlotDeltaSegment(val source: HistorySegment, points: Collection<HistoryPlotDelta>) {
    val points: List<HistoryPlotDelta> = immutableList(points)
}

/** Analytical endpoints only; these MUST NOT be appended to measured runs or treated as forecasts. */
internal class HistoryPlotReference(
    val source: EvenDistributionReference,
    val start: PlotPosition,
    val end: PlotPosition,
) {
    val meaning = PlotReferenceMeaning.NOMINAL_EVEN_DISTRIBUTION
    val unit = HistoryUnit.PERCENT
}

internal class HistoryPlotSeries(
    val kind: WindowKind,
    val content: HistoryPlotContent,
    val measuredDomain: HistoryPlotDomain?,
    val deltaDomain: HistoryPlotDomain?,
    samples: Collection<HistoryPlotSample>,
    segments: Collection<HistoryPlotSegment>,
    deltaSegments: Collection<HistoryPlotDeltaSegment>,
    references: Collection<HistoryPlotReference>,
) {
    val samples: List<HistoryPlotSample> = immutableList(samples)
    val segments: List<HistoryPlotSegment> = immutableList(segments)
    val deltaSegments: List<HistoryPlotDeltaSegment> = immutableList(deltaSegments)
    val references: List<HistoryPlotReference> = immutableList(references)
}

/** Retains the exact detached page, readiness, identity, query, gaps, loss, paging and live metadata. */
internal class HistoryPlotSnapshot(
    val source: HistoryGraphSnapshot,
    val fiveHour: HistoryPlotSeries,
    val weekly: HistoryPlotSeries,
)
