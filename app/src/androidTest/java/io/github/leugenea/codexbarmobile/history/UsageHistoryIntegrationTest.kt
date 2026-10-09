package io.github.leugenea.codexbarmobile.history

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Hosted production-default owner + actual Keystore/SQLite, with original synthetic transport only. */
@RunWith(AndroidJUnit4::class)
class UsageHistoryIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val originalFactory = NativeConnection.factory
    private lateinit var context: Context
    private lateinit var root: File
    private val fake = SyntheticSamplingTransport()
    private val gates = mutableListOf<SamplingGate>()
    private var hooks: HistoryStorageHooks? = null
    private var observer = Any()

    @Before fun prepare() {
        root = File(application.noBackupFilesDir, "synthetic-sampling-${UUID.randomUUID()}").canonicalFile
        assertTrue(root.mkdir())
        context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        NativeConnection.factory = { createOwner() }
        NativeConnection.resetForTests()
    }

    private fun createOwner(): ConnectionController {
        val controlled = hooks ?: return NativeConnection.create(context, fake, fake.clock, fake::pause)
        val credentials = KeystoreCredentialStore(context, NativeConnection.session(context))
        return ConnectionController(credentials, DeviceCodeAuthenticator(fake, credentials, fake.clock, fake::pause),
            NativeFeasibilityReader(fake, fake.clock, fake::pause), CoroutineScope(SupervisorJob() + Dispatchers.IO),
            history = HistoryLifetimeCoordinator(SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(historyDirectory(), hooks = controlled))))
    }

    @After fun cleanup() {
        gates.forEach { it.release() }
        try { NativeConnection.resetForTests() }
        finally {
            NativeConnection.factory = originalFactory
            try {
                val store = KeystoreCredentialStore(context, NativeConnection.session(context))
                store.delete(store.openSession())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun admittedEndpointPersistsThroughFreshRuntimeDormancyAndLogoutRejectsLateLoginData() {
        val current = login()
        history(current, "first accepted usage persisted") { it.points.size == 1 }
        val first = current.historySnapshots.value
        assertEquals(2L, fake.gets.get())
        assertEquals(5L, fake.requests.get()) // Three synthetic auth POSTs plus the two quota GETs.
        assertEquals(listOf(1L), first.storage!!.entries.map { it.id.ordinal })
        assertEquals(Instant.ofEpochSecond(1_800_000_000), first.points.single().observedAt)
        assertEquals(BigDecimal("12.375"), first.points.single().usedPercent)
        val comparison = first.entries.first().windows.first().comparison as UsedComparison.Available
        assertEquals(BigDecimal("80"), comparison.baselinePercent)
        assertEquals(BigDecimal("-67.625"), comparison.deltaPercentagePoints)
        assertTrue(File(historyDirectory(), "history.db").exists())
        assertEquals(File(root, "usage-history").canonicalFile, historyDirectory())
        assertTrue(historyDirectory().toPath().startsWith(context.applicationContext.noBackupFilesDir.toPath()))

        val count = fake.gets.get()
        val totalRequests = fake.requests.get()
        NativeConnection.resetForTests()
        val restored = owner(); phase(restored, ConnectionPhase.RESTORED)
        assertEquals(count, fake.gets.get())
        assertEquals(totalRequests, fake.requests.get())
        assertTrue(restored.historySnapshots.value.points.isEmpty())
        restored.usageForeground(observer, true); settled(restored)
        history(restored, "isolated fresh-runtime history read without provider activation") { it.points.size == 1 }
        assertEquals(count, fake.gets.get())
        assertEquals(totalRequests, fake.requests.get())
        assertEquals(first.partition, restored.historySnapshots.value.partition)
        assertNotSame(first.generation, restored.historySnapshots.value.generation)
        fake.advance(1_000)
        restored.readUsage(); phase(restored, ConnectionPhase.OBSERVED)
        history(restored, "fresh runtime observation is a new clock segment") { it.points.size == 2 }
        assertEquals(count + 2, fake.gets.get())
        assertTrue(BreakReason.NEW_CLOCK_EPOCH in restored.historySnapshots.value.points.last().segment.breaks)
        fake.heldPath = ReadOperation.USAGE.path
        restored.readUsage(); val late = fake.awaitHeld("old usage request reached")
        restored.signOut(); phase(restored, ConnectionPhase.SIGNED_OUT)
        assertTrue(late.cancelled)
        assertDeletion()
        assertTrue(restored.historySnapshots.value.points.isEmpty())
        fake.heldPath = null
        restored.connect(); phase(restored, ConnectionPhase.OBSERVED)
        history(restored, "new login owns only its own sample") { it.points.size == 1 }
        val next = restored.historySnapshots.value
        assertNotEquals(first.partition, next.partition)
        assertEquals(listOf(1L), next.storage!!.entries.map { it.id.ordinal })
        val nextGets = fake.gets.get()
        val nextRequests = fake.requests.get()
        late.reply(fake.usage); settled(restored)
        assertEquals(nextGets, fake.gets.get())
        assertEquals(nextRequests, fake.requests.get())
        assertEquals(next.partition, restored.historySnapshots.value.partition)
        assertEquals(1, restored.historySnapshots.value.points.size)
    }

    @Test fun partialEndpointsPreserveActualUsageAndIndependentInventoryClocksWithoutReplay() {
        fake.inventory = response("{}", 403)
        val current = login()
        history(current, "usage succeeds independently of inventory") { it.points.size == 1 && it.live.inventory.error == ReadError.FORBIDDEN }
        fake.usage = response("{}", 403)
        fake.inventory = response("""{"available_count":0,"credits":[]}""")
        fake.advance(1_000)
        current.readUsage(); phase(current, ConnectionPhase.OBSERVED)
        history(current, "inventory-only attempt settles without sample") { it.live.inventory.sourceObservedAt == Instant.ofEpochSecond(1_800_000_001) }
        assertEquals(1, current.historySnapshots.value.points.size)
        assertEquals(listOf(1L), current.historySnapshots.value.storage!!.entries.map { it.id.ordinal })
        assertEquals(Instant.ofEpochSecond(1_800_000_000), current.historySnapshots.value.live.usage.sourceObservedAt)
        assertEquals(ReadError.FORBIDDEN, current.historySnapshots.value.live.usage.error)
        assertEquals(4L, fake.gets.get())
        fake.usage = response(SyntheticSamplingTransport.USAGE)
        fake.inventory = response("{}", 403)
        fake.advance(1_000)
        current.readUsage(); phase(current, ConnectionPhase.OBSERVED)
        history(current, "successful usage after failure has explicit gap") { it.points.size == 2 }
        val snapshot = current.historySnapshots.value
        assertEquals(listOf(1L, 2L, 3L), snapshot.storage!!.entries.map { it.id.ordinal })
        assertEquals(HistoryGap.READ_ERROR, snapshot.storage.entries[1].gap)
        assertTrue(BreakReason.READ_ERROR in snapshot.points.last().segment.breaks)
        assertEquals(6L, fake.gets.get())
    }

    @Test fun actualHeldWriteAdmissionAndCommittedWriteAreRevokedBeforeCombinedLogoutCompletion() {
        for (irreversible in listOf(false, true)) {
            NativeConnection.resetForTests()
            val gate = SamplingGate().also { gates += it }
            hooks = object : HistoryStorageHooks {
                override fun beforeWriteAdmission() { if (!irreversible) gate.pause("before write admission") }
                override fun afterWriteAdmission() { if (irreversible) gate.pause("after write admission") }
            }
            val current = login()
            try {
                gate.await("actual recorder SQLite write reached")
                val access = capability(current)
                current.signOut(); settled(current)
                assertEquals(ConnectionPhase.SIGNING_OUT, current.state.value.phase)
                assertEquals(HistoryReadiness.UNAVAILABLE, current.historySnapshots.value.readiness)
                assertTrue(current.historySnapshots.value.points.isEmpty())
                assertTrue(access.read(HistoryReadQuery(access.partition, 1)) is HistoryReadOutcome.Unavailable)
                awaitProtectedRemoval()
            } finally { gate.release() }
            phase(current, ConnectionPhase.SIGNED_OUT)
            assertDeletion()
            assertTrue(current.historySnapshots.value.points.isEmpty())
            hooks = null
            NativeConnection.resetForTests()
            val reopened = owner(); phase(reopened, ConnectionPhase.IDLE)
            assertFalse(File(historyDirectory(), "history.db").exists())
            reopened.signOut(); phase(reopened, ConnectionPhase.SIGNED_OUT)
        }
    }

    @Test fun nativeHistoryReadFailureIsCategoricalAndNeverReplacesLiveUsage() {
        hooks = object : HistoryStorageHooks {
            override fun beforeRead() { throw java.io.IOException() }
        }
        val current = login()
        history(current, "native history error is explicit") { it.readiness == HistoryReadiness.ERROR && it.lostSamples == 1L }
        assertEquals(HistoryUnavailable.READ_FAILURE, current.historySnapshots.value.storageReason)
        assertNotNull(current.state.value.refresh.usage.success)
        assertEquals(ConnectionPhase.OBSERVED, current.state.value.phase)
        assertEquals(2L, fake.gets.get())
        assertTrue(current.historySnapshots.value.points.isEmpty())
        current.signOut(); phase(current, ConnectionPhase.SIGNED_OUT)
        assertDeletion()
    }

    @Test fun composeForegroundLossRecreationAndFinishRetireCurrentlyUsableHistoryPorts() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val current = owner()
        lateinit var afterRecreation: HistoryRuntimeAccess
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("connection-tab").performClick()
            compose.waitForIdle()
            current.connect(); phase(current, ConnectionPhase.OBSERVED)
            composeHistory(current, "Compose observer records connect usage") { it.points.size == 1 }
            val initial = capability(current)
            scenario.moveToState(Lifecycle.State.CREATED)
            composeHistory(current, "STOP revokes history") { it.readiness == HistoryReadiness.UNAVAILABLE }
            assertTrue(initial.read(HistoryReadQuery(initial.partition, 1)) is HistoryReadOutcome.Unavailable)
            fake.advance(1_000)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            composeHistory(current, "RESUME records one fresh observation with background break") { it.points.size == 2 }
            assertTrue(BreakReason.BACKGROUND in current.historySnapshots.value.points.last().segment.breaks)
            assertEquals(4L, fake.gets.get())
            val beforeRecreation = current.historySnapshots.value.generation
            scenario.recreate(); compose.waitForIdle()
            composeHistory(current, "recreation crosses STOP/RESUME and adopts a fresh history generation") {
                it.readiness == HistoryReadiness.READY && it.generation !== beforeRecreation
            }
            scenario.onActivity { assertSame(current, it.connection) }
            afterRecreation = capability(current)
            assertTrue(afterRecreation.read(HistoryReadQuery(afterRecreation.partition, 1)) is HistoryReadOutcome.Ready)
        } finally { scenario.close() }
        composeHistory(current, "finish retires the post-recreation capability") { it.readiness == HistoryReadiness.UNAVAILABLE }
        assertTrue(afterRecreation.read(HistoryReadQuery(afterRecreation.partition, 1)) is HistoryReadOutcome.Unavailable)
        assertTrue(KeystoreCredentialStore.file(context, NativeConnection.session(context)).exists())
        assertNotEquals(ConnectionPhase.SIGNED_OUT, current.state.value.phase)
    }

    private fun owner() = NativeConnection.get(context)
    private fun login(): ConnectionController = owner().also {
        phase(it, ConnectionPhase.IDLE)
        it.usageForeground(observer, true); settled(it)
        it.connect(); phase(it, ConnectionPhase.OBSERVED)
    }
    private fun historyDirectory() = File(root, "usage-history").canonicalFile
    private fun capability(current: ConnectionController): HistoryRuntimeAccess {
        val ready = current.session.snapshot() as? SessionResult.Ready ?: throw AssertionError("native sampling session is not adopted")
        return current.historyCapability(ready.envelope.generation) ?: throw AssertionError("native sampling access: ${current.historyAvailability}")
    }
    private fun phase(current: ConnectionController, expected: ConnectionPhase) = runBlocking {
        try { withTimeout(5_000) { current.state.first { it.phase == expected && !it.refresh.refreshing } } }
        catch (_: TimeoutCancellationException) { throw AssertionError("sampling phase $expected: last state=${current.state.value}") }
    }
    private fun settled(current: ConnectionController) = runBlocking {
        try { withTimeout(5_000) { current.commandsSettled() } }
        catch (_: TimeoutCancellationException) { throw AssertionError("sampling owner settled: last state=${current.state.value}") }
    }
    private fun history(current: ConnectionController, step: String, predicate: (HistoryGraphSnapshot) -> Boolean) = runBlocking {
        try { withTimeout(5_000) { current.historySnapshots.first(predicate) } }
        catch (_: TimeoutCancellationException) { throw AssertionError("$step: last readiness=${current.historySnapshots.value.readiness}, points=${current.historySnapshots.value.points.size}") }
    }
    private fun composeHistory(current: ConnectionController, step: String, predicate: (HistoryGraphSnapshot) -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 5_000) { predicate(current.historySnapshots.value) } }
        catch (_: ComposeTimeoutException) { throw AssertionError("$step: last readiness=${current.historySnapshots.value.readiness}, points=${current.historySnapshots.value.points.size}") }
    }
    private fun awaitProtectedRemoval() = runBlocking {
        val slot = NativeConnection.session(context)
        try { withTimeout(5_000) {
            while (KeystoreCredentialStore.file(context, slot).exists()) yield()
        } } catch (_: TimeoutCancellationException) { throw AssertionError("protected removal while history held: last state=file-present") }
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreCredentialStore.alias(context, slot)))
    }
    private fun assertDeletion() {
        val slot = NativeConnection.session(context)
        assertFalse(KeystoreCredentialStore.file(context, slot).exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreCredentialStore.alias(context, slot)))
        assertFalse(File(historyDirectory(), "history.db").exists())
        assertFalse(File(historyDirectory(), "history.db-journal").exists())
    }
    private fun response(body: String, status: Int = 200) = TransportResult.Response.bounded(status, body.toByteArray())
}

