# Architecture

Focal is a single Android application and a small pure-Kotlin `core` module.
The Android UI is Jetpack Compose. SQLite stores notes, audio ranges, and editable
passages. A pinned whisper.cpp submodule provides CPU inference through JNI.

## Capture and persistence

`RecordingService` is a microphone foreground service, started from the visible
Activity after permission. It owns `AudioRecord` and a partial wake lock. Closing
the Activity or removing its task does not intentionally stop the service.
Notification actions pause/resume and finish recording.

`SessionOperations` reserves process-wide ownership before service startup or
asynchronous deletion. Capture stops registered players before opening the
microphone; playback stays excluded until the microphone and source file close.
Recovery, remove-audio and deletion cannot own the same session concurrently.
Repeated starts are idempotent, conflicting operations report that the session is
busy, and ownership remains held through joined service teardown. Player callbacks
run outside the ownership lock. These are in-process guards, not cross-process
database locks.

Capture uses mono 16 kHz, signed 16-bit PCM. It appends the complete unfiltered
original to private storage with a bounded buffer and syncs at least once per
second. This costs about **115 MB per recorded hour**; paused time is excluded. There
are no duplicate chunk audio files. Users can remove audio while retaining text.
PCM is a deliberate first-version tradeoff: low capture overhead, simple recovery,
and exact seeking. Compression is a future optimization, not implemented behavior.

An energy gate finds candidate speech ranges with 500 ms pre-roll, 600 ms trailing
silence, and a 16 s maximum chunk. After eight seconds it can close a phrase at a
240 ms breath, reducing hard cuts through words. It is a conservative signal-energy heuristic,
not a trained VAD. Very quiet speech and noisy rooms need device evaluation.
The original recording remains available even when a range is missed.

Chunks refer to offsets in the original file. Before a chunk becomes `PENDING`,
its audio is synced. Each completed chunk and its passages are committed in one
SQLite transaction. Completion is idempotent. SQLite uses WAL and foreign keys.
Capture does not await model setup or SQLite writes. An ordered worker persists
at most 64 queued scalar metadata events; it never queues PCM. If storage cannot
keep up, recording stops visibly and retains the original for recovery. The
microphone closes before final metadata draining. The durable inference queue can
grow on disk without growing an in-memory audio queue. Finishing publishes its
terminal state only after the final audio range and metadata are settled.

After process death, the next launch marks unfinished notes as interrupted and
seals any recoverable writing range. Recovery syncs source audio before publishing
it and fills missing ranges without replacing committed passages or corrections.
Recovered ranges are bounded to 30 seconds. The user can continue transcription. Focal
does **not** silently reopen the microphone or restart a recording at boot.
Continuing transcription also seals recoverable writing ranges and durable audio
left by a failed live finalization before reading the pending queue.
Force-stop, reboot, revoked permission, microphone preemption, storage exhaustion,
and OEM process termination can stop capture. A crash can lose roughly the last
second that has not been synced; no promise of lossless recovery is made. A durable
deletion journal retries failed audio removal after restart. Database v1 migrates
additively to v2, retaining notes and edits.

New passage edits and editable drafts share a 128 KiB UTF-8 limit. Oversized input
is rejected visibly while preserving the preceding valid draft and saved text.
Library previews are bounded; ordinary passages use direct cursor reads, while
legacy oversized text uses 64 KiB SQL byte blocks with streaming UTF-8 decoding.
Unicode search retains matches across block boundaries. Opening a legacy passage
is limited to 16 MiB; exceeding that limit reports an error without deleting or
rewriting the saved source.

## Inference

The intended bundled model is multilingual Whisper base Q5_1, approximately 57 MB.
It includes Russian and the six other languages exposed in settings. No model
download or network permission is needed by a fully bundled APK.

Every `NativeWhisper.open` validates the complete supported multilingual base
structure before entering the private JNI loader: architecture, vocabulary and
token lengths, tensor names, shapes, types and payload bounds. Import performs
this preflight before replacing the installed model; failure or cancellation keeps
the previous model. A host experiment with another architecture does not make it
loadable by the Android app.

