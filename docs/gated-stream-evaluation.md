# Evaluate gate and context effects on continuous dev streams

`scripts/evaluate-gated-streams.py` consumes a freshly compiled, attested
[gate experiment](gate-experiment.md). It calls actual Kotlin gate, context,
review and reconciliation operations. It does no automatic correction or model
selection, and changes no production capture/decoder settings.

The frozen decoder is CPU, four threads, beam three, complete audio context,
64-token text context, zero temperature/increment and the pinned host probe's
remaining JNI-compatible options. Each chunk starts a cold host process and loads
the model. These timings do not measure the app's persistent engine, warm phone
latency, capture scheduling, battery or thermal behavior.

After [preparation](gate-experiment.md), inspect the plan before allocating the
exclusive inference window:

```sh
python3 scripts/evaluate-gated-streams.py \
  --experiment build/stt/gate-experiment-verified/manifest.json \
  --source-manifest build/stt/corpus/manifest.json \
  --kotlin-stdlib /private/cached-kotlin-stdlib.jar \
  --model app/src/main/assets/ggml-base-q5_1.bin \
  --output build/stt/e4-normal --conditions normal --contexts off on --plan-only
```

Remove `--plan-only` when the benchmark window is available. Use `--languages ru`
for a focused development run, or `--conditions quiet` for gain 0.1. Gain 0.03 is
available as `veryquiet`. Each output directory must be empty and ignored in its
containing Git worktree, or outside Git. Unix directories use owner-only access;
on Windows the caller must supply appropriate filesystem ACLs.

The modes separate distinct comparisons:

| Mode | Decoder inputs |
| --- | --- |
| `gated` | Exact gate ranges, truncating offsets to the milliseconds stored by capture |
| `direct-source` | Complete original source-clip intervals, at the same gain, without gate selection |
| `bypass` | Entire continuous PCM in fixed 30-second ranges with 500 ms overlap, including inserted silence |

Pass `--mode direct-source --contexts off --conditions quiet` to isolate quiet
speech from gate omissions. The existing normal isolated-clip baseline can be
reported separately when its model/decoder settings and source hashes match;
this runner does not silently import or relabel earlier runs. `bypass` is an
explicit experimental framing policy, not a claim that capture uses it.

`--controls-only --controls zero dc noise --control-language en --mode bypass --contexts off`
decodes the three synthetic controls. Gated controls with zero candidate ranges
perform no inference; their empty output does not establish silence-hallucination
robustness of the decoder. Control language is explicit and is not evidence for
all seven languages.

Preflight verifies all source splits/audio identities, reconstructs each stream
from the deterministic dev selection and gain/padding, checks hashed gate TSV
against its JSON ranges, verifies the fresh source/compiler/class/runtime
attestation, and snapshots replay classes/runtime inside the private run output.
The actual Kotlin gate runs again before each case. Sources and executable
dependencies are checked again before publishing aggregate results.
Every requested language/condition/control must be present. Native dependency
inspection must provide complete dynamic-library hashes; unavailable inspection
or static probe binaries are unsupported by this runner.

Previous passages match the app's chronological last-six query and stable
insertion-ID ordering. Prompts come only from previously committed ASR through
`Transcript.trustedContext`; references never reach the probe or prompt. Native
confidence lexemes are parsed as decimal transport and passed to Java Float
parsing in `Transcript.needsReview`. Padding-only native segments are excluded,
absolute timestamps are clamped, and actual Kotlin reconciliation supplies
trimmed passages and missing-range review cues.

Private output retains WAV slices, prompts, native segments/probabilities,
passages, per-stream references/hypotheses and hashes. Scoring reuses the existing
evaluator's NFC/casefold lexical WER/CER and separately reports its other CER
definitions. Source references and committed passages are each joined by one
space. Metrics are grouped by mode, gain condition and context policy; there is
no combined claim of independent samples across repeated scenarios or speakers.

Run the lightweight synthetic runner tests with:

```sh
python3 -m unittest discover -s scripts/tests -p test_evaluate_gated_streams.py
```

They perform no speech inference. Recognition claims require successful measured
runs, matched input/configuration identities, and inspection of boundary losses,
repetitions and synthetic-control insertions alongside aggregate error rates.
