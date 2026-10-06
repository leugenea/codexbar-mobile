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
            "gradle/wrapper/gradle-wrapper.jar": "238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5",
            "gradlew": "e01b5c97892572c82405c02b96a3382379100e7d825ce7c48883d95c26928750",
            "gradlew.bat": "ad2fac6060c5b929bed15d428e09483e52747d0120874346861ad4ec324af64c",
        }
        source = json.loads((ROOT / "docs/build/wrapper-source.json").read_text())
        approved = json.loads((ROOT / "docs/build/m1-toolchain-amendment.json").read_text())
        self.assertEqual(source["files"], expected)
        self.assertEqual(source["gradleVersion"], approved["gradle"]["version"])
        self.assertEqual(source["releaseCommit"], approved["gradle"]["releaseCommit"])
        self.assertEqual(approved["gradle"]["wrapperJarSha256"], expected["gradle/wrapper/gradle-wrapper.jar"])
        for path, digest in expected.items():
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), digest, path)
        self.assertTrue((ROOT / "gradlew").stat().st_mode & 0o111)
        wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn("gradle-" + approved["gradle"]["version"] + "-bin.zip", wrapper)
        self.assertIn("distributionSha256Sum=" + approved["gradle"]["distributionSha256"], wrapper)

    def test_only_owner_approved_gradle_agp_override_m0(self):
        baseline = json.loads((ROOT / "docs/research/m0/toolchain.json").read_text())
        approved = json.loads((ROOT / "docs/build/m1-toolchain-amendment.json").read_text())
        historical = approved["historicalBaseline"]
        self.assertEqual(historical["path"], "docs/research/m0/toolchain.json")
        self.assertEqual(historical["sha256"], "f94673e8a80883e8a08922ff7c5a78574db3d7a670aae55164a24b56e5b0fbf6")
        self.assertEqual(hashlib.sha256((ROOT / historical["path"]).read_bytes()).hexdigest(), historical["sha256"])
        self.assertEqual(approved["approvedOverrideFields"], ["gradle", "agp"])
        self.assertEqual(approved["ownerDecision"]["response"], "Давай")
        self.assertEqual(approved["gradle"]["version"], "9.8.0")
        self.assertEqual(approved["agp"]["version"], "9.4.1")
        for field in ("gradle", "agp"):
            self.assertEqual(approved[field]["previousVersion"], baseline[field]["version"])
        self.assertEqual(approved["agp"]["pomSha256"], approved["agp"]["publishedPomSha256"])
        root = (ROOT / "build.gradle").read_text()
        app = (ROOT / "app/build.gradle").read_text()
        installer = (ROOT / "tools/build/install-hosted-toolchain.sh").read_text()
        workflow = (ROOT / ".github/workflows/m1-toolchain.yml").read_text()
        self.assertIn("id 'com.android.application' version '" + approved["agp"]["version"] + "'", root)
        self.assertIn("kotlin-gradle-plugin:" + baseline["kotlin"]["version"], root)
        self.assertIn("plugin.compose' version '" + baseline["kotlin"]["composeCompilerPlugin"] + "'", root)
        self.assertNotIn("org.jetbrains.kotlin.android", root + app)
        for snippet in ("release(37) { minorApiLevel = 0 }", "minSdk 26", "targetSdk 37",
                        "buildToolsVersion '36.0.0'", "compose-bom:2026.09.00", "ui:1.12.1",
                        "material3:1.4.0", "kotlinx-coroutines-android:1.11.0"):
            self.assertIn(snippet, app)
        self.assertIn(baseline["jdk"]["sha256"], installer)
        self.assertIn(baseline["jdk"]["linuxX64Archive"], workflow)
        for package in baseline["sdkPackages"].values():
            archive = next(a for a in package["archives"] if a["os"] in ("all", "linux"))
            self.assertIn(archive["archive"], installer)
            self.assertIn(archive["checksum"], installer)
        self.assertIn("Pkg.Revision", installer)

    def test_receipt_assertions_and_lint_remain_strict(self):
        inventory = (ROOT / "tools/build/toolchain.init.gradle").read_text()
        self.assertIn("docs/build/m1-toolchain-amendment.json", inventory)
        self.assertIn("assert receipt.gradle == approved.gradle.version", inventory)
        self.assertIn("assert receipt.agp == approved.agp.version", inventory)
        for module in ("kotlin-gradle-plugin", "kotlin-compiler-embeddable",
                       "kotlin-compose-compiler-plugin-embeddable"):
            self.assertIn("'" + module + "', '2.4.20'", inventory)
        self.assertIn("assert receipt.javaRuntime == '17.0.20.1+1'", inventory)
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
            "android-actions/setup-android": ("be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd", "v4.0.4", 1),
        }
        uses = re.findall(r"(?m)^\s*-?\s*uses: (\S+) # (v[\d.]+)$", workflow)
        self.assertEqual(len(uses), len(re.findall(r"(?m)^\s*-?\s*uses:", workflow)))
        self.assertEqual(len(uses), sum(value[2] for value in pins.values()))
        for action, (sha, version, count) in pins.items():
            self.assertEqual(uses.count((action + "@" + sha, version)), count, action)
        configured = {
            "actions/setup-java": {
                "distribution": "jdkfile", "java-version": "17.0.20+101", "java-package": "jdk",
                "architecture": "x64", "jdk-file": "${{ runner.temp }}/m1-jdk.tar.gz",
                "force-download": "true", "check-latest": "false", "set-default": "true",
                "cache-jdk": "false", "overwrite-settings": "false",
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
        for job, home in (("checkpoint", "m1-gradle-strict"), ("native", "m1-gradle-native-strict")):
            body = workflow.split("  " + job + ":\n", 1)[1].split("\n  native:\n", 1)[0]
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
                if action == "android-actions/setup-android" and job == "checkpoint":
                    self.assertEqual(found, [])
                    continue
                self.assertEqual(len(found), 1, action)
                self.assertNotRegex(found[0], r"(?m)^        (?:if|continue-on-error):")
                actual = {key: value.split(" #", 1)[0].strip().strip("'\"")
                          for key, value in re.findall(r"(?m)^          ([\w-]+): (.*)$", found[0])}
                self.assertEqual(actual, inputs, action)
            download = body.index("name: Download and verify exact approved JDK archive")
            java = body.index("uses: actions/setup-java@")
            sdk = body.index("run: bash tools/build/install-hosted-toolchain.sh")
            gradle = body.index("uses: gradle/actions/setup-gradle@")
            self.assertLess(download, java)
            self.assertLess(java, sdk)
            self.assertLess(sdk, gradle)
            self.assertIn("--max-time 180", body[download:java])
            self.assertIn("3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e", body[download:java])
            self.assertIn("sha256sum --check", body[download:java])
            self.assertIn("setup-timings.txt", body)
            self.assertNotIn("steps.gradle.outputs.cache", body)

    def test_actions_cache_contract_rejects_synthetic_regressions(self):
        workflow = (ROOT / ".github/workflows/m1-toolchain.yml").read_text()
        for old, new in (
            ("cache-provider: basic", "cache-provider: enhanced"),
            ("cache-disabled: false", "cache-disabled: true"),
            ("cache-read-only: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository }}", "cache-read-only: true"),
            ("force-download: true", "force-download: false"),
            ("java-version: '17.0.20+101'", "java-version: '17.0.20.1+1'"),
            ("packages: ''", "packages: 'build-tools;37.0.0'"),
            ("cache-provider: basic", "cache-provider: basic\n          cache-write-only: true"),
            ("m1-gradle-native-strict\n", "unrestored-home\n"),
            ("          set -o pipefail", "          export GRADLE_USER_HOME=/unrestored\n          set -o pipefail"),
            ("# v6.4.0", "# v6.3.0"),
        ):
            with self.subTest(mutation=new):
                self.assertIn(old, workflow)
                with self.assertRaises(AssertionError):
                    self.assert_cached_actions_setup(workflow.replace(old, new, 1))

    def test_read_only_routes_require_cached_homes_and_real_strict_execution(self):
        workflow = (ROOT / ".github/workflows/m1-toolchain.yml").read_text()
        self.assertIn("  pull_request:", workflow)
        self.assertIn("  push:", workflow)
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertIn("runs-on: ubuntu-24.04", workflow)
        self.assertIn("  contents: read", workflow)
        self.assertEqual(workflow.count("persist-credentials: false"), 2)
        self.assertFalse((ROOT / "gradle/m1-bootstrap-diagnostic").exists())
        for unsafe in ("pull_request_target", "self-hosted", "secrets.", "continue-on-error:", "paths:",
                       "discover_dependencies", "m1-bootstrap-diagnostic", "m1-gradle-discovery",
                       "--write-verification-metadata", "--dependency-verification off", "actions/cache"):
            self.assertNotIn(unsafe, workflow)
        self.assert_cached_actions_setup(workflow)
        strict = workflow.split("- name: Strict committed verification", 1)[1].split("- name: Bind evidence", 1)[0]
        self.assertIn("--dependency-verification strict", strict)
        for flag in ("--no-build-cache", "--no-configuration-cache", "--rerun-tasks"):
            self.assertIn(flag, strict)
        for task in ("lintDebug", "assembleDebug", "compileDebugUnitTestKotlin",
                     "compileDebugAndroidTestKotlin", "testDebugUnitTest", "assembleDebugAndroidTest"):
            self.assertIn(":app:" + task, strict)
        self.assertIn("verify_m1_manifests.py", strict)
        self.assertIn("verify_m1_test_reports.py jvm", strict)
        self.assertIn("committed-integrity-provenance.json", workflow)
        self.assertIn("if: always()", workflow.split("- name: Upload", 1)[1])

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
        self.assertIn("write_sdk_package_metadata.py", (ROOT / "tools/build/install-hosted-toolchain.sh").read_text())

    def test_no_network_permission_and_honest_four_state_shell(self):
        ns = "{http://schemas.android.com/apk/res/android}"
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        self.assertEqual(manifest.findall("uses-permission"), [])
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
