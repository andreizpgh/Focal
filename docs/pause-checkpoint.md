# Paused local session · 2026-11-03

The owner explicitly resumed work on 2026-11-04. The text below preserves the
historical pause state. On resumption all saved source hashes matched; the
rejected Android VAD candidate was archived and removed from the working tree,
including its packaged assets, while the independent search fixes were retained.
Final UI and full-suite verification are in progress.

The owner explicitly paused work to free the laptop. Do not resume builds,
emulator, inference or agent work until the owner asks to continue. This is an
unfinished development checkpoint, not whole-app or STT acceptance.

## Repository and saved work

- Existing checkout and branch: `codex/sessions-stt-handoff`.
- Last committed and pushed HEAD: `56d2d13` (preserved-ASR research evidence).
  Draft PR #1 is updated through this commit. Full-history Gitleaks passed for
  62 commits. No new Actions run, model download or APK release occurred.
- The working tree intentionally retains uncommitted search fixes and the
  **rejected pre-ASR Android VAD candidate**. Do not stage everything or treat the
  latter as accepted production code.
- The rejected candidate's 24 non-UI tracked files, six untracked source/tool
  files and two ignored model/provenance assets are copied and hash-checked in
  `build/local-diagnostics/rejected-vad-android-archive/`. They have **not** been
  removed from the working tree. The shared `FocalApp.kt` and `InterfaceTest.kt`
  still contain both search changes and candidate readiness/dialog changes.
- A complete private pause snapshot is in
  `build/local-diagnostics/pause-20261103/`: HEAD, status, binary-capable diff,
  changed/untracked file copies and SHA-256 manifest. It is a recovery copy, not
  a commit or a substitute for checking the current working tree.

## Emulator and processes

The dedicated `focal_local_qa` AVD was stopped with `adb emu kill`, after stopping
the two Focal packages, restoring font scale 1.0 and portrait rotation, and syncing
storage. Data was not wiped. Other projects' devices and data were untouched.
The Focal Gradle daemon was stopped; inference and agent tasks are stopped.

The AVD is in ignored `build/avd`, so a later launch must set
`ANDROID_AVD_HOME="$PWD/build/avd"` from this checkout. Use the existing AVD and
the configuration in `local-emulator-qa.md`: KVM `-accel on`, host GLES, Vulkan
disabled, two virtual CPUs, requested 2048 MiB/effective 2560 MiB. KVM was verified
through the actual VM/vCPU descriptors; disabling Vulkan did not disable KVM.
The last running instance had been restarted after an unexplained earlier exit;
no GPU/OOM cause was established. No audio input/output is enabled in this setup.

## Search UI: implemented, final verification pending

Observed problems and current fixes:

1. At 200% font in landscape the IME clipped the search field. Measured insets and
   a compact header preserve the same field composition and keep it above the IME.
2. Rotation lost the caret and input intent. A bounded saved `TextFieldValue`
   preserves selection; the 200-unit query cap retains complete UTF-16 pairs.
3. After Back hid the IME, actual Home → Focal reopened it. Logs show no repeated
   app `show()` call: Android restored the still-focused editor. The current
   narrow ON_PAUSE fix dismisses search input only when a previously shown IME
   is already hidden; active IME/rotation input remains eligible for restoration.

The strengthened test includes actual Home/task return with same-Activity,
focus, IME, exact text and selection assertions, both active and manually hidden
input; direct Back → Home precedes any artificial lifecycle transition. The
previous APK fails this precise scenario. Existing oversized-editor assertions
and the rename test are unchanged. Temporary diagnostic logging/catches have
been removed from source.

Latest fixed candidate APK:
`6362c74148823839d4cc1b4e609bac54ce5b6b0904286efdf27d20ed06c9abe2`.
Its six-test run at **global font scale 2.0** passed the four search tests and
the strict oversized-editor test, but failed the rename test's final wait for
an immediately composed passage node. Targeted synthetic observation confirmed
the exact title and passage were saved; the 120-unit title occupied the viewport,
and scrolling displayed the passage. The failure remains recorded, not relabeled
as a passing run. Repeat the unchanged six tests at the documented font scale
1.0, then manually repeat search Home/rotation at 200% font on the final build.

