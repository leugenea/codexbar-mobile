"""Current light/dark saved paint cannot choose its own role colors."""
import json
import unittest

import test_history_text_contract as text_contract
from verify_history_text_captures import capture_receipt


class CurrentHistoryContracts(unittest.TestCase):
    def test_current_paint_targets_remain_registered_for_each_role_and_theme(self):
        fixture = text_contract.HistoryTextContracts()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.captures()
        for theme in ("light", "dark"):
            path = fixture.directory / f"history-current-{theme}-portrait.png"
            sidecar = path.with_suffix(".json")
            original = sidecar.read_text()
            for role in ("observation", "edge", "dash", "bridge"):
                data = json.loads(original)
                next(item for item in data["pixelSamples"] if item["kind"] == role)["targetArgb"] = -1
                sidecar.write_text(json.dumps(data))
                with self.subTest(theme=theme, role=role), self.assertRaises(ValueError):
                    capture_receipt(path)
            sidecar.write_text(original)
