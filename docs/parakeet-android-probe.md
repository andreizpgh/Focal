# Parakeet Android research probe

This opt-in instrumentation suite checks the pinned Parakeet runtime on Android.
It does not replace Focal's recognizer, write note transcripts, record audio, or
enable automatic corrections. Normal app and test builds do not depend on Sherpa.
Do not distribute a research test APK as a Focal release.

The host comparison is recorded in [the research report](benchmarks/2026-11-05-stt/report.md).
The probe is a deployment check: one public English fixture, exact-zero audio and
seeded varying noise. It cannot establish seven-language accuracy, conversational
quality, capture continuity, cancellation responsiveness, battery or thermal cost.

## Verified local inputs

Reuse cached files before downloading anything. New transfers and retries must
remain in the [cumulative budget](benchmarks/2026-11-03-stt/download-budget.json).
Models, AARs and raw receipts stay outside Git.

Place the official
[Sherpa 2.0.1 AAR](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar)
at `build/dependencies/sherpa-onnx-2.0.1.aar`:

- Bytes: `49441341`.
- SHA-256: `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`.
- Runtime: Sherpa `2.0.1`, revision `af2d8d81dbfa61c4cea4c568da78f3982edca005`,
  ONNX Runtime `1.28.2`. The test checks the loaded runtime's version getters.

Use the four files from the
[pinned Parakeet export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78).
Their names, sizes and hashes are checked before native loading by
`ParakeetProbeTest`. Stage them in the target application's private
`files/parakeet-probe/model/` directory. Existing host model files can be copied
over ADB; no network permission or model download is added to the app.

The speech fixture remains the existing verified `app/src/androidTest/assets/jfk.wav`.
Do not replace it with personal recordings. Raw results contain device facts and
transcripts and must remain private.

## Build and run

Use the existing JDK/SDK setup and the [local QA resource limits](local-emulator-qa.md).
Build before starting the dedicated emulator, with at most two Gradle workers:

```sh
./gradlew --offline --no-daemon --max-workers=2 --no-parallel \
  -PfocalParakeetTests=true :app:assembleDebug :app:assembleDebugAndroidTest
```

The flag replaces only the instrumentation Java source set. Public test assets
remain available. Research flags cannot be combined, and release tasks reject
this flag. Install the resulting app/test APK pair on the explicitly selected
dedicated AVD, then stage the verified model files. Never choose a connected
physical phone automatically; perform that work with its owner present.

Run this test with an external bounded watchdog and an explicit ADB serial:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class app.focal.ParakeetProbeTest#offlineRecognizerRunsPublicSpeechAndNoiseControls \
  app.focal.test/androidx.test.runner.AndroidJUnitRunner
```

Do not use `qa-local.py` for this probe: that runner intentionally requires the
normal application suite and its mandatory real-model checks. Rebuild the normal
test APK without the research flag before returning to full application QA.

Read the matching private JSON under `files/parakeet-probe/receipts/` using
`run-as app.focal`. It must say `PASS`, contain all three passed cases and match
the input/runtime identities, in addition to a successful instrumentation result.
Interrupted runs retain their last atomic phase; a missing final status is not
success. Record the exact APK hashes and the device or emulator configuration.

## Measurement and lifetime limits

The recognizer uses CPU, two threads, greedy decoding and automatic language
selection. A fresh stream is created for each input. Requested feature dimension
80 is overridden by model metadata in native initialization; it is not a measured
effective frontend value. No language prompt, reference or vocabulary hint is
provided to the recognizer.

Native decoding is synchronous and has no public abort API. The test releases
each stream after decoding returns and the recognizer after all stream work;
it never frees native objects from another thread. This checks a serial owner,
not Focal's live capture, cancellation or recovery integration.

The receipt separates model loading and decode time. Decode timing starts after
stream setup/audio acceptance, unlike the earlier host probe. PSS/RSS readings
are point measurements; `VmHWM` is the lifetime peak of the instrumentation
process, not the incremental model allocation. Emulator measurements cannot
stand in for ARM64 phone results. Token timestamps and TDT durations are retained
for inspection, not asserted to be reliable word or passage boundaries.
