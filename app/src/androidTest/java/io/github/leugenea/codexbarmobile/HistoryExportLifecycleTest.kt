package io.github.leugenea.codexbarmobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.history.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HistoryExportLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: HistoryNavigationFixture
    @Before fun prepare() { fixture = HistoryNavigationFixture(); fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun localHistoryExportLaunchesJsonSafAndWritesCoherentPagedHistoryOffMain() {
        val captured = AtomicReference<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                captured.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitConnectionScreen(compose, fixture.owner)
                fixture.owner.connect()
                compose.waitUntil(10_000) { fixture.owner.state.value.phase == ConnectionPhase.OBSERVED }
                compose.waitUntil(10_000) { recordedHistory(fixture.owner).storage?.entries?.size == 1 }
                fixture.seedLocalPage()
                val ready = fixture.owner.session.snapshot() as SessionResult.Ready
                val access = requireNotNull(fixture.owner.historyCapability(ready.envelope.generation))
                for (id in 36L..300L) assertTrue(access.append(HistoryAdmission(access.partition, ObservationId(id),
                    HistoryClock(ClockEpoch(java.util.UUID(0, 9)), id * 1000), HistoryEvent.Gap(HistoryGap.READ_ERROR))) is HistoryAppendOutcome.Stored)
                val requests = fixture.transport.requests.get()
                openDetailRoute(compose, fixture.owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
                compose.onNodeWithTag("history-export").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(10_000) { captured.get() != null }
                val intent = requireNotNull(captured.get())
                assertEquals("application/json", intent.type)
                // CreateDocument sets action/type/title, unlike GetContent it adds no OPENABLE category.
                assertNull(intent.categories)
                assertEquals("codexbar-history-v1.json", intent.getStringExtra(Intent.EXTRA_TITLE))
                val request = fixture.owner.historyExportRequest
                compose.waitUntil(10_000) { !request.busy.value }
                assertNull(request.pending)
                compose.onNodeWithTag("history-export").assertIsEnabled()
                verifyDocument(access)
                request.pending = "detached-before-recreation"
                request.busy.value = true
                scenario.recreate()
                compose.waitForIdle()
                scenario.onActivity { assertSame(request, it.connection.historyExportRequest) }
                assertEquals("detached-before-recreation", request.pending)
                openDetailRoute(compose, fixture.owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
                compose.onNodeWithTag("history-export").performScrollTo().assertIsNotEnabled()
                request.clear()
                assertEquals(requests, fixture.transport.requests.get())
                access.revoke()
                assertNull(access.exportHistory("test", Instant.EPOCH))
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun verifyDocument(access: HistoryRuntimeAccess) = runBlocking {
        val text = requireNotNull(fixture.owner.exportHistory("native-test", Instant.EPOCH))
        val decoded = HistoryExport.decode(text, access.partition)
        assertEquals(300, decoded.entries.size)
        assertEquals(300L, decoded.state.cursor.lastOrdinal)
        assertEquals(300L, decoded.entries.last().id.ordinal)
        assertNotNull(decoded.state.cursor.fiveHour.lastPoint)
        val target = File(fixture.root, "export.json")
        assertTrue(writeHistoryDocument(fixture.context, Uri.fromFile(target), text))
        assertEquals(text, target.readText(Charsets.UTF_8))
        assertFalse(writeHistoryDocument(fixture.context, Uri.fromFile(fixture.root), text))
        assertFalse(writeHistoryDocument(fixture.context, Uri.fromFile(target), null))
    }
}
