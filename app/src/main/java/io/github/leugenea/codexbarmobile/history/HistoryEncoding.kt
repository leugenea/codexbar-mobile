package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32

internal class HistoryFieldCapacity : IllegalArgumentException()

/** Private bounded binary schema, not Java serialization or a provider payload. */
internal object HistoryEncoding {
    const val MAX_BLOB = 16_384
    const val MAX_DECIMAL_PRECISION = 128
    private const val MAGIC = 0x48495331

    fun entry(value: HistoryEntry): ByteArray {
        if (value.windows.size > 2 || value.slots.size > 2) throw HistoryFieldCapacity()
        validateEntry(value)
        return pack {
            partition(value.partition); id(value.id); clock(value.clock); optional(value.observedAt) { instant(it) }
            optional(value.gap) { enumeration(it) }
            list(value.windows) { window(it) }; list(value.slots) { slot(it) }
            optional(value.allowed) { field(it) { bool(it) } }
            optional(value.limitReached) { field(it) { bool(it) } }
        }
    }

    fun entry(bytes: ByteArray): HistoryEntry = unpack(bytes) {
        val partition = partition(); val id = id(); val clock = clock()
        val at = optional { instant() }; val gap = optional { enumeration<HistoryGap>() }
        val windows = list(2) { window() }; val slots = list(2) { slot() }

        val entry = HistoryEntry(partition, id, clock, at, gap, windows, slots,
            optional { field { bool() } }, optional { field { bool() } })
        validateEntry(entry)
        entry
    }

    fun state(value: HistoryDiskState): ByteArray = pack {
        partition(value.cursor.partition); output.writeLong(value.cursor.lastOrdinal)
        tail(value.cursor.fiveHour); tail(value.cursor.weekly)
        optional(value.anchor) { instant(it.at); clock(it.clock) }; bool(value.ageSuspended)
        list(value.cutoffs.entries.toList()) { (reason, cutoff) -> enumeration(reason); output.writeLong(cutoff.ordinal) }
        optional(value.lastClock) { clock(it) }
    }

    fun state(bytes: ByteArray): HistoryDiskState = unpack(bytes) {
        val cursor = HistoryCursor(partition(), input.readLong(), tail(), tail())
        val anchor = optional { HistoryAgeAnchor(instant(), clock()) }; val suspended = bool()
        val cutoffs = list(3) { enumeration<HistoryTruncation>() to HistoryEvictionCutoff(input.readLong()) }
        val lastClock = optional { clock() }
        require(cutoffs.map { it.first }.distinct().size == cutoffs.size)
        validateCursor(cursor)
        require(cutoffs.all { it.second.ordinal <= cursor.lastOrdinal })
        HistoryDiskState(cursor, anchor, suspended, cutoffs.toMap(), lastClock)
    }

    private fun validateEntry(entry: HistoryEntry) {
        require(entry.windows.map { it.kind }.distinct().size == entry.windows.size)
        require(entry.slots.map { it.slot }.distinct().size == entry.slots.size)
        require(entry.gap == null || (entry.observedAt == null && entry.windows.isEmpty() && entry.slots.isEmpty()))
        if (entry.gap == null) require(entry.windows.size == 2 && entry.slots.size == 2)
        entry.windows.forEach { validateWindow(entry, it) }
    }

    private fun validateWindow(entry: HistoryEntry, window: HistoryWindow) {
        require(window.kind == WindowKind.FIVE_HOUR || window.kind == WindowKind.WEEKLY)
        window.percent?.value?.let { require(it >= BigDecimal.ZERO && it <= BigDecimal(100)) }
        window.point?.let { point ->
            require(point.observation == entry.id && point.observedAt == entry.observedAt && point.clock == entry.clock)
            require(point.segment.id.partition == entry.partition && point.segment.id.kind == window.kind)
            require(point.segment.id.first.ordinal <= entry.id.ordinal)
            require(point.usedPercent == window.percent?.value && point.baseline == window.baseline)
            validatePoint(point, entry.partition)
        }
    }

