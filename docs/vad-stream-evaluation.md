# Offline VAD framing experiment

The evaluate-vad-streams.py tool compares Silero framing on the **exact PCM**
from a verified [continuous-source preparation](gate-experiment.md). It uses the
[host VAD probe](vad-probe.md), pinned Silero 5.1.2 and the existing base Q5_1 ASR
probe. No production capture, gate, JNI or Android dependency is changed.

VAD processes a complete stream of at most 400 seconds. This is an offline host
comparison, not live capture equivalence or phone-performance evidence. Future
streaming integration must separately verify state, onset and tail handling,
bounded buffering, latency and resources.

## Freeze and inspect development ranges

Obtain the verified VAD model through the shared download budget; the evaluator
downloads nothing. Build both native probes against the same pinned Whisper/GGML
libraries. Use a new private, Git-ignored output directory:

    python3 scripts/evaluate-vad-streams.py \
      --experiment build/stt/gate-dev-baseline/manifest.json \
      --source-manifest build/stt/fleurs-fresh/manifest.json \
      --split dev \
      --kotlin-stdlib /private/kotlin-stdlib-2.1.20.jar \
      --vad-model build/stt/vad-model/ggml-silero-v5.1.2.bin \
      --vad-model-provenance build/stt/vad-model/provenance.json \
      --contexts off on --conditions normal quiet \
      --controls zero dc noise \
      --output build/stt/vad-dev-plan --plan-only

The --plan-only option executes VAD and writes the frozen policy plus selected
intervals. It never runs ASR or calculates text metrics. Run without that flag
and with a different empty output directory to evaluate recognition; VAD runs
again under the same policy. Defaults select all seven languages, normal/quiet
conditions and context on. The --controls-only option requires explicit named
controls; their ASR language is --control-language, English by default. Repeat
relevant controls in other languages before claiming language-wide hallucination
robustness. Empty candidate ranges perform no ASR.

The fixed VAD configuration is CPU, one thread, threshold 0.5, minimum speech
250 ms, minimum silence 600 ms, 500 ms padding, nominal maximum speech 15 seconds
and zero native overlap. The native library can return merged intervals longer
than its nominal maximum. The evaluator joins touching native intervals and
splits each continuous span into at most 16-second chunks, with exactly 500 ms
overlap at hard cuts. A real gap resets overlap. Every chunk records the native
segment indices that intersect it. Sample bounds remain monotonic and within
the original PCM; ASR extraction truncates to stored integer milliseconds,
matching the existing gate comparison.

The ASR policy stays multilingual base Q5_1, four CPU threads, beam three, full
encoder context, 64-token text context and zero temperature/increment. Context,
review decisions and overlap reconciliation use the existing freshly attested
Kotlin Transcript implementation. References never enter either native probe
or its prompt. Only the selected split's references are scored; both source
splits still undergo path, PCM identity, duplicate-audio and declared-group
checks.

## Identity, output and held-out rules

The evaluator validates the prepared split, hashes, original-source derivation,
compiler/class/runtime attestation, exact VAD model provenance and base-model
digest before inference. VAD output must match the expected model hash, PCM hash,
sample count, 512-sample probability windows, padding and all frozen settings.
Native centisecond ranges must map to their declared sample bounds. Missing,
nonfinite, partial or inconsistent output fails closed.

The plan records native executable and dynamic-library identities, the recorded
engine checkout, both models, helper hashes, replay build and framing policy.
VAD and ASR must use the same Whisper/GGML library hashes. These identities do not
independently prove binary build provenance. JSON hashes bind the exact bytes
parsed; later file mutations cannot replace the earlier verified identity.
Inputs and linked dependencies are checked before VAD and again before publishing
results. Runtime classes and stdlib are copied to private output so a concurrent
build cannot mix replay implementations.

Existing output is preserved: use a new empty ignored directory. Unix output
uses owner-only permissions; check directory ACLs separately on Windows.
The plan, selected intervals, probability JSON, WAV slices, prompts, logs,
references and hypotheses remain private. Publish only reviewed aggregate
evidence and appropriate hashes, without raw speech or local paths.

After selecting a policy on development data, preserve its plan.json.
Freeze the candidate and acceptance criteria before preparing or inspecting the
held-out audio, references or results; the CLI cannot enforce that research process.
The --split test option requires --frozen-plan PATH; the complete policy must
match before the held-out source manifest is read. Prepare that source with
prepare-gate-experiment.py --split test. Changing the policy after opening
held-out results requires a fresh held-out cohort. Excluding prior IDs/PCM does
not establish speaker/session independence.

Reports separate source-clip coverage, candidate audio duration, VAD phase
timings and ASR text scores. Coverage includes silence within original clips
and is not phoneme-aligned speech recall. VAD runs once per stream and is shared
across requested context ablations; ASR uses a cold process/model per chunk.
These timings exclude Android scheduling, capture and persistent-engine
behavior. Synthetic controls are not real room noise, and passing them alone
does not demonstrate recognition accuracy or phone battery/thermal behavior.

Run the lightweight evaluator regressions with:

    python3 -m unittest discover -s scripts/tests -p test_evaluate_vad_streams.py

These tests use synthetic metadata and mocked native decisions; they perform
no real ASR and do not establish Silero quality.
