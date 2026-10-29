"""Chart capture controls; synthetic metadata is not native visual evidence."""
import json
import unittest

import test_history_text_contract as text_contract
from verify_history_text_captures import capture_receipt

# Independent selected-family oracle for mandatory native report execution.
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
    def test_chart_requires_native_paint_trend_and_contained_canvas(self):
        fixture = text_contract.HistoryTextContracts()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.captures()
        path = fixture.directory / "history-chart-measured-portrait-dark.png"
        sidecar = path.with_suffix(".json")
        original = json.loads(sidecar.read_text())
        for key, value in (("pixelOraclePassed", False), ("trendPixelOraclePassed", False),
                           ("observationCount", 99), ("canvasRight", 4)):
            sidecar.write_text(json.dumps(dict(original, **{key: value})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_receipt(path)
