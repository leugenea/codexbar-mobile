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
            val result = present(parse(text))
            assertEquals(EntitlementState.UNKNOWN, result.state)
            assertEquals(expected, result.inventoryRowContainer)
            assertNull(result.inventoryRowCount)
            assertEquals(Completeness.UNKNOWN, result.completeness)
            assertEquals(Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
            assertEquals(Field(Knowledge.KNOWN, 0L), result.summaryAvailableCount)
            assertTrue(result.items.isEmpty())
            assertTrue(result.issues.isEmpty())
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
        val result = present(parse("""{"available_count":0,"credits":[]}"""))
        assertEquals(EntitlementState.EMPTY, result.state)
        assertEquals(Field(Knowledge.KNOWN, 0), result.inventoryRowContainer)
        assertEquals(0, result.inventoryRowCount)
        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
        assertTrue(result.items.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    @Test fun malformedArrayRowStaysVisibleWithoutChangingContainerKnowledge() {
        val result = present(parse("""{"available_count":0,"credits":[true]}"""))
        assertEquals(EntitlementState.MALFORMED, result.state)
        assertEquals(Field(Knowledge.KNOWN, 1), result.inventoryRowContainer)
        assertEquals(1, result.inventoryRowCount)
        assertEquals(Completeness.PARTIAL, result.completeness)
        assertEquals(EntitlementState.MALFORMED, result.items.single().state)
        assertEquals(Reason.WRONG_TYPE, result.items.single().source.reason)
        assertEquals(Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
        assertTrue(result.issues.isEmpty())
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
