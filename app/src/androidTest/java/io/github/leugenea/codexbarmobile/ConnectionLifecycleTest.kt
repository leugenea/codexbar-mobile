package io.github.leugenea.codexbarmobile

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Parcel
import android.view.WindowManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import io.github.leugenea.codexbarmobile.usage.SelectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Hosted only: production Activity/process owner + real A6, no sockets, no live sign-in. */
@RunWith(AndroidJUnit4::class)
class ConnectionLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fake = NativeFake()
    private var factories = 0
    private val defaultFactory = NativeConnection.factory
    private val defaultLauncher = MainActivity.browserLauncher

    @Before fun setup() {
        NativeConnection.factory = { app ->
            factories++
            NativeConnection.create(app, fake, fake.clock, fake::pause)
        }
        NativeConnection.resetForTests()
        val installed = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, installed.launchMode)
        assertEquals("Launcher uses the default app process", context.packageName, installed.processName)
        cleanStore()
    }

    @After fun cleanup() {
        NativeConnection.resetForTests()
        NativeConnection.factory = defaultFactory
        MainActivity.browserLauncher = defaultLauncher
        cleanStore()
    }

    private fun assertResetRequiresInstalledTestFactoryAndPreservesOwner() {
        val installedFactory = NativeConnection.factory
        val owner = NativeConnection.get(context)
        NativeConnection.factory = defaultFactory
        try {
            assertThrows(IllegalStateException::class.java) { NativeConnection.resetForTests() }
            assertSame(owner, NativeConnection.get(context))
            assertEquals(1, factories)
        } finally { NativeConnection.factory = installedFactory }
    }

    @Test
    fun activityLaunchesOnlyFixedSystemBrowserIntentAndCancelClearsOwnedCode() {
        assertResetRequiresInstalledTestFactoryAndPreservesOwner()
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
            assertTrue(original!!.state.value.auth is AuthState.AwaitingUser)
            assertSame(original, NativeConnection.get(context))
            assertNull(original!!.state.value.observations)
            assertEquals(1, fake.calls.size)
        } finally { scenario.close() }
        launch().use { resumed ->
            await(resumed, "finish preserves pending process login") { it.auth is AuthState.AwaitingUser }
            resumed.onActivity { assertSame(original, it.connection) }
            assertEquals(1, factories)
            assertEquals(1, fake.calls.size)
        }
        // Keep the mandatory historical test name. Explicit runtime reset, not
        // Activity finish, cancels polling and models loss of process memory.
        NativeConnection.resetForTests()
        bounded("runtime reset cancels polling", { original!!.state.value.toString() }) {
            original!!.state.value.phase == ConnectionPhase.CANCELLED
        }
        fake.poll.complete(Unit)
        assertNull(original!!.state.value.observations)
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
            val restored = persistedEnvelope(session)
            assertEquals(AccountWorkspaceBinding.Unresolved, restored.accountWorkspace)
            assertEquals(ACCESS, restored.accessToken.copyBytes().toString(Charsets.UTF_8))
            assertTrue(KeystoreCredentialStore.file(context, session).isFile)
            assertTrue(keyExists(session))
            assertSavedStateHasNoSecrets(scenario)
            assertPresentedObservations(facts)
            val beforeRecreation = fake.calls.size
            scenario.recreate()
            bounded("recreated live gate admits one resumed cycle") { fake.calls.size == beforeRecreation + 2 }
            await(scenario, "recreated observed state") { it.phase == ConnectionPhase.OBSERVED }
            assertEquals(beforeRecreation + 2, fake.calls.size)
            click("remove-account"); click("remove-account-confirm")
            await(scenario, "local key and file deletion") { it.phase == ConnectionPhase.SIGNED_OUT }
            assertFalse(KeystoreCredentialStore.file(context, session).exists())
            assertFalse(keyExists(session))
            assertEquals(beforeRecreation + 2, fake.calls.size)
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
        NativeConnection.resetForTests() // Fresh runtime, not an Activity-scoped owner.
        val requests = fake.calls.size
        launch().use { scenario ->
            openGate(scenario, ConnectionPhase.RESTORED)
            assertEquals(requests, fake.calls.size)
            assertSavedStateHasNoSecrets(scenario)
            click("remove-account"); click("remove-account-confirm")
            await(scenario, "restored local logout") { it.phase == ConnectionPhase.SIGNED_OUT }
        }
        val selector = File(context.noBackupFilesDir, "connection-session")
        val original = selector.readText()
        try {
            for (corrupt in listOf("synthetic-corrupt", "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")) {
                NativeConnection.resetForTests()
                selector.writeText(corrupt)
                launch().use { scenario ->
                    openGate(scenario, ConnectionPhase.FAILED)
                    click("connect", expectTransition = false)
                    click("remove-account"); click("remove-account-confirm")
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
            click("remove-account"); click("remove-account-confirm")
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
            click("remove-account"); click("remove-account-confirm")
            await(scenario, "final local logout") { it.phase == ConnectionPhase.SIGNED_OUT }
        }
    }

    @Test
    fun restoredSessionRotationReauthAndLogoutUseRealKeystore() {
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            await(scenario, "initial device code") { it.auth is AuthState.AwaitingUser }
            fake.poll.complete(Unit)
            await(scenario, "initial stored session") { it.phase == ConnectionPhase.OBSERVED }
        }
        NativeConnection.resetForTests()
        val requests = fake.calls.size
        launch().use { scenario ->
            openGate(scenario, ConnectionPhase.RESTORED)
            assertEquals(requests, fake.calls.size)
            fake.rejectOriginalBearer = true
            click("read-usage")
            await(scenario, "401 rotation and bounded retry") { it.phase == ConnectionPhase.OBSERVED }
            val slot = NativeConnection.session(context)
            val saved = persistedEnvelope(slot)
            scenario.onActivity {
                assertEquals(ROTATED_ACCESS, saved.accessToken.copyBytes().toString(Charsets.UTF_8))
                assertEquals(ROTATED_REFRESH, saved.refreshToken!!.copyBytes().toString(Charsets.UTF_8))
                assertTrue(it.connection.state.value.observations!!.successful)
            }
            assertEquals(1, fake.refreshCount)
            assertFalse(File(KeystoreCredentialStore.file(context, slot).parentFile, "rotation-pending").exists())
            val beforeRecreation = fake.calls.size
            scenario.recreate()
            bounded("rotated session resumes one read cycle") { fake.calls.size == beforeRecreation + 2 }
            await(scenario, "rotation survives Activity recreation") { it.phase == ConnectionPhase.OBSERVED }
            assertEquals(beforeRecreation + 2, fake.calls.size)
            fake.refreshStatus = 401
            click("refresh-session")
            await(scenario, "terminal refresh requires reauth") { it.phase == ConnectionPhase.REAUTH_REQUIRED }
            assertFalse(keyExists(slot))
            assertFalse(KeystoreCredentialStore.file(context, slot).exists())
            compose.onNodeWithTag("connect").performScrollTo().assertTextEquals("Sign in again")
            assertSavedStateHasNoSecrets(scenario)
            fake.refreshStatus = 200
            fake.rejectOriginalBearer = false
            click("connect")
            await(scenario, "reauth replaces quarantined session") { it.phase == ConnectionPhase.OBSERVED }
            click("remove-account"); click("remove-account-confirm")
            await(scenario, "reauthenticated local deletion") { it.phase == ConnectionPhase.SIGNED_OUT }
            assertFalse(keyExists(slot))
            assertFalse(KeystoreCredentialStore.file(context, slot).exists())
        }
        NativeConnection.resetForTests()
        launch().use { scenario -> openGate(scenario); assertSavedStateHasNoSecrets(scenario) }
    }

    @Test
    fun nativeLogoutDuringRefreshAndReplacementRejectLateRotatedCredentials() {
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            await(scenario, "code before refresh race") { it.auth is AuthState.AwaitingUser }
            fake.poll.complete(Unit)
            await(scenario, "session before refresh race") { it.phase == ConnectionPhase.OBSERVED }
            fake.holdRefresh = true
            click("refresh-session")
            bounded("refresh reached transport") { fake.refreshes.isNotEmpty() }
            val late = fake.refreshes.single()
            click("remove-account"); click("remove-account-confirm")
            await(scenario, "logout cancels refresh and deletes credentials") { it.phase == ConnectionPhase.SIGNED_OUT }
            assertEquals(1, fake.cancelledRefreshes)
            val slot = NativeConnection.session(context)
            assertFalse(keyExists(slot))
            assertFalse(KeystoreCredentialStore.file(context, slot).exists())
            fake.holdRefresh = false
            click("connect")
            await(scenario, "replacement session stored") { it.phase == ConnectionPhase.OBSERVED }
            late(response("""{"access_token":"$ROTATED_ACCESS","refresh_token":"$ROTATED_REFRESH"}"""))
            val saved = persistedEnvelope(slot)
            scenario.onActivity {
                assertEquals(ACCESS, saved.accessToken.copyBytes().toString(Charsets.UTF_8))
                assertEquals(REFRESH, saved.refreshToken!!.copyBytes().toString(Charsets.UTF_8))
            }
            assertSavedStateHasNoSecrets(scenario)
            click("connect") // replacement also deletes the previous durable pair before auth.
            settleOwnerCommands("explicit account replacement connect command processed")
            await(scenario, "explicit account replacement") { it.phase == ConnectionPhase.OBSERVED }
            click("remove-account"); click("remove-account-confirm")
            await(scenario, "replacement local deletion") { it.phase == ConnectionPhase.SIGNED_OUT }
        }
    }

    @Test
    fun nativeFailedRotationWriteQuarantinesRealStoreBeforeReauth() {
        val failWrite = java.util.concurrent.atomic.AtomicBoolean()
        NativeConnection.factory = { app ->
            val actual = KeystoreCredentialStore(app, NativeConnection.session(app))
            val store = object : CredentialStore by actual {
                override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> =
                    if (failWrite.get()) CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
                    else actual.replace(envelope, cancellation)
            }
            ConnectionController(store, io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator(fake, store, fake.clock, fake::pause),
                NativeFeasibilityReader(fake, fake.clock, fake::pause),
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO))
        }
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            await(scenario, "code before write failure") { it.auth is AuthState.AwaitingUser }
            fake.poll.complete(Unit)
            await(scenario, "durable session before write failure") { it.phase == ConnectionPhase.OBSERVED }
            failWrite.set(true)
            click("refresh-session")
            await(scenario, "rotated write failure quarantined") { it.phase == ConnectionPhase.REAUTH_REQUIRED }
            val slot = NativeConnection.session(context)
            assertFalse(keyExists(slot))
            assertFalse(KeystoreCredentialStore.file(context, slot).exists())
            assertEquals(1, fake.refreshCount)
            scenario.onActivity { assertNull(it.connection.state.value.observations) }
            compose.onNodeWithTag("read-usage").performScrollTo().assertIsNotEnabled()
            assertEquals(1, fake.refreshCount)
            assertSavedStateHasNoSecrets(scenario)
        }
        NativeConnection.resetForTests()
        launch().use { scenario -> openGate(scenario); assertEquals(1, fake.refreshCount) }
    }

    @Test
    fun nativeKeyLossCorruptionAndInterruptedRotationRestoreFailClosedToReauth() {
        val slot = NativeConnection.session(context)
        val store = KeystoreCredentialStore(context, slot)
        fun install(): SessionGeneration {
            val generation = store.openSession()
            assertTrue(store.replace(CredentialEnvelope(generation, SensitiveValue.copyOf(ACCESS.toByteArray()),
                SensitiveValue.copyOf(REFRESH.toByteArray())), CredentialCancellation()) is CredentialResult.Success)
            return generation
        }
        for (mutation in listOf("pending", "key", "ciphertext")) {
            NativeConnection.resetForTests()
            cleanStore()
            val generation = install()
            when (mutation) {
                "pending" -> assertTrue(store.beginRotation(generation) is CredentialResult.Success)
                "key" -> KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreCredentialStore.alias(context, slot))
                else -> KeystoreCredentialStore.file(context, slot).writeBytes(byteArrayOf(1, 2, 3))
            }
            val requests = fake.calls.size
            launch().use { scenario ->
                openGate(scenario, ConnectionPhase.REAUTH_REQUIRED)
                assertEquals(requests, fake.calls.size)
                scenario.onActivity { assertNull(it.connection.state.value.observations) }
                click("connect")
                await(scenario, "$mutation recovery code") { it.auth is AuthState.AwaitingUser || it.phase == ConnectionPhase.OBSERVED }
                fake.poll.complete(Unit)
                await(scenario, "$mutation explicit reauth") { it.phase == ConnectionPhase.OBSERVED }
                click("remove-account"); click("remove-account-confirm")
                await(scenario, "$mutation local deletion") { it.phase == ConnectionPhase.SIGNED_OUT }
                assertFalse(KeystoreCredentialStore.file(context, slot).exists())
                assertFalse(keyExists(slot))
            }
        }
        // Explicit holder resets model storage restoration, not actual process death/reboot.
    }

    @Test
    fun nativeTwoLiveOwnersLogoutWhileUsageIsHeldRejectsOldGeneration() {
        twoLiveOwners(replace = false)
    }

    @Test
    fun nativeTwoLiveOwnersReplacementWhileUsageIsHeldRejectsOldGeneration() {
        twoLiveOwners(replace = true)
    }

    private fun twoLiveOwners(replace: Boolean) {
        // Historical mandatory names retained: two callers now share the process
        // controller, never construct competing owners for the same durable slot.
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            await(scenario, "shared-owner initial code") { it.auth is AuthState.AwaitingUser }
            fake.poll.complete(Unit)
            await(scenario, "shared-owner initial session") { it.phase == ConnectionPhase.OBSERVED }
            val shared = NativeConnection.get(context)
            scenario.onActivity { assertSame(shared, it.connection) }
            assertSame(shared, NativeConnection.get(context.applicationContext))
            assertEquals(1, factories)
            fake.holdReads = true
            click("read-usage")
            bounded("shared-owner held usage GET") { fake.reads.size == 1 }
            val held = fake.reads.single()
            fake.holdReads = false
            shared.signOut()
            await(scenario, "shared-owner logout retires held usage") { it.phase == ConnectionPhase.SIGNED_OUT }
            if (replace) shared.connect()
            val phase = if (replace) ConnectionPhase.OBSERVED else ConnectionPhase.SIGNED_OUT
            await(scenario, "shared-owner $phase") { it.phase == phase }
            assertEquals("Retired usage transport cancelled", 1, fake.cancelledReads)
            // Local B2 ticks legitimately replace ConnectionState while the gate is visible.
            // Pause only that scheduler before the strict retired-generation identity oracle.
            click("offline-tab")
            compose.waitForIdle()
            settleOwnerCommands()
            await(scenario, "shared-owner paused $phase") { it.phase == phase }
            val previousAttempt = shared.state.value.refresh.usage.attempt
            assertRetiredReadCannotAffectSharedOwner(scenario, shared, held, replace)
            click("connection-tab")
            if (replace) await(scenario, "shared-owner foreground refresh settled") {
                it.phase == ConnectionPhase.OBSERVED && it.refresh.usage.attempt !== previousAttempt
            }
            assertSavedStateHasNoSecrets(scenario)
        }
    }

    private fun assertRetiredReadCannotAffectSharedOwner(
        scenario: ActivityScenario<MainActivity>, shared: ConnectionController,
        held: (TransportResult) -> Unit, replace: Boolean,
    ) {
        val slot = NativeConnection.session(context)
        val file = KeystoreCredentialStore.file(context, slot)
        val bytes = if (replace) file.readBytes() else null
        val requests = fake.calls.size
        val current = shared.state.value
        held(response(USAGE))
        settleOwnerCommands()
        assertSame("No stale observations after replacement/logout", current, shared.state.value)
        assertEquals("Old generation must not admit inventory", requests, fake.calls.size)
        if (bytes == null) {
            scenario.onActivity {
                assertNull(it.connection.state.value.observations)
                it.connection.readUsage()
                it.connection.readUsage(refreshSession = true)
                assertTrue(it.connection.session.snapshot() is SessionResult.Failed)
            }
            settleOwnerCommands()
            assertEquals("No new old-generation GET or refresh", requests, fake.calls.size)
            assertFalse(keyExists(slot))
            assertFalse(file.exists())
        } else {
            assertArrayEquals("No stale write after replacement", bytes, file.readBytes())
            assertEquals(ACCESS, persistedEnvelope(slot).accessToken.copyBytes().toString(Charsets.UTF_8))
            assertTrue(shared.session.snapshot() is SessionResult.Ready)
        }
    }

    @Test
    fun explicitDeviceCodeCopyWritesExactSensitiveClipAndAnnouncesSuccess() {
        launch().use { scenario ->
            openGate(scenario)
            awaitWindowFocus(scenario)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            scenario.onActivity { clipboard.setPrimaryClip(ClipData.newPlainText("Synthetic sentinel", "SYNTHETIC-UNCHANGED")) }
            click("connect")
            settleCopyCommands("initial copy login receipt")
            await(scenario, "copy code appears") { it.auth is AuthState.AwaitingUser }
            assertEquals("No automatic clipboard write", "SYNTHETIC-UNCHANGED", primaryClip(scenario).getItemAt(0).text.toString())
            compose.onNodeWithTag("copy-device-code").performScrollTo().assertTextEquals("Copy code").assertHasClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            click("copy-device-code")
            bounded("accessible copy confirmation", { NativeConnection.get(context).state.value.toString() }) {
                compose.onAllNodes(hasTestTag("device-code-copied")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("device-code-copied").assertTextEquals("Code copied")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            assertSensitiveCode(primaryClip(scenario), CODE)
            val firstConfirmationId = compose.onNodeWithTag("device-code-copied").fetchSemanticsNode().id
            click("copy-device-code")
            bounded("repeated copy creates fresh accessible confirmation", { NativeConnection.get(context).state.value.toString() }) {
                compose.onAllNodes(hasTestTag("device-code-copied")).fetchSemanticsNodes().any { it.id != firstConfirmationId }
            }
            compose.onNodeWithTag("device-code-copied").assertTextEquals("Code copied")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            assertSensitiveCode(primaryClip(scenario), CODE)
            val code = NativeConnection.get(context).state.value.auth as AuthState.AwaitingUser
            // The compatibility key marks sensitivity identically on every supported API.
            assertSensitiveCode(sensitiveDeviceCodeClip(code.userCode, "Synthetic label"), CODE)
            val adapterWrites = CopyOnWriteArrayList<ClipData>()
            assertTrue(AndroidDeviceCodeClipboard("Synthetic label", adapterWrites::add).copy(code.userCode))
            assertSensitiveCode(adapterWrites.single(), CODE)
            assertFalse(AndroidDeviceCodeClipboard("Synthetic label") { throw SecurityException("Synthetic platform failure") }.copy(code.userCode))
            assertSavedStateHasNoSecrets(scenario)
            scenario.recreate()
            await(scenario, "copy attempt retained after recreation") { it.auth === code }
            compose.onNodeWithTag("device-code-copied").assertDoesNotExist()
            assertSensitiveCode(primaryClip(scenario), CODE)
        }
    }

    @Test
    fun capturedDeviceCodeCopyRejectsCancelledCompletedAndSupersededAttempts() {
        val writes = CopyOnWriteArrayList<ClipData>()
        val clipboard = DeviceCodeClipboard { code -> writes += sensitiveDeviceCodeClip(code, "Synthetic label"); true }
        launch().use { scenario ->
            openGate(scenario)
            click("connect")
            settleCopyCommands("predecessor login receipt")
            await(scenario, "predecessor copy ready") { it.auth is AuthState.AwaitingUser }
            val owner = NativeConnection.get(context)
            val predecessor = owner.state.value.auth as AuthState.AwaitingUser
            assertTrue(writes.isEmpty())
            val stale: suspend () -> Boolean = { owner.copyDeviceCode(predecessor, clipboard) }
            assertTrue(copyResult("copy current predecessor", stale))
            assertSensitiveCode(writes.single(), CODE)
            owner.cancel()
            settleCopyCommands("copy cancellation receipt")
            await(scenario, "cancel removes copy control") { it.phase == ConnectionPhase.CANCELLED }
            compose.onNodeWithTag("copy-device-code").assertDoesNotExist()
            assertFalse(copyResult("cancel rejects stale copy", stale))
            fake.userCode = SECOND_CODE
            owner.connect()
            settleCopyCommands("successor login receipt")
            await(scenario, "successor copy ready") { it.auth is AuthState.AwaitingUser && it.auth !== predecessor }
            val successor = owner.state.value.auth as AuthState.AwaitingUser
            assertFalse(copyResult("successor rejects predecessor copy", stale))
            assertEquals(1, writes.size)
            assertTrue(copyResult("copy exact successor") { owner.copyDeviceCode(successor, clipboard) })
            assertSensitiveCode(writes.last(), SECOND_CODE)
            fake.poll.complete(Unit)
            await(scenario, "completed login removes copy control") { it.phase == ConnectionPhase.OBSERVED }
            compose.onNodeWithTag("copy-device-code").assertDoesNotExist()
            assertFalse(copyResult("completion rejects captured successor") { owner.copyDeviceCode(successor, clipboard) })
            assertEquals(2, writes.size)
        }
    }

    @Test
    fun capturedDeviceCodeCopyRejectsMissingFailedAndExpiredAttempts() {
        val writes = CopyOnWriteArrayList<SensitiveValue>()
        val clipboard = DeviceCodeClipboard { writes += it; true }
        launch().use { scenario ->
            openGate(scenario)
            val owner = NativeConnection.get(context)
            val missing = AuthState.AwaitingUser(SensitiveValue.copyOf(CODE.toByteArray()))
            assertFalse(copyResult("missing code rejects copy") { owner.copyDeviceCode(missing, clipboard) })
            click("connect")
            settleCopyCommands("failure login receipt")
            await(scenario, "capture failed attempt copy") { it.auth is AuthState.AwaitingUser }
            val failed = owner.state.value.auth as AuthState.AwaitingUser
            owner.browserFailed()
            settleCopyCommands("browser failure receipt")
            await(scenario, "failed attempt removes copy control") { it.phase == ConnectionPhase.FAILED }
            assertFalse(copyResult("failed attempt rejects copy") { owner.copyDeviceCode(failed, clipboard) })
            owner.connect()
            settleCopyCommands("expiring login receipt")
            await(scenario, "capture expiring copy") { it.auth is AuthState.AwaitingUser && it.auth !== failed }
            val expired = owner.state.value.auth as AuthState.AwaitingUser
            fake.expirePendingCode()
            assertSame("Projection still awaits the suspended poll", expired, owner.state.value.auth)
            assertFalse(copyResult("elapsed deadline rejects copy before poll resumes") { owner.copyDeviceCode(expired, clipboard) })
            fake.poll.complete(Unit)
            await(scenario, "deadline removes copy control") { it.auth is AuthState.Failed }
            assertEquals(io.github.leugenea.codexbarmobile.auth.AuthFailure.DEADLINE, (owner.state.value.auth as AuthState.Failed).category)
            compose.onNodeWithTag("copy-device-code").assertDoesNotExist()
            assertFalse(copyResult("expired attempt rejects copy") { owner.copyDeviceCode(expired, clipboard) })
            assertTrue(writes.isEmpty())
        }
    }

    private fun copyResult(step: String, action: suspend () -> Boolean): Boolean = ownerResult(step, action)

    private fun settleCopyCommands(step: String) = ownerResult(step) { NativeConnection.get(context).commandsSettled() }

    private fun <T> ownerResult(step: String, action: suspend () -> T): T {
        val result = CoroutineScope(Dispatchers.IO).async { action() }
        try {
            bounded(step, { "completed=${result.isCompleted}; ${NativeConnection.get(context).state.value}" }) { result.isCompleted }
            return runBlocking { result.await() }
        } finally { result.cancel() }
    }

    private fun awaitWindowFocus(scenario: ActivityScenario<MainActivity>) {
        var focused = false
        bounded("foreground clipboard window focus", { "focused=$focused" }) {
            scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
            focused
        }
    }

    private fun primaryClip(scenario: ActivityScenario<MainActivity>): ClipData {
        awaitWindowFocus(scenario)
        var clip: ClipData? = null
        scenario.onActivity { clip = it.getSystemService(ClipboardManager::class.java).primaryClip }
        return requireNotNull(clip) { "Synthetic primary clip missing while app focused" }
    }

    private fun assertSensitiveCode(clip: ClipData, expected: String) {
        assertEquals(1, clip.itemCount)
        assertEquals(expected, clip.getItemAt(0).text.toString())
        assertNull(clip.getItemAt(0).intent)
        assertNull(clip.getItemAt(0).uri)
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

    private fun assertPresentedObservations(facts: FeasibilityObservations) {
        assertEquals(SelectionState.KNOWN, facts.usage.usage!!.weekly.state)
        compose.onNodeWithTag("live-weekly-status").performScrollTo().assertTextEquals("Provider-reported usage")
        compose.onNodeWithTag("live-weekly-percent").performScrollTo().assertTextEquals("5% used")
        compose.onNodeWithTag("banked-inventory-text", useUnmergedTree = true).performScrollTo()
            .assertTextEquals("Inventory reports: 2 available banked resets")
        assertEquals(Instant.parse("2026-10-22T20:31:56.833553Z"), facts.inventory.inventory!!.items.first().value!!.expiresAt.value)
        // The exact source instant stays an owner oracle; the UI intentionally shows B1 hour precision.
        compose.onNodeWithTag("banked-item-0-absolute", useUnmergedTree = true).performScrollTo().assertExists()
        assertConnectionHasNoDiagnosticDump(compose)
    }

    private fun openGate(scenario: ActivityScenario<MainActivity>, phase: ConnectionPhase = ConnectionPhase.IDLE) {
        compose.waitForIdle()
        await(scenario, "initial $phase") { it.phase == phase }
        click("connection-tab")
        assertConnectionHasNoDiagnosticDump(compose)
    }
    private fun click(tag: String, expectTransition: Boolean = true) {
        val changesState = tag in setOf("connect", "read-usage", "refresh-session", "remove-account-confirm", "cancel-connect")
        val before = if (changesState) NativeConnection.get(context).state.value else null
        val node = compose.onNodeWithTag(tag)
        if (tag !in setOf("offline-tab", "connection-tab", "remove-account-confirm")) node.performScrollTo()
        node.performClick()
        if (before == null) return
        if (expectTransition) bounded("$tag owner admission", { NativeConnection.get(context).state.value.toString() }) {
            NativeConnection.get(context).state.value !== before
        } else {
            settleOwnerCommands()
            assertSame("$tag rejected without mutation", before, NativeConnection.get(context).state.value)
        }
    }
    private fun settleOwnerCommands(step: String = "owner command receipt") {
        try { runBlocking { withTimeout(5_000) { NativeConnection.get(context).commandsSettled() } } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val state = NativeConnection.get(context).state.value
            throw AssertionError("$step: last state=$state, refreshing=${state.refresh.refreshing}", error)
        }
    }
    private fun await(scenario: ActivityScenario<MainActivity>, step: String, test: (ConnectionState) -> Boolean) {
        var last = "no Activity"
        bounded(step, { last }) {
            var matches = false
            scenario.onActivity {
                val state = it.connection.state.value
                last = "$state, refreshing=${state.refresh.refreshing}"
                // Endpoint facts can be OBSERVED before the current B2 flight has settled.
                matches = test(state) && (state.phase != ConnectionPhase.OBSERVED || !state.refresh.refreshing)
            }
            matches
        }
        // Some owner waits run on the offline tab; require the real live root before this UI oracle.
        if (compose.onAllNodes(hasTestTag("live-usage")).fetchSemanticsNodes().isNotEmpty()) {
            assertConnectionHasNoDiagnosticDump(compose)
        }
    }
    private fun bounded(step: String, last: () -> String = { "no diagnostic" }, test: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 10_000, condition = test) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: timed out; last state=${last()}", error) }
    }

    private fun assertSavedStateHasNoSecrets(scenario: ActivityScenario<MainActivity>) {
        val bundle = Bundle()
        var diagnostics = ""
        var owner: ConnectionController? = null
        scenario.onActivity {
            assertEquals("Before saved-state capture", Lifecycle.State.RESUMED, it.lifecycle.currentState)
            owner = it.connection
        }
        // ComponentActivity.onSaveInstanceState demotes its LifecycleRegistry to CREATED.
        // Calling it on a resumed Activity leaves ActivityScenario thinking it is still
        // RESUMED, while Compose unregisters the root. Stop through the scenario first
        // so the real resume below restores both lifecycle and Compose registration.
        val previousAttempt = owner!!.state.value.refresh.usage.attempt
        scenario.moveToState(Lifecycle.State.CREATED)
        try {
            scenario.onActivity {
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, bundle)
                diagnostics = it.connection.state.value.toString() + it.connection.state.value.auth.toString()
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        } finally {
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
        compose.waitForIdle()
        if (previousAttempt != null) bounded("saved-state resume refresh completes", { owner.state.value.toString() }) {
            owner.state.value.refresh.usage.attempt !== previousAttempt && !owner.state.value.refresh.refreshing
        }
        scenario.onActivity {
            assertEquals("After saved-state capture", Lifecycle.State.RESUMED, it.lifecycle.currentState)
            assertSame("Saved-state capture must keep the connection owner", owner, it.connection)
        }
        // A resumed scenario alone is not proof that the Compose root is usable.
        // Query semantics on the test thread, never inside onActivity.
        compose.onNodeWithTag("connection-tab").assertExists()
        val parcel = Parcel.obtain()
        val bytes = try { parcel.writeBundle(bundle); parcel.marshall() } finally { parcel.recycle() }
        for (secret in listOf(CODE, SECOND_CODE, ACCESS, REFRESH, ROTATED_ACCESS, ROTATED_REFRESH, "synthetic-device", "synthetic-authorization", "synthetic-verifier")) {
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
    private fun persistedEnvelope(slot: java.util.UUID): CredentialEnvelope {
        val store = KeystoreCredentialStore(context, slot)
        // A separate inspection store owns its own capability, not the controller's.
        return (store.read(store.openSession()) as CredentialResult.Success).value
    }
    private fun keyExists(session: java.util.UUID): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .containsAlias(KeystoreCredentialStore.alias(context, session))

    private class NativeFake : AuthTransport {
        val calls = CopyOnWriteArrayList<ProviderHttpRequest>()
        val reads = CopyOnWriteArrayList<(TransportResult) -> Unit>()
        val poll = CompletableDeferred<Unit>()
        val refreshes = CopyOnWriteArrayList<(TransportResult) -> Unit>()
        @Volatile var holdRefresh = false
        @Volatile var refreshStatus = 200
        @Volatile var rejectOriginalBearer = false
        @Volatile var cancelledRefreshes = 0
        @Volatile var refreshCount = 0
        private val millis = AtomicLong()
        val clock = TransportClock { TransportTime(Instant.parse("2026-01-01T00:00:00Z").plusMillis(millis.get()), millis.get()) }
        @Volatile var userCode = CODE
        @Volatile var holdReads = false
        @Volatile var readStatus = 200
        @Volatile var cancelledReads = 0
        suspend fun pause(duration: Long) { poll.await(); millis.addAndGet(duration) }
        fun expirePendingCode() { millis.addAndGet(900_000L) }
        override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit): CancellationHandle {
            calls += request
            val refreshing = request is ProviderHttpRequest.FormPost &&
                request.fields["grant_type"]?.copyBytes()?.toString(Charsets.UTF_8) == "refresh_token"
            val heldRead = request is ProviderHttpRequest.Get && holdReads
            val heldRefresh = refreshing && holdRefresh
            if (refreshing) refreshCount++
            if (heldRefresh) refreshes += terminal
            else if (heldRead) reads += terminal
            else {
                millis.incrementAndGet()
                terminal(response(body(request, refreshing), status(request, refreshing)))
            }
            return CancellationHandle {
                if (heldRead) cancelledReads++
                if (heldRefresh) cancelledRefreshes++
            }
        }

        private fun body(request: ProviderHttpRequest, refreshing: Boolean): String = when (request.url.encodedPath) {
            "/api/accounts/deviceauth/usercode" -> """{"device_auth_id":"synthetic-device","user_code":"$userCode"}"""
            "/api/accounts/deviceauth/token" -> """{"authorization_code":"synthetic-authorization","code_verifier":"synthetic-verifier"}"""
            "/oauth/token" -> if (refreshing) """{"access_token":"$ROTATED_ACCESS","refresh_token":"$ROTATED_REFRESH"}"""
                else """{"access_token":"$ACCESS","refresh_token":"$REFRESH"}"""
            ReadOperation.USAGE.path -> USAGE
            ReadOperation.RESET_INVENTORY.path -> INVENTORY
            else -> error("Unexpected synthetic route")
        }

        private fun status(request: ProviderHttpRequest, refreshing: Boolean): Int = when {
            refreshing -> refreshStatus
            request is ProviderHttpRequest.Get && rejectOriginalBearer &&
                request.bearer.copyBytes().toString(Charsets.UTF_8) == ACCESS -> 401
            request is ProviderHttpRequest.Get -> readStatus
            else -> 200
        }
    }

    private companion object {
        const val CODE = "SYNTHETIC-NATIVE-CODE"
        const val SECOND_CODE = "SYNTHETIC-SUCCESSOR-CODE"
        const val ACCESS = "synthetic-native-access"
        const val REFRESH = "synthetic-native-refresh"
        const val ROTATED_ACCESS = "synthetic-native-rotated-access"
        const val ROTATED_REFRESH = "synthetic-native-rotated-refresh"
        const val USAGE = """{"rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"limit_window_seconds":604800,"used_percent":5,"reset_at":1791756793},"secondary_window":{"limit_window_seconds":18000,"used_percent":12,"reset_after_seconds":500}},"rate_limit_reset_credits":{"available_count":2}}"""
        const val INVENTORY = """{"available_count":2,"credits":[{"expires_at":"2026-10-22T20:31:56.833553Z"},{"expires_at":null}]}"""
        fun response(body: String, status: Int = 200) = TransportResult.Response.bounded(status, body.toByteArray())
    }
}
