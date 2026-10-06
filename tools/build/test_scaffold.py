"""Cheap scaffold/integrity contracts; no Gradle, SDK or native execution."""
import hashlib
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class ToolchainContract(unittest.TestCase):
    def test_wrapper_source_and_independent_jar_checksum(self):
        expected = {
            "gradle/wrapper/gradle-wrapper.jar": "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
            "gradlew": "45bda8deaef01b732f07bd317f962a736e349c6faa6662829bc0f41293b51212",
            "gradlew.bat": "9e62ee9a1c6eb7cecc90e55acc3ff09a1631ccfe3edddf180538a1e42b1532dc",
        }
        for path, digest in expected.items():
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), digest, path)
        self.assertTrue((ROOT / "gradlew").stat().st_mode & 0o111)
        baseline = json.loads((ROOT / "docs/research/m0/toolchain.json").read_text())
        wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn("gradle-" + baseline["gradle"]["version"] + "-bin.zip", wrapper)
        self.assertIn("distributionSha256Sum=" + baseline["gradle"]["distributionSha256"], wrapper)

    def test_m0_selected_versions_not_silently_amended(self):
        baseline = json.loads((ROOT / "docs/research/m0/toolchain.json").read_text())
        root = (ROOT / "build.gradle").read_text()
        app = (ROOT / "app/build.gradle").read_text()
        installer = (ROOT / "tools/build/install-hosted-toolchain.sh").read_text()
        self.assertIn("version '" + baseline["agp"]["version"] + "'", root)
        self.assertIn("kotlin-gradle-plugin:" + baseline["kotlin"]["version"], root)
        self.assertIn("plugin.compose' version '" + baseline["kotlin"]["composeCompilerPlugin"] + "'", root)
        self.assertNotIn("org.jetbrains.kotlin.android", root + app)
        for snippet in ("release(37) { minorApiLevel = 0 }", "minSdk 26", "targetSdk 37",
                        "buildToolsVersion '36.0.0'", "compose-bom:2026.09.00", "ui:1.12.1",
                        "material3:1.4.0", "kotlinx-coroutines-android:1.11.0"):
            self.assertIn(snippet, app)
        self.assertIn(baseline["jdk"]["sha256"], installer)
        self.assertIn(baseline["jdk"]["linuxX64Archive"], installer)
        for package in baseline["sdkPackages"].values():
            archive = next(a for a in package["archives"] if a["os"] in ("all", "linux"))
            self.assertIn(archive["archive"], installer)
            self.assertIn(archive["checksum"], installer)
        self.assertIn("Pkg.Revision", installer)

    def test_phase1_verification_is_explicitly_empty_not_claimed_reviewed(self):
        ns = {"v": "https://schema.gradle.org/dependency-verification"}
        metadata = ROOT / "gradle/verification-metadata.xml"
        xml = ET.parse(metadata).getroot()
        self.assertEqual(xml.find("v:configuration/v:verify-metadata", ns).text, "true")
        self.assertEqual(len(xml.find("v:components", ns)), 0)
        self.assertIn("NOT reviewed", metadata.read_text())
        self.assertTrue((ROOT / "gradle/m1-bootstrap-diagnostic").is_file())
        # Retire this phase-specific test with the marker when reviewed metadata lands.

    def test_read_only_pr_route_and_strict_discovery_separation(self):
        workflow = (ROOT / ".github/workflows/m1-toolchain.yml").read_text()
        self.assertIn("  pull_request:", workflow)
        self.assertIn("runs-on: ubuntu-24.04", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("  contents: read", workflow)
        self.assertIn("default: false", workflow)
        self.assertIn("gradle/m1-bootstrap-diagnostic", workflow)
        for unsafe in ("pull_request_target", "self-hosted", "secrets.", "continue-on-error:", "paths:"):
            self.assertNotIn(unsafe, workflow)
        self.assertEqual(len(re.findall(r"uses: [\w/-]+@[a-f0-9]{40} # v[\d.]+", workflow)), 2)
        strict = workflow.split("- name: Strict committed verification", 1)[1].split("- name: PHASE 1", 1)[0]
        self.assertIn("--dependency-verification strict", strict)
        self.assertNotIn("--write-verification-metadata", strict)
        self.assertNotIn("--dependency-verification off", strict)
        for task in ("lintDebug", "assembleDebug", "compileDebugUnitTestKotlin",
                     "compileDebugAndroidTestKotlin", "testDebugUnitTest", "assembleDebugAndroidTest"):
            self.assertIn(":app:" + task, strict)
        discovery = workflow.split("- name: PHASE 1", 1)[1].split("- name: Bind evidence", 1)[0]
        self.assertIn("always()", discovery)
        self.assertIn("steps.route.outputs.discover == 'true'", discovery)
        self.assertIn("--write-verification-metadata sha256", discovery)
        self.assertIn(":app:connectedDebugAndroidTest --dry-run", discovery)
        self.assertIn("lint_status=$?", discovery)
        self.assertIn("lint_status == 0", discovery)
        self.assertIn("if: always()", workflow.split("- name: Upload", 1)[1])
        self.assertIn("m1-gradle-strict", strict)
        self.assertIn("m1-gradle-discovery", discovery)

    def test_no_network_permission_and_honest_runnable_placeholder(self):
        ns = "{http://schemas.android.com/apk/res/android}"
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        self.assertEqual(manifest.findall("uses-permission"), [])
        activity = manifest.find("application/activity")
        self.assertEqual(activity.get(ns + "exported"), "true")
        self.assertEqual(activity.find("intent-filter/category").get(ns + "name"), "android.intent.category.LAUNCHER")
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        text = " ".join(e.text or "" for e in strings)
        self.assertIn("not authenticated", text)
        self.assertIn("Placeholder demo", text)
        self.assertIn("unofficial", text)
        self.assertIn("not implemented yet", text)
        ET.parse(ROOT / "app/src/main/res/values/themes.xml")
        source = (ROOT / "app/src/main/java/io/github/leugenea/codexbarmobile/MainActivity.kt").read_text()
        for snippet in ("ComponentActivity()", "setContent", "MaterialTheme", "heading()", "Modifier.padding(insets)"):
            self.assertIn(snippet, source)
        self.assertNotRegex(source, r"https?://|Socket|HttpClient|URLConnection|SharedPreferences")


if __name__ == "__main__":
    unittest.main()
