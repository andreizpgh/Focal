# Product verification · 2026-11-04

## UX Alpha 5 · 2026-11-04

Clean `7b102aee07b418a4e9ff6f01c426f708f2956cef` passed the complete local
`qa-local.py --release-checks`: **103 Android tests, zero failures/skips**, including
all eight required real-model checks. JUnit reported **145.206 seconds**; the
runner measured 146.192 seconds around instrumentation. Both lints and
debug/test/unsigned release/R8 builds passed in 3m51s; 36 unchanged core tests
used cached results. [Exact APK identity](../releases/ux-alpha-5.json).

Sessions, Session and Settings share a compact navigation header, typography and
insets. A long note title stays in the content. Metadata uses separate date and
language/duration groups; timestamps align with the first transcript baseline.
Settings uses spacing between sections and removes duplicate language selection:
the picker beside Record already remembers the last choice.

Search expands and closes without resetting the scrolled list, and the idle
record dock yields room while searching. Review filtering moves rows without
inserting explanatory copy or overlapping disappearing text. Copy confirms success
inside its button. Long-press opens the same action sheet as the session menu;
loading, cancellation, dirty navigation and the action target are guarded. The
editor makes Done/Save primary and Next hint secondary, preserving failed-save
drafts and requiring a successful save before advancing.

