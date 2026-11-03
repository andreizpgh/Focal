# Research build: no-speech probability at SOT

This optional host build applies a reviewed fix to a generated copy of
`whisper.cpp`. It does not change the recorded submodule, the Android build,
production thresholds, models, or the existing baseline build. It is research
support, not a claim that varying noise or quiet-speech recognition is fixed.

## Why the row matters

The pinned engine requests logits only for the final prompt token, then computes
no-speech probability from the first logits row. With language/task tokens or
trusted preceding text, that row is not the requested start-of-transcript (SOT)
row. Fresh decoder state can consequently report an almost uniform probability
(`1 / 51,865` for multilingual base); reused state can retain an earlier row.

The overlay locates SOT after the trusted text prefix, requests that logits row,
and computes its unfiltered vocabulary softmax. Normal next-token sampling still
uses the final prompt row. This follows the
[official Whisper SOT calculation](https://github.com/openai/whisper/blob/0b1ba3d46ebf7fe6f953acfd8cad62a4f851b49f/whisper/decoding.py#L529-L534).
The engine's existing skip rule remains the conjunction of no-speech probability
above `no_speech_thold` and average decoder log probability below `logprob_thold`.
Correcting the probability can therefore change output even without changing
thresholds; paired speech and noise evaluation is required before adoption.

The change adds one requested logits row and a temporary vocabulary-sized float
copy while calculating no-speech probability (207,460 bytes for multilingual
base). This is a code-level size estimate, not a measured memory, latency,
battery, or thermal result on phones.

## Exact source pin

| Item | SHA / revision |
| --- | --- |
| Recorded upstream commit | `4979e04f5dcaccb36057e059bbaed8a2f5288315` |
| Original `src/whisper.cpp`, LF | `02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e` |
| Reviewed patch | `c7fa8bde0daf3b4fd818d5b87e3833c8ea7a691357c607f5ff0379bc345786f7` |
| Generated `whisper.cpp`, LF | `fb5bd21a37f4378c2b2f6b3ac053f8d142211781530765d3cb1fa49c17caec03` |

`cmake/focal-whisper-sot.cmake` normalizes CRLF to LF, verifies the original hash,
performs the three exact reviewed replacements, writes an overlay under the
target's build directory, and verifies its resulting hash. Only the original
`whisper.cpp` entry in the `whisper` target's sources is replaced. Original
headers, include directories, other sources, and output locations are retained.
Configuration fails for a missing/imported target, an unexpected source list,
or either hash mismatch. No Python interpreter or external patch command is
needed. The hash checks pin this source file; they do not replace checking the
recorded submodule revision and cleanliness for a reproducible experiment.

## Separate host build

Run from the repository root with CMake 3.19 or newer and a C/C++ toolchain. Use a
fresh output directory so existing baseline binaries and provenance stay intact:

```sh
cmake -S third_party/whisper.cpp -B build/whisper-sot-research \
  -DCMAKE_PROJECT_whisper.cpp_INCLUDE="$PWD/cmake/whisper-research-entry.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_BUILD_SERVER=OFF -DWHISPER_CURL=OFF
cmake --build build/whisper-sot-research --target whisper-cli --parallel 2
build/whisper-sot-research/bin/whisper-cli --help
```

Upstream's project name is `whisper.cpp`. The project hook defers the overlay
until its targets exist; an include guard handles upstream's two `project()`
calls. The output retains the usual `src/libwhisper` and `bin/whisper-cli`
locations. Existing research probes may be compiled against this distinct build
directory; record their own hashes and policy with each paired result.

For a future Android experiment, the function can be
included and called after `add_subdirectory` creates `whisper`. This change does
not wire that call into the app. Promotion still requires independently reviewed
paired results on all supported languages, quiet speech and noise controls,
preserved audio/manual edits, and the outstanding phone resource checks.

## Build verification

On 2026-11-03, a separate Linux build with CMake 4.3.0 and GNU C++ 16.2.1
configured and built `whisper-cli` with two build jobs; `--help` exited zero.
The compile database contained exactly one `whisper.cpp`, the verified overlay,
and retained the original source include directory. A CRLF source-copy fixture
configured successfully; altered-source and missing-target fixtures failed with
the intended diagnostics. The original file hash and clean submodule status
were unchanged. This verifies build plumbing, not ASR accuracy or Android use.
