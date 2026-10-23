"""Independent issue142 regression oracles; never import registries under validation."""
EXPECTED_DASHBOARD_JVM = {
    "io.github.leugenea.codexbarmobile.DashboardPresentationTest": {
        "compactChartEquivalentUsesOriginalExactFactsAndIndependentReferences",
        "compactMissingChartAndPhaseActionsKeepTypedErrorsAndSinglePointTruth",
        "reportedInventoryCountNeverFallsBackToSummaryRowsOrZero",
        "paceLineUsesOverReserveAndOnPaceWithoutChangingExactComparison",
        "paceLineHalfTenthBoundariesRoundAwayFromZero",
        "paceLineNearZeroAndAdjacentBoundaryValuesNeverShowSignedZero",
        "paceLineKeepsOneDecimalAndLocaleSeparator",
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
        "bankedDisclosureUsesReportedCountAndRetainsEveryExpiryAndUnknownRow",
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
    "dashboard-banked-expanded-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "NONE"),
    "dashboard-drawer-portrait-dark": (1, True, 1.0, "DASHBOARD", "CURRENT", "NONE"),
    "dashboard-main-landscape-dark-large-font": (2, True, 2.0, "DASHBOARD", "CURRENT", "FIVE_HOUR"),
    "dashboard-connection-details-landscape-dark": (2, True, 1.0, "CONNECTION_DETAILS", "IDLE", "NONE"),
    "dashboard-local-history-details-large-font": (1, False, 2.0, "LOCAL_HISTORY_DETAILS", "PAGE", "NONE"),
}
EXPECTED_DASHBOARD_LAYOUTS = {
    "dashboard-main-portrait-light": ("history-chart-five-hour-pace", ["connection-screen", "dashboard"]),
    "dashboard-weekly-portrait-light": ("history-chart-weekly-pace", ["connection-screen", "dashboard"]),
    "dashboard-banked-expanded-portrait-light": ("banked-disclosure-status", ["connection-screen", "dashboard"]),
    "dashboard-drawer-portrait-dark": ("drawer-LOCAL_HISTORY_DETAILS", ["connection-screen", "dashboard", "details-drawer"]),
    "dashboard-main-landscape-dark-large-font": ("history-chart-five-hour-pace", ["connection-screen", "dashboard"]),
    "dashboard-connection-details-landscape-dark": ("banked-container", ["connection-screen", "connection-details"]),
    "dashboard-local-history-details-large-font": ("history-page-cursor", ["connection-screen", "connection-history"]),
}
EXPECTED_CURRENT_HOST = {"context": "MATCH", "selection": "MATCH", "periods": "MATCH", "projection": "MATCH"}

# Literal review budgets, independently checked against the enumerated sets.
EXPECTED_DASHBOARD_NATIVE_COUNT = 17
EXPECTED_DASHBOARD_JVM_COUNT = 18
