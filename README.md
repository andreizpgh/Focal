<div align="center">
  <img src="docs/mark-preview.png" width="80" height="80" alt="Focal">
  <h1>Focal</h1>
  <p>Offline voice notes for Android. Speak, and it’s written down.</p>
  <p>
    <strong><a href="https://github.com/andreizpgh/Focal/releases/download/v0.4.0/Focal-0.4.0.apk">Download Focal 0.4.0</a></strong>
    &nbsp; · &nbsp;
    <a href="docs/install.md">Install guide</a>
    &nbsp; · &nbsp;
    <a href="https://github.com/andreizpgh/Focal/releases/tag/v0.4.0">Release notes</a>
  </p>
  <sub>Android 8.0+ · ARM64 · about 0.7 GB · testing release</sub>
</div>

<br>

<p align="center">
  <img src="docs/screenshots/library-light.png" width="180" alt="The library in the light theme: the latest note on a dark card with its length and a Copy button, earlier notes listed below by day, and the Record button">
  &nbsp;
  <img src="docs/screenshots/recording-live.png" width="180" alt="The full-screen recording view: a waveform, the elapsed time 4:16, the text transcribed so far with the newest paragraph brightest, and Pause and Finish buttons">
  &nbsp;
  <img src="docs/screenshots/note-playing-dark.png" width="180" alt="A finished note in the dark theme playing back its recording: the current paragraph is bright, the spoken word is highlighted, and a progress bar sits above the Copy button">
  &nbsp;
  <img src="docs/screenshots/word-review-light.png" width="180" alt="Reviewing “harbur” in a transcript. A previous edit in the same note offers the spelling “harbour”.">
</p>

Tap **Record** and talk. Text appears after each pause while you keep speaking.
Tap **Finish**, then **Copy**.

- **Made for long dictation.** A ten-second thought or an hour-long walk. Pause,
  leave the app or lock the phone; recording continues and the text catches up.
- **Offline by design.** Speech recognition runs on the phone. No account, no
  cloud, and no Internet permission.
- **Automatic language detection.** Focal has been tested on short recordings in
  all 25 supported European languages. Accuracy varies by language and recording.
  [Results by language](docs/benchmarks/2026-11-19-multilingual/README.md)
- **Review while reading.** Tap a word or sweep a phrase to hear the audio,
  fix punctuation, or split and join paragraphs. **Review** takes you through
  underlined phrases. Choose a spelling from the audio or reuse an earlier edit
  in the same note. **Apply** saves it and offers Undo.
- **Add to a note later.** Open a note and record on: the new text follows the
  old in the same note.
- **Edit like a document.** Paragraphs are edited where they are, with undo, find
  and replace, and a way back to a paragraph’s original text.
- **Find your place.** Speech is set in short paragraphs, each with the time it
  starts at in the recording; tap the time to hear it from there.
- **Keep the receipt.** The original audio stays with the note until you remove
  it, from one note, several, or all of them. Copy gives you clean paragraphs,
  nothing else.

You can install 0.4.0 over 0.3.2. On an Android emulator the update preserved
a note's custom title, paragraphs and earlier spelling correction.

**Status:** a testing release. It has been checked on Android emulators, not yet
on phones: speed, memory, battery, heat, long background recordings and natural
(not read) speech still need [phone validation](docs/testing.md#phone-validation).
Check important text against the audio.

---

[Install guide](docs/install.md) ·
[Review and research](docs/review-and-research.md) ·
[Speech quality](docs/speech-quality.md) ·
[Architecture](docs/architecture.md) ·
[Design](docs/design.md) ·
[Testing](docs/testing.md) ·
[Contributing](CONTRIBUTING.md) ·
[Changelog](CHANGELOG.md) ·
[MIT license](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md)
