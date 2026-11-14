# Focal design specification

This is Focal's approved interface specification (version 5, 2026-11-06). It is normative: "must" is binding, and code comments cite its sections ("spec 4.22.4") and its IDs ("X1", "T16′", "R5"). [Design](design.md) summarizes it and lists the known, accepted deviations of the current app. The tokens in `app/src/main/java/app/focal/ui/design` implement it.

**How to read this.** "Must" is normative. Every dp value is a layout constant unless marked "ref". A ref value is a coordinate in the reference frame: 412 × 915 dp, 40 dp status bar (0–40), 24 dp gesture-navigation inset (891–915), Gboard top at y 625 when the keyboard is open. Real layouts are built from insets, not from ref coordinates. Colours are sRGB hex. Contrast ratios are WCAG 2.x, computed, not estimated. "Measured" values were measured on the reference renders (13). "x 24–388" means left edge 24, right edge 388. **p** is the expansion progress of the recording surface: 0 = the stage (the card in the library), 1 = the full-screen scene (6.4). **s** is the settings progress: 0 = the library, 1 = the settings page beside it (4.23). `lerp(a, b, t) = a + (b − a)·t`; `smoothstep(e0, e1, x)` is the cubic Hermite step `t²(3 − 2t)` with `t = clamp((x − e0)/(e1 − e0), 0, 1)`.

---

## 0. Decisions

These product decisions bind the rest of the document, which cites them by ID. Changing one is a product decision, not an implementation detail.

### 0.1 Product decisions

| # | Decision | Where |
|---|---|---|
| X1 | No settings button anywhere. Settings opens by a horizontal, finger-tracked swipe from right to left across the library content. The touch-down is at least 32 dp from the screen edges, so the system Back gesture is never taken; the gesture is direction-locked against the list's vertical scroll; a release past 35 % of the width or a fling completes on a no-bounce spring, otherwise it springs back; the settings page slides in from the right; a swipe from left to right, system Back or predictive back returns with the same progress-driven motion. Discoverability: a one-time peek on the very first launch, and once more after the first note is created if settings was never opened; it never repeats after settings has been opened once. Alternatives that must exist: a TalkBack custom action "Open settings" on the library; an `APPLICATION_PREFERENCES` intent filter, so Android's App info gear opens Focal's settings; the keyboard shortcut Ctrl+, . The settings page holds Theme, Storage and About | 4.23 |
| X2 | The idle library dock is a 56 dp search circle (24–80) + a full-width Record pill (92–388). While recording: Pause circle + Finish pill. The empty library: the Record pill alone (24–388) | 4.3 |
| X3 | The top of the library is pure content: the stage card starts under the status bar with the standard top margin; the time sits on the title line, right-aligned | 4.1.1, 4.22 |
| X4 | The empty library has no card: the resting nine-bar wave centred on paper, "Speak, and it’s written down." + "Tap Record and talk. Text appears after each pause.", the Record pill below. No drag-to-record gesture anywhere: Record is enough. A drag down on the stage only expands a recording that is already running | 4.22.5, 6.5 |
| X5 | Pause shows only the pause-glyph badge on the dimmed wave (no word, no caption) for manual pauses and for call and silenced-microphone auto-pauses, on the stage and in the scene. The only caption is for the storage-full auto-pause, one short line: "Storage is full" | 4.21.4, 4.22.3, 5.3.5 |
| X6 | Readiness: no check. The "Transcribing · N%" pill morphs once directly into the Copy pill; one soft shimmer sweep crosses the card; one `Confirm` haptic. Nothing else; no "Note ready" text in the app's UI. The words survive only outside it: in the system notification (posted only when the note is not on screen) and in TalkBack's spoken announcement | 4.22.4, T8″, 5.12, 8.2 |
| X7 | Everything else stays as specified here: the card hierarchy and states, the sticky day header, the expand/collapse choreography, the scene, the note view, find and replace, the undo pill and search | throughout |
| O1 | The library has no app name, wordmark, logo or tile; it has no top element at all (X3) | 4.1.1 |
| O2 | Day labels stay small (`section` 14/20); no editorial day heads | 4.12 |
| O3 | The library dock is a 56 dp search circle (x 24–80) plus a full-width Record pill (x 92–388); while a capture runs it carries Pause + Finish (V8) | 4.3, 4.4 |
| O4 | The empty library shows the nine bars as the instrument at rest, in ink on paper: still, 42 % of the profile (X4, F2) | 4.22.5 |
| O5 | While the microphone is open and the scene is expanded, the whole screen is the logo's material in both themes: near-black gradient, grain, top highlight, a large centred wave with a glowing centre bar and blurred outer bars, a large thin tabular timer, the latest text beneath (left-aligned), Pause circle + Finish pill in the dock, and a centred chevron pointing up. No "Recording" word (V4); paused is the badge on the wave (X5) | 4.21, 5.3 |
| O6 | Undo feedback is a grey tonal pill exactly as wide as the dock (x 24–388), 8 dp above it, sliding up from behind the dock. Record never moves | 4.8 |
| O7 | Library search and find and replace are rows riding on the keyboard on the 24–388 grid. Search results stack upward from the field | 4.9, 5.2, 5.8 |
| O8 | The note is a document, with the ← ✎ ⋮ top bar, the player strip and a Play circle + Copy pill dock | 5.5 |
| V1 | The library home is the **stage**: lengths as "12 min" (thin numerals + small muted unit, never m:ss except live); transcribing is a glass pill with a 2 dp track and "Transcribing · 82%" (the white gradient only for a fresh Copy); never bare material while loading | 4.22, 5.1 |
| V2 | The full-screen recording scene keeps its geometry, material, wave, timer, live text and Pause circle + Finish pill. The stage is its collapsed form, not a replacement | 4.21, 5.3 |
| V3 | Expand: Record, a tap on the live stage, or dragging the live stage down, tracking the finger. Release past ~40 % or a fling completes on a spring with no bounce; otherwise it springs back. Collapse: swipe up, the centred chevron or system Back; predictive back drives the same morph. The recording continues. The drag only ever expands a running recording (X4) | 6.4, 6.5 |
| V4 | No "Recording" text anywhere (stage or scene). The live wave and the running timer are the indicator; TalkBack announces the state and any pause reason | 4.21.4, 4.22.3, 8.2 |
| V5 | No "Today" (or any day) label above the stage. Day labels start in the list below; a sticky day header appears only once the list scrolls under the top | 4.1.1, 4.12.1 |
| V6 | Copy stays on the stage (one-tap copy of the latest note). A tap elsewhere on the stage opens the note | 4.22.6 |
| V7 | Finish opens the note's page (text develops, Copy in the thumb zone). Back to the library: the stage holds that note (transcribing or ready) | 5.4, T7, T16′ |
| V8 | While recording, the library dock shows the scene's controls: Pause circle + Finish pill. The stage shows the live wave, the timer and the latest resolved line | 4.3, 4.5.2, 5.1.4 |
| V9 | Monochrome, Inter, springs without bounce, haptics at four moments (2.9); bars only as live state or data, except the empty library's instrument at rest (X4) | throughout |
| W2 | The empty library is balanced and inviting; no app name or logo (X4) | 4.22.5 |
| W3 | No "Note ready" text in the app and no sound; the stage's bottom row stays balanced without the word (X6) | 4.22.4, T8″ |
| W5 | "Everything is saved" is not shown; only a storage-full pause has a caption (X5) | 4.22.3, 5.3.5 |
| W6 | The stage card's hierarchy: the time on the title's first line, right-aligned; balanced spacing; one bottom row, balanced in every state | 4.22.4 |
| W7 | The expand animation is kept exactly as 6.4 defines it | 6.4 |

### 0.2 Rules cited by ID

Each rule is stated in full in the section it names.

| # | Rule | Where |
|---|---|---|
| R1 | Paused, the Pause circle widens into a labelled "● Resume" pill (x 24–172) and Finish keeps its right edge (x 184–388), in the scene and in the library dock | 4.5.2, 4.7 |
| R2 | Replace is always visible and labelled with its count: "Replace all (N)" | 4.9.2 |
| R3 | "Replaced N · Undo" rises above find row 1 | 5.8 |
| R4 | The scene's chevron is centred on the vertical axis (x 182–230) | 4.21.2 |
| R5 | The timer reserves the tabular width of its format (0:00, 10:00, 1:00:00), so it never resizes while it runs; also on the stage | 4.21.3, 4.22.3 |
| R7 | Blur tiers per API level, grain through one `ImageShader`, the system-bar flip during the transition, the reduced-motion crossfade | 3.4, 6.2, 2.8.4 |
| S1 | Finish opens the note (T7); the settle story plays on Back, which folds the page into the stage (reverse T16′). Finish never lands on the library | 5.4, 6.1 |
| S2 | Lengths on the stage read "12 min": thin numerals, small muted unit; never "12:04" beside a "16:05". m:ss only while live | 4.22.4 |
| S3 | Transcribing on the stage: a glass pill, a 2 dp `stageInk` track, "Transcribing · 82%". The label never ends on a fill edge | 4.22.4 |
| S5 | No bare material while loading | 5.1.3 |
| Q2 | In the morph, the text region's top follows the timer's ease-out, so it stays 17.5–46 dp below the timer's box at every p | 6.4 |
| Q3 | In the morph, each typesetting keeps its own x: the stage's 20 dp inside the clip, the scene's at x 24, so lines are never cut by the clip | 6.4 |
| Q7 | Page chrome fades with the paper; dock containers crossfade without a coverage dip, while glyphs and labels fade through | 4.3, 6.1 |
| F1 | The readiness shimmer is masked out of card-local 0–48 and ramps in to 80, so the stage's time keeps 4.51:1 or better | 2.2.1, T8″ |
| F2 | The empty library's instrument rests at 42 % of the mark's profile, still; the full profile is the logo | 3.2, 4.22.5 |
| G1 | The library's top fade has two layers: the status edge, always drawn, and the pin band, which arrives only as the stage's bottom passes under the status edge. The stage is never tinted | 2.6, 4.1.1 |
| G4 | Previews end on the last whole word that fits; trailing `, . ; : – —` are dropped before "…" | 4.12.2, 4.22.4 |
| G5 | Day labels ride above the band on their way to the pin line, drawn in the pinned-label overlay | 4.12.1 |
| H1 | The peek reveals 32 dp: the 8 dp seam plus the settings page's blank 24 dp margin, never a clipped glyph | 4.23.5 |
| H2 | Under reduced motion the settings swipe is a fade-through on paper, so two typesettings never overlap | 4.23.2, 2.8.4 |
| H3 | In T1′e the invitation's alpha is (1 − a)², a = the material's alpha, so it is gone before the material is opaque | 6.1 |
| I2 | A touch-down catches a settle or the peek (the peek's coroutine is cancelled, not only stopped); one tracking formula serves both directions | 4.23.3, 4.23.5 |

---

## 1. Concept

**Two materials.** Focal has paper and an instrument.
- **Paper** is where you read and find your notes: white (or near-black in Dark), set in Inter, with no chrome above the text except what the screen needs. The list is a contents page; a note is a document.
- **The instrument** is the logo's own material (the launcher tile's near-black gradient, its grain, its top highlight and its glowing centre bar). It has two sizes and one identity:
  - **The stage**: a card at the top of the library, x 24–388, that always holds whatever is current. While the microphone is open it is the instrument in your hand: the live wave, the running timer and the latest line. Otherwise it holds the note being transcribed or the latest note, with one-tap Copy. In an empty library there is no stage yet: the instrument rests on the paper itself, in ink, waiting for Record (4.22.5).
  - **The scene**: the same material at full screen, in both themes, while you watch the recording. The mic-open state is unmistakable from across a room, which also makes it a privacy cue.
  - The stage is the scene's collapsed form. While a recording runs, dragging the stage down grows it into the scene under your finger; swiping the scene up folds it back. Nothing is re-created: the wave, the timer and the latest line travel. Only Record starts a recording.

**One bottom column.** Everything you touch often sits on one column at the bottom, x 24–388 dp: the dock, the feedback pill, the player strip, the search field and the find rows. Every bottom element shares those two edges. The top holds only navigation and mode entry (← ✎ ⋮, the chevron) and the stage, whose Copy is the one approved exception (V6). The library has no top bar and no settings control: settings is the page beside the library, one horizontal swipe away (4.23).

**The focus rule.** Whatever is current is set in full ink. Everything else recedes one step to `muted` (on the instrument, `#8C8C8C` in the scene and `#999999` on the stage), and nothing recedes further. The rule covers newly resolved text while recording, the paragraph being played, the paragraph being edited, the current find match, and the note on the stage. No text is ever blurred, hidden or replaced by placeholder shapes.

**The one object.** The logo appears in the product in one working form: the nine bars of the mark (section 3). They appear only as live state or data: the scene's hero and the stage's live wave (the microphone level), the writing head while a tail transcribes (on the note page), the playhead (the centre bar alone), and two static signatures (Settings footer, rationale sheet). One exception (X4): the empty library shows the instrument **at rest**, in ink on paper, still and low (42 % of the profile), which is what recording looks like before anyone speaks; it is never the full mark profile, so it does not read as the logo. Blur, the logo's depth of field, appears only on the wave's outer bars.

**The dock grammar.** At most two controls: a 56 dp circle and a 56 dp pill. Only their meaning changes: Search | Record → Pause | Finish (in the scene and, in paper dress, in the library while a capture runs) → Play | Transcribing → Play | Copy. While paused, the circle becomes a labelled pill (Resume), because pausing is the one moment that needs a decision.

**The signature motions.**
- **Expand:** the stage grows into the scene: on a spring after Record or a tap, or, while a recording runs, under your finger when you drag it down. The wave travels to the centre and grows; the timer steps down and grows into the big timer; the latest line rides the card's bottom edge.
- **Fold:** the same frames backwards, on swipe up, the chevron or Back. The recording continues.
- **Develop:** new text goes from faint to ink in 533 ms, like a print developing; the text before it recedes 140 ms later.
- **Settle:** Finish contracts the scene into the progress pill while the document develops underneath. Back folds the document into the stage, which keeps the note.
- **Wake:** Record in the empty library grows the material out of the resting instrument; the same bars rise to the live level in place.
- **Ready:** no words. The progress pill morphs once into Copy, one soft band of light crosses the card, and the phone ticks once.
- **Reveal:** swipe the library to the left and the settings page slides in beside it, under your finger; swipe back, or Back, and it slides away. Twice in the app's life the library hints at it with a 32 dp peek.

**Glyph grammar.** Each glyph means one thing everywhere.

| Glyph | Meaning |
|---|---|
| ● | The microphone: Record and Resume |
| ‖ | Pause (the control) and paused (the badge on the dimmed wave, on the stage and in the scene); there is no paused ring ○ |
| ■ | Finish |
| ▶ ‖ | Audio playback (on the page, ‖ pauses playback) |
| ✓ | Done: Copied |
| ⌃ | Fold the scene into the stage; recording continues |

---

## 2. Tokens

### 2.1 Colour roles (paper)

Two inks come from the brand assets: `#111111` (the lockup fill) and `#EDEDED` (the bar colour of the mark). Every other value is a neutral grey between them. There is no accent colour and no dynamic colour.

| Role | Light | Dark | Used for |
|---|---|---|---|
| `paper` | `#FFFFFF` | `#0F0F0F` | Window, page, `windowBackground` in `values/` and `values-night/`, splash background |
| `ink` | `#111111` | `#EDEDED` | Text in focus, titles, icons, the wave, caret, selection handles, focus ring |
| `muted` | `#6B6B6B` | `#8F8F8F` | Receded text, previews, meta, day labels, placeholders, total time |
| `faint` | `#949494` | `#6E6E6E` | Non-text only: doubt dots, disabled glyphs and labels, the first frame of *develop*, the sheet grab handle |
| `line` | `#EBEBEB` | `#222222` | Decorative hairlines: menu divider, licence separators |
| `s1` | `#F3F3F3` | `#1C1C1C` | Tonal fills: search circle and field, the library's Pause circle and Resume pill, find fields and nav pill, the Play/Pause circle, feedback pill, progress track, segmented track, edit sheet, jump pill, disabled pill |
| `s2` | `#E8E8E8` | `#282828` | Non-current find matches; the seam between the library and the settings page while the swipe is in flight (4.23; decorative, 1.23 / 1.30 against paper) |
| `lens` | `#E0E0E0` | `#333333` | The playing-word lens, the unplayed player track, the 2 dp row progress track |
| `select` | `#D6D6D6` | `#3D3D3D` | Text selection background |
| `inv` | `#111111` | `#EDEDED` | Record, Copy and the library's Finish; Done; Replace all; selected segment; current find match; progress fill |
| `onInv` | `#FFFFFF` | `#0F0F0F` | Text and glyphs on `inv` |
| `raised` | `#FFFFFF` | `#222222` | Dropdown menus |
| `raisedRing` | none | `#2C2C2C` | A 1 dp ring around menus in Dark |
| `scrim` | `#000000` at 32 % | `#000000` at 60 % | Behind the rationale bottom sheet only |
| `press` | `ink` at 8 % | `ink` at 10 % | State layer for pressed rows, menu items and icon buttons |
| `centreBarTop` / `centreBarBottom` | `#111111` / `#3D3D3D` | `#FFFFFF` / `#CFCFCF` | Vertical gradient of the wave's centre bar on paper (the mark's exact stops) |

**Contrast, computed.** These are the pairs the UI actually uses.

| Pair | Light | Dark | Requirement |
|---|---|---|---|
| `ink` on `paper` | 18.88 | 16.37 | Text 4.5 |
| `muted` on `paper` (receded text, meta, day labels) | 5.33 | 5.93 | Text 4.5 |
| `muted` on `s1` (placeholders, find count) | 4.80 | 5.27 | Text 4.5 |
| `muted` on `raised` (menu values) | 5.33 | 4.92 | Text 4.5 |
| `ink` on `s1` (feedback text, field text, circle glyphs, progress label) | 17.02 | 14.56 | Text 4.5 |
| `ink` on `s2` (find match) | 15.41 | 12.59 | Text 4.5 |
| `ink` on `lens` (playing word) | 14.30 | 10.79 | Text 4.5 |
| `ink` on `select` | 12.99 | 9.28 | Text 4.5 |
| `onInv` on `inv` (pills, current match, Done) | 18.88 | 16.37 | Text 4.5 |
| `ink` on `raised` (menu items) | 18.88 | 13.59 | Text 4.5 |
| `faint` on `paper` (doubt dots, non-text) | 3.03 | 3.76 | Non-text 3.0 |
| `inv` pill on `paper` (shape) | 18.88 | 16.37 | Non-text 3.0 |
| `ink` vs `muted` (the focus step) | 3.54 | 2.76 | A perceptible step, not a WCAG pair |

Rules:
- `muted` text never sits on `s2`, `lens` or `select` (those pairs measure below 4.5). The disabled pill label is `faint` on `s1` (2.73 / 3.34): it is disabled, and the disabled state is also announced.
- Receding never goes below `muted`, so receded text always reads at 5.3:1 or better.
- The low-contrast fills (`s1`, `s2`, `lens`: 1.11–1.52 against paper) are never the only carrier of meaning. A glyph, a label, an `inv` fill or a shape always carries it.
- **Contrast setting** (API 34+, `UiModeManager.getContrast() ≥ 0.5`): `muted` becomes `#4A4A4A` / `#B0B0B0`, `faint` becomes the normal `muted` value, and the scene's `sceneMuted` becomes `#A6A6A6`.
- **High-contrast text** (API 36+, `AccessibilityManager.isHighContrastTextEnabled()`; verify on device): receding is switched off and all text stays `ink`. Focus is then shown by the lens, the edit sheet and the find highlight.

**M3 `ColorScheme` mapping.** Construct `ColorScheme(...)` directly with every parameter; no role may come from `lightColorScheme()` defaults (they leak baseline purple).

| M3 role(s) | Value |
|---|---|
| primary / onPrimary / inversePrimary | `ink` / `onInv` / `onInv` |
| primaryContainer, secondaryContainer, tertiaryContainer | `s1` |
| onPrimaryContainer, onSecondaryContainer, onTertiaryContainer | `ink` |
| secondary, tertiary / onSecondary, onTertiary | `muted` / `paper` |
| background, surface, surfaceBright, surfaceContainerLowest | `paper` |
| onBackground, onSurface | `ink` |
| surfaceVariant, surfaceContainerLow, surfaceContainer | `s1` |
| surfaceContainerHigh | `s2` |
| surfaceContainerHighest, surfaceDim | `lens` |
| onSurfaceVariant | `muted` |
| outline / outlineVariant | `faint` / `line` |
| inverseSurface / inverseOnSurface | `inv` / `onInv` |
| error, errorContainer / onError, onErrorContainer | `ink`, `s1` / `onInv`, `ink` (errors are worded, never coloured) |
| surfaceTint | `paper` |
| scrim | `#000000` |
| primaryFixed / secondaryFixed / tertiaryFixed (+ Dim, on\*, on\*Variant), if present in 1.4.0 | `s1` / `s2` / `ink` / `muted` |

Use `tonalElevation = 0.dp` everywhere.

**Window and system bars.**
- Call `enableEdgeToEdge(SystemBarStyle.auto(...))` before `setContent`, with icon appearance taken from the app theme setting, not the system theme.
- `window.isNavigationBarContrastEnforced = false` on API 29+.
- `Theme.Focal` in `values/` (window `#FFFFFF`, light bars) and `values-night/` (window `#0F0F0F`). The first Compose frame then matches the window, with no flash on a cold start.
- When the in-app theme overrides the system theme, call `AppCompatDelegate.setDefaultNightMode` (or `UiModeManager.setApplicationNightMode` on API 31+).
- **While the scene is visible, both bars use light icons in both themes** (`isAppearanceLightStatusBars = false`, `isAppearanceLightNavigationBars = false`). The flip timing is defined in 6.2; during the morph the status and navigation icons flip separately, at their own clip thresholds.

### 2.2 Recording-scene palette (the logo material)

The scene ignores the theme. Its values come from `docs/mark.svg` (tile gradient `#262626 → #0A0A0A`, grain, highlight, bar colour `#EDEDED`, centre-bar gradient `#FFFFFF → #CFCFCF`).

