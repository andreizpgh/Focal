# Recognition and review across 25 languages

Parakeet Ultra INT8 was tested across all 25 supported European languages using 182 short recordings, 50 long continuous recordings (177.1 minutes total), and 7 controls.

Key findings:
- **Accuracy varies widely by language.** Short-clip WER ranged from 1.7–8.1% on Portuguese, Russian, Italian, Spanish, English, and French up to ~43% on Greek and Latvian.
- **Doubtful-word underlines are precise but sparse.** 89.5% of underlined words were real errors (256 of 286), catching 18.6% of all visible word mismatches.
- **Extra punctuation models did not beat Parakeet Ultra.** A 268 MB multilingual punctuation model (*Horizon*) tied Focal's raw ASR punctuation on the holdout (301 vs 301 mark errors across 7,044 gaps: helped in 4 languages, hurt in 3, tied in 18), and a small pause-timing classifier abstained. Focal keeps Parakeet Ultra's own punctuation and relies on manual review.

## Models compared

| System | Description | Shipped in APK |
| --- | --- | --- |
| **Focal** | Parakeet Ultra INT8 with deterministic word-preserving formatting (no synthetic punctuation) | Yes |
| **Timing** | 56-byte (7-weight) pause-duration classifier tested for missing sentence marks | No (abstained on holdout) |
| **Horizon** | 268 MB INT8 multilingual punctuation model (+ 34 MB tokenizer) tested at `0.90` confidence fill | No (net tie, high RAM/size cost) |

## Held-out summary

Across 84 short holdout clips, Focal made 284 word edits in 1,824 reference words (15.6% WER). Across the combined short and eligible long holdouts (7,044 scoreable gaps), Focal and *Horizon* tied at 301 mark errors each: filling high-confidence gaps fixed 5 exact marks, introduced 5 errors at previously correct gaps, and changed 4 gaps that remained wrong.

| Split | Recordings | Scored gaps | Focal mark errors | Timing (0.90) | Timing (dev cutoff) | Horizon (0.90) | Horizon fixed / introduced |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Short development | 98 | 1,340 | 28 | 28 | 28 | 27 | 2 / 1 |
| Eligible long development | 14 | 4,556 | 239 | 239 | 239 | 239 | 4 / 4 |
| Short holdout | 84 | 1,279 | 29 | 29 | 29 | 30 | 0 / 1 |
| Eligible long holdout | 17 | 5,765 | 272 | 272 | 272 | 271 | 5 / 4 |
| Language switches | 5 | 363 | 12 | 12 | 12 | 12 | 0 / 0 |
| Maltese floor speech | 2 | 151 | 9 | 9 | 9 | 9 | 0 / 0 |
| Supplemental recordings | 19 | 7,657 | 371 | 371 | 371 | 364 | 9 / 2 |

At its development-selected cutoff (`1.00`, abstain), *Timing* left all 301 holdout errors unchanged. On sentence boundaries, Focal missed 41 reference ends and added 59; *Horizon* missed 41 and added 66.

## Per-language breakdown

Each row shows the independent short holdout test, whether that language's primary long recording met the reference-agreement bar (`≥ 70%` lexical agreement with uploader captions or publisher text), punctuation mark errors across that language's holdout, and underline precision.

