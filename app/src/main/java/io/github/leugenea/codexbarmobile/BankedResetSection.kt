package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.Knowledge

/** Read-only cards in the existing live scroll tree. There are intentionally no action callbacks. */
@Composable
internal fun BankedResetSection(model: PresentedBankedResetSection) {
    val facts = model.entitlements
    Column(Modifier.fillMaxWidth().testTag("banked-section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.banked_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("banked-title").semantics { heading() })
        BankedStatus(stringResource(facts.state.labelResource), "banked-status")
        if (model.refreshing) BankedStatus(stringResource(R.string.banked_refreshing), "banked-refreshing")
        if (model.stale) BankedStatus(stringResource(R.string.banked_stale), "banked-stale")
        model.errorResource?.let { BankedStatus(stringResource(it), "banked-error") }
        BankedCount(facts.summaryAvailableCount, R.string.banked_summary, "banked-summary")
        BankedCount(facts.reportedAvailableCount, R.string.banked_inventory, "banked-inventory")
        Text(stringResource(R.string.banked_summary_clock, facts.summaryObservedAt?.toString()
            ?: stringResource(R.string.banked_field_unknown)), Modifier.testTag("banked-summary-clock"))
        Text(stringResource(R.string.banked_inventory_clock, facts.inventoryObservedAt?.toString()
            ?: stringResource(R.string.banked_field_unknown)), Modifier.testTag("banked-inventory-clock"))
        val container = facts.inventoryRowContainer
        val containerLabel = if (container?.knowledge == Knowledge.KNOWN && container.value != null) {
            pluralStringResource(R.plurals.banked_rows, container.value, container.value)
        } else fieldLabel(BankedResetPresentation.fieldLabel(container))
        Text(stringResource(R.string.banked_container, containerLabel), Modifier.testTag("banked-container"))
        facts.issues.forEach { BankedStatus(stringResource(BankedResetPresentation.issueResource(it)), "banked-issue-${it.name}") }
        facts.items.forEachIndexed { index, item -> BankedItem(item, index) }
        Text(stringResource(R.string.banked_limitations), Modifier.testTag("banked-limitations"),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BankedStatus(text: String, tag: String) {
    Text(text, Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun BankedCount(field: Field<Long>?, label: Int, tag: String) {
    val count = if (field?.knowledge == Knowledge.KNOWN && field.value != null) {
        pluralStringResource(R.plurals.banked_count, field.value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), field.value)
    } else fieldLabel(BankedResetPresentation.fieldLabel(field))
    val text = stringResource(label, count)
    Column(Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = text }) {
        HiddenBankedText(text, "$tag-text")
    }
}

@Composable
private fun BankedItem(item: PresentedEntitlementItem, index: Int) {
    val tag = "banked-item-$index"
    val title = stringResource(R.string.banked_item, index + 1)
    val status = stringResource(item.state.labelResource)
    val source = item.source.value
    val row = stringResource(R.string.banked_row, fieldLabel(BankedResetPresentation.fieldLabel(item.source)))
    val provider = stringResource(R.string.banked_provider_status, fieldLabel(BankedResetPresentation.providerStatus(source?.providerStatus)))
    val type = stringResource(R.string.banked_type, fieldLabel(BankedResetPresentation.resetType(source?.resetType)))
    val expiryField = stringResource(R.string.banked_expiry_knowledge,
        fieldLabel(BankedResetPresentation.fieldLabel(source?.expiresAt ?: item.source)))
    val absolute = item.expiry.absolute?.let { stringResource(R.string.banked_expiry_value, absoluteTimeLabel(it)) }
    val relative = stringResource(R.string.banked_expiry_value, relativeLabel(item.expiry))
    val snapshot = stringResource(item.expiry.snapshot.labelResource)
    val details = listOfNotNull(title, row, provider, type, expiryField, absolute, relative, snapshot).joinToString("\n")
    Card(Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = details; stateDescription = status }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HiddenBankedText(title, "$tag-title")
            HiddenBankedText(status, "$tag-status")
            HiddenBankedText(row, "$tag-row")
            HiddenBankedText(provider, "$tag-provider")
            HiddenBankedText(type, "$tag-type")
            HiddenBankedText(expiryField, "$tag-knowledge")
            absolute?.let { HiddenBankedText(it, "$tag-absolute") }
            HiddenBankedText(relative, "$tag-relative")
            HiddenBankedText(snapshot, "$tag-snapshot")
        }
    }
}

@Composable
private fun HiddenBankedText(text: String, tag: String) {
    Text(text, Modifier.testTag(tag).semantics { hideFromAccessibility() })
}

@Composable
private fun fieldLabel(label: BankedFieldLabel): String {
    val knowledge = stringResource(label.knowledgeResource)
    return label.reasonResource?.let { stringResource(R.string.banked_field_reason, knowledge, stringResource(it)) } ?: knowledge
}

@Composable
internal fun absoluteTimeLabel(time: AbsoluteTime): String = if (time.utcOffset == null) {
    stringResource(time.formatResource, time.date, time.hour)
} else stringResource(time.formatResource, time.date, time.hour, time.utcOffset)
