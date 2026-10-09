package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.history.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Textual graph equivalent only. Host supplies scrolling and an explicit evaluation clock.
 * One detail entry is visible at a time; controls expose every admitted page entry, including
 * filtered entries and timestamp-less gaps. No merged giant announcement or live countdown.
 * This is deliberately not wired into a production screen until the separate integration issue.
 */
@Composable
internal fun HistoryTextComponent(
    plot: HistoryPlotSnapshot, now: Instant?, zone: ZoneId, locale: Locale, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("history-text"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HistoryHeading(R.string.history_title, "history-title")
        Text(stringResource(historyStateResource(plot.source.readiness)), Modifier.testTag("history-readiness"))
        Text(stringResource(R.string.history_legend), Modifier.testTag("history-legend"))
        Text(stringResource(R.string.history_assumption), Modifier.testTag("history-assumption"))
        Text(stringResource(R.string.history_sparse), Modifier.testTag("history-sparse"))
        HistorySeriesSummary(plot.fiveHour, "history-five-hour")
        HistorySeriesSummary(plot.weekly, "history-weekly")
        HistoryPageStatus(plot)
        HistoryDetails(plot, now, zone, locale)
    }
}

@Composable
private fun HistorySeriesSummary(series: HistoryPlotSeries, tag: String) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryHeading(historyStateResource(series.kind), "$tag-title")
            Text(stringResource(historyStateResource(series.content)), Modifier.testTag("$tag-content"))
            Text(stringResource(R.string.history_series_counts, series.samples.count { it.measured != null },
                series.references.size), Modifier.testTag("$tag-counts"))
        }
    }
}

@Composable
private fun HistoryPageStatus(plot: HistoryPlotSnapshot) {
    val source = plot.source
    source.problem?.let { HistoryFact(HistoryTextFact(R.string.history_problem, state = it)) }
    source.storageReason?.let { HistoryFact(HistoryTextFact(R.string.history_storage, state = it)) }
    if (source.lostSamples > 0) Text(stringResource(R.string.history_lost, source.lostSamples), Modifier.testTag("history-loss"))
    source.truncation.forEach { reason ->
        HistoryFact(HistoryTextFact(R.string.history_truncated, state = reason))
        HistoryFact(HistoryTextFact(R.string.history_cutoff, source.storage?.cutoffs?.get(reason)?.ordinal?.toString()))
    }
    if (source.hasMore) Text(stringResource(R.string.history_more), Modifier.testTag("history-more"))
    if (source.query.kind != null || source.query.window != null) Text(stringResource(R.string.history_filter))
    HistoryEndpoint(source.live.usage, R.string.history_usage_endpoint)
    HistoryEndpoint(source.live.inventory, R.string.history_inventory_endpoint)
    if (source.live.refreshing) Text(stringResource(R.string.history_refreshing))
}

@Composable
private fun HistoryEndpoint(source: HistoryEndpointMetadata, label: Int) {
    HistoryHeading(label, "history-endpoint-$label")
    HistoryFact(HistoryTextFact(R.string.history_endpoint_observed, source.sourceObservedAt?.toString()))
    HistoryFact(HistoryTextFact(R.string.history_endpoint_attempt, source.latestAttemptObservedAt?.toString()))
    source.status?.let { HistoryFact(HistoryTextFact(R.string.history_http_status, it.toString())) }
    source.error?.let { HistoryFact(HistoryTextFact(R.string.history_endpoint_error, state = it)) }
    if (source.stale) Text(stringResource(R.string.history_stale))
}

@Composable
private fun HistoryDetails(plot: HistoryPlotSnapshot, now: Instant?, zone: ZoneId, locale: Locale) {
    val entries = plot.source.entries
    if (entries.isEmpty()) return
    // A new detached snapshot resets selection; never carry an ordinal into another generation/page.
    var expanded by remember(plot) { mutableStateOf(false) }
    var index by remember(plot) { mutableIntStateOf(0) }
    Button(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("history-details-toggle")) {
        Text(stringResource(if (expanded) R.string.history_hide_details else R.string.history_show_details, entries.size))
    }
    if (!expanded) return
    Text(stringResource(R.string.history_entry_position, index + 1, entries.size), Modifier.testTag("history-detail-position"))
    Button(onClick = { index-- }, enabled = index > 0, modifier = Modifier.fillMaxWidth().testTag("history-previous")) {
        Text(stringResource(R.string.history_previous))
    }
    Button(onClick = { index++ }, enabled = index < entries.lastIndex, modifier = Modifier.fillMaxWidth().testTag("history-next")) {
        Text(stringResource(R.string.history_next))
    }
    val detail = HistoryTextPresentation.detail(entries[index], now, zone, locale)
    Column(Modifier.fillMaxWidth().testTag("history-detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.facts.forEach { HistoryFact(it) }
        detail.windows.forEach { window -> HistoryWindowDetail(window) }
    }
}

@Composable
private fun HistoryWindowDetail(window: HistoryTextWindow) {
    HistoryHeading(historyStateResource(window.kind), "history-detail-kind-${window.kind.name}")
    window.facts.forEach { HistoryFact(it) }
    val time = window.reset
    if (time == null) {
        Text(stringResource(R.string.live_reset_unavailable))
        return
    }
    time.absolute?.let { Text(stringResource(R.string.live_reset_absolute, absoluteTimeLabel(it))) }
    Text(stringResource(R.string.live_reset_relative, relativeLabel(time)))
    Text(stringResource(time.snapshot.labelResource))
}

@Composable
private fun HistoryFact(fact: HistoryTextFact) {
    val value = fact.state?.let { stringResource(historyStateResource(it)) }
        ?: fact.value ?: stringResource(R.string.history_unknown)
    val chunks = HistoryTextPresentation.chunks(value)
    if (chunks.size == 1) {
        Text(stringResource(R.string.history_fact, stringResource(fact.label), chunks.single()))
    } else {
        Text(stringResource(fact.label))
        Text(stringResource(R.string.history_chunks, chunks.size))
        chunks.forEach { Text(it) }
    }
}

@Composable
private fun HistoryHeading(resource: Int, tag: String) {
    Text(stringResource(resource), Modifier.testTag(tag).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}
