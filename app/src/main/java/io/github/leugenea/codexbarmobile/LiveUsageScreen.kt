package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Lives in the connection screen's one scroll container; facts come from the process owner. */
@Composable
internal fun LiveUsageScreen(state: ConnectionState) {
    val time = phoneTimeConfiguration()
    val model = UsagePresentation.present(state, time.zone, time.locale)
    Column(Modifier.fillMaxWidth().testTag("live-usage"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.live_title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.live_identity), Modifier.testTag("live-identity"))
                Text(stringResource(model.status.labelResource), Modifier.testTag("live-status")
                    .semantics { liveRegion = LiveRegionMode.Polite })
                if (model.refreshing) Text(stringResource(R.string.live_refreshing), Modifier.testTag("live-refreshing")
                    .semantics { liveRegion = LiveRegionMode.Polite })
                if (model.stale) Text(stringResource(R.string.live_stale_detail), Modifier.testTag("live-stale")
                    .semantics { liveRegion = LiveRegionMode.Polite })
                model.errorResource?.let { Text(stringResource(it), Modifier.testTag("live-error")
                    .semantics { liveRegion = LiveRegionMode.Polite }) }
                Text(stringResource(R.string.live_provider_allowed, stringResource(model.allowedResource)),
                    Modifier.testTag("live-allowed").semantics { liveRegion = LiveRegionMode.Polite })
                Text(stringResource(R.string.live_provider_limit, stringResource(model.limitResource)),
                    Modifier.testTag("live-limit").semantics { liveRegion = LiveRegionMode.Polite })
                model.windows.forEach { UsageWindowSection(it) }
                Text(stringResource(R.string.live_time_precision), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("live-rounding"))
            }
        }
        BankedResetSection(BankedResetPresentation.present(state, time.zone, time.locale))
    }
}

@Composable
private fun UsageWindowSection(window: PresentedUsageWindow) {
    val label = stringResource(window.labelResource)
    val status = stringResource(window.status.labelResource)
    Column(Modifier.fillMaxWidth().testTag(window.tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(status, Modifier.testTag("${window.tag}-status").semantics { liveRegion = LiveRegionMode.Polite })
        if (window.percent != null && window.progress != null) {
            val percent = stringResource(R.string.live_percent, window.percent)
            val description = stringResource(R.string.live_progress_description, label, percent)
            Text(percent, Modifier.testTag("${window.tag}-percent")
                .semantics { hideFromAccessibility() })
            LinearProgressIndicator(progress = { window.progress }, modifier = Modifier.fillMaxWidth()
                .testTag("${window.tag}-progress").clearAndSetSemantics {
                    contentDescription = description
                    stateDescription = status
                    progressBarRangeInfo = ProgressBarRangeInfo(window.progress, 0f..1f)
                })
        }
        if (window.malformed) Text(stringResource(R.string.live_malformed_detail),
            Modifier.testTag("${window.tag}-warning"))
        ResetLabels(window.reset, window.tag)
    }
}

@Composable
private fun ResetLabels(time: PresentedTime?, tag: String) {
    if (time == null) {
        Text(stringResource(R.string.live_reset_unavailable), Modifier.testTag("$tag-reset-relative"))
        return
    }
    time.absolute?.let { absolute ->
        val text = absoluteTimeLabel(absolute)
        Text(stringResource(R.string.live_reset_absolute, text), Modifier.testTag("$tag-reset-absolute"))
    }
    val relative = relativeLabel(time)
    Text(stringResource(R.string.live_reset_relative, relative), Modifier.testTag("$tag-reset-relative")
        .semantics {
            if (time.state == TimeState.AWAITING_REFRESH) liveRegion = LiveRegionMode.Polite
        })
    Text(stringResource(time.snapshot.labelResource), Modifier.testTag("$tag-snapshot"),
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun relativeLabel(time: PresentedTime): String {
    val remaining = time.remaining ?: return stringResource(time.state.labelResource)
    return stringResource(time.state.labelResource, remainingTimeLabel(remaining))
}

@Composable
internal fun remainingTimeLabel(remaining: RemainingTime): String {
    // Quantities select grammar only; original Long values remain format arguments.
    val units = remaining.units.map { (resource, value) ->
        pluralStringResource(resource, value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), value)
    }
    return if (units.isEmpty()) stringResource(R.string.time_less_than_minute) else units.joinToString(" ")
}
