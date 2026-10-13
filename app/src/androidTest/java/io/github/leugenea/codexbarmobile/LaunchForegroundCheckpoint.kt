package io.github.leugenea.codexbarmobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext

/** Launch-only provenance; the existing lifecycle suite's private epoch ledger stays unchanged. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class LaunchForegroundCheckpoint : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val gate = Any()
    private val caller = ThreadLocal<Caller?>()
    private val epochs = mutableListOf<Epoch>()
    private val submissions = IdentityHashMap<Any, Int>()
    private val submittedObservers = IdentityHashMap<Any, Any>()
    private val registrations = mutableListOf<Pair<MainActivity, LifecycleEventObserver>>()
    private lateinit var transport: NavigationTransport
    private var installed = false
    private var completedEpoch: Int? = null
    @Volatile var ownerEpoch: Int? = null
        private set
    val context: CoroutineContext = Origin()
    val dispatcher: CoroutineDispatcher = OwnerDispatcher()

    fun install(value: NavigationTransport) {
        transport = value
        instrumentation.runOnMainSync {
            synchronized(gate) { installed = true }
            application.registerActivityLifecycleCallbacks(this)
        }
    }

    fun isCurrent(epoch: Int?): Boolean = synchronized(gate) {
        epoch != null && epoch == ownerEpoch && epochs.lastOrNull()?.let {
            it.index == epoch && !it.stopped && it.starts == 1 && it.stops == 0
        } == true
    }

    /** Count recorded initial operations plus the selected epoch directly; never subtract totals. */
    fun assertCampaignCounts(epoch: Int, requests: Long, gets: Long) {
        val campaign = transport.calls.filter { it.epoch == null || it.epoch == epoch }
        assertEquals("Initial + selected epoch $epoch requests: ${diagnostics()}", requests, campaign.size.toLong())
        assertEquals("Initial + selected epoch $epoch GETs: ${diagnostics()}", gets,
            campaign.count { it.path in ReadOperation.entries.map(ReadOperation::path) }.toLong())
    }

    /** A boolean entry receipt, recorded before transport counters or call lists are mutated. */
    fun admitted(epoch: Int?) {
        if (epoch == null) return
        synchronized(gate) {
            val previous = epochs[epoch - 1]
            epochs[epoch - 1] = previous.copy(admitted = true)
        }
    }

    fun completed(epoch: Int) = synchronized(gate) {
        check(completedEpoch == null) { "Only one return checkpoint may complete" }
        completedEpoch = epoch
    }

    /** Each real return has its own independent zero/one usage budget; unused epochs hide no duplicates. */
    fun assertStoppedEpochs() {
        val ledger = synchronized(gate) {
            assertTrue("Every ingress submits true then false: ${diagnostics()}", submissions.values.all { it == 2 })
            epochs.toList()
        }
        assertTrue("Default relaunch must create a real START epoch", ledger.isNotEmpty())
        assertNotNull("One fenced return must complete", completedEpoch)
        var expectedRequests = 5L
        var expectedGets = 2L
        for (epoch in ledger) {
            assertTrue("Return ${epoch.index} must STOP: ${diagnostics()}", epoch.stopped)
            assertTrue("Return ${epoch.index} has at most one owner START", epoch.starts in 0..1)
            assertEquals("Owner START/STOP pair for return ${epoch.index}", epoch.starts, epoch.stops)
            val skipped = epoch.starts == 0 || requireNotNull(epoch.stoppedBeforeAdmission)
            val usageBudget = if (skipped) 0 else 1
            val inventoryBudget = if (epoch.index == completedEpoch) 1 else 0
            val calls = transport.calls.filter { it.epoch == epoch.index }
            assertEquals("Exact usage budget for return ${epoch.index}: ${diagnostics()}", usageBudget,
                calls.count { it.path == ReadOperation.USAGE.path })
            assertEquals("Only the released checkpoint reaches inventory", inventoryBudget,
                calls.count { it.path == ReadOperation.RESET_INVENTORY.path })
            assertEquals("No unregistered route in return ${epoch.index}", usageBudget + inventoryBudget, calls.size)
            if (inventoryBudget == 0) calls.forEach { assertTrue("Unreleased return is cancelled", it.cancelled) }
            expectedRequests += usageBudget + inventoryBudget
            expectedGets += usageBudget + inventoryBudget
        }
        assertEquals("Initial campaign remains exact", 5, transport.calls.count { it.epoch == null })
        assertEquals("Every operation belongs to initial or recorded owner epoch", transport.calls.size,
            transport.calls.count { it.epoch == null } + ledger.sumOf { epoch -> transport.calls.count { it.epoch == epoch.index } })
        assertEquals("Exact global requests across independently checked epochs", expectedRequests, transport.requests.get())
        assertEquals("Exact global GETs across independently checked epochs", expectedGets, transport.gets.get())
    }

    /** Safe inside a Main lifecycle fence: the action suspends on IO, never Compose or a Main spin. */
    fun awaitOwner(step: String, owner: ConnectionController, action: suspend () -> Unit) {
        try { runBlocking(Dispatchers.IO) { withTimeout(5_000) { action() } } }
        catch (error: TimeoutCancellationException) {
            throw AssertionError("$step: last state=${owner.state.value}; ${diagnostics()}", error)
        }
    }

    fun <T> withoutForegroundOrigin(action: () -> T): T {
        val previous = caller.get()
        caller.remove()
        try { return action() } finally { caller.set(previous) }
    }

    fun diagnostics(): String = synchronized(gate) {
        "epochs=$epochs, ownerEpoch=$ownerEpoch, requests=${transport.requests.get()}, gets=${transport.gets.get()}, " +
            "calls=${transport.calls.map { "${it.path}(epoch=${it.epoch}, cancelled=${it.cancelled}, live=${it.live()})" }}"
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        val observer = LifecycleEventObserver { _, event -> synchronized(gate) {
            when (event) {
                Lifecycle.Event.ON_START -> epochs += Epoch(epochs.size + 1)
                Lifecycle.Event.ON_STOP -> epochs.lastOrNull()?.let { epochs[epochs.lastIndex] = it.copy(stopped = true) }
                else -> Unit
            }
        } }
        registrations += activity to observer
        activity.lifecycle.addObserver(observer)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    override fun close() {
        instrumentation.runOnMainSync {
            application.unregisterActivityLifecycleCallbacks(this)
            registrations.forEach { (activity, observer) -> activity.lifecycle.removeObserver(observer) }
            synchronized(gate) { installed = false }
        }
    }

    private data class Epoch(val index: Int, val stopped: Boolean = false, val starts: Int = 0,
        val stops: Int = 0, val admitted: Boolean = false, val stoppedBeforeAdmission: Boolean? = null)
    private data class Caller(val epoch: Int?, val origin: Any)
    private data class Command(val epoch: Int, val foreground: Boolean)
    private val originKey = object : CoroutineContext.Key<Origin> {}

    /** Capture START when repeatOnLifecycle creates its child, not at later transport execution. */
    private inner class Origin(private val epoch: Int? = null) : CopyableThreadContextElement<Caller?> {
        override val key get() = originKey
        override fun copyForChild(): Origin = Origin(synchronized(gate) { epochs.lastOrNull()?.index })
        override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext = overwritingElement
        override fun updateThreadContext(context: CoroutineContext): Caller? = caller.get().also { caller.set(Caller(epoch, this)) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Caller?) { caller.set(oldState) }
    }

    /** The production serial IO ordering, with provenance around actual observer commands only. */
    private inner class OwnerDispatcher : CoroutineDispatcher() {
        private val serial = Dispatchers.IO.limitedParallelism(1)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val command = synchronized(gate) {
                val ticket = context[UsageForegroundCommand]
                caller.get()?.takeIf { ticket != null && installed && it.epoch != null }?.let {
                    val typed = requireNotNull(ticket)
                    val count = (submissions[it.origin] ?: 0) + 1
                    assertTrue("Foreground ingress has only true then false", count in 1..2)
                    assertEquals("Actual foreground boolean matches its ingress sequence", count == 1, typed.foreground)
                    if (count == 1) submittedObservers[it.origin] = typed.observer
                    else assertSame("START/STOP use the exact observer", submittedObservers[it.origin], typed.observer)
                    submissions[it.origin] = count
                    Command(requireNotNull(it.epoch), typed.foreground)
                }
            }
            serial.dispatch(context, Runnable {
                if (command?.foreground == true) synchronized(gate) {
                    ownerEpoch = command.epoch
                    val previous = epochs[command.epoch - 1]
                    epochs[command.epoch - 1] = previous.copy(starts = previous.starts + 1)
                }
                block.run()
                if (command?.foreground == false) synchronized(gate) {
                    val previous = epochs[command.epoch - 1]
                    epochs[command.epoch - 1] = previous.copy(stops = previous.stops + 1,
                        stoppedBeforeAdmission = !previous.admitted)
                    ownerEpoch = null
                }
            })
        }
    }
}
