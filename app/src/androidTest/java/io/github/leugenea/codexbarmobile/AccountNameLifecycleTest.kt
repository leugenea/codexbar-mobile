package io.github.leugenea.codexbarmobile

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.account.AccountNameFile
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore

/** Real Activity/owner/storage with synthetic transport and labels, not process-death evidence. */
@RunWith(AndroidJUnit4::class)
class AccountNameLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: HistoryNavigationFixture
    @Before fun prepare() { fixture = HistoryNavigationFixture(); fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun accessibleEditorSetsEditsCancelsClearsAndBoundsUnicodeWithoutChangingProviderTraffic() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openConnection()
            label("Unnamed local account")
            save("Synthetic personal label")
            val counts = fixture.transport.counts()
            click("account-name-edit")
            compose.onNodeWithTag("account-name-input").assertTextContains("Synthetic personal label")
            compose.onNodeWithTag("account-name-input").performTextReplacement("Synthetic discarded")
            click("account-name-cancel")
            label("Synthetic personal label")
            // Incremental spaces must remain editable, not get trimmed after each keystroke.
            click("account-name-edit")
            compose.onNodeWithTag("account-name-input").performTextReplacement("Synthetic ")
            compose.onNodeWithTag("account-name-input").performTextInput("work label")
            click("account-name-save")
            awaitName("Synthetic work label")
            val long = "\ud83d\ude80".repeat(80)
            save(long + "\ud83d\ude80")
            awaitName(long)
            label(long)
            val node = compose.onNodeWithTag("account-name").fetchSemanticsNode()
            assertTrue(node.config.contains(SemanticsProperties.Heading))
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("account-name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().lineCount <= 2)
            assertEquals(long, node.config[SemanticsProperties.Text].single().text)
            save(" \t\n\u00a0")
            awaitName(null)
            label("Unnamed local account")
            save("Synthetic clear action")
            click("account-name-edit"); click("account-name-clear")
            awaitName(null)
            label("Unnamed local account")
            assertEquals(counts, fixture.transport.counts())
            assertFalse(nameFile().exists())
        }
    }

    @Test fun committedNameSurvivesRecreationAndFreshOwnerStorageRestorationWithoutRequests() {
        seedDormant()
        val counts = fixture.transport.counts()
        lateinit var before: io.github.leugenea.codexbarmobile.account.AccountNameEdit
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openConnection(); save("Synthetic persistent label")
            before = fixture.owner.accountName.value!!.edit!!
            scenario.recreate(); compose.waitForIdle()
            awaitName("Synthetic persistent label")
            label("Synthetic persistent label")
            assertSame(before, fixture.owner.accountName.value!!.edit)
            click("account-name-edit")
            compose.onNodeWithTag("account-name-input").performTextReplacement("Synthetic unsaved draft")
            scenario.recreate(); compose.waitForIdle()
            compose.onNodeWithTag("account-name-input").assertDoesNotExist()
            label("Synthetic persistent label")
            scenario.moveToState(Lifecycle.State.CREATED)
            await("STOP retires foreground history") { fixture.owner.historySnapshots.value.generation == null }
            scenario.moveToState(Lifecycle.State.RESUMED); compose.waitForIdle()
            label("Synthetic persistent label")
        }
        fixture.freshRuntime(); phase(ConnectionPhase.RESTORED)
        assertNotSame(before, fixture.owner.accountName.value!!.edit)
        ActivityScenario.launch(MainActivity::class.java).use {
            openConnection(); label("Synthetic persistent label")
            assertEquals(counts, fixture.transport.counts())
            assertTrue(nameFile().isFile)
        }
    }

    @Test fun logoutAndReplacementDeleteTheNameAndRejectCapturedPredecessorEdits() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openConnection(); save("Synthetic predecessor")
            val old = fixture.owner.accountName.value!!.edit!!
            click("account-name-edit")
            fixture.owner.signOut(); phase(ConnectionPhase.SIGNED_OUT)
            compose.onNodeWithTag("account-name-input").assertDoesNotExist()
            compose.onNodeWithTag("account-name").assertDoesNotExist()
            assertFalse(nameFile().exists())
            assertFalse(File(nameFile().parentFile, AccountNameFile.PENDING_NAME).exists())
            click("connect"); phase(ConnectionPhase.OBSERVED)
            label("Unnamed local account")
            val next = fixture.owner.accountName.value!!.edit!!
            assertNotEquals(old.partition, next.partition)
            fixture.owner.renameAccount(old, "Synthetic stale callback")
            settleCommands("stale edit command rejected")
            assertNull(fixture.owner.accountName.value!!.name)
            assertFalse(nameFile().exists())
            save("Synthetic successor")
            val counts = fixture.transport.counts()
            val unchanged = nameFile().readBytes()
            fixture.owner.renameAccount(old, "Synthetic late callback")
            settleCommands("late edit command rejected")
            assertArrayEquals(unchanged, nameFile().readBytes())
            label("Synthetic successor")
            assertEquals(counts, fixture.transport.counts())
            // Explicit replacement also settles predecessor removal before a new login.
            click("connect")
            await("replacement adopts a fresh unnamed account") {
                fixture.owner.state.value.let { it.phase == ConnectionPhase.OBSERVED && !it.refresh.refreshing }
                    && fixture.owner.accountName.value?.edit?.let { it !== next } == true
            }
            label("Unnamed local account")
            assertFalse(nameFile().exists())
        }
    }

    @Test fun unreadableNameIsNotAnIdentityOrCredentialFailureAndKeyLossPurgesIt() {
        seedDormant()
        val edit = fixture.owner.accountName.value!!.edit!!
        fixture.owner.renameAccount(edit, "Synthetic corruption seed")
        awaitName("Synthetic corruption seed")
        nameFile().writeBytes(byteArrayOf(1))
        fixture.freshRuntime(); phase(ConnectionPhase.RESTORED)
        ActivityScenario.launch(MainActivity::class.java).use {
            openConnection()
            label("Local account · name unavailable")
            compose.onNodeWithTag("account-name-error").assertExists()
            compose.onNodeWithTag("account-name-edit").assertIsEnabled()
            save("Synthetic repaired")
            compose.onNodeWithTag("account-name-error").assertDoesNotExist()
        }
        val slot = NativeConnection.session(fixture.context)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KeystoreCredentialStore.alias(fixture.context, slot)) }
        fixture.freshRuntime(); phase(ConnectionPhase.REAUTH_REQUIRED)
        assertNull(fixture.owner.accountName.value)
        assertFalse(nameFile().exists())
    }

    private fun seedDormant() {
        phase(ConnectionPhase.IDLE)
        fixture.owner.usageForeground(fixture.observer, true)
        fixture.owner.connect(); phase(ConnectionPhase.OBSERVED)
        fixture.freshRuntime(); phase(ConnectionPhase.RESTORED)
    }
    private fun openConnection() { compose.waitForIdle(); click("connection-tab") }
    private fun save(input: String) {
        click("account-name-edit")
        compose.onNodeWithTag("account-name-input").performTextReplacement(input)
        click("account-name-save")
        awaitName(io.github.leugenea.codexbarmobile.account.AccountDisplayName.from(input)?.text)
    }
    private fun label(text: String) = compose.onNodeWithTag("account-name").performScrollTo().assertTextEquals(text).assertIsDisplayed()
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag in setOf("account-name-edit", "connect")) node.performScrollTo()
        node.performClick(); compose.waitForIdle()
    }
    private fun settleCommands(step: String) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val receipt = scope.async { fixture.owner.commandsSettled() }
        try {
            await(step) { receipt.isCompleted }
            kotlinx.coroutines.runBlocking { receipt.await() }
        } finally { scope.cancel() }
    }
    private fun nameFile() = File(fixture.root, "usage-history/${AccountNameFile.FILE_NAME}")
    private fun awaitName(expected: String?) = await("committed name settled") {
        fixture.owner.accountName.value?.let { !it.storageFailed && it.name?.text == expected } == true
    }
    private fun phase(expected: ConnectionPhase) = await("phase $expected") {
        fixture.owner.state.value.let { it.phase == expected && !it.refresh.refreshing }
    }
    private fun await(step: String, predicate: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 8_000, condition = predicate) }
        catch (error: ComposeTimeoutException) { throw AssertionError("$step: last phase=${fixture.owner.state.value.phase}", error) }
    }
}