The design follows Android guidance for [consistent app bars](https://developer.android.com/develop/ui/compose/components/app-bars),
[layout animation](https://developer.android.com/develop/ui/compose/animation/composables-modifiers),
[long-press actions](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/tap-and-press)
and [text baseline alignment](https://developer.android.com/develop/ui/compose/layouts/alignment-lines).
Eight focused regressions were added. Original oversized-editor and transactional
assertions remain; only selectors and deliberately changed UI expectations moved.

Actual synthetic-note screens and recordings covered light/dark, 200% landscape
with IME, draft retention, playback, Copy and animations disabled. Frame review
found overlapping filter rows; shorter exit and ordered movement/entry fixed it.
Earlier focused checks also exposed search clipping at large font; required-height
measurement and hiding the idle dock fixed it. A retained manual rotation override
and an interrupted fixture caused separate test failures; restoring the AVD and
removing only that fixture allowed unchanged assertions to pass. Those failed logs
remain private. The first Alpha 5 full run passed on clean source.

Sol/Terra reviewed focused code; Astra accepted the actual static and motion
states. Exact artifact audit checks model and native hashes, certificate continuity,
16KB alignment and absence of Internet permission or fault helpers. Final captures
are in [interface preview](interface-preview.md). No model was downloaded and no
recognition setting changed. Phone performance, seven-language speech quality,
noise hallucinations, mixed-language terms, local correction and owner UI acceptance
remain open. The [Alpha 5 release](https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-5)
is published with verified remote asset sizes/digests; the public download returns
200. [PR #6](https://github.com/andreizpgh/Focal/pull/6) carries this update.
Full-history Gitleaks found no leaks before publication. Earlier assets are unchanged.

## UX Alpha 4 · 2026-11-04

Clean `a3038fa104f0178599ac1642fd3c0e471f368742` passed the full local
`qa-local.py --release-checks`: **95 Android tests, zero failures/skips**, including
all eight required real-model checks, in **143.684 seconds**. Both lints and the
debug/test/unsigned release/R8 builds passed in 4m10s; 36 unchanged core tests
used cached results. [Exact APK identity](../releases/ux-alpha-4.json).

This update fixes flush playback highlights, tall timestamp pressed states,
repeated Review labels, separated header filters, heavy popup shadows and
mismatched Save-label placement. The editor uses a filled surface with explicit
focus/error states. The processing wave now changes bar heights visibly;
it retains indeterminate semantics, lifecycle bounds and reduced-motion behavior.
Plain filled/text [buttons](https://developer.android.com/develop/ui/compose/components/button)
keep the two Save labels centered, and the moving status follows the
[indeterminate-progress pattern](https://developer.android.com/develop/ui/compose/components/progress).

The new motion regression failed on the installed Alpha 3, which only varied
opacity, and the same installed test APK passed the candidate alongside four
existing editor/playback/browsing regressions (5/0/0 in 10.684s). The final APK
passed all 95 tests. Existing oversized-draft assertions were not weakened.
The filter test now asserts the fixed header remains displayed while preserving
its earlier scroll restoration checks; review copy expectations match the dots.

Manual synthetic-note checks covered normal/200% font, true landscape with IME,
scroll-to-save, persisted edits, light/dark, menus and playback. A settled frame
confirmed the active line and caret above the keyboard; an earlier capture was
during its transition. Sol/Terra handled focused changes; Astra independently
reviewed source and actual screens. Artifact audit verifies the unchanged model,
six native libraries, certificate continuity, 16KB ZIP alignment, and no Internet
permission or fault helpers. Captures are in [interface preview](interface-preview.md).

The first full-run attempt failed incremental release Kotlin resolution of
existing functions. Recompiling Kotlin without incremental state passed on
unchanged source, followed by the complete successful run. Its failed log remains
private alongside the successful report `20261104T143143.240767Z`.

This is a UI testing release. It does not establish seven-language STT improvement,
noise suppression, local correction safety or Pixel 10 resource/background results.
No model was downloaded and no recognition setting changed. Owner acceptance of
the revised interface remains open.

The [Alpha 4 testing release](https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-4)
is published with verified remote asset sizes and SHA-256 digests.
[PR #5](https://github.com/andreizpgh/Focal/pull/5) carries the UI and delivery docs;
full-history secret scanning found no leaks. Prior assets remain unchanged.

## UX Alpha 3 · 2026-11-04

Clean `ed9d7cb8250eb8f7ad454fad47c5f70bf7b9a8de` passed the full local
`qa-local.py --release-checks` cycle: **94 Android tests, zero failures/skips**,
including all eight required real-model checks, in **149.326 seconds**.
Gradle reported a five-minute build with debug/release lint, debug/test and
unsigned release/R8 builds; 36 unchanged core tests used cached results.
The exact [development-signed APK](../releases/ux-alpha-3.json) is version
`0.1.0-alpha.3` / code 3. Its certificate matches Alpha 2. The unsigned release
build is a separate build check, not the instrumented or published artifact.

The UI updates the upward language popup, rounded session actions, timestamp
spacing, passage-editor padding and playback thumb. A new regression checks the
selected last language at 200% font. Review hints now explicitly allow checking
any passage; “No flagged passages” no longer implies an error-free transcript.
Recognition thresholds and decoder settings are unchanged.

Manual dark-theme observation found that the editor's separate window kept
light system bars. A new test failed on candidate `abd18287…e5c36b` at the dark
status-bar flag. The same test APK passed on `d2efdb6a…eda17`, together with three
editor/rotation regressions: **4/0/0 in 11.455 s**. The final dark-editor capture
confirms the navigation background also changed. Earlier focused UI/validator
checks passed **9/0/0 in 16.322 s** before this final color-only window fix.
Original oversized-editor assertions were retained.

Manual synthetic-note checks covered light/dark, language/menu placement,
playback, seeks 20 dp above/below the track and 200% landscape editing with the
IME. Save controls remained reachable by scrolling and the edit persisted.
Captures made before the dialog fix are recorded separately from the final APK.
Host audio was disabled. These checks establish neither audible playback nor
Pixel 10 background, microphone, thermal, battery or ARM latency behavior.
Sol/Terra cross-reviewed implementation, Astra reviewed the critical changes and
screens, and Terra independently checked the final full-QA/APK/model/signature
chain. Native libraries match Alpha 2; model hashes, 16 KB ZIP alignment and
absence of Internet permission, Silero and fault-test code were verified.

The [validator comparison](benchmarks/2026-11-03-local/validator-preflight-results.json)
measured host preflight only: first-call median 176.52→70.33 ms, later calls
125.65→9.48 ms with a warm OS file cache. It is not ASR decoding or phone speed.
The [six-pair glossary screen](benchmarks/2026-11-03-local/mixed-term-hint-results.json)
failed and is not shipped. Two local correction screens on the same 21 consumed
DEV/synthetic texts also failed their fixed acceptance rules:

| Candidate | Valid abstentions | Invalid replies | Valid edits | Host call total |
| --- | ---: | ---: | ---: | ---: |
| [Qwen3-0.6B Q4](benchmarks/2026-11-03-local/correction-shadow-qwen3-06b-results.json) | 16 | 5 | 0 | 24.62 s |
| [Qwen3.5-2B Q4](benchmarks/2026-11-03-local/correction-shadow-qwen35-2b-results.json) | 20 | 1 | 0 | 142.36 s |

The frozen complete-validation rule withheld aggregate WER/CER. Timings include
initial load and are descriptive host costs, not phone results. One accidental 2B
preflight generation is preserved separately and excluded from the matched run.
A subsequent source audit identified that the schema emits replacement before
source. A separate [16-call synthetic diagnostic](benchmarks/2026-11-03-local/correction-schema-order-results.json)
returned the same counts for both orders: five abstentions, three invalid replies
and zero valid repairs per arm. Reordering alone did not improve yield on those
eight canaries. These results cannot reject local correction generally. Automatic
correction, all-language quality/noise improvements and phone acceptance remain open.

A separate [Whisper small warm screen](benchmarks/2026-11-03-local/whisper-small-warm-controls-results.json)
stopped after eight Portuguese controls. Noise insertions fell 247→4, but
small corrupted a quiet negation that base recognized correctly. The critical
regression prevents promotion; no remaining language sessions ran. Its 369.27 s
host cost is not comparable to the cached baseline or phone performance.

The [ledger](benchmarks/2026-11-03-stt/download-budget.json) retains all prior
reservations under the owner's revised 5 GB ceiling. Current reservation:
**2,749,583,598 bytes**, remaining **2,250,416,402 bytes**. Both Qwen files and
Whisper small are verified local caches; no base STT model was downloaded again. The 42 fresh
held-out clips remain unopened. Alpha 3 was published and merged through
[PR #3](https://github.com/andreizpgh/Focal/pull/3); these research results do not
change its verified APK.

## Earlier delivery checkpoints

The Sessions / Focus wave direction is preserved. Source `fe70758` is published
as [UX Alpha 2](https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-2), version
`0.1.0-alpha.2` / code 2, for interface feedback before physical-phone validation.
The previous Alpha 1 assets were not changed. Paired speech accuracy and speed
across all seven languages, physical-phone validation and stable signing remain open.

The public landing page now names UX Alpha 2 explicitly and uses the approved
Focus wave logo. [PR #1](https://github.com/andreizpgh/Focal/pull/1) integrates this
checkpoint into `main`. The publication follow-up changes documentation only;
application and build inputs remain identical to verified `fe70758`. Interface
captures are dated separately and are not presented as exact Alpha 2 screenshots.

A [primary-source review](stt-quality-experiments.md#practical-direction-after-primary-source-review)
on 2026-11-04 keeps the scope on ordinary voice notes and background capture.
Independent Sol/Terra research and Astra review rejected further investment in
the narrow terminal-only recovery filter for now. That research-only checkpoint
(`86a3a1c`) added no production STT change or fresh inference. The subsequent UX
checks below are separate; no speech policy was changed.

## UX Alpha 2

Clean `fe70758350a114d5e66775ee114cd48778217594` passed the complete local runner
with `--release-checks`: **89 Android tests, zero failures/skips**, including all
eight mandatory real-model tests, in **154.329 seconds**. The 254-second build
phase passed debug/release lint and debug/test/unsigned release builds; 36 core
tests used cached results for unchanged source. Report `20261104T102008.204711Z`
binds the exact APKs. [Public release manifest](../releases/ux-alpha-2.json)

The new interface regression reproduces an interrupted session with a completed,
manually edited passage and a saved tail absent from the queue. The old APK has
no Continue button; the new one recovers only the tail without opening a microphone
or changing the original PCM/manual passage. A separate store regression proves
that pending-count zero previously allowed deletion of that tail. Removal now
rechecks recoverable tails transactionally; fully processed interrupted notes
retain the existing removal behavior. Both tests failed at their intended
assertions on the previous APK and passed on the candidate with the same test APK.
Five focused checks passed in 6.201 seconds, including the unchanged oversized-editor
rotation test. The initial focused-run postprocessor incorrectly applied the full
suite's eight-model-test requirement; its diagnostic is retained. The subsequent
full suite above passes that requirement without modification.

Manual system-dialog denial of notifications showed the new recording guidance.
Home, short screen lock, return, pause/resume and Finish retained a `READY` session
of 110,704 ms with exactly 3,542,528 PCM bytes. Host audio was disabled and no chunks
were produced; this is not speech-quality or physical-microphone evidence.
Permission grants/flags were restored. The dedicated AVD was stopped after QA.

Focused Sol review found the issues, Astra reviewed the source and publication,
and Terra independently checked the exact artifacts and full result. Published
APK SHA-256 is `955c7bb599ce4236013545ba50901cee5403146ecedf4f47e527193e5d91b315`;
test APK is `68153fa7ee70c80b8434d4d8bc4f2ae24e2d1f915cbba4473cfe53c2ac483376`.
These are the same bytes used for focused, manual and full verification. All six
native libraries remain byte-identical to the earlier verified build; model,
signature, 16 KiB ZIP/ELF alignment and absence of Internet/fault helpers/Silero
were checked. The release uses the development signature, without rebuilding or
re-signing. Alpha 1 update compatibility is not established.

All four uploaded asset digests and the annotated tag were checked before
publishing; an unauthenticated HEAD request returned HTTP 200 without downloading
the APK body. Full-history Gitleaks covered 82 commits before release publication.
One initial GitHub push failed with a server error; separate branch/tag retries
succeeded. No Actions or model/corpus downloads were needed. Exact local artifacts
are archived under `build/local-diagnostics/ux-alpha-2-20261104/verified-apks/`.

## Previous local checkpoint · 036d716

| Area | Evidence and scope |
| --- | --- |
| Core logic | 36 JVM tests verified from cached outputs for unchanged core sources |
| Host tooling | **157 tests passed** in 9.844 seconds after incremental native input validation was added. Six expected regression failures reproduced against the prior CMake inputs. |
| Build | Clean `036d716` passed the 56-second build phase, including debug/test APKs, both lint variants and unsigned release/R8. |
| Whole Android suite | Clean `036d716` passed **87 tests** with zero failures or skips in 149.980 instrumentation seconds on the dedicated KVM API 35 x86_64 AVD. |
| Real-model checks | All eight required JNI/workflow checks passed, including quiet public speech and constant-audio recovery. |
| Manual UI | Earlier checks cover paste/rotation, Save & next, Russian edit/copy/share, 200% text, dark mode, legacy paging, Rename, search caret/rotation and Back → Home → Focal. The final APK also passed short capture/pause/Home/resume/Finish, playback/seek and 200% font Rename rotation/save checks. |
| Long capture | 2:32:37 retained audio; task swipe and about 149 minutes screen off survived; a separate 30-minute memory series stayed 177,118–179,802 KiB PSS |
| Speech research | Fresh development comparisons cover all seven languages; [results and limitations](benchmarks/2026-11-03-local/report.md) retain negative variants and an unconsumed held-out cohort |

Full QA report `20261104T083156.095960Z` binds the exact `036d716` artifacts to
the clean source. The local archive is in
`build/local-diagnostics/post-ime-qa-20261104/verified-apks/`. The debug and
unsigned release native libraries for both ABIs are byte-identical to their
verified `7760ff2` counterparts. The APKs passed 16 KiB ZIP alignment checks.
Release was built and inspected separately; instrumentation ran the debug APK.
Both app artifacts contain the ownership overlay and exclude fault helpers,
Silero assets and `INTERNET` permission. The aggregate also retains earlier
checkpoints, hashes and failed attempts. [Local QA evidence](benchmarks/2026-11-03-local/emulator-qa.json)

| APK | SHA-256 |
| --- | --- |
| Debug | `b68946e904ed69b8c5123316f64611f5e3cc8334049191c91e5395255f6ae647` |
| Instrumentation | `b25ff568c8bab05ade9049619257f0f81b047e67e020a5fb98242d0c480ebd76` |
| Unsigned release | `5c929cca2e5fbbc094fec932e2cc3db044cd923a01ec542db4b1a28b3e34f181` |

The earlier clean ownership build at `7760ff2` passed 85 tests with zero
failures or skips in 155.054 instrumentation seconds.

The clean `057daca` run passed 85 of 86 tests in 163.099 instrumentation
seconds. The recovery fixture timed out waiting for its second attempt to reach
`READY` after a synthetic SQLite failure. The failed result is retained.
`f3e05f4` made the test wait for claim release and passed both targeted service
tests in 2.915 seconds. The test log observed a claim still held after visible
teardown. The earlier run did not log the duplicate-command branch.

The service cancellation regression changed from a committed `DONE` chunk to a
preserved `PENDING` chunk using the same test APK against `7760ff2` and `057daca`.
It exercises the cancellation/commit boundary with a synchronous fake decoder.
Source review covers the separate engine-publication ordering. The ViewModel
retry regression also failed and passed on one unchanged test APK. It verifies
visible busy feedback for an already-held recovery claim, data preservation and
new admission after release. A physical tap during the teardown window remains
unverified.

The clean `f359fd5` run passed 86 of 87 tests in 154.225 instrumentation seconds.
`activeSearchKeepsSelectionAndKeyboardThroughRecreation` failed after Back with
the correct query and caret at 14 instead of 22. A diagnostic with additional
reads and logging passed in 8.179 seconds. It observed caret 14 after the tap and
22 after explicit selection, with 22 retained at the keyboard check and
after Back. The original event ordering remains unknown.

`036d716` waits for the keyboard before setting the caret and asserts the caret
before Back. All subsequent assertions remain. The focused oversized-editor,
active-search and caret-return tests passed in 12.177 instrumentation seconds
using unchanged app APK `b68946e9…ae647` and test APK `b25ff568…bd76`. This
verifies the strengthened test setup. The app source remains `f359fd5`.

Local verification used no additional Actions or downloads. The shared ledger
has 881,742,333 bytes reserved, including 61,000,000 for one publication CI
download. The remaining allowance is 118,257,667 bytes.
All 42 fresh held-out clips remain closed. The earlier full-history Gitleaks scan
covered 78 commits through code checkpoint `036d716`; publication of `a2e9674`
covered 79. The UX Alpha 2 scan is recorded above. The secret-scanning workflow
remains enabled.

The emulator uses **KVM with `-accel on`** and host GLES; only Vulkan is disabled.
Other projects' AVDs and data were preserved. The `-no-audio` setup does not verify
host microphone input or audible playback. Its captured signal is weak, varying
synthetic PCM, not necessarily exact zeros. The long-session memory series covers
30 minutes of quiet capture, not sustained speech/inference or the entire 2.5-hour
session. Emulator results do not establish phone battery, heat or background behavior.

The final manual capture remained paused at 00:21 through Home and return, then
resumed and saved `READY` with 32,896 ms and 1,052,672 PCM bytes (exactly duration
× 32). It produced no chunks or passages with host audio disabled. Original-audio
position advanced and seeking moved it to 00:19. At 200% font, Rename preserved
the synthetic draft through actual landscape rotation; scrolling exposed the
whole Save button above the IME and the saved title matched SQLite exactly.
The initial rotation settings write did not persist, so WindowManager supplied
the visually verified rotation. Font/rotation settings were restored and only
the dedicated AVD was stopped. These are scoped current-build observations;
the earlier broad and long-session checks retain their original build identities.

## Confirmed fixes and an open manual failure

`101a153` retains the search caret when opening a result and returning. The field's
saved value previously left composition with the Sessions screen, resetting a
middle caret to the query's end. Moving `TextFieldValue` to the retained app state
preserves that caret without reactivating the keyboard. The frozen new test failed
at caret 13 instead of 6 on a source-matched `b59c6ee` rebuild (3.497 s), then
passed unchanged on the fix (3.395 s); six prior UI cases passed in 21.063 s.
At `101a153`, all 23 earlier InterfaceTest bodies, including the strict
oversized-editor test, were unchanged. An earlier draft wrongly expected
highlighted selection to survive explicit focus clearing. Cached Compose code and
runtime observation showed its deliberate collapse to the selection end. The
failed expectation is retained separately and the fix allows the framework callback.
The focused APK and later clean full-suite APK have separate recorded hashes.

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

The search follow-up now passes six focused UI regressions on the base-only app
(26.689 s, zero failures/skips), including the unchanged strict oversized-editor
and Rename checks. A saved bounded `TextFieldValue` preserves the caret and whole
UTF-16 characters, measured IME space keeps the field visible in short landscape
viewports, and a one-shot focus request avoids repeated app requests. Direct
Home/task testing exposed an additional Android restore path: after Back, the
retained focused editor reopened its keyboard without another app `show()` call.
The ON_PAUSE boundary commits already-dismissed software input while retaining
active input intent. The strengthened test fails before that fix and passes after
it, covering both active and hidden keyboards in the same Activity.

Manual checking on 2026-11-04 at 200% system font confirmed the whole field above
the landscape IME, exact continued typing through portrait/landscape/portrait,
and a hidden keyboard after Back → Home → Focal. The exact debug APK is
`1ce2ee6c355310d6c42b24cf74da11f993831452ef55d16b1d54a6e46cf990f0`;
it contains the verified base model and no Silero asset. The subsequent clean
84-test production run used this same APK and passed the complete release checks.
Both merged manifests lack Internet permission. The earlier six-test candidate
run at global font 2.0 retains its
Rename failure: saved title/text were correct and scrolling exposed the passage,
but its final immediate-node wait assumed the ordinary viewport. No assertions
were weakened to turn that failed run into a pass. Physical/mixed hardware
keyboard behavior and TalkBack are not established by these checks.

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
The clean full-suite checkpoint is now `b59c6ee`, with that candidate removed.

Fixed-prefix diagnostics now reproduce the warm decoder's call-history dependency
and isolate decoder-zero RNG carry on one ES DEV prefix. The pinned beam sampler
sorts logits but samples random IDs; a one-line reset removes the target difference
in four repeated include/skip sessions. This is a bounded causal result, not an
accepted STT policy. [Evidence and provenance](benchmarks/2026-11-03-local/report.md#reused-decoder-fixed-prefix-diagnosis).
The optional [combined beam research build](whisper-beam-research.md) now passes
eight actual-sampler checks (seven fail on its baseline) and twenty configuration
cases. A separate fourteen-call ES repeat is history-invariant, but its formerly
empty 1.06-second input produces a 122-character segment with a native 5-second
end. The completed persistent DEV comparison now rejects this repair with the
presence policy: word errors fall from 223/256 to 176/246, but six language/condition
cells worsen in WER or CER and 14 positive controls fail. Previously correct quiet
French/Portuguese negations become empty despite positive VAD. All 28 noise controls
skip ASR; that does not override speech regressions. Independent review verified
4,289 inputs, 2,187 outputs and all 335 ASR/185 VAD calls. Eight newly empty DEV
chunks and seven lexical flags remain open. [Full comparison](benchmarks/2026-11-03-local/report.md#true-top-k-with-persistent-whole-chunk-presence-rejected).
Source-local English analysis separately confirms that the current gate entirely
excludes two clips containing 25 reference words. It does not establish a replacement
framing policy or justify another run of the same rejected decoder. No held-out
clips were consumed and no speed improvement is claimed.
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

The later [preserved-ASR experiment](benchmarks/2026-11-03-local/report.md#preserved-asr-suppression-development-result-not-adoption)
retains every original decoder call and raw result, then applies VAD only to emitted
passages. All 185 raw outputs match production; emitted normal/quiet word errors
are 179/252 versus 223/256, with no regression in 14 cells. All 28 positive controls
are unchanged, while 28 noise controls have empty emitted output but still contain
nonempty raw ASR. Independent review verified 2,877 inputs and 945 outputs. Five
changed DEV ranges received source-local textual review; four lexical flags remain,
without an acoustic no-omission claim. WER improves only in EN/ES/IT, and extra VAD
work does not establish a speed improvement. The seven-language, held-out and
phone gates remain open. Future automatic suppression requires retained original
ASR, visible provenance and Undo; it has not been enabled in Android.

The subsequent [greedy control screen](benchmarks/2026-11-03-local/report.md#greedy-control-screen-rejected-before-human-dev)
was rejected before human DEV: 15 strict positive failures, including lost correct
FR/PT negations. Textual review also caught an IT time-component error despite
passing aggregate metrics. Independent review verified 1,762 inputs, 255 outputs
and both safe exports. No new held-out data, production policy or speed claim
results from this screen. The rejected Android VAD integration was archived and
removed from the working build on 2026-11-04, including its packaged model assets;
its earlier focused results remain historical candidate evidence.

A later [model-free beam control-flow study](benchmarks/2026-11-03-local/report.md#beam-control-flow-ablations-model-free-contracts-only)
isolated duplicate branch selection and final emission without a valid winner.
Four actual-source harness variants produced the frozen 15/4/11/0 assertion-failure
sets; the combined research variant passed all 22 synthetic cases. Independent
review verified 1,231 inputs and 24 outputs. No model or audio inference ran;
this does not reverse prior speech rejections or establish STT quality, speed,
Android adoption or durable-storage behavior. The production decoder is unchanged.

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

A later 56-control screen of corrected SOT, true top-k and beam control-flow
repairs also failed acceptance: 14 strict positive failures, three successful
newly empty outputs and one explicit native decoding failure. Critical review
found lost FR/PT quiet negations and a PT negation loss despite lower WER/CER.
All noise controls skipped ASR; that does not offset speech regressions. Independent
review verified 796 inputs and 289 outputs. Only existing synthetic speech controls
and two previously recorded emulator-noise signals were consumed; fresh held-out
audio remains closed. The production decoder is unchanged.

The [ownership build overlay](whisper-lifetime-research.md) was first added as an
inactive option generating the exact independently reviewed prototype without
changing the pinned submodule. Nine tests cover 19 configuration scenarios, including rejection of
unknown inputs and speech-policy overlay composition. The full host suite passed
**155 tests with no skips in 7.478 s**, using the existing pinned NumPy 2.4.6
environment. This historical checkpoint covered the optional overlay before
ordinary Android integration.

The separately frozen real-graph harness subsequently compiled both sanitized
variants in 91.926 s; independent prebuild/postbuild reviews verified 682 inputs,
96 tool/link identities and seven runtime dependencies. Its first healthy baseline
case loaded the cached model and initialized real state graphs, then hit the
60-second wall limit. Only that process ran; there is no full return or canonical
output, and the patched variant was not executed. The attempt is incomplete,
not an expected RED or proof of recovery. Independent review confirmed the
[failed-attempt record](benchmarks/2026-11-03-local/engine-lifetime-realgraph-results.json).
No model download, held-out evaluation or application change occurred.
A separately reviewed single-case diagnostic then passed the unchanged healthy
baseline in 79.903 s, supporting an insufficient original 60-second allowance.
It retained one inference thread, RSS guards and all functional assertions. The
maximum sampled VmHWM was 249,576 KiB. This does not establish a specific slow
stage, sanitizer overhead factor, patched/retry behavior or phone performance.

The later real-model ownership matrix is now covered by 23 matched cases from
one preserved attempt and seven from a separately reviewed continuation. All
15 patched cases pass with clean sanitizers; baseline has five healthy controls
and ten expected regressions. Same-state beam/greedy/auto retry outputs match the
required healthy controls exactly. The earlier wall timeout and subsequent
CPU-limit failure remain failed attempts. These checks reused the cached model
and consumed no new held-out speech. They establish scoped ownership behavior,
not speech accuracy, speed, arbitrary OOM resilience or phone resource budgets.

## Verified Whisper ownership fix

Independent source review found that self-KV growth could free a state still
owned by the context, then JNI close could free it again. The ownership fix
also covers related early initialization and partial-allocation cleanup defects.
Isolated model-free regressions reproduce two ASan use-after-free paths and
related cleanup errors. The baseline passed four controls and reproduced 20
expected errors. The ownership-only prototype passed all 24 cases with clean
sanitizers. Independent review verified source and tool hashes, binaries,
dependencies and 48 execution logs. These checks use real engine/GGML source
with synthetic descriptors and graph bypasses. The later actual-model retry and
loader-wrapper checks close that host gate. The isolated Android patched build
passed both strict tests in 173.937 s. Audio, manual edits and pending work survive
the error and ordinary recovery succeeds. In-flight cancel/close keeps its owner
until return and destroys it once. A first run failed only its 30 s arrival wait.
The separately reviewed 120 s
arrival bound observed the checkpoint after 34.520 s without changing functional
assertions or the 30 s barrier/45 s completion bounds. The failed run is preserved.
The [Android evidence](benchmarks/2026-11-03-local/engine-lifetime-android-results.json)
keeps the fault artifacts separate and records the targeted scope. The scoped
ownership verification is complete.

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
