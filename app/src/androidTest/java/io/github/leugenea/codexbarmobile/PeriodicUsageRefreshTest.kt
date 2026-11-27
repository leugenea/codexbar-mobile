package io.github.leugenea.codexbarmobile

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.background.greedy.GreedyScheduler
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import io.github.leugenea.codexbarmobile.account.AccountCatalog
import io.github.leugenea.codexbarmobile.account.AccountDisplayName
import io.github.leugenea.codexbarmobile.account.AccountStorage
import io.github.leugenea.codexbarmobile.account.AccountType
import io.github.leugenea.codexbarmobile.account.CatalogEntry
import io.github.leugenea.codexbarmobile.account.CatalogFile
import io.github.leugenea.codexbarmobile.account.CleanupDisposition
import io.github.leugenea.codexbarmobile.credentials.CredentialStore
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic provider + real owner/Keystore/SQLite. OS dispatch and TestDriver proof are distinct. */
@RunWith(AndroidJUnit4::class)
class PeriodicUsageRefreshTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val originalRootFactory = NativeConnection.rootFactory
    private val transport = SyntheticSamplingTransport()
    private lateinit var context: Context
    private lateinit var root: File
    private val manager get() = WorkManager.getInstance(app)
    private val owner get() = NativeConnection.get(context)
    private val observer = Any()

    @Before fun prepare() {
        cancelEnrolledWork()
        root = File(app.noBackupFilesDir, "synthetic-periodic-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        NativeConnection.rootFactory = {
            NativeConnection.createOwner(context, transport, transport.clock, transport::pause,
                enrollment = PeriodicUsageRefresh(manager)::enrollment)
        }
        NativeConnection.resetForTests()
    }

    @After fun cleanup() {
        cancelEnrolledWork()
        try { NativeConnection.resetForTests() }
        finally {
            NativeConnection.rootFactory = originalRootFactory
            val credentials = KeystoreCredentialStore(context, NativeConnection.session(context))
            credentials.delete(credentials.openSession())
            root.deleteRecursively()
        }
    }

    @Test fun explicitActivationKeepsOneLifetimeScopedOrdinaryFifteenMinuteRequest() {
        phase(ConnectionPhase.IDLE)
        owner.usageForeground(observer, true); settled()
        assertTrue(activeWork().isEmpty())
        login(); val target = target()
        val first = unique(target).single()
        owner.readUsage(); owner.readUsage(); settled()
        val kept = unique(target).single()
        assertEquals(first.id, kept.id)
        assertEquals(PeriodicUsageRefreshWorker::class.java.name, kept.workerClassName)
        assertEquals(900_000L, kept.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(900_000L, kept.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, kept.constraints.requiredNetworkType)
        assertTrue(kept.constraints.requiresBatteryNotLow())
        assertFalse(kept.constraints.requiresCharging())
        assertFalse(kept.constraints.requiresDeviceIdle())
        assertEquals(1, activeWork().size)
        val request = PeriodicUsageRefresh.request(target)
        val input = TestListenableWorkerBuilder.from(app, request).build().inputData.keyValueMap
        assertEquals(mapOf(PeriodicUsageRefresh.LIFETIME to target.partition.value.toString()), input)
    }


    @Test fun coldSystemJobUsesOnlyNonselectedEnrolledAccountAndNeverBorrowsSelection() {
        val f = AccountSelectionFixture()
        f.prepare()
        try {
            val periodic = PeriodicUsageRefresh(manager)
            f.enrollment = periodic::enrollment
            f.start()
            val a = f.runtime(f.a); val b = f.runtime(f.b)
            val targetA = BackgroundRefreshTarget(requireNotNull(f.a.partition))
            val targetB = BackgroundRefreshTarget(requireNotNull(f.b.partition))
            f.phase(a, ConnectionPhase.RESTORED); f.phase(b, ConnectionPhase.RESTORED)
            val observer = f.observer()
            assertSame(b, f.owner.settings(f.b.storage.id)?.controller)
            assertTrue(activeWork().isEmpty())
            f.select(f.b); f.phase(b, ConnectionPhase.OBSERVED)
            val workB = unique(targetB).single().id
            f.select(f.a); f.phase(a, ConnectionPhase.OBSERVED)
            val workA = unique(targetA).single().id
            f.select(f.b); f.phase(b, ConnectionPhase.OBSERVED)
            assertEquals(workB, unique(targetB).single().id)
            f.select(f.a); f.phase(a, ConnectionPhase.OBSERVED)
            assertEquals(workA, unique(targetA).single().id)
            f.stop(observer)
            f.receipt("drain initial process owner") { f.owner.shutdown() }

            val constructed = CopyOnWriteArrayList<UUID>()
            f.decorateCredential = { slot, store -> store.also { constructed += slot } }
            f.catalog = AccountCatalog.open(f.directory, { error("existing two-account catalog") })
            f.installActivity { f.start() }
            val aCount = f.aTransport.requests.get(); val bCount = f.bTransport.requests.get()
            assertTrue(constructed.isEmpty())
            f.aTransport.advance(900_000); f.bTransport.advance(900_000)
            f.bTransport.heldPath = ReadOperation.USAGE.path
            dispatchOsJob(awaitJob(workB), workB)
            f.await("cold B worker admitted with A still selected") {
                f.bTransport.liveHeldCall(ReadOperation.USAGE.path) != null && info(workB).state == WorkInfo.State.RUNNING
            }
            val coldB = f.runtime(f.b)
            assertEquals(listOf(f.b.storage.credentialSlot), constructed.toList())
            assertNull(coldB.currentHistoryDisplayContext())
            assertEquals(f.a.storage.id, f.catalog.snapshot().selected)
            assertEquals(aCount, f.aTransport.requests.get())
            f.bTransport.releaseHeld()
            f.await("B recorded before real OS worker completed") {
                info(workB).state == WorkInfo.State.ENQUEUED && f.appends.any {
                    it.admission.partition == targetB.partition && it.admission.event is HistoryEvent.Observed &&
                        (it.admission.clock.monotonicMillis ?: -1L) >= 900_000 && it.result is HistoryAppendOutcome.Stored
                }
            }
            assertEquals(bCount + 2, f.bTransport.requests.get())
            val stale = BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))
            periodic.enrollment(stale, true)
            val staleId = unique(stale).single().id
            assertEquals(ListenableWorker.Result.success(), run(stale, staleId))
            assertEquals(WorkInfo.State.CANCELLED, info(staleId).state)
            assertEquals("unknown worker must not instantiate the selected runtime",
                listOf(f.b.storage.credentialSlot), constructed.toList())
            assertEquals(aCount, f.aTransport.requests.get())
            assertEquals(bCount + 2, f.bTransport.requests.get())
            assertEquals(workA, unique(targetA).single().id)
            assertEquals(workB, unique(targetB).single().id)
        } finally { f.cleanup() }
    }

    @Test fun ambiguousSavedLifetimeNeverConstructsEitherRuntimeOrCancelsWork() {
        val f = AccountSelectionFixture()
        f.prepare()
        try {
            assertTrue(f.catalog.insert(f.b.copy(storage = AccountStorage.fresh(), name = "Synthetic duplicate binding")))
            val target = BackgroundRefreshTarget(requireNotNull(f.b.partition))
            PeriodicUsageRefresh(manager).enrollment(target, true)
            val work = unique(target).single().id
            val constructed = AtomicInteger()
            f.decorateCredential = { _, store -> store.also { constructed.incrementAndGet() } }
            f.installActivity { f.start() }
            assertEquals(ListenableWorker.Result.success(), run(target, work))
            assertEquals(0, constructed.get())
            assertEquals(0L, f.aTransport.requests.get() + f.bTransport.requests.get())
            assertEquals(WorkInfo.State.ENQUEUED, info(work).state)
        } finally { f.cleanup() }
    }

    @Test fun coldLegacyWorkerSettlesInterruptedRemovalBeforeCancellingItsWork() =
        recoverDurableWorkerDeletion(legacy = true, disposition = CleanupDisposition.REMOVE)

    @Test fun coldAddressedWorkerRetriesInterruptedReauthorizationBeforeCancellingItsWork() =
        recoverDurableWorkerDeletion(legacy = false, disposition = CleanupDisposition.RETAIN)

    private fun recoverDurableWorkerDeletion(legacy: Boolean, disposition: CleanupDisposition) {
        val f = AccountSelectionFixture()
        f.prepare()
        try {
            val removed = if (legacy) f.a else f.b
            val peer = if (legacy) f.b else f.a
            f.enrollment = PeriodicUsageRefresh(manager)::enrollment
            f.start()
            val target = BackgroundRefreshTarget(requireNotNull(removed.partition))
            val peerTarget = BackgroundRefreshTarget(requireNotNull(peer.partition))
            val work = enrollSaved(f, removed)
            val peerWork = enrollSaved(f, peer)
            assertTrue(f.catalog.select(peer.storage.id))
            f.receipt("stop before reconstructing interrupted cleanup") { f.owner.shutdown() }
            val requests = f.aTransport.requests.get() to f.bTransport.requests.get()
            val peerBytes = f.bytes(peer)

            // Crash cut after the real catalog receipt commit, before either protected store is deleted.
            val receipt = f.catalog.beginDeletion(removed.storage.id, disposition)
            val raw = CatalogFile(File(f.directory, "account-catalog"))
            assertEquals(listOf(receipt), raw.read().deletions)
            assertTrue(f.keyExists(removed))
            f.catalog = AccountCatalog.open(f.directory, { error("saved catalog required") })
            val constructed = CopyOnWriteArrayList<UUID>()
            f.decorateCredential = { slot, store -> store.also { constructed += slot } }
            val held = f.gate()
            val failFirst = AtomicBoolean(!legacy)
            f.hooks = { path -> object : HistoryStorageHooks {
                override fun afterTombstone() {
                    if (path != removed.storage.historyDirectory(f.directory)) return
                    held.pause("exact history tombstone before physical cleanup")
                    if (failFirst.getAndSet(false)) throw IOException("interrupted history deletion")
                }
            } }
            f.installActivity { f.start() }
            dispatchOsJob(awaitJob(work), work)
            held.await("worker reached the exact durable receipt")
            assertEquals(listOf(receipt), raw.read().deletions)
            assertEquals(WorkInfo.State.RUNNING, info(work).state)
            assertEquals(listOf(removed.storage.credentialSlot), constructed.toList())
            assertEquals(requests, f.aTransport.requests.get() to f.bTransport.requests.get())
            held.release()
            val firstResult = if (legacy) WorkInfo.State.CANCELLED else WorkInfo.State.ENQUEUED
            f.await("first scheduled cleanup opportunity finished with $firstResult") { info(work).state == firstResult }

            if (!legacy) {
                assertEquals(listOf(receipt), raw.read().deletions)
                assertEquals("failed physical cleanup must preserve the original opportunity", WorkInfo.State.ENQUEUED, info(work).state)
                assertEquals(ListenableWorker.Result.success(), run(target, work))
                assertEquals(removed.copy(partition = null), f.catalog.entry(removed.storage.id))
                assertFalse(f.keyExists(removed))
                assertFalse(File(removed.storage.historyDirectory(f.directory), "history.db").exists())
            } else f.assertRemoved(removed)
            val settled = raw.read()
            assertTrue(settled.deletions.isEmpty())
            assertEquals(peer.storage.id, settled.selected)
            assertEquals(WorkInfo.State.CANCELLED, info(work).state)
            assertEquals(peerWork, unique(peerTarget).single().id)
            assertEquals(peerBytes, f.bytes(peer))
            assertEquals(listOf(removed.storage.credentialSlot), constructed.toList())
            assertEquals(requests, f.aTransport.requests.get() to f.bTransport.requests.get())
        } finally { f.cleanup() }
    }

    @Test fun nextExactWorkerResumesFailedTerminalCleanupWithoutADurableReceipt() {
        val f = AccountSelectionFixture()
        f.prepare()
        val failing = AtomicBoolean()
        try {
            // The fixture's B is nonlegacy (the invalidated A in the reported interleaving).
            val removed = f.b; val peer = f.a
            val raw = CatalogFile(File(f.directory, "account-catalog"))
            f.catalog = AccountCatalog.open(f.directory, { error("saved catalog required") }, beforeDirectorySync = {
                if (failing.get()) throw IOException("unrelated selection directory sync")
            })
            assertTrue(f.catalog.select(removed.storage.id))
            f.enrollment = PeriodicUsageRefresh(manager)::enrollment
            val reservation = f.gate()
            val tombstone = f.gate()
            val directory = removed.storage.historyDirectory(f.directory)
            val peerReads = AtomicInteger()
            f.decorateCredential = { slot, store ->
                if (slot != peer.storage.credentialSlot) store else object : CredentialStore by store {
                    override fun read(generation: SessionGeneration) = store.read(generation).also { peerReads.incrementAndGet() }
                }
            }
            f.decorateHistory = { path, storage ->
                if (path != directory) storage else object : HistoryLifetimeStorage by storage {
                    override fun reserveDeletion(disposition: CleanupDisposition): HistoryDeletion {
                        reservation.pause("terminal invalidation before the catalog deletion reservation")
                        return storage.reserveDeletion(disposition)
                    }
                }
            }
            f.hooks = { path -> object : HistoryStorageHooks {
                override fun afterTombstone() { if (path == directory) tombstone.pause("terminal cleanup after durable receipt") }
            } }
            f.start(); f.installActivity()
            val target = BackgroundRefreshTarget(requireNotNull(removed.partition))
            val peerTarget = BackgroundRefreshTarget(requireNotNull(peer.partition))
            val work = enrollSaved(f, removed)
            val controller = f.runtime(removed)
            val peerWork = enrollSaved(f, peer)
            val peerCredential = f.protectedReadback(peer)
            val peerBytes = f.bytes(peer)
            val peerGets = f.aTransport.gets.get()
            val peerReadsBefore = peerReads.get()
            assertTrue(peerReadsBefore > 0)
            f.aTransport.advance(900_000)
            f.bTransport.heldPath = ReadOperation.USAGE.path
            f.bTransport.usageStatus = 401
            f.aTransport.tokenResponse = TransportResult.Response.bounded(400, """{"error":"invalid_grant"}""".toByteArray())
            dispatchOsJob(awaitJob(work), work)
            f.await("original scheduled worker has in-flight usage") {
                info(work).state == WorkInfo.State.RUNNING && f.bTransport.liveHeldCall(ReadOperation.USAGE.path) != null
            }
            failing.set(true)
            assertThrows(IOException::class.java) { f.catalog.select(peer.storage.id) }
            assertNull(f.catalog.backgroundSnapshot(target.partition))
            f.bTransport.releaseHeld()
            reservation.await("terminal refresh reached account cleanup while selection was still fenced")
            assertNull(f.catalog.backgroundSnapshot(target.partition))
            assertFalse(info(work).state.isFinished)
            reservation.release()
            assertEquals(ConnectionProblem.STORAGE, f.phase(controller, ConnectionPhase.FAILED).problem)
            assertEquals(HistoryAvailability.STORAGE_FAILURE, controller.historyAvailability)
            assertNull(f.catalog.backgroundSnapshot(target.partition))
            val failed = raw.read()
            assertTrue("Selection confirmation failed before a deletion receipt was written", failed.deletions.isEmpty())
            assertEquals(removed, failed.entries.single { it.storage == removed.storage })
            assertEquals(peer.storage.id, failed.selected)
            f.await("original work survives the observed pre-receipt cleanup failure") { info(work).state == WorkInfo.State.ENQUEUED }

            val requests = f.aTransport.requests.get() to f.bTransport.requests.get()
            failing.set(false)
            val next = start(target, work)
            try {
                tombstone.await("next exact worker resumed cleanup and reached the durable receipt")
                val receipt = raw.read().deletions.single()
                assertEquals(removed.storage.id, receipt.entry)
                assertEquals(CleanupDisposition.RETAIN, receipt.disposition)
                assertEquals(WorkInfo.State.ENQUEUED, info(work).state)
                tombstone.release()
                assertEquals(ListenableWorker.Result.success(), futureResult("exact cleanup opportunity", next))
            } finally { tombstone.release(); next.cancel(true) }
            f.phase(controller, ConnectionPhase.REAUTH_REQUIRED)
            val settled = raw.read()
            assertEquals(removed.copy(partition = null), settled.entries.single { it.storage == removed.storage })
            assertTrue(settled.deletions.isEmpty())
            assertEquals(peer.storage.id, settled.selected)
            assertFalse(f.keyExists(removed))
            assertFalse(File(directory, "history.db").exists())
            assertEquals(WorkInfo.State.CANCELLED, info(work).state)
            assertEquals(peerWork, unique(peerTarget).single().id)
            assertEquals(peerReadsBefore, peerReads.get())
            assertEquals(peerGets, f.aTransport.gets.get()) // The synthetic router sends the refresh POST to A.
            assertEquals(requests, f.aTransport.requests.get() to f.bTransport.requests.get())
            assertEquals(1L, f.aTransport.counts()["/oauth/token"] ?: 0L)
            assertArrayEquals(peerCredential.accessToken.copyBytes(), f.protectedReadback(peer).accessToken.copyBytes())
            assertEquals(peerBytes, f.bytes(peer))
        } finally {
            failing.set(false)
            f.cleanup()
        }
    }

    @Test fun coldUnmatchedWorkerCancelsOriginalWorkAfterRemovalWithResolvedLegacyAndRetainedAddress() {
        val f = AccountSelectionFixture()
        f.prepare()
        try {
            val legacy = f.a; val retained = f.b; val removed = f.seedAddressed("C")
            val target = BackgroundRefreshTarget(requireNotNull(removed.partition))
            val legacyTarget = BackgroundRefreshTarget(requireNotNull(legacy.partition))
            val retainedTarget = BackgroundRefreshTarget(requireNotNull(retained.partition))
            val periodic = PeriodicUsageRefresh(manager)
            val cut = CopyOnWriteArrayList<BackgroundRefreshTarget>()
            f.enrollment = { lifetime, enrolled ->
                // Simulate death after durable settlement, before WorkManager sees cancellation.
                if (lifetime == target && !enrolled) cut += lifetime else periodic.enrollment(lifetime, enrolled)
            }
            f.start(); f.installActivity()
            val work = enrollSaved(f, removed)
            val legacyWork = enrollSaved(f, legacy)
            val retainedWork = enrollSaved(f, retained)
            f.aTransport.advance(900_000)
            f.bTransport.usageStatus = 401
            f.aTransport.tokenResponse = TransportResult.Response.bounded(400, """{"error":"invalid_grant"}""".toByteArray())
            assertEquals(ListenableWorker.Result.success(), run(retainedTarget, retainedWork))
            f.phase(f.runtime(retained), ConnectionPhase.REAUTH_REQUIRED)
            assertEquals(WorkInfo.State.CANCELLED, info(retainedWork).state)

            val controller = f.runtime(removed)
            controller.signOut()
            f.phase(controller, ConnectionPhase.SIGNED_OUT)
            val raw = CatalogFile(File(f.directory, "account-catalog"))
            val settled = raw.read()
            assertEquals(listOf(legacy, retained.copy(partition = null)), settled.entries)
            assertTrue(settled.deletions.isEmpty())
            assertEquals(listOf(target), cut.toList())
            assertEquals(WorkInfo.State.ENQUEUED, info(work).state)
            f.assertRemoved(removed)
            f.receipt("shut down owner at the durable-catalog, enqueued-work cut") { f.owner.shutdown() }

            val constructed = CopyOnWriteArrayList<UUID>()
            val openedHistory = CopyOnWriteArrayList<File>()
            val requests = f.aTransport.requests.get() to f.bTransport.requests.get()
            val legacyBytes = f.bytes(legacy)
            f.decorateCredential = { slot, store -> store.also { constructed += slot } }
            f.decorateHistory = { path, storage -> storage.also { openedHistory += path } }
            f.enrollment = periodic::enrollment
            f.catalog = AccountCatalog.open(f.directory, { error("settled catalog required") })
            f.installActivity { f.start() }
            assertEquals(ListenableWorker.Result.success(), run(target, work))
            assertEquals(WorkInfo.State.CANCELLED, info(work).state)
            assertEquals(legacyWork, unique(legacyTarget).single().id)
            assertEquals(settled, raw.read())
            assertEquals(legacyBytes, f.bytes(legacy))
            assertTrue(constructed.isEmpty())
            assertTrue(openedHistory.isEmpty())
            assertEquals(requests, f.aTransport.requests.get() to f.bTransport.requests.get())
        } finally { f.cleanup() }
    }

    private fun enrollSaved(f: AccountSelectionFixture, entry: CatalogEntry): UUID {
        val controller = f.runtime(entry)
        f.phase(controller, ConnectionPhase.RESTORED)
        controller.readUsage()
        f.phase(controller, ConnectionPhase.OBSERVED)
        return unique(BackgroundRefreshTarget(requireNotNull(entry.partition))).single().id
    }

    @Test fun workerFirstLegacyUpgradeAdoptsAnInitiallyUnresolvedLifetimeWithoutReplacingWork() {
        login(); detach()
        val expected = target()
        val work = unique(expected).single().id
        val count = transport.requests.get()
        NativeConnection.resetForTests()
        val catalog = File(root.canonicalFile, "account-catalog")
        assertTrue("emulate the pre-catalog installation without touching credentials, history or WorkSpec",
            File(catalog, "catalog").delete())
        val raw = CatalogFile(catalog)
        HistoryFiles(File(root.canonicalFile, "usage-history"), HistoryStorageLimits()).use { held ->
            held.acquire()
            assertEquals(ListenableWorker.Result.success(), run(expected, work))
            val unresolved = raw.read()
            assertEquals(unresolved.selected, unresolved.entries.single().storage.id)
            assertTrue(unresolved.entries.single().storage.legacy)
            assertNull(unresolved.entries.single().partition)
            assertEquals(count, transport.requests.get())
            assertEquals(WorkInfo.State.ENQUEUED, info(work).state)
        }
        val adoptedAddress = raw.read().entries.single().storage
        transport.advance(900_000)
        transport.heldPath = ReadOperation.USAGE.path
        dispatchOsJob(awaitJob(work), work)
        val held = transport.awaitHeld("original UUID-only OS job entered after storage recovered")
        assertEquals(WorkInfo.State.RUNNING, info(work).state)
        val adopted = raw.read().entries.single()
        assertEquals(expected.partition, adopted.partition)
        assertEquals(adoptedAddress, adopted.storage)
        transport.heldPath = null; held.reply(transport.usage)
        await("original OS job finished and released its exact history lease") {
            info(work).state == WorkInfo.State.ENQUEUED && owner.historyAvailability == HistoryAvailability.UNAVAILABLE
        }
        assertEquals(count + 2, transport.requests.get())
        assertEquals(work, unique(expected).single().id)
        reopenWithPoints(2)
    }

    @Test fun minimizedAppRunsThroughRealSystemJobServiceAndDurableHistorySurvivesReopen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitConnectionScreen(compose, owner)
            owner.connect()
            await("initial visible observation committed") {
                owner.state.value.phase == ConnectionPhase.OBSERVED && recordedHistory(owner).points.size == 1
            }
            val enrolled = target()
            scenario.moveToState(Lifecycle.State.CREATED)
            await("Activity STOP releases display authority") { owner.currentHistoryDisplayContext() == null }
            transport.advance(900_000)
            transport.heldPath = ReadOperation.USAGE.path
            val work = unique(enrolled).single()
            val job = awaitJob(work.id)
            assertEquals("androidx.work.impl.background.systemjob.SystemJobService", job.service.className)
            if (Build.VERSION.SDK_INT >= 28) assertNotNull(job.requiredNetwork)
            if (Build.VERSION.SDK_INT >= 31) assertFalse(job.isExpedited)
            dispatchOsJob(job, work.id)
            val held = transport.awaitHeld("real JobService entered owner usage request while Activity stopped")
            await("actual scheduled worker is RUNNING") { info(work.id).state == WorkInfo.State.RUNNING }
            assertEquals(1, bounded("scheduled owner interest") { owner.backgroundRefreshInterests() })
            assertNull(owner.currentHistoryDisplayContext())
            transport.heldPath = null; held.reply(transport.usage)
            await("OS worker waits for actual durable completion then returns to periodic ENQUEUED") {
                info(work.id).let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0 } &&
                    owner.historyAvailability == HistoryAvailability.UNAVAILABLE
            }
            assertEquals(4L, transport.gets.get())
            println("PERIODIC_REFRESH_EVIDENCE forced-system-job-service; test-aged-enqueue-time; greedy-disabled-for-this-id; Activity=CREATED; ordinary-period=900000; exact-cadence=false")
        }
        reopenWithPoints(2)
        assertEquals(4L, transport.gets.get())
    }

    @SuppressLint("RestrictedApi") // Test-only delegate restoration, never a production API bypass.
    @Test fun testDriverRequiresNetworkBatteryAndInitialPeriodWithoutClaimingOsTiming() {
        val real = manager as WorkManagerImpl
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(app)
            login(); detach(); transport.advance(900_000)
            val work = unique(target()).single()
            val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(app))
            assertEquals(2L, transport.gets.get())
            driver.setInitialDelayMet(work.id)
            assertEquals(2L, transport.gets.get()) // Network/battery constraints still unmet.
            driver.setAllConstraintsMet(work.id)
            await("driver first period completes") { transport.gets.get() == 4L && info(work.id).state == WorkInfo.State.ENQUEUED }
            assertEquals(0, info(work.id).runAttemptCount)
            transport.advance(900_000)
            driver.setPeriodDelayMet(work.id)
            assertEquals(4L, transport.gets.get()) // Each period re-evaluates the constraints.
            driver.setAllConstraintsMet(work.id)
            await("driver next period completes without a retry loop") { transport.gets.get() == 6L && info(work.id).state == WorkInfo.State.ENQUEUED }
            transport.advance(900_000); transport.heldPath = ReadOperation.USAGE.path
            driver.setPeriodDelayMet(work.id); driver.setAllConstraintsMet(work.id)
            val held = transport.awaitHeld("TestDriver third period enters actual worker")
            await("scheduled worker retains owner interest before OS-style stop") {
                bounded("driver worker admitted") { owner.backgroundRefreshNetworkInterests() == 1 && owner.backgroundRefreshInterests() == 1 } &&
                    info(work.id).state == WorkInfo.State.RUNNING
            }
            driver.stopRunningWorkWithReason(work.id, WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW)
            await("scheduler stop propagates cancellation and releases sole owner interest") {
                bounded("driver stopped worker released") { owner.backgroundRefreshNetworkInterests() == 0 && owner.backgroundRefreshInterests() == 0 } &&
                    held.cancelled && info(work.id).state == WorkInfo.State.ENQUEUED
            }
            assertTrue(owner.session.snapshot() is SessionResult.Ready)
            transport.heldPath = null; held.reply(transport.usage)
            reopenWithPoints(3)
            assertEquals(7L, transport.gets.get())
            println("PERIODIC_REFRESH_EVIDENCE TestDriver constraint/time control only; not OS scheduling or battery evidence")
        } finally {
            cancelEnrolledWork()
            WorkManagerTestInitHelper.closeWorkDatabase()
            WorkManagerImpl.setDelegate(real)
        }
    }

    @Test fun workerRecentSuccessAndMalformedInputReturnWithoutAnyAdditionalRequests() {
        login(); detach()
        val count = transport.requests.get()
        assertEquals(ListenableWorker.Result.success(), run(target()))
        assertEquals(ListenableWorker.Result.failure(), runData(Data.EMPTY))
        assertEquals(ListenableWorker.Result.failure(), runData(workDataOf(PeriodicUsageRefresh.LIFETIME to "invalid")))
        assertNull(PeriodicUsageRefresh.target("00000000-0000-0000-0000-00000000000A"))
        assertEquals(count, transport.requests.get())
    }

    @Test fun coldWorkerRestoresOnlyMatchingEnrolledLifetimeAndCannotReadSuccessor() {
        login(); detach(); val old = target()
        NativeConnection.resetForTests(); phase(ConnectionPhase.RESTORED)
        val count = transport.requests.get()
        assertEquals(ListenableWorker.Result.success(), run(BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))))
        assertEquals(count, transport.requests.get())
        assertEquals(ListenableWorker.Result.success(), run(old))
        assertEquals(count + 2, transport.requests.get())
        reopenWithPoints(2); detach()
        owner.signOut(); phase(ConnectionPhase.SIGNED_OUT)
        owner.usageForeground(observer, true); owner.connect(); phase(ConnectionPhase.OBSERVED)
        assertNotEquals(old, target()); val successorCount = transport.requests.get()
        assertEquals(ListenableWorker.Result.success(), run(old))
        assertEquals(successorCount, transport.requests.get())
    }

    @Test fun coldCatalogSyncFailureKeepsTheSameEnrolledWorkThroughRecovery() {
        login(); detach()
        val expected = target()
        val enrolled = unique(expected).single()
        val requests = transport.requests.get()
        val periodic = PeriodicUsageRefresh(manager)
        val disk = AccountCatalog.open(root, { error("saved catalog required") })
        val saved = requireNotNull(disk.legacyEntry())
        assertEquals(expected.partition, saved.partition)
        assertEquals(saved.storage.id, disk.snapshot().selected)

        val originalRoot = NativeConnection.rootFactory
        val syncAttempts = AtomicInteger()
        val blocked = AtomicBoolean(true)
        NativeConnection.resetForTests()
        NativeConnection.rootFactory = {
            NativeConnection.createOwner(context, transport, transport.clock, transport::pause,
                enrollment = periodic::enrollment, openCatalog = {
                    AccountCatalog.open(root, { error("saved catalog required") }, beforeDirectorySync = {
                        syncAttempts.incrementAndGet()
                        if (blocked.get()) throw IOException("cold catalog directory sync blocked")
                    })
                })
        }
        try {
            assertEquals("worker must be the first cold ingress", 0, syncAttempts.get())
            assertEquals(ListenableWorker.Result.success(), run(expected))
            assertEquals("worker reached initial open and its checked retry", 2, syncAttempts.get())
            assertEquals(requests, transport.requests.get())
            // The status query is queued on WorkManager's serial executor after any worker cancellation.
            assertEquals(WorkInfo.State.ENQUEUED, info(enrolled.id).state)

            blocked.set(false)
            transport.advance(900_000)
            assertEquals(ListenableWorker.Result.success(), run(expected, enrolled.id))
            assertEquals(3, syncAttempts.get())
            assertEquals(expected, target())
            assertEquals(requests + 2, transport.requests.get())
            assertEquals(enrolled.id, unique(expected).single().id)
            val restored = owner
            restored.usageForeground(observer, true); settled()
            await("worker added a durable measurement to the same legacy history") { recordedHistory(restored).points.size == 2 }
            detach()

            val stale = BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))
            periodic.enrollment(stale, true)
            val staleWork = unique(stale).single()
            assertEquals(ListenableWorker.Result.success(), run(stale))
            assertEquals(WorkInfo.State.CANCELLED, info(staleWork.id).state)
            assertEquals(WorkInfo.State.ENQUEUED, info(enrolled.id).state)
            assertEquals(requests + 2, transport.requests.get())
        } finally {
            try { NativeConnection.resetForTests() } finally {
                NativeConnection.rootFactory = originalRoot
            }
        }
    }

    @Test fun coldWorkerKeepsExactEnrollmentWhenFirstHistoryRestoreIsUnavailableDuringAddRecovery() {
        login(); detach()
        val expected = target()
        val enrolled = unique(expected).single()
        val requests = transport.requests.get()
        val periodic = PeriodicUsageRefresh(manager)
        val catalog = AccountCatalog.open(root, { error("saved catalog required") })
        val saved = requireNotNull(catalog.legacyEntry())
        assertEquals(expected.partition, saved.partition)
        val address = requireNotNull(catalog.reserveAddition(
            requireNotNull(AccountDisplayName.from("Synthetic pending B")), AccountType.CODEX))
        val obstruction = File(address.historyDirectory(root), "unexpected-child")
        assertTrue(obstruction.mkdirs())
        val originalRoot = NativeConnection.rootFactory
        val unavailable = AtomicInteger()
        val raw = CatalogFile(File(root.canonicalFile, "account-catalog"))
        NativeConnection.resetForTests()
        NativeConnection.rootFactory = {
            AccountRuntimeOwner(context, AccountCatalog.open(root, { error("saved catalog required") }),
                transport, transport.clock, transport::pause, enrollment = periodic::enrollment,
                historyStorage = { directory ->
                    val real = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(directory))
                    object : HistoryLifetimeStorage by real {
                        override fun restore() = real.restore().also {
                            if (it == HistoryLifetimeOutcome.Unavailable) unavailable.incrementAndGet()
                        }
                    }
                })
        }
        try {
            transport.advance(900_000)
            val session = HistoryFiles(saved.storage.historyDirectory(root), HistoryStorageLimits()).use { held ->
                held.acquire()
                assertEquals(0, unavailable.get()) // No connection ingress since reset.
                assertEquals(ListenableWorker.Result.success(), run(expected, enrolled.id))
                assertEquals("first native restore and exact worker recheck hit the held lane", 2, unavailable.get())
                val restored = (owner.session.snapshot() as SessionResult.Ready).envelope
                assertEquals(requests, transport.requests.get())
                assertEquals(WorkInfo.State.ENQUEUED, info(enrolled.id).state)
                assertNull(owner.historyCapability(restored.generation))
                assertNull(bounded("confirmed catalog cannot mint an enrollment") { owner.backgroundRefreshTarget() })
                assertFalse(bounded("B is still obstructed at actual recovery") { NativeConnection.recoverAddition(context) })
                assertEquals(address, raw.read().candidate)

                val stale = BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))
                periodic.enrollment(stale, true)
                val staleWork = unique(stale).single()
                assertEquals(ListenableWorker.Result.success(), run(stale, staleWork.id))
                assertEquals(WorkInfo.State.CANCELLED, info(staleWork.id).state)
                assertEquals(2, unavailable.get())
                assertEquals(requests, transport.requests.get())
                restored
            }
            assertTrue(obstruction.delete())
            assertTrue(bounded("recover B independently") { NativeConnection.recoverAddition(context) })
            assertNull(raw.read().candidate)
            assertEquals(saved, raw.read().entries.single())
            assertEquals(ListenableWorker.Result.success(), run(expected, enrolled.id))
            assertEquals(requests + 2, transport.requests.get())
            assertEquals(enrolled.id, unique(expected).single().id)
            assertEquals(WorkInfo.State.ENQUEUED, info(enrolled.id).state)
            assertSame(session, (owner.session.snapshot() as SessionResult.Ready).envelope)
            assertEquals(expected, target())
            owner.usageForeground(observer, true); settled()
            await("same worker recorded against A's original partition") { recordedHistory(owner).points.size == 2 }
            detach()
        } finally {
            try { NativeConnection.resetForTests() } finally {
                NativeConnection.rootFactory = originalRoot
            }
        }
    }

    @Test fun warmWorkerKeepsExactEnrollmentWhenHistoryIsUnavailableDuringUncertainAdd() {
        val blocked = AtomicBoolean()
        val syncHits = AtomicInteger()
        val unavailable = AtomicBoolean()
        val restoreHits = AtomicInteger()
        val catalog = AccountCatalog.open(root, UUID::randomUUID, beforeDirectorySync = {
            if (blocked.get()) { syncHits.incrementAndGet(); throw IOException("B replacement not synced") }
        })
        val periodic = PeriodicUsageRefresh(manager)
        val originalRoot = NativeConnection.rootFactory
        NativeConnection.resetForTests()
        NativeConnection.rootFactory = {
            AccountRuntimeOwner(context, catalog, transport, transport.clock, transport::pause,
                enrollment = periodic::enrollment, historyStorage = { path ->
                    val real = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(path))
                    object : HistoryLifetimeStorage by real {
                        override fun restore(): HistoryLifetimeOutcome {
                            val result = real.restore()
                            if (!unavailable.get()) return result
                            (result as HistoryLifetimeOutcome.Bound).access.revoke()
                            restoreHits.incrementAndGet()
                            return HistoryLifetimeOutcome.Unavailable
                        }
                    }
                })
        }
        try {
            phase(ConnectionPhase.IDLE)
            val first = bounded("A lifecycle START") { NativeConnection.observeDashboard(context) }
            owner.connect(); phase(ConnectionPhase.OBSERVED)
            await("A has a history measurement before STOP") { owner.historySnapshots.value.points.size == 1 }
            val session = (owner.session.snapshot() as SessionResult.Ready).envelope
            val expected = target()
            val enrolled = unique(expected).single()
            val saved = requireNotNull(catalog.legacyEntry())
            assertEquals(expected.partition, saved.partition)
            assertNotNull(owner.historyCapability(session.generation))
            val requests = transport.requests.get()

            blocked.set(true)
            assertNull(bounded("B reservation replaced before sync") { NativeConnection.beginAddition(context, "Synthetic B", AccountType.CODEX) })
            val raw = CatalogFile(File(root.canonicalFile, "account-catalog"))
            val address = requireNotNull(raw.read().candidate)
            val bHits = syncHits.get()
            assertTrue(bHits > 0)
            assertNull(catalog.snapshots.value.candidate)
            assertNull(NativeConnection.entry(context, address.id))
            bounded("A lifecycle STOP revokes the old port") { first.stop(); owner.commandsSettled() }
            assertNull(owner.historyCapability(session.generation))
            transport.advance(900_000)
            unavailable.set(true)

            repeat(2) { round ->
                // First: exact before rebind, unavailable afterwards. Second: known unavailable at ingress.
                assertEquals(ListenableWorker.Result.success(), run(expected))
                assertEquals("A's unavailable rebind reached on worker round $round", round + 1, restoreHits.get())
                assertEquals(requests, transport.requests.get())
                assertEquals(WorkInfo.State.ENQUEUED, info(enrolled.id).state)
                assertNull(owner.historyCapability(session.generation))
                assertSame(session, (owner.session.snapshot() as SessionResult.Ready).envelope)
            }
            val stale = BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))
            periodic.enrollment(stale, true)
            val staleWork = unique(stale).single()
            assertEquals(ListenableWorker.Result.success(), run(stale))
            assertEquals("uncertain Add cannot prove another target stale", WorkInfo.State.ENQUEUED, info(staleWork.id).state)
            assertEquals(2, restoreHits.get())
            assertEquals(requests, transport.requests.get())

            val second = bounded("A lifecycle START while history is unavailable") { NativeConnection.observeDashboard(context) }
            try {
                bounded("A settles foreground fallback and fresh quota") {
                    owner.commandsSettled(); owner.historyRestorationSettled()
                    owner.state.first { (it.refresh.usage.successfulAtMillis ?: 0L) >= 900_000L && !it.refresh.refreshing }
                }
                assertEquals(3, restoreHits.get())
                assertEquals(HistoryAvailability.STORAGE_FAILURE, owner.historyAvailability)
                assertSame(session, (owner.session.snapshot() as SessionResult.Ready).envelope)
                assertNull(owner.historyCapability(session.generation))
                assertNull(bounded("missing history cannot mint new enrollment") { owner.backgroundRefreshTarget() })
                assertEquals(saved, raw.read().entries.single())
                assertEquals(saved.storage.id, NativeConnection.dashboard(context).value?.id)
                assertEquals(bHits, syncHits.get())

                blocked.set(false)
                assertTrue(bounded("explicitly confirm and clear B's reservation") { NativeConnection.recoverAddition(context) })
                assertNull(raw.read().candidate)
                assertEquals(ListenableWorker.Result.success(), run(stale, staleWork.id))
                assertEquals(WorkInfo.State.CANCELLED, info(staleWork.id).state)
                unavailable.set(false)
                transport.advance(900_000)
                assertEquals(ListenableWorker.Result.success(), run(expected))
                assertEquals(enrolled.id, unique(expected).single().id)
                assertEquals(WorkInfo.State.ENQUEUED, info(enrolled.id).state)
                assertEquals(requests + 4, transport.requests.get())
                assertSame(session, (owner.session.snapshot() as SessionResult.Ready).envelope)
                assertEquals(expected.partition, owner.historyCapability(session.generation)?.partition)
            } finally { bounded("release A's returned observer") { second.stop() } }
        } finally {
            try { NativeConnection.resetForTests() } finally {
                NativeConnection.rootFactory = originalRoot
            }
        }
    }

    @Test fun expectedOfflineErrorsAndRetryAfterFinishOpportunityWithoutWorkManagerRetryLoop() {
        login(); detach(); val expected = target(); transport.advance(900_000)
        transport.usage = TransportResult.Response.bounded(403, "{}".toByteArray())
        assertEquals(ListenableWorker.Result.success(), run(expected))
        assertEquals(4L, transport.gets.get())
        transport.usage = TransportResult.Response.bounded(429, "{}".toByteArray(), RetryAfter.NotBefore(1_020_000))
        assertEquals(ListenableWorker.Result.success(), run(expected))
        assertEquals(6L, transport.gets.get())
        assertEquals(ListenableWorker.Result.success(), run(expected))
        assertEquals(6L, transport.gets.get()) // Retained server boundary, no new cycle.
        transport.advance(120_000)
        transport.usage = TransportResult.Failure(TransportFailure.NETWORK)
        assertEquals(ListenableWorker.Result.success(), run(expected))
        assertEquals(10L, transport.gets.get()) // Existing A2 maximum: three usage attempts + one inventory.
        assertEquals(0, unique(expected).single().runAttemptCount)
        reopenWithPoints(1)
        assertEquals(10L, transport.gets.get())
    }

    @Test fun workerCancellationReleasesOnlyItsInterestWithAndWithoutUiConsumer() {
        login(); detach(); transport.advance(900_000)
        for (visible in listOf(false, true)) {
            transport.heldPath = ReadOperation.USAGE.path
            if (visible) {
                owner.usageForeground(observer, true); settled()
                // A command receipt alone does not finish asynchronous history restoration.
                await("UI history restored before shared worker admission") { recordedHistory(owner).points.size == 1 }
            }
            val future = start(target())
            val held = transport.awaitHeld("worker request admitted before cancellation")
            await("positive worker network and storage admission; UI=$visible") {
                bounded("owner interests") { owner.backgroundRefreshNetworkInterests() == 1 && owner.backgroundRefreshInterests() == 1 }
            }
            assertTrue("cancel the admitted worker; UI=$visible", future.cancel(true))
            // Future cancellation is immediate, not a receipt that the owner-lane finally ran.
            await("worker network detached; UI=$visible", { "directFutureDone=${future.isDone}, directFutureCancelled=${future.isCancelled}, heldCancelled=${held.cancelled}" }) {
                bounded("remaining owner interests") {
                    owner.backgroundRefreshNetworkInterests() == 0 &&
                        owner.backgroundRefreshInterests() == (if (visible) 1 else 0)
                } && (visible || held.cancelled)
            }
            assertTrue(future.isCancelled)
            assertEquals(!visible, held.cancelled)
            transport.heldPath = null; held.reply(transport.usage)
            settled()
            // A UI-owned flight keeps process storage until its actual recording settles.
            await("cancelled or shared endpoint and storage settle; UI=$visible") {
                !owner.state.value.refresh.refreshing && bounded("settled storage interest") { owner.backgroundRefreshInterests() } == 0
            }
            assertTrue(owner.session.snapshot() is SessionResult.Ready)
            assertEquals(if (visible) 5L else 3L, transport.gets.get())
            if (visible) {
                phase(ConnectionPhase.OBSERVED)
                assertEquals(2, recordedHistory(owner).points.size)
                detach()
            }
            transport.advance(900_000)
        }
    }

    @Test fun foregroundAndWorkerShareOnlyTheirAccountInBothOrdersAndReleaseIsIsolated() {
        val f = AccountSelectionFixture()
        f.prepare()
        try {
            val periodic = PeriodicUsageRefresh(manager)
            f.enrollment = periodic::enrollment
            f.start(); f.installActivity()
            val a = f.runtime(f.a); val b = f.runtime(f.b)
            f.phase(a, ConnectionPhase.RESTORED); f.phase(b, ConnectionPhase.RESTORED)
            val observer = f.observer()
            f.settle(a)
            val targetA = BackgroundRefreshTarget(requireNotNull(f.a.partition))
            val targetB = BackgroundRefreshTarget(requireNotNull(f.b.partition))
            periodic.enrollment(targetB, true) // Previously enrolled, currently nonselected.
            val workB = unique(targetB).single().id
            val path = ReadOperation.USAGE.path
            for ((round, workerFirst) in listOf(true, false).withIndex()) {
                f.aTransport.advance(900_000); f.bTransport.advance(900_000)
                f.aTransport.heldPath = path; f.bTransport.heldPath = path
                if (workerFirst) periodic.enrollment(targetA, true) else a.readUsage()
                val workA = unique(targetA).single().id
                if (!workerFirst) f.await("A foreground request precedes worker") { f.aTransport.liveHeldCall(path) != null }
                val workerA = start(targetA, workA)
                val workerB = start(targetB, workB)
                try {
                    val heldA = awaitWorkerLease(f, a, f.aTransport)
                    val heldB = awaitWorkerLease(f, b, f.bTransport)
                    if (workerFirst) { a.readUsage(); f.settle(a) }
                    assertEquals(round * 2L + 1, f.aTransport.gets.get())
                    assertEquals(round * 2L + 1, f.bTransport.gets.get())
                    assertTrue(workerA.cancel(true))
                    f.await("only A's background lease released") {
                        f.receipt("network leases") {
                            a.backgroundRefreshNetworkInterests() == 0 && b.backgroundRefreshNetworkInterests() == 1
                        }
                    }
                    assertFalse(heldA.cancelled); assertFalse(heldB.cancelled)
                    f.aTransport.heldPath = null; f.bTransport.heldPath = null
                    heldA.reply(); heldB.reply()
                    assertEquals(ListenableWorker.Result.success(), futureResult("B worker completed", workerB))
                    f.await("both original partitions recorded") {
                        listOf(targetA, targetB).all { target -> f.appends.count {
                            it.admission.partition == target.partition && it.admission.event is HistoryEvent.Observed &&
                                it.result is HistoryAppendOutcome.Stored
                        } == round + 1 }
                    }
                    f.await("A's accepted history interest drained") {
                        f.receipt("A settlement", a) { a.backgroundRefreshInterests() == 0 && !a.state.value.refresh.refreshing }
                    }
                    assertEquals((round + 1) * 2L, f.aTransport.gets.get())
                    assertEquals((round + 1) * 2L, f.bTransport.gets.get())
                    assertEquals(workA, unique(targetA).single().id)
                    assertEquals(workB, unique(targetB).single().id)
                } finally { workerA.cancel(true); workerB.cancel(true) }
            }
            f.stop(observer)
        } finally { f.cleanup() }
    }

    private fun awaitWorkerLease(f: AccountSelectionFixture, controller: ConnectionController,
        transport: NavigationTransport): NavigationTransport.HeldCall {
        val path = ReadOperation.USAGE.path
        f.await("held worker owns exact account's history and network") {
            transport.liveHeldCall(path) != null && f.receipt("owner leases", controller) {
                controller.backgroundRefreshNetworkInterests() == 1 && controller.backgroundRefreshInterests() == 1
            }
        }
        return requireNotNull(transport.liveHeldCall(path))
    }

    @Test fun reauthorizationAndRemovalAfterReconstructionCancelOnlyTheOriginalCapturedAccount() {
        login(); detach()
        val a = target(); val aWork = unique(a).single().id
        var bSlot: UUID? = null
        val usage = transport.usage; val tokens = transport.tokens
        try {
            transport.heldPath = "/api/accounts/deviceauth/token"
            val add = requireNotNull(bounded("begin B") { NativeConnection.beginAddition(context, "Synthetic B", AccountType.CODEX) })
            val poll = transport.awaitHeld("B is still unadmitted")
            val raw = CatalogFile(File(root.canonicalFile, "account-catalog"))
            bSlot = requireNotNull(raw.read().candidate).credentialSlot
            assertEquals(listOf(aWork), activeWork().map { it.id })
            transport.heldPath = null
            poll.reply(TransportResult.Response.bounded(200,
                """{"authorization_code":"synthetic-b","code_verifier":"synthetic-b-verifier"}""".toByteArray()))
            val added = bounded("B commits") { add.result() } as AdditionResult.Added
            val savedB = raw.read().entries.single { it.storage.id == added.id }
            val targetB = BackgroundRefreshTarget(requireNotNull(savedB.partition))
            val bWork = unique(targetB).single().id
            val b = requireNotNull(NativeConnection.entry(context, added.id))
            bounded("B first selected read finishes") { b.state.first { it.phase == ConnectionPhase.OBSERVED && !it.refresh.refreshing } }

            NativeConnection.resetForTests()
            transport.advance(900_000)
            transport.usage = TransportResult.Response.bounded(401, "{}".toByteArray())
            transport.tokens = TransportResult.Response.bounded(400, """{"error":"invalid_grant"}""".toByteArray())
            assertEquals(ListenableWorker.Result.success(), run(a, aWork))
            phase(ConnectionPhase.REAUTH_REQUIRED)
            assertEquals(WorkInfo.State.CANCELLED, info(aWork).state)
            assertEquals(added.id, raw.read().selected)
            assertEquals(bWork, unique(targetB).single().id)

            transport.usage = usage; transport.tokens = tokens
            owner.usageForeground(observer, true); owner.connect(); phase(ConnectionPhase.OBSERVED)
            val replacement = target()
            assertNotEquals(a, replacement)
            val replacementWork = unique(replacement).single().id
            detach(); NativeConnection.resetForTests(); phase(ConnectionPhase.RESTORED)
            owner.signOut(); phase(ConnectionPhase.SIGNED_OUT)
            assertEquals(WorkInfo.State.CANCELLED, info(replacementWork).state)
            assertEquals(bWork, unique(targetB).single().id)
            assertEquals(added.id, raw.read().selected)

            val count = transport.requests.get()
            assertEquals(ListenableWorker.Result.success(), run(a, aWork))
            assertEquals(ListenableWorker.Result.success(), run(replacement, replacementWork))
            assertEquals(count, transport.requests.get())
            assertEquals(ListenableWorker.Result.success(), run(targetB, bWork))
            assertEquals(count + 2, transport.requests.get())
            assertEquals(bWork, unique(targetB).single().id)
        } finally {
            transport.heldPath = null
            NativeConnection.resetForTests()
            bSlot?.let { slot -> KeystoreCredentialStore(context, slot).let { it.delete(it.openSession()) } }
        }
    }

    @Test fun installedSchedulerComponentsKeepCallerProtectionsAndDefaultProcessWithoutForegroundService() {
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        val services = installed.services.orEmpty().associateBy { it.name }
        assertEquals(setOf("androidx.work.impl.background.systemjob.SystemJobService", "androidx.room.MultiInstanceInvalidationService"), services.keys)
        val job = services.getValue("androidx.work.impl.background.systemjob.SystemJobService")
        assertTrue(job.exported); assertTrue(job.enabled)
        assertEquals("android.permission.BIND_JOB_SERVICE", job.permission)
        assertFalse(services.getValue("androidx.room.MultiInstanceInvalidationService").exported)
        val receivers = installed.receivers.orEmpty().associateBy { it.name }
        assertEquals(setOf("androidx.work.impl.utils.ForceStopRunnable\$BroadcastReceiver",
            "androidx.work.impl.background.systemalarm.RescheduleReceiver", "androidx.work.impl.diagnostics.DiagnosticsReceiver",
            "androidx.profileinstaller.ProfileInstallReceiver"), receivers.keys)
        assertFalse(receivers.getValue("androidx.work.impl.utils.ForceStopRunnable\$BroadcastReceiver").exported)
        assertFalse(receivers.getValue("androidx.work.impl.background.systemalarm.RescheduleReceiver").exported)
        for (name in listOf("androidx.work.impl.diagnostics.DiagnosticsReceiver", "androidx.profileinstaller.ProfileInstallReceiver")) {
            assertTrue(receivers.getValue(name).exported)
            assertEquals("android.permission.DUMP", receivers.getValue(name).permission)
        }
        val provider = installed.providers.orEmpty().single()
        assertEquals("androidx.startup.InitializationProvider", provider.name)
        assertFalse(provider.exported); assertEquals(app.packageName + ".androidx-startup", provider.authority)
        for (component in installed.services.orEmpty().toList() + installed.receivers.orEmpty().toList() + installed.providers.orEmpty().toList())
            assertEquals(app.packageName, component.processName)
    }

    private fun login() {
        phase(ConnectionPhase.IDLE)
        owner.usageForeground(observer, true); settled()
        owner.connect(); phase(ConnectionPhase.OBSERVED)
        await("initial admitted history persisted") { owner.historySnapshots.value.points.size == 1 }
    }
    private fun detach() { owner.usageForeground(observer, false); settled(); assertNull(owner.currentHistoryDisplayContext()) }
    private fun target() = requireNotNull(bounded("capture checked enrollment") { owner.backgroundRefreshTarget() })
    private fun settled() = bounded("owner command barrier") { owner.commandsSettled() }
    private fun phase(expected: ConnectionPhase) = bounded("phase $expected") {
        owner.commandsSettled(); owner.state.first { it.phase == expected && !it.refresh.refreshing }
    }
    private fun <T> bounded(step: String, action: suspend () -> T): T = runBlocking {
        try { withTimeout(5_000) { action() } }
        catch (_: TimeoutCancellationException) { throw AssertionError("$step: last state=${owner.state.value}, history=${owner.historyAvailability}, requests=${transport.requests.get()}") }
    }
    private fun await(step: String, details: () -> String = { "" }, predicate: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 5_000, condition = predicate) }
        catch (_: ComposeTimeoutException) { throw AssertionError("$step: ${diagnostics()}, ${details()}") }
    }
    private fun diagnostics() = "last state=${owner.state.value}, history=${owner.historyAvailability}, GETs=${transport.gets.get()}, " +
        "networkInterests=${bounded("diagnostic network interests") { owner.backgroundRefreshNetworkInterests() }}, " +
        "storageInterests=${bounded("diagnostic storage interests") { owner.backgroundRefreshInterests() }}, enrolledWork=${activeWork().map { it.state }}"
    private fun activeWork() = futureResult("query enrolled work", manager.getWorkInfosByTag(PeriodicUsageRefresh.TAG)).filterNot { it.state.isFinished }
    private fun unique(target: BackgroundRefreshTarget): List<WorkInfo> {
        var found = emptyList<WorkInfo>()
        await("unique enrolled request committed") {
            found = futureResult("query unique work", manager.getWorkInfosForUniqueWork(PeriodicUsageRefresh.name(target))).filterNot { it.state.isFinished }
            found.isNotEmpty()
        }
        return found
    }
    private fun info(id: UUID) = requireNotNull(futureResult("query work $id", manager.getWorkInfoById(id)))
    private fun run(target: BackgroundRefreshTarget, id: UUID? = null) =
        runData(workDataOf(PeriodicUsageRefresh.LIFETIME to target.partition.value.toString()), id)
    private fun runData(input: Data, id: UUID? = null) = futureResult("direct worker result", startData(input, id))
    private fun start(target: BackgroundRefreshTarget, id: UUID? = null) =
        startData(workDataOf(PeriodicUsageRefresh.LIFETIME to target.partition.value.toString()), id)
    private fun startData(input: Data, id: UUID? = null): ListenableFuture<ListenableWorker.Result> {
        lateinit var future: ListenableFuture<ListenableWorker.Result>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val builder = TestListenableWorkerBuilder<PeriodicUsageRefreshWorker>(context, input)
            if (id != null) builder.setId(id)
            future = builder.build().startWork()
        }
        return future
    }
    private fun awaitJob(work: UUID): JobInfo {
        var found: JobInfo? = null
        val scheduler = requireNotNull(app.getSystemService(JobScheduler::class.java)).let {
            if (Build.VERSION.SDK_INT >= 34) it.forNamespace("androidx.work.systemjobscheduler") else it
        }
        await("ordinary JobScheduler registration") {
            found = scheduler.allPendingJobs.singleOrNull { it.extras.getString("EXTRA_WORK_SPEC_ID") == work.toString() }
            found != null
        }
        return requireNotNull(found)
    }
    private fun dispatchOsJob(job: JobInfo, work: UUID) {
        makeDueForOsDispatch(work)
        val namespace = if (Build.VERSION.SDK_INT >= 34) "--namespace androidx.work.systemjobscheduler " else ""
        val dispatch = shell("cmd jobscheduler run -f $namespace${app.packageName} ${job.id}")
        assertTrue("forced OS dispatch: $dispatch", dispatch.contains("Running job"))
    }
    @SuppressLint("RestrictedApi")
    private fun makeDueForOsDispatch(work: UUID) {
        // Accelerate only test time, not the delivered period/constraints/input or execution path.
        // Cancel this ID's in-process delayed tracker so ONLY the real JobService can start it.
        val real = manager as WorkManagerImpl
        real.schedulers.filterIsInstance<GreedyScheduler>().forEach { it.cancel(work.toString()) }
        real.workDatabase.workSpecDao().setLastEnqueueTime(work.toString(), System.currentTimeMillis() - 900_001)
    }
    private fun shell(command: String): String {
        val stream = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        val read = FutureTask { stream.bufferedReader().use { it.readText() } }
        Thread(read, "periodic-test-shell").apply { isDaemon = true; start() }
        return try { futureResult("OS dispatch shell", read) }
        finally { read.cancel(true); stream.close() }
    }
    private fun cancelEnrolledWork() = futureResult("cancel enrolled work", manager.cancelAllWorkByTag(PeriodicUsageRefresh.TAG).result)
    private fun <T> futureResult(step: String, future: Future<T>): T = try { future.get(5, TimeUnit.SECONDS) }
        catch (_: TimeoutException) {
            val state = if (::context.isInitialized) owner.state.value.toString() else "preparing isolated context"
            throw AssertionError("$step: last state=$state, requests=${transport.requests.get()}")
        }
    private fun reopenWithPoints(expected: Int) {
        val requests = transport.requests.get()
        NativeConnection.resetForTests(); phase(ConnectionPhase.RESTORED)
        owner.usageForeground(observer, true); settled()
        await("fresh holder rereads durable background measurements") { owner.historySnapshots.value.points.size == expected }
        assertEquals(requests, transport.requests.get())
    }
}
