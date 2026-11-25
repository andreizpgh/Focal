# Testing

Focal is checked at three levels: JVM tests that need no device, instrumented
tests on an emulator or device, and a manual phone checklist (partially checked
on a 6 GB ARM64 phone; long-run and multi-device checks remain open). Emulator
results do not establish phone speed, memory, heat, battery
life or background reliability.

## Automated checks

### JVM tests, lint and builds

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests
```

- **`core`** tests the segmenter (pause and hard cuts, breaks, recovery warm-up,
  the energy fallback), word building and window merge, seam repair, paragraphs,
  titles, doubtful words, alignment of edited text and the thread policy.
  `transcript-vectors.json` holds formatter, segmenter and VAD cases generated
  from a reference implementation; the Kotlin code must reproduce every expected
  field.
- **App unit tests** cover pure parts of the app: storage thresholds, the live
  state's generations, model install bookkeeping, transcript JSON, the edit
  session's undo log, motion springs, library formatting, the settings gesture
  rules and the bundled license list.
- **Script tests** cover the model fetcher (against synthetic lock files), the
  word-error scorer and the release script's checks (synthetic APKs, apksigner
  and aapt2 output, provenance).

These need the speech runtime AAR but not the model:
`python3 scripts/fetch-model.py --runtime-only` is enough.

### Continuous integration

`.github/workflows/android.yml` runs on every pull request and push to `main`,
without the 670 MB model: the script tests, the runtime fetch, the core and app
unit tests, lint, the debug and device-test APKs and R8 shrinking of the release
code. `.github/workflows/secrets.yml` scans the full history with Gitleaks.
Instrumented tests do not run in CI yet.

### Instrumented tests

```sh
python3 scripts/fetch-model.py                    # the model, for the speech tests
./gradlew :app:connectedDebugAndroidTest -Pfocal.requireModel=true
```

Tests that need the model skip themselves when the APK has none;
`-Pfocal.requireModel=true` makes a missing model a build error instead. Run the
suite on an emulator or a test device: some tests use the app's own data
directory, so a phone with real notes is the wrong place.

| Area | Tests |
| --- | --- |
| Storage | `NoteStoreTest` (schema 6 and its upgrade from 5, edits, search index, trash, crash-loop guard, reopening a ready note for more recording, previews), `LegacyDatabaseUpgradeTest` (migration from schemas 1–4), `NoteArchiveTest` (Markdown export and backup/restore round-trip) |
| Recording and transcription | `RecordingServiceTest` (capture durability, pauses, the hold while another app has the microphone and its automatic return, continuing a ready note), `WindowFeedTest`, `SpeechWorkflowTest`, `UltraServiceTest`, `NotificationsTest` |
| Speech | `OfflineRecognizerTest` and `UltraTranscriberTest` (decode the public JFK fixture), `SileroVadTest`, `UltraModelTest` (install, storage checks, damaged and interrupted copies) |
| Playback | `SpeechPlayerTest` |
| Interface | `InterfaceTest` (record to copy, end to end), `ui/CopyFeedbackTest`, `ui/NoteEditTest`, `ui/UiMotionTest`, `gallery/SceneGestureTest` |
| Performance | `gallery/LongNoteTest`: frame times while scrolling and editing a 500-paragraph note |

The speech fixture is an 11-second excerpt of John F. Kennedy's 1961 inaugural
address (public domain). Emulator microphones deliver near-silence, so live
transcription of real speech is only exercised through this fixture.

### Screenshot gallery

`gallery/GalleryTest` renders every screen and state from fake data, in light and
dark themes, at the design's 412 × 915 dp frame, and writes PNGs to the app's
external files directory. It needs a display large enough for that frame:

```sh
adb shell wm size 824x1830 && adb shell wm density 320
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.focal.gallery.GalleryTest
adb pull /sdcard/Android/data/app.focal/files/gallery build/gallery
adb shell wm size reset && adb shell wm density reset
```

Each test method renders one group (`foundation`, `library`, `scene`, `note`,
`finish`, `coverage`, `transitions`, `film`). Pass
`-Pandroid.testInstrumentationRunnerArguments.shots=<prefixes>` to re-render a few
shots. Some groups repeat shots at 1.3× and 2.0× font scale. `film` renders the
row's opening and fold and both Finish transitions every 16 ms in both themes,
for frame-by-frame review of motion; its frames come from the test's clock, not
a device's timing.

### Release checks

`scripts/release.py` builds, signs and checks a testing release (see
[Releases](../CONTRIBUTING.md#releases)). Beyond the JVM tests and lint above it
verifies the signed APK itself: apksigner with one signer and the expected
certificate, `zipalign -c -P 16 4`, the package and version in aapt2's badging,
`lib/` holding arm64-v8a only with the speech libraries stored, and every file
of `models/parakeet-ultra.json` in `assets/` byte for byte, the ONNX files stored.
Its provenance copies the unticked items under [Phone validation](#phone-validation)
into `not_established`, so tick an item there only once it has been done on a
phone.

The script runs no device tests, and no emulator can run the published APK end
to end, so before publishing a release is checked on emulators in three parts:

1. **The instrumented suite** on the release commit. `connectedDebugAndroidTest`
   tests that commit's debug build, not the release APK.
2. **An emulator QA build** of the same commit. `-Pfocal.emulatorRelease=true`
   (command line only) adds the x86_64 libraries and an `-emulator-qa` version
   suffix to the release build, so its minified code, resources and manifest can
   run end to end on an x86_64 emulator. Such a build is never published.
3. **The signed APK** installed over the previous release. It is arm64 only: an
   x86_64 emulator installs and starts it through its arm64 translation, but it
   cannot record there, because ONNX Runtime crashes under the translation.

### Emulator notes

- Debug builds include x86_64 libraries for emulators; release builds are ARM64
  only.
- Live Update notifications need Android 16 QPR2 (API 36.1); an API 36.0 image
  cannot show them.
- The speech tests peak at about 0.9 GB PSS on an emulator, so give the emulator
  at least 3 GB of RAM.
- Start the emulator with `-no-audio` for the instrumented suite. Its microphone
  then delivers faint noise, which a recording keeps. Started with host audio
  and nothing streamed into it, the microphone delivers exact silence, and Focal
  discards such a recording as "Nothing was recorded".

## What has been verified

### 0.5.7 (pre-release physical phone check)

- **JVM, lint, and emulator checks:** `:core:test` (69 tests), `:app:testDebugUnitTest`
  (251 tests), `:app:compileDebugAndroidTestKotlin`, `:app:lintDebug` (0 errors),
  `:app:assembleDebug`, and `python3 -m unittest discover -s scripts/tests` (58 tests)
  passed; targeted instrumented tests on an API 36 (Android 16) x86_64 emulator
  (`NoteStoreTest`, `RecordingServiceTest`, `InterfaceTest`, `UiMotionTest`,
  `WindowFoldsTest` — 84 tests) passed.
- **Physical phone — POCO M4 Pro (`2201117PG`, Android 13 / API 33, HyperOS OS1.0,
  1080 × 2400 at 440 dpi, 90 Hz, 5.86 GB RAM / ONNX CPU arena off):** signed
  debug-keystore ARM64 release APK (`0.5.7`, `versionCode 22`, 700,660,557 bytes,
  16 KB aligned, `minifyReleaseWithR8`, `verifyReleaseAbis`).
  - **Launch & security:** cold `MainActivity` start (`am start -W`, 3 runs)
    451 / 356 / 355 ms `TotalTime` (458 / 361 / 360 ms `WaitTime`); warm
    `SettingsActivity` (`APPLICATION_PREFERENCES`) 161 ms in a separate
    `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` task; hot return to `MainActivity` 57 ms;
    external launch of non-exported `ShortcutActivity` from `adb shell` (UID 2000)
    rejected with `SecurityException: Permission Denial`.
  - **Memory & foreground service (~5 min live RU/EN dictation + widget pass,
    5 s polling):** idle `TOTAL PSS` 57.4 MB (`Native Heap` 18.4 MB) → peak
    1,179 MB PSS / 1,285 MB RSS (`Native Heap` 929 MB, `Graphics` 188 MB) while
    recording and decoding with Parakeet Ultra INT8 in 6 GB lean mode (steady
    decoding plateau 986–1,077 MB PSS) → after `Finish`, `Native Heap` returned
    to 48–55 MB (settled `TOTAL PSS` 229 MB, including ~95 MB purgeable Skia
    scratch RenderTarget). `RecordingService` ran `isForeground=true` throughout
    capture/transcription and exited cleanly with zero lingering services.
  - **Frame timing & heat:** `dumpsys gfxinfo app.focal` across 9,056 frames
    (post-reset, 90 Hz / 11.1 ms budget) reported 50th / 90th / 95th / 99th
    percentiles of 27 / 36 / 40 / 57 ms (GPU 12 / 19 / 20 / 22 ms; 72.5% of frames
    missed the 11.1 ms 90 Hz deadline during continuous recording-scene animation +
    CPU decoding, while `BufferQueueProducer` ran ~73–87 fps). On AC power (39%),
    CPU rose from 34.1 °C to 72.4 °C peak (skin 33.0 → 41.8 °C, battery
    31.4 → 33.5 °C) with `Thermal Status: 0` throughout. Zero crashes, ANRs, OOMs,
    or `Focal`/`FocalService`/`EngineGuard`/`SpeechModels` errors in `logcat`.
  - **Manual confirmation on phone:** Russian dictation with English technical
    terms and paragraph pause, English switch, short "Да" reply, landscape
    `Pause → Resume` 56 dp circle, flicker-free `Finish`, scrubber playback,
    reverse `Delete → Undo` animation, and silent home-widget recording discarded
    without saving an empty note.
  - **Still pending on phone:** 60-minute screen-off unplugged recording, battery
    drain, call/other-app mic interruption, Android 14–16 FGS limits / predictive
    back / Live Updates, TalkBack/Switch Access, second phone (> 6 GB RAM with ONNX
    arena on), and quantitative RU/EN WER scoring.

### 0.5.1

Checked on host JVM and an API 36 (Android 16) x86_64 emulator (`fintrail36`) with
the verified speech model bundled.

- **JVM, lint, and builds:** `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`
  (0 errors), `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` all passed.
- **Instrumented suite (`-e focal.requireModel true`):** 111 of 111 phone-sized tests
  passed on Android 16 (`NoteStoreTest`, `RecordingServiceTest`, `UltraServiceTest`,
  `EngineGuardServiceTest`, `LegacyDatabaseUpgradeTest`, `NoteEditTest`,
  `NoteEditInputTest`, `NoteEditSessionTest`, `InterfaceTest`, and `SpeechWorkflowTest`,
  covering real Parakeet Ultra INT8 decoding of the public JFK fixture). Nine wide-display
  `InterfaceTest` cases skipped on the phone emulator.
- **Visual and frame review:** Re-rendered `GalleryTest` (`library`, `note`, `transitions`)
  and inspected `copied-light`, `select-light`, `edit-light`, `playing-light`,
  `library-light` (`idle-light`), and `t16-row-back-120-light` to verify centered `Copied`,
  the full-width `Record` dock, the bottom `Listen` / `Done` strip, and removal of the Back
  transition surface artifact.
- **Release build:** Signed ARM64 APK verified with `scripts/release.py` (v2/v3 signing
  certificate `3e407a18…a3bc`, 16 KB zipalign, aapt2 badging, `arm64-v8a` only, and all
  five locked model files byte for byte) plus full-history Gitleaks scan (0 findings).
  Formal ARM phone profiling remains open below.

### 0.5.0

Checked on host JVM and an API 36 (Android 16) x86_64 emulator (`fintrail36`) with
the verified speech model bundled. No physical ARM phone run yet.

- **JVM, lint, and scripts:** 63 core unit tests, 214 app unit tests, `:app:lintDebug`
  (0 errors), and 58 Python script tests all passed.
- **Instrumented suite (`-Pfocal.requireModel=true`):** 158 of 158 phone-sized tests
  passed at 824 × 1830 (320 dpi), including inline review, `Keep` confirmation and
  `Review · N` dismissal, `Mark all as reviewed`, undoable confirmation, `NoteStoreTest`
  persistence, and real Parakeet Ultra INT8 decoding of the public JFK fixture. Ten
  additional wide-display tests (all nine two-pane `InterfaceTest` interactions and
  `GalleryTest#tablet`) passed at 1600 × 2560 (320 dpi). Landscape and wide gallery
  stages were not run separately.
