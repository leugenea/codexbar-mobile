"""Cheap semantic policy checks and in-memory negative controls; no builds."""
from copy import deepcopy
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from repository_policy import (ROOT, SCHEMA_SHA256, action_policy, dependabot_policy,
                               load_yaml, verified_schema, workflow_files, workflow_policy)


class RepositoryPolicyTests(unittest.TestCase):
    def test_every_workflow_is_pinned_hosted_and_least_privileged(self):
        paths = workflow_files(ROOT / ".github/workflows")
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(path=path.name):
                workflow_policy(path.read_text(), path.name)

    def test_both_extensions_and_inline_uses_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("fixture.yml", "fixture.yaml"):
                (root / name).touch()
            self.assertEqual(["fixture.yaml", "fixture.yml"], [p.name for p in workflow_files(root)])
        action_policy("- uses: actions/checkout@" + "a" * 40 + " # v4.3.0\n")
        action_policy("- run: |\n    uses: this is shell text, not an Action\n")
        for text in ("- uses: actions/checkout@v4 # v4.3.0\n",
                     '- "uses": actions/checkout@v4 # v4.3.0\n',
                     "- {uses: actions/checkout@v4}\n",
                     "- uses: actions/checkout@" + "a" * 40 + "\n",
                     "- uses: actions/checkout@" + "a" * 39 + " # v4.3.0\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                action_policy(text)

    def test_workflow_rejects_synthetic_pin_permission_runner_secret_and_credential_regressions(self):
        original = (ROOT / ".github/workflows/repository-policy.yml").read_text()
        checkout = load_yaml(original)["jobs"]["policy"]["steps"][0]["uses"]
        # A reviewed update can change the SHA and release comment together.
        upgraded = original.replace(checkout, "actions/checkout@" + "b" * 40, 1)
        release = next(line.split(" # ", 1)[1] for line in original.splitlines() if checkout in line)
        upgraded = upgraded.replace("# " + release, "# v9.1.0", 1)
        workflow_policy(upgraded, "repository-policy.yml")
        for old, new in (
            (checkout, "actions/checkout@v4"),
            ("permissions:\n  contents: read", "permissions: write-all"),
            ("permissions:\n  contents: read", "permissions: read-all"),
            ("    permissions: {}", "    permissions:\n      checks: write"),
            ("    runs-on: ubuntu-24.04", "    runs-on: [self-hosted, linux]"),
            ("    runs-on: ubuntu-24.04", "    runs-on: ${{ vars.RUNNER }}"),
            ("persist-credentials: false", "persist-credentials: true"),
            ("persist-credentials: false", "token: ${{ secrets['TOKEN'] }}"),
            ("persist-credentials: false", "token: ${{ secrets.TOKEN }}"),
            ("  pull_request:", "  pull_request_target:"),
            ("    permissions: {}", "    secrets: inherit"),
        ):
            with self.subTest(mutation=old), self.assertRaises(ValueError):
                workflow_policy(original.replace(old, new, 1), "repository-policy.yml")

    def test_only_metric_history_and_pages_can_write_and_only_on_main_push(self):
        original = (ROOT / ".github/workflows/code-metrics.yml").read_text()
        workflow_policy(original, "code-metrics.yml")
        with self.assertRaises(ValueError):
            workflow_policy(original, "lookalike.yaml")
        for old, new in (("github.ref == 'refs/heads/main'", "true"),
                         ("always() && github.event_name == 'push'", "always() && github.event_name != 'push'"),
                         ("      contents: read", "      contents: write"),
                         ("      pages: write", "      checks: write")):
            with self.subTest(mutation=old), self.assertRaises(ValueError):
                workflow_policy(original.replace(old, new, 1), "code-metrics.yml")

    def test_yaml_on_booleans_and_duplicate_keys(self):
        self.assertEqual(load_yaml("on: [pull_request]\nflag: false\n"),
                         {"on": ["pull_request"], "flag": False})
        with self.assertRaisesRegex(ValueError, "duplicate YAML key"):
            load_yaml("permissions: write-all\npermissions: {}\n")

    def test_dependabot_schema_is_pinned_and_tampering_rejected(self):
        self.assertEqual(len(SCHEMA_SHA256), 64)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "schema.json"
            path.write_text('{}')
            with self.assertRaisesRegex(ValueError, "schema checksum mismatch"):
                verified_schema(path)
        # No network in unit tests; the real pinned schema runs in tools/check.sh.
        with patch("repository_policy.urllib.request.urlopen", side_effect=OSError("offline")):
            with self.assertRaisesRegex(OSError, "offline"):
                verified_schema()

    def test_dependabot_covers_required_manifests_without_freezing_whole_file(self):
        original = load_yaml((ROOT / ".github/dependabot.yml").read_text())
        dependabot_policy(original)
        changed = deepcopy(original)
        changed["updates"].reverse()
        for entry in changed["updates"]:
            entry["schedule"]["time"] = "09:00"
            entry["groups"]["routine-upgrades"] = entry["groups"].pop("minor-and-patch")
        dependabot_policy(changed)
        for path in (".github/requirements-metrics.txt", ".github/requirements-policy.txt",
                     "tools/research/requirements.txt", "build.gradle", "app/build.gradle"):
            self.assertTrue((ROOT / path).is_file(), path)

    def test_dependabot_rejects_malformed_updates_and_missing_coverage(self):
        original = load_yaml((ROOT / ".github/dependabot.yml").read_text())
        for index in range(len(original["updates"])):
            for field, bad in (
                ("directory", "/wrong"), ("schedule", {"interval": "daily"}),
                ("open-pull-requests-limit", 0), ("open-pull-requests-limit", 10),
                ("open-pull-requests-limit", True), ("groups", {}),
                ("commit-message", {"prefix": ""}), ("automerge", True),
                ("ignore", [{"dependency-name": "*"}]),
            ):
                changed = deepcopy(original)
                changed["updates"][index][field] = bad
                with self.subTest(index=index, field=field, value=bad), self.assertRaises(ValueError):
                    dependabot_policy(changed)
            changed = deepcopy(original)
            del changed["updates"][index]
            with self.subTest(missing=index), self.assertRaises(ValueError):
                dependabot_policy(changed)
        for field, bad in (("update-types", ["major", "minor", "patch"]),
                           ("applies-to", "security-updates"), ("patterns", ["one-package"]),
                           ("exclude-patterns", ["*"])):
            changed = deepcopy(original)
            changed["updates"][0]["groups"]["minor-and-patch"][field] = bad
            with self.subTest(group_field=field), self.assertRaises(ValueError):
                dependabot_policy(changed)
        for changed in ({"version": 1, "updates": []}, {"version": 2, "updates": "wrong"}):
            with self.assertRaises(ValueError):
                dependabot_policy(changed)
        changed = deepcopy(original)
        changed["updates"].append(changed["updates"][0])
        with self.assertRaisesRegex(ValueError, "duplicate Dependabot"):
            dependabot_policy(changed)

    def test_policy_workflow_is_unrouted_with_stable_fail_closed_result(self):
        workflow = load_yaml((ROOT / ".github/workflows/repository-policy.yml").read_text())
        self.assertEqual(workflow["name"], "Repository policy")
        self.assertEqual(set(workflow["on"]), {"pull_request", "push", "workflow_dispatch"})
        self.assertIsNone(workflow["on"]["pull_request"])
        self.assertEqual(workflow["on"]["push"], {"branches": ["main"]})
        jobs = workflow["jobs"]
        self.assertEqual(set(jobs), {"policy", "result"})
        self.assertNotIn("if", jobs["policy"])
        self.assertNotIn("needs", jobs["policy"])
        result = jobs["result"]
        self.assertEqual(result["name"], "Repository policy result")
        self.assertEqual(result["if"], "always()")
        self.assertEqual(result["needs"], "policy")
        self.assertEqual(result["permissions"], {})
        self.assertEqual(len(result["steps"]), 1)
        step = result["steps"][0]
        self.assertEqual(step["env"], {"POLICY_RESULT": "${{ needs.policy.result }}"})
        self.assertNotIn("if", step)
        self.assertNotIn("continue-on-error", step)
        for status in ("success", "failure", "skipped", "cancelled", ""):
            execution = subprocess.run(["bash", "-eu", "-c", step["run"]],
                                       env=dict(os.environ, POLICY_RESULT=status), timeout=5)
            self.assertEqual(execution.returncode == 0, status == "success", status)
        for job in jobs.values():
            self.assertNotIn("continue-on-error", job)
            for item in job["steps"]:
                self.assertNotIn("continue-on-error", item)

    def test_entrypoint_fails_when_selected_linter_is_missing_or_fails(self):
        for variable in ("ACTIONLINT", "SHELLCHECK"):
            for executable in ("/not-present/policy-tool", "/bin/false"):
                env = dict(os.environ, CHECK_PYTHON=sys.executable, **{variable: executable})
                execution = subprocess.run(["bash", str(ROOT / "tools/check.sh")], env=env,
                                           text=True, capture_output=True, timeout=10)
                self.assertNotEqual(execution.returncode, 0, (variable, executable))


if __name__ == "__main__":
    unittest.main()
