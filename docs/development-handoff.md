# Focal: whole-app hardening and measured STT checkpoint

Branch: `codex/sessions-stt-handoff`. Baseline before the redesign:
`14be1ae6afe15e5229982d9e8cfe63d0f276e393`.

The redesign and reliability work are implemented and reviewed. The branch is a
development checkpoint, not a verified distribution release. The public alpha
APK is unchanged. Seven-language read-speech results are now measured on the host;
ARM phone performance and full application acceptance remain open.

## Product direction

Keep the selected **D / Sessions** layout: a dated recording library, white/light
canvas, black primary actions, compact speech-language selection, timed passages
and persistent original-audio controls. No app name in the main header or filler
copy under Record. Preserve light/dark/system support and accessible controls.

The owner has **approved Focus wave**, supplied in `focal-logo-wave.zip` by a
separate designer. The selected source and production assets are now in
[`design/brand/`](../design/brand/). Do not reopen the identity search or restore
the old quotation pair or other rejected studies. The nine-bar silhouette uses
a sharp central stroke, progressively softer/less opaque outer strokes and a
restrained dark material. Preserve selective focus in the primary launcher;
small/themed/notification variants use a crisp mask. The launcher owns its mask.

The UI uses the same geometry for actual microphone-level feedback and a small
indeterminate transcription indicator. Recording movement follows measured level;
pause/silence settle, and processing motion is gated by lifecycle and system
animation settings. Processing status stays above the persistent audio/copy dock.
There is no artificial percentage, splash delay, decorative idle loop, extra
branding in the Sessions header or copy under Record. Keep this integration
restrained and functional when extending the UI. Do not invent stored audio
waveforms from a scalar level or from random samples.

The interface is English by default. Speech support must be evaluated for English,
Russian, German, French, Spanish, Italian and Portuguese. Prioritize Russian
dictation evidence without treating the other languages as untested fallbacks.

## What is implemented

- Sessions navigation, Unicode search across every passage, timed review/editing,
  original-audio playback and seeking, copy/share, rename, durable draft handling,
  retry states and independent recording/model readiness.
- Capture writes PCM directly to disk. A bounded scalar metadata channel keeps
  SQLite writes off the audio thread; pause/stop/interruption preserve partial
  reads. Metadata overflow stops visibly while keeping source audio recoverable.
- Recovery fills only uncovered audio ranges. Completed passages and manual edits
  survive retries. Deletion has a durable journal and additive database migration.
- JNI shared ownership protects decode/cancel/close; cancelled partial output is
  never committed. Bounded trusted context, timestamp-aware overlap handling and
  DC-offset-resistant speech gating replace the earlier heuristics.
- AudioTrack playback reports played position rather than submitted bytes. Player
  replacement, focus, pause/seek and model-import failure/cancellation are covered.
- Independent reviews covered capture, storage, JNI, playback, app state, UI and
  the host evaluation tooling. Confirmed findings have targeted regressions.

The decoder still validates the multilingual **base** architecture. A successful
host result with another model does not make it loadable in Android automatically;
change model validation, packaging/import and resource policy intentionally if the
evidence supports a replacement.

## Verified state and evidence

Production source is `129ee02` (including `6872bb4` Unicode title limits,
`ff8ea8d` permission feedback, `a5e460d` constant-PCM protection and
`12e958c` editor feedback); host dependency setup is `e0a9c78`.
The owner's dedicated KVM-accelerated API 35 x86_64 AVD passed **82 Android tests,
zero failures/skips**, all eight mandatory real-model checks, 36 core tests,
debug/release lint and debug/test/unsigned release builds. The Python suite passes
146 tests, also in a clean environment with pinned NumPy. See the
[quality report](quality-report.md) and exact [emulator evidence](benchmarks/2026-11-03-local/emulator-qa.json)
for hashes. The latest full run used clean HEAD `129ee02`; earlier dirty-source
runs retain their original fingerprints and mappings.

The editor failure is fixed: input-connection replay of the accepted draft cleared
rejection feedback during recreation. It was not an offscreen warning. The original
assertions remain; real keyboard paste/rotation and Save & next passed. Manual QA
also fixed a stale microphone permission notice after a successful grant.

