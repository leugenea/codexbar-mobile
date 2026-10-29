"""Cheap scaffold/integrity contracts; no Gradle, SDK or native execution."""
import hashlib
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
        self.assertIn("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.2-bin.zip", wrapper)
        self.assertIn("distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607", wrapper)

    def test_runtime_toolchain_assertions_and_lint_remain_strict(self):
        inventory = (ROOT / "tools/build/toolchain.init.gradle").read_text()
        self.assertIn("assert receipt.gradle == '9.8.2'", inventory)
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

    def test_read_only_routes_require_real_strict_execution(self):
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
        strict = workflow.split("- name: Build, lint and unit tests with strict verification", 1)[1].split("- name: Upload", 1)[0]
        self.assertIn("--dependency-verification strict", strict)
        for flag in ("--build-cache", "--no-configuration-cache", "--console=plain"):
            self.assertIn(flag, strict)
        self.assertNotIn("--rerun-tasks", strict)
        self.assertNotIn("--no-build-cache", strict)
        for task in ("lintDebug", "assembleDebug", "compileDebugUnitTestKotlin",
                     "compileDebugAndroidTestKotlin", "testDebugUnitTest", "assembleDebugAndroidTest"):
            self.assertIn(":app:" + task, strict)
        self.assertIn("verify_manifests.py", strict)
        self.assertIn("verify_test_reports.py jvm", strict)
        self.assertIn("if: always()", workflow.split("- name: Upload", 1)[1])

    def assert_real_output_uploads(self, workflow):
        build, native = workflow.split("  instrumented:\n", 1)
        self.assertIn("fetch-depth: 0", build)
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
                         ("fetch-depth: 0", "fetch-depth: 1")):
            with self.subTest(mutation=old), self.assertRaises(AssertionError):
                self.assert_real_output_uploads(workflow.replace(old, new, 1))

    def test_app_manifest_overlays_cannot_broaden_network_policy(self):
        check_source_manifests(ROOT)

    def test_screen_capture_and_recents_use_normal_platform_policy(self):
        production = ROOT / "app/src/main"
        # All app windows, including Compose dialogs/popups, follow the ordinary
        # platform policy. Do not reintroduce a capture block on another route.
        blocked = r"FLAG_SECURE|setRecentsScreenshotEnabled|SecureFlagPolicy\s*\.\s*SecureOn"
        for path in sorted(production.rglob("*")):
            if path.suffix in {".kt", ".java", ".xml"}:
                with self.subTest(path=path.relative_to(ROOT)):
                    self.assertNotRegex(path.read_text(), blocked)


if __name__ == "__main__":
    unittest.main()
