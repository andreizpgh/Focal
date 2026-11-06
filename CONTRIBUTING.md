# Contributing

Small, focused changes are welcome. Describe the behaviour you change and how you
checked it. Focal stays offline: network calls, analytics, accounts and
cloud-dependent features do not belong in it.

## Prerequisites

- JDK 17.
- Android SDK with platform 37 (`compileSdk 37`, `targetSdk 36`, `minSdk 26`),
  NDK `28.2.13676358` and CMake `3.22.1`. Android Studio can install them; set
  `ANDROID_HOME`, or `sdk.dir` in an untracked `local.properties`.
- Python 3 for the scripts.

The Gradle wrapper (9.8.0) pins the build; versions of AGP, Kotlin and libraries
are in `gradle/libs.versions.toml`.

## Speech model and runtime

The model and the sherpa-onnx runtime are never committed. `scripts/fetch-model.py`
downloads them over HTTPS and accepts each file only when its size and SHA-256
match the lock files `models/parakeet-ultra.json` and `models/sherpa-onnx.json`,
which Gradle reads too.

```sh
python3 scripts/fetch-model.py                 # model (~670 MB) and runtime (~50 MB)
python3 scripts/fetch-model.py --runtime-only  # runtime only: enough for tests and lint
python3 scripts/fetch-model.py --check         # verify what is in place, no network
python3 scripts/fetch-model.py --from DIR --aar FILE   # reuse verified local copies
```

`--from` can be repeated when the files are in several directories; each file is
taken from the first directory that has it. Files land in `models/parakeet-ultra/`
and `libs/`, both ignored by Git.

Every build needs the runtime. Release builds also need the model and refuse a
missing or mismatched one. Debug builds without the model work, but the app then
records without transcribing; add `-Pfocal.requireModel=true` to make a missing
model an error.

## Build and test

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests
```

With an emulator or a test device connected (not a phone with real notes):

```sh
./gradlew :app:connectedDebugAndroidTest -Pfocal.requireModel=true
```

[Testing](docs/testing.md) describes each suite, the screenshot gallery
(`gallery/GalleryTest`) and the phone checklist. Report the checks you actually
ran; emulator timing says nothing about phone speed, heat or battery.

## Project layout

```
app/                    Android app: Compose UI, recording service, store, speech bridge
  src/main/cpp/         JNI bridge over the sherpa-onnx C API (the only native code)
  src/androidTest/      instrumented tests and the screenshot gallery
core/                   pure Kotlin: segmentation, formatting, alignment, tuning constants
models/                 lock files for the speech model and runtime (no weights)
scripts/                fetch-model.py, subset-inter.py (Inter font subset), score-wer.py
design/brand/           the logo and launcher icon sources and their generator
docs/                   architecture, design, speech quality, testing, install guide
```

## Product boundaries

- **One engine.** Parakeet Ultra through sherpa-onnx. The model has no language
  input; there is no language or model picker.
- **Capture is sacred.** Never let inference, SQLite or the interface block the
  capture thread. Audio must be synced before anything depends on it. Do not add
  unbounded in-memory audio queues.
- **Keep what was said.** Raw recognizer output and the original audio are kept;
  user edits are an overlay with Restore original. Formatting may not change
  words. Automatic correction stays disabled until a candidate clears the gates
  in [speech quality](docs/speech-quality.md#automatic-correction-is-disabled).
- **Recovery.** Changes to capture or storage need tests for process death,
  migration and recovery. A dead process must never reopen the microphone.
- **Interface.** English, with every user-facing string in `strings.xml`. Use the
  design tokens in `ui/design` ([design](docs/design.md)), keep 48 dp targets,
  and give every gesture a button, key or accessibility action.

See [architecture](docs/architecture.md) for the invariants in detail.

## Privacy and secrets

Never commit credentials, signing keys, `local.properties`, model weights, private
recordings, transcripts, device logs or screenshots of real notes. Use synthetic
data and the public fixtures: the JFK excerpt in `app/src/androidTest/assets` and
the FLEURS clips listed in `docs/benchmarks/fleurs-references.json`. Ignoring a
file does not remove copies already committed.

Before publishing, check your Git author email, review the staged diff and scan
the full history with [Gitleaks](https://github.com/gitleaks/gitleaks) (CI pins
8.24.3):

```sh
gitleaks git --redact=100 --log-opts="--all --full-history" .
git diff --cached --check
```

Treat any finding as a blocker. See [SECURITY.md](SECURITY.md) for what to do if
a secret is exposed.

## Releases

Testing releases are published as GitHub pre-releases with a `vX.Y.Z` tag and an
APK named `Focal-X.Y.Z.apk`.

1. Build the release APK from a clean checkout of the release commit, with the
   verified model in place.
2. Run the checks above and the instrumented suite against that exact APK.
3. Sign it outside Git. Testing releases use the same development certificate as
   Alpha 2 to Alpha 9, so updates install in place and keep notes.
4. Record the commit, APK and signing-certificate SHA-256, model and runtime hashes,
   the checks run and what remains unverified, and attach that with checksums.
5. Create a draft pre-release with notes from [CHANGELOG.md](CHANGELOG.md), check
   the uploaded asset's digest, then publish.

Never rebuild or re-sign between verification and upload, and never overwrite a
published asset. The automated release workflow is disabled until it is rebuilt
for the single-engine app.
