# Fresh continuous-speech development checkpoint · 2026-11-03

The conditioned Silero candidate reduces combined normal/quiet development word
errors in all seven languages, but **does not pass every language/condition**:
normal Russian, German and Portuguese and quiet Italian still regress. It is
host-only research, not an enabled Android change. The lower RMS threshold is
**rejected** because of normal-speech and synthetic-noise regressions.

The production SpeechGate and multilingual base Q5_1, beam 3, full-context decoder
remain unchanged. Automatic correction remains disabled. The refined fresh
held-out cohort is **unconsumed**. No candidate has been frozen for it: a newly
confirmed no-speech logit-selection problem requires separate engine research
before a defensible decision. This checkpoint does not establish an all-language
product improvement or complete application finalization.

## Data and comparison

FLEURS, Conneau et al. (2022), Google, CC-BY-4.0, revision
70bb2e84b976b7e960aa89f1c648e09c59f894dd supplies en_us, ru_ru, de_de,
fr_fr, es_419, it_it and pt_br. [Corpus provenance](corpus-provenance.json)
contains public IDs, hashes, attribution and selection metadata, with no audio
or reference text. [Machine-readable aggregates](aggregates.json) retain
counts and the exact report, executable, library, model, compiler, class,
source and configuration identities.

The fresh cohort contains six official development clips per language:
**42 clips, 437.54 seconds, 864 reference words and 4,349 lexical characters**.
The original 56 previously consumed IDs and canonical PCM16 hashes were
excluded before selection. A hash-only audit then found one Italian test
reference identical to a previously consumed reference. That clip was replaced
in archive order using the existing cache, with **zero additional downloaded
bytes**. The refined cohort has the same 42 development clips and 42 test
clips, totaling 482.42 test seconds. Its IDs, canonical PCM16 hashes and exact
reference hashes are disjoint from the old cohort; its dev/test raw PCM hashes
are also disjoint. No test references, audio or recognition outputs were
inspected for this comparison. Exact hash disjointness does not establish
semantic or speaker independence.

Selection takes the first complete 1–30 second WAVs in archive order after
exclusions, without ASR-based selection. These are small, non-random read-speech
subsets with no exposed speaker IDs. Results do not estimate population
accuracy; no speaker bootstrap or confidence interval is claimed.

Each language's six development clips forms a continuous PCM16 mono 16 kHz
stream with 2 seconds of leading silence, 1.2-second gaps and 2 seconds of
trailing silence. Normal gain 1.0 and quiet gain 0.1 reuse the **same source
clips**. They are correlated transformations, not 84 independent recordings.
Each gain has seven language streams totaling 507.54 seconds including inserted
silence. The prepared gain 0.03 condition has gate-only evidence and no ASR
comparison here. Scoring joins committed passages and source references with
one space and uses the unchanged NFC/casefold lexical WER/CER definitions.

The baseline replays the actual production Kotlin SpeechGate and Transcript.
The lower-threshold candidate changes only minimum RMS from 0.002 to 0.0008 in
a private snapshot; initial adaptive floor remains 0.0007. Trusted-context,
review and overlap reconciliation use the same Kotlin implementation. Both
use base Q5_1, beam 3, full encoder context, explicit language, four CPU decoder
threads and the same pinned whisper.cpp checkout
4979e04f5dcaccb36057e059bbaed8a2f5288315. The measured executable/library hashes
identify the binaries; the checkout hash alone does not prove their build
origin. [The gated evaluation documentation](../../gated-stream-evaluation.md)
describes the replay and validation boundaries.

## Lower-threshold comparison: normal speech

| Language | Baseline WER errors/words (%) | Candidate WER errors/words (%) | Baseline lexical CER errors/chars (%) | Candidate lexical CER errors/chars (%) |
| --- | ---: | ---: | ---: | ---: |
| en | 54/88 (61.36) | 16/88 (18.18) | 207/428 (48.36) | 49/428 (11.45) |
| ru | 28/105 (26.67) | 30/105 (28.57) | 51/587 (8.69) | 89/587 (15.16) |
| de | 12/122 (9.84) | 16/122 (13.11) | 15/736 (2.04) | 27/736 (3.67) |
| fr | 30/128 (23.44) | 72/128 (56.25) | 74/575 (12.87) | 186/575 (32.35) |
| es | 32/177 (18.08) | 25/177 (14.12) | 73/853 (8.56) | 45/853 (5.28) |
| it | 40/126 (31.75) | 54/126 (42.86) | 93/616 (15.10) | 135/616 (21.92) |
| pt | 11/118 (9.32) | 12/118 (10.17) | 32/554 (5.78) | 37/554 (6.68) |
| All | 207/864 (23.96) | 225/864 (26.04) | 545/4349 (12.53) | 568/4349 (13.06) |

## Lower-threshold comparison: quiet speech

| Language | Baseline WER errors/words (%) | Candidate WER errors/words (%) | Baseline lexical CER errors/chars (%) | Candidate lexical CER errors/chars (%) |
| --- | ---: | ---: | ---: | ---: |
| en | 70/88 (79.55) | 62/88 (70.45) | 290/428 (67.76) | 277/428 (64.72) |
| ru | 50/105 (47.62) | 29/105 (27.62) | 147/587 (25.04) | 59/587 (10.05) |
| de | 45/122 (36.89) | 19/122 (15.57) | 199/736 (27.04) | 36/736 (4.89) |
| fr | 43/128 (33.59) | 37/128 (28.91) | 113/575 (19.65) | 87/575 (15.13) |
| es | 48/177 (27.12) | 17/177 (9.60) | 115/853 (13.48) | 16/853 (1.88) |
| it | 30/126 (23.81) | 28/126 (22.22) | 50/616 (8.12) | 49/616 (7.95) |
| pt | 17/118 (14.41) | 11/118 (9.32) | 48/554 (8.66) | 35/554 (6.32) |
| All | 303/864 (35.07) | 203/864 (23.50) | 962/4349 (22.12) | 559/4349 (12.85) |

