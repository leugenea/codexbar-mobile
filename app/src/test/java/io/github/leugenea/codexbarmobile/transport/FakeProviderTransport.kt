package io.github.leugenea.codexbarmobile.transport

import java.time.Instant

/** Original synthetic clock/fake; no threads, sockets, sleeping or live provider data. */
internal class VirtualTransportClock(
    var wall: Instant = Instant.parse("2026-10-07T00:00:00Z"),
    var monotonicMillis: Long = 1000,
) : TransportClock {
    override fun now(): TransportTime = TransportTime(wall, monotonicMillis)

    fun advance(millis: Long) {
        monotonicMillis += millis
        wall = wall.plusMillis(millis)
    }
}

internal class FakeProviderTransport(private val clock: TransportClock) : ProviderTransport {
    val pending = mutableListOf<PendingRead>()

    override fun read(request: ReadRequest, terminal: (TransportResult) -> Unit): CancellationHandle {
        val call = PendingRead(request, clock, terminal)
        pending.add(call)
        call.delivery.checkDeadline()
        return call
    }

    fun tick() {
        pending.toList().forEach { it.delivery.checkDeadline() }
    }

    class PendingRead(
        val request: ReadRequest, clock: TransportClock, terminal: (TransportResult) -> Unit,
    ) : CancellationHandle {
        var closed = false
            private set
        val delivery = TerminalDelivery(request.deadline, clock) {
            closed = true
            terminal(it)
        }

        fun complete(result: TransportResult): Boolean = delivery.complete(result)
        override fun cancel() = delivery.cancel()
    }
}
