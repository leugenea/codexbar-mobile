package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.credentials.LocalCredentialNamespace
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic provider facts. Literal oracles, no activation or live credentials. */
class EntitlementPresentationTest {
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val generation = SessionGeneration(LocalCredentialNamespace())
    private val future = "2026-10-09T14:31:56.833553Z"

    private fun row(
        expiry: Input<Any> = Input.Value(future), status: Input<Any> = Input.Value("available"),
        type: Input<Any> = Input.Value("codex_rate_limits"), id: Input<Any> = Input.Value("synthetic-row"),
    ) = Input.Value(ResetItemInput(id, type, status, Input.Value("2026-10-01T00:00:00Z"), expiry))

    private fun observation(
        count: Input<Any> = Input.Value(1), rows: Input<List<Input<ResetItemInput>>> = Input.Value(listOf(row())),
        summary: Input<Any> = Input.Value(1), evaluated: Instant? = now,
    ) = BankedResetNormalizer.normalize(
        Input.Value(InventoryInput(count, rows)),
        UsageNormalizer.normalize(UsageInput(bankedAvailableCount = summary), Instant.parse("2026-10-07T23:59:00Z"), now),
        now, evaluated,
    )

    private fun snapshot(
        inventory: BankedResetObservation? = observation(), error: ReadError? = null,
        owner: SessionGeneration = generation, usage: UsageObservation? = null, summaryError: ReadError? = null,
    ) = EntitlementSnapshot(owner, FeasibilityObservations(
        EndpointObservation(ReadOperation.USAGE, error = summaryError, usage = usage),
        EndpointObservation(ReadOperation.RESET_INVENTORY, error = error, inventory = inventory),
    ))

    private fun present(
        facts: BankedResetObservation = observation(), at: Instant? = now, zone: ZoneId = ZoneOffset.UTC,
    ) = EntitlementPresentation.present(snapshot(facts), generation, at, zone, Locale.US)

