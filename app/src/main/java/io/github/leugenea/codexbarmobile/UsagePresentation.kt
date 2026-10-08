package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.Knowledge
import io.github.leugenea.codexbarmobile.usage.SelectionState
import io.github.leugenea.codexbarmobile.usage.WindowSelection
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.time.ZoneId
import java.util.Locale

internal enum class UsageScreenStatus(val labelResource: Int) {
    DISCONNECTED(R.string.live_disconnected), LOADING(R.string.live_loading),
    UNAVAILABLE(R.string.live_unavailable), CURRENT(R.string.live_current),
    STALE(R.string.live_stale), ERROR(R.string.live_error), REAUTHORIZE(R.string.live_reauthorize),
}

internal enum class UsageWindowStatus(val labelResource: Int) {
    AVAILABLE(R.string.live_window_available), EXHAUSTED(R.string.live_exhausted),
    UNAVAILABLE(R.string.live_window_unavailable), MALFORMED(R.string.live_malformed),
    AMBIGUOUS(R.string.live_ambiguous), UNSUPPORTED(R.string.live_unsupported),
}

internal data class PresentedUsageWindow(
    val labelResource: Int,
    val tag: String,
    val status: UsageWindowStatus,
    val percent: String? = null,
    val progress: Float? = null,
    val reset: PresentedTime? = null,
    val malformed: Boolean = false,
)

internal data class PresentedUsage(
    val status: UsageScreenStatus,
    val refreshing: Boolean,
    val stale: Boolean,
    val refreshEnabled: Boolean,
    val errorResource: Int?,
    val allowedResource: Int,
    val limitResource: Int,
    val windows: List<PresentedUsageWindow>,
)

/** Pure B2 -> UI projection. No transport, cache ownership, time arithmetic or entitlement UI. */
internal object UsagePresentation {
    fun present(state: ConnectionState, zone: ZoneId, locale: Locale): PresentedUsage {
        val refresh = state.refresh
        val connected = state.phase in connectedPhases
        // Never display retained facts under a disconnected/retired session.
        val usage = if (connected) refresh.usage.success?.usage else null
        val error = if (connected) refresh.usage.attempt?.error else null
        val windows = listOf(
            window(usage?.fiveHour, R.string.live_five_hour, "live-five-hour", refresh, zone, locale),
            window(usage?.weekly, R.string.live_weekly, "live-weekly", refresh, zone, locale),
        )
        return PresentedUsage(
            status(state, usage != null, error), refresh.refreshing && connected,
            refresh.usage.stale && usage != null, connected && !state.busy && !refresh.refreshing,
            error?.let(::errorResource), flag(usage?.allowed, R.string.live_allowed, R.string.live_not_allowed),
            flag(usage?.limitReached, R.string.live_limit_reached, R.string.live_limit_not_reached), windows,
        )
    }

    private fun status(state: ConnectionState, hasUsage: Boolean, error: ReadError?): UsageScreenStatus {
        phaseStatus(state)?.let { return it }
        return when {
            error == ReadError.REAUTHORIZE -> UsageScreenStatus.REAUTHORIZE
            error != null -> UsageScreenStatus.ERROR
            state.refresh.refreshing && !hasUsage -> UsageScreenStatus.LOADING
            !hasUsage -> UsageScreenStatus.UNAVAILABLE
            state.refresh.usage.stale -> UsageScreenStatus.STALE
            else -> UsageScreenStatus.CURRENT
        }
    }

    private fun phaseStatus(state: ConnectionState): UsageScreenStatus? = when (state.phase) {
        ConnectionPhase.REAUTH_REQUIRED -> UsageScreenStatus.REAUTHORIZE
        ConnectionPhase.RESTORING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.SIGNING_OUT -> UsageScreenStatus.LOADING
        ConnectionPhase.IDLE, ConnectionPhase.CANCELLED, ConnectionPhase.SIGNED_OUT -> UsageScreenStatus.DISCONNECTED
        ConnectionPhase.FAILED -> UsageScreenStatus.ERROR
        else -> null
    }

    private fun window(selection: WindowSelection?, label: Int, tag: String, refresh: UsageRefreshState,
        zone: ZoneId, locale: Locale): PresentedUsageWindow {
        val source = selection?.takeIf { it.state != SelectionState.AMBIGUOUS }?.candidates?.singleOrNull()
        val percent = source?.usedPercent?.takeIf { it.knowledge == Knowledge.KNOWN }?.value
        val status = if (percent != null) {
            if (percent.compareTo(BigDecimal(100)) == 0) UsageWindowStatus.EXHAUSTED else UsageWindowStatus.AVAILABLE
        } else missingStatus(selection, source?.usedPercent)
        return PresentedUsageWindow(label, tag, status, percent?.let { decimal(it, locale) },
            percent?.toFloat()?.div(100f), source?.let { refresh.reset(it, zone, locale) },
            selection?.state == SelectionState.MALFORMED)
    }

    private fun missingStatus(selection: WindowSelection?, percent: Field<BigDecimal>?): UsageWindowStatus = when {
        selection?.state == SelectionState.AMBIGUOUS -> UsageWindowStatus.AMBIGUOUS
        selection?.state == SelectionState.MALFORMED || percent?.knowledge == Knowledge.MALFORMED -> UsageWindowStatus.MALFORMED
        percent?.knowledge == Knowledge.UNSUPPORTED -> UsageWindowStatus.UNSUPPORTED
        else -> UsageWindowStatus.UNAVAILABLE
    }

    private fun decimal(value: BigDecimal, locale: Locale): String {
        // Provider exponents can be huge even for valid 0..100 values. Keep them exact
        // without allocating an unbounded plain-string expansion on the UI thread.
        val text = if (value.scale() in -100..100) value.toPlainString() else value.toString()
        return text.replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }

    private fun flag(field: Field<Boolean>?, yes: Int, no: Int): Int = when {
        field?.knowledge == Knowledge.MALFORMED -> R.string.live_flag_malformed
        field?.knowledge == Knowledge.UNSUPPORTED -> R.string.live_flag_unsupported
        field?.knowledge != Knowledge.KNOWN || field.value == null -> R.string.live_flag_unknown
        field.value -> yes
        else -> no
    }

    private fun errorResource(error: ReadError): Int = when (error) {
        ReadError.REAUTHORIZE -> R.string.live_reauthorize
        ReadError.FORBIDDEN -> R.string.live_forbidden
        ReadError.RATE_LIMITED -> R.string.live_rate_limited
        ReadError.TRANSIENT -> R.string.live_transient
        ReadError.INVALID_RESPONSE, ReadError.BODY_TOO_LARGE -> R.string.live_invalid_response
        ReadError.CANCELLED -> R.string.live_cancelled
        ReadError.DEADLINE_EXCEEDED -> R.string.live_deadline
        ReadError.OPERATION_NOT_ALLOWED -> R.string.live_operation_unsupported
    }

    private val connectedPhases = setOf(ConnectionPhase.RESTORED, ConnectionPhase.READING, ConnectionPhase.OBSERVED)
}
