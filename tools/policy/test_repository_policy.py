"""Cheap semantic policy checks and in-memory negative controls; no builds."""
from copy import deepcopy
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from repository_policy import (EXPRESSION, ROOT, SCHEMA_SHA256, action_policy, contains_secrets_context,
                               dependabot_policy, load_yaml, verified_schema, workflow_files, workflow_policy)


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

    def test_pr_rejects_every_secret_context_reference(self):
        original = (ROOT / ".github/workflows/repository-policy.yml").read_text()
        for expression in (
            "SECRETS.REVIEW_TEST", "SeCrEtS.REVIEW_TEST", "toJSON(secrets)",
            "toJSON(SeCrEtS)", "secrets['REVIEW_TEST']", "SECRETS['REVIEW_TEST']",
            "secrets", "format('{0}', secrets)", "format('}}', secrets.REVIEW_TEST)",
            "format('it''s }}', SECRETS.REVIEW_TEST)",
            "github.ref }} ${{ toJSON(SECRETS)",
        ):
            fixture = original + "\nenv:\n  REVIEW_VALUE: ${{ " + expression + " }}\n"
            with self.subTest(expression=expression), self.assertRaisesRegex(ValueError, "must not use secrets"):
                workflow_policy(fixture, "fixture.yaml")
        # Inspect decoded YAML values, not only their source spelling.
        encoded = original + '\nenv:\n  REVIEW_VALUE: "${{ \\u0073ecrets.REVIEW_TEST }}"\n'
        with self.assertRaisesRegex(ValueError, "must not use secrets"):
            workflow_policy(encoded, "fixture.yaml")
        for key in ("secrets", "SECRETS", "SeCrEtS"):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "must not use secrets"):
                workflow_policy(original + f"\n{key}: inherit\n", "fixture.yaml")
        for condition in ("SECRETS.REVIEW_TEST != ''", "contains(toJSON(secrets), 'x')",
                          "contains(toJSON(SECRETS), '${{')"):
            for old, new in (
                ("  policy:\n", "  policy:\n    if: " + condition + "\n"),
                ("persist-credentials: false", "persist-credentials: false\n        if: " + condition),
            ):
                fixture = original.replace(old, new, 1)
                with self.subTest(condition=condition, placement=old), self.assertRaisesRegex(
                    ValueError, "must not use secrets"
                ):
                    workflow_policy(fixture, "fixture.yaml")
        folded = original + "\nenv:\n  REVIEW_VALUE: >-\n    ${{ toJSON(\n    SECRETS) }}\n"
        with self.assertRaisesRegex(ValueError, "must not use secrets"):
            workflow_policy(folded, "fixture.yaml")

    def test_non_secret_expressions_and_literal_names_remain_allowed(self):
        original = (ROOT / ".github/workflows/repository-policy.yml").read_text()
        for expression in (
            "github.token", "GITHUB.TOKEN", "env.secrets", "github.event.secrets",
            "vars['secrets']", "format('secrets', github.ref)", "'it''s SECRETS'",
            "'secrets.REVIEW_TEST'", "format('}}', github.ref)",
            "'secrets' }} ${{ github.ref",
        ):
            fixture = original + "\nenv:\n  REVIEW_VALUE: ${{ " + expression + " }}\n"
            with self.subTest(expression=expression):
                workflow_policy(fixture, "fixture.yaml")
        workflow_policy(original + "\n# Document the secrets context without using it.\n", "fixture.yaml")
        workflow_policy(original + "\nenv:\n  REVIEW_VALUE: secrets.REVIEW_TEST\n", "fixture.yaml")
        for condition in ("contains('secrets', github.ref)", "contains('${{', github.ref)",
                          "${{ github.ref != '' }}"):
            fixture = original.replace("  policy:\n", "  policy:\n    if: " + condition + "\n", 1)
            with self.subTest(condition=condition):
                workflow_policy(fixture, "fixture.yaml")

    def test_expression_boundaries_preserve_spans_and_literal_contents(self):
        for value, expected in (
            ("${{ 'it''s }}' }}", [((0, 17), " 'it''s }}' ")]),
            ("${{ '}}' }}", [((0, 11), " '}}' ")]),
            ("${{ 'a' }} ${{ 'b' }}", [((0, 10), " 'a' "), ((11, 21), " 'b' ")]),
            ("${{ 'a' }}' }}", [((0, 10), " 'a' ")]),
            ("${{ 'a''' }}", [((0, 12), " 'a''' ")]),
            ("${{ 'unterminated }}", []),
            ("${{ 'a'' }}", []),
            ("${{ 'outer ${{ secrets.X }}", [((11, 27), " secrets.X ")]),
        ):
            with self.subTest(value=value):
                self.assertEqual([(match.span(), match.group(1)) for match in EXPRESSION.finditer(value)],
                                 expected)

    def test_pr_quote_escapes_hide_only_literal_identifiers(self):
        original = (ROOT / ".github/workflows/repository-policy.yml").read_text()
        for value, forbidden in (
            ("${{ 'it''s }} SECRETS.X' }}", False),
            ("${{ 'it''s }}' || env.secrets }}", False),
            ("${{ 'secrets' }} ${{ '}}''secrets' }}", False),
            ("${{ 'unterminated secrets.X }}", False),
            ("${{ 'it''s }}' || SECRETS.X }}", True),
            ("${{ 'secrets' }} ${{ '}}''x' || secrets['X'] }}", True),
            ("${{ 'outer ${{ secrets.X }}", True),
        ):
            fixture = original + "\nenv:\n  REVIEW_VALUE: " + value + "\n"
            with self.subTest(value=value):
                if forbidden:
                    with self.assertRaisesRegex(ValueError, "must not use secrets"):
                        workflow_policy(fixture, "fixture.yaml")
                else:
                    workflow_policy(fixture, "fixture.yaml")

    def test_linear_time_expression_quote_regression(self):
        # Bounded 50k-quote inputs exercise the old exponential ambiguity without
        # a flaky elapsed-time assertion, including CodeQL's exact opener shape.
        quotes = "'" + "''" * 25_000
        for prefix in ("${{", "${{{{"):
            for suffix in ("", " }}"):
                value = prefix + quotes + suffix
                with self.subTest(prefix=prefix, suffix=suffix):
                    self.assertEqual(list(EXPRESSION.finditer(value)), [])
                    self.assertFalse(contains_secrets_context(value))
            value = prefix + quotes + "' }}"
            with self.subTest(prefix=prefix, suffix="closed literal"):
                expected = prefix[3:] + quotes + "' "
                self.assertEqual([match.group(1) for match in EXPRESSION.finditer(value)], [expected])
                self.assertFalse(contains_secrets_context(value))

    def test_checkout_identity_is_case_insensitive_and_includes_subpaths(self):
        original = (ROOT / ".github/workflows/repository-policy.yml").read_text()
        checkout = load_yaml(original)["jobs"]["policy"]["steps"][0]["uses"]
        revision = checkout.split("@", 1)[1]
        for identity in ("Actions/Checkout", "ACTIONS/CHECKOUT", "actions/checkout/subpath", "Actions/Checkout/subpath"):
            fixture = original.replace(checkout, f"{identity}@{revision}", 1)
            with self.subTest(identity=identity):
                workflow_policy(fixture, "fixture.yaml")
            for credentials in ("persist-credentials: true", "persist-credentials: 'false'", "fetch-depth: 1"):
                with self.subTest(identity=identity, credentials=credentials), self.assertRaisesRegex(
                    ValueError, "checkout must use persist-credentials: false"
                ):
                    workflow_policy(fixture.replace("persist-credentials: false", credentials, 1), "fixture.yaml")

    def test_local_docker_and_traversal_action_references_cannot_masquerade_as_pins(self):
        for identity in ("./checkout", "./actions/checkout", "../actions/checkout",
                         "docker://alpine", "Docker://alpine", "DOCKER://alpine",
                         "actions/checkout/../other", "actions/checkout/./subpath"):
            reference = identity + "@" + "a" * 40
            with self.subTest(reference=reference), self.assertRaisesRegex(ValueError, "external Action"):
                action_policy(f"- uses: {reference} # v4.3.0\n")
        action_policy("- uses: OWNER/Repository/Subpath@" + "A" * 40 + " # v1.2.3\n")

    def test_permission_and_runner_case_variants_fail_closed(self):
        original = (ROOT / ".github/workflows/repository-policy.yml").read_text()
        for old, new in (
            ("permissions:\n  contents: read", "permissions: WRITE-ALL"),
            ("permissions:\n  contents: read", "permissions: Write-All"),
            ("permissions:\n  contents: read", "permissions:\n  CONTENTS: read"),
            ("permissions:\n  contents: read", "permissions:\n  contents: READ"),
            ("    permissions: {}", "    permissions: WRITE-ALL"),
            ("    permissions: {}", "    permissions:\n      Contents: Write"),
            ("    runs-on: ubuntu-24.04", "    runs-on: Self-Hosted"),
            ("    runs-on: ubuntu-24.04", "    runs-on: [SELF-HOSTED, linux]"),
            ("    runs-on: ubuntu-24.04", "    runs-on: {labels: [Self-Hosted, Linux]}"),
        ):
            self.assertIn(old, original)
            with self.subTest(mutation=new), self.assertRaises(ValueError):
                workflow_policy(original.replace(old, new, 1), "fixture.yaml")

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

    def test_main_workflow_queue_excludes_overlapping_snapshots_without_losing_pending_runs(self):
        original = (ROOT / ".github/workflows/code-metrics.yml").read_text()
        workflow = load_yaml(original)
        # The policy binds this model to the actual workflow-level expressions:
        # main pushes share one noncanceling max queue; PR/manual remain single/cancel.
        workflow_policy(original, "code-metrics.yml")
        concurrency = workflow["concurrency"]
        old_group = ("${{ github.workflow }}-${{ github.event_name }}-"
                     "${{ github.event_name == 'push' && github.run_id || "
                     "github.event.pull_request.number || github.ref }}")
        for key, value in (("group", old_group), ("queue", "single"),
                           ("queue", "max"), ("cancel-in-progress", True),
                           ("cancel-in-progress", False)):
            changed = original.replace(str(concurrency[key]),
                                       str(value).lower() if isinstance(value, bool) else value, 1)
            with self.subTest(key=key, value=value), self.assertRaisesRegex(ValueError, "Code metrics requires"):
                workflow_policy(changed, "code-metrics.yml")

        def replay(trace, shared):
            # A workflow holds its group across all jobs, including slow upload.
            # The old run-id groups permit R1; the validated main-ref group does not.
            active, pending, history, snapshots, served = set(), [], [], {}, []
            for run, phase in trace:
                group = concurrency["group"] if shared else run
                if phase == "queue":
                    pending.append(run)
                elif phase == "start":
                    if group in active:
                        return None
                    if run in pending:
                        pending.remove(run)
                    active.add(group)
                elif phase == "history":
                    history.append(run)
                elif phase == "snapshot":
                    snapshots[run] = list(history)
                elif phase == "deploy":
                    served = snapshots[run]
                    active.remove(group)
            self.assertEqual(pending, [])
            return history, served

        stale = [("A", "start"), ("A", "history"), ("A", "snapshot"),
                 ("B", "start"), ("B", "history"), ("B", "snapshot"),
                 ("B", "deploy"), ("A", "deploy")]
        self.assertEqual(replay(stale, shared=False), (["A", "B"], ["A"]))
        self.assertIsNone(replay(stale, shared=True))
        # Three pending pushes fit the documented max queue without replacement.
        # Admission order need not be dispatch order; each later checkout sees
        # all durable points from preceding workflows, including reordered ones.
        for admitted in (("A", "B", "C"), ("B", "C", "A")):
            trace = [(run, "queue") for run in admitted]
            trace += [(run, phase) for run in admitted
                      for phase in ("start", "history", "snapshot", "deploy")]
            self.assertEqual(replay(trace, shared=True), (list(admitted), list(admitted)))

    def assert_pages_snapshot_boundary(self, workflow):
        jobs = workflow["jobs"]
        snapshot, pages = jobs["snapshot"], jobs["pages"]
        self.assertEqual(snapshot["needs"], "history")
        self.assertEqual(snapshot["if"],
                         "${{ github.event_name == 'push' && github.ref == 'refs/heads/main' && "
                         "needs.history.result == 'success' }}")
        self.assertEqual(snapshot["permissions"], {"contents": "read"})
        self.assertNotIn("environment", snapshot)
        self.assertEqual(set(pages["needs"]), {"history", "snapshot"})
        self.assertEqual(pages["name"], "Deploy code metric chart")
        self.assertEqual(pages["environment"],
                         {"name": "github-pages", "url": "${{ steps.deploy.outputs.page_url }}"})
        self.assertEqual(pages["concurrency"],
                         {"group": "code-metrics-pages", "queue": "max", "cancel-in-progress": False})
        checkout, nojekyll, artifact, upload = snapshot["steps"]
        self.assertEqual(checkout["uses"],
                         "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1")
        self.assertEqual(checkout["with"], {"ref": "gh-pages", "persist-credentials": False})
        self.assertEqual(nojekyll["run"], "touch .nojekyll")
        self.assertEqual(artifact["id"], "artifact")
        self.assertEqual(upload["uses"],
                         "actions/upload-pages-artifact@fc324d3547104276b827a68afc52ff2a11cc49c9")
        self.assertEqual(snapshot["outputs"],
                         {"artifact_name": "${{ steps.artifact.outputs.artifact_name }}"})
        self.assertEqual(upload["with"], {"name": snapshot["outputs"]["artifact_name"],
                                         "path": ".", "include-hidden-files": True})
        deployment, = pages["steps"]
        self.assertEqual(deployment["uses"],
                         "actions/deploy-pages@368f82528645a54fb793d4d04e342629a3f51346")
        self.assertEqual(deployment["id"], "deploy")
        self.assertEqual(deployment["with"],
                         {"artifact_name": "${{ needs.snapshot.outputs.artifact_name }}"})
        for job in (snapshot, pages):
            self.assertNotIn("continue-on-error", job)
            for step in job["steps"]:
                self.assertNotIn("if", step)
                self.assertNotIn("continue-on-error", step)

    def test_pages_snapshot_boundary_and_actual_name_binding(self):
        original = (ROOT / ".github/workflows/code-metrics.yml").read_text()
        workflow_policy(original, "code-metrics.yml")
        self.assert_pages_snapshot_boundary(load_yaml(original))
        for old, new in (
            ("needs: [history, snapshot]", "needs: history"),
            ("needs: [history, snapshot]", "needs: [history, erosion]"),
            ("needs: [history, snapshot]", "needs: snapshot"),
            ("          name: ${{ steps.artifact.outputs.artifact_name }}", "          name: github-pages"),
            ("artifact_name: ${{ steps.artifact.outputs.artifact_name }}",
             "artifact_name: github-pages-${{ github.run_attempt }}"),
            ("artifact_name: ${{ needs.snapshot.outputs.artifact_name }}",
             "artifact_name: github-pages-${{ github.run_attempt }}"),
            ("artifact_name: ${{ needs.snapshot.outputs.artifact_name }}",
             "artifact_name: ${{ needs.history.outputs.artifact_name }}"),
            ("      contents: read\n    outputs:", "      pages: write\n    outputs:"),
            ("      - name: Upload chart snapshot", "      - if: always()\n        name: Upload chart snapshot"),
            ("      - name: Deploy chart", "      - continue-on-error: true\n        name: Deploy chart"),
        ):
            self.assertIn(old, original)
            with self.subTest(mutation=new), self.assertRaises(AssertionError):
                self.assert_pages_snapshot_boundary(load_yaml(original.replace(old, new, 1)))

    def test_pages_rejects_missing_or_unsuccessful_snapshot_guard(self):
        original = (ROOT / ".github/workflows/code-metrics.yml").read_text()
        guard = " && needs.snapshot.result == 'success'"
        for replacement in ("", " || needs.snapshot.result == 'success'",
                            " && needs.snapshot.result != 'success'",
                            " && needs.snapshot.result == 'failure'",
                            " && needs.snapshot.result == 'skipped'",
                            " && needs.snapshot.result == 'cancelled'"):
            self.assertIn(guard, original)
            with self.subTest(guard=replacement), self.assertRaisesRegex(ValueError, "pages: writer"):
                workflow_policy(original.replace(guard, replacement, 1), "code-metrics.yml")

    def test_snapshot_name_comes_from_producer_attempt_not_deploy_rerun(self):
        workflow = load_yaml((ROOT / ".github/workflows/code-metrics.yml").read_text())
        self.assert_pages_snapshot_boundary(workflow)
        command = workflow["jobs"]["snapshot"]["steps"][2]["run"]
        # Execute the actual output producer. A failed-only deploy rerun retains
        # the earlier successful job output; a full rerun executes this step anew.
        for producer_attempt, deploy_attempt in ((1, 1), (1, 2), (2, 2), (2, 3)):
            with self.subTest(producer=producer_attempt, deploy=deploy_attempt), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "output"
                execution = subprocess.run(["bash", "-eu", "-c", command],
                                           env=dict(os.environ, GITHUB_RUN_ATTEMPT=str(producer_attempt),
                                                    GITHUB_OUTPUT=str(output)),
                                           text=True, capture_output=True, timeout=5)
                self.assertEqual(execution.returncode, 0, execution.stderr)
                self.assertEqual(output.read_text(), f"artifact_name=github-pages-{producer_attempt}\n")
                if producer_attempt != deploy_attempt:
                    self.assertNotEqual(output.read_text(), f"artifact_name=github-pages-{deploy_attempt}\n")

    def test_yaml_on_booleans_and_duplicate_keys(self):
        self.assertEqual(load_yaml("on: [pull_request]\nflag: false\n"),
                         {"on": ["pull_request"], "flag": False})
        with self.assertRaisesRegex(ValueError, "duplicate YAML key"):
            load_yaml("permissions: write-all\npermissions: {}\n")

    def test_dependabot_schema_is_pinned_and_tampering_rejected(self):
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
