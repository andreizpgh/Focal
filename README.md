<div align="center">
  <img src="docs/mark-preview.png" width="80" height="80" alt="Focal">
  <h1>Focal</h1>
  <p>Offline voice notes for Android. Speak, and it’s written down.</p>
  <p>
    <strong><a href="https://github.com/andreizpgh/Focal/releases/download/v0.5.0/Focal-0.5.0.apk">Download Focal 0.5.0</a></strong>
    &nbsp; · &nbsp;
    <a href="docs/install.md">Install guide</a>
    &nbsp; · &nbsp;
    <a href="https://github.com/andreizpgh/Focal/releases/tag/v0.5.0">Release notes</a>
  </p>
  <sub>Android 8.0+ · ARM64 · ~0.7 GB · No internet permission · Testing release</sub>
</div>

<br>

<p align="center">
  <img src="docs/screenshots/library-light.png" width="180" alt="Library in light theme: tonal paper stage card for the latest note with one-tap Copy, notes grouped by day, and a centered Record dock">
  &nbsp;
  <img src="docs/screenshots/recording-live.png" width="180" alt="Full-screen recording screen in dark instrument material: live waveform, 4:16 timer, streaming transcript, and Pause and Finish controls">
  &nbsp;
  <img src="docs/screenshots/note-playing-dark.png" width="180" alt="Note playback in dark theme: spoken word highlighted, Review pill in the top bar, scrubber with 1× speed control, and Continue recording and Copy controls">
  &nbsp;
  <img src="docs/screenshots/word-review-light.png" width="180" alt="In-place word review in light theme: 1 of 2 stepper in the top bar, unclear word selected directly in the paragraph, and Listen, suggestion, and Keep controls above the keyboard">
</p>

Tap **Record** and talk. Text appears in paragraphs after each pause while you keep speaking. Tap **Finish**, fix or confirm any unclear word in place, and **Copy**.

- **Offline by construction.** [Parakeet Ultra INT8](docs/speech-quality.md) runs locally through `sherpa-onnx`. Focal declares no `INTERNET` permission, accounts, or analytics — audio and transcripts never leave your phone.
- **Built for long dictation.** Recording continues with the screen off or in the background, recovers transactionally from interruptions, and lets you continue recording into any finished note.
- **Automatic language detection.** Handles 25 European languages without a language picker; accuracy varies by language and recording ([multilingual results](docs/benchmarks/2026-11-19-multilingual/README.md)).
- **Edit and review in place.** Tap any word or **Review · N** to edit directly on the page with the keyboard open right away — listen to the clip, reuse an earlier edit from the same note in one tap, or confirm with **Keep** (all undoable).
- **Audio stays attached until you remove it.** Play back at **1× / 1.5× / 2×** with live word highlighting and paragraph timestamps, or delete audio per note or in bulk while keeping clean text.

### Status

Focal 0.5.0 is a **testing pre-release** (installs cleanly over 0.4.0 and 0.3.2). Automated JVM, lint, and Android 16 emulator suites pass with the bundled speech model; real-device ARM phone performance, thermal behavior, battery life, and natural dictation accuracy remain open in the [phone validation checklist](docs/testing.md#phone-validation). Check important transcripts against the original audio.

---

<p align="center">
  <a href="docs/install.md">Install</a> ·
  <a href="docs/architecture.md">Architecture</a> ·
  <a href="docs/design.md">Design</a> ·
  <a href="docs/speech-quality.md">Speech quality</a> ·
  <a href="docs/review-and-research.md">Research</a> ·
  <a href="docs/testing.md">Testing</a> ·
  <a href="CONTRIBUTING.md">Contributing</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="LICENSE">MIT License</a> (<a href="THIRD_PARTY_NOTICES.md">Notices</a>)
</p>
