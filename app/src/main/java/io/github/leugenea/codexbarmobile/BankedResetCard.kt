package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.usage.Completeness

/** Always-visible, read-only inventory. C1/B1 still own every count, state and expiry. */
@Composable
internal fun BankedResetCard(model: PresentedBankedResetSection, time: PhoneTimeConfiguration) {
    val count = DashboardPresentation.inventoryCount(model.entitlements)
    val title = if (count == null) stringResource(R.string.dashboard_banked_unknown)
        else stringResource(R.string.dashboard_banked_count, count)
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(time.locale, "MMMdHHmm")
    Card(Modifier.fillMaxWidth().testTag("banked-disclosure")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("banked-disclosure-title").semantics { heading() })
            BankedCardStatus(model)
            if (model.entitlements.items.isNotEmpty()) {
                BankedTableHeader()
                model.entitlements.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BankedTableRow(item, index, time, pattern)
                }
            }
        }
    }
}

@Composable
private fun BankedCardStatus(model: PresentedBankedResetSection) {
    Text(stringResource(DashboardPresentation.entitlementState(model.entitlements.state)),
        Modifier.testTag("banked-disclosure-status"))
    if (model.stale) Text(stringResource(R.string.dashboard_banked_stale))
    if (model.entitlements.completeness == Completeness.PARTIAL)
        Text(stringResource(R.string.dashboard_banked_partial))
}

@Composable
private fun BankedTableHeader() {
    Row(Modifier.fillMaxWidth().testTag("banked-disclosure-header").semantics { hideFromAccessibility() },
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dashboard_banked_expires), Modifier.weight(1f).semantics { hideFromAccessibility() },
            style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.dashboard_banked_remaining), Modifier.weight(1f).semantics { hideFromAccessibility() },
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
}

@Composable
private fun BankedTableRow(item: PresentedEntitlementItem, index: Int, time: PhoneTimeConfiguration, pattern: String) {
    val expiry = item.source.value?.expiresAt?.value?.takeIf { item.expiry.absolute != null }
        ?.let { TimePresentation.chartTime(it, time.zone, time.locale, pattern) }
        ?: stringResource(R.string.time_unknown_target)
    val remaining = item.expiry.remaining?.let { remainingTimeLabel(it) } ?: relativeLabel(item.expiry)
    val relative = relativeLabel(item.expiry)
    val status = if (item.state == EntitlementState.KNOWN) null
        else stringResource(DashboardPresentation.entitlementState(item.state))
    val description = stringResource(R.string.dashboard_banked_row, index + 1, expiry,
        DashboardPresentation.bankedRowDetails(relative, status))
    Row(Modifier.fillMaxWidth().testTag("banked-disclosure-item-$index").semantics(mergeDescendants = true) {
        contentDescription = description
    }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(expiry, Modifier.weight(1f).testTag("banked-disclosure-expiry-$index").semantics { hideFromAccessibility() })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(remaining, Modifier.fillMaxWidth().testTag("banked-disclosure-remaining-$index")
                .semantics { hideFromAccessibility() }, textAlign = TextAlign.End)
            if (status != null && status != remaining) Text(status,
                Modifier.fillMaxWidth().testTag("banked-disclosure-state-$index").semantics { hideFromAccessibility() },
                textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
        }
    }
}
