# Design

Focal's interface is built around one job: speak, see the text, copy it. This
page condenses the design system. The tokens live in code under
`app/src/main/java/app/focal/ui/design`, which is the source of truth when the two
differ.

## Principles

- **Text is the product.** A note reads like a document: a title, a quiet
  dateline and paragraphs. No timestamps, labels or counters in the text column.
- **Two materials.** *Paper* is where notes are read and found: white, or near
  black in the dark theme. *The instrument* is the logo's own material, a
  near-black gradient with grain and a soft top highlight, used only for
  recording. It looks the same in both themes, so an open microphone is
  unmistakable from across a room.
- **One focus.** Whatever is current is in full ink: new text while recording,
  the paragraph being played or edited, the current find match. Everything else
  recedes exactly one step to a muted grey. Text is never blurred, hidden or
  replaced by placeholders.
- **One bottom column.** Everything touched often sits in one column at the
  bottom, 24 dp from each edge: the dock, the feedback pill, the player strip, the
  search field and the find rows.
- **Nothing to configure.** No language or model picker, no modes beyond Edit,
  and a settings page with only theme, storage and About.
- **Honest states.** Progress is real, failures say what happened and what is
  kept, and nothing pretends to be finished before it is.

## Colour

Two inks and the greys between them; no accent colour and no dynamic colour.

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
3:1. With Android's contrast setting at medium or high, every receded grey moves
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
| `body` | 18 / 28 | 400 | Paragraphs |
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
| `stage` | 0.9, 300 | 484 ms | The recording surface only: expand, fold, settle |
| `fadeFast` | 1.0, 3800 | 150 ms | Icon and label swaps, exits |
| `fade` | 1.0, 1600 | 231 ms | Layers, menus, notices, the theme switch |
| `fadeSlow` | 1.0, 800 | 326 ms | Arriving text |
| `develop` | 1.0, 300 | 533 ms | Text colour: faint to ink, ink to muted |

The only tweens: a 150 ms linear crossfade that replaces the expand, fold and
settle under reduced motion; the 1.2 s sweep of the writing head; and the single
720 ms band of light when a note becomes ready.

New text **develops**: it fades in from a faint colour to ink over about half a
second, and the text before it recedes 140 ms later. There are no double-tap
gestures anywhere, so every tap acts on release without a delay.

Under reduced motion, the expand, fold and settle become a 150 ms crossfade
(never a cut from white paper to near-black), the readiness band is dropped, and
everything else snaps. The live meter keeps updating, because it is information.

## Haptics

Four moments and no others, all through Compose's haptic feedback (no vibration
permission):

| Moment | Feedback |
| --- | --- |
| The service reports that recording started | Toggle on |
| Finish, after the service accepts it | Confirm |
| A note becomes ready on screen (once per note, skipped within 1.5 s of another tick) | Confirm |
| Copy, after the clipboard accepted the text | Confirm |

Pause, Resume, expand and collapse, the settings swipe, playback, editing,
deleting, Undo and errors are silent. Focal plays no sounds.

## Components and interactions

### The stage

A card at the top of the library in the instrument's material. It always holds
what is current: while recording, the live wave, the running length and the
latest line; otherwise the note being transcribed, or the latest note with a
one-tap Copy. In an empty library there is no card; the nine bars rest in ink on
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
either edge are left to the system Back gesture. Twice in the app's life, after
the library has been idle for a while, the library peeks 32 dp to the left to
hint at the gesture; never after settings has been opened.

Every input has another path: an "Open settings" accessibility action, Ctrl+, on
a keyboard, and App info's settings link.

### The dock

At most two controls, a 56 dp circle and a 56 dp pill, whose meaning changes and
whose position never does: Search and Record in the library; Pause and Finish
while recording (Resume becomes a labelled pill while paused); Play and
Transcribing, then Play and Copy, on a note. When transcription finishes, the
progress pill turns into Copy once, with a single band of light and one tick.

### The feedback pill

Replaces snackbars and toasts. It rises from behind the dock, spans the bottom
column and shows one message at a time, such as "Note deleted · Undo". Delete and
Remove audio commit when the pill goes away without Undo. It is a polite live
region, and stays until dismissed while TalkBack is on.

### The document

The note page: title, dateline and paragraphs on paper.

- **Tap a word** to play from half a second before it. The playing paragraph stays
  in ink, a soft lens follows the word, a player strip appears above the dock and
  the text follows playback within the middle third of the screen.
- **Long-press** selects text with the system toolbar.
- **Tap the title** to rename it.
- Doubtful words have a row of faint dots under them; nothing else changes.
- **Delete** is hidden while a note is being transcribed or waits for the speech
  model; if it is reached another way, the pill asks to finish first.
- **Copy and Share** refuse a note longer than 200,000 characters; the pill asks
  you to select the part you need.

### Edit

Entered only by the pencil. Paragraphs become text fields in place, with the same
typography, so nothing moves. The focused paragraph sits on a light sheet; the
rest recede. Enter splits a paragraph and Backspace at its start joins it to the
one before; one undo and redo history covers the session. Changes save 600 ms
after typing stops. An edited paragraph shows "Edited · Restore original". Done
or Back leaves Edit; nothing is discarded and there is no dialog. Entering Edit
from playback places the caret before the playing word.

### Find and replace

From the note menu. Two rows ride the keyboard: the find field with the match
count and previous/next, and the replace field with "Replace all (N)". Matches
use the same folding as search (case, accents and `ё`). The current match is
shown inverted and scrolled into view. Replace all is one undoable step.

## Accessibility

- **TalkBack.** Each note paragraph is one node with actions: Play from here,
  Edit, Copy paragraph, Restore original, and Play unclear word when a word is
  doubtful. There are no word-level nodes, so a long note never becomes
  thousands of targets. The stage and each library row are merged nodes with the
  row's menu as actions. Recording state is spoken ("Recording, 4 minutes 16
  seconds"); seconds are not announced as they tick; pauses are announced.
- **Every gesture has an alternative.** Expanding the scene, opening settings and
  every swipe have a tap, key or accessibility action.
- **Keyboard.** Space plays and pauses; Ctrl+F finds; Ctrl+Z and Ctrl+Shift+Z
  undo and redo in Edit; Esc closes the innermost layer; Ctrl+, opens settings.
- **Not by colour alone.** Playback adds the lens shape and the pause glyph; the
  current match is inverted and counted; recording adds the moving wave and the
  running timer; paused adds a badge on the wave.
- **Large text.** Layouts are checked at 1.3× and 2.0× font scale: rows, pills
  and the find rows wrap, and the scene's instrument becomes compact so live text
  stays visible.
- **Reduced motion** is respected as described above.
