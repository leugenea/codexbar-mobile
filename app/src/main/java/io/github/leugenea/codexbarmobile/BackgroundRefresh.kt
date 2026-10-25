package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.HistoryPartition
import io.github.leugenea.codexbarmobile.history.HistoryRecordingOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

/** Existing opaque local credential lifetime, not a provider identity or the connection slot. */
internal data class BackgroundRefreshTarget(val partition: HistoryPartition) {
    override fun toString() = "BackgroundRefreshTarget(redacted)"
}

internal enum class BackgroundRefreshRejection { STALE_LIFETIME, DEFERRED, RETIRING }

/** Endpoint delivery and durable measurement acknowledgement are deliberately independent. */
internal sealed interface BackgroundRefreshOutcome {
    class Completed(val observations: FeasibilityObservations, val history: HistoryRecordingOutcome) : BackgroundRefreshOutcome {
        override fun toString() = "BackgroundRefreshOutcome.Completed(redacted)"
    }
    data class Rejected(val reason: BackgroundRefreshRejection, val notBeforeMillis: Long? = null) : BackgroundRefreshOutcome
}

/** One existing B2 flight. The process owner, not any waiter, owns its durable acknowledgement. */
internal class RefreshReadCompletion(val observations: FeasibilityObservations, val recording: Deferred<HistoryRecordingOutcome>)

internal class UsageRefreshFlight(var foreground: Boolean) {
    lateinit var job: Job
    var background = 0
    val result = CompletableDeferred<RefreshReadCompletion?>()
}

internal class BackgroundRefreshInterest(var flight: UsageRefreshFlight?) {
    var attached = true
}
