"""Synthetic archives and SDK text only; no Android/JVM execution or signing."""
import copy
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import release_runtime as runtime
import release_bundle as bundle
from test_release_artifact import EXPECTED


COMPONENT = 'sample:runtime:1.0'


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as z:
        for name, data in entries:
            z.writestr(name, data)
    return output.getvalue()


class RuntimeFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / f'codexbar-mobile-{EXPECTED["versionName"]}-1-unsigned.apk'
        self.jar = archive([('example/Library.class', b'SYNTHETIC class'), ('config.properties', b'CONFIG')])
        self.nested = archive([('nested/Extra.class', b'SYNTHETIC nested class')])
        self.outer = self.root / 'runtime.aar'
        self.outer.write_bytes(archive([('classes.jar', self.jar), ('libs/extra.jar', self.nested),
                                       ('AndroidManifest.xml', b'<manifest package="library.example"/>'),
                                       ('res/values/values.xml', b'<resources/>'), ('R.txt', b'int string x 0'),
                                       ('jni/x86/libexample.so', b'NATIVE'), ('assets/list.dat', b'DATA')]))
        self.apk.write_bytes(archive([('AndroidManifest.xml', b'SYNTHETIC binary XML'), ('res/a.xml', b'RENAMED XML'),
                                     ('classes.dex', b'SYNTHETIC dex, not executable'), ('resources.arsc', b'SYNTHETIC resources'),
                                     ('lib/x86/libexample.so', b'NATIVE'), ('assets/list.dat', b'DATA'),
                                     ('config.properties', b'CONFIG')]))
        self.classes = ['example.App', 'example.App$$ExternalSyntheticLambda0', 'example.Library',
                        'library.example.R', 'library.example.R$string', 'nested.Extra']
        self.raw = {'schema': 1, 'kind': 'release-runtime-resolution', 'variant': 'release',
                    'configuration': 'releaseRuntimeClasspath', 'buildInputs': EXPECTED,
                    'apk': runtime.file_record(self.apk), 'projectClasses': ['example.App'],
                    'components': [{'id': x, 'variants': []} for x in ['app', COMPONENT, 'sample:bom:1.0']],
                    'edges': [{'from': 'app', 'to': COMPONENT, 'constraint': False},
                              {'from': 'app', 'to': 'sample:bom:1.0', 'constraint': True}],
                    'artifacts': [{'component': COMPONENT, 'path': str(self.outer), **runtime.file_record(self.outer)}]}
        legal = self.root / bundle.LEGAL
        legal.mkdir(parents=True)
        (legal / 'license.txt').write_text('SYNTHETIC license, not a legal assertion\n')
        self.evidence = {'path': 'license.txt', **runtime.fingerprint((legal / 'license.txt').read_bytes()),
                         'source': 'https://example.invalid/version1/LICENSE', 'scope': 'Synthetic only', 'role': 'license'}
        self.catalog = {'schema': 1, 'kind': 'runtime-attribution-catalog', 'policy': 'Synthetic fixture',
                        'components': [{'id': COMPONENT, 'name': 'sample:runtime', 'version': '1.0',
                                        'license': 'Apache-2.0', 'evidence': [self.evidence],
                                        'noticeScope': 'Synthetic versioned source', 'disclosure': 'Not production evidence'}],
                        'embedded': [{'id': COMPONENT + '#nested-native', 'parent': COMPONENT,
                                      'name': 'Synthetic embedded portions', 'version': None,
                                      'license': 'Apache-2.0', 'evidence': [self.evidence],
                                      'noticeScope': 'Synthetic scope', 'disclosure': 'Standalone version unknown',
                                      'selector': {'members': [{'member': 'libs/extra.jar', 'sha256': runtime.fingerprint(self.nested)['sha256']},
                                                               {'member': 'jni/x86/libexample.so', 'sha256': runtime.fingerprint(b'NATIVE')['sha256']}]}}]}
        (self.root / 'LICENSE').write_text('SYNTHETIC application MIT fixture\n')
        (self.root / bundle.CATALOG).parent.mkdir(parents=True)
        self.save_catalog()
        shutil.copytree(runtime.ROOT / 'tools/build/schemas', self.root / 'tools/build/schemas')
        self.inventory = runtime.reconcile(self.raw, EXPECTED, self.apk, self.classes)
        self.receipt = {'schema': 1, 'runId': '1', 'runAttempt': '1', 'buildInputs': EXPECTED,
                        'source': EXPECTED['source'], **runtime.file_record(self.apk), 'inspection': {'unsigned': True}}

    def save_catalog(self):
        (self.root / bundle.CATALOG).write_bytes(runtime.canonical(self.catalog))

    def files(self):
        return bundle.render(self.root, self.inventory, self.receipt)


