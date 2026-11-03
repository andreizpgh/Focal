# Product verification · 2026-11-03

The Sessions / Focus wave direction is preserved. The latest production source
checkpoint is `ff8ea8d`; host dependency setup is `e0a9c78`. This is a development
checkpoint, not a finished product or verified distribution release. The public
alpha APK is unchanged. Quiet/noisy speech, physical-phone resources and stable
signing remain open gates.

## Current local evidence

| Area | Evidence and scope |
| --- | --- |
| Core logic | 34 JVM tests pass |
| Host tooling | 146 tests pass, including a clean virtual environment with hash-pinned NumPy 2.4.6 |
| Build | Debug/test APKs, debug/release lint and unsigned release R8 build pass |
| Whole Android suite | Dedicated API 35 x86_64 AVD with KVM: **80 tests passed, zero failures/skips**, 178.355 seconds |
| Real-model checks | All eight mandatory JNI/workflow checks pass, including quiet public speech and constant-audio recovery |
| Manual UI | Real keyboard paste/rotation, Save & next, Russian edit/copy/share, 200% text, dark mode and bounded legacy paging observed |
| Long capture | 2:32:37 retained audio; task swipe and about 149 minutes screen off survived; a separate 30-minute memory series stayed 177,118–179,802 KiB PSS |
| Speech research | Fresh development comparisons cover all seven languages; [results and limitations](benchmarks/2026-11-03-local/report.md) retain negative variants and an unconsumed held-out cohort |

The [local QA report](benchmarks/2026-11-03-local/emulator-qa.json) records exact
APK/model hashes and both test checkpoints. The latest runner recorded HEAD
`c21ae20` with a dirty-source fingerprint; its application/test bytes are now in
`a5e460d` and `ff8ea8d`. It was not a clean-HEAD run of those later commits.
Debug APK SHA-256:
`efd5d5fbbe6fa93e4c52c4b92e58c6921465c75ff049728e937900468157a6e6`.
No APK has been published as a replacement release.

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
row, while no-speech calculation reads the first row. A separate, ignored engine
snapshot is investigating the correct SOT row; the recorded submodule and shipped
engine remain unchanged. No threshold is justified by the current no-speech values.

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