Inference uses deterministic beam search (three candidates), CPU-only, with up to
four threads while reserving a logical processor on smaller CPUs. Power-save mode
selects one thread; moderate thermal pressure caps work at two. Severe thermal
pressure pauses inference while capture continues. There is no artificial delay
between results on a healthy device. Under battery saving or moderate thermal
pressure, queued work yields for a quarter of the decode time, capped at three
seconds. These are resource limits, not measured guarantees about temperature,
real-time speed, or battery life on particular phones. Beam search itself costs
more than greedy search; end-to-end speed and accuracy require corpus/device tests.
The speech worker uses normal scheduling priority while the capture worker retains
audio priority; inference is no longer explicitly assigned background CPU priority.

The decoder uses passage timestamps without word alignment. A contiguous tail of
trusted previous passages supplies at most 240 characters / 64 tokens of context;
it is cleared across long silence and never carries across notes. Queue reads and
context reads use bounded SQL queries. Decoder failures leave microphone capture
running and pending audio on disk; finishing the note then offers transcription
recovery. Completion checks read capture's finished flag before querying the queue
so a final chunk published during polling cannot be skipped.

Long speech ranges overlap by 500 ms. Matching text is removed only when its
segment timestamps fall wholly within already decoded audio. A passage spanning
the boundary cannot be split reliably without word alignment: possible repetition
remains visible with a review cue. Missing new-audio output receives a reviewable
empty passage. Native handles retain shared ownership through cancellation and
close; cancelled partial output cannot mark a chunk complete. Per-segment token probabilities and the
decoder's no-speech estimate produce review cues. The pinned engine's no-speech
value is currently under investigation because its initial logits row is not
requested; do not use it as a calibrated rejection rule. These are not calibrated error
probabilities; confident errors and omissions can remain unmarked. Overlap
comparison retains at most twelve word references on each side and refuses to use
a token longer than 4,096 characters as evidence for deleting words. A NUL in a
manual correction stops prompt carryover without changing that correction.

The player seeks in the original PCM and reports the actual AudioTrack playback
head. Pausing preserves position, navigation stops the previous session, and audio
focus loss or an audio-becoming-noisy broadcast pauses playback. Receivers and
playback ownership are released on pause, replacement, completion and stop;
generation checks reject stale callbacks. Delayed deletion or audio removal for
one note cannot stop a newer selection's player.

Qwen/Gemma rewriting is intentionally absent. A second large model would add RAM,
latency, heat, and a risk of changing the speaker's meaning before the base capture
and recognition path has been measured on phones.
Bounded acoustic and local-correction candidates are documented in
[speech-quality experiments](stt-quality-experiments.md); they remain disabled
until accuracy and phone-cost evidence support enabling them.

## Review and navigation

The approved Sessions layout and Focus wave identity remain the product baseline.
Search, the review filter and library scroll state survive session navigation and
activity recreation. Opening Search brings its field into view and requests
keyboard focus once; returning from a session retains the browsing context.

Bounded editable drafts survive activity recreation and same-session notification
navigation; failed saves remain open. Save & next keeps the existing sheet state
and reads current dirty/saving values when deciding whether dismissal is allowed.
Copy and share become available after the displayed transcript includes a saved
edit. Transfers have a conservative 256 KiB budget for both UTF-8 and UTF-16;
oversized or failed transfers show guidance without truncating text or replacing
the clipboard with a partial transcript.

Legacy passages above the edit limit show a bounded preview and an explicit
Open full passage action. Their read-only sheet exposes selectable pages of
approximately 8,192 UTF-16 units, preserving surrogate pairs and CRLF boundaries.
Only the page index and scroll position enter saved instance state; the full
source remains in the store. These passages cannot be overwritten through the
bounded editor. Original-audio playback remains available when source audio exists
and capture is inactive.

The [local emulator QA runner](local-emulator-qa.md) verifies model and APK
identity and requires complete instrumentation results. Its checks and host replay
tools do not establish physical-device behavior; see
[device validation](device-validation.md) for those remaining measurements.

## Privacy

No Internet permission, backend, accounts, telemetry, or analytics. Android backup
and device-transfer rules exclude the app's data. Notes stay in app-private files
and SQLite. User-initiated copy and share hand text to Android or the chosen app.
Uninstalling Focal deletes its private notes; export text first.

Model downloads happen on the build machine over verified TLS. The downloader
verifies the upstream LFS SHA-256 before using a file, resolves an immutable
revision, and bundles provenance. The native engine uses the baseline ARM64
instruction set; the NDK and linker provide 16 KB page alignment.
