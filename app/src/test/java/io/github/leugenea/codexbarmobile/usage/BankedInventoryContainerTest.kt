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

    @Test fun malformedContainerSyntaxIsAnEndpointFailureNotAnEmptyObservation() {
        for (text in listOf("""{"available_count":0,"credits":[}""", """{"available_count":0,"credits":[],"credits":null}""")) {
            assertEquals(PayloadResult.Failure(TransportFailure.INVALID_RESPONSE),
                BankedResetResponseParser.parse(M0FixtureAdapters.body(text), summary))
        }
    }
}
