# Product verification · 2026-11-03

The Sessions / Focus wave direction is preserved. The latest production source
checkpoint is `129ee02`, including Unicode title handling in `6872bb4`; host
dependency setup is `e0a9c78`. This is a development checkpoint, not a finished
product or verified distribution release. The public
alpha APK is unchanged. Quiet/noisy speech, physical-phone resources and stable
signing remain open gates.

## Current local evidence

| Area | Evidence and scope |
| --- | --- |
| Core logic | 36 JVM tests pass |
| Host tooling | 146 tests passed in a separate earlier clean virtual environment with hash-pinned NumPy 2.4.6; not part of the latest QA runner invocation |
| Build | Debug/test APKs, debug/release lint and unsigned release R8 build pass |
| Whole Android suite | Clean HEAD `129ee02`, dedicated API 35 x86_64 AVD with KVM: **82 tests passed, zero failures/skips**, 180.543 seconds |
| Real-model checks | All eight mandatory JNI/workflow checks pass, including quiet public speech and constant-audio recovery |
| Manual UI | Earlier paste/rotation, Save & next, Russian edit/copy/share, 200% text, dark mode and legacy paging; later Unicode title boundaries and rename-dialog rotation checked separately |
| Long capture | 2:32:37 retained audio; task swipe and about 149 minutes screen off survived; a separate 30-minute memory series stayed 177,118–179,802 KiB PSS |
| Speech research | Fresh development comparisons cover all seven languages; [results and limitations](benchmarks/2026-11-03-local/report.md) retain negative variants and an unconsumed held-out cohort |

The [local QA report](benchmarks/2026-11-03-local/emulator-qa.json) records exact
APK/model hashes and all three full-suite checkpoints. The latest runner recorded
clean HEAD `129ee02`. The earlier 77- and 80-test runs retain their dirty-source
fingerprints and later commit mappings; they are not relabeled as clean-HEAD runs.
Debug APK SHA-256:
`4d6cc4dade4d326e760d20005248ada12314d4983cdbfdf5681379cd67b785f7`.
No APK has been published as a replacement release.

Local verification has not triggered another Actions run while the separate
engine investigation continues. One final publication CI model/fixture download
remains reserved in the shared budget. Full-history Gitleaks passed before the
earlier published checkpoint; the secret-scanning workflow remains enabled.

The emulator uses **KVM with `-accel on`** and host GLES; only Vulkan is disabled.
Other projects' AVDs and data were preserved. The `-no-audio` setup does not verify
host microphone input or audible playback. Its captured signal is weak, varying
synthetic PCM, not necessarily exact zeros. The long-session memory series covers
30 minutes of quiet capture, not sustained speech/inference or the entire 2.5-hour
session. Emulator results do not establish phone battery, heat or background behavior.

## Confirmed fixes and an open manual failure

The original editor failure was reproduced twice without weakening assertions.
UI captures and input callbacks disproved the earlier offscreen-warning hypothesis:
activity recreation replayed the accepted draft and cleared rejection feedback.
`12e958c` ignores unchanged input callbacks. The warning and valid draft survive
real keyboard paste, orientation changes and activity recreation; Save & next
also passed. A separate regression verifies feedback clears on a genuine edit.

Manual permission testing exposed an indefinite, obsolete microphone notice after
Deny → Retry → Grant. `ff8ea8d` cancels only that notice's coroutine on retry,
permission result or return with permission granted. The real dialog flow now
starts recording without the stale notice; unrelated error messages are retained.

Title truncation could split a UTF-16 surrogate pair. A real paste of 119 ASCII
characters followed by an emoji displayed a replacement glyph and saved a lone
high surrogate (`ED A0 BD`) in SQLite. `6872bb4` preserves complete pairs while
retaining the 120-unit manual and 72-unit automatic title limits. The repeated
paste retained 119 valid characters; 118 characters plus the complete emoji
survived saving at exactly 120 UTF-16 units (`F0 9F 99 82` for the emoji).

