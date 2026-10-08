package io.github.leugenea.codexbarmobile.usage

import io.github.leugenea.codexbarmobile.transport.TransportFailure
import org.junit.Assert.*
import org.junit.Test

/** Synthetic direct-HTTP containers; no new provider fields or coerced inventory. */
class BankedInventoryContainerTest {
    private val summary = UsageNormalizer.normalize(UsageInput(bankedAvailableCount = Input.Value(0)))

    private fun parse(text: String) = M0FixtureAdapters.observation(
        BankedResetResponseParser.parse(M0FixtureAdapters.body(text), summary),
    )

    @Test fun absentAndNullContainersRetainDifferentA1ReasonsDespiteKnownZeroCounts() {
        for ((text, expected) in listOf(
            """{"available_count":0}""" to Field<Int>(Knowledge.UNAVAILABLE, reason = Reason.MISSING),
            """{"available_count":0,"credits":null}""" to Field<Int>(Knowledge.UNAVAILABLE, reason = Reason.PROVIDER_NULL),
        )) {
            val result = parse(text)
            assertEquals(expected, result.inventoryRowContainer)
            assertNull(result.inventoryRowCount)
            assertEquals(emptyList<Field<BankedResetItem>>(), result.items)
            assertEquals(Completeness.UNKNOWN, result.completeness)
            assertEquals(Field(Knowledge.KNOWN, 0L), result.summaryAvailableCount)
            assertEquals(Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
            assertEquals(emptySet<InventoryIssue>(), result.issues)
        }
    }

    @Test fun wrongTypeContainersAreMalformedWithoutDiscardingSummaryOrReportedCount() {
        for (container in listOf("{}", "true", "0", "\"synthetic\"")) {
            val result = parse("""{"available_count":7,"credits":$container}""")
            assertEquals(Field<Int>(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE), result.inventoryRowContainer)
            assertNull(result.inventoryRowCount)
            assertEquals(Completeness.UNKNOWN, result.completeness)
            assertTrue(result.items.isEmpty())
            assertEquals(Field(Knowledge.KNOWN, 0L), result.summaryAvailableCount)
            assertEquals(Field(Knowledge.KNOWN, 7L), result.reportedAvailableCount)
            assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH), result.issues)
        }
    }

    @Test fun onlyAnExplicitEmptyArrayEstablishesKnownZeroRows() {
        val result = parse("""{"available_count":0,"credits":[]}""")
        assertEquals(Field(Knowledge.KNOWN, 0), result.inventoryRowContainer)
        assertEquals(0, result.inventoryRowCount)
        assertEquals(Completeness.COMPLETE, result.completeness)
        assertTrue(result.items.isEmpty())
        assertEquals(Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun malformedRowDoesNotTurnAValidArrayIntoAMalformedContainer() {
        val result = parse("""{"available_count":0,"credits":[true]}""")
        assertEquals(Field(Knowledge.KNOWN, 1), result.inventoryRowContainer)
        assertEquals(1, result.inventoryRowCount)
        assertEquals(Completeness.PARTIAL, result.completeness)
        assertEquals(Field<BankedResetItem>(Knowledge.MALFORMED, reason = Reason.WRONG_TYPE), result.items.single())
        assertEquals(Field(Knowledge.KNOWN, 0L), result.reportedAvailableCount)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun malformedContainerSyntaxIsAnEndpointFailureNotAnEmptyObservation() {
        for (text in listOf("""{"available_count":0,"credits":[}""", """{"available_count":0,"credits":[],"credits":null}""")) {
            assertEquals(PayloadResult.Failure(TransportFailure.INVALID_RESPONSE),
                BankedResetResponseParser.parse(M0FixtureAdapters.body(text), summary))
        }
    }
}
