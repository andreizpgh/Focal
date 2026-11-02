# Device validation

Build and emulator checks are necessary but do not establish real-phone quality,
background reliability, battery consumption, or thermal behavior. Before calling
a build stable, record results for at least one midrange and one older ARM64 phone.

| Scenario | Expected behavior |
| --- | --- |
| First launch, airplane mode | Bundled model installs locally; record without a network |
| Each supported language, 2–5 minutes each | English, Russian, German, French, Spanish, Italian and Portuguese: compare missed/extra words against audio |
| 60–90 minute monologue with 1–5 minute pauses | Capture persists; no silence-only hallucinations; queue stays bounded in RAM |
| Screen off for 30 minutes; switch apps; swipe task away | Recording continues with an ongoing notification, subject to OEM restrictions |
| Pause/resume in app and notification | Paused audio is excluded; later speech is captured |
| Incoming call or another microphone user | No silent claim of continued capture; saved audio remains available |
| Force-stop/reboot during speech | Recording ends; reopen offers recovery of saved work |
| Near-full storage | Capture stops with a useful message; existing text/audio remain |
| Battery saver / thermal throttling | Capture remains responsive; inference slows or waits |
| Edit a marked passage; listen; save and next | Changes persist; next uncertain passage opens; playback stops on dismissal |
| Copy and share Russian text | Exact edited Unicode text and paragraph breaks are preserved |
| Remove audio / delete note | Removal matches confirmation; text is retained only for remove-audio |
| Large fonts, TalkBack, dark theme | Controls remain reachable, labeled, and legible |
| Decoder failure while recording | Microphone capture continues; finish offers recovery of pending audio |
| Language selector above Record | All seven languages are reachable and persist for the next note |
| Search + review filter | Matching notes open correctly; clearing search restores the list |

Record device/SoC, Android version, model provenance, recording duration, wall-clock
transcription time, peak memory, battery delta, thermal status, and any omissions.
Do not infer these measurements from desktop or emulator results.

## Repeatable decoder comparison

Use the same consented, manually transcribed clips for both runs. Include ordinary
dictation, quiet speech, names, numbers, pauses and noisy rooms for every language.
Keep private audio, transcripts and local manifests outside Git. A manifest is a
JSON array with `audio` (relative WAV path), `language` and `text` (reference).
Use mono 16 kHz PCM16 clips of at most 30 seconds.

```sh
python3 scripts/evaluate-stt.py /path/to/corpus.json --require-all-languages --baseline --output build/stt-before.json
python3 scripts/evaluate-stt.py /path/to/corpus.json --require-all-languages --output build/stt-after.json
```

The aggregate reports contain word error rate (WER), character error rate (CER),
and wall-time / audio-time ratio per language, along with model and decoder hashes,
thread count and host architecture. Each clip has a bounded decoder timeout. Host time includes model loading
and excludes capture, context between chunks, queue waits and Android throttling;
it is not phone latency. The baseline reproduces greedy decoding, two threads and
word alignment; the new run uses three beams and four threads. Do not declare a
language improved without comparing its measured errors. Also measure the complete
Android path: first-text latency, time from Finish to completion, boundary repeats,
omissions, CPU temperature and battery usage on actual phones.

For the next public-corpus evaluation, download a small, documented subset rather
than the full datasets. Keep combined model/corpus downloads below 1 GB. Report
read-speech results separately from spontaneous dictation and noisy recordings.
Access to model/corpus hosting is currently blocked by the development
environment's network policy; no multilingual WER result is available yet.
