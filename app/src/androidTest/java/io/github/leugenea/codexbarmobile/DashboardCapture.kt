package io.github.leugenea.codexbarmobile

import android.graphics.Bitmap
import android.content.res.Configuration
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
    private val observer = HeldHistoryObserver()
    private var previousObserver: HistoryUiObserver? = null
    fun install() { previousObserver = MainActivity.historyUiObserver; MainActivity.historyUiObserver = observer }
    fun restore() { observer.release(); MainActivity.historyUiObserver = previousObserver }

    fun capture(name: String, scenario: ActivityScenario<MainActivity>, fixture: HistoryNavigationFixture,
        label: String, kind: WindowKind? = null) {
        // Let the selected route finish its real delivery before installing a hold;
        // no predecessor is captured until after the hold's host acknowledgement.
        awaitRenderedSource(fixture.owner)
        // Freeze only UI delivery before selecting its predecessor. The owner, local
        // clock and actual revocation slot remain live throughout this capture.
        observer.hold()
        try {
            awaitRenderedSource(fixture.owner)
            val observed = compose.onNodeWithTag(label)
            val layouts = HistoryAcceptanceCapture(compose).layout(if (name == "dashboard-drawer-portrait-dark") observed else observed.performScrollTo())
            val observedLabel = observed.fetchSemanticsNode().config[SemanticsProperties.TestTag]
            val tag = if (kind == WindowKind.WEEKLY) "history-chart-weekly-canvas" else "history-chart-five-hour-canvas"
            if (kind != null) compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val frame = frame(scenario, fixture.owner)
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            try {
                val source = frame.source
                val roots = mountedRoots()
                val route = mountedRoute(roots)
                val data = JSONObject().put("name", name).put("synthetic", true).put("productionEntry", true)
                    .put("activity", MainActivity::class.java.name).put("api", Build.VERSION.SDK_INT)
                    .put("width", bitmap.width).put("height", bitmap.height).put("route", route.name)
                    .put("visibleRoots", JSONArray(roots)).put("requestMode", requestMode(source.request))
                    .put("currentHost", currentHost(frame, fixture.owner))
                    .put("requests", fixture.transport.requests.get()).put("gets", fixture.transport.gets.get())
                    .put("layoutLabel", observedLabel).put("layoutFontScale", layouts.layoutInput.density.fontScale)
                    .put("layoutDensity", layouts.layoutInput.density.density)
                    .put("sourceKind", kind?.name ?: "NONE")
                val config = frame.configuration
                data.put("orientation", config.orientation).put("fontScale", config.fontScale)
                    .put("density", frame.density).put("renderedLocale", config.locales[0].toLanguageTag())
                    .put("dark", config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                assertEquals(frame.density, layouts.layoutInput.density.density)
                assertEquals(config.fontScale, layouts.layoutInput.density.fontScale)
                data.put("windows", if (source.request is HistoryPresentationRequest.Current)
                    JSONArray(listOf(window(frame, WindowKind.FIVE_HOUR), window(frame, WindowKind.WEEKLY))) else JSONArray())
                data.put("inventoryCount", frame.state.refresh.inventory.success?.inventory?.reportedAvailableCount?.value ?: JSONObject.NULL)
                data.put("inventoryRows", frame.state.refresh.inventory.success?.inventory?.items?.size ?: 0)
                if (kind != null) chart(data, bitmap, frame, kind, tag)
                if (name == "dashboard-drawer-portrait-dark") data.put("drawerLabels", JSONArray(listOf(
                    text("drawer-LOCAL_HISTORY_DETAILS"), text("drawer-CONNECTION_DETAILS"), text("drawer-ACCOUNT_SETTINGS"))))
                revalidate(frame, scenario, fixture.owner)
                save(name, bitmap, data)
            } finally { bitmap.recycle() }
        } finally { observer.release() }
    }

    private fun mountedRoots(): List<String> {
        val roots = listOf("connection-screen", "dashboard", "connection-history", "connection-details").filter {
            compose.onAllNodesWithTag(it).fetchSemanticsNodes().size == 1
        }.toMutableList()
        try { compose.onNodeWithTag("details-drawer").assertIsDisplayed(); roots += "details-drawer" }
        catch (_: AssertionError) { /* A closed drawer is composed off-screen; it is not visible evidence. */ }
        assertTrue("Actual production root is mounted", "connection-screen" in roots)
        return roots
    }

    private fun mountedRoute(roots: List<String>): DashboardRoute {
        val children = listOf("dashboard", "connection-history", "connection-details").filter { it in roots }
        assertEquals("Exactly one actual route child", 1, children.size)
        return when (children.single()) {
            "dashboard" -> DashboardRoute.DASHBOARD
            "connection-history" -> DashboardRoute.LOCAL_HISTORY_DETAILS
            else -> DashboardRoute.CONNECTION_DETAILS
        }
    }

    private data class Frame(val source: HistoryGraphSnapshot, val current: HeldHistoryObserver.CurrentPass?,
        val state: ConnectionState, val configuration: Configuration, val density: Float)

    private fun awaitRenderedSource(owner: ConnectionController) {
        try { compose.waitUntil(5_000) {
            val source = observer.deliverySource
            source != null && when (source.request) {
                is HistoryPresentationRequest.Current -> observer.currentFrame?.source === source &&
                    compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().size == 1
                is HistoryPresentationRequest.Page -> observer.last?.source === source &&
                    compose.onAllNodesWithTag("connection-history").fetchSemanticsNodes().size == 1
                HistoryPresentationRequest.Idle -> compose.onAllNodesWithTag("connection-details").fetchSemanticsNodes().size == 1
            }
        } } catch (error: ComposeTimeoutException) {
            throw AssertionError("Capture frozen delivery acknowledged by actual host: last request=${owner.historySnapshots.value.request}, readiness=${owner.historySnapshots.value.readiness}, delivered=${observer.deliverySource?.request}, rendered=${observer.currentFrame?.source?.request}", error)
        }
    }

    private fun frame(scenario: ActivityScenario<MainActivity>, owner: ConnectionController): Frame {
        lateinit var captured: Frame
        scenario.onActivity {
            assertSame(owner, it.connection)
            val source = requireNotNull(observer.deliverySource)
            val current = if (source.request is HistoryPresentationRequest.Current) requireNotNull(observer.currentFrame) else null
            current?.let { rendered ->
                assertSame("Frame uses the frozen source acknowledged by the rendered host", source, rendered.source)
            }
            captured = Frame(source, current, it.connection.state.value,
                Configuration(it.resources.configuration), it.resources.displayMetrics.density)
        }
        return captured
    }

    private fun revalidate(frame: Frame, scenario: ActivityScenario<MainActivity>, owner: ConnectionController) {
        scenario.onActivity {
            assertSame("Frozen delivery cannot change mid-capture", frame.source, observer.deliverySource)
            frame.current?.let { captured ->
                val rendered = requireNotNull(observer.currentFrame)
                assertSame("PNG and both children use the exact rendered source", captured.source, rendered.source)
                assertEquals("Changed evaluation frame must fail capture", captured.now, rendered.now)
                assertSame(captured.fiveHour.storage, rendered.fiveHour.storage)
                assertSame(captured.weekly.storage, rendered.weekly.storage)
            }
            assertEquals(frame.configuration.orientation, it.resources.configuration.orientation)
            assertEquals(frame.configuration.uiMode, it.resources.configuration.uiMode)
            assertEquals(frame.configuration.fontScale, it.resources.configuration.fontScale)
            assertEquals(frame.configuration.locales, it.resources.configuration.locales)
            assertEquals(frame.density, it.resources.displayMetrics.density)
            assertSame("Inventory source cannot change mid-capture", frame.state.refresh.inventory.success,
                it.connection.state.value.refresh.inventory.success)
            currentHost(frame, owner) // Actual permission is re-read; UI hold never grants authority.
        }
    }

    private fun currentHost(frame: Frame, owner: ConnectionController): Any {
        val source = frame.source
        val request = source.request as? HistoryPresentationRequest.Current ?: return JSONObject.NULL
        val context = owner.currentHistoryDisplayContext()
        val pair = frame.current
        val contextMatches = context != null && source.displayPermission?.context === context && source.displayPermission?.current() === context
        val selectionMatches = context != null && source.selection != null && source.selection === context.selection &&
            source.generation != null && source.generation === context.generation && source.request == context.selection?.request
        val periodsMatch = context != null && request.matches(context.periods)
        val projected = pair?.source === source && pair?.fiveHour?.selection === source.selection && pair?.weekly?.selection === source.selection &&
            pair?.fiveHour?.storage === source.currentPages?.fiveHour && pair?.weekly?.storage === source.currentPages?.weekly &&
            pair?.fiveHour?.query == request.query(WindowKind.FIVE_HOUR) && pair?.weekly?.query == request.query(WindowKind.WEEKLY)
        val receipt = JSONObject()
        for ((key, matches) in listOf("context" to contextMatches, "selection" to selectionMatches, "periods" to periodsMatch, "projection" to projected)) {
            receipt.put(key, if (matches) "MATCH" else "MISMATCH")
            assertTrue("Actual current host $key must match before capture", matches)
        }
        return receipt
    }

    private fun requestMode(request: HistoryPresentationRequest) = when (request) {
        HistoryPresentationRequest.Idle -> "IDLE"
        is HistoryPresentationRequest.Page -> "PAGE"
        is HistoryPresentationRequest.Current -> "CURRENT"
    }

    private fun text(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun window(frame: Frame, kind: WindowKind): JSONObject {
        val pair = requireNotNull(frame.current)
        assertSame("Window receipts use the exact captured rendered source", frame.source, pair.source)
        val source = if (kind == WindowKind.FIVE_HOUR) pair.fiveHour else pair.weekly
        return JSONObject().put("kind", kind.name).put("limit", source.query.limit)
            .put("after", source.query.after?.ordinal ?: 0).put("reset", source.query.window?.resetAt?.toString() ?: "NONE")
            .put("duration", source.query.window?.durationSeconds ?: 0)
            .put("selectedOrdinals", JSONArray(source.storage?.entries.orEmpty().map { it.id.ordinal }))
            .put("lastAdmitted", source.storage?.lastAdmitted?.ordinal ?: 0)
    }

    private fun chart(data: JSONObject, bitmap: Bitmap, frame: Frame, kind: WindowKind, tag: String) {
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
        val current = requireNotNull(frame.current)
        val plot = HistoryPlotInputs.project(if (kind == WindowKind.FIVE_HOUR) current.fiveHour else current.weekly)
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