| Token | Value | Used for |
|---|---|---|
| `sceneTop` / `sceneMid` / `sceneBottom` | `#262626` at 0 % / `#171717` at 46 % / `#0A0A0A` at 100 % of the window height | The material's vertical linear gradient |
| `sceneHighlight` | White at 7.5 % alpha in a radial gradient centred at the top-centre of the window, ellipse radii 130 % of the width × 46 % of the height, fading linearly to 0 at 72 % of the radius | The tile's top highlight |
| `sceneGrain` | White at 7 % alpha, fractal value noise, 256 × 256 px tile, repeated | The tile's grain; also dithers the gradient against 8-bit banding |
| `sceneInk` | `#EDEDED` | Latest text, glyphs on glass, Resume label |
| `sceneHi` | `#F7F7F7` | The timer |
| `sceneMuted` | `#8C8C8C` | Older text, the state line (model conditions; "Storage is full"), hint, paused timer |
| `sceneGlass` | White at 8.5 % alpha, plus a 1 dp inner top highlight of white at 10 % | The Pause circle, the Resume pill, the "Latest" jump pill |
| `sceneAction` | Vertical gradient `#FFFFFF → #DCDCDC`, label `#111111`, shadow `0 8 28 rgba(0,0,0,.35)` plus a 1 dp ring of white at 6 % | The Finish pill (an echo of the centre bar's `#FFF → #CFCFCF`) |
| `sceneChevron` | `#EDEDED` at 50 % alpha | The collapse chevron |
| `sceneWave` | Bars `#EDEDED` at the mark's alpha falloff; centre bar `#FFFFFF → #CFCFCF`; glow = white at 34 % | The hero wave |

**Scene contrast, computed** against the material at each element's ref y (gradient plus highlight, at the horizontal centre, which is the brightest column). Grain adds at most ±3 levels locally and does not change these.

| Pair | Ratio | Requirement |
|---|---|---|
| Timer `#F7F7F7`, live position (ref 268) / start position (ref 452) | 15.49 / 16.86 | Text 4.5 |
| Paused timer `#8C8C8C` (ref 268, 76 sp) | 4.93 | Large text 3.0 |
| "Storage is full" in the state slot `#8C8C8C` (ref 354 / 540) | 5.21 / 5.48 | Text 4.5 |
| Pause badge on the hero wave: disc `#EDEDED` vs material (ref 216–244) / ‖ `#161616` on the disc | 13.40 / 15.46 | Non-text 3.0 |
| Hint `#8C8C8C` (ref 668) | 5.64 | Text 4.5 |
| Older text `#8C8C8C` at the top of the text region (ref 398) / near the dock (ref 700) | 5.29 / 5.67 | Text 4.5 |
| Latest text `#EDEDED` (ref 760) | 16.48 | Text 4.5 |
| Glyph or Resume label `#EDEDED` on glass (ref 847) | 13.84 | Text 4.5 |
| Finish label `#111111` on `#DCDCDC` (worst stop) | 13.77 | Text 4.5 |
| Finish pill against the material | 14.28 | Non-text 3.0 |
| Chevron (`#EDEDED` at 50 %, ref 72) | 4.06 | Non-text 3.0 |

#### 2.2.1 The stage (the material at card size)

The stage draws the same material (`SceneMaterial`, refactored to take a size) inside its own bounds: the gradient stops and the highlight ellipse are relative to the card; the grain is in window pixels, so it does not swim during the morph.

| Token | Value | Used for |
|---|---|---|
| `stageInk` | `#EDEDED` (= `sceneInk`) | Title, length numeral, latest live text, the storage-full line, the pause badge's disc, glass labels, the progress fill |
| `stageMuted` | `#999999` | Every receded text on the stage: the time, text, older live text, length unit. `sceneMuted` `#8C8C8C` measures only 4.30:1 in the card's highlight band; `#999999` measures 4.82:1 there and 5.3–6.9 elsewhere. |
| `stageTimer` | `#F7F7F7` (= `sceneHi`); paused `stageMuted` | The live timer |
| `stageGlass` | = `sceneGlass` (white 8.5 % + 1 dp inner top highlight at 10 %) | Copy when not fresh; the transcribing pill |
| `stageAction` | Gradient `#FFFFFF → #DCDCDC`, label `#111111`, 1 dp ring of white at 6 %, shadow `0 6 20` black at 40 %, clipped by the card | Copy while the note is fresh (and the readiness morph's target fill) |
| `stageShimmer` | A band 180 dp wide, rotated 18°, white 0 → 3.5 % → **10 %** → 3.5 % → 0 across its width; drawn between the material and the text, clipped by the card, and **masked out of card-local 0–48** (linear ramp to full at 80) | The readiness moment (T8″) only |
| `stageBadge` | Disc `stageInk`, 22 dp, with a 3 dp ring in the material's colour (`#121212` at 55 %) that cuts it out of the bars; ‖ two bars 2.25 × 9 dp, 2.7 dp apart, pill ends, `#161616`. In the scene the same badge at 28 dp: every dimension × 28/22 (ring 3.8 dp, ‖ 2.9 × 11.5 dp) | Paused, on the stage (4.22.3) and in the scene (4.21.4) |
| `stageTrack` | White at 22 % (track), `stageInk` (fill), 2 dp, radius 1 | The transcribing pill's track |
| `stageRing` | 1 dp inner ring, white at 7 %, Dark theme only | Separates the card's bottom stop `#0A0A0A` from dark paper `#0F0F0F` |

**Stage contrast, computed** against the card's centre column, its lightest point, at each element's card-local y:

| Pair | Ratio | Requirement |
|---|---|---|
| Time `#999999` (y 27–47, in the highlight band) | 4.82–5.28 | Text 4.5 |
| Title `#EDEDED` (y 20–76) | 11.34–14.49 | Text 4.5 |
| Text `#999999` (y 84–156) | 6.07–6.55 | Text 4.5 |
| **Text under the shimmer's peak** (10 %, masked above 48) | ≥ 4.51 | Text 4.5 (unmasked, the time would drop to 3.51: F1) |
| Length numeral `#EDEDED` / unit `#999999` (footer) | 16.49 / 6.80 | Text 4.5 |
| Live latest `#EDEDED` / older `#999999` (below the 34 dp mask) | 16.49 / 6.41 | Text 4.5 |
| "Storage is full" `#EDEDED` (y 196–220) | 16.44–16.71 | Text 4.5 |
| Pause badge: disc vs material (y 56–78, lowest) / ‖ `#161616` on the disc | 13.36 / 15.46 | Non-text 3.0 |
| Glass label `#EDEDED` (Copy, "Transcribing · 82%") | 13.51 / 13.45 | Text 4.5 |
| White Copy label `#111111` on `#DCDCDC` | 13.77 | Text 4.5 |
| Track fill vs track | 7.08 | Non-text 3.0 (the label also states the percentage) |
| Stage against Light paper | 12.0–19.8 | Non-text 3.0 |

The glass pill measures 1.22 against the card; its label and glyph carry the meaning (the tonal-fill rule, 2.1). **Contrast setting** (API 34+): `stageMuted` → `#B0B0B0`. **High-contrast text:** receding is off and all stage text is `stageInk`.

### 2.3 Typography

**Family.** Inter 4.x variable (wght 100–900, opsz 14–32), bundled as `res/font/inter_var.ttf`. The lockup already uses Inter 600, and `design/brand/Inter-LICENSE.txt` is the OFL text.
- **Subset ranges:** U+0000–024F, U+0300–036F, U+0370–03FF, U+0400–04FF (incl. Ukrainian ґ), U+2000–206F, U+20AC, U+2116, U+2190–2193, U+2212. These cover all 25 Parakeet v3 languages, including Romanian ș/ț and Maltese ħ/ġ/ż.
- Keep the `wght`, `opsz`, `tnum`, `case`, `calt` and `locl` tables. Measure the subset size and record it in `docs/design.md`. Missing glyphs fall back to the system sans.

**Two Compose families.** Variation settings belong to a `Font`, not to a `TextStyle`, so optical size needs two families.
- `FocalText`: `Font(R.font.inter_var, weight = FontWeight(W), variationSettings = FontVariation.Settings(FontVariation.weight(W), FontVariation.Setting("opsz", 16f)))` for W ∈ {400, 420, 500, 600}.
- `FocalDisplay`: the same with opsz 32 for W ∈ {250, 600}. Used only by `sceneTimer`, `stageLength`, `title` and `headline`.

**Scale.** Tracking follows Inter's dynamic metrics, `em = −0.0223 + 0.185·e^(−0.1745·size)`, except the two scene tokens, which are tuned by eye on the dark material.

| Token | Family | sp / line | Weight | Tracking (em) | Features | Use |
|---|---|---|---|---|---|---|
| `sceneTimer` | Display | 76 / 84 | 250 | −0.035 | `tnum` | The scene timer. Size fixed per scene (4.21.3), range 56–76 |
| `sceneText` | Text | 20 / 30 | 420 | −0.016 | | Live text in the scene |
| `stageLength` | Display | 40 / 48 | 250 | −0.02 | `tnum` | The note's length on the stage: numerals only; the unit is `meta` 500 `stageMuted`, 5 dp after it |
| `stageText` | Text | 17 / 24 | 400 | −0.0128 | | Stage text, the stage's live text (the `field` metrics); at 500 `stageInk`: the storage-full line |
| `dateline` | Text | 15 / 20 | 500 | −0.0088 | `tnum` | The stage's time, on the title's first baseline: "16:05", "Yesterday", "Mon", "28 Oct" |
| `title` | Display | 28 / 34 | 600 | −0.0209 | | Note title, Settings and Licenses titles, the empty library's headline (centred) |
| `headline` | Display | 22 / 28 | 600 | −0.0183 | | Stage title, rationale-sheet title |
| `body` | Text | 18 / 28 | 400 | −0.0143 | | Paragraphs, reading and editing. `LineBreak.Paragraph`, `Hyphens.None` |
| `rowTitle` | Text | 17 / 24 | 600 | −0.0128 | | Library row title, live-row label, licence names |
| `field` | Text | 17 / 24 | 400 | −0.0128 | | Search, find and replace fields, the empty library's second line (`muted`, centred); settings row titles use 500 |
| `label` | Text | 16 / 20 | 600 | −0.0110 | `tnum` on numbers | 56 dp pill labels (Record, Finish, Resume, Copy), Done, text buttons; menu items at 400 |
| `action` | Text | 15 / 20 | 600 | −0.0088 | `tnum` on numbers | Labels inside 48 dp controls: Replace all (N), the feedback action, Retry, the jump pill (500) |
| `secondary` | Text | 15 / 22 | 400 | −0.0088 | `tnum` on numbers | Previews, notices, descriptions, hint. Run-ins at 500 |
| `meta` | Text | 15 / 20 | 400 | −0.0088 | `tnum` | Note meta, the scene's state line (500), feedback text, find count, row durations, provenance line |
| `section` | Text | 14 / 20 | 600 | −0.0062 | | Day labels, settings group heads, search count head |
| `caption` | Text | 13 / 18 | 400 | −0.0032 | `tnum` | Player times, version footer |

Rules:
- **Tabular figures** on every number that changes in place: timers, durations, player times, percentages, counts, the stage's length and percentage.
- **Nothing below 13 sp.**
- **No weight changes inside `body` to show state** (bolding reflows lines). Focus is colour; the lens, matches and the edit sheet are shapes drawn behind glyphs. Search snippets are static, so their hits may use 600.
- **Reading measure:** the text column is capped at 600 dp; on a 412 dp phone it is 364 dp (about 40 Latin or 38 Cyrillic characters at 18 sp).
- **Sentence case everywhere.** Typographic quotes and apostrophes (“ ” ’) and the middle dot `·` with spaces.

### 2.4 Shape

| Token | Radius | Used for |
|---|---|---|
| `pill` | 50 % (`CircleShape` / `RoundedCornerShape(50)`) | Every button and circle, the search field, find fields and nav pill, the feedback pill, segmented track and thumb, jump pill, the wave's bars and the playhead |
| `sheet` | 28 dp | The stage; bottom-sheet top corners; the morph's corners (28·(1 − p)); page corners in the predictive-back preview and in T16′/T7b; the seam-side corners of the library and settings pages while the swipe is in flight (4.23) |
| `menu` | 16 dp | Dropdown menus |
| `block` | 12 dp | Edit sheet; row and settings press plates |
| `mark` | 8 dp | Inline highlights: playback lens, find matches, the search-arrival highlight |

`MaterialTheme.shapes = Shapes(extraSmall = 8.dp, small = 8.dp, medium = 12.dp, large = 16.dp, extraLarge = 28.dp)`. No other radius literals.

### 2.5 Spacing and layout grid

- **Grid.** 4 dp. Allowed steps: 2, 4, 8, 12, 16, 20, 24, 32, 36, 40, 48, 56, 64. Exceptions are derived optical offsets that this document names explicitly (for example the empty library's group, centred on the scene's start-hero centre, 4.22.5).
- **Gutter.** 24 dp for text, rows and every bottom element. Icon buttons sit 12 dp from the screen edge, so a 24 dp glyph's edge lands on the 24 dp line.
- **The bottom column.** x 24–388 (364 dp wide). On windows wider than 412 dp it is the content column (`widthIn(max = 600.dp)`, centred). Everything at the bottom is laid out inside it:

| Element | Height | Bottom edge | Ref y |
|---|---|---|---|
| Dock (circle 24–80, gap 12, pill 92–388; pill alone 24–388) | 56 | navigation inset + 16 | 819–875 |
| Player strip | 48 | dock top − 8 | 763–811 |
| Feedback pill | 48 (min) | topmost visible bottom element − 8 | 763–811 (dock only), 707–755 (strip visible), 457–505 (find rows) |
| Search field (IME open) | 56 | IME top − 8 | 561–617 |
| Find row 2 (IME open) | 48 | IME top − 8 | 569–617 |
| Find row 1 (IME open) | 48 | row 2 top − 8 | 513–561 |

- **IME rule.** The lowest bottom field's bottom edge = `min(IME top, navigation-inset top − 8) − 8`. Ref: with Gboard at 625 it is 617; with no IME inset (floating, split or hardware keyboard, or the IME hidden while the field stays open) it is 875, the dock's own bottom edge, so the search field takes the dock slot (819–875) and the find rows sit at 771–819 and 827–875. The dock is hidden whenever an IME-driven field is open, and the dock never moves vertically for any other reason.
- **Top bar.** 64 dp below the status bar (ref 40–104); icon targets 48 dp, centred at status + 32 (ref y 72). **The library has no top bar** (X3): the stage starts 16 dp under the status bar (ref 56).
- **Touch targets** are at least 48 × 48 dp everywhere. Words are the one exception (8.3).
- **Content padding.** Scrolling content reserves the bottom column's height + 64 dp so the last line can scroll clear of the fade.

### 2.6 Elevation, fades and scrims

- **Flat.** Surfaces are separated by tone, not by shadows or dividers. Two exceptions: menus (Light: `0 1 2 rgba(0,0,0,.08)` + `0 8 24 rgba(0,0,0,.14)`; Dark: no shadow, a 1 dp `raisedRing`) and the scene's Finish pill (2.2).
- **Scrim** only behind the rationale sheet.
- **Edge fades replace bars and dividers.** They are draw-only overlays (`drawWithContent`), alpha-only, identical on API 26+.
  - **Top (note, settings):** solid `paper` from the screen top to the top-bar bottom (ref 104), then a 16 dp gradient to transparent.
  - **Top (library), two layers (G1):** the **status edge**, solid `paper` to the status-bar bottom (ref 40), gradient to 56, always at full alpha: at rest it covers only the 16 dp of paper above the stage, and anything scrolled up slides under a 16 dp soft edge. The **pin band**, solid to ref 80, gradient to 96: its alpha is clamp01((56 − the stage's bottom)/16), so it arrives only as the stage's bottom passes under the status edge and never tints the stage. Day labels are drawn above both (4.12.1).
  - **Top (find):** solid to the status-bar bottom (ref 40), gradient to ref 88.
  - **The settings swipe** has no scrim and no shadow: the two pages are separated by the 8 dp `s2` seam and their 28 dp seam-side corners (4.23.2). Each page keeps its own top fade and moves with it.
  - **Bottom (paper):** a 64 dp gradient from transparent to `paper` that ends 8 dp above the topmost bottom element, then solid `paper` to the screen bottom. Ref with the dock only: gradient 747–811. It moves with the feedback pill and the strip (`move`).
  - **Bottom (find and search):** a 48 dp gradient ending at the top of the topmost bottom element, solid below. Find: 465–513; while "Replaced N · Undo" shows above row 1, 409–457.
  - **Scene text region:** content fades out over the region's top 128 dp, with `graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }` and a `DstIn` vertical gradient. This is alpha, not blur.

### 2.7 Iconography

- **Source.** Material Symbols Rounded, FILL 0, wght 400, GRAD 0, opsz 24, as vector drawables: `arrow_back`, `keyboard_arrow_down`, `keyboard_arrow_up`, `search`, `close`, `more_vert`, `edit`, `undo`, `redo`, `content_copy`, `check`, `arrow_downward`, `arrow_upward`, `open_in_new`, `chevron_right`, `subdirectory_arrow_right` (the ↳ in the replace field).
- **Filled (FILL 1):** `play_arrow`, `pause`.
- **Custom vectors:** record dot (10 dp in a pill, 12 dp in a circle), finish square (12 dp, radius 3), the pause badge (`stageBadge`, 22 dp on the stage, 28 dp in the scene), and the nine bars. There is no paused ring ○ and no ‖ in the state line. Focal draws no settings glyph anywhere (Android draws the App info gear itself).
- **Size and colour.** 24 dp, `ink` (`faint` when disabled). Glyphs inside 56 dp pills are 20 dp with an 8 dp gap before the label. Glyphs inside 48 dp controls are 24 dp, except the feedback action's undo glyph (18 dp, 6 dp gap).
- All top-bar icons are `ink`.
- **RTL.** Back, undo, redo, chevrons, arrows and ↳ set `autoMirrored = true`.

### 2.8 Motion

One voice: critically or near-critically damped springs with no visible bounce, defined once in `FocalMotion`. They match `MotionScheme.standard()` value for value; when material3 1.5 is adopted, `press`/`move`/`stage`/`fadeFast`/`fade`/`fadeSlow` map to `fastSpatialSpec`/`defaultSpatialSpec`/`slowSpatialSpec`/`fastEffectsSpec`/`defaultEffectsSpec`/`slowEffectsSpec`. Settle times are computed from the spring solution (overshoot 0.15 % at ζ 0.9, invisible).

| Token | Compose | Settle to 0.1 % | to 1 % | Used for |
|---|---|---|---|---|
| `press` | `spring(dampingRatio = 0.9f, stiffness = 1400f)` | 224 ms | 137 ms | Press scale 1 → 0.97; lens glide within a line; playhead 24 ↔ 32 dp; menu scale-in; Copied check scale |
| `move` | `spring(0.9f, 700f)` | 317 ms | 194 ms | Bounds and sizes (dock morphs, Resume pill, search circle → field), `animateItem`, follow-scroll, strip, feedback pill, jump pill, provenance line, sheets, the settings swipe's settle and the peek (4.23); the readiness pill's width |
| `stage` | `spring(0.9f, 300f)` | 484 ms | 296 ms | What takes the whole window: the recording surface (expand, fold, the drag's settle (clamped, 2.8.6), the settle into the note, the lift) and the Finish scroll-to-top; row and stage ↔ note transforms, opening and folding back (6.1); settings opening or closing with no finger on them (←, Esc, Back, the hint's Open, Ctrl+, the action, 4.23). A 0..1 progress that runs on it, or on `move` across the window, runs to 0.1 % (the settle time here), not to Compose's default 1 % |
| `fadeFast` | `spring(1f, 3800f)` | 150 ms | 108 ms | Icon and label swaps; lens line-jump; menu exit; hint exit; row press plate; feedback exit |
| `fade` | `spring(1f, 1600f)` | 231 ms | 166 ms | Layers, menus, top-bar items, notices, theme colour switch, scene fade during fold and settle |
| `fadeSlow` | `spring(1f, 800f)` | 326 ms | 235 ms | Alpha of arriving text; the glow's arrival |
| `develop` | `spring(1f, 300f)` | 533 ms | 383 ms | Text colour only: faint → ink (arrival), ink → muted (recede), muted → ink (rack focus) |

Write them as `spring<T>(dampingRatio, stiffness)` for `Float`, `Dp`, `Color`, `IntOffset`, `Rect` and `IntSize`. **The only tweens in the product:**

| Token | Compose | Used for |
|---|---|---|
| `reducedCrossfade` | `tween(durationMillis = 150, easing = LinearEasing)` | Expand, fold, settle and stage ↔ page under reduced motion (2.8.4) |
| `sweep` | `infiniteRepeatable(tween(1200, easing = LinearEasing))` | The writing head's working sweep phase (note page) |
| `readyShimmer` | `tween(720, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))` for the band's x; its alpha is sin(π·u), u the tween's linear progress | The one pass of light in the readiness moment (T8″) |
| IME-driven positions | No animation spec: positions read `WindowInsets.ime` in the layout phase every frame (API 30+ follows the system curve exactly) | Search field, find rows, dock hide/show while the IME moves |

#### 2.8.1 Composite effects

| Effect | Definition |
|---|---|
| **develop** (arrival) | Alpha 0 → 1 (`fadeSlow`) + colour `faint` → `ink` (`develop`) + translateY 8 → 0 dp (`move`), starting 60 ms after the item's height starts opening (`move`). When a stretch extends an open paragraph, only the new character range develops (an animated `SpanStyle(color)` on that range; no translate). Each stretch develops once, keyed by `(paragraphId, rangeStart)` with an `arrivedAt` timestamp; items whose `arrivedAt` is more than 600 ms old render settled, so scrolling never replays it. On the scene, `faint` is `#EDEDED` at 25 % and `ink` is `sceneInk`. |
| **recede** | Colour `ink` → `muted` (`develop`), 140 ms after the new text starts developing. On the scene: `sceneInk` → `sceneMuted`. |
| **rack focus** | Every receded paragraph returns to `ink` (`develop`), staggered 24 ms per paragraph from the bottom up. Only visible items stagger (at most 12, so at most 264 ms); off-screen items snap. |
| **lens** | The lens rectangle is an `Animatable<Rect>`. Within a line, x and width follow `press`. Across lines it fades out (`fadeFast`), jumps, and fades in (`fadeFast`); it never slides diagonally. |
| **working sweep** | Bar i's height = profile × (0.35 + 0.65·(sin(2π·t/1200 − 0.55·i) + 1)/2), t in ms. Runs only while visible and STARTED. |

#### 2.8.2 Scene choreography constants

| Constant | Value | Meaning |
|---|---|---|
| `lightUpStagger` | 24 ms | T1′: bar pair d lights up in the stage's live slot at +24·d ms (alpha `fade`), at the entering level (the full profile, history seeded at v = 1). From the empty library (T1′e) pair d rises from the resting level to the live level at +24·d ms (`fade`) |
| `emptyTimerDelay` | 160 ms | T1′e: "0:00" is born in its scene slot only after the invitation has left (fade-through) |
| `lightUpGlowDelay` / `lightUpTimerDelay` | 60 / 40 ms | T1′: glow (`fadeSlow`) and "0:00" (`fade`) |
| `noteExit` | `fadeFast` from the trigger | T1′: the stage's note content leaves with the clip's top-left |
| `libraryScale` / `libraryDim` | 1 − 0.06·p / 0.30·p | The library behind the morph (6.4) |
| `liveLayoutSwitch` | p ≥ 0.95 | T1′: the library switches to its live layout only while covered |
| `settleFadeStart` | 50 % of the clip travel | The scene's alpha starts falling during the settle into the note (T7, `fade`) |
| `settleTitleDelay` / `settleMetaDelay` | 120 / 200 ms | Title and meta develop on the page under the settling scene (T7, T7b) |
| `settleParagraphDelay` / `rackStagger` | 180 / 24 ms | Paragraph alpha (`fadeSlow`), staggered bottom-up |

There is no fold alpha fade and no Dark-theme pill tint: the scene always grows out of, and folds into, the same material.

#### 2.8.3 Other constants

| Constant | Value | Meaning |
|---|---|---|
| `copiedHold` | 1600 ms | "Copied" shows, then reverts with `fadeFast` |
| `readyMorph` | +0 | T8″: the pill's width (`move`), fill (`fade`) and content fade-through (`fade`) all start on the Ready commit's frame; `Confirm` fires on it |
| `readyShimmerDelay` | +160 ms | T8″: the one shimmer starts as the pill has turned white; it runs 720 ms (`readyShimmer`) |
| `readyHapticGuard` | 1500 ms | The Ready tick is skipped if any Focal haptic fired within this window (2.9) |
| `emptyMaterialFade` | `fadeFast` from the trigger | T1′e: the material's alpha a, so the instrument's box never pops dark |
| `developDelay` / `recedeDelay` | 60 / 140 ms | 2.8.1 |
| `bodyFadeDelay` | 80 ms | Note body fade-in during the row → note transform |
| `loadingGrace` | none | The library shows nothing until content exists, never bare material (S5) |
| `searchDebounce` / `findDebounce` | 120 / 120 ms | |
| `autosaveDebounce` | 600 ms | |
| `playPreroll` | 500 ms | Playback starts 0.5 s before the tapped word |
| `arrivalHighlight` | 1500 ms | Search-result hit highlight, then `fade` out |
| `notifPermissionDelay` | 600 ms | After capture is confirmed |
| `feedbackDuration` | 8000 ms; indefinite while touch exploration is on; otherwise `AccessibilityManager.calculateRecommendedTimeoutMillis(8000, FLAG_CONTENT_TEXT or FLAG_CONTENT_CONTROLS)` | |
| meter | attack 40 ms, release 180 ms, history step 80 ms | 3.3 |
| `hintRecordings` | 3 | The first-text hint shows for the first three recordings only |

There are **no double-tap gestures** anywhere, so every tap fires on pointer-up without the ~300 ms double-tap timeout. This is what makes tap-to-play feel instant.

#### 2.8.4 Reduced motion

Applies when `ValueAnimator.areAnimatorsEnabled()` is false (animator duration scale 0, or "Remove animations").
- **Expand, fold, the settle (T7, T7b), stage ↔ page (T16′) and Record from the empty library (T1′e)** become a 150 ms linear alpha crossfade (`reducedCrossfade`) between the full-window destination and the screen underneath, with no spatial travel. They run inside `withContext(FullMotion)`, where `FullMotion` is a `MotionDurationScale` with `scaleFactor = 1f`, so the crossfade still plays at animator scale 0. An instant cut from white paper to the near-black material is a full-screen luminance flash, which is worse for light-sensitive users than a short fade.
- **The readiness moment** has no shimmer and no morph: the pill snaps to the white Copy. `Confirm` still fires (it is not motion) and TalkBack's spoken "Note ready" stays.
- **The settings swipe** still tracks the finger, without spatial motion: the frame is a fade-through on paper (library 1 − smoothstep(0, 0.5, s), settings smoothstep(0.5, 1, s)); release uses the same thresholds and completes with `reducedCrossfade` from the current s. The **peek** does not move the library: the seam and the settings page's leading 32 dp fade in at the right edge (150 ms), hold 400 ms, fade out (150 ms) (4.23.5).
- **The drag still works and still tracks the finger, without spatial motion:** p is computed exactly as in 6.5, but the frame is the library with the full scene laid over it at alpha p (no clip growth, no travel, no scaling). Release uses the same thresholds and completes with the 150 ms linear crossfade from the current alpha. Swipe up and predictive back do the same in reverse.
- **Everything else snaps.** Springs complete instantly (Compose honours the system scale). Develop becomes an instant colour set; the lens jumps; the wave's light-up and the lift snap; the working sweep becomes the static mark at 60 % alpha.
- **The live meter keeps updating**, because it is information, but drops the history smear: all bars follow the current level.

#### 2.8.5 Predictive back

For every in-app back layer and page except the scene and the settings page:
- During the gesture the page scales 1 → 0.9, its corner radius goes 0 → 28 dp, and it shifts `(W/20 − 8)` dp toward the swipe edge. Progress is mapped through `PathInterpolator(0f, 0f, 0f, 1f)`.
- **Commit:** reverse container transform into the origin (`stage`) if the origin is visible (for a note opened from the stage: into the stage, reverse T16′), otherwise a fade-through (`fade`). **Cancel:** spring back (`press`).
- **The scene:** the gesture drives the morph itself (6.4): `p = 1 − 0.4·PathInterpolator(0f, 0f, 0f, 1f)(backEvent.progress)`, so a full back swipe previews exactly the 40 % release threshold. Commit: `animateTo(0)` on `stage`. Cancel: `animateTo(1)` on `move`. The swipe edge does not shift the scene sideways: the morph's target is the stage, not an edge.
- **Settings:** the gesture drives s itself (4.23.4): s = 1 − progress, so the page follows the gesture's progress as the in-app swipe follows the finger. Commit: `animateTo(0)` on `stage`. Cancel: `animateTo(1)` on `move`.
- Back layers resolve in this order: menu → keyboard (system) → find → edit (= Done) → search → player → scene → page (Note, Licenses) → settings.

#### 2.8.6 Expansion constants

| Constant | Value | Meaning |
|---|---|---|
| `expansion` | `Animatable(0f)` with `updateBounds(0f, 1f)` | p. One value for drag, taps, Back and predictive back |
| `travel` D | stage bottom → window bottom (ref 619 dp: 296 → 915) | Finger travel for p 0 → 1. The card's bottom edge follows the finger 1:1 |
| `releaseThreshold` | 0.4 of the travel (expand: p ≥ 0.4; collapse: p ≤ 0.6) | Below it, the surface springs back |
| `flingVelocity` | 1000 dp/s | A faster release completes in the fling's direction regardless of p |
| `settleSpec` | `stage` = `spring(0.9, 300)`, `initialVelocity = v / D`, clamped at the bounds | No bounce: if the spring reaches the target bound it ends there, so no overshoot frame is ever drawn. From rest: 484 ms to 0.1 % |
| `cancelSpec` | `move` | Predictive back cancelled |
| `backPreview` | 0.4 | Predictive back drives p down to 0.6 at most before commit |
| `catch` | a touch-down during a settle stops it | The surface can be grabbed mid-flight; the drag continues from the current p |
| `statusFlip` / hysteresis | clip top ≤ status top + 12 dp / back above + 16 dp | Status-bar icon appearance (6.2) |
| `navFlip` / hysteresis | clip bottom ≥ navigation inset top + 12 dp / back below + 8 dp | Navigation-bar icon appearance (6.2) |
| `dockDress` | the material's bottom edge e = clip bottom (window y) | **A wipe, not a fade.** Each live dock control is drawn in both dresses: the paper dress clipped to y ≥ e, the scene dress to y < e, glyphs and labels included. The edge sweeps the dock row from p 0.845 (e 819) to p 0.935 (e 875). Every part of every control keeps its own dress's contrast; a crossfade leaves a glass circle invisible on paper or an `inv` pill invisible on the material. T1′e: the edge starts at the instrument's box bottom (380) and sweeps the dock row from p 0.820 (e 819) to p 0.925 (e 875) |

#### 2.8.7 Settings swipe constants

| Constant | Value | Meaning |
|---|---|---|
| `settingsProgress` | `Animatable(0f)` with `updateBounds(0f, 1f)` | s. One value for the finger, springs, Back, predictive back, keys and the accessibility action |
| `settingsTravel` D_s | W + 8 (ref 420 dp) | Finger travel for s 0 → 1; both pages follow the finger 1:1 |
| `seam` | 8 dp, `s2` | Between the two pages while 0 < s < 1 |
| `edgeExclusion` | max(32 dp, `WindowInsets.systemGestures` on that side) | A touch-down closer than this to either side never starts the swipe, so the system Back gesture is never taken |
| `directionLock` | abs(Δx) > 1.5·abs(Δy), decided once, when the finger passes the touch slop (≈ 8 dp) | Horizontal: the swipe claims the stream. Otherwise the list, the expand drag and long-press keep it |
| `settingsThreshold` | 0.35 of D_s (147 dp, about 35 % of the width; open: s ≥ 0.35; close: s ≤ 0.65) | Below it, the pages spring back |
| `settingsFling` | 1000 dp/s | A faster release completes in the fling's direction regardless of s (the product's fling value, 2.8.6) |
| `settingsSpec` | `move`, `initialVelocity = −v / D_s`, clamped at the bounds | No bounce. From rest: 317 ms to 0.1 % |
| `peekOffset` / `peekHold` | 32 dp (s = 32/420) / 400 ms | The hint's reach and hold (4.23.5) |
| `peekIdle` / `peekMax` | 1200 ms / 2 per install | The library must be idle this long; never more than two peeks, none after settings has been opened |

### 2.9 Haptics: four moments, no others

| Moment | Compose `HapticFeedbackType` | View constant | Fires when | Below the constant's API |
|---|---|---|---|---|
| Recording started | `ToggleOn` | `TOGGLE_ON` (API 34) | The service reports capture running, not on touch-down and not when the expand starts. A failed start never ticks. | Compose's built-in fallback. Verify on an API 26 device that it is a short tick, not a buzz. |
| Finish | `Confirm` | `CONFIRM` (API 30) | On tap of Finish in the scene or the library dock, after the service accepts the command. The notification's Finish is silent. | Compose fallback |
| **Ready** | `Confirm` | `CONFIRM` (API 30) | On the first frame of the readiness morph (T8″ +0 on the stage; T8 on the page), only when the transition is on screen (activity RESUMED and the note's Copy visible), at most once per note, and **not** if any Focal haptic fired in the last 1500 ms (a short note that is ready right after Finish). Off screen there is no tick; the "Note ready" notification covers it. | Compose fallback |
| Copy | `Confirm` | `CONFIRM` (API 30) | After `ClipboardManager.setPrimaryClip` returns without throwing (Copy pill on the page or the stage, row menu Copy text) | Compose fallback |

Ready replaces nothing; the total stays small. Silent: Pause, Resume, auto-pause, expand and collapse (by tap, drag, Back or predictive back), the settings swipe and the peek, play, scrub, edit, Done, delete, Undo, Replace all, theme change and errors. Long-press and text selection keep the platform's own feedback. Use `LocalHapticFeedback.current.performHapticFeedback(...)` only; no `Vibrator`, no `VIBRATE` permission.

**No sound, and no setting for one.** Focal is used in meetings, on calls and in quiet rooms; a chime would also be recorded if the next note starts at once; Focal has no other UI sounds; and "nothing to configure" is a product principle. The background case already has the `ready` notification channel's own sound, which the user controls.

---

## 3. The nine bars

### 3.1 Geometry (from `docs/mark.svg`, unchanged)

| | Bar 1 | 2 | 3 | 4 | 5 (centre) | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|
| Height (units) | 7 | 12 | 19 | 30 | 40 | 30 | 19 | 12 | 7 |
| Alpha | .18 | .30 | .50 | .75 | 1 | .75 | .50 | .30 | .18 |
| Blur σ (units) | 2.2 | 1.6 | 1.0 | .45 | 0 | .45 | 1.0 | 1.6 | 2.2 |

- **Box.** 54 × 42 units. Bar width 3.2, pitch 6.2, first bar at x = 0.6. Bars are vertically centred in the box with corner radius = width / 2.
- **Scale.** `s = boxHeight / 42`, box width = 54·s. At 176 dp: s = 4.190, box 226.3 × 176, bars 13.4 dp wide, outer σ 9.2 dp.
- **Centre bar.** A vertical gradient brush: on paper `centreBarTop → centreBarBottom`; on the scene `#FFFFFF → #CFCFCF`. The other bars are flat (`ink` on paper, `#EDEDED` on the scene) at their alpha.
- **Glow (scene only).** A copy of the centre bar 5.6 units wide (1.75×) and 1 unit taller at each end, white at 34 %, blurred σ 2.6 units, drawn behind the centre bar.
- **Playhead.** The centre bar alone, 4 × 24 dp (`ink`, pill ends), used as the player's thumb (4.10).

### 3.2 Instances

| Instance | Box height | Colour | States | Rendering |
|---|---|---|---|---|
| Scene hero, before text | 176 dp (226 wide) | Scene | entering, live, paused (+ the 28 dp badge, 4.21.4) | Live tier (3.4) |
| Scene hero, with text | 136 dp (175 wide) | Scene | live, paused (+ the 28 dp badge) | Live tier |
| Scene hero, compact (font scale ≥ 1.5) | 120 before text / 88 with text | Scene | as above | Live tier |
| **Stage live wave** | 56 dp (72 wide) at x 44–116, y 76–132 | Scene | live (with glow), paused (+ the 22 dp badge, 4.22.3) | Live tier |
| **Wave in flight** (the morph) | lerp(56, 136 or 176, p) | Scene | live, paused | Sprite tier on API 28+ (3.4) |
| Writing head (note page) | 40 dp | Paper | working, paused | Live tier, no glow |
| **Empty library, instrument at rest** | 112 dp (144 wide), box x 134–278, y 268–380, centred on (206, 324) | Paper | rest | Rasterised once per size and theme (still, no frame loop). In T1′e the overlay draws it in both dresses from the sprite tier, like the wave in flight |
| Rationale sheet | 40 dp | Paper | static | Rasterised once |
| Settings footer | 32 dp | Paper | static | Rasterised once |
| Gap row (partial failure) | 16 dp | Paper, 40 % alpha | static | Rasterised once |

There is no loading mark. Bars appear only as live state or data, with one exception (X4): the empty library's instrument at rest, the state the scene wakes from. It is not the logo: no tile, no name, and never the full mark profile.

| State | Bar height | Alpha multiplier | Glow |
|---|---|---|---|
| **static** | Full mark profile | 1 | n/a |
| **live** | `profile × (0.22 + 0.78·v)`, v = the bar's level (3.3). Silence draws the mark at 22 %. | 1 | Scene only, follows the centre bar |
| **paused** | Full mark profile (the logo), heights eased with `move` | 0.4 (`fade`) | Off (`fade`) |
| **working** | Full profile × sweep (2.8.1) | 1 (0.6 static under reduced motion) | n/a |
| **rest** (empty library, on paper) | `profile × 0.42`, still | 1 (the mark's own alpha falloff; outer bars blurred per 3.1) | Off |
| **entering** (T1′ light-up) | Live, with the meter history seeded at v = 1, so the bars light up as the full logo and relax to the live level with the 180 ms release | 1, pair d from +24·d ms | Fades in from +60 ms (`fadeSlow`) |

### 3.3 Meter maths

- **Input.** The capture service publishes the RMS of each 20 ms buffer into a lock-free `AtomicInteger` (float bits), never a `StateFlow` collected at the root.
- **Level.** `dB = 20·log10(max(rms, 1e-9) / 32768)`; `n = clamp((dB + 50) / 40, 0, 1)` maps −50…−10 dBFS onto 0…1.
- **Smoothing** (one pole, per frame, in the draw lambda): α = `1 − exp(−Δt / τ)` with τ = 40 ms when n > s (attack) and 180 ms otherwise (release); `s += (n − s)·α`.
- **History.** A ring buffer of `(frameTime, s)` covering at least 400 ms. Bar distance d ∈ 0…4 from the centre reads `s(t − d·80 ms)` by linear interpolation. Bars ±d are equal, so the wave is symmetric like the mark: the present is sharp at the centre and the past blurs outward.
- **Drawing.** Read the level only inside `drawWithCache { onDrawBehind { … } }`, so nothing recomposes per frame. Request frames with `withFrameNanos` in a `LaunchedEffect` that runs only while the lifecycle is STARTED and the display is on. Use `Modifier.preferredFrameRate(FrameRateCategory.Normal)`.
- **Semantics.** Every wave is `invisibleToUser()`; the merged timer node (scene) or the stage node carries the meaning.

### 3.4 Rendering (Compose)

**Live tier, chosen once per process by API level** (`WaveRenderer`):

| API | Outer-bar blur | Glow | Light-up blur growth (T1′) |
|---|---|---|---|
| 31+ | Each distance pair d = 1…4 is drawn into its own `graphicsLayer` with `renderEffect = BlurEffect(σd·s·k, σd·s·k, TileMode.Decal)`. Four small layers plus one for the glow. | Fifth layer, `BlurEffect(2.6·s)` | k animates 0 → 1 with the bar's alpha during the light-up, so the outer bars arrive out of focus |
| 28–30 | **Sprite cache.** Each blurred bar is drawn from an `ALPHA_8` sprite tinted with `ColorFilter.tint`. A sprite per distance d holds a blurred capsule rendered once with `BlurMaskFilter(σd·s px, NORMAL)` on a software `Canvas`; bars taller than 6σ are drawn as three slices (top cap, stretched middle, bottom cap), which is exact because a blurred capsule's middle rows are identical. Bars shorter than 6σ use a sprite per 2 dp height bucket (at most 12 buckets per distance). LRU cap: 64 sprites. | One sprite per height bucket, same slicing | None: bars arrive by alpha |
| 26–27 | None: alpha only | None | None |

- **Radius vs σ.** `BlurEffect`, `RenderEffect.createBlurEffect` and `BlurMaskFilter` all take a radius, which Skia converts with σ = 0.57735·radius + 0.5. Pass radius = (σ_px − 0.5) / 0.57735 so the result matches the σ values in 3.1.
- If profiling on a phone with 6 GB of RAM or less shows the 31+ path costs more than the sprite path, switch API 31+ to sprites with the `WaveRenderer.Sprites` flag. Both look identical at rest.
- **In flight** (the morph, 0 < p < 1) the wave is drawn from the sprite cache on API 28+, including 31+, with 2 dp height buckets, so a drag never creates a `RenderEffect` per frame; it returns to its API's live tier at p = 0 or 1. The two look identical at rest. API 26–27: alpha only.
- **Static instances** (footer, rationale, gap row, the empty library's resting instrument) are rasterised once per size and density into an `ImageBitmap` on a software canvas, where `BlurMaskFilter` works on every API level, so they are blurred even on API 26–27. Cache them for the process lifetime.
- **No per-frame allocation.** Brushes, paths and paints live in `drawWithCache`. The per-frame work is nine rectangles (or nine sprite draws) and one glow.

**The scene material** (4.21.1) is a separate composable whose draw lambda reads no animated state, so it is recorded once and never invalidated by the wave. Grain is a 256 × 256 px `ALPHA_8` noise tile generated once per process (seeded fractal value noise, 2 octaves), drawn as `ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))` at 7 % white. Check the gradient for banding on an 8-bit OLED; the grain is also the dither.

---

## 4. Components

Each entry lists anatomy, size, states and motion. "Pressed" is the `press` state layer unless stated. Positions are ref.

### 4.1 Top areas

#### 4.1.1 Library top (content only)
- **There is no top row and no settings control.** The status bar, 16 dp of paper, then the stage at ref y 56 (4.22.1). No title, no app name, no wordmark, no logo, no day label at rest (O1, V5). Settings is the page beside the library, one horizontal swipe away (4.23).
- **Sticky day label.** Once a day label in the list reaches the pinned line, it pins there (4.12.1): x 24, ref 52–72 (status + 12). It disappears again when the list returns.
- **Fade.** The library top fade (2.6) has two layers: the status edge, always on (invisible at rest: it covers only paper), and the pin band, which arrives only once the stage has scrolled under the status edge. The stage is never tinted (G1).

#### 4.1.2 Note top bar
- 64 dp (ref 40–104), transparent over the top fade.
- ← "Back" at x 12–60. ✎ "Edit" at x 304–352. ⋮ "More options" at x 352–400. All `ink`.
- **Collapsed title.** In reading mode a single-line `rowTitle` copy of the note title fades in (`fade`) once the content title's bottom passes under the bar bottom (ref 104). It sits from x 72 to 296 (the pencil's left edge − 8), ellipsised, and fades out when the title returns.
- **Variants.** Transcribing / Waiting: ← and ⋮ only (✎ fades in at Ready, `fade`). Text only: ← ✎ ⋮. Find: all items fade out (`fade`). Edit: replaced by the edit bar (4.1.3).

#### 4.1.3 Edit bar
- Replaces the note top bar (64 dp, ref 40–104), crossfading (`fadeFast`).
- ↶ "Undo" at x 12–60 and ↷ "Redo" at x 60–108, each `faint` when there is nothing to undo or redo.
- ⋮ "More options" at x 256–304: "Restore original" (enabled when the paragraph holding the caret differs from its source) and "Find and replace".
- **Done pill** at x 308–388, y 52–92: 40 dp visual (48 dp target 48–96), `inv`, `label` in `onInv`, 20 dp horizontal padding. Done is the only filled shape on screen while the dock is hidden.
- **Save failure.** A `meta` `muted` "Not saved" appears left of ⋮ (`fade`) and Done's label becomes "Retry". Back triggers a retry. A second failure shows the feedback pill "Couldn’t save your edit. Free up some space, then tap Retry." above the keyboard; the user stays in Edit and nothing is discarded.

#### 4.1.4 Settings and Licenses bar
← "Back" at x 12–60 only. The page title (`title`) sits at y 112 and collapses into the bar on scroll exactly like the note title. On Settings, ← closes the page (s 1 → 0 on `stage`, 4.23.4); in `SettingsActivity` (the App info entry) it finishes the activity.

### 4.2 Icon button
48 dp target, 24 dp glyph. Pressed: a 40 dp `press` circle behind the glyph (`fadeFast`). Disabled: `faint` glyph, no press layer, `disabled()` semantics. A content description is required (section 12).

### 4.3 Dock

One composable keyed by a sealed `DockState`, inside a `LookaheadScope`, drawn at the root **above** both the library and the recording surface, so it never moves during the morph. The circle and pill use `Modifier.animateBounds(lookaheadScope, boundsTransform = { _, _ -> FocalMotion.move() })`. The dock never moves vertically between states; only contents, widths, left edges and dress change.

| Context | Circle slot | Pill slot |
|---|---|---|
| Library with notes | Search circle (4.4), x 24–80 | ● Record (`inv`), x 92–388 |
| Library, empty | none (width 0) | ● Record, x 24–388 |
| **Library, capture live** (V8) | Pause circle (`s1`, ‖ `ink`), x 24–80 | ■ Finish (`inv`), x 92–388 |
| **Library, capture paused** (V8, R1) | ● Resume pill (`s1`, `ink`), x 24–172 | ■ Finish (`inv`), x 184–388 |
| Scene, recording | Pause circle (`sceneGlass`, ‖), x 24–80 | ■ Finish (`sceneAction`), x 92–388 |
| Scene, paused | ● Resume pill (`sceneGlass`), x 24–172 | ■ Finish, x 184–388 |
| Morph, 0 < p < 1 | The live/paused controls above in both dresses, split at the material's edge: paper below it, scene above it (`dockDress`, 2.8.6) | same |
| Note, Transcribing | ▶ "Play" (`s1`) | Progress pill "Transcribing" (4.5.3) |
| Note, Waiting / Preparing | ▶ | Progress pill "Waiting to transcribe" / "Preparing speech model", static fill |
| Note, Ready | ▶ "Play" | Copy (`inv`) |
| Note, Playing | ‖ "Pause playback" | Copy |
| Note, text only (audio removed) | none | Copy, x 24–388 |
| Note, no speech recognized | ▶ | Copy, disabled |
| Note, while a capture is live elsewhere | ▶ disabled (`faint`; "Play, unavailable while recording") | Copy |
| Search, find, edit, rename (IME-driven) | Hidden: slides down 24 dp + fades (`move`/`fade`), synced to the IME when the IME drives it | |
| Settings, Licenses | No dock. During the settings swipe the library's dock (whatever it holds) moves with the library layer: it applies translationX = −s·D_s in its own `graphicsLayer`, so it stays drawn above the recording surface (4.23.2) | |

The library's Pause, Resume and Finish behave exactly as the scene's (5.3.4, 5.4): Pause/Resume are silent; Finish fires `Confirm` and opens the note (T7b).

**Library → live** (T1′): the geometry is unchanged; glyphs and labels fade through (search → ‖, "● Record" → "■ Finish"), then the material's edge wipes the dress (2.8.6). **Empty → live** (T1′e): Pause grows 0 → 56 at x 24 while the pill's left edge moves 24 → 92 (`move`); the labels fade through. **Finish → library:** the idle dock returns (T7b, then reverse T16′ on Back).

**Dock swaps between the library and a note** (T7b, T16′ both ways; q = the swap's progress, 0 → 1, on `fadeFast`): the containers crossfade without a coverage dip (the incoming dock's containers beneath at full alpha, the outgoing ones on top at alpha 1 − q; same geometry), while glyphs and labels **fade through**: outgoing at 1 − smoothstep(0, 0.5, q), incoming at smoothstep(0.5, 1, q). Two words are never superimposed ("Finish" over "Transcribing").

### 4.4 Search circle
- 56 × 56 dp, `s1`, `search` glyph 24 dp `ink`, at x 24–80. Content description "Search notes".
- Pressed: scale 0.97 (`press`).
- Tap opens search: the circle itself becomes the search field (4.9.1, T17).
- Absent in the empty library: it grows in from 0 width (`move`) when the first note lands, while Record's left edge moves 24 → 92 (`move`).
- While a capture runs, the circle slot holds Pause (4.5.2); its glyph crossfades search → ‖ in T1′ and back after Finish.

### 4.5 Pills on paper

#### 4.5.1 Primary pill (Record, Copy)
- Height 56, `pill` shape, `inv` fill, label `label` in `onInv`. Content: a centred row, 20 dp glyph (or the 10 dp record dot) + 8 + label.
- Pressed: scale 0.97 (`press`), no ripple and no overlay.
- Disabled: `s1` fill, `faint` glyph and label.
- 200 % font: the label may wrap to two lines; `heightIn(min = 56.dp)`, the radius stays 28 dp.
- **Copy → Copied.** The copy glyph crossfades to a check with a 0.7 → 1 scale (`press`) while the label crossfades (`fadeFast`). After `copiedHold` both revert (`fadeFast`). The label slot width is `max(width("Copy"), width("Copied"))`, so the glyph never shifts.

#### 4.5.2 Library live controls
- While a capture runs and the scene is collapsed, the dock carries the scene's controls in paper dress: the Pause circle (`s1`, ‖ 24 dp `ink`) at x 24–80 and the Finish pill (`inv`, ■ 12 dp radius 3 + 8 + "Finish" in `label` `onInv`) at x 92–388. Paused: "● Resume" pill (`s1`, `ink`) x 24–172 and Finish x 184–388 (R1).
- Same geometry, same behaviour and the same transitions as the scene's dock (4.7, T4). Pressed: scale 0.97 (`press`).
- Why paper dress: the scene's white `sceneAction` gradient would vanish on white paper; `inv` is the library's primary fill. The material's edge wipes the dress from paper to scene as it slides under the dock (2.8.6), and back on fold.
- There is no search circle while a capture runs; it returns at Finish (risk 14.10). Settings stays one swipe away (4.23.7).

#### 4.5.3 Progress pill ("Transcribing")
- Track `s1`; fill `inv` from the start edge, width = `p · pillWidth`, clipped by the pill shape (the fill's leading end is straight). The fill moves with `move` at each window commit, never as a fake crawl, never backwards.
- The label is drawn twice: `ink` underneath and `onInv` on top, clipped to the fill (`drawWithContent { clipRect(right = size.width·p) { drawContent() } }`). It reads at every percentage.
- p = resolved audio ms ÷ total audio ms; silent windows count as resolved.
- Not clickable. Semantics: `progressBarRangeInfo` and `stateDescription` "Transcribing, 64 percent", announced at 25 % steps only.
- **At Ready:** the fill reaches 100 %; the background `s1` → `inv` (`fade`); the label crossfades to copy glyph + "Copy" (`fadeFast`). No check. `Confirm` on this frame (2.9).

### 4.6 Circle button (paper)
56 dp, `s1`, 24 dp `ink` glyph: ▶ "Play", ‖ "Pause playback". Pressed: scale 0.97 (`press`). Glyph swaps crossfade (`fadeFast`). Disabled: `faint` glyph.

### 4.7 Scene dock: Pause / Resume and Finish
- **Pause circle.** 56 dp at x 24–80, `sceneGlass` (white 8.5 % + a 1 dp inner top highlight of white 10 %), ‖ 24 dp in `sceneInk`. Content description "Pause".
- **Finish pill.** 56 dp at x 92–388, `sceneAction` (gradient `#FFFFFF → #DCDCDC`, shadow, 1 dp ring), content ■ 12 dp (radius 3) + 8 + "Finish" in `label`, `#111111`. Pressed: scale 0.97.
- **Paused (R1).** The circle widens into a labelled pill and Finish keeps its right edge:
  - Resume pill x 24–172 (148 wide): `sceneGlass`, ● 10 dp + 8 + "Resume" in `label`, `sceneInk`.
  - Finish x 184–388 (204 wide).
  - Motion: both bounds animate together with `move` (`animateBounds` in the dock's `LookaheadScope`); ‖ crossfades to "● Resume" (`fadeFast`). Resume reverses it.
  - Why: Resume gets a word because pausing is the one moment that needs a decision, and Finish's right edge never moves, so a thumb aimed at Finish after an auto-pause still lands on Finish.
- Content descriptions: "Pause", "Resume", "Finish".
- **In the library** (V8) the same controls appear in paper dress, with the same geometry, behaviour and transitions (4.5.2). During the morph the dock does not move; only its dress changes (2.8.6).

### 4.8 Feedback pill (replaces the snackbar)
- **Anatomy.** Exactly the bottom column's width: x 24–388. `pill` shape, `s1` fill, min height 48.
  - Message: `meta` in `ink`, starting 20 dp inside (x 44), one line with an ellipsis; from font scale 1.3 it wraps to at most 2 lines and the pill grows (14 dp vertical padding).
  - Optional action: a text button 48 dp tall, 16 dp horizontal padding, ending 8 dp inside the pill. Content: undo glyph 18 dp + 6 + `action` label in `ink` (so "Undo" ends at x 364).
- **Position.** 8 dp above the topmost visible bottom element: ref 763–811 above the dock, 707–755 above the player strip, 457–505 above find row 1, 505–553 above a lone search field (561–617).
- **Z-order.** Drawn behind the dock (earlier in the same `Box`), so it appears to come out from behind it.
- **Motion.** Enter: translateY +16 → 0 dp (`move`) + alpha 0 → 1 (`fade`). Exit: translateY → +16 (`move`) + alpha → 0 (`fadeFast`). A replacing message crossfades its text in place (`fadeFast`) without re-rising. While it shows, the paper bottom fade and the list's bottom content padding grow by 56 dp (`move`), so nothing reads through around it.
- **Never moves Record.** The dock is not displaced by feedback, ever.
- **Duration.** `feedbackDuration` (2.8.3). One at a time; a new message replaces the old. Scrolling and taps elsewhere do not dismiss it; swiping it down dismisses it (and commits). Starting a recording dismisses it (`fadeFast`) and commits.
- **Deferred actions.** A hard delete or audio removal commits when the pill times out, is replaced or dismissed, or the app is backgrounded; it goes through the existing deletion journal.
- **Semantics.** A polite live region; Undo is also a custom action on the pill.

### 4.9 Keyboard-riding rows

#### 4.9.1 Search field
- **Anatomy.** x 24–388, 56 dp, `s1`, `pill`.
  - Leading ← "Close search": a 56 × 56 target at x 24–80, glyph centred at x 52 (exactly where the search glyph sat in the circle, so the glyph crossfades in place).
  - Text: `field`, `ink`, from x 80 to 332; placeholder "Search notes" in `muted`; caret 2 dp `ink`.
  - Trailing × "Clear": a 56 × 56 target at x 332–388, shown only when the query is non-empty (`fadeFast`).
- **Position.** Bottom = IME rule (2.5): ref 561–617 with the keyboard, 819–875 without it.
- **IME action.** Search: hides the keyboard and keeps the results; the field drops to the dock slot.

#### 4.9.2 Find and replace rows (R2)
Two rows, 8 dp apart, on a fixed two-column grid: fields x 24–244 (220 wide) | gap 8 | controls x 252–388 (136 wide). Ref with the keyboard: row 1 at 513–561, row 2 at 569–617.

| Row | Left (x 24–244, 48 dp, `s1`, `pill`) | Right (x 252–388, 48 dp, `pill`) |
|---|---|---|
| 1 | **Find field.** Leading ← "Close find" (48 × 48 target at x 24–72, glyph centred at 48). Query `field` `ink` from x 72. Count at the end: `meta`, `muted`, tnum, right edge 16 dp inside (x 228), at least 8 dp after the text: "2 of 4", "No matches", or nothing while the query is empty. Placeholder "Find in note". | **Nav pill** (`s1`): ∧ "Previous match" target x 252–320 (glyph centred at 286) and ∨ "Next match" target x 320–388 (glyph at 354). Both `faint` and disabled with 0 matches. |
| 2 | **Replace field.** A decorative ↳ (24 dp, `muted`, hidden from accessibility) centred at x 48, so the text starts at the same x 72 as the query. Placeholder "Replace with". | **Replace all** (`inv`): `action` label in `onInv`, tnum, centred: "Replace all (4)". Disabled (`s1` + `faint`, label "Replace all") when the query is empty or there are no matches. "Replace all (12)" measures 99 dp, leaving 18 dp each side. |

- IME actions: find field = Search (moves to the next match); replace field = Done (hides the keyboard; the rows drop to 771–819 / 827–875).
- Semantics: fields labelled "Find in note" and "Replace with"; the count is a polite live region (after the 120 ms debounce); Replace all reads "Replace all, 4 matches".

### 4.10 Player strip and playhead
- **Anatomy.** A 48 dp row in the bottom column, ref 763–811:
  - elapsed time: `caption`, `ink`, tnum, min width 40, start-aligned (x 24–64);
  - 12 dp gap; the track (x 76–336); 12 dp gap;
  - total time: `caption`, `muted`, tnum, min width 40, end-aligned (x 348–388).
  - Format `m:ss`, or `h:mm:ss` from one hour.
- **Track.** 4 dp tall (ref 785–789), `pill` ends. Played part `ink`, unplayed `lens`. It breaks 4 dp on each side of the playhead. The whole 48 dp row is the drag target.
- **Playhead.** The centre bar: 4 × 24 dp, `ink`, `pill` ends, centred on the track; 32 dp tall while dragging (`press`), and the text follows the word under it. No round thumb.
- **Motion.** Enters from 16 dp lower with alpha (`move`/`fade`) when playback starts; exits in reverse on Back, at the end of the audio, on entering Edit or Find, and on leaving the note.
- **Semantics.** Adjustable "Audio position", state "6 minutes 41 seconds of 24 minutes"; swipe up/down = ±5 s.

### 4.11 Jump pill
- **Paper.** 40 dp visual (48 target), `s1`, padding 12 start / 16 end: 18 dp arrow + 8 + `action` at weight 500, `ink`. Right edge 388; bottom 12 dp above the strip or dock (ref 767–807 above the dock, 711–751 above the strip). Use: "Now playing" with ↑ or ↓ toward the playing line when playback follow is suspended.
- **Scene.** The same geometry in `sceneGlass` with `sceneInk`: "↓ Latest", while recording and scrolled up (ref 767–807).
- **Motion.** Enter `fade` + scale 0.9 → 1 (`press`); exit `fadeFast`. Tap scrolls with `move` (crossfade when more than 3 screens away) and resumes follow.

### 4.12 Library list

#### 4.12.1 Day label
- `section`, `muted`. Labels: "Today", "Yesterday", the weekday for the past 7 days, "3 November", then "3 November 2025" for other years (device locale).
- **The list starts below the stage** (V5): the first day label's top is the stage's bottom + 32 (ref 328–348, measured box), then 16 dp to the first title (ref 364, measured). The stage's own note is not in the list (4.22.2), so the first label is the day of the next note.
- Spacing between sections: 36 dp from the previous row's preview bottom to the label top; 16 dp from the label bottom to the next title.
- **Sticky, only after scrolling.** Each label is a `stickyHeader`. The stage is a plain item before the first header, so at rest nothing is pinned; a header pins at its line (status + 12, ref 52–72) only once it reaches that line, over the solid band of the library top fade; the next label pushes it up. If the Compose version pins headers at the viewport edge rather than after the top content padding, offset the pinned header by the padding (read from `LazyListState.layoutInfo`). **Labels ride above the band** (G5): the pinned label is an overlay above the top fade, fed by `LazyListState.layoutInfo`; a label whose top is above the band's bottom edge (96) is drawn there at max(its list position, the pin line) and its list copy is hidden, so it is never covered on its way to the pin; the next label pushes the pinned one up (36 dp apart) and it slides under the status edge (the overlay is masked over 40–56 like it). Semantics: heading.

#### 4.12.2 Ready row (a contents-page entry)
- x 24–388. Line 1: title in `rowTitle` `ink`, at most 2 lines with an ellipsis; duration flush right like a page number, `meta` `muted` tnum, aligned to the first baseline, 16 dp gap. Formats: "42 s", "24 min", "1 h 12 min".
- Preview 4 dp below: `secondary` `muted`, at most 2 lines, opening with a time run-in "16:05" (`secondary` 500 `ink` tnum) followed by U+2002 (en space). When the title is derived, the preview begins after the title sentence; when custom, at the start of the text. **Ellipsis** (G4): the preview ends on the last whole word that fits, trailing `, . ; : – —` dropped, then "…" ("at all…", never "at all.…" or "the way h…"): one `TextMeasurer` pass per (text, width), `getLineEnd(last, visibleEnd = true)`, back to the previous word boundary, remembered per row.
- Rows are 24 dp apart. Ref with one-line titles: titles at 98, 194, 290 (pitch 96).
- **Derived title**: the first sentence, clipped at a word boundary at about 48 characters with `…`; a trailing full stop is dropped, `?` and `!` kept. **Fallback title**: "Note at 14:32" (device 12/24 h) in `muted`; a derived title develops in its place when it appears.
- **Hit area and press.** The full width, 12 dp above the title to 12 dp below the preview. Pressed: a `press` plate at x 8–404, radius 12 (`fadeFast`). No ripple.
- **Long-press** opens the row menu at the touch point (4.15). TalkBack gets the same actions.
- From font scale 1.5 the duration wraps under the title.

#### 4.12.3 Live row (none)
The stage is the live surface (4.22.3). A capture never appears as a list row.

#### 4.12.4 Transcribing and waiting rows
Only for notes that are not on the stage, for example a note whose drain was preempted by a new recording.
- **Transcribing:** trailing "64%" (`meta`, `ink`, tnum) instead of the duration; run-in "Transcribing" (500 `ink`) instead of the time; a 2 dp determinate track (`lens`, `ink` fill, radius 1) 8 dp under the preview across x 24–388, moving with `move`.
- **Waiting:** run-in "Waiting to transcribe" or "Preparing speech model"; no track; trailing duration.

#### 4.12.5 Delete
The row collapses (height `move`, alpha `fadeFast`); rows below move with `animateItem(placementSpec = move)`. Undo re-expands it in place (`move`) and develops its text.

### 4.13 Paragraph
- One `Text` per paragraph in `body`, items keyed by paragraph id with `contentType = "p"`. Paragraph gap 16 dp, column x 24–388.
- **States:**
  - settled: `ink`; receded: `muted` (`develop`); developing: 2.8.1;
  - playing: `ink`, others receded; **lens:** the playing word sits on an 8 dp-radius `lens` rectangle, the word box plus 4 dp horizontally, and 22 dp tall (the 28 dp line box from `TextLayoutResult.getBoundingBox` inset 3 dp top and bottom), drawn in `drawBehind`, so glyphs never move;
  - find match: `ink` on `s2`, radius 8, 2 dp horizontal outset, the same 22 dp height; current match: `onInv` on `inv`; everything else recedes, title included;
  - **Line breaks never change with state.** Lens, match and sheet shapes are drawn, and the text colours are `SpanStyle(color = …)` only (no background span, padding, letter spacing or weight), so a paragraph wraps identically in reading, playing, find and edit. QA: the same paragraph must break on the same words in all four states;
  - editing: 4.14;
  - doubtful word (provisional): round 1.5 dp `faint` dots on a 4 dp pitch, 3 dp below the baseline, drawn behind; no colour or weight change; cleared on edit; never copied. Nothing reserves space for marks.
- **Gap row** (partial failure), at each untranscribed range: 48 dp tall: [16 dp static mark at 40 %] + 12 + "0:42 not transcribed" (`meta`, `muted`, tnum) + flexible space + text button "Play" (`label`, `ink`, plays exactly that range; hidden when the audio is removed).

### 4.14 Edit sheet and provenance line
- **Sheet.** An `s1` rectangle, radius 12, drawn 12 dp outside the text box horizontally (x 12–400) and 8 dp vertically, behind the paragraph holding the caret, so the text does not move by a pixel. Enter `fade`, exit `fadeFast`. Other paragraphs and the title recede (`develop`).
- **Field.** Only the focused paragraph is a `BasicTextField(TextFieldState)` with the identical `TextStyle` and padding; caret 2 dp `ink`; `TextSelectionColors(handleColor = ink, backgroundColor = select)`. All other paragraphs stay `Text`. Tapping one commits the current field, swaps, and places the caret at the tap.
- **Provenance line.** Shown under the sheet while the focused paragraph's current text ≠ `source_text` and `source_text` is not NULL. Top = text bottom + 16; height 20; the item grows by 36 dp (`move`). Content: "Edited" (`meta` `muted`) + " · " + "Restore original" (`meta` 600 `ink`, a text button with a 48 dp target). Restore swaps the text back as one undoable step; the line collapses (`move`). Outside Edit, edited paragraphs carry no badge.

### 4.15 Menu
- **Container.** `raised`, radius 16, width 232 (min 196, max 280), 8 dp vertical padding; Light shadow, Dark 1 dp `raisedRing`.
- **Items.** 48 dp, 16 dp horizontal padding, `label` at weight 400 `ink`; optional trailing value `meta` `muted` tnum ("9 MB"). Pressed: full-width `press`. One 1 dp `line` divider (8 dp margins) before Remove audio.
- **Anchor.** From ⋮: top-right corner at (400, 100), i.e. x 168–400 from y 100. From a row long-press: at the touch point, clamped 16 dp from the screen edges.
- **Motion.** Enter: scale 0.95 → 1 from the anchor corner (`press`) + alpha (`fade`). Exit: alpha (`fadeFast`). Dismissed by tapping outside, Back and scrolling.
- **Items by context** (items that do not apply are hidden, not disabled):

| Item | Note: Ready | Note: Transcribing / Waiting | Note: text only | Row: Ready | Row: not Ready | Edit ⋮ |
|---|---|---|---|---|---|---|
| Copy text | (Copy pill) | | | ✓ | | |
| Find and replace | ✓ | | ✓ | | | ✓ |
| Restore original | | | | | | ✓ (enabled when edited) |
| Share | ✓ | | ✓ | ✓ | | |
| Rename | ✓ | ✓ | ✓ | ✓ | ✓ | |
| — divider — | ✓ | | | ✓ | | |
| Remove audio · size | ✓ | | | ✓ | | |
| Delete | ✓ | ✓ | ✓ | ✓ | ✓ | |

Orders: row menu = Copy text, Share, Rename, divider, Remove audio, Delete. Note menu = Find and replace, Rename, Share, divider, Remove audio, Delete. Delete on a transcribing note cancels its drain before the deferred delete. Remove audio stops playback.

### 4.16 Notice line
- **Paper.** An inline, non-modal sentence in `secondary`, at most 3 lines, full text width, 8 dp under the meta line. The first sentence is `ink`, the rest `muted`. An optional small pill 8 dp below: `s1`, 40 dp visual (48 target), `action` label, 16 dp horizontal padding ("Retry", "Try again"). Enters with `fade` while the content below moves (`move`). Never red, never boxed, never a dialog.
- **Scene.** The scene has no notice. Only a storage-full pause states its reason, in the state line (4.21.4); storage feedback after a failed Resume uses the feedback pill (5.3.5).

### 4.17 Segmented control
Track 48 dp, `s1`, `pill`; three equal segments; thumb `inv`, 40 dp tall, inset 4 dp, sliding with `move`; labels `meta` 500, `onInv` on the thumb and `ink` elsewhere. Applies immediately; the app's colours animate (`fade`); no haptic. From font scale 1.8, if any label would truncate, it becomes three stacked 48 dp radio rows.

### 4.18 Settings row and group head
- Row: min height 56; padding 12 vertical, 24 horizontal. Title `field` 500 `ink`; optional description `secondary` `muted` 2 dp below; optional trailing value (`field` 500 tnum `ink`), ↗ or › glyph (24 dp, `ink`), or nothing. Pressable rows get a plate at x 8–404, radius 12.
- Group head: `section` `muted`, 32 dp above (24 under the page title), 8 dp below.

### 4.19 Bottom sheet (rationale only)
Surface `paper` (Light) / `s1` (Dark), top radius 28, `scrim` behind. Grab handle 32 × 4 dp `faint`, 12 dp from the top. Content padding 24 dp: the static mark (40 dp) at top 36; `headline` 16 dp below; body (`secondary` `muted`) 8 dp below; primary pill (full width) 32 dp below; a text button 8 dp below the pill; 16 dp + navigation inset at the bottom. Motion: sheet rises (`move`), scrim fades (`fade`); predictive back drags it down; tapping the scrim dismisses.

### 4.20 Text button and focus ring
- Text button: `label` in `ink`, min height 48, padding 12 horizontal, pressed `press` pill.
- Keyboard focus ring: a 2 dp `ink` outline offset 2 dp following the control's shape (on the scene, `sceneInk`). Shown only in keyboard or D-pad navigation (`InputModeManager.inputMode == Keyboard`).

### 4.21 The recording scene

#### 4.21.1 Material
The full window (behind the status and navigation bars): the `scene*` gradient, highlight and grain (2.2), drawn by one static composable (3.4). It looks the same in Light and Dark.

#### 4.21.2 Chevron (R4)
`keyboard_arrow_up` (⌃), 22 dp glyph, `sceneChevron`, in a 48 × 48 target centred on the vertical axis: x 182–230, y 48–96 (measured ink centre 206.0, 71.75). Content description "Collapse. Recording continues". It is the only top element. Tap = fold into the stage (T5′). It points up because the scene folds upward into the stage.

#### 4.21.3 Timer (R5)
- `sceneTimer`, `sceneHi`, centred on x 206. Formats `0:00`, `10:00`, `1:00:00`.
- **Reserved width.** The text box width is the measured width of the current format's template ("0:00" < 10 min, "00:00" < 1 h, "0:00:00" from 1 h), with `TextAlign.Center`; tnum makes every digit equal width, so ticking never shifts a glyph. At a format change the box widens with `move` and the text recentres.
- **Size.** Computed once when the scene opens: the largest size in 56–76 sp (step 2) at which "0:00:00" fits in 364 dp at the current font scale. It never changes during the recording. At 1.0× it is 76 sp ("0:00:00" = 232 dp).
- Paused: colour → `sceneMuted` (`develop`).
- Semantics: merged with the state slot into one node, "Recording, 4 minutes 16 seconds" (8.2); not a live region.

#### 4.21.4 State line and the pause badge
- **While recording the slot is empty** (V4): the moving wave and the running timer are the indicator. The slot keeps its geometry (20 dp at ref 354 with text, 540 before text), so nothing moves when a word appears.
- **Paused (by hand, by a call or by another app):** the slot **stays empty**. The pause is drawn on the instrument: the wave dims to the full profile at 40 % with the glow off, the timer recedes to `sceneMuted`, and the **pause badge** (`stageBadge` at 28 dp) sits at the hero wave's bottom-right, at the same box-relative offset as on the stage: centre = wave box origin + (64, 47)·(h/56). With text (h 136, box x 118.6–293.4, y 116–252): centre (274.0, 230.1), disc 260–288 × 216.1–244.1 (measured), 24 dp above the timer's box (268). Before text (h 176, box top 236): centre (294.0, 383.7). In (`fadeFast`) on pause, out on resume. No word: ‖ means pause everywhere, and there is no ○ ring.
- **Paused because storage is full (the only caption):** the same badge, and the slot reads **"Storage is full"**, centred, `meta` 500 `sceneMuted`, no glyph (measured 354–374; 5.21:1, before text 5.48:1). It is written because Resume cannot work until space is freed; the feedback pill explains the amount when Resume is tapped (5.3.5).
- **Model conditions** (5.3.6), recording or paused: the slot shows "Preparing speech model" or "Text will catch up" alone, in the same style. While "Storage is full" shows, it takes the slot.
- Semantics: merged with the timer, "Recording, 4 minutes 16 seconds" or "Paused, 12 minutes 48 seconds" (8.2), with the reason spoken for automatic pauses ("Paused, 12 minutes 48 seconds, paused for a call"). The words are spoken, not drawn.

#### 4.21.5 Hint
"Text appears after each pause." `secondary` `sceneMuted`, centred, ref top 668. First three recordings only, until the first text lands (exit `fadeFast`). If notifications are off, the same slot holds "Notifications are off, so Pause and Finish are only in Focal." instead, once per recording.

#### 4.21.6 Text region
- x 24–388, from the region top (ref 398; there is no notice, so it never moves) to dock top − 24 (ref 795). Bottom-anchored once it overflows; a 128 dp alpha mask at its top.
- `sceneText`, paragraph gap 16. The latest resolved stretch is `sceneInk`; all older text is `sceneMuted`. Typically 1–3 lines are bright: the peek.
- Read-only: no word taps, no selection, no edit.
- **Follow.** Each arrival scrolls with `move` to keep the end of the text at the region bottom. A manual scroll of more than one paragraph stops follow and shows the scene jump pill "↓ Latest" (4.11). New text keeps developing off-screen.

#### 4.21.7 Scene geometry (ref)

| Element | Before text | With text | Paused (any cause) |
|---|---|---|---|
| Wave box | 176 tall × 226, centred, top 236 | 136 × 175, top 116 | 136, top 116, full profile at 40 % |
| Pause badge (paused only) | centre (294.0, 383.7), 28 dp | centre (274.0, 230.1), 28 dp | same |
| Timer box (84 dp line) | top 452 | top 268 | top 268, `sceneMuted` |
| State slot (20 dp; empty while recording and while paused) | top 540 | top 354 | top 354: empty, or "Storage is full" |
| Hint | top 668 | gone | gone |
| Text region | n/a | 398 → 795 | 398 → 795 (unchanged; there is no notice) |
| Dock | 819–875 | same | Resume 24–172, Finish 184–388 |

Vertical rhythm with text: wave box → 16 → timer box (84 dp line) → 2 → state slot (20) → 24 → text region. Before text: wave → 40 → timer → 4 → state slot, hint at 668. The slot is empty while recording and while paused, except "Storage is full" (4.21.4).

Compact (font scale ≥ 1.5): the wave is 120 dp before text (top 236) and 88 dp with text (top 116); the timer is sized per 4.21.3; the same rhythm applies, so the text region starts about 60 dp higher and at least three lines of live text stay visible.

### 4.22 The stage

The library's top area (V1). One card in the instrument's material that always holds whatever is current, once at least one note exists. Values are ref. "Measured" values come from the reference renders (13).

#### 4.22.1 Frame
- x 24–388 (the bottom column's edges), **y 56–296** (364 × 240), radius 28 (`sheet`). It starts 16 dp under the status bar; there is no top row (4.1.1).
- Material per 2.2.1; `stageRing` in Dark. Static while idle: no state reads in its draw.
- Card-local inset 20 on all text; the footer row is 48 dp tall, 16 dp from the card's bottom and right edges (card-local 176–224, **ref 232–280**, centre 256).
- It is the list's first item (`key = "stage"`, `contentType = "stage"`), before the first `stickyHeader`, so it scrolls away with the list under the top fade.
- **Wide windows:** the content column's width (max 600), centred. **Landscape phone:** the text column's width, height 200 (the footer row stays; the text shows 1 line).
- **An empty library has no stage** (4.22.5); the first stage appears with the first note (T27′).

#### 4.22.2 Which note is on the stage
The first match wins:
1. A live or paused capture (the live state, 4.22.3).
2. The most recent note that is transcribing, waiting or preparing.
3. The most recent note.

The stage's note is excluded from the list query, so nothing appears twice. A note preempted by a new recording ("Waiting to transcribe") returns to the list as a 4.12.4 row while the new recording is on the stage.

**Fresh flag** (memory only, no column): set when a note becomes Ready; cleared by its first Copy from any surface (stage, page, row menu) or by `ON_STOP`. Opening the note does not clear it, because Finish always opens it (V7).

#### 4.22.3 Live state (capture running or paused, scene collapsed)

| Element | Ref position | Style |
|---|---|---|
| Wave | box x 44–116, y 76–132 (56 dp tall, 72 wide; centre 80, 104) | Live tier with glow (3.4). Paused: full profile at 40 %, glow off |
| Timer | start x 132, line centred on y 104 | `sceneTimer` laid out at 76 sp and scaled by 48/76 (= 48 sp), `stageTimer`; reserved template width (R5): "0:00" 99 dp, "00:00" 129 dp, "0:00:00" 169 dp at 48 sp. Paused: `stageMuted` (`develop`) |
| **Pause badge** (paused, any cause) | centre at the wave box's (64, 47) → ref (108, 123); disc 97–119 × 112–134 (measured), **18.5 dp** clear of the timer's ink | `stageBadge` (2.2.1): a 22 dp `stageInk` disc with a 3 dp material-coloured cutout ring and ‖ in `#161616`. In ≤ `fadeFast`, out on resume. It replaces the word "Paused": no word, no ring, anywhere on the stage. A bare ‖ beside the bars reads as two more bars of the wave, so the glyph sits on a disc, like a badge |
| Nothing else in the top row | | No "Recording" word, no dot (V4), no "Paused" (X5) |
| Text region | x 44–368, y 148–276 (card-local 92–220), bottom-anchored, 34 dp top alpha mask (`DstIn`) | `stageText` 17/24. The latest resolved stretch is `stageInk`; older text `stageMuted`; paragraph gap 12. Nothing before the first text, except the first-recordings hint "Text appears after each pause." in `stageMuted` (same rule as the scene, 4.21.5) |
| **Paused by hand, by a call or by another app** (X5) | | No caption. The latest resolved line stays `stageInk`: it marks where you stopped. The reason is in the notification and spoken by TalkBack |
| **Paused, storage full** (the only caption) | the region's last line (measured 44–368 × 252–276) | **One line: "Storage is full"**, `stageText` 500 `stageInk` (16.44:1). The transcript above recedes to `stageMuted`. No continuation, no "Everything so far is saved" |
| Model conditions | the region's last line | "Preparing speech model" / "Text will catch up" in `stageMuted` |

The live state is a tap target (expand, T6′) and a drag source (6.5). No long-press menu.

#### 4.22.4 Note states (idle, transcribing, ready)

The reading order is **what → when → the text → how long, and what to do**: a Z pattern with no stacked meta lines. There is no separate dateline line: the time sits on the title's line.

| Element | Ref position | Style |
|---|---|---|
| **Title** | x 44, top 76 (card-local 20), at most 2 lines, balanced (`LineBreak.Heading`); width = 324 − time width − 16 (measured box 44–309.8 × 76–132 with "16:05") | `headline` 22/28 600, `stageInk`. Fallback title "Note at 17:08" in `stageMuted` |
| **Time** | right edge 368, on the title's **first** baseline (measured box 325.8–368 × 83–103) | `dateline` 15/20 500, `stageMuted`, tnum. Today "16:05" · yesterday "Yesterday" · the past six days "Mon" · this year "28 Oct" · older "28 Oct 2025" (device locale and 12/24 h). The familiar inbox convention: a bare time means today. TalkBack always reads the full form ("Today, 16:05") |
| **Text** | top = title bottom + 8 (ref 140 under a 2-line title); whole lines only, bottom ≤ footer top − 20 (ref 212): **3 lines** under a 2-line title, 4 under a 1-line title; the rows' word-boundary ellipsis (4.12.2, G4) (measured 44–368 × 140–212) | `stageText` (17/24 400) `stageMuted`. A derived title is followed by the text after the title sentence, as in rows |
| **Footer, left: length** | x 44, footer row (ref 232–280) | **"12 min"**: the numerals in `stageLength` (Display 250, 40/48, tracking −0.02 em, tnum, `stageInk`), the unit 5 dp after it in `meta` 500 `stageMuted`, baseline-aligned; 10 dp before a second group ("1 h 12 min", "42 s"). The first numeral is pulled left by its left side bearing (3–4.5 dp at 40 sp) so its ink lands on x 44. Never m:ss (S2). **It holds this slot in every note state** |
| **Footer, right: the pill** | right edge 372, y 232–280 | Copy (136 × 48, r 24, x 236–372): copy glyph 18 + 6 + `action` 15/20 600. **Fresh:** `stageAction`, label `#111111`. **Otherwise:** `stageGlass`, label `stageInk`. While transcribing: the progress pill (below) |

**The footer in every state** (W6: a bottom row balanced in every state). One structure: the length on the left at x 44, one pill on the right ending at x 372, the row centred on y 256.

| State | Left slot | Right slot |
|---|---|---|
| Idle | "12 min" | glass Copy 236–372 |
| Transcribing / waiting / preparing | "4 min" | glass progress pill, width max(136, label + 40): "Transcribing · 82%" + 2 dp track, measured 196–372 |
| The readiness moment (T8″, ≈ 0.9 s) | "4 min" | the progress pill morphs once into the white Copy (width w₀ → 136, glass → white, "Transcribing · 100%" → copy glyph + "Copy"); one shimmer crosses the card |
| Ready (fresh) | "4 min" | **white** Copy 236–372 |
| Copied | "4 min" | white ✓ "Copied", 1600 ms, then glass |
| Recording / paused | no footer: the live text runs to card-local 220 (4.22.3) | |
| Empty | no stage (4.22.5) | |

- **The stage has no writing head.** The progress pill's track already shows the work, and head + length + pill would make three items in one row. The note page keeps its writing head.
- **Footer overflow:** if the length and the pill would come closer than 12 dp (long labels, "1 h 12 min", large font), the length yields (`fadeFast`): it is also in the note's meta.

**Transcribing** (or waiting / preparing): the progress pill (S3): right edge 372, height 48, width = max(136, label + 40). `stageGlass`. Label `action` 600 `stageInk` tnum, centred, line box at pill-local 9–29 ("Transcribing · 82%"; "Waiting to transcribe"; "Preparing speech model"). **Track:** 2 dp at pill-local 35–37, inset 20 dp each side, `stageTrack`; the fill is resolved ÷ total audio and moves with `move` at each window commit, never as a fake crawl, never backwards. Waiting and Preparing have no track. Not clickable.

**Ready** (X6): no words and no check. The readiness moment T8″ (6.1) plays once if the note becomes Ready while the stage is on screen: the progress pill morphs once into the white Copy, `Confirm` fires on the morph's first frame, one shimmer crosses the card. Otherwise the stage simply shows the fresh white Copy. The white fill is not colour alone: the pill's label itself changed from "Transcribing · N%" to "Copy".

**Copied** (T9′): check + "Copied" for 1600 ms, then the pill relaxes to glass: the note is no longer fresh.

**Font scale for the time.** When the time + 16 dp exceeds 40 % of the text column (about 1.5× and above, or the long date format), the time drops to its own `dateline` line under the title, left-aligned, and the title takes the full width.

**Other states.**
- **Model couldn't load:** the Waiting layout; the text's last line is replaced by "Not enough free memory to transcribe right now." (`stageMuted`). Try again lives on the page (5.4).
- **Partial failure:** the ready layout; the text's last line reads "Part of this note couldn’t be transcribed." Copy copies what exists.
- **No speech recognized:** title "Note at 17:08" in `stageMuted`, text "No speech was recognized."; Copy disabled (glass, label at 40 %, announced as disabled).
- **Text only** (audio removed): no change; the length still describes the note.

#### 4.22.5 Empty state (no card)

The empty library shows what the app *is*, calmly, on paper: the instrument at rest, two lines, and the one control that starts it. There is no stage until the first note exists.

| Element | Ref position (measured) | Style |
|---|---|---|
| **Instrument at rest** | box 112 tall × 144 wide, x 134–278, y 268–380, centred on (206, 324); centre-bar ink 301.6–346.4 (44.8 dp) | The nine bars in paper dress: `ink`, the centre bar's `centreBarTop → centreBarBottom` gradient, every bar at the mark's own alpha (3.1), the outer bars blurred with the mark's σ (s = 112/42 = 2.667, outer σ 5.9 dp); **42 % of the profile, still**; no glow (glow means a live level). Never the full profile, so it never reads as the logo (F2). Rasterised once per size and theme (3.4) |
| Headline | 416–484 (box 56–356: max width 300, centred; 36 below the instrument's box) | "Speak, and it’s written down." `title` 28/34 600 `ink`, centred, balanced (two lines at 1.0×) |
| Second line | 496–544 (12 below) | "Tap Record and talk." / "Text appears after each pause." `field` 17/24 400 `muted`, centred, two lines (an explicit line break in `empty_body`) |
| Dock | Record 24–388 × 819–875 | The `inv` pill alone; no search circle (there is nothing to search) |

- **Balance.** The visible group runs from the centre bar's top (301.6) to the second line's bottom (544), centred at 422.8: 6.7 dp above the centre of the free paper (status-bar bottom 40 → dock top 819, centre 429.5), the optical centre. 261.6 dp of paper above the ink, 275 dp below the text.
- **It is the scene, waiting.** The instrument's centre is the scene's start-hero centre (206, 324), and its box is that hero at 112/176 scale, so Record grows the material out of it and the bars light up in place (T1′e). The headline and second line sit where "0:00" (452–536) is born, so they leave by fade-through.
- **No app name or logo**, and no card: the material appears only when the microphone opens.
- **The second line teaches the one action and what happens next.** It matches the scene's first-recordings hint (4.21.5).
- **Dark:** bars `#EDEDED` with the centre bar's `#FFFFFF → #CFCFCF`, `muted` `#8F8F8F`. Contrast: headline 18.88 (Light) / 16.37 (Dark), second line 5.33 / 5.93.

**Behaviour.**
- **Tap Record** → T1′e (6.1). Record is the only way to start a recording, here and everywhere (X4).
- **Nothing else responds:** no tap response, no drag. A horizontal swipe opens Settings like anywhere in the library (4.23).
- **TalkBack:** the composition is one node: "Speak, and it’s written down. Tap Record and talk. Text appears after each pause." with the custom action "Open settings" (4.23.6). Record is the next node (it also carries "Open settings").
- **Reduced motion:** nothing here moves; T1′e is the 150 ms crossfade (2.8.4).
- **Empty → first note** (T27′): Finish always lands on the note (V7). Back folds the page into the stage, which appears here for the first time at 56–296 (reverse T16′), while the search circle grows in (width 0 → 56, `move`) and Record's left edge moves 24 → 92 (`move`). A discarded empty recording ("Nothing was recorded") folds back by reverse T1′e into the resting instrument, and the invitation returns (`fade`).
- **Deleting the last note** (4.22.6): the stage fades out (`fadeFast`) and this composition fades in (`fade`); the search circle leaves (width → 0, `move`) and Record's left edge returns 92 → 24 (`move`). Undo reverses it.

#### 4.22.6 Interactions

| Target | Action |
|---|---|
| Stage, note states | Tap opens the note (T16′). Long-press opens the row menu (4.15): Copy text, Share, Rename, divider, Remove audio, Delete; while transcribing only Rename and Delete. A drag down does nothing but the list's own overscroll: only Record starts a recording (X4) |
| Copy pill | Copies the whole note (paragraphs joined by a blank line; the page's Copy rules, 5.5). `Confirm`. Check + "Copied" for 1600 ms, then glass. Disabled while transcribing (the pill is the progress pill) |
| Stage, live | Tap (or Enter) expands (T6′). Drag down expands under the finger (T28). No menu |
| Any stage, a horizontal drag to the left | Opens Settings (4.23). The direction lock decides once per stream, so it never competes with the expand drag |
| Rename from the stage | The title becomes a `BasicTextField` in place, same style, `stageInk` caret; the IME opens and the dock hides (5.1.6) |
| Delete the stage note | The content crossfades (`fadeFast` out, `develop` in) to the next note by 4.22.2; that note's row collapses out of the list (`move`); "Note deleted · Undo" (4.8). Undo reverses it. Deleting the last note: the stage fades out and the empty library's composition fades in (4.22.5) |
| Search open (T17) | The stage fades out with the list (`fade`) |

#### 4.22.7 Font scale
- `heightIn(min = 240.dp)`; the card grows to fit: title up to 3 lines, at least 2 text lines, the footer.
- From 1.5× the length takes its own 48 dp row above the footer's Copy, and the time takes its own line under the title (4.22.4).
- The live timer is sized once per composition, the largest size in 32–48 sp at which "0:00:00" fits between x 132 and 368 (at 1.0× it is 48 sp, 169 dp). The live text region keeps at least 2 lines.
- The empty library: the headline and second line wrap (balanced); from 1.5× the resting instrument is 80 dp; the group recentres on the free paper, and T1′e lerps the wave's centre to (206, 324).
- The morph reads the stage's real bounds (6.4), so a taller card needs no special case.

### 4.23 Settings: the swipe, the peek and the alternatives

The library has no settings control (X1). Settings is the page beside the library, reached by a horizontal swipe; every other input has an equivalent path (4.23.6).

#### 4.23.1 Model
- Two pages side by side in one horizontal strip: the **library** (the Library destination with its stage, list, top fade and pinned day label, and the root-level dock and feedback pill while they show the library's controls) at the start, the **settings page** (5.10) at the end, and an 8 dp **seam** between them.
- One value, `settingsProgress` **s**: 0 = the library, 1 = settings. Every element below is a pure function of s, read in the layout or draw phase, whoever moves it: a finger, a spring, ←, Back, predictive back, Ctrl+, or the accessibility action. A swipe never recomposes.
- s and the expansion p (6.4) are exclusive: the swipe arms only at p = 0, and nothing can expand the scene while s > 0 (4.23.7).
- **The page that is off screen is inert.** At s = 0 the settings layer, and at s = 1 the library layer (with its dock and feedback pill), is excluded from the accessibility tree and from keyboard and D-pad focus (`Modifier.clearAndSetSemantics { }` + `Modifier.focusProperties { canFocus = false }` on that layer), so TalkBack, Switch Access and Tab never land on a page nobody can see. Both layers are live while 0 < s < 1; a swipe in flight announces nothing.
- W = the window width (ref 412); D_s = W + 8 = 420, the strip's travel.

#### 4.23.2 Motion as functions of s

| Element | Function (ref) |
|---|---|
| Library layer | translationX = −s·D_s (−420 at s = 1), applied to the library's content and, with the same value in their own `graphicsLayer`s, to the root-level dock and feedback pill (they are drawn above the recording surface, so they are not children of this layer; 4.3). Not scaled and not dimmed: the two pages are peers in one strip |
| Settings layer | translationX = (1 − s)·D_s (off-screen at 420 when s = 0) |
| Seam | the 8 dp between them, x from W − s·D_s to W − s·D_s + 8 (measured at the peek: 380–388), filled `s2` (`#E8E8E8` / `#282828`; decorative, 1.23 / 1.30 against paper) |
| Seam-side corners | the library's top-right and bottom-right corners and the settings page's top-left and bottom-left corners take the `sheet` radius, 28 dp, while 0 < s < 1, and 0 at rest: the shape of a page in motion, as in predictive back (2.8.5). They sit at the window's top and bottom edges, so at rest nothing pops |
| Status and navigation bars | the system's; they do not move. Both pages are paper under them, so the icons keep the theme's appearance at every s |
| Finger | tracked 1:1: s = clamp(s₀ + (x_lock − x)/D_s, 0, 1), where x_lock is the finger's x at the moment the direction locks (so nothing jumps by the touch slop) and s₀ is s at that moment (0, or wherever a caught peek or settle was) |
| Settle | `move` = `spring(0.9, 700)` with `initialVelocity = −v/D_s` (v in dp/s, positive to the right) on an `Animatable` with bounds [0, 1]: it ends at the bound, so no overshoot frame is drawn (no bounce). From rest: 317 ms to 0.1 %, 194 ms to 1 % |

Reduced motion (2.8.4): no translation. The frame is a fade-through on paper: the library at alpha 1 − smoothstep(0, 0.5, s), the settings page at smoothstep(0.5, 1, s), so two typesettings never overlap (H2); a release completes with `reducedCrossfade` (150 ms) from the current s.

#### 4.23.3 The gesture

| Rule | Open (on the library) | Close (on the settings page) |
|---|---|---|
| Armed when | s = 0 and p = 0 (the scene collapsed), in any library state: empty, idle, transcribing, ready, live or paused. Not while search, a menu, a rename, the rationale sheet or the IME is open, nor while a transition runs (T1′, T1′e, T7b, T16′, T27′); a page caught mid-settle or mid-peek (0 < s < 1) is armed whichever way it was heading (Catch) | s = 1, no IME, no Licenses page above it |
| Touch-down region | **x ≥ max(32 dp, `systemGestures.left`) and x ≤ W − max(32 dp, `systemGestures.right`)**, so the system Back gesture's zones are never taken (Focal never calls `setSystemGestureExclusionRects`); y below the status bar and above the topmost bottom-column element − 8 (ref 811 with the dock alone, 755 with the feedback pill showing). Anywhere on the stage, the list or the empty composition, including on rows, Copy and the live stage | The same x rule; anywhere on the page below its top bar |
| Direction lock | Decided once per pointer stream, at the first move where the finger has travelled more than the touch slop (`ViewConfiguration.scaledTouchSlop`, ≈ 8 dp, as the straight-line distance from the touch-down point; Δx and Δy are measured from the same point): **horizontal if abs(Δx) > 1.5·abs(Δy) and Δx < 0.** The swipe then claims the stream: it consumes the events on `PointerEventPass.Initial`, so the list's scroll, the stage's expand drag, a row's press and long-press all cancel. Otherwise the vertical handlers keep the stream and the swipe cannot claim it later; a rightward horizontal stream is ignored | Horizontal if abs(Δx) > 1.5·abs(Δy) and Δx > 0; otherwise the page's own vertical scroll (at large font scales) keeps it |
| Tracking | s = clamp(s₀ + (x_lock − x)/D_s, 0, 1) | the same formula, s₀ = 1 at rest: s = clamp(s₀ − (x − x_lock)/D_s, 0, 1) |
| Release | v ≤ −1000 dp/s → 1; v ≥ +1000 dp/s → 0; otherwise s ≥ 0.35 → 1, else 0 | v ≥ +1000 → 0; v ≤ −1000 → 1; otherwise s ≤ 0.65 → 0, else 1 |
| Catch | A touch-down while s settles, or during the peek, stops it at once: `Animatable.stop()`, and the peek's coroutine is cancelled too (`stop()` does nothing during the peek's 400 ms hold, and the spring-back must not resume under the finger). While 0 < s < 1 the lock accepts either horizontal direction and tracking is the formula above from the current s₀. Release: commit in the drag's net direction if its net travel since the lock is at least 0.35·D_s or the speed is at least 1000 dp/s, otherwise go to the end nearer to s₀. A touch that ends without a lock (a tap) resumes the settle to the target it had (the peek: back to 0) | same |
| Never | From an edge zone, the dock row, the feedback pill or the navigation gesture inset; from a stream the list or the expand drag already owns; during a morph or a stage ↔ page transition | From the top bar's ← (a tap) |

v is the finger's horizontal velocity from a `VelocityTracker` over the last ~90 ms. No haptic (2.9) and no announcement. While the finger is down and during the settle, request `FrameRateCategory.High` (honoured on API 35+ adaptive-refresh devices).

#### 4.23.4 Closing
- **Swipe** right across the page (4.23.3).
- **←** (top bar, 12–60 × 48–96) and **Esc**: s 1 → 0 on `stage` from rest. The **Back key** (3-button navigation, a hardware Back key; API ≤ 33, where back has no progress) reaches the same `PredictiveBackHandler` as the gesture: its flow emits no events, so only the `animateTo(0f, stage)` after `collect` runs.
- **Predictive back** (API 34+): the gesture drives s itself: s = 1 − `BackEventCompat.progress` while the gesture runs, so the page follows the gesture's progress (the system reports it along the screen width, as the in-app swipe tracks the finger along D_s; the finger is not pinned to the page, because the system owns the gesture). Commit: `animateTo(0)` on `move`. Cancel: `animateTo(1)` on `move`. `swipeEdge` is ignored: the page always leaves to the right, also for a gesture from the right edge. The generic page preview of 2.8.5 (scale 0.9) is not used here.
- **Licenses** (pushed from Settings) returns to Settings with 2.8.5's generic predictive back; the strip stays at s = 1 underneath.
- After a close the library is exactly as it was: the scroll position, the stage, a running recording.

#### 4.23.5 The peek (discoverability)
A one-time hint that the library has a page beside it: s goes to 32/420 (the library slides 32 dp left; the seam and the settings page's blank 24 dp leading margin show at x 380–412, with their rounded corners at the top and bottom), holds, and springs back.

| Phase | Motion |
|---|---|
| Out | s 0 → 32/D_s on `move` from rest (317 ms to 0.1 %; the peek budgets 330 ms) |
| Hold | 400 ms |
| Back | s → 0 on `move` (≈ 330 ms) |
| Total | ≈ 1.06 s |

- **Why 32 dp:** the settings page's content starts 24 dp inside it, so 8 dp of seam + 24 dp of page show its edge and never a clipped glyph (H1). It is a nudge of the whole screen, legible from its motion and the seam.
- **When.** *Peek 1:* the first time the library is **idle** for 1200 ms (on the first launch this is the empty library). *Peek 2:* the first time the library is idle for 1200 ms while at least one note exists, in a later library visit than peek 1 (in practice: on Back from the first note's page, once its stage has settled). **Idle** = the activity RESUMED and the window focused; s = 0 and p = 0; no pointer down since the library appeared; no capture running; no transition running (T1′, T1′e, T7, T7b, T8″, T16′, T27′); no IME, menu, sheet, permission dialog or feedback pill; search closed. A visit is each time the library becomes the visible destination.
- **Never** once settings has been opened by any path (the swipe, the TalkBack action, the App info gear, Ctrl+,), and never more than twice in the app's life.
- **Persistence.** Two keys in the app's local preferences (Jetpack DataStore Preferences): `settings_opened: Boolean` (set the first time s reaches 1, or `SettingsActivity` first resumes) and `settings_peeks: Int` (0–2, incremented when a peek starts); the visit index of peek 1 lives in memory. Local only, never reported anywhere. If a backup restore brings them back as defaults, the app may peek again: harmless.
- **Interruption.** A touch-down during the peek catches it (4.23.3): a user who reaches for the page mid-peek can drag it open (the touch cancels the peek's coroutine as well as its animation, so the hold cannot end in a spring-back under the finger). Anything else (a capture starts, the app stops) springs s to 0 on `move`; the peek still counts as played.
- **Silent:** no haptic, no sound, no announcement (TalkBack users have the action, 4.23.6).
- **Reduced motion:** the library does not move. The seam and the settings page's leading 32 dp fade in over the right edge with `reducedCrossfade` (150 ms), hold 400 ms and fade out (150 ms).

#### 4.23.6 Alternatives: every input reaches Settings

| Input | Path |
|---|---|
| TalkBack, Switch Access, Voice Access | The custom action **"Open settings"** (`a11y_open_settings`) on two nodes present in every library state: (1) the library's first node (the stage, or the empty composition) and (2) the dock's pill (Record, or Finish while a capture runs). It runs the same motion (`move`; the crossfade under reduced motion). On arrival accessibility focus moves to the "Settings" heading, and the page's pane title is "Settings"; on close focus returns to the node that ran the action |
| App info (system Settings › Apps › Focal) | Android shows a **gear** in App info when an activity handles `android.intent.action.APPLICATION_PREFERENCES` (API 24+). Focal declares a `SettingsActivity` with `<intent-filter><action android:name="android.intent.action.APPLICATION_PREFERENCES" /><category android:name="android.intent.category.DEFAULT" /></intent-filter>`, `android:exported="true"` (no permission required: it shows only the theme, the storage size and About), `android:taskAffinity=""` and `android:excludeFromRecents="true"`, so it never stacks on the library's task and Back returns to App info. It hosts the same `SettingsScreen` composable (and Licenses) on paper, opens directly at its content (no swipe), and Back and ← finish it with the system's cross-activity back animation. Theme changes apply app-wide at once (`AppCompatDelegate.setDefaultNightMode`) |
| Keyboard | **Ctrl+,** on the library opens Settings with the same motion; Esc or Back closes it. Handled in `Activity.onKeyShortcut`, so it works with no focused node (a Compose key modifier would need one). Listed in the system's keyboard-shortcuts helper (Meta+/) through `Activity.onProvideKeyboardShortcuts`: group "Focal", "Open settings", Ctrl+, |
| Touch | The swipe; the peek teaches it |

#### 4.23.7 With the rest of the app
- **While recording:** the swipe works; the library, with its live stage and Pause | Finish dock, slides away while the capture runs; the notification keeps Pause and Finish. If the scene is opened from the notification or the launcher shortcut while s > 0, s snaps to 0 in the first frame under the covering scene (as T5′ snaps the list), so the scene always folds back into a visible stage.
- **The feedback pill** slides away with the library. A pending deferred action (Delete, Remove audio) commits when s reaches 1, as when the app is backgrounded.
- **The stage's drags:** a downward drag on a live stage expands it (6.5); a leftward drag past the lock opens Settings; on a note stage a downward drag only scrolls the list. The lock gives each stream to one handler.
- **RTL:** mirrored. Settings is the page on the start side; a left-to-right swipe opens it; the seam, the corners and the peek mirror; the edge rule is symmetric already.
- **Wide windows:** ≥ 600 dp, the same strip at the window's width. ≥ 840 dp (two panes), the swipe starts on the list pane and the whole window slides. **Landscape phone:** the same, except that the touch-down exclusion is the 96 dp end-side rail (5.14) instead of the bottom row; the system-gesture side insets still apply.
- **State:** `rememberSaveable` keeps whether settings is open; on restore s snaps to 0 or 1 without animation.

---

## 5. Screens and states

All y values are ref. Every paper screen is edge-to-edge with transparent system bars, a top fade, a bottom fade above the bottom column, and a content column capped at 600 dp.

**Navigation structure.** Navigation 3 `NavDisplay` with destinations Library, Note(id), Licenses. Transcribing, Ready and Editing are states of Note(id). **Settings is not a destination:** it is the second page of the Library destination's horizontal strip, driven by `settingsProgress` s (4.23), which handles back with `PredictiveBackHandler` while s > 0; `SettingsActivity` hosts the same screen for the App info entry (4.23.6). **The recording surface is not a destination**: it is a root-level overlay above `NavDisplay` driven by one progress value, `expansion` p (6.4): 0 = the stage in the library, 1 = the full scene. It handles back with `PredictiveBackHandler` while p > 0. Finish in the scene pushes Note(id) onto the back stack *under* the overlay without a transition, runs the settle (T7), then resets p to 0 and removes the overlay. Finish in the library dock pushes Note(id) with T7b. Collapse animates p to 0 (T5′); the library underneath is already there. Use `SharedTransitionLayout` for row ↔ note and stage ↔ note.

### 5.1 Library

#### 5.1.1 List (default)

| Element | Ref position | Style |
|---|---|---|
| Stage | x 24–388, y 56–296 | 4.22. Idle: the latest note ("Notes for Thursday’s design review", time "16:05" on the title's first baseline, three lines of text, "12 min", glass Copy) |
| Day label "Today" | x 24, 328–348 (stage bottom + 32) | `section` `muted`; sticky only after scrolling (4.12.1) |
| Row 1 / Row 2 | titles at 364 / 460 (pitch 96) | 4.12.2 |
| Day label "Yesterday" | 568–588 | |
| Row 3 / Row 4 | titles 604 / 700 | |
| Bottom fade | gradient 747–811, solid below | 2.6 |
| Dock | Search 24–80 · Record 92–388, y 819–875 | 4.3 |

The stage plus about four rows are visible on a 915 dp screen. No headline, app name, logo, settings control, chips, filters or dividers. Settings is a swipe to the left (4.23).

#### 5.1.2 Empty (X4)
No card: the instrument at rest on paper, the headline, the second line (4.22.5), and Record 24–388. Nothing else. On the first launch the peek plays after 1200 ms of calm (4.23.5). When the first note lands (T27′), the stage appears and holds it, the search circle grows in and Record's left edge moves 24 → 92 (`move`).

#### 5.1.3 Loading (S5)
Nothing but the dock until the first query returns; then the stage and the list appear together (`fade`). The material is never shown without content. Data is local, so this is rare.

#### 5.1.4 Recording, collapsed (V8)
Shown after the scene is folded (swipe up, chevron, Back) while capture is live or paused.
- The status bar shows the system's privacy indicator (green dot); Focal adds nothing to the status bar.
- **The stage is live** (4.22.3): the wave, the running timer, the latest resolved line. No "Recording" word.
- **The list** holds every note, including the one that was on the stage before Record, under its day label.
- **The dock** carries Pause | Finish (4.5.2); paused, Resume | Finish.
- **Paused by hand, by a call or by another app:** the wave rests at 40 %, the timer recedes, the badge sits on the wave's bottom-right. No word, no caption: the three look the same (X5).
- **Paused because storage is full:** the same, plus one line as the text region's last line, "Storage is full".
- Rows, the menu and scrolling all work; opening another note works (its Play is disabled while capture is live, 4.3). Search is not available until Finish; Settings stays one swipe away (4.23.7).

#### 5.1.5 After Finish: transcribing and ready on the stage
- Finish opens the note (V7, 5.4). **Back** folds the page into the stage (reverse T16′), which holds that note: transcribing ("4 min", "Transcribing · 82%" with its track) or ready.
- **Ready** happens in place (T8″): the pill morphs into the white Copy, one shimmer, one tick; then "4 min" · the white Copy. One tap copies the note (T9′). On Back from the first note's page, the second peek may follow (4.23.5).
- Record is available throughout; a new recording preempts the drain, and the preempted note moves to the list as a waiting row.

#### 5.1.6 Row actions
- **Long-press** → row menu at the touch point (4.15). The stage's long-press opens the same menu (4.22.6).
- **Copy text** writes the clipboard, fires `Confirm` and closes the menu. On API ≤ 32 only, the feedback pill also shows "Text copied".
- **Rename** turns the row's (or the stage's) title into a `BasicTextField` in the same style with all text selected. The IME opens and the dock hides. IME Done or tapping outside commits; Back cancels; an empty value restores the derived title.

#### 5.1.7 Delete → Undo (O6)
- The row collapses (4.12.5); a deleted stage note is replaced in place (4.22.6). The feedback pill "Note deleted · Undo" rises from behind the dock at x 24–388, y 763–811 (4.8). Record does not move.
- **Undo:** the row re-expands in place and develops (or the stage crossfades back); the pill sinks behind the dock.
- **Remove audio** from a row or the stage: "Audio removed · Undo"; the length stays.

#### 5.1.8 Errors (all in the feedback pill)

| Situation | Message | Action |
|---|---|---|
| Not enough storage to start | "Free up about 300 MB to record." (the missing reserve rounded up to the next 50 MB) | "Storage" → `Settings.ACTION_INTERNAL_STORAGE_SETTINGS` |
| Microphone busy | "Another app is using the microphone." No note is created. | "Try again" |
| Start failed after the expand began | The scene folds back into the stage (T5′ timing), which returns to its previous content; then the pill shows the reason from this table | as above |
| Empty recording (no bytes or exactly constant PCM) | "Nothing was recorded". The note is discarded; the stage returns to the previous latest note. | none |

The Record pill never disables silently.

### 5.2 Search (O7)

| Element | Ref position |
|---|---|
| Search field | x 24–388, 561–617 (keyboard at 625); 819–875 without an IME inset |
| Results stack | bottom-anchored, last row ends 24 above the field (537) |
| Count head "3 notes" | `section` `muted`, 16 above the topmost result's title |
| Rows | 24 apart, normal row anatomy |

- **Open** (T17): the stage, the sticky day label and the list fade out; the circle becomes the field; the keyboard opens. The settings swipe is not armed while search is open. With an empty query the upper screen is empty paper.
- **Results.** Each keystroke is debounced 120 ms and runs FTS4. Rows keep their anatomy; the run-in becomes the day ("Today", "Yesterday", "Mon", "3 Nov"); the snippet shows about 40 characters either side of the first hit (`…` where cut), with hits in 600 `ink` inside the `muted` snippet. `LazyColumn(reverseLayout = true)`: the most recent match sits nearest the field and the stack scrolls upward.
- **No results:** one `secondary` `muted` line, 16 dp above the field: No notes contain “boyler”.
- **Open a result:** the note opens scrolled so the first hit's paragraph sits at 30 % of the viewport, with an `s2` radius-8 highlight on the hit that fades after 1500 ms. Back returns to the results with the query intact (keyboard closed, field in the dock slot).
- **Close:** ← or Back (the system closes the keyboard first) reverses T17 and restores the list's scroll position.
- **Normalisation.** Case and accents are ignored: "café" = "cafe", "Ä" = "a", "ё" = "е" in both cases; "й" and "и" stay distinct (combining marks are stripped only after Latin and Greek base letters). The FTS shadow text and the query are normalised alike, with an offset map so highlights land on the original characters. Tokenizer `unicode61`.

### 5.3 Recording scene (O5)

#### 5.3.1 Start
- **Microphone permission first.** If it is missing, Record shows the system dialog (or the rationale sheet after a denial, 5.11) and nothing expands until it is granted; on grant, capture starts and the expand runs with no second tap.
- **Expand** (T1′) out of the stage; from the empty library T1′e, out of the resting instrument. Only Record starts a recording: the dock's pill, or the launcher shortcut "Record" (5.13). The service starts capture in parallel; capture is never gated on the animation. `ToggleOn` fires when the service confirms capture. If the list is scrolled, it scrolls to the top during the expand and the morph's origin follows the stage's live bounds (6.4).
- **Before text:** geometry in 4.21.7: wave 176 at 236, timer "0:00" at 452, the empty state slot at 540, hint at 668, Pause + Finish in the dock. No "Recording" word.
- **Notifications** (API 33+) are requested 600 ms after capture is confirmed, never before the first audio (5.11).

#### 5.3.2 Live text (the lift)
- On the first resolved stretch, the instrument lifts (T2): the wave 176 → 136 dp and up to top 116; the timer to 268; the state slot to 354; the hint exits. The text region opens at 398.
- From then on only the latest stretch is `sceneInk`; older text is `sceneMuted` and dissolves into the region's top mask (T3).
- **TalkBack:** the text region has the custom action "Read latest paragraph" (a polite announcement). Arrivals are not announced on their own.

#### 5.3.3 Scrolled up
A manual scroll of more than one paragraph stops follow; "↓ Latest" appears at 767–807, right edge 388. Tapping it scrolls back (`move`) and resumes follow.

#### 5.3.4 Paused (manual)
- Dock: Pause circle → "● Resume" pill 24–172; Finish 184–388 (4.7, T4).
- The pause badge (28 dp) arrives on the hero wave's bottom-right (`fadeFast`); the state slot stays empty; timer → `sceneMuted` (`develop`); wave → the full logo profile at 40 %, glow off (`move` heights, `fade` alpha). No word. No blinking, pulsing or loops.
- Text that already resolved keeps developing, because the worker drains the tail during the pause.
- The same happens on the stage when the scene is collapsed (4.22.3: the 22 dp badge, no caption), and Pause/Resume work from either dock.

#### 5.3.5 Paused automatically
A call or another app taking the microphone pauses exactly like a manual pause: the badge, no word, no caption, nothing moves (X5). The reason is in the notification (5.12) and spoken by TalkBack ("Paused for a call."). The one exception is storage: Resume cannot work until space is freed, so the reason is written, once, in one short line.

| Reason | Scene state line | Stage (4.22.3) |
|---|---|---|
| A call took the microphone | empty (the badge) | the badge, no caption |
| Microphone silenced (system or another app) | empty (the badge) | the badge, no caption |
| Storage nearly full | **Storage is full** | the badge + "Storage is full" as the text region's last line |
| Resume tapped, storage still short | unchanged | unchanged; the feedback pill shows "Free up about 300 MB, then resume." with the "Storage" action (5.1.8) |

#### 5.3.6 Speech-model conditions (never block recording)
- Model copy in progress: the state slot shows "Preparing speech model".
- Model can't load (memory or storage): the state slot shows "Text will catch up". The wave stays live; transcription is retried after Finish or at the next foreground start.

#### 5.3.7 Collapsed into the stage
- **Swipe up, the chevron, Back, or predictive back** fold the scene into the stage (T5′, T28, T29). Capture never stops on leave.
- **Swipe up** (6.5): a drag that starts above the dock row and outside the navigation gesture inset, claimed once it moves up past the touch slop. On the text region it is claimed only while follow is on (the region is at its end); otherwise the region scrolls first. The scene's bottom edge follows the finger; release past 40 % of the travel (p ≤ 0.6) or a fling faster than 1000 dp/s up completes; otherwise it springs back to full screen.
- **Predictive back** drives the same morph (2.8.5): the scene folds toward the stage as you swipe, up to the release threshold; commit completes the fold, cancel springs back.
- Swipe down in the scene does nothing except scroll the text region (there is no swipe-down collapse: the stage is above).
- The library then shows 5.1.4.

#### 5.3.8 Notifications off
The hint slot reads "Notifications are off, so Pause and Finish are only in Focal." once per recording; it takes priority over the first-text hint.

### 5.4 Transcribing (after Finish)

- **Settle** (T7): `Confirm` fires on tap; the scene contracts into the dock pill's bounds while the note page develops underneath. The pill is the progress pill.
- **Finish from the library dock** (T7b): `Confirm`; the live stage opens into the same page (container transform), and the dock's Pause | Finish become ▶ | progress pill in place.
- **Back** from the page: the page folds into the stage (reverse T16′), which holds the note in the same state (5.1.5). Predictive back previews it (2.8.5).
- **Layout:** top bar ← and ⋮ (no pencil); title (derived, or the `muted` fallback "Note at 14:32") at 112, `title`; meta at 188 ("Today, 14:32 · 4 min"); body from 232; the **writing head** (40 dp wave, working sweep) 16 dp after the last paragraph at x 24; dock ▶ + progress pill.
- **Commits.** Each committed window moves the fill (`move`), opens the new text and develops it; the writing head stays at the end.
- **What works:** word taps and ▶ play the resolved text's audio; long-press selection. Edit and Find are unavailable (no pencil; the menu has Rename and Delete only).
- **Ready:** (1) the last commit develops the tail; (2) the writing head fades and its line collapses (`fadeFast` + `move`); (3) the fill reaches 100 %, the pill → `inv` (`fade`) and its label goes "Transcribing" → copy glyph + "Copy" in one fade-through; `Confirm` on that frame (2.9); no check; (4) the pencil fades in (`fade`); (5) TalkBack hears "Note ready" (spoken only, never drawn). No shimmer: paper has no material.

| Sub-state | Pill label and fill | Writing head | Notice under the meta |
|---|---|---|---|
| Waiting (preempted by a new recording) | "Waiting to transcribe", static fill at p | paused | "Transcription continues after the current recording." |
| Preparing speech model | "Preparing speech model", static fill | working | none |
| Model couldn't load | "Waiting to transcribe" | paused | "Not enough free memory to transcribe right now." + small pill "Try again" |
| Process restarted mid-drain | resumes automatically in the foreground | working | none |

### 5.5 Ready note: reading (O8)

| Element | Ref position | Style |
|---|---|---|
| Top bar | ← 12–60, ✎ 304–352, ⋮ 352–400, y 48–96 | `ink` |
| Title | x 24–388, from 112 (two lines: 112–180) | `title`, at most 3 lines (4 at 200 %) |
| Meta | 8 below the title (188–208) | `meta` `muted` tnum |
| Notices | 8 below the meta | 4.16 |
| Body | 24 below the meta (from 232); paragraphs 16 apart | `body` |
| Bottom fade | 747–811 | |
| Dock | ▶ circle 24–80, Copy 92–388, 819–875 | |

- **Meta formats:** "Today, 14:32 · 24 min", "Yesterday, 21:47 · 52 min", "Mon, 16:40 · 1 h 12 min" (past week), "28 Oct, 09:12 · 3 min", "28 Oct 2025, 09:12 · 3 min". " · Text only" is appended when the audio has been removed.
- **Scroll position** is saved per note id (`rememberSaveable`) and restored on return within the process; opening from a search result overrides it.
- **Interactions:**
  - Tap a word: plays from 0.5 s before it (5.6).
  - Tap the title: inline rename (all text selected, IME Done; an empty value restores the derived title, shown as a `faint` placeholder).
  - Long-press: system selection with the standard toolbar. One `SelectionContainer` wraps the lazy column (cross-paragraph selection of visible items; verify on device).
  - ▶ with no session: plays from the start of the topmost visible paragraph; with a paused session, resumes.
  - **Copy:** press scale; `setPrimaryClip(ClipData.newPlainText(title, body))` with body = visible paragraphs joined by "\n\n" (title, hidden paragraphs and doubt marks excluded); `EXTRA_IS_SENSITIVE` on API 33+; then `Confirm`, check + "Copied" for 1600 ms and a polite "Copied". No toast or feedback pill on any API (API 33+ shows the system preview; on ≤ 32 the in-pill "Copied" is the confirmation).
  - ✎ enters Edit (5.7). ⋮ opens the note menu (4.15).

### 5.6 Playing

| Element | Ref position |
|---|---|
| Player strip | 763–811 (4.10) |
| Bottom fade | gradient 691–755, solid below |
| Dock | ‖ "Pause playback" + Copy |

- **Start** (tap a word or ▶): the lens fades in at the word (`fadeFast`, scale 0.92 → 1 `press`); other paragraphs recede (`develop`); the strip enters; ▶ → ‖ (`fadeFast`); audio starts 0.5 s before the word.
- **Focus.** The playing paragraph is `ink`, the rest `muted`; the lens follows the current word (2.8.1). Without exact word times there is no lens.
- **Follow.** The playing line stays within the middle third of the viewport (`move`). A manual scroll suspends follow and shows the "Now playing" jump pill (711–751); follow resumes on the pill, the next word tap or ▶.
- **Other taps:** another word seeks; ‖ pauses (focus and lens stay); dragging the playhead scrubs.
- **End:** Back (predictive: the strip follows the gesture down) or the end of the audio: the strip exits, paragraphs return to `ink` (`develop`), the lens fades (`fadeFast`), the session clears.
- **Audio focus:** focus loss and headset unplug pause. Playback stops when the activity stops.

### 5.7 Edit

- **Enter by the pencil only.**
  - From playback: playback pauses, the strip exits, the caret lands before the playing word, the field focuses and the IME opens. (Hear the mistake, tap the pencil, type.)
  - Otherwise: Edit opens with no paragraph focused and no IME; every paragraph stays `ink`. A tap places the caret, focuses that paragraph and opens the IME.
- **Layout (IME open):** edit bar (4.1.3) at 40–104; the focused paragraph on its sheet; provenance line under it when edited; the keyboard from 625; no dock. The caret line is kept above the keyboard (`move`).
- **While editing:** the focused paragraph is `ink` on the sheet; the others and the title are `muted`; a tap places the caret, never plays.
- **Split and merge.** Enter splits the paragraph at the caret (caret to the new paragraph's start). Backspace at position 0 merges with the previous one, joined with a single space unless whitespace already exists. Both are undoable; each half keeps its words' timings.
- **Undo/redo:** one log for the session across paragraphs, split, merge, restore and Replace all.
- **Autosave:** 600 ms after typing stops and on every paragraph commit. No "Saved" message. Failure: 4.1.3.
- **Exit:** Done or Back commits: the sheet fades (`fadeFast`), paragraphs rack to `ink` (`develop`), the edit bar crossfades back, the keyboard closes and the dock returns (`move`). Nothing is discarded; there is no dialog.

### 5.8 Find and replace (O7, R2, R3)

| Element | Ref position |
|---|---|
| Top fade | solid 0–40, gradient to 88; no top-bar items |
| Note text | everything `muted`, title included; matches `ink` on `s2`; current match `onInv` on `inv` |
| Current match | scrolled to the centre of the band from status + 24 (64) to row 1 − 24 (489), i.e. y ≈ 276 |
| Bottom fade | gradient 465–513, solid below (409–457 while the feedback shows) |
| Feedback "Replaced 4 · Undo" | x 24–388, 457–505 (8 above row 1) |
| Row 1 | find field 24–244, nav pill 252–388, 513–561 |
| Row 2 | replace field 24–244, Replace all (N) 252–388, 569–617 |
| Keyboard | from 625 |

- **Open** from the note menu or Edit's ⋮ (T20). The IME opens with focus in the find field; the dock and strip slide away; the top-bar items fade out.
- **Matching** follows search normalisation (5.2). ∧ ∨ and IME Search move the current match and scroll it to the band centre (`move`, or a crossfade when more than 3 screens away). The count updates after a 120 ms debounce.
- **Replace all (N)** replaces every match verbatim in one undoable step (T21): the text updates, the count reads "No matches", the button disables, and "Replaced 4 · Undo" rises 8 dp above row 1. Undo restores all of them in one step. Inside Edit, the same step is also in the edit undo log.
- **Close:** ← in the find field or Back (after the system closes the keyboard). Returns to the previous mode with focus restored (`develop`): reading (dock back) or Edit (sheet back on the same paragraph).
- Without an IME inset the rows sit at 771–819 / 827–875 and the feedback at 715–763.

### 5.9 Other note states

| State | Treatment |
|---|---|
| Legacy or edited text without word times | Taps play from the paragraph start, or from the nearest unchanged word through alignment. No lens; paragraph focus only. Times are never invented. |
| No speech recognized (sound, no words) | Fallback title in `muted`. The body is one `body` `muted` line: "No speech was recognized." ▶ stays; Copy is disabled. |
| Partial failure | Notice "Part of this note couldn’t be transcribed." + small pill "Retry" under the meta. A gap row (4.13) at each missing range. Copy copies what exists. Retry → Transcribing for the failed ranges only. |
| Recording ended by process death | Notice, `muted` only: "Recording stopped unexpectedly at 41:12. Everything before that was saved." |
| Recording ended because microphone access was revoked | Notice: "Recording stopped at 41:12 because microphone access was turned off. Everything before that was saved." |
| Audio removed | The ▶ circle exits (alpha `fadeFast`, width → 0 `move`); Copy widens to 24–388 (`move`). Word taps do nothing. Meta gains " · Text only". Feedback "Audio removed · Undo" (deferred, like Delete). |
| A capture is live elsewhere | ▶ and word taps are disabled ("Play, unavailable while recording"), so the speaker never leaks into the recording. |

### 5.10 Settings and Licenses

**Entry.** Settings is the page beside the library (4.23): the swipe, the TalkBack action "Open settings", Ctrl+, , or, from outside the app, the App info gear (`SettingsActivity`, 4.23.6). It holds Theme, Storage and About.

**Settings layout (ref):**

| Element | Position |
|---|---|
| ← | 12–60 × 48–96 |
| Title "Settings" | x 24, 112–146 |
| Group "Theme" | 170–190 |
| Segmented control System \| Light \| Dark | x 24–388, 198–246 |
| Group "Storage" | 278–298 |
| Row "Audio" + "1.4 GB" | title 318–342, description below (3 lines at 1.0×) |
| Group "About" | 454–474 |
| "Works offline" (informational) | title 494 |
| "Source code" ↗ | title 592 |
| "Open-source licenses" › | title 648 |
| Footer | static mark 32 dp, 48 below the last row; "Focal 1.0.0" `caption` `muted`, 8 below, centred (776–794) |

- **Theme:** default System. Applies immediately (T22).
- **Storage:** "Audio", value "1.4 GB" (tnum), description "Kept for 38 notes. To free space, remove audio from a note in its menu. The text stays." Not pressable.
- **About:** "Works offline" — "Focal has no internet permission. Audio and text never leave this phone." (not pressable). "Source code" ↗ opens the repository URL through `ACTION_VIEW` (no web view, no INTERNET permission). "Open-source licenses" › opens Licenses.
- The footer is the only place the mark appears as a signature on paper outside the empty state.

**Licenses.** Title "Open-source licenses". Each entry: name (`rowTitle`), then licence and origin (`secondary` `muted`); tap expands the full text in place (`move`; `secondary`, selectable). Entries: Parakeet Ultra speech model (CC BY 4.0; Moondream post-training of NVIDIA Parakeet TDT 0.6B v3, with the attribution text); sherpa-onnx (Apache 2.0); ONNX Runtime (MIT); Inter (SIL OFL 1.1); AndroidX and Jetpack Compose (Apache 2.0); Material Symbols (Apache 2.0); Kotlin (Apache 2.0). Generated from the build's licence manifest.

### 5.11 Permissions

- **Microphone, first Record tap:** the system dialog appears directly, with no pre-screen; the tap is the context. On grant, capture starts (and the expand runs) with no second tap.
- **After a denial** (`shouldShowRequestPermissionRationale` = true), the next Record tap shows the rationale sheet (4.19) over the library: title "Focal needs the microphone"; body "Focal turns speech into text on this phone. It has no internet access, so your audio never leaves the device."; [Allow microphone] → the system dialog; "Not now".
- **Permanently denied:** the sheet's pill reads "Open settings" (app details). On return with the permission granted, recording starts immediately.
- **Notifications** (API 33+): requested once, 600 ms after the first capture is confirmed; never re-requested automatically. If denied: the scene hint in 5.3.8. The FGS still runs; Android lists it in Task Manager.

### 5.12 Notifications

**Small icon:** the existing sharp white mark (`ic_notification`). Never colourised (`setColorized(false)`, no colour).

| Channel | Name | Importance | Sound / vibration | Badge |
|---|---|---|---|---|
| `recording` | Recording | LOW | none | no |
| `recording_paused` | Recording paused | DEFAULT | system default | no |
| `transcription` | Transcription | LOW | none | no |
| `ready` | Finished notes | DEFAULT | system default | yes |

| State | Channel | Title / text | Extras | Actions | Tap | Public version |
|---|---|---|---|---|---|---|
| Recording | recording | "Recording" / none | `setUsesChronometer(true)`, `setWhen(now − recordedMs)`, ongoing, `setOnlyAlertOnce`, `FOREGROUND_SERVICE_IMMEDIATE`, `CATEGORY_STOPWATCH` on 31+ (verify), else `CATEGORY_SERVICE` | Pause · Finish | Opens the app with the scene expanded | Recording + chronometer |
| Paused (manual) | recording | "Paused · 12:04" / none | chronometer off, ongoing | Resume · Finish | Scene expanded | Paused |
| Paused (auto) | recording (ongoing update) + one alert on `recording_paused` only when the app isn't visible, cancelled on resume | "Paused · 12:04" / the reason as a sentence: "A call is using the microphone." / "Another app is using the microphone." / "Storage is full." | | Resume · Finish | Scene expanded | Paused |
| Transcribing | transcription | "Transcribing" / the note title | `setProgress(100, p, false)`, ongoing (FGS), `setOnlyAlertOnce` | none | Opens the note | Transcribing + progress |
| Transcription paused (`onTimeout`) | transcription | "Transcription paused" / "Open Focal to continue." | auto-cancel; `stopSelf()` within seconds | none | Opens the note (the drain resumes in the foreground) | Transcription paused |
| Note ready | ready | "Note ready" / the note title | auto-cancel; posted only if that note isn't on screen; grouped when several. The one place the words appear on a surface a person reads; the app's own UI never draws them (X6) | none | Opens the note | Note ready |
| Partial failure | ready | "Part of a note couldn’t be transcribed" / the note title | auto-cancel | none | Opens the note | same |

- `VISIBILITY_PRIVATE` with the public versions above. Transcript text never appears in a notification; only the title, and only when unlocked.
- **Live Update** (API 36.1+, `SDK_INT_FULL ≥ VERSION_CODES_FULL.BAKLAVA_1`): Recording and Paused request `setRequestPromotedOngoing(true)` with `POST_PROMOTED_NOTIFICATIONS`; Paused sets `setShortCriticalText("Paused")`. An enhancement only; never repost after the user dismisses it.
- **Action icons:** ‖ Pause, ● Resume, ■ Finish. Notification Finish fires no haptic (the app is not in front).

### 5.13 Launcher shortcut, splash and entry

**Static shortcut** (`res/xml/shortcuts.xml`): `shortcutId="record"`, short label "Record", long label "New recording", disabled message "Recording isn’t available". Icon `ic_shortcut_record`: adaptive, background `#F3F3F3`, foreground an `#111111` filled circle 24 dp in the 108 dp canvas, monochrome layer the same circle. Intent action `app.focal.action.RECORD` targeting `MainActivity`.

| Situation | Behaviour |
|---|---|
| Settings is open (s > 0) when the shortcut or the notification opens the scene | s snaps to 0 under the covering scene (4.23.7), then as below |
| No live capture, permission granted, storage OK | The activity opens with the Library under the scene overlay, already expanded at p = 1 (no morph: the bars light up from the full profile, the chrome fades in). The service starts in the foreground from the visible activity. `ToggleOn` on confirmation. |
| A capture is already live | Opens it (scene expanded). Never a second recording. |
| Permission missing | Opens the Library and runs 5.11 as if Record had been tapped. |
| Storage short | Opens the Library with the storage feedback. |

**App info gear** (`APPLICATION_PREFERENCES`, API 24+): opens `SettingsActivity` on Focal's settings, in the caller's flow; Back returns to App info (4.23.6).

**Splash** (`androidx.core:core-splashscreen`): background `paper` per theme; animated icon = a static vector of the mark in `ink` (alpha falloff, no blur) inside the 2/3 safe circle; no icon background; exit with alpha `fadeFast`; kept only until the first frame. From the shortcut in Light theme the splash is white and the first frame is the scene, so the exit is a 150 ms white-to-material crossfade (accepted; it is not a flash).

### 5.14 Landscape, large screens, 200 % font

- **Landscape phone** (height < 480 dp): top bar 48 dp. The dock becomes a 96 dp end-side rail with its controls stacked and vertically centred: the circle (56) above the primary control, which becomes a 56 dp circle with a glyph only (content description = label; tooltip shows it). The player strip stays a 48 dp row under the text column. The feedback pill spans the text column, 8 dp above the strip or the bottom inset. The scene uses two columns: instrument (wave, timer, state, centred) on the start half, text region on the end half, rail at the end edge.
- **Width ≥ 600 dp:** the content column and the bottom column are capped at 600 and centred.
- **Width ≥ 840 dp:** `ListDetailPaneScaffold` (Library | Note); the dock lives in the detail pane; Record sits at the list pane's bottom when the detail pane is empty; the scene overlays the whole window. No orientation locks.
- **The stage** in landscape and on wide windows: 4.22.1. The morph's target is always the full window; in the landscape two-column scene the wave and the timer travel to the start half's centre.
- **The settings swipe** in landscape and on wide windows: 4.23.7. The empty library's composition stays centred in the content column.
- **200 % font:** see 8.4.

---

## 6. Transitions

Durations are settle times (2.8). "+n" is a start delay after the trigger. Under reduced motion, every transition between the stage, the scene and the page becomes a 150 ms `reducedCrossfade`, the drag becomes an alpha preview, and everything else snaps (2.8.4).

### 6.1 Table

| ID | From → to | Choreography | Total |
|---|---|---|---|
| T1′ | Library → scene (**expand from Record**) | Record `press`. The morph (6.4) runs p 0 → 1 on `stage` from the stage's bounds (from the empty library: T1′e). On top of it: the stage's note content leaves with the clip's top-left (`fadeFast`); the wave lights up in the stage's live slot, pair d from +24·d ms (`fade`), at the entering level (the full profile, history seeded at v = 1), and travels with p (it is not also scaled up from zero); glow from +60 (`fadeSlow`); "0:00" from +40 (`fade`); the dock's glyph and label go search → ‖ and "● Record" → "■ Finish" (`fadeFast`, fading through so the two words never overlap, 4.3), then the dock's dress follows the material. The library switches to its live layout at p ≥ 0.95, while covered. `ToggleOn` on capture confirmation, independent of the animation. | 484 ms |
| T1′e | Empty library → scene (**Record from the empty library**) | The morph runs p 0 → 1 on `stage` from the resting instrument's box A = (134, 268, 144 × 112), radius 28·(1 − p), every clip edge linear in p as in 6.4. The material inside the clip fades in, a = `fadeFast` from the trigger, so the box never pops dark (`emptyMaterialFade`); no Dark ring. The bars keep their centre (206, 324) and grow 112 → 176 dp (h = lerp(112, 176, p)), change dress from ink to scene with a (paper dress at 1 − a over the scene dress at a; in Dark both dresses are light, so only the scene dress is drawn) and rise from the resting level to the live level, pair d from +24·d ms (`fade`); glow from +60 (`fadeSlow`). The headline and the second line leave at alpha (1 − a)², so they are gone before the material is opaque (H3). "0:00" is born in its scene slot (centre 206, 494) at scale 0.92 → 1, alpha `fade` from **+160 ms**. The hint arrives as in 6.4. Dock: Pause grows 0 → 56 at x 24 while the pill's left edge moves 24 → 92 (`move`), "● Record" → "■ Finish" fades through; the material's edge wipes the dress from p 0.820 to 0.925. Status icons flip at p ≥ 0.955 (6.2). `ToggleOn` on capture confirmation. | 484 ms |
| T2 | First text (**lift**) | Wave 176 → 136 dp and top 236 → 116; timer top 452 → 268; the state slot 540 → 354 (all `stage`). The hint exits (`fadeFast`). The text region opens and the paragraph develops at +60 (533). | ≈ 600 ms |
| T3 | Next text | The paragraph opens (`move`) and develops at +60; the previous bright stretch recedes at +140 (`develop`); follow-scroll (`move`). The stage's text region does the same at its size. | ≈ 680 ms |
| T4 | Pause ↔ Resume | In the scene and in the library dock: circle 24–80 ↔ Resume pill 24–172 and Finish 92–388 ↔ 184–388 (`move`, together); ‖ ↔ "● Resume" (`fadeFast`). Scene (28 dp) and stage (22 dp): the pause badge in (`fadeFast`) on the dimmed wave, out on resume; no word. Timer colour (`develop`). Wave heights (`move`), alpha 1 ↔ 0.4 and glow (`fade`). By a call or another app: identical. Storage full only: "Storage is full" in the scene's state slot (`fadeFast`) and, on the stage, developing as the region's last line while the transcript above recedes. Nothing moves. | ≈ 530 ms |
| T5′ | Scene → stage (**fold**: chevron, Back, Esc) | The morph p 1 → 0 on `stage`. If the list was scrolled while the scene was open, it is snapped to the top in the first frame (it is covered). No alpha fade: the material lands on the same material. | 484 ms |
| T6′ | Live stage → scene (**expand from a tap**, Enter) | The morph p 0 → 1 on `stage`. The wave keeps its live level (no light-up). | 484 ms |
| T7 | Scene → note (**settle**, Finish) | `Confirm`. Scene content (chevron, timer, state slot, hint, text, dock) fades out (`fadeFast`). The clip contracts from the window into the dock pill's bounds (x 92–388, y 819–875, r 0 → 28), `stage`; scene alpha 1 → 0 from 50 % of the travel (`fade`). Underneath, Note(id) is composed in Transcribing: the title develops at +120 and the meta at +200 (`fade`); paragraphs fade in (`fadeSlow`) from +180, staggered 24 ms bottom-up; the writing head sits after the last paragraph, working; the dock is ▶ + progress pill at the current p. System bars flip back per 6.2. | ≈ 520 ms (+ ≤ 264 ms stagger) |
| T7b | Library → note (**Finish in the library dock**) | `Confirm`. Container transform from the stage's bounds to the window (`move`), r 28 → 0; material → paper over the first 60 % of the travel; the live content leaves (`fadeFast`); the page develops as in T7 (title +120, meta +200, paragraphs `fadeSlow` from +180); the dock's Pause \| Finish become ▶ \| progress pill (geometry unchanged; containers crossfade, glyphs and labels fade through, 4.3); the page's own chrome (top fade, ← ⋮) fades in with the paper, never ahead of it. | ≈ 400 ms |
| T8 | Window commit / Ready (note page) | Fill `move`; new text develops. At Ready: writing head `fadeFast` + collapse `move`; pill → `inv` (`fade`) and its label → copy glyph + "Copy" in one fade-through; `Confirm` on that first frame (2.9); no check; pencil `fade`. No shimmer (paper). | ≈ 400 ms |
| T8″ | Window commit / Ready (**stage**: the readiness moment) | Each commit moves the track fill and the percentage (`move`). At Ready, t from the commit, with the stage on screen, **one morph from +0:** the pill narrows w₀ → 136, right edge fixed (`move`); its fill goes glass → `stageAction` white (`fade`); its content fades through, "Transcribing · 100%" and the track out at 1 − smoothstep(0, 0.5, q), the copy glyph + "Copy" (`#111111`) in at smoothstep(0.5, 1, q), q on `fade`; **`Confirm` fires on this first frame** (2.9). **+160 → +880** one shimmer: `stageShimmer` crosses the card left to right between the material and the text (`readyShimmer`, alpha sin(π·u)), masked out of the highlight band (F1). **≈ 880** at rest: "4 min" · white Copy. No check, no text. The length never leaves. Plays only for an on-screen transition and at most once per note; in the background or off screen the stage simply shows the white Copy. TalkBack hears "Note ready" (spoken only). Reduced motion: 2.8.4. | ≈ 0.9 s |
| T9 | Copy (page) | `press` scale. `Confirm`. Glyph → check (`fadeFast` + 0.7 → 1 `press`), label crossfade; hold 1600 ms; revert (`fadeFast`). | 1750 ms |
| T9′ | Copy (**stage**) | As T9, then the pill goes white → glass (`fade`): the note is no longer fresh. | ≈ 2 s |
| T10–T15 | Playing, lens, player, Edit, provenance, Done | Defined in 4.10, 4.14, 5.6 and 5.7: T10 word tap → playing; T11 lens step; T12 close player; T13 reading → Edit; T14 provenance; T15 Edit → reading. | |
| T16 | Library row ↔ note | Container transform with `sharedBounds`: row bounds → page (`move`). The row title is a `sharedElement`, 17 → 28 sp with `ScaleToBounds`. Other rows `fadeFast`. Body fades in at +80 (`fade`). The dock morphs (`animateBounds` `move`, contents `fadeFast`). Back reverses into the row. | ≈ 400 ms |
| T16′ | **Stage ↔ note** | Tap on the stage (note states): container transform from the stage's bounds to the page (`move`), r 28 → 0; the material crossfades to paper over the first 60 % of the travel; the title is a `sharedElement`, 22 → 28 sp (`ScaleToBounds`), `stageInk` → `ink`, x 44 → 24, top 76 → 112; the time, text and footer leave (`fadeFast`); the body arrives at +80 (`fade`); the dock: Search \| Record → ▶ \| Copy or progress (containers crossfade, glyphs and labels fade through, 4.3; the geometry is the same); the page's chrome (top fade, ← ✎ ⋮) has the paper's alpha, so no white band or half-clipped icon appears inside the dark card. **Back reverses it into the stage** (V7, S1): paper → material over the last 60 %, the stage's time, text and footer arrive over the last 40 % (`smoothstep(0.6, 1, q)`), the dock returns to the idle library dock (Search \| Record, fade-through). Predictive back previews it (2.8.5). | ≈ 400 ms |
| T17 | Search open / close | The stage fades out with the list and the sticky label (`fade`). | ≈ 320 ms + IME |
| T18–T23, T25, T26 | Menu, Delete → Undo, Find, Replace all, Theme, Remove audio, Rationale, Splash | Defined with their components and screens (4.15, 5.1.7, 5.8, 5.9, 5.10, 5.11, 5.13). Delete of the stage note: 4.22.6. | |
| T24′ | **Library ↔ Settings** (the swipe, ←, Back, predictive back, Ctrl+,, the TalkBack action) | Both pages move as one strip, finger-tracked: library x = −s·(W + 8), settings x = (1 − s)·(W + 8), the 8 dp `s2` seam between them, seam-side corners 28 dp in flight (4.23.2). Release past 0.35 or a fling completes on `move` (no bounce, clamped); otherwise it springs back. Keys, ← and the action animate from rest on `stage`. Reduced motion: fade-through, 150 ms | finger + ≤ 317 ms |
| T27′ | Empty → first note | T1′e from the resting instrument. Finish lands on the note (V7); Back folds the page into the stage, which appears here for the first time at 56–296 (reverse T16′); the list below it is still empty (the stage's note is not repeated, 4.22.2). The search circle grows from width 0 and Record's left edge goes 24 → 92 (`move`). | ≈ 400 ms |
| T28 | **Drag the live stage down / swipe the scene up** | Only while a capture runs (X4). The morph tracks the finger: `p = clamp(p₀ + Δy / D, 0, 1)`. Release: 6.5. Settle on `stage` with the finger's velocity, clamped at the bounds. | finger + ≤ 484 ms |
| T29 | **Predictive back on the scene** | `p = 1 − 0.4·PathInterpolator(0, 0, 0, 1)(progress)`. Commit: p → 0 on `stage`. Cancel: p → 1 on `move`. | gesture + ≤ 484 ms |
| T31 | **The settings peek** (4.23.5) | s 0 → 32/420 on `move`, hold 400 ms, s → 0 on `move`. A touch catches it. At most twice per install, never after settings has been opened. Reduced motion: the edge fades in and out in place | ≈ 1.06 s |

### 6.2 System-bar icon flip (R7)
- Observe the morph's clip with `snapshotFlow { clipRect }`. **Status bar:** light icons (`isAppearanceLightStatusBars = false`) in the first frame where the clip's top edge is at or above the status-bar top + 12 dp (where the icons' glyphs begin: ref y ≤ 12, i.e. p ≥ 0.786 with the stage at y 56, p ≥ 0.955 in T1′e from y 268); back to the theme's appearance when the top edge is below + 16 dp (p < 0.714). The 4 dp hysteresis stops flicker while a finger hovers at the threshold.
- **Navigation bar:** light icons when the clip's bottom edge is at or below the navigation inset top + 12 dp (ref ≥ 903, p ≥ 0.981); back above + 8 dp (p < 0.968).
- T7 restores the theme's appearance in the first frame where the clip's top edge drops below the status-bar inset bottom (and the bottom edge rises above the navigation inset).
- In Dark theme the icons are already light; nothing flips. Under reduced motion, flip when the crossfade's alpha passes 0.5.
- Never leave a frame with dark icons over the material or light icons over paper: the dimmed library (up to 30 % black) still reads as paper.
- The settings swipe never flips anything: both pages are paper (4.23.2).

### 6.3 IME-driven motion
- Search field, find rows and the dock's hide/show read `WindowInsets.ime` in the layout phase (`Modifier.windowInsetsPadding` or `offset { }` from `WindowInsets.ime.getBottom(density)`), so on API 30+ they move frame-by-frame with the system curve.
- API 26–29 don't animate IME insets: the rows appear at their final position when the keyboard settles, with a 150 ms alpha fade-in (`fadeFast`) to soften the jump. Verify on an API 26 device.

### 6.4 The morph: every element as a function of p

One value, `expansion` (p), drives the whole stage ↔ scene change, whoever moves it: a finger (T28), a spring after a tap (T1′, T1′e, T5′, T6′), Back or predictive back (T29). Every element below is a pure function of p, read in the layout or draw phase, so a drag never recomposes. The same frames play forwards and backwards. The expand animation is kept exactly (W7). While paused, the badge persists and grows 22 → 28 dp, and only "Storage is full" hands over to a state line; T1′e starts from the resting instrument.

**Inputs.** A = the stage's `boundsInRoot()` (ref 24, 56, 364 × 240; T1′e: the resting instrument's box 134, 268, 144 × 112), read every frame (it moves only if the list scrolls). W = the window. D = W.bottom − A.bottom (ref 619). "Scene" values are for the scene's current layout: with text (wave top 116, h 136; timer top 268; text region 398–795) or before text (wave top 236, h 176; timer top 452; no text region).

| Element | Function of p (ref values) |
|---|---|
| Clip rect | left = lerp(24, 0, p); top = lerp(56, 0, p); right = lerp(388, 412, p); bottom = lerp(296, 915, p). Every edge linear, so the bottom edge moves exactly with the finger. Measured: p 0.25 → x 18–394, p 0.6 → 9.6–402.4, p 0.9 → 2.4–409.6. T1′e, from the instrument's box: left lerp(134, 0, p), top lerp(268, 0, p), right lerp(278, 412, p), bottom lerp(380, 915, p) |
| Corner radius | 28·(1 − p) dp |
| Material | Drawn in the clip rect: gradient stops and highlight ellipse relative to the clip (they stretch with it); grain in window pixels (it does not swim). Alpha 1, except T1′e: a = `fadeFast` from the trigger |
| Dark ring | `stageRing` at alpha 1 − p (none in T1′e: there is no card to separate) |
| Library layer (all but the stage and the dock) | scale 1 − 0.06·p about the window centre; black dim 0.30·p. The stage item itself is hidden while p > 0 (the overlay draws it) and shown again in the frame p reaches 0 |
| Wave box | height h = lerp(56, 136, p) [176 before text]; centre y = lerp(104, 184, p) [324] (T1′e: h lerp(112, 176, p), centre (206, 324) throughout, dress ink → scene with the material's alpha); centre x = lerp(80, 206, smoothstep(0.1, 1, p)). The x eases in so the timer can step down out of its way first; it arrives with zero velocity. The live level never stops; glow on (paused: 40 %, no glow); blur σ scales with h |
| Timer | laid out once at 76 sp; scale k = lerp(48/76, 1, p); centre x = lerp(132 + 49.7, 206, p) (half the reserved "0:00" width at 48 sp; per format, R5); centre y = lerp(104, 310, 1 − (1 − p)²) [494 before text], an ease-out, so it steps down early and never collides with the growing wave; colour `#F7F7F7` (paused: lerp(`#999999`, `#8C8C8C`, p)) |
| Text region (one box) | top = lerp(148, 398, e), where e = 1 − (1 − p)² is the timer's ease-out, so the region's top stays 17.5–46 dp below the timer's box at every p (a linear top would come within 2 dp of the digits at p 0.4); bottom = clip.bottom − lerp(20, 120, p), so the latest line rides the bottom edge with the finger; top mask height lerp(34, 128, p). Two typesettings, both bottom-anchored, each at its own fixed width **and its own x**, so lines never reflow and are never cut: the stage's (17/24, width 324) at x = clip.left + 20, alpha 1 − smoothstep(0.1, 0.35, p); the scene's (20/30, width 364) at x 24, alpha smoothstep(0.37, 0.62, p). The clip is symmetric about x 206, so the scene's lines keep ≥ 8.9 dp from both clip edges whenever they are visible. A fade-through: two typesettings are never visible at once. The box spans the window's width; the clip does the horizontal clipping |
| Pause badge (paused, any cause) | rides the wave box: centre = box origin + (64, 47)·(h/56); diameter lerp(22, 28, p) (ring and ‖ scale with it); alpha 1 at every p, so pause is drawn at every p and the badge is also the scene's paused mark |
| Scene state line (storage full only) | "Storage is full", centred on the timer's centre x, its top 2 dp under the timer's box, so it moves with the timer's ease-out and never meets the digits; alpha smoothstep(0.37, 0.62, p). Colour `sceneMuted`. No other pause draws a state line |
| Chevron | centred on the clip's centre x, top = clip.top + 48; alpha smoothstep(0.7, 1, p) |
| Hint (before text), model condition | at their scene positions; alpha smoothstep(0.7, 1, p) |
| "Storage is full" | the stage's typesetting carries it as its last line until p 0.35; the scene's state line carries it from p 0.37 (row above), so it is never absent. There is no notice |
| Dock | never moves; drawn above both layers. The material's bottom edge wipes its dress (`dockDress`, 2.8.6): paper dress below the edge, scene dress above it, glyphs and labels included, from p 0.845 (edge 819) to p 0.935 (edge 875); T1′e from p 0.820 to 0.925. In a slow drag the edge visibly re-dresses the controls as it passes behind them |
| Status / navigation icons | 6.2 (p ≥ 0.786 / p ≥ 0.981, with hysteresis; T1′e p ≥ 0.955 / p ≥ 0.978) |
| List, sticky label | part of the library layer; covered as the clip passes them |
| Stage note content (T1′ only) | at the clip's top-left, alpha `fadeFast` from the trigger (not a function of p: it never comes back) |

**Springs.** Taps: `stage` from rest (484 ms to 0.1 %, 296 ms to 1 %). Release: `stage` with `initialVelocity = v / D`; `Animatable` bounds [0, 1] end the animation at the bound, so a fast fling meets the window edge and stops there rather than overshooting. Predictive back cancel: `move`.

### 6.5 Gesture rules

| Rule | Expand (drag the live stage down) | Collapse (swipe the scene up) | Settings (swipe the library left, 4.23.3) |
|---|---|---|---|
| Armed when | A capture is live or paused, the scene is collapsed, the touch-down is inside the stage's bounds, and the list is at offset 0 at touch-down | The scene is expanded and the touch-down is above the dock row (y < dock top − 16, ref 803) and outside the navigation gesture inset; on the text region only while follow is on | s = 0 and p = 0; the touch-down at least max(32 dp, the system gesture inset) from both edges, above the bottom column, anywhere on the library's content |
| Claimed | After the touch slop (`ViewConfiguration.scaledTouchSlop`, ≈ 8 dp) if the motion is downward and abs(Δy) > abs(Δx). Upward or sideways: the list gets it | After the slop if upward and abs(Δy) > abs(Δx). Downward: the text region scrolls (or nothing) | After the slop if abs(Δx) > 1.5·abs(Δy) and Δx < 0, decided once; it then wins over the list and the expand drag for the whole stream |
| Tracking | p = clamp(p₀ + Δy / D, 0, 1) | p = clamp(p₀ + Δy / D, 0, 1) (Δy < 0) | s = clamp(s₀ + (x_lock − x) / D_s, 0, 1) |
| Release | v ≥ +1000 dp/s → 1; v ≤ −1000 → 0; else p ≥ 0.4 → 1, otherwise 0 | v ≤ −1000 → 0; v ≥ +1000 → 1; else p ≤ 0.6 → 0, otherwise 1 | v ≤ −1000 → 1; v ≥ +1000 → 0; else s ≥ 0.35 → 1, otherwise 0 (on `move`) |
| Catch | A touch-down during any settle stops it (`expansion.stop()`); the new drag starts from the current p and may go either way | same | same, on `settingsProgress` (also during the peek) |
| Never | Starts from a drag that began elsewhere on the list, or from a list fling that reached the top; overscroll stretch is not shown while the stage claims the drag. **A drag on a note stage or the empty library starts nothing: only Record starts a recording (X4)** | Starts in the gesture inset (that is Home) or on Pause, Resume or Finish | Starts in an edge zone (that is the system's Back), on the dock row or the feedback pill, or from a stream another handler owns |

v is the finger's velocity from a `VelocityTracker` over the last ~90 ms, in dp/s, positive downward (the expand and collapse) or rightward (settings). Taps and keys use the same p: Record (T1′, T1′e), a tap or Enter on the live stage (T6′), the chevron, Back or Esc (T5′). No haptic on any of them (2.9) until capture is confirmed. While the finger is down and during the settle, request `FrameRateCategory.High` (honoured on API 35+ adaptive-refresh devices); `Normal` otherwise.

---

## 7. Strings

English default in `res/values/strings.xml`. Sentence case. The one noun is **note**; never recording (as a noun), session, transcript or passage in the UI. Typographic punctuation. Durations and dates come from `DateUtils` / `DateTimeFormatter` with the device locale and 12/24 h setting, never `Locale.ENGLISH`.

### 7.1 Library, the stage and search

| ID | String |
|---|---|
| `cd_search_open` | Search notes |
| `search_hint` | Search notes |
| `cd_search_close` / `cd_search_clear` | Close search / Clear |
| `day_today` / `day_yesterday` | Today / Yesterday |
| `empty_title` | Speak, and it’s written down. |
| `empty_body` | Tap Record and talk.\nText appears after each pause. (two lines, an explicit break) |
| `search_count` (plurals) | %d note / %d notes |
| `search_none` | No notes contain “%s”. |
| `title_fallback` | Note at %s |
| `dateline_today` / `dateline_yesterday` / `dateline` | Today, %s / Yesterday, %s / %1$s, %2$s (note meta, TalkBack) |
| `stage_when` | The stage's time: today the bare time ("16:05"); `day_yesterday`; the abbreviated weekday ("Mon"); "28 Oct"; "28 Oct 2025", from `DateTimeFormatter` with the device locale |
| `len_s` / `len_min` / `len_h` | s / min / h (units drawn small after separate numerals: “12 min”, “1 h 12 min”, “42 s”) |
| `stage_transcribing` | Transcribing · %d%% |
| `stage_waiting` / `stage_preparing` | Waiting to transcribe / Preparing speech model |
| `paused_storage` | Storage is full (the stage's last line and the scene's state line: the only visible pause caption) |
| `row_transcribing` / `row_waiting` / `row_preparing` | Transcribing / Waiting to transcribe / Preparing speech model |
| `row_percent` | %d%% |
| `dur_s` / `dur_min` / `dur_h` / `dur_h_min` | %d s / %d min / %d h / %1$d h %2$d min |

There are no strings for a recording or paused row, a live pill, "Note ready" or "Paused" on the stage, "Everything so far is saved." or a settings control, and `empty_title` does not name the app. Pause reasons for calls and other apps are spoken only (7.8).

### 7.2 Actions and menus

| ID | String |
|---|---|
| `action_record` / `action_pause` / `action_resume` / `action_finish` | Record / Pause / Resume / Finish |
| `action_play` / `action_pause_playback` | Play / Pause playback |
| `action_copy` / `action_copied` | Copy / Copied |
| `action_transcribing` | Transcribing |
| `action_done` / `action_retry` / `action_try_again` / `action_undo` / `action_storage` | Done / Retry / Try again / Undo / Storage |
| `menu_copy_text` / `menu_find` / `menu_share` / `menu_rename` / `menu_remove_audio` / `menu_delete` / `menu_restore` | Copy text / Find and replace / Share / Rename / Remove audio / Delete / Restore original |
| `cd_back` / `cd_edit` / `cd_more` / `cd_undo` / `cd_redo` | Back / Edit / More options / Undo / Redo |

### 7.3 Feedback pill

| ID | String | Action |
|---|---|---|
| `feedback_deleted` | Note deleted | Undo |
| `feedback_audio_removed` | Audio removed | Undo |
| `feedback_replaced` (plurals) | Replaced %d | Undo |
| `feedback_restored` | Original restored | Undo (TalkBack action outside Edit) |
| `feedback_text_copied` | Text copied | none (API ≤ 32, row menu only) |
| `feedback_nothing_recorded` | Nothing was recorded | none |
| `feedback_storage` | Free up about %s to record. | Storage |
| `feedback_storage_resume` | Free up about %s, then resume. | Storage |
| `feedback_mic_busy` | Another app is using the microphone. | Try again |
| `feedback_save_failed` | Couldn’t save your edit. Free up some space, then tap Retry. | none |

### 7.4 Recording scene

| ID | String |
|---|---|
| `cd_collapse` | Collapse. Recording continues |
| `state_preparing` / `state_later` | Preparing speech model / Text will catch up |
| `hint_first_text` | Text appears after each pause. |
| `notice_notifications_off` | Notifications are off, so Pause and Finish are only in Focal. |
| `n_paused_call` / `n_paused_other_app` / `n_paused_storage` | A call is using the microphone. / Another app is using the microphone. / Storage is full. (notification text only, 5.12; the storage line is the same words as the in-app caption `paused_storage`) |
| `jump_latest` / `jump_now_playing` | Latest / Now playing |
| `a11y_read_latest` | Read latest paragraph |

The scene draws no "Recording" or "Paused" and has no pause notices: "Recording" exists only in spoken semantics (7.8), the badge is the paused mark, and the state line shows only `paused_storage` (7.1) or a model condition, which stands alone.

### 7.5 Note, edit and find

| ID | String |
|---|---|
| `meta` / `meta_text_only` | %1$s · %2$s / Text only |
| `no_speech` | No speech was recognized. |
| `partial_failure` | Part of this note couldn’t be transcribed. |
| `gap_not_transcribed` | %s not transcribed |
| `ended_unexpectedly` | Recording stopped unexpectedly at %s. Everything before that was saved. |
| `ended_permission` | Recording stopped at %s because microphone access was turned off. Everything before that was saved. |
| `waiting_after_recording` | Transcription continues after the current recording. |
| `model_failed` | Not enough free memory to transcribe right now. |
| `provenance_edited` | Edited |
| `not_saved` | Not saved |
| `find_hint` / `replace_hint` | Find in note / Replace with |
| `find_count` / `find_none` | %1$d of %2$d / No matches |
| `cd_find_close` / `cd_find_prev` / `cd_find_next` | Close find / Previous match / Next match |
| `replace_all` / `replace_all_n` | Replace all / Replace all (%d) |

### 7.6 Settings, licences and permissions

| ID | String |
|---|---|
| `settings_title` / `settings_theme` | Settings / Theme |
| `theme_system` / `theme_light` / `theme_dark` | System / Light / Dark |
| `settings_storage` / `storage_audio` | Storage / Audio |
| `storage_desc` (plurals) | Kept for %d note. To free space, remove audio from a note in its menu. The text stays. / Kept for %d notes. To free space, remove audio from a note in its menu. The text stays. |
| `settings_about` | About |
| `works_offline` / `works_offline_desc` | Works offline / Focal has no internet permission. Audio and text never leave this phone. |
| `source_code` / `licenses` | Source code / Open-source licenses |
| `version` | Focal %s |
| `perm_title` | Focal needs the microphone |
| `perm_body` | Focal turns speech into text on this phone. It has no internet access, so your audio never leaves the device. |
| `perm_allow` / `perm_not_now` / `perm_settings` | Allow microphone / Not now / Open settings (the system's app details; not Focal's settings) |
| `kbd_group` / `kbd_open_settings` | Focal / Open settings (the keyboard-shortcuts helper, Ctrl+,) |

### 7.7 Notifications and shortcut

| ID | String |
|---|---|
| `ch_recording` / `ch_paused` / `ch_transcription` / `ch_ready` | Recording / Recording paused / Transcription / Finished notes |
| `n_recording` / `n_paused` | Recording / Paused · %s |
| `n_transcribing` | Transcribing |
| `n_timeout_title` / `n_timeout_text` | Transcription paused / Open Focal to continue. |
| `n_ready` / `n_partial` | Note ready / Part of a note couldn’t be transcribed |
| `n_public_recording` / `n_public_paused` / `n_public_ready` | Recording / Paused / Note ready |
| `shortcut_short` / `shortcut_long` / `shortcut_disabled` | Record / New recording / Recording isn’t available |

### 7.8 Accessibility

| ID | String |
|---|---|
| `a11y_duration` (plurals) | %1$d hours %2$d minutes %3$d seconds (each part omitted when 0) |
| `a11y_scene_state` / `a11y_scene_state_paused` | Recording, %s / Paused, %s |
| `a11y_stage_live` | Recording, %1$s. Latest text: %2$s |
| `a11y_stage_paused` | Paused, %1$s. (automatic: Paused, %1$s, %2$s., with `a11y_reason_*`) |
| `a11y_reason_call` / `a11y_reason_other_app` / `a11y_reason_storage` | paused for a call / paused for another app / paused, storage is full (spoken only; also the polite announcements "Paused for a call." and so on) |
| `a11y_stage_note` | Latest note. %1$s. %2$s, %3$s. (title, full date and time, length) |
| `a11y_stage_empty` | Speak, and it’s written down. Tap Record and talk. Text appears after each pause. |
| `a11y_open_settings` | Open settings (custom action on the library's first node and the dock's pill, 4.23.6) |
| `settings_pane` | Settings (the settings page's pane title) |
| `a11y_copy_note_new` | Copy note, new (the fresh white Copy) |
| `a11y_expand_recording` | Expand recording |
| `a11y_collapse` | Collapse |
| `a11y_copy_note` | Copy note |
| `a11y_transcribing` | Transcribing, %d percent |
| `a11y_audio_position` / `a11y_position_of` | Audio position / %1$s of %2$s |
| `a11y_play_here` / `a11y_edit` / `a11y_copy_paragraph` | Play from here / Edit / Copy paragraph |
| `a11y_unclear` (plurals) / `a11y_play_unclear` | %1$d unclear word: %2$s / Play unclear word |
| `a11y_note_ready` / `a11y_note_deleted` | Note ready / Note deleted. Undo available. |
| `a11y_replace_all` (plurals) | Replace all, %d match / Replace all, %d matches |
| `a11y_play_unavailable` | Play, unavailable while recording |
| `a11y_open_note` / `a11y_source_code` | Open note (the stage's click label) / Source code, opens in browser |
| `a11y_announce_paused` / `a11y_announce_resumed` | Paused. / Resumed. (polite announcements; an automatic pause adds its reason, `a11y_reason_*`: "Paused for a call.") |

---

## 8. Accessibility

### 8.1 Contrast and colour
- Text is at least 4.5:1 in every state, receded text and the scene included; non-text that carries meaning is at least 3:1 (2.1, 2.2). Overrides exist for the contrast setting and high-contrast text.
- **Not by colour alone:** playback = lens shape + ‖ glyph + announcement; editing = sheet + edit bar; current match = inverted shape + "2 of 4"; recording = the moving wave + the running timer + the Pause | Finish dock (and the spoken state); paused = the badge on the dimmed wave (stage and scene) + the receded timer + Resume in the dock (and the spoken state); storage full adds the written line; transcribing on the stage = the track + "Transcribing · 82%"; ready = the pill's label changing from "Transcribing · N%" to "Copy" + the white fill + `Confirm` + TalkBack's spoken "Note ready"; doubt = dot pattern + description.
- The paper → material change is a spatial expansion from the stage, which is already the material, not a flash; under reduced motion it is a 150 ms crossfade, never a cut.

### 8.2 TalkBack structure

| Surface | Structure |
|---|---|
| Library | Order: stage → its Copy → day label → rows → dock (Search, Record). The stage (or, in an empty library, the composition) and the dock's pill (Record, or Finish) carry the custom action "Open settings" (4.23.6). The stage is one merged node (4.22, 12): note states "Latest note. Notes for Thursday’s design review. Today, 16:05, 12 minutes." with custom actions = the row menu, then its Copy button "Copy note"; live "Recording, 4 minutes 16 seconds. Latest text: …" with the `expand` action "Expand recording" (8.6). Day labels are headings. Each row is one merged node: title, duration, time, preview; custom actions = the row menu. Dock: "Search notes" and "Record", or "Pause"/"Resume" and "Finish" while a capture runs. |
| Settings | Pane title "Settings"; on arrival focus moves to the "Settings" heading. Order: ← "Back", the heading, Theme (a radio group: System, Light, Dark), Storage, About rows, the footer. On close focus returns to the node that opened it. The swipe and the peek are not announced; the pane change is |
| Search | Initial focus on the field. Traversal: count head, results top to bottom, then the field. The result count is a polite announcement after the debounce. |
| Feedback pill | A polite live region; Undo is a button and a custom action on the pill; indefinite while touch exploration is on. |
| Scene | Order: (1) chevron "Collapse. Recording continues"; (2) one merged node for timer + state: "Recording, 4 minutes 16 seconds" (the word is spoken, not drawn; seconds are not announced as they tick; state changes are polite: "Paused.", "Paused for a call.", "Paused for another app." or "Paused, storage is full.", although only the last is written); (3) the text region, with the custom action "Read latest paragraph"; (4) Pause / Resume; (5) Finish. The scene root has the `collapse` action "Collapse" (8.6). The wave, grain and material are `invisibleToUser()`. Arrivals are not announced. |
| Note | The title is a heading. One node per paragraph, custom actions "Play from here", "Edit", "Copy paragraph", and "Restore original" when edited. No word-level nodes, so a 9,000-word note never becomes thousands of targets. |
| Pills | Copy (page and stage): "Copy" / "Copy note", then announces "Copied". Progress pills (page dock and stage): disabled, `progressBarRangeInfo`, "Transcribing, 64 percent" at 25 % steps; the stage announces "Note ready" at Ready (spoken only; nothing is drawn; the fresh Copy reads "Copy note, new"). The empty library's composition reads `a11y_stage_empty` and offers "Open settings". |
| Player | Adjustable "Audio position", ±5 s, state "%1$s of %2$s". |
| Edit | The focused paragraph is an editable text node; Undo and Redo report disabled. |
| Find | Fields "Find in note" / "Replace with" take focus in that order; "2 of 4" is a polite live region; "Replace all, 4 matches"; the ↳ glyph is hidden. |

### 8.3 Targets and word taps
All controls are at least 48 dp: dock 56; the stage 364 × 240 and its Copy 136 × 48; feedback action 48; find controls 48 (∧ ∨ 68 × 48 each); Done 40 visual / 48 target; jump pill 40 / 48; menu items and segments 48; the playhead row 48; the chevron 48. The settings swipe has no target of its own; its equivalents are 4.23.6. Word taps are a pointer convenience: each line is a 28 dp target, the tap slop covers the full line height, and `getOffsetForPosition` resolves to the nearest word on that line. They are never the only path; paragraph actions cover TalkBack and Switch Access.

### 8.4 200 % font
- Day labels and titles wrap; durations wrap under the title from 1.5.
- The stage grows with its text (4.22.7); the morph reads its real bounds.
- Pills wrap their labels (min 56); the feedback pill and find rows grow vertically from a 48 dp minimum while the 136 dp control column keeps its width ("Replace all (4)" may wrap to two lines).
- The scene timer is sized once per scene (4.21.3, range 56–76 sp). From font scale 1.5 the instrument is compact (4.21.7), so at least three lines of live text stay visible.
- The edit bar keeps Done on one line and moves Redo into ⋮ from 1.8. The segmented control stacks if it would truncate. The note title allows 4 lines.
- Screenshot tests at 1.0 / 1.3 / 2.0 × in Light and Dark for the screens of the gallery (13).

### 8.5 Keyboard, RTL and language
- Space plays or pauses (reading). Ctrl+C copies the note when nothing is selected. Ctrl+F opens Find. Ctrl+Z / Ctrl+Shift+Z undo and redo in Edit. Esc closes the menu, then the player, find, edit, search or the scene (fold into the stage). Enter or Space on the focused live stage expands it; Ctrl+C on a focused stage copies its note. In the scene, Space pauses or resumes. **Ctrl+,** on the library opens Settings; Esc or Back closes it (4.23.6).
- Tab order: top bar → content → bottom column. Focus ring per 4.20.
- RTL: directional glyphs mirror; the bottom column mirrors, so the circle sits at the start edge (Search at x 332–388 in RTL) and the pill fills the rest (24–320); the stage's time sits at the title's left; fills and tracks run from the start edge; the wave is symmetric. The settings strip mirrors: Settings is the page on the start side and a left-to-right swipe opens it (4.23.7).
- If the engine exposes a per-paragraph language, set a `LocaleSpan` so TalkBack uses the right voice; otherwise the device locale applies.

### 8.6 The expand/collapse gesture for every input

The drag is an accelerator, never the only path. Every outcome of T28/T29 has a tap, a key and a TalkBack action.

| Input | Expand | Collapse |
|---|---|---|
| Touch | Tap the live stage; drag it down | Tap ⌃; swipe up; Back |
| TalkBack | The live stage is one merged button: "Recording, 4 minutes 16 seconds. Latest text: If the forecast…" Its click label and its semantics `expand` action are both "Expand recording" (TalkBack: "Double-tap to expand recording"; the actions menu lists "Expand recording") | The scene root carries the semantics `collapse` action "Collapse"; the chevron is a button "Collapse. Recording continues"; Back works |
| Switch Access, keyboard, D-pad | Focus the stage, Enter or Space | Esc, Back, or the chevron |
| Reduced motion | The same gestures, as an alpha crossfade preview (2.8.4) | same |

- **Focus hand-off.** After an expand, accessibility focus moves to the scene's merged timer node ("Recording, 4 minutes 16 seconds"); after a collapse it returns to the stage. A drag that springs back does not move focus.
- **Announcements.** Expand and collapse are not announced beyond the focus move (the state is unchanged: still recording). Pause and resume are announced ("Paused." / "Paused for a call." / "Resumed.").
- **The wave, the grain and the material** are `invisibleToUser()`. Seconds are never announced as they tick; nothing in the morph is a live region.
- **No information depends on the gesture:** Pause and Finish are in the library dock, the scene dock and the notification. Starting a recording is Record alone.

### 8.7 The settings swipe for every input

The swipe is an accelerator, never the only path (X1).

| Input | Open | Close |
|---|---|---|
| Touch | Swipe the library from right to left, starting ≥ 32 dp from both edges (4.23.3); the peek teaches it twice | Swipe right; ←; Back; predictive back |
| TalkBack | The custom action "Open settings" on the stage or the empty composition, and on Record / Finish; focus lands on the "Settings" heading | ← "Back"; the Back gesture; focus returns |
| Switch Access, Voice Access | The same custom action ("Open settings" in the actions menu) | ← |
| Keyboard, D-pad | Ctrl+, (listed under Meta+/) | Esc, Back |
| Outside the app | App info › the gear (`APPLICATION_PREFERENCES`, `SettingsActivity`) | Back returns to App info |
| Reduced motion | The same inputs; a fade-through instead of the slide; the peek fades in place | same |

---

## 9. Android 8–16: fallbacks and version matrix

| Feature | API 26–27 | API 28–30 | API 31–32 | API 33–35 | API 36+ |
|---|---|---|---|---|---|
| Live wave outer-bar blur | Alpha only, no glow | `BlurMaskFilter` sprites (3.4) | `RenderEffect` layers | `RenderEffect` | `RenderEffect` |
| Light-up blur growth (T1′) | none | none | yes | yes | yes |
| Drag to expand / swipe up to collapse | Nested scroll + pointer input, identical | same | same | same | same |
| The settings swipe and the peek | Pointer input + `graphicsLayer` translation, identical | same | same | same | same |
| Predictive back on Settings | Back key → s 1 → 0 on `stage` | same | same | 33: `OnBackInvokedCallback` without progress → the same | 34+: progress drives s (4.23.4) |
| App info gear (`APPLICATION_PREFERENCES`) | yes (API 24+) | yes | yes | yes | yes |
| Keyboard-shortcuts helper (Ctrl+,) | yes (API 24+) | yes | yes | yes | yes |
| Morph wave blur (in flight) | Alpha only | Sprite cache (2 dp buckets) | Sprite cache | Sprite cache | Sprite cache |
| Stage live wave at rest | Alpha only, no glow | Sprites | `RenderEffect` layers | `RenderEffect` | `RenderEffect` |
| Predictive back on the scene | Back key → T5′ on `stage` | same | same | 33: `OnBackInvokedCallback` without progress → T5′ | 34+: progress drives p (T29) |
| Frame-rate request during the drag | no-op | no-op | no-op | 35+ adaptive refresh: `High` | same |
| Static marks (footer, sheet, gap row) | Blurred, rasterised once in software | same | same | same | same |
| Scene material + grain | `ImageShader` grain, identical | identical | identical | identical | identical |
| Text effects (develop, recede, lens, find), edge fades | Colour / draw-behind / alpha `DstIn`, identical on all APIs | | | | |
| `Modifier.blur` | Not used anywhere | | | | |
| IME-synced rows | Jump at settle + 150 ms fade | same | Frame-synced (30+) | same | same |
| System-bar icon flip | `WindowInsetsControllerCompat` (light nav icons from 26) | same | same | same | same |
| Navigation-bar contrast override | No (system scrim on 3-button nav) | 29+: off | off | off | off |
| Splash | Compat (window background + icon) | Compat | System | System | System |
| Clipboard confirmation | In-pill "Copied" (+ row-menu feedback) | same | same | System preview + in-pill | same |
| `EXTRA_IS_SENSITIVE` | no | no | no | yes | yes |
| Notification permission | not needed | not needed | not needed | requested 600 ms after capture | same |
| Haptics (four moments, incl. Ready) | Compose fallbacks | Compose fallbacks; `Confirm` native from 30 | same | same | `ToggleOn` native (34+) |
| Predictive back progress | Instant back + forward transition | same | same | 33 opt-in; 34+ progress | default on |
| `LineBreak.Paragraph` | simple | simple | simple | paragraph | paragraph |
| Themed (monochrome) icons | no | no | no | yes | yes |
| Contrast setting override | no | no | no | 34+ | yes |
| High-contrast-text API | no | no | no | no | 36 (verify) |
| Live Update chip | no | no | no | no | 36.1+ |
| `preferredFrameRate` | no-op | no-op | no-op | honoured on ARR devices | same |

**Rule.** No information depends on blur, glow, grain, haptics or motion. Each is decorative or reinforcing, and every state is fully conveyed by text, glyph or shape on API 26.

**Version notes for the stage and the gesture.**
- API 26–32: no back progress; Back runs T5′ on `stage`. The drag and the swipe are Compose pointer input and nested scroll, identical on every level.
- API 29+ gesture navigation: the swipe up never arms inside the bottom `WindowInsets.systemGestures` inset (that is Home); the scene's dock row sits above it anyway. The expand drag starts at ref ≥ 56, inside the app, clear of the notification shade's swipe (which starts in the status bar itself). The settings swipe never starts within max(32 dp, `WindowInsets.systemGestures`) of either side, so the system Back gesture always wins at the edges; Focal sets no gesture-exclusion rects. On 3-button navigation the 32 dp margin still applies, for one rule everywhere.
- API 33: `OnBackInvokedCallback` without progress → T5′. API 34+: `BackEventCompat.progress` drives p (T29). With targetSdk 36 predictive back is on by default.
- API 35+: edge-to-edge is enforced; the stage and the scene already draw behind both bars. `preferredFrameRate(High)` during the drag on adaptive-refresh devices.
- API 36: the high-contrast-text check (2.1) covers the stage's text too.
- OEM skins can alter back animations and gesture thresholds: verify on phones (14.7).

---

## 10. Performance contract (design-relevant)

- **Note body.** `LazyColumn(items(paragraphs, key = { it.id }, contentType = { "p" }))`; only the focused paragraph is a text field. Tested with a synthetic one-hour note (about 9,000 words, 500 paragraphs) and 300 notes.
- **Fast-changing state** (meter level, playback position, lens rect, scene clip) is read in draw or layout lambdas only, never at a screen root. The library does not requery each second: the live stage and the dock observe the `LiveSession` flow. The expansion p and everything derived from it are read in layout and draw lambdas only.
- **Scene.** The material layer is static; the per-frame work is the wave (nine bars + glow) and the timer once a second. Drawing stops below STARTED or when the display is off (the scene is not kept on screen; no keep-screen-on flag). `FrameRateCategory.Normal`.
- **The morph.** One `Animatable<Float>`; every element reads it in `Modifier.layout {}`, `graphicsLayer {}` or `drawWithCache {}` lambdas, never in composition, so a drag costs no recomposition. The overlay is composed once when p leaves 0 (`derivedStateOf { p > 0f }`) and removed when it returns. Per frame: the clip path, the material (gradient brush rebuilt for the clip size), nine bars from sprites, one timer layer transform, two text layers' alpha and the region's mask. Profile on the ≤ 6 GB phone: hold a drag at p 0.5 for 10 s and fling; target no dropped frames at 120 Hz on a recent Pixel.
- **The empty library** is static: the resting instrument is rasterised once per size and theme and has no frame loop. **The readiness shimmer** is one gradient rect translated in a `graphicsLayer` for 720 ms, once per note.
- **The stage while live** redraws only the wave (draw phase) and the timer once a second; frames are requested only while the stage is in the viewport and the lifecycle is STARTED (`LazyListState.layoutInfo`).
- **The settings swipe** is two `graphicsLayer` translations and one seam rect per frame; s is read only in layout and draw lambdas. The settings page is composed when s first leaves 0 (the peek counts) and kept for the session, so the first real swipe never composes mid-gesture; a cold first swipe composes one small static page.
- **Develop** recomposes one paragraph's `AnnotatedString` for about 0.5 s; profile it while the open paragraph is extended.
- **Device checks** (phone validation; renders prove none of these): the scene's GPU cost on the ≤ 6 GB phone and a recent Pixel over a 60-minute screen-on session (heat, battery, frame time), `RenderEffect` vs sprite cost (3.4), gradient banding and OLED retention on the dark material, the IME ride on API 26–29 and with floating keyboards, the `Text` ↔ `BasicTextField` swap being pixel-identical, and the Inter subset size.

---

## 11. Compose implementation map

| Piece | Implementation |
|---|---|
| Theme | `FocalTheme` builds the full `ColorScheme` (2.1) and a `FocalColors` `CompositionLocal` with every role, including `scene*` values and `centreBarTop/Bottom`. |
| Motion | `object FocalMotion { fun <T> press() = spring<T>(0.9f, 1400f); fun <T> move() = spring<T>(0.9f, 700f); fun <T> stage() = spring<T>(0.9f, 300f); fun <T> fadeFast() = spring<T>(1f, 3800f); fun <T> fade() = spring<T>(1f, 1600f); fun <T> fadeSlow() = spring<T>(1f, 800f); fun <T> develop() = spring<T>(1f, 300f); val reducedCrossfade = tween<Float>(150, easing = LinearEasing) }` |
| Dock | One `Dock(state: DockState)` in a `LookaheadScope`, drawn at the root above the recording surface; circle and pill use `animateBounds` with `move`; the feedback pill is drawn first in the same `Box` (behind). |
| Bottom column insets | A `BottomColumn` layout that places the topmost element from `min(imeTop, navTop − 8) − 8` when an IME-driven field is open, else from `navTop − 16`. |
| Scene overlay | See "Recording surface" and "Morph overlay" below. T7 uses an `Animatable<Rect>` clip from the window into the dock pill's bounds. The library underneath gets `graphicsLayer { scaleX = scaleY = 1 − 0.06·p }` plus a black overlay at `0.30·p`. |
| Bars | `NineBars(instance, state)` with `WaveRenderer` tiers (3.4); level read in `drawWithCache`. |
| Material | `SceneMaterial()` draws gradient, highlight and the grain `ShaderBrush` once; no state reads. |
| Timer | `SceneTimer(elapsed)` with a reserved-width box (4.21.3) and a font size computed once in `remember`. |
| Pause badge | `PauseBadge(diameter: () -> Dp)` (`stageBadge`, 2.2.1): a disc, a 3 dp cut-out ring and two ‖ bars in one `drawWithCache`; placed by the wave box's layout at box origin + (64, 47)·(h/56), so it rides the wave through the morph (diameter lerp(22, 28, p)); in and out with `fadeFast`; no semantics (`invisibleToUser`, the state is in the merged node). The scene's state line is a `Text` composed only for `paused_storage` and the model conditions (4.21.4) |
| System bars | `SystemBarAppearance` effect observing the scene clip (6.2). |
| Find / search rows | `KeyboardRows` reading `WindowInsets.ime`; two-column grid 220 \| 8 \| 136. |
| Haptics | `LocalHapticFeedback` at the four moments only (2.9); a `lastHapticAt` timestamp in the app's haptic helper implements the Ready guard. |
| Stage | `Stage(state)` as the `LazyColumn`'s first item (`key = "stage"`), before the first `stickyHeader`. `Modifier.clip(RoundedCornerShape(28.dp)).drawWithCache { onDrawBehind { drawSceneMaterial(size) } }`; `onGloballyPositioned { stageBounds = it.boundsInRoot() }` |
| Recording surface | `LiveSurface` at the root above `NavDisplay`, owning `val expansion = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }`. It composes `MorphOverlay` while `expansion.value > 0f` (via `derivedStateOf`) and the full `RecordingScene` at p = 1. The scene is not a destination; Finish pushes Note(id) under it (5, T7) |
| Morph overlay | A full-window `Layout` whose children are placed from p in the placement block: clip by `Modifier.graphicsLayer { shape = MorphShape(clipRect(p), 28.dp * (1 - p)); clip = true }`; material by `drawWithCache` at the clip size; `NineBars` placed by `Modifier.layout` at the lerped box with its size from a `() -> Float`; the timer laid out once at 76 sp and moved with `graphicsLayer { translationX/Y; scaleX = scaleY = k; transformOrigin = TransformOrigin(0f, 0f) }`; the text region as two `Text` layers in a box with `graphicsLayer { alpha; compositingStrategy = Offscreen }` and a `DstIn` mask. No `SharedTransitionLayout` here: the morph needs exact, scrubbable functions of one p, and a seeked transition would evaluate each spring at a seek time instead |
| Expand drag | A `NestedScrollConnection` on the library's `LazyColumn` (it consumes the list's downward leftovers at offset 0, as an overscroll effect does): armed by `Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial) … } }` on the stage when the list is at offset 0 and a capture is live or paused (no capture: nothing is armed, X4); `onPostScroll` consumes downward leftovers into `expansion.snapTo(p + Δ/D)`; `onPreScroll` consumes upward deltas while p > 0; `onPreFling` settles (6.5) and consumes the velocity when p > 0 |
| Collapse drag | `Modifier.draggable(orientation = Vertical, state = rememberDraggableState { d -> scope.launch { expansion.snapTo((expansion.value + d / D).coerceIn(0f, 1f)) } }, startDragImmediately = expansion.isRunning, onDragStopped = { v -> settle(v) })` on the scene background (outside the dock row and the gesture inset), plus a `NestedScrollConnection` on the text region's list for upward leftovers while follow is on |
| Settle | `suspend fun settle(v: Float) { val target = …(6.5); expansion.animateTo(target, FocalMotion.stage(), initialVelocity = v / travelPx) }`; a touch-down calls `expansion.stop()` (catch) |
| Taps | `scope.launch { expansion.animateTo(1f, FocalMotion.stage()) }` (T1′, T6′) or `animateTo(0f, …)` (T5′) |
| Predictive back | `PredictiveBackHandler(enabled = expanded /* derivedStateOf { expansion.value > 0f } */) { events -> try { events.collect { e -> expansion.snapTo(1f - 0.4f * BackEase.transform(e.progress)) }; expansion.animateTo(0f, FocalMotion.stage()) } catch (e: CancellationException) { expansion.animateTo(1f, FocalMotion.move()); throw e } }` (`androidx.activity.compose`). If the Navigation 3 version in use routes back through `androidx.navigationevent`, use its handler with the same contract |
| Live dock | `LiveDock(edge = { clipRect(expansion.value).bottom })` at the root above the overlay. Each control draws its two dresses in the draw phase: paper inside `clipRect(top = edge − controlTop)`, scene inside `clipRect(bottom = edge − controlTop)` (`Modifier.drawWithContent`), so a drag never recomposes the dock. Semantics and touch belong to the control, not to a dress; same `DockState` geometry |
| System bars (morph) | `snapshotFlow { clipRect(expansion.value) }` → the thresholds and hysteresis of 6.2 → `WindowInsetsControllerCompat` |
| Reduced motion | When animators are off, `MorphOverlay` draws the full scene at `alpha = p` with no clip growth or transforms; settles use `reducedCrossfade` inside `withContext(FullMotion)` |
| Stage ↔ page | T16′/T7b with the `SharedTransitionLayout` already used for T16: `sharedBounds` on the stage and the page, `sharedElement` on the title |
| Settings strip | `LibraryHost` (the Library destination's root) owns `val settingsProgress = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }`. Children: the library (`LibraryScreen` + the root `Dock` + the feedback pill) in a layer with `graphicsLayer { translationX = -s * travelPx; shape = SeamShape(end, radius = if (s > 0f && s < 1f) 28.dp else 0.dp); clip = true }`, and `SettingsScreen` in a layer with `translationX = (1 - s) * travelPx` (composed once s first leaves 0); the `s2` seam is a background drawn only while 0 < s < 1. The gesture: `Modifier.pointerInput(Unit) { awaitEachGesture { val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); if (inEdgeZone(down.position)) return@awaitEachGesture; … lock on the first move past `viewConfiguration.touchSlop` with abs(Δx) > 1.5·abs(Δy); once horizontal, consume every change on `PointerEventPass.Initial` (the `LazyColumn` and the stage's `NestedScrollConnection` never see it) and `snapTo` s; on up, settle with the `VelocityTracker`'s x velocity } }`. `inEdgeZone` reads `WindowInsets.systemGestures` and max(32.dp, it) on both sides. `PredictiveBackHandler(enabled = s > 0f)` maps `progress` to s (4.23.4); ← and Esc call `animateTo(0f, move)`; the system Back key and gesture both go through that one `PredictiveBackHandler` (no events on API ≤ 33 or 3-button navigation, so only the `animateTo(0f, move)` after `collect` runs); the off-screen layer is inert (`clearAndSetSemantics { }` + `focusProperties { canFocus = false }`, 4.23.1); the dock and the feedback pill take the same translationX in their own layers (4.23.2); `rememberSaveable` for open/closed |
| Peek | `SettingsHint` in `LibraryHost`: a `LaunchedEffect` keyed on an `isIdle` `derivedStateOf` (4.23.5) that waits 1200 ms, reads `settings_opened` / `settings_peeks` from DataStore, increments `settings_peeks`, then `animateTo(32/420, move)`, `delay(400)`, `animateTo(0f, move)`. A touch-down cancels the effect's `Job` (the hold included) and calls `stop()`: `stop()` alone does nothing while `delay(400)` runs. Reduced motion: an alpha `Animatable` on an edge overlay instead |
| Settings alternatives | `Modifier.semantics { customActions = listOf(CustomAccessibilityAction(openSettingsLabel) { openSettings(); true }) }` on the stage (or the empty composition) and the dock's pill; `paneTitle = "Settings"` on the settings page; Ctrl+, in `Activity.onKeyShortcut` (no focused node needed), listed through `onProvideKeyboardShortcuts`, with Esc in the same activity-level key hook; `SettingsActivity` (hosting `SettingsScreen`) with the `APPLICATION_PREFERENCES` intent filter (4.23.6) |
| Readiness | `ReadyMoment(noteId)` on the stage's pill: three `Animatable`s started together on the Ready frame (width `move`, white `fade`, content fade-through `fade`) and the shimmer as a `drawWithCache` linear gradient translated by a `tween` from +160 ms; `Confirm` in the same frame; `LaunchedEffect(readyAt)` guarded by lifecycle RESUMED and a `playedFor` set (once per note) |
| Empty library | `LibraryEmpty()`: `RestingBars` (112 dp, rasterised once per size and theme) + the two texts, centred on (206, 324) at 1.0×; Record's T1′e starts `MorphOverlay` from the bars' box with the material's alpha on `fadeFast` |

---

## 12. Content descriptions (complete list)

| Element | Description |
|---|---|
| Search circle | Search notes |
| Close search / Clear | Close search / Clear |
| Stage, live | Recording, %1$s. Latest text: %2$s (paused: Paused, %1$s.; automatic: Paused, %1$s, paused for a call.); click label and `expand` action "Expand recording"; custom action "Open settings" |
| Stage, note | Latest note. %1$s. %2$s, %3$s.; click label "Open note"; custom actions = the row menu, then "Open settings" |
| Empty library composition | "Speak, and it’s written down. Tap Record and talk. Text appears after each pause."; custom action "Open settings" |
| Stage Copy pill | Copy note (fresh: "Copy note, new"), then "Copied"; disabled while transcribing (the pill is a progress bar: "Transcribing, 82 percent") |
| Scene chevron | Collapse. Recording continues |
| Scene root | `collapse` action "Collapse" |
| Pause circle / Resume pill (scene and library) | Pause / Resume |
| Finish (scene and library) | Finish |
| Back | Back |
| Pencil | Edit |
| ⋮ | More options |
| Paper circle | Play / Pause playback (disabled: Play, unavailable while recording) |
| Primary pill | Record / Copy / (progress: "Transcribing, 64 percent"); in the library, Record and Finish also carry "Open settings" |
| Settings page | pane title "Settings"; ← "Back" |
| Undo / Redo | Undo / Redo |
| Find ← / ∧ / ∨ | Close find / Previous match / Next match |
| Replace all | Replace all, %d matches |
| Feedback action | Undo / Storage / Try again |
| Jump pill | Latest / Now playing |
| Source code | Source code, opens in browser |
| Every wave, mark, the pause badge, the readiness shimmer, the seam, the grain and the material | none (`invisibleToUser`; the state is in the merged node) |

---

## 13. Reference renders

The specification was drawn and checked as 412 × 915 dp reference renders: every library state in Light and Dark, the settings swipe and the peek, pause and storage-full on the stage and in the scene, the readiness moment, the expand, the settle, Back to the stage, the scene and the note. Those working files are not part of this repository; the "measured" values in this document were taken from them. Landscape, two panes, 200 % font, Licenses, search, edit, find and playing were not drawn: sections 5 and 8.4 define them.

The app's screenshot gallery ([testing](testing.md#screenshot-gallery)) renders every screen and state from fake data, in Light and Dark, some groups also at 1.3× and 2.0× font scale; it shows the current app against this specification. Neither the renders nor the gallery establish phone frame rate, blur cost, heat, battery, how a haptic feels, gesture feel under a thumb, conflicts with OEM back gestures, or recognition quality (14).

---

## 14. Risks to verify on a phone

1. **Dark material inside Light theme.** The scene is a large luminance jump in daylight and at night; the stage puts a near-black card at the top of every launch (the empty library is paper). The expand from the card softens the jump; still hallway-test it. Fallback (no setting): lower the highlight and glow in Light theme.
2. **Battery and heat.** The material is static, but the wave redraws at display rate during a long screen-on recording, on the stage and in the scene. Phone check: 60 minutes on a phone with 6 GB of RAM or less and a recent Pixel (3.4 tiers, 10).
3. **OLED retention.** A static white timer and Finish pill on near-black. Rely on the normal screen timeout (no keep-screen-on). If retention appears in testing, add sub-pixel drift of the instrument (a rendering detail, not a feature).
4. **Bottom-anchored search** reverses the usual reading order; validate with the 300-note library.
5. **The IME ride** on API 26–29 and with floating, split and OEM keyboards (6.3).
6. **Morph frame time.** A full-window clip, the material, sprite bars and two text layers per frame while the finger moves. Hold a drag at p 0.5 and fling on both phones; if frames drop, draw only the scene's latest paragraph during the morph.
7. **Gesture feel and conflicts.** The 619 dp travel, the 40 % threshold and the 1000 dp/s fling come from reasoning, not thumbs. Test one-handed: expand drag vs the list's overscroll stretch (API 31+), swipe up vs the text region's scroll, swipe up near the Home gesture, and OEM back-gesture variants. For settings: diagonal thumb scrolls (the 1.5 ratio), a left swipe that starts on a row (no accidental opens while scrolling), the 35 % threshold one-handed with the right thumb, and OEMs whose back zone is wider than 32 dp (the rule takes the system inset when it is larger).
8. **Copy above the thumb zone.** The stage's Copy sits at ref y 232–280 (V6), above the thumb zone. If one-handed tests fail, the fallback is tap → note → Copy.
9. **Discoverability of the drag.** No coach mark: the tap, Record, and Back always work; the drag is an accelerator. Watch whether people find it.
10. **Search while recording.** The dock carries Pause | Finish, so search is unreachable until Finish. If testing shows people search mid-recording, add search to the library menu, not the dock.
11. **Density.** The stage plus about four rows are visible, where a plain list would show about seven.
12. **No "Recording" word.** The live wave, the running timer, the green privacy dot and the dark Pause/Finish dock carry the state; TalkBack says it. Hallway-test that sighted users still read "recording" from across a room.
13. **The dock's dress wipe** (2.8.6). Mid-crossing, the material's edge splits each control (a white-over-black Finish for a few frames). It reads as the material passing behind the dock in motion; confirm on a phone at 60/120 Hz that a slow drag does not read as a rendering fault. Fallback: hold the paper dress until the edge passes 875, then a 150 ms `fadeFast` swap.

14. **Settings discoverability.** There is no visible control; the peek plays at most twice. Test with five first-time users: "change the theme to dark". If most fail after both peeks, the fallbacks in order: a third peek after the fifth note; a "Settings" launcher shortcut (long-press on the app icon); never an on-screen button without a product decision.
15. **The peek reading as a glitch.** A one-second nudge of the whole screen may read as a stutter, especially on the first launch while the user is reading the empty library. Hallway-test it; if it reads as a fault, lengthen the hold to 600 ms or delay it to the second library visit.
16. **`SettingsActivity` from App info.** Confirm on phones, including an OEM skin, that the App info gear appears, that Back returns to App info (empty task affinity, excluded from recents), and that a theme change there recreates a running `MainActivity` correctly.
17. **The shimmer on OLED:** banding of a 10 % band over the grain; whether one sweep reads at arm's length; that it never reads as a loading skeleton.
18. **The Ready tick** right after a Finish tick on short notes: confirm the 1.5 s guard feels right, and that a Ready tick while the phone is in a pocket never happens (on-screen only).
19. **Badge legibility** on the 40 % wave in sunlight, and at 2.0× font scale where the timer grows toward it. In the scene (28 dp), whether people who paused by hand, or by a call, read "paused" without the word (the timer has stopped and recedes; Resume is labelled).
20. **The resting instrument** (42 % profile, ink on paper) on a low-DPI device: the outer bars must not vanish to mush; and whether first-time users read the still bars as a live meter (no timer, no glow, no motion and no green dot should prevent it; hallway-test it).
21. **No caption for calls.** A user who returns to a recording paused by a call sees only the badge. The notification states the reason; if testing shows confusion ("why did it stop?"), the fallback is the reason in the notification's title, not a caption.

Renders and screenshots prove layout, colour and choreography only.

---

## 15. What was cut, and why

| Cut | Why |
|---|---|
| App name, wordmark, lockup masthead or launcher tile in the library header | Decision O1: it is not a standard UI move and it is decoration competing with the notes. The brand lives in the instrument's material (scene and stage) and the Settings footer; the stage never shows the name or the logo, and the empty library's resting bars are the instrument at rest (42 % of the profile), never the mark. |
| Any on-screen settings control | X1: settings is the page beside the library, one swipe away, with a peek, a TalkBack action, the App info gear and Ctrl+, as equivalents; the library is content above and two controls below |
| "Note ready" on the stage, and a self-drawing check | X6: the pill's label becoming "Copy", its white fill, one shimmer and one tick are the whole moment |
| A readiness sound, and a setting for it | Meetings, calls and quiet rooms; a chime would land in the next recording; nothing to configure. The notification channel keeps its own sound for the background case |
| "Everything so far is saved." and the scene's auto-pause notice with its 58 dp text shift | W5: the scene's text never shifts |
| Captions for call and another-app pauses ("Paused for a call") | X5: the badge says paused; the reason is in the notification and spoken by TalkBack. Only "Storage is full" is written, because Resume cannot work until space is freed |
| The word "Paused" (stage and scene), the scene's ‖ state-line glyph and the ○ ring | X5: the badge on the dimmed wave is the one paused mark. ‖ means pause everywhere |
| The stage's dateline line and its writing head | W6: the time sits on the title's line; one footer row, length · pill, in every state |
| A tall dark card as the empty state | X4: the paper composition is calmer, teaches the one action, and lets the material appear only when the microphone opens |
| The full nine-bar mark profile as the empty state | It is the logo: a splash screen. The instrument rests at 42 % (F2) |
| Starting a recording by dragging the stage | X4: Record is enough. A drag on the stage only expands a recording that is already running, so no reflex flick can open the microphone |
| 26 sp editorial day heads | Decision O2: they shout. Day labels are 14 sp `section`. |
| The pinned top search bar, and a top search icon | Search is the dock's circle, in the thumb zone (O3, O7). |
| The 16–396 black snackbar | Replaced by the width-matched tonal feedback pill (O6). |
| Find bar at the top of the screen; a connected button group with a replace toggle | Rows ride the keyboard on the 24–388 grid; replace is always visible and labelled with its count (O7, R2). |
| "Finish here finishes in place" (a Finish that stays in the library) | Finish always opens the note (V7); the library's Pause and Finish are the scene's own controls (V8). |
| Resume as a bare ● circle | It read as Record; Resume is a labelled pill (R1). |
| Finish moving horizontally on pause | Its right edge is fixed (R1). |
| A white recording page (timer at top-left, 120 dp hero then writing head inside the text) | Replaced by the centred instrument on the logo material (O5). The writing head remains only for the Transcribing page. |
| Centred live captions; the M3-style grey capsule around the dock; the "‖‖‖ 4:16" lockup row | Concepts not chosen: centred text must realign on Finish; the capsule copies the M3 floating toolbar; the lockup crowds the bottom. |
| Any accent colour, including a red record dot | Breaks the monochrome palette and duplicates the system's green privacy indicator. |
| Blur on text, blur on scroll, `Modifier.blur` | Text is never blurred (1); GPU cost on a lazy list; reads as a rendering bug. Blur exists only on the wave's outer bars (and the scene glow). |
| Gradients, grain or glass on paper surfaces | Paper stays flat and tonal. The material belongs to the instrument alone (the scene and the stage). |
| Skeleton or "pending" lines | They invent text that does not exist yet; the working writing head says "still writing" honestly. |
| Double-tap to edit; single tap that edits in text-only notes | There are no double-tap gestures (2.8.3); it adds 300 ms to every tap-to-play. |
| Keep/Edit popover on doubtful words; review mode; counters | Doubtful words get the underline only. |
| Copy on the "Note ready" notification; copy-when-ready arming | A background clipboard write and a hidden state. |
| Capture problems ending the recording | Capture problems pause automatically, with a reason (5.3.5). |
| Waveform scrubber, separate timeline row, "charging" Copy pill | Over-animation and duplicate progress. |
| Google Sans | Reads as a Google app; Reserved Font Name risk. Inter is the lockup's face. |
| Haptics beyond record start, Finish, Ready and Copy | Four moments only (2.9), Ready among them (X6). Nothing else ticks: not the settings swipe, not the peek. |
| Confirmation dialogs (Delete, Remove audio, discard edit) | Deferred actions with Undo, and autosave. The only modal surface is the rationale sheet. |
| Timestamp gutter, language labels, engine names, "Interrupted", "Unclear speech" | Whisper-era machine facts, not user facts. |
| Speed control, ±10 s skips, a permanent player | Out of scope. Tapping a word is the seek. |
| Share next to Copy | One primary action; Share is in the menu. |
| Onboarding, a "Using Focal" page, a model or language picker | The empty library's two lines, a three-recording hint and the settings peek (twice at most); no language control exists. |
| Swipe to delete | Too easy to trigger while scrolling long previews. |
| Widget, Quick Settings tile, dependence on Live Updates | Out of scope; the static shortcut is the one extra entry point. |
| Bottom navigation, FAB, row cards, row dividers | Whitespace and type do the grouping. The stage is the one card, and it is the instrument, not a container style. |
| Dynamic colour; bouncy expressive springs; looping pulses other than the working sweep | Monochrome is the identity; motion stays calm. |
| The live row and the live pill "● 4:16 · Recording" | The stage is the live surface and the dock carries Pause \| Finish (V8). |
| The visible "Recording" label (scene state line, live row, stage) | V4: the live wave and the running timer are the indicator. "Paused" is not written either (X5); only "Storage is full" is. |
| "Today" (or any day) label above the stage | V5: the stage carries its own time (W6); day labels start in the list. |
| Swipe **down** to collapse the scene; the down chevron | The scene folds upward into the stage (V3): swipe up, ⌃. |
| Bloom from the Record pill; fold into the live pill; the Dark-theme pill tint | The scene always grows out of, and folds into, the stage: same material, no tint. |
| The nine-bar empty-state illustration (the full mark, centred on paper); the faint loading mark | The empty library shows the instrument at rest instead (X4), at 42 % of the profile, still, in ink on paper; loading shows nothing (S5). |
| Finish settling into the stage, and a 600 ms Record re-arm | V7 keeps Finish → note; the settle story plays on Back (S1). |
| The white progress fill on the stage | It read as an enabled button and clipped its label (S3). Glass + 2 dp track. |
| m:ss lengths on the stage ("12:04") | Misread as a time of day beside "16:05" (S2). |
| A "Recording" word or dot in the library dock | The controls themselves (Pause \| Finish) are the state. |
| Haptics on expand, collapse, the settings swipe, the peek or any threshold crossing | Four moments only (2.9). |
