package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.credentials.LocalCredentialNamespace
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.*
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** End-to-end A9 -> C1 tests with synthetic JSON and literal container/state expectations. */
class EntitlementContainerPresentationTest {
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val generation = SessionGeneration(LocalCredentialNamespace())
    private val summary = UsageNormalizer.normalize(UsageInput(bankedAvailableCount = Input.Value(0)), now)

    private fun parse(text: String) = M0FixtureAdapters.observation(
        BankedResetResponseParser.parse(M0FixtureAdapters.body(text), summary, now, now),
    )

    private fun present(facts: BankedResetObservation?, error: ReadError? = null) = EntitlementPresentation.present(
        EntitlementSnapshot(generation, FeasibilityObservations(
            EndpointObservation(ReadOperation.USAGE, usage = summary),
            EndpointObservation(ReadOperation.RESET_INVENTORY, inventory = facts, error = error),
        )), generation, now, ZoneOffset.UTC, Locale.US,
    )

    @Test fun missingAndNullRemainUnknownWithDistinctReasonsNotUnsupportedOrEmpty() {
        for ((text, expected) in listOf(
            """{"available_count":0}""" to Field<Int>(Knowledge.UNAVAILABLE, reason = Reason.MISSING),
            """{"available_count":0,"credits":null}""" to Field<Int>(Knowledge.UNAVAILABLE, reason = Reason.PROVIDER_NULL),
        )) {
            val decoded = parse(text)
            assertEquals("$text decoded summaryAvailableCount", Field(Knowledge.KNOWN, 0L), decoded.summaryAvailableCount)
            assertTrue("$text decoded issues", decoded.issues.isEmpty())
            val result = present(decoded)
            assertEquals("$text presented state", EntitlementState.UNKNOWN, result.state)
            assertEquals("$text presented inventoryRowContainer", expected, result.inventoryRowContainer)
            assertNull("$text presented inventoryRowCount", result.inventoryRowCount)
            assertEquals("$text presented completeness", Completeness.UNKNOWN, result.completeness)
            assertEquals("$text presented reportedAvailableCount", Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
            assertEquals("$text presented summaryAvailableCount", Field(Knowledge.KNOWN, 0L), result.summaryAvailableCount)
            assertTrue("$text presented items", result.items.isEmpty())
            assertTrue("$text presented issues", result.issues.isEmpty())
        }
    }

    @Test fun wrongTypeIsMalformedEvenWhenCountIsKnownZeroOrMissing() {
        for (count in listOf("\"available_count\":0,", "")) {
            for (container in listOf("{}", "true", "0", "\"synthetic\"")) {
                val result = present(parse("""{$count"credits":$container}"""))
                assertEquals(EntitlementState.MALFORMED, result.state)
                assertEquals(Field<Int>(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE), result.inventoryRowContainer)
                assertNull(result.inventoryRowCount)
                assertEquals(Completeness.UNKNOWN, result.completeness)
                assertTrue(result.items.isEmpty())
                assertEquals(Field(Knowledge.KNOWN, 0L), result.summaryAvailableCount)
                assertTrue(result.issues.isEmpty())
            }
        }
    }

    @Test fun explicitEmptyContainerAndProviderZeroAreEmptyWithoutRecomputingCounts() {
        val text = """{"available_count":0,"credits":[]}"""
        val decoded = parse(text)
        assertTrue("$text decoded issues", decoded.issues.isEmpty())
        val result = present(decoded)
        assertEquals("$text presented state", EntitlementState.EMPTY, result.state)
        assertEquals("$text presented inventoryRowContainer", Field(Knowledge.KNOWN, 0), result.inventoryRowContainer)
        assertEquals("$text presented inventoryRowCount", 0, result.inventoryRowCount)
        assertEquals("$text presented completeness", Completeness.COMPLETE, result.completeness)
        assertEquals("$text presented reportedAvailableCount", Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
        assertTrue("$text presented items", result.items.isEmpty())
        assertTrue("$text presented issues", result.issues.isEmpty())
    }

    @Test fun malformedArrayRowStaysVisibleWithoutChangingContainerKnowledge() {
        val text = """{"available_count":0,"credits":[true]}"""
        val decoded = parse(text)
        assertTrue("$text decoded issues", decoded.issues.isEmpty())
        val result = present(decoded)
        assertEquals("$text presented state", EntitlementState.MALFORMED, result.state)
        assertEquals("$text presented inventoryRowContainer", Field(Knowledge.KNOWN, 1), result.inventoryRowContainer)
        assertEquals("$text presented inventoryRowCount", 1, result.inventoryRowCount)
        assertEquals("$text presented completeness", Completeness.PARTIAL, result.completeness)
        assertEquals("$text presented item state", EntitlementState.MALFORMED, result.items.single().state)
        assertEquals("$text presented item source", Field<BankedResetItem>(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE),
            result.items.single().source)
        assertEquals("$text presented item source reason", Reason.WRONG_TYPE, result.items.single().source.reason)
        assertEquals("$text presented reportedAvailableCount", Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
        assertTrue("$text presented issues", result.issues.isEmpty())
    }

    @Test fun malformedJsonFailureIsProjectedAsMalformedWithoutManufacturingContainerFacts() {
        val decoded = BankedResetResponseParser.parse(M0FixtureAdapters.body("""{"available_count":0,"credits":[}"""))
        assertTrue(decoded is PayloadResult.Failure)
        val result = present(null, ReadError.INVALID_RESPONSE)
        assertEquals(EntitlementState.MALFORMED, result.state)
        assertNull(result.inventoryRowContainer)
        assertNull(result.reportedAvailableCount)
        assertEquals(Field(Knowledge.KNOWN, 0L), result.summaryAvailableCount)
        assertEquals(ReadError.INVALID_RESPONSE, result.inventoryError)
        assertTrue(result.items.isEmpty())
    }

    @Test fun discrepancyAndEndpointErrorKeepPrecedenceWithoutHidingMalformedContainerReason() {
        val facts = parse("""{"available_count":7,"credits":{}}""")
        val discrepant = present(facts)
        assertEquals(EntitlementState.DISCREPANT, discrepant.state)
        assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH), discrepant.issues)
        assertEquals(Field(Knowledge.KNOWN, 0L), discrepant.summaryAvailableCount)
        assertEquals(Field(Knowledge.KNOWN, 7L), discrepant.reportedAvailableCount)
        assertEquals(Field<Int>(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE), discrepant.inventoryRowContainer)
        val inaccessible = present(facts, ReadError.FORBIDDEN)
        assertEquals(EntitlementState.INACCESSIBLE, inaccessible.state)
        assertEquals(Field<Int>(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE), inaccessible.inventoryRowContainer)
        assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH), inaccessible.issues)
        assertEquals(7L, inaccessible.reportedAvailableCount?.value)
    }

    @Test fun defensiveContainerKnowledgeDoesNotBecomeKnownOrEmpty() {
        val base = parse("""{"available_count":0,"credits":[]}""")
        val unsupported = present(base.copy(inventoryRowContainer = Field(Knowledge.UNSUPPORTED, reason = Reason.UNSUPPORTED_FORMAT)))
        assertEquals(EntitlementState.UNSUPPORTED, unsupported.state)
        assertEquals(Reason.UNSUPPORTED_FORMAT, unsupported.inventoryRowContainer?.reason)
        val missingValue = present(base.copy(inventoryRowContainer = Field(Knowledge.KNOWN)))
        assertEquals(EntitlementState.UNKNOWN, missingValue.state)
        assertNull(missingValue.inventoryRowContainer?.value)
        val nonempty = present(base.copy(inventoryRowContainer = Field(Knowledge.KNOWN, 1), inventoryRowCount = 1))
        assertEquals(EntitlementState.KNOWN, nonempty.state)
        assertEquals(1, nonempty.inventoryRowContainer?.value)
        assertEquals(0L, nonempty.reportedAvailableCount?.value)
    }
}
