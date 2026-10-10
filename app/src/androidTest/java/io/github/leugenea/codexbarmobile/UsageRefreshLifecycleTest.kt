package io.github.leugenea.codexbarmobile

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real Activity boundaries, real protected restoration, synthetic method/path-routed transport. */
@RunWith(AndroidJUnit4::class)
class UsageRefreshLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val originalFactory = NativeConnection.factory
    private val fake = LifecycleTransport()
    private var factories = 0
    private val owner get() = NativeConnection.get(context)
    private var epochs: ForegroundEpochs? = null

    @Before fun install() {
        NativeConnection.factory = { app ->
            factories++
            NativeConnection.create(app, fake, SystemTransportClock) { kotlinx.coroutines.delay(it) }
        }
        NativeConnection.resetForTests()
        val store = store()
        val generation = store.openSession()
        assertTrue(store.delete(generation) is CredentialResult.Success)
        val next = store.openSession()
        assertTrue(store.replace(CredentialEnvelope(next, SensitiveValue.copyOf("synthetic-b2-native-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-b2-native-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
        epochs = ForegroundEpochs().also { tracker ->
            instrumentation.runOnMainSync { tracker.install() }
        }
    }

    @After fun uninstall() {
        try {
            epochs?.close()
            NativeConnection.resetForTests()
            val store = store()
            assertTrue(store.delete(store.openSession()) is CredentialResult.Success)
        } finally { NativeConnection.factory = originalFactory }
    }

    @Test fun homeCancelsEligibleReadAndReturningTaskRefreshesOnce() {
        launchGate().use { scenario ->
            var original: MainActivity? = null
            scenario.onActivity { original = it }
            owner.readUsage()
            settleCommands("initial read command")
            waitFor("initial successful cycle") { observed() }
            val success = owner.state.value.refresh.usage.success
            fake.holdUsage = true
            owner.readUsage()
            settleCommands("held foreground read command")
            val old = awaitLiveUsage("held foreground usage reached transport")
            armEpochs()
            pressHome()
            waitFor("Home stops Activity and cancellation has completed") {
                old.cancelled.get() && !owner.state.value.refresh.refreshing && belowStarted(original!!)
            }
            assertSame(success, owner.state.value.refresh.usage.success)
            assertTrue(owner.session.snapshot() is SessionResult.Ready)
            assertEquals(3, fake.gets.get())
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            val returning = awaitLiveUsage("original task resumes with a live usage GET", original, old)
            scenario.onActivity { assertSame(original, it); assertSame(owner, it.connection) }
            assertEquals(1, factories)
            releaseCoalescedUsage(returning, original!!)
            waitFor("bounded resumed cycle publishes both endpoints") {
                observed() && owner.state.value.refresh.usage.success !== success
            }
            finishRecovery(old, original, baselineGets = 3)
        }
    }

    @Test fun activityRecreationKeepsOneOwnerAndCoalescesRefresh() {
        fake.holdUsage = true
        launchGate().use { scenario ->
            var original: MainActivity? = null
            scenario.onActivity { original = it }
            val shared = owner
            shared.readUsage()
            settleCommands("pre-recreation read command")
            val old = awaitLiveUsage("pre-recreation usage is in flight")
            armEpochs()
            scenario.recreate()
            var recreated: MainActivity? = null
            scenario.onActivity { recreated = it; assertSame(shared, it.connection) }
            assertNotSame(original, recreated)
            assertEquals(Lifecycle.State.DESTROYED, original!!.lifecycle.currentState)
            val returning = awaitLiveUsage("old transport cancelled and recreated Activity owns a live resumed read",
                recreated, old)
            assertEquals(1, factories)
            releaseCoalescedUsage(returning, recreated!!)
            waitFor("recreated cycle finishes with separate successful observations") { observed() }
            assertNotNull(shared.state.value.refresh.usage.successfulAtMillis)
            assertNotNull(shared.state.value.refresh.inventory.successfulAtMillis)
            finishRecovery(old, recreated, baselineGets = 1)
        }
    }

    private fun observed(state: ConnectionState = owner.state.value): Boolean {
        return state.phase == ConnectionPhase.OBSERVED && !state.refresh.refreshing && state.problem == null &&
            state.refresh.usage.success != null && state.refresh.inventory.success != null
    }

    private fun awaitLiveUsage(step: String, activity: MainActivity? = null, predecessor: Held? = null): Held {
        var selected: Held? = null
        waitFor(step) {
            selected = fake.liveUsage()
            selected != null && selected !== predecessor &&
                (activity == null || resumed(activity)) && (predecessor == null || predecessor.cancelled.get())
        }
        return requireNotNull(selected)
    }

    private fun releaseCoalescedUsage(selected: Held, activity: MainActivity) {
        waitFor("live return reaches the exact coalescing checkpoint") {
            var released = false
            instrumentation.runOnMainSync {
                settleCommands("foreground commands before coalescing checkpoint")
                val current = fake.liveUsage()
                if (current != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    check(current === selected || selected.cancelled.get()) { "Replacement must retire the selected call" }
                    val before = fake.gets.get()
                    owner.readUsage(); owner.readUsage()
                    settleCommands("duplicate foreground read commands coalesce before release")
                    assertEquals("held duplicates issue no GET: ${diagnostics()}", before, fake.gets.get())
                    fake.releaseUsage(current)
                    // Suspend on an IO-completed state signal while Main fences lifecycle delivery.
                    // No spinning or Compose synchronization is needed inside this checkpoint.
                    awaitOwner("released cycle settles on owner lane") { owner.state.first { observed(it) } }
                    settleCommands("released cycle completion commands")
                    assertEquals("coalesced cycle adds exactly its inventory GET: ${diagnostics()}",
                        before + 1, fake.gets.get())
                    released = true
                }
            }
            released
        }
    }

    private fun finishRecovery(old: Held, activity: MainActivity, baselineGets: Int) {
        // Stop the real lifecycle observer before capturing the success/count oracle:
        // a subsequent completed return epoch may otherwise legitimately replace it again.
        pressHome()
        waitFor("recovered Activity is stopped and all endpoint work has settled") {
            belowStarted(activity) && !owner.state.value.refresh.refreshing && fake.liveUsage() == null
        }
        settleCommands("recovered foreground loss command")
        val fresh = owner.state.value.refresh.usage.success
        assertNotNull(fresh)
        assertEpochRequests(baselineGets)
        val total = fake.gets.get()
        old.reply(response("{}", 401))
        settleCommands("stale unauthorized predecessor reply")
        assertSame(fresh, owner.state.value.refresh.usage.success)
        assertEquals(total, fake.gets.get())
        assertEquals(1, fake.maximum.get())
    }

    private fun assertEpochRequests(baselineGets: Int) {
        val tracker = requireNotNull(epochs)
        val ledger = tracker.snapshot()
        val returned = fake.usageCalls.filter { it.epoch != null }
        val byEpoch = returned.groupBy { requireNotNull(it.epoch) }
        assertNotEquals("return must observe a START: ${diagnostics()}", 0, ledger.size)
        assertEquals("all return epochs have stopped: ${diagnostics()}", ledger.size + 1, tracker.stops.get())
        assertEquals("every return GET belongs to an observed epoch: ${diagnostics()}",
            returned.size, ledger.sumOf { byEpoch[it.index].orEmpty().size })
        val returnGets = ledger.sumOf { epochGets(it, byEpoch[it.index].orEmpty()) }
        assertEquals("exact epoch-aware GET total: ${diagnostics()}", baselineGets + returnGets, fake.gets.get())
    }

    private fun epochGets(epoch: ForegroundEpoch, calls: List<Held>): Int {
        val skipped = requireNotNull(epoch.stoppedBeforeAdmission) { "Epoch did not stop: ${diagnostics()}" }
        assertEquals("epoch ${epoch.index} admits at most one usage GET: ${diagnostics()}",
            if (skipped) 0 else 1, calls.size)
        if (skipped) return 0
        val call = calls.single()
        assertEquals("cancelled epochs cannot reach inventory: ${diagnostics()}",
            !call.cancelled.get(), call.inventory.get())
        // A cancelled admitted epoch contributes usage only; a completed epoch adds inventory.
        return if (call.cancelled.get()) 1 else 2
    }

    private fun launchGate(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        waitFor("restored session is dormant") { owner.state.value.phase == ConnectionPhase.RESTORED }
        assertEquals(0, fake.gets.get())
        compose.onNodeWithTag("connection-tab").performClick()
        compose.waitForIdle()
        settleCommands("connection tab lifecycle registration")
        return scenario
    }

    private fun pressHome() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun belowStarted(activity: MainActivity): Boolean = lifecycle(activity) < Lifecycle.State.STARTED
    private fun resumed(activity: MainActivity): Boolean = lifecycle(activity) == Lifecycle.State.RESUMED
    private fun lifecycle(activity: MainActivity): Lifecycle.State {
        var state = Lifecycle.State.INITIALIZED
        instrumentation.runOnMainSync { state = activity.lifecycle.currentState }
        return state
    }

    private fun waitFor(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 10_000, condition = condition) }
        catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: ${diagnostics()}", error)
        }
    }

    private fun settleCommands(step: String) = awaitOwner(step) { owner.commandsSettled() }

    private fun awaitOwner(step: String, action: suspend () -> Unit) {
        try { runBlocking(Dispatchers.IO) { withTimeout(5_000) { action() } } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$step: ${diagnostics()}", error)
        }
    }

    private fun diagnostics(): String = "last state=${owner.state.value}, refresh=${owner.state.value.refresh}, " +
        "epochs=${epochs?.snapshot()}, stops=${epochs?.stops?.get()}, gets=${fake.gets.get()}, " +
        "held=${fake.held.map { it.diagnostics() }}, usage=${fake.usageCalls.map { it.diagnostics() }}"

    private fun store() = KeystoreCredentialStore(context, NativeConnection.session(context))

    private fun armEpochs() {
        instrumentation.runOnMainSync { requireNotNull(epochs).arm() }
    }

    /** Attached before Compose registers its lifecycle observer, including after recreation. */
    private inner class ForegroundEpochs : Application.ActivityLifecycleCallbacks, AutoCloseable {
        val stops = AtomicInteger()
        private val application get() = context.applicationContext as Application
        private val registrations = mutableListOf<Pair<MainActivity, LifecycleEventObserver>>()
        private val ledger = mutableListOf<ForegroundEpoch>()
        private var armed = false

        fun install() { application.registerActivityLifecycleCallbacks(this) }

        fun arm() = synchronized(fake.gate) {
            stops.set(0)
            ledger.clear()
            fake.trackEpochs { ledger.size }
            armed = true
        }

        fun snapshot(): List<ForegroundEpoch> = synchronized(fake.gate) { ledger.toList() }

        private fun watch(activity: MainActivity) {
            val observer = LifecycleEventObserver { _, event ->
                if (armed) record(event)
            }
            registrations += activity to observer
            activity.lifecycle.addObserver(observer)
        }

        private fun record(event: Lifecycle.Event) = synchronized(fake.gate) {
            when (event) {
                Lifecycle.Event.ON_START -> ledger += ForegroundEpoch(ledger.size + 1)
                Lifecycle.Event.ON_STOP -> {
                    stops.incrementAndGet()
                    ledger.lastOrNull()?.let { epoch ->
                        // Capture skipped status at STOP, not from a later aggregate request count.
                        ledger[ledger.lastIndex] = epoch.copy(stoppedBeforeAdmission =
                            fake.usageCalls.none { it.epoch == epoch.index })
                    }
                }
                else -> Unit
            }
        }

        override fun close() {
            instrumentation.runOnMainSync {
                application.unregisterActivityLifecycleCallbacks(this)
                registrations.forEach { (activity, observer) -> activity.lifecycle.removeObserver(observer) }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) watch(activity)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private data class ForegroundEpoch(val index: Int, val stoppedBeforeAdmission: Boolean? = null)

    private class LifecycleTransport : AuthTransport {
        @Volatile var holdUsage = false
        val held = CopyOnWriteArrayList<Held>()
        val usageCalls = CopyOnWriteArrayList<Held>()
        val gets = AtomicInteger()
        val gate = Any()
        private val active = AtomicInteger()
        val maximum = AtomicInteger()
        private var pendingInventory: Held? = null
        private var currentEpoch: (() -> Int)? = null

        fun trackEpochs(index: () -> Int) { currentEpoch = index }

        override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline,
            terminal: (TransportResult) -> Unit): CancellationHandle {
            check(request is ProviderHttpRequest.Get) { "Unexpected synthetic method" }
            gets.incrementAndGet()
            return when (request.url.encodedPath) {
                ReadOperation.USAGE.path -> usage(terminal)
                ReadOperation.RESET_INVENTORY.path -> {
                    val call = requireNotNull(pendingInventory)
                    check(call.replied.get() && !call.cancelled.get()) { "Inventory without a live usage response" }
                    check(call.inventory.compareAndSet(false, true)) { "Duplicate inventory in one cycle" }
                    terminal(response(INVENTORY)); CancellationHandle {}
                }
                else -> error("Unexpected synthetic path")
            }
        }

        private fun usage(terminal: (TransportResult) -> Unit): CancellationHandle {
            val call: Held
            val hold: Boolean
            synchronized(gate) {
                // Stamp the epoch at admission; never spend unused earlier START credits.
                val epoch = currentEpoch?.invoke()
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                call = Held(usageCalls.size, epoch, terminal) { active.decrementAndGet() }
                usageCalls += call
                pendingInventory = call
                hold = holdUsage
                if (hold) held += call
            }
            if (!hold) call.reply(response(USAGE))
            return CancellationHandle { call.cancel() }
        }

        fun liveUsage(): Held? = held.singleOrNull { it.live() }

        fun releaseUsage(selected: Held) {
            val live = synchronized(gate) {
                check(held.any { it === selected }) { "Release must carry a recorded held-call identity" }
                holdUsage = false
                val current = held.filter { it.live() }
                held.filter { predecessor -> current.none { it === predecessor } }.forEach {
                    assertTrue("predecessor must be cancelled: ${it.diagnostics()}", it.cancelled.get())
                }
                current
            }
            live.forEach { it.reply(response(USAGE)) }
        }
    }

    private class Held(val id: Int, val epoch: Int?, private val terminal: (TransportResult) -> Unit,
        private val release: () -> Unit) {
        val cancelled = AtomicBoolean()
        val replied = AtomicBoolean()
        val inventory = AtomicBoolean()
        private val released = AtomicBoolean()
        fun live(): Boolean = !cancelled.get() && !replied.get()
        fun cancel() { cancelled.set(true); releaseOnce() }
        fun reply(result: TransportResult) { replied.set(true); releaseOnce(); terminal(result) }
        private fun releaseOnce() { if (released.compareAndSet(false, true)) release() }
        fun diagnostics(): String = "#$id(epoch=$epoch, cancelled=${cancelled.get()}, replied=${replied.get()}, inventory=${inventory.get()})"
    }

    private companion object {
        const val USAGE = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12,"reset_after_seconds":500}}}"""
        const val INVENTORY = """{"available_count":0,"credits":[]}"""
        fun response(body: String, status: Int = 200) = TransportResult.Response.bounded(status, body.toByteArray())
    }
}
