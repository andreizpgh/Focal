# Testing

Focal is checked at three levels: JVM tests that need no device, instrumented
tests on an emulator or device, and a manual phone checklist that has not been
done yet. Emulator results do not establish phone speed, memory, heat, battery
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
| Storage | `NoteStoreTest` (schema 6 and its upgrade from 5, edits, search index, trash, crash-loop guard, reopening a ready note for more recording, previews), `LegacyDatabaseUpgradeTest` (migration from schemas 1–4) |
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
`finish`, `coverage`, `transitions`). Pass
`-Pandroid.testInstrumentationRunnerArguments.shots=<prefixes>` to re-render a few
shots. Some groups repeat shots at 1.3× and 2.0× font scale.

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

### 0.3.1

On the API 36 x86_64 emulator only. No build has run on a phone. What follows
was checked while the changes were made, on the working tree; the checks of the
release commit itself are recorded after it.

**Tests.** The JVM tests and lint passed. The instrumented suite (debug build,
model bundled, a 720 × 1280 display, `-no-audio`): 164 tests; one failed,
`gallery/LongNoteTest`, for two reasons that were the test's own on this display
and are fixed in it: its tap went to the list's centre, which here is the gap
between two paragraphs, and it waited for the autosave by sleeping while the
rule's clock, which the autosave's debounce runs on, stood still. It passes with
both changed; the edit, motion and interface tests were run again after the last
changes to the page transitions (41 tests, none failed), not the whole suite.

**Motion, frame by frame.** The screen was recorded (`screenrecord`, 60 frames a
second) while a note was opened from the stage and from a row and closed again,
and consecutive frames were compared. On the 0.3.0 debug build an opening showed
three to six distinct frames with pauses of about 200 ms between them, one of
them a note that had not loaded ("Jan 1, 1970 · Text only"), and Back drew the
page over the library. On the emulator QA build of this tree (minified, x86_64)
the same four changes show 19 to 23 consecutive frames each, with no frame of an
unloaded note and no page drawn over another. The same recordings were made for
search (the first letter typed: one crossfade over about ten frames, no blank
page), the note menu (its shadow is there from its first frame), Edit (a tap on
a word, typing over it, a tap on another word: no line of text changes place,
and the page scrolls once, for the keyboard) and pulling the stage open.

**Not checked.** A phone, and so real frame rates; other keyboards than Gboard;
the pull gesture against Android's own back gesture at the screen's edge;
landscape, a tablet and two panes; TalkBack with the new switch, the selection
menu and the paragraph times; the one-time split of notes recorded before, on
real notes (it was exercised on the emulator's seeded notes only).

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
- [ ] TalkBack and Switch Access walk-through of record → read → edit → copy.
- [ ] Haptics: four distinct ticks and no others; their feel on older phones.
- [ ] Scroll and animation smoothness at 90 and 120 Hz, and grain or banding of
      the recording material on OLED screens.
- [ ] Installing over Alpha 9 with real notes keeps them.
