# Changelog

All notable changes to Focal are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). Every release so far is a testing
pre-release.

## [0.3.0] - 2026-11-13

Changes after the first week of use on a phone, and a review of the whole code
base. Checked on emulators only so far. Installs over 0.2.0 and keeps its notes.

### Added

- **Continue recording.** A note can take more: ● on its page (or "Continue
  recording" in its menu) records on into the same note. The new audio follows
  the old in the same file and its text follows the note's text; edits made
  before are kept.
- **Select several notes.** A long press selects a note (the card at the top
  included), taps add more, and "Delete N notes" removes them together with one
  Undo.
- **Listen while editing.** ▶ in Edit plays just the audio of the selected words,
  or of the words around the caret, and stops.
- **Edit** in a note's menu: the way into Edit from a keyboard, where there is no
  word to tap.

### Changed

- **Tap a word to replace it.** A tap on a word opens Edit with that word
  selected, so the next key replaces it; a long press sweeps a phrase the same
  way. Playing a note is ▶ in the dock; a tap no longer starts playback, and the
  pencil button is gone.
- **Library rows** show the title, the note's length and two lines of text. The
  time of day no longer opens the preview; search results show the day instead
  of the length.
- **Titles** from a long first sentence are shortened to whole words at a clause
  and carry no ellipsis. Titles made by 0.2.0 are derived again once; renamed
  notes keep their titles.
- **A recording interrupted by another app** (or a call) waits with the
  microphone open and goes on by itself when the audio comes back, instead of
  staying paused until **Resume**. After ten minutes it becomes an ordinary pause.
- **Settings** have the page's name beside the back arrow, one line per row and
  short notes under a group; the statement that Focal works offline closes the
  page.
- The hint that settings are a swipe away is a one-time message with **Open**,
  not a page that moves by itself.
- Search keeps the library on screen until something is typed.
- A microphone that will not start again on **Resume** leaves the recording
  paused with the reason; it used to end the recording. A recording that stops by
  itself, because its audio can no longer be written, now says so.
- The recorder keeps two seconds of audio in reserve instead of half a second, so
  a slow moment on the phone does not drop samples, and capture asks Android
  whether the microphone is silenced ten times a second instead of a hundred.

### Fixed

- The card at the top and some rows showed a title with no text under it: the
  preview read only the first paragraph, which the title can use up.
- Text moved while editing: paragraphs were set with a line-breaking strategy that
  re-broke earlier lines on every key, the whole page shifted by a few dp when the
  caret moved, and a line under the edited paragraph came and went.
- Closing search: the dock slid up from the bottom edge while the field came down
  to it. The dock now fades where it stands.
- A title on the top card was cut to make room for a long date beside it.
- The edit bar's **Done** was clipped on narrow phones.
- With the keyboard down, the hidden dock still took taps: a tap on the search
  field could start a recording, and **Replace all** could copy the note.
- A second tap on **Delete** or **Remove audio** while the menu closed made the
  first one final, with no Undo.
- A note opened again could start from the state it was left in: a note restored
  with Undo closed itself once, and "Note ready" could play a second time.
- Enter with words selected replaced them with a space instead of splitting the
  paragraph.
- Undoing a change in a paragraph that had scrolled away closed the keyboard and
  showed nothing; the page now goes to the paragraph.
- Pausing the playback of an untranscribed part restarted it as playback of the
  whole note.
- Dragging the playhead as the audio ended could leave the next playback's
  position stuck.
- The Copy button could keep another note's progress or "Copied" when the note
  under it changed.
- The bar showed no title when a note reopened where it had been read.
- The recording screen could freeze half lit when it was touched while it opened.
- After a cancelled back swipe, Back played the wrong transition, and the
  microphone sheet stayed lowered.
- Search reopened at once after closing had no field; after a rotation with an
  empty query the page flashed blank.
- TalkBack read every row as "not selected".
- A shortened title could drop a final "do" or "no", taken for function words of
  another language. The words of the sentence now decide which language's
  function words apply.

## [0.2.0] - 2026-11-07

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

[0.3.0]: https://github.com/andreizpgh/Focal/releases/tag/v0.3.0
[0.2.0]: https://github.com/andreizpgh/Focal/releases/tag/v0.2.0
[0.1.0-alpha.9]: https://github.com/andreizpgh/Focal/releases/tag/stt-alpha-9
[0.1.0-alpha.8]: https://github.com/andreizpgh/Focal/releases/tag/stt-alpha-8
[0.1.0-alpha.7]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-7
[0.1.0-alpha.2]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-2
[0.1.0-alpha.6]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-6
[0.1.0-alpha.1]: https://github.com/andreizpgh/Focal/releases/tag/v0.1.0-alpha.1
