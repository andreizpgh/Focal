# Focal: whole-app hardening and measured STT checkpoint

**Resumed at the owner's explicit request on 2026-11-04.** The historical
[pause checkpoint](pause-checkpoint.md) records preserved source and evidence.
The rejected Android VAD candidate has now been archived and removed from the
working build; independent search fixes passed six focused tests and manual
200% font rotation/Home checks, followed by a clean 84-test full run at `b59c6ee`.
A later caret-return fix passed fresh RED/GREEN and a clean **85-test** full run at
`101a153`; the AVD is stopped and exact verified APKs are archived privately. Heavy
local checks run sequentially to reduce laptop load.

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

The latest clean full-suite app source is `101a153` (search caret return and prior state/layout, scrollable
Rename in `d8df58e`, and the prior `129ee02`/`6872bb4` title fixes,
`ff8ea8d` permission feedback, `a5e460d` constant-PCM protection and
`12e958c` editor feedback); host dependency setup is `e0a9c78`.
The owner's dedicated KVM-accelerated API 35 x86_64 AVD passed **85 Android tests,
zero failures/skips**, all eight mandatory real-model checks, 36 core tests,
debug/release lint and debug/test/unsigned release builds. The Python suite passes
146 tests, repeated separately at `b59c6ee` in the existing hash-pinned NumPy environment;
host sources are unchanged and that suite was not rerun for the later UI-only fix. See the
[quality report](quality-report.md) and exact [emulator evidence](benchmarks/2026-11-03-local/emulator-qa.json)
for hashes. The latest full run used clean HEAD `101a153`; earlier dirty-source
runs retain their original fingerprints and mappings.

The new caret test uses one unchanged test APK for fresh RED on a source-matched
`b59c6ee` rebuild and GREEN on the fix: caret 6 previously became 13 after opening
a result and returning. Exact query, hidden IME and unfocused state also pass.
All 23 previous InterfaceTest bodies remain unchanged. Do not preserve highlighted
selection by suppressing Compose deselect callbacks: that earlier draft expectation
was invalid and is retained separately. The controlled saveable `TextFieldValue`
now belongs to `FocalApp`, preserving the actual caret through navigation.

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
Combined VAD compatibility completed at 136/178 word errors, unchanged from
unpatched cap30. Four production language/condition cells still regress, so it
is rejected for held-out evaluation. The 28 synthetic positive decoder outputs
were unchanged, but only 13/14 noise controls with nonempty context were strictly
empty: a French case retained punctuation. The threshold was not retuned.
VAD-only coverage retained all samples in those 28 synthetic positives; this is
not a human-speech accuracy result. The whole-chunk presence variant gives 179/266
word errors without a cell regression, preserving all 28 positive outputs and
skipping ASR on all 28 noise/context cases. Report assembly failed after inference;
a separately frozen postprocessor recovered counts, with independent review of
219 inputs, 1,638 outputs and 185 VAD decisions. The original FAILED status and
lexical-loss flag remain, with separate boundary/reference adjudication evidence.
It is not an accepted policy or a clean completed run. No Android integration is
accepted and the fresh test cohort stays closed. The completed shorter encoder
context variant is rejected: 183/249 word errors, six regressed cells against
production, nine against full-context presence, and 15 failed positive canaries.
Its 150 ASR calls reused 185 checked VAD decisions; no new VAD inference ran.
See the [separate result](benchmarks/2026-11-03-local/auto-context-dev-results.json).

A separate [VAD API research overlay](whisper-vad-research.md) makes compute failure
return false, clears partial probabilities and adds cooperative CPU cancellation.
Normal probabilities/ranges match the original engine exactly on the tested
fixtures; early/mid-graph cancellation and context reuse pass. It is not wired
into the verified Android checkpoint or combined with SOT. Native
conditioning/presence now matches all 185 already-used DEV/control requests exactly
(150 present, 35 absent), including probability bits and ranges; the original
floats are unchanged. Independent review checked all 784 frozen inputs.
The [public parity aggregate](benchmarks/2026-11-03-local/native-presence-parity.json)
does not establish ASR quality or Android/phone performance. The fresh paired
reused-ASR development run is now **rejected**: normal/quiet word errors change
from 223/256 to 176/255, but quiet Spanish rises from 16 to 19 and ten positive
controls fail. Targeted review confirms loss of a previously correct Portuguese
negation. Independent review verified all 3,129 inputs, 2,176 outputs and actual
Kotlin replay; five newly empty DEV chunks and four lexical flags stay open.
See the [persistent comparison](benchmarks/2026-11-03-local/persistent-presence-dev-results.json).
The app keeps its engine open; cold evidence did not establish this behavior.

