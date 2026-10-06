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
PY
sha256sum "$ANDROID_HOME/platforms/android-37.0/android.jar" \
  "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  "$ANDROID_HOME/cmdline-tools/22.0/bin/"{sdkmanager,avdmanager} > evidence/installed-binaries.sha256
