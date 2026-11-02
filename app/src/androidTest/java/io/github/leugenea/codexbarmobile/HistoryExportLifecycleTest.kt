package io.github.leugenea.codexbarmobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
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
    private val settings = HistoryAcceptanceSettings()
    private lateinit var fixture: HistoryNavigationFixture
    @Before fun prepare() { fixture = HistoryNavigationFixture(); fixture.prepare() }
    @After fun cleanup() { try { fixture.cleanup() } finally { settings.restore() } }

    @Test fun localHistoryExportLaunchesJsonSafAndWritesCoherentPagedHistoryOffMain() {
        val captured = AtomicReference<Intent>()
        val destination = AtomicReference<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                captured.set(intent)
                val uri = destination.get()
                return if (uri == null) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    else Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            // Keep foreground eligibility across recreation: this oracle isolates local export
            // from the activated owner's intentional STOP/START provider refresh.
            fixture.owner.usageForeground(fixture.observer, true)
            settleConnectionCommands(compose, fixture.owner, "export fixture foreground receipt")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitConnectionScreen(compose, fixture.owner)
                fixture.owner.connect()
                try {
                    compose.waitUntil(10_000) {
                        fixture.owner.state.value.let { it.phase == ConnectionPhase.OBSERVED && !it.refresh.refreshing } &&
                            recordedHistory(fixture.owner).storage?.entries?.size == 1
                    }
                } catch (error: ComposeTimeoutException) {
                    throw AssertionError("Export initial read/history settled: last=${fixture.owner.state.value}, history=${recordedHistory(fixture.owner)}", error)
                }
                fixture.seedLocalPage()
                val ready = fixture.owner.session.snapshot() as SessionResult.Ready
                val access = requireNotNull(fixture.owner.historyCapability(ready.envelope.generation))
                for (id in 36L..300L) assertTrue(access.append(HistoryAdmission(access.partition, ObservationId(id),
                    HistoryClock(ClockEpoch(java.util.UUID(0, 9)), id * 1000), HistoryEvent.Gap(HistoryGap.READ_ERROR))) is HistoryAppendOutcome.Stored)
                val requests = fixture.transport.requests.get()
                openDetailRoute(compose, fixture.owner, DashboardRoute.ACCOUNT_SETTINGS)
                compose.onNodeWithTag("connection-history").assertDoesNotExist()
                compose.onNodeWithTag("connection-details").assertDoesNotExist()
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
                val target = File(fixture.root, "saf-export.json")
                destination.set(Uri.fromFile(target))
                compose.onNodeWithTag("history-export").performClick()
                await("returned SAF destination written and export busy reset") { target.isFile && !request.busy.value }
                val exported = HistoryExport.decode(target.readText(Charsets.UTF_8), access.partition)
                assertEquals(300, exported.entries.size)
                assertEquals(300L, exported.state.cursor.lastOrdinal)
                assertNull(request.pending)
                verifyDocument(access)
                assertEquals("local export and document writes add no provider requests", requests, fixture.transport.requests.get())
                request.pending = "detached-before-recreation"
                request.busy.value = true
                scenario.recreate()
                compose.waitForIdle()
                scenario.onActivity { assertSame(request, it.connection.historyExportRequest) }
                assertEquals("detached-before-recreation", request.pending)
                openDetailRoute(compose, fixture.owner, DashboardRoute.ACCOUNT_SETTINGS)
                compose.onNodeWithTag("history-export").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("history-import").performScrollTo().assertIsEnabled()
                openDetailRoute(compose, fixture.owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
                compose.onNodeWithTag("history-export").assertDoesNotExist()
                compose.onNodeWithTag("history-import").assertDoesNotExist()
                assertEquals("detached-before-recreation", request.pending)
                request.clear()
                assertEquals("local export/recreation adds no provider requests with continuous foreground eligibility",
                    requests, fixture.transport.requests.get())
                access.revoke()
                assertNull(access.exportHistory("test", Instant.EPOCH))
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun dormantAccountSettingsGroupsHistoryToolsWithoutDiagnosticRoutesOrProviderReads() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitConnectionScreen(compose, fixture.owner)
            fixture.owner.connect()
            await("seeded one durable observation") { fixture.owner.state.value.phase == ConnectionPhase.OBSERVED &&
                !fixture.owner.state.value.refresh.refreshing && recordedHistory(fixture.owner).storage?.lastAdmitted == ObservationId(1) }
        }
        fixture.freshRuntime()
        val requests = fixture.transport.counts()
        for ((dark, font) in listOf(false to 1f, true to 2f)) {
            settings.apply(dark, font)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settings.observe(compose, scenario, landscape = false, dark = dark, font = font)
                awaitConnectionScreen(compose, fixture.owner)
                openDetailRoute(compose, fixture.owner, DashboardRoute.ACCOUNT_SETTINGS)
                await("dormant account has foreground history authority without a page") {
                    fixture.owner.state.value.phase == ConnectionPhase.RESTORED &&
                        fixture.owner.historySnapshots.value.let { it.request == HistoryPresentationRequest.Idle &&
                            it.partition != null && it.storage == null && it.currentPages == null } &&
                        fixture.owner.currentHistoryDisplayContext()?.generation != null
                }
                compose.onNodeWithTag("connection-history").assertDoesNotExist()
                compose.onNodeWithTag("connection-details").assertDoesNotExist()
                compose.onNodeWithTag("account-history-tools-title").assertTextEquals("Local usage history")
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                val capture = HistoryAcceptanceCapture(compose)
                capture.label("account-history-tools-title")
                for ((tag, label) in listOf("history-export" to "Export history", "history-import" to "Import history")) {
                    compose.onAllNodesWithTag(tag).assertCountEquals(1)
                    val button = compose.onNodeWithTag(tag).performScrollTo().assertTextEquals(label).assertIsEnabled()
                        .assert(hasAnyAncestor(hasTestTag("account-history-tools")))
                    capture.layout(button)
                }
                capture.capture(if (dark) "account-history-tools-dark-large-font" else "account-history-tools-light",
                    scenario, fixture, listOf("history-export", "history-import"))
                assertEquals("Account tools must not activate dormant credentials", requests, fixture.transport.counts())
                openDetailRoute(compose, fixture.owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
                compose.onNodeWithTag("history-export").assertDoesNotExist()
                compose.onNodeWithTag("history-import").assertDoesNotExist()
                await("stored history preserved by account navigation") {
                    recordedHistory(fixture.owner).storage?.lastAdmitted == ObservationId(1)
                }
                assertEquals(requests, fixture.transport.counts())
            }
        }
    }

    private fun await(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(10_000, condition) } catch (failure: ComposeTimeoutException) {
            throw AssertionError("$step: state=${fixture.owner.state.value}, history=${recordedHistory(fixture.owner)}", failure)
        }
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
