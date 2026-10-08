package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.credentials.LocalCredentialNamespace
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Literal UI-model oracles over synthetic A1 -> C1 -> B2 input, not a second entitlement projection. */
class EntitlementIntegrationTest {
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val generation = SessionGeneration(LocalCredentialNamespace())
    private val summaryTime = Instant.parse("2026-10-07T23:59:00Z")
    private val usage = UsageNormalizer.normalize(UsageInput(bankedAvailableCount = Input.Value(1)), summaryTime, now)

    private fun inventory(count: Input<Any> = Input.Value(1), rows: Input<List<Input<ResetItemInput>>> = Input.Value(listOf(row())),
        summary: UsageObservation = usage) = BankedResetNormalizer.normalize(Input.Value(InventoryInput(count, rows)), summary, now, now)

    private fun row(expiry: Input<Any> = Input.Value("2026-10-09T14:31:56.833553Z")) = Input.Value(
        ResetItemInput(Input.Value("synthetic-c2-item"), Input.Value("codex_rate_limits"), Input.Value("available"),
            Input.Value("2026-10-01T00:00:00Z"), expiry))

    private fun endpoint(inventory: BankedResetObservation? = inventory(), error: ReadError? = null): RefreshedEndpoint {
        val success = inventory?.let { EndpointObservation(ReadOperation.RESET_INVENTORY, observedAt = now, inventory = it) }
        val attempt = error?.let { EndpointObservation(ReadOperation.RESET_INVENTORY, error = it) } ?: success
        return RefreshedEndpoint(attempt, success, 19L)
    }

    private fun connection(detail: RefreshedEndpoint = endpoint(), summary: RefreshedEndpoint = RefreshedEndpoint(
        EndpointObservation(ReadOperation.USAGE, observedAt = summaryTime, usage = usage),
        EndpointObservation(ReadOperation.USAGE, observedAt = summaryTime, usage = usage), 7L),
        phase: ConnectionPhase = ConnectionPhase.OBSERVED, refreshing: Boolean = false, evaluated: Instant? = now,
        auth: AuthState = AuthState.Connected(generation)) = ConnectionState(phase, auth,
        refresh = UsageRefreshState(summary, detail, evaluated, refreshing))

    private fun present(state: ConnectionState = connection()) = BankedResetPresentation.present(state, ZoneOffset.UTC, Locale.US)

    @Test fun availableUsesC1CountsAndBothObservationClocksWithoutInferringAmount() {
        val model = present()
        val facts = model.entitlements
        assertEquals(EntitlementState.KNOWN, facts.state)
        assertEquals(1L, facts.summaryAvailableCount?.value)
        assertEquals(1L, facts.reportedAvailableCount?.value)
        assertEquals(summaryTime, facts.summaryObservedAt)
        assertEquals(now, facts.inventoryObservedAt)
        assertEquals(AbsoluteTime("Oct 9, 2026", "14:00", null), facts.items.single().expiry.absolute)
        assertEquals(RemainingTime(1, 14), facts.items.single().expiry.remaining)
        assertFalse(model.stale)
        assertFalse(model.refreshing)
        assertNull(model.errorResource)
    }

    @Test fun emptyUnknownUnsupportedMalformedAndInaccessibleAreNotInterchangeable() {
        val zeroSummary = UsageNormalizer.normalize(UsageInput(bankedAvailableCount = Input.Value(0)), summaryTime, now)
        val explicitEmpty = inventory(Input.Value(0), Input.Value(emptyList()), zeroSummary)
        assertEquals(EntitlementState.EMPTY, present(connection(endpoint(explicitEmpty))).entitlements.state)
        assertEquals(EntitlementState.UNKNOWN, present(connection(endpoint(inventory(rows = Input.Missing)))).entitlements.state)
        assertEquals(EntitlementState.UNSUPPORTED, present(connection(endpoint(inventory(rows = Input.Value(listOf(
            row(Input.Value("2026-10-09T00:00:00+00:00")))))))).entitlements.state)
        assertEquals(EntitlementState.MALFORMED, present(connection(endpoint(inventory(rows = Input.Invalid)))).entitlements.state)
        val inaccessible = present(connection(endpoint(null, ReadError.FORBIDDEN)))
        assertEquals(EntitlementState.INACCESSIBLE, inaccessible.entitlements.state)
        assertNull(inaccessible.entitlements.reportedAvailableCount)
        assertEquals(1L, inaccessible.entitlements.summaryAvailableCount?.value)
        assertEquals(R.string.banked_error_forbidden, inaccessible.errorResource)
    }

