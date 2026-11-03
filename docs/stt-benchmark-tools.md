# Reproducible host speech probes

These tools measure isolated local clips. They do not establish Android capture
coverage, continuous dictation quality, phone latency, battery use or thermals.
Keep audio, references, hypotheses, prompts and individual records outside Git.
The ignored `build/stt/` directory is the normal research destination.

## Select a fresh public held-out cohort

Before another download, carry forward the shared download ledger and reserve
every transfer under its existing 1 GB ceiling. Do not reset it in a new checkout.
`fetch-stt-corpus.py` defaults to both official splits; `--splits dev` or
`--splits test` fetches only the requested split. To select recordings after the
already consumed cohort without inspecting references or ASR results:

```sh
python3 scripts/fetch-stt-corpus.py \
  --output build/stt/fresh-held-out \
  --ledger build/stt/download-ledger.json \
  --splits test --test-clips 3 \
  --exclude-provenance docs/benchmarks/2026-11-03-stt/corpus-provenance.json
```

Repeat `--exclude-provenance` for every subsequently consumed cohort. The tool
validates the pinned dataset revision and required identities before any request.
It excludes prior IDs and audio identities before counting the first requested
complete 1–30 second recordings in archive order. Historical `pcm16_sha256`
identifies the canonical PCM16 WAV container; conversion normalizes WAV headers
before comparing it. New provenance also records raw `pcm_sha256`. Matching audio
under another ID is excluded, including across languages and official splits.
The new provenance records the hashes and clip counts of the exclusion inputs.
An insufficient bounded archive prefix fails without falling back to used clips.

Use a separate ignored output directory to preserve earlier manifests and scores.
The command refuses an output directory containing existing evidence before any
request; it may contain only a `source/` cache. On Unix the output directory must
be private to the current user; new directories use owner-only permissions.
An existing `source/<revision>/` cache and its `.sha256` files can be copied into
the new output directory to reuse verified prefixes without another transfer.
Selection still respects the shared budget and never downloads a complete shard.
IDs/PCM exclusions prove disjointness only against the supplied cohorts; they do
not establish independent speakers, sessions or reference text. Freeze the
candidate and scoring plan before opening held-out audio/results. Raw references,
audio, hypotheses and local paths remain private.

## Build the confidence probe

Initialize the pinned whisper.cpp submodule and build its host library first.
The probe requires a Linux host, a C++17 compiler and OpenSSL development headers
and `libcrypto` for SHA-256. It adds no Android dependency and changes no vendor
source or app decoder configuration.

```sh
bash scripts/build-stt-probe.sh build/whisper-host
python3 -m unittest discover -s scripts/tests -p 'test_stt_probe.py' -v
```

The build script compiles only `scripts/stt-probe.cpp` against the existing shared
engine. The tests compile a synthetic Whisper backend, check the parameter and
file contracts, and perform no model loading or speech inference. Build success
and synthetic tests are separate from measured recognition results.

## Score a development split

```sh
python3 scripts/evaluate-stt.py build/stt/corpus/manifest.json \
  --cli build/whisper-host/bin/stt-probe \
  --model build/stt/ggml-base-q8_0.bin \
  --split dev --require-all-languages --beam-size 3 --audio-context auto \
  --output build/stt/dev-base-q8-auto-probe.json \
  --records-output build/stt/dev-base-q8-auto-probe.jsonl
```

References never enter the probe's arguments or prompt. The evaluator checks all
manifest audio before filtering the requested split, verifies supplied file
SHA-256 values and rejects duplicate PCM across development and test. It also
rejects repeated PCM in the same language/split. Reusing a silence fixture across
different languages in one split is supported; this is not independent evidence.
Missing speaker/session identities do not become invented groups or confidence
intervals.

The default probe matches the current JNI decoder: CPU, flash attention, explicit
language, beam three, no retained context, a 64-token text-context cap, timestamps
without word alignment, suppression of non-speech tokens and zero temperature
increment. Audio shorter than one second is padded with zeros. The evaluator
filters text timed only in padding using the original source duration. Optional
beam/audio-context changes are experiments. `auto` uses
`min(1500, max(768, ceil((seconds + 2) * 50 / 256) * 256))` encoder positions;
`0` retains the complete model context. Historical word-alignment flags are
explicitly unsupported by the probe.

Reports retain the evaluator hash captured at startup, exact manifest identity,
model/executable/shared-library hashes, selected-corpus identity and settings.
The source-checkout commit is informational: it does not prove an arbitrary
executable was built from that checkout. Binary/library hashes identify what ran.

## Confidence and cost records

