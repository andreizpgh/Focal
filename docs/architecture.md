# Architecture

Focal is one Android application module (`app`) and one pure-Kotlin module
(`core`). The app records 16 kHz audio, cuts it into windows at pauses, decodes
each window with Parakeet Ultra through a small JNI bridge to sherpa-onnx, and
stores raw recognizer output beside an editable, formatted transcript. The
interface is Jetpack Compose.

## Modules

| Area | Where | What it holds |
| --- | --- | --- |
| `core` | `core/src/main/kotlin/app/focal/core` | Segmenter and window gate, word building and window merge, formatter (seams, paragraphs, title, doubtful words), alignment of edited text, meter math, thread policy and every tuning constant (`SpeechTuning`). No Android dependencies; unit-tested with shared test vectors. |
| Recording | `app.focal.recording` | `RecordingService` (capture and the transcription queue), `WindowFeed` (synced PCM into the segmenter), `SessionOperations` (process-wide ownership), notifications and the live state the UI reads. |
| Speech | `app.focal.speech` | The JNI bridge (`SherpaBridge`, `OfflineRecognizer`, `SileroVad`), model installation (`UltraModel`, `SpeechModels`), `UltraEngine`/`UltraTranscriber`, the device thread policy and `SpeechPlayer`. |
| Data | `app.focal.data` | `NoteStore` (SQLite, schema 5), schema and migrations, paragraph edit rules, search folding, transcript JSON. |
| Interface | `app.focal.ui.*` | `state` (view models and the UI contract), `shell` (navigation, overlay, bottom column), `library`, `scene`, `note`, `settings`, `components`, `design` (tokens). |
| Native | `app/src/main/cpp` | `focal_speech.cpp`, the only native code, plus the vendored sherpa-onnx C API header and license. |

## Invariants

These hold across every change:

- **Capture never waits.** The capture thread does not wait for the model, SQLite
  or the interface. Inference runs on its own thread and only reads audio that is
  already on disk.
- **Audio is durable first.** A window is planned only over synced PCM and is
  persisted before it is decoded. Losing the process loses at most the last
  unsynced second of audio.
- **Raw output is immutable.** Recognizer text and tokens are stored once per
  window. The transcript the user edits is an overlay; the original stays
  restorable per paragraph.
- **Memory is bounded.** There is no in-memory audio queue. Capture metadata uses
  a bounded queue of 64 events, decoded audio is at most about 22 seconds per
  window, and the VAD's internal segment queue is cleared after every 512 samples.
- **No silent microphone.** A dead process never reopens the microphone. Recording
  starts only from a visible activity.
- **Offline.** The app has no Internet permission.

## Capture and persistence

`RecordingService` is a foreground service of type microphone. The activity is
only a controller: leaving the app or dismissing its task does not stop a
recording. A partial wake lock is held with a 35-minute timeout and renewed every
30 minutes while capture or decoding needs it.

Starting needs 200 MB of free storage. The service opens the microphone before it
creates the note, so a busy microphone never leaves an empty note behind. Capture
runs on the `focal-capture` thread at audio priority. It appends mono 16-bit PCM
to the note's file in app-private storage through a 16 KiB buffer and syncs at
least once per second. That is about 115 MB per recorded hour.

**Pause** stops `AudioRecord`, syncs the file and records a break at the exact
sample offset. The service keeps its microphone type while paused. Capture pauses
on its own, and says why, when a call takes the microphone, when Android or
another app silences it, or when free storage drops below 64 MB. Resume needs 128
MB free.

**Finish** closes the microphone, syncs, and switches the service to the
media-processing type (data sync before Android 15) to finish transcription. A
recording with no bytes or perfectly constant samples is discarded. If microphone
permission is revoked, the recording ends and the note says so.

`SessionOperations` holds one capture slot and per-note processing claims inside
the process, so recording, transcription, deletion and audio removal never act on
the same note at once.

## Segmentation

`WindowFeed` reads the synced file up to its durable length and feeds the core
`Segmenter`. Silero VAD v4 (through the bridge) decides speech for every
consecutive 512-sample window with sherpa-onnx's hysteresis: threshold 0.5, 1,600
samples of speech to start, 800 of silence to end. If the VAD cannot load, a
relative-energy detector takes over: a 20 ms frame is quiet when its energy is at
most 1% of the mean over the last 10 seconds.

The cut rule works on 10 ms slots:

- Once a window holds 6 seconds, it closes 250 ms into the first pause of at least
  500 ms.
- If no pause qualifies by 20 seconds, it is hard-cut at the center of the longest
  quiet run (at least 60 ms) between 6 and 20 seconds, or else at the lowest
  five-frame energy. Hard-cut neighbors overlap by 0.5 s on each side; a word
  belongs to the window that holds its midpoint, and a repeated word starting
  within 480 ms is dropped.
- A pause-cut window is decoded with 250 ms of pause audio before it and 1 second
  of right context after it. Words in the right context belong to the next window.
- Pause and Finish close the open window at the exact position.

A window that owns less than 200 ms of audio, or whose peak is below −60 dBFS, is
stored as silent and not decoded. When the VAD heard no speech in a window, a
result of up to two words is stored but hidden.

