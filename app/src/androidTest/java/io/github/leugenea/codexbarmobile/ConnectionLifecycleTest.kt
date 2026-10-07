package io.github.leugenea.codexbarmobile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.view.WindowManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Hosted only: production Activity/ViewModel + real A6, no sockets, no live sign-in. */
@RunWith(AndroidJUnit4::class)
class ConnectionLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fake = NativeFake()
    private var factories = 0
    private val defaultFactory = MainActivity.connectionFactory
    private val defaultLauncher = MainActivity.browserLauncher

    @Before fun setup() {
        cleanStore()
        MainActivity.connectionFactory = { app ->
            factories++
            NativeConnection.create(app, fake, fake.clock, fake::pause)
        }
    }

    @After fun cleanup() {
        MainActivity.connectionFactory = defaultFactory
        MainActivity.browserLauncher = defaultLauncher
        cleanStore()
    }

    @Test
    fun activityLaunchesOnlyFixedSystemBrowserIntentAndCancelClearsOwnedCode() {
        val intents = CopyOnWriteArrayList<Intent>()
        MainActivity.browserLauncher = { _, intent -> intents += Intent(intent) }
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            await(scenario, "code ready") { it.auth is AuthState.AwaitingUser }
            compose.onNodeWithTag("device-code").assertTextEquals(CODE)
            click("open-browser")
            assertEquals(1, intents.size)
            val intent = intents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://auth.openai.com/codex/device", intent.dataString)
            assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
            assertNull(intent.component)
            assertNull(intent.`package`)
            assertNull(intent.extras)
            assertNull(intent.data!!.query)
            // Exercise the actual default startActivity lambda too, but intercept before
            // the external Activity starts: no real browser, account or network in CI.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val boundary = object : android.app.Instrumentation.ActivityMonitor() {
                var launched: Intent? = null
                override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult {
                    launched = Intent(intent)
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(boundary)
            MainActivity.browserLauncher = defaultLauncher
            try {
                click("open-browser")
                assertEquals(Intent.ACTION_VIEW, boundary.launched!!.action)
                assertEquals(intent.data, boundary.launched!!.data)
                assertNull(boundary.launched!!.extras)
            } finally { instrumentation.removeMonitor(boundary) }
            assertSavedStateHasNoSecrets(scenario)
            click("cancel-connect")
            await(scenario, "cancel clears code") { it.phase == ConnectionPhase.CANCELLED }
            assertEquals(1, fake.calls.size)
            fake.poll.complete(Unit)
            assertSavedStateHasNoSecrets(scenario)
            compose.onNodeWithTag("device-code").assertDoesNotExist()
            MainActivity.browserLauncher = { _, intent -> intents += Intent(intent) }
            scenario.onActivity { it.openVerificationBrowser() }
            assertEquals(1, intents.size) // no stale code, no new browser launch after cancel.
        }
    }

    @Test
    fun recreationAndBackgroundKeepOneBoundedOwnerAndFinishCancelsPolling() {
        var original: ConnectionController? = null
        val scenario = launch()
        try {
            openGate(scenario)
            click("connect")
            await(scenario, "pending code") { it.auth is AuthState.AwaitingUser }
            scenario.onActivity { original = it.connection }
            assertSavedStateHasNoSecrets(scenario)
            scenario.recreate()
            await(scenario, "retained code") { it.auth is AuthState.AwaitingUser }
            scenario.onActivity { assertSame(original, it.connection) }
            assertEquals(1, factories)
            assertEquals(1, fake.calls.size)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await(scenario, "return from background") { it.auth is AuthState.AwaitingUser }
            assertEquals(1, fake.calls.size)
            assertSavedStateHasNoSecrets(scenario)
            scenario.close()
            assertEquals(ConnectionPhase.CANCELLED, original!!.state.value.phase)
            fake.poll.complete(Unit)
            assertNull(original.state.value.observations)
            assertEquals(1, fake.calls.size)
        } finally { scenario.close() }
        // A fresh Activity owner is a restart simulation, not an actual process-kill claim.
        launch().use { restarted ->
            await(restarted, "fresh owner drops pending login") { it.phase == ConnectionPhase.IDLE }
            assertEquals(2, factories)
            assertEquals(1, fake.calls.size)
        }
    }

    @Test
    fun fakeExchangeUsesRealKeystoreTwoReadsSeparateClocksAndLocalSignOut() {
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            await(scenario, "synthetic pending code") { it.auth is AuthState.AwaitingUser }
            fake.poll.complete(Unit)
            await(scenario, "two independent reads") { it.phase == ConnectionPhase.OBSERVED }
            var state: ConnectionState? = null
            scenario.onActivity { state = it.connection.state.value }
            val facts = state!!.observations!!
            assertTrue(facts.successful)
            assertTrue(facts.usage.observedAt!! < facts.inventory.observedAt!!)
            assertEquals(5, fake.calls.size)
            assertEquals(listOf(ReadOperation.USAGE.path, ReadOperation.RESET_INVENTORY.path),
                fake.calls.filter { it is ProviderHttpRequest.Get }.map { it.url.encodedPath })
            val session = NativeConnection.session(context)
            val store = KeystoreCredentialStore(context, session)
            val restored = store.read((state.auth as AuthState.Connected).generation) as CredentialResult.Success
            assertEquals(AccountWorkspaceBinding.Unresolved, restored.value.accountWorkspace)
            assertEquals(ACCESS, restored.value.accessToken.copyBytes().toString(Charsets.UTF_8))
            assertTrue(KeystoreCredentialStore.file(context, session).isFile)
            assertTrue(keyExists(session))
            assertSavedStateHasNoSecrets(scenario)
            compose.onNodeWithText("Weekly (604800 s): KNOWN").performScrollTo().assertExists()
            compose.onNodeWithText("Inventory banked available_count: 2").performScrollTo().assertExists()
            compose.onNodeWithText("Banked expiry: 2026-10-22T20:31:56.833553Z").performScrollTo().assertExists()
            scenario.recreate()
            await(scenario, "recreated observed state") { it.phase == ConnectionPhase.OBSERVED }
            assertEquals(5, fake.calls.size)
            click("sign-out")
            await(scenario, "local key and file deletion") { it.phase == ConnectionPhase.SIGNED_OUT }
            assertFalse(KeystoreCredentialStore.file(context, session).exists())
            assertFalse(keyExists(session))
            assertEquals(5, fake.calls.size)
        }
    }

    @Test
    fun freshOwnerRestoresOnlyUnresolvedCredentialsWithoutRequestsAndBrowserFailureIsSafe() {
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            await(scenario, "await browser") { it.auth is AuthState.AwaitingUser }
            MainActivity.browserLauncher = { _, _ -> throw ActivityNotFoundException("SYNTHETIC sensitive platform text") }
            click("open-browser")
            await(scenario, "browser failure") { it.problem == ConnectionProblem.BROWSER }
            assertSavedStateHasNoSecrets(scenario)
            click("connect")
            await(scenario, "second code") { it.auth is AuthState.AwaitingUser }
            MainActivity.browserLauncher = { _, _ -> throw SecurityException("SYNTHETIC sensitive platform text") }
            click("open-browser")
            await(scenario, "browser security failure") { it.problem == ConnectionProblem.BROWSER }
            assertSavedStateHasNoSecrets(scenario)
            click("connect")
            await(scenario, "third code") { it.auth is AuthState.AwaitingUser }
            fake.poll.complete(Unit)
            await(scenario, "stored synthetic session") { it.phase == ConnectionPhase.OBSERVED }
        }
        val requests = fake.calls.size
        launch().use { scenario ->
            openGate(scenario, ConnectionPhase.RESTORED)
            assertEquals(requests, fake.calls.size)
            assertSavedStateHasNoSecrets(scenario)
            click("sign-out")
            await(scenario, "restored local logout") { it.phase == ConnectionPhase.SIGNED_OUT }
        }
        val selector = File(context.noBackupFilesDir, "connection-session")
        val original = selector.readText()
        try {
            for (corrupt in listOf("synthetic-corrupt", "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")) {
                selector.writeText(corrupt)
                launch().use { scenario ->
                    openGate(scenario, ConnectionPhase.FAILED)
                    click("connect")
                    click("sign-out")
                    await(scenario, "corrupt local selector fails closed") { it.problem == ConnectionProblem.STORAGE }
                    assertEquals(requests, fake.calls.size)
                    assertSavedStateHasNoSecrets(scenario)
                }
            }
        } finally { selector.writeText(original) }
    }

    @Test
    fun failedReadRetainsUnresolvedSessionAndSignOutCancelsInFlightTransport() {
        fake.holdReads = true
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            await(scenario, "code before held read") { it.auth is AuthState.AwaitingUser }
            fake.poll.complete(Unit)
            bounded("first GET reached") { fake.reads.isNotEmpty() }
            click("sign-out")
            await(scenario, "logout cancels read") { it.phase == ConnectionPhase.SIGNED_OUT }
            assertTrue(fake.cancelledReads > 0)
            fake.reads.first().invoke(response(USAGE))
            assertSavedStateHasNoSecrets(scenario)
            assertEquals(4, fake.calls.size)
            fake.holdReads = false
            fake.readStatus = 403
            click("connect")
            await(scenario, "forbidden read NOT_GO") { it.phase == ConnectionPhase.OBSERVED }
            scenario.onActivity {
                assertEquals(ReadError.FORBIDDEN, it.connection.state.value.observations!!.usage.error)
                assertEquals(ConnectionProblem.READ, it.connection.state.value.problem)
            }
            assertTrue(KeystoreCredentialStore.file(context, NativeConnection.session(context)).exists())
            click("sign-out")
            await(scenario, "final local logout") { it.phase == ConnectionPhase.SIGNED_OUT }
        }
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

    private fun openGate(scenario: ActivityScenario<MainActivity>, phase: ConnectionPhase = ConnectionPhase.IDLE) {
        compose.waitForIdle()
        await(scenario, "initial $phase") { it.phase == phase }
        click("connection-tab")
    }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag != "connection-tab") node.performScrollTo()
        node.performClick()
    }
    private fun await(scenario: ActivityScenario<MainActivity>, step: String, test: (ConnectionState) -> Boolean) {
        var last = "no Activity"
        bounded(step, { last }) {
            var matches = false
            scenario.onActivity { last = it.connection.state.value.toString(); matches = test(it.connection.state.value) }
            matches
        }
    }
    private fun bounded(step: String, last: () -> String = { "no diagnostic" }, test: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 10_000, condition = test) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: timed out; last state=${last()}", error) }
    }

    private fun assertSavedStateHasNoSecrets(scenario: ActivityScenario<MainActivity>) {
        val bundle = Bundle()
        var diagnostics = ""
        scenario.onActivity {
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, bundle)
            diagnostics = it.connection.state.value.toString() + it.connection.state.value.auth.toString()
            assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        val parcel = Parcel.obtain()
        val bytes = try { parcel.writeBundle(bundle); parcel.marshall() } finally { parcel.recycle() }
        for (secret in listOf(CODE, ACCESS, REFRESH, "synthetic-device", "synthetic-authorization", "synthetic-verifier")) {
            assertFalse("Secret in diagnostic", diagnostics.contains(secret))
            assertFalse("Secret in saved Bundle UTF-8", bytes.toString(Charsets.UTF_8).contains(secret))
            assertFalse("Secret in saved Bundle UTF-16", bytes.toString(Charsets.UTF_16LE).contains(secret))
        }
    }

    private fun cleanStore() {
        val session = NativeConnection.session(context)
        val store = KeystoreCredentialStore(context, session)
        store.delete(store.openSession())
    }
    private fun keyExists(session: java.util.UUID): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .containsAlias(KeystoreCredentialStore.alias(context, session))

    private class NativeFake : AuthTransport {
        val calls = CopyOnWriteArrayList<ProviderHttpRequest>()
        val reads = CopyOnWriteArrayList<(TransportResult) -> Unit>()
        val poll = CompletableDeferred<Unit>()
        private val millis = AtomicLong()
        val clock = TransportClock { TransportTime(Instant.parse("2026-01-01T00:00:00Z").plusMillis(millis.get()), millis.get()) }
        @Volatile var holdReads = false
        @Volatile var readStatus = 200
        @Volatile var cancelledReads = 0
        suspend fun pause(duration: Long) { poll.await(); millis.addAndGet(duration) }
        override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit): CancellationHandle {
            calls += request
            if (request is ProviderHttpRequest.Get && holdReads) reads += terminal
            else {
                millis.incrementAndGet()
                val body = when (request.url.encodedPath) {
                    "/api/accounts/deviceauth/usercode" -> """{"device_auth_id":"synthetic-device","user_code":"$CODE"}"""
                    "/api/accounts/deviceauth/token" -> """{"authorization_code":"synthetic-authorization","code_verifier":"synthetic-verifier"}"""
                    "/oauth/token" -> """{"access_token":"$ACCESS","refresh_token":"$REFRESH"}"""
                    ReadOperation.USAGE.path -> USAGE
                    ReadOperation.RESET_INVENTORY.path -> INVENTORY
                    else -> error("Unexpected synthetic route")
                }
                terminal(response(body, if (request is ProviderHttpRequest.Get) readStatus else 200))
            }
            return CancellationHandle { if (request is ProviderHttpRequest.Get && holdReads) cancelledReads++ }
        }
    }

    private companion object {
        const val CODE = "SYNTHETIC-NATIVE-CODE"
        const val ACCESS = "synthetic-native-access"
        const val REFRESH = "synthetic-native-refresh"
        const val USAGE = """{"rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"limit_window_seconds":604800,"used_percent":5,"reset_at":1791756793},"secondary_window":{"limit_window_seconds":18000,"used_percent":12,"reset_after_seconds":500}},"rate_limit_reset_credits":{"available_count":2}}"""
        const val INVENTORY = """{"available_count":2,"credits":[{"expires_at":"2026-10-22T20:31:56.833553Z"},{"expires_at":null}]}"""
        fun response(body: String, status: Int = 200) = TransportResult.Response.bounded(status, body.toByteArray())
    }
}
