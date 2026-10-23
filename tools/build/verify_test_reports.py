"""Real mandatory test-result gate. Synthetic unit tests validate this parser, not Android."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    "jvm": {
        "restoredOwnerRemainsDormantWithForegroundObserversUntilExplicitRead",
        "manualAndResumeCoalesceIntoOneInFlightCycle",
        "visiblePollingStartsNoEarlierThanSixtySeconds",
        "thirtySecondOperationDeadlineCancelsTransportAndPreservesSuccess",
        "inventoryHasItsOwnThirtySecondDeadlineAndUsagePublishesBeforeItSettles",
        "retryAfterDefersManualResumeAndPollUntilTheAdvertisedBoundary",
        "usageRetryWaitSurvivesForegroundCancellationAndDefersResumeAndManual",
        "inventoryRetryWaitSurvivesForegroundCancellationAndDefersResumeAndManual",
        "replacingGenerationClearsAnUnfinishedRetryAfterBoundary",
        "deferredManualReadWithoutLifecycleObserversStillRunsAtTheBoundary",
        "deferredManualReadReplacesTheLaterAutomaticPollWakeup",
        "usageAndInventoryBecomeStaleIndependentlyAtFifteenMinutes",
        "mixedEndpointOutcomesPreserveEachLastSuccessfulObservation",
        "replacementRejectsLateOldGenerationPublication",
        "foregroundLossCancelsReadAndResumeStartsOneBoundedCycle",
        "removingOneOfTwoVisibleObserversDoesNotCancelTheirSharedRead",
        "countdownTicksMakeNoRequestsAndPassedResetRetainsPercent",
        "weeklyPolicyFlowsThroughReceiptCacheAndTicksWithoutReanchoringOrLosingStaleFacts",
        "connectCompletedInBackgroundWaitsForTheLiveSurface",
        "cancellationBeforeCycleEntryCannotWedgeResumeOrAccountReplacement",
        "failedQuarantineRemovalClearsRetainedSuccessAndStopsAutomaticWork",
        "automaticPollAndResumeDoNotUnlockTransientTokenRefreshFailures",
        "repeatedUnauthorizedReadRetiresRetainedSuccessAfterRotation",
        "successfulQuarantineSettlementSurvivesForegroundCancellation",
        "failedQuarantineSettlementSurvivesForegroundCancellation",
        "independentTerminalRefreshAfterWaiterCancellationRetiresUsage",
    },
    "native": {
        "launcherReachesRealDisconnectedScreenWithInstalledSecurityPolicy",
        "recreationIgnoresLegacySelectionAndKeepsRealDisconnectedOwner",
        "landscapeKeepsRealIdentityAndConnectionHistoryControlsReachable",
        "defaultRelaunchResumesActivatedOwnerOnceAndDuplicateTriggersCoalesce",
    },
}
CLASS = {
    "jvm": "io.github.leugenea.codexbarmobile.UsageRefreshTest",
    "native": "io.github.leugenea.codexbarmobile.ConnectionLaunchSmokeTest",
}


REMAINING_TIME_JVM = {
    "io.github.leugenea.codexbarmobile.TimePresentationTest": {
        "dayHourMinuteAndSubsecondBoundariesFloorElapsedMinutes",
        "remainingTimeUnitsOmitEveryZeroWithoutChangingOrder",
    },
    "io.github.leugenea.codexbarmobile.TimePresentationResourcesTest": {
        "stateAndSnapshotWordingIsExplicit",
        "absoluteFormatsPreserveLocalDateAndRepeatedHourOffsetWithoutPrecisionSuffix",
        "dayHourAndMinutePluralsRetainZeroSingularAndMultipleUnits",
        "minutePrecisionFormatsSingularPluralAndOmittedUnits",
        "zeroUnitsUseLessThanAMinuteWithinRemainingWrapper",
    },
}


CURRENT_HISTORY_JVM = {
    "io.github.leugenea.codexbarmobile.HistoryTrendTest": {
        "emptyObservationsHaveNoTrend",
        "singleObservationHasNoTrend",
        "twoObservationsFitExactlyWithinTheirTimeRange",
        "identicalTimesWithVerticalSpreadHaveNoTrend",
        "flatObservationsFitAHorizontalLine",
        "risingFitUsesTimeSpacingRatherThanObservationIndex",
        "fallingFitHandlesUnsortedTimesWithoutChangingObservations",
        "verticalSpreadAtRepeatedTimesFitsAllPointsWithoutClampingEndpoints",
    },
    "io.github.leugenea.codexbarmobile.history.CurrentHistoryPeriodTest": {
        "normalizedDurationSelectionUsesExactAbsoluteIdentityAndExistingEvaluationClock",
        "expiryClockLossBeforeStartAmbiguityAndRelativeDiscrepantMalformedResetNeverFallback",
        "periodicToleranceKeepsAbsoluteOriginalAndChangedResetGetsANewQueryKey",
        "staleAndEndpointFailureRemainIndependentOfKnownUnexpiredIdentity",
        "freshHostAuthorityRejectsHeldPeriodOnExpiryClockLossResetChangeAndAccountRetirement",
    },
    "io.github.leugenea.codexbarmobile.history.CurrentHistoryReadTest": {
        "currentMeasurementsBehindArchiveFilledPagesUseRealSelectedLookaheadAndBoundedReads",
        "selectedOrdinalHolesCorrectionsAndResetBoundariesCannotCreateContiguousMeasuredEdges",
        "emptySelectionRetentionReadFailuresCorruptionAndRevocationRemainExplicit",
        "keyedUnknownPercentStatusIsRetainedWithoutInventedMeasurementOrReference",
        "jointCurrentScanFindsBothWindowsBehindArchivesWithIndependentLookahead",
        "jointCurrentScanMissingKeysStatusRetentionAndFailuresStayTyped",
        "jointCurrentScanHasOneHighWaterCeilingAndRejectsRevocation",
        "wholeCurrentWindowRetainsEveryObservationBeyond32AcrossBatchesWithoutChangingDetailPages",
    },
}
CURRENT_HISTORY_NATIVE = {
    "io.github.leugenea.codexbarmobile.CurrentWindowHistoryTest": {
        "currentPeriodBehindArchivedPagesHasExactMeasuredReferencePaintInProductionLight",
        "currentPeriodBehindArchivedPagesHasExactMeasuredReferencePaintInProductionDark",
        "expiryChangedResetUnknownAndAmbiguousRetireCurrentChartsWithoutArchivedFallback",
        "dormantRestoreHasTextHistoryButNoCurrentChartAndOpeningAddsNoRequests",
    },
    "io.github.leugenea.codexbarmobile.CurrentWindowRetirementTest": {
        "lateCurrentQueryCannotReplayRetiredAccountIntoProductionSuccessorChart",
        "lateSameAccountQueryCannotReplayOldResetAfterCurrentKeyRollover",
        "currentRetainedGraphKeepsStaleErrorLossAndRealRetentionExplicit",
        "knownCurrentPeriodWithLostOnlyAdmissionHasEmptyHistoryThenOneActualMarker",
    },
}
CURRENT_HISTORY_NATIVE_COUNT = sum(map(len, CURRENT_HISTORY_NATIVE.values()))


CREDENTIAL_CLASS = "io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStoreTest"
CREDENTIAL_CASES = {
    "realKeystoreRoundTripFreshInstanceAndRandomizedIv",
    "missingKeystoreKeyFailsClosedWithoutRecreationOverCiphertext",
    "realKeystoreRejectsCiphertextIvMetadataAndCrossSessionSubstitution",
    "injectedAtomicFileFailurePreservesCommittedCredentials",
    "localLogoutDeletesOwnedKeystoreKeyAndCiphertextAndInvalidatesOwner",
    "ciphertextUsesNoBackupDirectoryAndBothBackupFormatsExcludeAllAppData",
}


CONNECTION_CLASS = "io.github.leugenea.codexbarmobile.ConnectionLifecycleTest"
CONNECTION_CASES = {
    "activityLaunchesOnlyFixedSystemBrowserIntentAndCancelClearsOwnedCode",
    "recreationAndBackgroundKeepOneBoundedOwnerAndFinishCancelsPolling",
    "fakeExchangeUsesRealKeystoreTwoReadsSeparateClocksAndLocalSignOut",
    "freshOwnerRestoresOnlyUnresolvedCredentialsWithoutRequestsAndBrowserFailureIsSafe",
    "failedReadRetainsUnresolvedSessionAndSignOutCancelsInFlightTransport",
    "restoredSessionRotationReauthAndLogoutUseRealKeystore",
    "nativeLogoutDuringRefreshAndReplacementRejectLateRotatedCredentials",
    "nativeKeyLossCorruptionAndInterruptedRotationRestoreFailClosedToReauth",
    "nativeFailedRotationWriteQuarantinesRealStoreBeforeReauth",
    "nativeTwoLiveOwnersLogoutWhileUsageIsHeldRejectsOldGeneration",
    "nativeTwoLiveOwnersReplacementWhileUsageIsHeldRejectsOldGeneration",
}


USAGE_REFRESH_CLASS = "io.github.leugenea.codexbarmobile.UsageRefreshLifecycleTest"
USAGE_REFRESH_CASES = {
    "homeCancelsEligibleReadAndReturningTaskRefreshesOnce",
    "activityRecreationKeepsOneOwnerAndCoalescesRefresh",
}


LIVE_USAGE_CLASS = "io.github.leugenea.codexbarmobile.LiveUsageScreenTest"
LIVE_USAGE_CASES = {
    "productionLiveLightPaintAndExactUsageResetCapture",
    "productionLiveDarkPaintAndLargeFontRemainReadable",
    "fractionalWeeklyOnlyAndMissingResetHaveExactAccessibleSemantics",
    "manualRefreshRetainsValuesAndAnnouncesRefreshingThenError",
    "staleAndPassedResetReevaluateLocallyWithoutZeroOrNetwork",
    "exhaustedBarDoesNotOverrideAllowedAndMalformedSiblingsRemainVisible",
    "knownResetShowsSimultaneousLabelsAndRepeatedHourOffset",
    "landscapeLongCopyHasNoNativeTextOverflow",
    "recreationKeepsRealScreenAndOwnerAndStoppedActivityAdmitsNoReads",
    "disconnectedAndReauthorizationHaveAccessibleStatusAndNoInventedUsage",
}


BANKED_RESET_CLASS = LIVE_USAGE_CLASS
BANKED_RESET_CASES = {
    "bankedAvailableHasSimultaneousExpiryAndSingleViewOnlyAnnouncements",
    "bankedEmptyUnknownUnsupportedAndMalformedRemainExplicit",
    "bankedExpiredAndDiscrepantRetainBothCountsAndExpiry",
    "bankedMissingExpiryAndMalformedRowsKeepKnownSiblings",
    "bankedInventoryFailureAndUsageFailureKeepIndependentClocks",
    "bankedStaleAndRefreshCycleAreIndependentFromUsage",
    "bankedLogoutDropsItemsBeforeLateInventoryCanReturn",
    "bankedReplacementRejectsLateInventoryWhileNewAccountConnects",
    "bankedLandscapeLongCopyAndRecreationRemainReadable",
}


HISTORY_CLASS = "io.github.leugenea.codexbarmobile.history.HistoryPersistenceTest"
HISTORY_CASES = {
    "actualSQLiteReopenKeepsExactDecimalsMetadataCursorAndEqualPercentIds",
    "partitionCapabilitiesRejectCrossPartitionAndRetiredGenerations",
    "rollbackAndUncertainCommitHaveAtomicHighWaterAndRetry",
    "trustedAgeEvictionPersistsOrdinalCutoffWhileClockAnomaliesSuspendAge",
    "globalObservationCapRetainsHighWaterSegmentOriginAndPagination",
    "unknownTimeStatusesSurviveAgeWhileNewEpochSuspensionSurvivesReopen",
    "missingTimeClockAnomaliesPersistSuspensionAcrossReopen",
    "tighterReopenCountMaintenanceIsTransactionalAndPreservesAdmissionHighWater",
    "physicalDirectoryBudgetIncludesRollbackJournalControlAndDefaultCeilings",
    "fullSQLiteAndInterruptedMaintenanceReturnTypedFailureWithoutConsumingId",
    "interruptedByteMaintenanceRollsBackCutoffAndRetriesWithinBudget",
    "timestampColumnCorruptionCannotExposeOrAgeDeleteRecentMeasurements",
    "failedDirectoryValidationNeverBecomesSuccessfulAdoptionOnRetry",
    "orphanDatabaseWithEmptyBindingNeverTriggersImplicitDestructiveRecovery",
    "corruptRowsAndSchemaFailClosedAndExplicitRecoveryKeepsPrivacyFence",
    "deletionTombstoneSurvivesInterruptedCleanupAndFreshReopen",
    "heldWriteAdmissionAndDeletionCannotResurrectRemovedLifetime",
    "interruptedBindingAndMissingBindingNeverCreateOrAdoptHistory",
    "closeAndReadWriteFailuresAreCategoricalAndDoNotFabricateEmpty",
    "historyArtifactsUseNoBackupDirectoryWithMemoryTemporariesAndUnchangedBackupRules",
}


HISTORY_LIFETIME_CLASS = "io.github.leugenea.codexbarmobile.history.HistoryLifetimeTest"
HISTORY_LIFETIME_CASES = {
    "nativeActivityFinishAndRecreationKeepProcessHistoryOwner",
    "nativeCancelAndHolderShutdownRetireButRestoreContinuingLifetime",
    "nativeCrashCutsStageCredentialSaveAndActivationNeverReusePreviousLogin",
    "nativeCredentialFileRemovalFailureStillDeletesKeyAndHistoryWithoutFalseSuccess",
    "nativeFailedHistoryDeletionStillRemovesKeystoreAndQuarantinesFreshHolder",
    "nativeForegroundLossRetainsLifetimeWithoutPretendingLogout",
    "nativeFreshLoginRotationReopenAndReloginNeverJoin",
    "nativeHeldAppendAndReadLoseAuthorityImmediatelyOnOwnerRetirement",
    "nativeHeldCredentialSaveCannotActivateHistoryOrResurrectAfterLogout",
    "nativeHeldHistoryDeletionTimeoutBlocksSuccessorAndOldTicketCannotEraseNewPartition",
    "nativeInterruptedOldTombstonePurgesEvenCleanLookingCredentials",
    "nativeKeyLossCorruptionAndInterruptedRotationPurgeLifetimeHistory",
    "nativeTerminalRefreshWaitsForBothCleanupAfterForegroundLoss",
}


HISTORY_SAMPLING_CLASS = "io.github.leugenea.codexbarmobile.history.UsageHistoryIntegrationTest"
HISTORY_SAMPLING_CASES = {
    "admittedEndpointPersistsThroughFreshRuntimeDormancyAndLogoutRejectsLateLoginData",
    "partialEndpointsPreserveActualUsageAndIndependentInventoryClocksWithoutReplay",
    "actualHeldWriteAdmissionAndCommittedWriteAreRevokedBeforeCombinedLogoutCompletion",
    "nativeHistoryReadFailureIsCategoricalAndNeverReplacesLiveUsage",
    "composeForegroundLossRecreationAndFinishRetireCurrentlyUsableHistoryPorts",
}


HISTORY_TEXT_CLASS = "io.github.leugenea.codexbarmobile.HistoryTextComponentTest"
HISTORY_TEXT_CASES = {
    "readinessContentErrorsLossAndTruncationRemainIndependent",
    "collapsedDetailsReachEveryGapAndResetBreakWithoutOmission",
    "unknownBaselineKeepsMeasuredValueAndB1ResetContext",
    "longExactDecimalIsChunkedWithOneAccessibleOwnerPerPart",
    "portraitLightMeasuredScreenshotHasReachableUnclippedLabels",
    "landscapeDarkGappedScreenshotHasReachableControls",
    "largeFontUnknownScreenshotKeepsCompleteNativeTextAndControls",
}


HISTORY_CHART_CLASS = "io.github.leugenea.codexbarmobile.HistoryChartComponentTest"
HISTORY_CHART_CASES = {
    "singleMeasuredTickAndDashedNominalAreSeparateActualPixels",
    "connectedMeasurementsDrawStraightEdgesAndPercentAxisInDarkPortrait",
    "gapsCorrectionsAndResetChangesHaveNoPaintedBridgeInLandscapeDark",
    "emptyStatusOnlyLoadingAndErrorsNeverInventACanvasOrZero",
    "typedUnavailableGeometryAndUnderflowKeepExactFactsInsteadOfClamping",
    "unknownBaselineLargeFontKeepsSinglePointCompleteLabelsAndReachableDetails",
    "lineEndpointsAreInsetWithoutPointMarkersInLandscapeLight",
}


HISTORY_AUTHORITY_CLASS = "io.github.leugenea.codexbarmobile.HistoryDisplayAuthorityTest"
HISTORY_AUTHORITY_CASES = {
    "heldBoundCollectionCannotReplayRetiredFactsOnActualHostReexecution",
    "heldBoundCollectionRejectsReplacementThenShowsOnlySuccessorFacts",
    "heldNullDiagnosticsKeepCurrentLossButNeverReplayAfterRetireSuccessorRetire",
    "heldPublishedWindowAndPageCollectionHidesSupersededFactsOnActualHostReexecution",
}

HISTORY_NAVIGATION_CLASS = "io.github.leugenea.codexbarmobile.HistoryNavigationTest"
PHONE_TIME_CASES = {
    "productionLiveAndBankedClocksFollowSystemZoneResourceLocaleAndRecreationWithoutReads",
    "productionHistoryClocksFollowSystemZoneResourceLocaleAndRecreationWithoutReads",
}
HISTORY_NAVIGATION_CASES = PHONE_TIME_CASES | {
    "productionEntrySelectsWindowsAndNavigatesBoundedOrdinalPagesWithNativeCaptures",
    "defaultRestoredScreenAndHistoryNavigationNeverActivateTransportOrAddAdmissions",
    "logoutClearsVisibleHistoryAndReloginNeverDisplaysPreviousPartition",
    "recreationAndForegroundLossUseTheSameDormantOwnerButFreshHistoryAuthority",
    "integratedHistoryRetainsIndependentEndpointFailuresAndNoNewSamplesFromLocalNavigation",
}


HISTORY_ASSEMBLED_CLASS = "io.github.leugenea.codexbarmobile.HistoryAssembledAcceptanceTest"
HISTORY_ASSEMBLED_CASES = {
    "freshInstallAndSingleObservationExposeNoInventedTrendAndObservedOnlySignedDeltas",
    "sparseGapCorrectionAndChangedResetRemainSeparatedInActualLandscapeDarkPixelsAndDetails",
    "unknownResetAndUnknownPercentAtActualLargeFontKeepCompleteAccessibleFactsAndReachableControls",
    "truncatedPageLandscapeLightKeepsHonestLocalCursorAndEveryBoundedDetailReachable",
}


HISTORY_ISOLATION_CLASS = "io.github.leugenea.codexbarmobile.HistoryIsolationTest"
HISTORY_ISOLATION_CASES = {
    "heldWindowQueriesRejectSupersessionBeforeAndAfterAuthoritativePublication",
    "heldPageQueriesRejectSupersessionBeforeAndAfterAuthoritativePublication",
    "heldUsageAndInventoryResponsesCannotResurrectHistoryAcrossLogoutAndRelogin",
    "heldLocalPagesLoseLastObserverAuthorityBeforeFreshResumeAndRecreationAdoption",
    "integratedStaleUsageIndependentInventoryLossAndRetentionNeverBecomeEmptyOrCurrent",
}


ACCOUNT_NAME_CLASS = "io.github.leugenea.codexbarmobile.AccountNameLifecycleTest"
ACCOUNT_NAME_CASES = {
    "accessibleEditorSetsEditsCancelsClearsAndBoundsUnicodeWithoutChangingProviderTraffic",
    "committedNameSurvivesRecreationAndFreshOwnerStorageRestorationWithoutRequests",
    "logoutAndReplacementDeleteTheNameAndRejectCapturedPredecessorEdits",
    "unreadableNameIsNotAnIdentityOrCredentialFailureAndKeyLossPurgesIt",
}


ACCOUNT_REMOVAL_CLASS = "io.github.leugenea.codexbarmobile.AccountRemovalLifecycleTest"
ACCOUNT_REMOVAL_CASES = {
    "restoredAccountConfirmationExplainsLocalScopeAndCancelDismissAndRecreationChangeNothing",
    "confirmedRemovalDeletesAllLocalArtifactsAndHeldRepliesAndOldConfirmCannotTouchSuccessor",
    "replacementAndCancelDismissOpenDialogAndCapturedConfirmNeverReacquiresNewAccount",
    "samePhaseReplacementDismissesOpenDialogWithoutIntermediatePhaseDelivery",
    "nameArtifactDeletionFailureIsStorageFailureAndOnlyFreshConfirmedRetryReportsRemoval",
}


DEVICE_CODE_COPY_CLASS = CONNECTION_CLASS
DEVICE_CODE_COPY_CASES = {
    "explicitDeviceCodeCopyWritesExactSensitiveClipAndAnnouncesSuccess",
    "capturedDeviceCodeCopyRejectsCancelledCompletedAndSupersededAttempts",
    "capturedDeviceCodeCopyRejectsMissingFailedAndExpiredAttempts",
}



DASHBOARD_JVM = {
    'io.github.leugenea.codexbarmobile.HistoryPlotCacheTest': {
        'metadataTicksReuseWholeWindowGraphAndDrawingInputsWhileKeepingLiveMetadataAndPaceCurrent',
        'changedPageQueryGenerationPartitionRequestAndSelectionRebuildWholeWindowInputs',
        'cachedCurrentInputsNeverBypassPeriodOrDisplayRevocation',
    },
    'io.github.leugenea.codexbarmobile.DashboardPresentationTest': {
        'compactChartEquivalentUsesOriginalExactFactsAndIndependentReferences',
        'compactMissingChartAndPhaseActionsKeepTypedErrorsAndSinglePointTruth',
        'reportedInventoryCountNeverFallsBackToSummaryRowsOrZero',
        'bankedRowDetailsKeepMinutePrecisionAndIndependentStatesWithoutDuplicateAnnouncements',
        'paceLineUsesOverReserveAndOnPaceWithoutChangingExactComparison',
        'paceLineHalfTenthBoundariesRoundAwayFromZero',
        'paceLineNearZeroAndAdjacentBoundaryValuesNeverShowSignedZero',
        'paceLineKeepsOneDecimalAndLocaleSeparator',
        'compactPaceBeyondFirst32ObservationsMatchesAcceptedHeadlineAndClock',
        'compactWholeWindowLastPointEndLabelAndTrendReachLatestObservationBeyond32',
        'compactPaceKeepsIndependentlyAgingFiveHourAndWeeklyObservationsSeparate',
        'compactPaceNeverFallsBackWhenAcceptedComparisonIsMissingOrIneligible',
        'chartTimeLabelsUseYearlessLocalePatternsAndContainingHour',
        'chartTimeLabelsRetainFullAndPartialRepeatedHourOffsets',
        'chartTimeLabelsKeepUnrepresentableInstantsUnknown',
    },
    'io.github.leugenea.codexbarmobile.history.HistoryDisplayAuthorityTest': {
        'currentNullDiagnosticSelectionCannotReplaySuccessorOrRetiredLoss',
        'heldJointFactsRejectEqualRequestAfterRouteRoundTrip',
        'heldJointFactsRejectResetExpiryClockLossAndRetiredContexts',
    },
    'io.github.leugenea.codexbarmobile.history.UsageHistoryRecorderTest': {
        'heldJointRequestRejectsRouteQueryAndAccountSuccessors',
        'jointRequestPreservesQueueLossGapAndContiguousDurableOrdinals',
        'jointRequestUsesOneWorkerAndCoalescesLatestSelectionWithoutAdmissions',
        'admittedPreappendFailureUnderChangedSelectionRetainsActualLossAndNextGap',
        'admittedAppendFailureUnderChangedSelectionRetainsActualLossWithoutPresentationLoss',
    },
}
DASHBOARD_NATIVE = {
    'io.github.leugenea.codexbarmobile.DashboardCopyAndTimeTest': {
        'dashboardAndDisclosureFollowPhoneZoneLocaleAndTicksWithoutRequests',
        'minutePrecisionMatchesDashboardWindowDetailsAndBankedExpiry',
        'simplifiedWindowsKeepOneTopObservationYearlessAxisAndReadablePace',
        'noReachableTextOrSemanticsUsesRemovedTimePhraseWhileOriginalInstantsRemainExact',
    },
    'io.github.leugenea.codexbarmobile.DashboardHistoryIsolationTest': {
        'heldPairCollectionCannotReplayEqualQueryAfterDetailsRoundTrip',
        'heldPairCollectionCannotReplayPreviousPeriodAfterResetExpiryOrClockLoss',
        'heldPairReadCannotPublishAcrossRouteSwitchAccountReplacementAndForegroundRetirement',
        'pairReadsRemainBoundedBehindArchivesAndCoalesceWithoutExtraAdmissions',
    },
    'io.github.leugenea.codexbarmobile.DashboardUiTest': {
        'bankedTableUsesReportedCountAndRetainsEveryExpiryAndUnknownRow',
        'dashboardActionsFollowConnectionPhaseAndDormantNavigationDoesNotRead',
        'dashboardDarkLandscapeAndLargeFontKeepAccessibleTargetsAndCompleteText',
        'dashboardMissingEmptySingleAndFailedHistoryHaveOneHonestLinePerWindow',
        'dashboardShowsBothCurrentWindowsWithoutHistoryNavigationAndRetainsMeasuredReferencePixels',
        'drawerHasExactTwoLabelsBackPrecedenceAndDetailsReturnToDashboard',
        'emptyDashboardOpensAccountSettingsWithoutInlineManagementOrProviderRequests',
        'dashboardKnownPercentPreservesMalformedResetAndIndependentCurrentSiblingWarning',
        'fiveHourFractionalResetPairPopulatesDashboardButBeyondToleranceRemainsUnavailable',
    },
}
DASHBOARD_NATIVE_COUNT = sum(map(len, DASHBOARD_NATIVE.values()))
DASHBOARD_JVM_COUNT = sum(map(len, DASHBOARD_JVM.values()))

HISTORY_EXPORT_JVM = {
    "io.github.leugenea.codexbarmobile.history.HistoryExportTest": {
        "emptyHistoryRoundTripsWithZeroHighWaterAndNoInventedEntries",
        "statusOnlyRoundTripPreservesFieldKnowledgeSlotsAndIndependentFlags",
        "measuredRoundTripKeepsExactDecimalScaleFractionalResetAndClock",
        "gapsAndMultipleSegmentsRoundTripWithEvictedOriginsAndContinuationTail",
        "checkedInV1FixtureDecodesToExpectedEntriesAndContinuation",
        "unknownFieldsAtEveryObjectLevelAreIgnoredAndDestinationPartitionIsExplicit",
        "newerVersionAndMalformedKnownFieldsAreRejectedWithoutStorageWrites",
        "explicitOutputAllowlistNeverIncludesSecretsAccountPlanOrLocalPartition",
        "integerMembersRejectMalformedExponentAndNonIntegerTokens",
        "integerMembersRejectExponentNotationIncludingPrecisionLoss",
        "integerMembersRejectQuotedIntegers",
        "integerMembersPreserveExactLongsAndRejectOverflow",
        "enumMembersRequireQuotedStringsIncludingNullableFieldsAndBreakArrays",
    },
    "io.github.leugenea.codexbarmobile.HistoryExportRequestTest": {
        "startCancellationBeforeFirstDispatchReleasesBusyAndAllowsRetry",
        "resultCancellationBeforeFirstDispatchClearsConsumedSnapshotAndAllowsRetry",
        "pendingPickerSurvivesScopeCancellationAndDuplicateStartUntilNewScopeResult",
        "alreadyCancelledScopeCannotPrepareOrStrandConsumedResult",
        "preparationAndPickerFailuresReleaseBusyWithoutRetainingSnapshot",
    },
}
HISTORY_EXPORT_NATIVE = {
    "io.github.leugenea.codexbarmobile.HistoryExportLifecycleTest": {
        "localHistoryExportLaunchesJsonSafAndWritesCoherentPagedHistoryOffMain",
    },
}
HISTORY_EXPORT_NATIVE_COUNT = sum(map(len, HISTORY_EXPORT_NATIVE.values()))

HISTORY_IMPORT_JVM = {
    "io.github.leugenea.codexbarmobile.history.HistoryImportControllerTest": {
        "cancelledActivityWaiterKeepsProcessImportBusyResultAndRefreshedPresentation",
    },
    "io.github.leugenea.codexbarmobile.history.HistoryImportTest": {
        "checkedInV1FixtureAndUnknownFieldsImportWithRetainedOrigins",
        "exportImportRestoresExactRowsContinuationAndNextSegmentWithoutNewBreak",
        "importAppliesAgeCountAndByteRetentionWithoutChangingHighWaterOrCursor",
        "inconsistentCursorSegmentAndPendingBreakAreRejectedWithoutReplacement",
        "interruptedReplacementRollsBackRowsHighWaterCursorAndRetentionTogether",
        "malformedNewerAndOrdinalInvariantFilesLeaveExistingHistoryUntouched",
        "oversizedAndMalformedUtf8StreamsAreRejectedBeforeDecodeOrMutation",
        "retainedUnknownTimeHolesAndUnkeyedPointsDoNotInvalidateRealExports",
    },
    "io.github.leugenea.codexbarmobile.history.HistoryImportRecorderTest": {
        "importWaitsForPreImportNPlusOneThenQueuedRecordingUsesImportedHighWater",
        "revokedCapabilityRejectsImportWithoutChangingRowsOrContinuation",
    },
    "io.github.leugenea.codexbarmobile.HistoryImportRequestTest": {
        "importAdmissionCancelledBeforeDispatchCannotOpenPickerOrStrandBusy",
        "importPickerKeepsOriginalLifetimeAcrossRecreationAndDropsCancelledRead",
    },
}
HISTORY_IMPORT_NATIVE = {
    "io.github.leugenea.codexbarmobile.HistoryImportLifecycleTest": {
        "localHistoryImportConfirmsJsonSafReplacesSQLiteAndAnnouncesResult",
    },
}
HISTORY_IMPORT_NATIVE_COUNT = sum(map(len, HISTORY_IMPORT_NATIVE.values()))

def verify_reports(directory: Path, kind: str) -> dict:
    reports = sorted(directory.rglob("*.xml"))
    if not reports:
        raise ValueError(f"No {kind} JUnit XML under {directory}")
    cases = []
    for path in reports:
        root = ET.parse(path).getroot()
        if root.tag not in ("testsuite", "testsuites"):
            continue
        # Container/suite infrastructure errors must fail even if no testcase owns them.
        for suite in root.iter():
            if suite.tag not in ("testsuites", "testsuite"):
                continue
            for attr in ("failures", "errors", "skipped"):
                if int(suite.get(attr, "0")) != 0:
                    raise ValueError(f"{path}: nonzero {attr}")
        for case in root.iter("testcase"):
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise ValueError(f"{path}: failed/errored/skipped {case.get('name')}")
            cases.append({"class": case.get("classname"), "name": case.get("name"), "report": str(path)})
    actual = {case["name"] for case in cases if case["class"] == CLASS[kind]}
    missing = EXPECTED[kind] - actual
    if missing:
        raise ValueError(f"Missing real {kind} launch/refresh tests: {sorted(missing)}")
    current_history = CURRENT_HISTORY_JVM if kind == "jvm" else CURRENT_HISTORY_NATIVE
    dashboard = DASHBOARD_JVM if kind == "jvm" else DASHBOARD_NATIVE
    export = HISTORY_EXPORT_JVM if kind == "jvm" else HISTORY_EXPORT_NATIVE
    imported = HISTORY_IMPORT_JVM if kind == "jvm" else HISTORY_IMPORT_NATIVE
    remaining_time = REMAINING_TIME_JVM if kind == "jvm" else {}
    for classname, required in (current_history | dashboard | export | imported | remaining_time).items():
        for name in required:
            matches = [case for case in cases if case["class"] == classname and case["name"] == name]
            if len(matches) != 1:
                raise ValueError(f"Expected exactly one real {kind} current history case: {classname}.{name}")
    if kind == "native":
        for label, classname, required in (
            ("credential", CREDENTIAL_CLASS, CREDENTIAL_CASES),
            ("connection", CONNECTION_CLASS, CONNECTION_CASES),
            ("usage refresh", USAGE_REFRESH_CLASS, USAGE_REFRESH_CASES),
            ("live usage", LIVE_USAGE_CLASS, LIVE_USAGE_CASES),
            ("banked reset", BANKED_RESET_CLASS, BANKED_RESET_CASES),
            ("history", HISTORY_CLASS, HISTORY_CASES),
            ("history lifetime", HISTORY_LIFETIME_CLASS, HISTORY_LIFETIME_CASES),
            ("history sampling", HISTORY_SAMPLING_CLASS, HISTORY_SAMPLING_CASES),
            ("history text", HISTORY_TEXT_CLASS, HISTORY_TEXT_CASES),
            ("history chart", HISTORY_CHART_CLASS, HISTORY_CHART_CASES),
            ("history navigation", HISTORY_NAVIGATION_CLASS, HISTORY_NAVIGATION_CASES),
            ("history display authority", HISTORY_AUTHORITY_CLASS, HISTORY_AUTHORITY_CASES),
            ("history isolation", HISTORY_ISOLATION_CLASS, HISTORY_ISOLATION_CASES),
            ("history assembled acceptance", HISTORY_ASSEMBLED_CLASS, HISTORY_ASSEMBLED_CASES),
            ("local account name", ACCOUNT_NAME_CLASS, ACCOUNT_NAME_CASES),
            ("local account removal", ACCOUNT_REMOVAL_CLASS, ACCOUNT_REMOVAL_CASES),
            ("device code copy", DEVICE_CODE_COPY_CLASS, DEVICE_CODE_COPY_CASES),
        ):
            actual_names = {case["name"] for case in cases if case["class"] == classname}
            missing_names = required - actual_names
            if missing_names:
                raise ValueError(f"Missing real native {label} tests: {sorted(missing_names)}")
    if kind == "native":
        for name in PHONE_TIME_CASES:
            matches = [case for case in cases if case["class"] == HISTORY_NAVIGATION_CLASS and case["name"] == name]
            if len(matches) != 1:
                raise ValueError(f"Expected exactly one real native phone-time case: {name}")
    if not cases:
        raise ValueError(f"No executed {kind} tests")
    return {"kind": kind, "testCount": len(cases), "reports": [str(p) for p in reports], "testcases": cases}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=EXPECTED)
    args = parser.parse_args()
    directory = ROOT / ("app/build/test-results/testDebugUnitTest" if args.kind == "jvm"
                        else "app/build/outputs/androidTest-results/connected")
    receipt = verify_reports(directory, args.kind)
    destination = ROOT / "evidence" / ("native" if args.kind == "native" else "jvm")
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "executed-tests.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"{args.kind}: {receipt['testCount']} executed tests, all required cases present; no failures/errors/skips")


if __name__ == "__main__":
    main()
