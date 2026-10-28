"""Synthetic current-history capture controls; no Android application execution."""
import json
import unittest

import test_history_text_contract as text_contract
from verify_history_text_captures import ASSEMBLED_CAPTURES, verify_captures


class CurrentHistoryContracts(unittest.TestCase):
    def setUp(self):
        self.fixture = text_contract.HistoryTextContracts()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.directory

    def test_current_paint_targets_and_exact_period_selection_fail_closed(self):
        for name in (name for name in ASSEMBLED_CAPTURES if name.startswith("history-current-")):
            for key, value in (("currentOnly", False), ("currentReset", "2027-01-15T09:00:01Z"),
                               ("currentDuration", 604800), ("selectedOrdinals", [1, 2, 3, 4, 5]), ("lastAdmitted", 306)):
                self.fixture.captures()
                path = self.directory / (name + ".json")
                data = json.loads(path.read_text())
                data[key] = value
                path.write_text(json.dumps(data))
                with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                    verify_captures(self.directory)
            for role in ("observation", "edge", "dash", "bridge"):
                self.fixture.captures()
                path = self.directory / (name + ".json")
                data = json.loads(path.read_text())
                sample = next(item for item in data["pixelSamples"] if item["kind"] == role)
                sample["targetArgb"] = sample["actualArgb"]
                # Even a mutually agreeing receipt/PNG cannot choose its own registered role target.
                if sample["targetArgb"] != -1:
                    sample["targetArgb"] = -1
                path.write_text(json.dumps(data))
                with self.subTest(name=name, role=role), self.assertRaises(ValueError):
                    verify_captures(self.directory)


if __name__ == "__main__":
    unittest.main()
