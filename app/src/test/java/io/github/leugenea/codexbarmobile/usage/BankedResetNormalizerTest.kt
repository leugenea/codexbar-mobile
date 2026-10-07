package io.github.leugenea.codexbarmobile.usage

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BankedResetNormalizerTest {
    private val now = M0Fixtures.clock
    private val invalidEpochTimes = listOf(
        "0000-01-01T00:00:00Z", "1969-12-31T23:59:59Z", "1970-01-01T00:00:00Z",
        "1970-01-01T00:00:00.000001Z", "1970-01-01T00:00:00.999999Z",
    )
    private fun value(value: Any) = M0Fixtures.value(value)
    private fun normalize(
        input: Input<InventoryInput>, summary: Any? = null, evaluated: Instant? = now,
    ): BankedResetObservation {
        val usage = UsageNormalizer.normalize(UsageInput(
            bankedAvailableCount = summary?.let { value(it) } ?: Input.Missing,
        ), now.minusSeconds(3))
        return BankedResetNormalizer.normalize(input, usage, now, evaluated)
    }
    private fun item(input: Input<InventoryInput>) = normalize(input).items.single().value!!

    @Test fun m0BankedVectorsRetainCountsStatusAndExpiryKnowledge() {
        val cases = M0Fixtures.cases().associateBy { it.name }
        val distinct = normalize(cases.getValue("banked-distinct-from-purchased").inventory, 2)
        assertEquals(2L, distinct.summaryAvailableCount.value)
        assertEquals(2L, distinct.reportedAvailableCount.value)
        assertEquals(2, distinct.inventoryRowCount)
        assertEquals(Completeness.COMPLETE, distinct.completeness)
        assertEquals(Instant.ofEpochSecond(1791241200), distinct.items[0].value!!.expiresAt.value)
        assertEquals(false, distinct.items[0].value!!.locallyExpired)
        assertEquals(Reason.PROVIDER_NULL, distinct.items[1].value!!.expiresAt.reason)
        assertNull(distinct.items[1].value!!.locallyExpired)
        assertTrue(distinct.issues.isEmpty())
        val expired = normalize(cases.getValue("expiry-reported-count-conflict").inventory, 1)
        assertEquals(1L, expired.reportedAvailableCount.value)
        assertEquals("available", expired.items.single().value!!.providerStatus.value)
        assertEquals(true, expired.items.single().value!!.locallyExpired)
        assertEquals(setOf(InventoryIssue.EXPIRED_AVAILABLE_ITEM), expired.issues)
        val negative = normalize(cases.getValue("banked-malformed-count").inventory)
        assertEquals(Knowledge.MALFORMED, negative.reportedAvailableCount.knowledge)
        assertNull(negative.reportedAvailableCount.value)
        val unavailable = normalize(cases.getValue("banked-unavailable-not-zero").inventory)
        assertNull(unavailable.reportedAvailableCount.value)
        assertNull(unavailable.inventoryRowCount)
        val future = normalize(cases.getValue("unknown-entitlement-status").inventory)
        assertEquals("future_status", future.items.single().value!!.providerStatus.value)
        assertEquals(0L, future.reportedAvailableCount.value)
        assertEquals(setOf(InventoryIssue.UNKNOWN_STATUS), future.issues)
    }

    @Test fun upstreamInventoryMockRetainsNullExpiryAndGrantDates() {
        val result = normalize(M0Fixtures.inventoryMock(), evaluated = Instant.parse("2026-06-20T00:00:00Z"))
        assertEquals(2L, result.reportedAvailableCount.value)
        assertEquals(2, result.inventoryRowCount)
        assertEquals("credit-1", result.items[0].value!!.id.value)
        assertEquals(Instant.parse("2026-06-17T00:00:00Z"), result.items[0].value!!.grantedAt.value)
        assertEquals(Instant.parse("2026-07-17T00:00:00Z"), result.items[0].value!!.expiresAt.value)
        assertNull(result.items[1].value!!.expiresAt.value)
        assertNull(result.items[1].value!!.locallyExpired)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun sixFractionOwnerExpiriesRetainExactNanosecondsWithoutInventedClock() {
        val input = M0Fixtures.inventory(2,
            M0Fixtures.item("fixture-a", expiry = value(M0Fixtures.ownerExpiries[0])),
            M0Fixtures.item("fixture-b", expiry = value(M0Fixtures.ownerExpiries[1])),
        )
        val result = BankedResetNormalizer.normalize(input)
        assertNull(result.observedAt)
        assertNull(result.summaryObservedAt)
        assertEquals(Instant.ofEpochSecond(1792701116, 833553000), result.items[0].value!!.expiresAt.value)
        assertEquals(Instant.ofEpochSecond(1793300317, 564252000), result.items[1].value!!.expiresAt.value)
        assertTrue(result.items.all { it.value!!.locallyExpired == null })
    }

    @Test fun utcGrammarSupportsZeroThroughSixFractionsButNotLeapSecondNormalization() {
        for (fraction in listOf("", ".1", ".12", ".123", ".1234", ".12345", ".123456")) {
            val text = "2026-10-05T23:00:00${fraction}Z"
            assertEquals(Instant.parse(text), item(M0Fixtures.inventory(1, M0Fixtures.item(expiry = value(text)))).expiresAt.value)
        }
        // Instant.parse accepts second 60 by normalizing it: domain must reject it.
        val leap = item(M0Fixtures.inventory(1, M0Fixtures.item(expiry = value("2026-12-31T23:59:60Z"))))
        assertEquals(Knowledge.MALFORMED, leap.expiresAt.knowledge)
    }

    @Test fun missingNullUnsupportedAndMalformedExpiryRemainDistinctUnknown() {
        for (input in listOf(Input.Missing, Input.Null, value("2026-10-05T23:00:00+00:00"), value("2026-10-05T23:00:00.1234567Z"))) {
            val item = item(M0Fixtures.inventory(1, M0Fixtures.item(expiry = input)))
            assertNull(item.expiresAt.value)
            assertNull(item.locallyExpired)
            assertNotEquals(Knowledge.KNOWN, item.expiresAt.knowledge)
        }
        for (bad in listOf(true, 1791241200, "2026-02-30T00:00:00Z", "2026-10-05T24:00:00Z")) {
            val result = normalize(M0Fixtures.inventory(1, M0Fixtures.item(expiry = value(bad))))
            assertEquals(Knowledge.MALFORMED, result.items.single().value!!.expiresAt.knowledge)
            assertEquals(Completeness.PARTIAL, result.completeness)
            assertNull(result.items.single().value!!.locallyExpired)
        }
    }

    @Test fun expiryRejectsNonpositiveEpochSecondsWithoutChangingCountsOrValidSibling() {
        for (text in invalidEpochTimes) {
            val result = normalize(M0Fixtures.inventory(2,
                M0Fixtures.item(expiry = value(text)), M0Fixtures.item("fixture-reset-b"),
            ), 2)
            val invalid = result.items[0].value!!
            assertEquals(text, Knowledge.MALFORMED, invalid.expiresAt.knowledge)
            assertEquals(Reason.OUT_OF_RANGE, invalid.expiresAt.reason)
            assertNull(invalid.expiresAt.value)
            assertNull(invalid.locallyExpired)
            assertEquals(Knowledge.KNOWN, invalid.grantedAt.knowledge)
            assertEquals("available", invalid.providerStatus.value)
            assertEquals(2L, result.summaryAvailableCount.value)
            assertEquals(2L, result.reportedAvailableCount.value)
            assertEquals(2, result.inventoryRowCount)
            assertEquals(Completeness.PARTIAL, result.completeness)
            assertEquals(item(M0Fixtures.inventory(1, M0Fixtures.item("fixture-reset-b"))), result.items[1].value)
            assertTrue(result.issues.isEmpty())
        }
    }

    @Test fun grantRejectsNonpositiveEpochSecondsWithoutChangingExpiryOrValidSibling() {
        val original = (M0Fixtures.item() as Input.Value).value
        for (text in invalidEpochTimes) {
            val result = normalize(M0Fixtures.inventory(2,
                Input.Value(original.copy(grantedAt = value(text))), M0Fixtures.item("fixture-reset-b"),
            ), 2)
            val invalid = result.items[0].value!!
            assertEquals(text, Knowledge.MALFORMED, invalid.grantedAt.knowledge)
            assertEquals(Reason.OUT_OF_RANGE, invalid.grantedAt.reason)
            assertNull(invalid.grantedAt.value)
            assertEquals(Instant.parse("2026-10-05T23:00:00Z"), invalid.expiresAt.value)
            assertEquals(false, invalid.locallyExpired)
            assertEquals("available", invalid.providerStatus.value)
            assertEquals(2L, result.summaryAvailableCount.value)
            assertEquals(2L, result.reportedAvailableCount.value)
            assertEquals(Completeness.PARTIAL, result.completeness)
            assertEquals(item(M0Fixtures.inventory(1, M0Fixtures.item("fixture-reset-b"))), result.items[1].value)
            assertTrue(result.issues.isEmpty())
        }
    }

    @Test fun positiveEpochBoundaryRetainsGrantAndExpiryMicroseconds() {
        val original = (M0Fixtures.item() as Input.Value).value
        // M0 truncates before testing > 0: the first accepted second is 1, not EPOCH + 1us.
        for (text in listOf("1970-01-01T00:00:01Z", "1970-01-01T00:00:01.000001Z", M0Fixtures.ownerExpiries[0])) {
            val input = Input.Value(original.copy(grantedAt = value(text), expiresAt = value(text)))
            val result = normalize(M0Fixtures.inventory(1, input))
            val item = result.items.single().value!!
            assertEquals(Knowledge.KNOWN, item.grantedAt.knowledge)
            assertEquals(Knowledge.KNOWN, item.expiresAt.knowledge)
            assertEquals(Instant.parse(text), item.grantedAt.value)
            assertEquals(Instant.parse(text), item.expiresAt.value)
            assertEquals(Completeness.COMPLETE, result.completeness)
        }
    }

    @Test fun invalidEndpointClocksRemainUnknownWithoutChangingInventoryFacts() {
        val usage = UsageNormalizer.normalize(UsageInput(bankedAvailableCount = value(1)), now)
        val input = M0Fixtures.inventory(1, M0Fixtures.item())
        for (text in invalidEpochTimes) {
            val clock = Instant.parse(text)
            // Also reject an invalid summary clock supplied by an already-constructed observation.
            val result = BankedResetNormalizer.normalize(input, usage.copy(observedAt = clock), clock, clock)
            assertNull(result.observedAt)
            assertNull(result.summaryObservedAt)
            assertNull(result.items.single().value!!.locallyExpired)
            assertEquals(Knowledge.KNOWN, result.items.single().value!!.expiresAt.knowledge)
            assertEquals(1L, result.summaryAvailableCount.value)
            assertEquals(1L, result.reportedAvailableCount.value)
            assertEquals(Completeness.COMPLETE, result.completeness)
            assertTrue(result.issues.isEmpty())
        }
        val first = Instant.ofEpochSecond(1, 1000)
        val valid = BankedResetNormalizer.normalize(input, usage.copy(observedAt = first), first, first)
        assertEquals(first, valid.observedAt)
        assertEquals(first, valid.summaryObservedAt)
        assertEquals(false, valid.items.single().value!!.locallyExpired)
    }

    @Test fun expiryBoundaryNeverChangesProviderCountOrStatus() {
        val input = M0Fixtures.inventory(1, M0Fixtures.item(expiry = value("2026-10-04T23:00:00.000001Z")))
        val before = normalize(input)
        val at = normalize(input, evaluated = now.plusNanos(1000))
        val after = normalize(input, evaluated = now.plusSeconds(604800))
        assertEquals(false, before.items.single().value!!.locallyExpired)
        assertEquals(true, at.items.single().value!!.locallyExpired)
        assertEquals(at, after)
        assertEquals(1L, after.reportedAvailableCount.value)
        assertEquals("available", after.items.single().value!!.providerStatus.value)
        assertEquals(Instant.ofEpochSecond(1791154800, 1000), after.items.single().value!!.expiresAt.value)
    }

    @Test fun countDisagreementsRemainVisibleWithoutReplacingEitherRead() {
        val result = normalize(M0Fixtures.inventory(3, M0Fixtures.item(), M0Fixtures.item("fixture-reset-b")), 5)
        assertEquals(5L, result.summaryAvailableCount.value)
        assertEquals(3L, result.reportedAvailableCount.value)
        assertEquals(2, result.inventoryRowCount)
        assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH, InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH), result.issues)
        val empty = normalize(M0Fixtures.inventory(0), 0)
        assertEquals(0L, empty.reportedAvailableCount.value)
        assertEquals(0, empty.inventoryRowCount)
        assertEquals(Completeness.COMPLETE, empty.completeness)
        assertTrue(empty.issues.isEmpty())
        assertEquals(1L, normalize(M0Fixtures.inventory(1.0, M0Fixtures.item())).reportedAvailableCount.value)
    }

    @Test fun invalidCountsRejectBooleansFractionsNonfiniteNegativeAndOverflow() {
        for (bad in listOf(true, "2", -1, 0.5, Double.NaN, Float.POSITIVE_INFINITY, BigDecimal("9223372036854775808"))) {
            val result = normalize(M0Fixtures.inventory(bad, M0Fixtures.item()))
            assertEquals(Knowledge.MALFORMED, result.reportedAvailableCount.knowledge)
            assertNull(result.reportedAvailableCount.value)
            assertEquals(1, result.inventoryRowCount)
            assertEquals("available", result.items.single().value!!.providerStatus.value)
        }
    }

    @Test fun absentInvalidInventoryAndRowsPreserveSummaryAndIndependentClocks() {
        for (input in listOf(Input.Missing, Input.Null, Input.Invalid)) {
            val result = normalize(input, 2)
            assertEquals(2L, result.summaryAvailableCount.value)
            assertNull(result.reportedAvailableCount.value)
            assertNull(result.inventoryRowCount)
            assertEquals(Completeness.UNKNOWN, result.completeness)
            assertEquals(now.minusSeconds(3), result.summaryObservedAt)
            assertEquals(now, result.observedAt)
        }
        for (rows in listOf(Input.Missing, Input.Null, Input.Invalid)) {
            val result = normalize(Input.Value(InventoryInput(value(2), rows)))
            assertEquals(2L, result.reportedAvailableCount.value)
            assertNull(result.inventoryRowCount)
            assertEquals(Completeness.UNKNOWN, result.completeness)
        }
        val onlyInventory = BankedResetNormalizer.normalize(M0Fixtures.inventory(0), observedAt = now)
        assertNull(onlyInventory.summaryAvailableCount.value)
        assertNull(onlyInventory.summaryObservedAt)
        assertEquals(now, onlyInventory.observedAt)
    }

    @Test fun malformedItemsPreserveValidSiblingsAndMarkPartialInventory() {
        val malformed = Input.Value(ResetItemInput(id = value(42), status = value(false), expiresAt = Input.Invalid))
        val result = normalize(M0Fixtures.inventory(2, Input.Invalid, Input.Null, Input.Missing, malformed, M0Fixtures.item()))
        assertEquals(5, result.inventoryRowCount)
        assertEquals(Completeness.PARTIAL, result.completeness)
        assertEquals(Knowledge.MALFORMED, result.items[0].knowledge)
        assertEquals(Reason.PROVIDER_NULL, result.items[1].reason)
        assertEquals(Reason.MISSING, result.items[2].reason)
        assertEquals(Knowledge.MALFORMED, result.items[3].value!!.id.knowledge)
        assertEquals(Knowledge.MALFORMED, result.items[3].value!!.expiresAt.knowledge)
        assertEquals("fixture-reset-a", result.items[4].value!!.id.value)
        assertFalse(result.issues.contains(InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH))
    }

    @Test fun omittedInventoryAndItemFieldsRemainUnknownNotProviderZero() {
        val absent = normalize(Input.Value(InventoryInput()))
        assertEquals(Reason.MISSING, absent.reportedAvailableCount.reason)
        assertNull(absent.inventoryRowCount)
        assertEquals(Completeness.UNKNOWN, absent.completeness)
        val empty = normalize(Input.Value(InventoryInput(items = Input.Value(emptyList()))))
        assertNull(empty.reportedAvailableCount.value)
        assertEquals(0, empty.inventoryRowCount)
        val incomplete = normalize(M0Fixtures.inventory(1, Input.Value(ResetItemInput())))
        assertEquals(Completeness.PARTIAL, incomplete.completeness)
        val item = incomplete.items.single().value!!
        assertEquals(Reason.MISSING, item.id.reason)
        assertEquals(Reason.MISSING, item.grantedAt.reason)
        assertEquals(Reason.MISSING, item.expiresAt.reason)
        assertNull(item.locallyExpired)
        val invalidSummary = normalize(M0Fixtures.inventory(1, M0Fixtures.item()), true)
        assertEquals(Knowledge.MALFORMED, invalidSummary.summaryAvailableCount.knowledge)
        assertEquals(1L, invalidSummary.reportedAvailableCount.value)
        assertTrue(invalidSummary.issues.isEmpty())
    }

    @Test fun duplicateInventoryIdentityRetainsBothRowsAndMarksAmbiguity() {
        val result = normalize(M0Fixtures.inventory(2, M0Fixtures.item(), M0Fixtures.item()))
        assertEquals(2, result.items.size)
        assertEquals(setOf(InventoryIssue.DUPLICATE_IDENTITY), result.issues)
    }

    @Test fun unknownProviderStatusAndTypeArePreservedNotInterpretedAsEligibility() {
        val original = (M0Fixtures.item(status = "future_status") as Input.Value).value
        val input = Input.Value(original.copy(resetType = value("future_type")))
        val result = normalize(M0Fixtures.inventory(0, input))
        val item = result.items.single().value!!
        assertEquals("future_status", item.providerStatus.value)
        assertEquals("future_type", item.resetType.value)
        assertEquals(setOf(InventoryIssue.UNKNOWN_STATUS, InventoryIssue.UNKNOWN_RESET_TYPE), result.issues)
        assertEquals(0L, result.reportedAvailableCount.value)
    }
}
