package io.github.leugenea.codexbarmobile.usage

import io.github.leugenea.codexbarmobile.transport.TransportFailure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class UsageResponseParserTest {
    private val now = M0Fixtures.clock
    private fun parse(text: String, observed: Instant? = now, evaluated: Instant? = now) =
        M0FixtureAdapters.observation(UsageResponseParser.parse(M0FixtureAdapters.body(text), observed, evaluated))
    private fun payload(primary: String, secondary: String = "null") =
        """{"rate_limit":{"primary_window":$primary,"secondary_window":$secondary}}"""
    private fun window(percent: String = "22", duration: String = "18000", reset: String = "") =
        """{"used_percent":$percent,"limit_window_seconds":$duration$reset}"""

    @Test fun upstreamWeeklyMockDecodesUnchangedByDurationNotSlot() {
        val result = M0FixtureAdapters.observation(UsageResponseParser.parse(
            M0FixtureAdapters.mock("upstream-test-weekly-only.json"), now, now))
        assertEquals(now, result.observedAt)
        assertEquals("free", result.planType.value)
        assertEquals(SelectionState.UNAVAILABLE, result.fiveHour.state)
        assertEquals(SelectionState.KNOWN, result.weekly.state)
        val weekly = result.weekly.candidates.single()
        assertEquals(Slot.PRIMARY, weekly.slot)
        assertEquals(604800L, weekly.durationSeconds.value)
        assertEquals(BigDecimal.ZERO, weekly.usedPercent.value)
        assertEquals(Instant.ofEpochSecond(1775468693), weekly.reset.absolute.value)
        assertEquals(true, weekly.reset.due)
        assertEquals(Reason.MISSING, weekly.reset.relativeSeconds.reason)
        assertEquals(Reason.PROVIDER_NULL, result.slots[1].window.reason)
        assertEquals(Reason.MISSING, result.allowed.reason)
        val swapped = parse(payload("null", window("0", "604800")))
        assertEquals(Slot.SECONDARY, swapped.weekly.candidates.single().slot)
    }

    @Test fun allSeventeenM0VectorsDecodeWithIndependentExpectedFacts() {
        val vectors = M0FixtureAdapters.vectors()
        assertEquals(17, vectors.size)
        assertEquals(17, vectors.map { it.getValue("caseId") }.distinct().size)
        for (vector in vectors) {
            val expected = vector.getValue("expected") as JsonObject
            val windows = expected.getValue("windows") as JsonObject
            val result = parse(vector.getValue("rawUsage").toString())
            fun percent(key: String) = ((windows.getValue(key) as JsonObject)["usedPercent"] as JsonPrimitive)
                .content.toBigDecimalOrNull()
            assertEquals(vector["caseId"].toString(), percent("fiveHour"), result.fiveHour.candidates.singleOrNull()?.usedPercent?.value)
            assertEquals(vector["caseId"].toString(), percent("weekly"), result.weekly.candidates.singleOrNull()?.usedPercent?.value)
            assertEquals((expected["providerAllowed"] as JsonPrimitive).content.toBooleanStrictOrNull(), result.allowed.value)
            assertEquals((expected["providerLimitReached"] as JsonPrimitive).content.toBooleanStrictOrNull(), result.limitReached.value)
        }
        // These are synthetic harness metadata, not selected provider fields.
        val unsupported = parse("""{"plan_type":"future_plan","explicitProviderError":"subscription_sharing_user_not_eligible"}""")
        assertEquals("future_plan", unsupported.planType.value)
        assertEquals(SelectionState.UNAVAILABLE, unsupported.fiveHour.state)
        assertNull(parse("""{"http_status":401,"refresh_error":"refresh_token_expired"}""").allowed.value)
    }

    @Test fun missingNullWrongTypeParentsAndScalarsRemainDistinct() {
        for ((json, reason) in listOf("{}" to Reason.MISSING, "null" to Reason.PROVIDER_NULL,
            "[]" to Reason.WRONG_TYPE, "true" to Reason.WRONG_TYPE, "2" to Reason.WRONG_TYPE, "\"x\"" to Reason.WRONG_TYPE)) {
            val result = parse("""{"rate_limit":$json,"rate_limit_reset_credits":$json,"plan_type":null}""")
            assertEquals(reason, result.slots[0].window.reason)
            assertEquals(reason, result.slots[1].window.reason)
            assertEquals(reason, result.allowed.reason)
            assertEquals(reason, result.limitReached.reason)
            assertEquals(reason, result.bankedAvailableCount.reason)
            assertEquals(Reason.PROVIDER_NULL, result.planType.reason)
        }
        val empty = parse("{}")
        assertEquals(Reason.MISSING, empty.planType.reason)
        val partial = parse(payload("{}", window("18", "604800")))
        val first = partial.slots[0].window.value!!
        assertEquals(WindowKind.UNKNOWN, first.kind)
        assertEquals(Reason.MISSING, first.usedPercent.reason)
        assertEquals(BigDecimal(18), partial.weekly.candidates.single().usedPercent.value)
        val fields = parse(payload(window("null", reset = ",\"reset_at\":null,\"reset_after_seconds\":null"))).fiveHour.candidates.single()
        assertEquals(Reason.PROVIDER_NULL, fields.usedPercent.reason)
        assertEquals(Reason.PROVIDER_NULL, fields.reset.absolute.reason)
        assertEquals(Reason.PROVIDER_NULL, fields.reset.relativeSeconds.reason)
    }

    @Test fun malformedWindowOrFieldNeverDiscardsValidSibling() {
        val good = window("19.375", "604800")
        for (bad in listOf("false", "[]", "42", "\"invalid\"")) {
            val result = parse(payload(bad, good))
            assertEquals(Knowledge.MALFORMED, result.slots[0].window.knowledge)
            assertEquals(SelectionState.KNOWN, result.weekly.state)
            assertEquals(BigDecimal("19.375"), result.weekly.candidates.single().usedPercent.value)
        }
        for (bad in listOf("true", "\"42\"", "[]", "{}", "-0.1", "100.1")) {
            val result = parse(payload(window(bad), good))
            assertEquals(SelectionState.MALFORMED, result.fiveHour.state)
            assertEquals(Knowledge.MALFORMED, result.fiveHour.candidates.single().usedPercent.knowledge)
            assertEquals(BigDecimal("19.375"), result.weekly.candidates.single().usedPercent.value)
        }
    }

    @Test fun malformedUnknownAndDuplicateDurationsNeverUseSlotIdentity() {
        for (bad in listOf("true", "\"18000\"", "-1", "0", "18000.5", "9223372036854775808", "null", "[]")) {
            val result = parse(payload(window(duration = bad), window("9", "604800")))
            assertEquals(SelectionState.UNAVAILABLE, result.fiveHour.state)
            assertEquals(BigDecimal(9), result.weekly.candidates.single().usedPercent.value)
            assertNull(result.slots[0].window.value!!.durationSeconds.value)
        }
        assertEquals(WindowKind.UNSUPPORTED, parse(payload(window(duration = "3600"))).slots[0].window.value!!.kind)
        for (duration in listOf("18000", "604800")) {
            val result = parse(payload(window("8", duration), window("true", duration)))
            val selected = if (duration == "18000") result.fiveHour else result.weekly
            assertEquals(SelectionState.AMBIGUOUS, selected.state)
            assertEquals(listOf(Slot.PRIMARY, Slot.SECONDARY), selected.candidates.map { it.slot })
            assertNull(selected.candidates[1].usedPercent.value)
        }
    }

    @Test fun decimalLexemePrecisionAndFlagsRemainIndependentOfQuota() {
        val precision = "12.375000000000000000000000000001"
        val result = parse("""{"rate_limit":{"allowed":false,"limit_reached":true,"primary_window":${window(precision)}},"plan_type":"future_plan"}""")
        assertEquals(BigDecimal(precision), result.fiveHour.candidates.single().usedPercent.value)
        assertEquals(false, result.allowed.value)
        assertEquals(true, result.limitReached.value)
        assertEquals("future_plan", result.planType.value)
        for ((percent, allowed) in listOf("100" to true, "0" to false)) {
            val independent = parse("""{"rate_limit":{"allowed":$allowed,"limit_reached":${!allowed},"primary_window":${window(percent)}}}""")
            assertEquals(allowed, independent.allowed.value)
            assertEquals(BigDecimal(percent), independent.fiveHour.candidates.single().usedPercent.value)
        }
        assertEquals(BigDecimal("1e-20"), parse(payload(window("1e-20"))).fiveHour.candidates.single().usedPercent.value)
        val bad = parse("""{"plan_type":false,"rate_limit":{"allowed":1,"limit_reached":"true"},"rate_limit_reset_credits":{"available_count":true}}""")
        assertTrue(listOf(bad.planType, bad.allowed, bad.limitReached, bad.bankedAvailableCount).all { it.knowledge == Knowledge.MALFORMED })
    }

    @Test fun absoluteRelativeConflictingAndPassedResetsRetainOriginalFactsAndClock() {
        val text = payload(window("83", reset = ",\"reset_at\":1791154859,\"reset_after_seconds\":59"))
        val result = parse(text)
        val reset = result.fiveHour.candidates.single().reset
        assertEquals(now.plusSeconds(59), reset.absolute.value)
        assertEquals(59L, reset.relativeSeconds.value)
        assertEquals(now.plusSeconds(59), reset.relativeDerived.value)
        assertFalse(reset.discrepant)
        assertEquals(false, reset.due)
        val passed = parse(text, evaluated = now.plusSeconds(59)).fiveHour.candidates.single()
        assertEquals(true, passed.reset.due)
        assertEquals(BigDecimal(83), passed.usedPercent.value)
        assertEquals(reset.absolute, passed.reset.absolute)
        val conflict = parse(text.replace("1791154859", "1791154860")).fiveHour.candidates.single().reset
        assertTrue(conflict.discrepant)
        assertNull(conflict.due)
        assertEquals(now.plusSeconds(60), conflict.absolute.value)
        assertEquals(now.plusSeconds(59), conflict.relativeDerived.value)
        val relativeText = payload(window(reset = ",\"reset_after_seconds\":59"))
        val unknown = parse(relativeText, observed = null).fiveHour.candidates.single().reset
        assertEquals(Reason.CLOCK_REQUIRED, unknown.relativeDerived.reason)
        assertNull(unknown.due)
        assertNull(parse(relativeText, evaluated = null).fiveHour.candidates.single().reset.due)
        assertEquals(now.plusSeconds(59), parse(relativeText).fiveHour.candidates.single().reset.relativeDerived.value)
        for (clock in listOf(Instant.EPOCH, Instant.ofEpochSecond(-1), Instant.ofEpochSecond(0, 1000))) {
            val invalid = parse(relativeText, clock, clock)
            assertNull(invalid.observedAt)
            assertNull(invalid.fiveHour.candidates.single().reset.relativeDerived.value)
        }
    }

    @Test fun resetAndSummaryTypesRangesAndOverflowNeverInventDefaults() {
        for (bad in listOf("true", "\"2\"", "-1", "0.5", "9223372036854775808", "[]", "{}")) {
            val result = parse("""{"rate_limit":{"primary_window":${window(reset = ",\"reset_at\":$bad,\"reset_after_seconds\":$bad")}},"rate_limit_reset_credits":{"available_count":$bad}}""")
            val reset = result.fiveHour.candidates.single().reset
            assertEquals(Knowledge.MALFORMED, reset.absolute.knowledge)
            assertEquals(Knowledge.MALFORMED, reset.relativeSeconds.knowledge)
            assertEquals(Knowledge.MALFORMED, result.bankedAvailableCount.knowledge)
        }
        val zero = parse(payload(window(reset = ",\"reset_at\":0,\"reset_after_seconds\":0"))).fiveHour.candidates.single().reset
        assertEquals(Knowledge.MALFORMED, zero.absolute.knowledge)
        assertEquals(0L, zero.relativeSeconds.value)
        assertEquals(now, zero.relativeDerived.value)
        val overflow = parse(payload(window(reset = ",\"reset_at\":9223372036854775807,\"reset_after_seconds\":9223372036854775807"))).fiveHour.candidates.single().reset
        assertEquals(Knowledge.MALFORMED, overflow.absolute.knowledge)
        assertEquals(Knowledge.MALFORMED, overflow.relativeDerived.knowledge)
    }

    @Test fun purchasedBalanceIsNeverDecodedAsBankedEntitlement() {
        for (text in listOf("""{"credits":{"balance":"200","has_credits":true,"unlimited":true}}""",
            """{"credits":{"available_count":200},"rate_limit_reset_credits":null}""")) {
            assertNull(parse(text).bankedAvailableCount.value)
        }
        assertEquals(2L, parse("""{"credits":{"balance":"200"},"rate_limit_reset_credits":{"available_count":2}}""").bankedAvailableCount.value)
    }

    @Test fun syntaxNonfiniteDuplicateKeysAndNonObjectRootsFailAtA4Boundary() {
        // Nonfinite tokens/overflow are invalid JSON at A4, not a salvageable sibling.
        for (text in listOf("not-json", "[]", "null", "true", "42", "\"text\"", "{} trailing",
            """{"plan_type":"free","plan_type":"plus"}""", """{"plan_type":1,"plan_\u0074ype":2}""",
            payload(window("NaN")), payload(window("Infinity")), payload(window("1e309")))) {
            val failure = UsageResponseParser.parse(M0FixtureAdapters.body(text), now, now)
            assertEquals(PayloadResult.Failure(TransportFailure.INVALID_RESPONSE), failure)
            assertEquals(TransportFailure.INVALID_RESPONSE, (failure as PayloadResult.Failure).category)
        }
        assertTrue(UsageResponseParser.parse(M0FixtureAdapters.body(byteArrayOf(0xc3.toByte(), 0x28))) is PayloadResult.Failure)
        // Legal but unrepresentable decimal scale must not throw or retain a raw lexeme.
        val hugeScale = parse(payload(window("1e-9999999999"))).fiveHour.candidates.single().usedPercent
        assertEquals(Knowledge.MALFORMED, hugeScale.knowledge)
        val redacted = UsageResponseParser.parse(M0FixtureAdapters.body("""{"plan_type":"synthetic-private-plan"}"""))
        assertEquals("PayloadDecoded(redacted)", redacted.toString())
        val clockless = M0FixtureAdapters.observation(UsageResponseParser.parse(M0FixtureAdapters.body("{}")))
        assertNull(clockless.observedAt)
    }
}
