package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.InventoryIssue
import io.github.leugenea.codexbarmobile.usage.Knowledge
import io.github.leugenea.codexbarmobile.usage.Reason
import java.time.ZoneId
import java.util.Locale

internal data class BankedFieldLabel(val knowledgeResource: Int, val reasonResource: Int?)

internal data class PresentedBankedResetSection(
    val entitlements: PresentedEntitlements,
    val refreshing: Boolean,
    val stale: Boolean,
    val errorResource: Int?,
)

/** UI adapter only. B2 owns retained observations/isolation, C1 owns states/counts, B1 owns time. */
internal object BankedResetPresentation {
    fun present(state: ConnectionState, zone: ZoneId, locale: Locale): PresentedBankedResetSection {
        val connected = state.phase in connectedPhases
        // ConnectionState.auth is captured at read admission, published atomically with B2's cache.
        // B2 clears the cache on retirement and rejects late completions; never query the live session here.
        val generation = if (connected) (state.auth as? AuthState.Connected)?.generation else null
        val snapshot = generation?.let { owner -> state.refresh.observations?.let { EntitlementSnapshot(owner, it) } }
        val entitlements = EntitlementPresentation.present(snapshot, generation, state.refresh.evaluatedAt, zone, locale)
        return PresentedBankedResetSection(entitlements, connected && state.refresh.refreshing,
            connected && state.refresh.inventory.stale, entitlements.inventoryError?.let(::errorResource))
    }

    fun fieldLabel(field: Field<*>?): BankedFieldLabel = BankedFieldLabel(
        when (field?.knowledge) {
            Knowledge.KNOWN -> if (field.value == null) R.string.banked_field_unknown else R.string.banked_field_known
            Knowledge.MALFORMED -> R.string.banked_field_malformed
            Knowledge.UNSUPPORTED -> R.string.banked_field_unsupported
            else -> R.string.banked_field_unknown
        }, field?.reason?.let(::reasonResource),
    )

    // Only the evidenced status/type gets a human label, never arbitrary provider strings/identities.
    fun providerStatus(field: Field<String>?): BankedFieldLabel = protocolLabel(field, "available", R.string.banked_available)
    fun resetType(field: Field<String>?): BankedFieldLabel = protocolLabel(field, "codex_rate_limits", R.string.banked_reset_type)

    private fun protocolLabel(field: Field<String>?, supported: String, label: Int): BankedFieldLabel {
        val knowledge = fieldLabel(field)
        if (field?.knowledge != Knowledge.KNOWN || field.value == null) return knowledge
        return BankedFieldLabel(if (field.value == supported) label else R.string.banked_field_unsupported, knowledge.reasonResource)
    }

    private fun reasonResource(reason: Reason): Int = when (reason) {
        Reason.MISSING -> R.string.banked_reason_missing
        Reason.PROVIDER_NULL -> R.string.banked_reason_null
        Reason.WRONG_TYPE -> R.string.banked_reason_type
        Reason.OUT_OF_RANGE -> R.string.banked_reason_range
        Reason.UNSUPPORTED_FORMAT -> R.string.banked_reason_format
        Reason.CLOCK_REQUIRED -> R.string.banked_reason_clock
    }

    fun issueResource(issue: InventoryIssue): Int = when (issue) {
        InventoryIssue.SUMMARY_COUNT_MISMATCH -> R.string.banked_issue_summary
        InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH -> R.string.banked_issue_rows
        InventoryIssue.EXPIRED_AVAILABLE_ITEM -> R.string.banked_issue_expired
        InventoryIssue.DUPLICATE_IDENTITY -> R.string.banked_issue_duplicate
        InventoryIssue.UNKNOWN_STATUS -> R.string.banked_issue_status
        InventoryIssue.UNKNOWN_RESET_TYPE -> R.string.banked_issue_type
    }

    private fun errorResource(error: ReadError): Int = when (error) {
        ReadError.REAUTHORIZE -> R.string.banked_error_reauthorize
        ReadError.FORBIDDEN -> R.string.banked_error_forbidden
        ReadError.RATE_LIMITED -> R.string.banked_error_rate
        ReadError.TRANSIENT -> R.string.banked_error_transient
        ReadError.INVALID_RESPONSE, ReadError.BODY_TOO_LARGE -> R.string.banked_error_invalid
        ReadError.CANCELLED -> R.string.banked_error_cancelled
        ReadError.DEADLINE_EXCEEDED -> R.string.banked_error_deadline
        ReadError.OPERATION_NOT_ALLOWED -> R.string.banked_error_unsupported
    }

    private val connectedPhases = setOf(ConnectionPhase.RESTORED, ConnectionPhase.READING, ConnectionPhase.OBSERVED)
}
