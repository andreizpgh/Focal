# Wider audio context for a selected phrase

The unit of review is a tap on an underlined word or group of words in the reading screen. The original Parakeet Ultra INT8 draft, PCM audio and formatter output are fixed. A second pass uses the same model, pinned sherpa-onnx 2.0.1 C API, greedy CPU decoding and two threads. A replacement affects only the selected UTF-16 range. The recognizer receives audio alone. Language labels and references are for evaluation.

## Recordings and selection

We retained all 182 natural short FLEURS notes, both Maltese readings, 29 primary YODAS2 excerpts and all 19 supplementary publisher excerpts from the multilingual screen. The earlier caption agreement rule determines the 29 eligible YODAS2 notes. It can exclude weak ASR as well as unreliable captions. Each supplementary excerpt keeps its original development or holdout assignment. The 232 notes cover all 25 supported languages. Synthetic switches and parliamentary controls are outside this comparison. No span was selected using its reference text or its second-pass result. [Original splits, caption rule, licenses and sources](../2026-11-19-multilingual/method.md)

The reading screen supplies the exact selection and its timed words. Original windows are identified from the kept word midpoints and the stored start/end times. A word without a matching receipt must have exactly one possible window. Ambiguous ownership skips the tap. The proposed interval contains every original window that contributed a selected word, adds up to six seconds per side, clips at the recording boundaries and is capped at 30 seconds. Less than one second of newly available audio skips the tap. The extension can contain silence. Distinct taps that need identical audio share a measured decode in this experiment. A real tap after model release would pay the load cost again.

The previous screen already used this corpus to identify difficult languages. “Holdout” denotes its existing split. Taps within one recording share a speaker and can also share a decode. French, Polish, Russian and English have no eligible natural holdout tap for a wider crop. To check all 25 languages on held-out audio, a separate control for each of these four takes the lowest-confidence unflagged lexical word with an identifiable wider window in its long holdout recording, breaking ties by word order. The displayed flags and suggestion yields exclude these forced selections. Four additional unchanged-window controls compare second-pass tokens, times and log probabilities directly to the archived first-pass receipts.

## Alternatives and scoring

The selection policies below were fixed before comparing development or held-out scores. Each crop generates at most one spelling per policy. For all new alternatives, the second-pass words must have midpoints inside the original selection's time interval, have no unknown pieces, form a contiguous sequence, overlap its original time interval by at least 60% intersection over union, and grow by at most one word. Strip outer punctuation and retain the original surrounding text. A candidate must change the sequence of normalized lexical words.

| Policy | Rules in addition to time alignment |
| --- | --- |
| Timing only | Every selected word has confidence of at least 0.50. |
| Anchored (primary) | Every selected word has confidence of at least 0.70. At least one immediate neighboring word matches lexically and its midpoint differs by at most 400 ms. |
| Anchored, additional | The primary policy produces a spelling absent from the existing menu for this exact selection. |
| Unfiltered confidence (diagnostic) | Apply the shared time and lexical constraints with no confidence or neighbor requirement. |
| Existing menu | Replay the reading screen's stored overlapping-window readings and nearby spellings, in that order. This requires no fresh ASR. |

For each candidate, replace that one range in the original draft, then compute the change in whole-note Levenshtein word errors against the published reference. Lower is helpful, equal is neutral and higher is harmful. A reduction can be a partial repair. Each tap starts with the original text. The sum of isolated changes does not estimate the result of accepting every suggestion at once. For the existing menu, several options may be available at one tap. We count both suggestions and taps with any helpful option. Eligible notes without a flag contribute zero taps. We did not time human proofreading.

The separate Russian checks use two transcribed monologues and the first fluent reading of the Tarantella dictation. The full classroom session repeats the same written passage for almost 18 minutes. Its seven taps are planned independently. A tap can be scored against the printed key only when three normalized words on either side locate a unique intervening phrase of at most twice the selected word count plus two. Otherwise we count displayed alternatives as unscored. A supplementary textual check located six selections against the printed passage from the original surrounding repetitions before inspecting the suggested spellings. The results file records each start time and its zero-based half-open range in the normalized printed words. One interval was ambiguous. This supplementary alignment was made after the automatic coverage count was known and is reported separately. The first reading is part of the full session and their results must be kept separate. [Russian recordings and printed key](../2026-11-19-punctuation/README.md)

## Reproduction

Use Python 3.11+, JDK 17, the current core JAR and the Kotlin standard library. The multilingual replay supplies a private manifest with paths to the source audio, reference, formatted draft and timed words, plus the published checksums. Each note needs the reading screen's `choices.tsv`, the original `windows` table, 16 kHz PCM16LE and `context.tsv`. Run the context exporter on development and supplementary notes as well as on holdout notes with flags. Raw audio, text and speech tokens stay in local working storage. [Original replay and receipt producers](../2026-11-19-multilingual/method.md)

From the project root, point the environment variables below at your own private files and verified native runtime. The lean session configuration is the file used by the app on lower-memory devices.

```sh
python3 scripts/context-review/prepare.py "$MANIFEST" \
  docs/benchmarks/2026-11-19-multilingual/cases.jsonl "$WORK"
c++ -std=c++17 -O2 -Iapp/src/main/cpp/third_party scripts/context-review/decode.cpp \
  -L"$SHERPA_LIB" -lsherpa-onnx-c-api -o "$WORK/decode"
printf 'EnableCpuMemArena=0\n' > "$WORK/lean.conf"
LD_LIBRARY_PATH="$SHERPA_LIB" "$WORK/decode" models/parakeet-ultra "$WORK/lean.conf" \
  "$WORK/requests.tsv" "$WORK/tokens.tsv" > "$WORK/timings.tsv"
javac -cp "core/build/libs/core.jar:$KOTLIN_STDLIB" -d "$WORK" scripts/context-review/CropWords.java
java -Xmx96m -cp "$WORK:core/build/libs/core.jar:$KOTLIN_STDLIB" CropWords \
  "$WORK/tokens.tsv" "$WORK/words.tsv"
python3 scripts/context-review/score.py "$MANIFEST" "$WORK" "$WORK/cases.jsonl"
```

For the Russian repeated session pass `--repeated-key` to the scorer. To rerun the Android resource probe, compile the same C++ tool using the NDK x86-64 API 26 C++ compiler with `-static-libstdc++`, link the app's matching sherpa C library, and run it with that library and its packaged ONNX Runtime. The Android probe replays six private PCM crops three times in the order recorded in the timing data. Supply start zero and the crop duration when the PCM file has already been trimmed. Model load and PCM-to-result latency use a monotonic clock. Peak RSS comes from Linux `getrusage` for the standalone process and includes the recognizer itself. The resource probe excludes the reading screen, capture and device thermals.
