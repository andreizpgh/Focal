# STT decision review · 2026-11-05

The next product milestone is better transcription, not another Review UI.
Ultra is now the primary candidate: on the 42 consumed DEV recordings it reduces
Base's 146 word errors to 51, improving every supported language. Audio-grounded
Gemma correction adds too little measured benefit to justify enabling it now.
Alpha 7's reversible background-sound decisions do not improve ordinary ASR.
No new backend or automatic language correction is enabled by this review.

## What the existing measurements actually establish

The previous experiments remain immutable, including their failed acceptance
criteria. A rejected configuration is not a rejection of an entire model family.

| Existing experiment | Observation | Decision supported |
| --- | --- | --- |
| Whisper Small Q5, historical auto-context profile | 139 → 69 word errors / 847; Russian 12 → 3; English 7 → 8. Historical cold host time 189.95 → 273.45 s. | A substantial quality candidate. The frozen no-language-regression criterion failed; a one-word regression on a tiny subset does not establish general inferiority. |
| Small, later warm/full-context PT screen | Eight controls only; noise insertions 247 → 4, but a quiet negation regressed. Ordinary seven-language DEV was never run. | Do not ship that configuration. Do not claim ordinary DEV disproved Small. |
| Parakeet v3 INT8, complete DEV WAVs | 146 → 98 errors / 864; Spanish 15 → 44, including one 41-error output. | Promising aggregate accuracy with a serious unresolved failure. |
| Parakeet Android saved-audio preview | Normal 25 → 53 / 210; digitally attenuated quiet 31 → 52. | That x86_64 deployment failed. Automatic candidate language/chunking and explicit-language Linux full-WAV decoding differed; neither a pure model ranking nor a speed comparison. |
| Qwen3-ASR 0.6B INT8 screen | Seven normal clips 28 → 21 / 144; varying-noise raw insertions 17 → 75, including 73 in the capped response after 15 valid pairs. Five mixed-term cases were unrun. | That configuration is unsuitable as the default. It does not establish a trusted verifier. |
| FUTO ACFT Base Q8 | Seven ordinary clips 25 → 28 / 210; host wall time 40.929 → 19.880 s. | Speed improved in this profile, but quality and earlier critical controls do not support selecting it. |
| Qwen3.5-2B text correction | Seven DEV passages 44 → 43 / 159; median resident host response 9.298 s. | Benefit is too small for automatic correction. This is not evidence against every local LLM. |

Sources: [original paired results](benchmarks/2026-11-03-stt/report.md),
[later experiments](benchmarks/2026-11-05-stt/report.md),
[Small control history](development-handoff.md).
The historical test subset has been consumed; it is not new independent evidence.

Input selection is a separate, measured source of errors. On constructed quiet
DEV streams, the same probe/model produced 172 errors / 490 through the gate
without context, versus 84 on complete source intervals. Later Android framing
checks also recovered English words with complete PCM, but introduced words on
one noise control. Original audio is retained; an acoustic model cannot recover
speech it is never given. Simply lowering the threshold or disabling gating
globally has already failed controls. Preserve those results and investigate
speech coverage and context alongside model quality.

## Additional model research

Sources below were checked on November 5. Publisher benchmarks select candidates;
they are not Focal measurements. Different runtimes, normalization, segmentation
and corpora prevent ranking these tables against each other.

