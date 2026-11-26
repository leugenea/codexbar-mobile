package io.github.leugenea.codexbarmobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.account.CatalogEntry
import io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope
import io.github.leugenea.codexbarmobile.history.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AccountHistoryTransferTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture = AccountSelectionFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val transfers get() = NativeConnection.historyTransfers
    private val a get() = fixture.runtime(fixture.a)
    private val b get() = fixture.runtime(fixture.b)

    @Before fun prepare() { fixture.prepare() }

    @After fun cleanup() {
        try { if (::scenario.isInitialized) scenario.close() } finally { fixture.cleanup() }
    }

    @Test fun delayedPickersCrossSelectionAndRecreationWithoutRetargetingOrReusingAnUndrainedSlot() {
        launch()
        val originalA = fixture.owner.dashboard().value!!
        val sourceA = access(a)
        val file = replacement()
        val destination = File(fixture.directory, "delayed-export.json")
        val exportMessage = fixture.context.getString(R.string.history_export_account_success, fixture.a.name)
        val toast = AtomicReference<String>()
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.text.any { it.toString() == exportMessage }) toast.set(exportMessage)
        }
        try {
            settings(a)
            val open = startPicker("history-import", Intent.ACTION_OPEN_DOCUMENT)
            val export = startPicker("history-export", Intent.ACTION_CREATE_DOCUMENT)
            val detached = requireNotNull(transfers.export.pending).text
            val document = HistoryExport.decode(detached, sourceA.partition)
            assertEquals(sourceA.exportHistory(document.appVersion, document.exportedAt), detached)
            assertEquals(fixture.a.name, transfers.export.pending?.label)
            for (secret in listOf(fixture.a.name!!, fixture.b.name!!, fixture.a.storage.id.toString(),
                fixture.b.storage.id.toString(), sourceA.partition.value.toString(), "synthetic-A-access")) {
                assertFalse("No local identity in JSON", detached.contains(secret))
            }

            select(fixture.b)
            val peer = fixture.ready(b)
            val bytesB = fixture.bytes(fixture.b)
            val requestsB = fixture.bTransport.counts()
            scenario.recreate()
            awaitConnectionScreen(compose, b)
            settings(b)
            compose.onNodeWithTag("history-import").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("history-export").performScrollTo().assertIsNotEnabled()
            deliver(export, destination)
            compose.waitUntil(10_000) { !transfers.export.busy.value && toast.get() == exportMessage }
            assertEquals(detached, destination.readText(Charsets.UTF_8))
            compose.onNodeWithTag("history-export").assertIsEnabled()

            select(fixture.a)
            assertFalse(originalA.isCurrent())
            assertSame(a, fixture.owner.dashboard().value!!.controller)
            settings(a)
            compose.onNodeWithTag("history-import").performScrollTo().assertIsNotEnabled()
            val before = fixture.bytes(fixture.a)
            deliver(open, file)
            awaitImportSlot()
            compose.onNodeWithTag("history-import-confirm").assertDoesNotExist()
            assertEquals(before, fixture.bytes(fixture.a))

            val successor = startPicker("history-import", Intent.ACTION_OPEN_DOCUMENT)
            returnDashboard(compose, a)
            scenario.recreate()
            awaitConnectionScreen(compose, a)
            deliver(successor, file)
            awaitConfirmation()
            compose.onNodeWithTag("account-settings").assertDoesNotExist()
            compose.onNodeWithText(fixture.context.getString(R.string.history_import_account_confirmation, fixture.a.name))
                .assertIsDisplayed()
            compose.onNodeWithTag("history-import-cancel").performClick()
            awaitImportSlot()
            assertEquals(before, fixture.bytes(fixture.a))
            assertPeer(peer, bytesB, requestsB)
            assertEquals(4L, fixture.aTransport.requests.get()) // Only two explicit A selections.
        } finally { instrumentation.uiAutomation.setOnAccessibilityEventListener(null) }
    }

    @Test fun staleConfirmationCannotReturnAndAdmittedSQLiteReplacementSettlesOnlyOnA() {
        val hold = holdReplacement(beforeAdmission = false)
        launch()
        val file = replacement()
        val originalA = fixture.ready(a)
        choose(file)
        val oldConfirm = displayedAction("history-import-confirm")
        val oldCancel = displayedAction("history-import-cancel")
        select(fixture.b)
        awaitImportSlot()
        compose.onNodeWithTag("history-import-confirm").assertDoesNotExist()
        val unselectedBytes = fixture.bytes(fixture.b)
        compose.runOnIdle { oldConfirm(); oldCancel() }
        assertEquals(unselectedBytes, fixture.bytes(fixture.b))
        select(fixture.a)
        val beforeA = fixture.bytes(fixture.a)
        compose.runOnIdle { oldConfirm() }
        assertEquals(beforeA, fixture.bytes(fixture.a))

        choose(file)
        compose.runOnIdle { oldCancel(); oldConfirm() }
        compose.onNodeWithTag("history-import-confirm").assertIsDisplayed()
        assertNull(hold.outcome.get())
        val accessA = access(a)
        hold.armed.set(true)
        try {
            compose.onNodeWithTag("history-import-confirm").performClick()
            hold.gate.await("confirmation reached A transaction before SQLite commit")
            assertTrue(transfers.importing.value)
            select(fixture.b)
            val peer = fixture.ready(b)
            val bytesB = fixture.bytes(fixture.b)
            val requestsB = fixture.bTransport.counts()
            scenario.recreate()
            awaitConnectionScreen(compose, b)
            assertTrue(transfers.importing.value)
            hold.gate.release()
            awaitImportSlot()
            assertEquals(HistoryImportOutcome.Imported, hold.outcome.get())
            assertSame(originalA, fixture.ready(a))
            assertEquals(file.readText(), accessA.exportHistory("replacement", Instant.EPOCH))
            assertPeer(peer, bytesB, requestsB)
            assertEquals(4L, fixture.aTransport.requests.get())
        } finally { hold.gate.release() }
    }

    @Test fun removalBeforeNativeWriteAdmissionRejectsImportAndStillAllowsDetachedExport() {
        val hold = holdReplacement(beforeAdmission = true)
        launch()
        settings(a)
        val pendingExport = startPicker("history-export", Intent.ACTION_CREATE_DOCUMENT)
        val detached = requireNotNull(transfers.export.pending).text
        val initiating = a
        choose(replacement())
        hold.armed.set(true)
        try {
            compose.onNodeWithTag("history-import-confirm").performClick()
            hold.gate.await("confirmed A import reached native pre-admission cut")
            initiating.signOut()
            fixture.phase(initiating, ConnectionPhase.SIGNING_OUT)
            hold.gate.release()
            awaitImportSlot()
            assertEquals(HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), hold.outcome.get())
            fixture.phase(initiating, ConnectionPhase.SIGNED_OUT)
            awaitSelected(fixture.b)
            fixture.assertRemoved(fixture.a)
            val peer = fixture.ready(b)
            val bytesB = fixture.bytes(fixture.b)
            val requestsB = fixture.bTransport.counts()
            compose.onNodeWithTag("history-import-confirm").assertDoesNotExist()
            val destination = File(fixture.directory, "removed-export.json")
            deliver(pendingExport, destination)
            compose.waitUntil(10_000) { !transfers.export.busy.value }
            assertEquals(detached, destination.readText())
            assertPeer(peer, bytesB, requestsB)
        } finally { hold.gate.release() }
    }

    private class HeldReplacement(val gate: SelectionGate) {
        val armed = AtomicBoolean()
        val outcome = AtomicReference<HistoryImportOutcome>()
    }

    private fun holdReplacement(beforeAdmission: Boolean): HeldReplacement {
        val hold = HeldReplacement(fixture.gate())
        val aPath = fixture.a.storage.historyDirectory(fixture.directory)
        fixture.decorateHistory = { path, storage ->
            if (path != aPath) storage else object : HistoryLifetimeStorage by storage {
                override fun restore(): HistoryLifetimeOutcome = observeImport(storage.restore(), hold.outcome)
            }
        }
        fixture.hooks = { path -> object : HistoryStorageHooks {
            private fun pause() {
                if (path == aPath && hold.armed.compareAndSet(true, false)) hold.gate.pause("A replacement SQLite cut")
            }
            override fun beforeWriteAdmission() { if (beforeAdmission) pause() }
            override fun beforeCommit() { if (!beforeAdmission) pause() }
        } }
        return hold
    }

    private fun launch() {
        fixture.start()
        fixture.installActivity()
        // Recreation is not a last-observer STOP/START, which would legitimately refresh quota.
        fixture.observer()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitConnectionScreen(compose, a)
        select(fixture.a)
    }

    private fun select(entry: CatalogEntry) { fixture.select(entry); awaitSelected(entry) }

    private fun awaitSelected(entry: CatalogEntry) {
        val controller = fixture.runtime(entry)
        compose.waitUntil(10_000) { fixture.owner.dashboard().value?.id == entry.storage.id }
        fixture.phase(controller, ConnectionPhase.OBSERVED)
        val usage = controller.state.value.refresh.usage.success!!.usage
        fixture.await("${entry.name} selected usage durably recorded") {
            fixture.appends.any { it.admission.partition == entry.partition && it.result is HistoryAppendOutcome.Stored &&
                (it.admission.event as? HistoryEvent.Observed)?.usage === usage }
        }
        compose.waitForIdle()
        scenario.onActivity { assertSame(controller, it.connection) }
    }

    private fun access(controller: ConnectionController) = requireNotNull(controller.historyCapability(fixture.ready(controller).generation))
    private fun settings(controller: ConnectionController) = openDetailRoute(compose, controller, DashboardRoute.ACCOUNT_SETTINGS)
    private fun awaitConfirmation() = compose.waitUntil(10_000) { transfers.confirmation.value != null }
    private fun awaitImportSlot() = compose.waitUntil(10_000) {
        !transfers.importPicker.busy.value && !transfers.importing.value && transfers.confirmation.value == null
    }
    private fun displayedAction(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().config[DisplayedAccountAction]

    private fun choose(file: File) {
        settings(a)
        deliver(startPicker("history-import", Intent.ACTION_OPEN_DOCUMENT), file)
        awaitConfirmation()
    }

    private fun replacement(): File {
        val partition = fixture.a.partition!!
        val reduced = WindowHistory.append(HistoryCursor(partition), HistoryAdmission(partition,
            ObservationId(1), HistoryClock(ClockEpoch(java.util.UUID(0, 88)), 1_000),
            HistoryEvent.Gap(HistoryGap.READ_ERROR))) as HistoryReduction.Applied
        return File(fixture.directory, "replacement.json").apply {
            writeText(HistoryExport.encode(HistoryExportDocument("replacement", Instant.EPOCH, listOf(reduced.entry),
                HistoryRetention.advance(HistoryDiskState(HistoryCursor(partition)), reduced))))
        }
    }

    private fun assertPeer(peer: CredentialEnvelope, bytes: Map<String, List<Byte>>, requests: Map<String, Long>) {
        assertSame(peer, fixture.ready(b))
        assertEquals(bytes, fixture.bytes(fixture.b))
        assertEquals(requests, fixture.bTransport.counts())
        val persisted = fixture.protectedReadback(fixture.b)
        assertArrayEquals(peer.accessToken.copyBytes(), persisted.accessToken.copyBytes())
        assertArrayEquals(requireNotNull(peer.refreshToken).copyBytes(), requireNotNull(persisted.refreshToken).copyBytes())
    }

    // Intercept only the external UI; the real Compose contract launches and the real Activity
    // registry saves/restores its request codes. Deliver a raw Activity Result, not a Compose callback.
    private fun startPicker(tag: String, action: String): Int {
        val before = outstandingCodes()
        val monitor = Instrumentation.ActivityMonitor(IntentFilter(action).apply {
            addDataType(if (action == Intent.ACTION_CREATE_DOCUMENT) "application/json" else "*/*")
            addCategory(Intent.CATEGORY_OPENABLE)
        }, null, true)
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { monitor.hits == 1 }
            return (outstandingCodes() - before).single()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun outstandingCodes(): Set<Int> {
        var codes = emptySet<Int>()
        scenario.onActivity { activity ->
            val state = Bundle()
            activity.activityResultRegistry.onSaveInstanceState(state)
            val registered = requireNotNull(state.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"))
            val requestCodes = requireNotNull(state.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"))
            val launched = requireNotNull(state.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS"))
            codes = launched.map { key -> requestCodes[registered.indexOf(key)] }.toSet()
        }
        return codes
    }

    private fun deliver(code: Int, file: File) = scenario.onActivity { activity ->
        assertTrue("Original request code must survive selection/recreation", activity.activityResultRegistry.dispatchResult(
            code, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
    }

    private fun observeImport(outcome: HistoryLifetimeOutcome, receipt: AtomicReference<HistoryImportOutcome>): HistoryLifetimeOutcome {
        if (outcome !is HistoryLifetimeOutcome.Bound) return outcome
        val access = outcome.access
        return HistoryLifetimeOutcome.Bound(object : HistoryRuntimeAccess by access {
            override fun importHistory(text: String, now: Instant) = access.importHistory(text, now).also(receipt::set)
        })
    }
}
