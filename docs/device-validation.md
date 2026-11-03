# Device validation

Phone checks are **pending** and will be performed with the owner present, after
the owner connects the test phone. Their absence does not block development or
the [local emulator QA](local-emulator-qa.md). Emulator results do not establish
phone recognition quality, microphone behavior, battery use or thermals. Stable
release evidence needs a midrange and an older ARM64 phone, including a 4 GB device.

## Connect the test phone on Linux

1. With the owner, enable Developer options and USB debugging, connect a data-capable
   USB cable, unlock the phone and accept this computer's RSA prompt. Check the
   intended device with `adb devices -l`; `unauthorized` requires the phone prompt,
   and Linux USB permission errors require the distribution's Android udev setup.
   Do not restart the shared ADB server, change other AVDs, or loosen all USB permissions.
   See the [official USB/ADB instructions](https://developer.android.com/tools/adb).
2. Copy the phone's exact serial below. Use `-s` on every device command, even with
   other emulators or phones attached. Confirm `arm64-v8a` appears in its ABI list.

   ```sh
   FOCAL_PHONE_SERIAL='SERIAL_FROM_ADB_DEVICES'
   adb -s "$FOCAL_PHONE_SERIAL" get-state
   adb -s "$FOCAL_PHONE_SERIAL" shell getprop sys.boot_completed
   adb -s "$FOCAL_PHONE_SERIAL" shell getprop ro.product.cpu.abilist
   adb -s "$FOCAL_PHONE_SERIAL" shell getprop ro.build.version.sdk
   ```

3. Use the owner's spare test phone with no valuable Focal notes. Instrumentation
   changes Focal data, preferences, permissions and the clipboard, and records microphone
   input; use a quiet room and synthetic or consented speech. `install -r` retains app
   data, but that does not make the test suite safe for a personal Focal library.
   If signing differs, stop and agree on a suitable test installation; do not uninstall,
   clear data or replace signing keys to make installation pass.
4. Create a private report directory. Record device model/SoC, RAM, Android/security
   version, microphone route, font/display/animation settings and OEM battery policy.
   Keep the serial and raw device diagnostics private.

   ```sh
   umask 077
   FOCAL_PHONE_REPORT="build/phone-qa/$(date -u +%Y%m%dT%H%M%SZ)"
   mkdir -p "$FOCAL_PHONE_REPORT"
   git rev-parse HEAD > "$FOCAL_PHONE_REPORT/commit.txt"
   git status --short > "$FOCAL_PHONE_REPORT/source-status.txt"
   sha256sum app/build/outputs/apk/debug/app-debug.apk \
     app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
     app/src/main/assets/ggml-base-q5_1.bin > "$FOCAL_PHONE_REPORT/artifacts.sha256"
   ```

## Install verified artifacts and run automation

Use the exact debug/test APK hashes from a successful local QA manifest and keep
the checkout unchanged during testing. Rebuild and repeat local QA if they differ.
`scripts/qa-local.py` deliberately rejects physical phones; do not bypass that
guard or describe a direct phone run as an emulator-runner result.

```sh
adb -s "$FOCAL_PHONE_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$FOCAL_PHONE_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$FOCAL_PHONE_SERIAL" shell am start -n app.focal/.MainActivity
```

Wait for model preparation to finish, then verify the installed file, not only
the APK asset. Its SHA-256 must equal `models/model.json` and the local QA manifest;
a mismatched imported model must be resolved explicitly without deleting notes.

```sh
adb -s "$FOCAL_PHONE_SERIAL" shell run-as app.focal sha256sum \
  files/ggml-base-q5_1.bin > "$FOCAL_PHONE_REPORT/installed-model.sha256"
```

Continue only after the hashes match. For the full suite, use value `1` for global
`window_animation_scale`, `transition_animation_scale` and `animator_duration_scale`.
Record their original values before changing them and restore them afterward,
including on failure; restore an original `null` by deleting that setting. See the
restoration commands in the [local QA guide](local-emulator-qa.md).

```sh
adb -s "$FOCAL_PHONE_SERIAL" shell am instrument -w -e class \
  'app.focal.InterfaceTest#oversizedEditorInputKeepsTheValidDraftThroughRotation' \
  app.focal.test/androidx.test.runner.AndroidJUnitRunner \
  > "$FOCAL_PHONE_REPORT/editor.txt"
adb -s "$FOCAL_PHONE_SERIAL" shell am instrument -r -w \
  app.focal.test/androidx.test.runner.AndroidJUnitRunner \
  > "$FOCAL_PHONE_REPORT/instrumentation.txt"
```

An ADB exit code alone is insufficient. Require all source-discovered tests,
including the eight real-model checks, with zero failures/skips and a completed
runner. The existing parser can validate the direct phone log:

```sh
python3 - "$FOCAL_PHONE_REPORT" <<'PY'
import importlib.util
from pathlib import Path
import sys
spec = importlib.util.spec_from_file_location("focal_qa", "scripts/qa-local.py")
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
report = Path(sys.argv[1])
outcomes, problems = qa.parse_instrumentation(
    (report / "instrumentation.txt").read_text(), qa.expected_instrumentation_tests())
qa.write_junit(report / "results.xml", outcomes, problems)
if problems:
    raise SystemExit("Phone QA failed: " + "; ".join(problems))
print(f"Phone instrumentation passed: {len(outcomes)} tests, no skips.")
PY
```

If a run hangs or is interrupted, stop instrumentation with
`adb -s "$FOCAL_PHONE_SERIAL" shell am force-stop app.focal.test` and the same command for
`app.focal`; retain the failed log and restore changed settings. Do not clear data
or reboot another connected device. An instrumentation pass is separate from the
manual and resource gates below.

## Manual acceptance, in priority order

| Priority and scenario | Required observation |
| --- | --- |
| 1 · First launch offline; deny then grant microphone/notification permissions | Record is usable without networking/model downloads. Denials explain the action; permission changes do not silently claim continued capture. |
| 1 · Record → pause/resume in app and notification → Finish → listen/edit → Copy/Share | Paused time is excluded; later audio is retained; saved edits and Unicode/paragraphs match the transferred text. All seven language choices persist for new sessions. |
| 1 · Open editor with IME, reject an oversized paste, rotate portrait/landscape | Previous draft and visible warning survive; Save remains reachable. Save & next keeps the sheet stable. Legacy large text remains selectable/paged; refused large transfers preserve clipboard/source. |
| 1 · TalkBack, largest supported font, light/dark/system and reduced motion | Traverse and activate recording, language, review, seek, editor, large-text selection and dialogs. Labels/order/focus are useful; no trapped or unreachable control. Disabled animations stay static. |
| 2 · Thirty minutes screen off; switch apps and swipe Focal's task away | Record duration, notification, pending work and saved audio continuity. Reopen and play the beginning/middle/end; report OEM restrictions explicitly. |
| 2 · Controlled incoming call/other microphone user, permission revoke, force-stop and reboot | No silent loss disguised as recording. Existing audio/edits survive; reopening offers recovery without reopening the microphone. Use only test participants/content. |
| 2 · Wired/Bluetooth disconnect during playback; rapid Record/retry/play/delete | Playback pauses without leaking audio to the speaker; position is retained. Incompatible operations report busy and preserve source audio. |
| 2 · Decoder/import failure or cancellation; unfinished recovery | Capture remains independent of inference. Retry seals durable audio and retains completed/manual text; failed model import preserves the working model. |
| 3 · Sixty–ninety minutes with 1–5 minute pauses; battery saver and natural thermal pressure | Capture controls stay responsive, silence does not become invented speech, memory does not grow with queue length, and pending transcription finishes or remains recoverable. |
| 3 · Low storage; remove audio/delete; search/filter/return | On a dedicated test setup, low storage stops capture visibly without harming existing notes. Confirmations match removal; audio removal retains text. Search/filter/scroll survive navigation and rotation. |

Do not fill a personal phone's storage or induce overheating. Fault-injection
results belong to the tested setup; lack of a reproducible condition remains an
open check, not a pass. Restore permissions, audio routes, animation/font/theme
and battery settings with the owner when the session ends.

## Seven-language accuracy and phone resources

For **English, Russian, German, French, Spanish, Italian and Portuguese**, obtain
2–5 minutes of consented dictation per language as an initial screen, including
quiet onset/endings, room noise, pauses, restarts, names, numbers and negation.
Add continuous speech crossing real gate boundaries; include the owner's Russian
dictation when consented. This small screen alone does not establish release accuracy.

Compare baseline/candidate on identical immutable PCM with explicit language and
separate speaker/session development and held-out sets. Freeze code, model hashes,
settings and acceptance thresholds before viewing test scores. Prior FLEURS test
clips are already consumed and cannot become fresh validation. Report paired
WER/CER per language, boundary omissions/repetition, silence insertions and critical
errors. Use the [experiment gates](stt-quality-experiments.md#acceptance-and-abstention)
and [host tooling](stt-benchmark-tools.md); acoustic replay is separate from live
phone capture and does not establish microphone quality or phone latency.

Alternate baseline/candidate order, repeat cold and warm runs, and match starting
charge, temperature, screen state, route and background load. Record sample count,
P50/P95 first-text and Finish-to-ready latency, model-load/queue/decode time where
observable, and wall time versus audio duration. Do not infer a component time
from a total; mark unavailable measurements explicitly.

During a 30-minute speech-plus-silence trial, sample app memory at start, after
model load, every minute, after Finish and after cooldown; repeat sessions to check
growth. Preserve timestamped outputs locally:

```sh
adb -s "$FOCAL_PHONE_SERIAL" shell dumpsys meminfo app.focal
adb -s "$FOCAL_PHONE_SERIAL" shell dumpsys battery
adb -s "$FOCAL_PHONE_SERIAL" shell dumpsys thermalservice
```

Report RSS, PSS and swap separately when available; the largest sampled value is
a **sampled peak**, not a continuous peak. Android's [memory tooling](https://developer.android.com/topic/performance/memory/guide/tools-overview)
explains these distinctions. Thermal status is pressure, not temperature; retain
sensor identity/units and mark unavailable OEM readings. Battery percentage and
USB-powered trials are not app-energy measurements. For energy, use supported
power traces/ODPM or a documented external-meter/charge-counter method, with an
idle baseline and controlled unplugged trials; do not simulate unplugging with
`dumpsys battery` to claim physical consumption.

Apply the separately frozen [phone resource/interaction gates](stt-quality-experiments.md#phone-cost-and-unobtrusive-behavior).
Any language regression, introduced critical error, lost audio/edit, unbounded
growth, crash or unreachable essential action blocks the corresponding claim.
Missing measurements remain open. Automatic correction stays disabled; testing a
debug build does not satisfy stable signing or verify a different release artifact.

Publish only reviewed aggregates and exact build/model identities in the
[quality report](quality-report.md). Keep audio, transcripts, serials, raw logs,
screenshots and local paths in ignored private output. Carry the shared
[download ledger](benchmarks/2026-11-03-stt/download-budget.json) forward for any
new model/corpus transfer; existing phone recordings need no download.
