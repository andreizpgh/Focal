# Focal: whole-app hardening and measured STT checkpoint

**Resumed at the owner's explicit request on 2026-11-04.** The historical
[pause checkpoint](pause-checkpoint.md) records preserved source and evidence.
The rejected Android VAD candidate has now been archived and removed from the
working build; independent search fixes passed six focused tests and manual
200% font rotation/Home checks, followed by a clean 84-test full run at `b59c6ee`.
A later caret-return fix passed fresh RED/GREEN and a clean **85-test** full run at
`101a153`. Exact verified APKs from that checkpoint are archived privately. Heavy
local checks run sequentially to reduce laptop load.

Branch: `codex/sessions-stt-handoff`. Baseline before the redesign:
`14be1ae6afe15e5229982d9e8cfe63d0f276e393`.

The redesign and reliability work are implemented and reviewed. The branch is a
development checkpoint, not a verified stable distribution release. Alpha 1
assets are unchanged; the newer UX preview is described below. Seven-language
read-speech results are measured on the host;
ARM phone performance and full application acceptance remain open.

## Current delivery step · UX Alpha 4

The Alpha 3 feedback required further UI work. Clean `a3038fa` now passes the
complete local QA: **95 tests, no failures/skips**, eight required real-model
checks, 143.684s; both lints and R8/debug/test builds passed. Source, hashes and
limitations are in [release identity](../releases/ux-alpha-4.json) and
[quality report](quality-report.md). Review dots, padded active text, compact
headers, flat menus, filled editing and centered Save labels are implemented.
Processing now has visible wave motion. A new test failed against the old subtle
alpha-only motion and passes the candidate; original draft guards remain intact.

Manual 200%/landscape/IME checks preserve edits and show the caret above the
keyboard after its transition. Astra independently reviewed the actual screens.
The private evidence is `build/local-diagnostics/ux-alpha-4-20261104/`; the exact
verified APKs are in `verified-apks/`. No new models were fetched. Initial
incremental release Kotlin compilation failed; a bounded nonincremental Kotlin
rebuild and then full QA passed without source changes or repository cleaning.

