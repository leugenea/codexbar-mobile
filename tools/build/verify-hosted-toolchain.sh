#!/usr/bin/env bash
# Verification/receipts only; setup Actions and sdkmanager own installation.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf '%s\n' 'Hosted runner only' >&2; exit 1; }
: "${JAVA_HOME:?}" "${ANDROID_HOME:?}" "${ANDROID_SDK_ROOT:?}"
[[ "$ANDROID_HOME" == "$ANDROID_SDK_ROOT" ]]
[[ "$(readlink -f "$(command -v java)")" == "$(readlink -f "$JAVA_HOME/bin/java")" ]]
mkdir -p evidence
cp "$JAVA_HOME/release" evidence/jdk-release.txt
# Bound the actual executable, not just its release file; no hardcoded patch.
timeout 30 java -XshowSettings:properties -version > evidence/java-version.txt 2>&1
python3 - <<'PY'
import json, os, pathlib, re, shutil, xml.etree.ElementTree as ET
import hashlib, stat, time


def properties(path):
    return {key.strip(): value.strip().strip('"')
            for line in path.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#')
            for key, value in [line.split('=', 1)]}


def verify_jdk(release, observed):
    assert release['IMPLEMENTOR'] == observed['java.vendor'] == 'Eclipse Adoptium'
    assert observed['java.specification.version'] == '17'
    assert re.fullmatch(r'17(?:[.+-].*)?', release['JAVA_RUNTIME_VERSION'])
    assert observed['java.runtime.version'] == release['JAVA_RUNTIME_VERSION']
    return {'javaRuntime': observed['java.runtime.version'], 'javaVendor': observed['java.vendor'],
            'javaMajor': observed['java.specification.version']}


def child(element, name):
    return next((node for node in element if node.tag.split('}')[-1] == name), None)


def verify_sdk(path, props, metadata):
    local = [node for node in metadata.iter() if node.tag.split('}')[-1] == 'localPackage']
    assert len(local) == 1 and local[0].get('path') == path, path
    revision = props['Pkg.Revision']
    assert re.fullmatch(r'[0-9]+(?:\.[0-9]+){0,2}', revision), revision
    numbers = tuple(int(part) for part in revision.split('.'))
    numbers += (0,) * (3 - len(numbers))
    assert numbers[0] > 0, revision
    xml_revision = child(local[0], 'revision')
    actual = tuple(int(child(xml_revision, name).text) if child(xml_revision, name) is not None else 0
                   for name in ('major', 'minor', 'micro'))
    assert actual == numbers, (revision, actual)
    if path == 'platforms;android-37.0':
        assert props['AndroidVersion.ApiLevel'] in ('37', '37.0'), props
        assert props.get('AndroidVersion.MinorApiLevel', '0') == '0', props
        assert not props.get('AndroidVersion.CodeName'), props
        details = child(local[0], 'type-details')
        assert details.get('{http://www.w3.org/2001/XMLSchema-instance}type', '').endswith(':platformDetailsType')
        assert child(details, 'api-level').text in ('37', '37.0')
        for name, default in (('minor-api-level', '0'), ('codename', '')):
            node = child(details, name)
            assert (node.text or default if node is not None else default) == default
    else:
        assert path == 'build-tools;36.0.0' and numbers == (36, 0, 0), (path, revision)
    return {'path': path, 'revision': revision, 'sourceProperties': props}


