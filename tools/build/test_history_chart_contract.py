"""Chart capture parser controls; synthetic metadata is NOT native visual evidence."""
import json
import unittest

import test_history_text_contract as text_contract
from verify_history_text_captures import CAPTURES, INTEGRATED_CAPTURES, CHART_CAPTURES, CHART_COUNTS, verify_captures
from verify_history_text_captures import ASSEMBLED_CAPTURES


# Independent selected-family oracle, not names derived from generated XML.
RETAINED_CHART_CASES = {
    "singleMeasuredTickAndDashedNominalAreSeparateActualPixels",
    "connectedMeasurementsDrawStraightEdgesAndPercentAxisInDarkPortrait",
    "gapsCorrectionsAndResetChangesHaveContinuousPaintInLandscapeDark",
    "emptyStatusOnlyLoadingAndErrorsNeverInventACanvasOrZero",
    "typedUnavailableGeometryAndUnderflowKeepExactFactsInsteadOfClamping",
    "unknownBaselineLargeFontKeepsSinglePointCompleteLabelsAndReachableDetails",
    "lineEndpointsAreInsetWithoutPointMarkersInLandscapeLight",
}


class HistoryChartContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_collector_preserves_four_text_images_and_requires_six_actual_chart_images(self):
        self.assertEqual(set(CAPTURES) - set(CHART_CAPTURES) - set(INTEGRATED_CAPTURES) - set(ASSEMBLED_CAPTURES), {
            "history-measured-portrait-light", "history-exact-measurement-portrait-light",
            "history-gap-landscape-dark", "history-unknown-large-font"})
        self.assertEqual(set(CHART_COUNTS), set(CHART_CAPTURES))
        for name in CHART_CAPTURES:
            self.fixture.captures()
            self.assertEqual(len(verify_captures(self.directory)), 10 + len(INTEGRATED_CAPTURES) + len(ASSEMBLED_CAPTURES))
            (self.directory / (name + ".png")).unlink()
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_captures(self.directory)

    def test_chart_capture_rejects_false_pixels_counts_nonfinite_or_outside_bounds(self):
        mutations = (("pixelOraclePassed", False), ("pixelOraclePassed", 1),
                     ("observationCount", 99), ("observationCount", True), ("connectionCount", None),
                     ("referenceCount", -1), ("trendCount", -1), ("trendCount", True),
                     ("trendPixelOraclePassed", None), ("canvasLeft", -0.1), ("canvasTop", -0.1),
                     ("canvasRight", 4), ("canvasBottom", 4), ("canvasTop", float("nan")),
                     ("canvasLeft", "0"), ("canvasBottom", None), ("canvasRight", 0.1))
        for name in CHART_CAPTURES:
            for key, value in mutations:
                self.fixture.captures()
                path = self.directory / (name + ".json")
                data = json.loads(path.read_text())
                data[key] = value
                path.write_text(json.dumps(data))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    verify_captures(self.directory)


if __name__ == "__main__":
    unittest.main()