An unpublished Android candidate passes 26 focused tests and two additional
preparation/dialog regressions, each checked against removal of its fix.
Actual English and Russian force-stop recovery now retains varying emulator PCM
and displays an uncertain empty passage, but the language/control regressions
prevent acceptance. Do not publish the experimental integration as a verified
product change. Fixed-history repeats now isolate decoder-zero RNG carry on one
ES DEV prefix: the pinned beam sampler samples IDs instead of using its sorted
scores; one reset statement removes the repeated target difference. True top-k
selection is a separate, unaccepted candidate. The optional
[combined research build](whisper-beam-research.md) passes actual-sampler and
configuration checks; its fourteen-call ES repeat is history-invariant but adds
text on a previously empty short input. Its full persistent DEV comparison is now
**rejected**: 335 ASR/185 VAD calls, word errors 223/256 → 176/246, but six cells
worsen in WER/CER and 14 positive controls fail. Quiet French/Portuguese controls
lose previously correct negations despite positive VAD. All 28 noises skip ASR;
28/28 positive identities refer only to the two new arms, not the old production
baseline (6/28 exact). Independent review checked 4,289 inputs and 2,187 outputs.
Eight newly empty DEV chunks and seven lexical flags stay open. See the
[complete result](benchmarks/2026-11-03-local/true-topk-dev-results.json).
Separate English source accounting confirms two whole clips with 25 reference
words never reach ASR. Partial-clip errors are not all attributed to framing.
The proposed fixed-window variant has not run because the same decoder already
fails unchanged controls; do not promote it or consume held-out data.
Seven real-language Android DEV first chunks preserve all inputs, but RU/FR texts and the FR review label differ
from host results. See the [diagnostic evidence](benchmarks/2026-11-03-local/report.md#reused-decoder-fixed-prefix-diagnosis).
Freeze any final policy before held-out use. All 42 fresh held-out clips remain closed.

Focused independent reviews covered capture/recovery, storage/privacy, JNI,
playback/state, UX and evidence tooling. Source reviews are separate from runtime
checks. Earlier native screenshots in [interface preview](interface-preview.md)
remain honest captures of their recorded revisions, not proof of unexecuted checks.

A subsequent focused Rename fix makes the complete form scroll within explicit
IME/system insets. At 200% font in landscape, actual swipes now reach the heading
and both actions above the keyboard; Save, dirty Cancel/Keep editing and Discard
preserve the expected draft/storage state. Both existing strict editor regressions
pass (5.964 s). These working-APK checks do not replace clean full QA. Landscape
search accessibility is still being fixed; see the [quality report](quality-report.md).

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
Current conservative reservation: **881,742,333 bytes**, including a
61,000,000-byte reserve for one future publication CI run; **118,257,667 bytes**
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

The subsequent preserved-ASR research run executes all original calls before VAD
and retains raw output separately from suppressed emissions. All 185 raw outputs
match production, and actual Kotlin replay matches the frozen conditional replay.
Emitted errors are 179/252 words and 436/803 characters versus 223/256 and 598/822,
without a regression in 14 cells. All 28 positives are unchanged; 28 noise controls
retain nonempty raw ASR but emit no text, retaining an uncertain review cue.
Independent review verified 2,877 inputs,
945 outputs and the public `post-asr-dev-results.json` export. Five changed DEV
ranges received textual source-local review; four lexical flags remain, with no
acoustic no-loss guarantee. WER improves only for EN/ES/IT, and all 185 ASR calls
plus 185 VAD calls remain: neither all-seven improvement nor acceleration is
established. Held-out stays closed. Do not silently return empty JNI output in an
Android integration: immutable original ASR, visible provenance, Undo, manual-edit
protection and phone resource acceptance are required before enabling this policy.

The later fixed GREEDY1/corrected-SOT/presence control screen was rejected:
15 strict positive failures, including lost previously correct FR/PT negations;
critical review also found a time-component error in IT despite passing WER/CER
criteria. All 28 noise inputs skip ASR. Independent postrun review completed on
2026-11-04: 1,762 inputs, 255 outputs and 11 dependencies verified, with safe
aggregates in the local benchmark report. No human DEV/framing follow-up was run
for this failed candidate. The fresh held-out cohort remains unconsumed.

The subsequent [model-free control-flow ablations](benchmarks/2026-11-03-local/report.md#beam-control-flow-ablations-model-free-contracts-only)
confirm duplicate beam allocation and emission without a valid winner in the
rejected true-top-k parent. Four actual-source variants produced exactly 15/4/11/0
expected failures; the combined research change passed 22 synthetic cases.
Independent review verified 1,231 inputs and 24 outputs. No models/audio were
used, and no production change or quality/speed claim follows. A later neural
screen must keep the unchanged positive/noise controls and serialize raw failed
state before reset/free; final error `-11` is a failed trial, never successful
silence or an excluded sample. Prior rejections and the closed held-out set stand.

The later frozen neural control screen is now complete and rejected: 56 requests,
28 ASR attempts (27 success and one explicit `-11`), 56 VAD calls, 28 noise skips.
Fourteen strict positive gates failed; only six texts exactly match production.
Three successful calls newly emitted empty text; the Russian failed trial remains
separate. Critical review confirms quiet FR/PT negation loss and a PT negation
loss despite improved WER/CER. Against the rejected top-k parent, 27/28 accepted
positive texts match; the remaining case is explicit decoder exhaustion. This is
not whole-output equality or acceptance. Independent review checked 796 inputs,
289 outputs, 11 dependencies and all metrics/diagnostic encodings. No human DEV,
held-out data or new threshold was used. See the local benchmark report; all-seven
improvement and speed remain unestablished. Source-bound diagnostics locate the
two FR/PT quiet short-negation omissions in the existing no-speech conjunction:
completed raw beams already contain the reference, but all admissible beams meet
the cutoff. This is not permission to retune or evidence that other regressions
would be fixed; retain the safe diagnosis and original raw records.

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

Before accepting another engine build, resolve the newly reviewed allocation-error
ownership defects. Pinned `whisper_full_with_state` can free its borrowed state on
self-KV growth failure (`-7`); normal JNI/service cleanup then frees it again.
Uninitialized early `batch` cleanup and partial KV allocation are related defects.
An isolated ownership-only patch now passed all 24 frozen model-free cases;
baseline four controls passed and 20 expected errors were reproduced, including
two ASan use-after-free reports on owned/external failure → close. Independent
review checked 664 source inputs, 94 tool/link inputs and all 48 execution logs.
The actual engine and CPU GGML run with synthetic descriptors and graph bypasses;
this is not an actual-model recovery or arbitrary OOM proof. Preserve the v4
freeze and the failed v3 link attempt separately. Next verify real graphs and
same-state beam/greedy retries, the three loader wrappers, then Android error →
close → pending-audio/manual-edit preservation → new-engine recovery/cancellation.
Production is still unfixed; do not infer phone resource behavior from host tests
or induce real host memory pressure. The diagnostic probe's fatal-process handling
is not a production Android fix. See the local benchmark report for scope/hashes.

The optional [ownership-only CMake overlay](whisper-lifetime-research.md) reproduces
the exact reviewed `8cc010e3…ed64b` postimage and remains disabled in Android.
Nine tests cover 19 configuration cases; the full host suite now passes 155 tests
without skips (7.478 s). No extra Android run was needed for this inactive hook.
Real-graph preparation in `build/stt/engine-lifetime-realgraph-research/` is now
frozen as `86d55e199ca49267e07077953405e7451ec11aa27c76444027b369de7097209e`:
682 inputs, 96 tool/link identities and 16 passing pure Python evaluator tests.
Independent prebuild and postbuild reviews passed. Two sanitized full-engine
translation units built sequentially in 91.926 s, reusing the exact 23-TU GGML
archives and existing runtime/model/audio files. No downloads occurred.

The first actual-model attempt is **incomplete**: only baseline
`healthy_owned_beam` started. Model load and actual state-graph initialization
completed, then the frozen 60-second wall deadline killed the process (exit -9).
There was no full return, canonical output or fault marker; patched was not run.
The runner's missing-canonical message is secondary to the recorded timeout.
Independent review verified the single log, unchanged inputs/binaries and seven
loaded dependencies. The log cannot locate unfinished encoder versus decoder work;
SIMD is enabled. Preserve the failed attempt and all functional assertions.
See the [safe attempt record](benchmarks/2026-11-03-local/engine-lifetime-realgraph-results.json).
The interrupted 666-file draft and unexecuted preparation v1 remain separate.
Do not repeat the completed v4 suite merely to resume work.

A separately frozen, independently reviewed single healthy baseline diagnostic
then passed in 79.903 s without recompile or downloads. It uses the same binary,
model, PCM and full assertions, with 180 s wall/150–155 s CPU allowances, unchanged
RSS guards and one inference thread. The last live observation recorded 78.06 CPU
seconds at 78.030 elapsed; maximum sampled VmHWM was 249,576 KiB. This supports an
insufficient original 60-second allowance for this instrumented control, not a
specific slow stage, sanitizer overhead factor or phone performance. Diagnostic
freeze is `022953faed8205a9ca1eb758ee92599138bf61913692167ba89caaf560e01b65`,
summary `a62dfe93aa95709a998bae8006a9f50f238e97adcd237b9af7a0ca95ca8084ce`.
Before a new matrix, review bounds against actual auto-language/retry control flow;
the one healthy result does not establish retry, auto-language or patched outcomes.

The Android follow-up plan remains unimplemented. Use a separate explicit opt-in
debug fault artifact and test source set, leaving normal APKs and the strict
85-test/eight-model-check suite free of hooks. Service tests must await app/model
readiness themselves, then await complete worker/service cleanup before normal
recovery. In-flight cancellation needs a separate barrier-only case: cancellation
would mask a simultaneous allocation error, and the observer must not retain an
extra engine owner. Validate native -7, original audio/manual edits, pending chunks,
normal-engine recovery and exactly-once destruction before production adoption.

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
