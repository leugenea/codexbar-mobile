"""Issue168 source and synthetic report controls; not Android/JVM execution."""
from pathlib import Path
import unittest
import test_history_export_contract
from verify_test_reports import HISTORY_IMPORT_JVM, HISTORY_IMPORT_NATIVE

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_JVM = {
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
EXPECTED_NATIVE = {
    "io.github.leugenea.codexbarmobile.HistoryImportLifecycleTest": {
        "localHistoryImportConfirmsJsonSafReplacesSQLiteAndAnnouncesResult",
    },
}


class HistoryImportContracts(unittest.TestCase):
    def test_exact_import_identities_match_source_and_fail_closed(self):
        test_history_export_contract.assert_exact_reports(self, EXPECTED_JVM, EXPECTED_NATIVE, HISTORY_IMPORT_JVM, HISTORY_IMPORT_NATIVE)

    def test_import_is_root_registered_bounded_and_after_stored_content(self):
        main = ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile"
        action = (main / "HistoryImportAction.kt").read_text()
        root = (main / "DashboardNavigation.kt").read_text()
        self.assertIn("ActivityResultContracts.OpenDocument()", action)
        self.assertIn('launcher.launch(arrayOf("application/json"))', action)
        self.assertIn("withContext(Dispatchers.IO)", action)
        self.assertIn("OpenableColumns.SIZE", action)
        self.assertIn("HistoryImport.MAX_INPUT_BYTES", action)
        self.assertIn("val importer = historyImporter(controller)", root)
        details = root.split("DashboardRoute.LOCAL_HISTORY_DETAILS -> {", 1)[1].split("DashboardRoute.CONNECTION_DETAILS ->", 1)[0]
        self.assertLess(details.index("StoredHistoryContent("), details.index("HistoryImportAction("))
        self.assertNotIn("readUsage", action)
        self.assertNotIn("rememberSaveable", action)
        self.assertIn("## Import", (ROOT / "docs/history-export.md").read_text())
