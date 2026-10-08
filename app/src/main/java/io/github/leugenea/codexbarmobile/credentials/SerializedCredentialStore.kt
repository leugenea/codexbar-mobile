package io.github.leugenea.codexbarmobile.credentials

import java.util.concurrent.CancellationException

/**
 * Pure ownership/atomic-publication kernel with an independently owned durable-removal
 * executor. No credentials or platform storage are supplied here. Adapter operations
 * must not reenter this store or call user callbacks.
 * Staging is serialized with other replacements, but outside the ownership lock so logout
 * and session replacement can invalidate an uncooperative staging worker immediately.
 */
class SerializedCredentialStore(
    private val persistence: CredentialPersistence,
    // Trusted capability-only rebinding: no I/O, suspension or observer callbacks.
    private val activate: (SessionGeneration?) -> Unit = {},
    private val removalExecutor: java.util.concurrent.Executor = durableRemovals,
    storageWaitMillis: Long = STORAGE_WAIT_MILLIS,
) : CredentialStore {
    private val namespace = LocalCredentialNamespace()
    override val ownership = SessionOwnership(storageWaitMillis)
    private val replacements = Any()

    override fun openSession(): SessionGeneration = ownership.serialized {
        SessionGeneration(namespace).also { activate(it); ownership.activate(it) }
    }

    override fun admitCommandRemoval(replacement: Boolean): CredentialRemoval = ownership.serialized {
        val successor = if (replacement) SessionGeneration(namespace) else null
        activate(successor)
        val admitted = ownership.beginCommandRemoval(successor)
        val removal = Removal(admitted, successor)
        ownership.deferRemoval { removal.start() }
        CredentialRemoval(removal, successor)
    }

    override fun isActive(generation: SessionGeneration): Boolean = ownership.isActive(generation)

    override fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope> {
        if (!isActive(generation)) return stale()
        return ownership.settled(generation) {
            if (!ownership.isActive(generation)) return@settled stale()
            when (val result = safely(CredentialFailure.CORRUPT) { persistence.read() }) {
                is CredentialResult.Failure -> result
                is CredentialResult.Success -> if (result.value.generation === generation) result else stale()
            }
        }
    }

    override fun replace(
        envelope: CredentialEnvelope,
        cancellation: CredentialCancellation,
    ): CredentialResult<CredentialEnvelope> = synchronized(replacements) {
        val rejected = ownership.settled(envelope.generation) { rejection(envelope.generation, cancellation) ?: CredentialResult.Success(Unit) }
        if (rejected is CredentialResult.Failure) return@synchronized rejected
        when (val staged = safely(CredentialFailure.FAILED_WRITE) { persistence.prepare(envelope) }) {
            is CredentialResult.Failure -> ownership.serialized {
                rejection(envelope.generation, cancellation) ?: staged
            }
            is CredentialResult.Success -> commit(envelope, staged.value, cancellation)
        }
    }

    override fun beginRotation(generation: SessionGeneration): CredentialResult<Unit> = ownership.serialized {
        if (!ownership.isActive(generation)) stale() else CredentialResult.Success(Unit)
    }

    override fun finishRotation(generation: SessionGeneration): CredentialResult<Unit> = beginRotation(generation)

    override fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion> = admitRemoval(generation, null)

    private fun admitRemoval(generation: SessionGeneration, successor: SessionGeneration?): CredentialResult<CredentialDeletion> = ownership.serialized {
        if (!ownership.isActive(generation)) return@serialized stale()
        activate(successor)
        val admitted = requireNotNull(ownership.beginDeletion(generation, successor))
        CredentialResult.Success(Removal(admitted))
    }

    override fun delete(generation: SessionGeneration): CredentialResult<Unit> = complete(admitDeletion(generation))

    private fun complete(admitted: CredentialResult<CredentialDeletion>): CredentialResult<Unit> = when (admitted) {
        is CredentialResult.Failure -> admitted
        is CredentialResult.Success -> admitted.value.complete()
    }

    private inner class Removal(
        private val admitted: SessionOwnership.Deletion,
        private val successor: SessionGeneration? = null,
    ) : CredentialDeletion {
        private val claimed = java.util.concurrent.atomic.AtomicBoolean()
        @Volatile private var result: CredentialResult<Unit> = CredentialResult.Failure(CredentialFailure.FAILED_WRITE)
        override fun start() {
            if (!claimed.compareAndSet(false, true)) return
            // A predecessor completion schedules this runner; no thread waits for prior I/O.
            val previous = admitted.previous
            if (previous == null) schedule() else previous.whenComplete { _, _ -> schedule() }
        }

        private fun schedule() {
            try { removalExecutor.execute(::remove) }
            catch (_: Exception) { finish(CredentialResult.Failure(CredentialFailure.FAILED_WRITE)) }
        }

        private fun remove() = finish(safely(CredentialFailure.FAILED_WRITE) { persistence.delete() })

        private fun finish(saved: CredentialResult<Unit>) {
            ownership.serialized {
                if (saved is CredentialResult.Failure && successor != null && ownership.isActive(successor)) {
                    activate(null)
                    ownership.activate(null)
                }
            }
            result = saved
            ownership.finishDeletion(admitted, saved is CredentialResult.Success)
        }

        override fun complete(): CredentialResult<Unit> {
            start()
            return if (admitted.completion.waitStorage(ownership.storageWaitMillis) == null)
                CredentialResult.Failure(CredentialFailure.FAILED_WRITE) else result
        }

        override suspend fun await(): CredentialResult<Unit> {
            start()
            return if (admitted.completion.awaitStorage(ownership.storageWaitMillis) == null)
                CredentialResult.Failure(CredentialFailure.FAILED_WRITE) else result
        }
    }

    internal companion object {
        val durableRemovals = java.util.concurrent.Executors.newCachedThreadPool { task ->
            Thread(task, "credential-removal").apply { isDaemon = true }
        }
    }

    override fun replaceSession(generation: SessionGeneration): CredentialResult<SessionGeneration> {
        // Reserve the successor before deletion; a later owner can displace this exact right.
        val successor = ownership.serialized {
            if (!ownership.isActive(generation)) return stale()
            SessionGeneration(namespace)
        }
        val deleted = complete(admitRemoval(generation, successor))
        return ownership.serialized {
            when {
                deleted is CredentialResult.Failure -> {
                    if (ownership.isActive(successor)) { activate(null); ownership.activate(null) }
                    deleted
                }
                !ownership.isActive(successor) -> stale()
                else -> CredentialResult.Success(successor)
            }
        }
    }

    private fun commit(
        envelope: CredentialEnvelope,
        staged: PreparedCredentialWrite,
        cancellation: CredentialCancellation,
    ): CredentialResult<CredentialEnvelope> {
        try {
            return ownership.settled(envelope.generation) {
                rejection(envelope.generation, cancellation) ?: cancellation.commit {
                    when (val result = safely(CredentialFailure.FAILED_WRITE) { staged.commit() }) {
                        is CredentialResult.Failure -> result
                        is CredentialResult.Success -> CredentialResult.Success(envelope)
                    }
                }
            }
        } finally {
            // Cleanup cannot change the durable decision, and raw cleanup failures stay private.
            safely(CredentialFailure.FAILED_WRITE) { staged.discard(); CredentialResult.Success(Unit) }
        }
    }

    private fun rejection(
        generation: SessionGeneration,
        cancellation: CredentialCancellation,
    ): CredentialResult.Failure? = when {
        !ownership.isActive(generation) -> stale()
        cancellation.isCancelled() -> CredentialResult.Failure(CredentialFailure.CANCELLED)
        else -> null
    }

    private fun stale(): CredentialResult.Failure = CredentialResult.Failure(CredentialFailure.STALE_GENERATION)

    private fun <T> safely(fallback: CredentialFailure, action: () -> CredentialResult<T>): CredentialResult<T> = try {
        action()
    } catch (_: CancellationException) {
        CredentialResult.Failure(CredentialFailure.CANCELLED)
    } catch (_: Exception) {
        CredentialResult.Failure(fallback)
    }
}
