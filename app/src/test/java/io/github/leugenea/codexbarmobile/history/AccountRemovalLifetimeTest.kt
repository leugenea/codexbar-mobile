package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.account.AccountDisplayName
import io.github.leugenea.codexbarmobile.credentials.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual owner/combined teardown with synthetic storage; native cases establish durability. */
class AccountRemovalLifetimeTest {
    @Test fun confirmedRemovalClearsCredentialsHistoryAndNameAndCannotEraseSuccessor() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            seed(h)
            val predecessor = h.owner.accountRemoval
            h.owner.signOut(predecessor); h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertNull(h.journal.name)
            assertTrue(h.journal.entries.isEmpty())
            assertNull(h.owner.accountName.value)
            assertNull(h.owner.historySnapshots.value.storage)
            assertEquals(1, h.journal.deletions)
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            seed(h)
            val durable = h.persistence.durable
            val partition = h.journal.partition
            val count = h.fake.calls.size
            h.owner.signOut(predecessor); h.owner.commandsSettled()
            assertSame(durable, h.persistence.durable)
            assertEquals(partition, h.journal.partition)
            assertNotNull(h.journal.name)
            assertFalse(h.journal.entries.isEmpty())
            assertEquals(count, h.fake.calls.size)
            assertEquals(1, h.journal.deletions)
        }
    }

    @Test fun failedHistoryRemovalStaysFailClosedAndFreshConfirmationRetriesWithoutCredentialResurrection() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            seed(h)
            val confirmation = h.owner.accountRemoval
            h.journal.failDelete = true
            h.owner.signOut(confirmation); h.phase(ConnectionPhase.FAILED)
            assertEquals(ConnectionProblem.STORAGE, h.owner.state.value.problem)
            assertNull(h.persistence.durable)
            assertNotNull(h.journal.name)
            assertFalse(h.journal.entries.isEmpty())
            assertNull(h.owner.accountName.value)
            val failed = h.owner.state.value
            h.owner.readUsage(); h.owner.signOut(confirmation); h.owner.commandsSettled()
            assertSame(failed, h.owner.state.value)
            assertTrue(h.fake.calls.isEmpty())
            h.journal.failDelete = false
            h.owner.signOut(h.owner.accountRemoval); h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.journal.name)
            assertTrue(h.journal.entries.isEmpty())
            assertEquals(2, h.journal.deletions)
            assertEquals(2, h.persistence.deleteCount) // Existing retry reserves fresh combined cleanup.
        }
    }

    @Test fun confirmedRemovalWaitsForAdmittedNameWriteAndRejectsRepeatedOrCancelledIntent() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val cancelled = h.owner.accountRemoval
            h.owner.cancel(); h.phase(ConnectionPhase.CANCELLED)
            h.owner.signOut(cancelled); h.owner.commandsSettled()
            assertNotNull(h.persistence.durable)
            assertEquals(0, h.journal.deletions)
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            val edit = h.owner.accountName.value!!.edit!!
            val confirmation = h.owner.accountRemoval
            val gate = ControlledGate(); h.journal.nameGate = gate
            try {
                h.owner.renameAccount(edit, "Synthetic admitted removal label")
                gate.awaitEntered()
                h.owner.signOut(confirmation); h.owner.commandsSettled()
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertNull(h.owner.accountName.value)
                h.owner.signOut(confirmation); h.owner.connect(); h.owner.commandsSettled()
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
            } finally { gate.release(); h.journal.nameGate = null }
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.journal.name)
            assertNull(h.persistence.durable)
            assertEquals(2, h.journal.deletions) // Explicit replacement then confirmed removal.
        }
    }

    @Test fun confirmationFromClosedOwnerCannotRemoveFreshRestoredLifetime() = runBlocking {
        val journal = LifetimeJournal()
        val persistence = FakeCredentialPersistence()
        lateinit var old: AccountRemoval
        LifetimeFixture(journal, persistence).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            seed(h)
            old = h.owner.accountRemoval
        }
        LifetimeFixture(journal, persistence, seed = false).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val durable = persistence.durable
            h.owner.signOut(old); h.owner.commandsSettled()
            assertSame(durable, persistence.durable)
            assertEquals(ConnectionPhase.RESTORED, h.owner.state.value.phase)
            assertNotNull(journal.name)
            assertFalse(journal.entries.isEmpty())
            assertEquals(0, journal.deletions)
        }
    }

    private fun seed(h: LifetimeFixture) {
        h.journal.name = AccountDisplayName.from("Synthetic removal label")
        val sample = WindowHistory.append(h.journal.cursor, SyntheticHistory.admission(h.journal.cursor)) as HistoryReduction.Applied
        h.journal.cursor = sample.cursor
        h.journal.entries += sample.entry
    }
}
