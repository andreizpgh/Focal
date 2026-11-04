#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
exec > >(tee build/android-device-tests.log) 2>&1
report_failure() {
    local result=$?
    python3 - <<'PY'
from pathlib import Path
text = Path('build/android-device-tests.log').read_text(errors='replace')[-5000:]
print('::error title=Android device test details::' + text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
PY
    exit "$result"
}
trap report_failure ERR
test -e /dev/kvm || { echo '::error::Hardware Android virtualization is unavailable'; exit 1; }
sudo chmod 666 /dev/kvm
export ANDROID_AVD_HOME="$PWD/build/avd"
mkdir -p "$ANDROID_AVD_HOME"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'emulator' 'system-images;android-35;default;x86_64'
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name focal_test --path "$ANDROID_AVD_HOME/focal_test.avd" --package 'system-images;android-35;default;x86_64'
test -f "$ANDROID_AVD_HOME/focal_test.ini"
"$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fx focal_test
"$ANDROID_HOME/platform-tools/adb" start-server
"$ANDROID_HOME/emulator/emulator" -avd focal_test -accel on -no-window -gpu swiftshader -no-boot-anim -no-snapshot -memory 2048 -cores 2 > build/emulator.log 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT
report_boot_error() {
    python3 - <<'PY'
from pathlib import Path
text = Path('build/emulator.log').read_text(errors='replace')[-4000:]
print('::error title=Android emulator startup::' + text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
PY
}
if ! timeout 180 "$ANDROID_HOME/platform-tools/adb" wait-for-device; then report_boot_error; exit 1; fi
booted=false
for attempt in $(seq 1 90); do
    if [[ $("$ANDROID_HOME/platform-tools/adb" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]; then booted=true; break; fi
    sleep 2
done
if [[ $booted != true ]]; then report_boot_error; exit 1; fi
"$ANDROID_HOME/platform-tools/adb" shell input keyevent 82
"$ANDROID_HOME/platform-tools/adb" shell settings put global window_animation_scale 0
"$ANDROID_HOME/platform-tools/adb" shell settings put global transition_animation_scale 0
# Keep in-content animation enabled: the processing indicator has a visual motion check.
"$ANDROID_HOME/platform-tools/adb" shell settings put global animator_duration_scale 1
# Bound individual test methods as well as the suite. A method timeout cannot
# guarantee interruption of a stuck main-thread teardown, so retain the outer
# limit and leave time to publish diagnostics. Either timeout fails the job.
timeout --signal=TERM --kill-after=30s 19m ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=180000 --console=plain
