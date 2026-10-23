package io.github.leugenea.codexbarmobile.history

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.Instant

sealed interface HistoryImportOutcome {
    data object Imported : HistoryImportOutcome
    data object Invalid : HistoryImportOutcome
    data object UnsupportedVersion : HistoryImportOutcome
    data object TooLarge : HistoryImportOutcome
    data class Unavailable(val reason: HistoryUnavailable) : HistoryImportOutcome
}

internal class UnsupportedHistoryVersion : IllegalArgumentException()
internal class HistoryImportTooLarge : IllegalArgumentException()

/** The same transaction algorithm is exercised on JVM and framework SQLite. */
internal interface HistoryReplacementStorage {
    fun <T> transaction(block: () -> T): T
    fun removeThrough(ordinal: Long)
    fun oldestPrefix(count: Long): Long
    fun spacePages(): Long
    fun insert(entry: HistoryEntry, payload: ByteArray)
    fun writeState(state: HistoryDiskState)
}

internal object HistoryStorageSpace {
    fun reserve(payloadBytes: Int, available: () -> Long, evict: () -> Unit) {
        val required = 8L + (payloadBytes + 4095) / 4096
        while (available() < required) evict()
    }
}

internal object HistoryImport {
    // Public JSON is larger than the binary database; bound even unknown-size providers.
    const val MAX_INPUT_BYTES = HistoryLimits.MAX_BYTES * 2

    fun read(stream: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = stream.read(buffer)
        while (size != -1) {
            if (bytes.size().toLong() + size > MAX_INPUT_BYTES) throw HistoryImportTooLarge()
            bytes.write(buffer, 0, size)
            size = stream.read(buffer)
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun decode(text: String, partition: HistoryPartition): HistoryExportDocument {
        if (text.length.toLong() > MAX_INPUT_BYTES || text.toByteArray(Charsets.UTF_8).size.toLong() > MAX_INPUT_BYTES) {
            throw HistoryImportTooLarge()
        }
        return HistoryExport.decode(text, partition).also(HistoryImportValidation::validate)
    }

    /** Explicit restore applies age against import time, without changing the saved recording clock. */
    fun retained(document: HistoryExportDocument, limits: HistoryStorageLimits, now: Instant): HistoryExportDocument {
        val cutoff = now.minus(Duration.ofDays(limits.retentionDays))
        val expired = document.entries.filter { it.observedAt?.isBefore(cutoff) == true }
        var state = document.state
        expired.lastOrNull()?.let { state = HistoryRetention.removed(state, HistoryTruncation.AGE_RETENTION, it.id.ordinal) }
        val recent = document.entries.filterNot { it.observedAt?.isBefore(cutoff) == true }
        val excess = (recent.size - limits.observations).coerceAtLeast(0)
        if (excess > 0) state = HistoryRetention.removed(state, HistoryTruncation.OBSERVATION_CAP, recent[excess - 1].id.ordinal)
        return document.copy(entries = recent.drop(excess), state = state)
    }

    /** No pre-transaction maintenance: even byte evictions and continuation roll back on failure. */
    fun replace(document: HistoryExportDocument, storage: HistoryReplacementStorage,
        limits: HistoryStorageLimits, now: Instant, afterInsert: () -> Unit = {}, beforeCommit: () -> Unit = {}) {
        val retained = retained(document, limits, now)
        storage.transaction {
            storage.removeThrough(Long.MAX_VALUE)
            var state = retained.state
            val stateBytes = HistoryEncoding.state(state).size
            retained.entries.forEach { entry ->
                val payload = HistoryEncoding.entry(entry)
                HistoryStorageSpace.reserve(payload.size + stateBytes, storage::spacePages) {
                    val ordinal = storage.oldestPrefix(64)
                    if (ordinal == 0L) throw HistoryStorageException(HistoryUnavailable.STORAGE_FULL)
                    storage.removeThrough(ordinal)
                    state = HistoryRetention.removed(state, HistoryTruncation.BYTE_CAP, ordinal)
                }
                storage.insert(entry, payload)
                afterInsert()
            }
            storage.writeState(state)
            beforeCommit()
        }
    }
}
