package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.transport.ReadOperation

/** One bounded refresh allowance for a two-endpoint read; no cadence or scheduling. */
internal class AuthenticatedProviderReader(
    private val session: SessionCoordinator,
    private val reader: NativeFeasibilityReader,
) {
    suspend fun read(): FeasibilityObservations {
        val initial = session.snapshot()
        if (initial is SessionResult.Failed) return failure(initial.problem.readError())
        val before = (initial as SessionResult.Ready).envelope
        val usage = reader.endpoint(ReadOperation.USAGE, before.accessToken, session = session, envelope = before)
        val next = session.snapshot()
        if (next is SessionResult.Failed) return failure(next.problem.readError())
        val after = (next as SessionResult.Ready).envelope
        if (after.generation !== before.generation) return failure(ReadError.CANCELLED)
        val inventory = reader.endpoint(ReadOperation.RESET_INVENTORY, after.accessToken, usage.usage,
            session, after, alreadyRefreshed = before !== after)
        // Data from an invalidated generation never crosses the repository boundary.
        val final = session.snapshot()
        if (final is SessionResult.Ready && final.envelope.generation === before.generation)
            return FeasibilityObservations(usage, inventory)
        return failure((final as? SessionResult.Failed)?.problem?.readError() ?: ReadError.CANCELLED)
    }

    private fun failure(error: ReadError) = FeasibilityObservations(
        EndpointObservation(ReadOperation.USAGE, error = error),
        EndpointObservation(ReadOperation.RESET_INVENTORY, error = error),
    )
}

internal fun SessionProblem.readError(): ReadError = when (this) {
    SessionProblem.REAUTHORIZE, SessionProblem.STORAGE -> ReadError.REAUTHORIZE
    SessionProblem.RATE_LIMITED -> ReadError.RATE_LIMITED
    SessionProblem.MALFORMED -> ReadError.INVALID_RESPONSE
    SessionProblem.STALE -> ReadError.CANCELLED
    SessionProblem.TRANSIENT -> ReadError.TRANSIENT
}
