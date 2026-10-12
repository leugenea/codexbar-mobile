package io.github.leugenea.codexbarmobile

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

/** Actual production-root PNG, exact pair keys and registered measured/reference pixel roles. */
internal class DashboardCapture(private val compose: ComposeTestRule) {
    fun capture(name: String, scenario: ActivityScenario<MainActivity>, fixture: HistoryNavigationFixture,
        route: DashboardRoute, label: String, kind: WindowKind? = null) {
        val observed = compose.onNodeWithTag(label)
        val layouts = HistoryAcceptanceCapture(compose).layout(if (name == "dashboard-drawer-portrait-dark") observed else observed.performScrollTo())
        val tag = if (kind == WindowKind.WEEKLY) "history-chart-weekly-canvas" else "history-chart-five-hour-canvas"
        if (kind != null) compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val source = fixture.owner.historySnapshots.value
            val data = JSONObject().put("name", name).put("synthetic", true).put("productionEntry", true)
                .put("activity", MainActivity::class.java.name).put("api", Build.VERSION.SDK_INT)
                .put("width", bitmap.width).put("height", bitmap.height).put("route", route.name)
                .put("visibleRoots", JSONArray(listOf("connection-screen"))).put("requestMode", requestMode(source.request))
                .put("requests", fixture.transport.requests.get()).put("gets", fixture.transport.gets.get())
                .put("layoutLabel", label).put("layoutFontScale", layouts.layoutInput.density.fontScale)
                .put("layoutDensity", layouts.layoutInput.density.density)
                .put("sourceKind", kind?.name ?: "NONE")
            scenario.onActivity {
                assertSame(fixture.owner, it.connection)
                val config = it.resources.configuration
                data.put("orientation", config.orientation).put("fontScale", config.fontScale)
                    .put("density", it.resources.displayMetrics.density).put("renderedLocale", config.locales[0].toLanguageTag())
                    .put("dark", config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            }
            data.put("windows", if (source.request is HistoryPresentationRequest.Current)
                JSONArray(listOf(window(fixture.owner, WindowKind.FIVE_HOUR), window(fixture.owner, WindowKind.WEEKLY))) else JSONArray())
            data.put("inventoryCount", fixture.owner.state.value.refresh.inventory.success?.inventory?.reportedAvailableCount?.value ?: JSONObject.NULL)
            data.put("inventoryRows", fixture.owner.state.value.refresh.inventory.success?.inventory?.items?.size ?: 0)
            if (kind != null) chart(data, bitmap, fixture.owner, kind, tag)
            if (name == "dashboard-drawer-portrait-dark") data.put("drawerLabels", JSONArray(listOf(
                text("drawer-LOCAL_HISTORY_DETAILS"), text("drawer-CONNECTION_DETAILS"))))
            save(name, bitmap, data)
        } finally { bitmap.recycle() }
    }

    private fun requestMode(request: HistoryPresentationRequest) = when (request) {
        HistoryPresentationRequest.Idle -> "IDLE"
        is HistoryPresentationRequest.Page -> "PAGE"
        is HistoryPresentationRequest.Current -> "CURRENT"
    }

    private fun text(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun window(owner: ConnectionController, kind: WindowKind): JSONObject {
        val source = recordedHistory(owner, kind)
        return JSONObject().put("kind", kind.name).put("limit", source.query.limit)
            .put("after", source.query.after?.ordinal ?: 0).put("reset", source.query.window?.resetAt?.toString() ?: "NONE")
            .put("duration", source.query.window?.durationSeconds ?: 0)
            .put("selectedOrdinals", JSONArray(source.storage?.entries.orEmpty().map { it.id.ordinal }))
            .put("lastAdmitted", source.storage?.lastAdmitted?.ordinal ?: 0)
    }

    private fun chart(data: JSONObject, bitmap: Bitmap, owner: ConnectionController, kind: WindowKind, tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.HideFromAccessibility))
        val full = Rect(node.positionInRoot, node.size.toSize())
        val clipped = node.boundsInRoot
        assertEquals(full, clipped)
        assertTrue(full.left >= 0 && full.top >= 0 && full.right <= bitmap.width && full.bottom <= bitmap.height)
        for ((side, value) in listOf("Left" to full.left, "Top" to full.top, "Right" to full.right, "Bottom" to full.bottom)) {
            data.put("canvas$side", value)
        }
        for ((side, value) in listOf("Left" to clipped.left, "Top" to clipped.top, "Right" to clipped.right, "Bottom" to clipped.bottom)) {
            data.put("canvasClipped$side", value)
        }
        val plot = HistoryPlotInputs.project(recordedHistory(owner, kind))
        val series = if (kind == WindowKind.WEEKLY) plot.weekly else plot.fiveHour
        val drawing = historyChartDrawing(series)
        val colors = if (data.getBoolean("dark")) darkColorScheme() else lightColorScheme()
        val pixels = HistoryAcceptancePixels(bitmap, full, data.getDouble("density").toFloat())
        drawing.markers.forEach { pixels.sample("marker", it, colors.primary.toArgb(), true) }
        drawing.measured.forEach { pixels.between("edge", it.start, it.end, colors.primary.toArgb(), true) }
        series.segments.zipWithNext().forEach { (one, two) -> pixels.between("break",
            one.points.last().position as PlotPosition.Available, two.points.first().position as PlotPosition.Available, colors.primary.toArgb(), false) }
        drawing.nominal.forEach { pixels.dashes(it, colors.onSurfaceVariant.toArgb()) }
        data.put("markerCount", drawing.markers.size).put("connectionCount", drawing.measured.size)
            .put("referenceCount", drawing.nominal.size).put("boundaryCount", (series.segments.size - 1).coerceAtLeast(0))
            .put("pixelSamples", pixels.samples).put("pixelOraclePassed", true)
    }

    private fun save(name: String, bitmap: Bitmap, data: JSONObject) {
        val directory = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "dashboard-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "$name.json").writeText(data.toString(2))
    }
}
