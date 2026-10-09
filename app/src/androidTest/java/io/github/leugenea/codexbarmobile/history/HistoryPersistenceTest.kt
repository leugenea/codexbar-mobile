package io.github.leugenea.codexbarmobile.history

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.usage.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Hosted framework SQLite/disk tests, original synthetic numeric/time facts only. No process-death claim. */
@RunWith(AndroidJUnit4::class)
class HistoryPersistenceTest {
    private lateinit var fixtureRoot: File
    private lateinit var directory: File
    private val stores = mutableListOf<SQLiteHistoryStore>()
    private val at = Instant.ofEpochSecond(1_800_000_000, 7)
    private val epoch = ClockEpoch(UUID(0, 5))

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fixtureRoot = File(context.noBackupFilesDir, "synthetic-history-${UUID.randomUUID()}").canonicalFile
        directory = fixtureRoot
    }
    @After fun cleanup() {
        var failure: Throwable? = null
        try {
            for (backend in stores) {
                try { backend.close() } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
        } finally { fixtureRoot.deleteRecursively() }
        failure?.let { throw it }
    }
    private fun store(limits: HistoryStorageLimits = HistoryStorageLimits(), hooks: HistoryStorageHooks = object : HistoryStorageHooks {}): SQLiteHistoryStore =
        SQLiteHistoryStore(directory, limits, hooks).also { stores.add(it) }
    private fun created(store: SQLiteHistoryStore): HistoryAccess = bound("create partition", store.createPartition())
    private fun restored(store: SQLiteHistoryStore, partition: HistoryPartition): HistoryAccess = bound("adopt partition", store.adopt(partition))
    private fun bound(step: String, outcome: HistoryAccessOutcome): HistoryAccess = when (outcome) {
        is HistoryAccessOutcome.Bound -> outcome.access
        is HistoryAccessOutcome.Unavailable -> throw AssertionError("$step: expected Bound; actual=Unavailable(${outcome.reason})")
        HistoryAccessOutcome.Corrupt -> throw AssertionError("$step: expected Bound; actual=Corrupt")
    }
    private fun admission(access: HistoryAccess, ordinal: Long, seconds: Long = ordinal - 1, percent: String = "12.37500000000000000001", monotonic: Long? = seconds * 1000 + 1000,
        clockEpoch: ClockEpoch = epoch): HistoryAdmission {
        val window = WindowInput(Input.Value(18000L), Input.Value(BigDecimal(percent)), Input.Value(1_800_003_600L), Input.Invalid)
        val usage = UsageNormalizer.normalize(UsageInput(Input.Value(window), Input.Missing,
            allowed = Input.Value(false), limitReached = Input.Value(true)), at.plusSeconds(seconds))
        return HistoryAdmission(access.partition, ObservationId(ordinal), HistoryClock(clockEpoch, monotonic), HistoryEvent.Observed(usage))
    }
    private fun snapshot(access: HistoryAccess, limit: Int = 256, after: Long? = null): HistoryReadSnapshot =
        (access.read(HistoryReadQuery(access.partition, limit, after?.let { ObservationId(it) })) as HistoryReadOutcome.Ready).snapshot
    private fun stored(access: HistoryAccess, ordinal: Long, seconds: Long = ordinal - 1): HistoryAppendOutcome.Stored =
        access.append(admission(access, ordinal, seconds)) as HistoryAppendOutcome.Stored
    private fun database(): File = File(directory, "history.db")
    private fun diskCount(): Long = SQLiteDatabase.openDatabase(database().path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
        db.rawQuery("SELECT count(*) FROM observations", null).use { it.moveToFirst(); it.getLong(0) }
    }
    private fun mutate(sql: String) = SQLiteDatabase.openDatabase(database().path, null, SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { it.execSQL(sql) }
    private fun bytes(): Long = directory.listFiles()!!.sumOf { it.length() }

    private fun duplicatedSlots(value: HistoryAdmission, copies: Int): HistoryAdmission {
        val event = value.event as HistoryEvent.Observed
        return value.copy(event = HistoryEvent.Observed(event.usage.copy(slots = List(copies) { event.usage.slots.first() })))
    }

    @Test fun actualSQLiteReopenKeepsExactDecimalsMetadataCursorAndEqualPercentIds() {
        val firstStore = store(); val first = created(firstStore)
        val committed = stored(first, 1).entry
        stored(first, 2)
        assertEquals(2L, diskCount())
        assertTrue(database().length() > 0)
        firstStore.close()
        val access = restored(store(), first.partition)
        val page = snapshot(access)
        assertEquals(listOf(1L, 2L), page.entries.map { it.id.ordinal })
        assertEquals(ObservationId(2), page.lastAdmitted)
        assertArrayEquals(HistoryEncoding.entry(committed), HistoryEncoding.entry(page.entries.first()))
        assertEquals(BigDecimal("12.37500000000000000001"), page.entries.last().windows.first().point!!.usedPercent)
        assertEquals(ResetProvenance.ABSOLUTE, page.entries.first().windows.first().reset.provenance)
        assertEquals(Reason.WRONG_TYPE, page.entries.first().windows.first().reset.facts!!.relativeSeconds.reason)
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), access.append(admission(access, 1, percent = "99")))
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), access.append(duplicatedSlots(admission(access, 1), 3)))
        stored(access, 3)
        assertEquals(ObservationId(1), snapshot(access).entries.last().windows.first().point!!.segment.id.first)
        assertEquals(3L, diskCount())
    }

    @Test fun partitionCapabilitiesRejectCrossPartitionAndRetiredGenerations() {
        val backend = store(); val a = created(backend); stored(a, 1)
        val invented = HistoryPartition(UUID(0, 99))
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), backend.adopt(invented))
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), a.append(admission(a, 2).copy(partition = invented)))
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), a.read(HistoryReadQuery(invented, 1)))
        assertEquals(HistoryDeleteOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), a.delete(invented))
        val continuing = restored(backend, a.partition)
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), a.read(HistoryReadQuery(a.partition, 1)))
        assertEquals(1L, diskCount())
        assertEquals(HistoryDeleteOutcome.Deleted, continuing.delete(continuing.partition))
        val b = created(backend); stored(b, 1)
        assertNotEquals(a.partition, b.partition)
        assertEquals(HistoryDeleteOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), a.delete(a.partition))
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), backend.adopt(a.partition))
        assertEquals(listOf(1L), snapshot(b).entries.map { it.id.ordinal })
        assertEquals(1L, diskCount())
        b.revoke()
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), b.append(admission(b, 2)))
        assertEquals(1L, snapshot(restored(backend, b.partition)).lastAdmitted!!.ordinal)
    }

    @Test fun rollbackAndUncertainCommitHaveAtomicHighWaterAndRetry() {
        var failInsert = true; var failCommit = false; var uncertain = false
        val hooks = object : HistoryStorageHooks {
            override fun afterInsert() { if (failInsert) throw IOException() }
            override fun beforeCommit() { if (failCommit) throw IOException() }
            override fun afterCommit() { if (uncertain) throw IOException() }
        }
        val backend = store(hooks = hooks); val access = created(backend)
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), access.append(admission(access, 1)))
        assertEquals(0L, diskCount()); assertNull(snapshot(access).lastAdmitted)
        failInsert = false; failCommit = true
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), access.append(admission(access, 1)))
        assertEquals(0L, diskCount()); assertNull(snapshot(access).lastAdmitted)
        failCommit = false; uncertain = true
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), access.append(admission(access, 1)))
        assertEquals(1L, diskCount()); assertEquals(ObservationId(1), snapshot(access).lastAdmitted)
        backend.close()
        val reopened = restored(store(), access.partition)
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), reopened.append(admission(reopened, 1, percent = "4")))
        assertEquals(BigDecimal("12.37500000000000000001"), snapshot(reopened).entries.single().windows.first().point!!.usedPercent)
    }

    @Test fun trustedAgeEvictionPersistsOrdinalCutoffWhileClockAnomaliesSuspendAge() {
        val backend = store(); val access = created(backend)
        stored(access, 1, 0); stored(access, 2, 2_592_001)
        val page = snapshot(access)
        assertEquals(listOf(2L), page.entries.map { it.id.ordinal })
        assertEquals(HistoryEvictionCutoff(1), page.cutoffs[HistoryTruncation.AGE_RETENTION])
        assertEquals(1L, diskCount())
        backend.close()
        val reopened = restored(store(), access.partition)
        assertEquals(page.cutoffs, snapshot(reopened).cutoffs)
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), reopened.append(admission(reopened, 1)))
        // A forward wall jump with only 1 second of monotonic elapsed time must NOT delete #2.
        stored(reopened, 3, 2_592_002)
        assertTrue(reopened.append(admission(reopened, 4, 5_184_100, monotonic = 2_592_004_000)) is HistoryAppendOutcome.Stored)
        assertEquals(listOf(2L, 3L, 4L), snapshot(reopened).entries.map { it.id.ordinal })
        assertTrue(reopened.append(admission(reopened, 5, 2, monotonic = 2_592_005_000)) is HistoryAppendOutcome.Stored)
        assertEquals(listOf(2L, 3L, 4L, 5L), snapshot(reopened).entries.map { it.id.ordinal })
        assertEquals(BreakReason.NON_INCREASING_WALL_TIME, snapshot(reopened).entries.last().windows.first().point!!.segment.breaks.first { it == BreakReason.NON_INCREASING_WALL_TIME })
        assertEquals(HistoryDeleteOutcome.Deleted, reopened.delete(reopened.partition))
        val boundary = created(stores.last())
        stored(boundary, 1, 0); stored(boundary, 2, 2_592_000)
        assertEquals(listOf(1L, 2L), snapshot(boundary).entries.map { it.id.ordinal })
        val subsecond = admission(boundary, 3, 2_592_000, monotonic = 2_592_001_001)
        val event = subsecond.event as HistoryEvent.Observed
        assertTrue(boundary.append(subsecond.copy(event = HistoryEvent.Observed(event.usage.copy(
            observedAt = event.usage.observedAt!!.plusNanos(1))))) is HistoryAppendOutcome.Stored)
        assertEquals(listOf(2L, 3L), snapshot(boundary).entries.map { it.id.ordinal })
    }

    @Test fun globalObservationCapRetainsHighWaterSegmentOriginAndPagination() {
        val limits = HistoryStorageLimits(observations = 3, readPage = 2)
        val backend = store(limits); val access = created(backend)
        for (id in 1L..8L) stored(access, id)
        assertEquals(3L, diskCount())
        val first = snapshot(access, 2)
        assertEquals(listOf(6L, 7L), first.entries.map { it.id.ordinal }); assertTrue(first.hasMore)
        assertEquals(ObservationId(1), first.entries.first().windows.first().point!!.segment.id.first)
        assertEquals(HistoryEvictionCutoff(5), first.cutoffs[HistoryTruncation.OBSERVATION_CAP])
        assertEquals(listOf(8L), snapshot(access, 2, first.nextAfter!!.ordinal).entries.map { it.id.ordinal })
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.CAPACITY), access.read(HistoryReadQuery(access.partition, 3)))
        assertEquals(HistoryAppendOutcome.Rejected(AdmissionRejection.ORDINAL_GAP), access.append(admission(access, 10)))
        backend.close()
        val again = restored(store(limits), access.partition)
        assertEquals(first.cutoffs, snapshot(again, 2).cutoffs)
        assertEquals(ObservationId(8), snapshot(again, 2).lastAdmitted)
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), again.append(admission(again, 1)))
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), again.append(duplicatedSlots(admission(again, 1), 3)))
        assertEquals(3L, diskCount())
    }

    @Test fun unknownTimeStatusesSurviveAgeWhileNewEpochSuspensionSurvivesReopen() {
        val backend = store(); val access = created(backend)
        stored(access, 1, 0)
        assertTrue(access.append(HistoryAdmission(access.partition, ObservationId(2), HistoryClock(epoch, 2000),
            HistoryEvent.Gap(HistoryGap.BACKGROUND))) is HistoryAppendOutcome.Stored)
        stored(access, 3, 2_592_001)
        assertEquals(listOf(2L, 3L), snapshot(access).entries.map { it.id.ordinal })
        assertEquals(HistoryGap.BACKGROUND, snapshot(access).entries.first().gap)
        assertEquals(HistoryEvictionCutoff(1), snapshot(access).cutoffs[HistoryTruncation.AGE_RETENTION])
        assertTrue(access.append(admission(access, 4, 5_184_002, monotonic = 1000,
            clockEpoch = ClockEpoch(UUID(0, 77)))) is HistoryAppendOutcome.Stored)
        assertEquals(listOf(2L, 3L, 4L), snapshot(access).entries.map { it.id.ordinal })
        backend.close()
        val reopened = restored(store(), access.partition)
        assertTrue(reopened.append(admission(reopened, 5, 7_776_003, monotonic = 2_592_002_000,
            clockEpoch = ClockEpoch(UUID(0, 77)))) is HistoryAppendOutcome.Stored)
        assertEquals(listOf(2L, 3L, 4L, 5L), snapshot(reopened).entries.map { it.id.ordinal })
        assertEquals(4L, diskCount())
    }

    @Test fun missingTimeClockAnomaliesPersistSuspensionAcrossReopen() {
        val anomalies = listOf(HistoryClock(epoch, null), HistoryClock(ClockEpoch(UUID(0, 77)), 3000),
            HistoryClock(epoch, 1000), HistoryClock(epoch, 1500))
        for (clock in anomalies) {
            val backend = store(); val access = created(backend); stored(access, 1, 0)
            val missingTime = admission(access, 2, monotonic = 2000)
            val observed = missingTime.event as HistoryEvent.Observed
            val unknown = HistoryEvent.Observed(observed.usage.copy(observedAt = null))
            assertTrue(access.append(missingTime.copy(event = unknown)) is HistoryAppendOutcome.Stored)
            assertTrue(access.append(missingTime.copy(id = ObservationId(3), clock = clock, event = unknown)) is HistoryAppendOutcome.Stored)
            backend.close()
            val reopened = restored(store(), access.partition)
            stored(reopened, 4, 2_592_001)
            assertEquals(listOf(1L, 2L, 3L, 4L), snapshot(reopened).entries.map { it.id.ordinal })
            assertFalse(HistoryTruncation.AGE_RETENTION in snapshot(reopened).truncation)
            assertEquals(HistoryDeleteOutcome.Deleted, reopened.delete(reopened.partition))
            stores.last().close()
        }
    }

    @Test fun tighterReopenCountMaintenanceIsTransactionalAndPreservesAdmissionHighWater() {
        val backend = store(); val access = created(backend)
        for (id in 1L..8L) stored(access, id)
        backend.close()
        val smaller = HistoryStorageLimits(observations = 3)
        val interrupted = store(smaller, object : HistoryStorageHooks {
            override fun beforeMaintenanceCommit() { throw IOException() }
        })
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.READ_FAILURE), interrupted.adopt(access.partition))
        assertEquals(8L, diskCount()); interrupted.close()
        val reopened = restored(store(smaller), access.partition)
        assertEquals(3L, diskCount())
        assertEquals(listOf(6L, 7L, 8L), snapshot(reopened).entries.map { it.id.ordinal })
        assertEquals(ObservationId(8), snapshot(reopened).lastAdmitted)
        assertEquals(HistoryEvictionCutoff(5), snapshot(reopened).cutoffs[HistoryTruncation.OBSERVATION_CAP])
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), reopened.append(admission(reopened, 1)))
    }

    @Test fun physicalDirectoryBudgetIncludesRollbackJournalControlAndDefaultCeilings() {
        val limits = HistoryStorageLimits(bytes = 524_288)
        var observedJournal = false; var peak = 0L; var maintenanceCommits = 0
        var observedJournalOverhead = false
        val hooks = object : HistoryStorageHooks {
            private fun observe() {
                observedJournal = observedJournal || File(directory, "history.db-journal").exists()
                val currentBytes = bytes()
                observedJournalOverhead = observedJournalOverhead || currentBytes > database().length() + 32
                peak = maxOf(peak, currentBytes)
                assertTrue("physical transaction bytes=$peak", peak <= limits.bytes)
                assertFalse(File(directory, "history.db-wal").exists())
            }
            override fun beforeCommit() { observe() }
            override fun beforeMaintenanceCommit() { maintenanceCommits++; observe() }
        }
        val backend = store(limits, hooks); val access = created(backend)
        var result = stored(access, 1)
        var id = 1L
        while (HistoryTruncation.BYTE_CAP !in result.truncation && id < 2048) { id++; result = stored(access, id) }
        assertTrue("byte eviction not reached; last ordinal=$id bytes=${bytes()}", HistoryTruncation.BYTE_CAP in result.truncation)
        assertTrue(observedJournal); assertTrue(observedJournalOverhead)
        assertTrue(maintenanceCommits > 0); assertTrue(bytes() <= limits.bytes)
        val cutoff = snapshot(access).cutoffs[HistoryTruncation.BYTE_CAP]!!
        assertTrue(cutoff.ordinal > 0); assertTrue(diskCount() < id)
        backend.close()
        val reopened = restored(store(limits), access.partition)
        assertEquals(cutoff, snapshot(reopened).cutoffs[HistoryTruncation.BYTE_CAP])
        assertEquals(HistoryAppendOutcome.AlreadyAdmitted(ObservationId(1)), reopened.append(admission(reopened, 1)))
        reopened.delete(reopened.partition); stores.last().close()
        val defaults = store(); val plain = created(defaults); stored(plain, 1)
        assertTrue(bytes() <= HistoryLimits.MAX_BYTES)
        assertEquals(30L, HistoryStorageLimits().retentionDays)
        assertEquals(100_000, HistoryStorageLimits().observations)

    }

    @Test fun fullSQLiteAndInterruptedMaintenanceReturnTypedFailureWithoutConsumingId() {
        var full = true
        val backend = store(hooks = object : HistoryStorageHooks { override fun afterInsert() { if (full) throw SQLiteFullException() } })
        val access = created(backend)
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.STORAGE_FULL), access.append(admission(access, 1)))
        assertEquals(0L, diskCount()); assertNull(snapshot(access).lastAdmitted)
        full = false; stored(access, 1)
        assertEquals(1L, diskCount())
        val tooPrecise = "0." + "1".repeat(129)
        assertEquals(HistoryAppendOutcome.Rejected(AdmissionRejection.FIELD_CAPACITY), access.append(admission(access, 2, percent = tooPrecise)))
        val invalid = admission(access, 2)
        val observed = invalid.event as HistoryEvent.Observed
        val corruptField = observed.usage.fiveHour.candidates.single().copy(usedPercent = Field(Knowledge.KNOWN, BigDecimal("-1")))
        val malformed = invalid.copy(event = HistoryEvent.Observed(observed.usage.copy(
            fiveHour = WindowSelection(SelectionState.KNOWN, listOf(corruptField)))))
        assertEquals(HistoryAppendOutcome.Rejected(AdmissionRejection.INVALID_SAMPLE), access.append(malformed))
        assertEquals(HistoryAppendOutcome.Rejected(AdmissionRejection.INVALID_SAMPLE), access.append(duplicatedSlots(admission(access, 2), 2)))
        assertEquals(ObservationId(1), snapshot(access).lastAdmitted)
        backend.close()
        // A deliberately oversized interrupted control artifact exercises the REAL directory
        // capacity check. Do not fill the emulator filesystem or claim simulated SQLITE_FULL is ENOSPC.
        val pending = File(directory, "binding.pending")
        pending.writeBytes(ByteArray(262_144))
        val tiny = store(HistoryStorageLimits(bytes = 262_144))
        try {
            assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.STORAGE_FULL), tiny.adopt(access.partition))
            assertEquals(1L, diskCount())
        } finally { tiny.close(); assertTrue(pending.delete()) }
        val restored = restored(store(), access.partition)
        assertEquals(ObservationId(1), snapshot(restored).lastAdmitted)
        stored(restored, 2); assertEquals(2L, diskCount())
    }

    @Test fun interruptedByteMaintenanceRollsBackCutoffAndRetriesWithinBudget() {
        var interrupt = true
        val limits = HistoryStorageLimits(bytes = 524_288)
        val backend = store(limits, object : HistoryStorageHooks {
            override fun beforeMaintenanceCommit() { if (interrupt) throw IOException() }
        })
        val access = created(backend)
        var id = 1L
        var outcome: HistoryAppendOutcome = access.append(admission(access, id))
        while (outcome is HistoryAppendOutcome.Stored && id < 2048) {
            id++; outcome = access.append(admission(access, id))
        }
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), outcome)
        assertEquals(id - 1, diskCount())
        assertEquals(ObservationId(id - 1), snapshot(access).lastAdmitted)
        assertFalse(HistoryTruncation.BYTE_CAP in snapshot(access).truncation)
        assertTrue(bytes() <= limits.bytes)
        interrupt = false
        val retried = access.append(admission(access, id)) as HistoryAppendOutcome.Stored
        assertTrue(HistoryTruncation.BYTE_CAP in retried.truncation)
        assertTrue(diskCount() < id)
        assertEquals(ObservationId(id), snapshot(access).lastAdmitted)
        assertTrue(snapshot(access).cutoffs[HistoryTruncation.BYTE_CAP]!!.ordinal > 0)
    }

    @Test fun timestampColumnCorruptionCannotExposeOrAgeDeleteRecentMeasurements() {
        val backend = store(); val access = created(backend)
        stored(access, 1, 0); stored(access, 2, 60); backend.close()
        mutate("UPDATE observations SET observed_seconds=0 WHERE ordinal=2")
        val reader = store(); val current = restored(reader, access.partition)
        assertEquals(HistoryReadOutcome.Corrupt, current.read(HistoryReadQuery(current.partition, 2)))
        assertEquals(HistoryAppendOutcome.Corrupt, current.append(admission(current, 3, 2_592_101)))
        assertEquals(2L, diskCount())
        mutate("UPDATE observations SET observed_nanos=NULL WHERE ordinal=2")
        assertEquals(HistoryAppendOutcome.Corrupt, current.append(admission(current, 3, 2_592_101)))
        assertEquals(2L, diskCount())
        mutate("UPDATE observations SET observed_seconds=${at.epochSecond + 60}, observed_nanos=${at.nano} WHERE ordinal=2")
        assertEquals(ObservationId(2), snapshot(current).lastAdmitted)
        stored(current, 3, 2_592_101)
        assertEquals(listOf(3L), snapshot(current).entries.map { it.id.ordinal })
        assertEquals(HistoryEvictionCutoff(2), snapshot(current).cutoffs[HistoryTruncation.AGE_RETENTION])
    }

    @Test fun failedDirectoryValidationNeverBecomesSuccessfulAdoptionOnRetry() {
        val limits = HistoryStorageLimits(bytes = 524_288)
        val backend = store(limits); val access = created(backend); stored(access, 1); backend.close()
        val lane = File(directory, "lane")
        RandomAccessFile(lane, "rw").use { it.setLength(limits.bytes) }
        val oversized = store(limits)
        repeat(2) { assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.STORAGE_FULL), oversized.adopt(access.partition)) }
        oversized.close()
        RandomAccessFile(lane, "rw").use { it.setLength(0) }
        val unexpected = File(directory, "unexpected")
        unexpected.writeBytes(byteArrayOf(1))
        val corrupt = store(limits)
        repeat(2) { assertEquals(HistoryAccessOutcome.Corrupt, corrupt.adopt(access.partition)) }
        corrupt.close(); assertTrue(unexpected.delete())
        val restored = restored(store(limits), access.partition)
        assertEquals(1L, diskCount()); assertEquals(ObservationId(1), snapshot(restored).lastAdmitted)
    }

    @Test fun orphanDatabaseWithEmptyBindingNeverTriggersImplicitDestructiveRecovery() {
        val backend = store(); val access = created(backend); stored(access, 1); backend.close()
        val original = database().readBytes()
        val removing = store(); val current = restored(removing, access.partition)
        assertEquals(HistoryDeleteOutcome.Deleted, current.delete(current.partition)); removing.close()
        database().writeBytes(original)
        val orphan = store()
        repeat(2) { assertEquals(HistoryAccessOutcome.Corrupt, orphan.createPartition()) }
        assertArrayEquals(original, database().readBytes()); orphan.close()
        val reopened = store()
        assertEquals(HistoryAccessOutcome.Corrupt, reopened.createPartition())
        assertArrayEquals(original, database().readBytes())
        assertEquals(HistoryDeleteOutcome.Deleted, reopened.quarantineAndDelete())
        assertFalse(database().exists())
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), reopened.adopt(access.partition))
        val fresh = created(reopened); stored(fresh, 1); assertEquals(1L, diskCount())
    }

    @Test fun corruptRowsAndSchemaFailClosedAndExplicitRecoveryKeepsPrivacyFence() {
        val backend = store(); val access = created(backend); stored(access, 1); backend.close()
        mutate("PRAGMA user_version=9")
        val future = store()
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.UNSUPPORTED_SCHEMA), future.adopt(access.partition))
        future.close()
        assertEquals(1L, diskCount())
        mutate("PRAGMA user_version=1")
        mutate("UPDATE observations SET payload=x'000102030405060708090A0B' WHERE ordinal=1")
        val reader = store(); val bound = restored(reader, access.partition)
        assertEquals(HistoryReadOutcome.Corrupt, bound.read(HistoryReadQuery(bound.partition, 1)))
        assertEquals(HistoryDeleteOutcome.Deleted, reader.quarantineAndDelete())
        assertFalse(database().exists())
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), reader.adopt(access.partition))
        val replacement = created(reader)
        assertNotEquals(access.partition, replacement.partition); assertEquals(HistoryContent.EMPTY, snapshot(replacement).content)
        reader.close()
        mutate("UPDATE state SET payload=x'000102030405060708090A0B' WHERE slot=1")
        val partial = store()
        assertEquals(HistoryAccessOutcome.Corrupt, partial.adopt(replacement.partition))
        assertEquals(HistoryDeleteOutcome.Deleted, partial.quarantineAndDelete())
        val renewed = created(partial); stored(renewed, 1); partial.close()
        RandomAccessFile(database(), "rw").use { it.seek(0); it.write(ByteArray(100) { 0x55 }) }
        val corrupt = store()
        assertEquals(HistoryAccessOutcome.Corrupt, corrupt.adopt(renewed.partition))
        assertEquals(HistoryDeleteOutcome.Deleted, corrupt.quarantineAndDelete())
        assertFalse(database().exists())
    }

    @Test fun deletionTombstoneSurvivesInterruptedCleanupAndFreshReopen() {
        var tombstones = 0
        val backend = store(hooks = object : HistoryStorageHooks {
            override fun afterTombstone() { tombstones++; throw IOException() }
        })
        val access = created(backend); stored(access, 1)
        val deletion = access.beginDelete()
        val failed = HistoryDeleteOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE)
        assertEquals(failed, deletion.complete()); assertEquals(failed, deletion.complete())
        assertEquals(1, tombstones); assertTrue(database().exists())
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), access.read(HistoryReadQuery(access.partition, 1)))
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), backend.adopt(access.partition))
        backend.close()
        val reopened = store()
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), reopened.adopt(access.partition))
        assertFalse(database().exists())
        val replacement = created(reopened); assertNotEquals(access.partition, replacement.partition)
        stored(replacement, 1); assertEquals(1L, diskCount())
        val removed = replacement.beginDelete()
        assertEquals(HistoryDeleteOutcome.Deleted, removed.complete()); assertEquals(HistoryDeleteOutcome.Deleted, removed.complete())
        assertFalse(database().exists()); assertEquals(32L, File(directory, "binding").length())
    }

    @Test fun heldWriteAdmissionAndDeletionCannotResurrectRemovedLifetime() {
        for (alreadyAdmitted in listOf(false, true)) {
            exerciseHeldCommit(alreadyAdmitted)
        }
    }

    private fun exerciseHeldCommit(alreadyAdmitted: Boolean) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val hooks = object : HistoryStorageHooks {
            private fun hold() { entered.countDown(); await("release held history write", release, "alreadyAdmitted=$alreadyAdmitted") }
            override fun beforeWriteAdmission() { if (!alreadyAdmitted) hold() }
            override fun afterWriteAdmission() { if (alreadyAdmitted) hold() }
        }
        val backend = store(hooks = hooks); val access = created(backend)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val write = workers.submit<HistoryAppendOutcome> { access.append(admission(access, 1)) }
            await("history write reached explicit gate", entered, "alreadyAdmitted=$alreadyAdmitted")
            val deletion = access.beginDelete() // Must return while actual storage is held.
            val removal = workers.submit<HistoryDeleteOutcome> { deletion.complete() }
            assertFalse("deletion settled before held commit", removal.isDone)
            release.countDown()
            val outcome = result("held history append settles", write)
            if (alreadyAdmitted) assertTrue(outcome is HistoryAppendOutcome.Stored)
            else assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), outcome)
            assertEquals(HistoryDeleteOutcome.Deleted, result("durable deletion follows held append", removal))
            assertFalse(database().exists())
            assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), backend.adopt(access.partition))
            val newer = created(backend); stored(newer, 1)
            assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), access.append(admission(access, 2)))
            assertEquals(1L, diskCount())
            newer.delete(newer.partition)
        } finally {
            release.countDown(); workers.shutdownNow()
            try {
                assertTrue("history workers terminate; last state=${workers.isTerminated}", workers.awaitTermination(10, TimeUnit.SECONDS))
            } finally { backend.close() }
        }
    }

    @Test fun interruptedBindingAndMissingBindingNeverCreateOrAdoptHistory() {
        val backend = store(); val access = created(backend); stored(access, 1); backend.close()
        File(directory, "binding.pending").writeBytes(byteArrayOf(1, 2, 3))
        val interrupted = store()
        assertEquals(HistoryAccessOutcome.Corrupt, interrupted.adopt(access.partition))
        assertEquals(HistoryAccessOutcome.Corrupt, interrupted.createPartition())
        assertEquals(HistoryDeleteOutcome.Deleted, interrupted.quarantineAndDelete())
        val fresh = created(interrupted); stored(fresh, 1); interrupted.close()
        assertTrue(File(directory, "binding").delete())
        val missing = store()
        assertEquals(HistoryAccessOutcome.Corrupt, missing.adopt(fresh.partition))
        assertEquals(HistoryDeleteOutcome.Deleted, missing.quarantineAndDelete())
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), missing.adopt(fresh.partition))
        assertFalse(database().exists())
    }

    @Test fun closeAndReadWriteFailuresAreCategoricalAndDoNotFabricateEmpty() {
        var fail = true
        val backend = store(hooks = object : HistoryStorageHooks {
            override fun beforeWriteAdmission() { if (fail) throw IOException() }
            override fun beforeRead() { if (fail) throw IOException() }
        })
        val access = created(backend)
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), access.append(admission(access, 1)))
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.READ_FAILURE), access.read(HistoryReadQuery(access.partition, 1)))
        fail = false; assertEquals(HistoryContent.EMPTY, snapshot(access).content)
        stored(access, 1); backend.close()
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.CLOSED), access.read(HistoryReadQuery(access.partition, 1)))
        assertEquals(HistoryAppendOutcome.Unavailable(HistoryUnavailable.CLOSED), access.append(admission(access, 2)))
        assertEquals(HistoryAccessOutcome.Unavailable(HistoryUnavailable.CLOSED), backend.adopt(access.partition))
        val reopen = store(); val current = restored(reopen, access.partition)
        assertEquals(1L, diskCount()); assertEquals(1, snapshot(current).entries.size)
    }

    @Test fun historyArtifactsUseNoBackupDirectoryWithMemoryTemporariesAndUnchangedBackupRules() {
        verifyNativeSchemaPragmaSetters()
        val application = ApplicationProvider.getApplicationContext<Context>()
        // Exercise the production default child, not the process owner's retained shared history.
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = fixtureRoot
        }
        assertSame("default open must retain the fixture's application context", context, context.applicationContext)
        assertTrue(fixtureRoot.path.startsWith(application.noBackupFilesDir.canonicalPath + File.separator))
        val defaultDirectory = File(context.noBackupFilesDir, "usage-history").canonicalFile
        assertFalse("default history directory must be a fresh synthetic fixture", defaultDirectory.exists())
        directory = defaultDirectory
        val backend = SQLiteHistoryStore.open(context).also { stores.add(it) }
        val access = created(backend); stored(access, 1)
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(directory.path.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertEquals(setOf("history.db", "binding", "lane"), directory.listFiles()!!.map { it.name }.toSet())
        SQLiteDatabase.openDatabase(database().path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            assertEquals("delete", db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) })
            assertEquals(1, db.version)
        }
        assertTrue(bytes() <= HistoryLimits.MAX_BYTES)
        access.delete(access.partition)
        assertEquals(setOf("binding", "lane"), directory.listFiles()!!.map { it.name }.toSet())
        assertFalse(database().exists())
    }

    /** Exercise production configuration without the store's safe exception-to-outcome boundary. */
    private fun verifyNativeSchemaPragmaSetters() {
        assertTrue("create independent synthetic schema fixture", directory.mkdirs())
        val partition = HistoryPartition(UUID(0, 41))
        val limits = HistoryStorageLimits(bytes = 524_288)
        val expected = HistoryDiskState(HistoryCursor(partition, lastOrdinal = 7))
        try {
            HistorySchema(database(), limits).use { schema ->
                openSchema("create", schema, partition, create = true)
                assertEquals(HistoryDiskState(HistoryCursor(partition)), schema.state(partition))
                assertEquals(0L, schema.count())
                assertTrue("configured page ceiling", schema.spacePages() in 0 until limits.databasePages)
                schema.transaction { schema.writeState(expected) }
            }
            HistorySchema(database(), limits).use { schema ->
                openSchema("reopen", schema, partition, create = false)
                assertEquals(expected, schema.state(partition))
                assertTrue("reopened page ceiling", schema.spacePages() in 0 until limits.databasePages)
            }
            assertEquals(setOf("history.db"), directory.listFiles()!!.map { it.name }.toSet())
        } finally { directory.deleteRecursively() }
        assertFalse("remove independent synthetic schema fixture", directory.exists())
    }

    private fun openSchema(step: String, schema: HistorySchema, partition: HistoryPartition, create: Boolean) {
        try { schema.open(partition, create) } catch (failure: Exception) {
            // Only fixed categories escape: never a database path, SQL payload or raw Throwable.
            val category = when (failure) {
                is SQLiteException -> if (failure.message?.contains("Queries can be performed using SQLiteDatabase query or rawQuery methods only.") == true) "SQLITE_NONQUERY_RESULT" else "SQLITE_OTHER"
                is HistoryCorruption -> "CORRUPT"
                is HistoryStorageException -> "UNAVAILABLE(${failure.reason})"
                is IOException -> "IO"
                else -> "OTHER"
            }
            throw AssertionError("native schema $step: failure=$category")
        }
    }

    private fun await(step: String, gate: CountDownLatch, last: String) {
        assertTrue("$step: timeout; last state=$last count=${gate.count}", gate.await(10, TimeUnit.SECONDS))
    }
    private fun <T> result(step: String, future: Future<T>): T = try {
        future.get(10, TimeUnit.SECONDS)
    } catch (failure: Exception) { throw AssertionError("$step: last state=done:${future.isDone}, cancelled:${future.isCancelled}", failure) }
}
