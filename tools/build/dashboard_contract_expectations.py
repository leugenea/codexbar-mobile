"""Independent issue142 regression oracles; never import registries under validation."""
EXPECTED_DASHBOARD_JVM = {
    "io.github.leugenea.codexbarmobile.HistoryPlotCacheTest": {
        "metadataTicksReuseWholeWindowGraphAndDrawingInputsWhileKeepingLiveMetadataAndPaceCurrent",
        "changedPageQueryGenerationPartitionRequestAndSelectionRebuildWholeWindowInputs",
        "cachedCurrentInputsNeverBypassPeriodOrDisplayRevocation",
    },
    "io.github.leugenea.codexbarmobile.DashboardPresentationTest": {
        "compactChartEquivalentUsesOriginalExactFactsAndIndependentReferences",
        "compactMissingChartAndPhaseActionsKeepTypedErrorsAndSinglePointTruth",
        "reportedInventoryCountNeverFallsBackToSummaryRowsOrZero",
        "bankedRowDetailsKeepMinutePrecisionAndIndependentStatesWithoutDuplicateAnnouncements",
        "paceLineUsesOverReserveAndOnPaceWithoutChangingExactComparison",
        "paceLineHalfTenthBoundariesRoundAwayFromZero",
        "paceLineNearZeroAndAdjacentBoundaryValuesNeverShowSignedZero",
        "paceLineKeepsOneDecimalAndLocaleSeparator",
        "compactPaceBeyondFirst32ObservationsMatchesAcceptedHeadlineAndClock",
        "compactWholeWindowLastPointEndLabelAndTrendReachLatestObservationBeyond32",
        "compactPaceKeepsIndependentlyAgingFiveHourAndWeeklyObservationsSeparate",
        "compactPaceNeverFallsBackWhenAcceptedComparisonIsMissingOrIneligible",
        "chartTimeLabelsUseYearlessLocalePatternsAndContainingHour",
        "chartTimeLabelsRetainFullAndPartialRepeatedHourOffsets",
        "chartTimeLabelsKeepUnrepresentableInstantsUnknown",
    },
    "io.github.leugenea.codexbarmobile.history.HistoryDisplayAuthorityTest": {
        "currentNullDiagnosticSelectionCannotReplaySuccessorOrRetiredLoss",
        "heldJointFactsRejectEqualRequestAfterRouteRoundTrip",
        "heldJointFactsRejectResetExpiryClockLossAndRetiredContexts",
    },
    "io.github.leugenea.codexbarmobile.history.UsageHistoryRecorderTest": {
        "heldJointRequestRejectsRouteQueryAndAccountSuccessors",
        "jointRequestPreservesQueueLossGapAndContiguousDurableOrdinals",
        "jointRequestUsesOneWorkerAndCoalescesLatestSelectionWithoutAdmissions",
        "admittedPreappendFailureUnderChangedSelectionRetainsActualLossAndNextGap",
        "admittedAppendFailureUnderChangedSelectionRetainsActualLossWithoutPresentationLoss",
    },
}
EXPECTED_DASHBOARD_NATIVE = {
    "io.github.leugenea.codexbarmobile.DashboardCopyAndTimeTest": {
        "dashboardAndDisclosureFollowPhoneZoneLocaleAndTicksWithoutRequests",
        "minutePrecisionMatchesDashboardWindowDetailsAndBankedExpiry",
        "simplifiedWindowsKeepOneTopObservationYearlessAxisAndReadablePace",
        "noReachableTextOrSemanticsUsesRemovedTimePhraseWhileOriginalInstantsRemainExact",
    },
    "io.github.leugenea.codexbarmobile.DashboardHistoryIsolationTest": {
        "heldPairCollectionCannotReplayEqualQueryAfterDetailsRoundTrip",
        "heldPairCollectionCannotReplayPreviousPeriodAfterResetExpiryOrClockLoss",
        "heldPairReadCannotPublishAcrossRouteSwitchAccountReplacementAndForegroundRetirement",
        "pairReadsRemainBoundedBehindArchivesAndCoalesceWithoutExtraAdmissions",
    },
    "io.github.leugenea.codexbarmobile.DashboardUiTest": {
        "bankedTableUsesReportedCountAndRetainsEveryExpiryAndUnknownRow",
        "dashboardActionsFollowConnectionPhaseAndDormantNavigationDoesNotRead",
        "dashboardDarkLandscapeAndLargeFontKeepAccessibleTargetsAndCompleteText",
        "dashboardMissingEmptySingleAndFailedHistoryHaveOneHonestLinePerWindow",
        "dashboardShowsBothCurrentWindowsWithoutHistoryNavigationAndRetainsMeasuredReferencePixels",
        "drawerHasExactTwoLabelsBackPrecedenceAndDetailsReturnToDashboard",
        "emptyDashboardOpensAccountSettingsWithoutInlineManagementOrProviderRequests",
        "dashboardKnownPercentPreservesMalformedResetAndIndependentCurrentSiblingWarning",
        "fiveHourFractionalResetPairPopulatesDashboardButBeyondToleranceRemainsUnavailable",
    },
}
SCANNER_CLASS = "io.github.leugenea.codexbarmobile.history.CurrentHistoryReadTest"
EXPECTED_SCANNER_ADDITIONS = {
    "jointCurrentScanFindsBothWindowsBehindArchivesWithIndependentLookahead",
    "jointCurrentScanMissingKeysStatusRetentionAndFailuresStayTyped",
    "jointCurrentScanHasOneHighWaterCeilingAndRejectsRevocation",
    "wholeCurrentWindowRetainsEveryObservationBeyond32AcrossBatchesWithoutChangingDetailPages",
}
# These pre-existing source tests remain protected, but this does not make unrelated
# JVM methods new mandatory report targets.
PROTECTED_MIXED_SOURCE = {
    SCANNER_CLASS: {
        "currentMeasurementsBehindArchiveFilledPagesUseRealSelectedLookaheadAndBoundedReads",
        "selectedOrdinalHolesCorrectionsAndResetBoundariesCannotCreateContiguousMeasuredEdges",
        "emptySelectionRetentionReadFailuresCorruptionAndRevocationRemainExplicit",
        "keyedUnknownPercentStatusIsRetainedWithoutInventedMeasurementOrReference",
    },
    "io.github.leugenea.codexbarmobile.history.HistoryDisplayAuthorityTest": {
        "nullDiagnosticIdentityCannotReplayAcrossRetireBoundSuccessorAndNullRebind",
        "backgroundRevokesNullContextButResumeRetainsItsLossWithoutReplayingOldContext",
        "nullAccessWithExistingBindingKeepsItsContextAndBoundWorkerAdmission",
        "sameSlotRevokesHeldWorkerBeforeReadAndAllowsSuccessorDrain",
    },
    "io.github.leugenea.codexbarmobile.history.UsageHistoryRecorderTest": {
        "weeklyAcceptedObservationsKeepExactAbsoluteIdentitiesAndDiscrepantPointsIsolated",
        "historicalWeeklyDiscrepancyFlagIsConsumedWithoutRenormalizingStoredFacts",
        "acceptedEqualPercentReadsHaveDistinctIdsButCoalescingTicksAndQueriesDoNotSample",
        "usageDeliveryPersistsBeforeInventoryCompletionAndPartialFailuresNeverReplayCache",
        "dormantRestoreOffscreenReadAndInventoryOnlyNeverInventMeasurements",
        "lastObserverLossAndCancelRejectHeldResponsesAndWritesWithoutBackfill",
        "queueOverflowIsBoundedExplicitLossAndNextObservationBreaksWithoutRetry",
        "logoutWhileDurableWorkIsHeldCannotPublishOrResurrectIntoNewLogin",
        "categoricalWriteReadCorruptionAndThrownFailuresKeepLiveQuotaUsable",
        "realCorrectionsResetUnknownAndClockDiscontinuitiesKeepMathAndSegmentsTruthful",
        "orderedBoundedQueriesExposePaginationSelectionAndIndependentStaleness",
        "automaticCadenceAndRetryAfterRemainB2OwnedWithNoHistoryRetryWork",
        "heldQueriesRejectEarlierSelectionAndLogoutResultsWithoutExtraProviderWork",
        "acceptedResumeWhileRuntimeReadoptionIsHeldKeepsCapturedPartitionWithoutRetry",
    },
}
EXPECTED_CURRENT_READ_JVM = PROTECTED_MIXED_SOURCE[SCANNER_CLASS] | EXPECTED_SCANNER_ADDITIONS
EXPECTED_DASHBOARD_CAPTURE_SPECS = {
    "dashboard-main-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "FIVE_HOUR"),
    "dashboard-weekly-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "WEEKLY"),
    "dashboard-banked-table-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "NONE"),
    "dashboard-drawer-portrait-dark": (1, True, 1.0, "DASHBOARD", "CURRENT", "NONE"),
    "dashboard-main-landscape-dark-large-font": (2, True, 2.0, "DASHBOARD", "CURRENT", "FIVE_HOUR"),
    "dashboard-connection-details-landscape-dark": (2, True, 1.0, "CONNECTION_DETAILS", "IDLE", "NONE"),
    "dashboard-local-history-details-large-font": (1, False, 2.0, "LOCAL_HISTORY_DETAILS", "PAGE", "NONE"),
}
EXPECTED_DASHBOARD_LAYOUTS = {
    "dashboard-main-portrait-light": ("history-chart-five-hour-pace", ["connection-screen", "dashboard"]),
    "dashboard-weekly-portrait-light": ("history-chart-weekly-pace", ["connection-screen", "dashboard"]),
    "dashboard-banked-table-portrait-light": ("banked-disclosure-title", ["connection-screen", "dashboard"]),
    "dashboard-drawer-portrait-dark": ("drawer-LOCAL_HISTORY_DETAILS", ["connection-screen", "dashboard", "details-drawer"]),
    "dashboard-main-landscape-dark-large-font": ("history-chart-five-hour-pace", ["connection-screen", "dashboard"]),
    "dashboard-connection-details-landscape-dark": ("banked-container", ["connection-screen", "connection-details"]),
    "dashboard-local-history-details-large-font": ("history-page-cursor", ["connection-screen", "connection-history"]),
}
EXPECTED_BANKED_TABLE_ROWS = [
    dict(expiry="Jan 15, 09:00", remaining="1 hour", textCellCount=2,
         description="Banked reset 1, expires Jan 15, 09:00, 1 hour remaining"),
    dict(expiry="Jan 15, 07:00", remaining="Expired", textCellCount=2,
         description="Banked reset 2, expired Jan 15, 07:00"),
    dict(expiry="—", remaining="Unknown", textCellCount=2,
         description="Banked reset 3, expiry unknown, state Unknown"),
]

EXPECTED_CURRENT_HOST = {"context": "MATCH", "selection": "MATCH", "periods": "MATCH", "projection": "MATCH"}

# Literal review budgets, independently checked against the enumerated sets.
EXPECTED_DASHBOARD_NATIVE_COUNT = 17
EXPECTED_DASHBOARD_JVM_COUNT = 26