Detailed private UI evidence and exact source identities:
`build/local-diagnostics/search-home-fix/PAUSED.md` and
`paused-source-hashes.json`. Earlier manual candidate4 proved the remaining Home
failure; do not cite those screenshots as proof of the final Home fix. The final
test APK left in normal build outputs contains temporary diagnostic code from
the last investigation, while source is cleaned: **rebuild before testing**.

Before a clean full production run, preserve the shared UI files and remove only
the rejected VAD integration coherently. Keep all accepted search/Rename fixes,
the eight original mandatory real-model checks and standalone research overlays.
Move the ignored Silero asset and its provenance out of `app/src/main/assets`,
otherwise Gradle packages them even after source restoration. Do not use root
`gradlew clean`: root `build/` contains the private research evidence. Inspect
the rebuilt APK and then use `scripts/qa-local.py --release-checks` on a frozen
source checkpoint. A new complete clean run has **not** happened; the last one
remains HEAD `129ee02`, 82 Android tests, eight real-model tests and 36 core tests.

## STT research at the pause

The published post-ASR experiment preserves all 185 raw decoder outputs and
improves emitted WER only in EN/ES/IT. It does not establish all-seven quality or
speed. Future automatic removal of already-produced ASR requires immutable
original ASR, visible provenance, Undo and phone resource acceptance. It is not
enabled in Android. See the published benchmark report for its independent review.

The subsequent fixed GREEDY1/corrected-SOT/presence **control screen completed
and rejected**: seven sessions, 56 VAD calls, 28 ASR calls and 28 skips; 15 strict
positive failures, zero noise failures, seven exact positive outputs and three
newly empty positives. Textual review found lost previously correct French and
Portuguese negations, another German negation loss, and an Italian time-component
error despite passing aggregate metrics. Four cosmetic tie failures remain
separately counted. Do not attribute all errors to sampling alone: corrected SOT
also differs from the original production comparator.

Private directory: `build/stt/greedy-positive-screen/`.

- Launch freeze: `33679e1ba604d6b12c89c942d8e9298150e368ea24360dfa9137c6a0beb55c2e`.
- Result binding: `7b6447b2ce1efe2eaaa71a76582f6c4dbea0f219c0aa78a623ba6c6827d52faa`.
- Prelaunch independent review passed 1762 input hashes, 11 dependencies and
  the binding of 23 meaningful model-free checks. The completed duration of
  100.268 seconds is contended diagnostic time, not comparative speed.
- Independent **postrun review is unfinished**. Resume by verifying the 1762
  inputs, 255 outputs, 11 dependencies, native metadata/parity, score recomputation,
  public export and critical-review bindings. No final postrun PASS was issued.
- Private export: `greedy-positive-screen-postanalysis/greedy-control-public-results.json`
  under `build/stt`, SHA
  `ef42a0592d46e3b789d5d1f547112030e951eb868588842d1832e3325cff6ae3`.
  Critical aggregate SHA:
  `1a26b4c2a6071cf32771184c935ee10f66f4726047bc1dadef7ed85b0f24ceca`.
  Neither has been copied to public docs or committed yet.

No full DEV/framing/threshold-retuning follow-up is authorized by that failed
screen. All 42 fresh held-out clips remain unopened. Seven-language quality,
controlled whole-pipeline speed and phone gates remain unresolved. No new model,
audio, transcript or raw log should be published.

The download ledger is unchanged: 879,645,181 of 1,000,000,000 bytes reserved,
including 61,000,000 for future CI; 120,354,819 additionally unreserved. Keep the
original 645,592,677-byte prefix and count retries. The phone will be connected
later with the owner present; retain the plan in `device-validation.md`.
