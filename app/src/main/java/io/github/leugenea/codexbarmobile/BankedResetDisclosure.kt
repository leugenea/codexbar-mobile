package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.history.HistoryDisplayContext

/** Every retained row survives; only C1/B1 determine count, state and expiry. No action entitlement. */
@Composable
internal fun BankedResetDisclosure(model: PresentedBankedResetSection, authority: HistoryDisplayContext?) {
    var expanded by remember(authority) { mutableStateOf(false) }
    val count = DashboardPresentation.inventoryCount(model.entitlements)
    val title = if (count == null) stringResource(R.string.dashboard_banked_unknown)
        else stringResource(R.string.dashboard_banked_count, count)
    val expansion = stringResource(if (expanded) R.string.dashboard_expanded else R.string.dashboard_collapsed)
    Column(Modifier.fillMaxWidth().testTag("banked-disclosure"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("banked-disclosure-toggle").semantics { stateDescription = expansion }) {
            Text(title, Modifier.weight(1f))
            Text(if (expanded) "−" else "+", Modifier.semantics { hideFromAccessibility() })
        }
        BankedDisclosureStatus(model)
        if (expanded) model.entitlements.items.forEachIndexed { index, item -> BankedDisclosureItem(item, index) }
    }
}

@Composable
private fun BankedDisclosureStatus(model: PresentedBankedResetSection) {
    Text(stringResource(DashboardPresentation.entitlementState(model.entitlements.state)), Modifier.testTag("banked-disclosure-status"))
    if (model.stale) Text(stringResource(R.string.dashboard_banked_stale))
    if (model.entitlements.completeness == io.github.leugenea.codexbarmobile.usage.Completeness.PARTIAL)
        Text(stringResource(R.string.dashboard_banked_partial))
}

@Composable
private fun BankedDisclosureItem(item: PresentedEntitlementItem, index: Int) {
    val status = stringResource(DashboardPresentation.entitlementState(item.state))
    Card(Modifier.fillMaxWidth().testTag("banked-disclosure-item-$index").semantics(mergeDescendants = true) { stateDescription = status }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.banked_item, index + 1), style = MaterialTheme.typography.titleMedium)
            Text(item.expiry.absolute?.let { absoluteTimeLabel(it) } ?: stringResource(R.string.time_unknown_target),
                Modifier.testTag("banked-disclosure-expiry-$index"))
            Text(relativeLabel(item.expiry), Modifier.testTag("banked-disclosure-remaining-$index"))
            if (item.state != EntitlementState.KNOWN) Text(status)
        }
    }
}
