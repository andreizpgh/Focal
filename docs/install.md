# Install Focal

**[Download Focal-0.5.1.apk](https://github.com/andreizpgh/Focal/releases/download/v0.5.1/Focal-0.5.1.apk)**

Focal is distributed as a single APK on its [release page](https://github.com/andreizpgh/Focal/releases/tag/v0.5.1) under **Assets** — no GitHub account, Android Studio, or ZIP extraction required. Earlier versions stay available on their own release pages.

## Requirements

- **Android 8.0+** on an **ARM64** phone.
- **~0.7 GB download** — the speech model is bundled inside the APK; Focal never downloads anything after install.
- **~0.9 GB free storage** on first launch — Focal copies the speech model once into private app storage (requiring model size + 200 MB headroom).
- **~115 MB per hour** for saved recordings until you remove audio from a note (keeping its text).

## Install

1. Open the download link on your phone.
2. Open **Focal-0.5.1.apk** from your browser downloads or the Files app.
3. If prompted, allow **Install unknown apps** for that browser or file manager, go back, and tap **Install**. You can disable that permission afterwards.

Open Focal and tap **Record**. Grant microphone access (and, on Android 13+, notifications so Pause and Finish stay reachable from the shade). Speak normally; paragraphs appear after each pause. Tap **Finish**, review any flagged word if needed, and **Copy**.

> On first launch, the recording screen may show **Preparing speech model** while the bundled weights unpack in the background. You can start recording immediately — transcription catches up automatically once the copy finishes.

## Updating from an earlier version

- **From 0.5.0, 0.4.0, or 0.3.2:** install **Focal-0.5.1.apk** directly in place. Same application ID, signing certificate, and SQLite schema — all notes, custom titles, edits, review confirmations, and recordings are preserved.

<details>
<summary>Upgrading from older testing builds (0.3.1 down to Alpha 1)</summary>

All builds from Alpha 2 onward share the same development signing certificate (verified on every release):

- **Over 0.3.1:** installs in place with no note conversion.
- **Over 0.3.0:** unedited notes are split once into shorter paragraphs under the current formatter rules; manual edits are untouched.
- **Over 0.2.0:** automatic titles from long first sentences are re-derived once; custom titles stay.
- **Over Alpha 2 – Alpha 9:** notes migrate automatically to the current schema (text, edits, custom titles, and audio are kept; legacy Whisper files are cleaned up; language selection is replaced by automatic detection). Migrated notes lack per-word timestamps, so playback starts at the tapped paragraph.
- **Over Alpha 1:** signed with a different cloud key. Copy out any text you want to keep before uninstalling Alpha 1.

</details>

## Notes while testing

- Recording continues with the screen off or in the background; aggressive OEM battery savers may still restrict long background sessions.
- Calls, another app taking the microphone, or low storage pause recording automatically and tell you why.
- On Android 15+, the system may pause very long background transcriptions — reopening Focal resumes them automatically.
- Quiet speech and very short one-word replies are the main error sources; verify important transcripts against the original audio.

Physical phone performance, battery draw, and thermal behavior have not been formally profiled yet — see the [phone validation checklist](testing.md#phone-validation).