Further manual QA found two rename defects. Truncation could split an emoji's
surrogate pair; title limits now retain whole characters within the same UTF-16
caps. Rotation with the rename keyboard open also crashed because an Activity
effect requested focus before the dialog field was attached. The effect now lives
inside the dialog composition. Its strict regression failed on the previous APK
and passed after the fix; actual landscape rotation retained the draft and focus,
and Save persisted it after hiding the keyboard.

A 2:32:37 capture survived task swipe and roughly 149 minutes screen off. The first
30-minute memory series stayed bounded; pause/resume, Finish, playback position,
seek, Unicode edit/copy/share, large drafts and legacy paging were observed. This
AVD has no host audio: actual microphone/sound and phone resource claims remain open.

**Current manual failure:** recovery sends weak varying emulator noise to ASR and
can invent text. Exact constant zero/DC now skips inference and preserves original
audio/manual edits, with 126 native language/duration/context combinations and a
real recovery regression. Actual `-no-audio` input varies by small PCM units, so the
constant fix alone does not resolve the observed recovery failure. Do not describe
all manual QA as passing.

The current engine's `noSpeech` is nearly uniform for speech and noise. Source
inspection identifies an unrequested/wrong initial logits row. A minimal SOT-row
fix is being researched in an ignored source snapshot, with independent review.
An optional [hash-verified build overlay](whisper-sot-research.md) is now available;
it is not wired into Android. No submodule or shipped engine change has been made.
Validate it on paired speech and noise before any threshold or engine adoption.

