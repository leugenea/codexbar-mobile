package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Existing process topology with test-only gates around actual detached SQLite read results. */
internal class HistoryIsolationFixture {
    val native = HistoryNavigationFixture()
    val reads = CopyOnWriteArrayList<ReadReceipt>()
    val publications = CopyOnWriteArrayList<HistoryGraphSnapshot>()
    private val gates = CopyOnWriteArrayList<HistoryQueryGate>()
    private val nextRead = AtomicReference<HistoryQueryGate?>()
    private val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @Volatile var failRead = false
    var limits = HistoryStorageLimits()
    val owner get() = native.owner
    val transport get() = native.transport

    fun prepare() {
        native.prepare()
        NativeConnection.factory = { createOwner() }
    }

    private fun createOwner(): ConnectionController {
        val credentials = KeystoreCredentialStore(native.context, NativeConnection.session(native.context))
        val hooks = object : HistoryStorageHooks {
            override fun beforeRead() { if (failRead) throw java.io.IOException() }
        }
        val sqlite = SQLiteHistoryLifetimeStorage(SQLiteHistoryStore(File(native.root, "usage-history"), limits, hooks))
        val storage = object : HistoryLifetimeStorage by sqlite {
            override fun activate(partition: HistoryPartition) = wrap(sqlite.activate(partition))
            override fun restore() = wrap(sqlite.restore())
        }
        return ConnectionController(credentials,
            DeviceCodeAuthenticator(transport, credentials, transport.clock, transport::pause),
            NativeFeasibilityReader(transport, transport.clock, transport::pause),
            CoroutineScope(SupervisorJob() + Dispatchers.IO), refreshClock = transport.clock,
            history = HistoryLifetimeCoordinator(storage)).also { current ->
                watcher.launch { current.historySnapshots.collect { publications += it } }
            }
    }

    private fun wrap(result: HistoryLifetimeOutcome): HistoryLifetimeOutcome {
        if (result !is HistoryLifetimeOutcome.Bound) return result
        val access = result.access
        return HistoryLifetimeOutcome.Bound(object : HistoryRuntimeAccess by access {
            override fun read(query: HistoryReadQuery): HistoryReadOutcome {
                val gate = nextRead.getAndSet(null)
                val outcome = access.read(query)
                val receipt = ReadReceipt(access, query, outcome)
                reads += receipt
                gate?.pause(receipt)
                return outcome
            }
        })
    }

    /** Registered before any assertions; release is idempotent in finally and cleanup. */
    fun holdNextRead(step: String): HistoryQueryGate = HistoryQueryGate(step).also {
        gates += it
        check(nextRead.compareAndSet(null, it))
    }

    fun capability(): HistoryRuntimeAccess {
        val ready = owner.session.snapshot() as? SessionResult.Ready ?: throw AssertionError("isolation session not ready")
        return owner.historyCapability(ready.envelope.generation) ?: throw AssertionError("isolation access: ${owner.historyAvailability}")
    }

    fun settled(step: String) {
        try { runBlocking { withTimeout(5_000) { owner.commandsSettled() } } }
        catch (error: TimeoutCancellationException) { throw AssertionError("$step: owner command receipt timed out", error) }
    }

    fun cleanup() {
        gates.forEach { it.release() }
        transport.releaseHeld()
        watcher.cancel()
        native.cleanup()
    }

    data class ReadReceipt(val access: HistoryRuntimeAccess, val query: HistoryReadQuery, val outcome: HistoryReadOutcome)
}

internal class HistoryQueryGate(private val step: String) {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    @Volatile var receipt: HistoryIsolationFixture.ReadReceipt? = null
        private set
    @Volatile var returned = false
        private set

    fun pause(value: HistoryIsolationFixture.ReadReceipt) {
        receipt = value
        entered.countDown()
        check(released.await(20, TimeUnit.SECONDS)) { "$step: actual SQLite result still held; query=${value.query}" }
        returned = true
    }

    fun reached(): Boolean = entered.count == 0L
    fun release() { released.countDown() }
}
