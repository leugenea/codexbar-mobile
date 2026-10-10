package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.usage.WindowKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Hosted-only held UI delivery; real recorder/controller and the actual ConnectionHistory body. */
@RunWith(AndroidJUnit4::class)
class HistoryDisplayAuthorityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: HistoryNavigationFixture
    private val observer = HeldHistoryObserver()
    private val now = mutableStateOf(Instant.ofEpochSecond(1_800_000_100))
    private val hostedOwner = mutableStateOf<ConnectionController?>(null)
    @Volatile private var unavailableStorage = false
    private val owner get() = fixture.owner

    @Before fun prepare() {
        fixture = HistoryNavigationFixture()
        fixture.prepare()
        NativeConnection.factory = { createOwner() }
    }

    private fun createOwner(): ConnectionController {
        val credentials = KeystoreCredentialStore(fixture.context, NativeConnection.session(fixture.context))
        val storage = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(File(fixture.root, "usage-history")))
        // Synthetic optional-storage failure, not a controller/store testing API extension.
        val optional = object : HistoryLifetimeStorage by storage {
            override fun stage(): HistoryPartitionOutcome = if (unavailableStorage)
                HistoryPartitionOutcome.Unavailable(HistoryUnavailable.IO_FAILURE) else storage.stage()
        }
        val transport = fixture.transport
        return ConnectionController(credentials, DeviceCodeAuthenticator(transport, credentials, transport.clock, transport::pause),
            NativeFeasibilityReader(transport, transport.clock, transport::pause), CoroutineScope(SupervisorJob() + Dispatchers.IO),
            history = HistoryLifetimeCoordinator(optional))
    }

    @After fun cleanup() {
        observer.release()
        fixture.cleanup()
    }

    @Test fun heldBoundCollectionCannotReplayRetiredFactsOnActualHostReexecution() {
        boundPage()
        mount()
        retainedBoundPage()
        val old = holdPredecessor("bound retirement")
        try {
            owner.signOut()
            settled("sign-out processed")
            await("exact old context revoked") { old.displayPermission!!.current() == null }
            phase(ConnectionPhase.SIGNED_OUT)
            challenge(old, HistoryReadiness.UNAVAILABLE, "bound retirement")
            assertEquals(7L, fixture.transport.requests.get())
            assertEquals(4L, fixture.transport.gets.get())
        } finally { observer.release() }
        await("retirement delivered after release") { observer.last?.source?.displayPermission == null }
        compose.onNodeWithTag("history-readiness").assertTextEquals("unavailable")
    }

    @Test fun heldBoundCollectionRejectsReplacementThenShowsOnlySuccessorFacts() {
        boundPage()
        mount()
        retainedReplacementPage()
        val old = holdPredecessor("bound replacement")
        try {
            fixture.transport.usageStatus = 200
            fixture.transport.advance(1000)
            owner.connect()
            settled("replacement command processed")
            phase(ConnectionPhase.OBSERVED)
            await("successor recorder admitted its own point") {
                val source = owner.historySnapshots.value
                source.partition != old.partition && source.storage?.lastAdmitted?.ordinal == 1L
            }
            assertNotSame(old.displayPermission!!.context, old.displayPermission.current())
            assertNotNull(old.displayPermission.current()!!.generation)
            challenge(old, HistoryReadiness.LOADING, "bound replacement")
        } finally { observer.release() }
        await("successor facts reach the real host") { observer.last?.display?.points?.size == 1 }
        val successor = observer.last!!.display
        assertNotEquals(old.partition, successor.partition)
        assertEquals(HistoryNavigation().query, successor.query)
        assertEquals(listOf(1L), successor.entries.map { it.source.id.ordinal })
        assertEquals(Instant.ofEpochSecond(1_800_000_002), successor.live.usage.sourceObservedAt)
        assertEquals(Instant.ofEpochSecond(1_800_000_002), successor.live.inventory.sourceObservedAt)
        compose.onNodeWithTag("history-page-cursor").assertTextEquals("Exclusive admission cursor: 0 · Page limit: 32")
        compose.onNodeWithTag("history-select-FIVE_HOUR").assertIsSelected()
        compose.onNodeWithTag("history-select-WEEKLY").assertIsNotSelected()
        compose.onNodeWithTag("history-first-page").assertIsNotEnabled()
        compose.onNodeWithTag("history-details-toggle").assertTextEquals("Show exact details · Page entries: 1")
        assertNoOldClocks(old)
        assertEquals(12L, fixture.transport.requests.get())
        assertEquals(6L, fixture.transport.gets.get())
        dormantNavigationAddsNoRequests()
    }

    @Test fun heldNullDiagnosticsKeepCurrentLossButNeverReplayAfterRetireSuccessorRetire() {
        unavailableStorage = true
        login()
        fixture.transport.advance(1000)
        owner.readUsage()
        await("real recorder counts two unconfirmed admissions") { owner.historySnapshots.value.lostSamples == 2L }
        mount()
        await("current null context diagnostic rendered") { observer.last?.display?.lostSamples == 2L }
        click("history-select-WEEKLY")
        val old = holdPredecessor("null diagnostic retirement")
        assertNull(old.generation)
        assertEquals(2L, observer.last!!.display.lostSamples)
        assertEquals(HistoryLiveMetadata(), observer.last!!.display.live)
        compose.onNodeWithTag("history-loss").assertTextEquals("Samples lost or unconfirmed: 2; history is incomplete.")
        try {
            owner.cancel()
            settled("null diagnostic retirement processed")
            await("null diagnostic context revoked") { old.displayPermission!!.current() == null }
            challenge(old, HistoryReadiness.UNAVAILABLE, "null diagnostic retirement")
            unavailableStorage = false
            fixture.transport.advance(1000)
            owner.connect()
            phase(ConnectionPhase.OBSERVED)
            await("bound successor after null diagnostics") { owner.historySnapshots.value.generation != null }
            challenge(old, HistoryReadiness.LOADING, "null diagnostic bound successor")
            owner.cancel()
            settled("bound successor retirement processed")
            await("successor context cleared") { old.displayPermission!!.current() == null }
            assertNull(owner.historySnapshots.value.generation)
            challenge(old, HistoryReadiness.UNAVAILABLE, "null diagnostic successor retirement")
        } finally { observer.release() }
        await("cleared source collected") { observer.last?.source?.displayPermission == null }
        unavailableStorage = true
        fixture.transport.advance(1000)
        owner.connect()
        phase(ConnectionPhase.OBSERVED)
        await("fresh null context has only its own loss") { observer.last?.display?.lostSamples == 1L }
        val fresh = observer.last!!.source
        assertNull(fresh.generation)
        assertNotSame(old.displayPermission!!.context, fresh.displayPermission!!.context)
        assertEquals(1L, fresh.lostSamples)
        compose.onNodeWithTag("history-loss").assertTextEquals("Samples lost or unconfirmed: 1; history is incomplete.")
        assertEquals(17L, fixture.transport.requests.get())
        assertEquals(8L, fixture.transport.gets.get())
    }

    @Test fun heldPublishedWindowAndPageCollectionHidesSupersededFactsOnActualHostReexecution() {
        boundPage()
        mount()
        retainedBoundPage()
        val selections = listOf(
            "history-select-WEEKLY" to HistoryNavigation(kind = WindowKind.WEEKLY),
            "history-next-page" to HistoryNavigation(kind = WindowKind.WEEKLY, after = ObservationId(32)),
            "history-first-page" to HistoryNavigation(kind = WindowKind.WEEKLY),
            "history-select-FIVE_HOUR" to HistoryNavigation())
        for ((index, action) in selections.withIndex()) {
            val (tag, selection) = action
            val step = "selection ${index + 1}: $tag -> ${selection.query}"
            val old = holdPredecessor(step)
            assertTrue("$step: a real previously published page must reach the host", old.points.isNotEmpty())
            try {
                click(tag)
                await("$step: authoritative successor query published while old collection stays held") {
                    val source = owner.historySnapshots.value
                    source.query == selection.query && source.storage != null && source.readiness == HistoryReadiness.READY
                }
                assertSame("query supersession must not masquerade as lifecycle retirement",
                    old.displayPermission!!.context, old.displayPermission.current())
                assertEquals(selection.query, observer.last!!.display.query)
                challenge(old, HistoryReadiness.LOADING, step)
            } finally { observer.release() }
            await("$step: only selected successor page reaches host after collection release") {
                val display = observer.last?.display
                display?.query == selection.query && display.entries.size == if (selection.after == null) 32 else 3
            }
        }
        assertEquals(7L, fixture.transport.requests.get())
        assertEquals(4L, fixture.transport.gets.get())
    }

    private fun login() {
        phase(ConnectionPhase.IDLE)
        owner.usageForeground(fixture.observer, true)
        settled("foreground registered")
        owner.connect()
        phase(ConnectionPhase.OBSERVED)
    }

    private fun boundPage() {
        fixture.transport.inventoryStatus = 403
        login()
        await("first accepted usage written") { owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
        fixture.seedLocalPage()
        fixture.transport.usageStatus = 403
        fixture.transport.inventoryStatus = 200
        fixture.transport.advance(1000)
        owner.readUsage()
        await("independent clocks settled") {
            val source = owner.historySnapshots.value
            source.live.usage.error == ReadError.FORBIDDEN && source.live.inventory.status == 200 && !source.live.refreshing
        }
    }

    private fun mount() {
        hostedOwner.value = owner
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionHistory(requireNotNull(hostedOwner.value), now.value, observer)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun retainedBoundPage(): HistoryGraphSnapshot {
        await("host collected full first page with independent clocks") {
            val source = observer.last?.display
            source?.entries?.size == 32 && source.live.usage.sourceObservedAt != null && source.live.inventory.sourceObservedAt != null
        }
        click("history-details-toggle")
        compose.onNodeWithTag("history-detail", useUnmergedTree = true).assertExists()
        compose.waitForIdle()
        val source = observer.last!!.source
        assertEquals(HistoryNavigation().query, source.query)
        assertEquals(32, source.points.size)
        assertTrue(source.hasMore)
        assertNotEquals(source.live.usage.sourceObservedAt, source.live.inventory.sourceObservedAt)
        return source
    }

    private fun holdPredecessor(step: String): HistoryGraphSnapshot {
        observer.hold()
        // A passed-gate delivery may still be awaiting composition. Await that exact
        // receipt, not owner.value: newer owner publications are allowed to be parked.
        await("$step: host applied the last delivery admitted before hold") {
            val delivered = observer.deliverySource
            delivered != null && observer.last?.source === delivered
        }
        return requireNotNull(observer.deliverySource)
    }

    private fun challenge(old: HistoryGraphSnapshot, readiness: HistoryReadiness, step: String) {
        await("$step: UI collector independently parked") { observer.blocked != null }
        val query = observer.last!!.display.query
        val expected = now.value.plusSeconds(1)
        compose.runOnUiThread { now.value = expected }
        await("$step: actual host pass with changed evaluation input") { observer.last?.now == expected }
        val pass = observer.last!!
        assertSame("$step: held source must still be the exact predecessor; " +
            "expected=${snapshotState(old)}, actual=${snapshotState(pass.source)}", old, pass.source)
        assertEquals(old.query, pass.source.query)
        assertEquals(readiness, pass.display.readiness)
        assertEquals("the challenged host selection must not change", query, pass.display.query)
        assertScrubbed(pass.display)
        compose.onNodeWithTag("history-readiness").assertTextEquals(if (readiness == HistoryReadiness.LOADING)
            "History loading; retained page may be shown" else "unavailable")
        compose.onNodeWithTag("history-next-page").assertIsNotEnabled()
        compose.onNodeWithTag("history-page-ordinals").assertDoesNotExist()
        compose.onNodeWithTag("history-details-toggle").assertDoesNotExist()
        compose.onNodeWithTag("history-detail", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("history-loss").assertDoesNotExist()
        compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).assertDoesNotExist()
        assertNoOldClocks(old)
        historyText("Admission ordinal: 1").assertCountEquals(0)
        historyText("Measured used percent (exact %): 12.375").assertCountEquals(0)
        historyText("Measured used percent (exact %): 87.5").assertCountEquals(0)
        historyText("History recorder problem: storage unavailable").assertCountEquals(0)
    }

    private fun assertScrubbed(display: HistoryGraphSnapshot) {
        assertNull(display.generation)
        assertNull(display.partition)
        assertNull(display.storage)
        assertNull(display.problem)
        assertNull(display.storageReason)
        assertEquals(0L, display.lostSamples)
        assertTrue(display.entries.isEmpty())
        assertTrue(display.points.isEmpty())
        assertEquals(HistoryLiveMetadata(), display.live)
        assertFalse(display.hasMore)
    }

    private fun retainedReplacementPage(): HistoryGraphSnapshot {
        retainedBoundPage()
        click("history-select-WEEKLY")
        await("predecessor weekly first page collected") {
            observer.last?.display.let { it?.query == HistoryNavigation(kind = WindowKind.WEEKLY).query && it.entries.size == 32 }
        }
        click("history-next-page")
        await("predecessor weekly final page collected") { observer.last?.display?.entries?.map { it.source.id.ordinal } == listOf(33L, 34L, 35L) }
        click("history-details-toggle")
        compose.onNodeWithTag("history-detail", useUnmergedTree = true).assertExists()
        val old = observer.last!!.source
        assertEquals(HistoryNavigation(kind = WindowKind.WEEKLY, after = ObservationId(32)).query, old.query)
        assertEquals(3, old.points.size)
        return old
    }

    private fun assertNoOldClocks(old: HistoryGraphSnapshot) {
        listOf(old.live.usage.sourceObservedAt, old.live.inventory.sourceObservedAt).filterNotNull().forEach {
            historyText(it.toString(), substring = true).assertCountEquals(0)
        }
    }

    private fun historyText(text: String, substring: Boolean = false) = compose.onAllNodes(
        hasAnyAncestor(hasTestTag("connection-history")) and hasText(text, substring = substring), useUnmergedTree = true)

    private fun dormantNavigationAddsNoRequests() {
        owner.usageForeground(fixture.observer, false)
        settled("background pause processed")
        fixture.freshRuntime()
        val restored = owner
        phase(ConnectionPhase.RESTORED)
        restored.usageForeground(fixture.observer, true)
        settled("dormant foreground read admitted")
        compose.runOnUiThread { hostedOwner.value = restored }
        await("dormant local history restored through host") { observer.last?.display?.generation === restored.historySnapshots.value.generation && observer.last?.display?.entries?.size == 1 }
        click("history-select-WEEKLY")
        await("dormant selected page settled through host") { observer.last?.display?.query == HistoryNavigation(kind = WindowKind.WEEKLY).query && observer.last?.display?.entries?.size == 1 }
        assertEquals(ConnectionPhase.RESTORED, restored.state.value.phase)
        assertEquals(12L, fixture.transport.requests.get())
        assertEquals(6L, fixture.transport.gets.get())
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun phase(expected: ConnectionPhase) = await("phase $expected") {
        owner.state.value.let { it.phase == expected && !it.refresh.refreshing }
    }

    private fun settled(step: String) {
        try { runBlocking { withTimeout(5_000) { owner.commandsSettled() } } }
        catch (error: TimeoutCancellationException) {
            throw AssertionError("$step: owner receipt timed out; phase=${owner.state.value.phase}, " +
                "owner=${snapshotState(owner.historySnapshots.value)}, source=${snapshotState(observer.last?.source)}", error)
        }
    }

    private fun snapshotState(source: HistoryGraphSnapshot?): String = source?.let {
        "id=${System.identityHashCode(it)}, query=${it.query}, readiness=${it.readiness}, " +
            "generation=${it.generation?.let(System::identityHashCode)}, " +
            "context=${it.displayPermission?.context?.let(System::identityHashCode)}, " +
            "page=${it.storage?.let(System::identityHashCode)}, points=${it.points.size}, refreshing=${it.live.refreshing}"
    } ?: "null"

    private fun await(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 8_000, condition = condition) }
        catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: phase=${owner.state.value.phase}, " +
                "owner=${snapshotState(owner.historySnapshots.value)}, source=${snapshotState(observer.last?.source)}, " +
                "display=${snapshotState(observer.last?.display)}, delivery=${snapshotState(observer.deliverySource)}, " +
                "blocked=${snapshotState(observer.blocked)}", error)
        }
    }
}

