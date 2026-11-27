package io.github.leugenea.codexbarmobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Main-lane registry incarnation; neither an Activity reference nor saved state. */
internal class HistoryPickerOwner {
    val savedId: String = UUID.randomUUID().toString()
    var detached = false
        private set
    fun detach() { detached = true }
}

/** Process-memory only: retain a user-requested snapshot across SAF/Activity recreation, never Bundle it. */
internal class HistoryDocumentRequest<T : Any> {
    var pending: T? = null
        private set
    val busy = MutableStateFlow(false)
    private var awaiting: HistoryPickerOwner? = null

    fun rebind(restoredId: String, successor: HistoryPickerOwner) {
        val previous = awaiting ?: return
        if (previous.detached && previous.savedId == restoredId) awaiting = successor
    }

    fun retire(owner: HistoryPickerOwner) {
        if (awaiting === owner) clear()
    }

    private fun clear() { awaiting = null; pending = null; busy.value = false }

    fun start(owner: HistoryPickerOwner, scope: CoroutineScope, prepare: suspend () -> T?, launch: () -> Unit, failure: () -> Unit) {
        if (owner.detached || busy.value) return
        // Arm cleanup before any dispatch or persistent state change can race recreation.
        scope.launch(start = CoroutineStart.UNDISPATCHED) preparePicker@{
            var launched = false
            try {
                ensureActive()
                busy.value = true
                val prepared = prepare()
                ensureActive()
                if (owner.detached) return@preparePicker
                if (prepared != null) {
                    pending = prepared
                    awaiting = owner
                    launch()
                    launched = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // Preparation or platform launch failed; release before reporting failure.
            } finally {
                if (!launched) clear()
            }
            if (!launched) failure()
        }
    }

    fun finish(owner: HistoryPickerOwner, scope: CoroutineScope, write: suspend (T) -> Unit) {
        // Only the bound registry may consume this picker. Settling no longer has an Activity owner.
        if (owner.detached || awaiting !== owner) return
        val captured = pending ?: return
        awaiting = null
        pending = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ensureActive()
                write(captured)
            } finally {
                clear()
            }
        }
    }
}
