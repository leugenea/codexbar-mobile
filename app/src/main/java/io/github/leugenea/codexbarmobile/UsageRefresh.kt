package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.transport.*
import io.github.leugenea.codexbarmobile.usage.UsageWindow
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Attempts and retained successes are orthogonal, including partial inventory successes. */
internal data class RefreshedEndpoint(
    val attempt: EndpointObservation? = null,
    val success: EndpointObservation? = null,
    val successfulAtMillis: Long? = null,
    val stale: Boolean = false,
) {
    fun observation(operation: ReadOperation) = EndpointObservation(operation,
        status = attempt?.status, observedAt = success?.observedAt, error = attempt?.error,
        usage = success?.usage, inventory = success?.inventory, notBeforeMillis = attempt?.notBeforeMillis)
}

/** In-memory only. B1 remains the sole reset arithmetic/formatting implementation. */
internal data class UsageRefreshState(
    val usage: RefreshedEndpoint = RefreshedEndpoint(),
    val inventory: RefreshedEndpoint = RefreshedEndpoint(),
    val evaluatedAt: Instant? = null,
    val refreshing: Boolean = false,
) {
    val observations: FeasibilityObservations? get() = if (usage.attempt == null && inventory.attempt == null) null
        else FeasibilityObservations(usage.observation(ReadOperation.USAGE), inventory.observation(ReadOperation.RESET_INVENTORY))

    fun reset(window: UsageWindow, zone: ZoneId, locale: Locale): PresentedTime =
        TimePresentation.presentReset(window.reset, usage.success?.observedAt, evaluatedAt, zone, locale, usage.stale)
}

/**
 * Subordinate scheduler on ConnectionController's lane/scope, never a session owner.
 * Authenticated reads and generation admission remain the owner's responsibility.
 * A fresh restored session is dormant until explicit foreground or enrolled background admission.
 */
