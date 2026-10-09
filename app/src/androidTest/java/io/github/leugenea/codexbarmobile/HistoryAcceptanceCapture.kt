package io.github.leugenea.codexbarmobile

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.history.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual MainActivity root PNGs and independently clipped geometry, never a replacement composition. */
internal class HistoryAcceptanceCapture(private val compose: ComposeTestRule) {
    private val canvasTag = "history-chart-five-hour-canvas"

    fun label(tag: String) = layout(compose.onNodeWithTag(tag).performScrollTo())

    fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Native text layout required", layouts.isNotEmpty())
        layouts.forEach(::complete)
        contained(node.fetchSemanticsNode(), compose.onRoot().fetchSemanticsNode().boundsInRoot)
        return layouts.single()
    }

    private fun complete(layout: TextLayoutResult) {
        assertFalse("No vertical overflow", layout.didOverflowHeight)
        assertTrue(layout.lineCount > 0)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= 0 && layout.getLineRight(line) <= layout.size.width)
        }
    }

    private fun contained(node: androidx.compose.ui.semantics.SemanticsNode, root: Rect): Rect {
        // No scale/rotation exists on these production history nodes. Do not reuse
        // boundsInRoot as the full rectangle: it may already be scroll-clipped.
        val full = Rect(node.positionInRoot, node.size.toSize())
        assertTrue("Full native bounds root-contained: $full in $root", full.left >= root.left && full.top >= root.top &&
            full.right <= root.right && full.bottom <= root.bottom)
        assertEquals("Full bounds must match independently clipped native bounds", full, node.boundsInRoot)
        return full
    }

    fun capture(name: String, scenario: ActivityScenario<MainActivity>, fixture: HistoryNavigationFixture,
        visible: List<String>, chart: Boolean = false) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        visible.forEach { tag ->
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode()
            assertFalse(node.config.contains(SemanticsProperties.HideFromAccessibility))
            contained(node, root)
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val data = metadata(name, scenario, fixture, bitmap, visible)
            if (chart) chart(data, bitmap, fixture)
            save(name, bitmap, data)
        } finally { bitmap.recycle() }
    }

    private fun metadata(name: String, scenario: ActivityScenario<MainActivity>, fixture: HistoryNavigationFixture,
        bitmap: Bitmap, visible: List<String>): JSONObject {
        val source = fixture.owner.historySnapshots.value
        val entries = source.storage?.entries.orEmpty()
        val data = JSONObject().put("name", name).put("synthetic", true).put("productionEntry", true)
            .put("activity", MainActivity::class.java.name).put("api", Build.VERSION.SDK_INT)
            .put("width", bitmap.width).put("height", bitmap.height).put("visibleControls", JSONArray(visible))
            .put("scope", "production MainActivity, single default owner, isolated real SQLite/Keystore, synthetic transport/admissions; not live provider")
            .put("phase", fixture.owner.state.value.phase.name).put("readiness", source.readiness.name)
            .put("queryKind", source.query.kind?.name ?: "NONE").put("queryLimit", source.query.limit)
            .put("queryAfter", source.query.after?.ordinal ?: 0).put("pageEntries", entries.size)
            .put("firstOrdinal", entries.firstOrNull()?.id?.ordinal ?: 0).put("lastOrdinal", entries.lastOrNull()?.id?.ordinal ?: 0)
            .put("hasMore", source.hasMore).put("requests", fixture.transport.requests.get()).put("gets", fixture.transport.gets.get())
        scenario.onActivity {
            assertSame(fixture.owner, it.connection)
            val config = it.resources.configuration
            data.put("orientation", config.orientation).put("fontScale", config.fontScale)
                .put("density", it.resources.displayMetrics.density).put("renderedLocale", config.locales[0].toLanguageTag())
                .put("dark", config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        }
        val cursor = compose.onNodeWithTag("history-page-cursor")
        val layouts = mutableListOf<TextLayoutResult>()
        cursor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val effective = layouts.single().layoutInput.density
        assertEquals(data.getDouble("fontScale").toFloat(), effective.fontScale)
        assertEquals(data.getDouble("density").toFloat(), effective.density)
        data.put("layoutFontScale", effective.fontScale).put("layoutDensity", effective.density)
            .put("uiCursor", cursor.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
        return data
    }

    private fun chart(data: JSONObject, bitmap: Bitmap, fixture: HistoryNavigationFixture) {
        val node = compose.onNodeWithTag(canvasTag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.HideFromAccessibility))
        val bounds = contained(node, Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
        val clipped = node.boundsInRoot
        listOf("Left" to bounds.left, "Top" to bounds.top, "Right" to bounds.right, "Bottom" to bounds.bottom).forEach {
            data.put("canvas${it.first}", it.second)
        }
        listOf("Left" to clipped.left, "Top" to clipped.top, "Right" to clipped.right, "Bottom" to clipped.bottom).forEach {
            data.put("canvasClipped${it.first}", it.second)
        }
        val series = HistoryPlotInputs.project(fixture.owner.historySnapshots.value).fiveHour
        val drawing = historyChartDrawing(series)
        val colors = if (data.getBoolean("dark")) darkColorScheme() else lightColorScheme()
        val pixels = HistoryAcceptancePixels(bitmap, bounds, data.getDouble("density").toFloat())
        drawing.markers.forEach { pixels.sample("marker", it, colors.primary.toArgb(), true) }
        drawing.measured.forEach { pixels.between("edge", it.start, it.end, colors.primary.toArgb(), true) }
        series.segments.zipWithNext().forEach { (one, two) ->
            pixels.between("break", one.points.last().position as PlotPosition.Available,
                two.points.first().position as PlotPosition.Available, colors.primary.toArgb(), false)
        }
        drawing.nominal.forEach { pixels.dashes(it, colors.onSurfaceVariant.toArgb()) }
        data.put("markerCount", drawing.markers.size).put("connectionCount", drawing.measured.size)
            .put("referenceCount", drawing.nominal.size).put("boundaryCount", (series.segments.size - 1).coerceAtLeast(0))
            .put("pixelSamples", pixels.samples).put("pixelOraclePassed", true)
    }

    private fun save(name: String, bitmap: Bitmap, data: JSONObject) {
        val directory = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "history-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "$name.json").writeText(data.toString(2))
    }
}

/** Samples the very same whole-root bitmap that is saved, with Canvas-local registration. */
internal class HistoryAcceptancePixels(private val bitmap: Bitmap, private val bounds: Rect, private val density: Float) {
    val samples = JSONArray()

    fun sample(kind: String, position: PlotPosition.Available, target: Int, required: Boolean?): Boolean {
        val inset = 8 * density
        val x = (bounds.left + inset + position.x * (bounds.width - 2 * inset)).roundToInt()
        val y = (bounds.top + inset + (1 - position.y) * (bounds.height - 2 * inset)).roundToInt()
        assertTrue(x in 0 until bitmap.width && y in 0 until bitmap.height)
        val actual = bitmap.getPixel(x, y)
        val matched = listOf(0, 8, 16).all { shift -> abs(((actual shr shift) and 255) - ((target shr shift) and 255)) <= 12 }
        samples.put(JSONObject().put("kind", kind).put("x", x).put("y", y).put("targetArgb", target)
            .put("actualArgb", actual).put("matched", matched))
        if (required != null) assertEquals("Actual $kind pixels at ($x,$y)", required, matched)
        return matched
    }

    fun between(kind: String, one: PlotPosition.Available, two: PlotPosition.Available, target: Int, required: Boolean) {
        sample(kind, PlotPosition.Available((one.x + two.x) / 2, (one.y + two.y) / 2, PlotRenderDetail.APPROXIMATE), target, required)
    }

    fun dashes(line: HistoryChartLine, target: Int) {
        val hits = (15..70).map { n ->
            val fraction = n / 100.0
            sample("dash", PlotPosition.Available(line.start.x + (line.end.x - line.start.x) * fraction,
                line.start.y + (line.end.y - line.start.y) * fraction, PlotRenderDetail.APPROXIMATE), target, null)
        }
        assertTrue("Analytical reference has painted pixels", hits.count { it } >= 10)
        assertTrue("Analytical reference has unpainted dash gaps", hits.count { !it } >= 10)
    }
}
