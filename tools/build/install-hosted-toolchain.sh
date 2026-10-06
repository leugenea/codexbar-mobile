#!/usr/bin/env bash
# Hosted-only exact M0 archives; no sdkmanager/latest package substitution.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf '%s\n' 'Hosted runner only' >&2; exit 1; }
: "${RUNNER_TEMP:?}" "${GITHUB_ENV:?}" "${GITHUB_PATH:?}"
mkdir -p evidence "$RUNNER_TEMP/m1-sdk/platforms" "$RUNNER_TEMP/m1-sdk/build-tools"
cd "$RUNNER_TEMP"
curl --fail --location --retry 3 --output m1-jdk.tar.gz \
  'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz'
printf '%s\n' '3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e  m1-jdk.tar.gz' | sha256sum --check
mkdir -p m1-jdk
tar -xzf m1-jdk.tar.gz --strip-components=1 -C m1-jdk
export JAVA_HOME="$RUNNER_TEMP/m1-jdk"
export PATH="$JAVA_HOME/bin:$PATH"
grep -Fx 'JAVA_RUNTIME_VERSION="17.0.20.1+1"' "$JAVA_HOME/release"
grep -Fx 'IMPLEMENTOR="Eclipse Adoptium"' "$JAVA_HOME/release"
printf 'JAVA_HOME=%s\nANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' \
  "$JAVA_HOME" "$RUNNER_TEMP/m1-sdk" "$RUNNER_TEMP/m1-sdk" >> "$GITHUB_ENV"
printf '%s\n' "$JAVA_HOME/bin" >> "$GITHUB_PATH"
curl --fail --location --retry 3 --output m1-platform.zip \
  'https://dl.google.com/android/repository/platform-37.0_r02.zip'
curl --fail --location --retry 3 --output m1-build-tools.zip \
  'https://dl.google.com/android/repository/build-tools_r36_linux.zip'
printf '%s\n' \
  'ed8ebf7f8822a4de5686d427f237d2fa30ff7410  m1-platform.zip' \
  'b0b6376977657e8ad9b969bacf4093601da2c6fb  m1-build-tools.zip' | sha1sum --check
unzip -q m1-platform.zip -d m1-platform-unpacked
unzip -q m1-build-tools.zip -d m1-build-tools-unpacked
# Archive directory names are not the package/revision contract.
python3 - <<'PY'
import os, pathlib, shutil
root = pathlib.Path(os.environ['RUNNER_TEMP'])
for archive, destination, revision, api in (
    ('m1-platform-unpacked', 'platforms/android-37.0', '2', '37'),
    ('m1-build-tools-unpacked', 'build-tools/36.0.0', '36.0.0', None),
):
    files = list((root / archive).rglob('source.properties'))
    assert len(files) == 1, files
    source = files[0]
    props = dict(line.split('=', 1) for line in source.read_text().splitlines()
                 if '=' in line and not line.startswith('#'))
    props = {k.strip(): v.strip() for k, v in props.items()}
    assert props['Pkg.Revision'] == revision, props
    if api:
        assert props['AndroidVersion.ApiLevel'] in (api, api + '.0'), props
        assert props.get('AndroidVersion.MinorApiLevel', '0') == '0', props
    shutil.move(str(source.parent), str(root / 'm1-sdk' / destination))
PY
cd "$GITHUB_WORKSPACE"
# SDK installers normally write package.xml from the repository record after
# unpacking. The platform's decimal API cannot use sdklib's legacy int parser.
# Require the exact M0 archive/revision before writing official local metadata.
curl --fail --location --retry 3 --output evidence/sdk-repository.xml \
  'https://dl.google.com/android/repository/repository2-4.xml'
python3 tools/build/write_sdk_package_metadata.py "$RUNNER_TEMP/m1-sdk" \
  evidence/sdk-repository.xml docs/research/m0/toolchain.json evidence
cp "$JAVA_HOME/release" evidence/jdk-release.txt
cp "$RUNNER_TEMP/m1-sdk/platforms/android-37.0/source.properties" evidence/platform-source.properties
cp "$RUNNER_TEMP/m1-sdk/build-tools/36.0.0/source.properties" evidence/build-tools-source.properties
sha256sum "$RUNNER_TEMP"/m1-{jdk.tar.gz,platform.zip,build-tools.zip} > evidence/installed-archives.sha256
sha1sum "$RUNNER_TEMP"/m1-{platform.zip,build-tools.zip} > evidence/sdk-archives.sha1
java -version 2> evidence/java-version.txt
