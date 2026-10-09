"""Cheap build source/workflow contracts and explicitly synthetic report-parser tests."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_manifests import (APP_PERMISSIONS, PACKAGE, RECEIVER_PERMISSION, SOURCE_PERMISSIONS, check_apk_dump,
                              check_installed_dump, check_installed_path, check_manifest, check_permissions,
                              check_source_manifests)
from verify_test_reports import (HISTORY_SAMPLING_CASES, HISTORY_SAMPLING_CLASS, HISTORY_LIFETIME_CASES, HISTORY_LIFETIME_CLASS, HISTORY_CASES, HISTORY_CLASS, BANKED_RESET_CASES, BANKED_RESET_CLASS, CLASS, CONNECTION_CASES, CONNECTION_CLASS, CREDENTIAL_CASES,
                                 CREDENTIAL_CLASS, EXPECTED, LIVE_USAGE_CASES, LIVE_USAGE_CLASS,
                                 USAGE_REFRESH_CASES, USAGE_REFRESH_CLASS, verify_reports)

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("RUNNER_TEMP", os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir())))


class WorkflowContracts(unittest.TestCase):
    def assert_repository_gate_tools(self, workflow, entrypoint):
        self.assertTrue(workflow.startswith("name: Repository policy\n"))
        self.assertNotRegex(workflow, r"\b[Mm][0-5]\b|[Mm][0-5][_-]|[Mm][0-5][A-Z]")
        self.assertIn("run: bash tools/check.sh", workflow)
        self.assertIn("--require-hashes --no-deps", workflow)
        for requirements in (".github/requirements-policy.txt", "tools/research/requirements.txt",
                             ".github/requirements-metrics.txt"):
            self.assertIn("-r " + requirements, workflow)
        self.assertIn('python -m venv "$RUNNER_TEMP/policy-venv"', workflow)
        for tool, version, archive, checksum in (
            ("actionlint", "1.7.12", "actionlint.tar.gz",
             "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"),
            ("shellcheck", "0.11.0", "shellcheck.tar.xz",
             "8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198"),
        ):
            self.assertIn("/releases/download/v" + version + "/", workflow)
            self.assertIn("'" + checksum + "' " + archive, workflow)
            self.assertIn("sha256sum -c -", workflow)
            self.assertIn("tar -x", workflow)
            self.assertLess(workflow.index("sha256sum -c -"), workflow.index("tar -x"))
            self.assertIn("./" + tool + " --version", workflow)
            self.assertIn("${{ runner.temp }}/policy-tools/" + tool, workflow)
        for contract in ("set -euo pipefail", "tools/build tools/research tools/metrics tools/policy",
                         "-m unittest discover", '"$shellcheck" --version',
                         '"$shellcheck" --external-sources', 'bash -n "$source"',
                         '"$actionlint" -shellcheck "$shellcheck"', "repository_policy.py",
                         "ast.parse("):
            self.assertIn(contract, entrypoint)
        self.assertNotIn("|| true", entrypoint)

    def test_single_repository_gate_runs_all_suites_and_verified_linters(self):
        self.assert_repository_gate_tools(
            (ROOT / ".github/workflows/repository-policy.yml").read_text(),
            (ROOT / "tools/check.sh").read_text(),
        )

    def test_repository_gate_rejects_unverified_or_missing_tools_and_suites(self):
        original = [(ROOT / path).read_text() for path in (
            ".github/workflows/repository-policy.yml", "tools/check.sh")]
        for target, old, new in (
            (0, "sha256sum -c -", "true"),
            (0, "./shellcheck --version", "true"),
            (0, "--require-hashes --no-deps", "--no-deps"),
            (1, "tools/build tools/research tools/metrics tools/policy", "tools/build tools/research"),
            (1, '"$actionlint" -shellcheck "$shellcheck"', '"$actionlint" -shellcheck=""'),
            (1, 'bash -n "$source"', "true"),
        ):
            texts = original.copy()
            self.assertIn(old, texts[target])
            texts[target] = texts[target].replace(old, new, 1)
            with self.subTest(mutation=old), self.assertRaises(AssertionError):
                self.assert_repository_gate_tools(*texts)

    def assert_descriptive_workflow_names(self, android, research):
        expected = {
            "Android": {
                "build": "Build, lint and unit tests (strict dependency verification)",
                "instrumented": "Instrumented tests and coverage (API 36 emulator)",
                "result": "Android CI result",
            },
            "Research contract": {"contract": "Validate research fixtures and schemas"},
        }
        for workflow, text in (("Android", android), ("Research contract", research)):
            self.assertTrue(text.startswith("name: " + workflow + "\n"))
            jobs = dict(re.findall(r"(?m)^  (\w+):\n    name: (.+)$", text))
            self.assertEqual(jobs, expected[workflow])
            steps = re.findall(r"(?m)^      - (.+)$", text)
            self.assertTrue(steps)
            self.assertTrue(all(step.startswith("name: ") for step in steps),
                            "Every CI step must have a descriptive display name")
            self.assertNotRegex(text, r"\b[Mm][0-5]\b|[Mm][0-5][_-]|[Mm][0-5][A-Z]")

    def test_stable_check_names_and_descriptive_workflows_and_steps(self):
        self.assert_descriptive_workflow_names(
            (ROOT / ".github/workflows/android.yml").read_text(),
            (ROOT / ".github/workflows/research-contract.yml").read_text(),
        )

    def test_name_contract_rejects_ambiguous_checks_and_unnamed_steps(self):
        android = (ROOT / ".github/workflows/android.yml").read_text()
        research = (ROOT / ".github/workflows/research-contract.yml").read_text()
        for old, new in (("name: Android\n", "name: Pipeline\n"),
                         ("name: Android CI result", "name: Result"),
                         ("  build:\n", "  checkpoint:\n"),
                         ("      - name: Check out repository\n        uses:", "      - uses:")):
            with self.subTest(mutation=old), self.assertRaises(AssertionError):
                self.assert_descriptive_workflow_names(android.replace(old, new, 1), research)

    def assert_code_metrics_contract(self, text):
        self.assertTrue(text.startswith("name: Code metrics\n"))
        header, jobs_text = text.split("jobs:\n", 1)
        self.assertIn("  pull_request:\n", header)
        self.assertIn("  push:\n    branches: [main]\n", header)
        self.assertIn("  workflow_dispatch:\n", header)
        self.assertIn("permissions:\n  contents: read\n", header)
        self.assertIn("github.workflow", header)
        self.assertIn("github.event_name", header)
        self.assertIn("github.event_name == 'push' && github.run_id", header)
        self.assertIn("github.event.pull_request.number || github.ref", header)
        jobs = dict(re.findall(r"(?ms)^  (\w+):\n(.*?)(?=^  \w+:\n|\Z)", jobs_text))
        self.assertEqual(set(jobs), {"erosion", "duplication", "history", "pages"})
        names = {"erosion": "Code erosion (Kotlin complexity)",
                 "duplication": "Code duplication (jscpd)",
                 "history": "Publish code metric history", "pages": "Deploy code metric chart"}
        permissions = {"erosion": {"contents": "read"}, "duplication": {"contents": "read"},
                       "history": {"contents": "write"},
                       "pages": {"contents": "read", "pages": "write", "id-token": "write"}}
        for job, body in jobs.items():
            self.assertTrue(body.startswith("    name: " + names[job] + "\n"))
            self.assertIn("runs-on: ubuntu-24.04", body)
            # Read the six-space permission fields, stopping at the next job key.
            permission_text = re.search(r"(?ms)^    permissions:\n((?:      [^\n]+\n)+)", body).group(1)
            self.assertEqual(dict(re.findall(r"      ([\w-]+): (\w+)\n", permission_text)), permissions[job])
            self.assertIn("persist-credentials: false", body)
            steps = re.findall(r"(?m)^      - (.+)$", body)
            self.assertTrue(all(step.startswith("name: ") for step in steps))
        for unsafe in ("secrets.", "self-hosted", "pull_request_target", "continue-on-error", "paths:", "lizard"):
            self.assertNotIn(unsafe, text)
        self.assertNotRegex(text, r"\b[Mm][0-5]\b|[Mm][0-5][_-]|[Mm][0-5][A-Z]")
        self.assertEqual(text.count("persist-credentials: false"), 4)
        uses = re.findall(r"(?m)^\s*uses: ([\w/-]+)@([0-9a-f]{40}) # (v[\d.]+)$", text)
        self.assertEqual(len(re.findall(r"(?m)^\s*uses:", text)), len(uses))
        # Assert the executable surface, not exact release versions: reviewed
        # Dependabot SHA/comment updates must not require a second pin ledger.
        expected_actions = {
            "actions/checkout", "actions/setup-python", "actions/upload-artifact",
            "actions/download-artifact", "benchmark-action/github-action-benchmark",
            "actions/upload-pages-artifact", "actions/deploy-pages",
        }
        self.assertEqual({action for action, _, _ in uses}, expected_actions)
        for job, artifact in (("erosion", "code-erosion"), ("duplication", "code-duplication")):
            body = jobs[job]
            self.assertNotRegex(body, r"(?m)^    (?:if|needs):")
            self.assertIn("-m unittest discover -s tools/metrics", body)
            self.assertIn("name: " + artifact, body)
            self.assertIn("report.json", body)
            self.assertIn("report.md", body)
            self.assertIn('>> "$GITHUB_STEP_SUMMARY"', body)
            self.assertIn("if-no-files-found: error", body)
        self.assertIn("--require-hashes --no-deps -r .github/requirements-metrics.txt", jobs["erosion"])
        duplicate = jobs["duplication"]
        self.assertIn("97259f222ea7f6d51a0f2faa98ed5889430233e0fb8d2c129f23d84aff4b93b2", duplicate)
        self.assertLess(duplicate.index("sha256sum -c -"), duplicate.index("tar -xzf"))
        self.assertIn("--threshold 100 --fail-on-empty", duplicate)
        history, pages = jobs["history"], jobs["pages"]
        for body in (history, pages):
            condition = re.search(r"(?m)^    if: (.+)$", body).group(1)
            self.assertIn("github.event_name == 'push'", condition)
            self.assertIn("github.ref == 'refs/heads/main'", condition)
            self.assertIn("queue: max", body)
            self.assertIn("cancel-in-progress: false", body)
        self.assertIn("needs: [erosion, duplication]", history)
        self.assertIn("needs: history", pages)
        for value in ("tool: customSmallerIsBetter", "gh-pages-branch: gh-pages",
                      "benchmark-data-dir-path: dev/bench", "auto-push: true",
                      'alert-threshold: "110%"', "comment-on-alert: true", "fail-on-alert: false"):
            self.assertEqual(history.count(value), 2, value)
        self.assertIn("name: Code erosion", history)
        self.assertIn("name: Code duplication", history)
        self.assertIn("name: github-pages", pages)
        self.assertIn("ref: gh-pages", pages)
        self.assertIn("touch .nojekyll", pages)
        self.assertIn("name: github-pages-${{ github.run_attempt }}", pages)
        self.assertIn("artifact_name: github-pages-${{ github.run_attempt }}", pages)

    def test_code_metrics_are_informational_and_privilege_is_main_only(self):
        self.assert_code_metrics_contract((ROOT / ".github/workflows/code-metrics.yml").read_text())
        android = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn("needs: [build, instrumented]", android)
        self.assertNotIn("erosion", android)
        self.assertNotIn("duplication", android)
        # Cross-workflow pin/permission policy is exercised by tools/policy;
        # keep this stdlib suite usable in the unchanged Android job.

    def test_code_metrics_contract_rejects_privilege_and_history_regressions(self):
        original = (ROOT / ".github/workflows/code-metrics.yml").read_text()
        release_comment = re.findall(r"# v[\d.]+", original)[0]
        for old, new in (("      contents: read", "      contents: write"),
                         ("queue: max", "queue: single"),
                         ("github.event_name == 'push' && github.run_id", "github.ref"),
                         ("github.ref == 'refs/heads/main'", "true"),
                         ("fail-on-alert: false", "fail-on-alert: true"),
                         ("persist-credentials: false", "persist-credentials: true"),
                         ("artifact_name: github-pages-${{ github.run_attempt }}", "artifact_name: github-pages"),
                         (release_comment, "# unversioned")):
            with self.subTest(mutation=old), self.assertRaises(AssertionError):
                self.assert_code_metrics_contract(original.replace(old, new, 1))

    def test_all_required_real_tests_are_declared_and_placeholders_removed(self):
        for kind, tree, filename in (
            ("jvm", "test", "OfflineShellStateTest.kt"),
            ("native", "androidTest", "OfflineShellSmokeTest.kt"),
        ):
            source_dir = ROOT / f"app/src/{tree}/java/io/github/leugenea/codexbarmobile"
            source = (source_dir / filename).read_text()
            declared = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source))
            self.assertEqual(declared, EXPECTED[kind])
            self.assertFalse((source_dir / "BootstrapCompileTest.kt").exists())
        native = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/OfflineShellSmokeTest.kt").read_text()
        for actual in ("createEmptyComposeRule", "ActivityScenario.launch(intent)", "scenario.recreate()",
                       "compose.waitUntil", "last state=", "assertInstalledNetworkPolicy()",
                       "assertIsSelected()", "assertIdentity()", "ProgressBarRangeInfo",
                       "SemanticsActions.GetTextLayoutResult"):
            self.assertIn(actual, native)
        permission_set = re.search(r'setOf\((.*?)\),', native, re.S)
        assert permission_set is not None
        self.assertEqual(set(re.findall(r'"([^"]+)"', permission_set[1])), APP_PERMISSIONS)
        for policy in ("ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC",
                       "NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted",
                       "PermissionInfo.PROTECTION_SIGNATURE", "receiver.protectionLevel"):
            self.assertIn(policy, native)
        for shortcut in ("setContent", "runBlocking(", "Thread.sleep(", "BuildConfig"):
            self.assertNotIn(shortcut, "\n".join(line for line in native.splitlines() if not line.strip().startswith("/**")))

    def test_native_script_rejects_nonhosted_execution_before_setup(self):
        environment = dict(os.environ, GITHUB_ACTIONS="false")
        result = subprocess.run(["bash", str(ROOT / "tools/build/run-hosted-native-smoke.sh")],
                                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stderr.strip(), "Hosted runner only")

    def test_production_string_resources_are_declared(self):
        resources = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        declared = {element.get("name") for element in resources}
        source = (ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile/MainActivity.kt").read_text()
        self.assertTrue(set(re.findall(r"R\.string\.(\w+)", source)) <= declared)
        self.assertIn("heightIn(min = 48.dp)", source)
        self.assertIn("LiveRegionMode.Polite", source)

    def test_native_job_is_narrow_strict_read_only_and_observable(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        native = workflow.split("  instrumented:\n", 1)[1]
        self.assertIn("name: Instrumented tests and coverage (API 36 emulator)", native)
        self.assertIn("needs: build", native)
        self.assertIn("runs-on: ubuntu-24.04", native)
        self.assertIn("timeout-minutes: 50", native)
        self.assertIn("sudo chmod a+rw /dev/kvm", native)
        self.assertIn("if: always()", native)
        self.assertIn("android-instrumented-${{ github.run_id }}-${{ github.run_attempt }}", native)
        self.assertIn("app/build/outputs/androidTest-results/", native)
        for unsafe in ("secrets.", "self-hosted", "continue-on-error", "--dependency-verification off"):
            self.assertNotIn(unsafe, native)
        script = (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text()
        for contract in ("Hosted runner only", "trap cleanup EXIT", "-accel on", "-accel-check",
                         "15 * 1024 ** 3", "kill -0", "deadline=$((SECONDS + 300))",
                         "sys.boot_completed", "cmd input keyevent", '[[ "$api" == 36 ]]',
                         "--dependency-verification strict", '${GRADLE_USER_HOME:?}',
                         ":app:connectedDebugAndroidTest", "verify_test_reports.py native",
                         "diagnostic-screen.png", "logcat-live.txt", "project-sdk-before.sha256",
                         "system-images;android-36;google_apis;x86_64", "--channel=0",
                         "aapt\" dump permissions", "aapt\" dump xmltree",
                         "shell dumpsys package io.github.leugenea.codexbarmobile",
                         "verify_manifests.py --apk-permissions", "--apk-xmltree", "--installed-package"):
            self.assertIn(contract, script)
        for unsafe in ("--dependency-verification off", "--write-verification-metadata", "testInstrumentationRunnerArguments",
                       "--tests ", "--dry-run", "system-images;android-37"):
            self.assertNotIn(unsafe, script)
        final_gate = script.split("if (( test_status != 0 )); then exit \"$test_status\"; fi", 1)[1]
        for status in ("manifest_status", "native_report_status", "jvm_report_status", "aapt_status",
                       "apk_xmltree_status", "apk_hash_status", "apk_policy_status", "install_hash_status",
                       "install_status", "installed_path_status", "installed_path_policy_status",
                       "installed_dump_status", "permission_status", "uninstall_status"):
            self.assertIn(status + " == 0", final_gate)
        installation = script.split("--install ", 1)[1].split("2>&1", 1)[0]
        self.assertEqual(installation.strip(), 'platform-tools emulator "$image"')

    def assert_cached_execution(self, workflow, script, verifier):
        strict = workflow.split("- name: Build, lint and unit tests with strict verification", 1)[1].split("- name: Upload", 1)[0]
        connected = script.split("./gradlew ", 1)[1].split("2>&1", 1)[0]
        for command in (strict, connected):
            for flag in ("--no-daemon", "--dependency-verification strict", "--no-build-cache",
                         "--no-configuration-cache", "--rerun-tasks", "--stacktrace", "--info"):
                self.assertIn(flag, command)
            for unsafe in ("--build-cache", "--configuration-cache", "--dry-run", "--tests ",
                           "testInstrumentationRunnerArguments", "--write-verification-metadata",
                           "--gradle-user-home", " -g "):
                self.assertNotIn(unsafe, command)
        self.assertEqual(re.findall(r":app:(\w+)", strict), [
            "verifyResolvedToolchain", "lintDebug", "assembleDebug", "processReleaseManifest", "compileDebugUnitTestKotlin",
            "compileDebugAndroidTestKotlin", "testDebugUnitTest", "assembleDebugAndroidTest",
        ])
        self.assertEqual(re.findall(r":app:(\w+)", connected), [
            "verifyResolvedToolchain", "processReleaseManifest", "compileDebugUnitTestKotlin", "compileDebugAndroidTestKotlin",
            "testDebugUnitTest", "connectedDebugAndroidTest", "jacocoDebugCoverageVerification",
        ])
        self.assertIn('${GRADLE_USER_HOME:?}', script)
        self.assertNotRegex(script, r"(?m)^\s*(?:export\s+)?GRADLE_USER_HOME=")
        self.assertNotIn("must start fresh", script)
        for obsolete in ("HOST_ANDROID_HOME", "host_tools", "command -v sdkmanager", "cmdline-tools/latest"):
            self.assertNotIn(obsolete, script + workflow)
        for contract in ('sdkmanager="$ANDROID_HOME/cmdline-tools/22.0/bin/sdkmanager"',
                         'avdmanager="$ANDROID_HOME/cmdline-tools/22.0/bin/avdmanager"',
                         "cmdline-tools-source.properties", "cmdline-tools-binaries.sha256",
                         "sdkmanager-path.txt", "avdmanager-path.txt", "gradle-home.txt",
                         "Pkg.Revision[[:space:]]*=[[:space:]]*22\\.0",
                         'timeout 30 "$sdkmanager" --version', '[[ -d "$ANDROID_HOME/licenses" ]]',
                         "sha256sum --check evidence/native/project-sdk-before.sha256"):
            self.assertIn(contract, script)
        self.assertEqual(script.split("--install ", 1)[1].split("2>&1", 1)[0].strip(),
                         'platform-tools emulator "$image"')
        for contract in ('${JAVA_HOME:?}', '${ANDROID_HOME:?}', '${ANDROID_SDK_ROOT:?}',
                         "observed['java.specification.version'] == '17'",
                         "release['IMPLEMENTOR'] == observed['java.vendor'] == 'Eclipse Adoptium'",
                         '"$JAVA_HOME/release" evidence/jdk-release.txt', '"$(command -v java)"',
                         'timeout 30 java -XshowSettings:properties -version', 'installed-toolchain.json',
                         'installed-binaries.sha256', 'platform-package.xml', 'build-tools-package.xml'):
            # Package receipt names are produced from the observed package name.
            if contract in ('platform-package.xml', 'build-tools-package.xml'):
                self.assertIn("name + '-package.xml'", verifier)
            else:
                self.assertIn(contract, verifier)
        for obsolete in ('curl ', 'unzip ', 'tar -x', 'export JAVA_HOME=', 'GITHUB_PATH',
                         '17.0.20', 'installed-archives.sha256', 'sdk-archives.sha1',
                         'write_sdk_package_metadata.py', '--install', 'GITHUB_ENV'):
            self.assertNotIn(obsolete, verifier)
        for job in ('build', 'instrumented'):
            body = workflow.split('  ' + job + ':\n', 1)[1].split('\n  instrumented:\n', 1)[0]
            self.assertLess(body.index('uses: actions/setup-java@'), body.index('uses: android-actions/setup-android@'))
            self.assertLess(body.index('uses: android-actions/setup-android@'),
                            body.index('bash tools/build/verify-hosted-toolchain.sh'))
        native = workflow.split('  instrumented:\n', 1)[1]
        self.assertLess(native.index('bash tools/build/verify-hosted-toolchain.sh'),
                        native.index('bash tools/build/run-hosted-native-smoke.sh'))

    def test_cached_execution_preserves_strict_real_gates_and_observed_sdk_receipts(self):
        self.assert_cached_execution(
            (ROOT / ".github/workflows/android.yml").read_text(),
            (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text(),
            (ROOT / "tools/build/verify-hosted-toolchain.sh").read_text(),
        )

    def test_cached_execution_rejects_synthetic_regressions(self):
        original = [(ROOT / path).read_text() for path in (
            ".github/workflows/android.yml", "tools/build/run-hosted-native-smoke.sh",
            "tools/build/verify-hosted-toolchain.sh")]
        mutations = [(target, flag, "") for target in (0, 1) for flag in (
            "--no-build-cache", "--no-configuration-cache", "--rerun-tasks")]
        mutations += [
            (1, '${GRADLE_USER_HOME:?}', 'unused-home'),
            (1, 'image=', 'export GRADLE_USER_HOME="$RUNNER_TEMP/unrestored"\nimage='),
            (1, 'platform-tools emulator "$image"', 'platform-tools emulator "platforms;android-37" "$image"'),
            (1, 'cmdline-tools/22.0/bin/sdkmanager', 'cmdline-tools/latest/bin/sdkmanager'),
            (1, 'timeout 30 "$sdkmanager" --version', '"$sdkmanager" --version'),
            (1, 'cmdline-tools-binaries.sha256', 'missing-binary-receipt'),
            (1, 'Pkg.Revision[[:space:]]*=[[:space:]]*22\\.0', 'Pkg.Revision.*'),
            (2, "observed['java.specification.version'] == '17'", "observed['java.specification.version'] == '21'"),
            (2, 'installed-toolchain.json', 'missing-toolchain-receipt'),
            (2, 'timeout 30 java -XshowSettings:properties -version', 'java -version'),
            (2, '# Verification/receipts only;', '# curl manual installer;'),
        ]
        for target, old, new in mutations:
            with self.subTest(target=target, mutation=old):
                texts = original.copy()
                self.assertIn(old, texts[target])
                texts[target] = texts[target].replace(old, new, 1)
                with self.assertRaises(AssertionError):
                    self.assert_cached_execution(*texts)

    def installed_verifiers(self):
        # Execute only the shipped verifier's pure helpers, never Java/SDK/Gradle.
        script = (ROOT / "tools/build/verify-hosted-toolchain.sh").read_text()
        helpers = script.split("python3 - <<'PY'\n", 1)[1].split("# The functions above", 1)[0]
        namespace = {}
        exec(compile(helpers, "hosted-verifier-pure-helpers", "exec"), namespace)
        return namespace['verify_jdk'], namespace['verify_sdk']

    def synthetic_installed_package(self, path, revision):
        props = {'Pkg.Revision': revision}
        root = ET.Element('repository')
        local = ET.SubElement(root, 'localPackage', path=path)
        xml_revision = ET.SubElement(local, 'revision')
        for name, number in zip(('major', 'minor', 'micro'), revision.split('.')):
            ET.SubElement(xml_revision, name).text = number
        if path.startswith('platforms;'):
            props.update({'AndroidVersion.ApiLevel': '37.0', 'AndroidVersion.MinorApiLevel': '0'})
            details = ET.SubElement(local, 'type-details', {
                '{http://www.w3.org/2001/XMLSchema-instance}type': 'sdk:platformDetailsType'})
            ET.SubElement(details, 'api-level').text = '37.0'
        return props, root

    def test_installed_verifier_accepts_observed_17_patches_and_platform_revisions(self):
        verify_jdk, verify_sdk = self.installed_verifiers()
        for runtime in ('17.0.19+7', '17.0.20.1+1', '17.0.21+9'):
            with self.subTest(synthetic_runtime=runtime):
                receipt = verify_jdk({'IMPLEMENTOR': 'Eclipse Adoptium', 'JAVA_RUNTIME_VERSION': runtime},
                                     {'java.vendor': 'Eclipse Adoptium', 'java.specification.version': '17',
                                      'java.runtime.version': runtime})
                self.assertEqual(receipt['javaRuntime'], runtime)
        for revision in ('2', '3', '4.1'):
            with self.subTest(synthetic_platform_revision=revision):
                props, metadata = self.synthetic_installed_package('platforms;android-37.0', revision)
                receipt = verify_sdk('platforms;android-37.0', props, metadata)
                self.assertEqual(receipt['revision'], revision)
        props, metadata = self.synthetic_installed_package('build-tools;36.0.0', '36.0.0')
        self.assertEqual(verify_sdk('build-tools;36.0.0', props, metadata)['revision'], '36.0.0')

    def test_installed_verifier_rejects_wrong_major_vendor_runtime_api_and_metadata(self):
        verify_jdk, verify_sdk = self.installed_verifiers()
        for mutation in ('major', 'vendor', 'runtime', 'release-major'):
            with self.subTest(jdk_mutation=mutation):
                release = {'IMPLEMENTOR': 'Eclipse Adoptium', 'JAVA_RUNTIME_VERSION': '17.0.21+9'}
                observed = {'java.vendor': 'Eclipse Adoptium', 'java.specification.version': '17',
                            'java.runtime.version': '17.0.21+9'}
                if mutation == 'major':
                    observed['java.specification.version'] = '21'
                elif mutation == 'vendor':
                    observed['java.vendor'] = 'Other vendor'
                elif mutation == 'runtime':
                    observed['java.runtime.version'] = '17.0.19+7'
                else:
                    release['JAVA_RUNTIME_VERSION'] = observed['java.runtime.version'] = '21.0.1+1'
                with self.assertRaises(AssertionError):
                    verify_jdk(release, observed)
        for mutation in ('api', 'minor', 'preview', 'xml-api', 'xml-revision', 'path',
                         'duplicate', 'no-metadata', 'empty-revision', 'preview-revision',
                         'xml-type', 'build-tools'):
            with self.subTest(sdk_mutation=mutation):
                path = 'platforms;android-37.0'
                props, metadata = self.synthetic_installed_package(path, '3')
                local = metadata.find('localPackage')
                assert local is not None
                details = local.find('type-details')
                api = local.find('type-details/api-level')
                major = local.find('revision/major')
                assert details is not None and api is not None and major is not None
                if mutation == 'api':
                    props['AndroidVersion.ApiLevel'] = '36'
                elif mutation == 'minor':
                    props['AndroidVersion.MinorApiLevel'] = '1'
                elif mutation == 'preview':
                    props['AndroidVersion.CodeName'] = 'Preview'
                elif mutation == 'xml-api':
                    api.text = '37.1'
                elif mutation == 'xml-revision':
                    major.text = '4'
                elif mutation == 'path':
                    local.set('path', 'platforms;android-37')
                elif mutation == 'duplicate':
                    metadata.append(ET.fromstring(ET.tostring(local)))
                elif mutation == 'no-metadata':
                    metadata.remove(local)
                elif mutation == 'empty-revision':
                    props['Pkg.Revision'] = ''
                elif mutation == 'preview-revision':
                    props['Pkg.Revision'] = '3 rc1'
                elif mutation == 'xml-type':
                    details.set('{http://www.w3.org/2001/XMLSchema-instance}type',
                                                  'generic:genericDetailsType')
                else:
                    path = 'build-tools;36.0.0'
                    props, metadata = self.synthetic_installed_package(path, '37.0.0')
                with self.assertRaises(AssertionError):
                    verify_sdk(path, props, metadata)

    def assert_host_emulator_prerequisite(self, workflow):
        build, native = workflow.split("  instrumented:\n", 1)
        self.assertNotIn("apt-get", build)
        self.assertIn("runs-on: ubuntu-24.04", native)
        steps = re.split(r"(?m)^      - ", native)[1:]
        setup = [index for index, step in enumerate(steps)
                 if step.startswith("name: Prepare hosted emulator client library\n")]
        invocation = [index for index, step in enumerate(steps)
                      if "bash tools/build/run-hosted-native-smoke.sh" in step]
        self.assertEqual(len(setup), 1, "Missing/duplicate hosted emulator prerequisite")
        self.assertEqual(len(invocation), 1)
        self.assertLess(setup[0], invocation[0], "Emulator prerequisite must precede native invocation")
        step = steps[setup[0]]
        self.assertRegex(step, r"(?m)^        timeout-minutes: [1-5]$")
        self.assertNotRegex(step, r"(?m)^        (?:if|continue-on-error):")
        header, run = step.split("        run: |\n", 1)
        self.assertNotIn("if:", header)
        self.assertTrue(run.lstrip().startswith("set -euo pipefail\n"))
        receipt, commands = run.split("trap record_prerequisite EXIT\n", 1)
        for contract in ("status=$?", "trap - EXIT", 'exit "$status"',
                         "${binary:Package}", "${Version}", "${db:Status-Status}",
                         "host-libpulse0-receipt.txt", "package_query_exit=",
                         "library_readable=true", "library_readable=false", 'sha256sum "$library"'):
            self.assertIn(contract, receipt)
        self.assertIn("library=/usr/lib/x86_64-linux-gnu/libpulse.so.0", receipt)
        self.assertRegex(commands, r"timeout --signal=TERM --kill-after=15s [1-2]m sudo apt-get "
                                  r"-o APT::Update::Error-Mode=any update")
        self.assertRegex(commands, r"timeout --signal=TERM --kill-after=15s [1-2]m sudo env "
                                  r"DEBIAN_FRONTEND=noninteractive")
        self.assertRegex(commands, r"apt-get install\s+--yes\s+--no-install-recommends\s+libpulse0\s+\\\n"
                                  r"\s+2>&1 \| tee evidence/native/host-libpulse0-install.log")
        self.assertIn("tee evidence/native/host-apt-update.log", commands)
        self.assertIn("dpkg-query -W -f='${db:Status-Status}\\n' libpulse0 | grep -Fx installed", commands)
        self.assertIn('test -r "$library"', commands)
        self.assertLess(commands.index(" update"), commands.index("apt-get install"))
        self.assertLess(commands.index("apt-get install"), commands.index("dpkg-query"))
        self.assertLess(commands.index("dpkg-query"), commands.index('test -r "$library"'))
        for unsafe in ("||", "&&", "set +e", "exit 0", "LD_LIBRARY_PATH", "--allow-", "--force-yes"):
            self.assertNotIn(unsafe, commands)

    def test_host_emulator_prerequisite_is_bounded_observable_and_fail_closed(self):
        self.assert_host_emulator_prerequisite((ROOT / ".github/workflows/android.yml").read_text())

    def test_host_emulator_prerequisite_contract_rejects_synthetic_regressions(self):
        # Pure source controls: never install host packages or invoke an emulator here.
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        setup_match = re.search(r"(?ms)^      - name: Prepare hosted emulator client library\n.*?"
                                r"(?=^      - )", workflow)
        assert setup_match is not None, "Missing hosted emulator prerequisite"
        setup = setup_match.group()
        missing = workflow.replace(setup, "", 1)
        invocation_match = re.search(r"(?ms)^      - name: Run unit and instrumented tests with coverage.*?(?=^      - )", missing)
        assert invocation_match is not None, "Missing native invocation"
        invocation = invocation_match.group()
        for name, mutation in (
            ("missing", missing),
            ("misordered", missing.replace(invocation, invocation + setup, 1)),
            ("fail-open", workflow.replace(setup, setup.replace("          set -euo pipefail\n", "          set -uo pipefail\n", 1), 1)),
            ("conditional", workflow.replace(setup, setup.replace("        run: |", "        if: false\n        run: |"), 1)),
        ):
            with self.subTest(mutation=name):
                with self.assertRaises(AssertionError):
                    self.assert_host_emulator_prerequisite(mutation)


class SyntheticReportTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.directory = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def write_report(self, kind, *, missing=None, child=None, suite_error=None,
                     container_depth=0, container_error=None):
        suite = ET.Element("testsuite", tests=str(len(EXPECTED[kind])), failures="0", errors="0", skipped="0")
        if suite_error:
            suite.set(suite_error, "1")
        for name in sorted(EXPECTED[kind] - ({missing} if missing else set())):
            case = ET.SubElement(suite, "testcase", classname=CLASS[kind], name=name)
            if child:
                ET.SubElement(case, child)
        if kind == "native":
            for name in sorted(CREDENTIAL_CASES - ({missing} if missing else set())):
                ET.SubElement(suite, "testcase", classname=CREDENTIAL_CLASS, name=name)
            for classname, names in ((CONNECTION_CLASS, CONNECTION_CASES), (USAGE_REFRESH_CLASS, USAGE_REFRESH_CASES),
                                     (LIVE_USAGE_CLASS, LIVE_USAGE_CASES), (BANKED_RESET_CLASS, BANKED_RESET_CASES), (HISTORY_CLASS, HISTORY_CASES), (HISTORY_LIFETIME_CLASS, HISTORY_LIFETIME_CASES), (HISTORY_SAMPLING_CLASS, HISTORY_SAMPLING_CASES)):
                for name in sorted(names - ({missing} if missing else set())):
                    ET.SubElement(suite, "testcase", classname=classname, name=name)
        root = suite
        for depth in range(container_depth):
            container = ET.Element("testsuites", failures="0", errors="0", skipped="0")
            if container_error and depth == 0:
                container.set(container_error, "1")
            container.append(root)
            root = container
        path = self.directory / "SYNTHETIC-parser-fixture.xml"
        ET.ElementTree(root).write(path, encoding="unicode")
        return path

    def test_accepts_complete_executed_sets_in_synthetic_reports(self):
        for kind in EXPECTED:
            for depth in (0, 1, 2):
                with self.subTest(kind=kind, container_depth=depth):
                    self.write_report(kind, container_depth=depth)
                    result = verify_reports(self.directory, kind)
                    self.assertEqual(result["testCount"], len(EXPECTED[kind]) +
                                     (len(CREDENTIAL_CASES) + len(CONNECTION_CASES) + len(USAGE_REFRESH_CASES) + len(LIVE_USAGE_CASES) + len(BANKED_RESET_CASES) + len(HISTORY_CASES) + len(HISTORY_LIFETIME_CASES) + len(HISTORY_SAMPLING_CASES) if kind == "native" else 0))

    def test_rejects_missing_tests(self):
        for kind in EXPECTED:
            for depth in (0, 1, 2):
                with self.subTest(kind=kind, container_depth=depth):
                    self.write_report(kind, missing=sorted(EXPECTED[kind])[0], container_depth=depth)
                    with self.assertRaisesRegex(ValueError, "Missing real"):
                        verify_reports(self.directory, kind)

    def test_mandatory_keystore_cases_match_source_and_cannot_be_omitted_or_spoofed(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/credentials/KeystoreCredentialStoreTest.kt").read_text()
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)), CREDENTIAL_CASES)
        for missing in CREDENTIAL_CASES:
            self.write_report("native", missing=missing)
            with self.assertRaisesRegex(ValueError, "Missing real native credential"):
                verify_reports(self.directory, "native")
        path = self.write_report("native")
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            if case.get("classname") == CREDENTIAL_CLASS:
                case.set("classname", CLASS["native"])
        ET.ElementTree(root).write(path, encoding="unicode")
        with self.assertRaisesRegex(ValueError, "Missing real native credential"):
            verify_reports(self.directory, "native")

    def test_mandatory_connection_cases_match_source_and_cannot_be_omitted_or_spoofed(self):
        source = (ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/ConnectionLifecycleTest.kt").read_text()
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)), CONNECTION_CASES)
        for missing in CONNECTION_CASES:
            self.write_report("native", missing=missing)
            with self.assertRaisesRegex(ValueError, "Missing real native connection"):
                verify_reports(self.directory, "native")
        path = self.write_report("native")
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            if case.get("classname") == CONNECTION_CLASS:
                case.set("classname", CLASS["native"])
        ET.ElementTree(root).write(path, encoding="unicode")
        with self.assertRaisesRegex(ValueError, "Missing real native connection"):
            verify_reports(self.directory, "native")

    def test_usage_refresh_native_contract_requires_both_real_lifecycle_cases(self):
        native = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/UsageRefreshLifecycleTest.kt"
        declared = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", native.read_text()))
        self.assertEqual(declared, USAGE_REFRESH_CASES)
        for name in USAGE_REFRESH_CASES:
            with self.subTest(missing=name):
                self.write_report("native", missing=name)
                with self.assertRaisesRegex(ValueError, "Missing real native usage refresh"):
                    verify_reports(self.directory, "native")
        path = self.write_report("native")
        document = ET.parse(path)
        for case in document.iter("testcase"):
            if case.get("classname") == USAGE_REFRESH_CLASS:
                case.set("classname", CONNECTION_CLASS)
        document.write(path, encoding="unicode")
        with self.assertRaisesRegex(ValueError, "Missing real native usage refresh"):
            verify_reports(self.directory, "native")

    def test_live_usage_native_contract_requires_real_activity_cases_without_spoofing(self):
        native = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/LiveUsageScreenTest.kt"
        declared = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", native.read_text()))
        self.assertEqual(declared, LIVE_USAGE_CASES | BANKED_RESET_CASES)
        for name in LIVE_USAGE_CASES:
            with self.subTest(missing=name):
                self.write_report("native", missing=name)
                with self.assertRaisesRegex(ValueError, "Missing real native live usage"):
                    verify_reports(self.directory, "native")
        path = self.write_report("native")
        document = ET.parse(path)
        for case in document.iter("testcase"):
            if case.get("classname") == LIVE_USAGE_CLASS:
                case.set("classname", CONNECTION_CLASS)
        document.write(path, encoding="unicode")
        with self.assertRaisesRegex(ValueError, "Missing real native live usage"):
            verify_reports(self.directory, "native")

    def test_banked_reset_native_cases_cannot_be_omitted_or_spoofed(self):
        self.assertEqual(BANKED_RESET_CLASS, LIVE_USAGE_CLASS)
        for name in BANKED_RESET_CASES:
            with self.subTest(missing=name):
                self.write_report("native", missing=name)
                with self.assertRaisesRegex(ValueError, "Missing real native banked reset"):
                    verify_reports(self.directory, "native")
        path = self.write_report("native")
        document = ET.parse(path)
        for case in document.iter("testcase"):
            if case.get("name") in BANKED_RESET_CASES:
                case.set("classname", CONNECTION_CLASS)
        document.write(path, encoding="unicode")
        with self.assertRaisesRegex(ValueError, "Missing real native banked reset"):
            verify_reports(self.directory, "native")

    def test_history_native_cases_match_source_and_reject_omission_spoofing_and_skips(self):
        native = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/history/HistoryPersistenceTest.kt"
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", native.read_text())), HISTORY_CASES)
        for name in HISTORY_CASES:
            with self.subTest(missing=name):
                self.write_report("native", missing=name)
                with self.assertRaisesRegex(ValueError, "Missing real native history"):
                    verify_reports(self.directory, "native")
        for sabotage in ("wrong-class", "failure", "error", "skipped"):
            with self.subTest(sabotage=sabotage):
                path = self.write_report("native")
                document = ET.parse(path)
                case = next(case for case in document.iter("testcase") if case.get("classname") == HISTORY_CLASS)
                if sabotage == "wrong-class":
                    case.set("classname", CONNECTION_CLASS)
                else:
                    ET.SubElement(case, sabotage)
                document.write(path, encoding="unicode")
                with self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")

    def test_history_lifetime_native_cases_are_mandatory_and_cannot_be_spoofed(self):
        native = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/history/HistoryLifetimeTest.kt"
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", native.read_text())), HISTORY_LIFETIME_CASES)
        for name in HISTORY_LIFETIME_CASES:
            with self.subTest(missing=name):
                self.write_report("native", missing=name)
                with self.assertRaisesRegex(ValueError, "Missing real native history lifetime"):
                    verify_reports(self.directory, "native")
        for sabotage in ("wrong-class", "failure", "error", "skipped"):
            with self.subTest(sabotage=sabotage):
                document = ET.parse(self.write_report("native"))
                case = next(c for c in document.iter("testcase") if c.get("classname") == HISTORY_LIFETIME_CLASS)
                if sabotage == "wrong-class":
                    case.set("classname", HISTORY_CLASS)
                else:
                    ET.SubElement(case, sabotage)
                document.write(self.directory / "SYNTHETIC-parser-fixture.xml", encoding="unicode")
                with self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")

    def test_history_sampling_native_cases_are_mandatory_and_preserve_all_prior_cases(self):
        native = ROOT / "app/src/androidTest/java/io/github/leugenea/codexbarmobile/history/UsageHistoryIntegrationTest.kt"
        self.assertEqual(set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", native.read_text())), HISTORY_SAMPLING_CASES)
        self.assertEqual(sum(map(len, (EXPECTED["native"], CREDENTIAL_CASES, CONNECTION_CASES,
                                     USAGE_REFRESH_CASES, LIVE_USAGE_CASES, BANKED_RESET_CASES,
                                     HISTORY_CASES, HISTORY_LIFETIME_CASES))), 72)
        for name in HISTORY_SAMPLING_CASES:
            self.write_report("native", missing=name)
            with self.assertRaisesRegex(ValueError, "Missing real native history sampling"):
                verify_reports(self.directory, "native")
            for sabotage in ("wrong-class", "failure", "error", "skipped"):
                document = ET.parse(self.write_report("native"))
                case = next(c for c in document.iter("testcase") if c.get("name") == name)
                if sabotage == "wrong-class":
                    case.set("classname", HISTORY_CLASS)
                else:
                    ET.SubElement(case, sabotage)
                document.write(self.directory / "SYNTHETIC-parser-fixture.xml", encoding="unicode")
                with self.assertRaises(ValueError):
                    verify_reports(self.directory, "native")

    def test_rejects_failures_errors_and_skips(self):
        for kind in EXPECTED:
            for depth in (0, 1, 2):
                for child in ("failure", "error", "skipped"):
                    with self.subTest(kind=kind, container_depth=depth, child=child):
                        self.write_report(kind, child=child, container_depth=depth)
                        with self.assertRaisesRegex(ValueError, "failed/errored/skipped"):
                            verify_reports(self.directory, kind)
                for error in ("failures", "errors", "skipped"):
                    with self.subTest(kind=kind, container_depth=depth, suite_error=error):
                        self.write_report(kind, suite_error=error, container_depth=depth)
                        with self.assertRaisesRegex(ValueError, "nonzero"):
                            verify_reports(self.directory, kind)

    def test_rejects_container_failures_errors_and_skips(self):
        for kind in EXPECTED:
            for depth in (1, 2):
                for error in ("failures", "errors", "skipped"):
                    with self.subTest(kind=kind, container_depth=depth, container_error=error):
                        self.write_report(kind, container_depth=depth, container_error=error)
                        with self.assertRaisesRegex(ValueError, f"nonzero {error}"):
                            verify_reports(self.directory, kind)

    def test_rejects_absent_or_non_junit_reports(self):
        with self.assertRaisesRegex(ValueError, "No native JUnit XML"):
            verify_reports(self.directory, "native")
        (self.directory / "not-junit.xml").write_text("<unrelated/>")
        with self.assertRaisesRegex(ValueError, "Missing real"):
            verify_reports(self.directory, "native")

    def synthetic_manifest(self, permissions=APP_PERMISSIONS):
        declarations = (f'<permission android:name="{RECEIVER_PERMISSION}" android:protectionLevel="signature"/>'
                        if permissions == APP_PERMISSIONS else '')
        return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                'xmlns:tools="http://schemas.android.com/tools">'
                + ''.join(f'<uses-permission android:name="{name}"/>' for name in sorted(permissions))
                + declarations + '<application android:usesCleartextTraffic="false">'
                '<activity android:name=".MainActivity" android:launchMode="singleTask"/>'
                '</application></manifest>')

    def synthetic_apk_activity(self, name=PACKAGE + ".MainActivity"):
        return ('    E: activity (line=5)\n'
                f'      A: android:name(0x01010003)="{name}"\n'
                '      A: android:launchMode(0x0101001d)=(type 0x10)0x2\n')

    def test_manifest_launcher_and_process_policy_rejects_synthetic_regressions(self):
        manifest = self.directory / "SYNTHETIC-AndroidManifest.xml"
        for permissions in (SOURCE_PERMISSIONS, APP_PERMISSIONS):
            valid = self.synthetic_manifest(permissions)
            activity = '<activity android:name=".MainActivity" android:launchMode="singleTask"/>'
            for name in ('.MainActivity', 'MainActivity', PACKAGE + '.MainActivity'):
                manifest.write_text(valid.replace('.MainActivity', name))
                self.assertEqual(check_manifest(manifest, permissions)['mainActivityLaunchMode'], 'singleTask')
            mutations = [
                (valid.replace(' android:launchMode="singleTask"', ''), 'singleTask'),
                (valid.replace(activity, ''), 'singleTask'),
                (valid.replace(activity, activity + activity), 'singleTask'),
                (valid.replace('.MainActivity', '.OtherActivity'), 'singleTask'),
            ]
            for mode in ('standard', 'singleTop', 'singleInstance', 'singleInstancePerTask'):
                mutations.append((valid.replace('singleTask', mode), 'singleTask'))
            for element in ('application', 'activity'):
                mutations.append((valid.replace('<' + element + ' ',
                                                '<' + element + ' android:process=":other" '), 'single process'))
            mutations.append((valid.replace('</application>',
                              '<service android:name=".Other" android:process=":other"/></application>'), 'single process'))
            for text, error in mutations:
                with self.subTest(permissions=permissions, mutation=text):
                    manifest.write_text(text)
                    with self.assertRaisesRegex(ValueError, error):
                        check_manifest(manifest, permissions)

    def test_apk_launcher_and_process_policy_rejects_synthetic_regressions(self):
        permissions = f'package: {PACKAGE}\n' + ''.join(
            f"uses-permission: name='{name}'\n" for name in sorted(APP_PERMISSIONS))
        prefix = (f'  E: permission (line=3)\n'
                  f'    A: android:name(0x01010003)="{RECEIVER_PERMISSION}"\n'
                  '    A: android:protectionLevel(0x01010009)=(type 0x11)0x2\n'
                  '  E: application (line=4)\n'
                  '    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0\n')
        activity = self.synthetic_apk_activity()
        for name in ('.MainActivity', 'MainActivity', PACKAGE + '.MainActivity'):
            self.assertEqual(check_apk_dump(permissions, prefix + self.synthetic_apk_activity(name)), sorted(APP_PERMISSIONS))
        for tree, error in (
            (prefix, 'singleTask'),
            (prefix + activity + activity, 'singleTask'),
            (prefix + activity.replace('MainActivity', 'OtherActivity'), 'singleTask'),
            (prefix + activity.replace('      A: android:launchMode(0x0101001d)=(type 0x10)0x2\n', ''), 'singleTask'),
            (prefix + activity.replace('0x10)0x2', '0x10)0x0'), 'singleTask'),
            (prefix + activity.replace('0x10)0x2', '0x10)0x1'), 'singleTask'),
            (prefix + activity.replace('0x10)0x2', '0x10)0x3'), 'singleTask'),
            (prefix + '    A: android:process(0x01010011)=":other"\n' + activity, 'single process'),
            (prefix + activity + '      A: android:process(0x01010011)=":other"\n', 'single process'),
            (prefix + activity + '    E: service (line=6)\n      A: android:process(0x01010011)=":other"\n', 'single process'),
        ):
            with self.subTest(tree=tree), self.assertRaisesRegex(ValueError, error):
                check_apk_dump(permissions, tree)

    def write_synthetic_manifest_tree(self):
        files = {
            'app/src/main/AndroidManifest.xml': self.synthetic_manifest(SOURCE_PERMISSIONS),
            'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml': self.synthetic_manifest(),
            'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml': self.synthetic_manifest(),
            'tools/build/verify_manifests.py': (ROOT / 'tools/build/verify_manifests.py').read_text(),
        }
        for name, text in files.items():
            path = self.directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

    def run_manifest_cli(self):
        return subprocess.run([sys.executable, '-B', 'tools/build/verify_manifests.py'],
                              cwd=self.directory, text=True, capture_output=True, timeout=5)

    def test_normal_manifest_cli_requires_and_checks_release(self):
        self.write_synthetic_manifest_tree()
        self.assertEqual(self.run_manifest_cli().returncode, 0)
        receipt = json.loads((self.directory / 'evidence/verified-manifests.json').read_text())
        self.assertEqual(len(receipt), 3)
        self.assertIn('/release/processReleaseManifest/', receipt[2]['manifest'])
        release = self.directory / 'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'
        valid = release.read_text()
        for text, error in (
            (valid.replace('<uses-permission android:name="android.permission.INTERNET"/>', ''), 'allowlist'),
            (valid.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="true"'), 'Cleartext'),
            (valid.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="false" android:networkSecurityConfig="@xml/unsafe"'), 'Cleartext'),
            (valid.replace('protectionLevel="signature"', 'protectionLevel="normal"'), 'signature-only'),
            (valid.replace('launchMode="singleTask"', 'launchMode="standard"'), 'singleTask'),
            (valid.replace('launchMode="singleTask"', 'launchMode="singleTask" android:process=":other"'), 'single process'),
        ):
            with self.subTest(release_mutation=error):
                release.write_text(text)
                result = self.run_manifest_cli()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(error, result.stderr)
        release.unlink()
        self.assertNotEqual(self.run_manifest_cli().returncode, 0)

    def test_variant_overlay_reviewer_bypass_is_rejected_by_source_and_cli(self):
        self.write_synthetic_manifest_tree()
        for variant in ('release', 'debug', 'freeRelease'):
            path = self.directory / f'app/src/{variant}/AndroidManifest.xml'
            path.parent.mkdir(parents=True, exist_ok=True)
            # The reviewer's INTERNET-removal bypass; even a benign overlay is forbidden.
            for content in ('<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>', ''):
                with self.subTest(variant=variant, content=content):
                    path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                                    'xmlns:tools="http://schemas.android.com/tools">' + content + '</manifest>')
                    with self.assertRaisesRegex(ValueError, 'overlays'):
                        check_source_manifests(self.directory)
                    result = self.run_manifest_cli()
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('overlays', result.stderr)
            path.unlink()

    def test_protected_source_merge_directives_fail_closed(self):
        self.write_synthetic_manifest_tree()
        main = self.directory / 'app/src/main/AndroidManifest.xml'
        valid = main.read_text()
        for element, directive in (
            ('uses-permission', 'tools:node="remove"'),
            ('uses-permission', 'tools:node="replace"'),
            ('uses-permission', 'tools:node="removeAll"'),
            ('uses-permission', 'tools:remove="android:name"'),
            ('uses-permission', 'tools:replace="android:name"'),
            ('application', 'tools:node="replace"'),
            ('application', 'tools:remove="android:label, android:usesCleartextTraffic"'),
            ('application', 'tools:replace="android:usesCleartextTraffic"'),
            ('application', 'tools:remove="android:networkSecurityConfig"'),
            ('application', 'android:networkSecurityConfig="@xml/unsafe"'),
            ('activity', 'tools:remove="android:launchMode"'),
            ('activity', 'tools:replace="android:launchMode"'),
            ('application', 'tools:remove="android:process"'),
            ('activity', 'tools:replace="android:process"'),
        ):
            with self.subTest(element=element, directive=directive):
                main.write_text(valid.replace('<' + element + ' ', '<' + element + ' ' + directive + ' ', 1))
                with self.assertRaisesRegex(ValueError, 'directive|override'):
                    check_source_manifests(self.directory)
                self.assertNotEqual(self.run_manifest_cli().returncode, 0)

    def run_synthetic_installed_observation(self, mutation='', graph_exit=0):
        # Execute the shipped post-graph shell against explicitly synthetic SDK tools.
        # Start uninstalled, reproducing AGP cleanup; never invoke a real device/SDK.
        self.write_synthetic_manifest_tree()
        sdk = self.directory / 'SYNTHETIC-sdk'
        apk = self.directory / 'app/build/outputs/apk/debug/app-debug.apk'
        apk.parent.mkdir(parents=True, exist_ok=True)
        apk.write_bytes(b'SYNTHETIC APK bytes, not an Android artifact')
        evidence = self.directory / 'evidence/native'
        evidence.mkdir(parents=True, exist_ok=True)
        (self.directory / 'events.txt').write_text('')
        (self.directory / 'installed').unlink(missing_ok=True)
        permissions = f'package: {PACKAGE}\n' + ''.join(
            f"uses-permission: name='{name}'\n" for name in sorted(APP_PERMISSIONS))
        tree = ('  E: permission (line=1)\n'
                f'    A: android:name(0x01010003)="{RECEIVER_PERMISSION}"\n'
                '    A: android:protectionLevel(0x01010009)=(type 0x11)0x2\n'
                '  E: application (line=2)\n    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0\n'
                + self.synthetic_apk_activity())
        dump = f'  Package [{PACKAGE}] (synthetic):\n    requested permissions:\n' + ''.join(
            f'      {name}\n' for name in sorted(APP_PERMISSIONS)) + '    install permissions:\n'
        if mutation == 'extra-installed-permission':
            dump = dump.replace('    install permissions:', '      android.permission.CAMERA\n    install permissions:')
        if mutation == 'unsafe-apk':
            tree = tree.replace('0x12)0x0', '0x12)0xffffffff')
        for name, text in (('permissions.txt', permissions), ('tree.txt', tree), ('dump.txt', dump)):
            (self.directory / name).write_text(text)
        common = (f'#!{sys.executable}\nimport os, sys\nfrom pathlib import Path\n'
                  'args = sys.argv[1:]\nmutation = os.environ["SYNTHETIC_MUTATION"]\n'
                  'with open("events.txt", "a") as log: log.write(" ".join(args) + "\\n")\n')
        tools = {
            'build-tools/36.0.0/aapt': common + '''
if args[1] == 'permissions':
    print(Path('permissions.txt').read_text(), end='')
else:
    print(Path('tree.txt').read_text(), end='')
    if mutation == 'mutated-apk':
        with open(args[2], 'ab') as apk: apk.write(b'mutated after inspection')
''',
            'platform-tools/adb': common + f'''
args = args[2:]  # -s serial
installed = Path('installed')
if args[0] == 'install':
    if mutation == 'install-failure': sys.exit(7)
    installed.write_text('synthetic installed state')
    print('no success receipt' if mutation == 'install-no-success' else 'Success')
elif args[:3] == ['shell', 'pm', 'path']:
    if installed.exists() and mutation != 'missing-package':
        package = 'wrong.package' if mutation == 'wrong-package' else '{PACKAGE}'
        print(f'package:/data/app/~~synthetic/{{package}}-synthetic/base.apk')
    if mutation == 'path-failure': sys.exit(8)
elif args[:3] == ['shell', 'dumpsys', 'package']:
    print(Path('dump.txt').read_text() if installed.exists() else 'Unable to find package: {PACKAGE}')
    if mutation == 'dump-failure': sys.exit(9)
elif args[0] == 'uninstall':
    installed.unlink(missing_ok=True)
    print('Success')
    if mutation == 'uninstall-failure': sys.exit(10)
else:
    sys.exit(99)
''',
            'bin/python3': common + f'os.execv({sys.executable!r}, [{sys.executable!r}] + args)\n',
        }
        for name, text in tools.items():
            path = sdk / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            path.chmod(0o755)
        script = (ROOT / 'tools/build/run-hosted-native-smoke.sh').read_text()
        block = '# Inspect and bind' + script.split('# Inspect and bind', 1)[1]
        shell = ('set -uo pipefail\nset +e\n'
                 'adb="$ANDROID_HOME/platform-tools/adb"\n'
                 f'test_status={graph_exit}\nmanifest_status=0\nnative_report_status=0\njvm_report_status=0\n'
                 + block)
        environment = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SERIAL='SYNTHETIC-serial',
                           SYNTHETIC_MUTATION=mutation, PATH=str(sdk / 'bin') + os.pathsep + os.environ['PATH'])
        result = subprocess.run(['bash', '-c', shell], cwd=self.directory, env=environment,
                                text=True, capture_output=True, timeout=10)
        events = (self.directory / 'events.txt').read_text().splitlines()
        statuses = dict(line.split('=', 1) for line in (evidence / 'installed-observation-status.txt').read_text().splitlines())
        self.assertFalse((self.directory / 'installed').exists(), 'Cleanup must remove synthetic installed state')
        return result, events, statuses

    def test_post_agp_uninstall_reinstalls_verified_apk_before_observing_once(self):
        script = (ROOT / 'tools/build/run-hosted-native-smoke.sh').read_text()
        self.assertLess(script.index('coverage_attempt=2\ndone'), script.index('# Inspect and bind'))
        self.assertEqual(script.count('"$adb" -s "$ANDROID_SERIAL" install -r "$apk"'), 1)
        result, events, statuses = self.run_synthetic_installed_observation()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(value == '0' for value in statuses.values()), statuses)
        self.assertEqual([line.split()[0] for line in events],
                         ['dump', 'dump', 'tools/build/verify_manifests.py', '-s', '-s',
                          'tools/build/verify_manifests.py', '-s', 'tools/build/verify_manifests.py', '-s'])
        adb_events = [line.split()[2:] for line in events if line.startswith('-s ')]
        self.assertEqual(adb_events, [
            ['install', '-r', 'app/build/outputs/apk/debug/app-debug.apk'],
            ['shell', 'pm', 'path', PACKAGE], ['shell', 'dumpsys', 'package', PACKAGE], ['uninstall', PACKAGE]])
        digest = (self.directory / 'evidence/native/inspected-apk.sha256').read_text().split()[0]
        apk = self.directory / 'app/build/outputs/apk/debug/app-debug.apk'
        self.assertEqual(digest, hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertEqual(events[5], 'tools/build/verify_manifests.py --installed-path evidence/native/installed-package-path.txt')
        self.assertIn('--installed-package evidence/native/installed-package.txt', events[7])

    def test_installed_observation_fails_closed_and_preserves_graph_error(self):
        for mutation, failed_status in (
            ('mutated-apk', 'install_hash_exit'), ('unsafe-apk', 'apk_policy_exit'),
            ('install-failure', 'install_exit'), ('install-no-success', 'install_exit'),
            ('missing-package', 'installed_path_policy_exit'), ('wrong-package', 'installed_path_policy_exit'),
            ('path-failure', 'installed_path_exit'), ('dump-failure', 'installed_dump_exit'),
            ('extra-installed-permission', 'permission_exit'), ('uninstall-failure', 'uninstall_exit'),
        ):
            with self.subTest(mutation=mutation):
                result, events, statuses = self.run_synthetic_installed_observation(mutation)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotEqual(statuses[failed_status], '0')
                self.assertEqual(sum(' uninstall ' in line for line in events), 1)
                if mutation in {'mutated-apk', 'unsafe-apk'}:
                    self.assertFalse(any(' install ' in line for line in events))
                if mutation in {'missing-package', 'wrong-package', 'path-failure'}:
                    self.assertFalse(any(' dumpsys ' in line for line in events))
        result, _, _ = self.run_synthetic_installed_observation('missing-package', graph_exit=23)
        self.assertEqual(result.returncode, 23, 'Original graph failure takes precedence')

    def test_installed_path_requires_nonempty_exact_package(self):
        valid = f'package:/data/app/~~synthetic/{PACKAGE}-synthetic/base.apk\n'
        check_installed_path(valid)
        check_installed_path(valid.replace('\n', '\r\n'))
        for text in ('', '\n', 'Unable to find package: ' + PACKAGE, valid.replace(PACKAGE, 'wrong.package'),
                     valid.replace('package:', ''), valid + 'unrelated\n'):
            with self.subTest(path=text), self.assertRaisesRegex(ValueError, 'installed package path'):
                check_installed_path(text)

    def test_source_and_merged_manifest_parser_fails_closed(self):
        manifest = self.directory / "SYNTHETIC-AndroidManifest.xml"
        prefix = '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
        application = ('<application android:usesCleartextTraffic="false">'
                       '<activity android:name=".MainActivity" android:launchMode="singleTask"/>'
                       '</application>')
        for expected in (SOURCE_PERMISSIONS, APP_PERMISSIONS):
            for tag in ("uses-permission", "uses-permission-sdk-23"):
                permissions = ''.join(f'<{tag} android:name="{name}"/>' for name in sorted(expected))
                declaration = (f'<permission android:name="{RECEIVER_PERMISSION}" android:protectionLevel="signature"/>'
                               if expected == APP_PERMISSIONS else '')
                valid = prefix + permissions + declaration + application + '</manifest>'
                manifest.write_text(valid)
                self.assertEqual(check_manifest(manifest, expected)["requestedPermissions"], sorted(expected))
                mutations = [
                    (valid.replace(application, '<uses-permission android:name="android.permission.CAMERA"/>' + application), "allowlist"),
                    (valid.replace(permissions, ''), "allowlist"),
                    (valid.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="true"'), "Cleartext"),
                    (valid.replace('android:usesCleartextTraffic="false"', ''), "Cleartext"),
                    (valid.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="false" android:networkSecurityConfig="@xml/unsafe"'), "Cleartext"),
                    (valid.replace('android:name="android.permission.INTERNET"', 'android:name="android.permission.INTERNET" android:maxSdkVersion="27"'), "Conditional"),
                ]
                if declaration:
                    mutations.extend([
                        (valid.replace(declaration, ""), "signature-only"),
                        (valid.replace('protectionLevel="signature"', 'protectionLevel="normal"'), "signature-only"),
                        (valid.replace('protectionLevel="signature"', 'protectionLevel="dangerous"'), "signature-only"),
                    ])
                for text, error in mutations:
                    with self.subTest(expected=expected, tag=tag, mutation=text):
                        manifest.write_text(text)
                        with self.assertRaisesRegex(ValueError, error):
                            check_manifest(manifest, expected)
        manifest.write_text("<unrelated/>")
        with self.assertRaisesRegex(ValueError, "Not an Android manifest"):
            check_manifest(manifest)
        with self.assertRaises(FileNotFoundError):
            check_manifest(self.directory / "missing.xml")

    def test_apk_dump_requires_exact_permissions_and_cleartext_off(self):
        permissions = f'package: {PACKAGE}\n' + ''.join(
            f"uses-permission: name='{name}'\n" for name in sorted(APP_PERMISSIONS))
        declaration = (f'  E: permission (line=3)\n'
                       f'    A: android:name(0x01010003)="{RECEIVER_PERMISSION}" (Raw: "{RECEIVER_PERMISSION}")\n'
                       '    A: android:protectionLevel(0x01010009)=(type 0x11)0x2\n')
        xmltree = (declaration + '  E: application (line=4)\n'
                   '    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0\n'
                   + self.synthetic_apk_activity())
        self.assertEqual(check_apk_dump(permissions, xmltree), sorted(APP_PERMISSIONS))
        for bad_permissions, bad_tree, error in (
                (permissions + "uses-permission: name='android.permission.CAMERA'\n", xmltree, "allowlist"),
                (permissions.replace("uses-permission: name='android.permission.INTERNET'\n", ''), xmltree, "allowlist"),
                (permissions.replace("INTERNET'", "INTERNET' maxSdkVersion='27'"), xmltree, "conditional"),
                (permissions.replace(PACKAGE, 'synthetic.wrong.package'), xmltree, "identity"),
                (permissions, xmltree.replace('0x0\n', '0xffffffff\n'), "cleartext"),
                (permissions, xmltree.replace(declaration, ''), "declaration"),
                (permissions, xmltree.replace('0x11)0x2', '0x11)0x0'), "signature-only"),
                (permissions, xmltree.replace('0x11)0x2', '0x11)0x1'), "signature-only"),
                (permissions, declaration, "cleartext"),
                (permissions, xmltree + '    A: android:networkSecurityConfig(0x01010527)=@0x7f010000\n', "cleartext")):
            with self.subTest(permissions=bad_permissions, tree=bad_tree):
                with self.assertRaisesRegex(ValueError, error):
                    check_apk_dump(bad_permissions, bad_tree)

    def test_installed_dump_requires_exact_requested_permissions(self):
        text = f'Packages:\n  Package [{PACKAGE}] (synthetic):\n    flags=[ DEBUGGABLE HAS_CODE ]\n    requested permissions:\n' + ''.join(
            f'      {name}\n' for name in sorted(APP_PERMISSIONS)) + '    install permissions:\n      android.permission.INTERNET: granted=true\n'
        self.assertEqual(check_installed_dump(text), sorted(APP_PERMISSIONS))
        for bad, error in (
                ('', "identity"),
                ('Unable to find package: ' + PACKAGE, "identity"),
                (text.replace('    install permissions:', '      android.permission.CAMERA\n    install permissions:'), "allowlist"),
                (text.replace('      android.permission.INTERNET\n', ''), "allowlist"),
                (text.replace(PACKAGE, 'synthetic.wrong.package'), "identity"),
                (text.replace('requested permissions:', 'unrelated section:'), "requested permissions"),
                (text.replace('    install permissions:', '    requested permissions:'), "requested permissions")):
            with self.subTest(text=bad), self.assertRaisesRegex(ValueError, error):
                check_installed_dump(bad)

    def test_permission_allowlist_rejects_unexpected_and_missing_permissions(self):
        self.assertEqual(check_permissions(APP_PERMISSIONS), sorted(APP_PERMISSIONS))
        for actual in (APP_PERMISSIONS | {'android.permission.CAMERA'},
                       APP_PERMISSIONS - {'android.permission.INTERNET'}, set()):
            with self.assertRaisesRegex(ValueError, 'allowlist'):
                check_permissions(actual)

if __name__ == "__main__":
    unittest.main()
