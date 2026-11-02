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
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'emulator' 'system-images;android-35;default;x86_64'
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name focal_test --package 'system-images;android-35;default;x86_64'
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
"$ANDROID_HOME/platform-tools/adb" shell settings put global animator_duration_scale 0
./gradlew :app:connectedDebugAndroidTest --console=plain
