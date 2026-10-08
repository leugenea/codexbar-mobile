package io.github.leugenea.codexbarmobile.credentials

import java.util.concurrent.CancellationException

/** A3 atomic commit guard. Runtime scheduling belongs to the one process session owner. */
class SerializedCredentialStore(
    private val persistence: CredentialPersistence,
    private val activate: (SessionGeneration?) -> Unit = {},
) : CredentialStore {
    private val namespace = LocalCredentialNamespace()
    private val commitLock = Any()
    private val replacements = Any()
    @Volatile private var current: SessionGeneration? = null
    private val io = Any()

    override fun openSession(): SessionGeneration = synchronized(commitLock) {
        SessionGeneration(namespace).also { current = it; activate(it) }
    }

    override fun isActive(generation: SessionGeneration): Boolean = current === generation

    override fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope> {
        if (current !== generation) return stale()
        val result = safely(CredentialFailure.CORRUPT) { persistence.read() }
        if (current !== generation) return stale()
        return when (result) {
            is CredentialResult.Failure -> result
            is CredentialResult.Success -> if (result.value.generation === generation) result else stale()
        }
    }

    override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> =
        synchronized(replacements) {
            val rejected = synchronized(commitLock) { rejection(envelope.generation, cancellation) }
            if (rejected != null) return@synchronized rejected
            when (val prepared = safely(CredentialFailure.FAILED_WRITE) { persistence.prepare(envelope) }) {
                is CredentialResult.Failure -> synchronized(commitLock) { rejection(envelope.generation, cancellation) ?: prepared }
                is CredentialResult.Success -> commit(envelope, prepared.value, cancellation)
            }
        }

    private fun commit(envelope: CredentialEnvelope, staged: PreparedCredentialWrite,
        cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> {
        try {
            return synchronized(io) {
                // The irreversible decision is short. Runtime revocation never waits on fsync;
                // removal takes this I/O lock afterward and wins before terminal publication.
                val admitted = synchronized(commitLock) {
                    rejection(envelope.generation, cancellation) ?: cancellation.commit { CredentialResult.Success(Unit) }
                }
                if (admitted is CredentialResult.Failure) admitted else save(envelope, staged)
            }
        } finally { safely(CredentialFailure.FAILED_WRITE) { staged.discard(); CredentialResult.Success(Unit) } }
    }

    private fun save(envelope: CredentialEnvelope, staged: PreparedCredentialWrite): CredentialResult<CredentialEnvelope> =
        when (val saved = safely(CredentialFailure.FAILED_WRITE) { staged.commit() }) {
            is CredentialResult.Failure -> saved
            is CredentialResult.Success -> CredentialResult.Success(envelope)
        }

    override fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion> = synchronized(commitLock) {
        if (current !== generation) return@synchronized stale()
        current = null
        activate(null)
        CredentialResult.Success(Removal())
    }

    /** Reserved by the owner before dispatching I/O; repeated completion cannot remove a successor. */
    private inner class Removal : CredentialDeletion {
        private var result: CredentialResult<Unit>? = null
        override fun complete(): CredentialResult<Unit> = synchronized(this) {
            result ?: synchronized(io) { safely(CredentialFailure.FAILED_WRITE) { persistence.delete() } }.also { result = it }
        }
    }

    override fun delete(generation: SessionGeneration): CredentialResult<Unit> = when (val admitted = admitDeletion(generation)) {
        is CredentialResult.Failure -> admitted
        is CredentialResult.Success -> admitted.value.complete()
    }

    override fun replaceSession(generation: SessionGeneration): CredentialResult<SessionGeneration> = when (val deleted = delete(generation)) {
        is CredentialResult.Failure -> deleted
        is CredentialResult.Success -> synchronized(commitLock) {
            if (current != null) stale() else CredentialResult.Success(openSession())
        }
    }

    override fun beginRotation(generation: SessionGeneration): CredentialResult<Unit> = synchronized(commitLock) {
        if (current === generation) CredentialResult.Success(Unit) else stale()
    }
    override fun finishRotation(generation: SessionGeneration) = beginRotation(generation)

    private fun rejection(generation: SessionGeneration, cancellation: CredentialCancellation): CredentialResult.Failure? = when {
        current !== generation -> stale()
        cancellation.isCancelled() -> CredentialResult.Failure(CredentialFailure.CANCELLED)
        else -> null
    }
    private fun stale() = CredentialResult.Failure(CredentialFailure.STALE_GENERATION)
    private fun <T> safely(fallback: CredentialFailure, action: () -> CredentialResult<T>): CredentialResult<T> = try {
        action()
    } catch (_: CancellationException) { CredentialResult.Failure(CredentialFailure.CANCELLED)
    } catch (_: Exception) { CredentialResult.Failure(fallback) }
}
