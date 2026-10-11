package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class TimestampPresentationTest {
    private val utc = ZoneId.of("UTC")

    @Test fun originalFractionalInstantConvertsToPhoneZoneAndEffectiveLocale() {
        val original = Instant.parse("2026-10-09T00:00:00.123456789Z")
        assertEquals("Oct 9, 2026, 12:00:00 AM", TimePresentation.timestamp(original, utc, Locale.US))
        assertEquals("Oct 8, 2026, 5:00:00 PM", TimePresentation.timestamp(original, ZoneId.of("America/Los_Angeles"), Locale.US))
        assertEquals("09.10.2026, 02:00:00", TimePresentation.timestamp(original, ZoneId.of("Europe/Berlin"), Locale.GERMANY))
        assertEquals(123456789, original.nano)
        assertEquals(Instant.parse("2026-10-09T00:00:00.123456789Z"), original)
    }

    @Test fun repeatedSecondsIncludeActualOffsetsForFullAndHalfHourOverlaps() {
        val ny = ZoneId.of("America/New_York")
        assertEquals("Nov 1, 2026, 1:30:00 AM UTC-04:00", timestamp("2026-11-01T05:30:00Z", ny))
        assertEquals("Nov 1, 2026, 1:30:00 AM UTC-05:00", timestamp("2026-11-01T06:30:00Z", ny))
        val lordHowe = ZoneId.of("Australia/Lord_Howe")
        assertEquals("Apr 5, 2026, 1:45:00 AM UTC+11:00", timestamp("2026-04-04T14:45:00Z", lordHowe))
        assertEquals("Apr 5, 2026, 1:45:00 AM UTC+10:30", timestamp("2026-04-04T15:15:00Z", lordHowe))
        assertEquals("Apr 5, 2026, 1:15:00 AM", timestamp("2026-04-04T14:15:00Z", lordHowe))
        assertEquals("Oct 25, 2026, 1:30:00 AM UTC+00:00", timestamp("2026-10-25T01:30:00Z", ZoneId.of("Europe/London")))
    }

    @Test fun unknownAndUnrepresentableClocksNeverFallBackToIsoOrEpoch() {
        assertNull(TimePresentation.timestamp(null, utc, Locale.US))
        assertNull(TimePresentation.timestamp(Instant.MAX, utc, Locale.US))
        assertNull(TimePresentation.timestamp(Instant.MIN, ZoneId.of("America/Los_Angeles"), Locale.US))
    }

    @Test fun everyHistoryInstantUsesSharedLocalTextWhileExactFactsStayOriginal() {
        val entry = SyntheticHistory.append().entry
        val zone = ZoneId.of("America/Los_Angeles")
        val detail = detail(entry, zone)
        assertEquals("Jan 15, 2027, 12:00:00 AM", detail.facts.single { it.label == R.string.history_observed }.value)
        val window = detail.windows.first()
        val expected = mapOf(
            R.string.history_reset_exact to "Jan 15, 2027, 1:00:00 AM",
            R.string.history_point_time to "Jan 15, 2027, 12:00:00 AM",
            R.string.history_nominal_start to "Jan 14, 2027, 8:00:00 PM",
            R.string.history_nominal_end to "Jan 15, 2027, 1:00:00 AM",
        )
        expected.forEach { (label, text) -> assertEquals(text, window.facts.single { it.label == label }.value) }
        assertEquals("18000", window.facts.single { it.label == R.string.history_duration }.value)
        assertEquals("12.375", window.facts.single { it.label == R.string.history_measured }.value)
        assertSame(entry.windows.first().point, HistoryGraphWindow(entry.windows.first()).source.point)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), entry.observedAt)
        assertEquals(Instant.ofEpochSecond(1_800_003_600), entry.windows.first().reset.facts!!.absolute.value)
    }

    @Test fun conflictingSourceClocksRemainLabeledWithoutSelectingAnEffectiveReset() {
        val usage = SyntheticHistory.usage(primary = SyntheticHistory.window(
            absolute = Input.Value(1_800_003_600L), relative = Input.Value(99L)))
        val entry = SyntheticHistory.append(usage = usage).entry
        val window = detail(entry, utc).windows.first()
        assertEquals("Jan 15, 2027, 9:00:00 AM", window.facts.single { it.label == R.string.history_reset_exact }.value)
        assertEquals("Jan 15, 2027, 8:01:39 AM", window.facts.single { it.label == R.string.history_reset_derived }.value)
        assertEquals("99", window.facts.single { it.label == R.string.history_reset_seconds }.value)
        assertEquals(TimeState.DISCREPANT, window.reset!!.state)
        assertNull(window.reset.absolute)
        assertTrue(entry.windows.first().reset.facts!!.discrepant)
        assertEquals(Instant.ofEpochSecond(1_800_000_099), entry.windows.first().reset.facts!!.relativeDerived.value)
    }

    @Test fun fractionalHistorySourcesAndUnknownKnowledgeRemainUntouched() {
        val original = SyntheticHistory.at.plusNanos(123456789)
        val entry = SyntheticHistory.append(usage = SyntheticHistory.usage(observedAt = original)).entry
        val shown = detail(entry, utc)
        assertEquals("Jan 15, 2027, 8:00:00 AM", shown.facts.single { it.label == R.string.history_observed }.value)
        assertEquals(123456789, entry.observedAt!!.nano)
        assertEquals(original, entry.windows.first().point!!.observedAt)
        val unknown = SyntheticHistory.append(usage = SyntheticHistory.usage(primary =
            SyntheticHistory.window(absolute = Input.Null, relative = Input.Missing))).entry
        val facts = detail(unknown, utc).windows.first().facts
        assertNull(facts.single { it.label == R.string.history_reset_exact }.value)
        assertTrue(facts.any { it.state == Reason.PROVIDER_NULL })
        assertTrue(facts.any { it.state == Knowledge.UNAVAILABLE })
    }

    private fun timestamp(value: String, zone: ZoneId) = TimePresentation.timestamp(Instant.parse(value), zone, Locale.US)
    private fun detail(entry: HistoryEntry, zone: ZoneId) = HistoryTextPresentation.detail(
        HistoryGraphEntry(entry, entry.windows.map(::HistoryGraphWindow)), SyntheticHistory.at, zone, Locale.US)
}
