# Local Android emulator QA

Use a **dedicated test AVD** with no personal data. The suite installs and
updates `app.focal` and `app.focal.test`, records synthetic and emulator microphone
input, changes app data and the clipboard, and grants permissions. It temporarily
sets Android's three animation scales to 1 and restores their previous values,
including unset values. It never uninstalls an app or wipes an AVD. An interrupted
or unreachable emulator can prevent restoration; the original scales are saved
in the private manifest before any scale is changed. If an install fails because an
existing app uses another signature, use a different dedicated AVD; the runner
will not uninstall it for you.

This is a local development check, not a replacement for physical-phone testing.
The automated checks include real offline speech model loading and the bundled
public speech fixture, but emulators do not establish phone battery, thermal,
background reliability, or Russian recognition quality.

## Prepare once

1. Install Python 3.10+, Git, JDK 17, Android SDK 35, build tools 35.0.0,
   NDK 28.0.13004108, CMake 3.22.1 and Android SDK platform tools. Set
   `ANDROID_HOME` or configure the SDK path in the untracked `local.properties`.
   Add `adb`, Java and Git to your PATH. On Windows use the same SDK and JDK
   versions and run these commands from PowerShell with `py -3` if preferred.
   The Windows Gradle wrapper requires the checkout and `JAVA_HOME` paths to
   avoid cmd metacharacters (`& | < > ^ % ! ( )`); spaces are supported.
2. Initialize the recorded engine submodule and obtain the verified model:

   ```sh
   git submodule update --init --recursive
   python3 scripts/fetch-model.py
   ```

   Reuse existing checksum-verified model assets where available. Before any new
   transfer, carry forward the [shared download ledger](benchmarks/2026-11-03-stt/download-budget.json)
   and reserve the pinned 59,707,625 model bytes (including any repeated attempt).
   `fetch-model.py` does not update that cross-environment research ledger; record
   the reservation explicitly rather than resetting the budget on the laptop.
   Model fetching needs network access. The runner itself does not download the
   model or silently replace it. Gradle may resolve missing build dependencies;
   provision those in advance if working offline. The runner requires a clean,
   pinned submodule and checks the model's committed digest and provenance.
3. Create and start a dedicated Android Studio AVD with hardware acceleration
   enabled for the host. An API 35 Google APIs/default system image with a
   compatible architecture (x86_64 on x86 hosts, arm64 on Apple Silicon) is a
   reasonable starting point. Give it enough CPU and RAM for the full model;
   4 virtual CPUs and 4 GiB are a starting point, not a measured phone profile.
   Wait for it to boot and unlock it. Ensure microphone input works if you want to
   run the separate manual recording checks.
4. List devices with `adb devices` and copy the exact `emulator-NNNN` serial.
   `--serial` and the dedicated-AVD confirmation are mandatory, and the runner
   verifies that this serial is a booted emulator. Physical phones are rejected.

## Run

From the repository root, on macOS/Linux:

```sh
python3 scripts/qa-local.py --serial emulator-5554 --dedicated-test-avd
```

On Windows PowerShell:

```powershell
py -3 scripts/qa-local.py --serial emulator-5554 --dedicated-test-avd
```

Replace the example serial with the one from `adb devices`. If `adb` is not on
PATH, pass its path with `--adb`, for example `--adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"`.
To repeat the **full** instrumentation suite, use `--repeat 3` (allowed range
1–20). Each run has a 30-minute timeout by default; use `--timeout-minutes` from
5–120 if needed. On a timeout or interruption the runner force-stops the two
Focal packages to end test work; it does not clear their data. It stops on the
first failure. Use `--release-checks` to add release lint and the unsigned
release build. Builds and host tests run once, before all repetitions.

