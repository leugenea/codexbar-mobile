package io.github.leugenea.codexbarmobile.transport

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Only sanitized scheduling facts cross the boundary, never the raw header. */
sealed interface RetryAfter {
    data object Missing : RetryAfter
    data object Invalid : RetryAfter
    data class NotBefore(val monotonicMillis: Long) : RetryAfter {
        init {
            require(monotonicMillis >= 0) { "Invalid retry time" }
        }
    }
}

object RetryAfterParser {
    // Bound work on untrusted metadata. Invalid values defer rather than retry early.
    private const val MAX_HEADER_CHARS = 128

    fun parse(value: String?, receivedAt: TransportTime): RetryAfter {
        if (value == null) return RetryAfter.Missing
        if (value.length > MAX_HEADER_CHARS) return RetryAfter.Invalid
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return RetryAfter.Invalid
        val delay = if (trimmed.all { it in '0'..'9' }) secondsMillis(trimmed)
        else dateMillis(trimmed, receivedAt)
        return delay?.let { RetryAfter.NotBefore(saturatedAdd(receivedAt.monotonicMillis, it)) }
            ?: RetryAfter.Invalid
    }

    private fun secondsMillis(value: String): Long {
        val seconds = value.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
    }

    private fun dateMillis(value: String, receivedAt: TransportTime): Long? = try {
        val formatter = DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)
        val date = ZonedDateTime.parse(value, formatter).toInstant()
        val duration = Duration.between(receivedAt.wall, date)
        if (duration.isNegative) 0 else roundedMillis(duration)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun roundedMillis(duration: Duration): Long = try {
        // Round up so sub-millisecond clock precision cannot cause an early retry.
        saturatedAdd(duration.toMillis(), if (duration.nano % 1_000_000 == 0) 0 else 1)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}
