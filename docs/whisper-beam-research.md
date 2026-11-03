# Research build: deterministic beam candidates

This optional host build combines the reviewed [VAD error/cancellation API](whisper-vad-research.md),
[SOT no-speech probability calculation](whisper-sot-research.md), and an actual
top-k beam sampler. It generates build-directory copies of the pinned engine
without changing the recorded submodule. This optional hook does not activate the
combined engine in Android. It does not enable VAD gating, change thresholds,
load models, or alter capture by itself. The separate local VAD-only app candidate
does not establish acceptance of this combined engine; recognition quality and
app adoption require separate paired evaluation.

## Sampler contract

The pinned `whisper_sample_token_topk` sorts token scores, then ignores that
ordering and samples repeatedly from the complete probability distribution.
Beam search consumes those sampled tokens as its candidate extensions, including
at temperature zero. Repeated draws can produce duplicates and depend on prior
RNG consumption. Decoder 0 is seeded when its state is created, whereas the
per-call decoder initialization loop resets only the other decoders.

The candidate returns the token IDs from the existing partial sort. Equal logits
use ascending token ID as a deterministic tie rule. Token probabilities, log
probabilities and timestamp metadata keep their existing calculation. The
sampler neither reads nor advances RNG state; greedy sampling is unchanged.
The separate diagnostic decoder-0 RNG reset is **not** included.

This fixes a local sampler contract. It does not establish correct transcription,
noise rejection, multilingual accuracy, or phone resource use. In particular,
deterministic output can still be wrong.

## Pinned composition

| Item | Revision / SHA-256 |
| --- | --- |
| Recorded upstream commit | `4979e04f5dcaccb36057e059bbaed8a2f5288315` |
| Original `whisper.cpp`, LF | `02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e` |
| Original `whisper.h`, LF | `0525b758571ab71c00ddd8ea2845f74af7c62dfa5100c5586655f75bdc5ef4be` |
| VAD API intermediate `whisper.cpp` | `b980b5d0425cd322c61d53b801bef2a14a4368f891a90f781f3a9855cf255454` |
| Generated `whisper.h` | `001c3ef8fffd664d219089a549414983011719a535bcf07a3cc610e804b200ae` |
| SOT + VAD baseline `whisper.cpp` | `7b18969834da47224050bae38a9b9724826c5bfd58c17829e21041aa5bb77cf2` |
| SOT + VAD + top-k `whisper.cpp` | `14499a7ba84bf9e00f64db2e7c9a16d5faf0e5a0cca6356e9d81616af91c5a2f` |

`focal_whisper_beam_overlay()` first calls the unchanged VAD overlay, which checks
both original preimages and normalizes CRLF. It then requires exactly one
generated VAD source/header and their expected hashes. Literal SOT replacements
produce the checked baseline; the sampler replacements produce the checked final
source. Only that generated source replaces the VAD source in the `whisper`
target. The verified VAD header retains precedence over the original header.
No Python or external patch command is needed during configuration.

Use only the combined entry point for this experiment. Repeated application,
unknown preimages, duplicate source entries, and applying the standalone SOT or
VAD overlay before or after it are rejected. The standalone overlays retain their
previous behavior. Source-file hashes are not a substitute for verifying the
recorded submodule, other build inputs and actual loaded libraries.

## Separate host build and sampler checks

Use CMake 3.19 or newer, a C++17 toolchain, and a fresh output directory. Existing
baseline binaries must stay separate:

```sh
cmake -S third_party/whisper.cpp -B build/whisper-beam-research \
  -DCMAKE_PROJECT_whisper.cpp_INCLUDE="$PWD/cmake/whisper-beam-research-entry.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_BUILD_SERVER=OFF -DWHISPER_CURL=OFF
cmake --build build/whisper-beam-research \
  --target whisper-cli focal-whisper-beam-baseline-check focal-whisper-beam-check \
  --parallel 2
build/whisper-beam-research/bin/whisper-cli --help
build/whisper-beam-research/bin/focal-whisper-beam-baseline-check
build/whisper-beam-research/bin/focal-whisper-beam-check
```

The baseline check is intentionally expected to exit **1**; the candidate check
must exit **0**. Both emit a JSON summary. A compiler error or missing executable
is not an expected baseline failure. Neither executable accepts a model or audio
file, and neither performs ASR or VAD inference.

Both targets compile the same `scripts/whisper-beam-check.cpp`. It includes the
actual generated engine translation unit to call its static sampler, and links
to GGML without a second copy of `whisper`. Synthetic logits are converted by the
engine's own probability helpers. Eight checks cover explicit unique ranks at
`k=1`, `k=3` and the whole vocabulary; masked tokens; equal-score ties; token and
timestamp metadata; output independence from RNG history; and unchanged RNG
state. The scope is valid `k` and finite or masked negative-infinity logits;
invalid `k` and NaN handling are not added to the engine contract.

## Verified scope

The public hook built the CLI and both harnesses on Linux with two build jobs.
After successful compilation, the same harness produced seven baseline assertion
failures with exit 1 and eight passing candidate checks with exit 0. CLI help
exited zero. Both checks resolved the same GGML/system libraries. The compile
database confirmed inherited engine definitions and generated-header precedence
for the engine, harnesses and upstream example code. No model was loaded and no
ASR or VAD inference ran.

Twenty configuration checks passed their expected outcomes: LF and CRLF inputs
generated the pinned outputs; eighteen negative cases rejected altered original
or intermediate source/header bytes, missing/imported targets, duplicate or
unrelated source entries, a changed final replacement, repeated application, and
both orders with the standalone overlays. The original SOT/VAD ordering checks
still reject composition. The recorded submodule remained unchanged.

These model-free checks do not replace a fresh persistent-context speech
comparison, positive/noise controls, review of changed or missing speech, Android
lifecycle/cancellation QA, or phone battery and thermal measurements. Keep
held-out data closed until the final candidate and its development evidence have
been reviewed.
