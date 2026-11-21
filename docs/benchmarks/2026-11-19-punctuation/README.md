# Russian punctuation on long recordings

On the Android runtime, filling high-confidence gaps reduced the number of punctuation decisions a reviewer would need to revisit from 158 to 150 across two long monologues. It produced no change on the classroom dictation. Rebuilding all punctuation increased errors overall. This screen supports experimenting with individually reviewable suggestions. The model is outside the app.

| Recording | ASR words | Scored boundaries | Focal | Fill at 0.90 | Replace all |
| --- | ---: | ---: | ---: | ---: | ---: |
| Introduction | 513 | 472 | 54 | 49 | 52 |
| Listening | 1,006 | 943 | 104 | 101 | 132 |
| Tarantella, first reading | 104 | 96 | 4 | 4 | 11 |
| Tarantella, complete with repetitions | 638 | 307 | 20 | 20 | 37 |

The last three columns are discrepancies with the author's punctuation. Of 14 proposed signs on the two monologues, 13 could be scored. Ten fixed a mark, two damaged a previously correct boundary, and one supplied a period where the author used an exclamation mark. One proposal fell next to a lexical mismatch and is unscored. Nine of the ten exact fixes were commas. Only one additional sentence ending matched the reference as a boundary. Paragraph breaks and every recognized word were preserved. In one monologue the prediction recovered the comma before «чтобы», but also split the conjunction «так что». We evaluated signs against the published text, without timing a person proofreading.

An exploratory sweep on these same recordings traded accuracy for coverage. At 0.50 the model fixed 37 marks across the two monologues and spoiled 17 previously correct sites. It also introduced three errors on the complete dictation. At 0.95 it fixed six marks on the monologues with no observed regressions at scored boundaries. These thresholds have no independent validation. Both monologues have the same speaker.

## Memory and latency

Android API 35, x86-64 emulator, two virtual CPU cores. The emulator raised its guest RAM allocation to 2,560 MiB. All four notes used the CPU provider in the project's ONNX Runtime 1.28.2 AAR. ASR had exited before loading the punctuation model. The disk cost of the INT8 graph and packed vocabulary together is 30,927,140 bytes (29.5 MiB). The runtime is already a Focal dependency.

| Recording | Fresh text-to-predictions median | Loaded-model median / p95, complete note |
| --- | ---: | ---: |
| Introduction, 513 words | 543 ms | 168 / 407 ms |
| Listening, 1,006 words | 419 ms | 177 / 357 ms |
| Tarantella, first reading | 101 ms | 17 / 18 ms |
| Tarantella, complete | 231 ms | 120 / 295 ms |

The fresh column times reading UTF-8 text, mapping the vocabulary, preparing all windows, loading the native runtime and graph, executing one pass, and releasing the session, in one Android process. Three fresh processes per recording used normal OS file caches. The baseline excludes Android process startup. Drawing the UI, writing paragraphs, applying punctuation labels and speech recognition are outside the timer. Fresh-process peak RSS was 203–205 MiB, **85–87 MiB above the starting Android VM** (rounded). Preparing the text and filling windows took 2–12 ms. The warm column replays the same token windows without graph loading, for 30 whole-note passes with two threads in a separate executable. The shorter introduction ran first after emulator boot, so these samples should not be used to fit time per word. The numbers describe this emulator on a Ryzen 5 5500U; physical midrange ARM latency, peak memory, power and thermal behavior remain unmeasured.

Android token windows exactly matched the Hugging Face tokenizer on all four inputs. We scored the actual Android logits. Linux produced the same proposed text in three of four recordings and one fewer correct comma on Listening. Linux and Android use the same graph and runtime version but can produce different quantized scores. [Detailed scores, timings, memory and file hashes](results.json).

## Recordings and comparison

Two complete, continuous Russian Progress monologues by the same speaker have independently published transcripts. The third recording is a classroom dictation read by another speaker. Its printed answer key is on the recording's page. Audio, source transcripts and token IDs are kept out of Git.