class RuntimeTests(RuntimeFixture):
    def test_exact_runtime_graph_members_and_embedded_hashes_without_tooling_graph(self):
        self.assertEqual(self.inventory['unresolved'], [])
        nodes = {r['id']: r['status'] for r in self.inventory['resolution']['components']}
        self.assertEqual(nodes, {COMPONENT: 'observed', 'sample:bom:1.0': 'constraint-only'})
        self.assertEqual(len(self.inventory['artifacts'][0]['nested']), 2)
        self.assertEqual(self.inventory['artifacts'][0]['definedClasses'], ['example.Library', 'nested.Extra'])
        text = runtime.canonical(self.inventory).decode()
        self.assertNotIn(str(self.root), text)
        members = {r['path']: r for r in self.inventory['members']}
        self.assertEqual(members['res/a.xml']['kind'], 'android-resource-merge')
        self.assertEqual(members['lib/x86/libexample.so']['inputs'][0]['member'], 'jni/x86/libexample.so')
        self.assertEqual({r['kind'] for r in self.inventory['classes']},
                         {'project', 'runtime-input', 'generated-resource-class', 'd8-synthetic'})

    def test_resolution_omissions_duplicates_wrong_identity_and_hashes_fail(self):
        for mutate in (
            lambda r: r.update(schema=True), lambda r: r.update(variant='debug'),
            lambda r: r.update(configuration='debugRuntimeClasspath'), lambda r: r['components'].pop(),
            lambda r: r['components'].append(copy.deepcopy(r['components'][1])), lambda r: r['edges'].pop(0),
            lambda r: r['edges'].append(copy.deepcopy(r['edges'][0])),
            lambda r: r['artifacts'][0].update(sha256='0' * 64), lambda r: r['artifacts'][0].update(component='unknown'),
            lambda r: r['artifacts'].append(copy.deepcopy(r['artifacts'][0])),
            lambda r: r.update(artifacts=[]), lambda r: r.update(buildInputs={**EXPECTED, 'versionCode': 4}),
            lambda r: r['apk'].update(sha256='0' * 64),
        ):
            raw = copy.deepcopy(self.raw)
            mutate(raw)
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                runtime.reconcile(raw, EXPECTED, self.apk, self.classes)

    def test_selected_archive_once_retains_variants_but_duplicate_or_conflicting_receipts_fail(self):
        raw = copy.deepcopy(self.raw)
        raw['components'][1]['variants'] = [
            {'name': name, 'attributes': {'artifactType': 'aar'}, 'capabilities': [COMPONENT]}
            for name in ('releaseVariantReleaseRuntimePublication',
                         COMPONENT + ' variant releaseVariantReleaseRuntimePublication',
                         'adhoc variant for ' + COMPONENT)
        ]
        inventory = runtime.reconcile(raw, EXPECTED, self.apk, self.classes)
        self.assertEqual(len(inventory['artifacts']), 1)
        selected = next(row for row in inventory['resolution']['components'] if row['id'] == COMPONENT)
        self.assertEqual(selected['variants'], sorted(raw['components'][1]['variants'], key=runtime.canonical))
        for changes in ({}, {'path': str(self.root / 'other.aar')},
                        {'size': raw['artifacts'][0]['size'] + 1}, {'sha256': '0' * 64}):
            duplicate = {**raw['artifacts'][0], **changes}
            bad = {**raw, 'artifacts': [raw['artifacts'][0], duplicate]}
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'Duplicate resolved artifact'):
                runtime.reconcile(bad, EXPECTED, self.apk, self.classes)
        # Distinct classifier filenames are separate artifact identities, not collapsed by component.
        extra = self.root / 'runtime-extra.jar'
        extra.write_bytes(archive([('other/Extra.class', b'SYNTHETIC extra')]))
        raw['artifacts'].append({'component': COMPONENT, 'path': str(extra), **runtime.file_record(extra)})
        inventory = runtime.reconcile(raw, EXPECTED, self.apk, self.classes + ['other.Extra'])
        self.assertEqual([row['filename'] for row in inventory['artifacts']], ['runtime-extra.jar', 'runtime.aar'])

    def test_unknown_and_duplicate_classes_are_not_silently_assigned(self):
        for name in ['unknown.Library', 'absent.Context$$ExternalSyntheticLambda0', 'j$.time.Clock']:
            inv = runtime.reconcile(self.raw, EXPECTED, self.apk, self.classes + [name])
            self.assertTrue(inv['unresolved'])
            with self.assertRaisesRegex(ValueError, 'Unresolved'):
                bundle.render(self.root, inv, self.receipt)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            runtime.reconcile(self.raw, EXPECTED, self.apk, self.classes + [self.classes[0]])
        raw = copy.deepcopy(self.raw)
        raw['projectClasses'].append('example.Library')
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            runtime.reconcile(raw, EXPECTED, self.apk, self.classes)

    def test_defined_only_parser_never_counts_reference_or_method_rows(self):
        text = 'P d 1 1 0 example\n C d 1 1 7 example.Library\n M d 0 1 7 example.Library.run()\n C r 0 0 0 unused.Reference\n'
        self.assertEqual(runtime.defined_classes(text), ['example.Library'])
        for bad in ('C r 0 0 0 unused.Reference', 'C d 0 0 0 malformed/name', text + text):
            with self.assertRaises(ValueError):
                runtime.defined_classes(bad)
        result = runtime.subprocess.CompletedProcess([], 1, '', 'tool failure')
        with patch.object(runtime.subprocess, 'run', return_value=result), self.assertRaisesRegex(ValueError, 'apkanalyzer'):
            runtime.observe_classes(self.apk, self.root, self.root / 'reports')
        self.assertEqual((self.root / 'reports/dex-packages-stderr.txt').read_text(), 'tool failure')

    def test_zip_and_bundle_paths_duplicates_and_symlinks_fail(self):
        for name in ('../x', '/x', 'a/../x', 'a//x', './x', 'a\\x', 'a\x00x', 'C:/x'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                runtime.safe_name(name)
        for data in (archive([('../x', b'x')]), archive([('same', b'1'), ('same', b'2')])):
            with self.assertRaises(ValueError):
                runtime.members(data)
        (self.root / 'escape').symlink_to(self.root.parent)
        with self.assertRaisesRegex(ValueError, 'escape|Symlink'):
            runtime.confined(self.root, 'escape/file')
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as z:
            info = zipfile.ZipInfo('link')
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            z.writestr(info, '../target')
        with self.assertRaisesRegex(ValueError, 'symlinks'):
            runtime.members(data.getvalue())

    def test_json_duplicate_keys_float_and_nonfinite_rejected(self):
        p = self.root / 'bad.json'
        for data in ('{"schema":1,"schema":1}', '{"n":1.0}', '{"n":NaN}', '{"n":Infinity}'):
            p.write_text(data)
            with self.assertRaises(ValueError):
                runtime.load(p)

    def test_unknown_payload_and_native_or_data_drift_cannot_be_complete(self):
        with zipfile.ZipFile(self.apk, 'a') as z:
            z.writestr('unknown.bin', b'new data')
        raw = copy.deepcopy(self.raw)
        raw['apk'] = runtime.file_record(self.apk)
        inv = runtime.reconcile(raw, EXPECTED, self.apk, self.classes)
        self.assertIn('Unmapped APK member: unknown.bin', inv['unresolved'])


class BundleTests(RuntimeFixture):
    def test_deterministic_cyclonedx_notices_and_exact_member_bindings(self):
        files = self.files()
        self.assertEqual(files, self.files())
        self.assertNotIn(str(self.root).encode(), b''.join(files.values()))
        bom = json.loads(files['runtime.cdx.json'])
        self.assertEqual([r['bom-ref'] for r in bom['components']], [COMPONENT, COMPONENT + '#nested-native'])
        self.assertNotIn('sample:bom:1.0', [r['bom-ref'] for r in bom['components']])
        embedded = bom['components'][1]
        self.assertNotIn('version', embedded)
        self.assertNotIn('purl', embedded)
        self.assertIn(b'Standalone version: unknown', files['THIRD_PARTY_NOTICES.txt'])
        manifest = json.loads(files['bundle-manifest.json'])
        self.assertFalse(manifest['distributionApproval'])
        self.assertEqual(manifest['apk'], runtime.file_record(self.apk))
        for row in manifest['files']:
            self.assertEqual(runtime.fingerprint(files[row['path']]), {k: row[k] for k in ('size', 'sha256')})
        one, two = self.root / 'one', self.root / 'two'
        bundle.write_bundle(one, files, [self.apk])
        bundle.write_bundle(two, files, [self.apk])
        bundle.verify_files(one, files)
        for name in files:
            self.assertEqual((one / name).read_bytes(), (two / name).read_bytes())

    def test_bcp47_merged_resource_names_pass_without_allowing_unsafe_paths(self):
        resource = 'res/values-b+sr+Latn/values-b+sr+Latn.xml'
        self.outer.write_bytes(archive([*runtime.members(self.outer.read_bytes()).items(), (resource, b'<resources/>')]))
        self.raw['artifacts'][0].update(runtime.file_record(self.outer))
        inventory = runtime.reconcile(self.raw, EXPECTED, self.apk, self.classes)
        self.assertIn(resource, inventory['artifacts'][0]['mergedInputs'])
        files = bundle.render(self.root, inventory, self.receipt)
        self.assertIn(resource.encode(), files['runtime-inventory.json'])
        from jsonschema import ValidationError
        for path in ('../' + resource, '/' + resource, 'res/../values+x.xml',
                     'res//values+x.xml', 'res/./values+x.xml', 'res/values+x\\\\bad.xml', 'res/values+x\x00.xml'):
            bad = copy.deepcopy(inventory)
            bad['artifacts'][0]['mergedInputs'] = [path]
            with self.subTest(path=path), self.assertRaises(ValidationError):
                bundle.render(self.root, bad, self.receipt)

    def test_notice_omission_duplicate_bad_hash_and_parent_binding_rejected(self):
        original = copy.deepcopy(self.catalog)
        for mutation in (
            lambda c: c.update(components=[]), lambda c: c.update(embedded=[]),
            lambda c: c['components'].append(copy.deepcopy(c['components'][0])),
            lambda c: c['embedded'][0].update(parent='unknown:parent:1'),
            lambda c: c['embedded'][0]['selector']['members'][0].update(sha256='0' * 64),
            lambda c: c['components'][0]['evidence'][0].update(sha256='0' * 64),
            lambda c: c['components'][0]['evidence'][0].update(path='../LICENSE'),
            lambda c: c['components'][0]['evidence'].append(copy.deepcopy(c['components'][0]['evidence'][0])),
            lambda c: c['components'][0].update(license=''),
            lambda c: c['components'][0].update(evidence=[]),
        ):
            self.catalog = copy.deepcopy(original)
            mutation(self.catalog)
            self.save_catalog()
            with self.subTest(catalog=self.catalog), self.assertRaises(ValueError):
                self.files()
        self.catalog = original
        self.save_catalog()
        (self.root / bundle.LEGAL / 'license.txt').write_text('substituted license')
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            self.files()

    def test_renderer_rejects_artifact_identity_conflicts(self):
        for mutate in (lambda r: r.update(filename='different.apk'),
                       lambda r: r.update(sha256='0' * 64), lambda r: r.update(size=r['size'] + 1),
                       lambda r: r.update(source={**EXPECTED['source'], 'sha': 'b' * 40}),
                       lambda r: r.update(buildInputs={**EXPECTED, 'versionCode': 2}),
                       lambda r: r['inspection'].update(unsigned=False)):
            receipt = copy.deepcopy(self.receipt)
            mutate(receipt)
            with self.assertRaisesRegex(ValueError, 'identity differ'):
                bundle.render(self.root, self.inventory, receipt)

    def test_sidecar_omission_addition_and_one_byte_substitutions_fail(self):
        files = self.files()
        directory = self.root / 'bundle'
        bundle.write_bundle(directory, files, [self.apk])
        for name in files:
            path = directory / name
            path.write_bytes(files[name] + b'x')
            with self.subTest(name=name), self.assertRaises(ValueError):
                bundle.verify_files(directory, files)
            path.unlink()
            with self.assertRaises(ValueError):
                bundle.verify_files(directory, files)
            path.write_bytes(files[name])
        (directory / 'unlisted-directory-link').symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'Symlinks'):
            bundle.verify_files(directory, files)
        (directory / 'unlisted-directory-link').unlink()
        (directory / 'unlisted').write_text('unlisted')
        with self.assertRaises(ValueError):
            bundle.verify_files(directory, files)

    def test_output_never_overwrites_inputs_or_existing_capsules(self):
        files = self.files()
        for output in (self.root, self.apk, self.apk / 'child'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                bundle.write_bundle(output, files, [self.apk])
        out = self.root / 'bundle'
        bundle.write_bundle(out, files, [self.apk])
        with self.assertRaisesRegex(ValueError, 'empty'):
            bundle.write_bundle(out, files, [self.apk])

    def test_schema_rejects_unknown_versions_and_inconsistent_signed_state(self):
        files = self.files()
        inv = json.loads(files['runtime-inventory.json'])
        manifest = json.loads(files['bundle-manifest.json'])
        bom = json.loads(files['runtime.cdx.json'])
        from jsonschema import ValidationError
        for bad_inv, bad_manifest, bad_bom in (
            ({**inv, 'schema': 2}, manifest, bom), (inv, {**manifest, 'stage': 'signed-maybe'}, bom),
            (inv, {**manifest, 'stage': 'final-signed'}, bom), (inv, {**manifest, 'distributionApproval': True}, bom),
            (inv, manifest, {**bom, 'specVersion': '0.1'}),
        ):
            with self.subTest(manifest=bad_manifest), self.assertRaises(ValidationError):
                bundle.validate_schemas(self.root, bad_inv, bad_manifest, bad_bom)


if __name__ == '__main__':
    unittest.main()
