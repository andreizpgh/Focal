# Local transcript-quality comparison · 2026-11-05

Exploratory CPU measurements, not phone acceptance. Automatic correction remains
disabled. Source audio and recognizer output are retained privately; this report
contains aggregate results only. The 42 unused held-out clips remain unopened.

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

One bounded pipeline replay of 129 speech chunks and two activated-noise chunks
is the next planned comparison; it has not
been launched. No application backend was changed and no new APK was released.
Physical-phone quality, latency, memory, battery, thermals and background
behavior remain open.
