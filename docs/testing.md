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
| Storage | `NoteStoreTest` (schema 5, edits, search index, trash, crash-loop guard), `LegacyDatabaseUpgradeTest` (migration from schemas 1–4) |
| Recording and transcription | `RecordingServiceTest`, `WindowFeedTest`, `SpeechWorkflowTest`, `UltraServiceTest`, `NotificationsTest` |
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

## What has been verified

For 0.2.0, on x86_64 emulators only. No build has run on a phone.

**Instrumented suite (debug build).** At 8999953, the commit the 0.2.0 APK was
built from, the full suite passed on an API 36 emulator after `pm clear`, with
the model bundled: 132 tests, 125 passed, 7 skipped, none failed. The skipped
tests need a tablet-size display (four) or render adaptive gallery stages
(three). On an API 33 emulator the full suite last ran at 00130f9, an earlier
commit: 126 tests, 120 passed, 6 skipped, none failed. Later commits have run
targeted suites only.

**Minified code (emulator QA build).** The QA build of e13c2f1 ran end to end on
API 36, with the public JFK fixture streamed into the emulator's microphone
through its gRPC audio injection, so live decoding ran in the minified build.
Model install, permissions, live text, Pause and Resume, Finish, playback, Edit,
Find and Replace all, Copy, Delete with Undo and rotation worked; so did recovery
after a force-stop during capture or transcription, and the engine guard after
two simulated crashes. Its `classes.dex`, `resources.arsc` and manifest were
byte-identical to the arm64 release of e13c2f1. The 0.2.0 APK has the same
resources and manifest, but its code adds six later fixes, so its own minified
code has not run end to end.

**The 0.2.0 APK** (SHA-256
`8ebf7344a84d5c80292a7e2f7b1dca2b906060a69e867f2ee8eee23db3d6c613`, from a clean
tree at 8999953). apksigner verifies its v2 and v3 signatures: one signer, the
Android debug key (certificate SHA-256 `3e407a18…a3bc`). It was signed by hand
with apksigner's defaults, so it also carries a JAR signature, which Android 7
and later ignore. aapt2 reads `app.focal` 0.2.0 (versionCode 10), `zipalign -c
-P 16 4` passes, `lib/` holds arm64-v8a only and the four ONNX files are stored;
the release script's checks pass on it. On API 36, through the arm64
translation, it installs, cold-launches to the library and stays up for five
minutes with no fatal log entry. It cannot record there.

**Upgrades.** Installing over the exact Alpha 9 APK kept every note, paragraph,
edit, custom title and audio file (byte for byte): on API 35 and 36 with earlier
builds of the rebuild, where a recording interrupted mid-capture was also
transcribed after the upgrade, and on API 36 with the arm64 release of 00130f9
through the translation.

**Behavior.** Recording, Pause, Resume, Finish, process death during capture and
the recovery that follows behaved as designed with emulator audio. The
screenshot gallery was compared by eye with the design renders, in both themes
and at large font scales.

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
- [ ] Automatic pause for a phone call, another app taking the microphone and
      low storage, then Resume.
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

- [ ] Edit with Gboard and one other keyboard: Enter splits, Backspace at the
      start of a paragraph joins it, the caret stays above the keyboard, and the
      find and search rows ride the keyboard on Android 8–10.
- [ ] Predictive back on Android 14 and later: pages, the recording scene,
      settings and the player strip.
- [ ] The Live Update recording notification on Android 16 QPR2.
- [ ] The "New recording" shortcut on the phone's launcher.
- [ ] TalkBack and Switch Access walk-through of record → read → edit → copy.
- [ ] Haptics: four distinct ticks and no others; their feel on older phones.
- [ ] Scroll and animation smoothness at 90 and 120 Hz, and grain or banding of
      the recording material on OLED screens.
- [ ] Installing over Alpha 9 with real notes keeps them.
