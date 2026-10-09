package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.WindowKind

/** Pure, page-local presentation adapter. No sampling, query, clock, storage or quota calculations. */
internal object HistoryPlotInputs {
    fun project(source: HistoryGraphSnapshot): HistoryPlotSnapshot {
        // Reject an oversized handcrafted contract instead of silently truncating its exact facts.
        require(source.entries.size <= source.query.limit)
        require(source.entries.all { entry -> entry.windows.map { it.source.kind }.distinct().size == entry.windows.size })
        return HistoryPlotSnapshot(source, series(source, WindowKind.FIVE_HOUR), series(source, WindowKind.WEEKLY))
    }

    private fun series(source: HistoryGraphSnapshot, kind: WindowKind): HistoryPlotSeries {
        val selected = source.entries.flatMap { entry ->
            entry.windows.filter { it.source.kind == kind }.map { entry to it }
        }
        val references = selected.mapNotNull { (_, window) ->
            (window.reference as? DistributionReference.Available)?.series
        }.distinctBy { it.window to it.segment }
        val instants = selected.mapNotNull { it.second.source.point?.observedAt } +
            references.flatMap { listOf(it.start.at, it.end.at) }
        val time = HistoryPlotCoordinates.timeDomain(instants)
        val measuredDomain = time?.let { HistoryPlotDomain(it, HistoryPlotCoordinates.percent) }
        val deltaDomain = time?.let { HistoryPlotDomain(it, HistoryPlotCoordinates.delta) }
        val samples = selected.map { (entry, window) -> sample(entry, window, measuredDomain, deltaDomain) }
        val segments = runs(samples) { it.measured?.position is PlotPosition.Available }.map { run ->
            HistoryPlotSegment(run.first().measured!!.source.segment, run.map { it.measured!! })
        }
        val deltas = runs(samples) { it.delta?.position is PlotPosition.Available }.map { run ->
            HistoryPlotDeltaSegment(run.first().measured!!.source.segment, run.map { it.delta!! })
        }
        val analytical = references.map { reference -> HistoryPlotReference(reference,
            HistoryPlotCoordinates.position(measuredDomain!!, reference.start.at, reference.start.percent),
            HistoryPlotCoordinates.position(measuredDomain, reference.end.at, reference.end.percent)) }
        return HistoryPlotSeries(kind, content(source, samples), measuredDomain, deltaDomain, samples, segments, deltas, analytical)
    }

    private fun sample(
        entry: HistoryGraphEntry, window: HistoryGraphWindow,
        measuredDomain: HistoryPlotDomain?, deltaDomain: HistoryPlotDomain?,
    ): HistoryPlotSample {
        val measured = window.source.point?.let { point ->
            HistoryPlotPoint(point, HistoryPlotCoordinates.position(measuredDomain!!, point.observedAt, point.usedPercent))
        }
        val delta = (window.comparison as? UsedComparison.Available)?.let { comparison ->
            HistoryPlotDelta(comparison, HistoryPlotCoordinates.position(deltaDomain!!,
                comparison.point.observedAt, comparison.deltaPercentagePoints))
        }
        return HistoryPlotSample(entry, window, measured, delta)
    }

    private fun content(source: HistoryGraphSnapshot, samples: List<HistoryPlotSample>): HistoryPlotContent = when {
        source.storage == null -> HistoryPlotContent.NO_PAGE
        source.entries.isEmpty() -> HistoryPlotContent.EMPTY_PAGE
        source.entries.all { it.source.gap != null } -> HistoryPlotContent.STATUS_ONLY
        samples.isEmpty() -> HistoryPlotContent.FILTERED_EMPTY
        samples.none { it.measured != null } -> HistoryPlotContent.STATUS_ONLY
        samples.count { it.measured != null } == 1 -> HistoryPlotContent.SINGLE_POINT
        else -> HistoryPlotContent.MULTIPLE_POINTS
    }

    /** No shared run across pages; unavailable geometry is never removed to bridge its neighbors. */
    private fun runs(
        samples: List<HistoryPlotSample>, renderable: (HistoryPlotSample) -> Boolean,
    ): List<List<HistoryPlotSample>> {
        val runs = mutableListOf<MutableList<HistoryPlotSample>>()
        var previous: HistoryPlotSample? = null
        for (sample in samples) {
            if (!renderable(sample)) {
                previous = null
                continue
            }
            if (previous == null || !continuous(previous, sample)) runs.add(mutableListOf())
            runs.last().add(sample)
            previous = sample
        }
        return runs
    }

    private fun continuous(before: HistoryPlotSample, after: HistoryPlotSample): Boolean {
        val one = before.measured!!.source
        val two = after.measured!!.source
        if (one.segment.id != two.segment.id || one.segment.window != two.segment.window) return false
        if (one.segment.window == null) return false // Unkeyed points are always isolated.
        if (two.observedAt <= one.observedAt) return false
        // Ordinal holes, filtered entries, field statuses and explicit gaps cannot be bridged.
        return after.entry.source.id.ordinal - before.entry.source.id.ordinal == 1L
    }
}
