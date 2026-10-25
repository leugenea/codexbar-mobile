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
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
                bounded("driver worker admitted") { owner.backgroundRefreshInterests() } == 1 && info(work.id).state == WorkInfo.State.RUNNING
            }
            driver.stopRunningWorkWithReason(work.id, WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW)
            await("scheduler stop propagates cancellation and releases sole owner interest") {
                bounded("driver stopped worker released") { owner.backgroundRefreshInterests() } == 0 && held.cancelled && info(work.id).state == WorkInfo.State.ENQUEUED
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
            if (visible) { owner.usageForeground(observer, true); settled() }
            val future = start(target())
            val held = transport.awaitHeld("worker request admitted before cancellation")
            await("positive worker owner admission") { bounded("owner interest") { owner.backgroundRefreshInterests() } == 1 }
            future.cancel(true)
            await("worker interest released") { bounded("released owner interest") { owner.backgroundRefreshInterests() } == 0 }
            assertTrue(future.isCancelled)
            assertEquals(!visible, held.cancelled)
            transport.heldPath = null; held.reply(transport.usage)
            settled()
            await("cancelled or shared endpoint flight settles") { !owner.state.value.refresh.refreshing }
            assertTrue(owner.session.snapshot() is SessionResult.Ready)
            if (visible) { phase(ConnectionPhase.OBSERVED); detach() }
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
    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 5_000, condition = predicate) }
        catch (_: ComposeTimeoutException) { throw AssertionError("$step: last state=${owner.state.value}, history=${owner.historyAvailability}, GETs=${transport.gets.get()}, work=${activeWork().map { it.state }}") }
    }
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
    private fun run(target: BackgroundRefreshTarget) = runData(workDataOf(PeriodicUsageRefresh.LIFETIME to target.partition.value.toString()))
    private fun runData(input: Data) = futureResult("direct worker result", startData(input))
    private fun start(target: BackgroundRefreshTarget) = startData(workDataOf(PeriodicUsageRefresh.LIFETIME to target.partition.value.toString()))
    private fun startData(input: Data): ListenableFuture<ListenableWorker.Result> {
        lateinit var future: ListenableFuture<ListenableWorker.Result>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            future = TestListenableWorkerBuilder<PeriodicUsageRefreshWorker>(context, input).build().startWork()
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
