package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.account.*
import io.github.leugenea.codexbarmobile.credentials.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AccountNameLifetimeTest {
    @Test fun editsClearAndTokenRotationPreserveTheContinuingNameAuthorityWithoutExtraRequests() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val edit = h.owner.accountName.value!!.edit!!
            rename(h, edit, " Synthetic local ", "Synthetic local")
            assertTrue(h.fake.calls.isEmpty())
            rename(h, edit, "Synthetic edited", "Synthetic edited")
            h.owner.readUsage(refreshSession = true)
            h.phase(ConnectionPhase.OBSERVED)
            assertSame(edit, h.owner.accountName.value!!.edit)
            assertEquals("Synthetic edited", h.owner.accountName.value!!.name!!.text)
            rename(h, edit, " \t", null)
            assertEquals("AccountNameEdit(redacted)", edit.toString())
            assertFalse(h.owner.state.value.toString().contains("Synthetic"))
        }
    }

    @Test fun cleanCancelAndFreshStorageOwnerRetainNameButRejectTheOldRuntimeCallback() = runBlocking {
        val journal = LifetimeJournal(); val persistence = FakeCredentialPersistence()
        lateinit var old: AccountNameEdit
        LifetimeFixture(journal, persistence).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            old = h.owner.accountName.value!!.edit!!
            rename(h, old, "Synthetic restored", "Synthetic restored")
            h.owner.cancel(); h.phase(ConnectionPhase.CANCELLED)
            assertNull(h.owner.accountName.value)
            assertFalse(h.history.editName(old, "Synthetic stale"))
        }
        LifetimeFixture(journal, persistence, seed = false).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            assertEquals("Synthetic restored", h.owner.accountName.value!!.name!!.text)
            assertNotSame(old, h.owner.accountName.value!!.edit)
            h.owner.renameAccount(old, "Synthetic stale"); settled(h, "reject stale editor")
            assertEquals("Synthetic restored", journal.name!!.text)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun foregroundRetirementDoesNotRetireTheAccountNameOrItsEditAuthority() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val edit = h.owner.accountName.value!!.edit!!
            h.owner.usageForeground(Any(), false); settled(h, "retire foreground history")
            rename(h, edit, "Synthetic background label", "Synthetic background label")
            h.owner.usageForeground(Any(), true)
            await(h, "history resumed") { h.owner.historyAvailability == HistoryAvailability.AVAILABLE }
            assertSame(edit, h.owner.accountName.value!!.edit)
            assertEquals("Synthetic background label", h.owner.accountName.value!!.name!!.text)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun logoutAndReplacementRemoveNameAndRejectPredecessorCallbacks() = runBlocking {
        for (replacement in listOf(false, true)) LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.owner.accountName.value!!.edit!!
            rename(h, old, "Synthetic predecessor", "Synthetic predecessor")
            if (!replacement) { h.owner.signOut(); h.phase(ConnectionPhase.SIGNED_OUT); assertNull(journalName(h)) }
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            val next = h.owner.accountName.value!!.edit!!
            assertNotEquals(old.partition, next.partition)
            assertNull(journalName(h))
            h.owner.renameAccount(old, "Synthetic stale"); settled(h, "reject stale editor")
            assertNull(journalName(h))
            rename(h, next, "Synthetic successor", "Synthetic successor")
        }
    }

    @Test fun admittedHeldNameWriteSettlesBeforeRemovalAndCannotPublishOrTransferToSuccessor() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.owner.accountName.value!!.edit!!
            val gate = ControlledGate(); h.journal.nameGate = gate
            try {
                h.owner.renameAccount(old, "Synthetic held")
                gate.awaitEntered()
                h.owner.signOut(); settled(h, "reserve removal behind held name write")
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertNull(h.owner.accountName.value)
                h.owner.connect(); settled(h, "reject connect during held removal")
                assertTrue(h.fake.calls.isEmpty())
            } finally { gate.release(); h.journal.nameGate = null }
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(journalName(h))
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            assertNull(h.owner.accountName.value!!.name)
            h.owner.renameAccount(old, "Synthetic late"); settled(h, "reject late predecessor editor")
            assertNull(journalName(h))
        }
    }

    @Test fun failedNameStorageKeepsQuotaAndPriorLabelAndAllowsExplicitRetry() = runBlocking {
        val journal = LifetimeJournal().apply { failNameRead = true }
        LifetimeFixture(journal).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            assertTrue(h.owner.accountName.value!!.storageFailed)
            val edit = h.owner.accountName.value!!.edit!!
            journal.failNameRead = false
            rename(h, edit, "Synthetic retained", "Synthetic retained")
            journal.failNameWrite = true
            h.owner.renameAccount(edit, "Synthetic rejected")
            awaitName(h, "name write failure published") { it?.storageFailed == true }
            assertEquals("Synthetic retained", journalName(h))
            assertEquals("Synthetic retained", h.owner.accountName.value!!.name!!.text)
            assertEquals(ConnectionPhase.RESTORED, h.owner.state.value.phase)
            assertNotNull(h.persistence.durable)
            journal.failNameWrite = false
            rename(h, edit, "Synthetic retry", "Synthetic retry")
            assertFalse(h.owner.accountName.value!!.storageFailed)
        }
    }

    @Test fun keyLossCorruptionMissingCredentialsAndTerminalReauthRemoveTheLabel() = runBlocking {
        for (reason in listOf(CredentialFailure.KEY_LOST, CredentialFailure.CORRUPT, CredentialFailure.MISSING)) {
            val journal = LifetimeJournal().apply { name = AccountDisplayName.from("Synthetic orphan") }
            val persistence = FakeCredentialPersistence().apply { readFailure = reason }
            LifetimeFixture(journal, persistence).use { h ->
                h.phase(if (reason == CredentialFailure.MISSING) ConnectionPhase.IDLE else ConnectionPhase.REAUTH_REQUIRED)
                assertNull(journalName(h)); assertNull(h.owner.accountName.value)
            }
        }
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            rename(h, h.owner.accountName.value!!.edit!!, "Synthetic terminal", "Synthetic terminal")
            h.fake.respond = { call ->
                val refresh = call.request.url.encodedPath == "/oauth/token"
                call.reply(io.github.leugenea.codexbarmobile.auth.SyntheticAuth.response(
                    if (refresh) "{\"error\":\"invalid_grant\"}" else "{}", if (refresh) 400 else 401))
            }
            h.owner.readUsage()
            h.phase(ConnectionPhase.REAUTH_REQUIRED)
            assertEquals(1, h.fake.calls.count { it.request.url.encodedPath == "/oauth/token" })
            assertNull(h.persistence.durable)
            assertNull(journalName(h)); assertNull(h.owner.accountName.value)
        }
    }

    @Test fun recoveredLifetimeStorageRepublishesTheNameAndEditorWithoutActivatingProviderRequests() = runBlocking {
        val journal = LifetimeJournal().apply {
            failRestore = true
            name = AccountDisplayName.from("Synthetic recovered")
        }
        LifetimeFixture(journal).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val failed = h.owner.accountName.value!!
            assertTrue(failed.storageFailed)
            assertNull(failed.edit)
            assertEquals(HistoryAvailability.STORAGE_FAILURE, h.owner.historyAvailability)
            assertEquals(1, journal.restoreCalls)
            val observer = Any()
            val gate = ControlledGate(); journal.restoreGate = gate
            try {
                h.owner.usageForeground(observer, true)
                gate.awaitEntered()
                h.owner.usageForeground(observer, true)
                settled(h, "coalesce repeated foreground restore while storage is held")
                assertEquals(2, journal.restoreCalls)
            } finally { gate.release(); journal.restoreGate = null }
            awaitName(h, "repeated lifetime failure published without an editor") {
                it !== failed && it?.storageFailed == true && it.edit == null
            }
            settled(h, "failed local restore completed without automatic retry")
            assertEquals(HistoryAvailability.STORAGE_FAILURE, h.owner.historyAvailability)
            assertEquals(2, journal.restoreCalls)
            assertTrue(h.fake.calls.isEmpty())
            h.owner.usageForeground(Any(), false)
            settled(h, "unrelated observer removal does not retry lifetime storage")
            assertEquals(2, journal.restoreCalls)
            journal.failRestore = false
            h.owner.usageForeground(observer, true)
            awaitName(h, "recovered lifetime republishes name and editor") {
                it?.name?.text == "Synthetic recovered" && it.edit != null && !it.storageFailed
            }
            assertEquals(3, journal.restoreCalls)
            rename(h, h.owner.accountName.value!!.edit!!, "Synthetic available", "Synthetic available")
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun failedRemovalDisablesEditsAndRetryCannotReusePredecessorName() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val edit = h.owner.accountName.value!!.edit!!
            rename(h, edit, "Synthetic failed removal", "Synthetic failed removal")
            h.journal.failDelete = true
            h.owner.signOut(); h.phase(ConnectionPhase.FAILED)
            assertNull(h.owner.accountName.value)
            h.owner.renameAccount(edit, "Synthetic stale"); settled(h, "reject editor after failed removal")
            assertEquals("Synthetic failed removal", journalName(h))
            h.journal.failDelete = false
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            assertNull(journalName(h))
            assertNotSame(edit, h.owner.accountName.value!!.edit)
        }
    }

    private fun journalName(h: LifetimeFixture) = h.journal.name?.text
    private suspend fun rename(h: LifetimeFixture, edit: AccountNameEdit, input: String, expected: String?) {
        h.owner.renameAccount(edit, input)
        awaitName(h, "commit name ${expected ?: "<cleared>"}") {
            it?.edit === edit && !it.storageFailed && it.name?.text == expected
        }
    }
    private suspend fun settled(h: LifetimeFixture, step: String) = bounded(h, step) { h.owner.commandsSettled() }
    private suspend fun awaitName(h: LifetimeFixture, step: String, predicate: (AccountNameState?) -> Boolean) =
        bounded(h, step) { h.owner.accountName.first(predicate) }
    private suspend fun await(h: LifetimeFixture, step: String, predicate: () -> Boolean) =
        bounded(h, step) { while (!predicate()) delay(1) }
    private suspend fun <T> bounded(h: LifetimeFixture, step: String, action: suspend () -> T): T {
        try { return withTimeout(5_000) { action() } }
        catch (error: TimeoutCancellationException) {
            throw AssertionError("$step: last=${h.owner.accountName.value} phase=${h.owner.state.value.phase}", error)
        }
    }
}
