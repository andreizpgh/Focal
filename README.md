<div align="center">
  <img src="docs/mark-preview.png" width="80" height="80" alt="Focal">
  <h1>Focal</h1>
  <p>Offline voice notes for Android. Speak, and it’s written down.</p>
  <p>
    <strong><a href="https://github.com/andreizpgh/Focal/releases/download/v0.2.0/Focal-0.2.0.apk">Download Focal 0.2.0</a></strong>
    &nbsp; · &nbsp;
    <a href="docs/install.md">Install guide</a>
    &nbsp; · &nbsp;
    <a href="https://github.com/andreizpgh/Focal/releases/tag/v0.2.0">Release notes</a>
  </p>
  <sub>Android 8.0+ · ARM64 · about 0.7 GB · testing release</sub>
</div>

<br>

<!--
  TODO(release): the four screenshots below are captured from the final 0.2.0 build
  (unretouched, synthetic notes, light theme) and committed as
  docs/screenshots/home.png, recording.png, note.png and edit.png. Keep these
  names, or update the paths and alt text here.
-->
<p align="center">
  <img src="docs/screenshots/home.png" width="23%" alt="The library: the latest note on a dark card at the top, earlier notes grouped by day, Search and Record at the bottom">
  <img src="docs/screenshots/recording.png" width="23%" alt="Recording: the live wave, a large timer and the transcript appearing after each pause, with Pause and Finish">
  <img src="docs/screenshots/note.png" width="23%" alt="A finished note set as a document: title, date and paragraphs, with Play and Copy at the bottom">
  <img src="docs/screenshots/edit.png" width="23%" alt="Editing a paragraph in place, with Edited · Restore original under it and the keyboard open">
</p>
<p align="center"><sub>Unretouched captures of synthetic notes.</sub></p>

Tap **Record** and talk. Text appears after each pause while you keep speaking.
Tap **Finish**, then **Copy**.

- **Made for long dictation.** A ten-second thought or an hour-long walk. Pause,
  leave the app or lock the phone; recording continues and the text catches up.
- **Offline by design.** Speech recognition runs on the phone. No account, no
  cloud, and no Internet permission.
- **No language picker.** The speech model detects the language on its own. It is
  published for 25 European languages; Focal has measured seven on read speech:
  English, Russian, German, French, Spanish, Italian and Portuguese.
- **Hear any word.** Tap a word to play the recording from just before it. Words
  the recognizer was unsure of carry a faint dotted underline.
- **Fix it in place.** Edit paragraphs where they are, find and replace across a
  note, and restore any paragraph’s original text.
- **Keep the receipt.** The original audio stays with the note until you remove
  it. Copy gives you clean paragraphs, nothing else.

Version 0.2.0 installs over Alpha 9 and keeps your notes.

**Status:** a testing release. It has been checked on Android emulators, not yet
on phones: speed, memory, battery, heat, long background recordings and natural
(not read) speech still need [phone validation](docs/testing.md#phone-validation).
Check important transcripts against the audio.

---

[Install guide](docs/install.md) ·
[Speech quality](docs/speech-quality.md) ·
[Architecture](docs/architecture.md) ·
[Design](docs/design.md) ·
[Testing](docs/testing.md) ·
[Contributing](CONTRIBUTING.md) ·
[Changelog](CHANGELOG.md) ·
[MIT license](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md)
