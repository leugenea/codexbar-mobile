"""Quick offline artifact validation. No app coverage or live execution claims."""
import copy
from datetime import datetime, timezone
import hashlib
import json
import re
import unittest
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from jsonschema import ValidationError
from contract import DATA, ROOT, countdown, expiry_seconds, privacy_guard, read, read_outcome, schema_validate, select_windows, strict_json, validate_snapshot


class FixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.vectors = read(DATA / "fixtures/synthetic-vectors.json")["vectors"]

    def test_all_seventeen_normalized_synthetic_snapshots(self):
        self.assertEqual(len(self.vectors), 17)
        self.assertEqual(len({v["caseId"] for v in self.vectors}), 17)
        for vector in self.vectors:
            with self.subTest(case=vector["caseId"]):
                self.assertEqual(vector["caseId"], vector["expected"]["caseId"])
                validate_snapshot(vector["expected"])
                rate = vector["rawUsage"].get("rate_limit", {})
                self.assertEqual(vector["expected"]["providerAllowed"], rate.get("allowed"))
                self.assertEqual(vector["expected"]["providerLimitReached"], rate.get("limit_reached"))
                self.assertEqual(vector["expected"]["planTypeRaw"], vector["rawUsage"].get("plan_type"))

    def test_upstream_mock_schemas_from_repository(self):
        usage = read(DATA / "fixtures/upstream-test-weekly-only.json")
        detail = read(DATA / "fixtures/upstream-test-reset-details.json")
        schema_validate(usage, "source-usage-window.schema.json")
        schema_validate(detail, "source-reset-details.schema.json")
        self.assertEqual(usage["rate_limit"]["primary_window"]["limit_window_seconds"], 604800)
        self.assertIsNone(usage["rate_limit"]["secondary_window"])
        self.assertEqual(detail["available_count"], 2)
        self.assertIsNone(detail["credits"][1]["expires_at"])

    def test_source_shapes_reject_bad_counts_and_window_types(self):
        detail = read(DATA / "fixtures/upstream-test-reset-details.json")
        detail["available_count"] = -1
        with self.assertRaises(ValidationError):
            schema_validate(detail, "source-reset-details.schema.json")
        usage = read(DATA / "fixtures/upstream-test-weekly-only.json")
        usage["rate_limit"]["primary_window"]["used_percent"] = "5"
        with self.assertRaises(ValidationError):
            schema_validate(usage, "source-usage-window.schema.json")

    def test_snapshot_negative_mutations_rejected(self):
        mutations = [
            lambda s: s["windows"]["fiveHour"].update(usedPercent=-1),
            lambda s: s["bankedResets"].update(reportedAvailableCount=-1),
            lambda s: s["windows"]["fiveHour"].update(windowSeconds="18000"),
            lambda s: s.update(access_token="placeholder-not-a-secret"),
            lambda s: s["windows"]["fiveHour"].update(usedPercent=0),
            lambda s: s["windows"]["fiveHour"].update(usedPercent=float("inf")),
            lambda s: s["windows"]["fiveHour"].update(usedPercent=True),
            lambda s: s["bankedResets"]["items"].append({"key":"fixture-bad","resetType":"codex_rate_limits","providerStatus":"available","grantedAtEpochSeconds":None,"expiresAtEpochSeconds":None,"expiryKnowledge":"unknown","locallyExpired":False}),
            lambda s: s.update(observedAtEpochSeconds=s["evaluatedAtEpochSeconds"] + 1),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)):
                value = copy.deepcopy(self.vectors[0]["expected"])
                mutate(value)
                with self.assertRaises((ValidationError, ValueError)):
                    validate_snapshot(value)

    def test_wrong_window_duration_and_duplicate_reset_keys_rejected(self):
        weekly = copy.deepcopy(next(v["expected"] for v in self.vectors if v["caseId"] == "weekly-in-primary"))
        weekly["windows"]["weekly"]["windowSeconds"] = 18000
        with self.assertRaises(ValueError):
            validate_snapshot(weekly)
        bank = copy.deepcopy(next(v["expected"] for v in self.vectors if v["caseId"] == "banked-distinct-from-purchased"))
        bank["bankedResets"]["items"].append(copy.deepcopy(bank["bankedResets"]["items"][0]))
        with self.assertRaises(ValueError):
            validate_snapshot(bank)

    def test_schema_pass_does_not_erase_count_or_expiry_disagreement(self):
        values = {v["caseId"]:v["expected"] for v in self.vectors}
        bank = values["expiry-reported-count-conflict"]["bankedResets"]
        self.assertEqual(bank["reportedAvailableCount"], 1)
        self.assertTrue(bank["items"][0]["locallyExpired"])
        self.assertEqual(bank["reason"], "reported_count_expiry_conflict")
        modified = copy.deepcopy(values["banked-distinct-from-purchased"])
        modified["bankedResets"].update(summaryAvailableCount=7, reportedAvailableCount=3, reason="count_list_disagreement")
        validate_snapshot(modified)
        self.assertEqual((modified["bankedResets"]["summaryAvailableCount"],modified["bankedResets"]["reportedAvailableCount"],len(modified["bankedResets"]["items"])), (7,3,2))

    def test_owner_receipt_schema_and_evidence_limits(self):
        owner = read(DATA / "fixtures/owner-reported-live.json")
        schema_validate(owner, "owner-receipt.schema.json")
        self.assertEqual(owner["evidenceKind"], "owner_reported_live")
        self.assertIsNone(owner["observedAt"])
        self.assertIsNone(owner["usage"]["observedAt"])
        self.assertIsNone(owner["resets"]["observedAt"])
        self.assertFalse(owner["auth"]["identityVerified"])
        self.assertEqual(owner["auth"]["accountHeader"], "omitted")
        self.assertEqual(owner["usage"]["primaryWindow"]["windowSeconds"], 604800)
        self.assertEqual(owner["usage"]["secondaryWindow"], "unavailable")
        self.assertEqual(owner["resets"]["inventoryCount"], len(owner["resets"]["items"]))
        altered = copy.deepcopy(owner)
        altered["auth"]["identityVerified"] = True
        with self.assertRaises(ValidationError):
            schema_validate(altered, "owner-receipt.schema.json")

    def test_owner_microsecond_expiry_behavior_regression(self):
        owner = read(DATA / "fixtures/owner-reported-live.json")
        for item in owner["resets"]["items"]:
            with self.subTest(input=item["expiryInput"]):
                self.assertEqual(expiry_seconds(item["expiryInput"]), item["expiresAtEpochSeconds"])
        self.assertEqual([datetime.fromtimestamp(x["expiresAtEpochSeconds"], timezone.utc).strftime("%Y-%m-%d %H:%M:%S") for x in owner["resets"]["items"]], ["2026-10-22 20:31:56", "2026-10-29 18:58:37"])

    def test_expiry_null_missing_or_unsupported_stay_unknown(self):
        for value in (None, {}, 1792701116, True, "2026-10-22", "2026-10-22T20:31:56+00:00", "2026-10-22T20:31:56.1234567Z", "2026-02-30T20:31:56Z", "2026-10-22T20:31:60Z", "2026-10-22T20:31:56Z\n"):
            with self.subTest(value=value):
                self.assertIsNone(expiry_seconds(value))
        self.assertIsNone(expiry_seconds({}.get("expires_at")))

    def test_expiry_fraction_precision_and_calendar_bounds(self):
        for fraction in ("", ".1", ".12", ".123", ".1234", ".12345", ".123456"):
            with self.subTest(fraction=fraction):
                self.assertEqual(expiry_seconds("2026-10-22T20:31:56" + fraction + "Z"), 1792701116)
        self.assertIsNotNone(expiry_seconds("2024-02-29T00:00:00Z"))
        self.assertIsNone(expiry_seconds("2025-02-29T00:00:00Z"))

    def test_absent_null_unknown_and_duplicate_durations_not_zero(self):
        for raw in ({}, {"primary_window":None}, {"primary_window":{"limit_window_seconds":900,"used_percent":0}}):
            self.assertEqual(select_windows(raw)["fiveHour"], {"state":"unavailable","usedPercent":None})
        raw = {slot:{"limit_window_seconds":18000,"used_percent":5} for slot in ("primary_window","secondary_window")}
        self.assertEqual(select_windows(raw)["fiveHour"], {"state":"ambiguous","usedPercent":None})

    def test_five_hour_and_weekly_identified_by_duration_not_slot(self):
        result = select_windows({"primary_window":{"limit_window_seconds":604800,"used_percent":5},"secondary_window":{"limit_window_seconds":18000,"used_percent":0}})
        self.assertEqual(result["weekly"]["sourceSlot"], "primary_window")
        self.assertEqual(result["fiveHour"]["sourceSlot"], "secondary_window")
        self.assertEqual(result["fiveHour"]["usedPercent"], 0)

    def test_malformed_window_does_not_destroy_valid_sibling(self):
        for bad in ("5", True, -1, 101, float("nan"), float("inf")):
            result = select_windows({"primary_window":{"limit_window_seconds":18000,"used_percent":bad},"secondary_window":{"limit_window_seconds":604800,"used_percent":22}})
            self.assertEqual(result["fiveHour"]["state"], "malformed")
            self.assertEqual(result["weekly"]["usedPercent"], 22)

    def test_unknown_plan_alone_does_not_establish_ineligibility(self):
        raw = {"plan_type":"future_plan","rate_limit":{"primary_window":{"limit_window_seconds":604800,"used_percent":5}}}
        self.assertEqual(select_windows(raw["rate_limit"])["weekly"]["state"], "available")

    def test_policy_vectors_actual_outcomes(self):
        policies = read(DATA / "fixtures/policy-vectors.json")
        schema_validate(policies, "policy-vectors.schema.json")
        self.assertEqual(len(policies["cases"]), 7)
        self.assertEqual(len({v["caseId"] for v in policies["cases"]}), 7)
        for case in policies["cases"]:
            with self.subTest(case=case["caseId"]):
                self.assertEqual(read_outcome(case["httpStatus"], case["terminalRefresh"]), case["expectedOutcome"])

    def test_stale_and_reset_rollover_preserve_last_usage(self):
        values = {v["caseId"]:v["expected"] for v in self.vectors}
        self.assertEqual(values["stale-data"]["freshness"], "stale")
        self.assertEqual(values["stale-data"]["windows"]["fiveHour"]["usedPercent"], 33)
        due = values["reset-due-not-zero"]
        self.assertEqual(due["windows"]["fiveHour"]["usedPercent"], 83)
        self.assertEqual(countdown(due["windows"]["fiveHour"]["resetAtEpochSeconds"], due["evaluatedAtEpochSeconds"]), "awaiting refresh")

    def test_relative_only_reset_requires_explicit_clock_provenance(self):
        value = copy.deepcopy(next(v["expected"] for v in self.vectors if v["caseId"] == "subhour-reset"))
        window = value["windows"]["fiveHour"]
        window["resetSource"] = "provider_relative_derived"
        self.assertEqual(window["resetAtEpochSeconds"], value["observedAtEpochSeconds"] + window["resetAfterSecondsRaw"])
        validate_snapshot(value)
        window["resetAtEpochSeconds"] = None
        with self.assertRaises(ValueError):
            validate_snapshot(value)

    def test_dst_uses_instants_and_explicit_offsets(self):
        a = datetime.fromisoformat("2026-11-01T05:30:00+00:00")
        b = datetime.fromisoformat("2026-11-01T06:30:00+00:00")
        zone = ZoneInfo("America/New_York")
        self.assertEqual(a.astimezone(zone).strftime("%Y-%m-%d %H:%M %z"), "2026-11-01 01:30 -0400")
        self.assertEqual(b.astimezone(zone).strftime("%Y-%m-%d %H:%M %z"), "2026-11-01 01:30 -0500")
        self.assertEqual(countdown(int(b.timestamp()), int(a.timestamp())), "0d 1h")

    def test_subhour_unknown_and_due_display_not_zero_hours(self):
        self.assertEqual(countdown(1059,1000), "<1h")
        self.assertEqual(countdown(None,1000), "unknown")
        self.assertEqual(countdown(1000,1000), "awaiting refresh")
        self.assertEqual(countdown(900,1000), "awaiting refresh")

    def test_strict_json_rejects_nonfinite_and_duplicate_fields(self):
        for text in ('{"x":NaN}', '{"x":Infinity}', '{"x":1e999}', '{"x":1,"x":2}'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                strict_json(text)

    def test_fixture_privacy_guard_and_negative_control(self):
        for path in (DATA / "fixtures").glob("*.json"):
            if path.name != "provenance.json":
                privacy_guard(read(path))
        for key in ("access_token", "Authorization", "email", "profile_user_id"):
            with self.assertRaises(ValueError):
                privacy_guard({"nested":[{key:"placeholder"}]})

    def test_provenance_counts_hashes_and_tiers(self):
        provenance = read(DATA / "fixtures/provenance.json")
        self.assertEqual(provenance["synthetic"]["vectorCount"], len(self.vectors))
        for entry in provenance["files"]:
            self.assertEqual(hashlib.sha256((DATA / "fixtures" / entry["file"]).read_bytes()).hexdigest(), entry["sha256"])
            self.assertRegex(entry["sourceCommit"], r"^[0-9a-f]{40}$")
            self.assertIn(entry["sourceCommit"], entry["sourceUrl"])
        owner = provenance["ownerReceipt"]
        self.assertEqual(owner["evidenceKind"], "owner_reported_live")
        self.assertEqual(hashlib.sha256((DATA / "fixtures" / owner["file"]).read_bytes()).hexdigest(), owner["sha256"])

    def test_source_ids_urls_and_document_citations(self):
        sources = read(DATA / "sources.json")["sources"]
        ids = [s["id"] for s in sources]
        self.assertEqual(len(ids),len(set(ids)))
        for source in sources:
            url = urlsplit(source["url"])
            self.assertEqual(url.scheme,"https")
            self.assertTrue(url.netloc)
            self.assertIsNone(url.username)
            self.assertRegex(source["accessed"], r"^\d{4}-\d{2}-\d{2}$")
            for key in ("sha256", "raw_sha256", "text_sha256"):
                if key in source:
                    self.assertRegex(source[key], r"^[0-9a-f]{64}$")
        document = (DATA.parent / "m0.md").read_text()
        body, rendered = document.split("## Sources\n",1)
        cites = set(int(x) for x in re.findall(r"\[(\d+)\]", body))
        mapping = {int(i):u for i,u in re.findall(r"^\[(\d+)\] (https://\S+)",rendered,re.M)}
        ledger = {s["id"]:s["url"] for s in sources}
        self.assertEqual(cites,set(mapping))
        self.assertTrue(cites <= set(ids))
        self.assertEqual(mapping,{i:ledger[i] for i in cites})

    def test_toolchain_selection_has_exact_pins_and_future_build_boundary(self):
        toolchain = read(DATA / "toolchain.json")
        self.assertEqual(toolchain["status"], "selected_M0_baseline_not_resolved_or_built")
        self.assertEqual(toolchain["minSdk"],26)
        self.assertEqual(toolchain["kotlin"]["version"],toolchain["kotlin"]["composeCompilerPlugin"])
        for component, key in (("jdk","sha256"),("gradle","distributionSha256"),("agp","pomSha256"),("coroutines","pomSha256"),("compose","pomSha256")):
            self.assertRegex(toolchain[component][key],r"^[0-9a-f]{64}$")

    def test_ci_narrow_read_only_full_sha_contract(self):
        text = (ROOT / ".github/workflows/m0-contract.yml").read_text()
        self.assertIn("name: M0 contract",text)
        self.assertIn("runs-on: ubuntu-24.04",text)
        self.assertIn("timeout-minutes: 5",text)
        self.assertIn("  contents: read",text)
        self.assertIn("persist-credentials: false",text)
        for event in ("  pull_request:","  push:","  workflow_dispatch:"):
            self.assertIn(event,text)
        self.assertRegex(text,r"python-version: '3\.11\.15'")
        self.assertEqual(len(re.findall(r"uses: [\w/-]+@[a-f0-9]{40} # v[\d.]+",text)),2)
        for unsafe in ("pull_request_target", "self-hosted", "secrets.", "write-all", "paths:", "auth.openai.com", "chatgpt.com", "gradlew"):
            self.assertNotIn(unsafe,text)
        self.assertIn("python -B -m unittest discover -s tools/research -p 'test_*.py' -v",text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
