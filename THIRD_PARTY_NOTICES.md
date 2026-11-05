# Third-party notices

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp), including ggml:
  MIT license, preserved in `third_party/whisper.cpp/LICENSE` and its source tree.
  Pinned commit: `4979e04f5dcaccb36057e059bbaed8a2f5288315` (v1.8.2).
- [Whisper](https://github.com/openai/whisper): code and model weights are MIT
  licensed. Quantized weights are distributed by
  [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp).
- AndroidX and Kotlin: Apache License 2.0. Their notices remain with the
  respective dependencies.
- Gradle wrapper: Apache License 2.0.
- Android speech-test fixture: excerpt of John F. Kennedy's 1961 inaugural
  address (US public domain), from whisper.cpp's `samples/jfk.wav`. The test copy
  retains the original PCM samples with a minimal WAV header.


The optional Ultra build also includes:

- **Parakeet Ultra INT8 ONNX** — Moondream (M87 Labs, Inc.), based on NVIDIA's
  Parakeet TDT 0.6B v3; licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/legalcode.en).
  Marmalade [converted](https://github.com/maxwhipw/marmalade-stt-engines/blob/beadd617a8c999476d6ae327cde78f3a59dfcc44/NOTICE)
  the model to ONNX, omitted the VAD head and quantized the transducer to INT8.
  Focal uses its [release v1](https://github.com/maxwhipw/marmalade-stt-engines/releases/tag/v1)
  files unchanged. Original models: [Moondream](https://huggingface.co/moondream/parakeet-ultra/tree/73175eb7aeb0d82f1e2a6b53b3aabc10a90bcd0b)
  and [NVIDIA](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/541d1f99c6b0c3cd0b11a95167540bb8edefd82b).
- [sherpa-onnx 2.0.1](https://github.com/k2-fsa/sherpa-onnx/tree/af2d8d81dbfa61c4cea4c568da78f3982edca005): Apache License 2.0.
  The APK ships its C API library; Focal's JNI bridge compiles against its C API header, vendored
  unchanged with the license in `app/src/main/cpp/third_party/sherpa-onnx/`.
- [Silero VAD](https://github.com/snakers4/silero-vad) v4 ONNX model (`silero_vad.onnx`, as published in
  sherpa-onnx's [asr-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)):
  MIT License, Copyright (c) 2020-present Silero Team. Full text below.
- [ONNX Runtime 1.28.2](https://github.com/microsoft/onnxruntime/tree/v1.28.2): MIT License, Copyright Microsoft Corporation;
  its upstream third-party dependency notices also apply.

The Ultra APK includes the complete model attribution and license texts, Sherpa
and ONNX Runtime licenses, and ONNX Runtime third-party notices in
`assets/ultra-licenses.txt` (source: `app/src/ultra/assets/ultra-licenses.txt`).
Asset hashes and build instructions are in [Ultra transcription](docs/ultra-transcription.md).

Focal's source and original artwork are covered by the root MIT license.

Silero VAD — https://github.com/snakers4/silero-vad

MIT License

Copyright (c) 2020-present Silero Team

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
