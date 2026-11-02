# Focal

Kotlin Android app for local voice notes. The product path is record → review →
copy. Keep the UI quiet, English by default, with explicit speech-language choice.

- Use the existing checkout. Each cloud task is isolated; do not create a worktree
  unless the user explicitly requests it.
- No network permission, analytics, accounts, or automatic rewriting of speech.
- Preserve original audio and transactional recovery. Never let inference block
  the capture thread. Do not add unbounded in-memory audio queues.
- Keep third_party/whisper.cpp at the recorded submodule commit unless intentionally
  updating the engine. Do not edit its source as part of unrelated app work.
- No model weights, user recordings, signing keys, or SDK paths in Git.
- Build: `./gradlew :core:test :app:lintDebug :app:assembleDebug`.
- Device tests: `./gradlew :app:connectedDebugAndroidTest` with an Android device.
- Download verified build-time model: `python3 scripts/fetch-model.py`.
- Report actual checks. Desktop/emulator timing does not establish phone battery,
  thermal behavior, background reliability, or Russian recognition quality.

See CONTRIBUTING.md for prerequisites and docs/architecture.md for invariants.
