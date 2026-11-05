# Ultra transcription build

In the `-PfocalUltra=true` build, Record uses Parakeet Ultra automatically for
new notes. The recording control shows **Automatic** language. Finish saves
the remaining speech in the same note, ready for review, editing, Copy or Share.
Transcription stays offline and automatic LLM correction remains off.

Ultra processes durable original PCM in windows of up to 30 seconds. While
recording, it waits for 35 seconds of saved audio from the current cursor.
Pause and Finish make the saved tail eligible without that wait. The window
size limits each decode. Primary transcription supports recordings longer than
three minutes within device storage and Android service limits. See
[capture and recovery](architecture.md#ultra).

Existing Whisper notes keep their language and engine for recovery. On a
completed Whisper note of up to three minutes, **Transcribe with Ultra** opens
a copyable preview from the original audio. Manual edits and exclusions remain
in the saved note and are not applied to that new transcription.

The four Ultra assets add 670,577,016 bytes to the model payload. The first
nonconstant window triggers extraction and verification in app-private storage.
Later sessions verify and reuse the copy. Preparation requires the model's size
plus 32 MiB of free private storage. The APK and private copy both require disk
space. Cancellation is checked during preparation and after a native decode
returns.

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
The Alpha 8 combined build ran out of memory before release R8 completed.
Building its minified APK in a separate JVM completed that stage.

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

## Primary-path verification

The initial primary build completed in 5m 25s with 36 core JVM tests, 18 app
JVM tests and debug lint passing. A focused emulator run passed 24 tests in
53.281 s, including default Ultra recording with the public JFK fixture,
synthetic capture longer than three minutes, database migration and recovery.

On 21 previously used DEV inputs staged as saved audio, the Android recovery
service matched earlier Ultra scores on all 14 short speech inputs. Against
historical host Whisper Base, word errors were 25 → 12 / 210 for normal speech
and 31 → 15 / 210 for quiet speech. The quiet Portuguese case rose from 5 to
8 word errors. Four controls emitted no words. The two earlier long DEV
streams matched prior Ultra word and character error counts. A 276.8-second
Russian stream made by repeating existing DEV audio produced 48 errors / 420
words in ten windows. The repeated stream is a duration check and shares its
source with an earlier case. The comparison measures recognition on saved PCM
and uses historical Base scores.

The primary build passed all 146 emulator tests and a separate minified runtime
smoke with public speech. Physical-phone validation remains pending.
See [local emulator QA](local-emulator-qa.md) and
[device validation](device-validation.md). Earlier saved-audio comparisons are
recorded in the [STT benchmark report](benchmarks/2026-11-05-stt/report.md).
