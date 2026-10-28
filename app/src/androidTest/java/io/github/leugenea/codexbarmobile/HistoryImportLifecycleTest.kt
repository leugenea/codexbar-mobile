package io.github.leugenea.codexbarmobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.history.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HistoryImportLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: HistoryNavigationFixture
    @Before fun prepare() { fixture = HistoryNavigationFixture(); fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun localHistoryImportConfirmsJsonSafReplacesSQLiteAndAnnouncesResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val captured = AtomicReference<Intent>()
        val toast = AtomicReference<String>()
        val file = File(fixture.root, "import.json")
        val message = fixture.context.getString(R.string.history_import_success)
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && message in event.text.map { it.toString() }) toast.set(message)
        }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                captured.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            fixture.owner.usageForeground(fixture.observer, true)
            settleConnectionCommands(compose, fixture.owner, "import foreground receipt")
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitConnectionScreen(compose, fixture.owner)
                openDetailRoute(compose, fixture.owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
                compose.onNodeWithTag("history-import").performScrollTo().assertIsNotEnabled()
                fixture.owner.connect()
                await("initial recording") { fixture.owner.state.value.phase == ConnectionPhase.OBSERVED &&
                    !fixture.owner.state.value.refresh.refreshing && recordedHistory(fixture.owner).storage?.entries?.size == 1 }
                fixture.seedLocalPage()
                val requests = fixture.transport.requests.get()
                val ready = fixture.owner.session.snapshot() as SessionResult.Ready
                val access = requireNotNull(fixture.owner.historyCapability(ready.envelope.generation))
                val text = requireNotNull(access.exportHistory("native", Instant.EPOCH))
                file.writeText(text)
                val empty = HistoryExport.encode(HistoryExportDocument("native", Instant.EPOCH, emptyList(), HistoryDiskState(HistoryCursor(access.partition))))
                assertEquals(HistoryImportOutcome.Imported, access.importHistory(empty, Instant.now()))
                fixture.owner.queryHistory(HistoryGraphQuery())
                await("empty replacement published") { recordedHistory(fixture.owner).storage?.content == HistoryContent.EMPTY }
                compose.onNodeWithTag("history-import").performScrollTo().assertIsEnabled().performClick()
                await("SAF result read") { fixture.owner.historyImporterState.confirmation.value != null }
                assertEquals("*/*", captured.get().type)
                assertArrayEquals(arrayOf("application/json"), captured.get().getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                compose.onNodeWithText(fixture.context.getString(R.string.history_import_confirmation)).assertIsDisplayed()
                assertNull(snapshot(access).lastAdmitted)
                compose.onNodeWithTag("history-import-cancel").performClick()
                assertNull(snapshot(access).lastAdmitted)
                compose.onNodeWithTag("history-import").performScrollTo().performClick()
                await("second confirmation") { fixture.owner.historyImporterState.confirmation.value != null }
                compose.onNodeWithTag("history-import-confirm").performClick()
                await("import result toast and refreshed history") { toast.get() == message &&
                    recordedHistory(fixture.owner).storage?.lastAdmitted == ObservationId(35) }
                assertEquals(text, access.exportHistory("native", Instant.EPOCH))
                verifyContinuation(access)
                verifyAtomicSQLiteRollback()
                assertEquals(requests, fixture.transport.requests.get())
                access.revoke()
                assertEquals(HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), access.importHistory(text, Instant.now()))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        }
    }

    private fun verifyContinuation(access: HistoryRuntimeAccess) {
        val document = HistoryImport.decode(requireNotNull(access.exportHistory("native", Instant.EPOCH)), access.partition)
        val prior = document.state.cursor.fiveHour.lastPoint!!
        val usage = fixture.owner.state.value.refresh.usage.success!!.usage!!
        val result = access.append(HistoryAdmission(access.partition, ObservationId(36),
            prior.clock.copy(monotonicMillis = prior.clock.monotonicMillis!! + 1000),
            HistoryEvent.Observed(usage.copy(observedAt = prior.observedAt.plusSeconds(1))))) as HistoryAppendOutcome.Stored
        assertEquals(prior.segment.id, result.entry.windows.first().point!!.segment.id)
    }

    private fun verifyAtomicSQLiteRollback() {
        var fail = false
        val directory = File(fixture.root, "rollback")
        SQLiteHistoryStore(directory, hooks = object : HistoryStorageHooks {
            override fun afterInsert() { if (fail) throw IOException("Synthetic import transaction cut") }
        }).use { store ->
            val access = (store.createPartition() as HistoryAccessOutcome.Bound).access
            val clock = HistoryClock(ClockEpoch(UUID(1, 2)), 1000)
            assertTrue(access.append(HistoryAdmission(access.partition, ObservationId(1), clock, HistoryEvent.Gap(HistoryGap.READ_ERROR))) is HistoryAppendOutcome.Stored)
            val before = access.exportHistory("rollback", Instant.EPOCH)
            val source = HistoryImport.decode(requireNotNull(before), access.partition)
            val second = WindowHistory.append(source.state.cursor, HistoryAdmission(access.partition, ObservationId(2), clock,
                HistoryEvent.Gap(HistoryGap.BACKGROUND))) as HistoryReduction.Applied
            val text = HistoryExport.encode(source.copy(entries = source.entries + second.entry,
                state = HistoryRetention.advance(source.state, second)))
            fail = true
            assertEquals(HistoryImportOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), access.importHistory(text, Instant.now()))
            assertEquals(before, access.exportHistory("rollback", Instant.EPOCH))
            fail = false
            assertEquals(HistoryImportOutcome.Imported, access.importHistory(text, Instant.now()))
            assertEquals(ObservationId(2), snapshot(access).lastAdmitted)
        }
    }

    private fun snapshot(access: HistoryRuntimeAccess) = (access.read(HistoryReadQuery(access.partition, 256)) as HistoryReadOutcome.Ready).snapshot
    private fun await(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(10_000, condition) } catch (failure: ComposeTimeoutException) {
            throw AssertionError("$step: last=${fixture.owner.state.value}, history=${recordedHistory(fixture.owner)}", failure)
        }
    }
}
