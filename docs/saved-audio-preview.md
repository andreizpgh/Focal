# Saved-audio alternative transcript experiment

This opt-in debug build evaluates Parakeet on a completed recording's original
audio. It is not enabled in ordinary APKs or releases. The capture path still
uses the bundled Whisper model and the user's selected speech language.

**Not accepted for release:** the implemented path completed its functional
checks but worsened aggregate word and character errors on the bounded
seven-language DEV screen. The Spanish outlier and Italian overlap issue remain
included. See [results and limitations](benchmarks/2026-11-05-stt/report.md).

In the experimental build, open a completed recording's actions and choose
**Alternative transcript**. Parakeet detects the language automatically. The
result is read-only and can be copied; the saved transcript, manual edits,
language, passages and audio are not replaced. Preview state survives rotation
but is deliberately not persisted across process death.

The experiment accepts up to three minutes of PCM. It reads the entire saved
audio in 16-second windows with 500 ms overlap, including quiet audio omitted by
the capture speech gate. Only exactly constant samples skip decoding. Whole-window
timestamps do not establish word boundaries: uncertain repetitions can remain at
overlaps. Empty nonconstant ranges are identified separately and excluded from
Copy. This is not automatic language correction or calibrated error detection.

The worker owns one recognizer and one audio window at a time. Capture and
destructive operations cannot take its session claim. Stop, navigation away, or
backgrounding requests cancellation; a synchronous native call must return before
resources and the claim are released. Rotation retains the operation. Severe
thermal pressure rejects starting a preview; power saving selects one inference
thread, otherwise two. This does not establish phone thermal or battery safety.

## Local verification

Reuse the checksum-verified model files and official runtime described in
[the Android probe guide](parakeet-android-probe.md). The model must already be
staged in app-private `files/parakeet-probe/model`; no download or installer is
part of this experiment. The adapter verifies every model file before opening it.

```sh
./gradlew --offline -PfocalParakeetPreview=true \
  :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Use the documented [local resource limits](local-emulator-qa.md), then install
the two exact APKs on the dedicated test AVD. Run these opt-in tests explicitly:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class app.focal.SavedAudioPreviewIntegrationTest,app.focal.SavedAudioPreviewBackgroundIntegrationTest \
  app.focal.test/androidx.test.runner.AndroidJUnitRunner
```

The first test uses the bundled public JFK fixture, opens the real menu, recreates
the Activity while previewing, checks the result and clipboard, and compares the
complete original note, passages, chunks and audio digest. It writes a private
aggregate receipt under `files/parakeet-probe/receipts`. Its elapsed time includes
menu animation, checksum verification, model load, decode, recreation and UI
publication. The second test sends Home while the operation is active and checks
cancellation, claim release and unchanged originals. It does not assert that Home
arrives inside a native decode call.

Ordinary local QA also runs the processor's JVM tests and the controller's Android
tests with a blocking fake decoder. Those cover exact input windows and tails,
quiet/constant audio, size limits, Main-thread publication, cancellation and
ownership while a synchronous call is blocked. Release tasks reject either
Parakeet research flag. Rebuild without the flag for normal full-app QA.

Acceptance still requires paired speech-quality evidence, overlap and noise checks,
and physical-phone latency, memory, thermal and battery measurements. Emulator
timings and the public English fixture alone do not satisfy those requirements.
