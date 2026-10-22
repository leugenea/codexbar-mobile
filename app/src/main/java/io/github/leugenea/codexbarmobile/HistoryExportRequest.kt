package io.github.leugenea.codexbarmobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Process-memory only: retain a user-requested snapshot across SAF/Activity recreation, never Bundle it. */
internal class HistoryExportRequest {
    var pending: String? = null
    val busy = MutableStateFlow(false)
    fun clear() { pending = null; busy.value = false }

    fun start(scope: CoroutineScope, prepare: suspend () -> String?, launch: () -> Unit, failure: () -> Unit) {
        if (busy.value) return
        // Arm cleanup before any dispatch or persistent state change can race recreation.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ensureActive()
                busy.value = true
                pending = prepare()
                if (pending == null) failure() else launch()
            } catch (cancelled: CancellationException) {
                clear()
                throw cancelled
            } catch (_: RuntimeException) {
                clear()
                failure()
            } finally {
                if (pending == null) clear()
            }
        }
    }

    fun finish(scope: CoroutineScope, write: suspend (String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val captured = pending
                pending = null
                ensureActive()
                write(captured)
            } finally {
                clear()
            }
        }
    }
}
