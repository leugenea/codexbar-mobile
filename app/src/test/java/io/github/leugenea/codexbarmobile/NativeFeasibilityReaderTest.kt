package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthClock
import io.github.leugenea.codexbarmobile.auth.AuthFake
import io.github.leugenea.codexbarmobile.auth.SyntheticAuth
import io.github.leugenea.codexbarmobile.credentials.SensitiveValue
import io.github.leugenea.codexbarmobile.transport.*
import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/** Original synthetic read outcomes; no sockets, real account or raw live payload. */
class NativeFeasibilityReaderTest {
    private val usage = """{"plan_type":"synthetic-private-plan","rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"used_percent":5,"limit_window_seconds":604800,"reset_at":1791756793},"secondary_window":null},"rate_limit_reset_credits":{"available_count":2}}"""
    private val inventory = """{"available_count":2,"credits":[{"id":"synthetic-private-id-a","reset_type":"codex_rate_limits","status":"available","granted_at":"2026-10-03T23:00:00Z","expires_at":"2026-10-22T20:31:56.833553Z"},{"id":"synthetic-private-id-b","reset_type":"codex_rate_limits","status":"available","granted_at":"2026-10-03T23:00:00Z","expires_at":"2026-10-29T18:58:37.564252Z"}]}"""

    private fun read(usageResult: TransportResult, inventoryResult: TransportResult): FeasibilityObservations = runBlocking {
        val fake = AuthFake()
        val clock = AuthClock()
        fake.respond = { call ->
            clock.millis += 1_000
            call.reply(if (call.request.url.encodedPath == ReadOperation.USAGE.path) usageResult else inventoryResult)
        }
        NativeFeasibilityReader(fake, clock, clock::pause).read(SensitiveValue.copyOf("synthetic-bearer".toByteArray()))
    }

    @Test fun productionDecoderPreservesOwnerVerifiedSemanticsAndSeparateClocks() {
        val result = read(SyntheticAuth.response(usage), SyntheticAuth.response(inventory))
        val usageAt = Instant.parse("2026-01-01T00:00:01Z")
        val inventoryAt = Instant.parse("2026-01-01T00:00:02Z")
        assertTrue(result.successful)
        assertEquals(ReadOperation.USAGE, result.usage.operation)
        assertEquals(ReadOperation.RESET_INVENTORY, result.inventory.operation)
        assertEquals(200, result.usage.status)
        assertEquals(200, result.inventory.status)
        assertEquals(usageAt, result.usage.observedAt)
        assertEquals(inventoryAt, result.inventory.observedAt)
        val normalizedUsage = result.usage.usage!!
        assertEquals(SelectionState.UNAVAILABLE, normalizedUsage.fiveHour.state)
        assertEquals(SelectionState.KNOWN, normalizedUsage.weekly.state)
        assertEquals(BigDecimal(5), normalizedUsage.weekly.candidates.single().usedPercent.value)
        assertEquals(Instant.ofEpochSecond(1791756793), normalizedUsage.weekly.candidates.single().reset.absolute.value)
        assertEquals(2L, normalizedUsage.bankedAvailableCount.value)
        val normalizedInventory = result.inventory.inventory!!
        assertEquals(usageAt, normalizedInventory.summaryObservedAt)
        assertEquals(inventoryAt, normalizedInventory.observedAt)
        assertEquals(2L, normalizedInventory.summaryAvailableCount.value)
        assertEquals(2L, normalizedInventory.reportedAvailableCount.value)
        assertEquals(2, normalizedInventory.inventoryRowCount)
        assertEquals(Completeness.COMPLETE, normalizedInventory.completeness)
        assertEquals(Instant.ofEpochSecond(1792701116, 833553000), normalizedInventory.items[0].value!!.expiresAt.value)
        assertEquals(Instant.ofEpochSecond(1793300317, 564252000), normalizedInventory.items[1].value!!.expiresAt.value)
        assertFalse(result.toString().contains("synthetic-private"))
        assertFalse(result.inventory.toString().contains("codex_rate_limits"))
        assertFalse(result.usage.toString().contains("synthetic-private-plan"))
    }

    @Test fun usageSuccessInventoryFailureRetainsUsageSummaryClockAndIndependentError() {
        for ((failure, error) in failures()) {
            val result = read(SyntheticAuth.response(usage), failure)
            assertFalse(result.successful)
            assertNull(result.usage.error)
            assertEquals(Instant.parse("2026-01-01T00:00:01Z"), result.usage.observedAt)
            assertEquals(2L, result.usage.usage!!.bankedAvailableCount.value)
            assertEquals(BigDecimal(5), result.usage.usage.weekly.candidates.single().usedPercent.value)
            assertEquals(error, result.inventory.error)
            assertNull(result.inventory.observedAt)
            assertNull(result.inventory.inventory)
            assertNull(result.inventory.inventory?.reportedAvailableCount)
            assertNull(result.inventory.inventory?.items)
        }
    }

    @Test fun inventorySuccessUsageFailureNeverBorrowsInventoryClockForSummary() {
        for ((failure, error) in failures()) {
            val result = read(failure, SyntheticAuth.response(inventory))
            assertFalse(result.successful)
            assertEquals(error, result.usage.error)
            assertNull(result.usage.observedAt)
            assertNull(result.usage.usage)
            assertNull(result.inventory.error)
            val normalized = result.inventory.inventory!!
            assertNotNull(normalized.observedAt)
            assertEquals(normalized.observedAt, result.inventory.observedAt)
            assertNull(normalized.summaryObservedAt)
            assertEquals(Reason.MISSING, normalized.summaryAvailableCount.reason)
            assertEquals(2L, normalized.reportedAvailableCount.value)
            assertEquals(2, normalized.inventoryRowCount)
            assertEquals(Completeness.COMPLETE, normalized.completeness)
            assertEquals(Instant.ofEpochSecond(1792701116, 833553000), normalized.items[0].value!!.expiresAt.value)
        }
    }

    @Test fun summaryAndInventoryMismatchSurvivesReaderWithoutOverwritingEitherCount() {
        val result = read(SyntheticAuth.response(usage), SyntheticAuth.response(inventory.replace("available_count\":2", "available_count\":3")))
        assertEquals(2L, result.usage.usage!!.bankedAvailableCount.value)
        assertEquals(3L, result.inventory.inventory!!.reportedAvailableCount.value)
        assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH, InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH), result.inventory.inventory.issues)
    }

    private fun failures(): List<Pair<TransportResult, ReadError>> = listOf(
        SyntheticAuth.response("{}", 401) to ReadError.REAUTHORIZE,
        SyntheticAuth.response("{}", 403) to ReadError.FORBIDDEN,
        SyntheticAuth.response("{}", 429, RetryAfter.NotBefore(100_000)) to ReadError.RATE_LIMITED,
        TransportResult.Failure(TransportFailure.NETWORK) to ReadError.TRANSIENT,
        TransportResult.Failure(TransportFailure.BODY_TOO_LARGE) to ReadError.BODY_TOO_LARGE,
        SyntheticAuth.response("not-json") to ReadError.INVALID_RESPONSE,
        SyntheticAuth.response("[]") to ReadError.INVALID_RESPONSE,
    )
}
