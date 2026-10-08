package io.github.leugenea.codexbarmobile.credentials

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Commit value on the slot lane, wake collectors AFTER leaving it. MutableStateFlow's
 * normal setter can resume Unconfined collectors inline, so it cannot be used under the
 * ownership lock. Delayed wakeups always read the committed value, never replay old data.
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal class SequencedStateFlow<T>(private val ownership: SessionOwnership, initial: T) : StateFlow<T> {
    @Volatile private var committed = initial
    private val signals = MutableStateFlow(0L)
    private val revision = AtomicLong()
    override var value: T
        get() = committed
        set(next) {
            ownership.serialized {
                if (committed == next) return
                committed = next
                ownership.defer { signals.value = revision.incrementAndGet() }
            }
        }
    /** Busy admission and truthful storage failure may be delivered while removal is held. */
    internal fun notifyWithoutDeletionWait() {
        ownership.afterLane { signals.value = revision.incrementAndGet() }
    }

    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        var delivered = false
        var previous: T? = null
        signals.collect {
            // Capture an admitted snapshot. Emission runs outside the lane, just as an
            // already resumed StateFlow collector may finish after a later mutation.
            val current = ownership.serialized { value }
            if (!delivered || previous != current) {
                previous = current
                delivered = true
                collector.emit(current)
            }
        }
    }
}
