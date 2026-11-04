# Whisper state ownership and regression tests

`focal_whisper_lifetime_overlay()` generates a build-directory copy of the pinned
engine with the ownership-only fix exercised in the
[model-free allocation-failure study](benchmarks/2026-11-03-local/report.md#native-ownership-model-free-allocation-failure-regression).
Normal Android builds now include it; the explicit fault-test baseline retains
the original engine. It does not modify the submodule, sampling, SOT, VAD,
thresholds, model weights or transcription policy.

## Fix and limits

On self-KV growth failure, the original engine frees a state still owned by the
caller; closing that caller can access freed memory. Early state cleanup can
also free uninitialized batch pointers. The fix:

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
overlays are rejected. This is a separate ownership change. File checks do
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

## Isolated Android fault tests

The explicit `focalNativeFaultTests` Gradle property builds a separate debug-only
artifact. `baseline` uses the pinned engine; `patched` adds the exact ownership
postimage above. Normal builds omit this property. Android release tasks reject
either value, with an independent CMake debug-build guard.

```sh
./gradlew -PfocalNativeFaultTests=baseline :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew -PfocalNativeFaultTests=patched :app:assembleDebug :app:assembleDebugAndroidTest
```

Build and run these variants sequentially on a dedicated test emulator. Archive
each exact app/test APK pair before rebuilding; do not distribute fault artifacts.
The selected variant replaces the instrumentation source set, so it is not the
normal 87-test suite and must not be reported as a full `qa-local.py` pass.

The helper instruments build copies of the engine and JNI, leaving their tracked
sources unchanged. It refuses one self-KV allocation only after the real encoder
has completed and the same state requests growth from one to three decoders.
The fixture checks the actual bundled model and public JFK audio hashes. No real
host memory pressure is induced. A baseline reproduction requires both verified
native `-7` markers and an actual fatal caller-close stack; an arbitrary failed
JUnit assertion is insufficient.

The patched tests require unchanged audio, pending chunks and reviewed manual
edits after the service handles `-7`, followed by successful ordinary recovery
with a fresh engine. A separate barrier-only case cancels and closes an in-flight
decode, checks that its owner survives until return, rejects partial output and
requires exactly one destruction. Cancellation and allocation refusal are kept
separate because cancellation could mask the allocation error.

Before publishing an ordinary build, check both APK ABIs and DEX files for the
absence of fault helpers, JNI exports and markers, then run the unchanged full
suite. Source review and these commands alone are not runtime evidence.

## Verification boundary

Nine host tests exercise 19 configuration scenarios: LF, CRLF and the deferred
entry produce the exact reviewed source; 16 invalid cases fail with their intended
overlay error. They also check unchanged input files and unrelated target sources.
The postimage is byte-identical to the independently reviewed ownership prototype
that passed all 24 model-free sanitizer cases. The original engine passed four
controls and failed 20 expected regressions, including two ASan use-after-free
reports. Those tests use real engine/GGML code with explicit graph bypasses.

The separate real-graph harness now has independent prebuild/postbuild review and
two successfully compiled sanitized variants. Its first healthy baseline process
loaded the cached model and initialized actual state graphs, but hit the frozen
60-second wall limit before a full return or canonical output. No other case or
patched process ran. The [failed-attempt record](benchmarks/2026-11-03-local/engine-lifetime-realgraph-results.json)
preserves this incomplete evidence; the logs do not identify encoder versus decoder
as the unfinished stage. It is not an expected regression RED or patch acceptance.
A separately reviewed single-case diagnostic then completed that unchanged
healthy baseline in 79.903 s with strict counters and clean sanitizers. This
supports insufficient time for the original control; it does not validate the
remaining matrix or quantify sanitizer overhead. No recompile or download occurred.

The later matrix and a seven-case continuation now cover all 30 original
case/variant pairs: patched 15 pass with clean sanitizers; baseline five controls
pass and ten expected regressions reproduce. Same-state beam/greedy/auto retries,
close and the three loader wrappers are covered with actual model graphs. All
required canonical comparisons match exactly. This is combined coverage, not a
single clean run: the initial timeout and a later auto-retry CPU-limit failure
remain recorded. No model was downloaded and no held-out speech was consumed.

The isolated Android patched variant subsequently passed both strict integration
tests in 173.937 s: service failure preserves original audio, reviewed edits and
pending work before ordinary recovery succeeds; in-flight cancel/close retains
the engine until return, discards partial output and destroys it once. The first
attempt retained a failed 30 s arrival wait. A reviewed 120 s arrival allowance
then observed the actual checkpoint after 34.520 s; the 30 s barrier hold and
45 s completion/cleanup bounds were unchanged. These are test workload timings,
not phone latency measurements. The later clean `036d716` ordinary suite passed
87 tests, including eight real-model checks; debug/release lint and unsigned
release/R8 builds also passed. Both ordinary ABI libraries and DEX were checked
for absence of fault helpers. Release runtime and phone resources remain separate
gates recorded in the [handoff](development-handoff.md).

Incremental builds now track each checked original source with
`CMAKE_CONFIGURE_DEPENDS`. Two host tests exercise six input mutations: the old
configuration fails to reject them, while the fixed full host suite passes all
157 tests. This prevents stale generated overlays from bypassing preimage checks
after an upstream or JNI input changes; it does not change the engine postimage.
