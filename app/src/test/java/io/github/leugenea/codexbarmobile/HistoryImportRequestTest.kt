package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.credentials.LocalCredentialNamespace
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.history.HistoryPartition
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryImportRequestTest {
    @Test fun importPickerKeepsOriginalLifetimeAcrossRecreationAndDropsCancelledRead() = runTest {
        val state = HistoryImporterState()
        val target = HistoryImportTarget(SessionGeneration(LocalCredentialNamespace()), HistoryPartition(UUID(1, 2)))
        val old = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        state.picker.start(old, prepare = { target }, launch = {}, failure = { fail("No failure") })
        old.cancel(); runCurrent()
        assertSame(target, state.picker.pending)
        assertTrue(state.picker.busy.value)
        val recreated = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        state.picker.finish(recreated) { captured ->
            assertSame(target, captured)
            withContext(StandardTestDispatcher(testScheduler)) {
                state.confirmation.value = HistoryImportSelection(requireNotNull(captured), "file")
            }
        }
        recreated.cancel(); runCurrent()
        assertFalse(state.picker.busy.value)
        assertNull(state.picker.pending)
        assertNull(state.confirmation.value)
    }

    @Test fun importAdmissionCancelledBeforeDispatchCannotOpenPickerOrStrandBusy() = runTest {
        val request = HistoryImporterState().picker
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        request.start(scope, prepare = { withContext(StandardTestDispatcher(testScheduler)) { error("Cancelled preparation") } },
            launch = { fail("Cancelled picker") }, failure = { fail("Cancellation is not failure") })
        scope.cancel(); runCurrent()
        assertFalse(request.busy.value)
        assertNull(request.pending)
    }
}
