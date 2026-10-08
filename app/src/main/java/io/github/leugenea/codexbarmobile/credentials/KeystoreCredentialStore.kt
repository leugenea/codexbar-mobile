package io.github.leugenea.codexbarmobile.credentials

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.lang.ref.WeakReference
import java.security.UnrecoverableKeyException
import java.util.UUID

/**
 * One durable LOCAL session slot. The caller must retain its random session identifier for
 * pending restoration and allocate a new identifier for a different session after logout.
 * It is not provider identity. openSession replaces the runtime capability, not that durable
 * session; restored credentials remain Unresolved and must not be advertised as an account.
 * All live instances for a slot share A3's ownership kernel. No UI or auth wiring is supplied.
 */
class KeystoreCredentialStore(context: Context, localSessionId: UUID) : CredentialStore {
    private val owner = sharedOwner(context, localSessionId)
    override val ownership: SessionOwnership get() = owner.ownership

    override fun openSession(): SessionGeneration = owner.openSession()
    override fun isActive(generation: SessionGeneration): Boolean = owner.isActive(generation)
    override fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope> = owner.read(generation)
    override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> =
        owner.kernel.replace(envelope, cancellation)
    override fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion> = owner.admitDeletion(generation)
    override fun delete(generation: SessionGeneration): CredentialResult<Unit> = owner.delete(generation)
    override fun replaceSession(generation: SessionGeneration) = owner.replaceSession(generation)
    override fun beginRotation(generation: SessionGeneration) = owner.beginRotation(generation)
    override fun finishRotation(generation: SessionGeneration) = owner.finishRotation(generation)

    internal companion object {
        private val owners = mutableMapOf<String, WeakReference<CredentialSlotOwner>>()
        fun file(context: Context, session: UUID): File = File(context.noBackupFilesDir, "credentials/$session/value.bin")
        fun alias(context: Context, session: UUID): String = "${context.packageName}.credentials.$session"

        private fun sharedOwner(context: Context, session: UUID): CredentialSlotOwner = synchronized(owners) {
            val file = file(context, session)
            val path = file.canonicalPath
            owners.entries.removeAll { it.value.get() == null }
            owners[path]?.get() ?: CredentialSlotOwner(ProtectedCredentialPersistence(
                AtomicCredentialFile(file), AndroidCredentialCipher(alias(context, session)), session, File(file.parentFile, "rotation-pending"),
            )).also { owners[path] = WeakReference(it) }
        }
    }
}

/** The kernel atomically rebinds capabilities; durable deletion never retains its runtime lane. */
internal class CredentialSlotOwner(private val persistence: ProtectedCredentialPersistence) : CredentialStore {
    internal val kernel = SerializedCredentialStore(persistence, persistence::activate)
    override val ownership: SessionOwnership get() = kernel.ownership

    override fun openSession(): SessionGeneration = kernel.openSession()
    override fun isActive(generation: SessionGeneration): Boolean = kernel.isActive(generation)

    override fun read(generation: SessionGeneration): CredentialResult<CredentialEnvelope> = kernel.read(generation)
    override fun replace(envelope: CredentialEnvelope, cancellation: CredentialCancellation): CredentialResult<CredentialEnvelope> =
        kernel.replace(envelope, cancellation)
    override fun admitDeletion(generation: SessionGeneration): CredentialResult<CredentialDeletion> = kernel.admitDeletion(generation)
    override fun delete(generation: SessionGeneration): CredentialResult<Unit> = kernel.delete(generation)
    override fun replaceSession(generation: SessionGeneration): CredentialResult<SessionGeneration> = kernel.replaceSession(generation)
    override fun beginRotation(generation: SessionGeneration): CredentialResult<Unit> = ownership.settled {
        when (val read = kernel.read(generation)) {
            is CredentialResult.Failure -> read
            is CredentialResult.Success -> persistence.rotation(generation, pending = true)
        }
    }
    override fun finishRotation(generation: SessionGeneration): CredentialResult<Unit> = ownership.settled {
        when (val checked = kernel.finishRotation(generation)) {
            is CredentialResult.Failure -> checked
            is CredentialResult.Success -> persistence.rotation(generation, pending = false)
        }
    }
}

