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
        val request = HistoryDocumentRequest<HistoryImportTarget>()
        val target = HistoryImportTarget(SessionGeneration(LocalCredentialNamespace()), HistoryPartition(UUID(1, 2)))
        val old = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        request.start(old, prepare = { target }, launch = {}, failure = { fail("No failure") })
        old.cancel(); runCurrent()
        assertSame(target, request.pending)
        assertTrue(request.busy.value)
        val recreated = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        var read = false
        request.finish(recreated) { captured ->
            assertSame(target, captured)
            withContext(StandardTestDispatcher(testScheduler)) {
                read = true
            }
        }
        recreated.cancel(); runCurrent()
        assertFalse(request.busy.value)
        assertNull(request.pending)
        assertFalse(read)
    }

    @Test fun importAdmissionCancelledBeforeDispatchCannotOpenPickerOrStrandBusy() = runTest {
        val request = HistoryDocumentRequest<HistoryImportTarget>()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        request.start(scope, prepare = { withContext(StandardTestDispatcher(testScheduler)) { error("Cancelled preparation") } },
            launch = { fail("Cancelled picker") }, failure = { fail("Cancellation is not failure") })
        scope.cancel(); runCurrent()
        assertFalse(request.busy.value)
        assertNull(request.pending)
    }
}
