# Focal

Kotlin Android app for offline voice notes. The product path is record → read →
copy. Keep the UI quiet and in English. The speech language is detected
automatically: Parakeet Ultra takes no language input, so there is no language
choice, and very short utterances can come out in the wrong language
(see docs/speech-quality.md).

- Use the existing checkout. Do not create a worktree unless the user explicitly
  requests it.
- No network permission, analytics, or accounts. Automatic LLM correction stays
  disabled until paired speech evaluation demonstrates a clear accuracy benefit
  within phone resource budgets; the evidence and gates are in
  docs/speech-quality.md. Research may compare local correction candidates;
  preserve original audio/ASR and manual edits, and require visible provenance and
  undo before enabling corrections.
- Parakeet Ultra INT8 through the pinned sherpa-onnx 2.0.1 C API is the only
  speech engine. Do not add a second engine, a language picker or a model choice
  without an explicit product decision. Change the model or runtime only through
  the lock files in models/; keep the vendored C API header unchanged.
- Preserve original audio and transactional recovery. Never let inference block
  the capture thread. Do not add unbounded in-memory audio queues. A dead process
  must never reopen the microphone.
- Formatting must not change recognized words, and raw recognizer output stays
  immutable. Tuning constants live in core's SpeechTuning; the paragraph and
  doubtful-word thresholds are provisional.
- No model weights, user recordings, signing keys, or SDK paths in Git.
- Build: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- Device tests: `./gradlew :app:connectedDebugAndroidTest` with an emulator or test
  device; add `-Pfocal.requireModel=true` so the speech tests cannot skip.
- Download the verified model and runtime: `python3 scripts/fetch-model.py`
  (`--runtime-only` is enough for tests and lint).
- Report actual checks. Desktop/emulator timing does not establish phone battery,
  thermal behavior, background reliability, or Russian recognition quality.

See CONTRIBUTING.md for prerequisites, docs/architecture.md for invariants and
docs/testing.md for the pending phone checks.

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