- **Visual and frame review:** Inspected rendered 412 × 915 dp gallery shots for
  library light/dark, live recording, transcribing, dark playback (with 1× speed pill),
  in-place review light/dark, and mid-flight open/back transition frames for Stage and
  Row notes. The four screenshots in `docs/screenshots/` come from this 0.5.0 build.
- **Release build:** Signed ARM64 APK verified with `scripts/release.py` (v2/v3
  signing certificate `3e407a18…a3bc`, 16 KB zipalign, aapt2 badging, `arm64-v8a`
  only, all five locked model files byte for byte). Physical ARM phone validation
  remains open below.

### 0.4.0

**Wrapped title and inline review, 2026-11-19.** On a 320 × 640 Android 35
emulator a truncated two-line card opened into a three-line heading. Consecutive
screen-recorded frames showed the card text fading and the full heading appearing
with its final line breaks. The trailing words stayed on their lines through
the rest of the opening. A separate warm open/back run on the debug build
rendered 130 frames. Android marked three as janky and reported 21 ms median
and 30 ms p95 frame duration. Physical phone frame pacing is still unmeasured.
The instrumentation tests also exercised correction, phrase splitting, a
Greek question, joining paragraphs, undo and the store's stale-write guards.

The signed ARM64 APK was built from `c51fcd1`. The 63 core and 212 app JVM
tests passed, as did lint and all 93 Python script tests with the research
dependencies installed. The release checks verified the signing certificate,
package, alignment, native libraries and bundled model. The three assets
downloaded from GitHub matched the local APK, provenance and checksums byte
for byte.

