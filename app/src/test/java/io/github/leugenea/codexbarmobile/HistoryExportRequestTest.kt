package io.github.leugenea.codexbarmobile

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryExportRequestTest {
    @Test fun startCancellationBeforeFirstDispatchReleasesBusyAndAllowsRetry() = runTest {
        val request = HistoryExportRequest()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val io = StandardTestDispatcher(testScheduler, "preparation")
        var preparations = 0
        try {
            request.start(scope, prepare = { withContext(io) { preparations++; "snapshot" } },
                launch = { fail("Cancelled preparation must not open SAF") }, failure = { fail("Cancellation is not failure") })
            assertTrue(request.busy.value)
            assertEquals(0, preparations)
            scope.cancel() // No queued dispatcher work has executed.
            runCurrent()
            assertEquals(0, preparations)
            assertReusable(request)
        } finally {
            scope.cancel()
        }
    }

    @Test fun resultCancellationBeforeFirstDispatchClearsConsumedSnapshotAndAllowsRetry() = runTest {
        val request = HistoryExportRequest()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val io = StandardTestDispatcher(testScheduler, "destination")
        var writes = 0
        try {
            request.start(scope, prepare = { "snapshot" }, launch = {}, failure = { fail("Unexpected failure") })
            request.finish(scope) { captured ->
                assertEquals("snapshot", captured)
                withContext(io) { writes++ }
            }
            assertTrue(request.busy.value)
            assertNull(request.pending)
            assertEquals(0, writes)
            scope.cancel() // Consume the result, then recreate before destination dispatch.
            runCurrent()
            assertEquals(0, writes)
            assertReusable(request)
        } finally {
            scope.cancel()
        }
    }

    @Test fun pendingPickerSurvivesScopeCancellationAndDuplicateStartUntilNewScopeResult() = runTest {
        val request = HistoryExportRequest()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        var launches = 0
        try {
            request.start(scope, prepare = { "snapshot" }, launch = { launches++ }, failure = { fail("Unexpected failure") })
            request.start(scope, prepare = { error("Duplicate start must not prepare") },
                launch = { fail("Duplicate SAF") }, failure = { fail("Unexpected failure") })
            scope.cancel()
            runCurrent()
            assertEquals(1, launches)
            assertTrue(request.busy.value)
            assertEquals("snapshot", request.pending)
            request.finish(backgroundScope) { assertEquals("snapshot", it) }
            assertReusable(request)
        } finally {
            scope.cancel()
        }
    }

    @Test fun alreadyCancelledScopeCannotPrepareOrStrandConsumedResult() = runTest {
        val request = HistoryExportRequest()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        scope.cancel()
        request.start(scope, prepare = { error("Cancelled scope must not prepare") },
            launch = { fail("Cancelled scope must not open SAF") }, failure = { fail("Cancellation is not failure") })
        assertFalse(request.busy.value)
        request.start(backgroundScope, prepare = { "snapshot" }, launch = {}, failure = { fail("Unexpected failure") })
        request.finish(scope) { fail("Cancelled scope must not write") }
        assertReusable(request)
    }

    @Test fun preparationAndPickerFailuresReleaseBusyWithoutRetainingSnapshot() = runTest {
        val request = HistoryExportRequest()
        var failures = 0
        request.start(backgroundScope, prepare = { null }, launch = { fail("No document") }, failure = { failures++ })
        assertEquals(1, failures)
        assertReusable(request)
        request.start(backgroundScope, prepare = { "snapshot" }, launch = { throw IllegalStateException("Synthetic picker failure") },
            failure = { failures++ })
        assertEquals(2, failures)
        assertReusable(request)
    }

    private fun TestScope.assertReusable(request: HistoryExportRequest) {
        assertFalse(request.busy.value)
        assertNull(request.pending)
        request.start(backgroundScope, prepare = { "retry" }, launch = {}, failure = { fail("Retry failed") })
        assertTrue(request.busy.value)
        assertEquals("retry", request.pending)
        request.finish(backgroundScope) { assertEquals("retry", it) }
        assertFalse(request.busy.value)
        assertNull(request.pending)
    }
}
