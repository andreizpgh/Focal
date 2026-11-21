# Recognition and review across 25 languages

Focal was evaluated on 182 short recordings, 50 continuous recordings (177.1 minutes) and seven controls. All 25 speech languages have independent short test audio. The primary long holdout includes 17 languages with a usable source, either uploader captions passing the agreement check or publisher-aligned Maltese text. 19 supplemental recordings recheck the gaps in a different source. Keep manual review as the product default. The multilingual punctuation model had a net benefit in 4 languages, a net loss in 3 and a tie in 18 on this holdout.

## Held-out results

On 84 short recordings there were **284 word edits in 1824 reference words (15.6% WER)**. On the combined short and eligible long holdouts, Focal and the punctuation model disagreed with the reference at **301 and 301** of 7044 adjudicable gaps. Filling high-confidence gaps fixed 5 exact marks, introduced 5 errors at previously correct gaps, and changed 4 gaps that remained wrong. These counts estimate the number of sign decisions a reader might revisit. The models preserve the recognized words.

| Material | Notes | Scored gaps | Focal mark errors | Timing at 0.90 | Timing, dev cutoff | Horizon at 0.90 | Horizon fixed / introduced |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Short development | 98 | 1340 | 28 | 28 | 28 | 27 | 2 / 1 |
| Eligible long development | 14 | 4556 | 239 | 239 | 239 | 239 | 4 / 4 |
| Short holdout | 84 | 1279 | 29 | 29 | 29 | 30 | 0 / 1 |
| Eligible long holdout | 17 | 5765 | 272 | 272 | 272 | 271 | 5 / 4 |
| Language switches | 5 | 363 | 12 | 12 | 12 | 12 | 0 / 0 |
| Maltese floor speech | 2 | 151 | 9 | 9 | 9 | 9 | 0 / 0 |
| Supplemental recordings | 19 | 7657 | 371 | 371 | 371 | 364 | 9 / 2 |

The timing model has seven double-precision weights (56 bytes). Its development-selected cutoff was 1.00 (abstain). At this cutoff the holdout had 301 exact mark errors. Focal missed 41 reference sentence ends and added 59. Horizon missed 41 and added 66.

## Language breakdown

Each row shows an independent short test, then whether its original long holdout had a usable reference. Mark and underline counts cover that language’s short test and its eligible long holdout. A tie can reflect very little evidence. Supplemental cases and the five stitched switches have separate totals.

| Language | Short clips | Short word errors / words | Long holdout | Focal → Horizon mark errors | Fixed / introduced | Correctly flagged / flagged |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| Bulgarian | 4 | 12/107 (11.2%) | eligible | 9 → 9 | 0 / 0 | 13 / 14 |
| Czech | 2 | 9/44 (20.5%) | eligible | 9 → 9 | 0 / 0 | 1 / 1 |
| Danish | 2 | 12/45 (26.7%) | eligible | 32 → 32 | 0 / 0 | 9 / 10 |
| German | 2 | 0/39 (0.0%) | eligible | 16 → 15 | 1 / 0 | 1 / 2 |
| Greek | 4 | 40/94 (42.6%) | eligible | 15 → 15 | 0 / 0 | 64 / 66 |
| English | 4 | 5/88 (5.7%) | low agreement | 2 → 2 | 0 / 0 | 0 / 0 |
| Spanish | 4 | 6/120 (5.0%) | eligible | 9 → 9 | 0 / 0 | 0 / 2 |
| Estonian | 2 | 5/43 (11.6%) | eligible | 6 → 7 | 0 / 1 | 3 / 3 |
| Finnish | 4 | 7/54 (13.0%) | eligible | 11 → 11 | 0 / 0 | 10 / 13 |
| French | 4 | 11/135 (8.1%) | eligible | 22 → 22 | 0 / 0 | 1 / 2 |
| Croatian | 4 | 9/90 (10.0%) | low agreement | 1 → 1 | 0 / 0 | 4 / 4 |
| Hungarian | 2 | 8/42 (19.0%) | low agreement | 3 → 3 | 0 / 0 | 2 / 2 |
| Italian | 4 | 3/108 (2.8%) | eligible | 24 → 23 | 1 / 0 | 1 / 2 |
| Lithuanian | 4 | 15/66 (22.7%) | low agreement | 1 → 2 | 0 / 1 | 5 / 7 |
| Latvian | 4 | 45/104 (43.3%) | low agreement | 1 → 1 | 0 / 0 | 9 / 10 |
| Maltese | 4 | 19/64 (29.7%) | eligible | 17 → 17 | 0 / 0 | 96 / 101 |
| Dutch | 4 | 5/74 (6.8%) | eligible | 35 → 38 | 0 / 3 | 5 / 6 |
| Polish | 4 | 12/62 (19.4%) | eligible | 27 → 25 | 2 / 0 | 1 / 1 |
| Portuguese | 2 | 1/60 (1.7%) | eligible | 22 → 22 | 0 / 0 | 4 / 5 |
| Romanian | 2 | 7/40 (17.5%) | low agreement | 0 → 0 | 0 / 0 | 1 / 1 |
| Russian | 4 | 2/78 (2.6%) | eligible | 14 → 14 | 0 / 0 | 1 / 1 |
| Slovak | 4 | 11/94 (11.7%) | low agreement | 0 → 0 | 0 / 0 | 0 / 0 |
| Slovenian | 4 | 14/61 (23.0%) | low agreement | 3 → 3 | 0 / 0 | 0 / 1 |
| Swedish | 4 | 25/88 (28.4%) | eligible | 7 → 7 | 0 / 0 | 14 / 20 |
| Ukrainian | 2 | 1/24 (4.2%) | eligible | 15 → 14 | 1 / 0 | 11 / 12 |

