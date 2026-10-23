package io.github.leugenea.codexbarmobile.history

/** One UI-local retained page, not display authority. Call only after the host's fresh checks. */
internal class HistoryPlotCache {
    private var retained: HistoryPlotSnapshot? = null

    fun project(source: HistoryGraphSnapshot): HistoryPlotSnapshot {
        val previous = retained
        val plot = if (previous != null && sameInputs(previous.source, source)) previous
            else HistoryPlotInputs.project(source).also { retained = it }
        // Only immutable geometry is reused; diagnostics, clocks and latest-entry pace stay live.
        return HistoryPlotSnapshot(source, plot.fiveHour, plot.weekly)
    }

    private fun sameInputs(before: HistoryGraphSnapshot, after: HistoryGraphSnapshot): Boolean =
        before.storage === after.storage && before.generation === after.generation &&
            before.partition == after.partition && before.query == after.query &&
            before.request == after.request && before.selection === after.selection
}
