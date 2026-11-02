# Seven-language local speech checkpoint · 2026-11-03

Report date uses Asia/Tbilisi (UTC+4). The recorded experiment runs and frozen
held-out plan are dated 2026-11-02 UTC.

The Android default remains multilingual **base Q5_1, beam 3, full encoder
context**. Experiments find substantially lower read-speech error with small Q5_1
and faster host decoding with shortened context, but neither establishes a safe
all-language replacement. Automatic correction remains disabled.

## Data and reproducibility

- FLEURS, Conneau et al. (2022), Google, [CC-BY-4.0](https://huggingface.co/datasets/google/fleurs),
  revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`; en_us, ru_ru, de_de,
  fr_fr, es_419, it_it, pt_br. The first complete 1–30 second WAVs in archive
  order were selected without inspecting reference text or ASR results.
- Three development and five official test clips per language: 56 clips,
  650.1 seconds. Development contains 490 reference words. The subsets are tiny,
  non-random read speech. Speaker IDs are absent; dev/test speaker independence
  is unverified. No speaker confidence interval or population accuracy claim.
- [Corpus provenance](corpus-provenance.json) records source/derived WAV hashes,
  source IDs, reference hashes, archive/prefix hashes and attribution. Source
  float WAVs were converted to mono 16 kHz PCM16 without amplitude normalization
  using NumPy 2.3.5 and SciPy 1.17.0.
  WAV and raw PCM identities are checked across splits before decoding.
- whisper.cpp `4979e04f5dcaccb36057e059bbaed8a2f5288315`; verified model hashes are
  in [the model lock](../../../models/stt-candidates.json). Linux x86-64 CPU,
  four decoder threads, AVX2, GPU off, flash attention on; OpenMP/LLAMAFILE off.
  [Raw aggregate metadata](decoder-results.json) records exact executable,
  shared-library, model, settings, evaluator and corpus hashes.
- Current JNI defaults are matched: timestamps enabled, text context 64,
  temperature/increment zero, best-of 1, explicit language, no retained context
  on isolated clips. A host probe adds token/no-speech features and load/decode/RSS
  diagnostics; it does not constitute Android execution.
- Experimental `auto` encoder context is
  `min(1500, max(768, ceil((seconds + 2) * 50 / 256) * 256))`.
  It is never silently applied in Android. Full context is the zero override.
- WER, lexical CER, normalized-space CER and literal CER use the same scoring and normalization
  definitions for all candidates; evaluator hashes differ where provenance or
  diagnostic handling evolved, and are recorded explicitly. Acronyms such as `U.N.` versus `UN` can affect token WER;
  the metric was not changed after seeing results. No punctuation gain is called
  an acoustic gain. Critical names/numbers/negation are not fully adjudicated.

Every timing is one cold process per clip, including model load. Variant order
was sequential, not randomized/alternating repeated trials. Timings exclude
capture, queueing, continuous Android state, thermal pressure and energy. They
are descriptive host costs, not a claim of phone speedup. An initial baseline
run contended with Gradle/emulation and is excluded; its isolated repeat retained
identical recognition metrics. Beam 2/5 and context 1280 timing may also be
contended and must not support speed comparisons.

## Development comparison

WER percentages, lower is better. Each language column contains only three clips.
Complete counts, CER variants and timing distributions are in `decoder-results.json`.

| Development variant | WER | de | en | es | fr | it | pt | ru | Cold host seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| base-q5-full-isolated | 18.98 | 23.73 | 7.25 | 6.10 | 33.02 | 11.67 | 15.25 | 32.73 | 163.43 |
| base-q5-auto | 19.39 | 18.64 | 10.14 | 6.10 | 33.02 | 11.67 | 22.03 | 30.91 | 70.27 |
| base-q8-full | 22.24 | 23.73 | 13.04 | 9.76 | 33.02 | 15.00 | 28.81 | 30.91 | 121.90 |
| base-q8-auto | 19.80 | 22.03 | 10.14 | 9.76 | 32.08 | 11.67 | 22.03 | 27.27 | 43.21 |
| base-q5-greedy | 20.41 | 20.34 | 10.14 | 9.76 | 32.08 | 13.33 | 23.73 | 30.91 | 88.94 |
| base-q5-beam2 | 23.47 | 28.81 | 13.04 | 10.98 | 37.74 | 18.33 | 23.73 | 27.27 | excluded |
| base-q5-beam5 | 18.16 | 15.25 | 11.59 | 6.10 | 27.36 | 11.67 | 25.42 | 29.09 | excluded |
| base-q5-ctx1280-contended | 18.37 | 27.12 | 7.25 | 6.10 | 27.36 | 11.67 | 18.64 | 30.91 | excluded |
| small-q5-auto | 10.20 | 5.08 | 14.49 | 4.88 | 9.43 | 8.33 | 13.56 | 18.18 | 169.41 |
| small-q5-full | 11.22 | 3.39 | 13.04 | 4.88 | 13.21 | 10.00 | 18.64 | 16.36 | 331.53 |
| probe-q8-auto | 19.80 | 22.03 | 10.14 | 9.76 | 32.08 | 11.67 | 22.03 | 27.27 | 56.72 |

Shortening base Q5's context reduces observed cold host time from 163.43 to
70.27 seconds, but English and Portuguese WER worsen. Q8 alone does not improve
aggregate accuracy. Q8 with auto context takes 43.21 seconds in the CLI trial,
with further language regressions. The matching probability probe reproduces
its text metrics but takes 56.72 seconds; different process costs and trial
conditions must not be pooled as repeated latency samples.

Small Q5 with auto context reduces development WER from 18.98% to 10.20%
(93 to 50 errors), including Russian 32.73% to 18.18%, while English worsens
7.25% to 14.49%. Full-context small does not resolve this: English remains
13.04%, Portuguese worsens to 18.64%, and total host time rises to 331.53 seconds.
Beam 5 also trades aggregate improvement for English/Portuguese regressions.
None passes the no-language-regression criterion on development data.

## Frozen held-out comparison

[The plan](held-out-plan.json) was saved before opening any test hypothesis or
score. Its SHA-256 is
`3d6d132c47aa4549d7d089630f67d241027daf4df2424a0dd7388a16ca4c62ae`.
It fixes E0, the fast Q8/auto candidate and the small/auto accuracy candidate,
all settings, unchanged scoring and promotion constraints. The test set must
not be reused to tune another policy. Its five clips per language cannot close
the missing speaker, critical-error or phone gates even if scores improve.

| Held-out variant | WER | Lexical CER | de | en | es | fr | it | pt | ru | Cold host seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| base-q5-full | 16.41 | 6.45 | 13.16 | 6.73 | 6.87 | 30.43 | 16.94 | 20.80 | 13.64 | 189.95 |
| base-q8-auto | 18.89 | 6.78 | 20.18 | 9.62 | 7.63 | 31.06 | 16.94 | 26.40 | 14.77 | 90.38 |
| small-q5-auto | 8.15 | 2.95 | 9.65 | 7.69 | 4.58 | 14.29 | 4.84 | 9.60 | 3.41 | 273.45 |

Small/auto reduces aggregate test WER from 16.41% to 8.15%, and Russian from
13.64% to 3.41%. English increases from 6.73% to 7.69% (one additional word
error), so the frozen no-language-regression criterion fails. Its observed
cold host cost is 273.45 seconds versus baseline 189.95. Q8/auto takes
90.38 seconds but worsens aggregate WER to 18.89%. These results support
further acoustic-model research, not changing the shipped default. No test
results were used to retune these candidates or create a language-specific policy.

## Selective acoustic second pass: development shadow only

The exact existing Kotlin `Transcript.needsReview` selects a clip when any
segment within original audio needs review. References do not enter selection;
they are used only afterward to score. The primary is **experimental Q8/auto**,
not current E0; a selected clip uses the cached small/auto hypothesis. This is
whole-clip shadow substitution, not a deployed passage correction.
[The aggregate](shadow-results.json) retains both runner identities. The cached
comparison explicitly opts into mixed probe/CLI wrappers; a third same-model
cache verifies exact primary hypotheses on all 21 clips. This cohort agreement
does not establish general wrapper equivalence. Executed Kotlin jars are copied
from verified snapshots; confidence must reconstruct the cached primary text.

It selects 11/21 clips (15/37 segments), covering 73/97 primary word errors.
WER becomes 10.41% versus primary 19.80% and full-small 10.20%. It misses 24
primary word errors, including English and Russian errors; English selects
0/3 clips. No clip's WER increases relative to this experimental primary, but
Italian lexical CER increases from 3.63% to 4.95%. This is not a harm-free or
calibrated policy, and accepted-span critical correctness is unassessed.

The sum of cached primary plus selected-small cold times is 164.84 seconds,
versus 169.41 for full small: only about 2.7% estimated saving. This excludes
selection/integration cost and is **not measured combined latency**. Loading
and selection mistakes undermine the hoped-for cheap second pass. No automatic
replacement, new database overlay or LLM is enabled.

## Continuous gate and context

Actual production Kotlin `SpeechGate` and `Transcript` are replayed on continuous
streams made from the development clips, with fixed leading/trailing silence
and gaps. Normal, 0.1 gain and 0.03 gain conditions reuse the same utterances;
they are transformations, not new independent samples. Zero, DC and seeded
low-noise controls measure activation separately. See the
[gate experiment tooling](../../gate-experiment.md).

Coverage means intersection with **whole source-clip intervals including source
silence**, not phoneme-aligned speech coverage. It must not be described as a
percentage of spoken words captured. Actual gated ASR and trusted-context
comparisons are recorded separately from this gate-only measure.

[Gate preparation](gate-preparation.json) and [recognition aggregates](gate-results.json)
record the source/compiler/class/engine identities and exact interval summaries.
Preparation used Kotlin 2.1.20, with explicit host-runtime provenance. The original
unattested hand-compiled preparations were excluded from these inference runs.

| Gate condition | Source-clip interval coverage | Clips with no candidate audio | Candidate ranges |
| --- | ---: | ---: | ---: |
| Normal | 89.87% | 0/21 | 37 |
| Gain 0.1 | 72.05% | 3/21 | 40 |
| Gain 0.03 | 36.22% | 6/21 | 39 |

The 0.03 condition has gate-only evidence; it was not decoded. Each speech row
below represents seven continuous development streams with the same 490 reference
words. Three source clips per stream have fixed silence between them. Repeated
gain/context conditions are correlated and must not be pooled as independent data.

| Decoder input | Context | Word errors / 490 | WER | Lexical CER | Cold probe seconds |
| --- | --- | ---: | ---: | ---: | ---: |
| gated, normal | off | 128 | 26.12% | 13.37% | 165.42 |
| gated, normal | on | 137 | 27.96% | 15.10% | 168.66 |
| gated, quiet | off | 172 | 35.10% | 24.62% | 246.06 |
| gated, quiet | on | 176 | 35.92% | 25.52% | 217.96 |
| direct-source, quiet | off | 84 | 17.14% | 6.57% | 101.33 |

Current gating loses useful acoustic information in this constructed corpus.
Normal gated WER is 26.12% without context and 27.96% with current trusted
context, versus 18.98% for the complete isolated normal clips. Model hash and
common native-library hashes match that older CLI control; wrapper binaries
are different, so the comparison is explicitly scoped. Concatenating its
per-clip scores by stream preserves 93/490 errors. Among context-on/off pairs,
all 28 chunks with identical PCM and empty prompts produced identical native
text/timestamps; nine nonempty-prompt chunks account for the divergence.

The stronger matched quiet control uses the same probe and gain in both paths:
complete source intervals give 84 errors (17.14% WER), versus 172 (35.10%) through
the gate without context. Two English clips and one French clip receive no
candidate audio at gain 0.1. Context does not recover missing audio. This makes
quiet-speech activation and framing a concrete next priority, before assuming a
model replacement fixes continuous dictation. No phoneme-level omission count
or spontaneous phone-dictation accuracy is inferred from these examples.

The quiet gated run includes large decode-time outliers: German context-off
2.30/7.58-second slices took approximately 31.47/59.90 seconds to decode.
These observed costs are retained, not trimmed away. All stages use cold probe
processes; they exclude the app's persistent-engine advantage and are not phone
latency estimates.

The gate produces zero candidates on each 30-second zero/DC/seeded-low-noise
control. Explicitly bypassing the gate shows why removing it alone is not an acceptable fix:

| 30-second control | English inserted words | Russian inserted words |
| --- | ---: | ---: |
| Zero | 1 | 2 |
| Constant DC | 1 | 1 |
| Seeded low-level noise | 2 | 87 |

Gated versions perform no inference and emit no text. Bypass results cover only
English and Russian; they do not establish behavior on all seven languages or
real room noise. Lower thresholds or alternative voice activity detection must
be evaluated against both speech omissions and false activation/hallucinations.
The measured gate/context findings block an unqualified claim of production
STT quality; production parameters remain unchanged pending a validated policy.


## Download budget and decision

[The conservative ledger](download-budget.json) reserves **645,592,677 bytes**
against the shared 1,000,000,000-byte ceiling, including retries, metadata and
four checkpoint CI model/fixture reservations. Selected corpus prefixes total
38,797,312 bytes; an additional 1,048,576-byte
exploratory prefix probe is also charged, for 39,845,888 bytes of prefix transfers.
No whole corpus shard was fetched. Future environments must carry this total
forward. Qwen Q8's approximately 639 MB file does not fit the remainder and
was not downloaded.

Keep experimental defaults out of Android until there is adequate independent
speech evidence and acceptable phone cost. Next evidence should cover consented
Russian dictation and other languages, independently identified speakers/sessions,
quiet/noisy onset/endings, long pauses, real boundaries and adjudicated critical
errors. The [quality report](../../quality-report.md) tracks application QA;
[device validation](../../device-validation.md) retains physical ARM64 gates.
Audio, references, hypotheses and raw logs remain ignored local artifacts.