All 170 instrumented tests passed on an Android 16 emulator with the model
bundled. At 824 × 1830 and 320 dpi, 158 ran and 12 required a larger display.
Those 12 passed on a separate 1600 × 2560 run with rotation, including nine
two-pane interactions and the portrait tablet, landscape and wide galleries.
The minified x86-64 QA build recorded, paused, resumed and recognized the
public JFK fixture through the virtual microphone. The test then tapped Finish,
found the expected words in the transcript and tapped Copy.

The published 0.3.2 APK opened a synthetic two-paragraph note that contained
a custom title and a manual correction. Installing the signed 0.4.0 APK over
it preserved all three. Review offered the earlier spelling at its next
occurrence. Applying and undoing the suggestion restored the second paragraph
and kept the first correction. Physical ARM phone measurements remain open.

### 0.3.2

On the API 36 x86_64 emulator only. No build has run on a phone.

**Tests.** At 069decc, the commit the 0.3.2 APK was built from: the JVM tests
passed (core 58, app 210), lint reported no errors and the script tests passed
(58). The instrumented suite (debug build, model bundled, an emulator started
with `-no-audio`) passed on the emulator's own display: 164 tests, none failed.
While the changes were made it also passed at 1080 × 2400, where 12 tests skip
themselves because they need another display (one pane upright and two panes
turned, a tablet, landscape). The signed APK installed over the published 0.3.1
(with no notes in it) and started, with nothing in the crash log; the emulator
QA build started, recorded and went back to the library.

