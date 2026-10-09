package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Synthetic journal only: real owner/A3/auth/A10 topology, not native durability evidence. */
internal class LifetimeJournal {
    enum class Phase { EMPTY, STAGED, ACTIVE, DELETING }
    @Volatile var phase = Phase.ACTIVE
    @Volatile var partition = HistoryPartition(UUID.randomUUID())
    var cursor = HistoryCursor(partition)
    val entries = mutableListOf<HistoryEntry>()
    @Volatile var failDelete = false
    @Volatile var failStage = false
    @Volatile var failActivate = false
    var deleteGate: ControlledGate? = null
    var stageGate: ControlledGate? = null
    var activateGate: ControlledGate? = null
    var restoreGate: ControlledGate? = null
    var active: Access? = null
    var deletions = 0
    var appendFailure: HistoryAppendOutcome? = null
    var readFailure: HistoryReadOutcome? = null
    var throwRead = false
    val admissions = mutableListOf<HistoryAdmission>()

    fun storage(): HistoryLifetimeStorage = object : HistoryLifetimeStorage {
        override fun stage(): HistoryPartitionOutcome {
            stageGate?.pause()
            if (failStage) return HistoryPartitionOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE)
            check(phase == Phase.EMPTY)
            partition = HistoryPartition(UUID.randomUUID())
            cursor = HistoryCursor(partition)
            phase = Phase.STAGED
            return HistoryPartitionOutcome.Staged(partition)
        }
        override fun activate(partition: HistoryPartition): HistoryLifetimeOutcome {
            activateGate?.pause()
            if (failActivate) return HistoryLifetimeOutcome.Unavailable
            check(this@LifetimeJournal.partition == partition && phase == Phase.STAGED)
            phase = Phase.ACTIVE
            return bind()
        }
        override fun restore(): HistoryLifetimeOutcome {
            restoreGate?.pause()
            return when (phase) {
                Phase.ACTIVE -> bind()
                Phase.DELETING -> HistoryLifetimeOutcome.RemovalRequired
                Phase.STAGED -> { entries.clear(); phase = Phase.EMPTY; HistoryLifetimeOutcome.Unavailable }
                Phase.EMPTY -> HistoryLifetimeOutcome.Unavailable
            }
        }
        override fun reserveDeletion(): HistoryDeletion {
            active?.revoke()
            return HistoryDeletion {
                phase = Phase.DELETING
                deletions++
                deleteGate?.pause()
                if (failDelete) HistoryDeleteOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE)
                else { entries.clear(); phase = Phase.EMPTY; HistoryDeleteOutcome.Deleted }
            }
        }
        override fun close() { active?.revoke() }
    }

    private fun bind(): HistoryLifetimeOutcome = Access(partition).also { active?.revoke(); active = it }.let(HistoryLifetimeOutcome::Bound)

    inner class Access(override val partition: HistoryPartition) : HistoryRuntimeAccess {
        @Volatile private var revoked = false
        override fun revoke() { revoked = true }
        override fun append(admission: HistoryAdmission): HistoryAppendOutcome {
            if (revoked) return HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
            admissions += admission
            appendFailure?.let { return it }
            return when (val reduced = WindowHistory.append(cursor, admission)) {
                is HistoryReduction.Applied -> {
                    cursor = reduced.cursor; entries += reduced.entry; HistoryAppendOutcome.Stored(reduced.entry)
                }
                is HistoryReduction.AlreadyAdmitted -> HistoryAppendOutcome.AlreadyAdmitted(reduced.id)
                is HistoryReduction.Rejected -> HistoryAppendOutcome.Rejected(reduced.reason)
            }
        }
        override fun read(query: HistoryReadQuery): HistoryReadOutcome {
            if (revoked) return HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
            if (throwRead) throw IllegalStateException("synthetic read category")
            readFailure?.let { return it }
            val remaining = entries.filter { it.id.ordinal > (query.after?.ordinal ?: 0) }
            return HistoryReadOutcome.Ready(HistoryReadSnapshot(query, remaining.take(query.limit),
                cursor.lastOrdinal.takeIf { it > 0 }?.let(::ObservationId), remaining.size > query.limit))
        }
        override fun delete(partition: HistoryPartition): HistoryDeleteOutcome = HistoryDeleteOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
    }
}

internal class LifetimeFixture(
    val journal: LifetimeJournal = LifetimeJournal(),
    val persistence: FakeCredentialPersistence = FakeCredentialPersistence(),
    waitMillis: Long = 5_000,
    seed: Boolean = true,
) : AutoCloseable {
    val fake = AuthFake()
    val clock = AuthClock()
    @Volatile private var binding: SessionGeneration? = null
    private val rebound = object : CredentialPersistence by persistence {
        override fun read(): CredentialResult<CredentialEnvelope> {
            persistence.readFailure?.let { return CredentialResult.Failure(it) }
            return persistence.durable?.let {
                CredentialResult.Success(CredentialEnvelope(requireNotNull(binding), it.accessToken, it.refreshToken))
            } ?: CredentialResult.Failure(CredentialFailure.MISSING)
        }
    }
    val store = SerializedCredentialStore(rebound, activate = { binding = it })
    val history = HistoryLifetimeCoordinator(journal.storage())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        if (seed && persistence.durable == null) store.replace(syntheticEnvelope(store.openSession()), CredentialCancellation())
        respond()
    }
    val owner = ConnectionController(store, DeviceCodeAuthenticator(fake, store, clock, clock::pause),
        NativeFeasibilityReader(fake, clock, clock::pause), scope, storageWaitMillis = waitMillis, history = history)
    fun respond() {
        fake.respond = { call -> call.reply(SyntheticAuth.response(when (call.request.url.encodedPath) {
            "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
            "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
            "/oauth/token" -> SyntheticAuth.TOKENS
            else -> "{}"
        })) }
    }
    suspend fun phase(expected: ConnectionPhase) {
        try { withTimeout(5_000) { owner.state.first {
            it.phase == expected && (expected != ConnectionPhase.OBSERVED || !it.refresh.refreshing)
        } } } catch (_: TimeoutCancellationException) {
            throw AssertionError("lifetime $expected: last state=${owner.state.value}")
        }
    }
    fun capability(): HistoryRuntimeAccess {
        val ready = owner.session.snapshot() as? SessionResult.Ready ?: throw AssertionError("adopted session required")
        return owner.historyCapability(ready.envelope.generation) ?: throw AssertionError("history capability: ${owner.historyAvailability}")
    }
    override fun close() {
        journal.deleteGate?.release(); journal.stageGate?.release(); journal.activateGate?.release(); journal.restoreGate?.release()
        runBlocking { withTimeout(5_000) { owner.shutdown() } }
    }
}
