#!/usr/bin/env bash
# Hosted-only API 36 phone smoke. SDK build inputs remain the reviewed project pins.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf '%s\n' 'Hosted runner only' >&2; exit 1; }
: "${RUNNER_TEMP:?}" "${ANDROID_HOME:?}" "${HOST_ANDROID_HOME:?}"
mkdir -p evidence/native
# Create this before any infrastructure setup, so every failure has an artifact.
: > evidence/native/emulator.log
export ANDROID_USER_HOME="$RUNNER_TEMP/m1-android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ADB_VENDOR_KEYS="$ANDROID_USER_HOME"
export ANDROID_SERIAL=emulator-5554
export GRADLE_USER_HOME="$RUNNER_TEMP/m1-gradle-native-strict"
[[ ! -e "$GRADLE_USER_HOME" ]] || { printf '%s\n' 'Native strict Gradle home must start fresh' >&2; exit 1; }
mkdir -p "$ANDROID_AVD_HOME"
image='system-images;android-36;google_apis;x86_64'
avd='m1-api36-phone'
emulator_pid=''
logcat_pid=''
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
cleanup() {
  status=$?
  trap - EXIT
  set +e
  if [[ -x "$adb" ]]; then
    timeout 15 "$adb" -s "$ANDROID_SERIAL" logcat -d -v threadtime > evidence/native/logcat-final.txt 2>&1
    timeout 15 "$adb" -s "$ANDROID_SERIAL" exec-out screencap -p > evidence/native/diagnostic-screen.png 2> evidence/native/screenshot-error.txt
    timeout 15 "$adb" -s "$ANDROID_SERIAL" emu kill >> evidence/native/cleanup.log 2>&1
  fi
  for pid in "$logcat_pid" "$emulator_pid"; do
    if [[ -n "$pid" ]]; then
      kill "$pid" 2>/dev/null
      cleanup_deadline=$((SECONDS + 10))
      while kill -0 "$pid" 2>/dev/null && (( SECONDS < cleanup_deadline )); do sleep 1; done
      kill -KILL "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
    fi
  done
  if [[ -x "$adb" ]]; then timeout 15 "$adb" kill-server >> evidence/native/cleanup.log 2>&1; fi
  printf 'native_script_exit=%s\n' "$status" > evidence/native/exit-status.txt
  exit "$status"
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
printf '%s\n' 'boundary=host-infrastructure' > evidence/native/boundaries.txt
python3 - <<'PY'
import os, pathlib, shutil
free = shutil.disk_usage(os.environ['RUNNER_TEMP']).free
minimum = 15 * 1024 ** 3
pathlib.Path('evidence/native/disk-check.txt').write_text(f'free_bytes={free}\nminimum_bytes={minimum}\n')
assert free >= minimum, f'Insufficient disk before image installation: {free} < {minimum}'
PY
# No third-party emulator action or self-hosted runner. Use the runner's available tools,
# recording their effective revisions rather than pretending they are project version pins.
if command -v sdkmanager >/dev/null; then
  sdkmanager=$(command -v sdkmanager)
else
  sdkmanager="$HOST_ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
fi
host_tools=$(dirname "$(dirname "$(readlink -f "$sdkmanager")")")
[[ -f "$host_tools/source.properties" ]]
mkdir -p "$ANDROID_HOME/cmdline-tools"
host_tools_name=$(basename "$host_tools")
cp -R "$host_tools" "$ANDROID_HOME/cmdline-tools/$host_tools_name"
sdkmanager="$ANDROID_HOME/cmdline-tools/$host_tools_name/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/$host_tools_name/bin/avdmanager"
[[ -x "$sdkmanager" && -x "$avdmanager" ]]
printf '%s\n' "$sdkmanager" > evidence/native/sdkmanager-path.txt
cp "$ANDROID_HOME/cmdline-tools/$host_tools_name/source.properties" evidence/native/cmdline-tools-source.properties
timeout 30 "$sdkmanager" --version > evidence/native/sdkmanager-version.txt 2>&1
[[ -d "$HOST_ANDROID_HOME/licenses" ]]
cp -R "$HOST_ANDROID_HOME/licenses" "$ANDROID_HOME/licenses"
# Explicit device infrastructure only: never install another compile platform or Build Tools.
sha256sum "$ANDROID_HOME/platforms/android-37.0/source.properties" \
  "$ANDROID_HOME/platforms/android-37.0/package.xml" \
  "$ANDROID_HOME/build-tools/36.0.0/source.properties" \
  "$ANDROID_HOME/build-tools/36.0.0/package.xml" > evidence/native/project-sdk-before.sha256
timeout --signal=TERM --kill-after=30s 10m "$sdkmanager" --sdk_root="$ANDROID_HOME" --channel=0 \
  --install platform-tools emulator "$image" 2>&1 | tee evidence/native/sdk-install.log
sha256sum --check evidence/native/project-sdk-before.sha256
python3 - <<'PY'
import json, os, pathlib
sdk = pathlib.Path(os.environ['ANDROID_HOME'])
paths = ('platform-tools', 'emulator', 'system-images/android-36/google_apis/x86_64')
receipt = {'requestedDevice': {'api': 36, 'image': 'system-images;android-36;google_apis;x86_64',
                             'profile': 'pixel_2', 'abi': 'x86_64'}, 'effectivePackages': {}}
for name in paths:
    source = sdk / name / 'source.properties'
    properties = dict(line.split('=', 1) for line in source.read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    properties = {k.strip(): v.strip() for k, v in properties.items()}
    assert properties.get('Pkg.Revision'), (name, properties)
    if name.startswith('system-images/'):
        assert properties['AndroidVersion.ApiLevel'] == '36', properties
        assert properties['SystemImage.Abi'] == 'x86_64', properties
        assert properties['SystemImage.TagId'] == 'google_apis', properties
    receipt['effectivePackages'][name] = properties
    pathlib.Path('evidence/native/' + name.replace('/', '-') + '-source.properties').write_bytes(source.read_bytes())
pathlib.Path('evidence/native/infrastructure-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
PY
timeout 30 "$emulator" -version > evidence/native/emulator-version.txt 2>&1
sha256sum "$emulator" "$adb" > evidence/native/effective-binaries.sha256
[[ -r /dev/kvm && -w /dev/kvm ]]
timeout 30 "$emulator" -accel-check > evidence/native/acceleration.txt 2>&1
# An explicit acceleration requirement also guards against a software fallback at launch.
printf '%s\n' 'boundary=avd-create' >> evidence/native/boundaries.txt
printf 'no\n' | timeout 90 "$avdmanager" create avd --force --name "$avd" \
  --package "$image" --device pixel_2 > evidence/native/avd-create.log 2>&1
timeout 30 "$emulator" -list-avds > evidence/native/avd-list.txt
grep -Fx "$avd" evidence/native/avd-list.txt
cp "$ANDROID_AVD_HOME/$avd.avd/config.ini" evidence/native/avd-config.ini
"$adb" keygen "$ANDROID_USER_HOME/adbkey" > evidence/native/adb-keygen.log 2>&1
timeout 30 "$adb" start-server > evidence/native/adb-server.log 2>&1
"$emulator" -avd "$avd" -port 5554 -accel on -cores 2 -memory 2048 \
  -no-window -no-snapshot -no-boot-anim -noaudio -gpu swiftshader_indirect \
  > evidence/native/emulator.log 2>&1 &
emulator_pid=$!
printf '%s\n' "$emulator_pid" > evidence/native/emulator.pid
printf '%s\n' 'boundary=boot-wait' >> evidence/native/boundaries.txt
ready=false
deadline=$((SECONDS + 300))
while (( SECONDS < deadline )); do
  kill -0 "$emulator_pid"
  state=$(timeout 10 "$adb" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)
  boot=$(timeout 10 "$adb" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$state" == device && "$boot" == 1 ]] &&
      timeout 10 "$adb" -s "$ANDROID_SERIAL" shell cmd input keyevent KEYCODE_WAKEUP; then
    # Input command success and package-manager readiness are independent boot boundaries.
    if timeout 10 "$adb" -s "$ANDROID_SERIAL" shell pm path android > evidence/native/package-manager-ready.txt; then
      ready=true
      break
    fi
  fi
  printf 'state=%s boot=%s elapsed=%s\n' "$state" "$boot" "$SECONDS" >> evidence/native/boot-wait.log
  sleep 2
done
[[ "$ready" == true ]] || { printf '%s\n' 'Bounded emulator readiness failed' >&2; exit 1; }
timeout 10 "$adb" -s "$ANDROID_SERIAL" devices -l > evidence/native/adb-devices.txt
api=$(timeout 10 "$adb" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$api" == 36 ]]
printf 'observed_device_api=%s\nserial=%s\n' "$api" "$ANDROID_SERIAL" > evidence/native/device-target.txt
for property in ro.build.fingerprint ro.product.cpu.abi ro.product.model; do
  timeout 10 "$adb" -s "$ANDROID_SERIAL" shell getprop "$property" >> evidence/native/device-properties.txt
done
timeout 10 "$adb" -s "$ANDROID_SERIAL" shell wm size > evidence/native/device-size.txt
timeout 10 "$adb" -s "$ANDROID_SERIAL" shell wm density > evidence/native/device-density.txt
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10 "$adb" -s "$ANDROID_SERIAL" shell settings put global "$scale" 0
done
timeout 10 "$adb" -s "$ANDROID_SERIAL" shell input keyevent KEYCODE_MENU
"$adb" -s "$ANDROID_SERIAL" logcat -v threadtime > evidence/native/logcat-live.txt 2>&1 &
logcat_pid=$!
printf '%s\n' 'boundary=strict-native-tests' >> evidence/native/boundaries.txt
# The complete native test task, no class/method filters and no metadata bypass/generation.
set +e
timeout --signal=TERM --kill-after=30s 15m ./gradlew --no-daemon --dependency-verification strict \
  --stacktrace --info -I tools/build/toolchain.init.gradle :app:m1ToolchainCheckpoint \
  :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:connectedDebugAndroidTest \
  2>&1 | tee evidence/native/strict-connected.log
test_status=$?
set -e
printf 'connected_task_exit=%s\n' "$test_status" > evidence/native/connected-exit-status.txt
(( test_status == 0 ))
python3 tools/build/verify_m1_manifests.py
python3 tools/build/verify_m1_test_reports.py native
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump permissions app/build/outputs/apk/debug/app-debug.apk \
  > evidence/native/apk-permissions.txt
python3 - <<'PY'
from pathlib import Path
assert 'android.permission.INTERNET' not in Path('evidence/native/apk-permissions.txt').read_text()
PY
printf '%s\n' 'boundary=native-tests-and-apk-permissions-pass' >> evidence/native/boundaries.txt
