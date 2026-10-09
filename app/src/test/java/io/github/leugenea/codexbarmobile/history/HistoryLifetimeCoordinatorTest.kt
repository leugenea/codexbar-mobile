package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.auth.SyntheticAuth
import io.github.leugenea.codexbarmobile.credentials.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real single-owner coroutine topology; all protocol/persistence facts are explicitly synthetic. */
class HistoryLifetimeCoordinatorTest {
    @Test fun cleanRestoreAndRotationKeepPartitionButFreshLoginNeverJoins() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.capability()
            append(old)
            h.fake.respond = { call -> call.reply(SyntheticAuth.response(
                if (call.request.url.encodedPath == "/oauth/token")
                    """{"access_token":"synthetic-lifetime-rotated-access","refresh_token":"synthetic-lifetime-rotated-refresh"}""" else "{}")) }
            h.owner.readUsage(refreshSession = true)
            await("rotation request admitted") { h.fake.calls.any { it.request.url.encodedPath == "/oauth/token" } }
            h.phase(ConnectionPhase.OBSERVED)
            assertEquals(old.partition, h.capability().partition)
            assertEquals(1, page(h.capability()).entries.size)
            h.owner.signOut(); h.phase(ConnectionPhase.SIGNED_OUT)
            assertRevoked(old)
            assertTrue(h.journal.entries.isEmpty())
            h.respond()
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            assertNotEquals(old.partition, h.capability().partition)
            assertEquals(HistoryContent.EMPTY, page(h.capability()).content)
        }
    }

    @Test fun ordinaryCancelRevokesRuntimeButFreshOwnerRestoresContinuingHistory() = runBlocking {
        val journal = LifetimeJournal(); val persistence = FakeCredentialPersistence()
        LifetimeFixture(journal, persistence).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.capability(); append(old)
            h.owner.cancel(); h.phase(ConnectionPhase.CANCELLED)
            assertRevoked(old)
            assertNotNull(persistence.durable)
            assertEquals(0, journal.deletions)
        }
        LifetimeFixture(journal, persistence, seed = false).use { next ->
            next.phase(ConnectionPhase.RESTORED)
            assertEquals(journal.partition, next.capability().partition)
            assertEquals(1, page(next.capability()).entries.size)
            assertTrue(next.fake.calls.isEmpty())
        }
    }

    @Test fun holderShutdownRetiresWithoutDeletingContinuingLifetime() = runBlocking {
        val h = LifetimeFixture()
        h.phase(ConnectionPhase.RESTORED)
        val old = h.capability(); append(old)
        try { h.owner.shutdown(); assertRevoked(old); assertNotNull(h.persistence.durable) }
        finally { h.close() }
        assertEquals(LifetimeJournal.Phase.ACTIVE, h.journal.phase)
        assertEquals(0, h.journal.deletions)
    }

    @Test fun foregroundLossPreservesLifetimeAndNoNewSamplingExists() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val access = h.capability(); append(access)
            val observer = Any()
            h.owner.usageForeground(observer, false); h.owner.commandsSettled()
            assertRevoked(access)
            assertEquals(HistoryAvailability.UNAVAILABLE, h.owner.historyAvailability)
            assertNotNull(h.persistence.durable)
            h.owner.usageForeground(observer, true)
            await("foreground history re-adoption") { h.owner.historyAvailability == HistoryAvailability.AVAILABLE }
            assertNotSame(access, h.capability())
            assertEquals(access.partition, h.capability().partition)
            assertEquals(1, page(h.capability()).entries.size)
            assertEquals(0, h.journal.deletions)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun heldHistoryCleanupDoesNotBlockCredentialsOrOwnerLaneAndTimeoutIsTruthful() = runBlocking {
        LifetimeFixture(waitMillis = 50).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.capability(); append(old)
            val gate = ControlledGate(); h.journal.deleteGate = gate
            try {
                h.owner.signOut(); gate.awaitEntered()
                h.phase(ConnectionPhase.FAILED)
                assertEquals(ConnectionProblem.STORAGE, h.owner.state.value.problem)
                withTimeout(2_000) { h.owner.commandsSettled() }
                await("credential removal while history held") { h.persistence.durable == null }
                assertRevoked(old)
                h.owner.connect(); h.owner.readUsage(); h.owner.cancel(); h.owner.commandsSettled()
                assertTrue(h.fake.calls.isEmpty())
                assertEquals(LifetimeJournal.Phase.DELETING, h.journal.phase)
            } finally { gate.release() }
            await("actual history cleanup") { h.journal.phase == LifetimeJournal.Phase.EMPTY }
            h.owner.signOut(); h.phase(ConnectionPhase.SIGNED_OUT)
            assertTrue(h.journal.entries.isEmpty())
        }
    }

    @Test fun failedHistoryRemovalStillDeletesCredentialsAndRetryCannotReusePartition() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.capability(); append(old)
            h.journal.failDelete = true
            h.owner.signOut(); h.phase(ConnectionPhase.FAILED)
            assertNull(h.persistence.durable)
            assertRevoked(old)
            assertEquals(LifetimeJournal.Phase.DELETING, h.journal.phase)
            h.journal.failDelete = false
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            assertNotEquals(old.partition, h.capability().partition)
            assertEquals(HistoryContent.EMPTY, page(h.capability()).content)
        }
    }

    @Test fun failedCredentialRemovalStillAttemptsHistoryAndNoFalseCombinedSuccess() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED); append(h.capability())
            h.persistence.deleteFailure = CredentialFailure.FAILED_WRITE
            h.owner.signOut(); h.phase(ConnectionPhase.FAILED)
            assertNotNull(h.persistence.durable)
            assertEquals(LifetimeJournal.Phase.EMPTY, h.journal.phase)
            assertEquals(HistoryAvailability.UNAVAILABLE, h.owner.historyAvailability)
            h.persistence.deleteFailure = null
            h.owner.signOut(); h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
        }
    }

    @Test fun terminalRefreshOutlivesForegroundWaiterAndAwaitsBothRemovals() = runBlocking {
        LifetimeFixture(waitMillis = 50).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.capability(); append(old)
            val sent = CompletableDeferred<io.github.leugenea.codexbarmobile.auth.AuthFake.Call>()
            h.fake.respond = { sent.complete(it) }
            val observer = Any()
            h.owner.usageForeground(observer, true); h.owner.commandsSettled()
            h.owner.readUsage(refreshSession = true)
            val request = withTimeout(5_000) { sent.await() }
            val gate = ControlledGate(); h.journal.deleteGate = gate
            try {
                h.owner.usageForeground(observer, false); h.owner.commandsSettled()
                await("foreground read waiter canceled before terminal refresh") { !h.owner.state.value.refresh.refreshing }
                assertFalse(request.cancelled)
                request.reply(SyntheticAuth.response("{}", 401))
                gate.awaitEntered()
                h.phase(ConnectionPhase.FAILED)
                assertRevoked(old)
                await("terminal credential removal") { h.persistence.durable == null }
                h.owner.connect(); h.owner.commandsSettled()
                assertEquals(1, h.fake.calls.size)
            } finally { gate.release() }
            await("terminal history removal") { h.journal.phase == LifetimeJournal.Phase.EMPTY }
        }
    }

    @Test fun interruptedRemovalOnFreshOwnerPurgesCleanLookingCredentials() = runBlocking {
        val journal = LifetimeJournal().apply { phase = LifetimeJournal.Phase.DELETING }
        LifetimeFixture(journal).use { h ->
            h.phase(ConnectionPhase.REAUTH_REQUIRED)
            assertNull(h.persistence.durable)
            assertEquals(LifetimeJournal.Phase.EMPTY, journal.phase)
            assertTrue(h.fake.calls.isEmpty())
            assertEquals(HistoryAvailability.UNAVAILABLE, h.owner.historyAvailability)
        }
    }

    @Test fun stagedCrashCutNeverAdoptsHistoryWithContinuingProtectedCredentials() = runBlocking {
        val journal = LifetimeJournal().apply { phase = LifetimeJournal.Phase.STAGED }
        LifetimeFixture(journal).use { h ->
            h.phase(ConnectionPhase.RESTORED)
            assertNotNull(h.persistence.durable)
            assertEquals(HistoryAvailability.STORAGE_FAILURE, h.owner.historyAvailability)
            assertEquals(LifetimeJournal.Phase.EMPTY, journal.phase)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun keyLossCorruptAndMissingRestoresPurgeOrphanHistory() = runBlocking {
        for (reason in listOf(CredentialFailure.KEY_LOST, CredentialFailure.CORRUPT, CredentialFailure.MISSING)) {
            val persistence = FakeCredentialPersistence().apply { readFailure = reason }
            LifetimeFixture(persistence = persistence).use { h ->
                h.phase(if (reason == CredentialFailure.MISSING) ConnectionPhase.IDLE else ConnectionPhase.REAUTH_REQUIRED)
                assertNull(persistence.durable)
                assertEquals(LifetimeJournal.Phase.EMPTY, h.journal.phase)
                assertTrue(h.fake.calls.isEmpty())
            }
        }
    }

    @Test fun historyStageAndActivationFailureNeverReplaceLiveQuotaWithFabricatedHistory() = runBlocking {
        for (stage in listOf(false, true)) LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            h.journal.failStage = stage; h.journal.failActivate = !stage
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            assertNotNull(h.persistence.durable)
            assertEquals(HistoryAvailability.STORAGE_FAILURE, h.owner.historyAvailability)
            assertNotNull(h.owner.state.value.observations)
        }
    }

    @Test fun heldFreshStageIsRetiredBeforeOldCredentialSaveAndSuccessorAdmission() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.capability()
            val gate = ControlledGate(); h.journal.stageGate = gate
            try {
                h.owner.connect(); gate.awaitEntered()
                h.owner.signOut(); h.owner.commandsSettled()
                assertRevoked(old)
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertTrue(h.fake.calls.isEmpty())
            } finally { gate.release(); h.journal.stageGate = null }
            h.phase(ConnectionPhase.SIGNED_OUT)
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            val next = h.capability()
            assertNotEquals(old.partition, next.partition)
            assertRevoked(old)
            assertEquals(HistoryContent.EMPTY, page(next).content)
        }
    }

    @Test fun alreadyAdmittedCredentialCommitSettlesBeforeCombinedDeletionAndCannotBindOldHistory() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val old = h.capability(); append(old)
            val gate = ControlledGate(); h.persistence.beforeCommit = gate::pause
            try {
                h.owner.connect(); gate.awaitEntered()
                h.owner.signOut(); withTimeout(2_000) { h.owner.commandsSettled() }
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertRevoked(old)
                assertNotEquals(HistoryAvailability.AVAILABLE, h.owner.historyAvailability)
                await("history removal while admitted credential commit held") { h.journal.phase == LifetimeJournal.Phase.EMPTY }
            } finally { gate.release(); h.persistence.beforeCommit = null }
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertEquals(LifetimeJournal.Phase.EMPTY, h.journal.phase)
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            assertNotEquals(old.partition, h.capability().partition)
            assertEquals(HistoryContent.EMPTY, page(h.capability()).content)
        }
    }

    @Test fun heldActivationAfterDurableCredentialSaveCannotAdoptAfterRetirement() = runBlocking {
        LifetimeFixture().use { h ->
            h.phase(ConnectionPhase.RESTORED)
            val previous = h.capability(); append(previous)
            val gate = ControlledGate(); h.journal.activateGate = gate
            try {
                h.owner.connect(); gate.awaitEntered()
                assertNotNull(h.persistence.durable)
                h.owner.signOut(); h.owner.commandsSettled()
                assertEquals(ConnectionPhase.SIGNING_OUT, h.owner.state.value.phase)
                assertRevoked(previous)
                assertNotEquals(HistoryAvailability.AVAILABLE, h.owner.historyAvailability)
            } finally { gate.release(); h.journal.activateGate = null }
            h.phase(ConnectionPhase.SIGNED_OUT)
            assertNull(h.persistence.durable)
            assertTrue(h.journal.entries.isEmpty())
            h.owner.connect(); h.phase(ConnectionPhase.OBSERVED)
            assertNotEquals(previous.partition, h.capability().partition)
            assertEquals(HistoryContent.EMPTY, page(h.capability()).content)
        }
    }

    @Test fun foregroundLossDuringCleanRestorationCannotReenableRuntimeHistory() = runBlocking {
        val gate = ControlledGate()
        val journal = LifetimeJournal().apply { restoreGate = gate }
        LifetimeFixture(journal).use { h ->
            val observer = Any()
            try {
                gate.awaitEntered()
                h.owner.usageForeground(observer, false); h.owner.commandsSettled()
            } finally { gate.release(); journal.restoreGate = null }
            h.phase(ConnectionPhase.RESTORED)
            assertEquals(HistoryAvailability.UNAVAILABLE, h.owner.historyAvailability)
            assertNotNull(h.persistence.durable)
            assertEquals(0, journal.deletions)
            h.owner.usageForeground(observer, true)
            await("foreground re-adopts clean restoration") { h.owner.historyAvailability == HistoryAvailability.AVAILABLE }
            assertEquals(journal.partition, h.capability().partition)
            assertTrue(h.fake.calls.isEmpty())
        }
    }

    @Test fun staleGenerationCannotStageActivateRestoreOrCaptureAnotherLifetime() {
        val journal = LifetimeJournal()
        val store = SerializedCredentialStore(FakeCredentialPersistence())
        val old = store.openSession(); val current = store.openSession()
        HistoryLifetimeCoordinator(journal.storage()).use { coordinator ->
            coordinator.open(current)
            coordinator.restore(current)
            assertFalse(coordinator.stage(old))
            coordinator.activate(old); coordinator.restore(old)
            assertNull(coordinator.capability(old))
            assertNotNull(coordinator.capability(current))
            coordinator.pauseRuntime()
            coordinator.restore(current)
            assertNull(coordinator.capability(current))
            coordinator.resumeRuntime(); coordinator.restore(current)
            assertNotNull(coordinator.capability(current))
        }
    }

    private fun append(access: HistoryRuntimeAccess) {
        assertTrue(access.append(SyntheticHistory.admission(HistoryCursor(access.partition))) is HistoryAppendOutcome.Stored)
    }
    private fun page(access: HistoryRuntimeAccess) =
        (access.read(HistoryReadQuery(access.partition, 10)) as HistoryReadOutcome.Ready).snapshot
    private fun assertRevoked(access: HistoryRuntimeAccess) {
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), access.read(HistoryReadQuery(access.partition, 10)))
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), access.append(SyntheticHistory.admission(HistoryCursor(access.partition))))
    }
    private suspend fun await(step: String, predicate: () -> Boolean) {
        try { withTimeout(5_000) { while (!predicate()) delay(1) } }
        catch (_: TimeoutCancellationException) { throw AssertionError("$step: last state=false") }
    }
}
