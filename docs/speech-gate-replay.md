# Replay the production speech gate on the host

`scripts/SpeechGateReplay.kt` reads mono 16 kHz signed PCM16 little-endian audio
through the actual `core/SpeechGate.kt`. It streams one 20 ms frame at a time,
retains the final partial frame, and flushes exactly as capture does at Finish.
It does not approximate the gate, transcribe audio, simulate pause/resume, or
establish Android capture or phone performance. No audio or transcript belongs
in Git.

Compile once outside any recognition timing window:

```sh
kotlinc core/src/main/kotlin/app/focal/core/SpeechGate.kt \
  scripts/SpeechGateReplay.kt scripts/tests/SpeechGateReplayTest.kt \
  -include-runtime -d /tmp/focal-gate-replay.jar
java -cp /tmp/focal-gate-replay.jar app.focal.tools.SpeechGateReplayTest
java -cp /tmp/focal-gate-replay.jar app.focal.tools.SpeechGateReplayKt \
  /private/recording.pcm > /private/ranges.tsv
```

With an already cached embeddable Kotlin compiler, the same sources need no
Gradle build or downloads. Set `FOCAL_KOTLIN_COMPILER_CP` to the compiler and its
cached dependencies, and `FOCAL_KOTLIN_STDLIB` to the matching standard library:

```sh
java -cp "$FOCAL_KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$FOCAL_KOTLIN_STDLIB" \
  core/src/main/kotlin/app/focal/core/SpeechGate.kt \
  scripts/SpeechGateReplay.kt scripts/tests/SpeechGateReplayTest.kt \
  -d /tmp/focal-gate-replay.jar
java -cp "/tmp/focal-gate-replay.jar:$FOCAL_KOTLIN_STDLIB" \
  app.focal.tools.SpeechGateReplayTest
java -cp "/tmp/focal-gate-replay.jar:$FOCAL_KOTLIN_STDLIB" \
  app.focal.tools.SpeechGateReplayKt /private/recording.pcm > /private/ranges.tsv
```

The TSV reports `startSample`, exclusive `endSample`, and `overlap`. Divide
sample offsets by 16 to match the service's truncated millisecond offsets;
use those millisecond offsets when comparing the decoder's actual stored audio
ranges. Each millisecond contains 32 PCM bytes. Keep the source sample offsets
when measuring gate coverage so sub-millisecond tails remain visible.

For a boundary experiment, concatenate the selected public clips into a stream
longer than 16 seconds and retain their exact original sample offsets. Replay
that identical source for ordinary and quieter speech, zero-valued silence,
noise and any deliberate silence gaps. Compare words decoded from gated ranges
with the same source decoded without gating, and inspect uncovered spoken audio
and the 500 ms hard-cut overlap separately. A synthetic signal check validates
the harness; it does not measure speech recognition or quiet-speech coverage.

The standalone checks cover signed PCM serialization against direct production
gate input, arbitrary odd-sized reads, a long boundary and unpadded final tail,
bounded read requests, silence/DC offset/low noise, quiet input, empty input and
rejection of incomplete samples. Preserve private audio, TSV and per-clip
decoding outputs outside the repository; publish only selected provenance and
aggregate findings.
