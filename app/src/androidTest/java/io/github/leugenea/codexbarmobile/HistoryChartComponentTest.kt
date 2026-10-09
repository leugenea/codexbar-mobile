package io.github.leugenea.codexbarmobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual production Canvas/semantics/layout/pixels; synthetic data and hosted-only execution. */
@RunWith(AndroidJUnit4::class)
class HistoryChartComponentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var density = 0f
    private var fontScale = 0f
    private var orientation = 0
    private var renderedLocale = ""
    private var measured = Color.Unspecified
    private var nominal = Color.Unspecified
    private val five = "history-chart-five-hour"

    @Test fun singleMeasuredMarkerAndDashedNominalAreSeparateActualPixels() {
        val plot = HistoryTextFixture.plot()
        launch(plot).use {
            counts("Drawn markers: 1 · Within-run connections: 0 · Nominal reference lines: 1")
            compose.onNodeWithTag("history-chart-weekly-counts").assertTextEquals(
                "Drawn markers: 1 · Within-run connections: 0 · Nominal reference lines: 1")
            verifyLabels("$five-title", "$five-legend", "$five-first", "$five-last")
            compose.onNodeWithTag("$five-first").assertTextEquals("Time axis start: Oct 8, 2026, 20:00 (hour precision)")
            compose.onNodeWithTag("$five-last").assertTextEquals("Time axis end: Oct 9, 2026, 01:00 (hour precision)")
            withCanvas { bitmap ->
                markerPixels(bitmap, plot.fiveHour)
                // Independent sampled diagonal: a solid reference fails the required gaps.
                val hits = (15..70).map { n -> pixelMatches(bitmap, n / 100.0, n / 100.0, nominal) }
                assertTrue("Dashed reference needs actual painted pixels", hits.count { it } >= 10)
                assertTrue("Dashed reference needs actual unpainted gaps", hits.count { !it } >= 10)
            }
            withCanvas("history-chart-weekly") { bitmap -> markerPixels(bitmap, plot.weekly) }
            verifyLabels("history-chart-weekly-title", "history-chart-weekly-first", "history-chart-weekly-last")
            compose.onNodeWithTag("history-chart-weekly-first").assertTextEquals(
                "Time axis start: Oct 2, 2026, 01:00 (hour precision)")
            capture("history-chart-reference-portrait-light", plot, dark = false)
        }
    }

    @Test fun connectedMeasurementsDrawStraightEdgesAndPercentAxisInDarkPortrait() {
        val plot = HistoryTextFixture.plot(HistoryChartFixture.page())
        launch(plot, dark = true).use {
            counts("Drawn markers: 4 · Within-run connections: 3 · Nominal reference lines: 0")
            verifyLabels("$five-title", "$five-legend", "$five-counts", "$five-first", "$five-last")
            val scope = hasAnyAncestor(hasTestTag(five))
            for (value in listOf("100%", "50%", "0%")) compose.onAllNodes(scope and hasText(value)).assertCountEquals(1)
            withCanvas { bitmap ->
                markerPixels(bitmap, plot.fiveHour)
                edgePixels(bitmap, plot.fiveHour)
            }
            capture("history-chart-measured-portrait-dark", plot, dark = true)
        }
    }

    @Test fun gapsCorrectionsAndResetChangesHaveNoPaintedBridgeInLandscapeDark() {
        launch(HistoryTextFixture.plot(), landscape = true, dark = true).use { scenario ->
            val pages = listOf(HistoryChartFixture.page(gap = true), HistoryChartFixture.page(correction = true),
                HistoryChartFixture.page(changedReset = true))
            for (entries in pages) {
                val plot = HistoryTextFixture.plot(entries)
                render(scenario, plot, dark = true)
                counts("Drawn markers: 4 · Within-run connections: 2 · Nominal reference lines: 0")
                withCanvas { bitmap ->
                    markerPixels(bitmap, plot.fiveHour)
                    edgePixels(bitmap, plot.fiveHour)
                    val a = plot.fiveHour.segments[0].points.last().position as PlotPosition.Available
                    val b = plot.fiveHour.segments[1].points.first().position as PlotPosition.Available
                    assertFalse("No measured bridge across a supplied run boundary",
                        pixelMatches(bitmap, (a.x + b.x) / 2, (a.y + b.y) / 2, measured))
                }
            }
            val gapped = HistoryTextFixture.plot(pages.first())
            render(scenario, gapped, dark = true)
            withCanvas { markerPixels(it, gapped.fiveHour); edgePixels(it, gapped.fiveHour) }
            capture("history-chart-gap-landscape-dark", gapped, dark = true)
            click("history-details-toggle")
            click("history-next")
            click("history-next")
            compose.onNodeWithText("Gap reason (unknown means no recorded reason): background").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun emptyStatusOnlyLoadingAndErrorsNeverInventACanvasOrZero() {
        launch(HistoryTextFixture.plot(emptyList())).use { scenario ->
            val sources = listOf(HistoryTextFixture.plot(emptyList()), HistoryTextFixture.plot(listOf(HistoryTextFixture.gap())),
                HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.LOADING)),
                HistoryPlotInputs.project(HistoryGraphSnapshot(HistoryReadiness.ERROR, problem = HistoryRecorderProblem.READ_FAILURE)))
            for (plot in sources) {
                render(scenario, plot)
                compose.onNodeWithTag("$five-canvas").assertDoesNotExist()
                compose.onNodeWithTag("$five-no-geometry").assertExists()
                counts("Drawn markers: 0 · Within-run connections: 0 · Nominal reference lines: 0")
                compose.onNodeWithTag("history-readiness").assertTextEquals(
                    InstrumentationRegistry.getInstrumentation().targetContext.getString(historyStateResource(plot.source.readiness)))
                verifyLabels("$five-content", "$five-no-geometry")
            }
        }
    }

    @Test fun typedUnavailableGeometryAndUnderflowKeepExactFactsInsteadOfClamping() {
        val capacity = HistoryChartFixture.invalidGeometry(BigDecimal("0." + "1".repeat(1025)))
        launch(capacity).use { scenario ->
            counts("Drawn markers: 0 · Within-run connections: 0 · Nominal reference lines: 0")
            verifyLabels("$five-geometry-DECIMAL_CAPACITY")
            withCanvas { bitmap -> assertFalse(pixelMatches(bitmap, 0.5, 0.0, measured)) }
            capture("history-chart-invalid-portrait-light", capacity, dark = false)
            render(scenario, HistoryChartFixture.invalidGeometry(BigDecimal("101")))
            verifyLabels("$five-geometry-OUTSIDE_VALUE_DOMAIN")
            counts("Drawn markers: 0 · Within-run connections: 0 · Nominal reference lines: 0")
            click("history-details-toggle")
            compose.onNodeWithText("Measured used percent (exact %): 101").performScrollTo().assertIsDisplayed()
            val tiny = HistoryChartFixture.invalidGeometry(BigDecimal("1E-10000"))
            render(scenario, tiny)
            verifyLabels("$five-underflow")
            withCanvas { markerPixels(it, tiny.fiveHour) }
            click("history-details-toggle")
            compose.onNodeWithText("Measured used percent (exact %): 1E-10000").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun unknownBaselineLargeFontKeepsSinglePointCompleteLabelsAndReachableDetails() {
        val plot = HistoryTextFixture.plot(listOf(HistoryTextFixture.measured(reset = false)))
        launch(plot, font = 2f).use {
            counts("Drawn markers: 1 · Within-run connections: 0 · Nominal reference lines: 0")
            verifyLabels("$five-title", "$five-legend", "$five-counts", "$five-no-baseline", "$five-first", "$five-last")
            withCanvas { markerPixels(it, plot.fiveHour) }
            capture("history-chart-unknown-large-font", plot, dark = false)
            click("history-details-toggle")
            compose.onNodeWithText("Measured used percent (exact %): 12.345678901234567890").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Baseline and delta unavailable: unkeyed reset").onFirst().performScrollTo().also(::assertLayout)
            compose.onNodeWithTag("history-previous").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("history-next").performScrollTo().assertIsDisplayed()
            assertEquals(2f, fontScale)
        }
    }

    @Test fun endpointMarkersAreFullyInsetAndVisibleInLandscapeLight() {
        val plot = HistoryTextFixture.plot(HistoryChartFixture.page(endpoints = true))
        launch(plot, landscape = true).use {
            verifyLabels("$five-title", "$five-legend", "$five-first", "$five-last")
            withCanvas { bitmap ->
                markerPixels(bitmap, plot.fiveHour)
                edgePixels(bitmap, plot.fiveHour)
                val inset = 8 * density
                for (point in plot.fiveHour.segments.flatMap { it.points }) {
                    val position = point.position as PlotPosition.Available
                    val x = (inset + position.x * (bitmap.width - 2 * inset)).roundToInt()
                    val y = (inset + (1 - position.y) * (bitmap.height - 2 * inset)).roundToInt()
                    assertTrue(x - 4 * density >= 0 && x + 4 * density < bitmap.width)
                    assertTrue(y - 4 * density >= 0 && y + 4 * density < bitmap.height)
                    for (side in listOf(-2, 2)) {
                        assertTrue(colorMatches(bitmap.getPixel(x + (side * density).roundToInt(), y), measured))
                    }
                }
            }
            capture("history-chart-measured-landscape-light", plot, dark = false)
        }
    }

    @Test fun accessibleTextRemainsTheOnlyExactFactOwnerWithoutCountdownChatter() {
        launch(HistoryTextFixture.plot()).use {
            compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            click("history-details-toggle")
            val scope = hasTestTag("history-charts") or hasAnyAncestor(hasTestTag("history-charts"))
            compose.onAllNodes(scope and hasText("Measured used percent (exact %): 12.345678901234567890"),
                useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodes(scope and hasText("Observed-only delta (percentage points): -67.655006969815432110"),
                useUnmergedTree = true).assertCountEquals(1)
            for (key in listOf(SemanticsProperties.LiveRegion, SemanticsProperties.ContentDescription)) {
                compose.onAllNodes(scope and SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true).assertCountEquals(0)
            }
            compose.onAllNodesWithText("Reset: Oct 9, 2026, 01:00 (hour precision)").assertCountEquals(2)
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
                var actual = 0
                scenario.onActivity { actual = it.resources.configuration.orientation }
                actual == expected
            }
            render(scenario, plot, dark, font)
        } catch (error: Throwable) { scenario.close(); throw AssertionError("Chart fixture orientation/render readiness", error) }
        return scenario
    }

    private fun render(scenario: ActivityScenario<MainActivity>, plot: HistoryPlotSnapshot, dark: Boolean = false, font: Float = 1f) {
        scenario.onActivity { activity -> activity.setContent {
            val sourceDensity = LocalDensity.current
            val configuration = LocalConfiguration.current
            CompositionLocalProvider(LocalDensity provides Density(sourceDensity.density, font)) {
                val effective = LocalDensity.current
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect {
                        density = effective.density
                        fontScale = effective.fontScale
                        orientation = configuration.orientation
                        renderedLocale = configuration.locales[0].toLanguageTag()
                        measured = colors.primary
                        nominal = colors.onSurfaceVariant
                    }
                    Surface(Modifier.fillMaxSize().testTag("history-chart-fixture")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            HistoryChartComponent(plot, HistoryChartFixture.at, ZoneId.of("UTC"), Locale.US)
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("history-charts").assertExists()
    }

    private fun counts(text: String) = compose.onNodeWithTag("$five-counts").assertTextEquals(text)
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle() }
    private fun verifyLabels(vararg tags: String) { tags.forEach { assertLayout(compose.onNodeWithTag(it).performScrollTo()) } }

    private fun assertLayout(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Native text layout required", results.isNotEmpty())
        for (layout in results) {
            assertFalse("No height overflow", layout.didOverflowHeight)
            assertTrue(layout.lineCount > 0)
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(layout.getLineLeft(line) >= 0 && layout.getLineRight(line) <= layout.size.width)
            }
        }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("history-chart-fixture").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right)
    }

    private fun withCanvas(tag: String = five, oracle: (Bitmap) -> Unit) {
        val node = compose.onNodeWithTag("$tag-canvas", useUnmergedTree = true).performScrollTo()
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("history-chart-fixture").fetchSemanticsNode().boundsInRoot
        assertTrue("Entire Canvas must be root-contained", bounds.left >= root.left && bounds.top >= root.top &&
            bounds.right <= root.right && bounds.bottom <= root.bottom)
        val bitmap = node.captureToImage().asAndroidBitmap()
        try { oracle(bitmap) } finally { bitmap.recycle() }
    }

    private fun markerPixels(bitmap: Bitmap, series: HistoryPlotSeries) {
        val points = series.segments.flatMap { it.points }.map { it.position as PlotPosition.Available }
        assertTrue("Positive marker evidence required", points.isNotEmpty())
        points.forEach { point -> assertTrue("Actual measured marker at $point", pixelMatches(bitmap, point.x, point.y, measured)) }
    }

    private fun edgePixels(bitmap: Bitmap, series: HistoryPlotSeries) {
        val edges = series.segments.flatMap { it.points.zipWithNext() }
        assertTrue("Positive straight-edge evidence required", edges.isNotEmpty())
        for ((a, b) in edges) {
            val one = a.position as PlotPosition.Available
            val two = b.position as PlotPosition.Available
            for (fraction in listOf(0.25, 0.5, 0.75)) {
                assertTrue("Actual straight measured connection", pixelMatches(bitmap,
                    one.x + (two.x - one.x) * fraction, one.y + (two.y - one.y) * fraction, measured))
            }
        }
    }

    private fun pixelMatches(bitmap: Bitmap, x: Double, y: Double, expected: Color): Boolean {
        // Independently register Canvas-local expected fractions with observed native density/size.
        val inset = 8 * density
        val px = (inset + x * (bitmap.width - 2 * inset)).roundToInt()
        val py = (inset + (1 - y) * (bitmap.height - 2 * inset)).roundToInt()
        return colorMatches(bitmap.getPixel(px, py), expected)
    }

    private fun colorMatches(actual: Int, expected: Color): Boolean {
        val target = expected.toArgb()
        return listOf(0, 8, 16).all { shift -> abs(((actual shr shift) and 255) - ((target shr shift) and 255)) <= 12 }
    }

    private fun capture(name: String, plot: HistoryPlotSnapshot, dark: Boolean) {
        // Pixels sampled above came from the actual Canvas, never from a synthetic bitmap.
        val canvas = compose.onNodeWithTag("$five-canvas", useUnmergedTree = true).performScrollTo().fetchSemanticsNode().boundsInRoot
        val directory = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "history-chart")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onNodeWithTag("history-chart-fixture").captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val drawing = historyChartDrawing(plot.fiveHour)
            val metadata = JSONObject().put("synthetic", true).put("name", name).put("api", Build.VERSION.SDK_INT)
                .put("width", bitmap.width).put("height", bitmap.height).put("density", density).put("fontScale", fontScale)
                .put("orientation", orientation).put("dark", dark).put("renderedLocale", renderedLocale)
                .put("scope", "native chart component with accessible text; no live provider or production navigation")
                .put("markerCount", drawing.markers.size).put("connectionCount", drawing.measured.size).put("referenceCount", drawing.nominal.size)
                .put("canvasLeft", canvas.left).put("canvasTop", canvas.top).put("canvasRight", canvas.right).put("canvasBottom", canvas.bottom)
                .put("pixelOraclePassed", true)
            File(directory, "$name.json").writeText(metadata.toString(2))
        } finally { bitmap.recycle() }
    }
}