**Parakeet Ultra is the primary new quality-oriented candidate.**
Moondream's post-trained 0.6B model retains the Parakeet architecture and tokenizer
and is CC-BY-4.0. Its paired publisher FLEURS scores improve across all seven
Focal languages; Russian WER is 5.91 → 5.21%. The publisher comparison uses
Photon/GPU versus NeMo/bf16, not the Android export. Noise robustness also improves
in its reported speech-plus-noise tests; that is not a silence-hallucination test.
[Model card](https://huggingface.co/moondream/parakeet-ultra).

There is more concrete deployment evidence than a desktop leaderboard:
Marmalade reports Ultra INT8 through Sherpa/ORT on a Pixel 8a, four CPU threads,
English LibriSpeech decode RTF 0.078, approximately 1.315 GB peak process RSS and
4.5 s load. Decode RTF is not application end-to-end latency. These are
maintainer measurements on English inputs, not our Pixel 10 or seven-language
acceptance. The 670,577,016-byte export has now been verified locally;
670,483,077 new bytes were transferred because the tokenizer was reused. The
existing Parakeet probe was reused for the completed full-PCM diagnostics below;
Android framing and export behavior still need verification before integration.
[Android measurement](https://github.com/maxwhipw/marmalade-stt-android/blob/main/docs/benchmarks/2026-10-pixel8a-ort.md).
This export uses automatic language detection and omits the embedded VAD head;
do not assume it preserves Focal's explicit-language behavior or suppresses
no-speech input by itself.
[Conversion notice](https://github.com/maxwhipw/marmalade-stt-android/blob/main/NOTICE.md#moondream-parakeet-ultra-int8-our-conversion).

**Parakeet Redux is a size/speed tradeoff, not the preferred quality upgrade.**
Its 178 MB packed Photon representation is not the size of an Android LiteRT
bundle. Despite a better 25-language FLEURS average, all seven Focal languages
are worse than original Parakeet in its own table; Russian 5.91 → 7.91%, French
4.81 → 7.71%. Speech-plus-noise WER also worsens. A smaller download alone is not
a reason to select it for the current problem.
[Model card](https://huggingface.co/moondream/parakeet-redux),
[separate LiteRT conversion](https://huggingface.co/litert-community/parakeet-redux).

**Nemotron 3.5 ASR Streaming 0.6B is the streaming alternative.** NVIDIA lists
all seven Focal languages in its transcription-ready tier, with explicit
language conditioning and cached encoder state. This can avoid recomputing
overlapping audio, unlike buffered streaming. The OpenMDW-1.1 model has native
C++ and Android-oriented deployment paths. Its official throughput figures are
not phone measurements, and language conditioning does not prove within-sentence
RU+EN recognition. Prioritize it if live latency is the limiting factor after
the quality comparison; do not add a second backend merely for novelty.
[Official model](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b),
[native runtime](https://github.com/NVIDIA/NeMo-Speech.cpp).
Marmalade separately reports an Android Pixel 8a 1120 ms configuration at
decode RTF 0.208 and about 919 MB peak RSS. These English-input measurements
establish a practical lead, not Focal quality or Pixel 10 performance.
[Android evidence](https://github.com/maxwhipw/marmalade-stt-android/blob/main/docs/benchmarks/2026-10-pixel8a-ort.md#user-lineup-default-tuning-4-threads).

## Local LLM correction remains an open hypothesis

The intended feature is a visible, reversible change backed by evidence, not an
unexplained acoustic-confidence dot. The current recognizer's uncertainty cues
are not a grammar detector, and fluent mistakes can be confident.

The existing Qwen3.5 experiment was text-only. It repaired some synthetic typos,
but yielded only one fewer DEV word error and was not adjudicated against audio.
Keep its weights and receipts. Do not buy a larger text model merely to repeat
that same test. A text-only model can plausibly replace a correct unusual name,
number, negation or technical term with the wrong fluent expression.

**Gemma 4 E2B is a relevant new candidate, beyond the earlier Gemma 3n search.**
It accepts audio and text, so an experiment can compare the draft with the
original recording. Google's report evaluates direct ASR on all seven languages
(including Russian 8.4% FLEURS WER); this does not measure transcript correction.
The model is Apache-2.0. Google publishes an offline Android audio/text sample.
[Technical report, ASR table](https://arxiv.org/html/2607.02770),
[audio guide](https://ai.google.dev/gemma/docs/capabilities/audio),
[Android sample](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/qualcomm/gemma4/cpu_gpu/README.md).

The ordinary LiteRT bundle is about 2.59 GB; the separate Tensor G5 bundle is
about 3.11 GB. Only the ordinary bundle was downloaded for this experiment. The
33.2 MB LiteRT API runtime loads successfully on the host; its published 2,588,147,712-byte weight file is now SHA-verified locally.
A separate CPU audio smoke completed after correcting a missing cache-directory
setup in the diagnostic harness. The frozen Base-draft screen returned 35/36 responses: 34 met its text-output
contract, one returned an unsupported reasoning channel, and the final direct
short-Portuguese call remained unrun. All fourteen ordinary/attenuated speech
cases were completed in both modes. Direct ASR gave 15/18 word errors on the
normal/quiet groups; Base-draft correction gave 16/20, versus Ultra 15/14.
Correction retained all twelve noise insertions from its Base draft. This
does not establish an advantage over the stronger ASR alone.
[Partial screen and limitations](benchmarks/2026-11-05-stt/gemma4-audio-screen.json).

The separate frozen audio-plus-Ultra test completed all 18 correction calls,
with no repeated direct-ASR calls. Normal word errors fall only 15 → 14 / 210;
quiet errors remain 14, and lexical character errors rise 33 → 40 and 31 → 38.
Russian improves; Italian's WER increase reflects digit-to-word formatting,
while Spanish has both a repair and a new pronoun mismatch. On empty noise
drafts, Gemma introduces 30 words. A future empty-draft guard could avoid those
calls, but does not demonstrate a correction benefit on speech. The added call
takes a median 9.86 host seconds; phone costs are unmeasured. This supports
prioritizing the stronger ASR without a mandatory LLM, not rejecting all LLMs.
[Ultra-draft result](benchmarks/2026-11-05-stt/gemma4-ultra-correction.json).

Google documents a Tensor G5 deployment route, but
audio compatibility and costs of the exact artifact still need a
device check. Published Galaxy S26 text-generation throughput and CPU memory
figures cannot establish Pixel 10 audio latency, total memory or battery use.
[Artifacts](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main),
[Tensor route](https://developers.google.com/edge/tensor-sdk/slm-compilation),
[mobile benchmark limits](https://developers.google.com/edge/litert-lm/models/gemma-4).

Gemini Nano's ML Kit Proofreading is not a seven-language shortcut: its supported
language list excludes Russian and Portuguese, and it processes text rather than
checking the recording. Pixel family support alone does not establish feature
availability on a particular device.
[Official API limits](https://developers.google.com/ml-kit/genai/proofreading/android).

A recent repair/damage study found substantially fewer damaged correct
transcripts with audio in one Gemini experiment, but prompts differed and the
other model rarely edited. This motivates a paired local experiment, not a
promise of the same gain from Gemma.
[Research and limitations](https://arxiv.org/abs/2610.30385).

## New fixed Small diagnostic

The missing ordinary-speech check is now complete: 18 new candidate calls,
all on already consumed DEV and controls, with no tuning or held-out access.
The same seven source recordings appear at normal level and at gain 0.1;
these are seven unique source clips, not fourteen.
[Audited aggregate](benchmarks/2026-11-05-stt/small-auto-diagnostic.json).

| Condition | Base/full word errors | Small/auto word errors |
| --- | ---: | ---: |
| Ordinary speech, 210 reference words | 25 | 18 |
| Attenuated versions, 210 reference words | 31 | 19 |
| Two varying-noise controls, inserted words | 12 | 4 |

This supports Small as an accuracy candidate, with material limitations:
normal English word errors increase 2 → 3 while character errors fall 5 → 2;
Portuguese ties, and the short French negative loses its correct transcription.
The Portuguese short negative improves. None of these cases is discarded.
Model size and encoder context change together; cached Base results are exact
PCM quality comparators, not a paired speed measurement. Small's 18 cold calls
took 232.07 seconds under the 150% CPU quota, with maximum process RSS 450.6 MB.
No Android or physical-phone result follows from those host costs.

## New Ultra full-PCM diagnostic

The frozen 18-input diagnostic is complete, with every candidate result and
cached comparator independently re-scored. No model or prompt was tuned on the
outputs. [Audited aggregate](benchmarks/2026-11-05-stt/parakeet-ultra-dev-18.json).

| Condition | Base/full | Small/auto | Ultra |
| --- | ---: | ---: | ---: |
| Ordinary speech, word errors / 210 | 25 | 18 | 15 |
| Attenuated versions, word errors / 210 | 31 | 19 | 14 |
| Two varying-noise controls, inserted words | 12 | 4 | 0 |

On the exact Spanish source that gave original Parakeet 41 word errors, Ultra
produced 2 (Base 4; Small 1). That severe output regression did not recur on this clip; two errors remain.
It does not resolve all Spanish or Android framing issues. Brazilian Portuguese regressed from
Base's 5 to 8 errors in both gain conditions, including a merged numeric
quantity and lexical/name errors. Both short synthetic negatives are rendered
as an English negative instead of the selected French/Portuguese word. This
fails target-language fidelity, not a demonstrated reversal of meaning. The
frozen counts and earlier acceptance failures remain unchanged.

Ultra uses automatic language detection; Whisper receives the explicit source
language. There is no VAD, gate or post-hoc text cleanup. All 18 cold calls
completed in 101.71 seconds under the bounded host job, whose service reported
1.9G peak memory and no swap. Cached model costs are not matched speed trials;
these are neither Android results nor Pixel 10 predictions. The subsequent
42-source DEV comparison reused the seven exact ordinary outputs and decoded
only the 35 missing ones: **146 → 51 errors / 864 words**, with lower word and character error
counts than Base in every language. Original v3 gave 98 word errors. Six DEV
sources per language still do not establish independent acceptance.
[Full per-language comparison](benchmarks/2026-11-05-stt/report.md#parakeet-ultra--completed-dev42-comparison).

The separate five-input synthetic RU+EN check improved exact technical-term
matches from Base's 1/8 to Ultra's 4/8. All four Ultra matches were HTML; API and
guardrails remained unrecognized. No target term appeared in the absent-term
control. These stitched eSpeak phrases also have substantial surrounding-word
errors, so they neither prove natural code-switching nor justify a blanket
replacement dictionary. [Synthetic diagnostic](benchmarks/2026-11-05-stt/parakeet-ultra-mixed-5.json).

## Next decisions, in order

1. Use Ultra as the primary Android candidate. Reuse the existing debug-only
   Parakeet saved-audio preview and cached runtime; bind its model files to the
   verified Ultra sizes/hashes. Keep ordinary decoding unchanged until tested.
   No additional model download or new model-manager UI is needed.
2. Compare the actual 16-second Android window/overlap pipeline with the same
   full-PCM DEV inputs. Retain speech, silence, noise, quiet negations and mixed
   terms; add constructed long-boundary cases before accepting segmentation.
   Investigate speech lost by the current gate separately. If a longer context
   is justified, freeze it as a separate configuration rather than overwriting
   the failed original preview. Explicit speech-language behavior remains open
   for this automatic-language backend.
3. Do not insert Gemma into the default path on the current evidence. Both
   Base-draft and Ultra-draft hypotheses have now been tested, with different
   outcomes preserved. Revisit correction only for a concrete remaining error
   class with a measurable gain over the selected stronger ASR. Self-reported
   LLM confidence is not calibrated confidence. Preserve original ASR/audio
   and manual edits; any eventual correction needs source revision, visible
   diff and exact undo. No concurrent large ASR/LLM loads during capture.
4. Freeze the selected pipeline and acceptance criteria before the 42 unopened
   held-out clips. Report uncertainty and per-language error counts; do not
   silently replace historical failed criteria after seeing their outputs.
   Validate real mixed-language speech separately from read-speech and synthetic
   examples, then run owner-present Pixel 10 resource and microphone acceptance.

SwitchLingua provides a possible human-recorded RU+EN evaluation source, but its
audio is gated and has noncommercial terms. No account details were submitted,
terms accepted or audio downloaded. Its scripts are generated text read by people,
not spontaneous technical notes. FLEURS alone cannot establish HTML/API/guardrails
correctness, and multilingual coverage does not establish code-switching.
[Dataset and access conditions](https://huggingface.co/datasets/Shelton1013/SwitchLingua_audio).

The owner approved a cumulative 8,500,000,000-byte ceiling on November 5. The
[ledger](benchmarks/2026-11-03-stt/download-budget.json) now reserves
7,850,106,526 bytes, leaving 649,893,474. This carries forward every earlier
reservation. New allocations cover bounded metadata, the Ultra graphs, one
LiteRT API wheel and one ordinary Gemma 4 E2B audio bundle. No existing model
was downloaded again. Raw audio, model weights and logs remain outside Git.
Phone performance and final STT acceptance remain open.
