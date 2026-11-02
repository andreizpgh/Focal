# Local speech-quality experiments

Research plan, checked against primary sources on **2026-11-02**. No correction
model, automatic text replacement, or new Android behavior is enabled by this
document or its scoring script. The current app keeps its existing speech model.
Model/corpus hosting remains blocked in this environment, so multilingual quality,
Russian dictation accuracy, and phone performance are **unmeasured**. Synthetic
scorer tests establish metric behavior, not recognition quality.

Research may investigate local ASR corrections. Shipping a correction requires a
measured gain and must preserve what was spoken, including deliberate factual
mistakes, unusual names, repetitions, and imperfect grammar. A text-only model
cannot establish which of two plausible words was actually said.

## Compare bounded alternatives

Keep the same immutable audio, explicit language, pinned engine, chunk ranges,
and test split for each paired run. Change one variable at a time.

| Experiment | Concrete comparison | Evidence it could add | Main cost or failure mode |
| --- | --- | --- | --- |
| E0 | Current multilingual base Q5_1, beam 3, current boundary/context handling | Reference system | Current multilingual WER is still unknown |
| E1 | Base Q8_0 versus base Q5_1, otherwise identical | Isolate quantization loss | More memory/download bytes; a gain is not guaranteed |
| E2 | Multilingual small Q5_1 as the first pass versus E0 | More acoustic-model capacity | Extra decode time, memory and heat; verify each language |
| E3 | E0 plus small Q5_1 re-decoding only selected saved-audio ranges | Acoustic evidence for uncertain words and boundary cuts | Cold-load cost, correlated errors, selection misses confident mistakes |
| E4 | Context on/off and gate ablations using the same original recording | Separate prompt contamination, missed speech and ASR errors | A quieter threshold can create noise-only work and hallucinations |
| E5 | E0/E3 plus Qwen3-0.6B constrained choice between acoustic alternatives | Language context can reject an implausible alternative | Fluent but wrong replacements; extra runtime and model residency |

The first useful comparison is E0/E1/E2, followed by E3 if small is accurate but
too costly as the default. Run E5 against the strongest ASR-only alternative, not
only against a weaker baseline. Count extra loading and background contention.
Two Whisper passes are not independent evidence merely because they agree.

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

Use a hard combined ceiling of **1,000,000,000 downloaded model/corpus bytes**,
including failed/repeated transfers. Fetch LFS metadata first, pin immutable
revisions and SHA-256 hashes, and reserve the full declared size before a request.
Do not fetch whole corpus shards to obtain a few examples. If a shard or an
updated model will exceed the reservation, stop and reduce the experiment set.

These are staged upper-bound reservations, not a download target. Use existing
verified artifacts and local consented audio where possible. Run E0–E4 first;
do not download Qwen unless their results indicate the E5 comparison is likely
to add value and enough of the shared budget remains.

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

Twenty MB of downloaded audio is only a smoke subset. It cannot establish a
high-precision release claim. Expand the evaluation with consented local phone
recordings, which require no model/corpus download. Preserve attribution and
consent provenance outside Git. FLEURS is a candidate multilingual read-speech
sanity check with CC-BY-4.0 attribution; report it separately from spontaneous
dictation, noise and user speech. The source's full dataset is not the planned
download. ([Google FLEURS dataset](https://huggingface.co/datasets/google/fleurs))

## Paired seven-language evaluation

Use English, Russian, German, French, Spanish, Italian and Portuguese. Split by
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
For E5, prefer a choice among a small set of acoustic alternatives plus `abstain`.
If free text is studied, return only a bounded list of edits against a hashed
source revision: at most four short spans, no full-document regeneration.
Validate offsets, non-overlap, source hash, output length and exact unchanged text
outside accepted spans. Invalid/late output is an abstention, not a fallback rewrite.

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
