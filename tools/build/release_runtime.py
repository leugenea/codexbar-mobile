"""Bounded release-runtime observation; ordinary strict Gradle builds are the trust boundary.

No decompiler, historical build reconstruction or inference from the tooling graph.
Only the current unminified, single-APK release shape is supported.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import subprocess
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
HASH = re.compile(r'[0-9a-f]{64}')
COORDINATE = re.compile(r'[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+')


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True, indent=2) + '\n').encode('utf-8')


def unique(rows, label):
    if len(rows) != len(set(rows)):
        raise ValueError('Duplicate ' + label)
    return set(rows)


def load(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key: ' + key)
            result[key] = value
        return result
    def forbidden(value):
        raise ValueError('Non-integer JSON number: ' + value)
    return json.loads(path.read_bytes(), object_pairs_hook=pairs, parse_float=forbidden, parse_constant=forbidden)


def safe_name(name):
    if (not isinstance(name, str) or not name or '\\' in name or '\x00' in name
            or any(ord(c) < 32 for c in name) or ':' in name
            or name.startswith('/') or any(p in ('', '.', '..') for p in name.split('/'))):
        raise ValueError('Unsafe relative path: ' + repr(name))
    return name


def confined(root, name):
    safe_name(name)
    path = root / name
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('Path escapes root: ' + name)
    if any(p.is_symlink() for p in [path, *path.parents] if p != root.parent):
        raise ValueError('Symlink is not a bundle file: ' + name)
    return path


def fingerprint(data):
    return {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def file_record(path):
    return {'filename': safe_name(path.name), **fingerprint(path.read_bytes())}


def members(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        rows = archive.infolist()
        unique([r.filename for r in rows], 'ZIP member')
        if len(rows) > 100000 or sum(r.file_size for r in rows) > 512 * 1024 * 1024:
            raise ValueError('Archive exceeds bounded release inspection size')
        result = {}
        for row in rows:
            safe_name(row.filename[:-1] if row.is_dir() else row.filename)
            if stat.S_ISLNK(row.external_attr >> 16):
                raise ValueError('ZIP symlinks are unsupported')
            if not row.is_dir():
                result[row.filename] = archive.read(row)
        return result


def signature_member(name):
    # Only recognized v1 additions; non-signature META-INF content is payload.
    return name == 'META-INF/MANIFEST.MF' or bool(re.fullmatch(r'META-INF/[A-Za-z0-9_-]+\.(SF|RSA|DSA|EC)', name))


def payload(apk):
    return {n: fingerprint(b) for n, b in sorted(members(apk.read_bytes()).items()) if not signature_member(n)}


def defined_classes(text):
    classes = []
    for line in text.splitlines():
        fields = line.split()
        if len(fields) >= 3 and fields[:2] == ['C', 'd']:
            name = fields[-1]
            if not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_.$-]*', name):
                raise ValueError('Unsupported apkanalyzer class row: ' + line)
            classes.append(name)
    unique(classes, 'defined DEX class')
    if not classes:
        raise ValueError('apkanalyzer returned no defined classes')
    return sorted(classes)


def observe_classes(apk, sdk, reports):
    reports.mkdir(parents=True, exist_ok=True)
    command = [str(sdk / 'cmdline-tools/22.0/bin/apkanalyzer'), 'dex', 'packages', '--defined-only', str(apk)]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    (reports / 'dex-packages.txt').write_text(result.stdout)
    (reports / 'dex-packages-stderr.txt').write_text(result.stderr)
    if result.returncode:
        raise ValueError('apkanalyzer failed; see dex-packages reports')
    return defined_classes(result.stdout)


def validate_resolution(raw, expected, apk):
    if (raw.get('schema') != 1 or type(raw.get('schema')) is not int or raw.get('kind') != 'release-runtime-resolution'
            or raw.get('variant') != 'release' or raw.get('configuration') != 'releaseRuntimeClasspath'
            or raw.get('buildInputs') != expected or canonical(raw['buildInputs']) != canonical(expected)
            or {k: raw.get('apk', {}).get(k) for k in ('size', 'sha256')} != fingerprint(apk.read_bytes())):
        raise ValueError('Release runtime resolution has inconsistent artifact/build identity')
    nodes = unique([r['id'] for r in raw['components']], 'resolved component')
    if not nodes or 'app' not in nodes:
        raise ValueError('Missing resolution root')
    for node in nodes - {'app'}:
        if not COORDINATE.fullmatch(node):
            raise ValueError('Unsupported resolved coordinate: ' + node)
    edges = raw['edges']
    unique([(e['from'], e['to'], e['constraint']) for e in edges], 'resolved edge')
    if any(e['from'] not in nodes or e['to'] not in nodes or type(e['constraint']) is not bool for e in edges):
        raise ValueError('Missing edge endpoint or invalid constraint')
    reachable = {'app'}
    while True:
        expanded = reachable | {e['to'] for e in edges if e['from'] in reachable}
        if expanded == reachable:
            break
        reachable = expanded
    if reachable != nodes:
        raise ValueError('Disconnected resolution component')
    unique([(a['component'], a['filename']) for a in raw['artifacts']], 'resolved artifact')
    if not raw['artifacts']:
        raise ValueError('Missing release artifacts')
    for row in raw['artifacts']:
        safe_name(row['filename'])
        path = Path(row['path'])  # Private Gradle input receipt only; never emitted as a public path.
        if row['component'] not in nodes or file_record(path) != {k: row[k] for k in ('filename', 'size', 'sha256')}:
            raise ValueError('Resolved artifact byte/name/component mismatch')
        if path.suffix not in ('.aar', '.jar'):
            raise ValueError('Unsupported runtime artifact; original AAR/JAR required')
    unique(raw['projectClasses'], 'project class')
    return nodes


def archive_inputs(row):
    """Read AAR/JAR members without extraction, including every nested library."""
    outer = members(Path(row['path']).read_bytes())
    jars = {'': outer} if row['filename'].endswith('.jar') else {
        n: members(b) for n, b in outer.items() if n == 'classes.jar' or n.startswith('libs/') and n.endswith('.jar')}
    classes = {}
    files = {}
    nested = []
    packages = []
    for jar, contents in jars.items():
        if jar:
            nested.append({'member': jar, **fingerprint(outer[jar])})
        for name, data in contents.items():
            if name.endswith('.class') and not name.startswith('META-INF/versions/') and name != 'module-info.class':
                cname = name[:-6].replace('/', '.')
                if cname in classes:
                    raise ValueError('Ambiguous class inside runtime archive: ' + cname)
                classes[cname] = jar or 'archive'
            elif not name.endswith('.class'):
                files.setdefault(name, []).append((jar + '!' if jar else '') + name)
    if row['filename'].endswith('.aar'):
        for name in outer:
            if name.startswith(('assets/', 'jni/')):
                files.setdefault(name.replace('jni/', 'lib/', 1) if name.startswith('jni/') else name, []).append(name)
        if 'AndroidManifest.xml' in outer:
            package = ET.fromstring(outer['AndroidManifest.xml']).get('package')
            if package:
                packages.append(package)
    file_evidence = {}
    for name, sources in files.items():
        for source in sources:
            if '!' in source:
                jar, entry = source.split('!', 1)
                data = jars[jar][entry]
            elif source in outer:
                data = outer[source]
            else:
                data = jars[''][source]
            file_evidence.setdefault(name, []).append({'member': source, **fingerprint(data)})
    merged = sorted(n for n in outer if n.startswith('res/') or n in ('AndroidManifest.xml', 'baseline-prof.txt', 'R.txt'))
    return classes, file_evidence, sorted(nested, key=lambda r: r['member']), packages, merged


def reconcile(raw, expected, apk, classes):
    validate_resolution(raw, expected, apk)
    unique(classes, 'defined DEX class')
    observed = set(classes)
    owners = {}
    file_sources = {}
    input_rows = []
    generated_packages = {expected['releasePackage']}
    for row in sorted(raw['artifacts'], key=lambda r: (r['component'], r['filename'])):
        names, files, nested, packages, merged = archive_inputs(row)
        generated_packages.update(packages)
        item = {k: row[k] for k in ('component', 'filename', 'size', 'sha256')}
        item.update(nested=nested, mergedInputs=merged)
        item['definedClasses'] = sorted(observed.intersection(names))
        item['classInputSha256'] = fingerprint(canonical(sorted(names)))['sha256']
        input_rows.append(item)
        for name in names:
            owners.setdefault(name, []).append((row['component'], names[name]))
        for name, sources in files.items():
            file_sources.setdefault(name, []).extend({'component': row['component'], **s} for s in sources)
    project = set(raw['projectClasses'])
    unknown = []
    class_rows = []
    for name in sorted(observed):
        matches = owners.get(name, [])
        if len(matches) > 1 or matches and name in project:
            raise ValueError('Ambiguous runtime class owner: ' + name)
        if matches:
            kind, owner = 'runtime-input', matches[0][0]
        elif name in project:
            kind, owner = 'project', 'app'
        elif any(name == p + '.R' or name.startswith(p + '.R$') for p in generated_packages):
            kind, owner = 'generated-resource-class', 'app'
        elif '$$ExternalSynthetic' in name and name.split('$$ExternalSynthetic', 1)[0] in (project | owners.keys()):
            kind, owner = 'd8-synthetic', 'app'
        else:
            unknown.append('Unmapped defined class: ' + name)
            kind, owner = 'unresolved', 'app'
        class_rows.append({'name': name, 'kind': kind, 'component': owner})
    apk_members = members(apk.read_bytes())
    member_rows = []
    for name, data in sorted(apk_members.items()):
        if signature_member(name):
            raise ValueError('Unsigned runtime observation contains signature members')
        digest = fingerprint(data)
        matches = [r for r in file_sources.get(name, []) if {k: r[k] for k in ('size', 'sha256')} == digest]
        if matches:
            kind = 'runtime-input'
        elif name in ('AndroidManifest.xml', 'resources.arsc') or name.startswith('res/'):
            # Resource ZIP paths may be optimized; no guessed source-filename mapping.
            # AGP consumes the complete release resource/manifest closure as merged inputs.
            kind = 'android-resource-merge'
        elif re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name):
            kind = 'dex-output'
        elif name in ('assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm',
                      'META-INF/com/android/build/gradle/app-metadata.properties',
                      'META-INF/version-control-info.textproto'):
            kind = 'android-generated'
        else:
            kind = 'unresolved'
            unknown.append('Unmapped APK member: ' + name)
        member_rows.append({'path': name, **digest, 'kind': kind, 'inputs': sorted(matches, key=lambda r: (r['component'], r['member']))})
    components = []
    for node in sorted(raw['components'], key=lambda r: r['id']):
        if node['id'] == 'app':
            continue
        artifacts = [r for r in input_rows if r['component'] == node['id']]
        class_count = sum(len(r['definedClasses']) for r in artifacts)
        copied = [r['path'] for r in member_rows if any(i['component'] == node['id'] for i in r['inputs'])]
        merged = any(r['mergedInputs'] for r in artifacts)
        incoming = [e for e in raw['edges'] if e['to'] == node['id']]
        # No-payload KMP/platform nodes remain visible in the ledger, not in the shipping BOM.
        status = ('observed' if class_count or copied else 'merged-contributor' if merged else
                  'constraint-only' if incoming and all(e['constraint'] for e in incoming) else 'resolved-no-packaged-payload')
        components.append({'id': node['id'], 'status': status, 'definedClassCount': class_count,
                           'copiedMembers': copied, 'variants': sorted(node['variants'], key=canonical)})
    return {'schema': 1, 'kind': 'release-runtime', 'buildInputs': expected, 'apk': file_record(apk),
            'resolution': {'variant': raw['variant'], 'configuration': raw['configuration'],
                           'components': components, 'edges': sorted(raw['edges'], key=lambda r: (r['from'], r['to'], r['constraint']))},
            'artifacts': input_rows, 'classes': class_rows, 'members': member_rows, 'unresolved': sorted(unknown),
            'scope': 'Strict Gradle release inputs reconciled with unminified APK; merged-contributor does not claim each input byte survived. No historical build or universal notice-completeness proof.'}
