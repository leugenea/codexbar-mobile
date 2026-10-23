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
    /** Only this recorder writes the slot on its existing owner lane. Readers never wait. */
    private class DisplayAuthority : HistoryDisplayAuthority {
        @Volatile private var context: HistoryDisplayContext? = null
        override fun current(): HistoryDisplayContext? = context
        fun install(generation: HistoryGeneration?) { context = HistoryDisplayContext(generation) }
        fun clear() { context = null }
        fun permission(): HistoryDisplayPermission? = context?.let { HistoryDisplayPermission(it, this) }
    }
    private class Binding(val session: SessionGeneration, val access: HistoryRuntimeAccess,
        val permission: HistoryDisplayPermission) {
        val generation = requireNotNull(permission.context.generation)
        val revoked get() = permission.current() !== permission.context
    }
    private data class Work(val binding: Binding, val clock: HistoryClock, val event: HistoryEvent, val gap: HistoryGap?)
    private data class Waiting(val session: SessionGeneration, val partition: HistoryPartition, val clock: HistoryClock,
        val event: HistoryEvent, val gap: HistoryGap?)
    private data class Presentation(val readiness: HistoryReadiness = HistoryReadiness.UNAVAILABLE,
        val page: HistoryReadSnapshot? = null, val currentPages: CurrentHistoryPages? = null,
        val failure: HistoryReadOutcome? = null)
    private data class Result(val presentation: Presentation? = null, val problem: HistoryRecorderProblem? = null,
        val reason: HistoryUnavailable? = null)
    private val mutableState = MutableStateFlow(HistoryGraphSnapshot())
    private val displayAuthority = DisplayAuthority()
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
    private var replacing = false
    private var request: HistoryPresentationRequest = HistoryPresentationRequest.Page(HistoryGraphQuery())
    private var selection = HistoryDisplaySelection(request)
    private val query get() = (request as? HistoryPresentationRequest.Page)?.query ?: HistoryGraphQuery()
    private var queryRevision = 0L
    private var refresh = UsageRefreshState()
    private var live = HistoryLiveMetadata()
    private var page: HistoryReadSnapshot? = null
    private var currentPages: CurrentHistoryPages? = null
    private var problem: HistoryRecorderProblem? = null
    private var reason: HistoryUnavailable? = null
    private var lost = 0L

    init { require(capacity > 0) }

    /** Reads the actual revocation slot even before a collector receives its first permission. */
    fun currentDisplayContext(): HistoryDisplayContext? = displayAuthority.current()

    fun foreground(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        if (!visible) invalidate(HistoryGap.BACKGROUND)
    }

    fun bind(session: SessionGeneration, access: HistoryRuntimeAccess?, pending: Boolean = false) {
        bindingPending = pending
        if (!foreground) return
        if (access == null) {
            // Preserve an existing Binding. With no Binding, even null-generation diagnostics
            // need a distinct current context so retirement cannot replay their loss accounting.
            if (displayAuthority.current() == null) installContext(null)
            if (!pending) discardWaiting()
            problem = problem ?: if (pending) null else HistoryRecorderProblem.STORAGE_UNAVAILABLE
            publish(if (pending && problem == null) HistoryReadiness.LOADING else HistoryReadiness.ERROR)
            return
        }
        if (binding?.access === access) return
        // The single replacement revokes predecessor workers before successor publication.
        installContext(HistoryGeneration())
        val current = Binding(session, access, requireNotNull(displayAuthority.permission()))
        binding = current
        current.permission.context.periods = currentHistoryPeriods(current.access.partition, refresh)
        lifetime = session to access.partition
        adoptWaiting(current)
        problem = null
        reason = null
        clearPages()
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
        this.refresh = refresh
        displayAuthority.current()?.periods = currentHistoryPeriods(binding?.access?.partition, refresh)
        live = HistoryLiveMetadata.from(refresh)
        if (binding != null) publish(mutableState.value.readiness)
    }

    /** UI interest is idempotent only after checking the actual context and current periods. */
    fun observe(next: HistoryPresentationRequest, expected: HistoryDisplayContext) {
        if (displayAuthority.current() !== expected || !next.matches(expected.periods)) return
        if (request == next) return
        select(next)
    }

    /** Explicit local reread, including equal Page values. No provider work or admission. */
    fun query(next: HistoryGraphQuery) = select(HistoryPresentationRequest.Page(next))

    /** Owner-lane barrier: drain the admitted read/append pair before replacement, then reread.
     * No recorder can carry a pre-import N+1 across the replacement transaction. */
    suspend fun importHistory(access: HistoryRuntimeAccess, text: String, now: java.time.Instant): HistoryImportOutcome {
        val current = binding?.takeIf { it.access === access && !it.revoked }
            ?: return HistoryImportOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        if (replacing) return HistoryImportOutcome.Unavailable(HistoryUnavailable.WRITE_FAILURE)
        replacing = true
        return try {
            worker?.join()
            val result = withContext(storageDispatcher) { access.importHistory(text, now) }
            if (result == HistoryImportOutcome.Imported && binding === current && !current.revoked) select(request)
            result
        } finally {
            replacing = false
            drain()
        }
    }

    private fun select(next: HistoryPresentationRequest) {
        request = next
        queryRevision++
        selection = HistoryDisplaySelection(next)
        displayAuthority.current()?.selection = selection
        clearPages()
        if (binding == null) { publish(HistoryReadiness.UNAVAILABLE); return }
        readPending = next != HistoryPresentationRequest.Idle
        publish(if (readPending) HistoryReadiness.LOADING else HistoryReadiness.UNAVAILABLE)
        drain()
    }

    private fun installContext(generation: HistoryGeneration?) {
        displayAuthority.install(generation)
        selection = HistoryDisplaySelection(request)
        displayAuthority.current()?.selection = selection
    }

    private fun clearPages() { page = null; currentPages = null }

    /** Cancel/logout/new login discard queued old facts. No later worker can publish them. */
    fun retire() {
        refresh = UsageRefreshState()
        live = HistoryLiveMetadata()
        request = HistoryPresentationRequest.Page(HistoryGraphQuery())
        selection = HistoryDisplaySelection(request)
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
        displayAuthority.clear()
        binding = null
        queue.clear()
        waiting.clear()
        readPending = false
        clearPages()
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
        if (worker != null || replacing) return
        val current = binding ?: return
        val next = queue.removeFirstOrNull()
        if (next == null && !readPending) return
        readPending = false
        val requested = selection
        val revision = queryRevision
        // Presentation-only drains must leave actual admission loss for the next Work.
        val gap = if (next != null) workerGap.also { workerGap = null } else null
        worker = scope.launch(start = CoroutineStart.LAZY) {
            val result = withContext(storageDispatcher) { perform(current, next, requested, gap) }
            finish(current, revision, requested, result)
        }.also { it.start() }
    }

    private fun perform(current: Binding, work: Work?, requested: HistoryDisplaySelection, gap: HistoryGap?): Result {
        if (work != null) {
            val admission = admit(work, gap)
            if (admission != null) return admission
        }
        return Result(presentation = readPresentation(current, requested))
    }

    private fun admit(work: Work, gap: HistoryGap?): Result? = try {
        write(work, gap)
    } catch (_: Exception) {
        Result(problem = HistoryRecorderProblem.WRITE_FAILURE, reason = HistoryUnavailable.IO_FAILURE)
    }

    private fun activePresentation(current: Binding, requested: HistoryDisplaySelection): Boolean =
        !current.revoked && current.permission.context.selection === requested && requested.request.matches(current.permission.context.periods)

    private fun finish(current: Binding, revision: Long, requested: HistoryDisplaySelection, result: Result) {
        worker = null
        if (binding !== current || current.revoked) { drain(); return }
        if (result.problem != null) admissionFailed(result)
        if (revision == queryRevision && activePresentation(current, requested)) result.presentation?.let(::apply)
        drain()
    }

    private fun admissionFailed(result: Result) {
        if (lost < Long.MAX_VALUE) lost++
        workerGap = HistoryGap.READ_ERROR
        problem = result.problem
        reason = result.reason
        publish(HistoryReadiness.ERROR)
    }

    private fun apply(result: Presentation) {
        val failure = result.failure
        if (failure != null) { presentationFailed(failure); return }
        page = result.page
        currentPages = result.currentPages
        publish(if (problem != null) HistoryReadiness.ERROR else result.readiness)
    }

    private fun presentationFailed(failure: HistoryReadOutcome) {
        workerGap = HistoryGap.READ_ERROR
        if (failure == HistoryReadOutcome.Corrupt) problem = HistoryRecorderProblem.CORRUPT
        else problem = problem ?: HistoryRecorderProblem.READ_FAILURE
        reason = (failure as? HistoryReadOutcome.Unavailable)?.reason
        publish(HistoryReadiness.ERROR)
    }

    private fun write(work: Work, failedGap: HistoryGap?): Result? {
        val initial = read(work.binding, HistoryGraphQuery(limit = 1))
        if (initial !is HistoryReadOutcome.Ready) return Result(problem = HistoryRecorderProblem.READ_FAILURE,
            reason = (initial as? HistoryReadOutcome.Unavailable)?.reason)
        var ordinal = initial.snapshot.lastAdmitted?.ordinal ?: 0
        val gap = work.gap ?: failedGap
        if (gap != null) {
            failure(append(work, ordinal, HistoryEvent.Gap(gap)))?.let { return it }
            ordinal++
        }
        return failure(append(work, ordinal, work.event))
    }

    private fun append(work: Work, ordinal: Long, event: HistoryEvent): HistoryAppendOutcome {
        if (work.binding.revoked) return HistoryAppendOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        if (ordinal == Long.MAX_VALUE) return HistoryAppendOutcome.Unavailable(HistoryUnavailable.CAPACITY)
        return work.binding.access.append(HistoryAdmission(work.binding.access.partition, ObservationId(ordinal + 1), work.clock, event))
    }

    private fun failure(outcome: HistoryAppendOutcome): Result? = when (outcome) {
        is HistoryAppendOutcome.Stored, is HistoryAppendOutcome.AlreadyAdmitted -> null
        is HistoryAppendOutcome.Unavailable -> Result(problem =
            if (outcome.reason == HistoryUnavailable.CAPACITY) HistoryRecorderProblem.ORDINAL_CAPACITY else HistoryRecorderProblem.WRITE_FAILURE,
            reason = outcome.reason)
        is HistoryAppendOutcome.Rejected -> Result(problem = HistoryRecorderProblem.WRITE_FAILURE, reason = HistoryUnavailable.WRITE_FAILURE)
        HistoryAppendOutcome.Corrupt -> Result(problem = HistoryRecorderProblem.CORRUPT)
    }

    private fun readPresentation(current: Binding, requested: HistoryDisplaySelection): Presentation = try {
        if (!activePresentation(current, requested)) Presentation()
        else when (val interest = requested.request) {
            HistoryPresentationRequest.Idle -> Presentation()
            is HistoryPresentationRequest.Page -> pagePresentation(read(current, interest.query), interest.query)
            is HistoryPresentationRequest.Current -> pairPresentation(CurrentHistoryRead.readCurrent(current.access, interest) {
                !activePresentation(current, requested)
            })
        }
    } catch (_: Exception) { Presentation(failure = HistoryReadOutcome.Unavailable(HistoryUnavailable.IO_FAILURE)) }

    private fun pagePresentation(outcome: HistoryReadOutcome, requested: HistoryGraphQuery): Presentation = when (outcome) {
        is HistoryReadOutcome.Ready -> {
            if (requested.currentOnly && requested.window == null) Presentation()
            else Presentation(if (outcome.snapshot.content == HistoryContent.EMPTY) HistoryReadiness.EMPTY else HistoryReadiness.READY, page = outcome.snapshot)
        }
        else -> Presentation(failure = outcome)
    }

    private fun pairPresentation(outcome: CurrentHistoryReadOutcome): Presentation = when (outcome) {
        is CurrentHistoryReadOutcome.Ready -> Presentation(pairReadiness(outcome.pages), currentPages = outcome.pages)
        is CurrentHistoryReadOutcome.Unavailable -> Presentation(failure = HistoryReadOutcome.Unavailable(outcome.reason))
        CurrentHistoryReadOutcome.Corrupt -> Presentation(failure = HistoryReadOutcome.Corrupt)
    }

    private fun pairReadiness(pages: CurrentHistoryPages): HistoryReadiness {
        val retained = listOfNotNull(pages.fiveHour, pages.weekly)
        if (retained.isEmpty()) return HistoryReadiness.UNAVAILABLE
        return if (retained.all { it.content == HistoryContent.EMPTY }) HistoryReadiness.EMPTY else HistoryReadiness.READY
    }

    private fun read(current: Binding, requested: HistoryGraphQuery): HistoryReadOutcome {
        if (current.revoked) return HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED)
        if (requested.currentOnly && requested.window == null) return HistoryReadOutcome.Ready(
            HistoryReadSnapshot(requested.storage(current.access.partition), emptyList(), null, false))
        if (requested.currentOnly) return CurrentHistoryRead.read(current.access, requested) { current.revoked }
        return current.access.read(requested.storage(current.access.partition))
    }

    private fun publish(readiness: HistoryReadiness) {
        val current = binding
        mutableState.value = HistoryGraphSnapshot(readiness, current?.generation, current?.access?.partition,
            query, page, problem, reason, lost, live, displayAuthority.permission(), request, selection, currentPages)
    }

    /** Process shutdown joins blocking work off the owner lane before the storage adapter closes. */
    suspend fun settled() { worker?.join() }
}
