package io.github.leugenea.codexbarmobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.util.Locale

/** Native component tests, not production navigation or real-provider evidence. */
@RunWith(AndroidJUnit4::class)
class HistoryTextComponentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var renderedDensity = 0f
    private var renderedFont = 0f
    private var renderedOrientation = Configuration.ORIENTATION_UNDEFINED
    private var renderedLocale = ""

    @Test fun independentKindsExposeExactMeasuredAndObservedOnlyDelta() {
        launch(HistoryTextFixture.plot()).use {
            text("Five-hour window", 1)
            text("Weekly window", 1)
            text("The nominal baseline assumes evenly distributed full quota from reset minus window duration to reset. It is analytical, not measured usage or a prediction of exhaustion.")
            openDetails()
            text("Measured used percent (exact %): 12.345678901234567890")
            text("Measured used percent (exact %): 87.50")
            text("Observed-only delta (percentage points): -67.655006969815432110")
            text("Measurement observed at (exact UTC): 2026-10-09T00:00:00.123456789Z", 2)
            text("Provider allowed: false")
            text("Provider limit reached: true")
            assertNoChatterOrMergedDescription()
        }
    }

    @Test fun readinessContentErrorsLossAndTruncationRemainIndependent() {
        launch(HistoryTextFixture.plot()).use { scenario ->
            for (state in HistoryReadiness.entries) {
                render(scenario, HistoryTextFixture.plot(readiness = state, loss = 7, more = true,
                    problem = HistoryRecorderProblem.WRITE_FAILURE, reason = HistoryUnavailable.IO_FAILURE,
                    truncation = setOf(HistoryTruncation.BYTE_CAP)))
                compose.onNodeWithTag("history-readiness").assertTextEquals(context.getString(historyStateResource(state)))
                text("7 samples lost or unconfirmed; history is incomplete.")
                text("History recorder problem: write failure")
                text("Retention truncated history: byte cap")
                compose.onNodeWithTag("history-more").assertExists()
            }
            render(scenario, HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.LOADING)))
            compose.onNodeWithTag("history-five-hour-content").assertTextEquals("No history page available; usage is not zero")
            render(scenario, HistoryTextFixture.plot(emptyList()))
            compose.onNodeWithTag("history-five-hour-content").assertTextEquals("No entries in this admitted page")
            render(scenario, HistoryTextFixture.plot(query = HistoryGraphQuery(kind = WindowKind.WEEKLY)))
            compose.onNodeWithTag("history-five-hour-content").assertTextEquals("No entries match this window selection")
            render(scenario, HistoryTextFixture.plot(listOf(HistoryTextFixture.measured(weekly = false))))
            compose.onNodeWithTag("history-weekly-content").assertTextEquals("Status or gap entries only; no timestamped measurements")
            render(scenario, HistoryTextFixture.plot(HistoryTextFixture.correctionPage()))
            compose.onNodeWithTag("history-five-hour-content").assertTextEquals("Sparse observations; no continuous sampling")
        }
    }

    @Test fun collapsedDetailsReachEveryGapAndResetBreakWithoutOmission() {
        val first = HistoryTextFixture.measured()
        val gap = HistoryTextFixture.second(HistoryTextFixture.gap())
        launch(HistoryTextFixture.plot(listOf(first, gap))).use {
            compose.onNodeWithTag("history-detail").assertDoesNotExist()
            openDetails()
            text("Segment break: first retained segment observation", 2)
            text("Reset cause: unknown", 2)
            click("history-next")
            text("Admission ordinal: 2")
            text("Gap reason (unknown means no recorded reason): background")
            text("Source observed at (exact UTC): unknown · not zero")
            compose.onNodeWithTag("history-next").assertIsNotEnabled()
            click("history-previous")
            text("Admission ordinal: 1")
            compose.onNodeWithTag("history-previous").assertIsNotEnabled()
            click("history-details-toggle")
            compose.onNodeWithTag("history-detail").assertDoesNotExist()
        }
        launch(HistoryTextFixture.plot(HistoryTextFixture.correctionPage())).use {
            openDetails()
            click("history-next")
            text("Measured used percent (exact %): 3.00")
            text("Segment break: used-percent correction; cause unknown")
            text("Baseline and delta unavailable: uncertain correction")
            text("Reset cause: unknown")
        }
    }

    @Test fun unknownBaselineKeepsMeasuredValueAndB1ResetContext() {
        launch(HistoryTextFixture.plot(listOf(HistoryTextFixture.measured(reset = false)))).use {
            openDetails()
            text("Measured used percent (exact %): 12.345678901234567890")
            text("Baseline and delta unavailable: unkeyed reset", 2)
            text("Reset: Time unavailable", 2)
            val delta = hasAnyAncestor(hasTestTag("history-detail")) and hasText("Observed-only delta", substring = true)
            compose.onAllNodes(delta, useUnmergedTree = true).assertCountEquals(0)
        }
        launch(HistoryTextFixture.plot()).use {
            openDetails()
            text("Reset: Oct 9, 2026, 01:00 (hour precision)", 2)
            text("Reset: <1h remaining", 2)
            text("Absolute reset source (exact UTC): 2026-10-09T01:00:00Z", 2)
        }
    }

    @Test fun longExactDecimalIsChunkedWithOneAccessibleOwnerPerPart() {
        val decimal = "0." + "1234567890".repeat(45)
        launch(HistoryTextFixture.plot(listOf(HistoryTextFixture.measured(decimal, weekly = false, reset = false)))).use {
            openDetails()
            val exactChunks = HistoryTextPresentation.chunks(decimal)
            val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag("history-detail")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            val values = nodes.flatMap { it.config[SemanticsProperties.Text].map { value -> value.text } }
            assertEquals(decimal, values.filter { it in exactChunks }.joinToString(""))
            exactChunks.toSet().forEach { part -> text(part, exactChunks.count { it == part }) }
            assertTrue(values.all { it.length <= 240 })
            assertNoChatterOrMergedDescription()
        }
    }

    @Test fun portraitLightMeasuredScreenshotHasReachableUnclippedLabels() {
        launch(HistoryTextFixture.plot()).use {
            verifyLayout("history-title", "history-legend", "history-five-hour-content", "history-weekly-content")
            capture("history-measured-portrait-light", dark = false)
            openDetails()
            val value = compose.onNodeWithText("Measured used percent (exact %): 12.345678901234567890")
            value.performScrollTo().assertIsDisplayed()
            assertLayout(value)
            capture("history-exact-measurement-portrait-light", dark = false)
        }
    }

    @Test fun landscapeDarkGappedScreenshotHasReachableControls() {
        launch(HistoryTextFixture.plot(listOf(HistoryTextFixture.gap())), landscape = true, dark = true).use {
            verifyLayout("history-title", "history-legend", "history-five-hour-content", "history-assumption")
            openDetails()
            compose.onNodeWithText("Gap reason (unknown means no recorded reason): background").performScrollTo().assertIsDisplayed()
            capture("history-gap-landscape-dark", dark = true)
        }
    }

    @Test fun largeFontUnknownScreenshotKeepsCompleteNativeTextAndControls() {
        launch(HistoryTextFixture.plot(listOf(HistoryTextFixture.measured(reset = false))), font = 2f).use {
            verifyLayout("history-title", "history-legend", "history-five-hour-content", "history-assumption")
            openDetails()
            compose.onAllNodesWithText("Baseline and delta unavailable: unkeyed reset").onFirst().performScrollTo()
                .also(::assertLayout)
            capture("history-unknown-large-font", dark = false)
            compose.onNodeWithTag("history-previous").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("history-next").performScrollTo().assertIsDisplayed()
            assertEquals(2f, renderedFont)
        }
    }

    private fun launch(plot: HistoryPlotSnapshot, landscape: Boolean = false, dark: Boolean = false,
        font: Float = 1f): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        try {
            compose.waitUntil(10_000) {
                var orientation = 0
                scenario.onActivity { orientation = it.resources.configuration.orientation }
                orientation == expected
            }
            render(scenario, plot, dark, font)
        } catch (error: Throwable) { scenario.close(); throw AssertionError("History fixture orientation/render readiness", error) }
        return scenario
    }

    private fun render(scenario: ActivityScenario<MainActivity>, plot: HistoryPlotSnapshot, dark: Boolean = false, font: Float = 1f) {
        scenario.onActivity { activity -> activity.setContent {
            val density = LocalDensity.current
            val configuration = LocalConfiguration.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                val effectiveDensity = LocalDensity.current
                SideEffect {
                    renderedDensity = effectiveDensity.density
                    renderedFont = effectiveDensity.fontScale
                    renderedOrientation = configuration.orientation
                    renderedLocale = configuration.locales[0].toLanguageTag()
                }
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize().testTag("history-fixture")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            HistoryTextComponent(plot, HistoryTextFixture.at, ZoneId.of("UTC"), Locale.US)
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("history-text").assertExists()
    }

    private fun text(value: String, count: Int = 1) {
        val scope = hasTestTag("history-text") or hasAnyAncestor(hasTestTag("history-text"))
        compose.onAllNodes(scope and hasText(value), useUnmergedTree = true).assertCountEquals(count)
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle() }
    private fun openDetails() = click("history-details-toggle")
    private fun verifyLayout(vararg tags: String) { tags.forEach { assertLayout(compose.onNodeWithTag(it).performScrollTo()) } }

    private fun assertLayout(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Native text layout required", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertTrue("Text overflow: ${layout.layoutInput.text}", !layout.didOverflowHeight && layout.lineCount > 0)
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(layout.getLineLeft(line) >= 0 && layout.getLineRight(line) <= layout.size.width)
            }
        }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("history-fixture").fetchSemanticsNode().boundsInRoot
        assertTrue("Visible text must fit root horizontally", bounds.left >= root.left && bounds.right <= root.right)
    }

    private fun assertNoChatterOrMergedDescription() {
        val scope = hasTestTag("history-text") or hasAnyAncestor(hasTestTag("history-text"))
        for (key in listOf(SemanticsProperties.LiveRegion, SemanticsProperties.ContentDescription)) {
            compose.onAllNodes(scope and SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private fun capture(name: String, dark: Boolean) {
        val directory = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "AGP additionalTestOutputDir is required for hosted screenshot collection"
        }, "history-text")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onNodeWithTag("history-fixture").captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        val metadata = JSONObject().put("synthetic", true).put("name", name).put("api", Build.VERSION.SDK_INT)
            .put("width", bitmap.width).put("height", bitmap.height).put("density", renderedDensity)
            .put("fontScale", renderedFont).put("orientation", renderedOrientation).put("dark", dark)
            .put("renderedLocale", renderedLocale).put("scope", "native textual component; no live provider or production navigation")
        File(directory, "$name.json").writeText(metadata.toString(2))
        bitmap.recycle()
    }
}
