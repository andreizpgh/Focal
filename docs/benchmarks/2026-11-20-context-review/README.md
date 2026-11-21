# A second listen to an underlined phrase

Re-decoding a wider audio window sometimes produces a better word. On 109 holdout recordings, a conservative rule offered 34 spellings that the current review menu did not already have. Fifteen reduced word errors, 14 tied and five increased errors. That adds a useful choice at 15 of 343 taps (4.4%). The 17 helpful second-pass suggestions including duplicates came from eight recordings.

The wider pass remains a research option. Of the 311 holdout taps with enough fresh audio, 275 would pay for recognition and then display no suggestion under the stricter rule. This cost and the uneven results across the three priority languages make it a poor default for the reading screen.

## Review outcomes

Each suggestion replaces one selected phrase and is compared with that recording's reference. Helpful means fewer word edits are needed to reach the reference. A partial repair qualifies. A neutral suggestion can still be wrong. There was no reader timing study.

| Holdout policy | Taps with an option | Helpful suggestions | Neutral | Harmful |
| --- | ---: | ---: | ---: | ---: |
| Existing menu, no fresh decode | 23 | 7 | 11 | 5 |
| Wider audio, time and confidence | 84 | 37 | 39 | 8 |
| Wider audio, higher confidence and a matching neighbor | 36 | 17 | 14 | 5 |
| Same stricter rule, additional spellings only | 34 | 15 | 14 | 5 |
| Wider audio, no confidence filter (diagnostic) | 140 | 51 | 70 | 19 |

The stricter rule discarded 20 helpful and three harmful options relative to the timing and confidence rule. On the separate development split it generated 18 suggestions at 273 taps. Seven helped, eight tied and three harmed. Both splits keep all eligible recordings, including those with no underlines.

| Priority holdout language | Flagged phrases | Taps with wider audio | Helpful / neutral / harmful, timing and confidence | Helpful / neutral / harmful, stricter rule |
| --- | ---: | ---: | ---: | ---: |
| Latvian | 35 | 35 | 1 / 7 / 1 | 0 / 2 / 1 |
| Greek | 56 | 50 | 0 / 1 / 0 | 0 / 1 / 0 |
| Maltese | 65 | 62 | 10 / 8 / 2 | 3 / 3 / 1 |

The two splits contain 232 recordings in 25 languages and 616 natural flagged phrases. Of those, 549 taps could be replayed with more audio. They required 358 distinct decodes (128 minutes of audio). Of the 343 holdout taps, 30 had insufficient additional audio and two had ambiguous window ownership. Natural holdout taps exercised 21 languages. Separate unflagged controls cover the other four.

None of the four forced selections produced a suggestion. On the host, all four unchanged-window controls reproduced the archived tokens, timestamps and log probabilities exactly.

## Russian dictation

The 17 min 48 s classroom recording had seven underlines. Only one could be scored automatically from unique three-word flanks in the printed answer, and it had no suggestion. A separate textual check mapped six selections to the printed answer using the repeated phrases on either side. The strict policy offered two replacements on these six. Both exactly recovered the intended wording, including a fused two-word phrase and the name of the dance. The simpler timing and confidence policy recovered the dance name at a third occurrence. One selection could not be located confidently. These are checks against the printed passage, without independent listener scoring.

The first fluent reading of the same passage had zero underlines. On two other Russian monologues totaling nearly 14 minutes, there were five underlines. The stricter rule offered one replacement and the word error count stayed the same.

## Resource cost

| Standalone recognizer | Model load | First crop | Warm median | Warm p95 | Peak process RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ryzen 5 5500U host, 358 crops | 3.13 s | 1.64 s | 2.92 s | 4.35 s | 1,451 MiB |
| x86-64 Android 35 emulator, six crops repeated three times | 3.93 s | 7.79 s | 7.11 s | 10.53 s | 1,026 MiB |

Both used two CPU threads and the app's lower-memory setting. The sampled Android crops ranged from 9.48 to 30.00 seconds, and the guest reported 2.42 GiB RAM. Model load is paid separately when the engine has been released. RSS includes model load and inference in a single native process. A physical midrange phone's latency, memory pressure, battery use and thermals still require a device measurement.

All six Android crops produced the same tokens on each of their three runs. All six differed from their host token sequence. The full crop transcripts differed by 17 normalized word edits in total. On the seven associated selections both builds offered one neutral strict suggestion, while the timing-only policy offered one fewer option on Android. The multilingual quality tables use the host runs.

[Selection, scoring and reproduction](method.md) · [Results by language, Russian checks and Android timings](results.json) · [Per-recording counts](cases.jsonl)