The runner executes core unit tests, debug lint, the debug and instrumentation APK
builds, then verifies the actual APK's embedded model against the committed
SHA-256 and checks the test APK's speech fixture before installing either APK.
After installation it starts Focal and verifies the SHA-256 of the model in its
private app storage using `run-as`. If an existing imported or older model differs,
the runner fails instead of deleting or replacing app data; use a fresh dedicated
AVD or explicitly replace that model. The installed-model digest is in the manifest.
It runs all instrumentation tests directly with `adb -s SERIAL`, independently
of Gradle's device discovery. A run fails for a test failure, skipped test,
crash, missing completion or count, any missing source-discovered test, or any
of the five missing real-model checks. Source changes during build or testing
also fail the run; avoid editing the checkout while it runs. The test discovery
check deliberately fails closed if new test source syntax is unsupported.

Each invocation writes a fresh private directory under
`build/local-emulator-qa/`, which Git ignores. `manifest.json` records the Git
HEAD, dirty-state fingerprint, pinned submodule commits, model and APK hashes,
host/emulator resources and tool versions. `build.log`, core test results and
lint reports describe the build; each `run-NN/` has raw instrumentation output,
a JUnit XML summary and a JSON outcome. Raw test and build logs may contain
private information or local paths. Do not commit or upload them without review;
clean them up when no longer needed. The runner requests owner-only directory
permissions on Unix; on Windows, Git ignoring the directory does not restrict
its access, so check the parent folder's ACLs. If the emulator disconnects before settings
are restored, reconnect it and restore each value from the manifest with
`adb -s SERIAL shell settings put global KEY VALUE`; use
`adb -s SERIAL shell settings delete global KEY` for an original `null` value.

## Current focused reproduction

The checkpoint at `1df8060` has one unresolved API 35 UI failure:
`InterfaceTest.oversizedEditorInputKeepsTheValidDraftThroughRotation`. The valid
draft survives activity recreation, but the rejected-input warning exists outside
the visible viewport even after a bounded visibility wait. The test uses activity
recreation; actual portrait/landscape rotation still needs manual validation.

After installing the verified app/test APKs on the dedicated AVD, isolate it with:

```sh
adb -s emulator-5554 shell am instrument -w -e class 'app.focal.InterfaceTest#oversizedEditorInputKeepsTheValidDraftThroughRotation' app.focal.test/androidx.test.runner.AndroidJUnitRunner
```

This focused command is for diagnosis; it does not satisfy the full-suite or
five-model-test gate. In Android Studio, debug this test and break in
`assertWarningVisible` after recreation, before its `finally` removes the
synthetic fixture. Inspect the real dialog viewport, warning/field bounds,
scroll offset, IME insets and any overlapping dialog. Record API, window size,
density, font scale and animation settings. Keep captures and raw diagnostics
private. Do not weaken the warning-visibility or draft-retention assertions to
make the result green. After fixing the observed cause, rerun this scenario and
then the complete `qa-local.py` suite with no skips.

## Manual longer-session matrix

The automated suite does not perform a 30-minute capture. On the same dedicated
AVD, record a synthetic, non-private session and write down the build HEAD,
emulator configuration and observations. Compare against
[device validation](device-validation.md), and repeat on actual phones before
making device-quality claims.

| Scenario | Observe and record |
| --- | --- |
| 30 minutes with the screen off, plus app switching and task swipe | Ongoing notification, elapsed duration, whether capture continues and whether saved audio is playable afterward; note any emulator restrictions. |
| Pause and resume in app and notification | Paused time is excluded and later audio is preserved; compare elapsed time with the saved recording. |
| Capture while transcription is active | Capture controls remain responsive, saved audio remains intact, and pending work finishes or offers recovery. |
| During the long session and after finishing | Sample process memory with Android Studio Profiler or `adb -s SERIAL shell dumpsys meminfo app.focal`; record approximate peak, start/end values and pending work. |
| Finish, reopen, edit, play and remove audio on a synthetic note | Edits and playback match expectations; removing audio preserves text. |

Avoid taking screenshots or logs of private notes. Repeated full automated runs
can supplement this matrix, but they do not simulate the screen-off interval.