Normal speech worsens from 207 to 225 word errors, including regressions in
Russian, German, French, Italian and Portuguese. Quiet speech improves from
303 to 203 word errors, with lower WER and lexical CER in all seven languages.
That useful development signal does not cancel normal-speech or noise
regressions. Different boundaries also change decoder context, so more source
coverage need not improve transcription.

## Gate coverage and robustness

Coverage is intersection with whole source-clip intervals, including their
internal and edge silence. It is **not phoneme recall or word accuracy**.
The denominator is 7,000,640 source samples, or 437.54 seconds, for every row.

| Gain | Baseline source-clip coverage | Candidate coverage | Clips with no candidate audio, baseline → candidate | Candidate ranges, baseline → candidate |
| --- | ---: | ---: | ---: | ---: |
| 1.0 | 85.27% | 95.13% | 2/42 → 0/42 | 58 → 58 |
| 0.1 | 75.13% | 81.18% | 4/42 → 4/42 | 71 → 55 |
| 0.03, no ASR | 45.62% | 72.29% | 12/42 → 6/42 | 76 → 72 |

An additional gate-only snapshot lowers both minimum RMS to 0.0008 and initial
floor to 0.0002. It produces identical coverage summaries after the fixed
2-second silence. Immediate-start controls expose the startup difference:
220 Hz tones at PCM16 amplitudes 40 and 64 are retained immediately only by
the lower-floor snapshot; after 2 seconds of silence both candidate snapshots
retain them. A synthetic tone is not a human speech-quality test.

The expanded frozen-class suite uses 29 deterministic synthetic streams,
including **18 pure zero/DC/noise cases** and 11 tone/onset/transition cases.
Noise is seeded uniform integer PCM16, including ±32, ±48, ±64, ±96 and ±128,
with immediate and delayed starts, repeated seeds and level transitions.
The complete synthetic definitions, hashes and aggregate results are published
in the JSON. No speech corpus or ASR decoder is used in these controls.

| Gate | Activated pure no-speech cases | Candidate ranges | Candidate audio seconds including repeated overlap |
| --- | ---: | ---: | ---: |
| Production baseline | 2/18 | 4 | 61.48 |
| Lower threshold | 7/18 | 14 | 216.38 |
| Lower threshold and initial floor | 13/18 | 25 | 379.12 |
| Silero host-only offline candidate | 0/18 | 0 | 0 |

These seconds describe retained audio, not runtime. After 2 seconds of zeros,
stationary ±48/±64/±96 noise opens both threshold candidates while baseline
stays closed; the ±64 result repeats across three seeds. Following an active
tone, those noise levels retain the full 30-second tail in both candidates,
versus 0.6 seconds in baseline. Baseline also activates on ±128 noise, so the
controls do not establish that the current gate is generally noise-robust.

The three original 30-second zero/DC/±32-noise controls produce no ranges and
no ASR calls for either decoded variant. Their apparent success misses the
expanded-suite regression. Gate activation is not hallucination measurement:
there is no decoder output on the expanded controls, and a closed gate cannot
establish the decoder's behavior on noise it was never given.

## Completed host-only Silero development comparison

Three complete runs use the same original stream PCM hashes, ASR model and
executable/library fingerprints as the baseline. The JSON retains each report,
plan, policy, source, compiler and binary identity plus original/conditioned PCM
and bounded-range hashes. The decoder remains base Q5_1, beam 3, full acoustic
context and four threads; trusted text context is the explicit ablation below.

Silero 5.1.2 runs CPU-only with threshold 0.5, minimum speech 250 ms, minimum
silence 600 ms, maximum native speech duration 15 seconds, padding 500 ms and one
VAD thread. Native ranges are unioned and bounded to 16 seconds with 0.5-second
overlap before ASR because native merging can exceed its configured maximum.
See [VAD stream evaluation](../../vad-stream-evaluation.md).

- **None/on:** original PCM enters VAD; trusted ASR context is on.
- **Cap30/on:** only VAD input receives causal RMS conditioning, with context on.
- **Cap30/off:** identical conditioned VAD PCM and identical selected ranges,
  with trusted ASR context off.

Conditioning uses 512-sample frames, target RMS 0.03, gain limited to 1–30 and a
0.5-second peak-envelope release. State resets at each source stream; partial
frames use their actual length. **ASR always receives the unchanged prepared
source PCM**, not the amplified VAD input. This tests activation/framing, not
amplification of the recognizer's audio. These offline whole-stream experiments
do not implement or measure a bounded Android worker.

| Complete variant | Normal WER | Quiet WER | Normal lexical CER | Quiet lexical CER |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 207/864 (23.96%) | 303/864 (35.07%) | 545/4349 (12.53%) | 962/4349 (22.12%) |
| None/on | 165/864 (19.10%) | 224/864 (25.93%) | 382/4349 (8.78%) | 582/4349 (13.38%) |
| Cap30/on | 136/864 (15.74%) | 178/864 (20.60%) | 255/4349 (5.86%) | 352/4349 (8.09%) |
| Cap30/off | 138/864 (15.97%) | 179/864 (20.72%) | 261/4349 (6.00%) | 348/4349 (8.00%) |

Each cell below gives **word errors/reference words; lexical character
errors/reference characters**. Counts are per language stream, built from six
development clips.

### Normal speech counts

