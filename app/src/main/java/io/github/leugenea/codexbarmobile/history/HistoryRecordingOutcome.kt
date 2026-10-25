package io.github.leugenea.codexbarmobile.history

/** Acknowledgement of this actual endpoint admission, never a cache or worker-idle receipt. */
internal sealed interface HistoryRecordingOutcome {
    data class Stored(val id: ObservationId) : HistoryRecordingOutcome
    data class Failed(val problem: HistoryRecorderProblem, val reason: HistoryUnavailable? = null) : HistoryRecordingOutcome
    data object NotObserved : HistoryRecordingOutcome
    data object Revoked : HistoryRecordingOutcome
}
