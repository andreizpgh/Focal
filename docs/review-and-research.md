# Transcript review and speech research

Focal stores original PCM audio, raw recognizer tokens, and edited text separately. Parakeet Ultra INT8 produces every transcript offline. Every other language, punctuation, or re-decoding model listed below ran in standalone evaluation scripts outside the APK (raw JSON outputs and harnesses are archived in Git at tag `v0.4.0`, commit `cd859d6`).

## Current app behavior (1.0.0)

- **Editing and review.** Tapping any word opens inline editing with **Listen** and **Done** right above the keyboard. Tapping **Review · N** (or an underlined word) steps through low-confidence spans with **Listen**, one-tap suggestions (nearby spellings, earlier edits in the same note, and Android's offline system spell-checker for misspelled non-words), and undoable **Keep** / **Next** / **Done**.
- **Playback.** The player supports 0.5× to 2× speed in 0.25× steps (tap menu or press-and-drag), word-boundary scrubber snapping (`SeekSnap`) with clickless fade-in on seek, and spoken-word highlighting across both untouched and edited paragraphs.
- **Formatting.** Paragraphs split on pauses and sentence endings using Parakeet Ultra's own punctuation, with no synthetic comma or period insertion. ([Architecture](architecture.md) · [Speech quality](speech-quality.md))

## Experiment summary

Word-error counts measure whole-recording word edit distance against a frozen reference. A "helpful" candidate reduces edit distance; a "tied" candidate can still be wrong. These numbers measure opportunity to help, not human proofreading time.

| Experiment | Result | Decision |
| --- | --- | --- |
| Local LLMs (`Qwen3 0.6B`, `Qwen3.5 2B`, `Gemma 4 E2B`) | On 12 RU/EN clips (27 spans), `0.6B` improved 0/27 and `2B` improved 0 / harmed 2 (5.4 s/word and 2.2 GiB RSS on desktop CPU). Audio-grounded `Gemma 4 E2B` saved 1 word error on normal speech, 0 on quiet speech, raised character errors, and invented 30 words on silence. | Not shipped. Automatic LLM correction stays off until a model clears the [accuracy and phone budget gates](speech-quality.md#automatic-correction-is-disabled). ([Qwen study](benchmarks/2026-11-19-review/README.md)) |
| 25-language and multilingual punctuation (*Horizon*) | 284 word edits in 1,824 short holdout words (15.6% WER). *Horizon* (268 MB) tied Focal across 7,044 holdout gaps (301 vs 301 mark errors; 4 languages helped, 3 hurt, 18 tied). Underlines reached 89.5% precision and 18.6% recall. | Keep raw ASR punctuation and manual review. Window-overlap readings (1 helpful vs 2 harmful) were removed. ([Multilingual report](benchmarks/2026-11-19-multilingual/README.md)) |
| Russian punctuation model (29.5 MiB INT8) | Conservative fill reduced discrepancies from 158 to 150 on two long monologues and left a 17:48 classroom dictation at 20 (120 ms warm median on emulator). Replacing all marks raised dictation discrepancies to 37. | Not shipped. Gain is too small and single-language. ([Russian study](benchmarks/2026-11-19-punctuation/README.md)) |
| Wider-audio re-decoding on tap | On 343 holdout spans, strict replay added 34 options (15 helpful, 14 tied, 5 harmful), returned nothing on 275 of 311 eligible spans, and took ~7 s median on emulator. | Not shipped. Too slow and sparse for interactive review. ([Wider-audio study](benchmarks/2026-11-20-context-review/README.md)) |
| First-pass single-token alternatives | Strict filtering added 19 options across 343 underlines (4 helpful, 12 tied, 3 harmful); even an oracle over the full pool helped on 16 / 343. | Not shipped. Single subword forks rarely form complete words. ([First-pass study](benchmarks/2026-11-21-first-pass/README.md)) |
| Full word continuations (TDT decoder branching) | Following top-3 branches from uncertain pieces (+73 ms median/window, 0 MB extra weights) put a helpful spelling in the pool at 52 / 343 spans (42 in top-2), but strict 1-best gave 23 helpful / 97 tied / 25 harmful, and a small ranker gave 3 helpful / 9 tied / 3 harmful. | Experimental. Not safe for auto-selection; worth revisiting only as human-picked chips in Review. ([Word continuations](benchmarks/2026-11-21-word-continuations/README.md)) |

All experiment timings above come from desktop CPUs or x86-64 Android emulators. Initial ARM phone launch, memory, frame-timing, and short-run thermal measurements are in [testing](testing.md#100); long-duration battery, thermal throttling, and spontaneous phone WER remain open.

## Open questions for future work

1. **Human proofreading time.** Compare inline review against plain editing on unfamiliar spontaneous speech to measure actual seconds saved and error rates ([reader protocol](benchmarks/2026-11-19-multilingual/method.md#reader-timing)).
2. **Quiet speech and underline recall.** Quiet audio is the largest single error source (91 vs 50 errors on FLEURS DEV). Evaluate decode-only gain normalization on quiet windows and revisit underline calibration above 18.6% recall.
3. **Human-picked acoustic suggestions.** Test top-2 TDT word continuations strictly as optional chips filtered for valid spellings, rather than automated picks.
4. **Midrange ARM phone profiling (≤ 6 GB RAM).** Measure cold install, capture + decode RSS, thermal throttling, battery drain over an hour with the screen off, and frame pacing ([phone checklist](testing.md#phone-validation)).
