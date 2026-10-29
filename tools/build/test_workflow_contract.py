"""Cheap build source/workflow contracts and explicitly synthetic report-parser tests."""
from verify_test_reports import DASHBOARD_JVM, DASHBOARD_NATIVE
from verify_test_reports import CURRENT_HISTORY_JVM, CURRENT_HISTORY_NATIVE
from verify_test_reports import HISTORY_EXPORT_JVM, HISTORY_EXPORT_NATIVE
from verify_test_reports import HISTORY_IMPORT_JVM, HISTORY_IMPORT_NATIVE, REMAINING_TIME_JVM
from verify_test_reports import BACKGROUND_REFRESH_JVM
from verify_test_reports import PERIODIC_REFRESH_JVM, PERIODIC_REFRESH_NATIVE
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
from work_manifest_fixtures import components_apk, components_xml

from verify_manifests import (APP_PERMISSIONS, DEBUG_PACKAGE as PACKAGE, MAIN_ACTIVITY, RECEIVER_PERMISSION, SOURCE_PERMISSIONS, check_apk_dump,
                              check_installed_dump, check_installed_path, check_manifest, check_permissions,
                              check_source_manifests)
from verify_test_reports import HISTORY_ISOLATION_CASES, HISTORY_ISOLATION_CLASS, PHONE_TIME_CASES
from verify_test_reports import ACCOUNT_REMOVAL_CASES, ACCOUNT_REMOVAL_CLASS
from verify_test_reports import DEVICE_CODE_COPY_CASES, DEVICE_CODE_COPY_CLASS
from verify_test_reports import ACCOUNT_NAME_CASES, ACCOUNT_NAME_CLASS
from verify_test_reports import HISTORY_ASSEMBLED_CASES, HISTORY_ASSEMBLED_CLASS
from verify_test_reports import (HISTORY_AUTHORITY_CASES, HISTORY_AUTHORITY_CLASS, HISTORY_NAVIGATION_CASES, HISTORY_NAVIGATION_CLASS, HISTORY_CHART_CASES, HISTORY_CHART_CLASS, HISTORY_TEXT_CASES, HISTORY_TEXT_CLASS, HISTORY_SAMPLING_CASES, HISTORY_SAMPLING_CLASS, HISTORY_LIFETIME_CASES, HISTORY_LIFETIME_CLASS, HISTORY_CASES, HISTORY_CLASS, BANKED_RESET_CASES, BANKED_RESET_CLASS, CLASS, CONNECTION_CASES, CONNECTION_CLASS, CREDENTIAL_CASES,
                                 CREDENTIAL_CLASS, EXPECTED, LIVE_USAGE_CASES, LIVE_USAGE_CLASS,
                                 USAGE_REFRESH_CASES, USAGE_REFRESH_CLASS, verify_reports)

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("RUNNER_TEMP", os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir())))


