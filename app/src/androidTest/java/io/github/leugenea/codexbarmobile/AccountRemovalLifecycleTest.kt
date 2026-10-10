package io.github.leugenea.codexbarmobile

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.account.AccountNameFile
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.HistoryReadiness
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Production Activity/default owner and actual SQLite/Keystore; only transport is synthetic. */
@RunWith(AndroidJUnit4::class)
class AccountRemovalLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: HistoryNavigationFixture
    private val owner get() = fixture.owner
    @Before fun prepare() { fixture = HistoryNavigationFixture(); fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun restoredAccountConfirmationExplainsLocalScopeAndCancelDismissAndRecreationChangeNothing() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openConnection(); saveName("Synthetic retained account")
            await("restored history page") { owner.historySnapshots.value.storage?.entries?.isNotEmpty() == true }
            val name = nameFile().readBytes()
            val credentials = credentialFile().readBytes()
            val partition = owner.historySnapshots.value.partition
            val highWater = owner.historySnapshots.value.storage!!.lastAdmitted
            val requests = fixture.transport.counts()
            click("remove-account")
            dialogScope()
            click("remove-account-cancel")
            awaitDialogDismissed("Cancel dismisses restored account confirmation")
            click("remove-account")
            dismissRemovalDialogWithBack()
            click("remove-account")
            scenario.recreate(); compose.waitForIdle()
            awaitDialogDismissed("recreation dismisses restored account confirmation")
            phase(ConnectionPhase.RESTORED)
            await("recreated local history adopted") { owner.historySnapshots.value.storage != null }
            assertArrayEquals(name, nameFile().readBytes())
            assertArrayEquals(credentials, credentialFile().readBytes())
            assertTrue(keyExists())
            assertEquals(partition, owner.historySnapshots.value.partition)
            assertEquals(highWater, owner.historySnapshots.value.storage!!.lastAdmitted)
            assertEquals(requests, fixture.transport.counts())
            compose.onNodeWithTag("account-name").performScrollTo().assertTextEquals("Synthetic retained account")
        }
    }

    @Test fun confirmedRemovalDeletesAllLocalArtifactsAndHeldRepliesAndOldConfirmCannotTouchSuccessor() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openConnection(); saveName("Synthetic predecessor")
            await("predecessor history available") { owner.historySnapshots.value.storage != null }
            val oldPartition = owner.historySnapshots.value.partition
            fixture.transport.heldPath = ReadOperation.USAGE.path
            owner.readUsage()
            await("usage request held") { fixture.transport.heldCall(ReadOperation.USAGE.path) != null }
            val late = fixture.transport.heldCall(ReadOperation.USAGE.path)!!
            val oldConfirm = openRemovalDialog()
            click("remove-account-confirm"); phase(ConnectionPhase.SIGNED_OUT)
            assertTrue(late.cancelled)
            assertDeleted()
            awaitDialogDismissed("confirmed removal dismisses predecessor confirmation")
            compose.onNodeWithTag("account-name").assertDoesNotExist()
            click("history-toggle")
            compose.onNodeWithTag("connection-history").assertExists()
            compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
            assertNull(owner.historySnapshots.value.storage)
            click("history-toggle")
            fixture.transport.heldPath = null
            fixture.transport.usageBody = NavigationTransport.USAGE.replace("12.375", "42.125")
            click("connect"); phase(ConnectionPhase.OBSERVED)
            await("successor history recorded") { owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
            saveName("Synthetic successor")
            val partition = owner.historySnapshots.value.partition
            val credentials = credentialFile().readBytes()
            val name = nameFile().readBytes()
            val requests = fixture.transport.counts()
            assertNotEquals(oldPartition, partition)
            late.reply()
            replay(oldConfirm, "stale confirm after relogin and late provider reply")
            assertArrayEquals(credentials, credentialFile().readBytes())
            assertArrayEquals(name, nameFile().readBytes())
            assertTrue(keyExists())
            assertEquals(partition, owner.historySnapshots.value.partition)
            assertEquals(1L, owner.historySnapshots.value.storage!!.lastAdmitted!!.ordinal)
            assertEquals(setOf("42.125", "87.5"), owner.historySnapshots.value.points.map { it.usedPercent.toPlainString() }.toSet())
            assertEquals(requests, fixture.transport.counts())
            compose.onNodeWithTag("account-name").performScrollTo().assertTextEquals("Synthetic successor")
        }
    }

    @Test fun replacementAndCancelDismissOpenDialogAndCapturedConfirmNeverReacquiresNewAccount() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openConnection(); saveName("Synthetic replaced label")
            val stale = openRemovalDialog()
            val predecessor = owner.accountRemoval
            owner.connect()
            settle(owner, "replacement connect command processed")
            await("replacement adopted successor") {
                owner.state.value.let { it.phase == ConnectionPhase.OBSERVED && !it.refresh.refreshing }
                    && owner.accountRemoval !== predecessor && owner.accountName.value?.edit != null
            }
            awaitDialogDismissed("replacement dismisses predecessor confirmation")
            await("replacement history recorded") { owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
            saveName("Synthetic replacement successor")
            val credentials = credentialFile().readBytes()
            val name = nameFile().readBytes()
            val partition = owner.historySnapshots.value.partition
            val requests = fixture.transport.counts()
            replay(stale, "replaced dialog permission rejected")
            assertArrayEquals(credentials, credentialFile().readBytes())
            assertArrayEquals(name, nameFile().readBytes())
            assertEquals(partition, owner.historySnapshots.value.partition)
            assertEquals(requests, fixture.transport.counts())
            val cancelled = openRemovalDialog()
            owner.cancel(); phase(ConnectionPhase.CANCELLED)
            awaitDialogDismissed("owner cancellation dismisses account confirmation")
            replay(cancelled, "cancel invalidates removal permission")
            assertArrayEquals(credentials, credentialFile().readBytes())
            assertArrayEquals(name, nameFile().readBytes())
            assertTrue(keyExists())
        }
    }

    @Test fun samePhaseReplacementDismissesOpenDialogWithoutIntermediatePhaseDelivery() {
        phase(ConnectionPhase.IDLE)
        val controller = owner
        controller.usageForeground(fixture.observer, true)
        controller.connect(); phase(ConnectionPhase.OBSERVED)
        val predecessor = controller.accountRemoval
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    // Deliberately never collect connection state: every render has the same phase/owner.
                    AccountRemovalAction(ConnectionPhase.OBSERVED, controller)
                }
            } }
            compose.waitForIdle()
            compose.onNodeWithTag("remove-account").performClick()
            compose.onNodeWithTag("remove-account-dialog").assertIsDisplayed()
            controller.connect()
            settle(controller, "same-phase replacement connect command processed")
            await("same-phase successor adopted") {
                controller.state.value.let { it.phase == ConnectionPhase.OBSERVED && !it.refresh.refreshing }
                    && controller.accountRemoval !== predecessor && controller.accountName.value?.edit != null
            }
            awaitDialogDismissed("same-phase permission replacement dismisses dialog")
            assertNotSame(predecessor, controller.accountRemoval)
            assertEquals(ConnectionPhase.OBSERVED, controller.state.value.phase)
            compose.onNodeWithTag("remove-account").assertIsEnabled().performClick()
            compose.onNodeWithTag("remove-account-dialog").assertIsDisplayed()
            compose.onNodeWithTag("remove-account-cancel").performClick()
            awaitDialogDismissed("Cancel dismisses same-phase successor confirmation")
        }
    }

    @Test fun nameArtifactDeletionFailureIsStorageFailureAndOnlyFreshConfirmedRetryReportsRemoval() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openConnection(); saveName("Synthetic removal failure")
            val name = nameFile().readBytes()
            assertTrue(nameFile().delete())
            assertTrue(nameFile().mkdir())
            val obstacle = File(nameFile(), "synthetic-removal-obstacle")
            obstacle.writeBytes(name)
            try {
                val stale = openRemovalDialog()
                click("remove-account-confirm"); phase(ConnectionPhase.FAILED)
                awaitDialogDismissed("failed removal dismisses account confirmation")
                assertEquals(ConnectionProblem.STORAGE, owner.state.value.problem)
                assertFalse(credentialFile().exists())
                assertFalse(keyExists())
                assertTrue(obstacle.isFile)
                assertNull(owner.accountName.value)
                assertNull(owner.historySnapshots.value.storage)
                compose.onNodeWithTag("remove-account").performScrollTo().assertIsEnabled()
                val requests = fixture.transport.counts()
                replay(stale, "old failed confirmation cannot silently retry")
                assertEquals(ConnectionPhase.FAILED, owner.state.value.phase)
                assertTrue(obstacle.isFile)
                assertEquals(requests, fixture.transport.counts())
                assertTrue(obstacle.delete()); assertTrue(nameFile().delete())
                nameFile().writeBytes(name)
                click("remove-account"); click("remove-account-cancel")
                awaitDialogDismissed("Cancel dismisses removal retry confirmation")
                assertEquals(ConnectionPhase.FAILED, owner.state.value.phase)
                assertTrue(nameFile().isFile)
                click("remove-account"); click("remove-account-confirm")
                phase(ConnectionPhase.SIGNED_OUT)
                awaitDialogDismissed("confirmed retry dismisses account confirmation")
                assertDeleted()
                assertEquals(requests, fixture.transport.counts())
            } finally { obstacle.delete(); if (nameFile().isDirectory) nameFile().delete() }
        }
        fixture.freshRuntime(); phase(ConnectionPhase.IDLE)
        assertNull(owner.accountName.value)
        assertEquals(HistoryReadiness.UNAVAILABLE, owner.historySnapshots.value.readiness)
        assertDeleted()
    }

    private fun seedDormant() {
        phase(ConnectionPhase.IDLE)
        owner.usageForeground(fixture.observer, true)
        owner.connect(); phase(ConnectionPhase.OBSERVED)
        await("seed observation stored") { owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
        fixture.freshRuntime(); phase(ConnectionPhase.RESTORED)
    }
    private fun openConnection() { compose.waitForIdle(); click("connection-tab") }
    private fun saveName(value: String) {
        click("account-name-edit")
        compose.onNodeWithTag("account-name-input").performTextReplacement(value)
        click("account-name-save")
        await("local name saved") { owner.accountName.value?.name?.text == value }
    }
    private fun dialogScope() {
        compose.onNodeWithText("Remove local account?").assertIsDisplayed()
        compose.onNodeWithText("This removes saved sign-in credentials, local usage history and the local display name from this device. It does not delete your OpenAI/ChatGPT account or cancel a subscription.").assertIsDisplayed()
        compose.onNodeWithTag("remove-account-confirm").assertTextEquals("Remove account").assertHasClickAction()
        compose.onNodeWithTag("remove-account-cancel").assertTextEquals("Cancel").assertHasClickAction()
    }
    private fun openRemovalDialog(): AccountRemoval {
        val captured = owner.accountRemovalPermissions.value
        click("remove-account")
        compose.onNodeWithTag("remove-account-dialog").assertIsDisplayed()
        compose.onNodeWithTag("remove-account-confirm").assertHasClickAction()
        assertSame("Dialog opened with the captured removal permission", captured, owner.accountRemovalPermissions.value)
        return captured
    }
    private fun dismissRemovalDialogWithBack() {
        // Semantics can be ready before the dialog window receives input focus.
        // Fetch on the instrumentation thread; only read View properties on Main.
        val dialog = compose.onNodeWithTag("remove-account-dialog").assertIsDisplayed()
        val view = (dialog.fetchSemanticsNode().root as ViewRootForTest).view
        var focused = false
        var attached = false
        await("removal dialog window focused before Back",
            lastState = { "dialogAttached=$attached, dialogWindowFocused=$focused" }) {
            compose.runOnUiThread {
                attached = view.isAttachedToWindow
                focused = view.hasWindowFocus()
            }
            attached && focused
        }
        // A real system Back, on the instrumentation thread, targets the focused dialog.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitDialogDismissed("Back dismisses restored account confirmation")
    }
    private fun awaitDialogDismissed(step: String) {
        var dialogs = -1
        await(step, lastState = { "${owner.state.value}, dialogCount=$dialogs" }) {
            dialogs = compose.onAllNodesWithTag("remove-account-dialog").fetchSemanticsNodes().size
            dialogs == 0
        }
        compose.onNodeWithTag("remove-account-dialog").assertDoesNotExist()
    }
    private fun replay(captured: AccountRemoval, step: String) {
        // Model delayed dialog confirmation at its owner boundary, not a detached clickable node.
        compose.runOnUiThread { owner.signOut(captured) }
        settle(owner, step)
    }
    private fun settle(controller: ConnectionController, step: String) {
        // Permission rotation can precede AUTHENTICATING; acknowledge the command, not a conflated phase.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receipt = scope.async { controller.commandsSettled() }
        try {
            await(step, lastState = {
                "${controller.state.value}, refreshing=${controller.state.value.refresh.refreshing}, commandReceiptCompleted=${receipt.isCompleted}"
            }) { receipt.isCompleted }
            runBlocking { receipt.await() }
        } finally { scope.cancel() }
    }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag in setOf("remove-account", "account-name-edit", "history-toggle", "connect")) node.performScrollTo()
        node.performClick(); compose.waitForIdle()
    }
    private fun credentialFile() = KeystoreCredentialStore.file(fixture.context, NativeConnection.session(fixture.context))
    private fun nameFile() = File(fixture.root, "usage-history/${AccountNameFile.FILE_NAME}")
    private fun keyExists() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .containsAlias(KeystoreCredentialStore.alias(fixture.context, NativeConnection.session(fixture.context)))
    private fun assertDeleted() {
        assertFalse(credentialFile().exists())
        assertFalse(keyExists())
        for (name in listOf("history.db", "history.db-journal", "history.db-wal", "history.db-shm",
            AccountNameFile.FILE_NAME, AccountNameFile.PENDING_NAME)) assertFalse(File(fixture.root, "usage-history/$name").exists())
    }
    private fun phase(expected: ConnectionPhase) = await("phase $expected") {
        owner.state.value.let { it.phase == expected && !it.refresh.refreshing }
    }
    private fun await(step: String, lastState: () -> Any? = { owner.state.value }, predicate: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 8_000, condition = predicate) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: last state=${lastState()}", error) }
    }
}
