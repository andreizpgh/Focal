# Optional Ultra transcription build

The `-PfocalUltra=true` build includes Parakeet Ultra as an explicit alternative
for a completed recording of up to three minutes. The alternative reads the
saved original and displays copyable text; it does not replace the recording,
the existing transcript, or manual edits. Live transcription continues to use
Whisper. Ultra selects its language automatically rather than using the recording's
selected speech language. Review its text before copying it. Processing is local;
there is no LLM correction or model download in the app.

The four Ultra assets add 670,577,016 bytes to the model payload. On the first
alternative request they are copied from the APK into app-private storage and
verified; later requests verify and reuse that copy. Preparation requires the
model's size plus 32 MiB of free private storage. Installing a standard build
over this variant preserves that private copy along with the app's other data.
APK, installed app and private copy each require space; these sizes are not a measurement of phone memory,
latency, battery, or thermal behavior. Cancellation is checked during file
preparation and between decoding operations; an active native decode cannot be
interrupted until it returns.

## Prepare verified local assets

Use the prerequisites and existing Base and Silero assets in
[local emulator QA](local-emulator-qa.md). Reuse the cached files before making
any new transfer; account for any actual transfer and retry in the
[download ledger](benchmarks/2026-11-03-stt/download-budget.json). Model files
and runtime binaries do not belong in Git.

Place exactly the following files in the ignored directory
`build/stt/parakeet-ultra-20261105/model/`. The Gradle task checks each file's
size and SHA-256 and rejects missing, extra, symbolic-link, or corrupt files.
The three ONNX files are uncompressed in the APK. The source cache is reused
directly; Android build intermediates may still require additional disk space.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `encoder.int8.onnx` | 652,282,526 | `7ef3ca4ffb49d60d4faa757b2930c8cec9927e73c24f6155d5b23a00229a7ed6` |
| `decoder.int8.onnx` | 11,845,274 | `1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922` |
| `joiner.int8.onnx` | 6,355,277 | `8ba94c6919c17a6bd27368fb89533628a72ffc81d4acedebf3cdcb2cae331dbb` |
| `tokens.txt` | 93,939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` |

The model files are the [Marmalade release v1](https://github.com/maxwhipw/marmalade-stt-engines/releases/tag/v1)
INT8 ONNX conversion of [Moondream Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra/tree/73175eb7aeb0d82f1e2a6b53b3aabc10a90bcd0b),
based on [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/541d1f99c6b0c3cd0b11a95167540bb8edefd82b).
The conversion omitted the VAD head and quantized the ONNX transducer to INT8;
Focal uses the four published files unchanged. The release tag is not immutable,
so verify the file hashes above rather than relying on its name. See the
[conversion attribution](https://github.com/maxwhipw/marmalade-stt-engines/blob/beadd617a8c999476d6ae327cde78f3a59dfcc44/NOTICE)
and [third-party notices](../THIRD_PARTY_NOTICES.md).

Place the official [Sherpa ONNX 2.0.1 AAR](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar)
at `build/dependencies/sherpa-onnx-2.0.1.aar`. It is 49,441,341 bytes, SHA-256
`a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`.
Its pinned upstream revision is `af2d8d81dbfa61c4cea4c568da78f3982edca005`;
the package includes ONNX Runtime 1.28.2. Do not substitute a different AAR.

## Build and check

Close the AVD before prebuild. Use one Gradle worker and a 1,536 MiB heap.
The initial combined build ran out of memory before release R8 completed.
Building the minified APK in a separate JVM completed that stage.

```sh
./gradlew --offline --no-daemon --max-workers=1 --no-parallel \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -PfocalUltra=true \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintRelease

# Optional minified release check in a fresh JVM.
./gradlew --offline --no-daemon --max-workers=1 --no-parallel \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -PfocalUltra=true :app:assembleRelease
```

Omit `--offline` only if the required Gradle dependencies are not already
available. `-PfocalUltra=true` is mutually exclusive with the native-fault and
Parakeet research flags. It includes the runtime and four Ultra assets in the
app for this variant, for both supported ABIs (`arm64-v8a` and `x86_64`).
Without the flag, the ordinary build uses the standard factory and does not
include the Ultra runtime or model. The separate private research batch test is
not part of the Ultra full application test suite.

For the opt-in full emulator suite and optional release checks, follow the Ultra
section in [local emulator QA](local-emulator-qa.md). Emulator tests and host
experiments do not establish physical-phone acceptance or recognition quality
for all languages. The comparison and its limits are recorded in the
[STT benchmark report](benchmarks/2026-11-05-stt/report.md).
