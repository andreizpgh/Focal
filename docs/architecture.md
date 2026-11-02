# Architecture

Focal is a single Android application and a small pure-Kotlin `core` module.
The Android UI is Jetpack Compose. SQLite stores notes, audio ranges, and editable
passages. A pinned whisper.cpp submodule provides CPU inference through JNI.

## Capture and persistence

`RecordingService` is a microphone foreground service, started from the visible
Activity after permission. It owns `AudioRecord` and a partial wake lock. Closing
the Activity or removing its task does not intentionally stop the service.
Notification actions pause/resume and finish recording.

Capture uses mono 16 kHz, signed 16-bit PCM. It appends the complete unfiltered
original to private storage with a bounded buffer and syncs at least once per
second. This costs about **115 MB per recorded hour**, including pauses. There
are no duplicate chunk audio files. Users can remove audio while retaining text.
PCM is a deliberate first-version tradeoff: low capture overhead, simple recovery,
and exact seeking. Compression is a future optimization, not implemented behavior.

An energy gate finds candidate speech ranges with 500 ms pre-roll, 900 ms trailing
silence, and a 24 s maximum chunk. It is a conservative signal-energy heuristic,
not a trained VAD. Very quiet speech and noisy rooms need device evaluation.
The original recording remains available even when a range is missed.

Chunks refer to offsets in the original file. Before a chunk becomes `PENDING`,
its audio is synced. Each completed chunk and its passages are committed in one
SQLite transaction. Completion is idempotent. SQLite uses WAL and foreign keys.
There is one capture thread and one inference thread; the durable queue can grow
on disk without growing the in-memory queue.

After process death, the next launch marks unfinished notes as interrupted and
seals any recoverable writing range. The user can continue transcription. Focal
does **not** silently reopen the microphone or restart a recording at boot.
Force-stop, reboot, revoked permission, microphone preemption, storage exhaustion,
and OEM process termination can stop capture. A crash can lose roughly the last
second that has not been synced; no promise of lossless recovery is made.

## Inference

The intended bundled model is multilingual Whisper base Q5_1, approximately 57 MB.
It includes Russian and the six other languages exposed in settings. No model
download or network permission is needed by a fully bundled APK.

Inference is greedy, CPU-only, with at most two threads. Power-save mode or moderate
thermal pressure selects one thread. Severe thermal pressure pauses inference
while capture continues. After a decode, the worker yields for half the decode
time, capped at ten seconds. These are resource limits, not measured guarantees
about temperature, real-time speed, or battery life on particular phones.

Long speech ranges overlap by 500 ms. Exact multiword prefix overlap is removed
only at these deliberate boundaries. Per-segment token probabilities and the
decoder's no-speech estimate produce review cues. These are not calibrated error
probabilities; confident errors and omissions can remain unmarked. The UI always
allows editing and listening to any passage or the whole original recording.

Qwen/Gemma rewriting is intentionally absent. A second large model would add RAM,
latency, heat, and a risk of changing the speaker's meaning before the base capture
and recognition path has been measured on phones.

## Privacy

No Internet permission, backend, accounts, telemetry, or analytics. Android backup
and device-transfer rules exclude the app's data. Notes stay in app-private files
and SQLite. User-initiated copy and share hand text to Android or the chosen app.
Uninstalling Focal deletes its private notes; export text first.

Model downloads happen on the build machine over verified TLS. The downloader
verifies the upstream LFS SHA-256 before using a file, resolves an immutable
revision, and bundles provenance. The native engine uses the baseline ARM64
instruction set; the NDK and linker provide 16 KB page alignment.