internal class ProtectedCredentialPersistence(
    private val file: CredentialFile,
    private val cipher: CredentialCipher,
    private val session: UUID,
    private val rotationMarker: File? = null,
) : CredentialPersistence {
    private val io = Any()
    @Volatile private var generation: SessionGeneration? = null
    // Capability rebinding is callback-free and must never wait for deletion I/O.
    fun activate(generation: SessionGeneration?) { this.generation = generation }

    override fun read(): CredentialResult<CredentialEnvelope> = synchronized(io) {
        guarded(CredentialFailure.CORRUPT) {
            val current = generation ?: return@guarded failure(CredentialFailure.STALE_GENERATION)
            if (rotationMarker?.exists() == true) return@guarded failure(CredentialFailure.CORRUPT)
            if (!file.exists()) return@guarded failure(CredentialFailure.MISSING)
            val bytes = file.read()
            val plaintext = cipher.decrypt(CredentialBinaryFormat.sealed(bytes, session), CredentialBinaryFormat.header(session))
            try {
                CredentialResult.Success(CredentialBinaryFormat.envelope(plaintext, current))
            } finally { plaintext.fill(0) }
        }
    }

    override fun prepare(envelope: CredentialEnvelope): CredentialResult<PreparedCredentialWrite> {
        val encrypted = synchronized(io) {
            guarded(CredentialFailure.FAILED_WRITE) {
                if (generation !== envelope.generation) return@guarded failure(CredentialFailure.STALE_GENERATION)
                val plaintext = CredentialBinaryFormat.payload(envelope)
                try {
                    CredentialResult.Success(CredentialBinaryFormat.file(session, cipher.encrypt(
                        plaintext, CredentialBinaryFormat.header(session), file.exists(),
                    )))
                } finally { plaintext.fill(0) }
            }
        }
        return when (encrypted) {
            is CredentialResult.Failure -> encrypted
            is CredentialResult.Success -> guarded(CredentialFailure.FAILED_WRITE) {
                try { CredentialResult.Success(file.stage(encrypted.value)) }
                finally { encrypted.value.fill(0) }
            }
        }
    }

    fun rotation(owner: SessionGeneration, pending: Boolean): CredentialResult<Unit> = synchronized(io) {
        guarded(CredentialFailure.FAILED_WRITE) {
            if (generation !== owner) return@guarded failure(CredentialFailure.STALE_GENERATION)
            marker(pending)
            CredentialResult.Success(Unit)
        }
    }

    override fun delete(): CredentialResult<Unit> = synchronized(io) {
        // The kernel invalidated/rebound the capability at admission, before entering I/O.
        // Do not clear it here: a newer owner may already have been admitted.
        // Tombstone first. Both deletion attempts still run if marking fails.
        val tombstone = guarded(CredentialFailure.FAILED_WRITE) { marker(true); CredentialResult.Success(Unit) }
        // Attempt both removals even when one fails. A3 already invalidated the capability.
        val key = guarded(CredentialFailure.FAILED_WRITE) { cipher.deleteKey(); CredentialResult.Success(Unit) }
        val data = guarded(CredentialFailure.FAILED_WRITE) { file.delete(); CredentialResult.Success(Unit) }
        // Keep a quarantine marker if either deletion failed; a fresh owner must fail closed.
        val marker = if (key is CredentialResult.Success && data is CredentialResult.Success)
            guarded(CredentialFailure.FAILED_WRITE) {
                marker(false)
                CredentialResult.Success(Unit)
            } else CredentialResult.Success(Unit)
        listOf(tombstone, key, data, marker).filterIsInstance<CredentialResult.Failure>().firstOrNull()
            ?: CredentialResult.Success(Unit)
    }

    private fun marker(pending: Boolean) {
        val marker = rotationMarker ?: return
        val directory = marker.parentFile!!
        if (pending) {
            check(directory.isDirectory || directory.mkdirs())
            java.io.FileOutputStream(marker).use { it.write(1); it.fd.sync() }
        } else check(!marker.exists() || marker.delete())
        syncDirectory(directory)
    }

    private fun failure(category: CredentialFailure) = CredentialResult.Failure(category)

    private fun <T> guarded(fallback: CredentialFailure, action: () -> CredentialResult<T>): CredentialResult<T> = try {
        action()
    } catch (_: CredentialKeyLost) {
        failure(CredentialFailure.KEY_LOST)
    } catch (_: KeyPermanentlyInvalidatedException) {
        failure(CredentialFailure.KEY_LOST)
    } catch (_: UnrecoverableKeyException) {
        failure(CredentialFailure.KEY_LOST)
    } catch (_: Exception) {
        failure(fallback)
    }
}
