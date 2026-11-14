# Changelog

All notable changes to Focal are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). Every release so far is a testing
pre-release.

## [0.3.2] - 2026-11-14

Changes after a third round on a phone: transitions that left a mess on screen,
a card that lagged behind the finger, and whatever else an audit of every
animation found. Checked on an emulator only so far.

### Changed

- **Pulling the card down** works as the live card does: its edge follows the
  finger all the way, and the card turns into the note's page under the finger
  (the page is opened as the pull begins). Let go past 40 % of the way, or flung
  down, it opens; sooner, it goes back into the card. Either way it goes on at
  the finger's speed, and the dock keeps the library's buttons until the card is
  let go into the page. It used to stop following the finger a third of the way
  down and build the page only once it was let go.
- **Back is immediate.** The library stays composed under a note's page instead
  of being built again on Back, which held the screen for a moment before the
  page folded away.
- **Opening a note is lighter.** A note is read as the finger lands on its row or
  the card, so the page a tap opens has its text in its first frame; each
  paragraph is composed once instead of twice as the page appears.
- **Settings** are composed off screen a moment after the library appears, with
  their values read at once: the first swipe no longer builds the page under the
  finger, and the theme, the switch and the audio size no longer slide to their
  values as it comes in. Opened or closed without a finger (the hint's Open,
  Back), the page slides on the slower `stage` spring, as every motion that
  takes the whole window does.
- **A tap on another note while one folds away** opens it as soon as the fold
  has landed (the fold finishes four times as fast meanwhile), instead of being
  lost or drawing the two pages over each other.
- **A note's page lets go of its note** five seconds after it closes, and of
  what its Edit session held of it: a note read ahead as a finger lands on its
  row is not kept, and a note opened again is read again instead of showing
  what it was when it closed.
- **Motions that cross the window** (the scene, settings, sheets, search) run
  to their end instead of jumping over the last 1 % of their way.
- **A search result opens at its line** instead of opening at the top and then
  scrolling to it while the page fades in.
- **Another note on the card** (after a delete, or its Undo) fades in instead of
  replacing the last one in a single frame.

### Fixed

- Leaving a note and tapping another at once showed the first note's page over
  the library at full strength for a moment: the page that had just left was
  still held by the display, and the next change drew it as the page below.
- A tap just after Back went to the page that was folding away, out of sight.
- With animations turned off in the system, the settings page lay invisible
  over the library and took its taps: no note could be opened, and a tap could
  open the source code link. The library's dock did the same over settings.
- Two quick taps on two rows opened both notes, one over the other; a tap on a
  note that was still opening pressed its unseen buttons.
- Back pressed just after a tap on a row blanked the library for a moment, and
  the flying title jumped.
- Opening the same note again just after Back stalled for most of a second, and
  its dock did nothing.
- A card let go short took the taps meant for the library while it went back
  (a tap on a row now opens that note as soon as the card has landed), and a
  turn of the phone at that moment restored the note open.
- A back swipe started while a note opened turned the half-open card into the
  whole page at once.
- Two panes: Back from the licences blanked the window before the library
  appeared at once; with settings open over a note, Back stopped the player or
  left Edit unseen instead of closing settings.
- Folding a recording over a note's page scrolled the library under it to the
  top, and could give the hidden library keyboard focus.
- A pull could start while settings were still sliding away.
- The settings hint never came back after the first touch of a session.
- After the app returned from the background the storage row went blank; a tap
  on "Remove all audio" before the size was read could open its sheet later by
  itself; the size was read twice, and again under the licences.
- A touch that caught the scene in mid-motion and then dragged left it frozen
  half-open.
- The bottom fade and the list's end waited for one motion to finish before
  following the keyboard or a pill that came and went.
- A label that changed twice in quick succession came back at full strength
  before fading again; "Now playing", "↓ Latest" and menus shown again while
  fading out snapped.
- The action of a message being replaced in the feedback pill could undo or
  open what the next message was about.
- A card still fading in showed whole in the first frame of a page or a
  recording opening out of it.
- Under an open note the library kept its live card's wave running, measured
  its list again with every motion of the note's bottom bar, and kept keyboard
  focus.
- Double-tapping "Delete N" (or a button the first tap seemed to miss) pressed
  what replaced it: Record started a recording, Search opened the keyboard.
- The library's rows dropped by the feedback pill's height in one frame when the
  pill left; they now glide down with the fade.
- A tap that caught the settings page in mid-slide opened the note under it.
- The card's pill was blank for one frame as a note became ready.
- After a jump to a far match in Find was cut short (the next match asked for,
  Find closed), the note's text could stay faded or invisible.
