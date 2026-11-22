# Transcript review & speech research

Focal keeps three layers strictly separate: the **original PCM audio**, the **immutable recognizer tokens**, and the **user's editable text**. Parakeet Ultra INT8 generates every transcript offline. Every additional language, punctuation, or re-decoding model listed below was evaluated in standalone research harnesses outside the APK (raw JSON dumps and scripts archived in Git at tag `v0.4.0`, commit `cd859d6`).

## Current design in the app (0.5.1)

- **One-thumb editing vs. focused review.** Tapping any word opens inline editing with the keyboard ready and a single-row strip right above it holding **Listen** and **Done**. Tapping **Review · N** (or an underlined word) steps through low-confidence spans in a **‹ 1 of N ›** queue with **Listen**, one-tap suggestions (nearby spellings, earlier manual edits in the same note, and Android's offline system spell-checker for misspelled non-words), and undoable **Keep** / **Next** / **Done** (plus **Mark all as reviewed** inside review's overflow menu).
- **Synced playback (`0.5×–2×`).** The player strip offers `0.25×` speed steps via tap popover or press-and-drag with haptic detents, while the spoken-word lens stays synced across both untouched and edited paragraphs.
- **Unified transitions & gestures.** Notes open with a dual-layout title flight from both the Stage card and library rows, pull-down gestures track the finger 1:1 without leaking scroll on reversal, and row Back transitions clear cleanly with no surface flash.
- **Deterministic, word-preserving formatting.** Paragraphs break on pauses and sentence boundaries without synthetic punctuation rules; Parakeet Ultra's own punctuation is preserved intact. ([Architecture](architecture.md) · [Speech quality](speech-quality.md))

## What the experiments established

Word-error counts measure whole-recording word edit distance against a frozen reference. A "helpful" option lowers WER; a "tied" option may still be wrong. Candidate counts measure opportunity to help, not measured human proofreading speed.

| Experiment | Measured outcome | Product decision |
| --- | --- | --- |
| **Local LLMs (`Qwen3 0.6B`, `Qwen3.5 2B`, `Gemma 4 E2B`)** | On 12 RU/EN clips (27 spans), `0.6B` improved 0/27 and `2B` improved 0 / harmed 2 (5.4 s/word and 2.2 GiB RSS on desktop CPU). Audio-grounded `Gemma 4 E2B` saved 1 word error on normal speech, 0 on quiet speech, increased char errors, and hallucinated 30 words on silence. | **Rejected.** Automatic LLM correction stays disabled until a candidate clears the [accuracy & phone budget gates](speech-quality.md#automatic-correction-is-disabled). ([Qwen study](benchmarks/2026-11-19-review/README.md)) |
| **25-language & multilingual punctuation (*Horizon*)** | 284 word edits in 1,824 short holdout words (15.6% WER). *Horizon* (268 MB) tied Focal on 7,044 holdout gaps (301 vs 301 errors; 4 languages helped, 3 hurt, 18 tied). Doubtful-word underlines achieved 89.5% precision / 18.6% recall. | **Keep manual review & raw ASR punctuation.** Window-overlap readings (1 helpful vs 2 harmful) were removed. ([Multilingual report](benchmarks/2026-11-19-multilingual/README.md)) |
| **Compact Russian punctuation model (29.5 MiB INT8)** | Conservative fill cut discrepancies from 158 → 150 on two long monologues and held steady at 20 on a 17:48 classroom dictation (120 ms warm median on emulator). Full replacement worsened the dictation to 37. | **Research candidate only** (too narrow and small a gain to bundle for one language). ([Russian study](benchmarks/2026-11-19-punctuation/README.md)) |
| **Wider-audio re-decoding on tap** | Across 343 holdout spans, strict replay added 34 options (15 helpful, 14 tied, 5 harmful), returned nothing on 275 of 311 eligible spans, and took ~7 s median on emulator. | **Rejected.** Too slow and sparse for interactive review. ([Wider-audio study](benchmarks/2026-11-20-context-review/README.md)) |
| **First-pass single-token alternatives** | Strict filtering added 19 options across 343 underlines (4 helpful, 12 tied, 3 harmful); even an oracle over the full pool helped only 16 / 343. | **Rejected.** Single-piece forks rarely complete a valid word. ([First-pass study](benchmarks/2026-11-21-first-pass/README.md)) |
| **Full word continuations (TDT decoder branching)** | Following top-3 branches from uncertain pieces (+73 ms median/window, 0 MB weights) found a helpful spelling at 52 / 343 spans (42 in top-2), but strict 1-best selection gave 23 helpful / 97 tied / 25 harmful, and a small ranker gave 3 helpful / 9 tied / 3 harmful. | **Experimental.** Not safe for auto-selection; strongest candidate if revisited strictly as human-chosen chips in Review. ([Word-continuations study](benchmarks/2026-11-21-word-continuations/README.md)) |

> All timings above were measured on desktop or x86-64 Android emulators. Real ARM phone latency, memory pressure, thermals, and battery remain unmeasured.

## Next research priorities

1. **Time real human proofreading.** Counterbalance inline review vs. plain editing on unfamiliar spontaneous passages (RU/EN plus weaker languages) to measure actual seconds saved and error rate ([reader protocol](benchmarks/2026-11-19-multilingual/method.md#reader-timing)).
2. **Improve recall on quiet speech & calibration.** Quiet audio is the largest single error source (91 vs 50 errors on FLEURS DEV); test gentle decode-only gain normalization on quiet windows and revisit underline calibration beyond 18.6% recall.
3. **Human-picked acoustic chips.** Revisit top-2 TDT word continuations strictly as optional reviewer chips (filtered for valid spellings) rather than automated 1-best picks.
4. **Profile on physical midrange ARM phones (≤ 6 GB RAM).** Measure real cold install, capture + decode RSS, thermal throttling, battery drain over 1 hour screen-off, and frame pacing ([phone validation checklist](testing.md#phone-validation)).
