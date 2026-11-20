package io.github.leugenea.codexbarmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CodexAdditionTest {
    private lateinit var f: CodexAdditionFixture
    @Before fun prepare() { f = CodexAdditionFixture() }
    @After fun cleanup() { f.cleanup() }

    @Test fun twoAuthenticatedAddsTransferTheirOriginalSessionsAndContinueAfterReconstruction() {
        f.start()
        f.wait("foreground registration") { f.owner.registerForeground() }
        f.wait("empty bootstrap") { f.owner.legacy().state.first { it.phase == ConnectionPhase.IDLE } }
        val a = addAndObserve("Synthetic Alpha")
        val aController = f.runtime(a.storage.id)
        val aSession = f.ready(aController)
        val peer = f.bytes(a.storage)
        val enrollment = f.enrollment.toList()
        f.transport.heldPath = CodexAdditionFixture.POLL
        val handle = requireNotNull(f.begin("Synthetic Beta"))
        val poll = f.held(CodexAdditionFixture.POLL)
        val candidate = f.pending()
        assertEquals(a.storage.id, f.disk().selected)
        assertNull(f.owner.runtime(candidate.id))
        assertNull(f.owner.settings(candidate.id))
        assertNull(f.begin("Synthetic parallel"))
        assertSame(aController, f.owner.dashboard().value?.controller)
        assertEquals(2L, f.transport.gets.get())
        f.transport.heldPath = ReadOperation.USAGE.path
        poll.reply()
        val b = accepted(handle)
        val usage = f.held(ReadOperation.USAGE.path)
        assertFalse(f.wait("cancel after admission") { handle.cancel() })
        assertFalse(usage.cancelled)
        assertEquals(candidate, b.storage)
        assertEquals(f.enrollment.toList(), enrollment)
        assertSame(aSession, f.ready(aController))
        assertEquals(peer, f.bytes(a.storage))
        usage.reply()
        observed(b)
        assertEquals(4L, f.transport.gets.get())
        assertNotEquals(a.storage.id, b.storage.id)
        assertNotEquals(a.storage.credentialSlot, b.storage.credentialSlot)
        assertNotEquals(a.partition, b.partition)
        assertEqualTokens(f.protected(a.storage), f.protected(b.storage))
        assertSlotAAD(a.storage, b.storage)
        f.stop()
        listOf(a, b).forEach(::assertPersistedHistory)
        f.catalog = AccountCatalog.open(f.directory, { error("existing") })
        f.transport.heldPath = null
        f.start()
        assertEquals(b.storage.id, f.owner.dashboard().value?.id)
        val restored = f.runtime(b.storage.id)
        f.wait("passive B restoration") { restored.state.first { it.phase == ConnectionPhase.RESTORED } }
        assertEquals(4L, f.transport.gets.get())
        assertEquals("Synthetic Beta", restored.accountName.value?.name?.text)
        assertTrue(f.wait("explicit restored selection") { f.owner.select(a.storage.id) })
        f.observe(f.runtime(a.storage.id))
        assertEquals(6L, f.transport.gets.get())
    }

    @Test fun firstUsageIsAdmittedOnlyAfterIndependentCatalogReadbackEvenWithoutAnObserver() = runBlocking {
        f.start()
        f.wait("empty bootstrap") { f.owner.legacy().state.first { it.phase == ConnectionPhase.IDLE } }
        val atRequest = CompletableDeferred<CatalogSnapshot>()
        f.transport.usageAdmission = { atRequest.complete(f.disk()) }
        val publication = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            val first = requireNotNull(f.owner.dashboard().first { it?.id != null })
            Triple(first.id, f.ready(first.controller).generation, f.disk())
        }
        try {
            val entry = accepted(requireNotNull(f.begin("Synthetic dormant dashboard")))
            val (id, generation, firstDisk) = f.wait("first usable publication readback") { publication.await() }
            val requestDisk = f.wait("first usage admission readback") { atRequest.await() }
            assertEquals(entry.storage.id, id)
            assertSame(f.writes[entry.storage.credentialSlot], generation)
            assertEquals(firstDisk, requestDisk)
            assertEquals(entry.storage.id, requestDisk.selected)
            assertEquals(listOf(entry), requestDisk.entries)
            assertNull(requestDisk.candidate)
            f.observe(f.runtime(entry.storage.id))
            assertEquals(1L, f.transport.counts()[ReadOperation.USAGE.path])
            assertEquals(1L, f.transport.counts()[ReadOperation.RESET_INVENTORY.path])
        } finally { withTimeout(12_000) { publication.cancelAndJoin() } }
    }

    @Test fun pendingRenameDestinationRejectsFinalAdmissionWithoutChangingSelectionOrPeerArtifacts() {
        val a = peer()
        assertNull(f.begin("  "))
        assertNull(f.begin(a.name!!))
        f.transport.heldPath = CodexAdditionFixture.POLL
        val handle = requireNotNull(f.begin("Synthetic contested"))
        val call = f.held(CodexAdditionFixture.POLL)
        val address = f.pending()
        val peerBytes = f.bytes(a.storage)
        val session = f.ready(f.runtime(a.storage.id))
        assertTrue(f.catalog.reserveRename(a.storage.id, AccountDisplayName.from("Synthetic contested")))
        assertNull(f.begin("Synthetic other"))
        call.reply()
        assertEquals(AdditionResult.Rejected.CONFLICT, f.result(handle))
        assertEquals(CatalogRename(a.storage.id, "Synthetic contested"), f.disk().rename)
        assertRejected(a, address, peerBytes, session)
        assertNull(f.begin("Synthetic contested"))
    }

    @Test fun cancellationBrowserFailureAndTimeoutRejectExactAttemptsWhileLateTerminalsCannotResurrect() {
        val a = peer()
        val bytes = f.bytes(a.storage)
        val session = f.ready(f.runtime(a.storage.id))
        f.transport.heldPath = CodexAdditionFixture.POLL
        var previous: Pair<CodexAddition, AuthState.AwaitingUser>? = null
        repeat(3) { cut ->
            val handle = requireNotNull(f.begin("Synthetic retry"))
            val call = f.held(CodexAdditionFixture.POLL)
            val address = f.pending()
            val code = f.wait("display the exact device attempt") { handle.auth.first { it is AuthState.AwaitingUser } } as AuthState.AwaitingUser
            assertNotNull(f.wait("original browser permission") { handle.verificationUrl(code) })
            assertNull(f.wait("stale browser permission") { previous?.let { (old, displayed) -> old.verificationUrl(displayed) } })
            val expected = when (cut) {
                0 -> AdditionResult.Rejected.CANCELLED.also { assertTrue(f.wait("cancel admitted") { handle.cancel() }) }
                1 -> AdditionResult.Rejected.BROWSER.also { assertTrue(f.wait("browser failure admitted") { handle.browserFailed(code) }) }
                else -> AdditionResult.Rejected.AUTH.also { f.transport.advance(24 * 60 * 60 * 1_000L); call.reply() }
            }
            assertEquals(expected, f.result(handle))
            if (cut < 2) { assertTrue(call.cancelled); call.reply() }
            else assertEquals(AuthState.Failed(AuthStage.POLL, AuthFailure.DEADLINE), handle.auth.value)
            assertFalse(f.wait("stale browser failure") { handle.browserFailed(code) })
            assertRejected(a, address, bytes, session)
            previous = handle to code
        }
        f.denyPoll = true
        val denied = requireNotNull(f.begin("Synthetic denied"))
        assertEquals(AdditionResult.Rejected.AUTH, f.result(denied))
        assertEquals(AuthState.Failed(AuthStage.POLL, AuthFailure.HTTP_STATUS), denied.auth.value)
        assertNull(f.disk().candidate)
        assertEquals(2L, f.transport.gets.get())
    }

    @Test fun legacyWorkerKeepsItsNetworkEnrollmentAndAdmittedSQLiteWriteWhileAddsCancelAndSucceed() = runBlocking {
        val gate = f.gate()
        val armed = AtomicBoolean(false)
        f.history = { path -> SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(path, hooks = object : HistoryStorageHooks {
            override fun afterWriteAdmission() {
                if (path.name == "usage-history" && armed.compareAndSet(true, false)) gate.pause("A admitted SQLite append")
            }
        })) }
        f.start()
        f.wait("foreground registration") { f.owner.registerForeground() }
        val legacy = f.owner.legacy()
        f.wait("ready to connect legacy peer") { legacy.state.first { it.phase == ConnectionPhase.IDLE } }
        legacy.connect()
        f.observe(legacy)
        val a = f.disk().entries.single()
        f.until("A first append settled") { f.append[requireNotNull(a.partition)] is HistoryAppendOutcome.Stored }
        val original = f.ready(legacy)
        val target = requireNotNull(f.enrollment.filterNotNull().lastOrNull())
        val enrollment = f.enrollment.toList()
        f.transport.heldPath = ReadOperation.USAGE.path
        val worker = async(Dispatchers.Default) { legacy.refreshInBackground(target) }
        try {
            val request = f.held(ReadOperation.USAGE.path)
            val bytes = f.bytes(a.storage)
            f.transport.heldPath = CodexAdditionFixture.POLL
            val cancelled = requireNotNull(f.begin("Synthetic worker cancellation"))
            val poll = f.held(CodexAdditionFixture.POLL)
            assertTrue(f.wait("cancel B while worker A is held") { cancelled.cancel() })
            assertEquals(AdditionResult.Rejected.CANCELLED, f.result(cancelled))
            poll.reply()
            assertFalse(request.cancelled)
            assertEquals(bytes, f.bytes(a.storage))
            assertEquals(a.storage.id, f.disk().selected)
            armed.set(true)
            request.reply()
            gate.await("real A append admitted before B selection")
            f.transport.heldPath = null
            addAndObserve("Synthetic worker successor")
            assertFalse(worker.isCompleted)
            assertSame(original, f.ready(legacy))
            assertEquals(enrollment, f.enrollment.toList())
            gate.release()
            val completed = f.wait("original worker completion after successor selection") { worker.await() }
            assertTrue(completed is BackgroundRefreshOutcome.Completed)
            assertEqualTokens(original, f.protected(a.storage))
        } finally {
            gate.release()
            f.transport.releaseHeld()
            withTimeout(12_000) { worker.cancelAndJoin() }
        }
    }

    private fun peer(): CatalogEntry {
        f.start()
        f.wait("foreground registration") { f.owner.registerForeground() }
        f.wait("empty bootstrap") { f.owner.legacy().state.first { it.phase == ConnectionPhase.IDLE } }
        return addAndObserve("Synthetic peer")
    }

    private fun addAndObserve(name: String) = accepted(requireNotNull(f.begin(name))).also(::observed)
    private fun accepted(handle: CodexAddition): CatalogEntry {
        val id = (f.result(handle) as AdditionResult.Added).id
        val projection = requireNotNull(f.owner.dashboard().value)
        val disk = f.disk()
        assertEquals(id, projection.id)
        assertEquals(id, disk.selected)
        assertNull(disk.candidate)
        val entry = disk.entries.single { it.storage.id == id }
        assertSame(f.runtime(id), projection.controller)
        assertSame(f.writes[entry.storage.credentialSlot], f.ready(projection.controller).generation)
        assertEquals(1, f.constructed[entry.storage.credentialSlot])
        return entry
    }

    private fun observed(entry: CatalogEntry) {
        f.observe(f.runtime(entry.storage.id))
        f.until("exact partition stored first authenticated observation") { f.append[requireNotNull(entry.partition)] is HistoryAppendOutcome.Stored }
    }

    private fun assertPersistedHistory(entry: CatalogEntry) = SQLiteHistoryStore(entry.storage.historyDirectory(f.directory)).use { storage ->
        val partition = requireNotNull(entry.partition)
        val access = (storage.adopt(partition) as HistoryAccessOutcome.Bound).access
        val read = access.read(HistoryReadQuery(partition, 16)) as HistoryReadOutcome.Ready
        assertEquals(listOf(1L), read.snapshot.entries.map { it.id.ordinal })
        assertEquals(entry.name, (storage.readName(partition) as AccountNameRead.Ready).name?.text)
    }

    private fun assertEqualTokens(a: CredentialEnvelope, b: CredentialEnvelope) {
        assertArrayEquals(a.accessToken.copyBytes(), b.accessToken.copyBytes())
        assertArrayEquals(a.refreshToken!!.copyBytes(), b.refreshToken!!.copyBytes())
    }

    private fun assertSlotAAD(a: AccountStorage, b: AccountStorage) {
        val first = KeystoreCredentialStore.file(f.context, a.credentialSlot).readBytes()
        val sealed = CredentialBinaryFormat.sealed(first, a.credentialSlot)
        val cipher = AndroidCredentialCipher(KeystoreCredentialStore.alias(f.context, a.credentialSlot))
        assertThrows(java.security.GeneralSecurityException::class.java) {
            cipher.decrypt(sealed, CredentialBinaryFormat.header(b.credentialSlot))
        }
        assertTrue(f.key(a.credentialSlot)); assertTrue(f.key(b.credentialSlot))
    }

    private fun assertRejected(a: CatalogEntry, candidate: AccountStorage, peer: Map<String, List<Byte>>, session: CredentialEnvelope) {
        f.removed(candidate)
        assertNull(f.disk().candidate)
        assertEquals(a.storage.id, f.disk().selected)
        assertEquals(listOf(a), f.disk().entries)
        assertEquals(a.storage.id, f.owner.dashboard().value?.id)
        assertSame(session, f.ready(f.runtime(a.storage.id)))
        assertEquals(peer, f.bytes(a.storage))
        assertEqualTokens(session, f.protected(a.storage))
        assertTrue(f.key(a.storage.credentialSlot))
        assertEquals(2L, f.transport.gets.get())
        assertTrue(f.enrollment.none { it != null })
    }
}