Every window plan is persisted, as the segmenter's checkpoint, before it is
decoded. After process death, segmentation resumes after the last persisted
window: the VAD is re-warmed on the 2 seconds before it (the energy detector on
its whole 10-second history) and recorded breaks are replayed.

The evidence for these numbers is in [speech quality](speech-quality.md#segmentation).

## Speech engine

### JNI bridge

`focal_speech.cpp` links only `libsherpa-onnx-c-api.so` and `libonnxruntime.so`
from the pinned sherpa-onnx 2.0.1 AAR. Gradle verifies the AAR's size and SHA-256
against `models/sherpa-onnx.json`, extracts the two libraries for CMake, and
checks the vendored C API header against its pinned hash. The AAR's Kotlin API is
not used: the C API also returns per-token log-probabilities, which doubtful
words need.

The bridge fixes the model type (`nemo_transducer`), decoding (`greedy_search`)
and provider (`cpu`) instead of taking them from the caller, because some invalid
values make sherpa-onnx exit the process. No C++ exception reaches the JVM; every
native object is released on every path; strings cross as UTF-8 bytes. A decode
returns text, tokens, timestamps, durations and log-probabilities.

### Recognizer lifecycle

One speech worker thread (`focal-speech`, default priority) owns the recognizer.
It loads the recognizer right after capture starts, so the first window does not
wait for it, and never blocks capture while loading.

- **Threads** are fixed when the recognizer is created: as many as the phone has
  performance cores (cores outside the slowest cluster), at most four, and at
  most two under battery saver or a thermal status of moderate or worse. Under
  that pressure the worker also pauses for a quarter of each decode's time
  (150 ms to 3 s) between windows.
- **Severe heat:** at a thermal status of severe or worse (Android 10 and later),
  the worker stops decoding and checks again every 3 seconds. Recording goes on;
  windows stay pending and the text catches up once the phone cools.
- **Memory arena:** ONNX Runtime's CPU arena is off on phones with 6 GB of RAM or
  less, through a session config file in no-backup storage.
- **Failures:** if the model cannot load, recording continues and windows stay
  pending. Each decode counts an attempt before it starts; a window that fails
  twice is marked failed, and two consecutive failing windows drop the
  recognizer. After two loads that never finished, automatic loading stops until
  the user retries.

### Model install

The APK carries the five model files: Parakeet Ultra's encoder, decoder, joiner
and tokens, and Silero VAD. The four ONNX files are stored uncompressed so they
can be streamed out of the APK. On first launch, and again whenever an
activity resumes until it succeeds, `SpeechModels` copies them in the background to
`noBackupFilesDir/parakeet-ultra-v1-<digest>`:

- The copy needs the model's size plus 200 MB free.
- Files are written to a staging directory, hashed while copying, synced, and
  renamed into place atomically; then a marker listing every file's name, size
  and SHA-256 is written.
- Later sessions check the marker and file sizes without re-hashing. An app
  update with the same model reuses the copy.
- A hash mismatch while copying means the APK is damaged; the app asks for a
  reinstall instead of retrying.

Recording works before the copy finishes; transcription catches up afterwards.
Release builds refuse to package a missing or mismatched model. Debug builds
without the model record but cannot transcribe.

## Storage

`NoteStore` uses SQLite (WAL, foreign keys on). Schema 5:

| Table | Holds |
| --- | --- |
| `notes` | One recording: title (empty = derived), custom-title flag, state, duration, pause and end reasons, audio-present flag, legacy flag, trash time. |
| `windows` | One segmentation window: index, own, audio and keep ranges, how it ended, speech and peak measures, state (pending, done, silent, failed), attempts, engine, raw text and token JSON (text, start, duration, log-probability). Raw output is never changed. |
| `paragraphs` | The editable body: order, time range, current text, `source_text` (the recognizer's text; NULL when unknown), words JSON with times and confidence, edited, hidden and open flags, revision. |
| `gaps` | Ranges of migrated notes that no window covers yet. |
| `breaks` | Pause positions in samples. |
| `paragraph_fts`, `title_fts` | FTS4 indexes (unicode61 tokenizer, diacritics and `ё` folded) over views of paragraph text and titles. |
| `pending_audio_deletions` | A journal that retries audio file deletion after a restart. |

Every paragraph and note row has exactly one search entry, updated in the same
transaction as the text. Edits apply only to finished notes and check the
paragraph's revision, so the transcription worker and the editor never race.
Delete moves a note to the trash for Undo; trashed notes are purged at the next
start. A note that is being recorded or transcribed cannot be deleted.

**Migration from schemas 1–4** keeps every note. Old passages become paragraphs
one to one with their ids, text and edits; their original text becomes
`source_text` (unknown stays unknown); background-sound exclusions become hidden
paragraphs. Migrated notes have no word times, so playback starts at the
paragraph. Unfinished notes get gaps for every range no completed chunk covers,
and only those ranges are transcribed. A note whose audio file is gone becomes
text only. Installing over Alpha 9 with existing notes was checked on API 35 and
API 36 emulators.

## Transcription queue and recovery

One sequential queue runs per process, in this order: the live recording, the
recording that just finished, notes the user asked to retry, and notes waiting
for transcription. A new recording preempts the queue; the preempted note waits.

Each window is decoded and then committed in one transaction: raw output, merged
words, the open paragraph and any new ones, the derived title and the search
index. A note becomes ready only when no window is pending, no gap remains and no
saved audio lies past the last window.

- **Android's background limit.** When Android 15 or later ends the processing
  service (`onTimeout`), the in-flight window stays pending, the note waits, and a
  notification says "Transcription paused · Open Focal to continue."
- **Process death.** At the next start, notes that were recording or transcribing
  become waiting; a recording that was cut off says when it stopped. When an
  activity next resumes, the waiting notes continue automatically. The microphone
  is never reopened.

### Notifications and entry points

Four channels: recording, paused alerts, transcription progress and finished
notes. The recording notification has a chronometer, Pause or Resume, and Finish;
automatic pauses say why. Transcription shows determinate progress. "Note ready"
is posted unless that note is on screen, and is grouped with a summary. On Android
16 QPR2 and later the recording notification requests promotion as a Live Update
(not yet verified). Lock-screen versions show no note text.

The static "New recording" shortcut opens a non-exported trampoline activity,
which hands the request to the main activity in memory. No exported intent can
make Focal record. Settings are also reachable from App info through
`APPLICATION_PREFERENCES`.

## Formatting

Formatting is deterministic and keeps the recognized words: a seam repair that
would change any word's letters or digits is dropped.

- **Words** come from tokens: a word starts at a token with a leading space;
  closing punctuation attaches to the word before it and never sets the word's end
  time. `<unk>` tokens are dropped (inside a word they mark it doubtful).
- **Seams.** At a window seam with a pause under 0.8 s, a period before a lowercase
  continuation is removed, unless it ends an abbreviation, initial or number. Case
  is never changed.
- **Paragraphs** close only at sentence ends: after a pause of at least 3.5 s,
  after 2.0 s once the paragraph holds two sentences, or once it holds 80 words.
  Unpunctuated speech closes at the longest recent pause at 160 words. While a
  note transcribes, its last paragraph stays open and grows; `TranscriptAssembler`
  produces the same paragraphs incrementally as the batch formatter.
- **Title**: the first sentence (extended if shorter than 12 characters), clipped
  at a word boundary to 48 characters. A renamed title is never replaced.
- **Doubtful words**: a word whose least confident letter-bearing token is below
  probability 0.5 gets a dotted underline. Editing the word clears it. These
  thresholds are provisional ([evidence](speech-quality.md#doubtful-words)).
- **Copy** joins the visible paragraphs with a blank line: no title, no marks.
  Copy and Share refuse a note longer than 200,000 characters, which could
  overflow Android's limit for passing data between apps.

When a paragraph is edited, `Alignment` maps the new text to the recognizer's
words, so unchanged words keep their times. A tap on a word plays from 0.5 s
before it, from the nearest earlier timed word, or from the paragraph's start.
Times are never invented.

## Playback

`SpeechPlayer` streams the note's PCM file through `AudioTrack` and reports the
actual playback head. Losing audio focus or unplugging headphones pauses it. The
player refuses to start while a recording is live, and leaving a note stops it.

## Interface

`MainActivity` hosts the whole app. `SettingsActivity` serves App info's settings
link; `ShortcutActivity` is the shortcut's trampoline.

- **State contract.** `app.focal.ui.state` has one view model per surface:
  `LibraryViewModel`, `RecordingViewModel`, `NoteViewModel` (per note),
  `PlaybackViewModel` (one per activity, owns the `AudioTrack`) and
  `SettingsViewModel`. Their states are immutable and contain no strings; the UI
  maps them to `strings.xml`. Fast values, such as the microphone level and the
  playback position, are read in draw and frame callbacks, never in composition.
- **Navigation.** A Navigation 3 back stack of Library, Note (optionally opened at
  a search hit) and Licenses. It survives process death and is always rooted at
  the library. Predictive back is supported throughout.
- **Settings** is not a destination: it is a second page beside the library,
  opened by a horizontal swipe, a keyboard shortcut or an accessibility action.
- **Recording scene.** An overlay above the pages, driven by one expansion value
  from 0 to 1. The library's stage card grows into the full-screen scene and folds
  back; the pages scale and dim behind it and ignore input while it is open.
- **Bottom column.** The dock, feedback pill, player strip and keyboard-riding rows
  sit above both the pages and the overlay, so the dock never moves while the
  scene grows behind it.
- **Design tokens** live in `app.focal.ui.design`; see [design](design.md).

A hand-written baseline profile for Focal's own code ships with the APK.

## Privacy

- No Internet permission, accounts, analytics or crash reporting.
- Backup and device transfer exclude all app data; the model copy lives in
  no-backup storage.
- Notes, audio and the database stay in app-private storage. Copy and Share hand
  text to Android only when the user asks; copied text is marked sensitive, so
  Android 13 and later hide it in the clipboard preview.
- Uninstalling Focal deletes every note. Copy important text out first.
