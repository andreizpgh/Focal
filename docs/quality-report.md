# Product verification · 2026-11-03

Report date uses Asia/Tbilisi (UTC+4); the current checks ran on 2026-11-02 UTC.

The Sessions / Focus wave direction is preserved. This checkpoint hardens the
whole application and adds reproducible seven-language speech measurements.
The public alpha APK is unchanged. Full local-emulator QA, physical-phone
performance and distribution signing remain release gates; this is not a
claim that the application is finished.

## Current checkpoint

| Area | Evidence and scope |
| --- | --- |
| Core logic | 34 JVM tests pass, including bounded context, overlap, invalid confidence and recording boundaries |
| Host tooling | 109 Python tests pass across fetch/evaluation/probe/correction/gate/shadow/local-QA/CI-summary tools |
| Build | Debug/test APKs assemble; debug and release lint pass; release R8 compilation passes |
| Real-model JNI | All five previously skipped model-dependent JNI/workflow checks pass on the API 35 CI emulator, including real speech and capture without an activity |
| Whole Android suite | Latest [CI run 41538859249](https://github.com/andreizpgh/Focal/actions/runs/41538859249): 76 tests completed, 75 passed, one failed, zero skips; the remaining warning-visibility failure is an open gate |
| Speech measurements | Real FLEURS audio, pinned engine and three verified models; paired WER/CER per language, with development/test separation and explicit limitations in the [benchmark report](benchmarks/2026-11-03-stt/report.md) |
| Packaging/privacy | No Internet permission, backups disabled, native ELF/ZIP alignment checked at 16 KiB; full-history/source Gitleaks checks pass at the published checkpoint |
| Local QA preparation | [Dedicated-emulator runner](local-emulator-qa.md) verifies source, APK and installed model hashes, exact test identities, all five real-model checks and absence of skips; fake-device tests and independent source review pass |

The initial full CI passed host tests, real-speech smoke, build/lint and all 34
core tests. It exposed an oversized-editor visibility assertion, a 24-pixel
absolute-coordinate scroll assertion after recreation, and a likely paused-clock
UI test deadlock. The warning now requests visibility outside the capped text
field; scroll checks
use logical and viewport-relative position; paused-clock checks avoid synchronous
scrolling and idle lookups. Independent re-review and the full local build pass.
The next runs completed all 76 tests with no skips: first 73 passed, then 75.
They verified all five required model-dependent checks and exposed dialog-window
keyboard handling, an ambiguous search selector and warning-restoration timing.
Search, library restoration and Save & next now pass. The final bounded run
[41538859249](https://github.com/andreizpgh/Focal/actions/runs/41538859249) again completed
75/76 successfully. The sole remaining failure is warning visibility after
activity recreation, including after a five-second visibility wait; it is not
being dismissed as an immediate-assertion race. The valid draft assertion passes
before that failure; this test does not reach its later Save check. Reproduce
the actual viewport on the local accelerated emulator before another UI change.
No further blind Actions cycle is planned; the test and failure remain visible.
The fail-closed model gate correctly rejected the initial incomplete suite.
Per-test, suite and job limits now preserve diagnostics instead of hanging silently.

Host measurements include process/model loading and are not phone latency or
battery results. The local software-emulated Android guest became too slow to
complete broad instrumentation reliably; its partial successful JNI checks do
not count as a full suite pass. Application/source checkpoint: `1df806012e117034f91a8732bd7c32189a3419e7`.
The final local build includes debug/release lint, test APK, release R8 and bundled
model verification. Debug APK SHA-256:
`9c9b8cebff6261ec61b23e02d9ea11cc1b6be7fbd21b73a74fb18df08046c84b`.
Unsigned release APK SHA-256:
`e8561b873b39e389904f2a3a54b0bba10b31d1e35594810cf0822bb54d7b2992`.
These local artifacts are not published replacements for the alpha.

A hardware-accelerated local emulator is the preferred environment for repeated whole-app and longer-session QA. GitHub
Actions is used for bounded checkpoint verification, not repeated endurance runs.

## Independent whole-app review

Six focused Sol/Terra reviewers covered Android/runtime QA, capture/recovery,
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

1. Fix and verify the outstanding restored-warning visibility failure, then pass
   the entire suite without skips. Repeat whole-app UI, interruption/recovery,
   large-text and recording/playback paths on the owner's
   accelerated emulator using the [local QA guide](local-emulator-qa.md).
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