| Input | Audio evaluated | Reference |
| --- | --- | --- |
| Introduction | 4 min 33 s | [Russian Progress, June 12](https://russianprogress.com/2018/06/12/learn-russian-the-natural-way/) |
| Listening | 9 min 11 s | [Russian Progress, June 19](https://russianprogress.com/2018/06/19/principle2/) |
| Tarantella, first fluent reading | First 74 s of the original file | [Printed dictation, 105 words](https://childbookcase.ru/onlayn-diktanty-po-russkomu-yazyku/) |
| Tarantella, complete classroom session | 17 min 48 s, including slow repetitions and the final rereading | Same printed key, scored separately |

The starting point is Focal's formatted draft. All files were converted to 16 kHz mono PCM and processed locally with the locked Parakeet Ultra INT8 recognizer, greedy decoding with two CPU threads, Silero voice detection, the project's Segmenter, ownership filters, silence guards and batch Formatter. The same words and case go into every comparison. Publisher transcriptions include editorial choices and occasionally differ from the speech.

The model is [RUPunct_small](https://huggingface.co/RUPunct/RUPunct_small), a three-layer Russian token classifier, using the [INT8 export](https://huggingface.co/ekhodzitsky/rupunct-small-onnx/tree/c4a35be7e4a413cdfb08427db75b621b571569c9). The model takes lowercase words. Each block owns at most 80 WordPiece tokens with up to 24 contextual tokens on either side. Only the first piece of each owned word votes. Softmax probabilities with the same punctuation sign are summed across case labels. Case predictions are discarded. Window length is at most 130 including special tokens.

The primary policy fills whitespace-only gaps when the sign's probability reaches 0.90, like an optional review suggestion. It preserves all existing marks, spelling, capitalization, numbers, hyphenated words and paragraph breaks. A diagnostic variant replaces every scored mark with the highest-probability prediction. Hyphenation is outside this task.

For each fluent reading, minimum-edit word alignment ignores case and treats е/ё as equivalent. We compare the punctuation between consecutive exact word matches and the final mark when both final words match. Quotation marks and brackets are ignored. A missing, extra or wrong-type sign costs one review site. Sentence-end counts treat a period, ellipsis, question mark and exclamation mark as boundaries. For the full dictation, we score only the middle boundary of a six-word sequence identical to a unique sequence in the printed key. This permits repeated passages without treating partial, repeated fragments as complete sentences. Do not pool these repeated observations with the first reading. Word substitutions, paragraph quality and human proofreading time are outside the reported score.

## Repeat the measurement

Use the model revision above and verify the hashes in `results.json`. The Linux harness uses the host build of the same ONNX Runtime release as the Android AAR. Compile the replay executable against the [ONNX Runtime 1.22.0 C API header](https://github.com/microsoft/onnxruntime/blob/v1.22.0/include/onnxruntime/core/session/onnxruntime_c_api.h) and the applicable shared library. It requests backward-compatible API version 22. Install `tokenizers==0.22.2` in an isolated Python environment. Supply a UTF-8 copy of the Focal draft and the independent published reference.

```sh
cc -std=c11 -O2 -I"$ORT_HEADERS" scripts/punctuation-bench/runner.c \
  -L"$ORT_LIB" -lonnxruntime -o "$BENCH/runner"
LD_LIBRARY_PATH="$ORT_LIB" python scripts/punctuation-bench/evaluate.py \
  --model "$MODEL_DIR" --runner "$BENCH/runner" --asr "$DRAFT" \
  --reference "$REFERENCE" --out "$BENCH/note"
```

Use `--repeated-reference` in place of `--reference` for the classroom session. Omit both for a timing-only pass. The evaluator emits token windows, timing samples, aggregate scores, a local proposed text and change positions. Treat the windows and proposed text as transcript data.

For Android, use the NDK's `aarch64-linux-android26-clang` for ARM64 or `x86_64-linux-android26-clang` for the emulator and the matching `jni/$ABI/libonnxruntime.so` from the pinned AAR. Place the binary, library, ONNX graph and one note's window file in a temporary device directory. From that directory, the following emits fresh-process load time, first and warm full-note passes, and process memory in KiB.

```sh
LD_LIBRARY_PATH=. ./runner rupunct_small_int8.onnx windows.txt 2 30
```


The text benchmark reads the original draft in an Android VM with a memory-mapped vocabulary. Build the vocabulary with `pack_vocab.py tokenizer.json vocab.bin`. Compile `AndroidTextBench.java` with Java 8 compatibility and convert the class using D8 with minimum API 26 into `bench.jar`. Build `libpunctbench.so` from `android_bridge.c` and `runner.c` with the same NDK compiler and ORT library, adding `-shared -fPIC`. The following runs in the temporary device directory. On Android the archive must be read-only. Compare `actual.txt` byte for byte with the reference tokenizer's windows.

```sh
chmod 444 bench.jar
CLASSPATH="$PWD/bench.jar" LD_LIBRARY_PATH="$PWD" app_process -Djava.library.path="$PWD" \
  /system/bin AndroidTextBench "$PWD" "$PWD/draft.txt" "$PWD/actual.txt" 0
LD_LIBRARY_PATH=. ./runner rupunct_small_int8.onnx actual.txt 2 0 android-logits.bin
```

Pass the returned floats to `evaluate.py --logits android-logits.bin` in place of `--runner`, using the identical text and tokenizer. The Java tokenizer covers the lexical inputs in this Russian benchmark. Check token parity before using it for another language or corpus.
