package io.github.leugenea.codexbarmobile.credentials

/**
 * Opaque immutable bytes. Equality is object identity, not secret comparison, and hashes
 * reveal nothing about contents. This is redaction, not secure erasure or timing proof.
 * Only trusted storage/transport adapters may explicitly copy bytes; never log that copy.
 */
class SensitiveValue private constructor(private val bytes: ByteArray) {
    fun copyBytes(): ByteArray = bytes.copyOf()
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = 0
    override fun toString(): String = "SensitiveValue(redacted)"

    companion object {
        fun copyOf(bytes: ByteArray): SensitiveValue = SensitiveValue(bytes.copyOf())
    }
}
