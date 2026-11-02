# Replay production context and boundary decisions

`scripts/TranscriptReplay.kt` calls the actual `Transcript.trustedContext` and
`Transcript.reconcileBoundary` implementations from `core`. It performs no
inference and contains no second implementation of either algorithm. Use decoded
hypotheses as input; reference transcripts do not enter this tool.

Compile it with the gate replay tools outside recognition timing windows:

```sh
kotlinc core/src/main/kotlin/app/focal/core/SpeechGate.kt \
  core/src/main/kotlin/app/focal/core/Transcript.kt \
  scripts/SpeechGateReplay.kt scripts/TranscriptReplay.kt \
  scripts/tests/SpeechGateReplayTest.kt scripts/tests/TranscriptReplayTest.kt \
  -include-runtime -d /tmp/focal-replay.jar
java -cp /tmp/focal-replay.jar app.focal.tools.TranscriptReplayTest
java -Xmx32m -cp /tmp/focal-replay.jar app.focal.tools.TranscriptBoundsTest
java -cp /tmp/focal-replay.jar app.focal.tools.TranscriptReplay \
  context /private/previous.tsv 15500 > /private/context.base64
java -cp /tmp/focal-replay.jar app.focal.tools.TranscriptReplay \
  reconcile /private/previous.tsv /private/decoded.tsv 15500 31000 16000 \
  > /private/passages.tsv
```

The cached-compiler command in [gate replay](speech-gate-replay.md) works with the
same six sources; its runtime classpath must also include the Kotlin standard
library. A single jar can contain both tools. `TranscriptReplay` uses an object
entry point; the gate entry point remains `SpeechGateReplayKt`.

Both segment files have four tab-separated columns:

| Column | Meaning |
| --- | --- |
| `startMs` | Absolute start in the original recording, milliseconds |
| `endMs` | Absolute end, milliseconds |
| `uncertain` | Literal `true` or `false` |
| `textBase64` | Standard base64 of the complete UTF-8 hypothesis, empty for a missing-range cue |

The header is optional on input and always emitted by `reconcile`. LF and CRLF
are accepted. Text may contain tabs, newlines, quotes, NUL or supplementary Unicode
characters because the transport encodes it. Invalid UTF-8, malformed columns,
more than 100 input segments, text over 65,536 UTF-8 bytes, and unbounded rows are
rejected. Timestamps must be nonnegative signed 64-bit values and each segment
must end at or after its start. Inputs and results stay in private working files;
base64 is an encoding, not a privacy measure.

`context previous.tsv beforeMs` emits one base64-encoded UTF-8 line, including an
empty line for empty context. It uses the production 240-character limit.
`reconcile previous.tsv decoded.tsv chunkStartMs chunkEndMs [overlapEndMs]`
emits segments after the actual production overlap and missing-audio decisions.
Omit `overlapEndMs` when the chunk has no prior audio overlap.

To match the app, supply chronological previous passages selected as the latest
six whose start precedes the previous chunk end for an overlapped chunk, or the
current chunk start otherwise. Set `uncertain` as the app does: the passage is
uncertain and has not been manually reviewed. Pass the current chunk start to
`context`; a passage partly overlapping it cannot supply prompt text. Decoded
segments should already have the absolute timestamp conversion and padding
filter used by `Transcriber`. This tool does not simulate those database or
decoder steps, calibrate uncertainty, or establish phone performance.

Overlap matching retains at most twelve word references and normalizes only
that bounded tail/prefix. It does not concatenate full legacy passages. A token
longer than 4,096 characters cannot establish a duplicate; ambiguous audio stays
visible for review. Original manual text is retained. A NUL-containing manual
correction stops prompt carryover because the native prompt API cannot accept it.

Standalone tests exercise the real operations through their CLI arguments and
transport: Unicode/quotes/control-character round trips, an uncertain or NUL
context barrier, partial audio overlap, duplicate removal with a missing-range
cue, and malformed or oversized input rejection. No public or private speech
reference is needed for these synthetic integration checks.
