# Following alternative spellings to the end of a word

On the frozen holdout, the same recognizer produced a word-error-reducing alternative for 52 of 343 flagged selections. The previous single-piece experiment found one at 16. Finishing the word uncovered useful spellings, but choosing which one to show remained difficult. The strict menu added 145 options absent from the existing menu. Applying those individually would improve 23 selections, leave 97 tied and worsen 25. The experimental decoder stays outside the app.

## What changed for the reviewer

Each tap selects the same original words, including when adjacent underlines form one phrase. For each proposed replacement we measure the change in whole-recording word edit distance against the frozen reference. Each change starts from the original transcript. A tie can still be a wrong spelling. The complete pool is an upper bound assuming the reader always selects the best option.

| Held-out menu | Selections with options | Selections with any helpful option | Helpful options | Tied | Harmful |
| --- | ---: | ---: | ---: | ---: | ---: |
| Existing audio-overlap and nearby-spelling menu | 23 | 7 | 7 | 11 | 5 |
| Previous single-piece pool | 231 | 16 | 17 | 364 | 56 |
| Same new branch starts, original suffix fixed | 246 | 21 | 22 | 616 | 102 |
| All rejoined continuations | 222 | 52 | 54 | 443 | 84 |
| Up to two ranked continuations | 205 | 42 | 42 | 225 | 57 |
| Strict first choice | 151 | 27 | 27 | 99 | 25 |
| Strict first choice absent from existing menu | 145 | 23 | 23 | 97 | 25 |
| Broad choice also present in nearby confident words | 15 | 7 | 7 | 4 | 4 |

Thirty selections had a useful complete continuation where the existing menu and both single-piece pools had none. Twenty-three strict choices added a helpful option at a selection where the existing menu had none, across 14 recordings. At 13 of these selections the previous single-piece pool also lacked a helpful choice. On the separate development split the strict policy added 128 spellings, with 17 helpful, 94 tied and 17 harmful.

The test used 123 development and 109 held-out recordings, spanning 25 languages. Of the 343 held-out underlines, 341 could be attributed unambiguously to original recognition windows. English had no natural holdout underline. The pool helped at least once in 15 languages, including seven of 35 Latvian selections and ten of 65 Maltese selections. It helped at none of 56 Greek selections. One Russian holdout selection had a helpful option in the broad menu. The separate 17:48 Russian classroom dictation had seven flags, six scoreable against the printed key. Its only completed option increased word errors by one. Repetitions in this recording use the earlier adjudicated printed-key mapping. Time spent by a human proofreading has not been measured.

## Android cost

The standalone recognizer ran on Android 35 x86-64, with two virtual CPU cores and 2.42 GiB of reported guest RAM. The ten timed windows lasted 3.73–20.74 seconds. Eight came from the earlier comparison. The other two had many uncertain emissions in Maltese and Latvian. Each process discarded a warm-up window.

Direct timing around collecting alternatives and following them cost **73.1 ms per window in the median**. The largest two-run average was **164.9 ms**. There were zero to 12 branch starts per window. This timing includes the sparse diagnostic trace and excludes menu generation. It requires the running recognizer and adds no model weights.

Across two full recognitions per window in each condition, total elapsed time was 2.0% lower with the feature enabled. The median paired difference was −63 ms. Run-to-run variation was larger than the expected overhead, so the difference does not establish a speedup. Peak recognizer RSS was 930.3 MiB disabled and 930.1 MiB enabled. These sample peaks cannot resolve a small memory difference. Every timed process reproduced the packaged recognizer's entire token, duration, timestamp and confidence receipt. The two directly instrumented passes also reproduced the same alternatives at every branch.

These measurements cover CPU inference on an emulator. ARM phone latency, heat, energy and full-app peak memory remain unmeasured.

## Selecting one option with a small decision model

The classifier trained on 457 distinct within-selection options from development recordings. At the 10% development display budget, it offered 27 choices across excluded development languages. Zero helped, 17 tied and ten harmed. Applied to the holdout, the same threshold yielded **15 choices, three helpful, nine tied and three harmful**, with zero net change in word errors. No development threshold met the high-precision gate. Nearby acoustic corroboration alone added a helpful choice beyond the existing menu at two held-out selections and none on development. Neither approach supports automatic replacement.