internal class UsageRefresh(
    private val scope: CoroutineScope,
    private val clock: TransportClock,
    private val read: suspend (Boolean, Boolean, (EndpointObservation) -> Unit, (Long) -> Unit) -> RefreshReadCompletion?,
    private val changed: (UsageRefreshState) -> Unit,
) {
    var state = UsageRefreshState()
        private set
    private val observers = mutableSetOf<Any>()
    private var lifecycleManaged = false
    private var activated = false
    private var epoch = 0L
    private var flight: UsageRefreshFlight? = null
    private var poll: Job? = null
    private var ticks: Job? = null
    private var pending = false
    private var forceSession = false
    private var explicitRead = false
    private var nextPollMillis = 0L
    private var notBeforeMillis = 0L
    private val visible get() = observers.isNotEmpty()
    private val eligible get() = !lifecycleManaged || visible
    internal val foregroundEligible get() = eligible
    internal val hasForegroundObservers get() = visible
    /** Current-flight network waiters, not process-owned recording interests. */
    internal val backgroundInterests get() = flight?.background ?: 0
    internal val deferredUntilMillis get() = notBeforeMillis.takeIf { clock.now().monotonicMillis < it }

    /** Both endpoints must be recent; failures and clock rollback never masquerade as freshness. */
    internal fun recentSuccess(ageMillis: Long): Boolean {
        if (state.refreshing || ageMillis <= 0) return false
        val now = clock.now().monotonicMillis
        return listOf(state.usage, state.inventory).all { endpoint ->
            val at = endpoint.successfulAtMillis
            endpoint.attempt?.error == null && at != null && now >= at && now - at < ageMillis
        }
    }

    fun foreground(observer: Any, foreground: Boolean) {
        val wasVisible = visible
        lifecycleManaged = true
        if (foreground) observers.add(observer) else observers.remove(observer)
        if (visible == wasVisible) return
        if (visible) {
            flight?.foreground = true
            if (activated) { reevaluate(); startTicks(); request() }
        } else {
            poll?.cancel(); poll = null
            ticks?.cancel(); ticks = null
            flight?.let { it.foreground = false; cancelUnobserved(it) }
        }
    }

    /** Duplicate triggers during a healthy flight are consumed, not queued as another read. */
    fun request(refreshSession: Boolean = false, explicit: Boolean = false) {
        activated = true
        if (visible && ticks == null) startTicks()
        if (flight?.job?.isActive == true) { flight?.foreground = eligible; return }
        pending = true
        forceSession = forceSession || refreshSession
        explicitRead = explicitRead || explicit
        drain()
    }

    private fun drain() {
        if (!pending || !eligible || flight != null) return
        if (clock.now().monotonicMillis < notBeforeMillis) {
            // A pending explicit/resume read wakes at the boundary, not an older poll floor.
            poll?.cancel(); poll = null
            schedule(); return
        }
        val force = forceSession.also { forceSession = false }
        val explicit = explicitRead.also { explicitRead = false }
        pending = false
        startFlight(force, explicit, foreground = true)
    }

    /** One bounded trigger; never activates foreground ticks, polling or explicit auth retry. */
    fun retainBackgroundFlight(): UsageRefreshFlight? = flight?.takeIf { it.job.isActive }?.also { it.background++ }

    fun background(): UsageRefreshFlight? {
        retainBackgroundFlight()?.let { return it }
        if (flight != null) return null
        if (deferredUntilMillis != null) return null
        return startFlight(false, false, foreground = visible).also { it.background++ }
    }

    fun releaseBackground(current: UsageRefreshFlight) {
        check(current.background > 0)
        current.background--
        cancelUnobserved(current)
    }

    private fun cancelUnobserved(current: UsageRefreshFlight) {
        if (!current.foreground && current.background == 0) current.job.cancel()
    }

    private fun startFlight(force: Boolean, explicit: Boolean, foreground: Boolean): UsageRefreshFlight {
        val owner = epoch
        val current = UsageRefreshFlight(foreground)
        nextPollMillis = saturatedAdd(clock.now().monotonicMillis, POLL_MILLIS)
        poll?.cancel(); poll = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = read(force, explicit, { observation ->
                currentCoroutineContextCheck(owner)
                accept(observation)
            }, { at ->
                currentCoroutineContextCheck(owner)
                notBeforeMillis = maxOf(notBeforeMillis, at)
            })
            current.result.complete(result)
        }
        current.job = job
        flight = current
        // Completion also runs for cancellation before the coroutine body ever entered.
        job.invokeOnCompletion { current.result.complete(null); scope.launch { finished(current, owner) } }
        update(state.copy(refreshing = true))
        job.start()
        return current
    }

    private fun finished(current: UsageRefreshFlight, owner: Long) {
        if (flight !== current) return
        flight = null
        if (epoch == owner) update(state.copy(refreshing = false))
        drain()
        schedule()
    }

    private fun currentCoroutineContextCheck(owner: Long) {
        if (owner != epoch || flight?.job?.isActive != true) throw CancellationException()
    }

    private fun accept(observation: EndpointObservation) {
        val now = clock.now()
        observation.notBeforeMillis?.let { notBeforeMillis = maxOf(notBeforeMillis, it) }
        val previous = if (observation.operation == ReadOperation.USAGE) state.usage else state.inventory
        val endpoint = if (observation.error == null) RefreshedEndpoint(observation, observation, now.monotonicMillis)
            else previous.copy(attempt = observation)
        update(if (observation.operation == ReadOperation.USAGE) state.copy(usage = endpoint, evaluatedAt = now.wall)
            else state.copy(inventory = endpoint, evaluatedAt = now.wall))
    }

    private fun schedule() {
        if (!activated || !eligible || (!visible && !pending) || flight != null || poll != null) return
        val at = maxOf(if (pending) 0L else nextPollMillis, notBeforeMillis)
        poll = scope.launch {
            delay((at - clock.now().monotonicMillis).coerceAtLeast(0))
            poll = null
            request()
        }
    }

    private fun startTicks() {
        ticks = scope.launch {
            while (true) { delay(TICK_MILLIS); reevaluate() }
        }
    }

    private fun reevaluate() {
        val now = clock.now()
        update(state.copy(usage = stale(state.usage, now.monotonicMillis),
            inventory = stale(state.inventory, now.monotonicMillis), evaluatedAt = now.wall))
    }

    private fun stale(endpoint: RefreshedEndpoint, now: Long): RefreshedEndpoint {
        val observed = endpoint.successfulAtMillis
        return endpoint.copy(stale = observed != null && now >= saturatedAdd(observed, STALE_MILLIS))
    }

    private fun update(next: UsageRefreshState) { state = next; changed(next) }

    /** Synchronous invalidation, before any successor can be adopted on the owner lane. */
    fun reset() {
        epoch++
        activated = false
        pending = false
        forceSession = false
        explicitRead = false
        notBeforeMillis = 0
        nextPollMillis = 0
        ticks?.cancel(); ticks = null
        poll?.cancel(); poll = null
        flight?.job?.cancel()
        update(UsageRefreshState(evaluatedAt = clock.now().wall))
    }

    fun close() {
        reset()
        ticks?.cancel(); ticks = null
        observers.clear()
    }

    internal companion object {
        const val POLL_MILLIS = 60_000L
        const val STALE_MILLIS = 900_000L
        const val TICK_MILLIS = 1_000L
    }
}
