package io.github.leugenea.codexbarmobile.usage

import io.github.leugenea.codexbarmobile.transport.TransportFailure
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BankedResetResponseParserTest {
    private val now = M0Fixtures.clock
    private val usage = M0FixtureAdapters.observation(UsageResponseParser.parse(
        M0FixtureAdapters.body("""{"rate_limit_reset_credits":{"available_count":2}}"""), now.minusSeconds(3)))
    private fun parse(text: String, summary: UsageObservation? = usage, observed: Instant? = now, evaluated: Instant? = now) =
        M0FixtureAdapters.observation(BankedResetResponseParser.parse(M0FixtureAdapters.body(text), summary, observed, evaluated))
    private fun item(id: String = "synthetic-a", expiry: String = "\"2026-10-05T23:00:00Z\"", extra: String = "") =
        """{"id":"$id","reset_type":"codex_rate_limits","status":"available","granted_at":"2026-10-03T23:00:00Z","expires_at":$expiry$extra}"""
    private fun inventory(vararg items: String, count: String = "2") =
        """{"available_count":$count,"credits":[${items.joinToString(",")}]}"""

    @Test fun upstreamInventoryMockDecodesUnchangedWithIndependentCountAndGrantExpiry() {
        val result = M0FixtureAdapters.observation(BankedResetResponseParser.parse(
            M0FixtureAdapters.mock("upstream-test-reset-details.json"), usage, now, Instant.parse("2026-06-20T00:00:00Z")))
        assertEquals(now.minusSeconds(3), result.summaryObservedAt)
        assertEquals(now, result.observedAt)
        assertEquals(2L, result.summaryAvailableCount.value)
        assertEquals(2L, result.reportedAvailableCount.value)
        assertEquals(2, result.inventoryRowCount)
        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(listOf("credit-1", "credit-2"), result.items.map { it.value!!.id.value })
        val first = result.items[0].value!!
        assertEquals("codex_rate_limits", first.resetType.value)
        assertEquals("available", first.providerStatus.value)
        assertEquals(Instant.parse("2026-06-17T00:00:00Z"), first.grantedAt.value)
        assertEquals(Instant.parse("2026-07-17T00:00:00Z"), first.expiresAt.value)
        assertEquals(false, first.locallyExpired)
        assertEquals(Instant.parse("2026-06-18T00:00:00Z"), result.items[1].value!!.grantedAt.value)
        assertEquals(Reason.PROVIDER_NULL, result.items[1].value!!.expiresAt.reason)
        assertNull(result.items[1].value!!.locallyExpired)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun allM0InventoryPayloadVectorsDecodeWithoutTreatingHarnessStatusAsWire() {
        val vectors = M0FixtureAdapters.vectors().filter { it["rawResetDetails"] != JsonNull }
        assertEquals(5, vectors.size)
        for (vector in vectors) {
            val raw = vector.getValue("rawResetDetails") as JsonObject
            if (raw.containsKey("http_status")) continue // A2 read policy, not a successful provider body.
            val expected = (vector.getValue("expected") as JsonObject).getValue("bankedResets") as JsonObject
            val summary = M0FixtureAdapters.observation(UsageResponseParser.parse(
                M0FixtureAdapters.body(vector.getValue("rawUsage").toString()), now.minusSeconds(3)))
            val result = parse(raw.toString(), summary)
            assertEquals((expected["summaryAvailableCount"] as JsonPrimitive).content.toLongOrNull(), result.summaryAvailableCount.value)
            assertEquals((expected["reportedAvailableCount"] as JsonPrimitive).content.toLongOrNull(), result.reportedAvailableCount.value)
            when ((vector.getValue("caseId") as JsonPrimitive).content) {
                "banked-distinct-from-purchased" -> {
                    assertEquals(2, result.inventoryRowCount)
                    assertEquals(Reason.PROVIDER_NULL, result.items[1].value!!.expiresAt.reason)
                    assertTrue(result.issues.isEmpty())
                }
                "expiry-reported-count-conflict" -> {
                    assertEquals(true, result.items.single().value!!.locallyExpired)
                    assertEquals(setOf(InventoryIssue.EXPIRED_AVAILABLE_ITEM), result.issues)
                }
                "banked-malformed-count" -> {
                    assertEquals(Reason.OUT_OF_RANGE, result.reportedAvailableCount.reason)
                    assertEquals(0, result.inventoryRowCount)
                }
                "unknown-entitlement-status" -> {
                    assertEquals("future_status", result.items.single().value!!.providerStatus.value)
                    assertEquals(setOf(InventoryIssue.UNKNOWN_STATUS), result.issues)
                }
                else -> fail("Unasserted M0 inventory vector")
            }
        }
    }

    @Test fun missingNullWrongTypeInventoryAndCountsPreserveSummary() {
        for ((text, reason) in listOf("{}" to Reason.MISSING,
            """{"available_count":null,"credits":null}""" to Reason.PROVIDER_NULL,
            """{"available_count":true,"credits":2}""" to Reason.WRONG_TYPE,
            """{"available_count":"2","credits":{}}""" to Reason.WRONG_TYPE)) {
            val result = parse(text)
            assertEquals(reason, result.reportedAvailableCount.reason)
            assertNull(result.reportedAvailableCount.value)
            assertNull(result.inventoryRowCount)
            assertEquals(Completeness.UNKNOWN, result.completeness)
            assertEquals(2L, result.summaryAvailableCount.value)
            assertEquals(now.minusSeconds(3), result.summaryObservedAt)
            assertEquals(now, result.observedAt)
        }
        val empty = parse(inventory(count = "0"))
        assertEquals(0L, empty.reportedAvailableCount.value)
        assertEquals(0, empty.inventoryRowCount)
        assertEquals(Completeness.COMPLETE, empty.completeness)
        assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH), empty.issues)
        for (bad in listOf("-1", "0.5", "9223372036854775808", "[]", "{}")) {
            val result = parse(inventory(item(), count = bad))
            assertEquals(Knowledge.MALFORMED, result.reportedAvailableCount.knowledge)
            assertNull(result.reportedAvailableCount.value)
            assertEquals(1, result.inventoryRowCount)
            assertEquals("synthetic-a", result.items.single().value!!.id.value)
        }
        assertEquals(2L, parse(inventory(item(), item("synthetic-b"), count = "2.0")).reportedAvailableCount.value)
    }

    @Test fun malformedItemsAndMissingRequiredFieldsPreserveGoodSiblingAndPartialState() {
        val result = parse(inventory("true", "[]", "null", "{}", item()))
        assertEquals(5, result.inventoryRowCount)
        assertEquals(Completeness.PARTIAL, result.completeness)
        assertEquals(Knowledge.MALFORMED, result.items[0].knowledge)
        assertEquals(Reason.WRONG_TYPE, result.items[1].reason)
        assertEquals(Reason.PROVIDER_NULL, result.items[2].reason)
        val empty = result.items[3].value!!
        assertTrue(listOf(empty.id, empty.resetType, empty.providerStatus, empty.grantedAt, empty.expiresAt).all { it.reason == Reason.MISSING })
        assertEquals("synthetic-a", result.items[4].value!!.id.value)
        assertFalse(result.issues.contains(InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH))
        val wrong = parse(inventory("""{"id":42,"reset_type":false,"status":{},"granted_at":1791154800,"expires_at":true}""", item()))
        val malformed = wrong.items[0].value!!
        assertTrue(listOf(malformed.id, malformed.resetType, malformed.providerStatus, malformed.grantedAt, malformed.expiresAt).all {
            it.knowledge == Knowledge.MALFORMED && it.reason == Reason.WRONG_TYPE
        })
        assertEquals("synthetic-a", wrong.items[1].value!!.id.value)
        assertEquals(2L, wrong.reportedAvailableCount.value)
        assertEquals(Completeness.PARTIAL, wrong.completeness)
        val nullable = parse(inventory("""{"id":null,"reset_type":null,"status":null,"granted_at":null,"expires_at":null}"""))
        val nullItem = nullable.items.single().value!!
        assertTrue(listOf(nullItem.id, nullItem.resetType, nullItem.providerStatus, nullItem.grantedAt, nullItem.expiresAt).all { it.reason == Reason.PROVIDER_NULL })
        assertNull(nullItem.locallyExpired)
    }

    @Test fun utcZeroThroughSixFractionsPreservePrecisionWithStrictCalendarAndPositiveEpoch() {
        for (fraction in listOf("", ".1", ".12", ".123", ".1234", ".12345", ".123456")) {
            val date = "2026-10-05T23:00:00${fraction}Z"
            assertEquals(Instant.parse(date), parse(inventory(item(expiry = "\"$date\""))).items.single().value!!.expiresAt.value)
        }
        for (bad in listOf("2026-02-30T00:00:00Z", "2026-12-31T23:59:60Z", "2026-10-05T24:00:00Z",
            "0000-01-01T00:00:00Z", "1969-12-31T23:59:59Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00.000001Z")) {
            val result = parse(inventory(item(expiry = "\"$bad\""), item("synthetic-b")))
            val first = result.items[0].value!!
            assertEquals(Reason.OUT_OF_RANGE, first.expiresAt.reason)
            assertNull(first.locallyExpired)
            assertEquals(Completeness.PARTIAL, result.completeness)
            assertEquals(Instant.parse("2026-10-05T23:00:00Z"), result.items[1].value!!.expiresAt.value)
            assertEquals(2L, result.reportedAvailableCount.value)
        }
        assertEquals(Instant.ofEpochSecond(1, 1000), parse(inventory(item(expiry = "\"1970-01-01T00:00:01.000001Z\""))).items.single().value!!.expiresAt.value)
        val badGrant = parse(inventory(item().replace("2026-10-03T23:00:00Z", "1970-01-01T00:00:00Z"))).items.single().value!!
        assertEquals(Reason.OUT_OF_RANGE, badGrant.grantedAt.reason)
        assertEquals(Knowledge.KNOWN, badGrant.expiresAt.knowledge)
    }

    @Test fun missingNullUnsupportedAndWrongTypeExpiryNeverMeanInfiniteEntitlement() {
        val missing = parse(inventory("""{"id":"synthetic-a","reset_type":"codex_rate_limits","status":"available","granted_at":"2026-10-03T23:00:00Z"}""")).items.single().value!!
        assertEquals(Reason.MISSING, missing.expiresAt.reason)
        assertNull(missing.locallyExpired)
        for ((text, knowledge, reason) in listOf(
            Triple("null", Knowledge.UNAVAILABLE, Reason.PROVIDER_NULL),
            Triple("\"2026-10-05T23:00:00+00:00\"", Knowledge.UNSUPPORTED, Reason.UNSUPPORTED_FORMAT),
            Triple("\"2026-10-05T23:00:00.1234567Z\"", Knowledge.UNSUPPORTED, Reason.UNSUPPORTED_FORMAT),
            Triple("1791241200", Knowledge.MALFORMED, Reason.WRONG_TYPE),
        )) {
            val result = parse(inventory(item(expiry = text))).items.single().value!!
            assertEquals(knowledge, result.expiresAt.knowledge)
            assertEquals(reason, result.expiresAt.reason)
            assertNull(result.expiresAt.value)
            assertNull(result.locallyExpired)
            assertEquals("available", result.providerStatus.value)
        }
    }

    @Test fun duplicateIdentityFutureTypeStatusAndCountDiscrepanciesStayExplicit() {
        val duplicate = parse(inventory(item(), item()))
        assertEquals(2, duplicate.inventoryRowCount)
        assertEquals(setOf(InventoryIssue.DUPLICATE_IDENTITY), duplicate.issues)
        val future = parse(inventory(item().replace("codex_rate_limits", "future_type").replace("available", "future_status"), count = "0"))
        assertEquals("future_type", future.items.single().value!!.resetType.value)
        assertEquals("future_status", future.items.single().value!!.providerStatus.value)
        assertEquals(setOf(InventoryIssue.UNKNOWN_STATUS, InventoryIssue.UNKNOWN_RESET_TYPE, InventoryIssue.SUMMARY_COUNT_MISMATCH), future.issues)
        val mismatch = parse(inventory(item(), item("synthetic-b"), count = "3"))
        assertEquals(2L, mismatch.summaryAvailableCount.value)
        assertEquals(3L, mismatch.reportedAvailableCount.value)
        assertEquals(2, mismatch.inventoryRowCount)
        assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH, InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH), mismatch.issues)
        val expired = parse(inventory(item(expiry = "\"2026-10-04T23:00:00Z\""), count = "1"))
        assertEquals(true, expired.items.single().value!!.locallyExpired)
        assertEquals(1L, expired.reportedAvailableCount.value)
        assertEquals("available", expired.items.single().value!!.providerStatus.value)
        assertTrue(expired.issues.contains(InventoryIssue.EXPIRED_AVAILABLE_ITEM))
    }

    @Test fun ownerReceiptIsOnlySemanticInputAndNeverAReconstructedBackendBody() {
        // Original synthetic rows use the two allowlisted date strings as regressions.
        // No owner IDs, raw response reconstruction or documentation-receipt clock.
        val text = inventory(item(expiry = "\"${M0Fixtures.ownerExpiries[0]}\""),
            item("synthetic-b", "\"${M0Fixtures.ownerExpiries[1]}\""))
        val clockless = M0FixtureAdapters.observation(BankedResetResponseParser.parse(M0FixtureAdapters.body(text)))
        assertNull(clockless.observedAt)
        assertNull(clockless.summaryObservedAt)
        assertEquals(Instant.ofEpochSecond(1792701116, 833553000), clockless.items[0].value!!.expiresAt.value)
        assertEquals(Instant.ofEpochSecond(1793300317, 564252000), clockless.items[1].value!!.expiresAt.value)
        assertTrue(clockless.items.all { it.value!!.locallyExpired == null })
        val first = Instant.ofEpochSecond(1792701116, 833553000)
        assertEquals(false, parse(text, evaluated = first.minusNanos(1)).items[0].value!!.locallyExpired)
        assertEquals(true, parse(text, evaluated = first).items[0].value!!.locallyExpired)
        for (clock in listOf(Instant.EPOCH, Instant.ofEpochSecond(0, 1000), Instant.ofEpochSecond(-1))) {
            val result = parse(text, usage.copy(observedAt = clock), clock, clock)
            assertNull(result.observedAt)
            assertNull(result.summaryObservedAt)
            assertTrue(result.items.all { it.value!!.locallyExpired == null })
            assertEquals(2L, result.reportedAvailableCount.value)
        }
    }

    @Test fun syntaxErrorsNonfiniteNonObjectRootsAndPurchasedProtocolAreNotInventory() {
        for (text in listOf("[", "[]", "null", "true", "42", "\"text\"", "{} trailing",
            """{"available_count":NaN}""", """{"available_count":1e309}""",
            """{"available_count":2,"available_count":0}""")) {
            assertEquals(PayloadResult.Failure(TransportFailure.INVALID_RESPONSE), BankedResetResponseParser.parse(M0FixtureAdapters.body(text), usage, now, now))
        }
        assertTrue(BankedResetResponseParser.parse(M0FixtureAdapters.body(byteArrayOf(0xff.toByte()))) is PayloadResult.Failure)
        val purchased = parse("""{"balance":"200","has_credits":true,"unlimited":true}""")
        assertNull(purchased.reportedAvailableCount.value)
        assertNull(purchased.inventoryRowCount)
        val redacted = BankedResetResponseParser.parse(M0FixtureAdapters.body(inventory(item("synthetic-private-id"))))
        assertEquals("PayloadDecoded(redacted)", redacted.toString())
    }
}
