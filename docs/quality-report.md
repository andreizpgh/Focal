# Product verification · 2026-11-02

The Sessions redesign and reliability changes are implemented on this development
branch. Real-speech quality, phone performance and release readiness remain
unverified. The existing public alpha APK does not contain this revision.

## Completed checks

| Area | Verification |
| --- | --- |
| Core speech logic | 30 passing JVM tests: boundaries, context, partial frames, DC offset, review cues, resource policy |
| Correction evaluation tooling | 15 passing host tests for edit mapping, paired metrics, incomplete evidence and critical errors; no model inference |
| Native Android behavior | 53 passing distinct instrumentation tests; 5 model-dependent checks explicitly skipped |
| Build | Debug APK and instrumentation APK assemble; debug/release lint pass; release R8 compilation passes |
| Platform packaging | Packaged ARM64/x86-64 native LOAD segments use 16 KB alignment; APK has no Internet permission |
| Privacy review | Gitleaks 8.24.3 full-history and working-tree scans report no leaks |
| UI | Native captures; light/dark, Russian edit/copy, original audio, navigation, 320×560 dp at 200% font scale |
| Prototype access | Owner-private top-level pages; offline pack without iframes or external requests |

Android checks ran on an Android 10 x86-64 emulator. They establish behavior on
that test environment, not ARM phone latency, thermal behavior or OEM reliability.
Service tests supply bounded synthetic PCM and an explicitly unavailable decoder;
they never pretend to measure speech accuracy. Three UI capture scenarios were
also repeated at approximately 390×846 dp.

After the final notification and playback-control polish, all 11 service tests and
5 relevant UI scenarios passed again. The recovery test now waits for the joined
asynchronous teardown before checking cleared live state; its original-audio,
pending-range and no-microphone assertions remain intact. The final source and
private-gallery secret scans also passed.

## Independent review and regression coverage

Implementation and independent cross-layer reviews covered capture/lifecycle,
SQLite/files, native inference, playback, model import, ViewModel/navigation, and
UI accessibility. Confirmed findings were fixed and checked with targeted
regressions:

- Capture continues while a real SQLite write transaction is held; scalar
  metadata uses a bounded queue. Overflow stops visibly and preserves audio.
- Pause/resume are idempotent; final partial reads survive stop, interruption and
  microphone silencing. Completion waits for durable audio and drained metadata.
- Invalid recovery and stale controls cannot open the microphone or violate the
  foreground-service startup deadline. Teardown removes the ongoing notification.
- Recovery fills uncovered ranges without replacing finished passages or user
  corrections. Source ranges are validated, completion is idempotent, and failed
  physical deletion has a durable retry journal with additive migration.
- Native handles retain safe ownership through cancel/close. Cancelled output
  cannot be committed as a completed chunk; invalid confidence remains reviewable.
- Boundary reconciliation uses timestamps; it retains ambiguous repetitions for
  review instead of deleting legitimately repeated speech. Context uses contiguous
  trusted whole words and excludes overlapped audio.
- Playback reports the audio hardware's played position; pause, seek, focus loss
  and rapidly replacing the source do not let an old player overwrite new state.
- Failed reads offer retry, delayed deletion preserves a newer selection, and
  drafts survive rotation and same-session notification navigation. Failed edits
  and renames stay open. Failed/cancelled model imports preserve installed bytes.

## Focus wave follow-up · 2026-11-03

The owner-approved identity is integrated into adaptive/themed/notification
resources, documentation and compact UI marks. The primary launcher keeps the
original selective blur and material in static raster layers; the app does not
compute Gaussian blur while recording. The live microphone-level display shares
the approved nine-bar geometry and opacity falloff.

- Required core/debug/lint tasks, instrumentation assembly, debug/release lint
  and release R8 compilation pass. No speech-model or corpus downloads were made.
- The complete 11-scenario UI suite passes on the Android 10 x86-64 emulator,
  including 200% text at 320 × 560 dp, actual-level/pause semantics, Russian
  editing/copy, persistent long-transcript processing status, and removal of
  progress when processing finishes. Recording and processing also passed
  targeted runs with system animation scale enabled. These synthetic presentation
  states do not establish recognition quality or phone power consumption.
- Asset generation and independent review verify original source hashes,
  outlined Inter wordmarks, 108 dp/432 px adaptive layers, an opaque full-bleed
  background, transparent foreground, the 66 dp safe circle, and nine separated
  notification strokes at 24 px. The themed mask retains source alpha. The
  pixel-identical rebuild check passes with the recorded Chromium version.
- Independent UI and asset reviews found no remaining actionable defects.
  Processing motion reads one phase in drawing, is bounded to nine bars, stops
  below STARTED, and becomes static when system animations are disabled.
  Actual TalkBack traversal and Android 13+ launcher tinting still need device
  validation.

Initial QA exposed a software-emulator System UI ANR (Android denied clipboard
access while its dialog held focus), an offscreen LazyColumn lookup in the new
test, and Compose/Espresso idle/reentrant-layout failures while the existing real
player continuously updated its position. After restoring the emulator, the new
test uses the lazy list's scroll action. The playback test now explicitly advances
Compose frames only while audio plays, keeps all seek/Pause/Play assertions, and
restores the saved clock setting with guaranteed player cleanup. No playback
production logic was changed to accommodate the test. The subsequent complete UI
run passed all 11 cases.

The private native gallery was updated with direct Android captures; image
loading, both theme buttons and 390 px layout were checked. Source and history
secret scans pass. These checks do not close the speech, ARM-device, signing or
release gates below.

## Remaining release gates

1. Obtain the pinned model and a licensed, bounded multilingual corpus. This
   environment currently denies Hugging Face destinations. Model/corpus downloads
   must stay below 1 GB; no full corpus download is planned.
2. Run the real JNI decode/cancellation and microphone workflow checks currently
   skipped for missing weights. Compare WER/CER for all seven languages, including
   Russian, names, numbers, negation, quiet speech and boundary omissions.
3. Compare model/decoder/correction candidates using paired held-out evidence.
   No local LLM correction is enabled or claimed to improve accuracy yet.
4. Validate a bundled APK on midrange and older ARM64 phones, including current
   Android foreground-service restrictions, screen-off capture, calls, thermals,
   memory, battery and actual TalkBack traversal.
5. Use stable distribution signing and verify the exact model-bundled release
   artifact before publishing it. Release packaging rejects missing model weights.

See [device validation](device-validation.md), [interface captures](interface-preview.md),
the [staged speech-quality experiments](stt-quality-experiments.md), and
[architecture](architecture.md). A build passing structural checks is not a
measured recognition-quality result.