private class HeldHistoryObserver : HistoryUiObserver {
    data class Pass(val source: HistoryGraphSnapshot, val display: HistoryGraphSnapshot, val now: Instant?)
    private val passes = CopyOnWriteArrayList<Pass>()
    private val deliveryLock = Any()
    private var gate: CompletableDeferred<Unit>? = null
    /** Last snapshot admitted past the gate, not necessarily projected by Compose yet. */
    @Volatile var deliverySource: HistoryGraphSnapshot? = null
        private set
    @Volatile var blocked: HistoryGraphSnapshot? = null
        private set
    val last get() = passes.lastOrNull()
    fun hold() = synchronized(deliveryLock) {
        check(gate == null)
        blocked = null
        gate = CompletableDeferred()
    }
    fun release() {
        val held = synchronized(deliveryLock) { gate.also { gate = null } }
        held?.complete(Unit)
    }
    override fun snapshots(source: StateFlow<HistoryGraphSnapshot>): Flow<HistoryGraphSnapshot> = source.onEach { snapshot ->
        while (true) {
            // Admit and record atomically with hold(). Once held, this receipt is frozen;
            // the host must acknowledge it before the test captures its predecessor.
            val held = synchronized(deliveryLock) { gate.also { if (it == null) deliverySource = snapshot } }
            if (held == null) break
            blocked = snapshot
            held.await()
            // A released delivery must also respect a hold installed before it resumed.
        }
    }
    override fun projected(source: HistoryGraphSnapshot, display: HistoryGraphSnapshot, now: Instant?) {
        passes += Pass(source, display, now)
    }
}
