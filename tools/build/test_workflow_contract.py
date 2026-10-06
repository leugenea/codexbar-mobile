"""Cheap build source/workflow contracts and explicitly synthetic report-parser tests."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_manifests import check_manifest
from verify_test_reports import CLASS, EXPECTED, verify_reports

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("RUNNER_TEMP", os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir())))


class WorkflowContracts(unittest.TestCase):
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
        pins = {
            "actions/checkout": ("08eba0b27e820071cde6df949e0beb9ba4906955", "v4.3.0"),
            "actions/setup-python": ("5fda3b95a4ea91299a34e894583c3862153e4b97", "v7.0.0"),
            "actions/upload-artifact": ("ea165f8d65b6e75b540449e92b4886f43607fa02", "v4.6.2"),
            "actions/download-artifact": ("d3f86a106a0bac45b974a628896c90dbdf5c8093", "v4.3.0"),
            "benchmark-action/github-action-benchmark": ("4322e5726e6334590d251fc4f92bec0efafc45dc", "v1.22.2"),
            "actions/upload-pages-artifact": ("fc324d3547104276b827a68afc52ff2a11cc49c9", "v5.0.0"),
            "actions/deploy-pages": ("368f82528645a54fb793d4d04e342629a3f51346", "v5.0.1"),
        }
        self.assertEqual({action for action, _, _ in uses}, set(pins))
        for action, sha, version in uses:
            self.assertEqual((sha, version), pins[action])
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
        for path in (ROOT / ".github/workflows").glob("*.y*ml"):
            text = path.read_text()
            self.assertEqual(len(re.findall(r"(?m)^\s*uses:", text)),
                             len(re.findall(r"(?m)^\s*uses: [\w/-]+@[0-9a-f]{40} # v[\d.]+$", text)))

    def test_code_metrics_contract_rejects_privilege_and_history_regressions(self):
        original = (ROOT / ".github/workflows/code-metrics.yml").read_text()
        for old, new in (("      contents: read", "      contents: write"),
                         ("queue: max", "queue: single"),
                         ("github.event_name == 'push' && github.run_id", "github.ref"),
                         ("github.ref == 'refs/heads/main'", "true"),
                         ("fail-on-alert: false", "fail-on-alert: true"),
                         ("persist-credentials: false", "persist-credentials: true"),
                         ("artifact_name: github-pages-${{ github.run_attempt }}", "artifact_name: github-pages"),
                         ("# v4.3.0", "# unversioned")):
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
                       "compose.waitUntil", "last state=", "assertManifestHasNoInternet()",
                       "assertIsSelected()", "assertIdentity()", "ProgressBarRangeInfo",
                       "SemanticsActions.GetTextLayoutResult"):
            self.assertIn(actual, native)
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
                         "system-images;android-36;google_apis;x86_64", "--channel=0"):
            self.assertIn(contract, script)
        for unsafe in ("--dependency-verification off", "--write-verification-metadata", "testInstrumentationRunnerArguments",
                       "--tests ", "--dry-run", "system-images;android-37"):
            self.assertNotIn(unsafe, script)
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
            "verifyResolvedToolchain", "lintDebug", "assembleDebug", "compileDebugUnitTestKotlin",
            "compileDebugAndroidTestKotlin", "testDebugUnitTest", "assembleDebugAndroidTest",
        ])
        self.assertEqual(re.findall(r":app:(\w+)", connected), [
            "verifyResolvedToolchain", "compileDebugUnitTestKotlin", "compileDebugAndroidTestKotlin",
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
                    self.assertEqual(result["testCount"], len(EXPECTED[kind]))

    def test_rejects_missing_tests(self):
        for kind in EXPECTED:
            for depth in (0, 1, 2):
                with self.subTest(kind=kind, container_depth=depth):
                    self.write_report(kind, missing=sorted(EXPECTED[kind])[0], container_depth=depth)
                    with self.assertRaisesRegex(ValueError, "Missing real"):
                        verify_reports(self.directory, kind)

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

    def test_source_and_merged_manifest_parser_fails_closed(self):
        manifest = self.directory / "SYNTHETIC-AndroidManifest.xml"
        prefix = '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
        manifest.write_text(prefix + '<application/></manifest>')
        self.assertEqual(check_manifest(manifest)["requestedPermissions"], [])
        for tag in ("uses-permission", "uses-permission-sdk-23"):
            manifest.write_text(prefix + f'<{tag} android:name="android.permission.INTERNET"/></manifest>')
            with self.assertRaisesRegex(ValueError, "requests INTERNET"):
                check_manifest(manifest)
        manifest.write_text("<unrelated/>")
        with self.assertRaisesRegex(ValueError, "Not an Android manifest"):
            check_manifest(manifest)
        with self.assertRaises(FileNotFoundError):
            check_manifest(self.directory / "missing.xml")

if __name__ == "__main__":
    unittest.main()
