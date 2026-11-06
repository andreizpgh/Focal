# Changelog

All notable changes to Focal are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). Every release so far is a testing
pre-release.

## [0.2.0] - 2026-11-06

A rebuilt app around one offline speech engine and a new interface. Installing
it over Alpha 9 kept every note in emulator tests. Alpha 2–8 share Alpha 9's
signing key, and migration tests cover their older database formats.

### Added

- Text appears after each pause while you record, instead of after the first 35
  seconds of audio.
- Tap a word to play the recording from just before it; the playing word is
  highlighted and the text follows playback.
- Edit in place: paragraphs become editable where they are, Enter splits and
  Backspace joins, with undo and redo for the session and **Restore original**
  for edited paragraphs. Notes from earlier alphas keep their edits, but where
  their original text was not saved there is nothing to restore.
- Find and replace within a note, with one-step Undo.
- Words the recognizer was unsure of get a faint dotted underline (provisional).
- Automatic paragraphs and a title taken from the first sentence.
- Undo after **Delete** and **Remove audio**.
- Recording pauses by itself for a phone call, another app taking the microphone
  or nearly full storage, says why, and resumes when you tap **Resume**.
- Transcription interrupted by Android or a restart continues the next time you
  open Focal.
- A **New recording** launcher shortcut, and settings reachable from App info.
- Search opens the note at the matching words.

### Changed

- A new interface: the latest note or the recording in progress sits on a card at
  the top of the library and grows into a full-screen recording view; notes read
  as documents; settings are one swipe beside the library.
- Parakeet Ultra transcribes every note, with the language detected automatically.
- Notifications show why a recording paused, transcription progress and
  **Note ready**.
- The speech model is copied once in the background on first launch; recording
  works meanwhile.
- The APK is ARM64 only and about 0.7 GB, down from 816 MB.

### Removed

- The Whisper engine and its model, and the speech-language setting.
- The **Transcribe with Ultra** preview, the background-sound check and the
  "to review" cues. Text that was excluded with the background-sound check stays
  hidden.

### Known limitations

- Not yet validated on phones: speed, memory, heat, battery and long background
  recordings.
- Quality is measured on read speech only. Quiet speech can be missed, and very
  short replies can come out in the wrong language.

## [0.1.0-alpha.9] - 2026-11-05

Parakeet Ultra became the engine for new recordings, with automatic language
detection. Existing Whisper notes kept their engine.

## [0.1.0-alpha.8] - 2026-11-05

Parakeet Ultra bundled as an optional, copy-only transcription of saved
recordings up to three minutes.

## [0.1.0-alpha.7] - 2026-11-05

An optional background-sound check for finished recordings, with reversible
exclusions.

## [0.1.0-alpha.2] to [0.1.0-alpha.6] - 2026-11-04

Interface previews: the Sessions layout, themes, editing, search, rename and
recovery of interrupted recordings.

## [0.1.0-alpha.1] - 2026-11-02

First public alpha: background recording and local transcription with the
bundled Whisper Base model.

[0.2.0]: https://github.com/andreizpgh/Focal/releases/tag/v0.2.0
[0.1.0-alpha.9]: https://github.com/andreizpgh/Focal/releases/tag/stt-alpha-9
[0.1.0-alpha.8]: https://github.com/andreizpgh/Focal/releases/tag/stt-alpha-8
[0.1.0-alpha.7]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-7
[0.1.0-alpha.2]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-2
[0.1.0-alpha.6]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-6
[0.1.0-alpha.1]: https://github.com/andreizpgh/Focal/releases/tag/v0.1.0-alpha.1