The [Alpha 4 testing APK](https://github.com/andreizpgh/Focal/releases/download/ux-alpha-4/Focal.apk)
is published with remotely verified asset sizes/digests. Delivery is tracked in
[PR #5](https://github.com/andreizpgh/Focal/pull/5). Older assets are unchanged.
UI owner acceptance, all-seven-language speech quality/speed,
noise hallucinations, local correction and physical Pixel 10 validation stay open.

## Previous delivery step · UX Alpha 3

The owner's Alpha 2 screenshots led to a focused UI update: an upward language
picker beside Record, rounded note actions, wider timestamp gutter, better editor
padding and a larger playback thumb. Review remains a recognizer hint, not a
language checker. The filter now says so and no longer calls unflagged text
“All checked”. Original audio, manual edits, recovery and speech thresholds stay
unchanged. Explicit Focal dark theme now also applies to editor/rename windows.

Clean source `ed9d7cb8250eb8f7ad454fad47c5f70bf7b9a8de`, version
`0.1.0-alpha.3` / code 3, passed **94 Android tests without failures or skips**,
including eight real-model checks, in 149.326 seconds. The full runner passed
both lints and debug/test/unsigned release/R8 builds; 36 core tests were cached.
Report `20261104T121600.005709Z`; verified APKs are privately archived under
`build/local-diagnostics/ux-alpha-3-20261104/verified-apks/`. The development
certificate matches Alpha 2. See [release identity](../releases/ux-alpha-3.json)
and [quality report](quality-report.md) for exact checks and limitations.

The original editor regression remains fixed. A newly observed light system bar
inside the dark editor failed its test on the preceding candidate; the same test
APK passed after the dialog-window fix. Manual screenshots confirmed the color
change. Light/dark, large font, actual landscape/IME editing and playback controls
were checked with synthetic notes. The owner's screenshots remain private.

The owner uses a **Pixel 10** and will connect it later while present. Deleted
example notes are not available; do not ask for them again. Ordinary developer
notes with English terms such as HTML/API/guardrails are a required scenario.
Phone absence does not block local work. Main includes Alpha 3 through merged
[PR #3](https://github.com/andreizpgh/Focal/pull/3), merge `1b58198`. The
[published testing APK](https://github.com/andreizpgh/Focal/releases/download/ux-alpha-3/Focal.apk)
has verified remote size/digest and retains the Alpha 2 certificate. Subsequent
changes use a new PR; do not overwrite any published assets or claim final acceptance.

A bounded vocabulary buffer reduced host model-validation time, with unchanged
malformed-input/cancellation decisions and real-model acceptance. This is not
ASR decoding or phone speed. A six-pair synthetic RU/English glossary experiment
failed its guards; no prompt was shipped or other-language follow-up run.
The owner authorized a **5 GB cumulative download ceiling**, retaining prior
reservations: **2,749,583,598 / 5,000,000,000 bytes**, leaving **2,250,416,402**.
Pinned Qwen3-0.6B and Qwen3.5-2B Q4 files are local and verified; do not fetch
again. Each completed the same 21 source-only DEV/synthetic requests. The first
returned 16 abstentions and five invalid replies; the second returned 20
abstentions and one capped reply. Neither produced a valid edit, so neither
has complete paired WER/CER or qualifies for integration. There was one extra
2B generation caused by a wrong preflight debug key; it remains separate from
the matched 21. No outcomes were used to change the frozen requests.

A later audit found that the sorted schema requires `replacement` before
`source`. The separate 16-call synthetic diagnostic completed: both orders had
five abstentions and three invalid replies, with zero valid repairs. Three
parseable edit objects in each arm confirmed the requested order. Reordering
alone did not improve yield on these eight canaries. Do not repeat the original
21 or fund another larger LLM from these results. A future full-text reply plus
bounded minimal-diff adapter would be a new hypothesis, not a reinterpretation
of these failures; semantic accuracy and phone costs would still need evidence.
See the [research record](stt-quality-experiments.md#local-correction-shadow-screen--2026-11-04)
and linked aggregate JSON files. Preserve every completed result and
reuse the weights. Automatic correction remains off. The 42 fresh held-out clips
remain unopened; used test data is not new validation.

The private directory `build/stt/correction-shadow-20261104/` retains the original
`DISCOVERY.md`, the separate `QWEN35-COMPARISON.md`, exact runtime receipts and
`CANDIDATES.md`. Original pre-download metadata stays immutable. The
Whisper small warm canary under `build/stt/whisper-small-warm-20261104/` completed
only eight PT controls and was rejected: noise insertions fell 247→4 but a quiet
negation changed from correct to wrong. No further sessions ran. The pinned
190,085,487-byte file is now local; do not fetch again. The run retained four
decoder threads with CPUs 0–1 affinity (one physical core) and cannot support a
speed comparison. Private `first-pt-controls/status.json` binds its result.

### Previous UX Alpha 2 delivery

Alpha 2 source `fe70758350a114d5e66775ee114cd48778217594` passed 89 tests,
including eight real-model checks, in 154.329 seconds. It added recovery for
unpublished audio tails and notification-denial guidance. Its exact APK and
report remain archived and its public assets are unchanged. The owner installed
and reviewed this version; use Alpha 4 for the next interface feedback cycle.

### Previous verified checkpoint

The Whisper ownership fix is enabled in ordinary Android builds. Clean `036d716`
passed all 87 Android tests, including the eight required model checks, in 149.980
instrumentation seconds with zero failures or skips. The build phase passed in
56 seconds, with both lint variants, unsigned release/R8 and 36 cached core tests.
The exact debug, test and unsigned release APKs are archived locally under
`build/local-diagnostics/post-ime-qa-20261104/verified-apks/`. The final manual pass covered short capture/pause/Home/resume/Finish,
playback/seek and 200% font Rename rotation/save. Settings were restored and the
dedicated AVD was stopped. [Full QA evidence and APK hashes](quality-report.md)

Follow-up review verified incremental native input checks (`9ffa3d1`, six
expected CMake regression failures and 157 passing host tests) and service
cancellation before the native abort (`057daca`, matched RED/GREEN on the
pending-chunk commit boundary). The clean 86-test run at `057daca` retained one
failure in the recovery fixture. Waiting for the actual claim release in
`f3e05f4` passed the two focused service tests in 2.915 seconds. Its marker
observed a claim after visible teardown. The original run did not record the
second command's branch.

The `f359fd5` ViewModel fix reports busy when the same recovery claim is still
held. The same test APK failed against the earlier app and passed against the
fixed app. The fixture confirms feedback, PCM and chunk preservation and fresh
claim admission after release. The physical tap window was not exercised.

The `f359fd5` full run passed 86 of 87 tests, including all eight model checks,
in 154.225 instrumentation seconds. Active search retained the query but showed
caret 14 instead of 22 after Back. A separate diagnostic passed while observing
14 after tap and 22 after explicit selection, at the keyboard check and after
Back. Its extra reads and logs leave the failed run's event ordering unknown.
`036d716` waits for the keyboard before setting the caret and verifies the caret
before Back. Later assertions are unchanged. Three focused UI tests passed in
12.177 seconds on unchanged app APK `b68946e9…ae647` with test APK `b25ff568…bd76`.

The earlier exact APK remains archived; use UX Alpha 4 above for the owner preview.
The owner permits focused
GPT-6.1 Sol xhigh and Terra reviewers, with root retaining integration and acceptance.

## Product direction

The owner's 2026-11-04 clarification keeps this a practical note-taking tool.
Recording after Home and with the screen off is core UX; it is implemented but
still needs real-phone audio-continuity/OEM checks. The
[primary-source STT review](stt-quality-experiments.md#practical-direction-after-primary-source-review)
supports retaining the existing model/full context and workflow. Defer the narrow
terminal-only recovery filter and its proposed 56-pair run: the storage/retry UI
cost is disproportionate to its scope. Keep streaming UI out of scope. The later
owner feedback authorizes bounded local STT-model and language-correction
comparisons, starting with the prepared source-only shadow screen. Require paired
benefit before changing the app; the seven-language gates remain open.

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

The pre-ownership checkpoint `101a153` includes search caret return and prior
state/layout work, scrollable Rename in `d8df58e`, the prior `129ee02`/`6872bb4` title fixes,
`ff8ea8d` permission feedback, `a5e460d` constant-PCM protection and
`12e958c` editor feedback. Host dependency setup was `e0a9c78`.
The owner's dedicated KVM-accelerated API 35 x86_64 AVD passed **85 Android tests,
zero failures/skips**, all eight mandatory real-model checks, 36 core tests,
debug/release lint and debug/test/unsigned release builds at that checkpoint.
The historical Python result at `b59c6ee` was 146 tests in the hash-pinned NumPy
environment. The [quality report](quality-report.md) tracks later verification.
The [emulator evidence](benchmarks/2026-11-03-local/emulator-qa.json) retains
the exact early hashes and dirty-source fingerprints.

The new caret test uses one unchanged test APK for fresh RED on a source-matched
`b59c6ee` rebuild and GREEN on the fix: caret 6 previously became 13 after opening
a result and returning. Exact query, hidden IME and unfocused state also pass.
At `101a153`, all 23 earlier InterfaceTest bodies were unchanged. The earlier
draft expectation that highlighted selection survives Compose deselection was
invalid and is retained separately. The controlled saveable `TextFieldValue`
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
it is not wired into Android. No SOT speech-policy change is enabled and the submodule remains unchanged.
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

The current shared ceiling is **5,000,000,000 bytes including retries**, raised
by the owner on 2026-11-04 for removable local research artifacts. Reservation:
**2,749,583,598 bytes**, including the existing 61,000,000-byte future CI reserve;
remaining **2,250,416,402 bytes**. The old reservation prefix is preserved.
The additional reservations include two Qwen Q4 files, one local Whisper small
transfer and bounded metadata. All three local model files are verified.
Do not reset the ledger or repeatedly fetch existing models. The 42 fresh
held-out clips remain closed.
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

Use this checkout/branch, synchronized with `main`. PR #1 records the UX Alpha 2
integration; subsequent changes need a new PR. Initialize the recorded submodule.
Read `AGENTS.md`, the quality/benchmark reports and
[the local QA guide](local-emulator-qa.md). Keep public history focused and scan
full history for secrets before pushing; never publish raw recordings/transcripts,
model weights, local configuration or signing material.

The local QA runner requires an explicit dedicated emulator serial, verifies the
model actually installed in app storage, checks all expected tests and all eight
real-model checks, fails on skips, restores animation settings and saves private
reports. It never uninstalls or wipes app data. Its host tests/source review are
not substitutes for running it on the owner's accelerated emulator.

## Ownership evidence and remaining gates

The ownership fix covers the allocation-error defects found in the pinned engine.
Pinned `whisper_full_with_state` can free its borrowed state on
self-KV growth failure (`-7`); normal JNI/service cleanup then frees it again.
Uninitialized early `batch` cleanup and partial KV allocation are related defects.
An isolated ownership-only patch now passed all 24 frozen model-free cases;
baseline four controls passed and 20 expected errors were reproduced, including
two ASan use-after-free reports on owned/external failure → close. Independent
review checked 664 source inputs, 94 tool/link inputs and all 48 execution logs.
These model-free checks use the actual engine and CPU GGML with synthetic
descriptors and graph bypasses. Preserve the v4 freeze and the failed
v3 link attempt separately. The later real-graph and Android checks extend the
evidence. See the local benchmark report for scope and hashes.

The [ownership-only CMake overlay](whisper-lifetime-research.md) reproduces the
exact reviewed `8cc010e3…ed64b` postimage. It was initially inactive; after the
checks below, ordinary Android builds now include it without the fault helper.
At the earlier inactive-overlay checkpoint, nine tests covered 19 configuration
cases and the full host suite passed 155 tests without skips (7.478 s). No extra
Android run was performed for that inactive hook. The later incremental-input
checks raise the current host total to 157, as recorded above.
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

The later matrix matched 23 processes before an auto retry hit its CPU limit;
a separate continuation completed the seven remaining processes in 326.010 s.
Independent postrun review confirms 30/30 unique pairs: patched 15 pass, baseline
five controls pass and ten expected regressions reproduce. Exact canonical parity
covers same-state beam/greedy/auto retries. Both resource-limit failures stay
failed; this is composite coverage, not a single clean run. Do not rerun these
completed host cases without a new source change or finding.

The Android follow-up is implemented as explicit opt-in debug variants with
separate test sources. The baseline reproduced native -7 followed by a fatal
caller close. The patched artifact passed both strict recovery/cancellation tests
in 173.937 s, preserving original audio, reviewed edits and pending chunks, then
recovering with the ordinary factory/new engine. Cancel/close keeps the active
owner until return, rejects partial output and frees it once. The helper observer
holds no extra owner. A first cancellation test failed its 30 s arrival wait;
a reviewed 120 s wait reached the checkpoint after 34.520 s, while the barrier
hold remains 30 s and completion/cleanup remain 45 s. The failed attempt is retained.

Ordinary builds include the ownership fix without changing STT policy. Use the
final normal QA and artifact records for delivery. Keep the opt-in fault APKs,
exact local test artifacts and raw logs private. Paired speech accuracy and
speed across all seven languages remain open. The owner will connect a phone
later for physical-device validation.

1. Address weak-noise hallucinations and quiet-word loss in ordinary notes with
   one justified, frozen candidate. Complete quiet intervals scored 84/490 errors
   versus gated 172/490; bypassing the gate inserted 87 Russian words on 30 seconds
   of seeded noise. These findings do not justify a broad amplitude cutoff or
   unguarded bypass. Use existing DEV evidence for early rejection on names,
   numbers, negation and phrase omissions; open fresh held-out only after a
   candidate passes development gates. Do not repeat rejected SOT/beam/context
   variants or the deferred terminal-only screen. Keep the actual recovery
   failure and seven-language WER/CER improvement open until verified.
2. Inspect avoidable runtime cost without changing decoder inputs or call history.
   A proposed lazy model load could avoid work for sessions with no chunks, but
   moves cold-load latency and model errors to the first passage. It is not an
   accepted optimization. Require matched measurements, including first-text
   latency, before changing scheduling; host results are not phone results.
3. With the owner present, validate midrange and older ARM64 phones, including a
   4 GB device and current
   Android foreground rules: screen-off recording, calls, recovery, TalkBack,
   cold/warm latency, memory, battery and thermals. Emulator timing cannot establish
   these phone claims. While waiting for the phone, continue justified local work;
   do not repeat completed QA or expand the product to fill the waiting time.
4. Run the approved bounded local model comparisons; keep automatic correction
   and production LLM integration disabled until their gates pass. Any correction
   still requires the existing immutable original
   ASR/audio, manual-edit protection, provenance and Undo requirements still apply.
5. Re-review changed layers, run required build/tests and applicable regressions,
   then verify the exact bundled, stably signed release artifact before publication.
   Do not replace existing release assets or call the product finished while these
   gates remain unresolved.

Use evidence to justify larger refactors or model changes. Keep inference and
correction off the capture thread, memory and queues bounded, and the original
audio durable throughout the work.

For a self-contained starting instruction, use
[`next-session-prompt.md`](next-session-prompt.md).
