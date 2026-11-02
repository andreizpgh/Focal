# Focal

Local voice notes for Android. Speak, review, copy.

Focal is being built in Kotlin with a quiet, light-first interface. Speech
recognition uses whisper.cpp on the device. No account, cloud transcription,
analytics, or Internet permission.

## Development

Initial implementation is in progress. An installable release is not yet available.

- Android 8.0+; ARM64 phones, x86-64 emulators.
- JDK 17, Android SDK 35, NDK 28.0.13004108, CMake 3.22.1.
- `git submodule update --init --recursive`
- Set `ANDROID_HOME` or `sdk.dir` in an untracked `local.properties`.
- `./gradlew :core:test :app:assembleDebug`

The native engine is pinned to whisper.cpp v1.8.2. The intended bundled model is
multilingual Whisper base, quantized to Q5_1. Model provisioning and the complete
recording/review workflow are under development. Quality, speed, and battery
use need measurements on real phones; no performance claims are made yet.

## License

MIT. See [third-party notices](THIRD_PARTY_NOTICES.md).
