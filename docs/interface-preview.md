# Interface preview

Native Android captures of the monochrome interface, using synthetic sample notes
on an Android 10 emulator at approximately 390 × 846 dp. The recording capture uses a synthetic
session state to exercise layout; it does not demonstrate microphone capture or
recognition quality. These screens describe the working tree, not the existing
0.1 alpha download.

| Sessions · light | Sessions · dark |
| --- | --- |
| ![Notes in light theme](screenshots/library-light.png) | ![Notes in dark theme](screenshots/library-dark.png) |

| Recording controls | Processing status |
| --- | --- |
| ![Recording layout fixture](screenshots/recording-layout.png) | ![Processing remains visible below a long transcript](screenshots/processing.png) |

| Note · light | Note · dark |
| --- | --- |
| ![Note in light theme](screenshots/note-light.png) | ![Note in dark theme](screenshots/note-dark.png) |

[Vector logo](mark.svg) · [Logo preview](mark-preview.png)

[Approved brand sources and rebuild instructions](../design/brand/README.md)

The owner-private [mobile gallery](https://focal-design-review.andreizakharov.chatgpt.site/native/)
shows these same unretouched captures with light/dark switching.

The selected Sessions direction uses a white canvas, black primary actions and
a dated timeline. Recording uses an open timer and a Focus wave amplitude envelope
driven by the actual microphone level, with brief smoothing and a settled paused
state. This is a live level meter, not a stored audio waveform;
transcript passages are timed text with review cues. The app name and redundant
helper copy are absent from the header and recording footer. The approved Focus
wave launcher retains selective blur, its central highlight and subtle material.
Themed, notification and compact UI marks use crisp geometry; the themed/UI
variants retain the opacity falloff. Source assets and outlined wordmarks are
versioned with a reproducible generator.

During transcription, a small wave gently shifts emphasis between two adjacent
strokes while the centre remains stable. One status sits above the audio/copy dock
and stays visible when the transcript scrolls. It is indeterminate: no invented
completion percentage. The animation stops when processing ends or the UI leaves
STARTED, and becomes static when system animations are disabled. No delayed launch
animation, idle decorative loop or runtime Gaussian blur was added. The settings
footer uses the same mark beside the version.

The record action stays at the bottom with an explicit speech-language selector.
Search covers titles and every passage, including Unicode text; the review filter includes interrupted notes.
Notes expose original-audio playback, editable passages and a persistent copy
button. The editor preserves its draft across recreation, confirms discarding
changes, survives same-session notification navigation, and waits for a successful
save before advancing. Original-audio playback supports seeking and pause/resume.
Light, dark and system
appearance use the same spacing and component hierarchy.

Search input is dismissed when opening a note or another screen. Saving or closing
the passage editor also dismisses its keyboard.

The speech changes and their limits are described in [architecture](architecture.md).
Quality across languages needs the [corpus and phone checks](device-validation.md);
UI screenshots and emulator tests do not establish recognition accuracy or latency.

To reproduce captures on a clean emulator, build/install both debug APKs and run:

```sh
adb shell am instrument -w -r -e screenshots true -e class app.focal.InterfaceTest app.focal.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/app.focal/files/screenshots build/ui-captures
```

Screenshots are opt-in and use the test fixtures in `InterfaceTest`. Do not run
capture mode against an installation containing personal notes.

## Working-tree verification · 2026-11-02

The required core/lint/debug build checks pass, along with release lint and R8
compilation. The Android checks cover all seven language selections, 200% font
scale at 320 × 560 dp, activity recreation, notification navigation, original-audio
playback, exact Russian copy, and failed SQLite writes without losing the draft.

See the [quality report](quality-report.md) for counts, independent review and the
remaining real-model/device gates. Screenshots are direct, unretouched Android
captures. They do not establish speech accuracy, phone latency or release readiness.