    private fun validateCursor(cursor: HistoryCursor) {
        require(cursor.fiveHour.lastPoint?.segment?.id?.kind?.let { it == WindowKind.FIVE_HOUR } != false)
        require(cursor.weekly.lastPoint?.segment?.id?.kind?.let { it == WindowKind.WEEKLY } != false)
        listOf(cursor.fiveHour, cursor.weekly).forEach { tail ->
            require(tail.lastCandidate == tail.lastPoint?.segment?.window)
            tail.lastCandidate?.let { require(it.partition == cursor.partition) }
            tail.lastPoint?.let {
                require(it.observation.ordinal <= cursor.lastOrdinal)
                validatePoint(it, cursor.partition)
            }
        }
    }

    private fun validatePoint(point: HistoryPoint, partition: HistoryPartition) {
        require(point.segment.id.partition == partition)
        require(point.usedPercent >= BigDecimal.ZERO && point.usedPercent <= BigDecimal(100))
        point.segment.window?.let { require(it.partition == partition && it.kind == point.segment.id.kind) }
    }

    private fun pack(block: HistoryBinaryWriter.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val writer = HistoryBinaryWriter(DataOutputStream(bytes))
        writer.output.writeInt(MAGIC); writer.block(); writer.output.flush()
        if (bytes.size() > MAX_BLOB - 8) throw HistoryFieldCapacity()
        val payload = bytes.toByteArray()
        writer.output.writeLong(CRC32().apply { update(payload) }.value)
        return bytes.toByteArray()
    }

    private fun <T> unpack(bytes: ByteArray, block: HistoryBinaryReader.() -> T): T {
        require(bytes.size in 12..MAX_BLOB)
        val length = bytes.size - 8
        val crc = DataInputStream(ByteArrayInputStream(bytes, length, 8)).readLong()
        require(crc == CRC32().apply { update(bytes, 0, length) }.value)
        val reader = HistoryBinaryReader(DataInputStream(ByteArrayInputStream(bytes, 0, length)))
        require(reader.input.readInt() == MAGIC)
        val result = reader.block()
        require(reader.input.available() == 0)
        return result
    }
}

internal class HistoryBinaryWriter(val output: DataOutputStream) {
    fun bool(value: Boolean) = output.writeByte(if (value) 1 else 0)
    fun enumeration(value: Enum<*>) = output.writeByte(value.ordinal)
    fun <T> optional(value: T?, write: (T) -> Unit) { bool(value != null); if (value != null) write(value) }
    fun <T> list(values: Collection<T>, write: (T) -> Unit) { output.writeByte(values.size); values.forEach(write) }
    fun partition(value: HistoryPartition) = uuid(value.value)
    private fun uuid(value: UUID) { output.writeLong(value.mostSignificantBits); output.writeLong(value.leastSignificantBits) }
    fun id(value: ObservationId) = output.writeLong(value.ordinal)
    fun instant(value: Instant) { output.writeLong(value.epochSecond); output.writeInt(value.nano) }
    fun clock(value: HistoryClock) { uuid(value.epoch.value); optional(value.monotonicMillis) { output.writeLong(it) } }
    fun decimal(value: BigDecimal) {
        if (value.precision() > HistoryEncoding.MAX_DECIMAL_PRECISION) throw HistoryFieldCapacity()
        val text = value.toString()
        if (text.length > 256) throw HistoryFieldCapacity()
        output.writeUTF(text)
    }
    fun <T> field(value: Field<T>, write: (T) -> Unit) {
        require(value.value == null || value.knowledge == Knowledge.KNOWN)
        enumeration(value.knowledge); optional(value.reason) { enumeration(it) }; optional(value.value, write)
    }
    private fun identity(value: WindowIdentity) {
        partition(value.partition); enumeration(value.kind); output.writeLong(value.durationSeconds); instant(value.resetAt)
    }
    private fun segment(value: HistorySegment) {
        partition(value.id.partition); enumeration(value.id.kind); id(value.id.first)
        optional(value.window) { identity(it) }; list(value.breaks) { enumeration(it) }
    }
    private fun point(value: HistoryPoint) {
        id(value.observation); instant(value.observedAt); clock(value.clock); decimal(value.usedPercent)
        segment(value.segment); enumeration(value.nominalStart); enumeration(value.baseline)
    }
    fun tail(value: WindowCursor) {
        optional(value.lastPoint) { point(it) }; optional(value.lastCandidate) { identity(it) }
        enumeration(value.nominalStart); optional(value.pendingBreak) { enumeration(it) }
    }
    private fun reset(value: HistoryReset) {
        enumeration(value.provenance)
        optional(value.facts) {
            field(it.absolute) { instant(it) }; field(it.relativeSeconds) { output.writeLong(it) }
            field(it.relativeDerived) { instant(it) }; bool(it.discrepant); optional(it.due) { bool(it) }
        }
    }
    fun window(value: HistoryWindow) {
        enumeration(value.kind); enumeration(value.selection)
        optional(value.duration) { field(it) { output.writeLong(it) } }
        optional(value.percent) { field(it) { decimal(it) } }; reset(value.reset)
        optional(value.point) { point(it) }; enumeration(value.baseline)
    }
    fun slot(value: HistorySlot) {
        enumeration(value.slot); enumeration(value.knowledge); optional(value.reason) { enumeration(it) }
        optional(value.kind) { enumeration(it) }; optional(value.duration) { field(it) { output.writeLong(it) } }
    }
}

