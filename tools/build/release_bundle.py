"""Deterministic APK-bound runtime attribution sidecars; never builds or signs an APK."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

import release_artifact as artifact
from release_runtime import (ROOT, canonical, confined, file_record, fingerprint, load,
                             members, observe_classes, payload, reconcile, safe_name, unique)
from version_identity import git, resolve

CATALOG = 'tools/build/runtime-licenses.json'
LEGAL = 'docs/legal/runtime'
DISCLAIMER = ('Bounded source-backed attribution, not a legal warranty, universal historical '
              'notice-completeness proof or owner distribution/publication approval.')


def attribution(root, inventory):
    catalog = load(root / CATALOG)
    if (catalog.get('schema') != 1 or type(catalog.get('schema')) is not int
            or catalog.get('kind') != 'runtime-attribution-catalog'):
        raise ValueError('Unknown attribution catalog schema')
    entries = catalog['components'] + catalog['embedded']
    unique([r['id'] for r in entries], 'attribution component')
    by_id = {r['id']: r for r in catalog['components']}
    shipping = {r['id'] for r in inventory['resolution']['components'] if r['status'] in ('observed', 'merged-contributor')}
    missing = shipping - by_id.keys()
    if missing:
        raise ValueError('Missing source-backed runtime attribution: ' + ', '.join(sorted(missing)))
    selected = [by_id[key] for key in sorted(shipping)]
    covered = set()
    for entry in catalog['embedded']:
        if entry['parent'] not in by_id:
            raise ValueError('Unknown embedded parent: ' + entry['parent'])
        if entry['parent'] not in shipping:
            continue
        selector = entry['selector']
        if set(selector) == {'members'}:
            for expected in selector['members']:
                safe_name(expected['member'])
                matches = []
                for a in inventory['artifacts']:
                    if a['component'] != entry['parent']:
                        continue
                    matches.extend(r for r in a['nested'] if r['member'] == expected['member'])
                    # Native/data bytes were independently joined to actual APK members.
                    matches.extend(i for row in inventory['members'] for i in row['inputs']
                                   if i['component'] == entry['parent'] and i['member'] == expected['member'])
                if not matches or any(r['sha256'] != expected['sha256'] for r in matches):
                    raise ValueError('Embedded member identity mismatch: ' + entry['id'])
                covered.add((entry['parent'], expected['member']))
        elif set(selector) == {'classPrefixes'}:
            prefixes = selector['classPrefixes']
            if not prefixes or not all(isinstance(p, str) and p for p in prefixes):
                raise ValueError('Invalid embedded source selector')
            if not any(row['component'] == entry['parent'] and row['name'].startswith(tuple(prefixes))
                       for row in inventory['classes']):
                continue  # Not observed; do not falsely list absent source portions as shipped.
        else:
            raise ValueError('Unsupported embedded selector')
        selected.append(entry)
    for a in inventory['artifacts']:
        for nested in a['nested']:
            if nested['member'].startswith('libs/') and (a['component'], nested['member']) not in covered:
                raise ValueError('Missing nested library attribution: ' + a['component'] + '!' + nested['member'])
    for row in inventory['members']:
        if row['path'].startswith('lib/'):
            if not row['inputs'] or any((i['component'], i['member']) not in covered for i in row['inputs']):
                raise ValueError('Missing native embedded attribution: ' + row['path'])
    files = {}
    for entry in sorted(selected, key=lambda r: r['id']):
        if (not entry['license'] or not entry['noticeScope'] or not entry['disclosure'] or not entry['evidence']
                or not any(e['role'] == 'license' for e in entry['evidence'])):
            raise ValueError('Incomplete attribution: ' + entry['id'])
        unique([r['path'] for r in entry['evidence']], 'component notice binding')
        for evidence in entry['evidence']:
            data = confined(root / LEGAL, evidence['path']).read_bytes()
            if fingerprint(data) != {k: evidence[k] for k in ('size', 'sha256')}:
                raise ValueError('License/NOTICE/source bytes changed: ' + evidence['path'])
            if not evidence['source'].startswith('https://') or not evidence['scope']:
                raise ValueError('Missing source/scope for attribution')
            files['legal/' + evidence['path']] = data
    # First-party permission/copyright text accompanies every capsule.
    files['legal/application-MIT.txt'] = (root / 'LICENSE').read_bytes()
    return sorted(selected, key=lambda r: r['id']), files


def notices(entries, files):
    lines = ['CodexBar Mobile runtime attributions', DISCLAIMER, '',
             'Each component below maps to the full texts following this index.',
             'Embedded standalone version "unknown" is intentional, not a guessed Maven version.', '']
    for entry in entries:
        lines += [entry['id'], 'License: ' + entry['license'],
                  'Standalone version: ' + (entry['version'] or 'unknown'),
                  entry['noticeScope'], entry['disclosure']]
        for e in entry['evidence']:
            lines += [f"  {e['role']}: legal/{e['path']} (SHA-256 {e['sha256']})",
                      '  Source: ' + e['source'], '  Scope: ' + e['scope']]
        lines.append('')
    for name, data in sorted(files.items()):
        if name.endswith(('.dat', '.kt')):  # Corresponding source is delivered, not printed into a notice.
            continue
        lines += ['=' * 72, name, '=' * 72, data.decode('utf-8'), '']
    return ('\n'.join(lines).rstrip() + '\n').encode('utf-8')


def sbom(inventory, entries, apk_record):
    expected = inventory['buildInputs']
    components = []
    for entry in entries:
        hashes = [{'alg': 'SHA-256', 'content': a['sha256']} for a in inventory['artifacts'] if a['component'] == entry['id']]
        component = {'type': 'data' if entry['id'].endswith(('#public-suffix-list', '#unicode-idna')) else 'library',
                     'bom-ref': entry['id'], 'name': entry['name'],
                     'licenses': [{'expression': entry['license']}],
                     'properties': [{'name': 'release:notice-paths', 'value': ','.join('legal/' + e['path'] for e in entry['evidence'])},
                                    {'name': 'release:scope-disclosure', 'value': entry['disclosure']}]}
        if entry['version'] is not None:
            component['version'] = entry['version']
        else:
            component['properties'].append({'name': 'release:standalone-version', 'value': 'unknown'})
        if hashes:
            component['hashes'] = sorted({r['content']: r for r in hashes}.values(), key=lambda r: r['content'])
        if 'parent' in entry:
            component['properties'].append({'name': 'release:embedded-parent', 'value': entry['parent']})
        else:
            group, name, version = entry['id'].split(':')
            component['purl'] = f'pkg:maven/{group}/{name}@{version}'
        components.append(component)
    included = {c['bom-ref'] for c in components}
    edges = inventory['resolution']['edges']
    dependencies = []
    for node in ['app', *sorted(included)]:
        reached, pending, targets = set(), [node], set()
        while pending:
            current = pending.pop()
            if current in reached:
                continue
            reached.add(current)
            for edge in edges:
                if edge['from'] == current and not edge['constraint']:
                    if edge['to'] in included:
                        targets.add(edge['to'])
                    else:
                        pending.append(edge['to'])
        targets.update(e['id'] for e in entries if e.get('parent') == node)
        targets.discard(node)
        dependencies.append({'ref': node, 'dependsOn': sorted(targets)})
    return {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
            'metadata': {'component': {'type': 'application', 'bom-ref': 'app', 'name': 'CodexBar Mobile',
                                       'version': expected['versionName'], 'licenses': [{'license': {'id': 'MIT'}}],
                                       'hashes': [{'alg': 'SHA-256', 'content': apk_record['sha256']}],
                                       'properties': [{'name': 'release:source-sha', 'value': expected['source']['sha']},
                                                      {'name': 'release:version-code', 'value': str(expected['versionCode'])},
                                                      {'name': 'release:apk-filename', 'value': apk_record['filename']},
                                                      {'name': 'release:boundary', 'value': DISCLAIMER}]}},
            'components': components, 'dependencies': dependencies}


def string_map_unique_items(validator, enabled, instance, schema):
    """Keep exact uniqueItems semantics without pairwise comparison of class records."""
    from jsonschema import Draft7Validator, ValidationError
    if (enabled and validator.is_type(instance, 'array')
            and all(type(row) is dict and all(type(k) is str and type(v) is str for k, v in row.items())
                    for row in instance)):
        # Sorted complete key/value tuples preserve object equality and ignore key order.
        # Other JSON shapes use the stock comparison, including bool/number semantics.
        rows = [tuple(sorted(row.items())) for row in instance]
        if len(rows) != len(set(rows)):
            yield ValidationError('Array has non-unique elements')
    else:
        yield from Draft7Validator.VALIDATORS['uniqueItems'](validator, enabled, instance, schema)


def validate_schemas(root, inventory, manifest, bom, catalog=None):
    # Lazy dependency: stdlib-only Android consumers/import contracts remain usable.
    from jsonschema import Draft7Validator, ValidationError, validators
    from referencing import Registry, Resource
    if bom.get('bomFormat') != 'CycloneDX' or bom.get('specVersion') != '1.6':
        raise ValidationError('This pipeline requires CycloneDX JSON 1.6')
    schema_dir = root / 'tools/build/schemas'
    resources = []
    for name in ('bom-1.6.schema.json', 'spdx.schema.json', 'jsf-0.82.schema.json'):
        doc = load(schema_dir / 'cyclonedx' / name)
        resources.append((doc['$id'], Resource.from_contents(doc)))
    registry = Registry().with_resources(resources)
    inventory_validator = validators.extend(Draft7Validator, {'uniqueItems': string_map_unique_items})
    inventory_validator(load(schema_dir / 'runtime-inventory.schema.json'), registry=registry).validate(inventory)
    Draft7Validator(load(schema_dir / 'release-bundle.schema.json'), registry=registry).validate(manifest)
    Draft7Validator(load(schema_dir / 'cyclonedx/bom-1.6.schema.json'), registry=registry).validate(bom)
    if catalog is not None:
        Draft7Validator(load(schema_dir / 'runtime-catalog.schema.json'), registry=registry).validate(catalog)


def render(root, inventory, artifact_manifest, *, stage='unsigned', apk_record=None, signed=None, accepted=None):
    if (canonical(artifact_manifest['buildInputs']) != canonical(inventory['buildInputs'])
            or artifact_manifest['source'] != inventory['buildInputs']['source']
            or {k: artifact_manifest[k] for k in ('filename', 'size', 'sha256')} != inventory['apk']
            or artifact_manifest['inspection']['unsigned'] is not True):
        raise ValueError('Runtime inventory and unsigned artifact identity differ')
    if inventory['unresolved']:
        raise ValueError('Unresolved shipped inventory: ' + '; '.join(inventory['unresolved'][:10]))
    entries, files = attribution(root, inventory)
    inventory = {**inventory, 'attribution': entries}
    apk_record = inventory['apk'] if apk_record is None else apk_record
    bom = sbom(inventory, entries, apk_record)
    files.update({'runtime-inventory.json': canonical(inventory), 'runtime.cdx.json': canonical(bom),
                  'THIRD_PARTY_NOTICES.txt': notices(entries, files), 'artifact-manifest.json': canonical(artifact_manifest)})
    manifest = {'schema': 1, 'kind': 'release-runtime-bundle', 'stage': stage,
                'buildInputs': inventory['buildInputs'], 'apk': apk_record,
                'attributionStatus': 'bounded-source-backed', 'distributionApproval': False,
                'boundary': DISCLAIMER,
                'files': [{'path': name, **fingerprint(data)} for name, data in sorted(files.items())]}
    if stage == 'final-signed':
        manifest.update(signedInspection=signed, acceptedUnsigned=accepted)
    validate_schemas(root, inventory, manifest, bom, load(root / CATALOG))
    files['bundle-manifest.json'] = canonical(manifest)
    return files


def write_bundle(output, files, protected):
    destination = output.resolve()
    for path in protected:
        p = path.resolve()
        if destination == p or p.is_relative_to(destination) or destination.is_relative_to(p):
            raise ValueError('Bundle output must be separate from input APK/bundle paths')
    if output.is_symlink() or output.exists() and any(output.iterdir()):
        raise ValueError('Bundle creation requires a new empty output directory')
    output.mkdir(parents=True, exist_ok=True)
    for name, data in sorted(files.items()):
        p = confined(output, name)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
    verify_files(output, files)


def verify_files(output, expected):
    paths = list(output.rglob('*'))
    if output.is_symlink() or any(p.is_symlink() for p in paths):
        raise ValueError('Symlinks are not bundle members')
    actual = {p.relative_to(output).as_posix() for p in paths if not p.is_dir()}
    if actual != set(expected):
        raise ValueError('Bundle member omissions/additions: ' + repr(sorted(actual.symmetric_difference(expected))))
    for name, data in expected.items():
        if confined(output, name).read_bytes() != data:
            raise ValueError('Bundle bytes differ: ' + name)


def unsigned_observation(root, expected, apk, artifact_manifest, resolution, sdk, reports):
    before = file_record(apk)
    recorded = load(artifact_manifest)
    receipt = artifact.inventory(apk, expected, recorded['runId'], recorded['runAttempt'], sdk, reports / 'unsigned-inspection')
    if canonical(recorded) != canonical(receipt):
        raise ValueError('Unsigned artifact manifest differs from independent APK/source inspection')
    raw = load(resolution)
    classes = observe_classes(apk, sdk, reports)
    inventory = reconcile(raw, expected, apk, classes)
    if file_record(apk) != before:
        raise ValueError('APK changed during runtime observation')
    return inventory, receipt


def unsigned_files(root, expected, apk, artifact_manifest, resolution, sdk, reports):
    inventory, receipt = unsigned_observation(root, expected, apk, artifact_manifest, resolution, sdk, reports)
    reports.mkdir(parents=True, exist_ok=True)
    # Diagnostic inventory survives a blocked attribution/render step; never a passing capsule.
    (reports / 'runtime-inventory.json').write_bytes(canonical(inventory))
    return render(root, inventory, receipt)


def verify_accepted_unsigned(root, expected, apk, bundle, sdk, reports):
    """Recheck an accepted build capsule offline; no cache paths or rebuild are needed.

    Downstream admission must independently pin the accepted capsule digest/source.
    Fresh hosted verify-unsigned additionally re-derives it from current Gradle inputs.
    """
    manifest = load(confined(bundle, 'bundle-manifest.json'))
    inv = load(confined(bundle, 'runtime-inventory.json'))
    if manifest['stage'] != 'unsigned' or inv['buildInputs'] != expected or canonical(inv['buildInputs']) != canonical(expected):
        raise ValueError('Accepted unsigned source/build identity differs')
    if inv['apk'] != file_record(apk):
        raise ValueError('Accepted unsigned APK identity differs')
    receipt = load(confined(bundle, 'artifact-manifest.json'))
    if canonical(receipt) != canonical(artifact.inventory(apk, expected, receipt['runId'], receipt['runAttempt'], sdk, reports / 'unsigned-inspection')):
        raise ValueError('Accepted unsigned artifact inspection differs')
    observed_members = {n: fingerprint(b) for n, b in members(apk.read_bytes()).items()}
    unique([r['path'] for r in inv['members']], 'inventory member')
    if observed_members != {r['path']: {k: r[k] for k in ('size', 'sha256')} for r in inv['members']}:
        raise ValueError('Accepted inventory member coverage differs')
    unique([r['name'] for r in inv['classes']], 'inventory defined class')
    if observe_classes(apk, sdk, reports) != sorted(r['name'] for r in inv['classes']):
        raise ValueError('Accepted inventory defined-class coverage differs')
    if inv['attribution'] != attribution(root, inv)[0]:
        raise ValueError('Accepted attribution binding differs')
    files = render(root, inv, receipt)
    verify_files(bundle, files)
    return inv, receipt, files


def final_files(root, expected, unsigned_apk, unsigned_bundle, final_apk, expected_signer, sdk, reports):
    unsigned_before, final_before = file_record(unsigned_apk), file_record(final_apk)
    inv, receipt, unsigned = verify_accepted_unsigned(root, expected, unsigned_apk, unsigned_bundle, sdk, reports / 'accepted')
    if unsigned_apk.resolve() == final_apk.resolve():
        raise ValueError('Final APK must be a distinct input from the unsigned APK')
    signed = artifact.inspect_apk(final_apk, expected, sdk, reports / 'final-inspection', expected_signer=expected_signer)
    if payload(unsigned_apk) != payload(final_apk):
        raise ValueError('Final signed payload differs from accepted unsigned payload')
    if file_record(unsigned_apk) != unsigned_before or file_record(final_apk) != final_before:
        raise ValueError('APK bytes changed during final inspection')
    return render(root, inv, receipt, stage='final-signed', apk_record=final_before, signed=signed,
                  accepted={'apk': unsigned_before, 'bundleManifestSha256': fingerprint(unsigned['bundle-manifest.json'])['sha256']})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('create-unsigned', 'verify-unsigned', 'bind-final', 'verify-final'))
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--resolution', type=Path)
    parser.add_argument('--artifact-manifest', type=Path)
    parser.add_argument('--unsigned-apk', type=Path)
    parser.add_argument('--unsigned-bundle', type=Path)
    parser.add_argument('--expected-signer')
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', '.')))
    parser.add_argument('--reports', type=Path, required=True)
    args = parser.parse_args()
    try:
        expected = resolve(args.root, expected_sha=args.expected_sha)
        if git(args.root, 'status', '--porcelain'):
            raise ValueError('Release capsule requires a clean source checkout')
        for read_only in [args.bundle] + ([args.unsigned_bundle] if args.unsigned_bundle is not None else []):
            if (args.reports.resolve().is_relative_to(read_only.resolve())
                    or read_only.resolve().is_relative_to(args.reports.resolve())):
                raise ValueError('Reports must be separate from read-only bundles')
        for read_only in (args.apk, args.unsigned_apk, args.artifact_manifest, args.resolution):
            if read_only is not None and (read_only.resolve().is_relative_to(args.reports.resolve())
                                         or args.reports.resolve().is_relative_to(read_only.resolve())):
                raise ValueError('Reports must be separate from read-only input files')
        if args.operation.endswith('unsigned'):
            if args.resolution is None or args.artifact_manifest is None:
                raise ValueError('Unsigned mode requires current --resolution and --artifact-manifest')
            files = unsigned_files(args.root, expected, args.apk, args.artifact_manifest, args.resolution, args.sdk, args.reports)
            protected = [args.apk, args.artifact_manifest, args.resolution, args.reports]
        else:
            if args.unsigned_apk is None or args.unsigned_bundle is None or args.expected_signer is None:
                raise ValueError('Final mode requires accepted unsigned APK/bundle and independent expected signer')
            files = final_files(args.root, expected, args.unsigned_apk, args.unsigned_bundle,
                                args.apk, args.expected_signer, args.sdk, args.reports)
            protected = [args.apk, args.unsigned_apk, args.unsigned_bundle, args.reports]
        if args.operation in ('create-unsigned', 'bind-final'):
            write_bundle(args.bundle, files, protected)
        else:
            verify_files(args.bundle, files)
        print('Release runtime capsule ' + args.operation + ' passed; ' + DISCLAIMER)
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Release runtime capsule failed: {error}\n')


if __name__ == '__main__':
    main()
