# Contributing

Small, focused changes are welcome. Describe the behavior being changed and how
you checked it. Keep the application offline; new network calls, analytics, and
cloud-dependent features do not belong in Focal.

## Build

Use JDK 17, SDK 35, NDK 28.0.13004108, and CMake 3.22.1. Android Studio can install
these. Set `ANDROID_HOME`, or `sdk.dir` in your untracked `local.properties`.

```sh
git submodule update --init --recursive
python3 scripts/fetch-model.py
python3 scripts/fetch-vad-model.py
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The model downloader verifies the upstream LFS SHA-256 and records the immutable
upstream revision in the APK. Reproduce a model with
`python3 scripts/fetch-model.py --revision <revision-from-model-provenance.json>`.
The separate speech-presence model is pinned by `models/speech-presence.json`;
the downloader verifies its exact size and SHA-256 before installation. To reuse
an existing copy without another transfer, run
`python3 scripts/fetch-vad-model.py --local-file /path/to/ggml-silero-v5.1.2.bin`.
Both debug and release builds reject a missing or corrupt presence model.
Never commit model binaries, private recordings, signing keys, or local SDK paths.

An Android emulator or connected phone is needed for device tests:

```sh
./gradlew :app:connectedDebugAndroidTest
```

`scripts/smoke-stt.sh` builds the same pinned speech engine on the host and checks
a real English speech fixture. This validates model loading and decoding; it does
not measure Android performance or Russian accuracy.

Host evaluation regressions use Python 3.11+ and the pinned NumPy dependency
(including the VAD-only conditioning checks). Run them in a local virtual environment:

```sh
python3 -m venv build/host-tools
build/host-tools/bin/python -m pip install --require-hashes --only-binary=:all: -r scripts/requirements-host.txt
build/host-tools/bin/python -m unittest discover -s scripts/tests -p 'test_*.py'
```

On Windows, use `build/host-tools/Scripts/python.exe`. This dependency is for host
research/tests only; it is not packaged in the Android application.

The debug APK is for development. Distribution releases need a separately managed,
stable signing key; release builds refuse to package an absent speech model.

## Product boundaries

- English interface by default. Speech language is explicit and stored per note.
- Preserve the original audio and the speaker's meaning. Automatic correction is
  currently disabled; research candidates must clear the accuracy, provenance,
  undo and phone-resource gates in [speech-quality experiments](docs/stt-quality-experiments.md)
  before being enabled.
- Review cues are heuristics, not guarantees of correctness.
- Recording must not depend on an Activity remaining alive.
- Keep work and queues bounded in memory. Audio and completed transcript segments
  must survive process death; the last unsynced audio buffer may be lost.
- Respect Android microphone, foreground service, and notification permissions.
- Test meaningful lifecycle and recovery behavior when changing capture or storage.

See [architecture](docs/architecture.md) and [device validation](docs/device-validation.md).

## Before publishing

Review the staged diff and your Git author email. Use GitHub's private commit
email if you do not want a personal address in public history. Keep commits
focused and explain observable changes and relevant verification.

Never commit credentials, local configuration, signing keys, private recordings,
transcripts, or device logs. Use synthetic data or documented public fixtures.
Ignoring a file does not remove earlier committed copies.

With [Gitleaks 8.24.3](https://github.com/gitleaks/gitleaks/releases/tag/v8.24.3):

```sh
gitleaks git --redact=100 --log-opts="--all --full-history" .
git diff --cached --check
```

CI scans the full fetched history on pushes and pull requests. Treat any finding
as a blocker; do not paste secret values into issues or logs. Coordinate credential
revocation and any history cleanup with the owner before rewriting public refs.

Testing releases promote an existing successful Android build using the pinned
manifest in `releases/`. The tag workflow checks the build, artifact checksum,
unchanged app inputs, and embedded model before publishing the APK and checksums.
Release notes live in `docs/releases/`. Alpha APKs use development signing;
stable distribution needs a persistent, separately managed release signing key.

Local UX previews use a distinct `ux-alpha-N` tag and a `local-emulator-qa`
manifest in `releases/`. They promote the exact archived debug APK from a clean,
successful `scripts/qa-local.py --release-checks` run, after focused review and
manual checks. Publish through a new draft prerelease, verify the remote asset
digest against the manifest, then publish the draft. Include `Focal.apk`,
`SHA256SUMS`, model provenance and the public verification manifest; never attach
private QA logs, recordings or signing keys. Do not rebuild or re-sign between QA
and upload, overwrite an existing release, or infer Alpha 1 signing compatibility.
The existing `v*` CI-artifact workflow and its verification requirements stay intact.
