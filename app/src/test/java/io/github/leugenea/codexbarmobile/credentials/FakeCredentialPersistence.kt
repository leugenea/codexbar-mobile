package io.github.leugenea.codexbarmobile.credentials

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Original synthetic I/O seam; not Keystore, a file system or native durability evidence. */
internal class FakeCredentialPersistence : CredentialPersistence {
    @Volatile var durable: CredentialEnvelope? = null
    var readFailure: CredentialFailure? = null
    var prepareFailure: CredentialFailure? = null
    var commitFailure: CredentialFailure? = null
    var deleteFailure: CredentialFailure? = null
    var readException: Exception? = null
    var prepareException: Exception? = null
    var commitException: Exception? = null
    var deleteException: Exception? = null
    var discardException: Exception? = null
    var stagingGate: ControlledGate? = null
    var beforeCommit: (() -> Unit)? = null
    var beforeDelete: (() -> Unit)? = null
    var prepareCount = 0
    var commitCount = 0
    var deleteCount = 0
    var discardCount = 0

    override fun read(): CredentialResult<CredentialEnvelope> {
        readException?.let { throw it }
        readFailure?.let { return CredentialResult.Failure(it) }
        return durable?.let { CredentialResult.Success(it) }
            ?: CredentialResult.Failure(CredentialFailure.MISSING)
    }

    override fun prepare(envelope: CredentialEnvelope): CredentialResult<PreparedCredentialWrite> {
        prepareCount++
        stagingGate?.pause()
        prepareException?.let { throw it }
        prepareFailure?.let { return CredentialResult.Failure(it) }
        return CredentialResult.Success(object : PreparedCredentialWrite {
            override fun commit(): CredentialResult<Unit> {
                commitCount++
                beforeCommit?.invoke()
                commitException?.let { throw it }
                commitFailure?.let { return CredentialResult.Failure(it) }
                durable = envelope
                return CredentialResult.Success(Unit)
            }

            override fun discard() {
                discardCount++
                discardException?.let { throw it }
            }
        })
    }

    override fun delete(): CredentialResult<Unit> {
        deleteCount++
        beforeDelete?.invoke()
        deleteException?.let { throw it }
        deleteFailure?.let { return CredentialResult.Failure(it) }
        durable = null
        return CredentialResult.Success(Unit)
    }
}

/** Positive rendezvous; five seconds is only a labeled deadlock guard, never an oracle. */
internal class ControlledGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun pause() {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "Synthetic staging release timed out" }
    }

    fun awaitEntered() {
        check(entered.await(5, TimeUnit.SECONDS)) { "Synthetic staging entry timed out" }
    }

    fun release() = released.countDown()
}

internal class ControlledWorker<T>(action: () -> T) : AutoCloseable {
    private val task = FutureTask(action)
    private val worker = Thread(task, "synthetic-credential-writer").apply { isDaemon = true; start() }
    val isDone: Boolean get() = task.isDone
    fun result(): T = task.get(5, TimeUnit.SECONDS)
    override fun close() {
        worker.join(5000)
        check(!worker.isAlive) { "Synthetic writer did not settle" }
        // Rethrow failures from hooks on the test thread; do not swallow test assertions.
        result()
    }
}

internal fun syntheticEnvelope(generation: SessionGeneration, suffix: String = "initial", refresh: Boolean = true): CredentialEnvelope =
    CredentialEnvelope(
        generation,
        SensitiveValue.copyOf("synthetic-access-$suffix".toByteArray()),
        if (refresh) SensitiveValue.copyOf("synthetic-refresh-$suffix".toByteArray()) else null,
    )