**Review.** Before release the change was reviewed by focused agents, one per
part (page changes, the pull, the library kept under pages, the note page,
settings and the scene) and per aspect (motion across the app, Compose costs,
the docs against the code, tests), each finding checked by another agent
against the code, plus two passes by a second model on the page state machine
and on per-frame cost and lifetime. What they confirmed is fixed and listed in
the changelog; an emulator run of the old build showed the worst of them (with
animations off in the system the library took no taps; two quick taps opened
two notes; Back just after a tap blanked the library).

**Frames.** Measured on emulator QA builds (minified, x86_64, never published) at
1080 × 2400, with `dumpsys gfxinfo` frame stats, a Perfetto trace and 60 fps
screen recordings compared frame by frame:

- *Back, then a tap on another note* (the owner's screenshot): on the 0.3.1 build
  the note that had just been closed was drawn over the library at full
  strength for several frames as the next one began to open. On the new build
  no frame shows it, and a tap 50 or 150 ms after Back is not lost: the fold
  ends within about 8 to 17 frames and the tapped note opens from there.
- *A tap, then Back 50 or 150 ms later:* the card folds back with its title and
  the library's rows return as it folds; the near-white frames of the first
  fix are gone.
- *A card let go short, then a tap on a row* 0 to 250 ms later: the tapped note
  opened in every run, as soon as the card had landed (its return quickens);
  a tap on the card itself opens it from the card.
- *Two rows tapped in quick succession:* only the first note opens.
- *The same note opened again* 0.6 s after Back opens at once, its dock working.
- *Back:* the frames after Back took 11.6 and 32.3 ms on the main thread on the
  0.3.1 build (the library composed again); on the new one at most 10.8 ms, with
  a median of 2 ms over the first 30 frames.
- *Opening a note,* warm: a median of 2 ms a frame; the first frame, which
  composes the page, 13 to 31 ms.
- *Pulling the card:* its edge follows the finger step by step; let go short it
  goes back into the card and the dock never changes; let go past 40 % or flung,
  it opens and the dock swaps after the release. A median of 2.7 ms a frame,
  at most 18.4 ms.
- *Animations off in the system:* rows open, settings open and close, and no
  tap reaches a page that is not shown.
- *Scrolling* the library with a pinned day label: at most 4.3 ms a frame on the
  main thread.
- *A 45 s run of random taps, Backs, pulls and double taps:* no crash.

**Not checked:** any of it on a phone, and so real frame rates and how long a
note's first frame takes there; the predictive back gesture (the emulator's
injected edge swipes did not start one); landscape, a tablet and two panes; a
fold or unfold during a page change (the open page's change then ends without
its transform); TalkBack over a covered library. Known and left as it was:
Finish tapped while the recording scene is still opening or folding starts its
landing from the full scene, so the scene snaps open for a frame first; opening
a search result, the keyboard and the search field leave over the first frames
of the note's page.