# File-only material facts. No SDK/Java/shell/loader/discovery child is added.
class MaterialObserver:
    MAX_FILE, MAX_TOTAL, MAX_ROWS = 256 * 1024 * 1024, 512 * 1024 * 1024, 64
    MAX_TEXT, MAX_JSON = 1024 * 1024, 4 * 1024 * 1024

    def __init__(self, environment):
        keys = ('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'JAVA_HOME', 'GITHUB_WORKSPACE', 'RUNNER_TEMP',
                'GITHUB_ACTIONS', 'GITHUB_REPOSITORY', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB',
                'GITHUB_SHA', 'GITHUB_REF', 'GITHUB_EVENT_NAME', 'GITHUB_EVENT_PATH', 'GITHUB_WORKFLOW',
                'GITHUB_WORKFLOW_REF', 'GITHUB_WORKFLOW_SHA')
        self.env = {key: environment.get(key) for key in keys}
        self.deadline = time.monotonic() + 120
        self.inputs, self.rows, self.total = {}, {}, 0
        self.sdk = self.root('ANDROID_HOME')
        if self.root('ANDROID_SDK_ROOT') != self.sdk:
            raise ValueError('SDK root mismatch')
        self.jdk, self.source, self.scratch = (self.root(key) for key in ('JAVA_HOME', 'GITHUB_WORKSPACE', 'RUNNER_TEMP'))
        if self.scratch.is_relative_to(self.source) or self.scratch.stat().st_uid != os.getuid():
            raise ValueError('Observer scratch boundary')

    def check(self):
        if time.monotonic() >= self.deadline:
            raise ValueError('Observer deadline')

    def root(self, key):
        value = self.env.get(key)
        if not isinstance(value, str) or not value or not pathlib.Path(value).is_absolute():
            raise ValueError('Observer root missing')
        path = pathlib.Path(value)
        if '..' in path.parts or any(p.is_symlink() for p in (path, *path.parents)) or not path.is_dir():
            raise ValueError('Observer root unsafe')
        if path != path.resolve():
            raise ValueError('Observer root is not canonical')
        return path

    def parent(self, path):
        self.check()
        path = pathlib.Path(path)
        if not path.is_absolute() or '..' in path.parts:
            raise ValueError('Observer path unsafe')
        handles, current = [], pathlib.Path(path.anchor)
        try:
            fd = os.open(current, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            handles.append((current, fd, os.fstat(fd)))
            for part in path.parts[1:-1]:
                current /= part
                fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                handles.append((current, fd, os.fstat(fd)))
            return handles
        except BaseException:
            self.close(handles)
            raise

    def close(self, handles):
        for path, fd, saved in reversed(handles):
            os.close(fd)

    def anchors(self, handles):
        self.check()
        for path, fd, saved in handles:
            now, visible = os.fstat(fd), path.lstat()
            if ((now.st_dev, now.st_ino) != (saved.st_dev, saved.st_ino)
                    or (visible.st_dev, visible.st_ino) != (saved.st_dev, saved.st_ino)
                    or not stat.S_ISDIR(visible.st_mode)):
                raise ValueError('Observer parent replacement')

    def read(self, path, limit, *, launcher=False):
        handles = self.parent(path)
        try:
            fd = os.open(path.name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=handles[-1][1])
            try:
                info = os.fstat(fd)
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > limit:
                    raise ValueError('Observer bounded regular file required')
                data, sha, count, prefix = bytearray(), hashlib.sha256(), 0, b''
                while True:
                    self.check()
                    part = os.read(fd, 1024 * 1024)
                    if not part:
                        break
                    prefix = (prefix + part)[:64]
                    count += len(part)
                    if count > limit or (launcher and prefix.startswith(b'#!') and count > self.MAX_TEXT):
                        raise ValueError('Observer material/text cap')
                    sha.update(part)
                    if not launcher or prefix.startswith(b'#!'):
                        data.extend(part)
                now, visible = os.fstat(fd), os.stat(path.name, dir_fd=handles[-1][1], follow_symlinks=False)
                keys = ('st_dev', 'st_ino', 'st_mode', 'st_nlink', 'st_uid', 'st_gid', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
                if any(getattr(x, key) != getattr(info, key) for x in (now, visible) for key in keys):
                    raise ValueError('Observer material drift')
                self.anchors(handles)
                self.check()
                row = dict(sha256=sha.hexdigest(), size=count, mode=stat.S_IMODE(info.st_mode),
                           device=info.st_dev, inode=info.st_ino, headerHex=prefix.hex())
                return bytes(data), row
            finally:
                os.close(fd)
        finally:
            self.close(handles)

    def input(self, path):
        data, row = self.read(path, self.MAX_JSON)
        if str(path) in self.inputs and self.inputs[str(path)] != row:
            raise ValueError('Observer original input changed')
        self.inputs[str(path)] = row
        return data

    def context(self):
        if self.env.get('GITHUB_ACTIONS') != 'true' or self.env.get('GITHUB_REPOSITORY') != 'leugenea/codexbar-mobile':
            raise ValueError('Observer producer mismatch')
        for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
            if not re.fullmatch(r'[1-9][0-9]*', self.env.get(key, '')):
                raise ValueError('Observer run mismatch')
        if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_-]*', self.env.get('GITHUB_JOB', '')):
            raise ValueError('Observer job mismatch')
        identity = [str(self.source), *(self.env[k] for k in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB'))]
        key = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        observation = json.loads(self.input(self.scratch / ('release-acquisition-' + key + '.json')))
        context, state = observation['context'], observation['state']
        if (observation['mode'] != 'normal' or context['mode'] != 'normal' or context['admissionHeld']
                or context['checkoutRole'] not in ('normal_pr_merge', 'direct')
                or context['repository'] != self.env['GITHUB_REPOSITORY'] or state['root'] != str(self.source)):
            raise ValueError('Observer acquisition binding mismatch')
        if not re.fullmatch(r'[0-9a-f]{40}', context['sourceHeadSha']):
            raise ValueError('Observer source head missing')
        for field, value in (('compiledCheckoutSha', state['head']), ('producerEventSha', self.env.get('GITHUB_SHA')),
                             ('producerEventRef', self.env.get('GITHUB_REF')), ('eventName', self.env.get('GITHUB_EVENT_NAME'))):
            if context[field] != value:
                raise ValueError('Observer source/event mismatch')
        for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB'):
            if context[key] != self.env[key]:
                raise ValueError('Observer original producer mismatch')
        if not all(re.fullmatch(r'[0-9a-f]{40}', state[k]) for k in ('head', 'tree')) or state['head'] != self.env.get('GITHUB_SHA'):
            raise ValueError('Observer committed checkout mismatch')
        event = self.input(pathlib.Path(self.env['GITHUB_EVENT_PATH']))
        if hashlib.sha256(event).hexdigest() != context['eventSha256']:
            raise ValueError('Observer event digest mismatch')
        metadata = self.source / '.git'
        if not metadata.is_dir() or metadata.is_symlink():
            raise ValueError('Observer requires captured nonshared files checkout')
        for name, expected in (('index', state['rawIndex']), ('HEAD', state['headFile'])):
            if hashlib.sha256(self.input(metadata / name)).hexdigest() != expected:
                raise ValueError('Observer protected source/index mismatch')
        if set(state['configs']) != {str(metadata / 'config')}:
            raise ValueError('Observer original config scope mismatch')
        for name, expected in state['configs'].items():
            data = self.input(pathlib.Path(name))
            if hashlib.sha256(data).hexdigest() != expected['sha256'] or self.inputs[name]['mode'] != expected['mode']:
                raise ValueError('Observer protected config mismatch')
        tracked = {}
        for row in state['trackedFiles']:
            relative = pathlib.Path(row['path'])
            if relative.is_absolute() or '..' in relative.parts or row['path'] in tracked:
                raise ValueError('Observer tracked path mismatch')
            data = self.input(self.source / relative)
            if hashlib.sha256(data).hexdigest() != row['sha256']:
                raise ValueError('Observer tracked bytes mismatch')
            actual_mode = '100755' if self.inputs[str(self.source / relative)]['mode'] & 0o111 else '100644'
            if actual_mode != row['gitMode']:
                raise ValueError('Observer tracked mode mismatch')
            tracked[row['path']] = row['sha256']
        workflow = re.fullmatch(r'leugenea/codexbar-mobile/(\.github/workflows/android\.yml)@[^\s]+', self.env.get('GITHUB_WORKFLOW_REF', ''))
        if not workflow or self.env.get('GITHUB_WORKFLOW') != 'Android' or not re.fullmatch(r'[0-9a-f]{40}', self.env.get('GITHUB_WORKFLOW_SHA', '')):
            raise ValueError('Observer workflow identity unsupported')
        script = 'tools/build/verify-hosted-toolchain.sh'
        if script not in tracked or workflow[1] not in tracked:
            raise ValueError('Observer script/workflow not in captured source')
        return dict(repository=context['repository'], sourceHead=context['sourceHeadSha'], compiledHead=state['head'],
                    tree=state['tree'], producerSha=context['producerEventSha'], producerRef=context['producerEventRef'],
                    eventSha256=context['eventSha256'], job=context['GITHUB_JOB'], run=context['GITHUB_RUN_ID'],
                    attempt=context['GITHUB_RUN_ATTEMPT'], sdk=str(self.sdk), jdk=str(self.jdk), root=str(self.source),
                    workflowRef=self.env['GITHUB_WORKFLOW_REF'], workflowSha=self.env['GITHUB_WORKFLOW_SHA'],
                    workflowFileSha256=tracked[workflow[1]], scriptSha256=tracked[script], rawIndex=state['rawIndex'],
                    logicalIndex=state['logicalIndex'])

    def material(self, path, *, launcher=False, metadata=False):
        path = pathlib.Path(path)
        if not any(path.is_relative_to(root) for root in (self.sdk, self.jdk)) or '..' in path.parts:
            raise ValueError('Observer literal outside roots')
        if str(path) in self.rows:
            return
        if len(self.rows) >= self.MAX_ROWS:
            raise ValueError('Observer row cap')
        limit = self.MAX_JSON if metadata else self.MAX_FILE
        data, row = self.read(path, limit, launcher=launcher)
        self.total += row['size']
        if self.total > self.MAX_TOTAL:
            raise ValueError('Observer aggregate cap')
        row.update(path=str(path), selection='UNKNOWN')
        self.rows[str(path)] = row
        if launcher:
            prefix = bytes.fromhex(row['headerHex'])
            row['format'] = 'ELF' if prefix.startswith(b'\x7fELF') else 'SCRIPT' if prefix.startswith(b'#!') else 'UNKNOWN'
            if row['format'] == 'SCRIPT':
                row['launcherText'] = data.decode('utf-8')
                row['unresolvedSelection'] = 'UNKNOWN: expressions/interpreters/PATH/branches are not evaluated'
                # Observation only: literal rooted quoted tokens, never expansion,
                # PATH search, interpreter lookup or runtime branch selection.
                tokens = re.findall(r'''["'](/[^"'\n]+)["']''', row['launcherText'])
                row['rootedLiteralReferences'] = []
                for token in tokens:
                    target = pathlib.Path(token)
                    if re.search(r'[$`\\*?\[\]{}]', token) or not any(target.is_relative_to(root) for root in (self.sdk, self.jdk)):
                        continue
                    row['rootedLiteralReferences'].append(token)
                    if str(target) not in self.rows:
                        try:
                            self.material(target)
                        except FileNotFoundError:
                            if len(self.rows) >= self.MAX_ROWS:
                                raise ValueError('Observer row cap') from None
                            self.rows[str(target)] = dict(path=str(target), status='ABSENT', selection='UNKNOWN')

    def preserve(self):
        self.check()
        for name, expected in self.inputs.items():
            if self.read(pathlib.Path(name), self.MAX_JSON)[1] != expected:
                raise ValueError('Observer input changed')
        for name, expected in self.rows.items():
            if expected.get('status') == 'ABSENT':
                if os.path.lexists(name):
                    raise ValueError('Observer absent literal changed')
            elif self.read(pathlib.Path(name), self.MAX_FILE, launcher=True)[1] != {k:expected[k] for k in
                    ('sha256', 'size', 'mode', 'device', 'inode', 'headerHex')}:
                raise ValueError('Observer material changed')
        self.check()

    def publish(self, path, data):
        handles = self.parent(path)
        try:
            self.anchors(handles)
            fd = os.open(path.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=handles[-1][1])
            with os.fdopen(fd, 'wb') as stream:
                os.fchmod(stream.fileno(), 0o600)
                saved = os.fstat(stream.fileno())
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            self.anchors(handles)
            actual, row = self.read(path, self.MAX_JSON)
            if (actual != data or row['mode'] != 0o600
                    or (row['device'], row['inode']) != (saved.st_dev, saved.st_ino)):
                raise ValueError('Observer publication readback mismatch')
            return row
        finally:
            self.close(handles)

    def package(self, package, installed):
        directory = self.sdk / package
        source = self.read(directory / 'source.properties', self.MAX_JSON)[0]
        metadata = self.read(directory / 'package.xml', self.MAX_JSON)[0]
        label = 'platform' if package == 'platforms/android-37.0' else 'build-tools'
        if (self.input(self.source / 'evidence' / (label + '-source.properties')) != source
                or self.input(self.source / 'evidence' / (label + '-package.xml')) != metadata):
            raise ValueError('Observer installed metadata receipt differs')
        props = {key.strip(): value.strip().strip('"')
                 for line in source.decode().splitlines() if '=' in line and not line.lstrip().startswith('#')
                 for key, value in [line.split('=', 1)]}
        name = package.replace('/', ';', 1)
        actual = verify_sdk(name, props, ET.fromstring(metadata))
        if actual != installed['effectivePackages'][name]:
            raise ValueError('Observer validated package changed')

    def observe(self):
        context = self.context()
        installed = json.loads(self.input(self.source / 'evidence/installed-toolchain.json'))
        if installed['sdkRoot'] != str(self.sdk):
            raise ValueError('Observer installed root mismatch')
        for package in ('platforms/android-37.0', 'build-tools/36.0.0'):
            self.package(package, installed)
            for name in ('source.properties', 'package.xml'):
                self.material(self.sdk / package / name, metadata=True)
        for name in ('aapt', 'aapt2', 'apksigner'):
            self.material(self.sdk / 'build-tools/36.0.0' / name, launcher=True)
        key = hashlib.sha256(json.dumps(context, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        receipt = dict(schema=1, purpose='installed-material-observation-only', sdkClosure='HELD', context=context,
                       contextSha256=key, material=list(self.rows.values()))
        data = (json.dumps(receipt, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode()
        if len(data) > self.MAX_JSON:
            raise ValueError('Observer encoded observation cap')
        self.preserve()
        if self.context() != context:
            raise ValueError('Observer original context changed')
        directory = self.scratch / ('sdk-material-observation-' + key)
        handles = self.parent(directory)
        try:
            self.anchors(handles)
            os.mkdir(directory.name, 0o700, dir_fd=handles[-1][1])
            child = os.open(directory.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=handles[-1][1])
            try:
                os.fchmod(child, 0o700)
                identity = os.fstat(child)
                first = self.publish(directory / 'observation.json', data)
                if (directory.lstat().st_dev, directory.lstat().st_ino) != (identity.st_dev, identity.st_ino):
                    raise ValueError('Observer output directory changed')
                second = self.publish(self.source / 'evidence/sdk-material-observation.json', data)
                for path, expected in ((directory / 'observation.json', first),
                                       (self.source / 'evidence/sdk-material-observation.json', second)):
                    content, actual = self.read(path, self.MAX_JSON)
                    if content != data or actual != expected:
                        raise ValueError('Observer published identity changed')
                self.preserve()
                if self.context() != context:
                    raise ValueError('Observer final context drift')
                visible = directory.lstat()
                if ((visible.st_dev, visible.st_ino) != (identity.st_dev, identity.st_ino)
                        or stat.S_IMODE(visible.st_mode) != 0o700 or visible.st_uid != os.getuid()):
                    raise ValueError('Observer output directory identity/mode changed')
                self.anchors(handles)
                self.check()
                return receipt
            finally:
                os.close(child)
        finally:
            self.close(handles)


# The functions above are also exercised with explicitly synthetic offline fixtures.
evidence = pathlib.Path('evidence')
receipt = verify_jdk(properties(evidence / 'jdk-release.txt'), properties(evidence / 'java-version.txt'))
sdk = pathlib.Path(os.environ['ANDROID_HOME'])
receipt['sdkRoot'] = str(sdk)
receipt['requestedPackages'] = ['platforms;android-37.0', 'build-tools;36.0.0']
receipt['effectivePackages'] = {}
for path, name in (('platforms;android-37.0', 'platform'), ('build-tools;36.0.0', 'build-tools')):
    directory = sdk.joinpath(*path.split(';'))
    source, package = directory / 'source.properties', directory / 'package.xml'
    shutil.copyfile(source, evidence / (name + '-source.properties'))
    shutil.copyfile(package, evidence / (name + '-package.xml'))
    receipt['effectivePackages'][path] = verify_sdk(path, properties(source), ET.parse(package).getroot())
assert (sdk / 'platforms/android-37.0/android.jar').is_file()
assert os.access(sdk / 'build-tools/36.0.0/aapt', os.X_OK)
command_tools = sdk / 'cmdline-tools/22.0/source.properties'
assert properties(command_tools)['Pkg.Revision'] == '22.0'
shutil.copyfile(command_tools, evidence / 'cmdline-tools-source.properties')
(evidence / 'installed-toolchain.json').write_text(json.dumps(receipt, indent=2) + '\n')
try:
    MaterialObserver(os.environ).observe()
except (ValueError, KeyError, OSError, TypeError, UnicodeError, AttributeError, AssertionError, ET.ParseError):
    # No raw material/path/config/exception diagnostics; owned residuals are retained.
    raise SystemExit('SDK_MATERIAL_OBSERVATION_FAILED') from None
PY
sha256sum "$ANDROID_HOME/platforms/android-37.0/android.jar" \
  "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  "$ANDROID_HOME/cmdline-tools/22.0/bin/"{sdkmanager,avdmanager} > evidence/installed-binaries.sha256
