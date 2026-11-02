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
import java.util.concurrent.atomic.AtomicBoolean
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
        val cancelPicker = AtomicBoolean(true)
        val toast = AtomicReference<String>()
        val file = File(fixture.root, "import.json")
        val message = fixture.context.getString(R.string.history_import_success)
        val unavailable = fixture.context.getString(R.string.history_import_unavailable)
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                event.text.map { it.toString() }.firstOrNull { it == message || it == unavailable }?.let(toast::set)
            }
        }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                captured.set(intent)
                if (cancelPicker.getAndSet(false)) return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            fixture.owner.usageForeground(fixture.observer, true)
            settleConnectionCommands(compose, fixture.owner, "import foreground receipt")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitConnectionScreen(compose, fixture.owner)
                openDetailRoute(compose, fixture.owner, DashboardRoute.ACCOUNT_SETTINGS)
                compose.onNodeWithTag("history-import").performScrollTo().assertIsNotEnabled()
                returnDashboard(compose, fixture.owner)
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
                assertNull(snapshot(access).lastAdmitted)
                openDetailRoute(compose, fixture.owner, DashboardRoute.ACCOUNT_SETTINGS)
                await("account tools need authority, not a displayed page") { fixture.owner.historySnapshots.value.let {
                    it.request == HistoryPresentationRequest.Idle && it.storage == null && it.currentPages == null && it.partition != null
                } && fixture.owner.currentHistoryDisplayContext()?.generation != null }
                compose.onNodeWithTag("history-import").performScrollTo().assertIsEnabled().performClick()
                await("cancelled SAF picker busy reset") { captured.get() != null && !fixture.owner.historyImporterState.picker.busy.value }
                assertNull(fixture.owner.historyImporterState.picker.pending)
                assertNull(fixture.owner.historyImporterState.confirmation.value)
                assertNull(snapshot(access).lastAdmitted)
                compose.onNodeWithTag("history-import").assertIsEnabled().performClick()
                await("SAF result read") { fixture.owner.historyImporterState.confirmation.value != null }
                assertEquals("*/*", captured.get().type)
                assertArrayEquals(arrayOf("application/json"), captured.get().getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                compose.onNodeWithText(fixture.context.getString(R.string.history_import_confirmation)).assertIsDisplayed()
                assertNull(snapshot(access).lastAdmitted)
                val pending = fixture.owner.historyImporterState
                val selection = requireNotNull(pending.confirmation.value)
                compose.onNodeWithTag("history-import").assertIsNotEnabled()
                scenario.recreate()
                awaitConnectionScreen(compose, fixture.owner)
                scenario.onActivity { assertSame(pending, it.connection.historyImporterState) }
                assertSame(selection, pending.confirmation.value)
                compose.onNodeWithTag("account-settings").assertDoesNotExist()
                compose.onNodeWithTag("history-import-cancel").assertIsDisplayed()
                compose.onNodeWithTag("history-import-cancel").performClick()
                await("confirmation cancellation settled") { pending.confirmation.value == null && !pending.picker.busy.value }
                assertNull(snapshot(access).lastAdmitted)
                openDetailRoute(compose, fixture.owner, DashboardRoute.ACCOUNT_SETTINGS)
                compose.onNodeWithTag("history-import").performScrollTo().performClick()
                await("second confirmation") { fixture.owner.historyImporterState.confirmation.value != null }
                compose.onNodeWithTag("history-import-confirm").performClick()
                await("import result toast without a history page") { toast.get() == message && !pending.importing.value &&
                    !pending.picker.busy.value && snapshot(access).lastAdmitted == ObservationId(35) }
                compose.onNodeWithTag("history-import").assertIsEnabled()
                openDetailRoute(compose, fixture.owner, DashboardRoute.LOCAL_HISTORY_DETAILS)
                await("imported history published on explicit navigation") {
                    recordedHistory(fixture.owner).storage?.lastAdmitted == ObservationId(35) }
                compose.onNodeWithTag("history-export").assertDoesNotExist()
                compose.onNodeWithTag("history-import").assertDoesNotExist()
                assertEquals(text, access.exportHistory("native", Instant.EPOCH))
                verifyContinuation(access)
                verifyAtomicSQLiteRollback()
                assertEquals(requests, fixture.transport.requests.get())
                verifyPendingImportCannotTargetSuccessor(toast, unavailable)
                access.revoke()
                assertEquals(HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), access.importHistory(text, Instant.now()))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        }
    }

    private fun verifyPendingImportCannotTargetSuccessor(toast: AtomicReference<String>, unavailable: String) {
        openDetailRoute(compose, fixture.owner, DashboardRoute.ACCOUNT_SETTINGS)
        compose.onNodeWithTag("history-import").performScrollTo().performClick()
        val pending = fixture.owner.historyImporterState
        await("predecessor confirmation selected") { pending.confirmation.value != null }
        val selection = requireNotNull(pending.confirmation.value)
        fixture.owner.signOut()
        await("logout completed with predecessor import still pending") { fixture.owner.state.value.phase == ConnectionPhase.SIGNED_OUT }
        settleConnectionCommands(compose, fixture.owner, "pending import logout receipt")
        assertSame(selection, pending.confirmation.value)
        val signedOutRequests = fixture.transport.requests.get()
        fixture.owner.connect()
        await("successor read settled") { fixture.owner.state.value.phase == ConnectionPhase.OBSERVED && !fixture.owner.state.value.refresh.refreshing }
        settleConnectionCommands(compose, fixture.owner, "pending import successor receipt")
        val ready = fixture.owner.session.snapshot() as SessionResult.Ready
        val successor = requireNotNull(fixture.owner.historyCapability(ready.envelope.generation))
        await("successor records ordinal one independently") { snapshot(successor).lastAdmitted == ObservationId(1) }
        assertNotSame(selection.target.generation, ready.envelope.generation)
        assertNotEquals(selection.target.partition, successor.partition)
        val before = requireNotNull(successor.exportHistory("native", Instant.EPOCH))
        val requests = fixture.transport.requests.get()
        assertEquals("Only explicit successor sign-in admits its five requests", signedOutRequests + 5, requests)
        toast.set(null)
        compose.onNodeWithTag("history-import-confirm").performClick()
        await("stale confirmation rejected and reported at owner boundary") { toast.get() == unavailable &&
            pending.confirmation.value == null && !pending.importing.value && !pending.picker.busy.value }
        assertEquals("A predecessor file cannot replace successor history", before, successor.exportHistory("native", Instant.EPOCH))
        compose.onNodeWithTag("history-import").assertIsEnabled()
        assertEquals("Rejecting a stale local import adds no provider requests", requests, fixture.transport.requests.get())
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
