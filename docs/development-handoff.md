# Sessions redesign: continuation checkpoint

Branch: `codex/sessions-stt-handoff`. Baseline before the redesign:
`14be1ae6afe15e5229982d9e8cfe63d0f276e393`.

The redesign and reliability work are implemented and reviewed. The branch is a
development checkpoint, not a verified distribution release. The public alpha
APK is unchanged. Recognition quality and ARM phone performance remain unmeasured.

## Product direction

Keep the selected **D / Sessions** layout: a dated recording library, white/light
canvas, black primary actions, compact speech-language selection, timed passages
and persistent original-audio controls. No app name in the main header or filler
copy under Record. Preserve light/dark/system support and accessible controls.

The current flat quotation-pair logo is provisional. Identity exploration is open
again: seek a distinctive connection to Focal's name and voice capture, compatible
with the selected minimal UI. Do not treat the existing mark as approved or apply
an exploratory replacement before selection. Prioritize an immediately readable
name, coherent proportions, a related compact mark and fit with the quiet product.
Space, depth, color or material may support that identity; neither flat-only
constraints nor elaborate physical metaphors are mandatory. Product/engine work
may proceed independently; keep pending identity work confined to brand assets
and exploration files.

The interface is English by default. Speech support must be evaluated for English,
Russian, German, French, Spanish, Italian and Portuguese. Prioritize Russian
dictation evidence without treating the other languages as untested fallbacks.

## What is implemented

- Sessions navigation, Unicode search across every passage, timed review/editing,
  original-audio playback and seeking, copy/share, rename, durable draft handling,
  retry states and independent recording/model readiness.
- Capture writes PCM directly to disk. A bounded scalar metadata channel keeps
  SQLite writes off the audio thread; pause/stop/interruption preserve partial
  reads. Metadata overflow stops visibly while keeping source audio recoverable.
- Recovery fills only uncovered audio ranges. Completed passages and manual edits
  survive retries. Deletion has a durable journal and additive database migration.
- JNI shared ownership protects decode/cancel/close; cancelled partial output is
  never committed. Bounded trusted context, timestamp-aware overlap handling and
  DC-offset-resistant speech gating replace the earlier heuristics.
- AudioTrack playback reports played position rather than submitted bytes. Player
  replacement, focus, pause/seek and model-import failure/cancellation are covered.
- Independent reviews covered capture, storage, JNI, playback, app state, UI and
  the host evaluation tooling. Confirmed findings have targeted regressions.

The decoder still validates the multilingual **base** architecture. A successful
host result with another model does not make it loadable in Android automatically;
change model validation, packaging/import and resource policy intentionally if the
evidence supports a replacement.

## Verified state

See [quality report](quality-report.md) for the evidence and its limits:

- 30 JVM tests and 53 distinct Android instrumentation tests pass. Five checks
  requiring real model weights are skipped. Android tests used an Android 10
  x86-64 emulator and synthetic PCM, not measured speech recognition.
- Final repeats passed all 11 service tests and 5 relevant UI scenarios after
  notification cleanup and playback-control polish.
- 15 correction-scorer host tests pass. Debug/test APKs, debug/release lint and
  release R8 compilation pass; packaged native libraries have 16 KB LOAD alignment.
- Full-history and source secret scans passed before publication. No Internet
  permission, models, recordings, local SDK paths, signing keys or device logs
  belong in this branch.

Native screenshots in [interface preview](interface-preview.md) are unretouched
captures with synthetic notes. The owner-private gallery and its native route are
linked there. Static deployment source is already saved separately in Sites;
preserve its private audience. The offline prototype pack remains under
`design/explorations/`.

## Continue in the published cloud environment

Use the existing checkout and this branch; no checkpoint archive is necessary.
Read `AGENTS.md`, `CONTRIBUTING.md`, [architecture](architecture.md),
[device validation](device-validation.md) and
[speech-quality experiments](stt-quality-experiments.md).

Initialize the pinned whisper.cpp submodule and use the documented Android tools.
The preceding runtime retained its old network policy after environment settings
were published: HF metadata requests still returned CONNECT 403. Check the actual
network policy of the new runtime and try a bounded metadata request first; do not
assume that a prior blocked result or newly saved configuration applies to it.

No model or corpus bytes were downloaded during this implementation. The shared
transfer ceiling is **1,000,000,000 bytes**, including retries. Fetch metadata,
pin immutable revisions and hashes, and download only selected licensed samples.
Do not fetch whole corpus shards. Do not spend the budget on every candidate
before evaluating whether the earlier comparisons justify the next one.

## Remaining sequence

1. Obtain verified baseline weights and bounded public audio, record provenance,
   and run the five skipped JNI/workflow checks with actual inference.
2. Measure paired WER/CER on all seven languages. Cover names, numbers, negation,
   quiet/noisy speech, silence hallucinations and actual gate boundaries. Distinguish
   host isolated-clip results from continuous Android dictation and phone latency.
3. Compare base Q5, base Q8 and small Q5 under controlled conditions. Evaluate a
   selective acoustic second pass and context/gate ablations before adding an LLM.
   Use `scripts/evaluate-stt.py` and the staged experiment plan; retain reproducible
   manifests and aggregate results without committing speech or transcripts.
4. If acoustic results warrant it, evaluate constrained local correction with
   `scripts/score-stt-corrections.py`. A lower WER is insufficient if corrections
   introduce wrong names, amounts or negations. Require held-out improvement,
   adjudicated accepted-span precision and acceptable phone cost. Model confidence
   is not evidence that a replacement was spoken. Keep inconclusive variants off.
5. Any shipped correction must preserve immutable original ASR/audio, manual edits
   and a consistent visible/copy revision. Show a restrained changed-span underline;
   tapping reveals original → correction and Undo. Avoid extra steps in normal use.
   The current mutable passage text is insufficient provenance: migrate the data
   model before enabling automatic corrections, as specified in the experiment doc.
6. Validate on midrange and older ARM64 phones, including a 4 GB device and current
   Android foreground rules: screen-off recording, calls, recovery, TalkBack,
   cold/warm latency, memory, battery and thermals. Emulator timing cannot establish
   any of these phone-performance claims.
7. Re-review changed layers, run required build/tests and applicable regressions,
   then verify the exact bundled, stably signed release artifact before publication.
   Do not replace existing release assets or call the product finished while these
   gates remain unresolved.

Use evidence to justify larger refactors or model changes. Keep inference and
correction off the capture thread, memory and queues bounded, and the original
audio durable throughout the work.
