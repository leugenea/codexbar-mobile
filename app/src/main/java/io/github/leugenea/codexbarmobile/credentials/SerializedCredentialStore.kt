package io.github.leugenea.codexbarmobile.credentials

import java.util.concurrent.CancellationException

/**
 * Pure ownership/atomic-publication kernel. No credentials, platform storage or scheduling
 * are supplied here. Adapter operations must not reenter this store or call user callbacks.
 * Staging is serialized with other replacements, but outside the ownership lock so logout
 * and session replacement can invalidate an uncooperative staging worker immediately.
 */
class SerializedCredentialStore(private val persistence: CredentialPersistence) : CredentialStore {
    private val namespace = LocalCredentialNamespace()
    private val ownership = Any()
    private val replacements = Any()
    private var active: SessionGeneration? = null

    override fun openSession(): SessionGeneration = synchronized(ownership) {
        SessionGeneration(namespace).also { active = it }
    }

    override fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope> = synchronized(ownership) {
        if (active !== generation) return@synchronized stale()
        when (val result = safely(CredentialFailure.CORRUPT) { persistence.read() }) {
            is CredentialResult.Failure -> result
            is CredentialResult.Success -> if (result.value.generation === generation) result else stale()
        }
    }

    override fun replace(
        envelope: CredentialEnvelope,
        cancellation: CredentialCancellation,
    ): CredentialResult<CredentialEnvelope> = synchronized(replacements) {
        val rejected = synchronized(ownership) { rejection(envelope.generation, cancellation) }
        if (rejected != null) return@synchronized rejected
        when (val staged = safely(CredentialFailure.FAILED_WRITE) { persistence.prepare(envelope) }) {
            is CredentialResult.Failure -> synchronized(ownership) {
                rejection(envelope.generation, cancellation) ?: staged
            }
            is CredentialResult.Success -> commit(envelope, staged.value, cancellation)
        }
    }

    override fun delete(generation: SessionGeneration): CredentialResult<Unit> = synchronized(ownership) {
        if (active !== generation) return@synchronized stale()
        active = null
        safely(CredentialFailure.FAILED_WRITE) { persistence.delete() }
    }

    private fun commit(
        envelope: CredentialEnvelope,
        staged: PreparedCredentialWrite,
        cancellation: CredentialCancellation,
    ): CredentialResult<CredentialEnvelope> {
        try {
            return synchronized(ownership) {
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
        active !== generation -> stale()
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
