# Reproducible host speech probes

These tools measure isolated local clips. They do not establish Android capture
coverage, continuous dictation quality, phone latency, battery use or thermals.
Keep audio, references, hypotheses, prompts and individual records outside Git.
The ignored `build/stt/` directory is the normal research destination.

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