A verified dev-only SOT/abstention repeat (`0.8` no-speech, `0` log-probability
threshold) reduced normal/quiet word errors from 207/303 to 179/294, with no
WER/CER regression in the 14 cells, and emptied both noise captures in all seven
languages with empty text context. Nonempty contexts remain a separate control.
It is **not accepted**: five newly empty chunks retain a lexical
speech-loss flag requiring adjudication. Reference/time review suggests a global
alignment false positive but is not acoustic proof. The initial run failed an
unused-root-file freeze and remains diagnostic; the completed repeat freezes the
actual consumed snapshot. See the [exact result and limits](benchmarks/2026-11-03-local/report.md#sot-probability-and-abstention-verified-development-repeat).
Combined VAD compatibility and short positive controls remain development work;
the fresh test cohort stays closed.

Focused independent reviews covered capture/recovery, storage/privacy, JNI,
playback/state, UX and evidence tooling. Source reviews are separate from runtime
checks. Earlier native screenshots in [interface preview](interface-preview.md)
remain honest captures of their recorded revisions, not proof of unexecuted checks.

## Reproducible speech evaluation

Hugging Face access works in this environment. The immutable engine is
`4979e04f5dcaccb36057e059bbaed8a2f5288315`; do not update it incidentally.
Models are locked in `models/stt-candidates.json`: base Q5_1, base Q8_0 and small
Q5_1 from revision `5359861c739e955e79d9a303bcbc70fb988958b1`.

FLEURS (CC-BY-4.0), revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`,
provides 3 development and 5 test utterances per supported language: 56 clips,
650.1 seconds. Only bounded archive prefixes were fetched, never full shards.
The source IDs, derived-audio/reference hashes, aggregate results and frozen
held-out plan are in [the benchmark directory](benchmarks/2026-11-03-stt/report.md).
Audio, references, hypotheses and raw logs stay in ignored local output.

The [local continuation](benchmarks/2026-11-03-local/report.md) adds 42 fresh dev
clips and 42 unconsumed test clips, excluding previously consumed IDs/PCM/reference
hashes. One repeated Italian test reference was replaced from cached data before
scoring. Simple lower energy thresholds were rejected for noise and normal-speech
regressions. Offline Silero with VAD-only causal gain and existing text context
reduces dev errors from 207/303 to 136/178 for normal/quiet input, but individual
conditions regress. The same recordings at two gains are correlated. Fresh held-out
preparation/scoring remains postponed while the engine confidence defect is checked.

Speaker IDs are absent and dev/test speaker independence is unverified. This
small read-speech screen cannot establish release quality, calibrated confidence,
critical-error precision or Russian spontaneous-dictation performance. Preserve
these limitations when comparing models; do not turn a fast host run or aggregate
WER gain into an all-language Android claim.

The shared ceiling remains **1,000,000,000 downloaded bytes including retries**.
Current conservative reservation: **879,645,181 bytes**, including a
61,000,000-byte reserve for one future publication CI run; **120,354,819 bytes**
remain. The old 645,592,677-byte reservation prefix is preserved. Carry the public budget snapshot forward if the private
ledger is unavailable; never reset the budget to zero in a fresh environment.
The 639 MB Qwen artifact no longer fits the remainder. No LLM was downloaded.
CI model downloads also count; use Actions for bounded checkpoints, and the
owner's local accelerated emulator for long/repeated app QA.

Use `scripts/fetch-stt-model.py`, `fetch-stt-corpus.py` and `evaluate-stt.py` for
bounded, hashed experiments. `stt-probe.cpp` exposes JNI-equivalent probabilities
and load/decode/RSS diagnostics on the host. Actual Kotlin gate/context/reconcile
replay separates segmentation loss from decoder errors. No automatic correction
or experimental encoder context is enabled in the application.

## Continue locally and in the cloud

Use this checkout/branch and existing draft PR #1. Initialize the recorded
submodule. Read `AGENTS.md`, the quality/benchmark reports and
[the local QA guide](local-emulator-qa.md). Keep public history focused and scan
full history for secrets before pushing; never publish raw recordings/transcripts,
model weights, local configuration or signing material.

The local QA runner requires an explicit dedicated emulator serial, verifies the
model actually installed in app storage, checks all expected tests and all eight
real-model checks, fails on skips, restores animation settings and saves private
reports. It never uninstalls or wipes app data. Its host tests/source review are
not substitutes for running it on the owner's accelerated emulator.

## Remaining sequence

1. Resolve the observed recovery hallucinations on weak varying noise. Investigate
   the no-speech logits defect with the pinned source plus an explicit, reviewed
   research patch; retain the original engine for paired comparison. Do not hide
   the failure behind a broad amplitude cutoff. Repeat the actual manual recovery
   path and complete local suite after any production fix.
2. Address the measured quiet-speech/gate quality gap before another broad model
   search: matched quiet full intervals have 84/490 errors versus current gated
   172/490; bypassing the gate hallucinated 87 Russian words on a 30-second seeded
   noise control. Validate any candidate against omissions and false activation
   with fresh held-out evidence. Extend paired WER/CER on all seven languages. Cover names, numbers, negation,
   quiet/noisy speech, silence hallucinations and actual gate boundaries. Distinguish
   host isolated-clip results from continuous Android dictation and phone latency.
3. Use the benchmark results to choose the next comparison, without retuning on
   the consumed test split. Evaluate a
   selective acoustic second pass and context/gate ablations before adding an LLM.
   Use `scripts/evaluate-stt.py` and the staged experiment plan; retain reproducible
   manifests and aggregate results without committing speech or transcripts.
4. If acoustic results warrant it, evaluate constrained local correction with
   `scripts/score-stt-corrections.py`. A lower WER is insufficient if corrections
   introduce wrong names, amounts or negations. Require held-out improvement,
   adjudicated accepted-span precision and acceptable phone cost. Model confidence
   is not evidence that a replacement was spoken. Keep inconclusive variants off.
5. Any shipped correction must preserve immutable original ASR/audio, manual edits
   and a consistent visible/copy revision. Show a restrained changed-span underline;
   tapping reveals original → correction and Undo. Avoid extra steps in normal use.
   The current mutable passage text is insufficient provenance: migrate the data
   model before enabling automatic corrections, as specified in the experiment doc.
6. Validate on midrange and older ARM64 phones, including a 4 GB device and current
   Android foreground rules: screen-off recording, calls, recovery, TalkBack,
   cold/warm latency, memory, battery and thermals. Emulator timing cannot establish
   any of these phone-performance claims.
7. Re-review changed layers, run required build/tests and applicable regressions,
   then verify the exact bundled, stably signed release artifact before publication.
   Do not replace existing release assets or call the product finished while these
   gates remain unresolved.

Use evidence to justify larger refactors or model changes. Keep inference and
correction off the capture thread, memory and queues bounded, and the original
audio durable throughout the work.

For a self-contained starting instruction, use
[`next-session-prompt.md`](next-session-prompt.md).
