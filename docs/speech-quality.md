# Speech quality

This page summarises what has been measured about Focal's transcription, what the
measurements chose, and what is still unknown. Every figure below comes from read
speech on a desktop host or an Android emulator. None of it is a phone measurement
or a test of natural dictation.

Measured results and design decisions are kept apart: each section states the
measurement first and then what the app does with it.

## What Focal runs

- **Recognizer:** Parakeet Ultra INT8, Marmalade's ONNX export of Moondream's
  post-trained NVIDIA Parakeet TDT 0.6B v3 (CC BY 4.0). Four files, about 670 MB.
- **Runtime:** sherpa-onnx 2.0.1 (ONNX Runtime 1.28.2) through its C API, CPU only,
  greedy TDT decoding.
- **Language:** detected automatically. The exported model has no language input,
  so Focal cannot force a language. Parakeet TDT 0.6B v3 is published for 25
  European languages; Focal has measured seven of them (English, Russian, German,
  French, Spanish, Italian and Portuguese).
- **Segmentation:** Silero VAD v4 decides where windows end
  (see [architecture](architecture.md#segmentation)).

## Test material

The engine, segmentation, confidence and formatting figures use 84 clips of
[FLEURS](https://huggingface.co/datasets/google/fleurs) read speech: six clips per
language from the official dev split (the **DEV set**, 864 reference words) and
six per language from the test split (the **held-out set**, 961 words). Clip IDs,
reference texts and hashes are in
[`fleurs-references.json`](benchmarks/fleurs-references.json) and
[`fleurs-corpus.json`](benchmarks/fleurs-corpus.json).

The held-out set was opened once, for the engine decision below. The later
segmentation experiments used both sets to make choices, so no unused hold-out
remains. Treat every figure after the engine comparison as in-sample.

Word and character errors use one normalisation: Unicode NFC, case folding, and
letter and digit runs only. Character errors compare the words joined without
spaces. Russian `ё` is not folded into `е`, and digits versus spelled-out numbers
count as errors, so absolute error rates are somewhat pessimistic. Comparisons
between configurations are not affected.

FLEURS: Conneau et al. (2022), Google, licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Focal uses it for
evaluation only; no FLEURS audio is in this repository or the app.

## Engine choice

Measured on the DEV set, each clip decoded as one window on the host. Whisper
Base Q5_1 received the correct language and used beam search; both Parakeet
models chose the language themselves.

| Language | Words | Whisper Base | Parakeet TDT v3 | Parakeet Ultra |
| --- | ---: | ---: | ---: | ---: |
| English | 88 | 11 | 3 | 5 |
| Russian | 105 | 31 | 19 | 13 |
| German | 122 | 13 | 5 | 3 |
| French | 128 | 32 | 13 | 14 |
| Spanish | 177 | 15 | 44 | 4 |
| Italian | 126 | 31 | 7 | 3 |
| Portuguese | 118 | 13 | 7 | 9 |
| **Word errors** | **864** | **146** | **98** | **51** |
| Character errors | 4,349 | 262 | 229 | 107 |

Parakeet TDT v3 had one Spanish clip with 41 word errors; Ultra made 2 on the
same clip.

On the held-out set, Ultra ran on an Android emulator and Whisper Base on the
host, on identical audio:

| Language | Words | Whisper Base | Parakeet Ultra |
| --- | ---: | ---: | ---: |
| English | 123 | 12 | 7 |
| Russian | 123 | 31 | 12 |
| German | 136 | 32 | 6 |
| French | 178 | 56 | 14 |
| Spanish | 128 | 10 | 5 |
| Italian | 116 | 26 | 2 |
| Portuguese | 157 | 17 | 5 |
| **Word errors** | **961** | **184** | **51** |
| Character errors | 5,010 | 369 | 94 |

Ultra improved word errors on 31 clips and tied on 11. Character errors improved
on 32 clips, tied on 9 and worsened on 1.
([Report](benchmarks/2026-11-05-stt/ultra-android-report.md),
[aggregate](benchmarks/2026-11-05-stt/ultra-android-heldout-42.json).)

Weaknesses that showed up in the same work:

- Very short replies can come out in the wrong language. Synthetic French and
  Portuguese one-word negatives were transcribed as English "No", and a Russian
  "Нет" decoded on its own came out as a fragment.
- Quiet Portuguese was worse than Whisper Base on the one attenuated fixture.
- Russian with English technical terms was tried on two synthetic (eSpeak)
  sentences, each at normal and one-tenth volume, for eight term occurrences,
  plus one control sentence without terms. Ultra matched 4 of 8 terms, "HTML"
  each time; "API" and "guardrails" never (Whisper Base 1 of 8).

**Decision:** Parakeet Ultra is the only engine. Whisper, its model and its
research tooling were removed. The earlier comparisons, including candidates that
failed their screens (Whisper Small, Qwen3-ASR, FUTO ACFT), are in the
[decision review at Alpha 9](https://github.com/andreizpgh/Focal/blob/stt-alpha-9/docs/stt-decision-review-2026-11-05.md)
and the [full report](https://github.com/andreizpgh/Focal/blob/stt-alpha-9/docs/benchmarks/2026-11-05-stt/report.md).

## Segmentation

Alpha 9 waited for 35 seconds of audio and then decoded windows of up to 30
seconds. The rebuild asked where windows should end so that text appears soon
after a pause without cutting through words.

The experiment joined each language's six clips into a continuous stream in four
variants: normal gaps (0.3–0.8 s), long noisy gaps ("pause stress"), the normal
stream at one tenth of the volume ("quiet"), and pink-noise gaps. That gives 3,456
reference words per DEV variant set and 3,844 per held-out set. Latency is
simulated at a real-time factor of 0.15, not measured.

| Policy | DEV word errors | Held-out word errors | Dropped sentences | Text after a pause (p50) | Finish to ready (mean) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Whole stream, one window (comparator) | 263 | 393 | 4 / 7 | 9.8 / 12.6 s | 8.9 / 10.2 s |
| Alpha 9 planner (30 s windows) | 244 | 335 | 4 / 7 | 10.5 / 10.4 s | 3.4 / 3.4 s |
| Energy detector, 6–20 s windows | 230 | 274 | 1 / 2 | 2.0 / 1.9 s | 1.1 / 1.1 s |
| Silero VAD, 6–20 s windows | 221 | 244 | 2 / 0 | 1.9 / 1.9 s | 1.0 / 0.9 s |
| **Silero VAD, 6–20 s, 1 s right context** | **227** | **237** | **2 / 0** | **2.6 / 2.6 s** | **1.3 / 1.4 s** |

The last three columns are DEV / held-out; the two timing columns are simulated,
as above. Every dropped sentence in the DEV set came from the quiet English
stream.

Right context fixes sentence ends at window cuts: a window decoded without the
audio after it tends to end its last word with a period. False sentence breaks
fell from 34 to 15 (DEV) and from 55 to 30 (held-out) with one second of right
context, at the cost of about 0.7 s of latency.

Quiet speech remains the largest error source. With the shipped policy, the quiet
DEV variant had 91 word errors against 50 for the normal one. Silero can classify
quiet room noise as speech, in which case no pause qualifies and windows run to
the 20-second cap.

**What the app does:** the shipped segmenter is the last row, with changes made
after a review of the experiment and not measured end to end:

- 250 ms of pause audio is decoded before each pause-cut window, to protect word
  onsets.
- The duplicate guard at hard cuts is 480 ms instead of 300 ms.
- Windows shorter than 200 ms or with a peak below −60 dBFS are recorded as silent
  without decoding.
- A result of one or two words from a window in which the VAD heard no speech is
  stored but not shown.

A 15-second cap scored as well as 20 seconds or better; 20 was kept for fewer
seams. If Silero cannot be loaded, a relative-energy detector takes over (more
errors in the table above, but no model needed).
([Summary data](benchmarks/2026-11-05-pipeline/summary.json).)

## Doubtful words

Measured: flagging a word when its least confident letter-bearing token has a
probability below 0.5 marks about 1% of words.

| Set | Words flagged | Flags that were real errors | Errors that were flagged |
| --- | ---: | ---: | ---: |
| DEV | 0.8% | 79% | 12% |
| Held-out | 1.0% | 86% | 17% |

The flags repeat across the four variants of the same clips. Counted as distinct
words (15 DEV, 21 held-out), precision is about 0.8 with a 95% interval whose
lower bound is 0.55–0.60. Every language had fewer than ten flags; German was 0
of 1 on both sets. Deleted words can never be flagged, and most errors are
confident.

**What the app does:** doubtful words get a subtle dotted underline. Tapping one
plays it like any other word; editing the word clears the mark; copied text never
contains it. This is provisional: it should be checked on real dictation, and one
constant (`SpeechTuning.DOUBT_ENABLED`) turns it off.

## Formatting

Formatting never changes the recognized words. It removes a period at a window
seam, splits paragraphs and derives a title. Raw recognizer output is stored
unchanged.

- **Seam repair.** A period before a lowercase continuation across a short pause
  (under 0.8 s) is removed. With the shipped segmentation it fixed 8 of 271 seams
  and damaged 1. Without right context the same rule fixed 38 and damaged 1, but
  right context already prevents most of those periods. Lowercasing a capitalised
  word after a seam was rejected: 14 fixed against 23 damaged.
- **Duplicate guard.** On 86 forced hard cuts, words repeated near the cut fell
  from 16 to 2, and the guard removed no correct word.
- **Paragraphs (provisional).** No reference paragraphs exist, and pauses measured
  from token times run about 0.3–0.5 s longer than the acoustic gap. With the
  experiment's 1.5 s threshold, 22 of 70 gaps in the normal streams (all shorter
  than 0.8 s) measured as paragraph-length pauses. The app therefore closes a
  paragraph only at a sentence end: after a pause of at least 3.5 s, after 2.0 s
  once the paragraph holds two sentences, or once it holds 80 words. Speech without
  punctuation closes at the longest recent pause when a paragraph reaches 160
  words. These thresholds still need calibration on real dictation.
- **Title.** The first sentence, extended by the next if it is shorter than 12
  characters, clipped at a word boundary to 48 characters. Abbreviations and
  ordinals ("Dr.", "3.") do not end the title's sentence.

## Threads and memory

Measured on the x86_64 host, so only the ratios mean anything:

- Four threads decoded 1.38 times as fast as two (median real-time factor 0.092
  against 0.127).
- Loading the model took about 2.8 s. Peak resident memory grew with window
  length: 1,470 MiB at 20 s, 1,673 MiB at 30 s and 2,538 MiB at 74 s.
- On an emulator, decoding an 11-second clip peaked at about 910 MiB PSS with ONNX
  Runtime's CPU memory arena and 808 MiB without it.

For comparison, Marmalade reports (vendor figures, English, Pixel 8a, four
threads) a decode real-time factor of 0.078, about 1.3 GB peak RSS and a 4.5 s
load.

**What the app does:** windows are capped at 20 seconds of owned audio, which also
caps decoder memory. The recognizer uses as many threads as the phone has
performance cores, at most four, and two under battery saver or thermal pressure.
The arena is off on phones with 6 GB of RAM or less. None of this is validated on
a phone.

## Automatic correction is disabled

Local language models were tested as a correction pass after recognition. None
improved the transcript enough to justify the cost and the risk of changing what
was said.

- **Text-only correction** (Qwen3.5 2B, Q4_K_M): seven DEV passages went from 44
  to 43 word errors out of 159. On seven synthetic typos it made four exact
  repairs and one wrong one. Median response on the host was 9.3 s.
- **Audio-grounded correction** (Gemma 4 E2B, audio plus Ultra's draft), on seven
  clips from an earlier FLEURS DEV subset at normal and reduced volume: normal
  speech 15 to 14 word errors out of 210, quiet speech unchanged at 14, while
  character errors rose from 33 to 40 and from 31 to 38. On empty drafts of noise
  it invented 30 words. Median call time on the host was 9.9 s; phone cost was not
  measured.

([Correction results at Alpha 9](https://github.com/andreizpgh/Focal/blob/stt-alpha-9/docs/benchmarks/2026-11-05-stt/gemma4-ultra-correction.json).)

Correction stays disabled until a candidate clears all of these gates, frozen
before evaluation on unseen audio:

1. At least 5% fewer word errors than recognition alone, with a paired 95%
   interval that supports the gain, a meaningful gain in Russian and no supported
   language worse. Character errors and names, numbers and negations are checked
   too.
2. Accepted changes are correct with a 95% lower bound of at least 99.5% in every
   language, and introduce no name, number or negation errors.
3. No regression in capture, recovery, original audio or manual edits.
4. Phone budgets met: correction never delays capture, text, Copy or saving; total
   memory under a quarter of RAM; at most about 10% extra energy over a 30-minute
   recording.

An enabled correction would also need the original recognizer text kept beside
it, a visible mark on every changed span, and one-step undo.

## Known limitations

- Read speech only. Natural dictation, accents, crosstalk and Russian–English
  mixing in real speech are untested.
- The language is chosen per window by the model. Short replies, and windows that
  hold only a few words, can come out in the wrong language.
- Quiet speech is the largest error source and can be missed entirely.
- Paragraph thresholds and the doubtful-word threshold are provisional.
- Nothing has been measured on a phone: speed, memory, heat and battery are open
  (see [testing](testing.md#phone-validation)).

## Re-running the regression check

The DEV set can be re-scored with any decoder that writes one transcript per
clip.

1. Fetch the model with `python3 scripts/fetch-model.py`.
2. Download the FLEURS audio archives at revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`.
   A clip ID such as `fleurs-en_us-dev-10098964113747380446` names
   `10098964113747380446.wav` in `data/en_us/audio/dev.tar.gz`.
   [`fleurs-corpus.json`](benchmarks/fleurs-corpus.json) lists each WAV's SHA-256.
3. Decode each clip as one window with the app's configuration: sherpa-onnx 2.0.1,
   model type `nemo_transducer`, `greedy_search`, CPU, the four model files from
   `models/parakeet-ultra/`. Write `{"clip id": "text", ...}` to a JSON file.
4. Score it:

   ```sh
   python3 scripts/score-wer.py hypotheses.json --split dev
   ```

The DEV counts above came from host decoding with the sherpa-onnx Python package.
A different ONNX Runtime build or CPU can change a word here and there, so compare
totals and per-language counts rather than expecting an exact match.