The ranking screen uses a three-class linear classifier. Its features are the initial token probability, its ratio to the original, the sequence score difference clipped to ±8, the absolute boundary shift, whether the replacement splits a word, an independent matching spelling in the surrounding 48 words on either side, and relative character edit distance. Features are available before the reference is read. Labels describe whether the candidate helps, ties or harms whole-note word accuracy. Training excludes suggestions already in the app's menu.

The score is predicted probability of helping minus predicted probability of harming. Softmax regression uses 24 coefficients, including three intercepts, and unit L2 regularization on the slopes. One threshold targets offers at 10% of all development flags. A second threshold requires at least eight proposals across three development languages, with at least 60% helpful and at most 10% harmful. Both thresholds use predictions from models trained with the entire validation language excluded. The final fit uses development data only. Earlier experiments and the fixed-rule results had already been inspected on this holdout, so this comparison is exploratory. A human reviewer was not timed.

## Reproducing the candidate comparison

Use the pinned model, source archive, CPU settings, public audio receipts and selection plan from the [single-piece experiment](../2026-11-21-first-pass/method.md). The [benchmark sources](../../../scripts/word-review) are separate from the application build.

1. Compile the diagnostic host executable from the original decode harness, the first-pass stock observer built with `-DFOCAL_CAPTURE_ENCODINGS`, and `encodings.cpp`. Run the 415 original requests with the released host shared library and `FOCAL_WORD_ENCODINGS` pointing to a private output file. This records the emitted tokens and the encoder's valid frames. Keep the binary intermediates local.
2. Apply `decoder.patch` to the original pinned sherpa tree and copy `replay.h` and `word-paths.inc` to its `sherpa-onnx/csrc` directory. Build the C API for the desired platform. Run `vocab.py` on the locked token list. Set `FOCAL_WORD_TYPES` to its output and `FOCAL_WORD_PATHS` to a writable trace file. For the quality run only, set `FOCAL_WORD_REPLAY` to the released encoder frames, using the identical single-stream request order.
3. The original greedy path runs to completion. At up to eight low-confidence lexical pieces per window, the experiment saves the predictor state and up to three competing lexical IDs with the same word-start status. The winner probability must be below 0.50, the alternative probability at least 0.035 and its ratio to the winner at least 0.10. Each fork uses its own predictor state and the original encoder frames. It follows the best next token and duration, including blank emissions. A fork can produce up to eight pieces across one or two words and run up to 24 joiner calls. The target boundary must be within 18 frames. A result must emit the next original token within two frames of its original timestamp, or actually reach the end for a final word.
4. Rank by cumulative token and duration log-probability difference from the original over the same region, including the right anchor. The broad menu requires initial probability of at least 0.08, a ratio of 0.25 and a score difference of at least −3. The strict menu requires 0.15, 0.40, −0.8 and the exact anchor frame. Ties use the initial ratio, probability and spelling. The fixed-suffix control tries the same starting competitors with the rest of the original word held fixed.
5. Reconstruct each spelling from its unchanged prefix and branch pieces. Match the original lexical spelling and exact word end to the Kotlin WordBuilder receipt, requiring an unambiguous start containing the seed. Changed words replace only their UTF-16 range inside the selection. Missing or ambiguous owners keep the original underline in the denominator.
6. With Python 3, NumPy and SciPy installed, run `continuations.py WORK FIRST_PASS_WORK models/parakeet-ultra/tokens.txt PREVIOUS_CONTEXT_RESULTS_JSON OUTPUT_JSON`. The script checks reference hashes and discards any window with changed tokens, timing or confidence. All 415 windows (71.05 minutes) matched exactly, including all previously archived decisions. There were 709 eligible seeds, 1,553 branch starts, 5,480 extra joiner calls, 3,534 extra predictor calls and 1,330 completed paths. The private file of cached frames was used only to reproduce the released host output. The Android measurements ran directly on audio.
7. For Android, run the released library once, then the experimental build with observation off, on, on and off, in fresh processes. Leave `FOCAL_WORD_REPLAY` unset. Two further enabled runs with `FOCAL_WORD_COST=1` measure observation and branch-following sections with a monotonic clock. Use `--android` when scoring to verify all six receipts against the released library and read the measured costs.

[Per-language counts, Russian controls and Android timings](results.json)
