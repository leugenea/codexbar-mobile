package io.github.leugenea.codexbarmobile.credentials

import java.nio.ByteBuffer
import java.util.UUID

/** v1 uses fixed authenticated metadata; unresolved binding is never a saved account. */
internal object CredentialBinaryFormat {
    const val MAX_TOKEN = 64 * 1024
    const val MAX_FILE = MAX_TOKEN * 2 + 128
    private const val HEADER_SIZE = 28

    fun header(session: UUID): ByteArray = ByteBuffer.allocate(HEADER_SIZE)
        .putInt(0x43424352).putInt(1).put(1.toByte()).put(0.toByte())
        .putShort(0).putLong(session.mostSignificantBits).putLong(session.leastSignificantBits).array()

    fun payload(envelope: CredentialEnvelope): ByteArray {
        val access = envelope.accessToken.copyBytes()
        val refresh = envelope.refreshToken?.copyBytes()
        try {
            require(access.size in 1..MAX_TOKEN)
            require(refresh == null || refresh.size in 1..MAX_TOKEN)
            return ByteBuffer.allocate(8 + access.size + (refresh?.size ?: 0))
                .putInt(access.size).put(access).putInt(refresh?.size ?: -1)
                .apply { if (refresh != null) put(refresh) }.array()
        } finally {
            access.fill(0)
            refresh?.fill(0)
        }
    }

    fun envelope(payload: ByteArray, generation: SessionGeneration): CredentialEnvelope {
        val input = ByteBuffer.wrap(payload)
        val access = token(input, false)!!
        var refresh: ByteArray? = null
        try {
            refresh = token(input, true)
            require(!input.hasRemaining())
            return CredentialEnvelope(generation, SensitiveValue.copyOf(access), refresh?.let(SensitiveValue::copyOf))
        } finally {
            access.fill(0)
            refresh?.fill(0)
        }
    }

    private fun token(input: ByteBuffer, nullable: Boolean): ByteArray? {
        val length = input.int
        if (nullable && length == -1) return null
        require(length in 1..MAX_TOKEN && length <= input.remaining())
        return ByteArray(length).also(input::get)
    }

    fun file(session: UUID, sealed: SealedCredential): ByteArray {
        require(sealed.iv.size == 12)
        require(sealed.ciphertext.size in 16..(MAX_FILE - HEADER_SIZE - 16))
        return ByteBuffer.allocate(HEADER_SIZE + 16 + sealed.ciphertext.size)
            .put(header(session)).put(sealed.iv).putInt(sealed.ciphertext.size).put(sealed.ciphertext).array()
    }

    fun sealed(file: ByteArray, session: UUID): SealedCredential {
        require(file.size in (HEADER_SIZE + 32)..MAX_FILE)
        val input = ByteBuffer.wrap(file)
        val metadata = ByteArray(HEADER_SIZE).also(input::get)
        require(metadata.contentEquals(header(session)))
        val iv = ByteArray(12).also(input::get)
        val length = input.int
        require(length >= 16 && length == input.remaining())
        return SealedCredential(iv, ByteArray(length).also(input::get))
    }
}

internal class SealedCredential(val iv: ByteArray, val ciphertext: ByteArray)