class WorkflowContracts(unittest.TestCase):
    def android_build_steps(self, workflow, job_name="build"):
        # Existing workflow contracts parse named step boundaries with stdlib only.
        # Android's isolated metrics venv deliberately has no YAML dependency.
        job = re.search(r"(?ms)^  " + re.escape(job_name) + r":\n(.*?)(?=^  \w+:\n|\Z)", workflow)
        self.assertIsNotNone(job)
        steps = []
        for block in re.split(r"(?m)^      - name: ", job.group(1))[1:]:
            name, _, body = block.partition("\n")
            fields = dict(re.findall(r"(?m)^        ([\w-]+): ([^\n]+)$", body))
            if fields.get("run") == "|":
                run = body.split("        run: |\n", 1)[1]
                fields["run"] = "\n".join(line[10:] for line in run.splitlines() if line.startswith("          "))
            steps.append(dict(fields, name=name))
        return steps

    def assert_android_offline_parser_environment(self, workflow):
        steps = self.android_build_steps(workflow)
        names = [step["name"] for step in steps]
        prepare_name = "Prepare hash-pinned offline parser environment"
        discovery_name = "Validate offline build and workflow contracts"
        for name in (prepare_name, discovery_name):
            self.assertEqual(names.count(name), 1)
        initialize = names.index("Initialize build diagnostics")
        prepare = names.index(prepare_name)
        discovery = names.index(discovery_name)
        self.assertLess(initialize, prepare)
        self.assertLess(prepare, discovery)
        self.assertLess(discovery, names.index("Set up Temurin JDK 17"))
        venv = "$RUNNER_TEMP/android-build-contract-venv"
        python = f'"{venv}/bin/python"'
        setup = steps[prepare]["run"].replace("\\\n", " ")
        self.assertIn('python3 --version &&', setup)
        self.assertIn(f'python3 -m venv "{venv}" &&', setup)
        self.assertIn(f'timeout --signal=TERM --kill-after=10s 3m {python} -m pip install', setup)
        for flag in ("--require-hashes", "--no-deps", "--no-cache-dir", "--disable-pip-version-check", "--timeout 30", "--retries 1"):
            self.assertIn(flag, setup)
        self.assertEqual(re.findall(r"-r\s+(\S+)", setup),
                         [".github/requirements-metrics.txt", "tools/research/requirements.txt", ".github/requirements-captures.txt"])
        consumer = steps[discovery]["run"].replace("\\\n", " ")
        self.assertIn(f"{python} -B -m unittest discover -s tools/build -p 'test_*.py' -v", consumer)
        self.assertNotIn("-m pip", consumer)
        self.assertNotRegex(consumer, r"\bpython3\s+-B\s+-m unittest")
        for step, stem, filename in ((steps[prepare], "setup", "offline-parser-setup.log"),
                                     (steps[discovery], "contracts", "offline-build-contracts.log")):
            self.assertEqual(step.get("timeout-minutes"), "5")
            self.assertNotIn("if", step)
            self.assertNotIn("continue-on-error", step)
            script = step["run"]
            self.assertIn("set -euo pipefail", script)
            self.assertIn("set +e", script)
            self.assertIn(f'2>&1 | tee evidence/{filename}\n{stem}_status=("${{PIPESTATUS[@]}}")\nset -e', script)
            self.assertIn(f'if (( {stem}_status[0] != 0 )); then exit "${{{stem}_status[0]}}"; fi', script)
            self.assertIn(f"(( {stem}_status[1] == 0 ))", script)
            self.assertNotIn("|| true", script)
        upload = next(step for step in steps if step["name"] == "Upload build logs and outputs (all outcomes)")
        self.assertEqual(upload.get("if"), "always()")
        self.assertIn("            evidence/", workflow)

    def test_android_offline_discovery_uses_prepared_hash_pinned_isolated_parser_python(self):
        self.assert_android_offline_parser_environment((ROOT / ".github/workflows/android.yml").read_text())

    def test_native_capture_consumer_uses_its_prepared_hash_pinned_python(self):
        steps = self.android_build_steps((ROOT / ".github/workflows/android.yml").read_text(), "instrumented")
        prepare = next(i for i, step in enumerate(steps) if step["name"] == "Prepare native release parser environment")
        collect = next(i for i, step in enumerate(steps) if step["name"] == "Record native task outcomes (all outcomes)")
        self.assertLess(prepare, collect)
        setup = steps[prepare]["run"]
        python = '"$RUNNER_TEMP/native-release-venv/bin/python"'
        self.assertIn('python3 -m venv "$RUNNER_TEMP/native-release-venv"', setup)
        self.assertIn(python + " -m pip install", setup)
        self.assertIn("--require-hashes --no-deps", setup)
        self.assertIn("-r .github/requirements-captures.txt", setup)
        self.assertNotIn("if", steps[prepare])
        self.assertNotIn("continue-on-error", steps[prepare])
        self.assertEqual(steps[collect]["if"], "always()")
        self.assertIn(python + " tools/build/verify_history_text_captures.py", steps[collect]["run"])

    def assert_repository_gate_tools(self, workflow, entrypoint):
        self.assertTrue(workflow.startswith("name: Repository policy\n"))
        self.assertNotRegex(workflow, r"\b[Mm][0-5]\b|[Mm][0-5][_-]|[Mm][0-5][A-Z]")
        self.assertIn("run: bash tools/check.sh", workflow)
        self.assertIn("--require-hashes --no-deps", workflow)
        for requirements in (".github/requirements-policy.txt", "tools/research/requirements.txt",
                             ".github/requirements-metrics.txt", ".github/requirements-captures.txt"):
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

        # Cross-workflow pin/permission policy is exercised by tools/policy;
        # keep this stdlib suite usable in the unchanged Android job.

    def test_native_script_rejects_nonhosted_execution_before_setup(self):
        environment = dict(os.environ, GITHUB_ACTIONS="false")
        result = subprocess.run(["bash", str(ROOT / "tools/build/run-hosted-native-smoke.sh")],
                                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stderr.strip(), "Hosted runner only")

    def test_native_job_is_narrow_strict_read_only_and_observable(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        native = workflow.split("  instrumented:\n", 1)[1].split("  result:\n", 1)[0]
        self.assertIn("name: Instrumented tests and coverage (API 36 emulator)", native)
        self.assertNotRegex(native, r"(?m)^    needs:")
        self.assertIn("runs-on: ubuntu-24.04", native)
        self.assertRegex(native, r"(?m)^    timeout-minutes: 60$")
        step = native.split("      - name: Run unit and instrumented tests with coverage (API 36)\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("timeout-minutes: 45", step)
        self.assertIn("timeout --signal=TERM --kill-after=30s 42m bash tools/build/run-hosted-native-smoke.sh", step)
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
                         "shell dumpsys package io.github.leugenea.codexbarmobile.dev",
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

    def assert_parallel_android_gates(self, workflow):
        jobs = dict(re.findall(r"(?ms)^  (\w+):\n(.*?)(?=^  \w+:\n|\Z)", workflow.split("jobs:\n", 1)[1]))
        self.assertEqual(set(jobs), {"build", "instrumented", "result"})
        for job in ("build", "instrumented"):
            self.assertNotRegex(jobs[job], r"(?m)^    (?:needs|if):")
        result = jobs["result"]
        self.assertIn("    if: always()\n", result)
        self.assertIn("    needs: [build, instrumented]\n", result)
        self.assertIn('[[ "$BUILD_RESULT" == success && "$INSTRUMENTED_RESULT" == success ]]', result)
        self.assertIn("BUILD_RESULT: ${{ needs.build.result }}", result)
        self.assertIn("INSTRUMENTED_RESULT: ${{ needs.instrumented.result }}", result)

    def test_android_gates_run_in_parallel_but_result_requires_both(self):
        self.assert_parallel_android_gates((ROOT / ".github/workflows/android.yml").read_text())

    def assert_full_evidence_filtered_console(self, workflow, script):
        strict = workflow.split("- name: Build, lint and unit tests with strict verification", 1)[1].split("- name: Upload", 1)[0]
        graph = script.split('  set +e\n  timeout --signal=TERM', 1)[1].split('  python3 tools/build/coverage_gate.py phases', 1)[0]
        for block, evidence in (
            (strict, 'tee evidence/strict.log'),
            (graph, 'tee evidence/native/strict-connected.log "$attempt_dir/strict-connected.log"'),
        ):
            self.assertIn(evidence + " | bash tools/build/filter-gradle-console.sh", block)
            self.assertIn('--info', block)
            self.assertIn('graph_status=("${PIPESTATUS[@]}")', block)
            self.assertLess(block.index(evidence), block.index('bash tools/build/filter-gradle-console.sh'))
            self.assertLess(block.index('graph_status=('), block.index('set -e'))
            self.assertIn('graph_status[1]', block)
            self.assertIn('graph_status[2]', block)
        self.assertIn('if (( graph_status[0] != 0 )); then exit "${graph_status[0]}"; fi', strict)
        self.assertIn('test_status=${graph_status[0]}', graph)
        self.assertIn('if (( test_status != 0 )); then exit "$test_status"; fi', graph)

    def test_full_info_evidence_precedes_console_filter_and_graph_status_is_preserved(self):
        self.assert_full_evidence_filtered_console(
            (ROOT / ".github/workflows/android.yml").read_text(),
            (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text(),
        )


    def assert_cached_execution(self, workflow, script, verifier):
        strict = workflow.split("- name: Build, lint and unit tests with strict verification", 1)[1].split("- name: Upload", 1)[0]
        connected = script.split("./gradlew ", 1)[1].split("2>&1", 1)[0]
        for command in (strict, connected):
            for flag in ("--no-daemon", "--dependency-verification strict", "--build-cache",
                         "--no-configuration-cache", "--stacktrace", "--info", "--console=plain"):
                self.assertIn(flag, command)
            for unsafe in ("--no-build-cache", "--rerun-tasks", "--configuration-cache", "--dry-run", "--tests ",
                           "testInstrumentationRunnerArguments", "--write-verification-metadata",
                           "--gradle-user-home", " -g "):
                self.assertNotIn(unsafe, command)
        self.assertEqual(re.findall(r":app:(\w+)", strict), [
            "verifyResolvedToolchain", "lintDebug", "assembleDebug", "lintRelease", "assembleRelease", "processReleaseManifest", "compileDebugUnitTestKotlin",
            "compileDebugAndroidTestKotlin", "testDebugUnitTest", "assembleDebugAndroidTest", "collectReleaseRuntimeInventory",
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

    def assert_execution_evidence(self, workflow, script):
        strict = workflow.split("- name: Build, lint and unit tests with strict verification", 1)[1].split("- name: Upload", 1)[0]
        check = "          python3 tools/build/verify_gradle_execution.py build evidence/strict.log\n"
        self.assertIn(check, strict)
        self.assertLess(strict.index("graph_status[1] == 0"), strict.index(check))
        graph = script.split('while :; do', 1)[1].split('coverage_attempt=2\ndone', 1)[0]
        check = '  python3 tools/build/verify_gradle_execution.py native "$attempt_dir/strict-connected.log"'
        for contract in (check, '--graph-exit "$test_status" > "$attempt_dir/gate-execution.log"',
                         'gate_status=$?', 'if (( gate_status != 0 )); then', 'exit "$gate_status"'):
            self.assertIn(contract, graph)
        self.assertLess(graph.index(check), graph.index('native_coverage_retry.py'))
        self.assertNotIn('|| true', graph.split(check, 1)[1].split('coverage_gate.py phases', 1)[0])

    def test_every_attempt_checks_real_gate_execution_before_retry(self):
        self.assert_execution_evidence((ROOT / '.github/workflows/android.yml').read_text(),
                                       (ROOT / 'tools/build/run-hosted-native-smoke.sh').read_text())

    def test_cached_execution_preserves_strict_real_gates_and_observed_sdk_receipts(self):
        self.assert_cached_execution(
            (ROOT / ".github/workflows/android.yml").read_text(),
            (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text(),
            (ROOT / "tools/build/verify-hosted-toolchain.sh").read_text(),
        )

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
                                     (LIVE_USAGE_CLASS, LIVE_USAGE_CASES), (BANKED_RESET_CLASS, BANKED_RESET_CASES), (HISTORY_CLASS, HISTORY_CASES), (HISTORY_LIFETIME_CLASS, HISTORY_LIFETIME_CASES), (HISTORY_SAMPLING_CLASS, HISTORY_SAMPLING_CASES), (HISTORY_TEXT_CLASS, HISTORY_TEXT_CASES), (HISTORY_CHART_CLASS, HISTORY_CHART_CASES), (HISTORY_NAVIGATION_CLASS, HISTORY_NAVIGATION_CASES), (HISTORY_AUTHORITY_CLASS, HISTORY_AUTHORITY_CASES), (HISTORY_ISOLATION_CLASS, HISTORY_ISOLATION_CASES), (HISTORY_ASSEMBLED_CLASS, HISTORY_ASSEMBLED_CASES), (ACCOUNT_NAME_CLASS, ACCOUNT_NAME_CASES), (ACCOUNT_REMOVAL_CLASS, ACCOUNT_REMOVAL_CASES), (DEVICE_CODE_COPY_CLASS, DEVICE_CODE_COPY_CASES)):
                for name in sorted(names - ({missing} if missing else set())):
                    ET.SubElement(suite, "testcase", classname=classname, name=name)
        current = (CURRENT_HISTORY_JVM | DASHBOARD_JVM) if kind == "jvm" else (CURRENT_HISTORY_NATIVE | DASHBOARD_NATIVE)
        current |= HISTORY_EXPORT_JVM if kind == "jvm" else HISTORY_EXPORT_NATIVE
        current |= HISTORY_IMPORT_JVM if kind == "jvm" else HISTORY_IMPORT_NATIVE
        current |= REMAINING_TIME_JVM if kind == "jvm" else {}
        current |= BACKGROUND_REFRESH_JVM if kind == "jvm" else {}
        current |= PERIODIC_REFRESH_JVM if kind == "jvm" else PERIODIC_REFRESH_NATIVE
        for classname, names in current.items():
            for name in sorted(names):
                if name != missing:
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
                    path = self.write_report(kind, container_depth=depth)
                    result = verify_reports(self.directory, kind)
                    self.assertEqual(result["kind"], kind)
                    self.assertGreater(result["testCount"], 0)
                    self.assertEqual(result["reports"], [str(path)])

    def test_required_cases_reject_omission_wrong_class_and_duplicates(self):
        # One representative per distinct exact-once branch, not per method.
        examples = (("jvm", CURRENT_HISTORY_JVM), ("native", CURRENT_HISTORY_NATIVE),
                    ("native", {HISTORY_SAMPLING_CLASS: HISTORY_SAMPLING_CASES}),
                    ("native", {HISTORY_NAVIGATION_CLASS: PHONE_TIME_CASES}))
        for kind, registry in examples:
            classname = next(iter(registry))
            name = sorted(registry[classname])[0]
            for mutation in ("missing", "wrong-class", "duplicate"):
                path = self.write_report(kind)
                tree = ET.parse(path)
                suite = tree.getroot()
                case = next(c for c in suite if c.get("classname") == classname and c.get("name") == name)
                if mutation == "missing":
                    suite.remove(case)
                elif mutation == "wrong-class":
                    case.set("classname", "SYNTHETIC-wrong-class")
                else:
                    ET.SubElement(suite, "testcase", attrib=dict(case.attrib))
                tree.write(path)
                with self.subTest(kind=kind, classname=classname, mutation=mutation), self.assertRaises(ValueError):
                    verify_reports(self.directory, kind)

    def test_rejects_missing_tests(self):
        for kind in EXPECTED:
            for depth in (0, 1, 2):
                with self.subTest(kind=kind, container_depth=depth):
                    self.write_report(kind, missing=sorted(EXPECTED[kind])[0], container_depth=depth)
                    with self.assertRaisesRegex(ValueError, "Missing real"):
                        verify_reports(self.directory, kind)
        # Native-only families have a separate required-set branch.
        self.write_report("native", missing=sorted(CREDENTIAL_CASES)[0])
        with self.assertRaisesRegex(ValueError, "Missing real native credential"):
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
        permission_removal = ('<uses-permission android:name="android.permission.FOREGROUND_SERVICE" tools:node="remove"/>'
                              if permissions == SOURCE_PERMISSIONS else '')
        service_removal = ('<service android:name="androidx.work.impl.foreground.SystemForegroundService" tools:node="remove"/>'
                           if permissions == SOURCE_PERMISSIONS else '')
        return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                'xmlns:tools="http://schemas.android.com/tools">'
                + ''.join(f'<uses-permission android:name="{name}"/>' for name in sorted(permissions))
                + permission_removal + declarations + '<application android:usesCleartextTraffic="false">'
                '<activity android:name=".MainActivity" android:launchMode="singleTask"/>'
                + (components_xml(PACKAGE) if permissions == APP_PERMISSIONS else '')
                + service_removal
                + '</application></manifest>')

    def synthetic_apk_activity(self, name=MAIN_ACTIVITY):
        return ('    E: activity (line=5)\n'
                f'      A: android:name(0x01010003)="{name}"\n'
                '      A: android:launchMode(0x0101001d)=(type 0x10)0x2\n' + components_apk(PACKAGE))

    def test_manifest_launcher_and_process_policy_rejects_synthetic_regressions(self):
        manifest = self.directory / "SYNTHETIC-AndroidManifest.xml"
        for permissions in (SOURCE_PERMISSIONS, APP_PERMISSIONS):
            valid = self.synthetic_manifest(permissions)
            activity = '<activity android:name=".MainActivity" android:launchMode="singleTask"/>'
            for name in ('.MainActivity', 'MainActivity', MAIN_ACTIVITY):
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
        for name in ('.MainActivity', 'MainActivity', MAIN_ACTIVITY):
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
            'tools/build/work_manifest_policy.py': (ROOT / 'tools/build/work_manifest_policy.py').read_text(),
            'tools/build/historical_apk_contract.py': (ROOT / 'tools/build/historical_apk_contract.py').read_text(),
        }
        for name, text in files.items():
            path = self.directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if '/release/' in name:
                text = text.replace(PACKAGE, PACKAGE.removesuffix('.dev'))
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
        # The debug oracle remains unchanged; the separate release observer has its own
        # synthetic protocol tests. Stub only that new boundary in this debug harness.
        parser = self.directory / 'native-release-venv/bin/python'
        parser.parent.mkdir(parents=True, exist_ok=True)
        parser.write_text(f'#!{sys.executable}\nimport os, sys\nsys.exit(27 if os.environ["SYNTHETIC_MUTATION"] == "release-observation-failure" else 0)\n')
        parser.chmod(0o755)
        environment = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SERIAL='SYNTHETIC-serial',
                           RUNNER_TEMP=str(self.directory),
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

    def test_release_observer_failure_is_mandatory_but_preserves_original_debug_graph_error(self):
        result, _, statuses = self.run_synthetic_installed_observation('release-observation-failure')
        self.assertTrue(all(value == '0' for value in statuses.values()), statuses)
        self.assertNotEqual(result.returncode, 0)
        result, _, _ = self.run_synthetic_installed_observation('release-observation-failure', graph_exit=23)
        self.assertEqual(result.returncode, 23)

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
                observed = application.replace('</application>', components_xml(PACKAGE) + '</application>') if declaration else application
                valid = prefix + permissions + declaration + observed + '</manifest>'
                manifest.write_text(valid)
                self.assertEqual(check_manifest(manifest, expected)["requestedPermissions"], sorted(expected))
                mutations = [
                    (valid.replace(observed, '<uses-permission android:name="android.permission.CAMERA"/>' + observed), "allowlist"),
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