An actual portrait-to-landscape rotation with Rename and the keyboard open then
exposed a separate crash on `6872bb4`: the focus request ran before the dialog's
field was attached. `129ee02` moves that request into the dialog composition.
The new recreation/keyboard/draft/save regression failed before the fix (2.017 s)
and passed afterward (2.905 s). Manual rotation on the fixed build preserved the
draft, focused field and visible keyboard. In landscape, Save was below the IME;
hiding the keyboard with Back allowed saving the exact draft to SQLite. This
does not establish that Save is visible with the landscape keyboard open. A later
manual check on the same APK at 200% font confirmed that the visible keyboard Done
action saved the exact synthetic title to SQLite. The field was usable, although
the dialog heading was clipped; font and rotation settings were restored.

A later focused UI fix replaces Rename's fixed title/body/actions layout with one
scrollable form and explicit system/IME insets. Window inspection confirmed that
Android panned the former oversized dialog beyond the screen; scrolling only its
body could not restore the heading. At 200% font, actual portrait-to-landscape
rotation now preserves the field and keyboard, and swipes expose the complete
heading and both actions above the IME. Tapping Save persists the exact synthetic
title. Dirty Cancel → Keep editing retains the draft; Discard preserves the saved
title. The unchanged strict rename-recreation and oversized-draft tests both pass
(5.964 s), and independent source review found no confirmed regression.

These are focused checks on a working APK containing the still-unaccepted STT
integration, not a new clean full-suite checkpoint. The initial Rename QA APK is
`f84afe713bb0de00121cd2d7254a3fc4ce2e3e6c6bdc170a5a9f9c00c5267c44`;
its `SessionEditor.kt` SHA-256 is
`55e07b03fbe7522e4e3ae70e1b981b66daadc20c453f7649c68bfec9d80a7550`.
The subsequent search-candidate APK used for the dirty-cancel checks
is `8d8b25131f82a9812cd1ec5d5bfb84a2097fa5294c118c5f6468813c0b35f738`;
the rename source is unchanged.
Real TalkBack remains unverified.

Recovery bypasses the live energy gate. A new native regression returned one segment
from 0.5 seconds of exact zero PCM in English on the previous APK (31.474 seconds).
That assertion counts segments; it does not separately measure the returned words. `a5e460d` bypasses inference for strictly constant finite
PCM before padding. All 126 combinations of seven languages, zero/positive/negative
DC, duration and text context return no segments. The workflow preserves original
audio and manual edits without adding blank review passages; varying-audio empty
results retain their existing review cue. The three new real-model tests passed
in 39.44 seconds, including a public speech fixture scaled to 1% amplitude.
These test durations are different workloads, not paired phone latency estimates.

**Open:** actual emulator input varies from −8 to 7 PCM16 units (RMS about
0.000124). Recovery still invents text from that weak signal: observed in Russian
before the constant guard and English afterward. The exact-constant fix does not
solve this broader noise case. The final 2.5-second durable tail was preserved even
though interruption metadata had reached only 2 seconds. No amplitude threshold
or automatic text correction has been enabled to hide this failure.

The pinned engine's no-speech output is nearly uniform on both speech and noise.
Source inspection found that initial-prompt decoding requests only the last logits
row, while no-speech calculation reads the first row. The optional
[SOT research overlay](whisper-sot-research.md) in `9a27635` supports separate host
experiments; it is not adopted by Android. The recorded submodule and shipped
engine remain unchanged. No threshold is justified by the current no-speech values.

