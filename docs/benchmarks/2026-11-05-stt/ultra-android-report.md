# Ultra saved-audio Android quality · 2026-11-05

Ultra was evaluated on an Android emulator through the actual saved-audio
processor, using its verified staged model. This was a research build, not the
bundled application APK or a physical phone. The model chooses language
automatically; the Base comparator uses the selected language. The independent
held-out audit verified all raw receipts, inputs, scoring, model staging, build
identities and resource limits. No acoustic adjudication was performed. This is the quality gate for an optional
copy-only alternative on saved recordings up to three minutes; bundled-app and
original-preservation QA are separate checks.

## Held-out read speech

The frozen comparison used the same 42 previously unused FLEURS PCM clips, six
per language. Base Q5_1 ran in fresh host processes on full PCM; Ultra ran on
the Android emulator, one unpadded saved-audio window per clip. Word errors fell
from **184 to 51 / 961**, and lexical character errors from **369 to 94 / 5,010**.
The word-error reduction was 72.3%; both predeclared aggregate quality criteria
passed. These are quality counts, not a host-to-Android speed comparison.

| Language | Reference words | Word errors, Base → Ultra | Reference characters | Character errors, Base → Ultra |
| --- | ---: | ---: | ---: | ---: |
| English | 123 | 12 → 7 | 609 | 38 → 22 |
| Russian | 123 | 31 → 12 | 754 | 51 → 14 |
| German | 136 | 32 → 6 | 809 | 51 → 9 |
| French | 178 | 56 → 14 | 785 | 148 → 32 |
| Spanish | 128 | 10 → 5 | 642 | 18 → 7 |
| Italian | 116 | 26 → 2 | 628 | 31 → 4 |
| Portuguese | 157 | 17 → 5 | 783 | 32 → 6 |

Word errors improved on 31 clips and tied on 11; character errors improved on
32, tied on nine and worsened on one. Each language aggregate improved for
both measures, but the small read-speech sample does not establish recognition
quality for every utterance or natural language switching. The overlap audit
used available WAV and reference identities for the earlier cloud corpus, whose
raw PCM hashes were unavailable. [Aggregate and provenance](ultra-android-heldout-42.json).

## Framing and long boundaries on consumed DEV audio

On seven normal and seven digitally attenuated clips, the 30-second Android
processor yielded 12 and 15 word errors out of 210 words in each group, versus
25 and 31 for Base on the same PCM. Lexical character errors were 29 and 32 out
of 1,050, versus 47 and 63. Four noise/constant controls were blank. Quiet
Portuguese remained worse than Base on its single attenuated fixture. These
were previously consumed DEV cases, and the Base comparator was a host run.

The first 30-second long-clip check failed: on the constructed quiet Spanish
stream, two hard-cut joins repeated phrases and another added a spurious tail.
A targeted follow-up on the same Russian and Spanish streams used a wider
low-energy search while retaining bounded windows. It verified all 12 decoder
windows, replaced the Spanish hard cuts with low-energy cuts, and no longer exhibited
the flagged repeats or tail on text and boundary review. The earlier failure
remains part of the evidence. The follow-up did not exercise hard fallback or
prove boundary correctness on arbitrary recordings. [DEV aggregate](ultra-android-dev-framing.json).

The separate
[bundled-app verification](https://github.com/andreizpgh/Focal/blob/stt-alpha-9/docs/quality-report.md#alpha-8-bundled-ultra--final-emulator-verification)
records full emulator QA and minified runtime smoke for Alpha 8. Physical-phone
resources and behavior, quiet speech and natural language switching remain
open. Automatic LLM correction stays disabled.