    @Test fun availablePreservesBothCountsRowsStatusTypeAndIndependentClocks() {
        val result = present()
        assertEquals(EntitlementState.KNOWN, result.state)
        assertEquals(EntitlementSnapshotState.CURRENT_GENERATION, result.snapshot)
        assertEquals(Field(Knowledge.KNOWN, 1L), result.summaryAvailableCount)
        assertEquals(Field(Knowledge.KNOWN, 1L), result.reportedAvailableCount)
        assertEquals(1, result.inventoryRowCount)
        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(Instant.parse("2026-10-07T23:59:00Z"), result.summaryObservedAt)
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), result.inventoryObservedAt)
        assertEquals(emptySet<InventoryIssue>(), result.issues)
        assertNull(result.summaryError)
        assertNull(result.inventoryError)
        val item = result.items.single()
        assertEquals(EntitlementState.KNOWN, item.state)
        val source = item.source.value!!
        assertEquals(Field(Knowledge.KNOWN, "available"), source.providerStatus)
        assertEquals(Field(Knowledge.KNOWN, "codex_rate_limits"), source.resetType)
        assertEquals(Instant.parse("2026-10-09T14:31:56.833553Z"), source.expiresAt.value)
        assertEquals(TimeKind.ENTITLEMENT_EXPIRY, item.expiry.kind)
        assertEquals(TimeState.FUTURE, item.expiry.state)
        assertEquals(AbsoluteTime("Oct 9, 2026", "14:00", null), item.expiry.absolute)
        assertEquals(RemainingTime(1, 14), item.expiry.remaining)
        assertEquals(TimeSnapshot.OBSERVED, item.expiry.snapshot)
        assertEquals(R.string.entitlement_expiry, item.expiryLabelResource)
    }

    @Test fun emptyRequiresAnExplicitProviderZeroAndCompleteEmptyInventory() {
        val result = present(observation(Input.Value(0), Input.Value(emptyList()), Input.Value(0)))
        assertEquals(EntitlementState.EMPTY, result.state)
        assertEquals(0L, result.reportedAvailableCount?.value)
        assertEquals(0L, result.summaryAvailableCount?.value)
        assertEquals(0, result.inventoryRowCount)
        assertEquals(emptyList<PresentedEntitlementItem>(), result.items)
        assertEquals(Completeness.COMPLETE, result.completeness)
    }

    @Test fun missingNullAndMalformedCountsRetainKnowledgeWithoutRowCountFallback() {
        for ((input, expected, reason) in listOf(
            Triple(Input.Missing, EntitlementState.UNKNOWN, Reason.MISSING),
            Triple(Input.Null, EntitlementState.UNKNOWN, Reason.PROVIDER_NULL),
            Triple(Input.Invalid, EntitlementState.MALFORMED, Reason.WRONG_TYPE),
        )) {
            val result = present(observation(count = input))
            assertEquals(expected, result.state)
            assertNull(result.reportedAvailableCount?.value)
            assertEquals(reason, result.reportedAvailableCount?.reason)
            assertEquals(1L, result.summaryAvailableCount?.value)
            assertEquals(1, result.inventoryRowCount)
        }
    }

    @Test fun unavailableInventoryShapeDoesNotBecomeEmptyEvenWithKnownZero() {
        for (rows in listOf(Input.Missing, Input.Null, Input.Invalid)) {
            val result = present(observation(Input.Value(0), rows, Input.Value(0)))
            assertEquals(EntitlementState.UNKNOWN, result.state)
            assertEquals(0L, result.reportedAvailableCount?.value)
            assertEquals(Completeness.UNKNOWN, result.completeness)
            assertNull(result.inventoryRowCount)
            assertTrue(result.items.isEmpty())
        }
    }

    @Test fun nullMissingUnsupportedAndMalformedExpiryNeverMeanInfiniteLifetime() {
        for ((input, state, knowledge) in listOf(
            Triple(Input.Null, EntitlementState.UNKNOWN, Knowledge.UNAVAILABLE),
            Triple(Input.Missing, EntitlementState.UNKNOWN, Knowledge.UNAVAILABLE),
            Triple(Input.Value("2026-10-09T14:00:00+00:00"), EntitlementState.UNSUPPORTED, Knowledge.UNSUPPORTED),
            Triple(Input.Value("2026-02-30T00:00:00Z"), EntitlementState.MALFORMED, Knowledge.MALFORMED),
        )) {
            val result = present(observation(rows = Input.Value(listOf(row(expiry = input)))))
            val item = result.items.single()
            assertEquals(state, result.state)
            assertEquals(state, item.state)
            assertEquals(knowledge, item.expiry.targetKnowledge)
            assertEquals(TimeState.UNKNOWN_TARGET, item.expiry.state)
            assertNull(item.expiry.absolute)
            assertNull(item.expiry.remaining)
            assertEquals(1L, result.reportedAvailableCount?.value)
        }
    }

    @Test fun expiredAvailableRetainsProviderCountAndShowsBothExpiryAndDiscrepancy() {
        val result = present(at = Instant.parse("2026-10-09T14:31:56.833553Z"))
        assertEquals(EntitlementState.DISCREPANT, result.state)
        assertEquals(setOf(InventoryIssue.EXPIRED_AVAILABLE_ITEM), result.issues)
        assertEquals(1L, result.reportedAvailableCount?.value)
        assertEquals(1L, result.summaryAvailableCount?.value)
        val item = result.items.single()
        assertEquals(EntitlementState.EXPIRED, item.state)
        assertEquals(TimeState.EXPIRED, item.expiry.state)
        assertEquals(TimeSnapshot.STALE, item.expiry.snapshot)
        val source = item.source.value!!
        assertEquals("available", source.providerStatus.value)
        assertEquals(false, source.locallyExpired)
        assertNull(item.expiry.remaining)
    }

    @Test fun cachedLocalExpiryFlagAndIssueAreNotUsedAsCurrentTimeEvidence() {
        val cached = observation(evaluated = Instant.parse("2026-10-10T00:00:00Z"))
        assertEquals(setOf(InventoryIssue.EXPIRED_AVAILABLE_ITEM), cached.issues)
        val result = present(cached)
        assertEquals(EntitlementState.KNOWN, result.state)
        assertEquals(emptySet<InventoryIssue>(), result.issues)
        assertEquals(true, result.items.single().source.value!!.locallyExpired)
        assertEquals(TimeState.FUTURE, result.items.single().expiry.state)
        val noClock = present(cached, at = null)
        assertEquals(EntitlementState.UNKNOWN, noClock.state)
        assertEquals(TimeState.UNKNOWN_CLOCK, noClock.items.single().expiry.state)
        assertEquals(AbsoluteTime("Oct 9, 2026", "14:00", null), noClock.items.single().expiry.absolute)
        assertTrue(noClock.issues.isEmpty())
    }

    @Test fun subHourExpiryRemainsEntitlementExpiryNotOrdinaryReset() {
        val result = present(observation(rows = Input.Value(listOf(row(Input.Value("2026-10-08T00:59:59.999999Z"))))))
        val item = result.items.single()
        assertEquals(EntitlementState.KNOWN, result.state)
        assertEquals(TimeKind.ENTITLEMENT_EXPIRY, item.expiry.kind)
        assertEquals(TimeState.LESS_THAN_HOUR, item.expiry.state)
        assertEquals(AbsoluteTime("Oct 8, 2026", "00:00", null), item.expiry.absolute)
        assertNull(item.expiry.remaining)
        assertEquals(R.string.time_less_than_hour, item.expiry.state.labelResource)
    }

    @Test fun repeatedDstHourUsesB1AbsoluteOffsetAndElapsedRemainingTime() {
        val at = Instant.parse("2026-11-01T04:30:00Z")
        val facts = observation(rows = Input.Value(listOf(row(Input.Value("2026-11-01T06:30:00.000001Z")))))
        val item = present(facts, at, ZoneId.of("America/New_York")).items.single()
        assertEquals(TimeKind.ENTITLEMENT_EXPIRY, item.expiry.kind)
        assertEquals(AbsoluteTime("Nov 1, 2026", "01:00", "-05:00"), item.expiry.absolute)
        assertEquals(RemainingTime(0, 2), item.expiry.remaining)
    }

    @Test fun summaryDisagreementIsVisibleAndNeitherCountIsRecomputed() {
        val result = present(observation(summary = Input.Value(7)))
        assertEquals(EntitlementState.DISCREPANT, result.state)
        assertEquals(7L, result.summaryAvailableCount?.value)
        assertEquals(1L, result.reportedAvailableCount?.value)
        assertEquals(1, result.inventoryRowCount)
        assertEquals(setOf(InventoryIssue.SUMMARY_COUNT_MISMATCH), result.issues)
        assertEquals(EntitlementState.KNOWN, result.items.single().state)
    }

    @Test fun rowDisagreementNeverReplacesAReportedCountWithInventorySize() {
        val result = present(observation(count = Input.Value(8), summary = Input.Value(8)))
        assertEquals(EntitlementState.DISCREPANT, result.state)
        assertEquals(8L, result.reportedAvailableCount?.value)
        assertEquals(8L, result.summaryAvailableCount?.value)
        assertEquals(1, result.inventoryRowCount)
        assertEquals(setOf(InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH), result.issues)
    }

    @Test fun duplicateIdentityAndUnknownProviderStatusRemainVisible() {
        val duplicate = present(observation(Input.Value(2), Input.Value(listOf(row(), row())), Input.Value(2)))
        assertEquals(EntitlementState.DISCREPANT, duplicate.state)
        assertEquals(setOf(InventoryIssue.DUPLICATE_IDENTITY), duplicate.issues)
        assertEquals(2, duplicate.items.size)
        val unknownStatus = present(observation(rows = Input.Value(listOf(row(status = Input.Value("future-status"))))))
        assertEquals(EntitlementState.UNSUPPORTED, unknownStatus.state)
        assertEquals("future-status", unknownStatus.items.single().source.value!!.providerStatus.value)
        assertEquals(setOf(InventoryIssue.UNKNOWN_STATUS), unknownStatus.issues)
        assertEquals(1L, unknownStatus.reportedAvailableCount?.value)
    }

    @Test fun unknownResetTypeAndMissingStatusAreNotActivationPermission() {
        val type = present(observation(rows = Input.Value(listOf(row(type = Input.Value("future-reset"))))))
        assertEquals(EntitlementState.UNSUPPORTED, type.state)
        assertEquals("future-reset", type.items.single().source.value!!.resetType.value)
        assertEquals(setOf(InventoryIssue.UNKNOWN_RESET_TYPE), type.issues)
        val status = present(observation(rows = Input.Value(listOf(row(status = Input.Missing)))))
        assertEquals(EntitlementState.UNKNOWN, status.state)
        assertEquals(Reason.MISSING, status.items.single().source.value!!.providerStatus.reason)
    }

    @Test fun malformedAndMissingRowsDoNotDiscardAValidSibling() {
        for ((bad, expected) in listOf(Input.Invalid to EntitlementState.MALFORMED, Input.Null to EntitlementState.UNKNOWN)) {
            val result = present(observation(Input.Value(2), Input.Value(listOf(bad, row())), Input.Value(2)))
            assertEquals(expected, result.state)
            assertEquals(2L, result.reportedAvailableCount?.value)
            assertEquals(2, result.inventoryRowCount)
            assertEquals(Completeness.PARTIAL, result.completeness)
            assertEquals(expected, result.items[0].state)
            assertEquals(TimeState.UNKNOWN_TARGET, result.items[0].expiry.state)
            assertEquals(EntitlementState.KNOWN, result.items[1].state)
            assertEquals(TimeState.FUTURE, result.items[1].expiry.state)
        }
    }

    @Test fun missingNonDisplayIdentityKeepsInventoryPartialInsteadOfClaimingComplete() {
        val result = present(observation(rows = Input.Value(listOf(row(id = Input.Missing)))))
        assertEquals(EntitlementState.UNKNOWN, result.state)
        assertEquals(Completeness.PARTIAL, result.completeness)
        assertEquals(EntitlementState.KNOWN, result.items.single().state)
        assertEquals(Reason.MISSING, result.items.single().source.value!!.id.reason)
    }

    @Test fun endpointFailuresStayCategoricalAndNeverBecomeZero() {
        for ((error, expected) in listOf(
            ReadError.FORBIDDEN to EntitlementState.INACCESSIBLE,
            ReadError.REAUTHORIZE to EntitlementState.INACCESSIBLE,
            ReadError.TRANSIENT to EntitlementState.INACCESSIBLE,
            ReadError.INVALID_RESPONSE to EntitlementState.MALFORMED,
            ReadError.BODY_TOO_LARGE to EntitlementState.MALFORMED,
            ReadError.OPERATION_NOT_ALLOWED to EntitlementState.UNSUPPORTED,
            ReadError.CANCELLED to EntitlementState.UNKNOWN,
        )) {
            val usage = UsageNormalizer.normalize(UsageInput(bankedAvailableCount = Input.Value(3)), now, now)
            val result = EntitlementPresentation.present(snapshot(null, error, usage = usage), generation, now, ZoneOffset.UTC, Locale.US)
            assertEquals(expected, result.state)
            assertEquals(error, result.inventoryError)
            assertEquals(3L, result.summaryAvailableCount?.value)
            assertEquals(now, result.summaryObservedAt)
            assertNull(result.reportedAvailableCount)
            assertNull(result.inventoryObservedAt)
            assertNull(result.inventoryRowCount)
            assertEquals(Completeness.UNKNOWN, result.completeness)
            assertTrue(result.items.isEmpty())
        }
    }

    @Test fun failedSummaryDoesNotEraseIndependentInventoryObservation() {
        val facts = observation(summary = Input.Missing)
        val result = EntitlementPresentation.present(snapshot(facts, summaryError = ReadError.FORBIDDEN), generation, now, ZoneOffset.UTC, Locale.US)
        assertEquals(EntitlementState.KNOWN, result.state)
        assertEquals(ReadError.FORBIDDEN, result.summaryError)
        assertEquals(Reason.MISSING, result.summaryAvailableCount?.reason)
        assertEquals(1L, result.reportedAvailableCount?.value)
        assertEquals(TimeState.FUTURE, result.items.single().expiry.state)
    }

    @Test fun noInventoryObservationIsUnknownNotEmptyOrUnsupported() {
        val result = EntitlementPresentation.present(snapshot(null), generation, now, ZoneOffset.UTC, Locale.US)
        assertEquals(EntitlementState.UNKNOWN, result.state)
        assertNull(result.summaryAvailableCount)
        assertNull(result.reportedAvailableCount)
        assertNull(result.summaryObservedAt)
        assertTrue(result.items.isEmpty())
    }

    @Test fun oldGenerationSameNamespaceAndSignedOutRejectEveryFactBeforeProjection() {
        val replacement = SessionGeneration(generation.namespace)
        val old = snapshot(observation(summary = Input.Value(7)), summaryError = ReadError.TRANSIENT)
        for (active in listOf(replacement, null)) {
            val result = EntitlementPresentation.present(old, active, now, ZoneOffset.UTC, Locale.US)
            assertEquals(EntitlementSnapshotState.STALE_GENERATION, result.snapshot)
            assertEquals(EntitlementState.UNKNOWN, result.state)
            assertNull(result.summaryAvailableCount)
            assertNull(result.reportedAvailableCount)
            assertNull(result.summaryObservedAt)
            assertNull(result.inventoryObservedAt)
            assertNull(result.inventoryRowCount)
            assertNull(result.summaryError)
            assertNull(result.inventoryError)
            assertTrue(result.items.isEmpty())
            assertTrue(result.issues.isEmpty())
        }
    }

    @Test fun missingSnapshotDoesNotBorrowTheCurrentSessionsFacts() {
        val result = EntitlementPresentation.present(null, generation, now, ZoneOffset.UTC, Locale.US)
        assertEquals(EntitlementSnapshotState.UNKNOWN, result.snapshot)
        assertEquals(EntitlementState.UNKNOWN, result.state)
        assertNull(result.reportedAvailableCount)
        assertTrue(result.items.isEmpty())
    }

    @Test fun purchasedBalancePlanFlagsAndPeriodicResetCannotBecomeBankedAvailability() {
        val usage = M0FixtureAdapters.observation(UsageResponseParser.parse(M0FixtureAdapters.body("""
            {"credits":{"balance":"200","has_credits":true,"unlimited":true},"plan_type":"plus",
             "rate_limit":{"allowed":true,"primary_window":{"limit_window_seconds":18000,"used_percent":0,"reset_at":1791504000}}}
        """), now, now))
        val purchased = M0FixtureAdapters.observation(BankedResetResponseParser.parse(M0FixtureAdapters.body("""
            {"balance":"200","has_credits":true,"unlimited":true}
        """), usage, now, now))
        val result = present(purchased)
        assertEquals(EntitlementState.UNKNOWN, result.state)
        assertNull(result.summaryAvailableCount?.value)
        assertNull(result.reportedAvailableCount?.value)
        assertNull(result.inventoryRowCount)
        assertTrue(result.items.isEmpty())
        val summary = M0FixtureAdapters.observation(UsageResponseParser.parse(M0FixtureAdapters.body("""
            {"credits":{"balance":"200"},"rate_limit_reset_credits":{"available_count":2}}
        """), now, now))
        val banked = M0FixtureAdapters.observation(BankedResetResponseParser.parse(M0FixtureAdapters.body("""
            {"available_count":2,"credits":[],"balance":"200","has_credits":true,"unlimited":true}
        """), summary, now, now))
        val independent = present(banked)
        assertEquals(2L, independent.reportedAvailableCount?.value)
        assertEquals(2L, independent.summaryAvailableCount?.value)
        assertEquals(0, independent.inventoryRowCount)
        assertEquals(EntitlementState.DISCREPANT, independent.state)
        assertEquals(setOf(InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH), independent.issues)
    }

    @Test fun fieldKnowledgeSurvivesUnsupportedAndDefensivelyInconsistentTypedModels() {
        val base = observation()
        val unsupported = present(base.copy(reportedAvailableCount = Field(Knowledge.UNSUPPORTED, reason = Reason.UNSUPPORTED_FORMAT)))
        assertEquals(EntitlementState.UNSUPPORTED, unsupported.state)
        assertEquals(Reason.UNSUPPORTED_FORMAT, unsupported.reportedAvailableCount?.reason)
        val missingValue = present(base.copy(reportedAvailableCount = Field(Knowledge.KNOWN)))
        assertEquals(EntitlementState.UNKNOWN, missingValue.state)
        assertNull(missingValue.reportedAvailableCount?.value)
        val unsupportedRow = present(base.copy(items = listOf(Field(Knowledge.UNSUPPORTED, reason = Reason.UNSUPPORTED_FORMAT))))
        assertEquals(EntitlementState.UNSUPPORTED, unsupportedRow.items.single().state)
        assertEquals(Knowledge.UNSUPPORTED, unsupportedRow.items.single().expiry.targetKnowledge)
    }

    @Test fun itemStatePreservesMalformedStatusAndUnknownDisplayableTime() {
        val malformed = present(observation(rows = Input.Value(listOf(row(status = Input.Invalid)))))
        assertEquals(EntitlementState.MALFORMED, malformed.state)
        assertEquals(Reason.WRONG_TYPE, malformed.items.single().source.value!!.providerStatus.reason)
        val base = observation()
        val extreme = base.items.single().value!!.copy(expiresAt = Field(Knowledge.KNOWN, Instant.MAX))
        val unknownTime = present(base.copy(items = listOf(Field(Knowledge.KNOWN, extreme))))
        assertEquals(EntitlementState.UNKNOWN, unknownTime.state)
        assertEquals(TimeState.UNKNOWN_TARGET, unknownTime.items.single().expiry.state)
        assertNull(unknownTime.items.single().expiry.absolute)
    }
}