| Language | Baseline | None/on | Cap30/on | Cap30/off |
| --- | ---: | ---: | ---: | ---: |
| en | 54/88; 207/428 | 37/88; 145/428 | 10/88; 27/428 | 12/88; 33/428 |
| ru | 28/105; 51/587 | 30/105; 59/587 | 30/105; 54/587 | 27/105; 48/587 |
| de | 12/122; 15/736 | 14/122; 24/736 | 13/122; 21/736 | 12/122; 20/736 |
| fr | 30/128; 74/575 | 27/128; 54/575 | 27/128; 54/575 | 27/128; 61/575 |
| es | 32/177; 73/853 | 16/177; 12/853 | 15/177; 14/853 | 15/177; 14/853 |
| it | 40/126; 93/616 | 30/126; 52/616 | 29/126; 49/616 | 31/126; 47/616 |
| pt | 11/118; 32/554 | 11/118; 36/554 | 12/118; 36/554 | 14/118; 38/554 |

### Quiet speech counts

| Language | Baseline | None/on | Cap30/on | Cap30/off |
| --- | ---: | ---: | ---: | ---: |
| en | 70/88; 290/428 | 62/88; 277/428 | 26/88; 66/428 | 29/88; 71/428 |
| ru | 50/105; 147/587 | 35/105; 61/587 | 37/105; 62/587 | 35/105; 64/587 |
| de | 45/122; 199/736 | 30/122; 64/736 | 18/122; 26/736 | 19/122; 28/736 |
| fr | 43/128; 113/575 | 36/128; 75/575 | 37/128; 94/575 | 33/128; 75/575 |
| es | 48/177; 115/853 | 17/177; 17/853 | 13/177; 15/853 | 13/177; 16/853 |
| it | 30/126; 50/616 | 30/126; 47/616 | 36/126; 53/616 | 36/126; 52/616 |
| pt | 17/118; 48/554 | 14/118; 41/554 | 11/118; 36/554 | 14/118; 42/554 |

Cap30/on combines to 314 word errors versus baseline 510 across the two gains.
Per-language combined word errors are en 124 → 36, ru 78 → 67, de 57 → 31,
fr 73 → 64, es 80 → 28, it 70 → 65 and pt 28 → 23. Those sums reuse each source
utterance twice; they are correlated development observations, not additional
independent speech evidence.

The unresolved condition regressions remain material. Normal Russian worsens
28 → 30 word errors, German 12 → 13 and Portuguese 11 → 12; quiet Italian
worsens 30 → 36. Lexical CER also worsens in those four cells. Turning context
off changes the tradeoffs rather than producing a uniformly better policy:
normal word errors become 138 and quiet errors 179. No language-specific
switch or automatic correction is selected from these results.

### VAD coverage and controls

Coverage still measures whole source-clip intervals including silence, not
spoken-word or phoneme recall. Each speech row has 42 clips and the same
7,000,640 source-sample denominator. Context on/off has identical cap30 coverage.

| VAD input | Gain | Source-clip coverage | Clips without candidate audio | ASR ranges |
| --- | ---: | ---: | ---: | ---: |
| None | 1.0 | 83.03% | 1/42 | 43 |
| None | 0.1 | 80.02% | 4/42 | 42 |
| Cap30 | 1.0 | 85.59% | 0/42 | 44 |
| Cap30 | 0.1 | 85.31% | 0/42 | 44 |

All three completed variants emitted no ranges and made no ASR calls on the
three original 30-second zero/DC/seeded-noise controls. Separately, unconditioned
Silero produced zero ranges on all 29 expanded synthetic streams, including the
18 pure no-speech cases. A separate development-only conditioner screen also
returned zero ranges on the same 29 cases with cap30; its own plan/report/summary
hashes and settings are retained in the JSON. That screen did not run ASR. The
completed speech comparisons themselves include only the three original controls.
Zero activation on these controls does not establish real-room robustness or
retention of quiet speech at every boundary.

## Confidence diagnostic and held-out status

Two short emulator-input captures contained varying PCM16 values −8…7 with
normalized RMS about 0.000124, despite host audio being disabled. Their English
and Russian host decodes each produced one segment. Both unconditioned and
cap30 Silero selected zero ranges on these two inputs. This limited diagnostic
does not validate recovery filtering on all seven languages or real-room noise.

