"""Independent issue-195 mandatory identity/count oracle; not imported by the verifier."""
PERIODIC_JVM = {'io.github.leugenea.codexbarmobile.history.PeriodicRefreshAdmissionTest': {
    'dormantRestoreAndForegroundObservationNeverEnrollUntilExplicitActivation',
    'successfulInteractiveLoginEnrollsOnlyItsNewActiveLifetime',
    'periodicRecentSuccessSkipsBothEndpointsAndExactFifteenMinuteBoundaryRuns',
    'failedOrPartialEndpointDoesNotBecomeARecentSuccessfulCycle',
    'periodicManualOverlapInBothOrdersStillJoinsActualFlightOnce',
    'periodicRateLimitAndErrorsRemainBoundedWithoutHiddenPeriodicRetries',
    'staleLifetimeIsRejectedBeforeRecentSuccessAndTerminalAuthCancelsEnrollment',
}}
PERIODIC_NATIVE = {'io.github.leugenea.codexbarmobile.PeriodicUsageRefreshTest': {
    'explicitActivationKeepsOneLifetimeScopedOrdinaryFifteenMinuteRequest',
    'minimizedAppRunsThroughRealSystemJobServiceAndDurableHistorySurvivesReopen',
    'testDriverRequiresNetworkBatteryAndInitialPeriodWithoutClaimingOsTiming',
    'workerRecentSuccessAndMalformedInputReturnWithoutAnyAdditionalRequests',
    'coldWorkerRestoresOnlyMatchingEnrolledLifetimeAndCannotReadSuccessor',
    'workerCancellationReleasesOnlyItsInterestWithAndWithoutUiConsumer',
    'removalAndTerminalReauthorizationCancelEnrolledWorkWithoutSuccessorReads',
    'installedSchedulerComponentsKeepCallerProtectionsAndDefaultProcessWithoutForegroundService',
    'expectedOfflineErrorsAndRetryAfterFinishOpportunityWithoutWorkManagerRetryLoop',
}}
PERIODIC_NATIVE_COUNT = 9
PERIODIC_JVM_COUNT = 7