### 0.3.1

On the API 36 x86_64 emulator only. No build has run on a phone.

**Tests.** At 5119dca, the commit the 0.3.1 APK was built from: the JVM tests
passed (core 58, app 205), lint reported no errors and the script tests passed
(58). The instrumented suite (debug build, model bundled) ran after `pm clear`
on an 824 × 1830 display, on an emulator started with `-no-audio`: 164 tests,
none failed. Tests that need a wide display skip themselves there, and the
landscape, tablet and two-pane gallery stages were not rendered for this
release. While the changes were made the suite also ran on a 720 × 1280 display,
where `gallery/LongNoteTest` failed for two reasons of its own, both fixed in
it: its tap went to the list's centre, which there is the gap between two
paragraphs, and it waited for the autosave by sleeping while the rule's clock,
which the autosave's debounce runs on, stood still.

**Emulator QA build** of the same commit (minified, x86_64, 0.3.1-emulator-qa,
never published), through the emulator's own microphone, which delivers faint
noise: Record opened the scene and the timer ran, Finish opened the note, which
became ready with "No speech was recognized", the library showed it on the
stage, and settings opened with the new rows. Logcat's crash buffer was empty.
No speech was streamed into this build, so text arriving while recording was
not seen in it; the speech tests of the suite decode the JFK fixture in the
debug build.

