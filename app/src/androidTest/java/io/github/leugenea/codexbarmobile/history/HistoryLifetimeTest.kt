package io.github.leugenea.codexbarmobile.history

import android.content.Context
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
import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Hosted actual Keystore + framework SQLite with synthetic auth. Fresh holder is NOT process death. */
@RunWith(AndroidJUnit4::class)
class HistoryLifetimeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val originalFactory = NativeConnection.factory
    private lateinit var directory: File
    private lateinit var slot: UUID
    private lateinit var credentials: KeystoreCredentialStore
    private val fake = SyntheticTransport()
    private var hooks: HistoryStorageHooks = object : HistoryStorageHooks {}
    private var saveGate: Gate? = null
    private val gates = mutableListOf<Gate>()
    private var waitMillis = 5_000L

    @Before fun prepare() {
        directory = File(context.noBackupFilesDir, "synthetic-lifetime-${UUID.randomUUID()}").canonicalFile
        slot = UUID.randomUUID()
        credentials = KeystoreCredentialStore(context, slot)
        NativeConnection.factory = { createOwner() }
        NativeConnection.resetForTests()
    }

    @After fun cleanup() {
        gates.forEach { it.release() }
        fake.releaseRefresh()
        try { NativeConnection.resetForTests() }
        finally {
            NativeConnection.factory = originalFactory
            try { credentials.delete(credentials.openSession()) }
            finally { directory.deleteRecursively() }
        }
    }

    private fun createOwner(): ConnectionController {
        credentials = KeystoreCredentialStore(context, slot)
        val backing = credentials
        val protected = object : CredentialStore by backing {
            override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> {
                saveGate?.pause("credential save")
                return backing.replace(envelope, cancellation)
            }
        }
        val history = HistoryLifetimeCoordinator(SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(directory, hooks = hooks)))
        return ConnectionController(protected, DeviceCodeAuthenticator(fake, protected, fake.clock, fake::pause),
            NativeFeasibilityReader(fake, fake.clock, fake::pause), CoroutineScope(SupervisorJob() + Dispatchers.IO),
            storageWaitMillis = waitMillis, history = history)
    }
    private fun owner() = NativeConnection.get(context)
    private fun freshOwner(): ConnectionController { NativeConnection.resetForTests(); return owner() }
    private fun gate() = Gate().also { gates += it }
    private fun login(): ConnectionController = owner().also { phase(it, ConnectionPhase.IDLE); it.connect(); phase(it, ConnectionPhase.OBSERVED) }
    private fun phase(owner: ConnectionController, expected: ConnectionPhase) = runBlocking {
        try { withTimeout(5_000) { owner.state.first {
            it.phase == expected && (expected != ConnectionPhase.OBSERVED || !it.refresh.refreshing)
        } } } catch (_: TimeoutCancellationException) { throw AssertionError("native lifetime $expected: last state=${owner.state.value}") }
    }
    private fun settled(owner: ConnectionController) = runBlocking {
        try { withTimeout(5_000) { owner.commandsSettled() } }
        catch (_: TimeoutCancellationException) { throw AssertionError("owner commands settled: last state=${owner.state.value}") }
    }
    private fun capability(owner: ConnectionController): HistoryRuntimeAccess {
        val current = owner.session.snapshot() as? SessionResult.Ready ?: throw AssertionError("native adopted session required")
        return owner.historyCapability(current.envelope.generation) ?: throw AssertionError("native history: ${owner.historyAvailability}")
    }
    private fun admission(access: HistoryRuntimeAccess) = HistoryAdmission(access.partition, ObservationId(1), HistoryClock(ClockEpoch(UUID(0, 19)), 1000),
        HistoryEvent.Observed(UsageNormalizer.normalize(UsageInput(
            Input.Value(WindowInput(Input.Value(18000L), Input.Value(java.math.BigDecimal("12.375")), Input.Value(1_800_003_600L), Input.Missing)),
            Input.Missing), Instant.ofEpochSecond(1_800_000_000))))
    private fun append(access: HistoryRuntimeAccess) { assertTrue("native append", access.append(admission(access)) is HistoryAppendOutcome.Stored) }
    private fun page(access: HistoryRuntimeAccess): HistoryReadSnapshot {
        val result = access.read(HistoryReadQuery(access.partition, 10))
        assertTrue("native read: fixed outcome=${result.javaClass.simpleName}", result is HistoryReadOutcome.Ready)
        return (result as HistoryReadOutcome.Ready).snapshot
    }
    private fun revoked(access: HistoryRuntimeAccess) {
        val read = access.read(HistoryReadQuery(access.partition, 10))
        val append = access.append(admission(access))
        assertTrue("retired read is unavailable", read is HistoryReadOutcome.Unavailable)
        assertTrue("retired append is unavailable", append is HistoryAppendOutcome.Unavailable)
        val accepted = setOf(HistoryUnavailable.PARTITION_REVOKED, HistoryUnavailable.CLOSED)
        assertTrue((read as HistoryReadOutcome.Unavailable).reason in accepted)
        assertTrue((append as HistoryAppendOutcome.Unavailable).reason in accepted)
    }
    private fun awaitCredentialsAbsent(step: String) = runBlocking {
        await(step, { KeystoreCredentialStore.file(context, slot).exists().toString() }) {
            !KeystoreCredentialStore.file(context, slot).exists()
        }
        assertCredentialsAbsent()
    }
    private fun assertCredentialsAbsent() {
        assertFalse(KeystoreCredentialStore.file(context, slot).exists())
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keys.containsAlias(KeystoreCredentialStore.alias(context, slot)))
    }
    private fun seedCredentials() {
        val generation = credentials.openSession()
        assertTrue(credentials.replace(CredentialEnvelope(generation, SensitiveValue.copyOf("synthetic-lifetime-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-lifetime-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
    }

    @Test fun nativeFreshLoginRotationReopenAndReloginNeverJoin() {
        val first = login(); val old = capability(first); append(old)
        val before = fake.exchanges.get()
        first.readUsage(refreshSession = true)
        runBlocking { await("rotation requested", { fake.exchanges.get().toString() }) { fake.exchanges.get() > before } }
        phase(first, ConnectionPhase.OBSERVED)
        assertEquals(old.partition, capability(first).partition)
        val restored = freshOwner(); phase(restored, ConnectionPhase.RESTORED)
        val continuing = capability(restored)
        assertEquals(old.partition, continuing.partition)
        assertEquals(1, page(continuing).entries.size)
        restored.signOut(); phase(restored, ConnectionPhase.SIGNED_OUT)
        assertCredentialsAbsent(); assertFalse(File(directory, "history.db").exists()); revoked(continuing)
        restored.connect(); phase(restored, ConnectionPhase.OBSERVED)
        val next = capability(restored)
        assertNotEquals(old.partition, next.partition)
        assertEquals(HistoryContent.EMPTY, page(next).content)
        revoked(old)
    }

    @Test fun nativeCancelAndHolderShutdownRetireButRestoreContinuingLifetime() {
        val first = login(); val access = capability(first); append(access)
        first.cancel(); phase(first, ConnectionPhase.CANCELLED); revoked(access)
        assertTrue(KeystoreCredentialStore.file(context, slot).exists())
        val restored = freshOwner(); phase(restored, ConnectionPhase.RESTORED)
        assertEquals(access.partition, capability(restored).partition)
        assertEquals(1, page(capability(restored)).entries.size)
        val beforeShutdown = capability(restored)
        val reopened = freshOwner(); phase(reopened, ConnectionPhase.RESTORED)
        revoked(beforeShutdown)
        assertEquals(access.partition, capability(reopened).partition)
    }

    @Test fun nativeActivityFinishAndRecreationKeepProcessHistoryOwner() {
        val first = login(); val access = capability(first); append(access)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitForIdle()
            scenario.onActivity { assertSame(first, it.connection) }
            compose.onNodeWithTag("connection-tab").performClick()
            compose.waitForIdle()
            scenario.recreate(); compose.waitForIdle()
            scenario.onActivity { assertSame(first, it.connection) }
        } finally { scenario.close() }
        assertSame(first, owner())
        runBlocking { await("Activity finish retires history port", { first.historyAvailability.name }) {
            first.historyAvailability == HistoryAvailability.UNAVAILABLE
        } }
        revoked(access)
        val restored = freshOwner(); phase(restored, ConnectionPhase.RESTORED)
        assertEquals(access.partition, capability(restored).partition)
        assertEquals(1, page(capability(restored)).entries.size)
    }

    @Test fun nativeForegroundLossRetainsLifetimeWithoutPretendingLogout() {
        val first = login(); val access = capability(first); append(access)
        val observer = Any()
        first.usageForeground(observer, true); settled(first)
        first.usageForeground(observer, false); settled(first)
        revoked(access)
        assertEquals(HistoryAvailability.UNAVAILABLE, first.historyAvailability)
        assertTrue(KeystoreCredentialStore.file(context, slot).exists())
        assertNotEquals(ConnectionPhase.SIGNED_OUT, first.state.value.phase)
        first.usageForeground(observer, true)
        runBlocking { await("foreground re-adopts native history", { first.historyAvailability.name }) {
            first.historyAvailability == HistoryAvailability.AVAILABLE
        } }
        assertNotSame(access, capability(first))
        assertEquals(access.partition, capability(first).partition)
        assertEquals(1, page(capability(first)).entries.size)
        first.usageForeground(observer, false); settled(first)
    }

    @Test fun nativeTerminalRefreshWaitsForBothCleanupAfterForegroundLoss() {
        val first = login(); val access = capability(first); append(access)
        val deletion = gate()
        hooks = object : HistoryStorageHooks { override fun afterTombstone() { deletion.pause("terminal tombstone") } }
        val current = freshOwner(); phase(current, ConnectionPhase.RESTORED)
        val adopted = capability(current)
        fake.terminalRefresh = true
        fake.holdRefresh = true
        val observer = Any()
        current.usageForeground(observer, true); settled(current)
        try {
            current.readUsage(refreshSession = true)
            assertTrue("refresh request reached: last state=not-entered", fake.refreshReached.await(5, TimeUnit.SECONDS))
            current.usageForeground(observer, false); settled(current)
            runBlocking { await("read waiter retired before terminal refresh", { current.state.value.refresh.refreshing.toString() }) {
                !current.state.value.refresh.refreshing
            } }
            revoked(adopted)
            assertTrue(KeystoreCredentialStore.file(context, slot).exists())
            fake.releaseRefresh()
            deletion.await("terminal removal entered")
            assertNotEquals(ConnectionPhase.REAUTH_REQUIRED, current.state.value.phase)
            revoked(adopted)
            awaitCredentialsAbsent("terminal protected removal while history held")
        } finally { deletion.release() }
        phase(current, ConnectionPhase.REAUTH_REQUIRED)
        assertFalse(File(directory, "history.db").exists())
        assertCredentialsAbsent()
    }

    @Test fun nativeFailedHistoryDeletionStillRemovesKeystoreAndQuarantinesFreshHolder() {
        val first = login(); val old = capability(first); append(old)
        hooks = object : HistoryStorageHooks { override fun afterTombstone() { throw IOException() } }
        val current = freshOwner(); phase(current, ConnectionPhase.RESTORED)
        current.signOut(); phase(current, ConnectionPhase.FAILED)
        assertEquals(ConnectionProblem.STORAGE, current.state.value.problem)
        assertCredentialsAbsent(); revoked(old)
        hooks = object : HistoryStorageHooks {}
        val reopened = freshOwner(); phase(reopened, ConnectionPhase.IDLE)
        assertFalse(File(directory, "history.db").exists())
        reopened.connect(); phase(reopened, ConnectionPhase.OBSERVED)
        assertNotEquals(old.partition, capability(reopened).partition)
        assertEquals(HistoryContent.EMPTY, page(capability(reopened)).content)
    }

    @Test fun nativeCredentialFileRemovalFailureStillDeletesKeyAndHistoryWithoutFalseSuccess() {
        val current = login(); val old = capability(current); append(old)
        val value = KeystoreCredentialStore.file(context, slot)
        assertTrue(value.delete())
        assertTrue(value.mkdir())
        val obstruction = File(value, "synthetic-delete-obstruction")
        obstruction.writeBytes(byteArrayOf(1))
        try {
            current.signOut(); phase(current, ConnectionPhase.FAILED)
            assertEquals(ConnectionProblem.STORAGE, current.state.value.problem)
            assertTrue(value.exists())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias(KeystoreCredentialStore.alias(context, slot)))
            assertFalse(File(directory, "history.db").exists())
            assertTrue(File(value.parentFile, "rotation-pending").exists())
            revoked(old)
            val count = fake.requests.get()
            val blocked = freshOwner(); phase(blocked, ConnectionPhase.FAILED)
            assertEquals(ConnectionProblem.STORAGE, blocked.state.value.problem)
            assertEquals(count, fake.requests.get())
            assertNotEquals(HistoryAvailability.AVAILABLE, blocked.historyAvailability)
        } finally { obstruction.delete(); value.delete() }
        val recovered = freshOwner(); phase(recovered, ConnectionPhase.REAUTH_REQUIRED)
        assertCredentialsAbsent()
        recovered.connect(); phase(recovered, ConnectionPhase.OBSERVED)
        assertNotEquals(old.partition, capability(recovered).partition)
        assertEquals(HistoryContent.EMPTY, page(capability(recovered)).content)
    }

    @Test fun nativeHeldHistoryDeletionTimeoutBlocksSuccessorAndOldTicketCannotEraseNewPartition() {
        val first = login(); val old = capability(first); append(old)
        val deletion = gate(); waitMillis = 50
        hooks = object : HistoryStorageHooks { override fun afterTombstone() { deletion.pause("held logout tombstone") } }
        val current = freshOwner(); phase(current, ConnectionPhase.RESTORED)
        val oldCleanup = (capability(current) as HistoryAccess).beginDelete()
        try {
            current.signOut(); deletion.await("logout removal entered"); phase(current, ConnectionPhase.FAILED)
            awaitCredentialsAbsent("logout protected removal while history held"); revoked(old)
            val count = fake.requests.get()
            current.connect(); current.cancel(); settled(current)
            assertEquals(count, fake.requests.get())
            assertEquals(ConnectionProblem.STORAGE, current.state.value.problem)
        } finally { deletion.release() }
        // Await the actual cleanup, not a timing window. Repeated sign-out shares its ticket.
        runBlocking { await("held cleanup settles", { File(directory, "history.db").exists().toString() }) { !File(directory, "history.db").exists() } }
        current.signOut(); phase(current, ConnectionPhase.SIGNED_OUT)
        current.connect(); phase(current, ConnectionPhase.OBSERVED)
        val next = capability(current); append(next)
        assertEquals(HistoryDeleteOutcome.Deleted, oldCleanup.complete())
        deletion.release(); settled(current)
        assertEquals(1, page(next).entries.size)
        assertNotEquals(old.partition, next.partition)
    }

    @Test fun nativeCrashCutsStageCredentialSaveAndActivationNeverReusePreviousLogin() {
        for (cut in listOf("after-removal", "before-save", "after-save", "after-activation", "missing-binding", "pending-binding")) {
            NativeConnection.resetForTests()
            credentials.delete(credentials.openSession())
            directory.deleteRecursively()
            SQLiteHistoryStore(directory).use { backend -> prepareBindingCut(backend, cut) }
            if (cut == "missing-binding") assertTrue(File(directory, "binding").delete())
            if (cut == "pending-binding") File(directory, "binding.pending").writeBytes(byteArrayOf(1))
            val reopened = owner()
            phase(reopened, if (cut in setOf("before-save", "after-removal")) ConnectionPhase.IDLE else ConnectionPhase.RESTORED)
            if (cut == "after-activation") assertEquals(HistoryContent.EMPTY, page(capability(reopened)).content)
            else assertNotEquals(HistoryAvailability.AVAILABLE, reopened.historyAvailability)
            assertEquals(0, fake.requests.get())
        }
    }

    private fun prepareBindingCut(backend: SQLiteHistoryStore, cut: String) {
        seedCredentials()
        val old = backend.createPartition()
        assertTrue("$cut: preceding lifetime", old is HistoryAccessOutcome.Bound)
        val preceding = (old as HistoryAccessOutcome.Bound).access
        append(preceding)
        assertEquals(HistoryDeleteOutcome.Deleted, preceding.beginDelete().complete())
        assertTrue(credentials.delete(credentials.openSession()) is CredentialResult.Success)
        revoked(preceding)
        if (cut == "after-removal") return
        val staged = backend.stagePartition()
        assertTrue("$cut: stage outcome", staged is HistoryPartitionOutcome.Staged)
        val partition = (staged as HistoryPartitionOutcome.Staged).partition
        assertNotEquals(preceding.partition, partition)
        if (cut != "before-save") seedCredentials()
        if (cut in setOf("after-activation", "missing-binding", "pending-binding")) {
            assertTrue("$cut: activation outcome", backend.activatePartition(partition) is HistoryAccessOutcome.Bound)
        }
    }

    @Test fun nativeInterruptedOldTombstonePurgesEvenCleanLookingCredentials() {
        seedCredentials()
        SQLiteHistoryStore(directory, hooks = object : HistoryStorageHooks {
            override fun afterTombstone() { throw IOException() }
        }).use { backend ->
            val active = backend.createPartition()
            assertTrue("old lifetime create", active is HistoryAccessOutcome.Bound)
            append((active as HistoryAccessOutcome.Bound).access)
            assertTrue(backend.quarantineAndDelete() is HistoryDeleteOutcome.Unavailable)
        }
        val reopened = owner(); phase(reopened, ConnectionPhase.REAUTH_REQUIRED)
        assertCredentialsAbsent()
        assertFalse(File(directory, "history.db").exists())
        assertEquals(0, fake.requests.get())
    }

    @Test fun nativeHeldAppendAndReadLoseAuthorityImmediatelyOnOwnerRetirement() {
        for (operation in listOf("append-before", "append-after", "read")) {
            NativeConnection.resetForTests(); credentials.delete(credentials.openSession()); directory.deleteRecursively()
            val hold = gate()
            hooks = object : HistoryStorageHooks {
                override fun beforeWriteAdmission() { if (operation == "append-before") hold.pause(operation) }
                override fun afterWriteAdmission() { if (operation == "append-after") hold.pause(operation) }
                override fun beforeRead() { if (operation == "read") hold.pause(operation) }
            }
            val current = login(); val access = capability(current)
            val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val result = worker.async {
                if (operation == "read") access.read(HistoryReadQuery(access.partition, 10)) else access.append(admission(access))
            }
            try {
                hold.await("$operation entered")
                current.signOut(); settled(current)
                assertEquals(ConnectionPhase.SIGNING_OUT, current.state.value.phase)
                assertEquals(HistoryAvailability.REMOVAL_PENDING, current.historyAvailability)
            } finally { hold.release() }
            runBlocking {
                try {
                    val actual = try { withTimeout(5_000) { result.await() } }
                    catch (_: TimeoutCancellationException) { throw AssertionError("$operation worker settlement: last state=${current.state.value}") }
                    if (operation == "read") assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), actual)
                    if (operation == "append-before") assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), actual)
                    if (operation == "append-after") assertTrue(actual is HistoryAppendOutcome.Stored)
                } finally { worker.cancel() }
            }
            phase(current, ConnectionPhase.SIGNED_OUT); revoked(access); assertCredentialsAbsent()
            assertFalse(File(directory, "history.db").exists())
        }
    }

    @Test fun nativeKeyLossCorruptionAndInterruptedRotationPurgeLifetimeHistory() {
        for (fault in listOf("key-loss", "ciphertext-corrupt", "rotation-pending")) {
            NativeConnection.resetForTests(); credentials.delete(credentials.openSession()); directory.deleteRecursively()
            val current = login(); val old = capability(current); append(old)
            val requests = fake.requests.get()
            NativeConnection.resetForTests()
            when (fault) {
                "key-loss" -> KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(KeystoreCredentialStore.alias(context, slot))
                "ciphertext-corrupt" -> {
                    val file = KeystoreCredentialStore.file(context, slot)
                    val damaged = file.readBytes()
                    damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
                    file.writeBytes(damaged)
                }
                else -> assertTrue(credentials.beginRotation(credentials.openSession()) is CredentialResult.Success)
            }
            val reopened = owner(); phase(reopened, ConnectionPhase.REAUTH_REQUIRED)
            revoked(old)
            assertCredentialsAbsent()
            assertFalse(File(directory, "history.db").exists())
            assertEquals(requests, fake.requests.get())
        }
    }

    @Test fun nativeHeldCredentialSaveCannotActivateHistoryOrResurrectAfterLogout() {
        val current = owner(); phase(current, ConnectionPhase.IDLE)
        val save = gate(); saveGate = save
        try {
            current.connect(); save.await("fresh credential save entered")
            current.signOut(); phase(current, ConnectionPhase.SIGNED_OUT)
            assertCredentialsAbsent()
            assertEquals(HistoryAvailability.UNAVAILABLE, current.historyAvailability)
        } finally { save.release(); saveGate = null }
        val reopened = freshOwner(); phase(reopened, ConnectionPhase.IDLE)
        assertCredentialsAbsent()
        assertFalse(File(directory, "history.db").exists())
    }

    private suspend fun await(step: String, last: () -> String, predicate: () -> Boolean) {
        try { withTimeout(5_000) { while (!predicate()) delay(1) } }
        catch (_: TimeoutCancellationException) { throw AssertionError("$step: last state=${last()}") }
    }
    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun pause(step: String) { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)) { "$step: last state=held" } }
        fun await(step: String) { assertTrue("$step: last state=not-entered", entered.await(5, TimeUnit.SECONDS)) }
        fun release() { released.countDown() }
    }
    private class SyntheticTransport : AuthTransport {
        val requests = AtomicLong()
        val exchanges = AtomicLong()
        private val millis = AtomicLong()
        @Volatile var terminalRefresh = false
        @Volatile var holdRefresh = false
        @Volatile private var heldRefresh: (() -> Unit)? = null
        val refreshReached = CountDownLatch(1)
        fun releaseRefresh() { heldRefresh?.invoke(); heldRefresh = null }
        val clock = TransportClock { TransportTime(Instant.ofEpochSecond(1_800_000_000).plusMillis(millis.get()), millis.get()) }
        suspend fun pause(duration: Long) { millis.addAndGet(duration) }
        override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit): CancellationHandle {
            requests.incrementAndGet()
            val path = request.url.encodedPath
            if (path == "/oauth/token") exchanges.incrementAndGet()
            val status = if (path == "/oauth/token" && terminalRefresh) 401 else 200
            val body = when (path) {
                "/api/accounts/deviceauth/usercode" -> """{"device_auth_id":"synthetic-lifetime-device","user_code":"SYNTHETIC-LIFETIME"}"""
                "/api/accounts/deviceauth/token" -> """{"authorization_code":"synthetic-lifetime-authorization","code_verifier":"synthetic-lifetime-verifier"}"""
                "/oauth/token" -> if (exchanges.get() > 1) {
                    """{"access_token":"synthetic-lifetime-rotated-access","refresh_token":"synthetic-lifetime-rotated-refresh"}"""
                } else """{"access_token":"synthetic-lifetime-access","refresh_token":"synthetic-lifetime-refresh"}"""
                else -> "{}"
            }
            val response = TransportResult.Response.bounded(status, body.toByteArray())
            if (path == "/oauth/token" && holdRefresh) {
                heldRefresh = { terminal(response) }
                refreshReached.countDown()
            } else terminal(response)
            return CancellationHandle {}
        }
    }
}
