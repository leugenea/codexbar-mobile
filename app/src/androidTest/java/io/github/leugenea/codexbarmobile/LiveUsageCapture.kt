package io.github.leugenea.codexbarmobile

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Samples the real secure Activity's Compose bitmap, retaining the existing test capture seam. */
internal class LiveUsageCapture(private val compose: ComposeTestRule) {
    fun inheritedContent(dark: Boolean) {
        val node = compose.onNodeWithTag("account-name").performScrollTo()
        val layout = HistoryAcceptanceCapture(compose).layout(node)
        val colors = if (dark) darkColorScheme() else lightColorScheme()
        assertEquals("Root Text inherits onBackground", colors.onBackground, layout.layoutInput.style.color)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { glyph(bitmap, fullBounds(node), colors.onBackground.toArgb()) }
        finally { bitmap.recycle() }
    }

    fun capture(name: String, dark: Boolean, scenario: ActivityScenario<MainActivity>, owner: ConnectionController) {
        // Scroll once to this viewport. Reading other facts must not move it between registration and capture.
        compose.onNodeWithTag("live-five-hour").performScrollTo()
        val tags = listOf("live-five-hour-percent", "live-five-hour-reset-absolute", "live-five-hour-reset-relative",
            "live-weekly-percent", "live-weekly-reset-absolute", "live-weekly-reset-relative")
        val layouts = tags.associateWith { HistoryAcceptanceCapture(compose).layout(compose.onNodeWithTag(it)) }
        compose.onNodeWithTag("connection-tab").assertIsDisplayed().assertHasClickAction()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val data = metadata(name, dark, scenario, owner, bitmap, layouts.values.first())
            val facts = JSONObject()
            tags.forEach { facts.put(it, layouts.getValue(it).layoutInput.text.text) }
            data.put("visibleFacts", facts)
            paint(data, bitmap, dark)
            save(name, bitmap, data)
        } finally { bitmap.recycle() }
    }

    private fun metadata(name: String, dark: Boolean, scenario: ActivityScenario<MainActivity>, owner: ConnectionController,
        bitmap: Bitmap, layout: TextLayoutResult): JSONObject {
        val data = JSONObject().put("name", name).put("screen", "live-usage").put("synthetic", true)
            .put("productionEntry", true).put("activity", MainActivity::class.java.name).put("api", Build.VERSION.SDK_INT)
            .put("width", bitmap.width).put("height", bitmap.height).put("phase", owner.state.value.phase.name)
            .put("visibleControls", JSONArray(listOf("connection-tab")))
            .put("scope", "production MainActivity and default process owner, real isolated Keystore, synthetic transport; not live provider")
        scenario.onActivity {
            assertSame(owner, it.connection)
            val config = it.resources.configuration
            val effectiveDark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            assertEquals(dark, effectiveDark)
            data.put("dark", effectiveDark).put("orientation", config.orientation).put("fontScale", config.fontScale)
                .put("density", it.resources.displayMetrics.density).put("renderedLocale", config.locales[0].toLanguageTag())
        }
        val effective = layout.layoutInput.density
        assertEquals(data.getDouble("fontScale").toFloat(), effective.fontScale)
        assertEquals(data.getDouble("density").toFloat(), effective.density)
        return data.put("layoutFontScale", effective.fontScale).put("layoutDensity", effective.density)
    }

    private fun paint(data: JSONObject, bitmap: Bitmap, dark: Boolean) {
        val colors = if (dark) darkColorScheme() else lightColorScheme()
        val bounds = fullBounds(compose.onNodeWithTag("live-five-hour-percent", useUnmergedTree = true))
        val bar = fullBounds(compose.onNodeWithTag("live-five-hour-progress"))
        val density = data.getDouble("density").toFloat()
        val samples = JSONArray()
        samples.put(sample(bitmap, "background", 1, bounds.center.y.roundToInt(), colors.background.toArgb(),
            Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())))
        val gutter = Rect(bounds.left - 16 * density, bounds.top, bounds.left - 4 * density, bounds.bottom)
        samples.put(sample(bitmap, "card", gutter.center.x.roundToInt(), gutter.center.y.roundToInt(),
            colors.surfaceContainerLow.toArgb(), gutter))
        val point = glyph(bitmap, bounds, colors.onSurface.toArgb())
        samples.put(sample(bitmap, "content", point.first, point.second, colors.onSurface.toArgb(), bounds))
        samples.put(sample(bitmap, "progress", (bar.left + bar.width * 0.06f).roundToInt(), bar.center.y.roundToInt(),
            colors.primary.toArgb(), bar))
        samples.put(sample(bitmap, "track", (bar.left + bar.width * 0.6f).roundToInt(), bar.center.y.roundToInt(),
            colors.secondaryContainer.toArgb(), bar))
        data.put("pixelSamples", samples).put("pixelOraclePassed", true)
    }

    private fun fullBounds(node: SemanticsNodeInteraction): Rect {
        val observed = node.assertIsDisplayed().fetchSemanticsNode()
        val full = Rect(observed.positionInRoot, observed.size.toSize())
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("Sample owner must not be scroll-clipped", full, observed.boundsInRoot)
        assertTrue("Sample owner is contained in root", full.left >= root.left && full.top >= root.top &&
            full.right <= root.right && full.bottom <= root.bottom)
        return full
    }

    private fun glyph(bitmap: Bitmap, bounds: Rect, target: Int): Pair<Int, Int> {
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt()) {
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                if (matches(bitmap.getPixel(x, y), target)) return x to y
            }
        }
        throw AssertionError("Actual inherited text paint missing in $bounds for color $target")
    }

    private fun matches(actual: Int, target: Int) = listOf(0, 8, 16).all {
        abs(((actual shr it) and 255) - ((target shr it) and 255)) <= 12
    }

    private fun sample(bitmap: Bitmap, kind: String, x: Int, y: Int, target: Int, bounds: Rect): JSONObject {
        assertTrue(x in 0 until bitmap.width && y in 0 until bitmap.height)
        assertTrue(x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom)
        val actual = bitmap.getPixel(x, y)
        assertTrue("Actual $kind paint at ($x,$y): $actual vs $target", matches(actual, target))
        return JSONObject().put("kind", kind).put("x", x).put("y", y).put("targetArgb", target)
            .put("actualArgb", actual).put("matched", true).put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
    }

    private fun save(name: String, bitmap: Bitmap, data: JSONObject) {
        val directory = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "live-usage")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "$name.json").writeText(data.toString(2))
    }
}