The original long holdout fell below the caption agreement criterion in English, Croatian, Hungarian, Lithuanian, Latvian, Romanian, Slovak and Slovenian. Across development and holdout, 19 of 48 YODAS2 items fell below 70%. Of 19 supplemental recordings, 16 run at least three minutes and meet the same agreement check. Across both sets, 23 languages have a long test recording with at least 70% lexical agreement. Latvian and Maltese fell below that threshold. In the publisher-aligned Maltese holdout, the recognizer made 315 word edits in 670 words. All original case receipts retain their observed agreement and scores. Speaker identity and source quality limit comparisons between languages.

The largest short-test WERs were 43.3% in Latvian (45 errors in 104 words) and 42.6% in Greek (40 in 94). Ignoring decomposable diacritics in the scoring changes those error counts to 45 and 40. This diagnostic keeps the accent-sensitive WER as the primary measure.

## What the reviewer sees

- Of 286 underlined words, 256 differed from the reference (89.5% precision). The underlines covered 256 of 1378 visible word mismatches (18.6%). Restricting to already flagged words with confidence below 0.35 gave 64 mismatches in 69 flags. Omitted words have no visible tap target.
- The actual nearby-spelling menu displayed a choice for 8 of 227 flagged spans. At least one suggestion reduced word errors at 2 spans. Of the individual choices, 2 helped, 6 tied and 0 worsened WER. Each is scored in isolation. A separate replay of the overlapping-audio rule offered alternatives on 4 spans. Of those choices, 1 helped, 1 tied and 2 made the transcript worse. The app offers each option for explicit acceptance.
- At scored holdout sites Focal split paragraphs inside reference sentences 16 times out of 101 splits. In the 21 readings with publisher paragraphing, 22 breaks matched, 122 were extra and 65 were missed. Paragraphs can be split or joined directly from a word in reading mode.
- Replaying the previous and regional formatters produced identical lexical word sequences on all 239 primary/control notes. All 239 retained their previous display text. Targeted fixtures exercise the regional signs. The regional rules recognize a semicolon after a Greek word as a question ending, allow editing Spanish inverted marks and retain combining accents during selection. The bundled Inter font contained every one of the 210 distinct NFC letters and combining marks observed in the short drafts and references.

## Android resource cost and Russian dictation

The pinned INT8 graph is 268,414,381 bytes and the tokenizer JSON is 34,363,286 bytes. The standalone CPU probe used the packaged ONNX Runtime 1.28.2 on a two-core x86-64 Android emulator. Per-recording times replay pre-tokenized windows in a fresh process, then 12 warm passes. They exclude tokenization, recognition and UI. The ARM64 executable was cross-compiled. Actual midrange ARM latency, combined app memory, heat and battery still need a physical device.

| Russian recording | Words | Scored gaps | Focal → Horizon errors | Model load | Warm median / p95 | Peak process RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Listening monologue | 1006 | 943 | 104 → 89 | 2.49 s | 2.37 / 3.94 s | 387.6 MiB |
| Complete classroom dictation | 638 | 307 | 20 → 19 | 1.13 s | 1.95 / 2.23 s | 386.5 MiB |

The Russian controls use the same audio, word alignment and printed dictation key as the [Russian-only model comparison](../2026-11-19-punctuation/README.md). The 29.5 MiB Russian package scored 101 and 20 on these same two recordings. A human proofreading session has not yet been timed.

On this emulator the seven-coefficient classifier processed 473 already prepared gaps of a 608-word Russian note in 0.012 ms median (0.017 ms p95). The first pass took 0.095 ms. ART process RSS was 117.9 MiB at start and peaked at 126.7 MiB including the input fixture, runtime and warm-up. Feature construction is outside the timer. Each warm sample averages 100 complete passes.

[Method, sources and reader protocol](method.md) · [Measurements and model hashes](results.json) · [Source IDs and per-recording scores](cases.jsonl)
