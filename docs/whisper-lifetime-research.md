# Research build: state ownership on allocation failure

The optional `focal_whisper_lifetime_overlay()` generates a build-directory copy
of the pinned engine with the ownership-only prototype exercised in the
[model-free allocation-failure study](benchmarks/2026-11-03-local/report.md#native-ownership-model-free-allocation-failure-regression).
It is not enabled in Android and does not modify the submodule. It changes no
sampling, SOT, VAD, thresholds, model weights or transcription policy.

## Fix and limits

On self-KV growth failure, the original engine frees a state still owned by the
caller; closing that caller can access freed memory. Early state cleanup can
also free uninitialized batch pointers. The prototype:

- retains the borrowed state on a failed cache replacement;
- prepares the next KV cache before releasing the old cache;
- initializes batch storage and clears cache ownership on free;
- uses local RAII for partial KV/state construction and the three state-bearing
  model-loader wrappers.

The patch does not promise recovery from arbitrary OOM. Unchecked batch/GGML
allocation, abort paths, model-loader exceptions before model return, backend
construction before ownership transfer and worker-thread exceptions remain
outside its scope. Transactional replacement also temporarily retains the old
cache: the derived extra base-model self-KV peak is 6 MiB, excluding metadata,
graphs and allocator overhead. This is not measured phone RSS.

## Exact source composition

| Input/output | Revision or SHA-256 |
| --- | --- |
| Recorded upstream commit | `4979e04f5dcaccb36057e059bbaed8a2f5288315` |
| Original `whisper.cpp`, normalized LF | `02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e` |
| Original `whisper.h`, normalized LF | `0525b758571ab71c00ddd8ea2845f74af7c62dfa5100c5586655f75bdc5ef4be` |
| Ownership-only `whisper.cpp` | `8cc010e36be180b2843287562cd69104a2b1f11e4d7a95827fffce7db70ed64b` |

Call the function after `add_subdirectory` has created the source-built Whisper
target, or use the deferred project entry below. It requires exactly one original
source and header, verifies both preimages, applies exact literal replacements,
and checks the complete postimage before writing it or changing the target.
The original header and other target sources are retained. LF/CRLF inputs produce
the same LF output without changing the original files.

Unknown source/header bytes, imported or missing targets, missing/duplicate engine
inputs, repeated application and composition with the SOT/VAD/beam research
overlays are rejected. This is a separate ownership experiment. File checks do
not replace validation of the full submodule, toolchain and loaded dependencies.

```sh
cmake -S third_party/whisper.cpp -B build/whisper-lifetime-research \
  -DCMAKE_PROJECT_whisper.cpp_INCLUDE="$PWD/cmake/whisper-lifetime-research-entry.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_BUILD_SERVER=OFF -DWHISPER_CURL=OFF
cmake --build build/whisper-lifetime-research --target whisper-cli --parallel 2
```

These are optional build instructions, not a claim that this public CLI build or
its real-model inference has been verified. No Python or external patch program
is needed during configuration.

## Verification boundary

Nine host tests exercise 19 configuration scenarios: LF, CRLF and the deferred
entry produce the exact reviewed source; 16 invalid cases fail with their intended
overlay error. They also check unchanged input files and unrelated target sources.
The postimage is byte-identical to the independently reviewed ownership prototype
that passed all 24 model-free sanitizer cases. The original engine passed four
controls and failed 20 expected regressions, including two ASan use-after-free
reports. Those tests use real engine/GGML code with explicit graph bypasses.

Actual-model same-state beam/greedy retry and close, the three loader wrappers,
Android error/recovery/cancellation and phone resource checks remain open. The
separate real-graph harness is incomplete preparation, not an executed experiment.
Do not enable this overlay in the application on the strength of configuration
tests alone. The [handoff](development-handoff.md) records the next required checks.
