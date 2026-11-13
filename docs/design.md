# Design

Focal's interface is built around one job: speak, see the text, copy it. This
page condenses the approved [design specification](design-spec.md), which holds
every measurement, state, transition and string, and which code comments cite
by section ("spec 4.22.4"). The tokens live in code under
`app/src/main/java/app/focal/ui/design`, which is the source of truth when this
summary and the code differ. Where the app departs from the specification, the
difference is listed under [Known deviations](#known-deviations).

## Principles

- **Text is the product.** A note reads like a document: a title, a quiet
  dateline and short paragraphs. The only thing between them is the time a
  paragraph starts at, for a note that still has its audio, and that can be
  turned off.
- **Two materials.** *Paper* is where notes are read and found: white, or near
  black in the dark theme. *The instrument* is the logo's own material, a
  near-black gradient with grain and a soft top highlight, used only for
  recording. It looks the same in both themes, so an open microphone is
  unmistakable from across a room.
- **One focus.** Whatever is current is in full ink: new text while recording,
  the paragraph being played, the current find match. Everything else recedes
  exactly one step to a muted gray. (In Edit everything stays in ink: the caret
  says where the edit is.) Text is never blurred, hidden or
  replaced by placeholders.
- **One bottom column.** Everything touched often sits in one column at the
  bottom, 24 dp from each edge: the dock, the feedback pill, the player strip, the
  search field and the find rows.
- **Little to configure.** No language or model picker, no modes beyond Edit.
  The settings page holds the theme, one reading option, one storage action and
  About; every row on it does something.
- **Nothing moves by itself.** Text stays where it is while it is edited, pages
  shift only under a finger, and what leaves fades where it stands.
- **Honest states.** Progress is real, failures say what happened and what is
  kept, and nothing pretends to be finished before it is.

## Color

Two inks and the grays between them; no accent color and no dynamic color.

| Role | Light | Dark | Used for |
| --- | --- | --- | --- |
| `paper` | `#FFFFFF` | `#0F0F0F` | Backgrounds |
| `ink` | `#111111` | `#EDEDED` | Text and primary controls |
| `muted` | `#6B6B6B` | `#8F8F8F` | Receded text, metadata |
| `faint` | `#949494` | `#6E6E6E` | Placeholders, disabled glyphs, doubt dots |
| `line` | `#EBEBEB` | `#222222` | Hairlines |
| `s1` / `s2` | `#F3F3F3` / `#E8E8E8` | `#1C1C1C` / `#282828` | Secondary controls, edit sheet, find matches |
| `lens` | `#E0E0E0` | `#333333` | The playing word |
| `inv` / `onInv` | `#111111` / `#FFFFFF` | `#EDEDED` / `#0F0F0F` | Primary pills (Record, Copy), the current find match |

The recording scene uses a vertical gradient (`#262626` → `#171717` → `#0A0A0A`),
a 7% grain, ink `#EDEDED` and muted `#8C8C8C`. The stage card uses the same
material with muted `#999999`.

Text contrast is at least 4.5:1 in every state and meaningful non-text at least
3:1. With Android's contrast setting at medium or high, every receded gray moves
one step closer to ink; with high-contrast text, nothing recedes.

## Typography

Inter 4.1, variable, bundled as a subset (Latin with extensions, Greek, Cyrillic
and the punctuation the interface uses; 420,648 bytes). It is rebuilt with
`scripts/subset-inter.py`. Text styles use the 16 pt optical size; the display
styles use 32. Figures that change in place (timers, durations, counts) are
tabular.

| Token | Size / line | Weight | Used for |
| --- | --- | --- | --- |
| `sceneTimer` | 76 / 84 sp | 250 (display) | The recording timer |
| `stageLength` | 40 / 48 | 250 (display) | The stage card's running length |
| `title` | 28 / 34 | 600 (display) | Note titles |
| `headline` | 22 / 28 | 600 (display) | Page headings |
| `sceneText` | 20 / 30 | 420 | Live text in the scene |
| `body` | 18 / 28 | 400 | Paragraphs (lines break one after another, so typing never moves the lines above the caret) |
| `rowTitle`, `stageText`, `field` | 17 / 24 | 600, 400, 400 | Library rows, the stage, inputs |
| `label` | 16 / 20 | 600 | Buttons |
| `action`, `dateline`, `meta`, `secondary` | 15 / 20–22 | 600, 500, 400, 400 | Pill labels, datelines, metadata |
| `section` | 14 / 20 | 600 | Day labels, settings groups |
| `caption` | 13 / 18 | 400 | Small print |

Line boxes are exact (the leading is split above and below), so paragraphs wrap
identically when reading, playing, finding and editing.

## Shape, space and layout

- Five radii only: full pill (buttons, fields, the feedback pill), 28 dp (the
  stage, sheet tops, page corners during back gestures), 16 dp (menus), 12 dp
  (the edit sheet and press plates) and 8 dp (the playback lens, find matches).
- A 4 dp grid. Text and the bottom column start 24 dp from the window edges and
  are capped at 600 dp wide on large windows.
- Every control has at least a 48 dp target; dock controls are 56 dp.
- Fades replace bars and dividers: 16 dp at the top of scrolling content, 64 dp
  above the bottom column.

## Motion

One voice: critically or near-critically damped springs with no visible bounce.
They match Material 3's standard motion scheme value for value.

| Token | Spring (damping, stiffness) | Settles in | Used for |
| --- | --- | --- | --- |
| `press` | 0.9, 1400 | 224 ms | Press scale, the lens within a line, menu scale-in |
| `move` | 0.9, 700 | 317 ms | Bounds and sizes: dock morphs, sheets, the settings swipe, the feedback pill |
| `stage` | 0.9, 300 | 484 ms | What takes the whole window: the recording surface (expand, fold, settle) and a note's page opening out of the stage or a row and folding back |
| `fadeFast` | 1.0, 3800 | 150 ms | Icon and label swaps, exits |
| `fade` | 1.0, 1600 | 231 ms | Layers, menus, notices, the theme switch |
| `fadeSlow` | 1.0, 800 | 326 ms | Arriving text |
| `develop` | 1.0, 300 | 533 ms | Text color: faint to ink, ink to muted |

The only tweens: a 150 ms linear crossfade that replaces the expand, fold and
settle under reduced motion, and wherever the scene has no origin on screen (it
fades in over a note's page when that note goes on recording); the 1.2 s sweep
of the writing head; and the single 720 ms band of light when a note becomes
ready.

**Frame time never jumps.** Every animation is a function of frame time, and a
frame that takes long (a page composed for the first time) would otherwise be
followed by one that shows the motion where the wall clock says it should be by
then: most of it gone in one step. The app's frame clock advances by at most
40 ms a frame, so a long frame holds a motion still and the next goes on from
there. A page change also waits for its page: a note opening out of the stage or
a row starts to move once the note is loaded and laid out, and a fold once the
library is laid out under it again. Nothing of a note is drawn before the note
is there.

New text **develops**: it fades in from a faint color to ink over about half a
second, and the text before it recedes 140 ms later. There are no double-tap
gestures anywhere, so every tap acts on release without a delay.

Under reduced motion, the expand, fold and settle become a 150 ms crossfade
(never a cut from white paper to near-black), the readiness band is dropped, and
everything else snaps. The live meter keeps updating, because it is information.

## Haptics

Five moments and no others, all through Compose's haptic feedback (no vibration
permission):

| Moment | Feedback |
| --- | --- |
| The service reports that recording started | Toggle on |
| Finish, after the service accepts it | Confirm |
| A note becomes ready on screen (once per note, skipped within 1.5 s of another tick) | Confirm |
| Copy, after the clipboard accepted the text | Confirm |
| A long press takes hold: a note is selected, a word in a note | Long press |

Pause, Resume, expand and collapse, the settings swipe, playback, typing,
deleting, Undo and errors are silent. Focal plays no sounds.

## Components and interactions

### The stage

A card at the top of the library in the instrument's material. It always holds
what is current: while recording, the live wave, the running length and the
latest line; otherwise the note being transcribed, or the latest note with a
one-tap Copy. Dragging the card down opens what it holds: the recording scene
while recording, and at rest the note, whose card follows the finger and opens
into the page when it is let go far enough (or flung); sooner, it goes back. In an empty library there is no card; the nine bars rest in ink on
the paper above one line of invitation.

### The recording scene

The stage at full screen: the nine-bar wave (driven by the microphone level), a
large timer, a state line, and the live transcript with the newest text in ink.
Pause and Finish sit in the dock.

- **Expand:** tap the live stage or drag it down; it grows into the scene under
  the finger. The wave, timer and latest line travel; nothing is re-created.
- **Fold:** swipe up, tap the chevron or press Back; the scene shrinks back into
  the stage and recording continues. Predictive back previews the fold.
- **Settle:** Finish contracts the scene into the note page, where the document
  develops as transcription finishes.

### Settings

Settings is the page beside the library, not a screen on top of it. Swiping the
library from right to left slides it in under the finger; a release past 35% of
the width, or a fast fling, completes it. Touches that start within 32 dp of
either edge are left to the system Back gesture.

Nothing on the library shows that a page lies beside it, so it is said once, in
words: after the library has been at rest for a moment the feedback pill reads
"Swipe left for settings" with **Open**, which opens the page with the swipe's
own motion. The hint shows at most twice, only once the library holds a note,
and never after settings has been opened. Nothing moves by itself.

The page has its name beside the back arrow and four groups under small labels.
A row is one line, and every row does something or leads somewhere: the theme;
**Paragraph times**, a switch; **Remove all audio**, with what it would free
beside it; the source code and the licenses. Only the version is there to be
read. Remove all audio asks once, in a bottom sheet that says how many notes
lose their audio, how much that frees and that their text stays: it cannot be
undone from this page. That Focal works offline is not a setting, so it closes
the page under the mark.

Every input has another path: an "Open settings" accessibility action, Ctrl+, on
a keyboard, and App info's settings link.

### The library

Under the stage, notes are listed by day. A row is its title, its length flush
right, and two lines of the text that follows the title. It carries one piece of
metadata in one place: the length, or what is happening to the note while it is
not ready ("42%", "Waiting"). The day is the label above it and the time of day
is on the note's page. Search results show the day where the length was.

A long press selects a note, the stage's note included; while notes are selected
a tap adds or removes one, the dock is ✕, ⋮ and "Delete N notes", and Back ends
it. Selected rows lie on one plate: rows selected next to each other join into a
single block, square where they meet, and the stage lightens a step; neither
gets an outline. ⋮ (there while a selected note has audio that can go) offers
**Remove audio** with its size: the notes stay as text. Deleting several notes,
or removing their audio, offers one Undo. A note that is being transcribed
cannot be selected.

Search keeps the library on screen until the first results are there; the
library then dissolves as the results come up from the field, in one crossfade.
A tap on the library under an empty field closes search.

### The dock

At most three controls, whose meaning changes and whose position never does:
Search and Record in the library; Pause and Finish while recording (Resume
becomes a labeled pill while paused); ✕, ⋮ and Delete while notes are selected;
Play, ● and Copy on a note. ● continues the recording into that note: the scene
comes in over the page, its timer goes on from the note's length, and Finish
settles it back. In a short window, where the dock stands in a side rail, there
is no room for ●: **Continue recording** in the note's menu does the same. When transcription finishes, the progress pill turns into Copy
once, with a single band of light and one tick.

The dock fades where it stands when a keyboard or a row takes its place. It never
slides.

### The feedback pill

Replaces snackbars and toasts. It rises from behind the dock, spans the bottom
column and shows one message at a time, such as "Note deleted · Undo". Delete and
Remove audio commit when the pill goes away without Undo. It is a polite live
region, and stays until dismissed while TalkBack is on.

### The document

The note page: title, dateline and paragraphs on paper. The text is edited where
it is read.

- **Tap a word** to replace it: Edit opens with that word selected, so the next
  key replaces it. Between words, the caret goes there.
- **Paragraph times.** A note that still has its audio shows, over each paragraph
  after the first, the time it starts at; a tap plays from there. A setting turns
  them off.
- **Long-press** to sweep a phrase: the word under the finger, growing word by
  word as the finger moves; lifting it opens Edit with the phrase selected.
- **▶ in the dock** plays the note. The playing paragraph stays in ink, a soft
  lens follows the word, a player strip appears above the dock and the text
  follows playback within the middle third of the screen.
- **Tap the title** to rename it.
- Doubtful words have a row of faint dots under them; a tap selects them like any
  other word.
- **Delete** is hidden while a note is being transcribed or waits for the speech
  model; if it is reached another way, the pill asks to finish first.
- **Copy and Share** refuse a note longer than 200,000 characters; the pill asks
  you to copy the part you need.
- A note that cannot be edited yet (it is still being transcribed) plays from the
  tapped word instead.

### Edit

The paragraph with the caret becomes a text field in place, with the same
typography and the same line breaks, so nothing moves; nothing is drawn around
it and nothing recedes. A tap means the same everywhere in Edit as it did on the
way in: on a word it selects the word, so the next key replaces it; inside what
is selected it puts the caret there, to change a letter; on bare paper it puts
the caret there. Enter splits a paragraph and Backspace at its start joins it to
the one before; one undo and redo history covers the session. Changes save 600 ms
after typing stops. ▶ in the edit bar plays just the audio of the selected words,
or of the words around the caret, and stops: a word is checked against what was
said without leaving it. **Restore original** for an edited paragraph is in the
edit menu. Done or Back leaves Edit; nothing is discarded and there is no dialog.
**Edit** in the note's menu is the way in without a pointer: the caret goes
before the playing word, or to the start of the first paragraph on screen.

The page moves for one reason only: the caret's line would otherwise be under the
keyboard or the bar, and then by the least that brings it into view, together
with the keyboard as it rises. The title and the dateline stay as Edit found
them until Done (a title derived from the first sentence would change, and
re-wrap, with every save of that sentence), and nothing is added under a
paragraph while it is edited: text never moves under the fingers.

### Find and replace

From the note menu. Two rows ride the keyboard: the find field with the match
count and previous/next, and the replace field with "Replace all (N)". Matches
use the same folding as search (case, accents and `ё`). The current match is
shown inverted and scrolled into view. Replace all is one undoable step.

## Accessibility

- **TalkBack.** Each note paragraph is one node with actions: Play from here,
  Edit, Copy paragraph, Restore original, and Play unclear word when a word is
  doubtful. There are no word-level nodes, so a long note never becomes
  thousands of targets. The stage and each library row are merged nodes that say
  whether they are selected and offer Copy text, Share, Rename, Remove audio and
  Delete as actions. Recording state is spoken ("Recording, 4 minutes 16
  seconds"); seconds are not announced as they tick; pauses are announced.
- **Every gesture has an alternative.** Expanding the scene, opening settings and
  every swipe have a tap, key or accessibility action.
- **Keyboard.** Space plays and pauses; Ctrl+F finds; Ctrl+Z and Ctrl+Shift+Z
  undo and redo in Edit; Esc closes the innermost layer; Ctrl+, opens settings.
- **Not by color alone.** Playback adds the lens shape and the pause glyph; the
  current match is inverted and counted; recording adds the moving wave and the
  running timer; paused adds a badge on the wave.
- **Large text.** Layouts are checked at 1.3× and 2.0× font scale: rows, pills
  and the find rows wrap, and the scene's instrument becomes compact so live text
  stays visible.
- **Reduced motion** is respected as described above.

## Known deviations

The current app departs from the specification in these known ways. The accepted
ones stand in for amendments to the specification.

**Accepted**

- **Landscape and large screens** (spec 5.14). Paused, the landscape rail keeps a
  labelled Resume. In a short window the find rows sit side by side, or in one
  row on a narrower phone. An empty landscape bottom column ends in a 16 dp fade.
  The landscape stage shows whole lines by 4.22.4's rule, so two under a
  one-line title, not 4.22.1's one. Two panes split at a separating vertical
  hinge, with settings and the scene on the detail side.
- **Live text in the scene** starts at the top of its region and is
  bottom-anchored once it overflows (6.4 says bottom-anchored). While "↓ Latest"
  shows, the text fades out 8 dp above it.
- **Page transitions** (T16, T16′). The page's text appears only once the flying
  title has passed it, the page's chrome leaves before the stage's own text
  returns, and paper covers the library under the clip. The live stage's top
  mask grows with the font size.
- **Copy** centres "Copy" in its pill; "Copied" starts at the same place, so it
  sits up to 6.5 dp right of centre while it shows (4.5.1 reserves the wider
  label's slot instead).
- **The menu divider** in the dark theme is `#303030`: the specified `line`
  color is the menu's own `#222222` (2.1).
- **Right to left.** Text takes its direction from its content, so English
  interface text reads left to right in a mirrored layout, and a note's title and
  meta follow the transcript (8.5).
- **Waiting notes** give more reasons than 5.4's memory sentence: repeated
  failures, too little storage to prepare the speech model, a model that could
  not be prepared, and a damaged model, which asks to reinstall Focal and offers
  no Try again.
- **Licenses** also list Focal itself and the Silero VAD model (5.10).
- **The launcher shortcut** opens a private `ShortcutActivity` rather than
  `MainActivity`, so no other app can start the microphone (5.13).
- **The settings hint's flags** are kept in SharedPreferences, not DataStore
  (4.23.5). The first-text hint shows while the library holds fewer than three
  notes, because no recording count is stored (2.8.3).
- **After the first week on a phone** (November 2026) these parts of the
  specification were replaced, as described above: a tap on a word edits instead
  of playing, and the pencil is gone (5.5, 5.7); the provenance line under an
  edited paragraph is gone (4.14); the row's time run-in and the row menu are
  gone, and a long press selects (4.12, 4.15); the settings peek is a hint in
  the feedback pill (4.23.5); the settings and licences titles sit in the bar
  (4.1.4, 5.10); the dock fades instead of sliding and may hold a third control
  (4.3); search keeps the library until a query is typed (5.2); body text breaks
  line by line, not as a whole paragraph (2.3); derived titles are at most 36
  characters and are never cut with an ellipsis (4.22.4).
- **After the second week** (0.3.1) these were replaced too: the edit sheet and
  the receding of the other paragraphs in Edit are gone, and a tap in Edit
  selects a word (4.14, 5.7); paragraphs carry their start time (1, 5.5);
  settings hold a switch and an action instead of a storage note (5.10); a
  selection is one plate without a ring, and its dock has ⋮ (4.12, 4.3); the
  stage opens its note by a drag too (6.5); a page transform runs on `stage`,
  waits for its page and no longer counts from the tap (6.1, 2.8); search
  crossfades to its results instead of fading through (5.2, Q7); a menu's
  arrival starts at its first drawn frame (4.15).

**Not resolved**

- Under reduced motion, page changes snap, including stage ↔ page (T16′) and
  Finish from the library dock (T7b), where 2.8.4 asks for a 150 ms crossfade.