The existing engine's no-speech values were about 0.0000193 on both noise and
most examined development outputs. Source review confirmed the cause: prompt
preparation requests only the final token's logits, but no-speech softmax reads
the first block. Fresh contexts therefore use approximately uniform zero-logit
probabilities (1/51,865); later calls can read stale rows. The official Whisper
implementation instead uses the actual start-of-transcript token before final-token
sampling. [OpenAI implementation](https://github.com/openai/whisper/blob/0b1ba3d46ebf7fe6f953acfd8cad62a4f851b49f/whisper/decoding.py#L529-L534).

Token confidence alone also overlaps: the largest noise-segment geometric mean
was 0.4844, and 19/142 baseline, 11/138 lower-threshold, 3/114 none/on and 5/122
cap30/on development segments were at or below it. These are correlated,
unadjudicated segment outputs; this is evidence against treating that exploratory
cutoff as a calibrated rejection rule, not a critical-error precision estimate.

The engine finding does not invalidate the recorded text/error counts, but it
blocks interpreting the current no-speech field as reliable acoustic evidence.
An independently reviewed SOT-logit patch is available through an optional
[hash-verified research overlay](../../whisper-sot-research.md). The production
engine/submodule is unchanged. The paired development experiment below is separate
from held-out validation: no candidate freeze, preparation, reference inspection or
scoring has been performed on the refined **test** cohort.

### SOT probability and abstention: verified development repeat

One fixed development policy requests the correct SOT logits row and uses
`no_speech_thold=0.8`, `logprob_thold=0`, with temperature increment zero. It keeps
base Q5_1, full encoder context, beam 3, four CPU threads and the production Kotlin
gate/context/reconciliation. This changes the decoder's abstention policy; it is
not an amplitude cutoff or automatic correction. The plan and exact binaries were
frozen before inference; [counts and provenance](engine-dev-results.json) bind the
patched engine explicitly instead of treating its upstream commit as a pristine
binary identity.

The verified repeat used 42 dev clips and the same normal/quiet PCM and 129 chunks
as the baseline. WER and CER did not increase in any of the 14 language/condition
cells. Word-error counts are:

| Language | Normal baseline → candidate | Quiet baseline → candidate | Reference words per condition |
| --- | ---: | ---: | ---: |
| English | 54 → 54 | 70 → 61 | 88 |
| Russian | 28 → 28 | 50 → 50 | 105 |
| German | 12 → 12 | 45 → 45 | 122 |
| French | 30 → 30 | 43 → 43 | 128 |
| Spanish | 32 → 17 | 48 → 48 | 177 |
| Italian | 40 → 27 | 30 → 30 | 126 |
| Portuguese | 11 → 11 | 17 → 17 | 118 |
| Total | **207 → 179** | **303 → 294** | **864** |

Character errors were 545 → 438 for normal and 962 → 946 for quiet speech, with
4,349 reference characters in each condition. Normal and quiet conditions are
correlated versions of the same six clips per language. This does not demonstrate improved accuracy
for every language: four languages have unchanged word-error counts.

On the two original varying emulator-noise captures, all seven language choices
changed from nonempty output to zero segments: 14/14 paired cases with empty text
context. These are two signals, not 14 independent recordings or real-room noise
validation; nonempty trusted contexts remain a separate control requirement.

**The candidate is not accepted.** Five development chunks became empty; all
other native text/timestamps and prompt inputs were identical. An automatic lexical
coverage check retained a rejection flag for one lost English reference-token
match. Reviewing source-clip boundaries shows that global alignment assigned that
match to a different clip; the removed output resembles repetition or invented
text at short boundaries. This is evidence to review the flag, not acoustic proof
that no valid speech was lost. The affected English source tail has measurable
acoustic energy, so it cannot simply be labeled silence. The original flag,
reference/time review hashes and measured acoustic-statistics hash are retained.
Varying-input empty results must remain reviewable with original audio preserved.

The initial attempt failed its outer source freeze because an unrelated title
fix changed a root checkout file, although inference executed unchanged snapshot
bytes. Its outputs remain diagnostic and are excluded as acceptance evidence.
A separate, independently reviewed freeze of the actual consumed inputs then
completed with before/after identity checks and identical recognition changes.
Neither that repeat nor the lexical review opens the fresh test cohort. The
completed compatibility and control checks below retain blocking negative results.
Fresh held-out evidence, actual Android recovery and phone resources remain open.

Both baseline and candidate used 129 cold host calls. Their summed probe wall
costs were 433.65 and 472.57 seconds; background build/QA contention differed.
These values cannot rank speed or establish phone latency. No performance benefit
is claimed from this development repeat.

### Completed compatibility and positive/noise controls

The separately frozen combination of SOT `0.8/0` and the existing cap30/context-on
Silero policy completed 88 ASR calls. It reproduced the unpatched cap30 result
exactly: 136/178 word errors, with no changed native text/timestamps or newly empty
chunks. Both original comparators were fixed before inference. Against production,
four language/condition cells still regress:

| Condition | Word-error increase | Character-error increase |
| --- | ---: | ---: |
| Russian normal | +2 | +3 |
| German normal | +1 | +6 |
| Italian quiet | +6 | +3 |
| Portuguese normal | +1 | +4 |

The combined candidate is **rejected for held-out evaluation**. Improved totals
for every language after pooling its two correlated conditions do not override
these cells. [Combined counts and provenance](combined-dev-results.json) retain
both comparisons and the negative decision. This run includes the three original
synthetic English controls; it does not establish the full combined recovery-noise
matrix with all language/context choices. Its 391.24-second supervisor duration
and cold probe timings are descriptive, not a controlled speed comparison.

A further frozen native-decoder check used 14 locally generated eSpeak NG 1.52.0
positive fixtures: one short negation and one appointment sentence per language,
each at gains 1 and 0.1, giving 28 cases. Two independent generations produced
identical PCM; no audio was downloaded. Short negations last about 285–455 ms and
the decoder pads them to one second. One full-scale Spanish sentence sample is
recorded in the private validation report; synthesis parameters were not retuned.
These are synthetic development canaries, not independent human recordings.

All 28 scored positive texts were identical before and after the SOT policy;
WER/CER did not change. Both implementations had zero normalized word errors in
only 8/28 cases; only 1/28 scored texts matched the reference literally, including
case and punctuation. The JSON distinguishes these two metrics. In particular,
quiet Portuguese short negation
produced punctuation but no recognized word in both versions. Preserving a
baseline result does not establish adequate short-speech recognition.

The same run tested the two emulator-noise captures in all seven languages with
fixed, nonempty synthetic context in the corresponding language. Candidate output
had zero lexical words in 14/14 cases, but only **13/14 had zero native segments**.
One French case retained a punctuation segment, with SOT no-speech value 0.794683,
below the frozen 0.8 threshold. The strict empty-output requirement therefore
**failed**; punctuation was not removed and the threshold was not retuned.
[Whitelisted canary results](canary-results.json) exclude native text diagnostics.
The private raw report remains unpublished.

A separate VAD-only screen of the same 28 positive fixtures, with unchanged
cap30/Silero settings, selected ranges covering all original samples in 28/28
cases, including every short negation. This is sample coverage, not word accuracy
or a combined ASR pass. Its private plan/result hashes are
`2478e005a7f7fd8a15eac28bbf0240623a68bac6097037acef2b54940ac347fe` /
`cae0b8335821339089b766f7db6f9bacb054b9fca9e9c795702639f31e22281f`.

### Whole-chunk presence: recovered development report

The next variant uses the original unpatched ASR engine and VAD only as a presence
check on each existing production chunk. A positive decision keeps the entire
original ASR chunk and its boundaries. A negative decision skips ASR and passes
an empty decoded list through the actual Kotlin reconciliation code, retaining
its uncertain review cue and original audio. Neither the model nor decoder
thresholds change. Causal gain affects only the separate VAD input; recurrent
state and conditioning reset for each original chunk.

| Condition | Baseline word errors / 864 | Candidate word errors / 864 | Baseline character errors / 4,349 | Candidate character errors / 4,349 |
| --- | ---: | ---: | ---: | ---: |
| Normal | 207 | 179 | 545 | 438 |
| Quiet | 303 | 266 | 962 | 850 |

There is no WER/CER increase in any of the 14 language/condition cells. Seven of
129 ASR calls are skipped; six previously nonempty native results become empty.
All 28 synthetic positive native texts remain identical to baseline. The two
actual noise captures in seven languages and both empty/nonempty text contexts
skip ASR in **28/28 cases**. These are policy decisions, not 28 native decoder
calls returning empty output. All allowed ASR inputs retain their original PCM,
sample counts, boundaries and prompts. The [public result](presence-dev-results.json)
contains the per-cell counts and hash bindings without transcripts.

**This is a recovered report, not a clean completed run or an accepted policy.**
Inference and its final identity checks completed, but report assembly raised
`KeyError: 'wer'` by expecting rates in per-record count data. The original failed
status, frozen runner and outputs remain unchanged. A separately frozen,
deterministic postprocessor assembled the report from those completed outputs
without running inference again. Independent review checked all 219 frozen inputs,
1,638 preserved non-log outputs, both engine identities and all 185 VAD decisions.
The original freeze is
`6662032932949f475d102c21ace258201d369406ed452e460df610e9cb9f65d9`;
the separate recovery binding is
`4597ab824f90dbdf0d3dbd307a48b8d75939dd5be94ec18105bb092f303edc55`.

The automatic speech-loss flag remains: one global English reference-token match
is lost and twelve are gained. Separate source/time review finds all five shared
English reference positions in the unchanged preceding chunk. The additional
quiet Spanish removal similarly matches eight positions already retained in its
preceding chunk. That supports redundant/invented boundary-output removal; it
does not establish acoustic silence or prove general speech recall. The review
hash is `95ee1530d3c2e1ad9fe519612729756893f962e8f74d20d43b3ba7c9b63265a5`.
The automatic flag is not removed by this adjudication evidence.

Android integration, an independently frozen held-out comparison and controlled
whole-pipeline timing remain open. The fresh 42-clip test cohort is still closed.
This candidate does not establish better human-speech WER in every language or
phone performance; improvements and unchanged cells are reported separately.

## Shorter encoder context with whole-chunk presence: rejected

This completed DEV run changed only encoder context to
`min(1500, max(768, ceil((original_seconds + 2) * 50 / 256) * 256))`.
It preserved original whole-chunk PCM and generated trusted prompts from each
candidate stream's own preceding Kotlin-reconciled passages. It made 150 fresh
cold ASR calls: 149 at context 768 and one at 1024. Its 185 VAD decisions were
reused verified results; no new VAD inference ran. Both full-context comparators
are historical cold runs, including the explicitly recovered presence report.

| Run | Normal word errors / words | Quiet word errors / words | Normal character errors / characters | Quiet character errors / characters |
| --- | ---: | ---: | ---: | ---: |
| Production full context | 207 / 864 | 303 / 864 | 545 / 4,349 | 962 / 4,349 |
| Presence, full context | 179 / 864 | 266 / 864 | 438 / 4,349 | 850 / 4,349 |
| Presence, shorter context | 183 / 864 | 249 / 864 | 457 / 4,349 | 826 / 4,349 |

**The candidate is rejected.** Six of fourteen language/condition cells regress
against production and nine regress against full-context presence in WER or CER.
Fifteen of 28 positive canaries fail the frozen gate: six have more word errors,
seven have more character errors, and seven change text while tying both error
counts; these groups overlap. Zero-word-error canaries increase from eight to
nine and the one lexically empty canary becomes nonempty, but neither improvement
overrides the failed comparisons. Only five candidate texts are exactly unchanged
from the positive comparators.

All 28 noise cases retain cached VAD-negative decisions, skip ASR and preserve
their ordinary uncertain review cues. This adds no fresh noise-decoder evidence.
Five newly empty committed-chunk comparisons against production were already
skipped by the presence comparator; none is newly empty against presence. They
must not be attributed to shorter context. Speech-loss adjudication remains
unperformed because the numerical/control failures already establish rejection.

The [public whitelist](auto-context-dev-results.json) contains per-language
WER/CER counts, control comparisons and engine/model/result hashes. Independent
review checked 1,445 frozen inputs, 1,238 non-log outputs and all 150 native
profiles, invocations and whole-WAV identities. The execution freeze is
`bc892805b40701db0fe6ad60563137ae18a373518f1c5e92450504ea09ff109e`;
the completed result binding is
`d039ba4808377e534676eb9e4118e249aac80b570c2149dfb7c7d83b5ab914c7`.
The same 42 DEV clips recur across correlated gains. This is not a persistent
session, controlled speed or phone-resource result, and the rejected candidate
does not open the held-out cohort.

## Persistent whole-chunk presence: rejected

This completed DEV comparison gives each arm a fresh model context for each of
21 matched sessions, then reuses that context across the session's requests.
Both arms use the same reviewed engine with the VAD error/cancellation fix;
ASR/SOT logic, decoder thresholds and full encoder context 1500 are unchanged.
The candidate applies the same whole-chunk presence policy. Allowed ASR calls
retain the entire original input and padding; each arm generates trusted prompts
from its own preceding Kotlin-reconciled passages.

The same 42 DEV clips form 14 language/condition streams and 129 requests per
arm. Seven additional sessions interleave 28 synthetic positives with 28 noise
controls, using the frozen language/context order. The run completed 185 baseline
and 150 candidate ASR calls, plus 185 fresh candidate VAD checks. These are fresh
persistent comparators: historical cold outputs bind framing and source identity
only and do not supply baseline recognition results.

| Condition | Persistent baseline word errors / 864 | Persistent candidate word errors / 864 | Baseline character errors / 4,349 | Candidate character errors / 4,349 |
| --- | ---: | ---: | ---: | ---: |
| Normal | 223 | 176 | 598 | 431 |
| Quiet | 256 | 255 | 822 | 801 |

**The candidate is rejected.** Quiet Spanish word errors rise from 16 to 19 out
of 177 reference words, although character errors fall from 18 to 16. Ten of 28
positive controls fail the frozen criteria: four have more word errors, five
have more character errors, and five change text while tying both error counts;
these groups overlap. No positive control is skipped or newly empty. All 28
noise controls skip ASR after fresh VAD checks, preserving uncertain review cues;
this is not evidence of 28 decoder calls returning empty output.

Thirty DEV chunks change native text or reconciled passages. Five previously
nonempty native results become empty, and four retain automatic global lexical
loss flags. Source-local review aids do not resolve acoustic speech loss; the
flags remain open. A targeted output review confirmed that one Portuguese quiet
short-negation control changed from a correct normalized result to an incorrect
result without the negation: word errors rose from 0 to 3 and character errors
from 0 to 11. Full critical-error and acoustic adjudication remain open. The
private targeted-review SHA-256 is
`ec1eaa5042d2534d17871773b7ab085f4bcdf5497dc6c49b92f1a93f3722a41d`.

The [public whitelist](persistent-presence-dev-results.json) contains per-cell
metrics, control comparisons and hash bindings without transcripts. Its SHA-256 is
`4db756ae1729def4daa13af29296e67e1c2124c06a326c78e1750762afe46eaf`.
Independent review verified 3,129 frozen inputs, 2,176 non-log outputs, all 370
request identities and the 335 native ASR profiles. All 185 VAD decisions matched
the frozen native-parity expectations. Separate read-only Kotlin replay reproduced
258 DEV contexts and 370 reconciliation results. The launch freeze is
`d60947de3e043b8c52c83f81285ba15648f1fbe8662a5052cc2c0571cb5684a2`;
the completed result binding is
`0b2009f2e312cd82765fd300a3a5f47dfc79c984f2d51b6b01af616acd6fb3a2`.

Host execution overlapped Android QA; its 1,476.07-second duration and decoder
timings are diagnostic, not a speed ranking or phone-resource measurement. This
rejection neither adopts the policy nor opens the unchanged 42-clip held-out
cohort. It does not establish an accuracy improvement in every supported language.

## Reused decoder: fixed-prefix diagnosis

The rejected warm candidate exposed a real call-history dependency. Eight fresh
ES DEV sessions compared the same four-chunk prefix with and without its third,
already-empty ASR call. Original WAV bytes and explicit prompts were fixed across
histories, with full encoder context, beam size 3, temperature 0 and unchanged
0.6/−1 thresholds. Both the original and corrected-SOT engine were repeated twice.
All four engine/history combinations repeated exactly, and the original inclusive
prefix reproduced the earlier warm baseline. Nevertheless, skipping the empty call
changed the later target text and timestamps in both engines. Corrected SOT made
the target no-speech value identical (0.0152495), without removing the output
difference. No VAD ran in this diagnostic.

The [pinned engine source](https://github.com/ggml-org/whisper.cpp/blob/4979e04f5dcaccb36057e059bbaed8a2f5288315/src/whisper.cpp)
sorts token scores in `whisper_sample_token_topk`, but then ignores those sorted
IDs and samples from the full probability distribution. Decoder zero's RNG state
persists across calls; only decoders 1 and higher are reseeded for each full call.
`no_context=true` does not reset that RNG. Calling this deterministic beam search
in the previous architecture description was incorrect.

A separate causal probe added exactly one RNG-zero reset statement to the
corrected-SOT parent. Four fresh sessions, again two include/skip repetitions,
made all target text, timestamps, token probabilities and no-speech values equal.
The original third chunk remained empty. This supports RNG carry as the mechanism
for this fixed ES prefix; it neither accepts RNG reset as a production policy nor
establishes speech quality in other languages. Actual sorted top-k selection is a
separate candidate requiring its own evaluation.

Independent review verified 1,248 inputs and 65 non-log outputs for the eight-session
probe. Root independently verified all 2,458 inputs, 33 outputs and exact raw target
transcriptions for the reset probe, separately from the runner author's checks.
The public aggregates contain hashes and observations without transcripts:

- [Eight-session history comparison](beam-history-prefix-results.json), SHA-256
  `3849d50623d38b8b5ad5c5c8d2c0fac97affc8a8a25847150912880c83982cec`.
- [Four-session reset diagnosis](rng-reset-prefix-results.json), SHA-256
  `e19e9a4216b8ef81f9624e396c60487f345bb0d49ea938d6f4d13d592639cc13`.

The 28 + 14 ASR calls used already-consumed DEV inputs, not held-out recordings.
Execution overlapped other local work; durations are diagnostic only. Previous
quality rejections, lexical flags and the closed 42-clip held-out cohort remain.

### Actual top-k candidate: bounded contract check

The separate [combined research overlay](../../whisper-beam-research.md) uses the
sorted highest-scoring IDs with deterministic token-ID ties. It preserves the
reviewed SOT/VAD API changes and does **not** include the diagnostic RNG reset.
The same test harness compiles the actual baseline and candidate sampler:
seven of eight baseline assertions fail; all eight candidate assertions pass.
CLI help and twenty configuration cases also pass their expected outcomes,
including eighteen fail-closed cases. Independent source/provenance review checked
the public files, runtime inputs and generated source hashes. These checks load
no model and perform no ASR or VAD inference.

A separately frozen four-session ES prefix run then used the new library for
fourteen ASR calls, without VAD or threshold changes. All target text, timestamps,
probabilities and no-speech values match across both histories and both repeats.
However, the formerly empty 1.06-second third input now produces one 122-character
segment whose native end is 5,000 ms in both inclusive sessions. Stable output is
not necessarily correct output; this is not an accepted speech-quality result.
The historical `include-empty` label describes the old baseline, not the new
candidate's third output.

Root independently verified all 2,463 frozen inputs, 33 non-log outputs, target
equality and the third-input observations. The [public aggregate](true-topk-prefix-results.json)
has SHA-256 `0bdf471e93d904a0502ca067f56deb0b7423bfa462cb4bdb0dc9424dc0530d2c`.
The public overlay's model-free verification is bound by runtime result
`9cc17d9bb409ceb8629845aea1ee904a7e82519dcfade33cf3ac6f082e785c59`
and configuration result `f3104e7aa78c3bd03b01c96bce14184cab3fbdff5f849b47551e0fc75258066c`.
The completed persistent DEV comparison below rejects this candidate. Android
integration, fresh held-out validation and controlled timing are not established.
The earlier presence-only candidate remains rejected.

## True top-k with persistent whole-chunk presence: rejected

The fixed comparison completed 42 fresh sessions, 335 ASR calls and 185 VAD
calls. Both new arms use the same corrected-SOT/true-top-k engine: one decodes
every original request, while the other applies the unchanged whole-chunk
presence policy first. Each engine remains open within its session. Actual Kotlin
context/reconciliation uses each arm's own preceding output; reference text never
enters the prompt. The primary comparator is the already verified warm production
run, not the other new arm. All 42 DEV clips and 56 controls were already consumed;
the 42 fresh held-out clips remain closed.

**The combined candidate is rejected.** Normal/quiet word errors change from
223/256 to 176/246, and character errors from 598/822 to 467/820. Six cells worsen
in WER or CER, and 14 of 28 positive controls fail the unchanged strict gate.

| Language | Normal word errors, production → candidate | Quiet word errors | Normal character errors | Quiet character errors |
| --- | ---: | ---: | ---: | ---: |
| English | 54 → 54 | 65 → 62 | 212 → 233 | 293 → 277 |
| Russian | 26 → 24 | 48 → 47 | 50 → 46 | 153 → 144 |
| German | 15 → 13 | 42 → 46 | 21 → 20 | 180 → 207 |
| French | 28 → 29 | 45 → 38 | 60 → 73 | 104 → 98 |
| Spanish | 23 → 14 | 16 → 17 | 47 → 14 | 18 → 15 |
| Italian | 65 → 31 | 27 → 26 | 183 → 51 | 41 → 47 |
| Portuguese | 12 → 11 | 13 → 10 | 25 → 30 | 33 → 32 |

All 28 noise controls skip ASR. All 28 positives are identical between the two
**new** arms, including native transcription and committed passages; only 6/28
are literal matches to the old production output. Three positives become empty.
Targeted source/reference review confirms that previously correct short negations
in quiet French and Portuguese become empty after ASR despite positive VAD.
Four failed controls differ only in case/punctuation, but the original strict
changed-text-tie rule is retained. The decoder-only arm also fails the primary
gates and seven noise controls. Within the new engine, presence suppresses one
Spanish quiet output and reduces WER/CER; that secondary result does not override
rejection.

There are 115 changed DEV chunks, eight newly empty chunks and seven retained
lexical-loss flags. Targeted reference/time review also identifies two baseline
reference-matching words absent from all candidate passages overlapping that
Russian source. This is textual coverage, not word-level acoustic alignment or
acoustic adjudication of all changes; unresolved speech-loss and critical-error
review stays open.

Independent post-run review verified 4,289 frozen inputs, 2,187 outputs, eleven
runtime dependencies, all native call counts and 185 presence-parity rows.
The 35 skipped requests have no fabricated ASR JSON/profile; present requests
retain the original float input. The [public aggregate](true-topk-dev-results.json)
was reproduced exactly from the bound results:

- Launch freeze: `8862774efc56e4323034ce8b46f2e4d3e0312e5dba9f74559c06449b6262e5c2`.
- Result binding: `b1f633b7969b982365fb37c61ac9d1c6877e4ddce8316dc37a4531238fc1ab8e`.
- Public aggregate: `c7b92155303e8ed7729605836d3fa51397c71baeeaffc6ecb2d0847900b35558`.

The 1,392.56-second elapsed time includes a contended host run and is diagnostic,
not a speed comparison or phone measurement. No Android decoder change is adopted.

## English normal source-framing diagnosis

A separate read-only analysis of six already-consumed English DEV clips explains
why a decoder-only change cannot fix every error. Two complete clips containing
25 reference words never reach ASR through the current gate. On aligned 20 ms
frames they have at most one and zero consecutive frames above even the minimum
RMS 0.002; the gate requires two. Recorded source ranges independently confirm
their omission. No threshold was tuned or new inference run.

A third 10.02-second clip supplies only 1.86 seconds and accounts for 27 local
word errors. That does not assign all 27 errors to the gate: separating framing
and decoder effects requires controlled paired evidence and acoustic review.
Another partially covered clip recognizes all eight words correctly, so sample
coverage is not speech recall. The two fully covered clips account for two errors;
these source-local totals equal the official 54/88 stream count.

The historical global-cap30 cold run submits audio from all six sources and
scores 10/88. Its framing, prompts and engine history differ, so this is diagnostic
motivation, not a causal or accepted quality comparison. A proposed fixed-window
experiment using the same rejected decoder has not run: unchanged control failures
already prevent its acceptance. A future framing policy must keep capture, memory,
durability, first-result latency and all quality gates explicit.

The [whitelisted diagnosis](en-framing-diagnosis.json) contains hashes, ordinal
source rows and aggregate counts, without audio, transcripts or local paths.
Root verified all 62 input bindings and the exported accounting; its SHA-256 is
`2cc8900070e0d2d8606bc7ce38c94bb1608cd3639d7b923dd236f58db668c0c7`.

## Android seven-language integration smoke

The unpublished VAD candidate was exercised through its actual Android JNI on
one existing normal DEV first chunk per language, with a fresh engine and empty
prompt for each. All seven immutable audio inputs and model/APK identities were
verified; each produced lettered speech. Against the corresponding host results,
literal text matched in 5/7 cases (RU and FR differed), review labels in 6/7
(FR differed), and complete text/timestamp/confidence segments in 0/7.

The [public aggregate](android-dev-smoke-results.json) has SHA-256
`9d869a84ec1c159acd234350d3d5a08190d904bd9c85a5bf248ad91cf8a59dba`.
Independent review checked the fixture bindings, helper and target APKs, raw JNI
results and the actual frozen Kotlin review calculation. The target APK was
`34ea88dfd1ea5a93773efb6a00ffe80815055708e4ddc88393d8a781732dc59d`;
later UI-only APKs are separate artifacts. This is observable integration evidence,
not internal VAD numerical parity, seven-language accuracy, speed or phone evidence.
The host candidate remains rejected. The differences are not attributed to the RNG
finding without a separate cross-runtime comparison.

## Execution and resource scope

The initial baseline process terminated with SIGTERM/exit 143 during the German
portion; the cause is unverified. It produced no completed report, and all its
partial outputs are excluded. A clean full baseline repeat and a subsequent
full lower-threshold run completed. The first cap30/context-on process also
terminated with exit 143 before producing a completed report; its cause is
unverified and all partial outputs are excluded. A detached full repeat completed,
as did the default Silero and cap30/context-off runs. Only completed inference enters the recognition tables above. The later presence
report has the explicitly documented postprocessing recovery described above; its
original run remains failed.

| Complete run | Normal cold probe seconds | Quiet cold probe seconds | ASR calls, normal + quiet |
| --- | ---: | ---: | ---: |
| Baseline | 199.13 | 234.52 | 58 + 71 = 129 |
| Lower threshold | 206.59 | 188.12 | 58 + 55 = 113 |

Silero adds separate VAD and conditioning costs. Values below cover the seven
speech streams in each condition; control costs remain in the JSON.

| Complete VAD run | Condition | Cold ASR seconds | Cold VAD seconds | Conditioning seconds | ASR calls |
| --- | --- | ---: | ---: | ---: | ---: |
| None/on | normal | 153.21 | 2.207 | — | 43 |
| None/on | quiet | 149.19 | 2.257 | — | 42 |
| Cap30/on | normal | 199.62 | 3.576 | 0.834 | 44 |
| Cap30/on | quiet | 185.82 | 3.425 | 0.799 | 44 |
| Cap30/off | normal | 168.22 | 2.208 | 0.517 | 44 |
| Cap30/off | quiet | 168.08 | 2.458 | 0.543 | 44 |

The none variant has no conditioning step. VAD wall time includes its cold
process/model work; conditioning is measured separately. No combined phone
latency is inferred.

In the cold runs tabulated above, each ASR call starts a host process and loads
the model. Probe totals exclude Kotlin/helper I/O, capture and queueing. These
are not randomized repeated
trials. Some host work overlapped Android build/QA, and contention was not
experimentally controlled; the timings must not be used to rank candidate speed.
Different gates change both audio and call counts. These descriptive costs do not
establish a phone latency, memory, battery, thermal or background-reliability
improvement. No bounded Android VAD worker or phone resource gate is measured.

## Download budget audit

The existing [public budget snapshot](../2026-11-03-stt/download-budget.json)
matches all 193 private ledger reservations. The original checkpoint's
645,592,677-byte reservation prefix is unchanged. The private ledger's cached
total was corrected to equal its entries; no reservation was added or changed
by this audit and no download was performed.

| Reservation category added after checkpoint | Bytes |
| --- | ---: |
| Local baseline model | 59,707,625 |
| Future CI model and fixtures | 61,000,000 |
| Metadata | 16,777,216 |
| Corpus prefixes | 95,682,564 |
| VAD model, conservative reservation | 885,099 |
| Total added | 234,052,504 |

Total reservations are **879,645,181 / 1,000,000,000 bytes**, including the
61,000,000-byte future CI reserve and conservative metadata/retry limits.
An additional **120,354,819 bytes** remain unreserved. Reservations are not a count
of actual transferred bytes. The Italian held-out refinement reused cached data
and adds zero bytes.

## Remaining evidence

The lower-threshold candidate fails both development condition and noise criteria.
Conditioned Silero has a useful completed development signal, but still fails
several language/condition comparisons. A meaningful no-speech estimate and any
recovery-presence filter need separate paired development evidence before freezing
another policy. The refined held-out cohort remains unconsumed.

Broader spontaneous and real-room speech, critical names/numbers/negation
assessment, independent speakers and phone resource checks remain unconfirmed.
No production VAD integration, model change, automatic correction or all-language
product-quality improvement is established by this host checkpoint.