internal class HistoryBinaryReader(val input: DataInputStream) {
    fun bool(): Boolean { val value = input.readUnsignedByte(); require(value <= 1); return value == 1 }
    inline fun <reified T : Enum<T>> enumeration(): T = enumValues<T>()[input.readUnsignedByte()]
    fun <T> optional(read: () -> T): T? = if (bool()) read() else null
    fun <T> list(maximum: Int, read: () -> T): List<T> {
        val count = input.readUnsignedByte(); require(count <= maximum); return List(count) { read() }
    }
    fun partition(): HistoryPartition = HistoryPartition(uuid())
    private fun uuid(): UUID = UUID(input.readLong(), input.readLong())
    fun id(): ObservationId = ObservationId(input.readLong())
    fun instant(): Instant { val seconds = input.readLong(); val nanos = input.readInt(); require(nanos in 0..999_999_999); return Instant.ofEpochSecond(seconds, nanos.toLong()) }
    fun clock(): HistoryClock = HistoryClock(ClockEpoch(uuid()), optional { input.readLong() })
    fun decimal(): BigDecimal {
        val size = input.readUnsignedShort(); require(size in 1..256)
        val text = ByteArray(size); input.readFully(text)
        val value = BigDecimal(String(text, Charsets.US_ASCII))
        require(value.precision() <= HistoryEncoding.MAX_DECIMAL_PRECISION)
        return value
    }
    fun <T> field(read: () -> T): Field<T> {
        val knowledge = enumeration<Knowledge>(); val reason = optional { enumeration<Reason>() }
        val value = optional(read)
        require(value == null || knowledge == Knowledge.KNOWN)
        return Field(knowledge, value, reason)
    }
    private fun identity(): WindowIdentity {
        val partition = partition(); val kind = enumeration<WindowKind>(); val seconds = input.readLong()
        require(seconds == when (kind) { WindowKind.FIVE_HOUR -> 18000L; WindowKind.WEEKLY -> 604800L; else -> -1L })
        return WindowIdentity(partition, kind, seconds, instant())
    }
    private fun segment(): HistorySegment {
        val id = SegmentIdentity(partition(), enumeration(), id())
        val window = optional { identity() }; val breaks = list(BreakReason.entries.size) { enumeration<BreakReason>() }
        require(breaks.distinct().size == breaks.size)
        return HistorySegment(id, window, breaks)
    }
    private fun point(): HistoryPoint = HistoryPoint(id(), instant(), clock(), decimal(), segment(), enumeration(), enumeration())
    fun tail(): WindowCursor = WindowCursor(optional { point() }, optional { identity() }, enumeration(), optional { enumeration() })
    private fun reset(): HistoryReset = HistoryReset(enumeration(), optional {
        ResetTime(field { instant() }, field { input.readLong() }, field { instant() }, bool(), optional { bool() })
    })
    fun window(): HistoryWindow = HistoryWindow(enumeration(), enumeration(), optional { field { input.readLong() } },
        optional { field { decimal() } }, reset(), optional { point() }, enumeration())
    fun slot(): HistorySlot = HistorySlot(enumeration(), enumeration(), optional { enumeration() },
        optional { enumeration() }, optional { field { input.readLong() } })
}
