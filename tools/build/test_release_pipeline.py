"""Source/YAML and synthetic compiler evidence contracts; no real tool execution."""
from pathlib import Path
import subprocess
import sys
import unittest

from verify_gradle_execution import validate, REQUIRED

ROOT = Path(__file__).resolve().parents[2]


class ReleasePipelineTests(unittest.TestCase):
    def test_new_android_consumers_import_without_policy_site_packages(self):
        # Schema validation is explicit; SDK/runtime consumers remain stdlib-only at import.
        consumers = ('test_release_pipeline', 'test_release_artifact', 'test_version_identity',
                     'release_artifact', 'version_identity', 'hosted_release_fixtures', 'verify_gradle_execution',
                     'release_runtime', 'release_bundle', 'test_release_runtime', 'test_release_bundle')
        script = 'import importlib, sys; sys.path.insert(0, sys.argv[1]); ' \
                 '[importlib.import_module(name) for name in sys.argv[2:]]'
        result = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', script,
                                 str(ROOT / 'tools/build'), *consumers],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_release_lint_preserves_only_agp_redundant_vital_suppression(self):
        log = '\n'.join('GATE_TASK_OUTCOME :app:' + name + ' SUCCESS' for name in REQUIRED['build'])
        for name in ('lintVitalRelease', 'lintVitalReportRelease'):
            valid = log + f'\nGATE_TASK_OUTCOME :app:{name} SKIPPED\n> Task :app:{name} SKIPPED'
            validate(valid, 'build')
            partner = name.replace('Vital', '')
            with self.assertRaises(ValueError):
                validate(valid.replace(partner + ' SUCCESS', partner + ' SKIPPED'), 'build')
            with self.assertRaises(ValueError):
                validate(valid.replace(f'{name} SKIPPED', f'{name} FROM_CACHE'), 'build')

    def test_runtime_observation_is_release_only_and_fresh_without_application_changes(self):
        app = (ROOT / 'app/build.gradle').read_text()
        collector = app.split('abstract class ReleaseRuntimeInputs', 1)[1].split('// One resolver', 1)[0]
        for text in ('runtimeConfiguration.incoming.resolutionResult.allComponents',
                     'runtimeConfiguration.incoming.artifacts.artifacts', 'artifact.id.componentIdentifier',
                     'variant.externalVariant', 'dep.constraint', 'sha(file.bytes)'):
            self.assertIn(text, collector)
        for forbidden in ('compileClasspath', 'configurations.findAll', 'testRuntimeClasspath', 'zipTree'):
            self.assertNotIn(forbidden, collector)
        registration = app.split("withBuildType('release')", 1)[1].split('def debugClasses', 1)[0]
        self.assertIn('variant.runtimeConfiguration', registration)
        self.assertIn('SingleArtifact.APK.INSTANCE', registration)
        self.assertIn('ScopedArtifacts.Scope.PROJECT', registration)
        self.assertIn("destination.set(layout.buildDirectory.dir('runtime-release'))", registration)
        self.assertIn('collectReleaseRuntimeInventory', REQUIRED['build'])
        self.assertNotIn('collectReleaseRuntimeInventory', REQUIRED['native'])

    def test_selected_archive_identity_is_shared_with_fail_closed_consumer(self):
        collector = (ROOT / 'app/build.gradle').read_text().split(
            'abstract class ReleaseRuntimeInputs', 1)[1].split('// One resolver', 1)[0]
        # One physical core AAR was exposed by publication and ad-hoc variants.
        # Canonicalize at the producer, not by relaxing the consumer's uniqueness check.
        for text in ('def artifactsByKey = [:]', 'def key = [row.component, row.filename]',
                     'artifactsByKey.containsKey(key) && artifactsByKey[key] != row',
                     'throw new GradleException("Conflicting resolved runtime artifact: ${key}")',
                     'artifactsByKey[key] = row', 'artifactsByKey.values().toList().sort'):
            self.assertIn(text, collector)
        collection = collector.split('runtimeConfiguration.incoming.artifacts.artifacts.each', 1)[1]
        self.assertLess(collection.index('addVariant(artifact.variant)'), collection.index('artifactsByKey[key] = row'))

    def test_runtime_sidecars_are_mandatory_hosted_build_and_existing_fixture_consumers(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        build = workflow.split('  instrumented:', 1)[0]
        self.assertIn(':app:collectReleaseRuntimeInventory', build)
        self.assertIn('app/build/runtime-release/', build.split('Upload build logs and outputs (all outcomes)', 1)[1])
        block = build.split('- name: Create and rederive APK-bound runtime SBOM and notices', 1)[1].split('- name:', 1)[0]
        for text in ('for operation in create-unsigned verify-unsigned',
                     'android-build-contract-venv/bin/python', '--expected-sha "$EXPECTED_SOURCE_SHA"',
                     '--artifact-manifest evidence/release/artifact-manifest.json',
                     '--resolution app/build/runtime-release/runtime-resolution.json',
                     '--bundle evidence/release/bundle'):
            self.assertIn(text, block)
        for forbidden in ('continue-on-error:', 'if:', '|| true', 'bind-final', 'apksigner sign'):
            self.assertNotIn(forbidden, block)
        fixtures = (ROOT / 'tools/build/hosted_release_fixtures.py').read_text()
        for text in (':app:collectReleaseRuntimeInventory', 'unsigned_files(root, expected',
                     'verify_files(capsule, unsigned_files(', 'write_bundle(capsule, files'):
            self.assertIn(text, fixtures)
        self.assertIn('timeout-minutes: 15', build.split('Run isolated hosted version and release fixtures', 1)[1])
        self.assertIn('MaxMetaspaceSize=768m', build)

    def test_gradle_package_and_single_version_resolver_contract(self):
        app = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("namespace 'io.github.leugenea.codexbarmobile'", app)
        self.assertIn("applicationId 'io.github.leugenea.codexbarmobile'", app)
        self.assertIn("applicationIdSuffix '.dev'", app)
        self.assertIn('versionCode buildIdentity.versionCode', app)
        self.assertIn('versionName buildIdentity.versionName', app)
        self.assertEqual(app.count('version_identity.py'), 1)
        self.assertIn('android:name="io.github.leugenea.codexbarmobile.MainActivity"',
                      (ROOT / 'app/src/main/AndroidManifest.xml').read_text())
        fixtures = (ROOT / 'tools/build/hosted_release_fixtures.py').read_text()
        self.assertIn('Hosted runner only', fixtures)
        self.assertIn("dir=os.environ['RUNNER_TEMP']", fixtures)
        self.assertNotIn('push', fixtures)
        self.assertNotIn("'fetch'", (ROOT / 'tools/build/version_identity.py').read_text().split('def git', 1)[1])


if __name__ == '__main__':
    unittest.main()
