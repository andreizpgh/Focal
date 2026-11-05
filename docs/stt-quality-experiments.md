# Local speech-quality experiments

The [November 5 decision review](stt-decision-review-2026-11-05.md) revisits
candidate selection, input coverage and audio-grounded correction. It supersedes
model-family conclusions, not historical measurements or failed acceptance
criteria. The next priority is core transcription, not Review UI refinement.

Research plan and measured checkpoint, **2026-11-03**. No automatic correction
is enabled. The app retains multilingual base Q5_1, beam 3 and full encoder
context. The current environment can obtain pinned Hugging Face artifacts;
paired host measurements now cover seven languages on a small FLEURS subset.
See [the benchmark report](benchmarks/2026-11-03-stt/report.md) for actual results,
source hashes and limitations. Read speech on this subset does not establish
spontaneous dictation quality or phone performance.

Research may investigate local ASR corrections. Shipping a correction requires a
measured gain and must preserve what was spoken, including deliberate factual
mistakes, unusual names, repetitions, and imperfect grammar. A text-only model
cannot establish which of two plausible words was actually said.

## New local comparison · 2026-11-05

The current Android full-PCM check recovered words missed by the energy gate on
one English recording, but a separate control introduced four words on previously
skipped weak noise. Automatic gate bypass remains off; see the
[framing report](benchmarks/2026-11-05-stt/report.md#whisper-framing-speech-recovery-and-a-noise-regression).

The bounded [FUTO ACFT Base](https://github.com/futo-org/whisper-acft) comparison
is complete. This model is trained for short encoder contexts and used in FUTO
Voice Input. Its multilingual
Q8_0 artifact was fetched once (81,768,602 bytes) and matches the
[official catalog](https://github.com/futo-org/voice-input/blob/c0bbb05b47f02e1df089bed5bf63736ae1735d1a/shared/src/main/java/org/futo/voiceinput/shared/Models.kt#L190-L194)
SHA-256 `e44f352c9aa2c3609dece20c733c4ad4a75c28cd9ab07d005383df55fa96efc4`.
The [model card](https://huggingface.co/futo-org/acft-whisper-base) identifies
Apache-2.0 and the intended dynamic-context use. This is a new configuration
comparison, not a retry of shortening the stock model. It is not promoted:
seven ordinary DEV pairs gave 25→28/210 word errors and 47→46/1,050 lexical
character errors, with host process wall time 40.929→19.880s. Quiet instructions
and noise remain problematic. The notebook-style context and the formula from
FUTO's Android code were frozen and measured separately; neither stopped screen
was retrospectively passed by the diagnostic completion. See the
[comparison and limits](benchmarks/2026-11-05-stt/report.md#futo-acft-context-formulas-kept-separate).
Keep the fresh held-out cohort unopened and stop this context sweep. FUTO's newer
[asr4all-l](https://huggingface.co/futo-org/asr4all-l) explicitly supports English
only, so it is not a replacement for the seven-language backend and was not
downloaded.


The [new aggregate report](benchmarks/2026-11-05-stt/report.md) records a Qwen3-ASR
screen and a separate full-text correction experiment. Qwen reduced word errors
on seven ordinary DEV clips, but generated a repeated paragraph on noise until
its output cap; it is not promoted. Full-text Qwen3.5-2B correction repaired four
synthetic typos and one reference-supported DEV word, but also accepted an
incorrect spelling repair. Automatic edits remain disabled. These are new
experiments; earlier failures below are not reclassified as successes.

Parakeet TDT v3 INT8 completed the 21-case screen and a 42-clip DEV comparison,
reusing the installed runtime and verified earlier outputs. DEV word errors
fell 146 → 98 / 864, but Spanish worsened 15 → 44 / 177. Its language selection
is automatic. The Kotlin replay on existing gate ranges has completed: 129
speech and two noise chunks, normal word errors 223 → 102 / 864 and attenuated
256 → 181 / 864. Quiet German WER and Portuguese CER worsened, the gate missed
two normal and four quiet English clips, and no speech-overlap boundary was
exercised. The earlier Spanish full-WAV translation did not recur on the frozen
ranges; this does not prove a fix. Astra reviewed all 131 raw outputs and replay.
The isolated Android prototype passed one test / three inputs in 25.844s; see
[the probe guide](parakeet-android-probe.md). The app backend is unchanged;
a 43-case gate-only audit rejected a global floor reduction because it activates
more noise. The explicit alternative-transcript preview is now an opt-in debug
experiment with original-data, rotation, Copy and cancellation checks. Its actual
16s/500ms path failed the 18-input DEV screen: normal word errors 25 → 53 / 210,
attenuated 31 → 52 / 210, dominated by the retained Spanish outlier. All four
no-speech controls were blank. It is not promoted and does not replace the
manual-language capture path. See [the exact-path report](benchmarks/2026-11-05-stt/report.md).
The 42 unused held-out clips remain closed. Heavy work runs serially with an
explicit memory/CPU limit after an unexpected host reboot of unverified cause.

## What the earlier measured checkpoint changes

The [fresh local continuation](benchmarks/2026-11-03-local/report.md) retains
42 development clips and a separate unconsumed 42-clip cohort. Lower energy
thresholds were rejected; offline Silero with VAD-only gain improves aggregate
development WER but has condition-specific regressions. Actual emulator recovery
still hallucinates on weak varying input. The pinned engine's nearly uniform
no-speech values are being investigated in a separate research build; they must
not be treated as calibrated evidence for rejection. Production framing/models
remain unchanged, apart from a proven exact-constant PCM fast path.


The frozen held-out screen gives small/auto 69/847 word errors versus E0's
139/847, but English worsens by one word and cold host cost increases. It does
not pass the all-language or phone gates, so the app default is unchanged.

Actual gate/context replay identifies a more direct next priority. On matched
quiet development audio, complete source intervals give 84/490 errors; current
gating gives 172/490 without context and 176/490 with it. Context cannot recover
excluded audio. Disabling the gate is not a sufficient fix: a 30-second seeded
noise control produces 87 inserted Russian words in the bypass experiment.
Evaluate quieter activation/framing or an alternative voice activity detector
against both omissions and false activation, with fresh held-out phone speech.
Do not tune another model/policy on the already consumed test split. Keep E5
and automatic correction off while this capture-to-decoder quality gap is open.

## Practical direction after primary-source review

Checked **2026-11-04**. The product remains record → review/edit → copy, with
recording continuing after Home or screen lock until an explicit Finish or a
reported interruption. Keep Sessions / Focus wave, explicit speech language,
original audio and one bounded transcription worker. Background capture is a
core acceptance requirement. Do not add live partial hypotheses, diarization,
word alignment, cloud services or a model catalogue to complete this note-taking
workflow. The owner's later feedback specifically requests local language
correction research and visible, reversible edits. Evaluate that separately in
the bounded shadow screen below; no correction model has yet demonstrated a
paired accuracy benefit or passed the phone resource gates.

The following are maintainer documentation and a published research paper.
Shipped applications demonstrate product patterns; their troubleshooting advice
is not a controlled seven-language Android result.

| Primary source | Evidence and limit | Decision for Focal |
| --- | --- | --- |
| [FUTO Voice Input: context fine-tuning](https://github.com/futo-org/whisper-acft#results) | Used in Android Voice Input v1.3.2+. On the first 128 VoxPopuli DE examples, stock base WER was 22.47 with full context and 318.15 with dynamic context; the specially fine-tuned dynamic-context model scored 22.31. This is not a seven-language phone benchmark. | Keep full encoder context for the current stock base Q5_1. Do not copy a speed setting without its trained model and paired evaluation. |
| [FUTO Keyboard voice input](https://docs.keyboard.futo.tech/settings/voiceinput) | Long-form mode removes a default 30-second dictation limit, with a repetition warning. Silence auto-stop is optional and may need manual intervention in noise. | Keep explicit Finish. A model window length is not a recording-duration limit; pauses must not end a note. |
| [Aiko maintainer FAQ](https://sindresorhus.com/aiko) | Local Whisper, explicit audio language, recording before transcription and retained recordings for retry. Optional silence skipping addresses invented tails/repetition. The supported-language list includes Focal's seven, but quality varies. Its iOS background restriction does not describe Android. | Preserve source audio and manual language selection. A usable note-taking product does not require streaming partial text. Keep the existing worker; this is not a reason to rewrite scheduling. |
| [VoiceInk hallucination guidance](https://tryvoiceink.com/docs/repeated-text-or-hallucinations) | Recommends VAD and checking microphone input. Retry uses the saved audio and creates another history entry. No paired WER or safe VAD threshold is supplied. | Treat VAD as a hypothesis, preserve edits/source and make uncertain output reviewable. Do not import phrase blacklists or treat low speech probability as proof of silence. |
| [WhisperX, Interspeech 2023, Tables 1/3 and §3.3](https://www.isca-archive.org/interspeech_2023/bain23_interspeech.pdf) | VAD Cut & Merge combines speech into windows up to 30 seconds. TED-LIUM WER 10.52→9.70 and 2.7× speed at batch size 1 used large-v2/greedy on NVIDIA A40. This is English long-form GPU evidence, not base/Silero/Android evidence. | Avoid cutting every short detected speech island into a separate ASR call. Test preserved phrase boundaries on the real note pipeline; do not promise the paper's speedup. |

The [upstream whisper.cpp Android sample](https://github.com/ggml-org/whisper.cpp/blob/master/examples/whisper.android/README.md)
recommends tiny/base and a release build. It supports retaining the existing
base model as a practical baseline, not an accuracy or phone-performance claim.
Newer alternatives do not justify an immediate engine migration:
[sherpa-onnx Qwen3-ASR 0.6B INT8](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html)
lists all seven languages, but its three ONNX files are approximately 937 MiB
combined, beyond the budget remaining at that review; the later 5 GB ceiling
removes that download blocker. Model-file size is not runtime memory.
[Moonshine's current language list](https://github.com/moonshine-ai/moonshine/blob/main/docs/index.md)
does not establish all seven for STT; several required languages appear under
TTS instead. No alternative ASR model or runtime was downloaded or integrated
in that review.

Android explicitly provides the [microphone foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
for continuing capture in the background. Start it from the visible app after
microphone permission; background creation is restricted. Focal already uses
this service, notification controls and a partial wake lock while capture or
decoding needs the CPU. Hold wake locks only while needed, as described in the
[Android guide](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock).
The [Android 15+ six-hour processing limits](https://developer.android.com/develop/background-work/services/fgs/timeout)
apply to dataSync/mediaProcessing, not microphone capture; Focal changes service
type for finishing transcription and handles timeout as recoverable work.
User force-stop stops capture; reopening offers recovery of durable audio without
restarting the microphone. OEM background restrictions still need device checks.
The earlier 2.5-hour screen-off AVD capture had host audio disabled; real speech
continuity, calls, battery and thermals remain in the [phone matrix](device-validation.md).

Independent focused Sol/Terra research and an Astra critical review support the
following scope decision. Defer the terminal-only recovery skip: its narrow
benefit would require durable skipped-ASR provenance, explicit re-decode and
manual-edit protection. The private cached audit compared segment-based presence
with admission on any valid frame at probability ≥0.5. Decisions were identical
for 70 requests (28 synthetic positives, 28 noise/context cases and
14 DEV terminal ranges); it adds no fresh inference or timing evidence. Do not
build that feature or run its proposed 56 paired controls simply to extend the
benchmark history. No such run or production change was made.

The next quality comparison must concern ordinary notes: whole quiet words,
phrase boundaries, pauses and noise, with one frozen candidate and early rejection
on a critical omission. Do not resume rejected SOT/beam/context variants or tune
on the unopened 42-clip local test cohort. For speed, inspect avoidable loading
and copies while preserving decoder inputs and outputs; accept a change only
with matched measurements. General seven-language quality/speed and physical
phone acceptance remain open. This review changes priorities, not acceptance gates.

## Historical bounded alternatives

The table below records the original comparison plan and completed research
context. It is not a queue to execute before shipping; use the practical scope
above and the measured rejection reasons to choose any further experiment.

Keep the same immutable audio, explicit language, pinned engine, chunk ranges,
and test split for each paired run. Change one variable at a time.

| Experiment | Concrete comparison | Evidence it could add | Main cost or failure mode |
| --- | --- | --- | --- |
| E0 | Current multilingual base Q5_1, beam 3, current boundary/context handling | Reference system | Measured small-corpus baseline; phone dictation remains open |
| E1 | Base Q8_0 versus base Q5_1, otherwise identical | Isolate quantization loss | More memory/download bytes; a gain is not guaranteed |
| E2 | Multilingual small Q5_1 as the first pass versus E0 | More acoustic-model capacity | Extra decode time, memory and heat; verify each language |
| E3 | E0 plus small Q5_1 re-decoding only selected saved-audio ranges | Acoustic evidence for uncertain words and boundary cuts | Cold-load cost, correlated errors, selection misses confident mistakes |
| E4 | Context on/off and gate ablations using the same original recording | Separate prompt contamination, missed speech and ASR errors | A quieter threshold can create noise-only work and hallucinations |
| E5 | E0/E3 plus Qwen3-0.6B constrained choice between acoustic alternatives | Language context can reject an implausible alternative | Fluent but wrong replacements; extra runtime and model residency |

The original plan ordered E0/E1/E2 before E3 and any E5 comparison. Further work
on these alternatives is deferred unless new evidence justifies it. Any future
comparison must count extra loading and background contention and use the
strongest accepted ASR-only baseline. Two Whisper passes are not independent
evidence merely because they agree.

OpenAI lists base at 74M parameters and small at 244M, and explicitly documents
uneven language performance, hallucinations and repetition. Larger capacity is a
hypothesis to test on Focal's audio, not a promised improvement.
([Official Whisper model card](https://github.com/openai/whisper/blob/main/model-card.md))

Qwen's official release identifies a 0.6B model, Apache-2.0 licensing, support for
119 languages/dialects, and a hard non-thinking chat-template switch. These are
deployment/language-coverage facts, not evidence of ASR correction precision. Pin
the exact template with `enable_thinking=False`; `/no_think` text alone is a soft
instruction. Short, bounded outputs matter more here than a large context window.
([Qwen release](https://qwenlm.github.io/blog/qwen3/),
[official model card](https://huggingface.co/Qwen/Qwen3-0.6B))

Gemma 3 270M-IT is a possible later lower-capacity comparator. Google's launch
describes it as a model for task-specific fine-tuning; its size does not establish
seven-language correction accuracy. Check its separate Gemma license/access
terms and model-specific evidence first. It is not part of the download plan
below, and no fine-tuned derivative is selected.
([Google's 270M announcement](https://developers.googleblog.com/introducing-gemma-3-270m/),
[official model](https://huggingface.co/google/gemma-3-270m-it))

## One shared download budget

The current cumulative ceiling is **8,500,000,000 bytes**, approved on November 5.
The [shared ledger](benchmarks/2026-11-03-stt/download-budget.json) reserves
**7,850,106,526 bytes**, leaving **649,893,474**. This includes every reservation
described below, the new Ultra graphs, Gemma audio bundle, API runtime and metadata.

The owner previously increased the ceiling to **5,000,000,000 bytes** on
2026-11-04 for removable local research artifacts. Count models, corpus,
metadata and failed/repeated transfers across sessions. Fetch metadata first,
pin immutable revisions and SHA-256 hashes, and reserve the full declared size
before each request. Reuse verified files; a larger budget does not justify
fetching whole corpus shards or every model variant.

Before the Ultra/Gemma audio comparison, the shared ledger reserved
**4,557,289,366 bytes**, leaving **442,710,634 bytes**. This retains the earlier
881,742,333 bytes, including the 61,000,000-byte future CI reserve, and adds
17,858 bytes of initial metadata, 196,608 bytes reserved for runtime/candidate
metadata, two Qwen weight transfers of 396,705,472 and 1,280,835,840 bytes,
and 190,085,487 bytes for the locally missing Whisper small research file.
The November 5 continuation also includes 2,200,000 bytes reserved for metadata,
1,001,817,053 bytes for Qwen-ASR files/runtime, 670,478,772 bytes for Parakeet,
1,000,000 bytes Android runtime metadata and one verified 49,441,341-byte
official Android AAR transfer. The ACFT comparison adds a 1,000,000-byte metadata
reserve and one verified 81,768,602-byte model transfer. Existing model files
were reused.
The old cloud small transfer remains counted; this local transfer adds to it.
The original allocation below is historical context, not an instruction to
repeat downloads. The older corpus figures describe the previous cloud study;
complete shards were never fetched. Keep recordings and weights outside Git.

| Artifact | Maximum reservation | Stage |
| --- | ---: | --- |
| Existing base Q5_1 baseline, if unavailable locally | 60,000,000 bytes | E0 |
| Base Q8_0 | 82,000,000 bytes | E1, if needed |
| Small Q5_1 | 191,000,000 bytes | E2/E3, if needed |
| Selected public audio and references, including archive overhead | 20,000,000 bytes | E0–E4, if needed |
| Official Qwen3-0.6B Q8_0 GGUF | 640,000,000 bytes | E5, only after E0–E4 |
| Combined upper bound | **993,000,000 bytes** | Only if every stage is justified |

These rounded reservations come from upstream listings of about 59.7/81.8/190 MB
for the ASR files and 639 MB for Qwen. Recheck exact byte lengths before fetching.
The official Qwen GGUF repository currently lists Q8_0 only: a community Q4 file
must not be described as an official Qwen artifact. One optional local
requantization of the verified Q8_0 file avoids another download; record its input
hash, pinned quantizer commit/options and output hash, and re-evaluate its quality.
This is a second lossy quantization, not an equivalent artifact to Q4 generated
directly from the original floating-point weights.
Model-file size is not peak runtime memory.
([Upstream ASR files](https://huggingface.co/ggerganov/whisper.cpp/tree/main),
[official Qwen GGUF files](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/main))

### Mixed-language glossary screen · 2026-11-04

A prompt-only comparison of `HTML, API, guardrails.` against an empty prompt
failed the frozen Russian DEV screen. Six paired synthetic fixtures used the
same PCM, including quiet, absent-term and varying-noise controls. Twelve cold
host calls produced one additional exact term, but five pairs regressed on at
least one guard; noise insertions rose from five to seven words. Term matching
ignores capitalization, so changing letter case cannot qualify as a gain.
The other six languages were not run, and the hint is not shipped. eSpeak
fixtures are a cheap failure screen, not a measurement of natural code switching
or Pixel recognition. [Aggregate results](benchmarks/2026-11-03-local/mixed-term-hint-results.json)

### Historical shortlist after the November 4 budget increase

This records the earlier selection. Completed Qwen-ASR, Small, Ultra and Gemma
audio results now appear in the [November 5 decision review](stt-decision-review-2026-11-05.md).

The larger allowance supports measured comparisons, not a model catalogue in
the app. Qwen3.5-2B Q4_K_M was selected as one modern local comparator at
1,280,835,840 bytes. Its official card lists multilingual and instruction
benchmarks, not ASR correction precision. Gemma was also considered: the newer
Gemma 4 E2B has a published mobile path, but its examined Q4_0 text artifact is
2,841,481,184 bytes, before runtime memory. It has not been downloaded or tested
here. Neither vendor benchmark selects an optimal note-taking model for Pixel 10.
[Qwen3.5-2B](https://huggingface.co/Qwen/Qwen3.5-2B),
[Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4),
[pinned Gemma text artifact](https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/blob/6938c58e698502037401a5f19f6905f2fb2f7dee/gemma-4-E2B-it-Q4_0.gguf)

For ASR, Whisper small can use the existing host note-replay pipeline with only
the model changed. Its earlier isolated EN/PT regressions require early noise
and negation rejection before a longer seven-language run. Qwen3-ASR 0.6B is a
credible later acoustic alternative now that download size fits the ceiling;
its different Android runtime, audio limits, timestamps and silent-input behavior
need a bounded host check before any integration. Keep the app on the accepted
base configuration until paired evidence warrants changing it.
[Qwen3-ASR publisher](https://github.com/QwenLM/Qwen3-ASR),
[sherpa-onnx Qwen3-ASR models](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html)

### Whisper small warm control screen · 2026-11-04

The locally missing pinned small Q5_1 file was downloaded once and checked against
`models/stt-candidates.json`; the earlier cloud transfer remains in the budget.
Eight Portuguese controls reused the historical warm host binary, exact audio,
prompts and call order. Only the model changed within that decoder pipeline.
Four noise controls reduced inserted words from 247 to 4, but the quiet
one-word negation changed from correct to wrong (0→1 word error, 0→3 character
errors), both before and after passage commit. This critical regression rejects
the configuration; no subsequent ordinary-speech or other-language sessions ran.
[Control results](benchmarks/2026-11-03-local/whisper-small-warm-controls-results.json)

The run took 369.27 host seconds with the historical four decoder threads
restricted to two logical CPUs. Baseline scheduling differed, so no speed
comparison is claimed. These are synthetic host controls, not microphone or
Pixel results; the remaining noise output is still a failure. Android's current
model validator does not accept small. No model substitution was shipped.

### Local correction shadow screen · 2026-11-04

The Unsloth Qwen3-0.6B Q4_K_M candidate is **396,705,472 bytes** and has now
been downloaded locally and verified against SHA-256
`ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`.
It is a community quantization, separate from Qwen's official Q8 artifact.
The 21-case source-only run completed with 16 abstentions, five invalid replies
and no valid edit. It failed the frozen screen; it is not integrated into the app.
[Pinned Unsloth artifact](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/blob/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf)

The 35 synthetic fixtures with supplied choices remain an offline format check.
A separate 21-request shadow plan gives the model only text and language. It
pairs seven already-consumed DEV ASR excerpts with clean reference controls
and includes seven synthetic stress cases. The model can abstain or find one
short, exact-source replacement. Acoustic adjudication is required for any
claimed speech correction.

A separately registered Qwen3.5-2B Q4_K_M comparison kept the same 21 texts,
order, instructions, caps and validator. It returned 20 abstentions and one
reply that exhausted the 48-token limit, with no valid edit. Invalid replies
are not counted as successful abstentions; the frozen complete-validation rule
withheld paired aggregate WER/CER for both runs. The 0.6B and 2B runs took 24.62
and 142.36 host seconds respectively, including initial loading. Different architectures, templates,
quantizations and prefix-cache behavior prevent treating this as a phone speed
comparison. [0.6B aggregate](benchmarks/2026-11-03-local/correction-shadow-qwen3-06b-results.json),
[2B aggregate](benchmarks/2026-11-03-local/correction-shadow-qwen35-2b-results.json)

The 2B host configuration uses a generic chat template verified byte-for-byte
against Ollama's Qwen3.5 renderer for these 21 two-message inputs. This preserves
the JSON grammar with thinking disabled on Ollama 0.21.2. An initial diagnostic
request used the wrong debug field and generated one extra response with the
reference parser. It is preserved separately and excluded from the 21-case
comparison. The corrected 42 render-only calls performed no token generation. No
prompt, example or scoring threshold was tuned from that accidental answer.

A subsequent source review identified an interface limitation: the sorted JSON
serializer puts `replacement` before `source`, and the pinned runtime generates
required properties in schema order. This does not establish causation, but
a separate order-only diagnostic tested eight new synthetic texts with both
schemas (16 calls, four languages). Each arm returned five abstentions and three
contract-invalid replies, with zero valid repairs; three parseable edit objects
per arm confirmed the actual field order. Reordering alone did not improve valid
repair yield on these canaries. Some rejected whole-sentence outputs did repair
a typo, while another chose a wrong word. That is a reason to distinguish
language ability from the response contract, not to reclassify invalid outputs
as passes. The original 21 cases were not rerun, and no further LLM calls are
planned from this result. The failed screens do not prove that Qwen, Gemma or
text correction generally cannot help.
[Schema-order diagnostic](benchmarks/2026-11-03-local/correction-schema-order-results.json),
[Ollama's pinned grammar converter](https://github.com/ollama/ollama/blob/v0.21.2/llama/llama.cpp/common/json-schema-to-grammar.cpp)

Automatic correction remains disabled pending the quality, provenance, Undo
and phone gates below.

A small downloaded read-speech subset cannot establish a high-precision release
claim. The current 56 clips span 650.1 seconds; 21 development clips and 35 official
test clips are selected in archive order without looking at ASR or reference text.
Speaker IDs are unavailable, so dev/test speaker independence is unverified.
These are utterance splits, not the speaker/session split required for promotion. Expand the evaluation with consented local phone
recordings, which require no model/corpus download. Preserve attribution and
consent provenance outside Git. FLEURS is a candidate multilingual read-speech
sanity check with CC-BY-4.0 attribution; report it separately from spontaneous
dictation, noise and user speech. The source's full dataset is not the planned
download. ([Google FLEURS dataset](https://huggingface.co/datasets/google/fleurs))

## Paired seven-language evaluation

Use English, Russian, German, French, Spanish, Italian and Portuguese. The
initial FLEURS sanity check does not satisfy all the following release criteria. Split by
speaker/session into development and held-out test sets before tuning. Do not put
adjacent cuts from the same recording in both sets. References are for scoring
only; the correction runner must never receive them. Freeze model/engine hashes,
prompt/template, quantization, candidate selection and acceptance policy before
opening test results. A failed held-out result requires a new held-out set after
policy changes.

For every language include ordinary speech, quiet/onset/final syllables, long
pauses/no speech, room noise, fast speech, restarts, rare/proper names, addresses,
dates/amounts/units, and negation. Include deliberately unusual but correctly
spoken sentences that a language model might try to make more plausible. Add
continuous clips crossing the actual gate boundary and pause/resume; isolated
WAV decoding alone does not exercise this path. Russian needs the user's own
consented dictation among the held-out material, not just an English smoke test.

Record and compare:

- Paired WER and lexical CER per language, overall micro averages and an explicit
  per-language table; literal CER separately retains punctuation, spacing and case.
  Do not count punctuation-only polish as a factual recognition gain.
- Missing/extra words at boundaries, gate-missed spoken audio, and hallucinated
  words on silence. These remain separate from ordinary substitutions.
- Precision of **accepted changed spans**, coverage/abstention and incorrect or
  uncertain accepted spans. A fluent replacement and a net WER gain are not human
  labels of correctness. Review accepted spans against audio, blinded to method.
- Introduced errors, including damage to initially correct words inside a larger
  edit. Improved errors elsewhere must not cancel these in the report. Annotate
  names, numbers and negation independently, including failures of protection.
- Paired confidence intervals resampled by speaker/session, not by repeated timing
  trials. Review ties/ambiguous speech as uncertain; do not manufacture a reference.

## Acceptance and abstention

Start with shadow evaluation. Token probabilities, model self-confidence and
agreement between related models are uncalibrated features. Choose a small
acceptance rule on development audio and evaluate its entire risk/coverage curve.
The source-only discovery screen allows one short replacement or abstention.
An acoustic-choice comparison may instead use a small set of supplied alternatives.
For free text, return only a bounded list of edits against a hashed
source revision: at most four short spans, no full-document regeneration.
Validate offsets, non-overlap, source hash, output length and exact unchanged text
outside accepted spans. Keep invalid and late outputs separate from explicit
abstentions in shadow results. Only valid proposals can reach a correction preview.

A starting experiment cap is 256 input tokens and 48 output tokens, no retained
conversation and no thinking output. These caps are experimental settings, not
an efficiency claim. JSON grammar constrains syntax; it cannot prove that a word
was spoken or that offsets are correct. Validate the result in ordinary code.
([llama.cpp grammar documentation](https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md))

Abstain on unresolved acoustic disagreement, weak/absent speech, wrong language,
unsupported vocabulary, stale source revision, resource pressure or deadline.
Protect names, numbers and negation conservatively; validate the protection
mechanism itself on all seven languages. Capitalization is not a reliable named
entity detector. A text-only guess must not invent a name, amount or negation.
Disfluencies and grammar stay as spoken. When evidence is insufficient, keep the
original ASR text and its existing review cue.

Freeze proposed release gates **before** held-out evaluation:

1. At least 5% relative aggregate WER reduction versus the strongest affordable
   ASR-only baseline, with the paired 95% interval supporting an improvement.
   Require a meaningful Russian gain; no supported language may regress. Check
   CER and critical categories too. Adjusting this target later needs a new test.
2. Accepted-span precision's lower two-sided 95% Wilson bound at least 99.5%
   **in every language**, complete adjudication, and zero introduced critical
   name/number/negation errors. With 800 independent perfect accepted spans the
   Wilson lower bound is about 99.52%; a handful of perfect edits proves little.
   Correlated spans require speaker/session analysis and more evidence. Insufficient
   accepted spans mean inconclusive, even when coverage is intentionally low.
3. No regression in gate coverage, boundary omission/repetition, capture durability,
   original audio, manual edits or the existing recovery/device tests.
4. Pass the separately frozen phone resource/interaction gates below. Host text
   metrics alone never authorize shipping. Failure or inconclusive evidence keeps
   correction disabled; a language-specific rollout also needs its own evaluation.

## Phone cost and unobtrusive behavior

Measure at least one midrange and one older ARM64 phone, including a 4 GB device.
Alternate baseline/candidate order, use identical audio, comparable starting
temperature/charge/screen state and repeated cold/warm trials. Record device/SoC,
OS, engine/model/template hashes, thread count, context/output caps and run order.
Separate model loading, input prefill, generation/second decode, queue time and
Finish-to-ready latency. Report P50/P95, peak RSS and PSS, memory growth across
repeated sessions, thermal status/duration and measured energy for 30-minute
dictation plus silence. Compare incremental cost with the ASR-only run.

Starting UX targets to freeze before testing: correction never delays microphone
capture, first text, Copy, navigation or manual saving; warm incremental P95 at
most 500 ms per selected short range on the midrange phone and 1 s on the older
phone. Cold cost is reported separately and may require abstention. Total peak
memory should stay below 25% of device RAM without low-memory kills; reject leaks.
Target no more than 10% extra energy over the ASR-only 30-minute trial and no
sustained thermal-status increase. These are proposed product gates, not measured
results or universal hardware guarantees.

Use a single bounded inference queue with capture priority, at most one pending
correction per source revision. Stop/yield correction under thermal pressure,
battery saving, a new recording or an exceeded budget. Evaluate coexistence of
ASR and LLM memory; unloading/reloading shifts cost rather than eliminating it.
No UI spinner or extra confirmation step should be needed for the common path.

Use Android power traces/ODPM where available, or a documented external meter or
charge-counter method. ODPM is device-level and availability varies; do not call
coarse battery percentages or emulator timing an app energy measurement. Thermal
status is a categorical pressure signal, not a temperature reading.
([Android Power Profiler](https://developer.android.com/studio/profile/power-profiler),
[PowerManager thermal API](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()))

## Future correction data and interaction contract

Keep the original audio **and immutable original ASR text**. The current mutable
`Passage.text` alone is insufficient provenance for automatic corrections. A future
migration would add a separate proposal/accepted-overlay history with source hash,
language, audio range, model/engine/prompt/policy versions, replacement, decision,
timestamps and undo state. A manually reviewed passage is authoritative.

Persist proposals separately; apply through a transaction checking the exact
source revision. Arrival while an editor/rename form is dirty, a save is running,
or a copy/share snapshot is being prepared must never overwrite that interaction.
Defer or discard the proposal; do not move the cursor or replace its draft.

If an accepted correction eventually clears all gates, show a restrained underline
under the changed span. Tapping exposes original ASR → correction, access to the
original audio, and **Undo**. TalkBack announces that the span was corrected and
offers the same action; color is not the only cue. Undo removes that overlay and
prevents reapplication for the same source revision. If a later manual edit
overlaps it, preserve the user's text and do not blindly replace coordinates.
Copy/share use one consistent visible revision and preserve manual edits.

## Offline correction scorer

`scripts/score-stt-corrections.py` applies accepted edits to the original, validates
their mapping and scores paired outcomes. It performs no inference/downloads and
prints only aggregates, never IDs, references or transcripts. Keep real JSONL,
audio, annotations and local reports outside Git. Its host tests use synthetic
sentences to check the scorer, not to claim model quality.

Each JSONL record has `id`, `language`, explicit `split` (`dev`/`test`), a
speaker/session `group`, `reference`, `original`, optional `original_sha256`
(SHA-256 of exact UTF-8 original), and `proposals`. Each proposal has `start`,
`end`, `replacement`, `decision` (`accept`/`abstain`), optionally numeric
`confidence`, human `judgement` (`correct`/`incorrect`/`uncertain`), and human
`introduced_errors`. Offsets are half-open **Unicode code-point indices in the
unnormalized original**. Android UTF-16 indices require explicit conversion;
combining characters and surrogate pairs must not be sliced accidentally.

An optional `candidate` must exactly equal applying the accepted edits. Human
`critical_errors` maps each of `names`, `numbers`, `negation` to
`{"original": 0, "candidate": 0}` counts. Zero is an assessed result; missing
annotation stays unassessed. A correct span means a spoken ASR error was fixed
without introducing another error, not that a sentence became more grammatical.

Optional `measurements` is a list of timing/resource trials. Each has a device
label, `variant` (`original`/`candidate`), `phase` (`cold`/`warm`), and any measured
`correction_ms`, `finish_to_ready_ms`, `peak_rss_bytes`, `peak_pss_bytes`, `energy_mj`,
`peak_thermal_status` (Android 0–6). The report separates device/variant/phase/language;
missing values are not zeros. Put repeated timing trials here rather than copying
the same utterance into many supposedly independent quality rows. Archive runtime
versions and profiling-method details beside the report, not inside a transcript.

```json
{"id":"synthetic-en-1","group":"synthetic-session-a","language":"en","split":"test","reference":"Meet on Friday.","original":"Meet on fryday.","proposals":[{"start":8,"end":14,"replacement":"Friday","decision":"accept","judgement":"correct","introduced_errors":0}],"critical_errors":{"names":{"original":0,"candidate":0},"numbers":{"original":0,"candidate":0},"negation":{"original":0,"candidate":0}}}
```

```sh
python3 scripts/score-stt-corrections.py /private/paired.jsonl --split test --require-all-languages --output /private/correction-metrics.json
python3 -m unittest discover -s scripts/tests -p 'test_score_stt_corrections.py'
```

The report includes WER, lexical/literal CER, changed-span precision and its Wilson
lower bound, introduced-error annotation coverage, critical errors, initially
correct clips harmed, silence insertions, grouped paired WER delta intervals and
resource percentiles. Positive WER delta means worse. Missing span labels yield
null precision, never an inferred 100%. Critical-category corpus totals are null
unless every clip is assessed; assessed-subset counts remain separately visible.
The paired interval is null with fewer than 1,000 bootstrap samples or fewer than
two reference-bearing groups; two groups are still inadequate for strong release
claims. The script rejects duplicate clips and
explicit speaker/session groups crossing dev/test. Ungrouped data is only a smoke
screen; the report is not an automatic shipping decision.
