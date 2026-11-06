# Third-party notices

Focal's own source code and original artwork are covered by the root
[MIT license](LICENSE). The components below keep their own licences. The app
shows every notice that ships with it under **Settings › Open-source licenses**;
the full texts are in `app/src/main/assets/licenses.txt` and
`app/src/main/assets/ultra-licenses.txt`.

## In the app

### Parakeet Ultra INT8 ONNX (speech model)

- **What:** the four model files (`encoder.int8.onnx`, `decoder.int8.onnx`,
  `joiner.int8.onnx`, `tokens.txt`) from Marmalade's
  [release v1](https://github.com/maxwhipw/marmalade-stt-engines/releases/tag/v1),
  distributed unchanged. Sizes and SHA-256 digests are pinned in
  `models/parakeet-ultra.json`.
- **Licence:** [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/legalcode.en).
- **Attribution:** Parakeet Ultra was created by Moondream (M87 Labs, Inc.) by
  post-training NVIDIA's Parakeet TDT 0.6B v3, which is also licensed under CC BY
  4.0. Original models:
  [Moondream Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra/tree/73175eb7aeb0d82f1e2a6b53b3aabc10a90bcd0b)
  and [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/541d1f99c6b0c3cd0b11a95167540bb8edefd82b).
- **Changes:** Marmalade converted the Moondream weights to ONNX for sherpa-onnx,
  exported the transducer (encoder, decoder and joiner), omitted the VAD head and
  quantized the weights to INT8, keeping the v3 vocabulary and without retraining
  ([conversion notice](https://github.com/maxwhipw/marmalade-stt-engines/blob/beadd617a8c999476d6ae327cde78f3a59dfcc44/NOTICE)).
  Focal makes no further changes.

### Silero VAD (voice activity model)

- **What:** the Silero VAD v4 ONNX model (`silero_vad.onnx`), unchanged, as
  published in sherpa-onnx's
  [asr-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models).
- **Licence:** MIT, Copyright (c) 2020-present Silero Team
  ([silero-vad](https://github.com/snakers4/silero-vad)).

### sherpa-onnx 2.0.1

- **What:** the C API library (`libsherpa-onnx-c-api.so`) from the
  [2.0.1 Android AAR](https://github.com/k2-fsa/sherpa-onnx/tree/af2d8d81dbfa61c4cea4c568da78f3982edca005),
  shipped byte for byte. Focal's JNI bridge compiles against the C API header,
  vendored unchanged with its licence in `app/src/main/cpp/third_party/sherpa-onnx/`.
- **Licence:** Apache License 2.0.

### ONNX Runtime 1.28.2

- **What:** `libonnxruntime.so`, from the same AAR, shipped byte for byte.
- **Licence:** MIT, Copyright (c) Microsoft Corporation
  ([source](https://github.com/microsoft/onnxruntime/tree/v1.28.2)). Its
  third-party notices also apply and ship in `ultra-licenses.txt`.

### Inter 4.1

- **What:** `app/src/main/res/font/inter_var.ttf`, a subset of `InterVariable.ttf`
  from the [Inter 4.1 release](https://github.com/rsms/inter/releases/tag/v4.1),
  made by `scripts/subset-inter.py`.
- **Licence:** SIL Open Font License 1.1, Copyright (c) 2016 The Inter Project
  Authors.

### AndroidX, Jetpack Compose, Kotlin and kotlinx.coroutines

- **Licence:** Apache License 2.0. Versions are pinned in
  `gradle/libs.versions.toml`.

### Material Symbols

- **What:** the Rounded icons drawn as `ic_*.xml` vectors in
  `app/src/main/res/drawable` (Focal's own mark and shortcut icons excepted).
- **Licence:** Apache License 2.0
  ([material-design-icons](https://github.com/google/material-design-icons)).

## In the repository only

- **Gradle wrapper:** Apache License 2.0.
- **Inter 4.001** (`design/brand/Inter.woff2`), used only to outline the
  logo lockups in `design/brand`: SIL Open Font License 1.1, text in
  `design/brand/Inter-LICENSE.txt`.
- **Speech test fixture** (`app/src/androidTest/assets/jfk.wav`): an excerpt of
  John F. Kennedy's 1961 inaugural address, in the public domain in the United
  States, taken from whisper.cpp's `samples/jfk.wav` with the original PCM samples
  and a minimal WAV header.

## For evaluation only

- **FLEURS** (Conneau et al., 2022, Google;
  [dataset](https://huggingface.co/datasets/google/fleurs)): licensed under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Focal's speech-quality
  figures use 84 of its read-speech clips. Their reference transcriptions are
  reproduced in `docs/benchmarks/fleurs-references.json`; no FLEURS audio is in
  the repository or the app.
