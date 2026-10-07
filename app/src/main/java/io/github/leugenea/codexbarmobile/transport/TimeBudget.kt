package io.github.leugenea.codexbarmobile.transport

import java.time.Instant

/** Wall time interprets HTTP dates; monotonic time owns deadlines and scheduled retries. */
data class TransportTime(val wall: Instant, val monotonicMillis: Long) {
    init {
        require(monotonicMillis >= 0) { "Invalid monotonic time" }
    }
}

fun interface TransportClock {
    fun now(): TransportTime
}

class ReadDeadline private constructor(val expiresAtMillis: Long) {
    fun isExpired(now: TransportTime): Boolean = now.monotonicMillis >= expiresAtMillis

    companion object {
        const val MAX_DURATION_MILLIS = 30_000L

        fun after(now: TransportTime, durationMillis: Long = MAX_DURATION_MILLIS): ReadDeadline {
            require(durationMillis in 1..MAX_DURATION_MILLIS) { "Invalid read deadline" }
            return ReadDeadline(saturatedAdd(now.monotonicMillis, durationMillis))
        }
    }
}

internal fun saturatedAdd(base: Long, increment: Long): Long =
    if (increment > Long.MAX_VALUE - base) Long.MAX_VALUE else base + increment
