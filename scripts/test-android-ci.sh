#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test -e /dev/kvm || { echo '::error::Hardware Android virtualization is unavailable'; exit 1; }
sudo chmod 666 /dev/kvm
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'emulator' 'system-images;android-35;default;x86_64'
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name focal_test --package 'system-images;android-35;default;x86_64'
"$ANDROID_HOME/emulator/emulator" -avd focal_test -no-window -gpu swiftshader_indirect -no-boot-anim -no-snapshot -memory 2048 -cores 2 > build/emulator.log 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT
timeout 180 "$ANDROID_HOME/platform-tools/adb" wait-for-device
booted=false
for attempt in $(seq 1 90); do
    if [[ $("$ANDROID_HOME/platform-tools/adb" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]; then booted=true; break; fi
    sleep 2
done
if [[ $booted != true ]]; then tail -n 40 build/emulator.log; exit 1; fi
"$ANDROID_HOME/platform-tools/adb" shell input keyevent 82
"$ANDROID_HOME/platform-tools/adb" shell settings put global window_animation_scale 0
"$ANDROID_HOME/platform-tools/adb" shell settings put global transition_animation_scale 0
"$ANDROID_HOME/platform-tools/adb" shell settings put global animator_duration_scale 0
./gradlew :app:connectedDebugAndroidTest --console=plain
