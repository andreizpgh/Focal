# Ultra as the primary transcription engine

This is an integration quality check on previously consumed DEV audio, not a
new held-out model comparison or a phone performance measurement. The frozen
[aggregate](ultra-primary-dev-21.json) binds the exact app/test APKs, inputs,
results archive and scorer. The earlier [42-clip held-out model comparison](ultra-android-report.md)
remains a separate experiment.

## Product path

New recordings in the bundled Ultra build are transcribed automatically into
the main note, with automatic language detection. There is no separate action
or three-minute recording limit. Original audio stays on disk while a single
worker processes bounded windows. Existing Whisper notes keep their engine and
manual edits. Automatic LLM correction remains disabled.

The first live window waits for 35 seconds of durably saved audio to avoid
isolating a tiny final window when recording stops just after publication.
Pause and Finish make the remaining audio
eligible immediately; native processing still takes time. This is block-based
transcription, not instantaneous streaming. Each native window is at most
30 seconds. The fixed `ultra-v1` model/framing identity and next-window cursor
are saved with the recording. See [architecture](../../architecture.md).

## Paired speech results

The actual RecordingService recovery path processed the complete original PCM:
queue → primary Ultra → transactional passages. Capture is tested separately,
including real-model recognition of the public speech fixture through normal
recording and a 185-second synthetic capture with its decoder blocked.

Fourteen speech clips are exactly the seven previously consumed normal DEV
clips and their attenuated copies. Only these fourteen have a historical
same-PCM Whisper Base baseline. The new primary pipeline retained the previous
Ultra word/character error counts in every language/condition; this does not
claim byte-for-byte equality of every transcript.

| Language | Normal word errors: Base → primary Ultra | Quiet word errors: Base → primary Ultra |
| --- | --- | --- |
| EN | 2 → 1 / 28 | 6 → 1 / 28 |
| RU | 8 → 4 / 26 | 8 → 4 / 26 |
| DE | 1 → 0 / 19 | 0 → 0 / 19 |
| FR | 2 → 0 / 32 | 5 → 0 / 32 |
| ES | 4 → 2 / 59 | 4 → 2 / 59 |
| IT | 3 → 0 / 25 | 3 → 0 / 25 |
| PT | 5 → 5 / 21 | 5 → 8 / 21 |

Totals: normal **25 → 12 / 210** word errors and **47 → 29 / 1,050** lexical
character errors; quiet **31 → 15 / 210** and **63 → 32 / 1,050** respectively.
Quiet Portuguese remains worse than Base (**5 → 8 / 21** word errors).
The integration did not introduce that regression, but it remains an open
quality limitation; aggregate improvements do not erase it.

## Controls and longer recordings

All four controls produced zero inserted words. Constant zero/DC PCM bypasses
native inference. Varying noise returned blank text and retains a missing-text
review cue; an empty result is not treated as proof that no speech existed.

The two existing long fixtures retained their previous Ultra metrics: Russian
138.4 seconds, **22 / 210** word errors over five stored ranges; quiet Spanish
179.08 seconds, **7 / 354** over seven ranges. They have no paired Whisper
baseline. The added 276.8-second fixture is exactly two copies of the same
Russian source/reference: **48 / 420** word errors over ten ranges. It verifies
operation past three minutes and new seams; it is not independent speech data.

All 21 results and 40 stored PCM ranges passed source-hash, coverage, overlap,
cursor and text-assembly verification. Stored ranges are not native-call counts.
The corpus ends on full milliseconds; sub-millisecond microphone tails remain
in the original audio under the existing timestamp precision.

The integration probe completed in 506.322 seconds on the dedicated KVM
emulator under a shared 150% CPU / 4 GiB / zero-swap job limit. Its wall time
includes model preparation, service startup and persistence; it is not a matched
speed comparison. An independent review rechecked the receipts, score arithmetic,
APK identities and the boundaries of these claims. No model was downloaded.

## Remaining acceptance

Final APK-wide QA and manual/R8 smoke are reported separately in the
[quality report](../../quality-report.md). Real microphone speech, Russian with
English terms, quiet instructions, latency, memory, heat, battery and background
reliability still require the owner-present [Pixel 10 check](../../device-validation.md).