| Language | Short clips | Short WER (errors / words) | Long holdout | Focal → Horizon mark errors | Fixed / introduced | True errors / underlined |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| Bulgarian | 4 | 11.2% (12/107) | eligible | 9 → 9 | 0 / 0 | 13 / 14 |
| Czech | 2 | 20.5% (9/44) | eligible | 9 → 9 | 0 / 0 | 1 / 1 |
| Danish | 2 | 26.7% (12/45) | eligible | 32 → 32 | 0 / 0 | 9 / 10 |
| German | 2 | 0.0% (0/39) | eligible | 16 → 15 | 1 / 0 | 1 / 2 |
| Greek | 4 | 42.6% (40/94) | eligible | 15 → 15 | 0 / 0 | 64 / 66 |
| English | 4 | 5.7% (5/88) | low agreement | 2 → 2 | 0 / 0 | 0 / 0 |
| Spanish | 4 | 5.0% (6/120) | eligible | 9 → 9 | 0 / 0 | 0 / 2 |
| Estonian | 2 | 11.6% (5/43) | eligible | 6 → 7 | 0 / 1 | 3 / 3 |
| Finnish | 4 | 13.0% (7/54) | eligible | 11 → 11 | 0 / 0 | 10 / 13 |
| French | 4 | 8.1% (11/135) | eligible | 22 → 22 | 0 / 0 | 1 / 2 |
| Croatian | 4 | 10.0% (9/90) | low agreement | 1 → 1 | 0 / 0 | 4 / 4 |
| Hungarian | 2 | 19.0% (8/42) | low agreement | 3 → 3 | 0 / 0 | 2 / 2 |
| Italian | 4 | 2.8% (3/108) | eligible | 24 → 23 | 1 / 0 | 1 / 2 |
| Lithuanian | 4 | 22.7% (15/66) | low agreement | 1 → 2 | 0 / 1 | 5 / 7 |
| Latvian | 4 | 43.3% (45/104) | low agreement | 1 → 1 | 0 / 0 | 9 / 10 |
| Maltese | 4 | 29.7% (19/64) | eligible | 17 → 17 | 0 / 0 | 96 / 101 |
| Dutch | 4 | 6.8% (5/74) | eligible | 35 → 38 | 0 / 3 | 5 / 6 |
| Polish | 4 | 19.4% (12/62) | eligible | 27 → 25 | 2 / 0 | 1 / 1 |
| Portuguese | 2 | 1.7% (1/60) | eligible | 22 → 22 | 0 / 0 | 4 / 5 |
| Romanian | 2 | 17.5% (7/40) | low agreement | 0 → 0 | 0 / 0 | 1 / 1 |
| Russian | 4 | 2.6% (2/78) | eligible | 14 → 14 | 0 / 0 | 1 / 1 |
| Slovak | 4 | 11.7% (11/94) | low agreement | 0 → 0 | 0 / 0 | 0 / 0 |
| Slovenian | 4 | 23.0% (14/61) | low agreement | 3 → 3 | 0 / 0 | 0 / 1 |
| Swedish | 4 | 28.4% (25/88) | eligible | 7 → 7 | 0 / 0 | 14 / 20 |
| Ukrainian | 2 | 4.2% (1/24) | eligible | 15 → 14 | 1 / 0 | 11 / 12 |

Primary long recordings for 8 languages fell below the 70% caption-agreement filter. Adding 19 supplemental recordings (16 of them ≥ 3 minutes passing the check) brought long-recording coverage to 23 of 25 languages; Latvian and Maltese stayed below 70% (on publisher-aligned Maltese text, the recognizer made 315 word edits in 670 words). Short-clip WERs for Latvian (43.3%) and Greek (42.6%) did not change when stripping decomposable diacritics. Because clip counts per language are small (2–4 clips), speaker and audio quality strongly affect individual rows.

## Review behavior

- **Underlines:** 89.5% precision (256 of 286 underlines differed from the reference), covering 18.6% of visible word mismatches (256 of 1,378). Tightening the threshold below `0.35` gave 64 mismatches in 69 flags. Omitted words have no displayed token to underline.
- **Spelling hints:** Nearby-spelling suggestions appeared on 8 of 227 flagged spans (2 helpful, 6 tied, 0 harmful). Window-overlap alternate readings fired on 4 spans (1 helpful, 1 tied, 2 harmful) and were removed in 0.5.0.
- **Paragraph breaks:** 16 of 101 automatic splits fell inside a reference sentence. Against publisher paragraphing (21 readings), 22 breaks matched, 122 were extra, and 65 were missed. Readers can split or join paragraphs in place.
- **Scripts and typography:** The bundled Inter subset covered all 210 distinct NFC letters and combining marks seen in the evaluation. Regional rules handle Greek semicolon question marks, Spanish inverted marks, and combining accents without altering recognized words.

## Emulator resource probe

Measured on a two-core x86-64 Android emulator using ONNX Runtime 1.28.2 on pre-tokenized windows (excluding ASR and UI; physical ARM phone latency, memory, heat, and battery remain unmeasured).

| Russian control recording | Words | Scored gaps | Focal → Horizon errors | Model load | Warm median / p95 | Peak process RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Listening monologue | 1,006 | 943 | 104 → 89 | 2.49 s | 2.37 / 3.94 s | 387.6 MiB |
| Complete classroom dictation | 638 | 307 | 20 → 19 | 1.13 s | 1.95 / 2.23 s | 386.5 MiB |

On the same two recordings, the 29.5 MiB [Russian-only punctuation model](../2026-11-19-punctuation/README.md) scored 101 and 20. The 7-weight timing classifier ran in 0.012 ms median (126.7 MiB ART RSS).

[Method, sources, and reader protocol](method.md) · Raw JSON results and per-recording dumps are archived in Git at tag `v0.4.0` (`cd859d6`).
