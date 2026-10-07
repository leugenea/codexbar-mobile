"""Cheap scaffold/integrity contracts; no Gradle, SDK or native execution."""
import hashlib
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

from verify_manifests import check_source_manifests

ROOT = Path(__file__).resolve().parents[2]


class ToolchainContract(unittest.TestCase):
    def test_wrapper_source_and_independent_jar_checksum(self):
        expected = {
            "gradle/wrapper/gradle-wrapper.jar": "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98",
            "gradlew": "e01b5c97892572c82405c02b96a3382379100e7d825ce7c48883d95c26928750",
            "gradlew.bat": "ad2fac6060c5b929bed15d428e09483e52747d0120874346861ad4ec324af64c",
        }
        for path, digest in expected.items():
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), digest, path)
        self.assertTrue((ROOT / "gradlew").stat().st_mode & 0o111)
        wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.1-bin.zip", wrapper)
        self.assertIn("distributionSha256Sum=dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f", wrapper)

    def test_current_toolchain_and_install_policy(self):
        baseline = json.loads((ROOT / "docs/research/m0/toolchain.json").read_text())
        root = (ROOT / "build.gradle").read_text()
        app = (ROOT / "app/build.gradle").read_text()
        verifier = (ROOT / "tools/build/verify-hosted-toolchain.sh").read_text()
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn("id 'com.android.application' version '9.4.1'", root)
        self.assertIn("kotlin-gradle-plugin:" + baseline["kotlin"]["version"], root)
        self.assertIn("plugin.compose' version '" + baseline["kotlin"]["composeCompilerPlugin"] + "'", root)
        self.assertNotIn("org.jetbrains.kotlin.android", root + app)
        for snippet in ("release(37) { minorApiLevel = 0 }", "minSdk 26", "targetSdk 37",
                        "buildToolsVersion '36.0.0'", "compose-bom:2026.09.00", "ui:1.12.1",
                        "material3:1.4.0", "kotlinx-coroutines-android:1.11.0"):
            self.assertIn(snippet, app)
        # Research archive/patch selections are frozen historical evidence, not current install policy.
        self.assertIn("distribution: temurin", workflow)
        self.assertIn("java-version: '17'", workflow)
        for path in baseline["sdkPackages"]:
            self.assertIn("'" + path + "'", workflow)
        self.assertIn("Pkg.Revision", verifier)
        self.assertIn("installed-toolchain.json", verifier)
        self.assertFalse((ROOT / "tools/build/write_sdk_package_metadata.py").exists())
        for obsolete in (baseline["jdk"]["sha256"], baseline["jdk"]["linuxX64Archive"],
                         "write_sdk_package_metadata.py", "installed-archives.sha256"):
            self.assertNotIn(obsolete, verifier + workflow)

    def test_runtime_toolchain_assertions_and_lint_remain_strict(self):
        inventory = (ROOT / "tools/build/toolchain.init.gradle").read_text()
        self.assertIn("assert receipt.gradle == '9.8.1'", inventory)
        self.assertIn("assert receipt.agp == '9.4.1'", inventory)
        for module in ("kotlin-gradle-plugin", "kotlin-compiler-embeddable",
                       "kotlin-compose-compiler-plugin-embeddable"):
            self.assertIn("'" + module + "', '2.4.20'", inventory)
        self.assertIn("assert receipt.javaMajor == '17'", inventory)
        self.assertIn("assert receipt.javaRuntime ==~ /17(?:[.+-].*)?/", inventory)
        self.assertNotIn('17.0.20', inventory)
        self.assertIn("assert receipt.javaVendor == 'Eclipse Adoptium'", inventory)
        self.assertIn("assert kgpJar.name ==~", inventory)
        self.assertIn("actual.every { it.version == expected[2] }", inventory)
        app = (ROOT / "app/build.gradle").read_text()
        lint_match = re.search(r"\blint\s*\{([^{}]*)\}", app)
        assert lint_match is not None, "Missing strict lint configuration"
        lint = lint_match.group(1)
        self.assertIn("abortOnError true", lint)
        self.assertIn("warningsAsErrors true", lint)
        for weakening in ("disable", "ignore", "baseline", "checkOnly", "warning", "fatal"):
            self.assertNotRegex(lint, r"(?m)^\s*" + weakening + r"\b")
        for path in (ROOT / "app/src/main").rglob("*"):
            if path.suffix in (".xml", ".kt"):
                self.assertNotRegex(path.read_text(), r'tools:ignore\s*=|@SuppressLint\s*\(')

    def assert_backup_policy(self, manifest, legacy, extraction):
        ns = "{http://schemas.android.com/apk/res/android}"
        application = manifest.find("application")
        assert application is not None, "Missing application declaration"
        self.assertEqual(application.get(ns + "allowBackup"), "false")
        self.assertEqual(application.get(ns + "fullBackupContent"), "@xml/backup_rules")
        self.assertEqual(application.get(ns + "dataExtractionRules"), "@xml/data_extraction_rules")
        self.assertEqual(legacy.tag, "full-backup-content")
        self.assertEqual(extraction.tag, "data-extraction-rules")
        self.assertEqual([section.tag for section in extraction], ["cloud-backup", "device-transfer"])
        domains = {"root", "file", "database", "sharedpref", "external", "device_root",
                   "device_file", "device_database", "device_sharedpref"}
        for section in (legacy, *extraction):
            self.assertEqual(len(section), len(domains))
            self.assertEqual({entry.get("domain") for entry in section}, domains)
            for entry in section:
                self.assertEqual(entry.tag, "exclude")
                self.assertEqual(entry.attrib, {"domain": entry.get("domain"), "path": "."})

    def test_explicit_legacy_cloud_and_device_transfer_exclusions(self):
        self.assert_backup_policy(
            ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot(),
            ET.parse(ROOT / "app/src/main/res/xml/backup_rules.xml").getroot(),
            ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot(),
        )

    def test_backup_contract_rejects_policy_weakening(self):
        # Synthetic mutations test source policy only, not Android backup execution.
        for mutation in ("allow_backup", "missing_reference", "missing_cloud", "missing_transfer",
                         "legacy_domain", "cloud_domain", "transfer_domain", "narrow_path", "include"):
            with self.subTest(mutation=mutation):
                manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
                legacy = ET.parse(ROOT / "app/src/main/res/xml/backup_rules.xml").getroot()
                extraction = ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot()
                ns = "{http://schemas.android.com/apk/res/android}"
                application = manifest.find("application")
                assert application is not None
                if mutation == "allow_backup":
                    application.set(ns + "allowBackup", "true")
                elif mutation == "missing_reference":
                    del application.attrib[ns + "dataExtractionRules"]
                elif mutation == "missing_cloud":
                    extraction.remove(extraction[0])
                elif mutation == "missing_transfer":
                    extraction.remove(extraction[1])
                elif mutation == "legacy_domain":
                    legacy.remove(legacy[-1])
                elif mutation == "cloud_domain":
                    extraction[0].remove(extraction[0][-1])
                elif mutation == "transfer_domain":
                    extraction[1].remove(extraction[1][-1])
                elif mutation == "narrow_path":
                    extraction[1][0].set("path", "placeholder")
                else:
                    extraction[1][0].tag = "include"
                with self.assertRaises(AssertionError):
                    self.assert_backup_policy(manifest, legacy, extraction)

    def test_single_min26_adaptive_icon_includes_monochrome(self):
        resources = ROOT / "app/src/main/res"
        self.assertEqual(sorted(p.relative_to(resources).as_posix() for p in resources.glob("mipmap-*/ic_launcher.xml")),
                         ["mipmap-anydpi/ic_launcher.xml"])
        icon = ET.parse(resources / "mipmap-anydpi/ic_launcher.xml").getroot()
        self.assertEqual(icon.tag, "adaptive-icon")
        self.assertEqual([layer.tag for layer in icon], ["background", "foreground", "monochrome"])
        ns = "{http://schemas.android.com/apk/res/android}"
        self.assertEqual(icon[0].get(ns + "drawable"), "@color/launcher_background")
        for layer in icon[1:]:
            self.assertEqual(layer.get(ns + "drawable"), "@drawable/ic_launcher_foreground")

    def assert_cached_actions_setup(self, workflow):
        pins = {
            "actions/checkout": ("08eba0b27e820071cde6df949e0beb9ba4906955", "v4.3.0", 2),
            "actions/upload-artifact": ("ea165f8d65b6e75b540449e92b4886f43607fa02", "v4.6.2", 2),
            "actions/setup-java": ("de7274f081f381c8f8158605e0321c36c376e2e6", "v6.0.1", 2),
            "gradle/actions/setup-gradle": ("3f5f9adaf7d9fecd50b5935e54106014257a94e6", "v6.4.0", 2),
            "android-actions/setup-android": ("be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd", "v4.0.4", 2),
        }
        uses = re.findall(r"(?m)^\s*-?\s*uses: (\S+) # (v[\d.]+)$", workflow)
        self.assertEqual(len(uses), len(re.findall(r"(?m)^\s*-?\s*uses:", workflow)))
        self.assertEqual(len(uses), sum(value[2] for value in pins.values()))
        for action, (sha, version, count) in pins.items():
            self.assertEqual(uses.count((action + "@" + sha, version)), count, action)
        configured = {
            "actions/setup-java": {
                "distribution": "temurin", "java-version": "17", "java-package": "jdk",
                "architecture": "x64", "overwrite-settings": "false",
            },
            "gradle/actions/setup-gradle": {
                "cache-provider": "basic", "cache-disabled": "false",
                "cache-read-only": "${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository }}",
                "validate-wrappers": "true", "allow-snapshot-wrappers": "false",
                "dependency-graph": "disabled", "build-scan-publish": "false",
                "add-job-summary": "always", "add-job-summary-as-pr-comment": "never",
            },
            "android-actions/setup-android": {
                "cmdline-tools-version": "15859902", "packages": "",
                "accept-android-sdk-licenses": "true", "log-accepted-android-sdk-licenses": "false",
            },
        }
        for job, home in (("build", "android-gradle-build"), ("instrumented", "android-gradle-instrumented")):
            body = workflow.split("  " + job + ":\n", 1)[1].split("\n  instrumented:\n", 1)[0]
            header, step_text = body.split("    steps:\n", 1)
            self.assertIn("      GRADLE_USER_HOME: ${{ github.workspace }}/../" + home + "\n", header)
            self.assertNotIn("${{ runner.temp }}", header)
            self.assertNotRegex(step_text, r"(?m)^\s*GRADLE_USER_HOME:")
            self.assertNotRegex(step_text, r"\bGRADLE_USER_HOME=")
            self.assertNotIn("must start fresh", body)
            self.assertNotIn('[[ ! -e "$GRADLE_USER_HOME" ]]', body)
            steps = re.split(r"(?m)^      - ", step_text)[1:]
            for action, inputs in configured.items():
                found = [step for step in steps if "uses: " + action + "@" in step]
                self.assertEqual(len(found), 1, action)
                self.assertNotRegex(found[0], r"(?m)^        (?:if|continue-on-error):")
                actual = {key: value.split(" #", 1)[0].strip().strip("'\"")
                          for key, value in re.findall(r"(?m)^          ([\w-]+): (.*)$", found[0])}
                self.assertEqual(actual, inputs, action)
            java = body.index("uses: actions/setup-java@")
            isolated = body.index("name: Select isolated Android SDK")
            android = body.index("uses: android-actions/setup-android@")
            sdk = body.index("name: Install named project SDK packages")
            gradle = body.index("uses: gradle/actions/setup-gradle@")
            self.assertLess(java, isolated)
            self.assertLess(isolated, android)
            self.assertLess(android, sdk)
            self.assertLess(sdk, gradle)
            self.assertIn('"$RUNNER_TEMP/android-sdk" "$RUNNER_TEMP/android-sdk" >> "$GITHUB_ENV"', body[isolated:android])
            install = body[sdk:gradle]
            self.assertNotRegex(install, r"(?m)^        (?:if|continue-on-error):")
            self.assertIn('set -euo pipefail', install)
            self.assertIn('timeout 30 "$sdkmanager" --version', install)
            self.assertIn('timeout --signal=TERM --kill-after=30s 10m "$sdkmanager"', install)
            self.assertIn('--sdk_root="$ANDROID_HOME" --channel=0', install)
            self.assertEqual(install.split('--install ', 1)[1].split('2>&1', 1)[0].strip(),
                             "'platforms;android-37.0' 'build-tools;36.0.0'")
            self.assertIn('bash tools/build/verify-hosted-toolchain.sh', install)
            # Retired filenames below are absence guards, not active CI references.
            for obsolete in ('jdkfile', 'jdk-file:', 'force-download:', 'cache-jdk:', 'cache: gradle',
                             '17.0.20', 'm1-jdk.tar.gz', 'curl ', 'unzip ', 'write_sdk_package_metadata.py',
                             'android-emulator-runner', 'build-tools;37.0.0'):
                self.assertNotIn(obsolete, body)
            self.assertNotIn("steps.gradle.outputs.cache", body)

    def test_actions_cache_contract_rejects_synthetic_regressions(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        for old, new in (
            ("cache-provider: basic", "cache-provider: enhanced"),
            ("cache-disabled: false", "cache-disabled: true"),
            ("cache-read-only: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository }}", "cache-read-only: true"),
            ("distribution: temurin", "distribution: jdkfile"),
            ("java-version: '17'", "java-version: '17.0.20.1+1'"),
            ("architecture: x64", "architecture: x64\n          force-download: true"),
            ("'platforms;android-37.0' 'build-tools;36.0.0'", "'platforms;android-37' 'build-tools;25.0.0'"),
            ('timeout 30 "$sdkmanager" --version', '"$sdkmanager" --version'),
            ('timeout --signal=TERM --kill-after=30s 10m "$sdkmanager"', '"$sdkmanager"'),
            ('--sdk_root="$ANDROID_HOME" --channel=0', '--sdk_root="$ANDROID_HOME" --channel=3'),
            ('name: Install named project SDK packages and verify observed toolchain\n',
             'name: Install named project SDK packages and verify observed toolchain\n        if: false\n'),
            ("uses: android-actions/setup-android@", "uses: invalid/setup-android@"),
            ("packages: ''", "packages: 'build-tools;37.0.0'"),
            ("cache-provider: basic", "cache-provider: basic\n          cache-write-only: true"),
            ("android-gradle-instrumented\n", "unrestored-home\n"),
            ("          set -o pipefail", "          export GRADLE_USER_HOME=/unrestored\n          set -o pipefail"),
            ("# v6.4.0", "# v6.3.0"),
        ):
            with self.subTest(mutation=new):
                self.assertIn(old, workflow)
                with self.assertRaises(AssertionError):
                    self.assert_cached_actions_setup(workflow.replace(old, new, 1))

    def test_read_only_routes_require_cached_homes_and_real_strict_execution(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn("  pull_request:", workflow)
        self.assertIn("  push:", workflow)
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertIn("runs-on: ubuntu-24.04", workflow)
        self.assertIn("  contents: read", workflow)
        self.assertEqual(workflow.count("persist-credentials: false"), 2)
        # Preserve absence guards for the retired dependency-bootstrap paths.
        self.assertFalse((ROOT / "gradle/m1-bootstrap-diagnostic").exists())
        for unsafe in ("pull_request_target", "self-hosted", "secrets.", "continue-on-error:", "paths:",
                       "discover_dependencies", "m1-bootstrap-diagnostic", "m1-gradle-discovery",
                       "--write-verification-metadata", "--dependency-verification off", "actions/cache"):
            self.assertNotIn(unsafe, workflow)
        self.assert_cached_actions_setup(workflow)
        strict = workflow.split("- name: Build, lint and unit tests with strict verification", 1)[1].split("- name: Upload", 1)[0]
        self.assertIn("--dependency-verification strict", strict)
        for flag in ("--no-build-cache", "--no-configuration-cache", "--rerun-tasks"):
            self.assertIn(flag, strict)
        for task in ("lintDebug", "assembleDebug", "compileDebugUnitTestKotlin",
                     "compileDebugAndroidTestKotlin", "testDebugUnitTest", "assembleDebugAndroidTest"):
            self.assertIn(":app:" + task, strict)
        self.assertIn("verify_manifests.py", strict)
        self.assertIn("verify_test_reports.py jvm", strict)
        self.assertIn("if: always()", workflow.split("- name: Upload", 1)[1])

    def assert_real_output_uploads(self, workflow):
        build, native = workflow.split("  instrumented:\n", 1)
        self.assertIn("fetch-depth: 2", build)
        for body, name in ((build, "android-build"), (native, "android-instrumented")):
            steps = re.split(r"(?m)^      - ", body)[1:]
            uploads = [step for step in steps if "uses: actions/upload-artifact@" in step]
            self.assertEqual(len(uploads), 1)
            upload = uploads[0]
            self.assertIn("        if: always()\n", upload)
            self.assertIn("          name: " + name + "-${{ github.run_id }}-${{ github.run_attempt }}", upload)
            for path in ("evidence/", "build/reports/", "app/build/reports/", "app/build/test-results/",
                         "app/build/outputs/apk/", "app/build/intermediates/merged_manifests/"):
                self.assertIn("            " + path + "\n", upload)
            self.assertIn("if-no-files-found: error", upload)
        outcomes = next(step for step in re.split(r"(?m)^      - ", native)[1:]
                        if "coverage_gate.py phases" in step)
        self.assertIn("        if: always()\n", outcomes)
        for path in ("app/build/outputs/unit_test_code_coverage/", "app/build/outputs/code_coverage/",
                     "app/build/coverage-gate/", "app/build/outputs/androidTest-results/"):
            self.assertIn("            " + path + "\n", native)

    def test_always_uploads_real_reports_apks_coverage_and_diagnostics(self):
        self.assert_real_output_uploads((ROOT / ".github/workflows/android.yml").read_text())

    def test_output_upload_contract_rejects_missing_reports_and_conditional_uploads(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        for old, new in (("app/build/reports/", "unused/reports/"),
                         ("app/build/outputs/code_coverage/", "unused/coverage/"),
                         ("        if: always()", "        if: success()"),
                         ("fetch-depth: 2", "fetch-depth: 1")):
            with self.subTest(mutation=old), self.assertRaises(AssertionError):
                self.assert_real_output_uploads(workflow.replace(old, new, 1))

    def test_inventory_filters_projects_before_artifact_selection(self):
        inventory = (ROOT / "tools/build/toolchain.init.gradle").read_text()
        self.assertIn("configuration.incoming.artifactView { view ->", inventory)
        self.assertIn("view.componentFilter { component ->", inventory)
        self.assertIn("component instanceof ModuleComponentIdentifier", inventory)
        for unsafe in ("configuration.incoming.artifacts", "lenient", "withVariantReselection", "artifactType"):
            # Comments may explain AGP artifact types; executable view must remain untyped/non-lenient.
            code = "\n".join(line for line in inventory.splitlines() if not line.strip().startswith("//"))
            self.assertNotIn(unsafe, code)
        self.assertIn("c.name.toLowerCase().contains('debug')", inventory)
        self.assertIn("digest(artifact.file)", inventory)
        self.assertIn("actual.every { it.version == expected[2] }", inventory)
        self.assertNotIn("write_sdk_package_metadata.py", (ROOT / "tools/build/verify-hosted-toolchain.sh").read_text())

    def test_app_manifest_overlays_cannot_broaden_network_policy(self):
        check_source_manifests(ROOT)

    def test_internet_only_cleartext_off_and_honest_four_state_shell(self):
        ns = "{http://schemas.android.com/apk/res/android}"
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        permissions = {node.get(ns + "name") for node in manifest
                       if node.tag in ("uses-permission", "uses-permission-sdk-23")}
        self.assertEqual(permissions, {"android.permission.INTERNET"})
        application = manifest.find("application")
        assert application is not None
        self.assertEqual(application.get(ns + "usesCleartextTraffic"), "false")
        self.assertIsNone(application.get(ns + "networkSecurityConfig"))
        activity = manifest.find("application/activity")
        self.assertEqual(activity.get(ns + "exported"), "true")
        self.assertEqual(activity.find("intent-filter/category").get(ns + "name"), "android.intent.category.LAUNCHER")
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        text = " ".join(e.text or "" for e in strings)
        self.assertIn("Not authenticated", text)
        self.assertIn("not connected", text)
        self.assertIn("offline demo", text)
        self.assertIn("Unofficial", text)
        self.assertNotIn("not implemented yet", text)
        ET.parse(ROOT / "app/src/main/res/values/themes.xml")
        source = (ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile/MainActivity.kt").read_text()
        for snippet in ("ComponentActivity()", "setContent", "MaterialTheme", "heading()",
                        "padding(insets)", "WindowInsets.safeDrawing", "isSystemInDarkTheme()",
                        "rememberSaveable", "verticalScroll", "Preview.Disconnected", "Preview.Loading",
                        "Preview.Error", "Preview.Demo"):
            self.assertIn(snippet, source)
        self.assertNotRegex(source, r"https?://|Socket|HttpClient|URLConnection|SharedPreferences")


if __name__ == "__main__":
    unittest.main()
