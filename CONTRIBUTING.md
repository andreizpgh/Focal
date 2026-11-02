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
./gradlew :core:test :app:lintDebug :app:assembleDebug
```

The model downloader verifies the upstream LFS SHA-256 and records the immutable
upstream revision in the APK. Reproduce a model with
`python3 scripts/fetch-model.py --revision <revision-from-model-provenance.json>`.
Never commit model binaries, private recordings, signing keys, or local SDK paths.

An Android emulator or connected phone is needed for device tests:

```sh
./gradlew :app:connectedDebugAndroidTest
```

`scripts/smoke-stt.sh` builds the same pinned speech engine on the host and checks
a real English speech fixture. This validates model loading and decoding; it does
not measure Android performance or Russian accuracy.

The debug APK is for development. Distribution releases need a separately managed,
stable signing key; release builds refuse to package an absent speech model.

## Product boundaries

- English interface by default. Speech language is explicit and stored per note.
- Preserve the original audio and the speaker's meaning. No automatic rewriting.
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