private class SamplingGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    fun pause(step: String) {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "$step: last state=held" }
    }
    fun await(step: String) { assertTrue("$step: last state=not-entered", entered.await(5, TimeUnit.SECONDS)) }
    fun release() { released.countDown() }
}

/** No raw/live inputs. A retained callback deliberately ignores cancellation to test the owner fence. */
private class SyntheticSamplingTransport : AuthTransport {
    class Call(private val terminal: (TransportResult) -> Unit) {
        @Volatile var cancelled = false
        fun reply(result: TransportResult) { terminal(result) }
    }
    val gets = AtomicLong()
    val requests = AtomicLong()
    private val millis = AtomicLong()
    val clock = TransportClock { TransportTime(Instant.ofEpochSecond(1_800_000_000).plusMillis(millis.get()), millis.get()) }
    @Volatile var usage = response(USAGE)
    @Volatile var inventory = response("""{"available_count":0,"credits":[]}""")
    @Volatile var heldPath: String? = null
    private val held = CopyOnWriteArrayList<Call>()
    private var reached = CountDownLatch(1)
    fun advance(duration: Long) { millis.addAndGet(duration) }
    suspend fun pause(duration: Long) { require(duration >= 0) } // Synthetic immediate authorization; no real-time sleep.
    fun awaitHeld(step: String): Call {
        assertTrue("$step: last state=not-entered", reached.await(5, TimeUnit.SECONDS))
        reached = CountDownLatch(1)
        return held.last()
    }
    override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit): CancellationHandle {
        requests.incrementAndGet()
        val call = Call(terminal)
        val path = request.url.encodedPath
        if (request is ProviderHttpRequest.Get) gets.incrementAndGet()
        if (path == heldPath) { held += call; reached.countDown() }
        else call.reply(result(path))
        return CancellationHandle { call.cancelled = true }
    }
    private fun result(path: String): TransportResult = when (path) {
        ReadOperation.USAGE.path -> usage
        ReadOperation.RESET_INVENTORY.path -> inventory
        "/api/accounts/deviceauth/usercode" -> response("""{"device_auth_id":"synthetic-sampling-device","user_code":"SYNTHETIC-SAMPLING"}""")
        "/api/accounts/deviceauth/token" -> response("""{"authorization_code":"synthetic-sampling-authorization","code_verifier":"synthetic-sampling-verifier"}""")
        "/oauth/token" -> response("""{"access_token":"synthetic-sampling-access","refresh_token":"synthetic-sampling-refresh"}""")
        else -> throw AssertionError("Unexpected synthetic sampling route")
    }
    companion object {
        const val USAGE = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12.375,"reset_at":1800003600}}}"""
        private fun response(body: String) = TransportResult.Response.bounded(200, body.toByteArray())
    }
}
