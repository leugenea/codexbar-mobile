#!/usr/bin/env bash
# Hosted-only API 36 phone smoke. SDK build inputs remain the reviewed project pins.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf '%s\n' 'Hosted runner only' >&2; exit 1; }
: "${RUNNER_TEMP:?}" "${ANDROID_HOME:?}" "${GRADLE_USER_HOME:?}"
mkdir -p evidence/native
# Create this before any infrastructure setup, so every failure has an artifact.
: > evidence/native/emulator.log
export ANDROID_USER_HOME="$RUNNER_TEMP/android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ADB_VENDOR_KEYS="$ANDROID_USER_HOME"
export ANDROID_SERIAL=emulator-5554
# setup-gradle restores/saves this job-level home; never override it here.
printf 'gradle_user_home=%s\n' "$GRADLE_USER_HOME" > evidence/native/gradle-home.txt
mkdir -p "$ANDROID_AVD_HOME"
image='system-images;android-36;google_apis;x86_64'
avd='android-api36-phone'
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
  python3 tools/build/coverage_gate.py phases --exit "$status"
  receipt_status=$?
  if (( status == 0 && receipt_status != 0 )); then status=$receipt_status; fi
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
# setup-android's explicit build 15859902 installs tools22 in the isolated SDK.
# Device infrastructure is observed separately from unchanged project version pins.
sdkmanager="$ANDROID_HOME/cmdline-tools/22.0/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/22.0/bin/avdmanager"
[[ -x "$sdkmanager" && -x "$avdmanager" ]]
printf '%s\n' "$sdkmanager" > evidence/native/sdkmanager-path.txt
printf '%s\n' "$avdmanager" > evidence/native/avdmanager-path.txt
cp "$ANDROID_HOME/cmdline-tools/22.0/source.properties" evidence/native/cmdline-tools-source.properties
grep -Ex 'Pkg.Revision[[:space:]]*=[[:space:]]*22\.0' evidence/native/cmdline-tools-source.properties
sha256sum "$sdkmanager" "$avdmanager" > evidence/native/cmdline-tools-binaries.sha256
timeout 30 "$sdkmanager" --version > evidence/native/sdkmanager-version.txt 2>&1
[[ -d "$ANDROID_HOME/licenses" ]]
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
"$emulator" -avd "$avd" -port 5554 -accel on -cores 2 -memory 4096 \
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
: > evidence/native/boot-success.txt
printf '%s\n' 'boundary=strict-native-tests' >> evidence/native/boundaries.txt
# The complete native test task, no class/method filters and no metadata bypass/generation.
# Keep the original 15-minute graph budget across both attempts. A retry is
# allowed only for missing/empty/truncated native coverage AND ADB-offline evidence after
# passing suites. It reruns the whole graph: JVM/native data must share compilation.
graph_deadline=$((SECONDS + 900))
coverage_attempt=1
while :; do
  attempt_dir="evidence/native/attempt-$coverage_attempt"
  mkdir -p "$attempt_dir"
  printf 'coverage_graph_attempt=%s\n' "$coverage_attempt" >> evidence/native/boundaries.txt
  remaining=$((graph_deadline - SECONDS))
  (( remaining > 0 )) || exit 124
  set +e
  timeout --signal=TERM --kill-after=30s "${remaining}s" ./gradlew --no-daemon --dependency-verification strict \
    --build-cache --no-configuration-cache \
    --stacktrace --info --console=plain -I tools/build/toolchain.init.gradle :app:verifyResolvedToolchain \
    :app:processReleaseManifest :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest \
    :app:connectedDebugAndroidTest :app:jacocoDebugCoverageVerification \
    2>&1 | tee evidence/native/strict-connected.log "$attempt_dir/strict-connected.log" | bash tools/build/filter-gradle-console.sh
  graph_status=("${PIPESTATUS[@]}")
  test_status=${graph_status[0]}
  printf 'graph_task_exit=%s\n' "$test_status" | tee evidence/native/graph-exit-status.txt "$attempt_dir/graph-exit-status.txt"
  receipt_status=$?
  set -e
  # Logging failures are not coverage-transport retries; keep the graph exit first.
  if (( graph_status[1] != 0 || graph_status[2] != 0 || receipt_status != 0 )); then
    if (( test_status != 0 )); then exit "$test_status"; fi
    exit 1
  fi
  # Reject reused/skipped gates on every attempt, before considering a transport retry.
  set +e
  python3 tools/build/verify_gradle_execution.py native "$attempt_dir/strict-connected.log" \
    --graph-exit "$test_status" > "$attempt_dir/gate-execution.log" 2>&1
  gate_status=$?
  set -e
  if (( gate_status != 0 )); then
    if (( test_status != 0 )); then exit "$test_status"; fi
    exit "$gate_status"
  fi
  python3 tools/build/coverage_gate.py phases > "$attempt_dir/phases.log" 2>&1
  cp evidence/native/task-phase-outcomes.json "$attempt_dir/task-phase-outcomes.json"
  if (( test_status == 0 || coverage_attempt == 2 )); then break; fi
  if ! python3 -B tools/build/native_coverage_retry.py --exit "$test_status" \
      > evidence/native/retry-decision.log 2>&1; then break; fi
  printf '%s\n' 'Retrying the full coverage graph once after ADB-offline incomplete coverage; attempt 1 is preserved.' >&2
  timeout 15 "$adb" -s "$ANDROID_SERIAL" logcat -d -v threadtime \
    > "$attempt_dir/logcat-at-retry.txt" 2>&1 || true
  # Reconnection, not a blind delay. No force-stop/pm clear/emulator teardown
  # occurs before AGP has finished collection and the failed data is archived.
  retry_ready=false
  retry_deadline=$((SECONDS + 60))
  while (( SECONDS < retry_deadline && SECONDS < graph_deadline )); do
    if ! kill -0 "$emulator_pid"; then break; fi
    state=$(timeout 5 "$adb" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)
    boot=$(timeout 5 "$adb" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    printf 'state=%s boot=%s elapsed=%s\n' "$state" "$boot" "$SECONDS" >> evidence/native/retry-readiness.log
    if [[ "$state" == device && "$boot" == 1 ]] &&
        timeout 5 "$adb" -s "$ANDROID_SERIAL" shell pm path android >> evidence/native/retry-readiness.log 2>&1; then
      retry_ready=true
      break
    fi
    sleep 1
  done
  [[ "$retry_ready" == true ]] || exit "$test_status"
  coverage_attempt=2
done
# Evaluate the actual suites even if a later coverage task failed.
set +e
python3 tools/build/verify_manifests.py
manifest_status=$?
python3 tools/build/verify_test_reports.py native
native_report_status=$?
python3 tools/build/verify_test_reports.py jvm
jvm_report_status=$?
# Inspect and bind the exact APK before reinstalling it after AGP's uninstall.
# This is outside the retry loop: installed observations run only on the final attempt.
apk='app/build/outputs/apk/debug/app-debug.apk'
sha256sum "$apk" > evidence/native/inspected-apk.sha256
apk_hash_status=$?
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump permissions "$apk" \
  > evidence/native/apk-permissions.txt
aapt_status=$?
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump xmltree "$apk" AndroidManifest.xml \
  > evidence/native/apk-manifest-xmltree.txt
apk_xmltree_status=$?
python3 tools/build/verify_manifests.py --apk-permissions evidence/native/apk-permissions.txt \
  --apk-xmltree evidence/native/apk-manifest-xmltree.txt
apk_policy_status=$?
sha256sum --check evidence/native/inspected-apk.sha256 > evidence/native/install-apk-hash-check.txt 2>&1
install_hash_status=$?
install_status=1
installed_path_status=1
installed_path_policy_status=1
installed_dump_status=1
permission_status=1
# Missing/mutated/unverified APKs must never reach adb install.
if (( apk_hash_status == 0 && aapt_status == 0 && apk_xmltree_status == 0 && apk_policy_status == 0 && install_hash_status == 0 )); then
  timeout 30 "$adb" -s "$ANDROID_SERIAL" install -r "$apk" > evidence/native/installed-package-install.txt 2>&1
  install_status=$?
  if (( install_status == 0 )); then
    grep -Eq $'^Success\r?$' evidence/native/installed-package-install.txt
    install_status=$?
  fi
  if (( install_status == 0 )); then
    timeout 15 "$adb" -s "$ANDROID_SERIAL" shell pm path io.github.leugenea.codexbarmobile \
      > evidence/native/installed-package-path.txt
    installed_path_status=$?
    python3 tools/build/verify_manifests.py --installed-path evidence/native/installed-package-path.txt
    installed_path_policy_status=$?
    if (( installed_path_status == 0 && installed_path_policy_status == 0 )); then
      timeout 15 "$adb" -s "$ANDROID_SERIAL" shell dumpsys package io.github.leugenea.codexbarmobile \
        > evidence/native/installed-package.txt
      installed_dump_status=$?
      python3 tools/build/verify_manifests.py --apk-permissions evidence/native/apk-permissions.txt \
        --apk-xmltree evidence/native/apk-manifest-xmltree.txt \
        --installed-path evidence/native/installed-package-path.txt --installed-package evidence/native/installed-package.txt
      permission_status=$?
    fi
  fi
fi
# Always attempt cleanup, including a partial/failed installation or failed observation.
timeout 15 "$adb" -s "$ANDROID_SERIAL" uninstall io.github.leugenea.codexbarmobile \
  > evidence/native/installed-package-uninstall.txt 2>&1
uninstall_status=$?
if (( uninstall_status == 0 )); then
  grep -Eq $'^Success\r?$' evidence/native/installed-package-uninstall.txt
  uninstall_status=$?
fi
printf 'apk_hash_exit=%s\naapt_exit=%s\napk_xmltree_exit=%s\napk_policy_exit=%s\ninstall_hash_exit=%s\ninstall_exit=%s\ninstalled_path_exit=%s\ninstalled_path_policy_exit=%s\ninstalled_dump_exit=%s\npermission_exit=%s\nuninstall_exit=%s\n' \
  "$apk_hash_status" "$aapt_status" "$apk_xmltree_status" "$apk_policy_status" "$install_hash_status" "$install_status" "$installed_path_status" \
  "$installed_path_policy_status" "$installed_dump_status" "$permission_status" "$uninstall_status" \
  > evidence/native/installed-observation-status.txt
set -e
# Preserve the original graph failure, rather than masking it with cleanup/parsers.
if (( test_status != 0 )); then exit "$test_status"; fi
(( manifest_status == 0 && native_report_status == 0 && jvm_report_status == 0 && apk_hash_status == 0 && aapt_status == 0 && apk_xmltree_status == 0 && apk_policy_status == 0 && install_hash_status == 0 && install_status == 0 && installed_path_status == 0 && installed_path_policy_status == 0 && installed_dump_status == 0 && permission_status == 0 && uninstall_status == 0 ))
printf '%s\n' 'boundary=native-tests-and-apk-permissions-pass' >> evidence/native/boundaries.txt
