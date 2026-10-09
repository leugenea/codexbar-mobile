package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.EndpointObservation
import io.github.leugenea.codexbarmobile.UsageRefreshState
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** One actual B2 cycle admission, not an emission/percentage/cache identity. Owner lane only. */
internal class HistoryObservationAdmission internal constructor(
    internal val session: SessionGeneration,
    internal val epoch: Long,
    internal val partition: HistoryPartition?,
) {
    internal var delivered = false
}

/**
 * Subordinate owner-lane inbox; at most [capacity] waiting observations and one blocking worker.
 * No timer, provider work, retry or backfill. Runtime revocation is nonblocking; the native port
 * rechecks its own irreversible admission fence. Already-admitted commits settle before deletion.
 */
internal class UsageHistoryRecorder(
    private val scope: CoroutineScope,
    private val storageDispatcher: CoroutineDispatcher,
    private val capacity: Int = 16,
) {
    private class Binding(val session: SessionGeneration, val access: HistoryRuntimeAccess) {
        val generation = HistoryGeneration()
        @Volatile var revoked = false
    }
    private data class Work(val binding: Binding, val clock: HistoryClock, val event: HistoryEvent, val gap: HistoryGap?)
    private data class Waiting(val session: SessionGeneration, val partition: HistoryPartition, val clock: HistoryClock,
        val event: HistoryEvent, val gap: HistoryGap?)
    private data class Result(val page: HistoryReadOutcome, val problem: HistoryRecorderProblem? = null,
        val reason: HistoryUnavailable? = null)
    private val mutableState = MutableStateFlow(HistoryGraphSnapshot())
    val state: StateFlow<HistoryGraphSnapshot> = mutableState
    private val clockEpoch = ClockEpoch(UUID.randomUUID())
    private val queue = ArrayDeque<Work>()
    private val waiting = ArrayDeque<Waiting>()
    private var lifetime: Pair<SessionGeneration, HistoryPartition>? = null
    private var bindingPending = false
    private var binding: Binding? = null
    private var epoch = 0L
    private var foreground = false
    private var pendingGap: HistoryGap? = null
    private var workerGap: HistoryGap? = null
    private var worker: Job? = null
    private var readPending = false
    private var query = HistoryGraphQuery()
    private var queryRevision = 0L
    private var live = HistoryLiveMetadata()
    private var page: HistoryReadSnapshot? = null
    private var problem: HistoryRecorderProblem? = null
    private var reason: HistoryUnavailable? = null
    private var lost = 0L

    init { require(capacity > 0) }

    fun foreground(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        if (!visible) invalidate(HistoryGap.BACKGROUND)
    }

    fun bind(session: SessionGeneration, access: HistoryRuntimeAccess?, pending: Boolean = false) {
        bindingPending = pending
        if (!foreground) return
        if (access == null) {
            if (!pending) discardWaiting()
            problem = problem ?: if (pending) null else HistoryRecorderProblem.STORAGE_UNAVAILABLE
            publish(if (pending && problem == null) HistoryReadiness.LOADING else HistoryReadiness.ERROR)
            return
        }
        if (binding?.access === access) return
        binding?.revoked = true
        val current = Binding(session, access)
        binding = current
        lifetime = session to access.partition
        adoptWaiting(current)
        problem = null
        reason = null
        page = null
        readPending = true
        publish(HistoryReadiness.LOADING)
        drain()
    }

    fun capture(session: SessionGeneration) = HistoryObservationAdmission(session, epoch,
        binding?.takeIf { it.session === session }?.access?.partition ?: lifetime?.takeIf { it.first === session }?.second)

    /** Called only after B2 accepted the actual endpoint delivery; failures contribute gaps, not points. */
    fun accept(admission: HistoryObservationAdmission, observation: EndpointObservation) {
        if (observation.operation != ReadOperation.USAGE || admission.delivered) return
        admission.delivered = true
        if (!foreground || admission.epoch != epoch) return
        val clock = HistoryClock(clockEpoch, observation.receivedAtMillis)
        if (observation.error != null || observation.usage == null) {
            pendingGap = HistoryGap.READ_ERROR
            return
        }
        val partition = admission.partition
        if (partition == null) { lose(HistoryRecorderProblem.STORAGE_UNAVAILABLE); return }
        enqueue(admission.session, partition, clock, HistoryEvent.Observed(observation.usage))
    }

    private fun discardWaiting() {
        while (waiting.isNotEmpty()) { waiting.removeFirst(); lose(HistoryRecorderProblem.STORAGE_UNAVAILABLE) }
    }

    private fun enqueue(session: SessionGeneration, partition: HistoryPartition, clock: HistoryClock, event: HistoryEvent) {
        if (queue.size + waiting.size >= capacity) { lose(HistoryRecorderProblem.QUEUE_OVERFLOW); return }
        val current = binding
        if (current != null && current.session === session && current.access.partition == partition)
            queue.addLast(Work(current, clock, event, pendingGap))
        else {
            val hint = lifetime
            if (!bindingPending || hint == null || hint.first !== session || hint.second != partition) { lose(HistoryRecorderProblem.STORAGE_UNAVAILABLE); return }
            waiting.addLast(Waiting(session, hint.second, clock, event, pendingGap))
        }
        pendingGap = null
        drain()
    }

    /** Only waiting for an in-progress runtime re-adoption, never retrying failed durable work. */
    private fun adoptWaiting(current: Binding) {
        while (waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            if (next.session === current.session && next.partition == current.access.partition)
                queue.addLast(Work(current, next.clock, next.event, next.gap))
            else lose(HistoryRecorderProblem.STORAGE_UNAVAILABLE)
        }
    }

    fun metadata(refresh: UsageRefreshState) {
        live = HistoryLiveMetadata.from(refresh)
        if (binding != null) publish(mutableState.value.readiness)
    }

    fun query(next: HistoryGraphQuery) {
        query = next
        queryRevision++
        page = null
        if (binding == null) { publish(HistoryReadiness.UNAVAILABLE); return }
        readPending = true
        publish(HistoryReadiness.LOADING)
        drain()
    }

    /** Cancel/logout/new login discard queued old facts. No later worker can publish them. */
    fun retire() {
        live = HistoryLiveMetadata()
        query = HistoryGraphQuery()
        queryRevision++
        invalidate(null)
        pendingGap = null
        workerGap = null
        lifetime = null
        lost = 0
        live = HistoryLiveMetadata()
        problem = null
        reason = null
        publish(HistoryReadiness.UNAVAILABLE)
    }

    private fun invalidate(gap: HistoryGap?) {
        epoch++
        binding?.revoked = true
        binding = null
        queue.clear()
        waiting.clear()
        readPending = false
        page = null
        pendingGap = gap
        publish(HistoryReadiness.UNAVAILABLE)
    }

    private fun lose(cause: HistoryRecorderProblem) {
        if (lost < Long.MAX_VALUE) lost++
        pendingGap = HistoryGap.READ_ERROR
        problem = cause
        reason = null
        publish(HistoryReadiness.ERROR)
    }

    private fun drain() {
        if (worker != null) return
        val current = binding ?: return
        val next = queue.removeFirstOrNull()
        if (next == null && !readPending) return
        readPending = false
        val requested = query
        val revision = queryRevision
        val gap = workerGap.also { workerGap = null }
        worker = scope.launch(start = CoroutineStart.LAZY) {
            val result = withContext(storageDispatcher) {
                perform(current, next, requested, gap)
            }
            finish(current, next, revision, result)
        }.also { it.start() }
    }

    private fun perform(current: Binding, work: Work?, requested: HistoryGraphQuery, gap: HistoryGap?): Result = try {
        if (work == null) Result(read(current, requested)) else write(work, requested, gap)
    } catch (_: Exception) {
        Result(HistoryReadOutcome.Unavailable(HistoryUnavailable.IO_FAILURE),
            if (work == null) HistoryRecorderProblem.READ_FAILURE else HistoryRecorderProblem.WRITE_FAILURE, HistoryUnavailable.IO_FAILURE)
    }

    private fun finish(current: Binding, work: Work?, revision: Long, result: Result) {
        worker = null
        if (binding !== current || current.revoked) { drain(); return }
        if (result.problem != null) {
            if (work != null) { if (lost < Long.MAX_VALUE) lost++; workerGap = HistoryGap.READ_ERROR }
            problem = result.problem
            reason = result.reason
        }
        if (revision == queryRevision) apply(result.page)
        drain()
    }

    private fun apply(outcome: HistoryReadOutcome) {
        when (outcome) {
            is HistoryReadOutcome.Ready -> {
                page = outcome.snapshot
                publish(if (problem != null) HistoryReadiness.ERROR else if (page!!.content == HistoryContent.EMPTY)
                    HistoryReadiness.EMPTY else HistoryReadiness.READY)
            }
            is HistoryReadOutcome.Unavailable -> {
                workerGap = HistoryGap.READ_ERROR
                problem = problem ?: HistoryRecorderProblem.READ_FAILURE
                reason = outcome.reason
                publish(HistoryReadiness.ERROR)
            }
            HistoryReadOutcome.Corrupt -> {
                workerGap = HistoryGap.READ_ERROR
                problem = HistoryRecorderProblem.CORRUPT
                publish(HistoryReadiness.ERROR)
            }
        }
    }

    private fun write(work: Work, requested: HistoryGraphQuery, failedGap: HistoryGap?): Result {
        val initial = read(work.binding, HistoryGraphQuery(limit = 1))
        if (initial !is HistoryReadOutcome.Ready) return Result(initial, HistoryRecorderProblem.READ_FAILURE)
        var ordinal = initial.snapshot.lastAdmitted?.ordinal ?: 0
        val gap = work.gap ?: failedGap
        if (gap != null) {
            val result = append(work, ordinal, HistoryEvent.Gap(gap))
            failure(result)?.let { return it }
            ordinal++
        }
        val result = append(work, ordinal, work.event)
        failure(result)?.let { return it }
        return Result(read(work.binding, requested))
    }

    private fun append(work: Work, ordinal: Long, event: HistoryEvent): HistoryAppendOutcome {
        if (work.binding.revoked) return HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        if (ordinal == Long.MAX_VALUE) return HistoryAppendOutcome.Unavailable(HistoryUnavailable.CAPACITY)
        return work.binding.access.append(HistoryAdmission(work.binding.access.partition, ObservationId(ordinal + 1), work.clock, event))
    }

    private fun failure(outcome: HistoryAppendOutcome): Result? = when (outcome) {
        is HistoryAppendOutcome.Stored, is HistoryAppendOutcome.AlreadyAdmitted -> null
        is HistoryAppendOutcome.Unavailable -> Result(HistoryReadOutcome.Unavailable(outcome.reason),
            if (outcome.reason == HistoryUnavailable.CAPACITY) HistoryRecorderProblem.ORDINAL_CAPACITY else HistoryRecorderProblem.WRITE_FAILURE, outcome.reason)
        is HistoryAppendOutcome.Rejected -> Result(HistoryReadOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE), HistoryRecorderProblem.WRITE_FAILURE)
        HistoryAppendOutcome.Corrupt -> Result(HistoryReadOutcome.Corrupt, HistoryRecorderProblem.CORRUPT)
    }

    private fun read(current: Binding, requested: HistoryGraphQuery): HistoryReadOutcome {
        if (current.revoked) return HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        return current.access.read(requested.storage(current.access.partition))
    }

    private fun publish(readiness: HistoryReadiness) {
        val current = binding
        mutableState.value = HistoryGraphSnapshot(readiness, current?.generation, current?.access?.partition,
            query, page, problem, reason, lost, live)
    }

    /** Process shutdown joins blocking work off the owner lane before the storage adapter closes. */
    suspend fun settled() { worker?.join() }
}