The completed cold development screen uses VAD only to skip chunks without detected speech,
retaining whole original ASR inputs otherwise. Word errors are 179/266 versus
207/303 at normal/quiet gain, with no increase in the 14 cells; all 28 synthetic
positive outputs are unchanged and all 28 captured-noise/context cases skip ASR.
The [benchmark report](benchmarks/2026-11-03-local/report.md#whole-chunk-presence-recovered-development-report)
records a report-assembly failure, independently checked postprocessing recovery,
and the unresolved lexical-loss flag. This is not an accepted Android change;
held-out evidence and controlled timing remain open. Earlier SOT/combined variants
retain their negative control or per-cell results rather than being promoted.

The separate [VAD API overlay](whisper-vad-research.md) passes normal-output parity,
CPU cancellation, cleanup and reused-context checks in its host harness. It fixes
an upstream path that reported success after partial graph failure. The app does
not use this overlay in the verified `129ee02` checkpoint. Subsequent
[native policy parity](benchmarks/2026-11-03-local/native-presence-parity.json)
matches all 185 already-used DEV/control requests exactly: conditioned PCM,
probability bits, ranges and presence decisions, with original samples unchanged.
There are 150 positive and 35 negative decisions using one reused detector.
The subsequent [paired reused-ASR comparison](benchmarks/2026-11-03-local/report.md#persistent-whole-chunk-presence-rejected)
is rejected. Normal/quiet word errors change from 223/256 to 176/255, but quiet
Spanish regresses from 16 to 19 errors and ten positive controls fail, including
loss of a previously correct Portuguese negation. Independent review checked
3,129 inputs, 2,176 outputs and the actual Kotlin context/reconciliation replay.
Five newly empty DEV chunks and four lexical flags remain open. The fresh held-out
cohort stays closed; functional Android checks cannot override this rejection.

The local, unpublished Android integration passed 26 focused tests and two
additional preparation/dialog regressions. The latter both failed when their
individual fixes were removed and passed after restoration. Manual English and
Russian force-stop recovery retained the original varying emulator PCM and showed
an uncertain empty passage rather than invented text. These are diagnostic
candidate results, not a clean full-suite checkpoint or an accepted STT policy.
The verified production checkpoint remains `129ee02`.

Fixed-prefix diagnostics now reproduce the warm decoder's call-history dependency
and isolate decoder-zero RNG carry on one ES DEV prefix. The pinned beam sampler
sorts logits but samples random IDs; a one-line reset removes the target difference
in four repeated include/skip sessions. This is a bounded causal result, not an
accepted STT policy. [Evidence and provenance](benchmarks/2026-11-03-local/report.md#reused-decoder-fixed-prefix-diagnosis).
The optional [combined beam research build](whisper-beam-research.md) now passes
eight actual-sampler checks (seven fail on its baseline) and twenty configuration
cases. A separate fourteen-call ES repeat is history-invariant, but its formerly
empty 1.06-second input produces a 122-character segment with a native 5-second
end. The repair is not an accepted quality improvement; full DEV gates remain.
A separate Android smoke used seven actual-language DEV first chunks: audio was
unchanged in 7/7, host text matched in 5/7 and review labels in 6/7. It establishes
integration only; cross-runtime numerical parity and seven-language quality remain
unproven. [Android aggregate](benchmarks/2026-11-03-local/android-dev-smoke-results.json).

The [shorter encoder-context experiment](benchmarks/2026-11-03-local/report.md#shorter-encoder-context-with-whole-chunk-presence-rejected)
is rejected: word errors are 183/249, but six of fourteen cells regress against
production and nine against full-context presence in WER or CER; 15 of 28 positive
canaries fail the comparison gate. Cached VAD-negative noise decisions add no new
noise inference evidence. No speed benefit is claimed from these contended cold runs.

## Independent whole-app review

Focused independent reviewers covered Android/runtime QA, capture/recovery,
storage/privacy/model import, native STT/evaluation, playback/state, and
UI/accessibility. Authors and reviewers were separate for changed layers. The
review/fix cycle also covered the tools used to produce evidence.

Confirmed findings fixed in this checkpoint:

- A process-wide operation registry reserves capture before asynchronous startup,
  stops players before microphone ownership, and excludes recovery/deletion races.
  Recovery seals writing ranges and preserves completed text and manual edits.
- Playback releases its lease and pauses on a noisy audio-route change. Stale
  player callbacks and delayed deletion cannot stop a newer selected source.
- Model opening validates the complete supported base structure, vocabulary,
  tensor names/shapes/types and byte bounds before native loading. Both import
  and previously installed models use that validation. Cancelled/failed import
  preserves the installed model.
- Transcript context rejects embedded NUL and breaks at untrusted text. Overlap
  work examines a bounded suffix; very large tokens cannot cause unbounded work
  or establish a destructive match. Ambiguous repetition remains reviewable.
- New edits have a 128 KiB UTF-8 budget. Large legacy passages stay readable and
  searchable through bounded SQLite reads; the UI pages them without putting
  whole passages into saved state. Copy/share use explicit bounded snapshots.
- Library search/scroll survive navigation and rotation. Search focuses once;
  returning to the library does not unexpectedly reopen the keyboard. Saving the
  next passage preserves the review sheet's state. Failed edits retain the draft.
- Evidence tooling rejects stale or mismatched model/corpus hashes, unsafe output
  aliases, incomplete test reports and missing real-model checks. The local runner
  checks the model actually retained in app-private storage after `install -r`,
  instead of assuming it matches the APK.

Targeted regression tests accompany behavioral fixes. Source review cannot
establish animation smoothness, actual TalkBack traversal or long-session device
reliability; those remain explicit runtime checks.

## Earlier evidence, retained with its original scope

Before this checkpoint, 53 distinct Android instrumentation tests passed and five
model-dependent tests were skipped. Later, all 11 service tests and five related
UI scenarios were repeated after notification/playback polish. The Focus wave
follow-up passed its full 11-scenario UI suite, including 200% text at 320×560 dp,
Russian editing/copy, actual-level/pause semantics and persistent processing
status. Native captures are linked in [interface preview](interface-preview.md).
Those results describe earlier revisions, not unexecuted new tests.

Focus wave assets retain the approved selective blur in static raster launcher
layers; no runtime Gaussian blur is used during capture. Geometry and asset
reproducibility were independently checked. Processing movement stops below
STARTED and respects disabled system animations. Actual Android 13+ launcher
appearance and TalkBack still need device verification.

## Measured speech-quality issue

The current gate is a known quality limitation in the constructed continuous
read-speech experiments. With gain 0.1, complete source intervals give 84/490 word
errors; gated audio gives 172/490 without context and 176/490 with it. Conversely,
bypassing the gate produces text on all synthetic silence/noise controls, including
87 inserted Russian words on 30 seconds of seeded noise. The next policy must
improve quiet activation/framing while retaining false-activation protection.

This is measured host evidence, not a phone or independent-speaker result. It
remains an open product-quality gate even if the functional Android suite passes.
See the [full results](benchmarks/2026-11-03-stt/report.md). No experimental decoder,
gate threshold, model replacement or automatic correction has been enabled.

## Remaining release gates

1. Resolve recovery hallucinations on varying weak noise without suppressing quiet
   speech. Verify any engine/VAD change with paired development data, frozen fresh
   held-out evidence and the complete local suite. Keep the observed failure open
   until the actual UI recovery path passes.
2. Extend speech evidence beyond the small public read-speech corpus: independent
   speakers/sessions, consented Russian dictation, all seven languages, quiet/noisy
   speech, actual boundaries, names, numbers and negations. Freeze decisions before
   opening new held-out results. No automatic correction is enabled.
3. Validate the exact bundled build on midrange and older ARM64 phones, including
   a 4 GB device: current foreground-service rules, screen-off capture, calls,
   memory growth, cold/warm latency, thermal behavior, measured energy and TalkBack.
4. Verify stable distribution signing and the exact release artifact before
   publishing. Do not replace the public alpha with an unverified build.

See [architecture](architecture.md), [device validation](device-validation.md) and
[the experiment plan](stt-quality-experiments.md) for invariants and acceptance gates.
