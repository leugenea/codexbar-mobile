package io.github.leugenea.codexbarmobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Process-memory only: retain a user-requested snapshot across SAF/Activity recreation, never Bundle it. */
internal class HistoryDocumentRequest<T : Any> {
    var pending: T? = null
        private set
    val busy = MutableStateFlow(false)
    private fun clear() { pending = null; busy.value = false }

    fun start(scope: CoroutineScope, prepare: suspend () -> T?, launch: () -> Unit, failure: () -> Unit) {
        if (busy.value) return
        // Arm cleanup before any dispatch or persistent state change can race recreation.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var launched = false
            try {
                ensureActive()
                busy.value = true
                pending = prepare()
                if (pending != null) {
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

    fun finish(scope: CoroutineScope, write: suspend (T) -> Unit) {
        // No result may consume another operation while it is preparing or settling.
        val captured = pending ?: return
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
