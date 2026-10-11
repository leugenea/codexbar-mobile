package io.github.leugenea.codexbarmobile

import android.Manifest
import android.app.AlarmManager
import android.app.LocaleManager
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** Hosted production-entry matrix; real system zone broadcasts and effective app resource locales.
 * The extra fixture observer keeps the established owner foreground across configuration recreation,
 * so formatting can be tested independently of B2's intentional resume-read policy.
 */
@RequiresApi(33)
internal class PhoneTimeEvidence(private val compose: ComposeTestRule, private val fixture: HistoryNavigationFixture) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val locales = context.getSystemService(LocaleManager::class.java)
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val capture = HistoryAcceptanceCapture(compose)

    fun run(history: Boolean) {
        val originalLocales = locales.applicationLocales
        val originalZone = TimeZone.getDefault()
        try {
            locales.applicationLocales = LocaleList(Locale.US)
            setZone("UTC")
            fixture.transport.advance(123)
            // The evidenced wire grammar accepts up to six fractional digits; keep that source exact.
            fixture.transport.inventoryBody = """{"available_count":1,"credits":[{"reset_type":"codex_rate_limits","status":"available","expires_at":"2027-01-15T09:00:00.123456Z"}]}"""
            fixture.owner.usageForeground(fixture.observer, true)
            settleConnectionCommands(compose, fixture.owner, "timestamp fixture foreground receipt")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitConnectionScreen(compose, fixture.owner)
                compose.onNodeWithTag("connect").performScrollTo().performClick()
                await("original endpoint cycle and history admission settled") {
                    fixture.owner.state.value.phase == ConnectionPhase.OBSERVED && !fixture.owner.state.value.refresh.refreshing &&
                        fixture.owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L
                }
                val usage = fixture.owner.state.value.refresh.usage.success
                val inventory = fixture.owner.state.value.refresh.inventory.success
                val entry = fixture.owner.historySnapshots.value.storage!!.entries.single()
                assertEquals("source receipt precision", Instant.parse("2027-01-15T08:00:00.123Z"), entry.observedAt)
                assertEquals("expiry source precision", Instant.parse("2027-01-15T09:00:00.123456Z"),
                    inventory!!.inventory!!.items.single().value!!.expiresAt.value)
                val routes = fixture.transport.counts()
                ready(scenario, history, Locale.US)
                // API36 en-US MEDIUM clocks use U+202F before the day period (unlike the JDK oracle).
                labels(history, "Jan 15, 2027, 8:00:00\u202fAM", "Jan 15, 2027, 09:00 (hour precision)", "Jan 15, 2027, 9:00:00\u202fAM", "Jan 15, 2027, 4:00:00\u202fAM")
                var activity: MainActivity? = null
                scenario.onActivity { activity = it }
                setZone("America/Los_Angeles")
                await("actual system zone broadcast updates visible labels") { ZoneId.systemDefault().id == "America/Los_Angeles" }
                labels(history, "Jan 15, 2027, 12:00:00\u202fAM", "Jan 15, 2027, 01:00 (hour precision)", "Jan 15, 2027, 1:00:00\u202fAM", "Jan 14, 2027, 8:00:00\u202fPM")
                scenario.onActivity { assertSame("zone-only update keeps visible Activity", activity, it) }
                assertSame("formatting preserves accepted usage source", usage, fixture.owner.state.value.refresh.usage.success)
                assertSame("formatting preserves accepted inventory source", inventory, fixture.owner.state.value.refresh.inventory.success)
                assertEquals("zone formatting cannot request transport", routes, fixture.transport.counts())
                setZone("Europe/Berlin")
                locales.applicationLocales = LocaleList(Locale.GERMANY)
                ready(scenario, history, Locale.GERMANY)
                labels(history, "15.01.2027, 09:00:00", "15.01.2027, 10:00 (hour precision)", "15.01.2027, 10:00:00", "15.01.2027, 05:00:00")
                scenario.recreate()
                ready(scenario, history, Locale.GERMANY)
                labels(history, "15.01.2027, 09:00:00", "15.01.2027, 10:00 (hour precision)", "15.01.2027, 10:00:00", "15.01.2027, 05:00:00")
                settleConnectionCommands(compose, fixture.owner, "timestamp configuration/recreation owner receipt")
                assertSame("recreation keeps original usage facts", usage, fixture.owner.state.value.refresh.usage.success)
                assertSame("recreation keeps original inventory facts", inventory, fixture.owner.state.value.refresh.inventory.success)
                assertEquals("local formatting/configuration/recreation adds no provider requests", routes, fixture.transport.counts())
                assertEquals("no formatting admissions", listOf(entry.id), fixture.owner.historySnapshots.value.storage!!.entries.map { it.id })
                noRawTime(if (history) "connection-history" else "live-usage")
            }
        } finally {
            try { locales.applicationLocales = originalLocales }
            finally {
                try { setZone(originalZone.id) }
                finally { TimeZone.setDefault(originalZone) }
            }
        }
    }

    private fun setZone(id: String) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.SET_TIME_ZONE)
        try { alarm.setTimeZone(id) }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        // The framework also invalidates its cache on the broadcast; discard test-suite overrides.
        TimeZone.setDefault(null)
    }

    private fun ready(scenario: ActivityScenario<MainActivity>, history: Boolean, locale: Locale) {
        await("effective rendering resource locale $locale") {
            var effective: Locale? = null
            scenario.onActivity { effective = it.resources.configuration.locales[0] }
            effective == locale
        }
        awaitConnectionScreen(compose, fixture.owner)
        if (!history) return
        if (compose.onAllNodesWithTag("connection-history").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("history-toggle").performScrollTo().performClick()
        }
        await("current history page after configuration") {
            fixture.owner.historySnapshots.value.storage?.entries?.size == 1 &&
                fixture.owner.historySnapshots.value.query.kind == io.github.leugenea.codexbarmobile.usage.WindowKind.FIVE_HOUR
        }
        await("history detail control composed after current page") {
            compose.onAllNodesWithTag("history-details-toggle").fetchSemanticsNodes().size == 1
        }
        if (compose.onAllNodesWithTag("history-detail", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("history-details-toggle").performScrollTo().performClick()
        }
    }

    private fun labels(history: Boolean, observed: String, reset: String, resetSource: String, nominalStart: String) {
        if (history) {
            fact("Source observed at (second precision): $observed")
            fact("Measurement observed at (second precision): $observed")
            val endpoint = "Last successful source observation (second precision): $observed"
            compose.onAllNodes(scope("connection-history") and hasText(endpoint), useUnmergedTree = true).assertCountEquals(2)
            fact("Latest attempt observed at (second precision): $observed", count = 2)
            fact("Absolute reset source (second precision): $resetSource")
            fact("Nominal assumption start (second precision): $nominalStart")
            fact("Nominal assumption end (reset, second precision): $resetSource")
            fact("Reset: $reset")
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        } else {
            label("live-five-hour-reset-absolute", "Reset: $reset")
            label("banked-summary-clock", "Summary observed at (second precision): $observed")
            label("banked-inventory-clock", "Inventory observed at (second precision): $observed")
            compose.onNodeWithTag("banked-item-0-absolute", useUnmergedTree = true).performScrollTo()
                .assertTextEquals("Banked entitlement expiry: $reset")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            val accessible = !SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
            compose.onAllNodes(scope("banked-section") and hasContentDescription("Banked entitlement expiry: $reset", substring = true) and accessible,
                useUnmergedTree = true).assertCountEquals(1)
        }
    }

    private fun label(tag: String, value: String) {
        await("$tag updates to $value") {
            compose.onAllNodes(hasTestTag(tag) and hasText(value), useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        capture.layout(compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertTextEquals(value))
    }

    private fun fact(value: String, count: Int = 1) {
        val matcher = scope("connection-history") and hasText(value)
        await("history fact updates to $value (owners=$count)") {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == count
        }
        compose.onAllNodes(matcher, useUnmergedTree = true).assertCountEquals(count)
        capture.layout(compose.onAllNodes(matcher, useUnmergedTree = true)[0].performScrollTo())
    }

    private fun noRawTime(tag: String) {
        val raw = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:|1800000000|1800003600")
        val matcher = SemanticsMatcher("raw absolute clock in text/accessibility") { node ->
            val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
            val description = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
            val state = node.config.getOrElse(SemanticsProperties.StateDescription) { "" }
            raw.containsMatchIn("$text $description $state")
        }
        compose.onAllNodes(scope(tag) and matcher, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun scope(tag: String) = hasAnyAncestor(hasTestTag(tag))

    private fun await(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 8_000, condition = condition) }
        catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: last zone=${ZoneId.systemDefault()}, phase=${fixture.owner.state.value.phase}, " +
                "refreshing=${fixture.owner.state.value.refresh.refreshing}, readiness=${fixture.owner.historySnapshots.value.readiness}", error)
        }
    }
}