**The 0.3.1 APK** (`Focal-0.3.1.apk`, 700,512,510 bytes, SHA-256
`15f9bf056b29aaeb0b8b3ac81dbd2629fa38c369d7c249f94751f86ca91ea255`).
`scripts/release.py` signed it with the certificate of the earlier releases
(SHA-256 `3e407a18…a3bc`) and checked it as described under
[Release checks](#release-checks). The published 0.3.0 APK was installed on the
emulator and started; 0.3.1 installed over it as an update and started. 0.3.0
cannot record under the emulator's arm64 translation, so that install carried no
notes. The database schema is the one 0.3.0 has. The one-time split of earlier
notes ran in the debug build over notes seeded through the store under the old
rules: the later paragraphs of a two-minute monologue came out split, each
under its start time, and its first paragraph, edited beforehand, was left
whole. How many paragraphs the note had before and after was not counted.

**Motion, frame by frame.** The screen was recorded (`screenrecord`, 60 frames a
second) while a note was opened from the stage and from a row and closed again,
and consecutive frames were compared. On the 0.3.0 debug build an opening showed
three to six distinct frames with pauses of about 200 ms between them, one of
them a note that had not loaded ("Jan 1, 1970 · Text only"), and Back drew the
page over the library. On an emulator QA build of this code the same four
changes show 19 to 23 consecutive frames each, with no frame of an unloaded note
and no page drawn over another. The same recordings were made in the debug
build for search (the first letter typed: one crossfade over about ten frames,
no blank page), the note menu (its shadow is there from its first frame), Edit
(a tap on a word, typing over it, a tap on another word: no line of text changes
place, the page scrolls once, for the keyboard, and the typed text was still
there after the process was killed) and pulling the stage open.

**Not checked:** any of it on a phone, and so real frame rates; other keyboards
than Gboard; the pull gesture against Android's own back gesture at the screen's
edge; landscape, a tablet and two panes; TalkBack with the new switch, the
selection menu and the paragraph times; the one-time split on a phone's real
notes; speech in Russian through the microphone.

### 0.3.0

On the API 36 x86_64 emulator only. No build has run on a phone.

**Tests.** At 33ca95d, the commit the 0.3.0 APK was built from: the JVM tests
passed (core 56, app 200), lint reported no errors and the script tests passed
(58). The instrumented suite (debug build, model bundled) ran after `pm clear`
on an 824 × 1830 display: 164 tests, none failed. Tests that need a wide display
skip themselves there, and the landscape, tablet and two-pane gallery stages
were not rendered for this release. One test records through the emulator's own
microphone; started with host audio for the speech check below, that microphone
delivers exact silence, which Focal discards as "Nothing was recorded", so the
suite is run on an emulator started with `-no-audio`.

**Emulator QA build** of the same commit (minified, x86_64, 0.3.0-emulator-qa,
never published), with the public JFK fixture streamed into the virtual
microphone: text appeared while recording, Finish opened the note and it became
ready; ● on the note continued the recording with the same speech, the timer
went on from the note's length and the new paragraph followed the old one; a tap
on a word and four typed letters replaced it. Logcat showed no crash, ANR, link
or JNI error. The emulator itself crashed twice in its audio code when a stream
was started before Record; a stream started after Record worked.

**The 0.3.0 APK** (`Focal-0.3.0.apk`, 700,492,030 bytes, SHA-256
`c8132226a5fba74b55fe0f3b19e303705d82df87e790965c4982c542cabf26e9`).
`scripts/release.py` signed it with the certificate of the earlier releases
(SHA-256 `3e407a18…a3bc`) and checked it as described under
[Release checks](#release-checks). The published 0.2.0 APK was installed on the
emulator and started; 0.3.0 installed over it as an update and started. 0.2.0
cannot record under the emulator's arm64 translation, so that install carried no
notes: the conversion of a 0.2.0 database (schema 5 to 6) is covered by
`NoteStoreTest`.

**By hand in the debug build**, with notes seeded through the store: with search
open and the keyboard hidden, a tap on the field brought the keyboard back and
started nothing; a tap past a line's last word placed the caret, a tap on a word
selected it; **Edit** in the menu put the caret in the first paragraph; several
notes were selected and deleted with one Undo; a note deleted from its page and
restored with Undo six seconds later opened again; Finish in the scene over a
note settled into the note's dock pill.

**Not checked:** any of it on a phone; another app actually taking the
microphone (the hold is tested with a scripted input); a microphone that
refuses to start (tested with a scripted input); frame pacing of any animation;
speech in Russian through the microphone.

**Known and left for later:**

- Back from a note that went on recording folds with a fade-through instead of
  the fold into the stage card, because the note has moved from its row to the
  stage since the page opened.
- In two panes, where the scene crossfades over a note, the dock changes pane
  half-way through the crossfade.
- A first launch without room for the speech model plans a recording's windows
  with the energy detector, and they are kept when the model arrives.
- A note's view model lives as long as the activity, so every note opened keeps
  its text in memory until then.
- `core` still holds Whisper-era helpers (`Transcript.title`,
  `reconcileBoundary`) and `SpeechPlayer` a few methods that only tests call.

### 0.2.0

On x86_64 emulators only. No build has run on a phone.

**Instrumented suite (debug build).** At 523bdd2, the commit the 0.2.0 APK was
built from, the full suite ran after `pm clear` with the model bundled, on API 36
and on API 33: 156 tests each, 144 passed, 12 skipped, none failed. Nine of the
skipped tests need a wide display and three are adaptive gallery stages; at
1600 × 2560 the interface tests passed 28 of 28 on both emulators. The gallery
(341 shots, both themes, 1.3× and 2× font, RTL, landscape, tablet and a
simulated hinge) was reviewed against the design specification.

**Minified code (emulator QA build).** The x86_64 QA build of the same commit
(`-Pfocal.emulatorRelease=true`, version suffix `-emulator-qa`, never published)
ran end to end on API 36, with the public JFK fixture streamed into the
emulator's microphone through its gRPC audio injection, so live decoding ran in
minified code. Model install, live text, Pause and Resume, Finish, playback,
Edit with split, join and undo, Find and Replace all, Copy, Delete with Undo and
rotation worked; a force-stop during transcription recovered to Ready, and two
kills inside a speech stage stopped automatic transcription until Try again.

**The 0.2.0 APK** (`Focal-0.2.0.apk`, 700,492,030 bytes, SHA-256
`8ee0a0b5a2eadc36a90fbf5eb92a353ee48db7268ab51570413c340d924b7226`, from a clean
tree at 523bdd2). Two release builds of that commit produced the same unsigned
APK. `scripts/release.py` signed it (v2 and v3, one signer, the Android debug key,
certificate SHA-256 `3e407a18…a3bc`) and checked it: `app.focal` 0.2.0
(versionCode 10), 16 KB alignment, arm64-v8a only in `lib/`, every model file
byte for byte with the four ONNX files stored. On API 36, through the arm64
translation, it installed over the exact Alpha 9 APK's data, kept the notes, a
custom title, an edited passage and the audio files (byte for byte), launched to
the library and opened a note. It cannot record there: ONNX Runtime crashes inside
the emulator's arm64 translation, which is why the QA build above exists.

**Behavior.** Recording, Pause, Resume, Finish, process death during capture and
transcription and the recovery that follows behaved as designed with emulator
audio. Speech quality figures come from host experiments on read speech; see
[speech quality](speech-quality.md).

## Phone validation

Pending. Use the exact release APK, on at least one phone with 6 GB of RAM or
less and one recent Pixel. Note the device, Android version, battery level and
temperature before each run.

**Long recording and background**

- [ ] 60 minutes of recording with the screen off and the charger unplugged, on
      both phones. Check for gaps in the saved audio (playback around pauses and
      at the end) and whether the recording and transcription notifications
      stayed up.
- [ ] How far transcription lags behind speech during that hour, and the time from
      Finish to "Note ready".
- [ ] Memory: process PSS and RSS during decoding (`adb shell dumpsys meminfo
      app.focal`), with the ONNX Runtime arena on (above 6 GB) and off.
- [ ] Heat and battery: thermal status during the hour (`adb shell dumpsys
      thermalservice`) and the battery used, compared with an hour of screen-off
      idle.
- [ ] Two against four recognizer threads on the same audio: speed, heat and
      battery.
- [ ] A hot phone: whether transcription stops at thermal status severe, how
      long it stays stopped, and that it resumes without losing audio.
- [ ] First launch: how long the 670 MB model copy takes, and that a recording
      started during it is transcribed afterwards.
- [ ] Finish with the screen off on Android 14, 15 and 16 (the service changes
      its foreground type), and Android 15's processing time limit: the
      "Transcription paused" notification and the automatic continuation when
      Focal is opened again.
- [ ] A phone call and another app taking the microphone during a recording
      (a voice message in a messenger, voice typing, a video call): the note
      shows as paused, nothing is written meanwhile, and recording goes on by
      itself afterwards without losing its first words. Low storage pauses and
      waits for Resume.
- [ ] Continue recording into a note: with real speech, after edits, after a
      restart, and twice in a row. The new text follows the old; playback and
      Edit's ▶ land on the right words on both sides of the join.
- [ ] Force-stop during a recording, then reopen: the note shows where it stopped
      and its audio is transcribed.

**Speech**

- [ ] Natural Russian and English dictation, including Russian with English
      technical terms, scored against a careful manual transcript.
- [ ] Short replies ("Да", "No", "Oui") alone and between longer sentences: is
      the language right?
- [ ] Quiet speech, a distant phone and a noisy room.
- [ ] Paragraph breaks on real dictation: too many, too few? (Thresholds 2.0 s
      and 3.5 s are provisional.)
- [ ] Doubtful-word underlines: what share mark real errors?

**Interface**

- [ ] Edit with Gboard and one other keyboard: a tap on a word selects it and
      typing replaces it, a long press sweeps a phrase, Enter splits, Backspace
      at the start of a paragraph joins it, the caret stays above the keyboard,
      the text does not move while typing, and the find and search rows ride
      the keyboard on Android 8–10.
- [ ] Opening and closing search, selecting notes and the settings hint: nothing
      arrives from two directions at once, and nothing moves by itself.
- [ ] Predictive back on Android 14 and later: pages, the recording scene,
      settings and the player strip.
- [ ] The Live Update recording notification on Android 16 QPR2.
- [ ] The "New recording" shortcut on the phone's launcher.
- [ ] Quick Settings tile (`RecordTileService`): tap while unlocked and from the
      lock screen (`unlockAndRun`), tap while already recording to open the live
      scene, confirm a stale "Recording" tile resets after force-stop or process
      death, and check whether the tile label reads clearly beside MIUI/HyperOS's
      "Screen recorder" tile (since HyperOS omits `subtitle`).
- [ ] Home-screen widget (`RecordWidgetProvider`) in 1×1 and 2×1 sizes on the
      phone's launcher (including MIUI/HyperOS on Poco M4 Pro): centered 56 dp
      circle/pill sizing, whether HyperOS supports horizontal resize (or needs a
      separate 2×1 provider), launch animation vs icon launch (`taskAffinity`),
      and zero blank Recents entry (`ShortcutActivity`).
- [ ] Dock and page morphs at 90 and 120 Hz (check with developer refresh
      overlay): stage and row taps waiting for a note to load, pulled stage
      release and cancel, rapid Back mid-morph, both Finish transitions (`T7`
      and `T7b`), and large font scale (`≥ 1.5×`) height stability without
      snaps or ghost faces.
- [ ] Audio playback and scrubber: `SeekSnap` word-boundary landing, silent park
      within the last 300 ms of unaligned audio, clickless 12 ms fade-in on
      seek, and rotation while parked; plus swipe-to-dismiss bottom sheets,
      Settings data task busy labels, and the empty-library Settings hint.
- [ ] TalkBack and Switch Access walk-through of record → read → edit → copy.
- [ ] Haptics: four distinct ticks and no others; their feel on older phones.
- [ ] Scroll and animation smoothness at 90 and 120 Hz, and grain or banding of
      the recording material on OLED screens.
- [ ] Installing over Alpha 9 with real notes keeps them.
