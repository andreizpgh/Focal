# Install Focal

**[Download Focal-0.5.3.apk](https://github.com/andreizpgh/Focal/releases/download/v0.5.3/Focal-0.5.3.apk)**

Focal is distributed as a single APK on the [release page](https://github.com/andreizpgh/Focal/releases/tag/v0.5.3) under **Assets**. You don't need a GitHub account, Android Studio, or a ZIP archive. Older releases remain on their own release pages.

## Requirements

- Android 8.0 or newer on an ARM64 phone.
- About 0.7 GB for the download. The speech model is bundled inside the APK, and nothing is downloaded afterwards.
- About 0.9 GB of free storage on first launch. Focal copies the model once into private app storage (model size plus 200 MB headroom).
- About 115 MB per hour for saved recordings, until you remove audio from a note while keeping its text.

## Install

1. Open the download link on your phone.
2. Open **Focal-0.5.3.apk** from your browser downloads or the Files app.
3. If Android asks, allow **Install unknown apps** for that browser or file manager, go back, and tap **Install**. You can turn that permission off afterwards.

Open Focal and tap **Record**. Grant microphone access (and, on Android 13+, notifications so Pause and Finish stay available in the notification shade). Talk normally; paragraphs appear after pauses. Tap **Finish**, fix any errors if needed, and **Copy**.

On first launch, the recording screen may say **Preparing speech model** while the weights unpack in the background. You can start recording right away; transcription catches up once unpacking finishes.

## Updating

- **From 0.5.2, 0.5.1, 0.5.0, 0.4.0, or 0.3.2:** install **Focal-0.5.3.apk** directly over the existing app. It uses the same application ID, signing certificate, and SQLite schema, so all notes, titles, edits, review confirmations, and recordings stay intact.

<details>
<summary>Upgrading from older builds (0.3.1 down to Alpha 1)</summary>

Builds from Alpha 2 onward share the same development signing certificate (checked before every release):

- **Over 0.3.1:** installs in place with no conversion.
- **Over 0.3.0:** unedited notes are split once into shorter paragraphs under the current rules; edited paragraphs stay unchanged.
- **Over 0.2.0:** automatic titles from long first sentences are re-derived once; custom titles stay unchanged.
- **Over Alpha 2 to Alpha 9:** notes migrate automatically to the current format. Text, edits, custom titles, and audio are kept, legacy Whisper files are removed, and language selection is replaced by automatic detection. Migrated notes have no word timestamps, so playback starts at the tapped paragraph.
- **Over Alpha 1:** signed with a different cloud key. Copy out any text you want to keep before uninstalling Alpha 1.

</details>

## Known caveats while testing

- Recording continues with the screen off or in the background, though some phone manufacturers aggressively restrict long background services.
- Calls, another app taking the microphone, or low storage pause recording automatically and tell you why.
- On Android 15+, the system may pause very long background transcriptions. Reopening Focal continues them.
- Quiet speech and very short one-word replies are the main sources of recognition errors. Check important notes against the audio.

Phone performance, battery draw, and thermal behavior have not been formally profiled yet. See [testing](testing.md#phone-validation).
