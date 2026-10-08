package io.github.leugenea.codexbarmobile

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/** Source resource contract, not Android rendering; native consumers own rendering checks. */
class TimePresentationResourcesTest {
    private fun resources(): List<Element> {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.isFile }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    @Test fun stateAndSnapshotWordingIsExplicit() {
        val strings = resources().filter { it.tagName == "string" }.associate { it.getAttribute("name") to it.textContent }
        val expected = mapOf(
            "time_less_than_hour" to "<1h remaining",
            "time_awaiting_refresh" to "Reset passed · awaiting refresh",
            "time_expired" to "Entitlement expired",
            "time_unknown_target" to "Time unavailable",
            "time_unknown_clock" to "Remaining time unavailable · clock unknown",
            "time_discrepant" to "Conflicting time information",
            "time_snapshot_observed" to "As observed · freshness not guaranteed",
            "time_snapshot_unknown" to "Snapshot freshness unknown",
            "time_snapshot_stale" to "Stale snapshot · awaiting refresh",
            "time_clock_before_observation" to "Clock is before snapshot observation",
        )
        for ((name, text) in expected) assertEquals(name, text, strings[name])
        assertEquals("1 day 14 hours remaining", String.format(Locale.US, strings.getValue("time_remaining"), "1 day 14 hours"))
    }

    @Test fun absoluteFormatsDeclareHourPrecisionAndTheRepeatedHourOffset() {
        val strings = resources().filter { it.tagName == "string" }.associate { it.getAttribute("name") to it.textContent }
        assertEquals(
            "Oct 9, 2026, 14:00 (hour precision)",
            String.format(Locale.US, strings.getValue("time_absolute"), "Oct 9, 2026", "14:00"),
        )
        assertEquals(
            "Nov 1, 2026, 01:00 UTC-05:00 (hour precision)",
            String.format(Locale.US, strings.getValue("time_absolute_offset"), "Nov 1, 2026", "01:00", "-05:00"),
        )
        assertEquals(
            "Oct 25, 2026, 01:00 UTC+00:00 (hour precision)",
            String.format(Locale.US, strings.getValue("time_absolute_offset"), "Oct 25, 2026", "01:00", "+00:00"),
        )
    }

    @Test fun dayAndHourPluralsRetainZeroSingularAndMultipleUnits() {
        val plurals = resources().filter { it.tagName == "plurals" }.associate { plural ->
            val items = plural.getElementsByTagName("item")
            plural.getAttribute("name") to (0 until items.length).associate { index ->
                val item = items.item(index) as Element
                item.getAttribute("quantity") to item.textContent
            }
        }
        val days = plurals.getValue("time_days")
        val hours = plurals.getValue("time_hours")
        assertEquals("1 day", String.format(Locale.US, days.getValue("one"), 1L))
        assertEquals("0 days", String.format(Locale.US, days.getValue("other"), 0L))
        assertEquals("5 days", String.format(Locale.US, days.getValue("other"), 5L))
        assertEquals("1 hour", String.format(Locale.US, hours.getValue("one"), 1))
        assertEquals("0 hours", String.format(Locale.US, hours.getValue("other"), 0))
        assertEquals("14 hours", String.format(Locale.US, hours.getValue("other"), 14))
        val format = resources().single { it.getAttribute("name") == "time_days_hours" }.textContent
        assertEquals("0 days 1 hour", String.format(Locale.US, format, "0 days", "1 hour"))
    }
}
