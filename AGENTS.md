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

## Public repository standards

This repository is also a developer portfolio. Keep code, history, documentation,
and release assets suitable for public review.

- Make focused commits with descriptive subjects. Review the staged diff before
  committing; do not commit scratch output, conversation transcripts, or CI logs.
- Never commit credentials, private audio/transcripts, personal device data,
  local configuration, or signing material, including to temporary branches.
  Use synthetic examples and the documented public speech fixture.
- Check author/committer email privacy before the first commit in a new environment.
  Preserve the owner's chosen public identity; do not invent personal details.
  The owner explicitly chose to keep the currently configured author email public.
- Run Gitleaks with full-history scanning before publishing changes. Keep the
  secret-scanning workflow enabled; never broadly suppress findings to pass CI.
- If a credential is exposed, stop publishing it, revoke/rotate it through its
  owner, and coordinate cleanup. Deleting the latest copy does not clean history.
  Do not force-push or rewrite published history without explicit authorization.
- Keep the README concise and product-focused. Put build steps and limitations in
  linked documentation. Use honest screenshots of the current app and state
  measured results separately from intended behavior.
- Never publish unverified APKs, overwrite published release assets, or include
  signing keys in releases. Link each testing release to its verified build.
