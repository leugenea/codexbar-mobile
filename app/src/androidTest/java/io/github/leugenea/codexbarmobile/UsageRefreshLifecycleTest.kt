package io.github.leugenea.codexbarmobile

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
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
    }

    @After fun uninstall() {
        try {
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
            waitFor("initial successful cycle") { owner.state.value.phase == ConnectionPhase.OBSERVED }
            val success = owner.state.value.refresh.usage.success
            fake.holdUsage = true
            owner.readUsage()
            waitFor("held foreground usage reached transport") { fake.held.size == 1 }
            val old = fake.held.single()
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
            waitFor("original task resumes and admits one usage GET") {
                resumed(original!!) && fake.held.size == 2
            }
            scenario.onActivity { assertSame(original, it); assertSame(owner, it.connection) }
            assertEquals(1, factories)
            owner.readUsage(); owner.readUsage()
            settleCommands()
            assertEquals(4, fake.gets.get())
            fake.held.last().reply(response(USAGE))
            waitFor("bounded resumed cycle publishes both endpoints") {
                owner.state.value.phase == ConnectionPhase.OBSERVED && owner.state.value.refresh.usage.success !== success
            }
            val fresh = owner.state.value.refresh.usage.success
            old.reply(response("{}", 401))
            settleCommands()
            assertSame(fresh, owner.state.value.refresh.usage.success)
            assertEquals(5, fake.gets.get())
            assertEquals(1, fake.maximum.get())
        }
    }

    @Test fun activityRecreationKeepsOneOwnerAndCoalescesRefresh() {
        fake.holdUsage = true
        launchGate().use { scenario ->
            var original: MainActivity? = null
            scenario.onActivity { original = it }
            val shared = owner
            shared.readUsage()
            waitFor("pre-recreation usage is in flight") { fake.held.size == 1 }
            val old = fake.held.single()
            scenario.recreate()
            var recreated: MainActivity? = null
            scenario.onActivity { recreated = it; assertSame(shared, it.connection) }
            assertNotSame(original, recreated)
            assertEquals(Lifecycle.State.DESTROYED, original!!.lifecycle.currentState)
            waitFor("old transport cancelled and recreated Activity owns one resumed read") {
                old.cancelled.get() && resumed(recreated!!) && fake.held.size == 2
            }
            shared.readUsage(); shared.readUsage()
            settleCommands()
            assertEquals(2, fake.gets.get())
            assertEquals(1, factories)
            fake.held.last().reply(response(USAGE))
            waitFor("recreated cycle finishes with separate successful observations") {
                shared.state.value.phase == ConnectionPhase.OBSERVED && !shared.state.value.refresh.refreshing
            }
            assertNotNull(shared.state.value.refresh.usage.successfulAtMillis)
            assertNotNull(shared.state.value.refresh.inventory.successfulAtMillis)
            val fresh = shared.state.value.refresh.usage.success
            old.reply(response("{}", 401)); settleCommands()
            assertSame(fresh, shared.state.value.refresh.usage.success)
            assertEquals(3, fake.gets.get())
            assertEquals(1, fake.maximum.get())
        }
    }

    private fun launchGate(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        waitFor("restored session is dormant") { owner.state.value.phase == ConnectionPhase.RESTORED }
        assertEquals(0, fake.gets.get())
        compose.onNodeWithTag("connection-tab").performClick()
        compose.waitForIdle()
        settleCommands()
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
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: last state=${owner.state.value}", error) }
    }

    private fun settleCommands() {
        try { runBlocking { withTimeout(5_000) { owner.commandsSettled() } } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("B2 owner commands did not settle: ${owner.state.value}", error)
        }
    }

    private fun store() = KeystoreCredentialStore(context, NativeConnection.session(context))

    private class LifecycleTransport : AuthTransport {
        @Volatile var holdUsage = false
        val held = CopyOnWriteArrayList<Held>()
        val gets = AtomicInteger()
        private val active = AtomicInteger()
        val maximum = AtomicInteger()
        override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline,
            terminal: (TransportResult) -> Unit): CancellationHandle {
            check(request is ProviderHttpRequest.Get) { "Unexpected synthetic method" }
            gets.incrementAndGet()
            return when (request.url.encodedPath) {
                ReadOperation.USAGE.path -> if (holdUsage) {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    val call = Held(terminal) { active.decrementAndGet() }
                    held += call
                    CancellationHandle { call.cancel() }
                } else { terminal(response(USAGE)); CancellationHandle {} }
                ReadOperation.RESET_INVENTORY.path -> { terminal(response(INVENTORY)); CancellationHandle {} }
                else -> error("Unexpected synthetic path")
            }
        }
    }

    private class Held(private val terminal: (TransportResult) -> Unit, private val release: () -> Unit) {
        val cancelled = AtomicBoolean()
        private val released = AtomicBoolean()
        fun cancel() { cancelled.set(true); releaseOnce() }
        fun reply(result: TransportResult) { releaseOnce(); terminal(result) }
        private fun releaseOnce() { if (released.compareAndSet(false, true)) release() }
    }

    private companion object {
        const val USAGE = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12,"reset_after_seconds":500}}}"""
        const val INVENTORY = """{"available_count":0,"credits":[]}"""
        fun response(body: String, status: Int = 200) = TransportResult.Response.bounded(status, body.toByteArray())
    }
}
