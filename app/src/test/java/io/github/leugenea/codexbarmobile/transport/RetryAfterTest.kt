package io.github.leugenea.codexbarmobile.transport

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RetryAfterTest {
    private val clock = VirtualTransportClock()

    @Test fun deltaSecondsUseReceiptMonotonicClock() {
        assertEquals(RetryAfter.NotBefore(6000), parse(" 5 "))
        assertEquals(RetryAfter.NotBefore(1000), parse("0"))
        assertEquals(RetryAfter.NotBefore(2000), parse("0001"))
        assertEquals(RetryAfter.Missing, parse(null))
    }

    @Test fun dateUsesInjectedWallClockThenFreezesMonotonicNotBefore() {
        val retry = parse("Wed, 7 Oct 2026 00:00:10 GMT")
        assertEquals(RetryAfter.NotBefore(11_000), retry)
        clock.wall = clock.wall.plusSeconds(3600)
        assertEquals(RetryAfter.NotBefore(11_000), retry)
        assertEquals(RetryAfter.NotBefore(1000), parse("Wed, 7 Oct 2026 00:00:00 GMT"))
        assertEquals(RetryAfter.NotBefore(1000), parse("Tue, 6 Oct 2026 23:59:59 GMT"))
    }

    @Test fun fractionalClockRoundsUpRatherThanRetryingEarly() {
        clock.wall = Instant.parse("2026-10-07T00:00:00.000000001Z")
        assertEquals(RetryAfter.NotBefore(2000), parse("Wed, 7 Oct 2026 00:00:01 GMT"))
        clock.wall = Instant.parse("2026-10-07T00:00:00.999999999Z")
        assertEquals(RetryAfter.NotBefore(1001), parse("Wed, 7 Oct 2026 00:00:01 GMT"))
    }

    @Test fun malformedHeadersFailClosedWithoutRetainingInput() {
        val invalid = listOf("", " ", "-1", "+1", "1.5", "١", "synthetic-sensitive-marker",
            "Wed, 31 Feb 2026 00:00:00 GMT", "Wed, 7 Oct 2026 25:00:00 GMT", "x".repeat(129))
        invalid.forEach { assertEquals(RetryAfter.Invalid, parse(it)) }
        assertFalse(parse("synthetic-sensitive-marker").toString().contains("synthetic-sensitive-marker"))
    }

    @Test fun hugeDelaysSaturateWithoutWrappingOrShortening() {
        listOf("9223372036854775807", "9223372036854776", "9".repeat(100)).forEach {
            assertEquals(RetryAfter.NotBefore(Long.MAX_VALUE), parse(it))
        }
        clock.monotonicMillis = Long.MAX_VALUE - 5
        assertEquals(RetryAfter.NotBefore(Long.MAX_VALUE), parse("1"))
        clock.wall = Instant.MIN
        assertEquals(RetryAfter.NotBefore(Long.MAX_VALUE), parse("Wed, 7 Oct 2026 00:00:01 GMT"))
    }

    @Test fun deadlineIsMonotonicBoundedAndOverflowSafe() {
        val deadline = ReadDeadline.after(clock.now())
        assertEquals(31_000, deadline.expiresAtMillis)
        clock.wall = clock.wall.plusSeconds(86400)
        assertFalse(deadline.isExpired(clock.now()))
        clock.advance(30_000)
        assertTrue(deadline.isExpired(clock.now()))
        clock.monotonicMillis = Long.MAX_VALUE - 1
        assertEquals(Long.MAX_VALUE, ReadDeadline.after(clock.now()).expiresAtMillis)
        listOf(0L, -1L, 30_001L).forEach { duration ->
            assertThrows(IllegalArgumentException::class.java) { ReadDeadline.after(clock.now(), duration) }
        }
        assertThrows(IllegalArgumentException::class.java) { TransportTime(Instant.EPOCH, -1) }
        assertThrows(IllegalArgumentException::class.java) { RetryAfter.NotBefore(-1) }
    }

    private fun parse(value: String?): RetryAfter = RetryAfterParser.parse(value, clock.now())
}
