# Word alternatives from the first recognition pass

Parakeet Ultra can supply competing token scores as it transcribes a note. Capturing them added little time on the two-core Android emulator. The more substantial limitation was the content of the suggestions. A strict rule added a helpful choice at four of 343 held-out underlined phrases (1.2%), across three recordings. Of 19 spellings absent from the current menu, four reduced word errors, 12 tied and three increased errors. First-pass suggestions remain an experiment.

## What a reviewer would see

The primary comparison covers 109 held-out recordings in all 25 supported languages. Of 343 underlines, 341 had unambiguous original window ownership. English had no natural holdout underline and was exercised by a separate unchanged-window control. The 123 development recordings were scored separately.

| Holdout menu | Taps with options | Taps with a helpful option | Helpful options | Neutral | Harmful |
| --- | ---: | ---: | ---: | ---: | ---: |
| Current menu | 23 | 7 | 7 | 11 | 5 |
| First pass, up to two competing spellings | 231 | 15 | 15 | 272 | 56 |
| First pass, one strict spelling | 23 | 8 | 8 | 12 | 3 |
| Strict spelling absent from the current menu | 19 | 4 | 4 | 12 | 3 |

The first-listed broad suggestion helped at 13 taps and harmed at 45. Even a perfect choice among **all** word candidates passing the broad acoustic and boundary rules, before the display limit of two, would help at only 16 of 343 taps. This ceiling applies to this single-piece substitution method. On development, the strict rule added 11 new spellings. Zero helped, eight tied and three harmed.

| Holdout language | Flagged phrases | New strict options | Helpful | Neutral | Harmful |
| --- | ---: | ---: | ---: | ---: | ---: |
| Greek | 56 | 0 | 0 | 0 | 0 |
| Latvian | 35 | 0 | 0 | 0 | 0 |
| Maltese | 65 | 4 | 2 | 2 | 0 |
| Russian | 1 | 0 | 0 | 0 | 0 |

The Latvian strict rule found one helpful spelling already available in the app. The other two new helpful holdout spellings came from Bulgarian and Slovenian. On this same holdout, re-decoding wider audio had added 15 helpful options, at several seconds per eligible tap. [Wider-audio comparison](../2026-11-20-context-review/README.md)

## Continuous Russian speech

On a separate 17 min 48 s classroom dictation the strict rule recovered the dance name from the wrong initial consonant. It offered a second spelling at a repeated fragment whose correspondence with the printed key was ambiguous. Six of the seven flags could be located in the printed text. The broad rule added a harmful option at another scored location. The two separate monologues had five flags with no helpful options, and the first fluent reading of the dictation had no flags. The classroom and first-reading results overlap and are kept separate. The checks compare spellings to a published reference. Human proofreading time was not measured.

## Preservation of the original result

The quality run used the original released host library. Across **415 original decoder windows** (71.05 minutes) it reproduced every archived token, timestamp, duration and confidence value. Of 19,947 emitted pieces, 882 met the uncertainty threshold for retaining competitors. The sample intentionally selects windows containing underlines, so this is not a storage rate for arbitrary recordings.

The separately compiled host timing build reproduced all receipts on 85 of 415 windows; 236 windows changed at least one token. Its output was excluded from the quality totals. On the 85 matching windows, the two collection methods agreed on the competitor IDs at all 181 uncertain emissions. The maximum log-probability discrepancy was below 0.000001.

## Time and memory

The comparison covers eight original windows of 3.73–20.74 seconds, with two timed observations per window in each condition. Both builds used the same model and returned identical words, timings and confidence values with collection enabled or disabled.

| Standalone recognizer | Median paired change per window | Combined time change | Peak RSS off → on |
| --- | ---: | ---: | ---: |
| Android 35 x86-64, two virtual cores | +5.25 ms | +0.63% | 930.4 → 930.9 MiB |
| AMD Ryzen 5 5500U, two CPU threads | −25.75 ms | −3.69% | 1,255.7 → 1,259.4 MiB |

The host difference was within the variation between short timing runs. The Android trace had 13 low-confidence decisions in 470 emitted pieces. Both collection rounds produced identical alternate IDs and scores. All eight Android windows also matched the packaged library's original token, timing and confidence receipts. The timings cover collection during speech recognition. They exclude menu generation, database writes and time spent reviewing. The physical phone's energy and thermal behavior remain unmeasured.

Raw per-recording logs, JSON measurements and prototype scripts are archived in Git at tag `v0.4.0` (`cd859d6`).
