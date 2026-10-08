package io.github.leugenea.codexbarmobile.history

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant

/** Two tables, schema v1; no WAL, migration, ATTACH, SQL text from callers or disk-sort query. */
internal class HistorySchema(private val file: File, private val limits: HistoryStorageLimits) : AutoCloseable {
    private var opened: SQLiteDatabase? = null
    private val database: SQLiteDatabase get() = opened ?: throw IllegalStateException()

    fun open(partition: HistoryPartition, create: Boolean) {
        if (opened != null) return
        if (!create && !file.exists()) throw HistoryCorruption()
        val flags = SQLiteDatabase.NO_LOCALIZED_COLLATORS or if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0
        val db = SQLiteDatabase.openDatabase(file.path, null, flags) { throw HistoryCorruption() }
        try {
            opened = db
            if (!create) verifyVersion()
            if (create) database.execSQL("PRAGMA page_size=4096")
            configure()
            if (create) initialize(partition)
            val check = text("PRAGMA quick_check(1)")
            if (check != "ok") throw HistoryCorruption()
            validateState(partition)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun initialize(partition: HistoryPartition) {
        database.execSQL("PRAGMA page_size=4096")
        database.execSQL("PRAGMA max_page_count=${limits.databasePages}")
        transaction {
            database.execSQL(STATE_SQL)
            database.execSQL(OBSERVATIONS_SQL)
            writeState(HistoryDiskState(HistoryCursor(partition)))
            database.execSQL("PRAGMA application_id=1212764977")
            database.version = VERSION
        }
    }

    private fun verifyVersion() {
        if (database.version != VERSION) throw HistoryStorageException(HistoryUnavailable.UNSUPPORTED_SCHEMA)
        if (number("PRAGMA application_id") != 1212764977L) throw HistoryCorruption()
        val declarations = database.rawQuery("SELECT name, sql FROM sqlite_master WHERE substr(name,1,7)<>'sqlite_' ORDER BY name LIMIT 3", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        if (declarations != listOf("observations" to OBSERVATIONS_SQL, "state" to STATE_SQL)) throw HistoryCorruption()
    }

    private fun configure() {
        if (number("PRAGMA page_size") != 4096L) throw HistoryCorruption()
        if (text("PRAGMA journal_mode=DELETE") != "delete") throw HistoryCorruption()
        database.execSQL("PRAGMA synchronous=FULL")
        database.execSQL("PRAGMA temp_store=MEMORY")
        database.execSQL("PRAGMA cache_size=-256")
        database.execSQL("PRAGMA journal_size_limit=0")
        database.execSQL("PRAGMA secure_delete=ON") // Logical removal only; not forensic erasure.
        if (number("PRAGMA temp_store") != 2L || number("PRAGMA synchronous") != 2L) throw HistoryCorruption()
        if (number("PRAGMA max_page_count=${limits.databasePages}") > limits.databasePages) {
            throw HistoryStorageException(HistoryUnavailable.STORAGE_FULL)
        }
    }

    fun state(partition: HistoryPartition): HistoryDiskState {
        return database.rawQuery("SELECT partition, high_water, length(payload), CASE WHEN length(payload)<=16384 THEN payload END FROM state WHERE slot=1", null).use {
            if (!it.moveToFirst()) throw HistoryCorruption()
            if (it.getString(0) != partition.value.toString() || it.getInt(2) !in 12..HistoryEncoding.MAX_BLOB) throw HistoryCorruption()
            val result = decodeState(it.getBlob(3))
            if (result.cursor.partition != partition || result.cursor.lastOrdinal != it.getLong(1)) throw HistoryCorruption()
            result
        }
    }

    private fun decodeState(bytes: ByteArray): HistoryDiskState = try {
        HistoryEncoding.state(bytes)
    } catch (_: Exception) { throw HistoryCorruption() }

    private fun validateState(partition: HistoryPartition) {
        val state = state(partition)
        database.rawQuery("SELECT max(ordinal), count(*) FROM observations WHERE partition<>? OR ordinal>?", arrayOf(partition.value.toString(), state.cursor.lastOrdinal.toString())).use {
            it.moveToFirst()
            if (it.getLong(1) != 0L) throw HistoryCorruption()
        }
    }

    fun insert(entry: HistoryEntry, payload: ByteArray) {
        val values = ContentValues().apply {
            put("ordinal", entry.id.ordinal); put("partition", entry.partition.value.toString())
            entry.observedAt?.let { put("observed_seconds", it.epochSecond); put("observed_nanos", it.nano) }
            put("payload", payload)
        }
        database.insertOrThrow("observations", null, values)
    }

    fun writeState(state: HistoryDiskState) {
        val values = ContentValues().apply {
            put("slot", 1); put("partition", state.cursor.partition.value.toString())
            put("high_water", state.cursor.lastOrdinal); put("payload", HistoryEncoding.state(state))
        }
        database.insertWithOnConflict("state", null, values, SQLiteDatabase.CONFLICT_REPLACE).also {
            if (it == -1L) throw HistoryStorageException(HistoryUnavailable.WRITE_FAILURE)
        }
    }

    fun page(query: HistoryReadQuery, maximum: Int): Pair<List<HistoryEntry>, Boolean> {
        val entries = database.rawQuery("SELECT $ROW_COLUMNS FROM observations WHERE partition=? AND ordinal>? ORDER BY ordinal LIMIT ?",
            arrayOf(query.partition.value.toString(), (query.after?.ordinal ?: 0).toString(), (maximum + 1).toString())).use {
            buildList {
                while (it.moveToNext()) {
                    add(row(it, query.partition))
                }
            }
        }
        return entries.take(maximum) to (entries.size > maximum)
    }

    private fun decodeEntry(bytes: ByteArray): HistoryEntry = try {
        HistoryEncoding.entry(bytes)
    } catch (_: Exception) { throw HistoryCorruption() }

    private fun row(cursor: Cursor, partition: HistoryPartition): HistoryEntry {
        if (cursor.getInt(1) !in 12..HistoryEncoding.MAX_BLOB) throw HistoryCorruption()
        val entry = decodeEntry(cursor.getBlob(2))
        if (entry.partition != partition || entry.id.ordinal != cursor.getLong(0)) throw HistoryCorruption()
        if (!matchesTime(cursor, entry.observedAt)) throw HistoryCorruption()
        return entry
    }

    private fun matchesTime(cursor: Cursor, at: Instant?): Boolean {
        if (at == null) return cursor.isNull(3) && cursor.isNull(4)
        return !cursor.isNull(3) && !cursor.isNull(4) && cursor.getLong(3) == at.epochSecond && cursor.getLong(4) == at.nano.toLong()
    }

    fun count(): Long = number("SELECT count(*) FROM observations")
    fun spacePages(): Long = limits.databasePages - number("PRAGMA page_count") + number("PRAGMA freelist_count")

    /** Unknown observation timestamps are never inferred from receipt time or neighboring rows. */
    fun ageBefore(cutoff: Instant, partition: HistoryPartition): Long {
        return database.rawQuery("SELECT $ROW_COLUMNS FROM observations WHERE $AGE_PREDICATE ORDER BY ordinal", ageArguments(cutoff)).use {
            var last = 0L
            while (it.moveToNext()) last = row(it, partition).id.ordinal
            last
        }
    }
    fun removeBefore(cutoff: Instant) { database.delete("observations", AGE_PREDICATE, ageArguments(cutoff)) }
    private fun ageArguments(cutoff: Instant): Array<String> = arrayOf(cutoff.epochSecond.toString(), cutoff.epochSecond.toString(), cutoff.nano.toString())

    fun oldestPrefix(count: Long): Long = number("SELECT coalesce(max(ordinal),0) FROM (SELECT ordinal FROM observations ORDER BY ordinal LIMIT ?)", arrayOf(count.toString()))
    fun removeThrough(ordinal: Long) { database.delete("observations", "ordinal<=?", arrayOf(ordinal.toString())) }

    fun <T> transaction(block: () -> T): T {
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally { database.endTransaction() }
    }

    private fun number(sql: String, arguments: Array<String>? = null): Long = database.rawQuery(sql, arguments).use {
        if (!it.moveToFirst()) throw HistoryCorruption()
        it.getLong(0)
    }
    private fun text(sql: String): String = database.rawQuery(sql, null).use { if (!it.moveToFirst()) throw HistoryCorruption(); it.getString(0) }
    override fun close() { opened?.close(); opened = null }
    companion object {
        const val VERSION = 1
        private const val AGE_PREDICATE = "observed_seconds<? OR (observed_seconds=? AND observed_nanos<?)"
        private const val ROW_COLUMNS = "ordinal, length(payload), CASE WHEN length(payload)<=16384 THEN payload END, observed_seconds, observed_nanos"
        private const val STATE_SQL = "CREATE TABLE state (slot INTEGER PRIMARY KEY CHECK(slot=1), partition TEXT NOT NULL, high_water INTEGER NOT NULL CHECK(high_water>=0), payload BLOB NOT NULL CHECK(length(payload)<=16384))"
        private const val OBSERVATIONS_SQL = "CREATE TABLE observations (ordinal INTEGER PRIMARY KEY CHECK(ordinal>0), partition TEXT NOT NULL, observed_seconds INTEGER, observed_nanos INTEGER, payload BLOB NOT NULL CHECK(length(payload)<=16384))"
    }
}
