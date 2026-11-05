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
   python3 scripts/fetch-vad-model.py
   ```

   Reuse existing checksum-verified model assets where available. Before any new
   transfer, carry forward the [shared download ledger](benchmarks/2026-11-03-stt/download-budget.json)
   and reserve the pinned 59,707,625 model bytes (including any repeated attempt).
   `fetch-model.py` does not update that cross-environment research ledger; record
   the reservation explicitly rather than resetting the budget on the laptop.
   The optional background-sound check also requires the pinned 885,098-byte
   Silero asset. Reuse it with `python3 scripts/fetch-vad-model.py --local-file PATH`;
   a verified installed asset skips any network request. Reserve those bytes only
   for an actual new transfer.
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

### Linux launch configuration used for this checkpoint

The local API 35 default x86_64 AVD completed QA with Android Emulator 37.1.11,
KVM acceleration, host GLES rendering and Vulkan disabled. This combination can
be selected for an existing dedicated AVD without changing global host settings:

```sh
emulator -avd focal_local_qa -accel on -gpu host -feature -Vulkan \
  -cores 2 -memory 2048 -no-snapshot -no-window -no-audio
```

If the dedicated AVD lives in the ignored workspace directory `build/avd`, run
the command from the repository root with `ANDROID_AVD_HOME="$PWD/build/avd"`
in its environment. Keep using that same AVD directory on restart; an absent
default-home AVD is not a reason to create or wipe another device.

Replace `focal_local_qa` with the dedicated AVD's name. The emulator raised the
requested memory to **2560 MiB**; record the effective configuration, not only
the command-line request. The tested display was 320 × 640 at 160 dpi, with
font scale 1.0. Other AVDs were untouched; no wipe or global configuration change
was needed. KVM accelerates the virtual CPU independently of Vulkan graphics;
disabling Vulkan does not disable KVM.

Two earlier SwiftShader-based launch attempts crashed. The successful attempt
also changed memory and CPU allocation, so the exact cause is undetermined;
this is a working local configuration, not a general diagnosis or guarantee.
The `-no-audio` launch disables host audio, but its recorded PCM need not be
exactly zero. It supports synthetic and emulator-input checks; it does not verify
real microphone input or audible playback. Use a separate launch with audio
enabled for those manual checks.

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
release build. Builds and core unit tests run once, before all repetitions.
The separate Python host-tool suite is not run by this command.

The runner executes core and app JVM unit tests, debug lint, the debug and instrumentation APK
builds, then verifies the actual APK's embedded model against the committed
SHA-256 and checks the test APK's speech fixture before installing either APK.
The pinned speech-presence model is checked in source and in the exact APK as well.
After installation it starts Focal and verifies the SHA-256 of the model in its
private app storage using `run-as`. If an existing imported or older model differs,
the runner fails instead of deleting or replacing app data; use a fresh dedicated
AVD or explicitly replace that model. The installed-model digest is in the manifest.
It runs all instrumentation tests directly with `adb -s SERIAL`, independently
of Gradle's device discovery. A run fails for a test failure, skipped test,
crash, missing completion or count, any missing source-discovered test, or any
of the ten missing real-model checks (including real VAD speech/reset and graph-error recovery). Source changes during build or testing
also fail the run; avoid editing the checkout while it runs. The test discovery
check deliberately fails closed if new test source syntax is unsupported.

Each invocation writes a fresh private directory under
`build/local-emulator-qa/`, which Git ignores. `manifest.json` records the Git
HEAD, dirty-state fingerprint, pinned submodule commits, model and APK hashes,
host/emulator resources and tool versions. `build.log`, core/app test results and
lint reports describe the build; each `run-NN/` has raw instrumentation output,
a JUnit XML summary and a JSON outcome. Raw test and build logs may contain
private information or local paths. Do not commit or upload them without review;
clean them up when no longer needed. The runner requests owner-only directory
permissions on Unix; on Windows, Git ignoring the directory does not restrict
its access, so check the parent folder's ACLs. If the emulator disconnects before settings
are restored, reconnect it and restore each value from the manifest with
`adb -s SERIAL shell settings put global KEY VALUE`; use
`adb -s SERIAL shell settings delete global KEY` for an original `null` value.

## UX Alpha 3 verification · 2026-11-04

Clean `ed9d7cb` passed `qa-local.py --release-checks`: **94 Android tests,
zero failures/skips**, eight mandatory model checks, 149.326 instrumentation
seconds. Gradle reported 5m; debug/release lint, debug/test/unsigned R8 release
and 36 cached core tests passed. The [release manifest](../releases/ux-alpha-3.json)
binds exact artifacts; the [quality report](quality-report.md) separates focused,
manual and full-suite evidence. The dedicated AVD uses KVM and two vCPUs. Checks
ran sequentially with low priority and CPU affinity 0–3.

The new editor-window regression failed against the preceding UI candidate and
passed with the dialog-color fix using the same test APK. The final candidate
also passed manual 200% font landscape editing with the keyboard, scrolling to
Save and retaining the edit after returning to portrait. Light/dark, popup/menu
geometry and actual seek taps outside the visible track were checked with
synthetic notes. No owner screenshot or recording was published. These are
emulator checks, not phone speech, battery, thermal or audible-audio evidence.

## Verified local checkpoints · 2026-11-03–04

Seven complete `qa-local.py --release-checks` runs passed:

| App source checkpoint | Android tests | Failures / skips | Mandatory real-model tests | Instrumentation time |
| --- | ---: | ---: | ---: | ---: |
| `12e958c`, editor fix and subsequent long-session manual checks | 77 | 0 / 0 | 5 | 116.109 s |
| `ff8ea8d`, exact-constant PCM and microphone-notice fixes | 80 | 0 / 0 | 8 | 178.355 s |
| Clean HEAD `129ee02`, Unicode title and rename-focus fixes | 82 | 0 / 0 | 8 | 180.543 s |
| Clean HEAD `b59c6ee`, scrollable Rename and search lifecycle/layout fixes | 84 | 0 / 0 | 8 | 144.224 s |
| Clean HEAD `101a153`, search caret preserved on result navigation | 85 | 0 / 0 | 8 | 151.721 s |
| Clean HEAD `7760ff2`, ownership-only native fix | 85 | 0 / 0 | 8 | 155.054 s |
| Clean HEAD `036d716`, service cancellation, retry feedback and test synchronization | 87 | 0 / 0 | 8 | 149.980 s |

The latest run verified **36 core tests** (cached unchanged outputs), debug and
release lint, and debug, instrumentation and unsigned release/R8 builds at clean
HEAD `036d716`. **157 Python host-tool tests** passed separately in 9.844 s with
the existing hash-pinned environment. Those tested host changes were committed in
`9ffa3d1`; subsequent Android changes did not alter host sources. The host suite
is not part of this runner's test count.
The first two runs recorded dirty working trees at HEAD `388aee0` and `c21ae20`,
respectively, with 34 core tests; their tested app changes were subsequently
committed through the checkpoints above. These earlier source mappings are not
claims of clean-HEAD runs. The [public aggregate](benchmarks/2026-11-03-local/emulator-qa.json)
retains all seven passing runner source fingerprints and APK identities, plus
two later failed attempts separately. Raw logs and
device identifiers are not published.

The latest 2026-11-04 run limited host work to four logical CPUs, two Gradle workers and
sequential project execution, with reduced CPU priority. Its debug APK is
`b68946e904ed69b8c5123316f64611f5e3cc8334049191c91e5395255f6ae647`.
Both debug and unsigned release APKs contain the verified base model without
Silero assets; neither merged manifest requests Internet permission. Durations
above are verification workload timings, not comparative app or phone performance.

The runner records APK hashes but does not archive APK files. Preserve exact
verified debug/test APKs privately before rebuilding when the next regression
requires those same binaries. A source-matched rebuild has its own artifact hash;
do not label it as the original APK. The latest verified artifacts were archived
locally after QA, and the dedicated AVD was stopped without wiping its data.

The `057daca` full run failed one of 86 tests: its recovery fixture did not wait
for ownership release before retrying. A later stronger completion wait observed
that release lag and passed both focused cases; the old command branch was not logged. The `f359fd5` full run failed one of 87 tests at a search cursor
assertion. An observed diagnostic pass found position 14 after tapping and 22 after
setting the cursor, opening the IME and Back; the exact late event in the failed
attempt remains unknown. `036d716` waits for IME visibility before setting the
cursor and adds a pre-Back assertion. All later assertions and the original
oversized-editor checks remain. Three focused tests passed before the fresh full
87-test run. Neither failed full run has been relabeled as passing.

The previously failing
`InterfaceTest.oversizedEditorInputKeepsTheValidDraftThroughRotation` was
reproduced twice at checkpoint `388aee0`. Direct observation corrected the earlier
offscreen-warning hypothesis: after activity recreation, `TextField.onValueChange`
repeated the already accepted draft, clearing `inputTooLarge`. The editor capture
showed no warning; scrolling was not the cause. Commit `12e958c` ignores unchanged
text callbacks, preserving rejection feedback until a real edit. The original
strict test passed twice after the fix, and an additional regression checks both
unchanged callbacks and a genuine text change.

Commit `6872bb4` preserves surrogate pairs at the existing manual/automatic title
limits. Before it, pasting 119 ASCII characters plus an emoji cut the emoji in
half; SQLite retained the invalid high-surrogate bytes `ED A0 BD`. Afterward,
the same paste kept 119 valid characters, and 118 characters plus an emoji saved
as 120 UTF-16 units with intact emoji bytes `F0 9F 99 82`. Core boundary cases and
a SQLite round-trip regression cover the truncation paths.

Actual rotation with Rename and its keyboard open exposed a separate
`FocusRequester` initialization crash on `6872bb4`. Commit `129ee02` places the
focus effect inside the dialog's composition, alongside its field. The new
`InterfaceTest.renamedTitleDraftAndKeyboardSurviveRecreation` failed on the
previous implementation in 2.017 s and passed after the fix in 2.905 s. It checks
focus, visible IME, preserved draft and saving; both new Android regressions are
included in the clean 82-test run. These durations describe focused tests, not
performance benchmarks.

Commit `a5e460d` also prevents **exactly constant** zero/DC PCM from reaching
Whisper during recovery. A new native regression failed on the previous APK:
0.5 seconds of zero PCM in English returned one segment (focused run: 31.474 s).
The three added regressions then passed together in 39.440 s: 126 constant-input
conditions across all seven languages, quiet varying public speech, and recovery
that preserves original audio and existing manual edits without adding passages.
These are test-run durations, not phone decoder performance measurements.
The complete 80-test run includes all three. Varying low-level noise remains an
open failure described below; the exact-constant fix does not solve it.

The full automated result is separate from the manual observations below.
The editor test uses activity recreation; actual portrait/landscape rotation
was also checked manually with a rejected paste and a legacy passage.

### Manual observations

Unless a later build is named, these checks used debug app source `12e958c`,
synthetic notes and the dedicated emulator above. The public aggregate separates
these observations from the later build's checks; it contains no recordings,
transcripts, screenshots or device identifiers.

| Scenario | Observed result |
| --- | --- |
| Record, Home, swipe the task away, screen off, return | Capture continued. The final saved recording was 9,157,264 ms (about 2 h 32 min), with `READY` state and 293,032,448 PCM bytes, exactly duration × 32. The live gate produced no chunks or transcript with host audio disabled. |
| Foreground service and memory during screen-off capture | All 31 samples over 1,800 seconds showed a foreground recording service. PSS ranged from 177,118 to 179,802 KiB; start/end were 179,802/178,768 KiB. This monitoring covered the first 30 minutes only; a separate later snapshot was 180,212 KiB. |
| Pause, resume and Finish in the app | Two paused checks retained the same 292,534,272-byte file. Resuming added audio before Finish. |
| Playback and seek after finishing | Playback position advanced and seeking changed the position. Input/output audio was disabled, so this verifies control/state behavior, not audible playback. |
| Oversized paste and orientation change | The preceding valid draft and rejection warning remained visible with the keyboard and after portrait/landscape rotation. Save & next opened the following review passage. |
| Russian edit, copy and share | The saved edit copied exactly as the expected 176 UTF-8 bytes, including paragraph breaks; the Android share sheet opened. No external share target was used. |
| Dark theme and font scale 2.0 | A 4,000-line, 88,000-byte draft remained editable; adding the synthetic marker and saving produced 88,005 bytes after trailing-newline trimming. |
| Legacy large text and transfer refusal | A 380,017-byte passage opened in 25 read-only parts; Part 2 survived rotation. Refused whole-transcript Copy preserved the previous 176-byte clipboard. |
| Notification Pause, Resume and Finish | Pause showed 00:29 and persisted `PAUSED`; Resume showed recording at 00:43; Finish produced a completed 00:44 Russian-language session. |
| Speech-language picker | All seven supported language labels were visible. The automated suite separately checks selection and persistence. |
| Microphone denial, retry and grant on `ff8ea8d` | Denial showed the Settings notice without starting capture. Retrying showed Android's permission dialog without the stale notice; granting permission started recording and removed obsolete feedback. |
| Unicode title paste/save on `6872bb4` | Real paste at the 120-unit boundary kept complete Unicode characters; saving preserved the exact expected title in SQLite. |
| Rename rotation on `129ee02` | Actual portrait-to-landscape rotation retained the draft, focused field and visible IME without crashing. Save was below the landscape keyboard; Back hid the IME, after which Save persisted the exact draft. Visibility of Save with the landscape IME open was not established. |
| Rename at 200% font in landscape on the same APK | The focused field remained usable. The keyboard Done action dismissed the dialog and saved the exact synthetic title, confirmed in SQLite. The dialog heading was clipped at the top; this does not establish visibility of every dialog element while the IME is open. Font and rotation settings were restored afterward. |

The final ordinary APK (`036d716` QA, app hash `b68946e9…ae647`) also passed a
short record → pause → Home → return → resume → Finish check. The paused timer
stayed at 00:21; final `READY` duration 32,896 ms matched 1,052,672 PCM bytes exactly.
Playback position advanced and seeking reached 00:19. Rename kept its draft at
200% font through actual landscape rotation; scrolling exposed the full Save
button above the IME and the saved title matched SQLite. Settings were restored
and the dedicated AVD stopped. This does not repeat the earlier long-session,
editor, audible playback or physical-device checks.

The screen-off interval lasted roughly 2.5 hours, but service/memory samples
were collected only for the first 30 minutes. This was capture without host audio;
it does not exercise sustained speech inference, host microphone input, audible
output, phone battery/thermals, OEM background restrictions, calls or TalkBack.
The remaining device matrix still needs those separate checks.

### Open manual recovery failure

Force-stop and reopen correctly exposed an interrupted recording; Continue
transcription recovered its durable 2.5-second audio tail beyond the earlier
2-second metadata watermark. However, recovery invented text from emulator input
with host audio disabled. This occurred in Russian before the constant-input fix
and was reproduced in English on the later build.

The measured PCM varied from **−8 to 7** (PCM16), with normalized RMS approximately
**0.000124**. It was not constant silence, so the exact-zero/DC guard correctly
did not apply. The passing constant-input regressions do not close this manual
failure. A solution still needs paired quiet-speech and noise evidence; this report
does not claim that silence/noise hallucinations or whole-app acceptance are solved.
The optional [SOT research overlay](whisper-sot-research.md) in `9a27635` supports
separate host experiments only. The SOT speech-policy candidate remains unshipped. The later ownership-only
fix changes cleanup behavior while preserving the recorded submodule.

## Focused editor regression

The 2026-11-04 base-only search checkpoint passed six focused tests, including
the unchanged oversized-editor and Rename tests, in 26.689 seconds. Actual
200% font portrait/landscape typing and Back → Home → Focal were then checked
manually: the query/caret survived, the entire field remained above the IME, and
a dismissed keyboard stayed hidden. The exact build and earlier failed candidate
scope are recorded in the [quality report](quality-report.md). These focused
results do not replace the full runner result above.

After installing the verified app/test APKs on the dedicated AVD, isolate it with:

```sh
adb -s emulator-5554 shell am instrument -w -e class 'app.focal.InterfaceTest#oversizedEditorInputKeepsTheValidDraftThroughRotation' app.focal.test/androidx.test.runner.AndroidJUnitRunner
```

This focused command is for diagnosis; it does not satisfy the full-suite or
eight-model-test gate. If the regression recurs, debug this test and break in
`assertWarningVisible` after recreation, before its `finally` removes the
synthetic fixture. Check whether rejection state survives field callbacks before
assuming a viewport problem. Inspect the actual dialog, warning/field bounds,
scroll offset, IME insets and any overlapping dialog. Record API, window size,
density, font scale and animation settings. Keep captures and raw diagnostics
private. Preserve the warning-visibility, draft-retention and successful-save
assertions. After any fix, rerun this scenario and then the complete `qa-local.py`
suite with no skips.

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