Private JSONL records additionally retain `probe.segments`: native offsets in
milliseconds (`offsets.from`/`to`), text, lexical-token probabilities, the decoder's
`noSpeech` estimate and `in_original_audio`. Padding-only segments remain visible
in diagnostics with that flag false. Special-token probabilities are excluded;
invalid probabilities become zero and an invalid no-speech estimate becomes one,
matching JNI. Confidence fields use JNI's six significant digits of serialization
precision. **Serialization precision is not a correctness guarantee.** These
features are uncalibrated, and confident mistakes can remain unselected.

The aggregate `probe_measurements` contains internal model-load/decode totals and
observed P50/P95 values, plus maximum process peak RSS from `getrusage`. These
phases remain separate from the evaluator's existing process wall time, which
includes startup, model loading and output work. RSS describes one host process;
it does not measure concurrent models on a phone. Every clip starts a fresh
process; these are cold trials, not repeated warm or phone measurements.

A selective acoustic second pass must choose ranges using the first pass's audio
and available diagnostic features, before consulting reference errors or second
pass wins. The existing `Transcript.needsReview` rule can be frozen for a shadow
comparison; its thresholds are not calibrated acceptance probabilities. Score
selection misses, harmed correct words, every language and additional loading
cost. Preserve original ASR/audio and manual edits, and keep automatic changes
disabled while accuracy or phone evidence remains inconclusive.

## Cached second-pass shadow comparison

`scripts/evaluate-stt-shadow.py` validates two cached runs and their companion
aggregate reports, then calls the real Kotlin `Transcript.needsReview` through
[`TranscriptReplay`](transcript-replay.md). Freeze the replay jar's SHA-256 and
put its runtime classpath in a local file before running:

```sh
python3 scripts/evaluate-stt-shadow.py \
  build/stt/dev-probe-q8-auto.jsonl build/stt/dev-small-q5-auto.jsonl \
  --split dev --classpath-file /tmp/focal-gate-runtime-classpath.txt \
  --replay-jar-sha256 "$FROZEN_REPLAY_SHA256" \
  --allow-mixed-runners --compatibility-run build/stt/dev-base-q8-auto.jsonl \
  --output build/stt/dev-e3-shadow.json \
  --records-output build/stt/dev-e3-shadow.jsonl
```

The tool checks paired IDs, WAV/PCM/reference identity, settings, source-record
provenance and common Whisper/GGML library hashes. It completes selection using
only first-pass probability/no-speech features before scoring references. Any
eligible segment requesting review selects the entire cached second-pass clip.
Padding-only segments are excluded; no eligible segments means no selection
under this exact policy. Empty-output misses therefore need explicit reporting.

Decoder and evaluator hashes must match by default. The example explicitly
allows the probe/CLI caches to differ and supplies a compatibility witness: the
same first-pass model evaluated with the second pass's runner/evaluator must
produce identical hypotheses on every paired clip. The aggregate records both
runner identities and witness hashes. Agreement on this cohort does not prove
general wrapper equivalence. Replay/stdlib jars are copied into private temporary
snapshots before selection, so concurrent rebuilds cannot mix policy binaries.
The pinned jar identifies the Kotlin policy binary; its hash alone does not
independently prove the source from which it was built.

Private results preserve all three hypotheses, the original revision hash and
selection evidence. Aggregate results show every language, unflagged errors,
initially correct clips harmed, and character-error increases that a WER tie can
hide. Whole-clip error changes still do not adjudicate every changed word, name,
number or negation. These development results do not establish accepted-span
precision or speaker-level confidence intervals.

The estimated cold cost adds each cached first pass to the cached second pass
for selected clips. It excludes selector transport and measures no actual
combined execution, queueing, model coexistence or phone cost. A successful
cached comparison requires a subsequent real pipeline experiment; it does not
enable automatic corrections or change the app's model.

## Context ablations

The direct probe accepts `--prompt-file PATH`. Generate this file only from
actual previous ASR through the real `Transcript.trustedContext` implementation;
reference text must never supply it. Input is strict UTF-8, at most 960 bytes and
240 UTF-16 code units, without NUL, overlong encodings or surrogate scalars.
Supplementary characters count as two UTF-16 units, matching JNI's bound.

The output profile records `initial_prompt_sha256`, byte count and UTF-16 count,
without reproducing prompt text. Use the same saved audio and compare context
on/off with a frozen policy. The normal evaluator supplies no prompt; continuous
context experiments require a runner that calls the actual Kotlin context and
boundary logic. The probe rejects outputs that alias its WAV, model or prompt,
including preexisting hardlinks.
