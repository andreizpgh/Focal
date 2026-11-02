# Device validation

Build and emulator checks are necessary but do not establish real-phone quality,
background reliability, battery consumption, or thermal behavior. Before calling
a build stable, record results for at least one midrange and one older ARM64 phone.

| Scenario | Expected behavior |
| --- | --- |
| First launch, airplane mode | Bundled model installs locally; record without a network |
| English and Russian, 2–5 minutes each | Check actual transcription and missed/extra words against the audio |
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

Record device/SoC, Android version, model provenance, recording duration, wall-clock
transcription time, peak memory, battery delta, thermal status, and any omissions.
Do not infer these measurements from desktop or emulator results.
