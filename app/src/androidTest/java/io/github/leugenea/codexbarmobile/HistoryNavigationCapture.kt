package io.github.leugenea.codexbarmobile

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

/** Captures the unmodified production composition after actual UI navigation, not setContent fixtures. */
internal class HistoryNavigationCapture(private val compose: ComposeTestRule) {
    fun assertLabel(tag: String) {
        val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Native label layout: $tag", layouts.isNotEmpty())
        layouts.forEach(::assertComplete)
    }

    private fun assertComplete(layout: TextLayoutResult) {
        assertFalse(layout.didOverflowHeight)
        assertTrue(layout.lineCount > 0)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= 0 && layout.getLineRight(line) <= layout.size.width)
        }
    }

    fun capture(name: String, scenario: androidx.test.core.app.ActivityScenario<MainActivity>, fixture: HistoryNavigationFixture,
        visible: List<String>, canvas: String? = null) {
        visible.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val directory = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "history-navigation")
            assertTrue(directory.isDirectory || directory.mkdirs())
            val metadata = metadata(name, scenario, fixture, bitmap, visible)
            if (canvas != null) addCanvas(metadata, canvas, bitmap)
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(directory, "$name.json").writeText(metadata.toString(2))
        } finally { bitmap.recycle() }
    }

    private fun metadata(name: String, scenario: androidx.test.core.app.ActivityScenario<MainActivity>,
        fixture: HistoryNavigationFixture, bitmap: Bitmap, visible: List<String>): JSONObject {
        val source = fixture.owner.historySnapshots.value
        val metadata = JSONObject().put("synthetic", true).put("name", name).put("api", Build.VERSION.SDK_INT)
            .put("width", bitmap.width).put("height", bitmap.height).put("productionEntry", true)
            .put("activity", MainActivity::class.java.name).put("visibleControls", JSONArray(visible))
            .put("scope", "production MainActivity navigation with synthetic persisted admissions; no live-provider evidence")
            .put("phase", fixture.owner.state.value.phase.name).put("queryLimit", source.query.limit)
            .put("queryAfter", source.query.after?.ordinal ?: 0).put("queryKind", source.query.kind?.name)
            .put("firstOrdinal", source.storage!!.entries.first().id.ordinal)
            .put("lastOrdinal", source.storage.entries.last().id.ordinal)
            .put("pageEntries", source.storage.entries.size).put("hasMore", source.hasMore)
            .put("requests", fixture.transport.requests.get()).put("gets", fixture.transport.gets.get())
        scenario.onActivity {
            val config = it.resources.configuration
            metadata.put("density", it.resources.displayMetrics.density).put("fontScale", config.fontScale)
                .put("orientation", config.orientation).put("renderedLocale", config.locales[0].toLanguageTag())
                .put("dark", config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        }
        return metadata
    }

    private fun addCanvas(metadata: JSONObject, tag: String, bitmap: Bitmap) {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= bitmap.width && bounds.bottom <= bitmap.height)
        metadata.put("canvasLeft", bounds.left).put("canvasTop", bounds.top)
            .put("canvasRight", bounds.right).put("canvasBottom", bounds.bottom)
    }
}
