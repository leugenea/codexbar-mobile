package io.github.leugenea.codexbarmobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Captured callbacks come from the existing settings UI, not a future Accounts screen. */
@RunWith(AndroidJUnit4::class)
class CapturedAccountActionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: AccountSelectionFixture
    private val originalBrowser = MainActivity.browserLauncher
    @Before fun prepare() { fixture = AccountSelectionFixture(); fixture.prepare() }
    @After fun cleanup() { MainActivity.browserLauncher = originalBrowser; fixture.cleanup() }

    @Test fun displayedReauthBrowserFailureCancelAndCopyStayOnAWhileBIsSelectedAndRejectSuccessor() {
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val oldEditor = requireNotNull(a.accountName.value?.edit)
        fixture.aTransport.usageStatus = 401
        fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.REAUTH_REQUIRED)
        fixture.aTransport.usageStatus = 200
        fixture.aTransport.heldPath = "/api/accounts/deviceauth/token"
        fixture.installActivity()
        ActivityScenario.launch(MainActivity::class.java).use {
            openDetailRoute(compose, a, DashboardRoute.ACCOUNT_SETTINGS)
            val reauth = displayedClick("connect")
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            peerAppend(b, 1)
            val peerEnvelope = fixture.ready(b)
            invoke(reauth)
            await("captured A reauthorization reaches its displayed-code boundary", a) { a.state.value.auth is AuthState.AwaitingUser }
            assertEquals(fixture.b.storage.id, root.dashboard().value!!.id)
            assertEquals(fixture.a.storage, fixture.catalog.entry(fixture.a.storage.id)!!.storage)
            assertEquals(fixture.a.name, fixture.catalog.entry(fixture.a.storage.id)!!.name)
            fixture.select(fixture.a)
            openDetailRoute(compose, a, DashboardRoute.ACCOUNT_SETTINGS)
            val first = a.state.value.auth as AuthState.AwaitingUser
            val browser = displayedClick("open-browser")
            val cancel = displayedClick("cancel-connect")
            compose.onNodeWithTag("remove-account").performScrollTo().performClick()
            val remove = displayedClick("remove-account-confirm")
            compose.onNodeWithTag("remove-account-cancel").performClick()
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
            peerAppend(b, 2)
            val peer = fixture.bytes(fixture.b)
            val peerCounts = fixture.bTransport.counts()
            val peerHistory = fixture.diskPage(fixture.b).entries.map { entry -> entry.id to entry.windows.map { it.point?.usedPercent } }
            val launches = AtomicInteger()
            var failBrowser = false
            MainActivity.browserLauncher = { _, intent ->
                val reject = failBrowser
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("https://auth.openai.com/codex/device", intent.data.toString())
                assertTrue(intent.categories.contains(Intent.CATEGORY_BROWSABLE))
                launches.incrementAndGet()
                if (reject) throw ActivityNotFoundException("synthetic platform failure")
            }
            invoke(browser)
            await("valid delayed A browser launch on Main", a) { launches.get() == 1 }
            val copied = mutableListOf<SensitiveValue>()
            assertTrue(fixture.receipt("valid delayed A copy", a) { a.copyDeviceCode(first, DeviceCodeClipboard { copied += it; true }) })
            assertSame(first.userCode, copied.single())
            failBrowser = true
            invoke(browser)
            fixture.phase(a, ConnectionPhase.FAILED)
            assertEquals(ConnectionProblem.BROWSER, a.state.value.problem)
            assertEquals(2, launches.get())
            a.connect(a.state.value.operation)
            await("same-entry successor awaiting code", a) { a.state.value.auth is AuthState.AwaitingUser && a.state.value.auth !== first }
            val successor = a.state.value.auth as AuthState.AwaitingUser
            invoke(browser); invoke(cancel); invoke(reauth); invoke(remove)
            settleBrowser(a)
            assertSame(successor, a.state.value.auth)
            assertEquals(2, launches.get())
            assertFalse(fixture.receipt("old A copy cannot borrow equal-text successor", a) { a.copyDeviceCode(first, DeviceCodeClipboard { copied += it; true }) })
            a.renameAccount(oldEditor, "Forbidden stale name")
            fixture.aTransport.releaseHeld()
            fixture.phase(a, ConnectionPhase.OBSERVED)
            val healthy = fixture.ready(a)
            invoke(reauth); invoke(cancel); invoke(remove); invoke(browser)
            settleBrowser(a)
            assertSame(healthy, fixture.ready(a))
            assertEquals(fixture.a.name, a.accountName.value!!.name!!.text)
            assertEquals(fixture.b.storage.id, fixture.catalog.snapshot().selected)
            assertSame(peerEnvelope, fixture.ready(b))
            assertEquals(peer, fixture.bytes(fixture.b))
            assertEquals(peerHistory, fixture.diskPage(fixture.b).entries.map { entry -> entry.id to entry.windows.map { it.point?.usedPercent } })
            assertEquals(peerCounts, fixture.bTransport.counts())
            assertArrayEquals(peerEnvelope.accessToken.copyBytes(), fixture.protectedReadback(fixture.b).accessToken.copyBytes())
            assertEquals(2L, peerCounts[ReadOperation.USAGE.path])
            assertEquals(2L, peerCounts[ReadOperation.RESET_INVENTORY.path])
        }
    }

    @Test fun displayedCancelStaysOnAAndContinuousCompositionBrowserCannotBorrowSuccessorOrOutliveAdmission() {
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        fixture.aTransport.usageStatus = 401
        fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.REAUTH_REQUIRED)
        fixture.aTransport.usageStatus = 200
        fixture.aTransport.heldPath = "/api/accounts/deviceauth/token"
        fixture.installActivity()
        val launches = AtomicInteger()
        MainActivity.browserLauncher = { _, _ -> launches.incrementAndGet() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDetailRoute(compose, a, DashboardRoute.ACCOUNT_SETTINGS)
            invoke(displayedClick("connect"))
            await("first live A attempt", a) { a.state.value.auth is AuthState.AwaitingUser }
            val cancel = displayedClick("cancel-connect")
            fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED); peerAppend(b, 1)
            val peer = fixture.bytes(fixture.b); val peerCounts = fixture.bTransport.counts()
            invoke(cancel); fixture.phase(a, ConnectionPhase.CANCELLED)
            assertEquals(fixture.b.storage.id, fixture.catalog.snapshot().selected)
            assertEquals(peer, fixture.bytes(fixture.b)); assertEquals(peerCounts, fixture.bTransport.counts())

            fixture.select(fixture.a)
            openDetailRoute(compose, a, DashboardRoute.ACCOUNT_SETTINGS)
            invoke(displayedClick("connect"))
            await("continuously composed second A attempt", a) { a.state.value.auth is AuthState.AwaitingUser }
            val second = a.state.value.auth as AuthState.AwaitingUser
            val browser = displayedClick("open-browser")
            invoke(displayedClick("cancel-connect")); fixture.phase(a, ConnectionPhase.CANCELLED)
            invoke(displayedClick("connect"))
            await("same-root third A attempt", a) { a.state.value.auth is AuthState.AwaitingUser && a.state.value.auth !== second }
            val third = a.state.value.auth as AuthState.AwaitingUser
            invoke(browser); settleBrowser(a)
            assertEquals(0, launches.get())
            assertSame(third, a.state.value.auth)

            // Hold Main after the real ingress enqueues its owner admission. Its return
            // continuation cannot launch until the later serialized revocation has settled.
            val enqueued = CountDownLatch(1); val retired = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            lateinit var browserJob: Job
            val revoke = scope.async {
                try {
                    check(enqueued.await(8, TimeUnit.SECONDS)) { "browser ingress: last state=${a.state.value}" }
                    withTimeout(8_000) { a.commandsSettled() }
                    assertSame(third, a.state.value.auth)
                    assertEquals("https://auth.openai.com/codex/device", a.verificationUrl(third))
                    a.cancel(a.state.value.operation)
                    withTimeout(8_000) { a.commandsSettled() }
                    assertEquals(ConnectionPhase.CANCELLED, a.state.value.phase)
                } finally { retired.countDown() }
            }
            try {
                scenario.onActivity { activity ->
                    browserJob = activity.openVerificationBrowser(a, third)
                    enqueued.countDown()
                    assertTrue("Main resume after revocation: last state=${a.state.value}", retired.await(8, TimeUnit.SECONDS))
                }
                fixture.receipt("admitted browser Main continuation and revocation outcome", a) { revoke.await(); browserJob.join() }
                assertEquals(0, launches.get())
                assertEquals(peer, fixture.bytes(fixture.b)); assertEquals(peerCounts, fixture.bTransport.counts())
                assertArrayEquals("synthetic-B-access".toByteArray(), fixture.protectedReadback(fixture.b).accessToken.copyBytes())
            } finally { retired.countDown(); scope.cancel() }
        }
    }

    @Test fun retainedCredentiallessSavedEntryCanCancelThenConfirmRemovalAndActivateDormantPeer() {
        val root = fixture.start(); val selected = root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val peer = fixture.bytes(fixture.b)
        fixture.aTransport.usageStatus = 401
        fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.REAUTH_REQUIRED)
        assertEquals(fixture.a.copy(partition = null), fixture.catalog.entry(fixture.a.storage.id))
        assertFalse(a.session.snapshot() is SessionResult.Ready)
        assertFalse(fixture.keyExists(fixture.a))
        assertEquals(0L, fixture.bTransport.requests.get())
        fixture.installActivity()
        ActivityScenario.launch(MainActivity::class.java).use {
            openDetailRoute(compose, a, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("remove-account").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("remove-account-cancel").performClick()
            fixture.settle(a)
            assertEquals(fixture.a.storage.id, selected.value!!.id)
            assertEquals(peer, fixture.bytes(fixture.b))
            assertEquals(0L, fixture.bTransport.requests.get())
            compose.onNodeWithTag("remove-account").performScrollTo().performClick()
            compose.onNodeWithTag("remove-account-confirm").performClick()
            fixture.phase(a, ConnectionPhase.SIGNED_OUT)
            fixture.await("selected removal publishes stable B fallback") { selected.value?.id == fixture.b.storage.id }
            fixture.phase(b, ConnectionPhase.OBSERVED)
            peerAppend(b, 1)
            fixture.assertRemoved(fixture.a)
            assertEquals(1L, fixture.bTransport.counts()[ReadOperation.USAGE.path])
            assertEquals(1L, fixture.bTransport.counts()[ReadOperation.RESET_INVENTORY.path])
            assertArrayEquals("synthetic-B-access".toByteArray(), fixture.protectedReadback(fixture.b).accessToken.copyBytes())
        }
    }

    @Test fun missingCredentialSavedEntryRestoresRemovableButEmptyAnonymousInstallationDoesNot() {
        KeystoreCredentialStore(fixture.context, fixture.a.storage.credentialSlot).let { assertTrue(it.delete(it.openSession()) is CredentialResult.Success) }
        val root = fixture.start(); root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.REAUTH_REQUIRED); fixture.phase(b, ConnectionPhase.RESTORED)
        fixture.installActivity()
        ActivityScenario.launch(MainActivity::class.java).use {
            openDetailRoute(compose, a, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("remove-account").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("remove-account-cancel").performClick()
            compose.onNodeWithTag("remove-account").performScrollTo().performClick()
            compose.onNodeWithTag("remove-account-confirm").performClick()
            fixture.phase(a, ConnectionPhase.SIGNED_OUT)
            fixture.await("missing-credential removal selects B") { root.dashboard().value?.id == fixture.b.storage.id }
            fixture.phase(b, ConnectionPhase.OBSERVED)
            openDetailRoute(compose, b, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("remove-account").performScrollTo().performClick()
            compose.onNodeWithTag("remove-account-confirm").performClick()
            fixture.phase(b, ConnectionPhase.SIGNED_OUT)
            fixture.await("empty catalog has no selection") { root.dashboard().value?.id == null }
            val anonymous = root.legacy()
            // The retained legacy controller may show its terminal phase; it is not a saved entry.
            assertFalse(anonymous.hasSavedAccount)
            assertTrue(fixture.catalog.snapshot().entries.isEmpty())
            openDetailRoute(compose, anonymous, DashboardRoute.ACCOUNT_SETTINGS)
            compose.onNodeWithTag("remove-account").performScrollTo().assertIsNotEnabled()
            fixture.assertRemoved(fixture.a); fixture.assertRemoved(fixture.b)
        }
    }

    @Test fun liveRenameAndCapturedRemovalAcrossRootABAKeepAccountAuthorityWithoutRetargeting() {
        val root = fixture.start(); val selected = root.dashboard()
        val a = fixture.runtime(fixture.a); val b = fixture.runtime(fixture.b)
        fixture.phase(a, ConnectionPhase.RESTORED); fixture.phase(b, ConnectionPhase.RESTORED)
        val editor = requireNotNull(a.accountName.value?.edit)
        val removal = a.accountRemoval
        fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
        val peerEnvelope = fixture.ready(b)
        val peer = fixture.bytes(fixture.b)
        val counts = fixture.bTransport.counts()
        a.renameAccount(editor, "Synthetic renamed A")
        fixture.await("captured live A editor settles while B selected") { a.accountName.value?.name?.text == "Synthetic renamed A" }
        assertEquals("Synthetic renamed A", fixture.catalog.entry(fixture.a.storage.id)!!.name)
        assertSame(editor, a.accountName.value!!.edit)
        fixture.select(fixture.a); fixture.phase(a, ConnectionPhase.OBSERVED)
        fixture.select(fixture.b); fixture.phase(b, ConnectionPhase.OBSERVED)
        val afterABA = fixture.bytes(fixture.b)
        val countsAfterABA = fixture.bTransport.counts()
        a.signOut(removal); fixture.phase(a, ConnectionPhase.SIGNED_OUT)
        assertEquals(fixture.b.storage.id, selected.value!!.id)
        assertSame(peerEnvelope, fixture.ready(b))
        assertEquals(afterABA, fixture.bytes(fixture.b))
        assertEquals(countsAfterABA, fixture.bTransport.counts())
        fixture.assertRemoved(fixture.a)
        assertEquals(peer, fixture.bytes(fixture.b)) // No foreground recorder was activated in this case.
        assertEquals(counts[ReadOperation.USAGE.path]!! + 1, countsAfterABA[ReadOperation.USAGE.path])
        assertArrayEquals(peerEnvelope.accessToken.copyBytes(), fixture.protectedReadback(fixture.b).accessToken.copyBytes())
    }

    private fun displayedClick(tag: String): () -> Boolean {
        val node = compose.onNodeWithTag(tag)
        if (tag != "remove-account-confirm") node.performScrollTo()
        return requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }
    private fun invoke(action: () -> Boolean) { compose.runOnUiThread { assertTrue(action()) } }
    private fun settleBrowser(controller: ConnectionController) {
        fixture.settle(controller)
        // The serialized admission preceded its owner receipt; now drain its already
        // dispatched Main continuation before asserting absence of a platform effect.
        compose.runOnUiThread { }
    }
    private fun peerAppend(controller: ConnectionController, observations: Int) {
        fixture.await("B exact admitted observation $observations") {
            fixture.appends.count { it.admission.partition == fixture.b.partition &&
                it.admission.event is io.github.leugenea.codexbarmobile.history.HistoryEvent.Observed } == observations
        }
        fixture.settle(controller)
    }
    private fun await(label: String, controller: ConnectionController, predicate: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 8_000, condition = predicate) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$label: last state=${controller.state.value}", error) }
    }
}
