# Local transcript-quality comparison · 2026-11-05

Exploratory CPU measurements, not phone acceptance. Automatic correction remains
disabled. Source audio and recognizer output are retained privately; this report
contains aggregate results only. The later held-out Android comparison is linked below.

## Current Android Ultra evaluation

On 42 frozen held-out FLEURS clips, the same PCM produced Base host → Android
Ultra word errors **184 → 51 / 961** and lexical character errors **369 → 94 /
5,010**. Each of the seven language aggregates improved on both measures. An
independent audit checked the raw outputs, source identities and scoring. This
was a text-based comparison on a staged research build. The
[bundled-app verification](../../quality-report.md#alpha-8-bundled-ultra--final-emulator-verification)
records the separate emulator integration and minified smoke. Physical-phone
validation remains open. The earlier DEV long-Spanish seam failure and the
targeted follow-up are both retained in the [Android evaluation](ultra-android-report.md).

## Gemma 4 E2B audio · correction of the stronger Ultra draft

A separate frozen diagnostic used Ultra drafts for 18 correction calls; direct
ASR was not repeated. The correction instruction, audio, runtime, model and
decoding limits were unchanged, and no reference text or error locations were
supplied. Unlike the earlier screen's stop-on-output-failure rule, this diagnostic
collected all returned messages. All 18 responses were structurally valid, but
two were invalid transcripts of noise. This is coverage completion, not a quality
pass. [Audited aggregate](gemma4-ultra-correction.json).

| Condition | Ultra | Gemma + Ultra draft |
| --- | ---: | ---: |
| Normal word errors / 210 | 15 | 14 |
| Attenuated word errors / 210 | 14 | 14 |
| Normal lexical character errors / 1,050 | 33 | 40 |
| Attenuated lexical character errors / 1,050 | 31 | 38 |
| Two noise cases, inserted words | 0 | 30 |

Russian word errors improve 4 → 2 and 3 → 2. Italian word errors rise 0 → 1
in both conditions because a digit is expanded into a word for the same
quantity; that is a reference-format mismatch, not demonstrated semantic damage.
Spanish totals tie while individual edits include both a repaired plural and a
new pronoun mismatch. The Portuguese quantity error remains. The short Portuguese
negative returns to its selected language; the French one remains in English.
There are only seven unique ordinary source recordings, each used at two gains.

On the two empty Ultra noise drafts, Gemma invents text: one response wrongly
claims audio is unavailable, the other supplies a quotation. Both are retained
in the raw-output score. Skipping empty drafts could avoid these calls, but that
unexecuted rule is not a measured correction gain on speech. The earlier
Base-draft screen's invalid response and unrun call remain separate failures.

The added resident-model call takes a median 9.86 host seconds across these
18 inputs; the bounded service took 183.04 s, reported 1.3G peak memory and no
swap. This is not Pixel 10 latency. The small lexical gain, mixed edit quality
and extra cost do not establish a benefit sufficient to enable automatic edits.
The next experiment at that checkpoint was Ultra in the Android audio path,
without a mandatory LLM stage. Keep the cached Gemma artifact and evidence;
this result does not reject every local correction model or future approach.

## Gemma 4 E2B audio · Base-draft screen remains incomplete

One ordinary LiteRT E2B bundle and the API runtime were downloaded and verified.
The first smoke stopped before inference because the harness had not created its
cache directory. A separately preserved attempt corrected only that setup and
completed one smoke, then four pilot calls. No weights were downloaded again;
completed assay calls were not repeated. [Audited aggregate](gemma4-audio-screen.json).

The fixed assay returned **35 of 36** planned responses: **34** met its text-output
contract, **one** returned an unsupported reasoning channel with no visible
transcript, and **one** was not run after the predeclared stop. All fourteen
ordinary/attenuated speech cases completed in both arms. Direct transcription
and correction of the current Base draft both receive audio and explicit
language, without references or hinted error positions. Their results are
separate; this is not a test of correction on Ultra's stronger draft.

| Condition | Base | Small | Ultra | Gemma direct | Gemma + Base draft |
| --- | ---: | ---: | ---: | ---: | ---: |
| Normal word errors / 210 | 25 | 18 | 15 | 15 | 16 |
| Attenuated word errors / 210 | 31 | 19 | 14 | 18 | 20 |
| Two noise cases, inserted words | 12 | 4 | 0 | 1 | 12 |

Correction reduces WER versus Base in 11/14 speech passages and ties in three,
but that does not mean every edit is right: reference-based review found new
mismatches inside some improved passages. Gemma direct has 58/64 lexical
character errors on the normal/quiet groups versus Base 47/63; correction gives
45/60. Thus WER alone obscures some tradeoffs. Noise correction preserves the
Base hallucinations. The French direct short answer is empty; correction keeps
its negative word. Portuguese correction returns the unsupported channel and
its direct counterpart is explicitly unrun. Invalid output is not accepted as
an empty, successful transcript, and the failed screen is not reclassified.

One model stays resident within each stage, with fresh conversations, two text
and two audio CPU threads, 2048 total-context and 256 response tokens, thinking
requested off, and hard external deadlines. The invalid response still produced
reasoning; this setting is not a sufficient output contract. The API exposes
no independently checked finish reason: counters detect observed caps but do
not prove EOS. Raw text, including any unwanted explanation, is scored without
cleanup. Host timing and memory do not establish Pixel 10 audio performance.
Automatic correction remains disabled. The separate audio-plus-Ultra diagnostic
above tests added value against the stronger recognizer.

## Parakeet Ultra · completed DEV42 comparison

The same 42 consumed DEV clips now have results for Base, original Parakeet v3
and Ultra. Seven exact Ultra outputs were reused from the 18-input diagnostic;
only 35 new calls were needed. This is a composite quality comparison, with
no new held-out input. Independent audit verified all 42 PCM bindings and 126
raw outputs. [Aggregate and provenance](parakeet-ultra-dev-42.json).

| Language | Reference words | Base errors | v3 errors | Ultra errors |
| --- | ---: | ---: | ---: | ---: |
| English | 88 | 11 | 3 | 5 |
| Russian | 105 | 31 | 19 | 13 |
| German | 122 | 13 | 5 | 3 |
| French | 128 | 32 | 13 | 14 |
| Spanish | 177 | 15 | 44 | 4 |
| Italian | 126 | 31 | 7 | 3 |
| Portuguese | 118 | 13 | 7 | 9 |
| **Total** | **864** | **146** | **98** | **51** |

Ultra improves WER and lexical CER against Base in every language in this DEV
sample. Overall WER is **16.90% → 5.90%**; character errors **262 → 107 / 4,349**.
Against v3, English/French/Portuguese word errors rise; the Spanish outlier
accounts for much of the aggregate improvement. The earlier Portuguese clip
with a merged numerical quantity remains a real regression within the better
language aggregate. These are six sources per language, not population estimates.

The separate [18-input diagnostic](parakeet-ultra-dev-18.json) also covers quiet
versions and controls: normal Base/Small/Ultra errors 25/18/15, quiet 31/19/14,
and two varying-noise inputs 12/4/0 insertions. Both short synthetic negatives
become an English negative under Ultra: selected-language fidelity fails, but
these text outputs do not establish a reversal of meaning. Those controls and
the Portuguese error are retained, not outweighed into an unconditional pass.

Ultra uses automatic language selection; Base and Small use explicit language.
All receive complete PCM here, without the app gate. The 35 fresh Ultra calls
took 164.78 s, median model load 2.77 s and decode 1.47 s, maximum process RSS
1.26 GB under CPU150%/4GiB/no-swap limits. These are candidate-only host costs,
not paired speed gains or Android timing. The next application experiment must
verify framing, noise, long boundaries and resource use before changing the
default. The 42 untouched held-out clips remain reserved for a frozen pipeline.

## Ultra technical-term canaries · synthetic only

Five existing eSpeak fixtures test one absent-term phrase plus two RU+EN
scripts at normal and attenuated gain. No new speech was generated or downloaded.
Exact target hits, out of eight, were Base **1**, v3 **2**, Ultra **4**. Ultra's
four hits were HTML in all four positive fixtures; API and guardrails each
remained 0/2. All three avoided target-word insertion in the absent-term control.
Ultra retained the expected number token at both gains; none matched the
expected Russian negation markers. These are lexical counts, not acoustic
adjudication of meaning. [Audited aggregate](parakeet-ultra-mixed-5.json).

Overall word errors fell 43 → 31 / 36 versus Base, but surrounding lexical
character errors on the four positive fixtures rose 83 → 86 / 122. The poor
surrounding transcription and artificial language joins limit what this screen
can establish. It does not resolve natural HTML/API/guardrails dictation.
Five fresh Ultra calls took 18.99 s; no matched timing or phone claim is made.

## Small auto-context · complete full-PCM diagnostic

A fixed diagnostic now fills the previously missing ordinary-speech check for
cached Small Q5_1. Eighteen new candidate calls use the exact PCM and scoring
from completed Base comparisons: seven normal DEV sources, their gain-0.1
versions, two varying-noise controls and two synthetic short negatives. The
normal/quiet versions share sources; none is fresh held-out evidence.
[Audited aggregate](small-auto-diagnostic.json).

Normal word errors fell **25 → 18 / 210**, attenuated errors **31 → 19 / 210**;
lexical character errors fell **47 → 35 / 1,050** and **63 → 38 / 1,050**.
Normal English word errors rose 2 → 3 while character errors fell 5 → 2;
Portuguese ordinary speech tied. Noise insertions fell 12 → 4 but did not vanish.
A short French negative that Base recognized correctly became an unrelated word;
the Portuguese short negative improved. No control was dropped or retried.

Base uses full encoder context; Small uses the frozen historical auto-context
formula. This compares configurations rather than isolating model size. Both
use explicit language and complete source PCM, without gate/VAD. Raw and emitted
text agree for every result. Candidate cold calls took 232.07 s with maximum
process RSS 450.6 MB under the bounded host job; cached Base timings are not a
paired speed baseline. Independent audit verified all 18 PCM/result chains and
recomputed both forms of the scores. Prior Small failures remain unchanged.
This supports further accuracy work, not default-model promotion or a phone claim.

## Qwen3-ASR 0.6B INT8: not promoted

The frozen screen compares the existing Whisper base Q5_1 profile against
[the pinned sherpa-onnx export](https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/tree/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a).
Both receive the same audio and explicit speech language, with two CPU threads.
Model decoding receives no reference text. Synthetic, quiet, noise and ordinary
read-speech results are kept separate. [Aggregate data](qwen-asr-screen.json).

A host reboot interrupted the first attempt. Two complete pairs and one candidate
output survived verification; only missing outputs were recomputed. The separate
continuation preserves the failed attempt and binds its reused files. This is a
composite result, not one uninterrupted run. No evidence establishes the reboot's
cause. Subsequent jobs have a 4 GiB cgroup memory ceiling, 3 GiB memory pressure
threshold, no swap, CPU quota 150%, two decoder threads and lower priority.

Fifteen pairs completed. On the sixteenth input—two seconds of synthetic noise—
Qwen repeated a paragraph until its 128-token output limit. The run stopped as
planned, leaving five mixed-term cases unexecuted. Acoustic frame/token counts
matched the input; the failure was unfinished output generation, not demonstrated
input truncation. It is not counted as a valid pair.

| Ordinary DEV language | Reference words | Whisper word errors | Qwen word errors |
| --- | ---: | ---: | ---: |
| English | 10 | 8 | 6 |
| Russian | 14 | 3 | 1 |
| German | 31 | 3 | 4 |
| French | 23 | 5 | 4 |
| Spanish | 30 | 2 | 2 |
| Italian | 15 | 2 | 0 |
| Portuguese | 21 | 5 | 4 |

Across these seven clips, word errors fell **28 → 21 / 144 words** (WER
19.44% → 14.58%), and lexical character errors fell **66 → 48 / 729 characters**
(CER 9.05% → 6.58%). One clip per language cannot establish general accuracy.

The first two completed varying-noise cases improved from 12 to 2 inserted
words. The failed sixteenth output adds **73** raw inserted words versus **5**
for its pinned Whisper baseline: all three observed varying-noise inputs therefore
give **17 → 75** raw insertions. This descriptive total includes the capped,
invalid output; it must not be hidden by reporting only the successful prefix.
Exact-zero audio separately gave 1 → 9 words in the host probes. Production Focal
already skips exact-constant PCM, so this is not an app regression measurement.

All four quiet French/Portuguese controls lacked the expected negation marker
under Qwen; the short French control specifically lost a marker Whisper had
recognized correctly. The late-phrase control also missed its final words.
These failures prevent promotion despite the ordinary-speech gain. Mixed-term
improvement and phone resources remain unproven. The production backend is unchanged.

Cold Qwen loads took 4.16–4.50 seconds for the seven ordinary clips; decoding took
2.21–8.02 seconds. Total process wall time was 6.97–12.86 seconds versus
6.02–6.55 seconds for Whisper, under the resumed host limits. These measurements
include process/model loading, not a resident Android engine or phone performance.

## Full-text local correction: some utility, not ready for automatic edits

The existing local Qwen3.5-2B Q4_K_M was given one fixed instruction to return
`corrected_text`, without answer choices, reference text or preselected error
positions. This is a new interface experiment; earlier failed span-schema runs
remain failed. Fourteen new synthetic controls and seven mechanically selected,
already-consumed DEV excerpts were frozen before generation. No weights were
downloaded again. [Aggregate data](correction-text-screen.json).

The first attempt stopped during render-only preflight: the server acknowledged
unload before its next status response became empty. It generated no answers.
A separately retained attempt waited up to ten seconds for unload and completed
all 21 requests. Responses and progress receipts were synchronized to disk.

Of seven synthetic spelling errors, the model proposed five bounded edits:
four exactly repaired the error, one remained misspelled, and two were left
unchanged. Synthetic accepted-edit precision was **4/5**, recall **4/7**.
These are format/utility canaries, not speech-accuracy estimates. Of seven clean
controls, four were copied exactly and three acquired an unwanted final quote;
the strict diff filter blocked all three. They are punctuation failures, not
demonstrated changes of meaning. A fourth extra-quote response on a Russian DEV
excerpt was likewise blocked. No clean control was changed by the adapter.

The seven DEV excerpts contained 159 reference words. One accepted Italian word
repair matched its reference; word errors fell **44 → 43**, lexical character
errors **137 → 136 / 812 characters**. Blocked and unchanged outputs remain in
these denominators through the original-text fallback. No audio adjudication was
performed, and the Russian name/number errors were not repaired. Do not describe
these reference-only results as verified acoustic corrections.

The first call took 9.705 seconds; the following resident calls had median
9.298 seconds and range 7.457–17.229 seconds on the constrained host. Model load
for the first call was 1.794 seconds, with a warm OS cache. Ollama's reported
model allocation of 2,273,960,192 bytes is not a measured peak RSS/PSS or a phone
memory budget. This small improvement, remaining invalid repair and per-passage
cost do not justify enabling automatic edits. The experiment supports further
bounded investigation of local correction, not rejection of the idea itself.

## Parakeet TDT v3 INT8: wider development comparison warranted

All 21 cases completed with the
[pinned export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78),
the existing sherpa-onnx runtime and verified cached Whisper results. Only the
four new model/tokenizer files were downloaded, totaling 670,478,772 bytes.
[Aggregate data](parakeet-screen.json). Inputs and scorer are unchanged; the
candidate receives no reference, vocabulary hint or selected-language prompt.
[Parakeet selects its language automatically](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
which differs from the explicitly conditioned baseline. Its Portuguese training
coverage is European, while this ordinary Portuguese clip is Brazilian.

The mixed-term controls use two Russian eSpeak templates with English terms
synthesized in an `en-gb` voice, joined to the Russian `ru` voice with fixed
80 ms pauses; quiet variants scale the same samples to 0.1 gain. The quiet
French and Portuguese controls likewise use eSpeak voices with 0.1 gain, not
recordings of people speaking quietly. Each pair uses immutable matched input,
but intelligibility and the outputs have not been adjudicated by listening.
Failures are real for these fixtures; the stitched voices and pronunciation
do not establish performance on natural human code switching or phone dictation.
The model is not generally disproved; all-language and physical-phone acceptance
remain open.

Ordinary word errors fell **28 → 16 / 144** (WER 19.44% → 11.11%); lexical
character errors fell **66 → 39 / 729**. Per-language word errors were EN 8→2,
RU 3→1, DE 3→0, FR 5→4, ES 2→2, IT 2→1, PT 5→6. Portuguese worsened slightly;
Italian character errors also increased 2→3. All three varying-noise outputs
were empty: **17 → 0** inserted words. Exact-zero output was also empty.
This small screen earns a wider development comparison, not a backend switch.

Mixed-term recognition is still inadequate. Four positive synthetic cases contain
eight target occurrences: exact matches rose only **1/8 → 2/8**. Two HTML matches
were gained and one correct HTML was lost. Neither model recognized API or
guardrails exactly. Surrounding word errors fell 29→23 but character errors rose
83→97. The number cases lost their action, number and guardrails, retaining only
sentence endings. Net term gain is not evidence that the owner's mixed-language
note-taking scenario works.

Two short quiet negatives changed from their Portuguese/French forms to `No.`:
this is a wrong-language/form result, not proof of a positive answer or deleted
negative meaning. Longer quiet phrases remain severely corrupted. Their four
expected-language marker flags must not be reported as four new semantic inversions.
No acoustic adjudication or physical-phone check has been performed.

For the seven ordinary clips, cold model loading took 2.640–2.766 seconds,
decoding 0.651–2.215 seconds, and per-process peak RSS was 1,065–1,137 MiB.
These are constrained-host observations; cached Whisper timing does not establish
a matched speed comparison.

## Parakeet: 42-clip development comparison

The wider comparison completed on 42 previously used DEV read-speech clips,
six per language. Seven quality pairs were reused from the screen above; 35
fresh pairs ran in alternating AB/BA order. The inputs were complete WAVs,
without Focal's live gate. Whisper used the selected language; Parakeet chose
its language automatically. The separate 42 held-out clips remain unopened.
[Aggregate data and source hashes](parakeet-dev.json). Independent review
verified all 42 raw-result hashes and reproduced the scores.

| Language | Reference words | Word errors: Whisper → Parakeet | Reference lexical characters | Character errors: Whisper → Parakeet |
| --- | ---: | ---: | ---: | ---: |
| English | 88 | 11 → 3 | 428 | 32 → 9 |
| Russian | 105 | 31 → 19 | 587 | 48 → 43 |
| German | 122 | 13 → 5 | 736 | 17 → 6 |
| French | 128 | 32 → 13 | 575 | 68 → 21 |
| Spanish | 177 | 15 → 44 | 853 | 15 → 101 |
| Italian | 126 | 31 → 7 | 616 | 46 → 19 |
| Portuguese | 118 | 13 → 7 | 554 | 36 → 30 |
| **Total** | **864** | **146 → 98** | **4,349** | **262 → 229** |

The aggregate WER was **16.90% → 11.34%** and lexical CER **6.02% → 5.27%**.
Word and character errors fell in six languages; Spanish worsened. One Spanish
clip produced 41 word errors versus four for Whisper, with Spanish and English
mixed in the candidate output; character errors rose 3 → 97. The other five
Spanish clips improved from 11 → 3 word errors. All six remain in the totals.
This is a consequential result for that clip, not an estimate of its frequency;
no acoustic adjudication was performed. The
previous quiet and synthetic mixed-term concerns remain unresolved. These small,
read-speech development results do not establish general seven-language accuracy.

Only the 35 fresh paired cold processes (18 AB, 17 BA) enter matched host timing medians:
Whisper → Parakeet load **0.085 → 2.664 s**, decode **6.255 → 1.599 s**,
process wall **6.424 → 4.720 s**, and median per-process peak RSS approximately
**181 → 1,106 MiB**. The Parakeet decode phase includes stream setup and audio
acceptance; OS caches could be warm. These are aggregate constrained-host
measurements, not warm-resident, per-language or phone speed claims.

The Python configuration requests 80 feature bins, but native initialization
uses the model metadata: 128 bins and per-feature normalization. The wrapper's
configuration string describes the request, not the effective native frontend.

## Parakeet: gated development pipeline replay

The completed host replay used the existing Kotlin gate and transcript
reconciliation on the same frozen ranges: 129 speech chunks and two activated
noise chunks across normal and attenuated versions of the 42 DEV clips. The
Whisper results are the earlier four-thread baseline with trusted context;
Parakeet uses automatic language selection. These are paired quality results,
not matched timing or an Android run. [Aggregate data](parakeet-pipeline.json).
Independent Astra review verified all 131 raw outputs and the Kotlin replay.

| Condition | Reference words | Word errors: Whisper → Parakeet | Reference lexical characters | Character errors: Whisper → Parakeet |
| --- | ---: | ---: | ---: | ---: |
| Normal | 864 | 223 → 102 | 4,349 | 598 → 321 |
| Attenuated | 864 | 256 → 181 | 4,349 | 822 → 730 |

Normal word errors improved in all seven languages; Portuguese character errors
rose 25 → 26. For attenuated input, German worsened 42 → 44 word errors and
Portuguese character errors rose 33 → 46. The gate provided no speech-overlap
audio for two English clips at normal gain and four at attenuated gain; those
reference words remain in the scores. Neither backend can recover audio omitted
before decoding. Quiet full-WAV results at the same gain were not measured.
The earlier Spanish full-WAV translation was not reproduced on these frozen
ranges; this does not establish that the failure is fixed. No speech-overlap
boundaries occurred, so this run does not validate overlap reconciliation.

Candidate-only constrained-host medians were 2.660 s for 15 note loads,
1.168 s for 15 first decodes, and 0.874 s for 116 resident decodes. Peak
process RSS was 1,279,733,760 bytes. These timings have no matched historical
Whisper comparison and do not establish phone performance.

## Parakeet: isolated Android deployment check

The opt-in instrumentation prototype passed one test with three inputs in
25.844 seconds on the dedicated API 35 x86_64 KVM emulator. It loaded the pinned
Sherpa 2.0.1 / ONNX Runtime 1.28.2 runtime and verified all four cached model
files. The public 11-second English fixture produced substantive speech; both
30-second controls (exact zero and seeded varying noise) produced empty output.
[Portable result and exact APK identities](parakeet-android.json);
[reproduction and limitations](../../parakeet-android-probe.md).

Model loading took 1.874 s; decode times were 3.220 s for speech, 9.285 s for
zeros and 9.376 s for varying noise. Decode excludes stream setup and input
acceptance. The process-lifetime peak RSS reached 1,299,664 KiB; PSS after model
release was 143,406 KiB. These values describe this emulator process, not
incremental model memory or phone performance. Long controls intentionally
bypass the application gate and exact-constant shortcut.

Core tests (cached), debug lint and app/test builds passed in 2m46s. A release
task with the research flag was rejected as intended. Independent APK inspection
confirmed that Sherpa/ORT occur only in the test APK, neither manifest requests
Internet, native libraries have 16 KiB-compatible alignment and production native
fault hooks are absent. This is not a test on a 16 KiB-page physical device.
Native ownership is serial; the suite does not test capture, cancellation,
recovery, overlap or edits. The emulator was stopped after the run.

The application backend remains unchanged and no new APK was released. The unused
held-out cohort and physical-phone quality, latency, memory, battery, thermal and
background gates remain open.

## Gate omission diagnosis: do not lower the global threshold

A read-only waveform replay reproduced all 43 saved Kotlin range receipts:
14 existing speech streams and 29 synthetic controls. The current absolute
threshold is 65.54 PCM16 RMS units. The two completely omitted normal English
clips peak at 67.59 and 53.69; the first never supplies the required two
consecutive voiced frames. Four quiet English clips peak at only 5.34–9.62.
These are digital levels, not a measurement of audibility on the owner's phone.
[Bound inputs, threshold sweep and limits](gate-floor-audit.json).

Halving the absolute threshold to 0.001 only reduces normal omitted clips from
two to one and leaves all four attenuated omissions. Meanwhile, active pure-noise
controls rise from 2/18 to 6/18 and their queued audio grows from 61.48 to 185.40
seconds. Reducing it further to 0.0005 recovers both normal clips but activates
8/18 noise controls. The adaptive floor, debounce and frame boundaries were
unchanged. This fails the proposed simple fix before expensive ASR; no decoder
calls or production changes were made. Independent review reproduced the
aggregates. The broader quiet-speech problem remains open.

## Functional experiment and adoption decision

The [opt-in debug preview](../../saved-audio-preview.md) now runs on saved audio,
preserving the original transcript and manual edits. Fifteen focused Android
tests cover the controller and real menu, rotation, Copy and Home cancellation;
six JVM tests cover bounded processing. The exact-path quality screen below
rejects promotion despite those functional passes. Ordinary APKs retain the
Whisper/manual-language path. No automatic LLM correction passed its gate.

Keep the unused 42 held-out clips closed. The subsequent Android Whisper
comparison contrasted production gate ranges with complete source fragments
without changing models. Its partial speech result and separate noise continuation
are recorded below: full-source bypass was rejected after weak-noise insertions
rose from zero to four. Physical Pixel 10 quality, latency, memory and background
checks remain separate requirements.

## Saved-audio preview: bounded Android DEV screen

The actual opt-in Android preview completed 18 inputs on an API 35 x86_64
emulator: one previously consumed DEV read-speech clip in each of seven
languages at normal gain and 0.1 digital gain, plus four no-speech controls.
Its 16-second windows overlap by 0.5 seconds. Whisper comparisons use the same
PCM, but full-WAV decoding on Linux with explicit language: normal results are
cached, while the seven attenuated results were newly measured. Android preview
uses automatic language and different segmentation. This is a quality screen,
not a matched speed comparison or a phone test.

| Language | Reference words | Normal word errors: Whisper → preview | Attenuated word errors: Whisper → preview | Reference lexical characters | Normal character errors: Whisper → preview | Attenuated character errors: Whisper → preview |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| English | 28 | 2 → 1 | 6 → 1 | 120 | 5 → 4 | 19 → 4 |
| Russian | 26 | 8 → 6 | 8 → 8 | 141 | 13 → 12 | 12 → 15 |
| German | 19 | 1 → 0 | 0 → 0 | 118 | 1 → 0 | 0 → 0 |
| French | 32 | 2 → 0 | 5 → 0 | 160 | 5 → 0 | 9 → 0 |
| Spanish | 59 | 4 → 39 | 4 → 38 | 279 | 3 → 106 | 3 → 101 |
| Italian | 25 | 3 → 1 | 3 → 1 | 141 | 2 → 10 | 2 → 10 |
| Portuguese | 21 | 5 → 6 | 5 → 4 | 91 | 18 → 20 | 18 → 18 |
| **Total** | **210** | **25 → 53** | **31 → 52** | **1,050** | **47 → 152** | **63 → 148** |

The Spanish high-error example remains included; these results do not establish
why the output differs. An Italian overlap also differs from its reference.
Neither was acoustically adjudicated. Four controls yielded blank text; the
zero and DC controls did not open a decoder. Digital attenuation and synthetic
noise do not represent the full range of real recordings. One correlated pair
per language cannot establish population accuracy.

The instrumentation test took 146.391 seconds, with 23 decodes and 16
recognizer opens. Peak process-lifetime RSS was 870.7 MiB; post-process RSS,
after recognizer release, ranged from 101.03 to 134.91 MiB. These are emulator
process measurements, including other process activity; the elapsed time includes
model hashing, loading and receipt I/O. No timing comparison is claimed.

An independent audit passed for the evidence and aggregate. The frozen manifest,
lock, test source and both APK hashes are recorded in the [aggregate result](saved-audio-preview.json).
This screen does not support promoting the alternative backend. The unused
held-out cohort, acoustic review and real-phone quality and resource gates remain
open; automatic correction remains disabled.

## Whisper framing: speech recovery and a noise regression

The existing Android Transcriber was compared on unchanged SpeechGate ranges
and complete original PCM, with the same model, explicit language and one
thread. The 18-case attempt stopped at its shared 120-second case budget during
the Russian full-source arm: only two pairs completed, both the same English
DEV recording at normal and 0.1 gain. Normal word errors fell 26 → 2 / 28 and
quiet errors 28 → 3 / 28; the gate had retained 18.56% and 0% of the audio.
Russian has only a completed gated arm (4 / 26 errors, three calls totaling
110.079s); its full arm did not have a standalone 90-second timeout. The failed
attempt remains incomplete, with no seven-language claim.
[Partial counts and exact artifact identities](whisper-framing-partial.json).

A separate continuation completed both previously unrun varying-noise controls:
30s seeded noise gave 40 → 2 inserted words, but 2s emulator noise gave 0 → 4.
Four Transcriber calls passed in 167.232s instrumentation / 169.149s host wall.
All nonempty results were marked uncertain; this does not remove invented text.
**Automatic full-source bypass is rejected.**
[Noise counts and identities](whisper-framing-noise.json).
Independent Astra review verified inputs, ranges, receipts, binaries and scores.
These are emulator observations, not phone performance or general noise rates.

The first noise attempt failed before inference: its cleanup assumed an
interrupted fixture still had a database owner, but the matching PCM was an
orphan. The continuation verified and preserved that exact file. No production
storage change follows from this harness failure; the disappearance of the row
was not diagnosed. Prior failed results are retained. The AVD is stopped.

## FUTO ACFT: context formulas kept separate

One official multilingual ACFT Base Q8_0 file was fetched once (81,768,602 bytes),
matched the [publisher catalog](https://github.com/futo-org/voice-input/blob/c0bbb05b47f02e1df089bed5bf63736ae1735d1a/shared/src/main/java/org/futo/voiceinput/shared/Models.kt#L190-L194)
SHA-256, and passed the existing Android model-structure validator on the host.
No production backend or model-selection behavior changed.

The first controls-first screen used the research-notebook formula
`min(1500, round(frames / 320) + 8)`. It stopped as planned after three pairs
and six successful calls in 19.993 seconds. Noise insertions fell 8 → 1 and
4 → 2, but a 0.288-second digitally attenuated French negative answer changed
from correct to an unrelated word. Raw and emitted text agreed; filtering did
not cause the error. Ten pairs, including all seven ordinary DEV clips, did
not run. This profile is rejected, without a seven-language verdict.
[Independently audited aggregate](acft-notebook-context.json).

Inspection of the pinned [FUTO Android source](https://github.com/futo-org/voice-input/blob/c0bbb05b47f02e1df089bed5bf63736ae1735d1a/app/src/main/cpp/voiceinput.cpp#L202-L224)
found a different context formula: `max(160, min(1500, ceil(frames / 320) + 32))`.
For the short French input it gives 160 rather than 22. This motivated a
separately frozen comparison on the same controls and DEV inputs, with fresh
AB/BA arms and unchanged rejection rules. It evaluates this context formula
inside Focal's pinned host engine, not FUTO's application: timestamp mode,
engine and padding behavior differ. The first run remains a rejected result.

That separate screen also stopped at its frozen criterion: five pairs / ten
successful calls in 32.900 seconds. Short French remained correct and short
Portuguese improved from a missing word to the expected negative answer. Both
models failed the longer quiet French phrase (11 → 12 word errors / 12 words);
neither retained its expected negative meaning. The rejection therefore does
not establish a newly introduced polarity error against a correct baseline.
Noise insertions remained 8 → 1 and 4 → 2, with nonempty candidate output.
[Independently audited control results](acft-product-context.json).
The measured cgroup peak was 227,577,856 bytes, swap peak zero and no high/max/OOM
events. These are host-job resources, not Android or model-only memory.

Independent review approved a separate diagnostic completion of only the eight
unrun, already frozen pairs: the last Portuguese control and seven ordinary DEV
clips. It preserves their original AB/BA order and all decoding parameters,
without repeating the five completed pairs or changing the rejected screen.
This characterizes the candidate; it cannot turn the failed gate into acceptance.

The diagnostic completion finished all eight pairs / sixteen calls in 66.996
seconds. On the seven ordinary clips, word errors changed **25 → 28 / 210**
(11.90% → 13.33% WER), while lexical character errors changed **47 → 46 / 1,050**.
English word errors were 2→5, Russian 8→8, German 1→3, French 2→2, Spanish 4→4,
Italian 3→1 and Portuguese 5→5. German's additional word errors mainly reflect
a fused word boundary; its lexical character error count did not change.
The remaining Portuguese quiet instruction was closer lexically (12→11/13 word
errors) but both models still lost its negative instruction and date/time details.
[Independently audited diagnostic results](acft-product-remaining.json).

All seven ordinary candidate calls were faster on the constrained host. Their
combined process wall time was **40.929 → 19.880 seconds**, including load and
output; paired ratios ranged from 1.41× to 3.16×. Each arm used a fresh process,
but filesystem caches were not controlled. Weights, quantization and context
changed together. This is neither an isolated optimization nor a phone-speed
measurement. The diagnostic cgroup peaked at 222,994,432 bytes with no swap or
memory-pressure/OOM events.

**ACFT Base is not promoted.** Lower host latency did not establish better
accuracy, the quiet controls remain unreliable, and noise output is still
nonempty. Both original stopped screens remain separate rejected attempts;
completing coverage does not retroactively pass their criteria. No further
context sweep or model download is justified by this checkpoint. The existing
backend, automatic-correction setting and unopened held-out cohort are unchanged.

## Background-sound detection: two exact Android noise chunks

A bounded host check applied the existing Silero presence policy to the two
archived SpeechGate chunks of the 30-second seeded-noise control above. It used
the same PCM, including the 500 ms overlap, verified runtime/model bytes and
fixed conditioning and detector settings. **No ASR ran.** Both detector calls
completed, covered all 954 probability windows and returned no speech ranges.
The 40 words previously emitted by Android would therefore become **40 → 0**
if both chunk outputs were hidden. This is a counterfactual over preserved ASR,
not a new transcription score or evidence that automatic deletion is safe.
[Audited counts, limits and artifact hashes](noise-transfer.json).

Detection took 0.0507 and 0.0465 seconds on the host. The whole service took
0.480 seconds and its rounded peak memory was 29.9 MiB, with zero swap. Each
child had a 15-second timeout and 512 MiB address-space limit; the outer service
had a 60-second cap. These are host-job measurements, not Android or phone
latency, battery or thermal evidence. The previous positive controls were
reused as archived evidence, not rerun; this check adds no quiet-speech safety
result. It supports trying an explicit, reversible background-sound review
proposal. Automatic suppression remains disabled and held-out inputs remain
unopened.

## Sampler cost: parity passed, follow-up threshold not met

One synthetic host microbenchmark compared the pinned beam sampler with a
standalone copy that omits its unused `logits_id` fill and `partial_sort`.
Across five constructed distributions, three RNG histories and 24 successive
steps, every checked token metadata field and the full RNG state matched.
The timing blocks used fixed ABBA order, 64 calls per block, one CPU and an
optimized build. No audio, model inference or recorded decoder probability
vectors were used. [Protocol identities and per-scenario results](sampler-cost.json).

Median saved time across the five scenarios was **117.107 microseconds per
sampler call**, below the predeclared **500-microsecond** threshold for a
separate full-ASR follow-up. All five differences were positive; that does not
override the failed cost threshold. The job, including compilation, took
2.063 seconds and reported approximately 178.1 MiB peak memory with zero swap.
The sampler process had an 18-second external timeout. **No production engine
change or full-ASR follow-up follows from this screen.** Synthetic sampler
parity does not establish decoder equivalence, recognition quality or
end-to-end speed, and host timing does not establish phone performance.
