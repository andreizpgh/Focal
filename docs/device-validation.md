# Device validation

Build and emulator checks are necessary but do not establish real-phone quality,
background reliability, battery consumption, or thermal behavior. Before calling
a build stable, record results for at least one midrange and one older ARM64 phone.

Use a dedicated, hardware-accelerated emulator on the user's local computer for
repeatable app QA and longer synthetic recording sessions. Follow the
[local emulator QA guide](local-emulator-qa.md) for the runner, prerequisites,
private result manifest and manual long-session matrix. Prefer that setup over
slow shared cloud software emulation for sustained runs. The automated suite does
not itself perform a 30-minute capture, and a faster emulator does not supply ARM64
phone, battery, thermal, microphone or OEM-background evidence.

Record the exact commit and APK/model hashes for each run. Written regressions,
host/core test results and successful builds or lint are separate evidence from a
completed Android instrumentation run; track current results in the
[quality report](quality-report.md).

| Scenario | Expected behavior |
| --- | --- |
| First launch, airplane mode | Bundled model installs locally; record without a network |
| Each supported language, 2–5 minutes each | English, Russian, German, French, Spanish, Italian and Portuguese: compare missed/extra words against audio |
| 60–90 minute monologue with 1–5 minute pauses | Capture persists; no silence-only hallucinations; queue stays bounded in RAM |
| Screen off for 30 minutes; switch apps; swipe task away | Recording continues with an ongoing notification, subject to OEM restrictions |
| Pause/resume in app and notification | Paused audio is excluded; later speech is captured |
| Rapid Record, playback, Continue transcription and delete/remove-audio actions | Only compatible operations proceed; busy actions report a reason, capture excludes playback, and source audio is preserved |
| Incoming call or another microphone user | No silent claim of continued capture; saved audio remains available |
| Wired/Bluetooth headphones disconnect during playback | Playback pauses without unexpectedly continuing on a speaker; resuming retains the played position |
| Force-stop/reboot during speech | Recording ends; reopen offers recovery of saved work |
| Near-full storage | Capture stops with a useful message; existing text/audio remain |
| Battery saver / thermal throttling | Capture remains responsive; inference slows or waits |
| Edit a marked passage; listen; save and next | Changes persist; next uncertain passage opens without replaying the sheet entrance; playback stops on dismissal |
| Copy and share Russian text | Exact edited Unicode text and paragraph breaks are preserved |
| Oversized paste, legacy large passage and oversized Copy/Share | Oversized input is visibly rejected with the preceding draft intact; legacy text opens in selectable read-only pages, survives rotation and remains unchanged; transfer refusal preserves saved text and the existing clipboard |
| Remove audio / delete note | Removal matches confirmation; text is retained only for remove-audio |
| Large fonts, TalkBack, dark theme and reduced motion | Controls and legacy-text pages remain reachable, labeled and legible; verify actual TalkBack traversal and selection, not only Compose semantics; disabled animations remain static |
| Decoder failure while recording | Microphone capture continues; finish offers recovery of pending audio |
| Continue after failed live finalization | Durable writing ranges and remaining audio are recovered without reopening the microphone or replacing completed/manual text |
| Language selector above Record | All seven languages are reachable and persist for the next note |
| Search + review filter; open a result, rotate and return | Matching notes open correctly; library filter and scroll position survive, the keyboard stays dismissed on return, and clearing search restores the list |
| Invalid/cancelled replacement model import | Invalid files report an error; failure or cancellation preserves the previously installed model |

Record device/SoC, Android version, model provenance, recording duration, wall-clock
transcription time, peak memory, battery delta, thermal status, and any omissions.
Do not infer these measurements from desktop or emulator results.

## Repeatable decoder comparison

Use the same consented, manually transcribed clips for both runs. Include ordinary
dictation, quiet speech, names, numbers, pauses and noisy rooms for every language.
Keep private audio, transcripts and local manifests outside Git. A manifest is a
JSON array with `audio` (relative WAV path), `language` and `text` (reference).
Use mono 16 kHz PCM16 clips of at most 30 seconds. Follow the manifest, split,
hashing and probe instructions in [host speech probes](stt-benchmark-tools.md).
For example, measure the current bundled-model control with:

```sh
python3 scripts/evaluate-stt.py /private/corpus.json \
  --cli build/whisper-host/bin/stt-probe \
  --model app/src/main/assets/ggml-base-q5_1.bin \
  --require-all-languages --threads 4 --beam-size 3 --audio-context 0 \
  --output build/stt/control.json
```

Repeat with the candidate model or one intentional setting change and a different
output path, using identical selected audio and references. The evaluator's
`--baseline` option is a historical greedy/two-thread/word-alignment configuration;
it is not the current app control and is unsupported by the confidence probe.
Keep model selection on development data separate from a held-out comparison.

Aggregate reports include WER, CER, wall-time/audio-time ratios and identities for
the corpus, settings, evaluator, model and executable. Each clip has a bounded
decoder timeout. Cold host time includes model loading and excludes capture,
context between chunks, queue waits and Android throttling; it is not phone
latency. Use the actual [speech gate](speech-gate-replay.md) and
[context/boundary replay](transcript-replay.md) for continuous-source experiments,
while retaining their distinction from Android capture. Also measure the complete
Android path: first-text latency, time from Finish to completion, boundary repeats,
omissions, CPU temperature and battery usage on actual phones. A lower aggregate
error rate alone does not establish every language or critical word improved.

For the next public-corpus evaluation, download a small, documented subset rather
than the full datasets. Keep combined model/corpus downloads below 1 GB. Report
read-speech results separately from spontaneous dictation and noisy recordings.
Check the active environment's network policy before requesting metadata or
downloads. Availability and measured speech results belong in the dated quality
report; do not infer either from an earlier environment or from a passing build.
