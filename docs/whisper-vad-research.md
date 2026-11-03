# Research build: VAD failure and cancellation contract

This optional host build adds cooperative cancellation and reliable failure
reporting to the pinned VAD API. It is not connected to Android or to the SOT
research overlay. It does not change ASR decoding, thresholds, capture, or the
recorded submodule. A working VAD API alone does not establish that skipping ASR
improves recognition or meets phone resource budgets.

## Contract

The pinned `whisper_vad_detect_speech` allocates its probability vector before
processing windows. If graph computation fails, it breaks the loop and returns
`true`, leaving a partially populated vector available to callers. Treating this
as a successful absence-of-speech result could suppress valid transcription.
The existing VAD API also has no cancellation callback.

The additive `whisper_vad_detect_speech_with_abort` takes a `ggml_abort_callback`
and borrowed callback data. The existing function delegates with a null callback;
its signature and all public structure layouts remain unchanged. Calls on one
VAD context must be serialized by the caller. The callback must not throw and
its data must remain valid until the synchronous call returns.

The implementation checks cancellation before work, between input windows and
before successful return. On CPU it also installs the existing backend callback,
which checks between graph nodes. A CPU backend without that facility rejects a
requested callback. Other backends have only the window checks; this experiment
does not claim bounded cancellation on those backends. RAII removes the borrowed
callback, resets the scheduler and clears usable probabilities on failure or
cancellation. A failed graph computation returns `false`; callers must never
interpret failure as an empty successful VAD result. Reuse starts with fresh
recurrent state, as the original VAD implementation does.

## Exact source pin

| Item | SHA / revision |
| --- | --- |
| Recorded upstream commit | `4979e04f5dcaccb36057e059bbaed8a2f5288315` |
| Original `src/whisper.cpp`, LF | `02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e` |
| Original `include/whisper.h`, LF | `0525b758571ab71c00ddd8ea2845f74af7c62dfa5100c5586655f75bdc5ef4be` |
| Reviewed two-file patch | `4f8f82a93a502f68aecb6539e94acf031a0678f30ddb7a4fa9d591a8453da7b2` |
| Generated `whisper.cpp`, LF | `b980b5d0425cd322c61d53b801bef2a14a4368f891a90f781f3a9855cf255454` |
| Generated `whisper.h`, LF | `001c3ef8fffd664d219089a549414983011719a535bcf07a3cc610e804b200ae` |

`cmake/focal-whisper-vad.cmake` normalizes CRLF, checks both original hashes,
applies literal replacements and checks both generated hashes. It replaces
exactly one original source and header entry in the `whisper` target. Generated
files stay in the build directory; the generated header is first in the target's
public build include directories, including for upstream examples and consumers.
The original source include directory remains available for backend headers.
No Python or external patch command is needed during configuration.

Missing/imported targets, unexpected source entries and mismatched preimages or
postimages fail configuration. Applying this overlay and the SOT overlay to one
target is intentionally rejected in either order. Check the recorded submodule
revision and cleanliness as well: these hashes pin the two changed files, not
every build input. This hook is for build-tree research, not installation or
packaging of an altered upstream SDK.

## Separate host build

From the repository root, use CMake 3.19 or newer, a C++17 toolchain and a fresh
output directory. Existing baseline binaries must remain separate:

```sh
cmake -S third_party/whisper.cpp -B build/whisper-vad-research \
  -DCMAKE_PROJECT_whisper.cpp_INCLUDE="$PWD/cmake/whisper-vad-research-entry.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_BUILD_SERVER=OFF -DWHISPER_CURL=OFF
cmake --build build/whisper-vad-research \
  --target whisper-cli focal-vad-api-check --parallel 2
build/whisper-vad-research/bin/whisper-cli --help
```

The hook handles upstream's two `project()` calls and defers setup until its
targets exist. Ordinary `src/libwhisper` and `bin/whisper-cli` locations remain.
It also builds the standalone `focal-vad-api-check` executable, which links through
the target's public header interface. The harness takes a caller-verified VAD
model followed by raw mono 16 kHz little-endian PCM16 fixtures, each containing
1–480,000 samples. Output identifies fixtures by zero-based argument index,
without including their filenames. Reject output from any nonzero exit status;
hash local inputs before and after a recorded comparison. For example, after
preparing local fixtures:

```sh
build/whisper-vad-research/bin/focal-vad-api-check \
  build/models/ggml-silero-v5.1.2.bin build/fixtures/jfk.pcm
```

Verify the Silero model before running: 885,098 bytes and SHA-256
`29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf`.
The example paths are local inputs, not downloads or bundled weights. The model
is distributed under MIT; any future app distribution must include its full
license and attribution. This change does not distribute it.

The harness checks exact probability bits and segment ranges between the old
entry point, the new entry point and a non-cancelling callback. It cancels before
work, inside the first CPU graph and inside a later graph after half the input
windows have completed. Every abort must return `false` and expose zero usable
probabilities; a subsequent legacy call on the same context must match its fresh
normal result. Null/zero-length input must clear stale results too. Defining
`FOCAL_VAD_BASELINE` when separately compiling the same harness against the
original engine emits only its normal snapshots, allowing exact cross-engine
comparison without requiring the new symbol in the baseline library.

## Verified scope and limits

The private CPU prototype built with two jobs. On the public JFK fixture, a
derived 0.01-gain copy, 30 seconds of zero PCM and 30 seconds of constant nonzero
PCM, the original engine and patched normal calls produced identical probability
bits and ranges. Each fixture passed all three cancellation locations, callback
cleanup, invalid-input clearing and context reuse. The CPU graph invoked its
callback 74 times per window. Middle cancellation therefore occurred inside
computation after completed windows, exercising the graph-failure return path
with partial results already present.

The checked CMake overlay also built `whisper-cli` and the hardened harness with
two jobs; CLI help exited zero. Its compile database confirmed header precedence
for the engine, upstream common code and the harness consumer. The same four
fixtures and a copy with quotes, a backslash and a newline in its filename passed
exact cross-engine comparison and the cancellation checks. Empty, odd-length,
one-byte-truncated and over-30-second PCM inputs were rejected. CRLF source/header
copies configured successfully; changed source/header hashes, missing/imported
targets, a duplicate source and both SOT/VAD application orders failed as intended.
The original submodule remained clean.

A generic non-cancellation backend failure or allocation failure was not fault
injected; those paths have source review only. Callback-true-to-return timings
observe host cleanup after cooperative cancellation, not external request
latency, a hard deadline or phone performance. APK/JNI ownership races, multiple
concurrent contexts, non-CPU backends, battery and thermal behavior remain outside
this harness. Any app integration still requires paired speech evaluation,
native conditioning/presence parity, cancellation/lifecycle tests, preserved
audio and uncertain review cues, and phone resource checks.
