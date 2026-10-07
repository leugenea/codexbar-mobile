package io.github.leugenea.codexbarmobile.usage

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Primitive validation shared by both reads; no numeric-string/Boolean coercion. */
internal object PrimitiveNormalizer {
    private val utcFormat = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z")

    fun <T, R> read(input: Input<T>, validate: (T) -> Field<R>): Field<R> = when (input) {
        Input.Missing -> Field(Knowledge.UNAVAILABLE, reason = Reason.MISSING)
        Input.Null -> Field(Knowledge.UNAVAILABLE, reason = Reason.PROVIDER_NULL)
        Input.Invalid -> malformed(Reason.WRONG_TYPE)
        is Input.Value -> validate(input.value)
    }

    fun <T> known(value: T): Field<T> = Field(Knowledge.KNOWN, value)
    fun <T> malformed(reason: Reason): Field<T> = Field(Knowledge.MALFORMED, reason = reason)

    fun boolean(input: Input<Any>): Field<Boolean> = read(input) {
        if (it is Boolean) known(it) else malformed(Reason.WRONG_TYPE)
    }

    fun text(input: Input<Any>): Field<String> = read(input) {
        if (it is String) known(it) else malformed(Reason.WRONG_TYPE)
    }

    private fun decimal(value: Any): Field<BigDecimal> = when (value) {
        is BigDecimal -> known(value)
        is Byte, is Short, is Int, is Long -> known(BigDecimal(value.toString()))
        is Float -> floating(value.toDouble(), value.toString())
        is Double -> floating(value, value.toString())
        else -> malformed(Reason.WRONG_TYPE)
    }

    private fun floating(value: Double, text: String): Field<BigDecimal> =
        if (value.isFinite()) known(BigDecimal(text)) else malformed(Reason.OUT_OF_RANGE)

    fun percent(input: Input<Any>): Field<BigDecimal> = read(input) {
        val number = decimal(it)
        val value = number.value ?: return@read number
        if (value < BigDecimal.ZERO || value > BigDecimal(100)) malformed(Reason.OUT_OF_RANGE)
        else number
    }

    fun integer(input: Input<Any>, minimum: Long = 0): Field<Long> = read(input) {
        val number = decimal(it)
        val value = number.value ?: return@read Field(number.knowledge, reason = number.reason)
        try {
            val exact = value.longValueExact()
            if (exact < minimum) malformed(Reason.OUT_OF_RANGE) else known(exact)
        } catch (_: ArithmeticException) {
            malformed(Reason.OUT_OF_RANGE)
        }
    }

    fun epoch(input: Input<Any>): Field<Instant> {
        val seconds = integer(input, 1)
        val value = seconds.value ?: return Field(seconds.knowledge, reason = seconds.reason)
        return instant { Instant.ofEpochSecond(value) }
    }

    /** Only the evidenced UTC grammar is supported, not every ISO-8601 shape. */
    fun utc(input: Input<Any>): Field<Instant> = read(input) {
        if (it !is String) return@read malformed(Reason.WRONG_TYPE)
        if (!utcFormat.matches(it)) return@read Field(Knowledge.UNSUPPORTED, reason = Reason.UNSUPPORTED_FORMAT)
        // Strict local parsing rejects leap-second/24:00 normalization by Instant.parse.
        instant { LocalDateTime.parse(it.dropLast(1)).toInstant(ZoneOffset.UTC) }
    }

    /** M0 requires integer epoch seconds >= 1, even for subsecond UTC inputs. */
    fun positiveEpoch(value: Instant?): Instant? = value?.takeIf { it.epochSecond > 0 }

    fun instant(operation: () -> Instant): Field<Instant> = try {
        positiveEpoch(operation())?.let { known(it) } ?: malformed(Reason.OUT_OF_RANGE)
    } catch (_: DateTimeException) {
        malformed(Reason.OUT_OF_RANGE)
    } catch (_: ArithmeticException) {
        malformed(Reason.OUT_OF_RANGE)
    }
}
