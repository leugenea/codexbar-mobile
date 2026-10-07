package io.github.leugenea.codexbarmobile.transport

/** Shared single-winner delivery gate; resource closure/scheduling remain adapter-owned. */
class TerminalDelivery(
    private val deadline: ReadDeadline,
    private val clock: TransportClock,
    private val terminal: (TransportResult) -> Unit,
) : CancellationHandle {
    private var finished = false

    fun complete(result: TransportResult): Boolean {
        val accepted = if (deadline.isExpired(clock.now()))
            TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED) else result
        return deliver(accepted)
    }

    /** The adapter's deadline scheduler calls this even when a call never completes. */
    fun checkDeadline(): Boolean = if (deadline.isExpired(clock.now()))
        deliver(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED)) else false

    override fun cancel() {
        deliver(TransportResult.Failure(TransportFailure.CANCELLED))
    }

    private fun deliver(result: TransportResult): Boolean {
        val won = synchronized(this) {
            if (finished) false else {
                finished = true
                true
            }
        }
        // Outside the lock: callbacks may reenter/cancel without deadlocking.
        if (won) terminal(result)
        return won
    }
}
