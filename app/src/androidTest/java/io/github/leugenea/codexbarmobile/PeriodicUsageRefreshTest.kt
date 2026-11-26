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
import io.github.leugenea.codexbarmobile.account.AccountType
import io.github.leugenea.codexbarmobile.account.CatalogFile
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
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
    private val originalFactory = NativeConnection.factory
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
        NativeConnection.factory = {
            NativeConnection.create(context, transport, transport.clock, transport::pause,
                PeriodicUsageRefresh(manager)::enrollment)
        }
        NativeConnection.resetForTests()
    }

    @After fun cleanup() {
        cancelEnrolledWork()
        try { NativeConnection.resetForTests() }
        finally {
            NativeConnection.factory = originalFactory
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

    @Test fun capturedEntryEnrollmentCancelsOnlyItsOwnContinuingWorkAcrossCallbackReconstruction() {
        val a = BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))
        val b = BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))
        val periodic = PeriodicUsageRefresh(manager)
        val first = periodic.entryEnrollment(null)
        first(a); periodic.enrollment(b)
        val aWork = unique(a).single(); val bWork = unique(b).single()
        first(a)
        assertEquals(aWork.id, unique(a).single().id)
        val reconstructed = periodic.entryEnrollment(a.partition)
        reconstructed(null); reconstructed(null)
        await("only captured A work cancelled") { info(aWork.id).state == WorkInfo.State.CANCELLED }
        assertEquals(bWork.id, unique(b).single().id)
        assertFalse(info(bWork.id).state.isFinished)
        assertEquals(1, activeWork().size)
        assertEquals(0L, transport.requests.get())
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
            makeDueForOsDispatch(work.id)
            val namespace = if (Build.VERSION.SDK_INT >= 34) "--namespace androidx.work.systemjobscheduler " else ""
            val dispatch = shell("cmd jobscheduler run -f $namespace${app.packageName} ${job.id}")
            assertTrue("forced OS dispatch: $dispatch", dispatch.contains("Running job"))
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

        val injected = NativeConnection.factory
        val originalRoot = NativeConnection.rootFactory
        val syncAttempts = AtomicInteger()
        val blocked = AtomicBoolean(true)
        NativeConnection.resetForTests()
        NativeConnection.rootFactory = {
            NativeConnection.createOwner(context, transport, transport.clock, transport::pause,
                legacyEnrollment = periodic::entryEnrollment, openCatalog = {
                    AccountCatalog.open(root, { error("saved catalog required") }, beforeDirectorySync = {
                        syncAttempts.incrementAndGet()
                        if (blocked.get()) throw IOException("cold catalog directory sync blocked")
                    })
                })
        }
        NativeConnection.factory = originalFactory
        try {
            assertEquals("worker must be the first cold ingress", 0, syncAttempts.get())
            assertEquals(ListenableWorker.Result.success(), run(expected))
            assertEquals("worker reached the cold-open barrier", 1, syncAttempts.get())
            assertEquals(requests, transport.requests.get())
            // The status query is queued on WorkManager's serial executor after any worker cancellation.
            assertEquals(WorkInfo.State.ENQUEUED, info(enrolled.id).state)

            blocked.set(false)
            assertTrue(bounded("same-process catalog recovery") { NativeConnection.recoverAddition(context) })
            phase(ConnectionPhase.RESTORED)
            assertEquals(2, syncAttempts.get())
            assertEquals(expected, target())
            assertEquals(requests, transport.requests.get())
            assertEquals(enrolled.id, unique(expected).single().id)

            transport.advance(900_000)
            assertEquals(ListenableWorker.Result.success(), run(expected))
            assertEquals(requests + 2, transport.requests.get())
            assertEquals(enrolled.id, unique(expected).single().id)
            val restored = owner
            restored.usageForeground(observer, true); settled()
            await("worker added a durable measurement to the same legacy history") { recordedHistory(restored).points.size == 2 }
            detach()

            val stale = BackgroundRefreshTarget(HistoryPartition(UUID.randomUUID()))
            periodic.enrollment(stale)
            val staleWork = unique(stale).single()
            assertEquals(ListenableWorker.Result.success(), run(stale))
            assertEquals(WorkInfo.State.CANCELLED, info(staleWork.id).state)
            assertEquals(WorkInfo.State.ENQUEUED, info(enrolled.id).state)
            assertEquals(requests + 2, transport.requests.get())
        } finally {
            try { NativeConnection.resetForTests() } finally {
                NativeConnection.factory = injected
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
        val injected = NativeConnection.factory
        val originalRoot = NativeConnection.rootFactory
        val unavailable = AtomicInteger()
        val raw = CatalogFile(File(root.canonicalFile, "account-catalog"))
        NativeConnection.resetForTests()
        NativeConnection.rootFactory = {
            AccountRuntimeOwner(context, AccountCatalog.open(root, { error("saved catalog required") }),
                transport, transport.clock, transport::pause, legacyEnrollment = periodic::entryEnrollment,
                historyStorage = { directory ->
                    val real = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(directory))
                    object : HistoryLifetimeStorage by real {
                        override fun restore() = real.restore().also {
                            if (it == HistoryLifetimeOutcome.Unavailable) unavailable.incrementAndGet()
                        }
                    }
                })
        }
        NativeConnection.factory = originalFactory
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
                periodic.enrollment(stale)
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
                NativeConnection.factory = injected
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
        val injected = NativeConnection.factory
        val originalRoot = NativeConnection.rootFactory
        NativeConnection.resetForTests()
        NativeConnection.rootFactory = {
            AccountRuntimeOwner(context, catalog, transport, transport.clock, transport::pause,
                legacyEnrollment = periodic::entryEnrollment, historyStorage = { path ->
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
        NativeConnection.factory = originalFactory
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
            periodic.enrollment(stale)
            val staleWork = unique(stale).single()
            assertEquals(ListenableWorker.Result.success(), run(stale))
            assertEquals(WorkInfo.State.CANCELLED, info(staleWork.id).state)
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
                NativeConnection.factory = injected
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

    @Test fun removalAndTerminalReauthorizationCancelEnrolledWorkWithoutSuccessorReads() {
        login(); detach(); val old = target()
        owner.signOut(); phase(ConnectionPhase.SIGNED_OUT)
        await("removal cancels all enrolled work") { activeWork().isEmpty() }
        val count = transport.requests.get()
        assertEquals(ListenableWorker.Result.success(), run(old))
        assertEquals(count, transport.requests.get())
        owner.usageForeground(observer, true); owner.connect(); phase(ConnectionPhase.OBSERVED); detach()
        transport.advance(900_000)
        transport.usage = TransportResult.Response.bounded(401, "{}".toByteArray())
        transport.tokens = TransportResult.Response.bounded(400, """{"error":"invalid_grant"}""".toByteArray())
        assertEquals(ListenableWorker.Result.success(), run(target()))
        phase(ConnectionPhase.REAUTH_REQUIRED)
        await("terminal reauthorization cancels enrolled work") { activeWork().isEmpty() }
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
    private fun start(target: BackgroundRefreshTarget) = startData(workDataOf(PeriodicUsageRefresh.LIFETIME to target.partition.value.toString()))
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
