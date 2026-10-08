package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.usage.BankedResetItem
import io.github.leugenea.codexbarmobile.usage.BankedResetObservation
import io.github.leugenea.codexbarmobile.usage.Completeness
import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.InventoryIssue
import io.github.leugenea.codexbarmobile.usage.Knowledge
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal enum class EntitlementState(val labelResource: Int) {
    KNOWN(R.string.entitlement_known),
    EMPTY(R.string.entitlement_empty),
    UNSUPPORTED(R.string.entitlement_unsupported),
    UNKNOWN(R.string.entitlement_unknown),
    INACCESSIBLE(R.string.entitlement_inaccessible),
    MALFORMED(R.string.entitlement_malformed),
    EXPIRED(R.string.time_expired),
    DISCREPANT(R.string.entitlement_discrepant),
}

internal enum class EntitlementSnapshotState { CURRENT_GENERATION, UNKNOWN, STALE_GENERATION }

/** Capture the generation at read admission, not from the session when a late result arrives. */
internal class EntitlementSnapshot(val generation: SessionGeneration, val observations: FeasibilityObservations)

/** Source fields retain status/type/expiry knowledge and reasons, including malformed row siblings. */
internal class PresentedEntitlementItem(
    val source: Field<BankedResetItem>,
    val state: EntitlementState,
    val expiry: PresentedTime,
) {
    val expiryLabelResource: Int = R.string.entitlement_expiry
}

/**
 * Counts are independent provider facts, never a row count, purchased balance or a fallback
 * for one another. A KNOWN count does not establish known expiry or activation permission.
 * Snapshot generation is local isolation only; it does not verify account/workspace identity.
 */
internal class PresentedEntitlements(
    val state: EntitlementState,
    val snapshot: EntitlementSnapshotState,
    val summaryAvailableCount: Field<Long>? = null,
    val reportedAvailableCount: Field<Long>? = null,
    val summaryObservedAt: Instant? = null,
    val inventoryObservedAt: Instant? = null,
    val inventoryRowCount: Int? = null,
    val completeness: Completeness = Completeness.UNKNOWN,
    val items: List<PresentedEntitlementItem> = emptyList(),
    val issues: Set<InventoryIssue> = emptySet(),
    val summaryError: ReadError? = null,
    val inventoryError: ReadError? = null,
    val inventoryRowContainer: Field<Int>? = null,
)

/** Read-only A9 projection. B1 alone reevaluates expiry; no time math, parser, I/O or history. */
internal object EntitlementPresentation {
    fun present(
        snapshot: EntitlementSnapshot?, activeGeneration: SessionGeneration?, now: Instant?,
        zone: ZoneId, locale: Locale,
    ): PresentedEntitlements {
        if (snapshot == null) return PresentedEntitlements(EntitlementState.UNKNOWN, EntitlementSnapshotState.UNKNOWN)
        if (snapshot.generation !== activeGeneration) {
            // Discard ALL old-session facts, even errors and observation clocks.
            return PresentedEntitlements(EntitlementState.UNKNOWN, EntitlementSnapshotState.STALE_GENERATION)
        }
        val endpoints = snapshot.observations
        val observation = endpoints.inventory.inventory
        val items = observation?.items.orEmpty().map { item(it, observation?.observedAt, now, zone, locale) }
        val issues = currentIssues(observation?.issues.orEmpty(), items)
        return PresentedEntitlements(
            state(observation, endpoints.inventory.error, items, issues), EntitlementSnapshotState.CURRENT_GENERATION,
            observation?.summaryAvailableCount ?: endpoints.usage.usage?.bankedAvailableCount,
            observation?.reportedAvailableCount,
            observation?.summaryObservedAt ?: endpoints.usage.usage?.observedAt, observation?.observedAt,
            observation?.inventoryRowCount, observation?.completeness ?: Completeness.UNKNOWN,
            items, issues, endpoints.usage.error, endpoints.inventory.error,
            inventoryRowContainer = observation?.inventoryRowContainer,
        )
    }