- During playback, a paragraph could stay grey for a quarter of a second after
  the voice reached it.
- "Now playing" turned its arrow the other way as it faded out.
- Back while a recording settled into its note folded the note into the library
  at the same time.
- The chevron tapped while the scene was still opening did not fold it.
- The newest paragraph of a recording opened again after scrolling back to it.
- The library composed itself on every frame of search closing; the day label
  pinned at the top redrew a layer the size of the window on every frame of a
  scroll; the recording's transcript composed itself again every second with
  the timer.
- Search results lagged behind the keyboard and overlapped for a moment as it
  opened or closed.
- A tap outside a menu that was fading out was lost.
- A message that replaced another in the feedback pill swapped its action
  button in a single frame.
- Find's rows appeared under the dock instead of over it while it faded.

## [0.3.1] - 2026-11-13

Changes after a second round on a phone: what moved, flickered or did nothing.
Checked on an emulator only so far. Installs over 0.3.0 and keeps its notes;
notes recorded before are split into shorter paragraphs once, without changing a
word.

### Added

- **Paragraph times.** A note that still has its audio shows, over each paragraph
  after the first, the time it starts at; a tap plays from there. **Paragraph
  times** in settings turns them off.
- **Remove audio for several notes.** While notes are selected, ⋮ in the dock
  offers "Remove audio" with its size: the notes stay as text, with one Undo.
- **Remove all audio** in settings, with what it would free; it asks once, since
  it cannot be undone from there.
- **Pull the card down to open its note**, as the live card opens the recording:
  the card follows the finger and opens into the page when let go far enough.

### Changed

- **Paragraphs are shorter.** A paragraph closes after a pause of 1.5 s once it
  holds two sentences (it was 2 s), after 3 s in any case (3.5 s), or at 60 words
  (80): dictation without long pauses no longer reads as one block. Notes
  recorded before are split once by the same rule where their text is still the
  recognizer's own; edited paragraphs are left alone, and no word changes.
- **Edit.** A tap on a word selects it everywhere in Edit, not only on the way
  in; a tap inside the selection puts the caret there. Nothing is drawn around
  the paragraph being edited and the others no longer dim.
- **Opening a note** runs on the slower `stage` spring and starts once the note is
  loaded and laid out, instead of at the tap.
- **Search** crossfades from the library to the results once the first results
  are there.
- **Settings.** The storage group is an action instead of a size with a note
  under it.
- **Selection.** Selected rows next to each other form one block; the selected
  card lightens instead of getting a ring.

### Fixed

- Opening a note and going back flickered: the motion was timed by the wall
  clock from the tap, so the frames spent composing the page swallowed most of
  it, and a note not loaded yet was drawn for a frame or two ("1 Jan 1970",
  "Text only", a writing head). Frame time now advances by at most 40 ms a frame
  for every animation in the app, and a page waits for its note.
- Back could draw the note's page and the library over each other: the display
  asked for a swipe-back preview of the library itself as the pop completed,
  which replaced the fold that was running.
- A note reopened where it had been read flew its title to the top of the window
  and dropped it there; the title now leaves with the card when the page has
  none on screen.
- Text moved while editing: the title, derived from the first sentence, was
  derived again with every autosave and re-wrapped; a field taking focus asked
  for its whole paragraph to be scrolled into view, and the caret then asked the
  page back. The title now holds until Done, and the page scrolls only for a
  caret that would be hidden, together with the keyboard.
- In Edit a tap on a word put the caret inside it, and the keyboard added what
  was typed to the old word.
- A menu's shadow appeared only after the menu had faded in (a layer drawn at
  less than full alpha was clipped to the menu's own bounds), and the fade itself
  was mostly over before the menu's window was first drawn.
- The first letter typed in search blanked the whole page before the results
  came.

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

[0.3.2]: https://github.com/andreizpgh/Focal/releases/tag/v0.3.2
[0.3.1]: https://github.com/andreizpgh/Focal/releases/tag/v0.3.1
[0.3.0]: https://github.com/andreizpgh/Focal/releases/tag/v0.3.0
[0.2.0]: https://github.com/andreizpgh/Focal/releases/tag/v0.2.0
[0.1.0-alpha.9]: https://github.com/andreizpgh/Focal/releases/tag/stt-alpha-9
[0.1.0-alpha.8]: https://github.com/andreizpgh/Focal/releases/tag/stt-alpha-8
[0.1.0-alpha.7]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-7
[0.1.0-alpha.2]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-2
[0.1.0-alpha.6]: https://github.com/andreizpgh/Focal/releases/tag/ux-alpha-6
[0.1.0-alpha.1]: https://github.com/andreizpgh/Focal/releases/tag/v0.1.0-alpha.1
