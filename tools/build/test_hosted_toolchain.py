"""Offline hosted-toolchain metadata parser tests; never probe Java or an SDK."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class HostedToolchainTests(unittest.TestCase):
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