    private fun item(
        source: Field<BankedResetItem>, observedAt: Instant?, now: Instant?, zone: ZoneId, locale: Locale,
    ): PresentedEntitlementItem {
        val expiry = source.value?.expiresAt ?: Field(source.knowledge, reason = source.reason)
        val time = TimePresentation.present(expiry, TimeKind.ENTITLEMENT_EXPIRY, observedAt, now, zone, locale)
        return PresentedEntitlementItem(source, itemState(source, time), time)
    }

    private fun fieldState(field: Field<*>): EntitlementState = when (field.knowledge) {
        Knowledge.MALFORMED -> EntitlementState.MALFORMED
        Knowledge.UNSUPPORTED -> EntitlementState.UNSUPPORTED
        Knowledge.UNAVAILABLE -> EntitlementState.UNKNOWN
        Knowledge.KNOWN -> if (field.value == null) EntitlementState.UNKNOWN else EntitlementState.KNOWN
    }

    private fun itemState(source: Field<BankedResetItem>, expiry: PresentedTime): EntitlementState {
        val value = source.value ?: return fieldState(source)
        val fields = listOf(source, value.providerStatus, value.resetType, value.expiresAt).map(::fieldState)
        incompleteState(fields)?.let { return it }
        if (value.providerStatus.value != "available" || value.resetType.value != "codex_rate_limits") {
            return EntitlementState.UNSUPPORTED
        }
        return when (expiry.state) {
            TimeState.EXPIRED -> EntitlementState.EXPIRED
            TimeState.FUTURE, TimeState.LESS_THAN_HOUR -> EntitlementState.KNOWN
            else -> EntitlementState.UNKNOWN
        }
    }

    private fun incompleteState(states: List<EntitlementState>): EntitlementState? = when {
        EntitlementState.MALFORMED in states -> EntitlementState.MALFORMED
        EntitlementState.UNSUPPORTED in states -> EntitlementState.UNSUPPORTED
        EntitlementState.UNKNOWN in states -> EntitlementState.UNKNOWN
        else -> null
    }

    private fun currentIssues(
        original: Set<InventoryIssue>, items: List<PresentedEntitlementItem>,
    ): Set<InventoryIssue> {
        // A1's expiry issue is cached at decode time; reevaluate via B1 without altering counts.
        val issues = original - InventoryIssue.EXPIRED_AVAILABLE_ITEM
        val expiredAvailable = items.any {
            it.source.value?.providerStatus?.value == "available" && it.expiry.state == TimeState.EXPIRED
        }
        return if (expiredAvailable) issues + InventoryIssue.EXPIRED_AVAILABLE_ITEM else issues
    }

    private fun state(
        observation: BankedResetObservation?, error: ReadError?, items: List<PresentedEntitlementItem>,
        issues: Set<InventoryIssue>,
    ): EntitlementState {
        if (error != null) return errorState(error)
        if (observation == null) return EntitlementState.UNKNOWN
        if (issues.any { it in discrepancies }) return EntitlementState.DISCREPANT
        val countState = fieldState(observation.reportedAvailableCount)
        // A1 missing/null -> UNAVAILABLE -> UNKNOWN; invalid container -> MALFORMED.
        // Keep container reasons visible even when an endpoint error or discrepancy takes precedence.
        val containerState = fieldState(observation.inventoryRowContainer)
        val incomplete = incompleteState(listOf(countState, containerState) + items.map { it.state })
        if (incomplete != null) return incomplete
        if (observation.completeness != Completeness.COMPLETE) return EntitlementState.UNKNOWN
        return if (observation.reportedAvailableCount.value == 0L && observation.inventoryRowContainer.value == 0) {
            EntitlementState.EMPTY
        } else EntitlementState.KNOWN
    }

    private fun errorState(error: ReadError): EntitlementState = when (error) {
        ReadError.INVALID_RESPONSE, ReadError.BODY_TOO_LARGE -> EntitlementState.MALFORMED
        ReadError.OPERATION_NOT_ALLOWED -> EntitlementState.UNSUPPORTED
        ReadError.CANCELLED -> EntitlementState.UNKNOWN
        else -> EntitlementState.INACCESSIBLE
    }

    private val discrepancies = setOf(
        InventoryIssue.SUMMARY_COUNT_MISMATCH, InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH,
        InventoryIssue.EXPIRED_AVAILABLE_ITEM, InventoryIssue.DUPLICATE_IDENTITY,
    )
}
