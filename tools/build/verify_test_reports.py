"""Real mandatory test-result gate. Synthetic unit tests validate this parser, not Android."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    "jvm": {
        "newLaunchHasNoUsageAndIsDisconnected",
        "everySelectionIsExplicitAndOnlyDemoExposesSyntheticUsage",
        "retryMovesOnlyAnErrorToFrozenLoadingWithoutExposingUsage",
        "fixtureActionIsAdmittedOnlyFromDisconnectedOrLoading",
        "resetAlwaysRemovesTheSampleAndReturnsToDisconnected",
        "savedSelectionRoundTripsWithoutIntroducingAnAuthenticatedState",
        "absentCorruptOrFutureSavedSelectionFailsClosed",
        "syntheticUsageRejectsInvalidPercentagesInsteadOfClampingThem",
    },
    "native": {
        "launcherIsOfflineAndAllFourPreviewsRemainHonestThroughActions",
        "everyPreviewSurvivesActivityRecreationWithoutAnAccount",
        "landscapeKeepsDisclaimerVisibleAndSampleActionsReachable",
    },
}
CLASS = {
    "jvm": "io.github.leugenea.codexbarmobile.OfflineShellStateTest",
    "native": "io.github.leugenea.codexbarmobile.OfflineShellSmokeTest",
}


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
    "fractionalWeeklyOnlyAndMissingResetHaveExactAccessibleSemantics",
    "manualRefreshRetainsValuesAndAnnouncesRefreshingThenError",
    "staleAndPassedResetReevaluateLocallyWithoutZeroOrNetwork",
    "exhaustedBarDoesNotOverrideAllowedAndMalformedSiblingsRemainVisible",
    "knownResetShowsSimultaneousLabelsAndRepeatedHourOffset",
    "landscapeLongCopyHasNoNativeTextOverflow",
    "recreationRestoresLiveTabAndOwnerAndOfflinePreviewStaysSeparate",
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
        raise ValueError(f"Missing real {kind} offline-shell tests: {sorted(missing)}")
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
        ):
            actual_names = {case["name"] for case in cases if case["class"] == classname}
            missing_names = required - actual_names
            if missing_names:
                raise ValueError(f"Missing real native {label} tests: {sorted(missing_names)}")
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