    @Test fun summaryDiscrepancyRetainsBothLiteralProviderCounts() {
        val different = UsageNormalizer.normalize(UsageInput(bankedAvailableCount = Input.Value(7)), summaryTime, now)
        val facts = present(connection(endpoint(inventory(summary = different)))).entitlements
        assertEquals(EntitlementState.DISCREPANT, facts.state)
        assertEquals(7L, facts.summaryAvailableCount?.value)
        assertEquals(1L, facts.reportedAvailableCount?.value)
        assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH), facts.issues)
    }

    @Test fun expiredAndSubHourItemsKeepDistinctExpiryKindAndOriginalCount() {
        val facts = inventory(rows = Input.Value(listOf(row(Input.Value("2026-10-08T00:59:59.999999Z")))))
        val first = present(connection(endpoint(facts))).entitlements
        assertEquals(TimeState.LESS_THAN_HOUR, first.items.single().expiry.state)
        assertEquals(TimeKind.ENTITLEMENT_EXPIRY, first.items.single().expiry.kind)
        assertEquals(AbsoluteTime("Oct 8, 2026", "00:00", null), first.items.single().expiry.absolute)
        val expired = present(connection(endpoint(facts), evaluated = Instant.parse("2026-10-08T01:00:00Z"))).entitlements
        assertEquals(EntitlementState.EXPIRED, expired.items.single().state)
        assertEquals(EntitlementState.DISCREPANT, expired.state)
        assertEquals(1L, expired.reportedAvailableCount?.value)
        assertEquals(setOf(InventoryIssue.EXPIRED_AVAILABLE_ITEM), expired.issues)
    }

    @Test fun usageFailureDoesNotChangeRetainedInventoryStateClockErrorOrFreshness() {
        val state = connection()
        val failed = state.refresh.usage.copy(attempt = EndpointObservation(ReadOperation.USAGE, error = ReadError.FORBIDDEN), stale = true)
        val model = present(connection(summary = failed, refreshing = true))
        assertEquals(EntitlementState.KNOWN, model.entitlements.state)
        assertEquals(now, model.entitlements.inventoryObservedAt)
        assertEquals(summaryTime, model.entitlements.summaryObservedAt)
        assertFalse(model.stale)
        assertTrue(model.refreshing)
        assertNull(model.errorResource)
        assertEquals(ReadError.FORBIDDEN, model.entitlements.summaryError)
        assertEquals(19L, state.refresh.inventory.successfulAtMillis)
        assertEquals(7L, failed.successfulAtMillis)
    }

    @Test fun inventoryFailureAndStalenessDoNotReplaceUsageClockOrFacts() {
        val state = connection(detail = endpoint(error = ReadError.FORBIDDEN).copy(stale = true))
        val model = present(state)
        assertEquals(EntitlementState.INACCESSIBLE, model.entitlements.state)
        assertEquals(1L, model.entitlements.reportedAvailableCount?.value)
        assertEquals(now, model.entitlements.inventoryObservedAt)
        assertTrue(model.stale)
        assertEquals(UsageScreenStatus.CURRENT, UsagePresentation.present(state, ZoneOffset.UTC, Locale.US).status)
        assertEquals(7L, state.refresh.usage.successfulAtMillis)
        assertEquals(summaryTime, state.refresh.usage.success?.observedAt)
    }

    @Test fun disconnectedRetirementAndMissingGenerationDiscardAllFactsEvenWithRetainedInput() {
        for (phase in listOf(ConnectionPhase.IDLE, ConnectionPhase.AUTHENTICATING, ConnectionPhase.CANCELLED,
            ConnectionPhase.SIGNING_OUT, ConnectionPhase.SIGNED_OUT, ConnectionPhase.REAUTH_REQUIRED, ConnectionPhase.FAILED)) {
            val model = present(connection(phase = phase, refreshing = true, detail = endpoint(error = ReadError.FORBIDDEN).copy(stale = true)))
            assertTrue(model.entitlements.items.isEmpty())
            assertNull(model.entitlements.summaryAvailableCount)
            assertNull(model.entitlements.inventoryObservedAt)
            assertNull(model.errorResource)
            assertFalse(model.refreshing)
            assertFalse(model.stale)
        }
        assertTrue(present(connection(auth = AuthState.Idle)).entitlements.items.isEmpty())
        assertTrue(present(ConnectionState(ConnectionPhase.RESTORED, AuthState.Connected(generation))).entitlements.items.isEmpty())
        assertEquals(TimeState.UNKNOWN_CLOCK, present(connection(evaluated = null)).entitlements.items.single().expiry.state)
    }

    @Test fun fieldKnowledgeAndEveryReasonHaveExplicitResourceLabels() {
        for ((field, resource) in listOf(
            null to R.string.banked_field_unknown,
            Field<Long>(Knowledge.KNOWN) to R.string.banked_field_unknown,
            Field(Knowledge.KNOWN, 0L) to R.string.banked_field_known,
            Field<Long>(Knowledge.UNAVAILABLE) to R.string.banked_field_unknown,
            Field<Long>(Knowledge.MALFORMED) to R.string.banked_field_malformed,
            Field<Long>(Knowledge.UNSUPPORTED) to R.string.banked_field_unsupported)) {
            assertEquals(BankedFieldLabel(resource, null), BankedResetPresentation.fieldLabel(field))
        }
        for ((reason, resource) in listOf(Reason.MISSING to R.string.banked_reason_missing,
            Reason.PROVIDER_NULL to R.string.banked_reason_null, Reason.WRONG_TYPE to R.string.banked_reason_type,
            Reason.OUT_OF_RANGE to R.string.banked_reason_range, Reason.UNSUPPORTED_FORMAT to R.string.banked_reason_format,
            Reason.CLOCK_REQUIRED to R.string.banked_reason_clock)) {
            assertEquals(BankedFieldLabel(R.string.banked_field_unknown, resource),
                BankedResetPresentation.fieldLabel(Field<Long>(Knowledge.UNAVAILABLE, reason = reason)))
        }
    }

    @Test fun statusAndTypeLabelsAreAllowlistedInsteadOfShowingArbitraryProviderStrings() {
        assertEquals(R.string.banked_available, BankedResetPresentation.providerStatus(Field(Knowledge.KNOWN, "available")).knowledgeResource)
        assertEquals(R.string.banked_reset_type, BankedResetPresentation.resetType(Field(Knowledge.KNOWN, "codex_rate_limits")).knowledgeResource)
        assertEquals(R.string.banked_field_unsupported, BankedResetPresentation.providerStatus(Field(Knowledge.KNOWN, "synthetic-other-status")).knowledgeResource)
        assertEquals(R.string.banked_field_unsupported, BankedResetPresentation.resetType(Field(Knowledge.KNOWN, "synthetic-other-type")).knowledgeResource)
        assertEquals(R.string.banked_field_unknown, BankedResetPresentation.providerStatus(null).knowledgeResource)
        assertEquals(R.string.banked_field_unknown, BankedResetPresentation.resetType(Field(Knowledge.KNOWN)).knowledgeResource)
    }

    @Test fun everyInventoryErrorAndIssueMapsToBankedSpecificCopy() {
        for ((error, resource) in listOf(ReadError.REAUTHORIZE to R.string.banked_error_reauthorize,
            ReadError.FORBIDDEN to R.string.banked_error_forbidden, ReadError.RATE_LIMITED to R.string.banked_error_rate,
            ReadError.TRANSIENT to R.string.banked_error_transient, ReadError.INVALID_RESPONSE to R.string.banked_error_invalid,
            ReadError.BODY_TOO_LARGE to R.string.banked_error_invalid, ReadError.CANCELLED to R.string.banked_error_cancelled,
            ReadError.DEADLINE_EXCEEDED to R.string.banked_error_deadline, ReadError.OPERATION_NOT_ALLOWED to R.string.banked_error_unsupported)) {
            assertEquals(resource, present(connection(endpoint(error = error))).errorResource)
        }
        for ((issue, resource) in listOf(InventoryIssue.SUMMARY_COUNT_MISMATCH to R.string.banked_issue_summary,
            InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH to R.string.banked_issue_rows,
            InventoryIssue.EXPIRED_AVAILABLE_ITEM to R.string.banked_issue_expired,
            InventoryIssue.DUPLICATE_IDENTITY to R.string.banked_issue_duplicate,
            InventoryIssue.UNKNOWN_STATUS to R.string.banked_issue_status,
            InventoryIssue.UNKNOWN_RESET_TYPE to R.string.banked_issue_type)) {
            assertEquals(resource, BankedResetPresentation.issueResource(issue))
        }
    }
}
