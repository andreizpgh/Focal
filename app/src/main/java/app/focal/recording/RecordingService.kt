package app.focal.recording

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.focal.BuildConfig
import app.focal.FocalApplication
import app.focal.core.DecodePolicy
import app.focal.core.LevelMeter
import app.focal.core.MeterMath
import app.focal.core.SpeechDetector
import app.focal.data.CaptureReason
import app.focal.data.NoteState
import app.focal.data.NoteStore
import app.focal.data.StoredWindow
import app.focal.speech.DeviceSpeechPolicy
import app.focal.speech.MODEL_MISSING
import app.focal.speech.ModelStatus
import app.focal.speech.SileroVad
import app.focal.speech.UltraEngine
import app.focal.speech.UltraModel
import app.focal.speech.UltraTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Small device boundaries also allow deterministic capture-durability tests without speech models. */
internal interface RecordingInput : AutoCloseable {
    val initialized: Boolean
    val silenced: Boolean
    fun start()
    fun stop()
    fun read(samples: ShortArray, offset: Int, count: Int): Int
}

internal interface RecordingDecoder : AutoCloseable {
    /** Loads the recognizer ahead of the first window, with [threads] fixed for its lifetime. */
    fun prepare(threads: Int) {}

    /** Decodes one persisted window and commits it with [NoteStore.commitDecoded]. */
    suspend fun transcribe(store: NoteStore, window: StoredWindow, threads: Int)
    fun cancel()
}

/**
 * Owns capture and transcription. The activity is only a controller.
 *
 * Capture (one microphone session at a time) writes 16 kHz PCM to the note's file on its own thread at
 * audio priority, syncs it every second and never waits for SQLite, the model or the UI. Pause, an
 * automatic pause (a call or another app silenced the microphone, storage nearly full) and Resume keep the
 * microphone foreground-service type; each pause is recorded as a break where segmentation closes the
 * open window.
 *
 * One speech worker transcribes sequentially: the live recording first (windows planned by Silero VAD over
 * the synced audio, each persisted before it is decoded), then the recording that just finished, notes the
 * user asked to retry, and, when started from the visible app, every note waiting for transcription. A new
 * recording preempts the queue; the preempted note waits. After Finish the service switches to the
 * media-processing (dataSync before Android 15) type, drains the rest and posts "Note ready".
 */
class RecordingService : Service() {
    private val app get() = application as FocalApplication
    private val store get() = app.store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); task.run() }, "focal-capture")
    }.asCoroutineDispatcher()
    private val speechDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT); task.run() }, "focal-speech")
    }.asCoroutineDispatcher()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var notifications: ServiceNotifications
    private val notificationLock = Any()
    private val workLock = Any()
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private var wakeLock: PowerManager.WakeLock? = null
    /** When the wake lock was last acquired (elapsed realtime); guarded by the service monitor. */
    private var wakeAcquiredAt = 0L
    private val captureAwake = AtomicBoolean(false)
    private val decoderAwake = AtomicBoolean(false)
    @Volatile private var destroyed = false
    /** The latest start id (main thread): stopping with it never cancels a newer queued command. */
    private var lastStartId = 0

    // Guarded by workLock.
    @Volatile private var capture: Capture? = null
    private val finishing = ArrayDeque<String>()
    private val requested = LinkedHashSet<String>()
    private val owned = HashMap<String, SessionOperations.SessionClaim>()
    private var drainWaiting = false
    /** DRAIN arrived since the worker last looked for waiting notes. */
    private var drainRequested = false
    /** RECOVER commands accepted but not yet in [requested] (their failed windows are being reset). */
    private var pendingRequests = 0
    private var worker: Job? = null

    // Speech worker state.
    @Volatile private var transcriber: RecordingDecoder? = null
    @Volatile private var processingNote: String? = null
    @Volatile private var processingTitle = ""
    @Volatile private var engineRetry = false
    private val skipped = HashSet<String>()

    override fun onCreate() {
        super.onCreate()
        notifications = ServiceNotifications(this).also { it.createChannels() }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Focal:recording").apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            START -> startCapture(intent.getStringExtra(OPERATION), startId)
            RECOVER -> {
                val id = intent.getStringExtra(NOTE)
                if (id.isNullOrBlank()) {
                    // A malformed raw intent: answer the foreground start, then stop unless other work runs.
                    runCatching { restoreForeground(preferProcessing = true) }
                    SessionOperations.releasePending(intent.getStringExtra(OPERATION))
                    stopIfIdle(startId)
                } else startProcessing(id, intent.getStringExtra(OPERATION), intent.getBooleanExtra(RETRY_FAILED, false), startId)
            }
            DRAIN -> startProcessing(null, null, false, startId)
            PAUSE -> capture?.requestPause() ?: stopIfIdle(startId)
            RESUME -> capture?.requestResume() ?: stopIfIdle(startId)
            STOP -> capture?.requestStop() ?: stopIfIdle(startId)
            else -> stopIfIdle(startId)
        }
        // Never silently reopen a microphone after process death.
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ commands

    private fun startCapture(token: String?, startId: Int) {
        // Every foreground-service start must be promoted before it can be rejected.
        try { promote(microphoneType(), capture?.let { recordingNotification(it) } ?: notifications.starting()) }
        catch (error: RuntimeException) {
            // The microphone type can be refused (permission removed, or a start from the background).
            RecordingState.events.tryEmit(ServiceEvent.StartFailed(
                if (error is SecurityException) StartFailure.PERMISSION else StartFailure.OTHER,
                if (error is SecurityException) "Allow microphone access and start recording while Focal is open."
                else "Recording could not start. Open Focal and try again."))
            if (capture == null) RecordingState.live.update { if (it.noteId == null) it.copy(message = "Recording could not start.") else it }
            // Processing promotion opens no microphone and disarms the foreground-start deadline.
            runCatching { promote(processingType(), notifications.transcribing(processingNote, "", null, false)) }
            SessionOperations.releasePending(token)
            stopIfIdle(startId); return
        }
        val claim = try { SessionOperations.acceptSession(token, capture = true, noteId = null) } catch (_: Exception) { null }
        if (claim == null) {
            // A second Record while recording is idempotent; anything else reports that capture is busy.
            if (capture == null) RecordingState.live.update {
                if (it.noteId == null) it.copy(message = "This session is busy. Try again when its current operation finishes.") else it
            }
            restoreForeground()
            stopIfIdle(startId); return
        }
        val session = Capture(claim)
        synchronized(workLock) { capture = session }
        // A finishing recording may have promoted transcription since the promotion above: the microphone
        // type and notification of this capture win now that it is current.
        promoteForCurrentWork()
        session.job = scope.launch(captureDispatcher) { runCapture(session) }
        ensureWorker()
    }

    private fun startProcessing(noteId: String?, token: String?, retryFailed: Boolean, startId: Int) {
        try { restoreForeground(preferProcessing = true) }
        catch (_: RuntimeException) {
            SessionOperations.releasePending(token)
            stopIfIdle(startId); return
        }
        if (noteId != null) {
            val claim = try { SessionOperations.acceptSession(token, capture = false, noteId = noteId) } catch (_: Exception) { null }
            if (claim == null) {
                if (!SessionOperations.matchesSession(capture = false, noteId = noteId)) RecordingState.live.update {
                    if (it.noteId == null) it.copy(message = "This note is being changed. Try again when it finishes.") else it
                }
                stopIfIdle(startId); return
            }
            synchronized(workLock) {
                owned[noteId]?.takeIf { it !== claim }?.release()
                owned[noteId] = claim
                skipped -= noteId
                // Counted now, so a worker that is just going idle does not stop the service under it.
                pendingRequests++
            }
            // The user's Retry also tries a failed model install again, before the worker looks at it.
            app.models.retry()
            engineRetry = true
            notifications.cancelTimedOut()
            scope.launch {
                try {
                    app.preferences.unfinishedEngineLoads = 0
                    // Only the user's explicit retry gives failed windows new attempts.
                    if (retryFailed) store.retryFailed(noteId)
                } catch (error: Exception) { Log.w(TAG, "Could not reset failed windows", error) }
                finally {
                    // Queued only now, so the worker never sees the note before its failed windows are pending.
                    synchronized(workLock) { requested += noteId; pendingRequests-- }
                }
                ensureWorker()
            }
        } else {
            synchronized(workLock) { drainWaiting = true; drainRequested = true }
            notifications.cancelTimedOut()
            ensureWorker()
        }
    }

    private fun stopIfIdle(startId: Int) {
        val idle = synchronized(workLock) { capture == null && worker == null }
        if (idle) stopSelfResult(startId)
    }

    // ------------------------------------------------------------------ foreground

    private fun microphoneType() = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
    private fun processingType() = when {
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    private fun promote(type: Int, notification: Notification) = synchronized(notificationLock) {
        if (destroyed) return@synchronized
        if (Build.VERSION.SDK_INT >= 29) startForeground(ServiceNotifications.FOREGROUND_ID, notification, type)
        else startForeground(ServiceNotifications.FOREGROUND_ID, notification)
    }

    /** Re-promotes with the type the current work needs (each foreground start must be answered). */
    private fun restoreForeground(preferProcessing: Boolean = false) = synchronized(notificationLock) {
        val session = capture
        if (session != null && !session.ended) promote(microphoneType(), foregroundNotification())
        else if (preferProcessing || worker != null) promote(processingType(), transcribingNotification())
    }

    /**
     * Promotes with the type and notification of the current work, decided under the notification lock: a
     * live capture keeps the microphone type, otherwise transcription's type. Throws if Android refuses.
     */
    private fun promoteForCurrentWork() = synchronized(notificationLock) {
        val session = capture
        if (session != null && !session.ended) promote(microphoneType(), foregroundNotification())
        else promote(processingType(), transcribingNotification())
    }

    /** The foreground notification for the current work: a live capture always wins over transcription. */
    private fun foregroundNotification(): Notification {
        val session = capture
        return when {
            session == null || session.ended -> transcribingNotification()
            session.noteId == null -> notifications.starting()
            else -> recordingNotification(session)
        }
    }

    /**
     * Re-posts the foreground notification for whatever runs now. Every update goes through here, so a
     * transcription update that lands after a new recording started cannot replace its chronometer and
     * Pause/Finish actions.
     */
    private fun refreshNotification() = synchronized(notificationLock) {
        if (!destroyed) notifications.update(foregroundNotification())
    }

    private fun recordingNotification(session: Capture): Notification =
        notifications.recording(session.noteId, session.elapsedMs, session.paused.get(), session.pauseReason)

    private fun transcribingNotification(): Notification {
        val live = RecordingState.live.value
        val id = processingNote ?: live.noteId
        return notifications.transcribing(id, processingTitle, live.progress, live.speech == SpeechAvailability.PREPARING)
    }


    /**
     * Holds the CPU awake while capture or a decode needs it. The lock has a timeout (a lost release never
     * holds it for good), so long work renews it: capture calls this every second and the worker on every
     * pass, which re-acquires it [WakePolicy.RENEW_MS] after the last acquisition and whenever it expired,
     * whether or not anything is being decoded (long silence, a model still installing, thermal waits).
     */
    @Synchronized private fun reconcileWake() {
        wakeLock?.let { lock ->
            val wanted = captureAwake.get() || decoderAwake.get()
            val now = SystemClock.elapsedRealtime()
            when (WakePolicy.action(wanted, lock.isHeld, wakeAcquiredAt, now)) {
                WakePolicy.Action.RELEASE -> runCatching { lock.release() }
                WakePolicy.Action.ACQUIRE -> {
                    // Not reference counted: acquiring a held lock re-arms its timeout.
                    lock.acquire(WakePolicy.TIMEOUT_MS)
                    wakeAcquiredAt = now
                }
                WakePolicy.Action.KEEP -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ capture

    private sealed interface CaptureEvent {
        data class Duration(val ms: Long) : CaptureEvent
        data class State(val name: String) : CaptureEvent
        data class AutoPause(val reason: String) : CaptureEvent
        /** A Pause at this PCM sample, already synced: segmentation closes the open window there. */
        data class Break(val sample: Long) : CaptureEvent
    }

    /** One microphone session: its claim, controls and the state the worker and notifications read. */
    private inner class Capture(val claim: SessionOperations.SessionClaim) {
        @Volatile var noteId: String? = null
        val stopping = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        val resumeRequested = AtomicBoolean(false)
        @Volatile var pauseReason: String? = null
        @Volatile var storageShort = 0L
        /** The microphone delivered audio once: the recognizer may load now. */
        @Volatile var started = false
        /** Audio is closed and its metadata drained: transcription treats the recording as finished. */
        @Volatile var ended = false
        @Volatile var failure: String? = null
        @Volatile var endReason: String? = null
        @Volatile var elapsedMs = 0L
        @Volatile var deviceFailed = false
        var job: Job? = null
        private val inputLock = Any()
        private var input: RecordingInput? = null
        @Volatile var inputRunning = false

        fun attach(device: RecordingInput?) = synchronized(inputLock) { input = device; inputRunning = false }

        /** Starts the microphone unless a stop or pause arrived first. */
        fun startInput(): Boolean = synchronized(inputLock) {
            val device = input ?: return false
            if (!stopping.get() && !paused.get()) { device.start(); inputRunning = true }
            inputRunning
        }

        fun stopInput() = synchronized(inputLock) { runCatching { input?.stop() }; inputRunning = false }

        fun requestPause() {
            if (ended || stopping.get()) return
            pauseReason = null
            paused.set(true)
            stopInput()
            // Capture acknowledges only after the microphone stops and PCM is synced.
        }

        fun requestResume() {
            if (ended || stopping.get() || !paused.get()) return
            resumeRequested.set(true)
        }

        fun requestStop() {
            stopping.set(true)
            stopInput()
        }

        /** Pauses on its own (call, silenced microphone, storage); the user resumes when ready. */
        fun pauseAutomatically(reason: String) {
            if (ended || stopping.get()) return
            pauseReason = reason
            paused.set(true)
            stopInput()
        }
    }

    /** Scalar metadata only: capture never waits for SQLite or queues audio in memory. */
    private inner class CaptureMetadata(private val session: Capture, private val id: String) {
        private val events = Channel<CaptureEvent>(
            if (BuildConfig.DEBUG) metadataCapacityForTests?.coerceIn(1, METADATA_CAPACITY) ?: METADATA_CAPACITY else METADATA_CAPACITY
        )
        private val rejected = AtomicBoolean(false)
        private val job = app.scope.launch(Dispatchers.IO) {
            try {
                for (event in events) {
                    when (event) {
                        is CaptureEvent.Duration -> store.duration(id, event.ms)
                        is CaptureEvent.State -> store.state(id, event.name)
                        is CaptureEvent.AutoPause -> store.pauseAutomatically(id, event.reason)
                        is CaptureEvent.Break -> store.addBreak(id, event.sample)
                    }
                }
            } catch (_: Exception) {
                rejected.set(true)
                session.failure = session.failure ?: "Recording metadata could not be saved. Original audio is kept for recovery."
                session.requestStop()
                events.close()
                // Drop queued scalar updates; the synced file length is the durable duration.
                while (events.tryReceive().isSuccess) continue
            }
        }

        /** The durable duration is rebuilt from the synced file, so a dropped update loses no audio. */
        val failed get() = rejected.get()

        fun enqueue(event: CaptureEvent) {
            if (events.trySend(event).isFailure) {
                rejected.set(true)
                error("Recording stopped because saved-audio metadata could not keep up. Original audio is kept for recovery.")
            }
        }

        suspend fun finish() { events.close(); job.join() }
    }

    private suspend fun runCapture(session: Capture) {
        var device: RecordingInput? = null
        try {
            app.storageReady.await()
            if (session.stopping.get()) return
            @Suppress("UsableSpace")
            val short = StorageReserve.shortfall(filesDir.usableSpace, StorageReserve.START_BYTES)
            if (short > 0) {
                fail(session, ServiceEvent.StartFailed(StartFailure.STORAGE, "Free up some space to record.", short))
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fail(session, ServiceEvent.StartFailed(StartFailure.PERMISSION, "Microphone access is required."))
                return
            }
            // The microphone is opened before a note exists: a busy microphone creates no note.
            device = try { openInput() } catch (error: Exception) { null }
            if (device == null || !device.initialized) {
                runCatching { device?.close() }
                device = null
                fail(session, ServiceEvent.StartFailed(StartFailure.MICROPHONE, "Another app is using the microphone."))
                return
            }
            val id = store.create()
            session.noteId = id
            session.claim.bindNote(id)
            RecordingState.live.value = LiveSession(id, message = "Starting recording…", speech = speechAvailability())
            val opened = device
            device = null
            captureAudio(session, id, opened)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            runCatching { device?.close() }
            val message = error.message ?: "Could not start recording."
            if (session.noteId == null) fail(session, ServiceEvent.StartFailed(StartFailure.OTHER, message))
            else session.failure = session.failure ?: message
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { finishCapture(session) }
        }
    }

    private fun fail(session: Capture, event: ServiceEvent.StartFailed) {
        RecordingState.events.tryEmit(event)
        if (session.noteId == null) RecordingState.live.update { if (it.noteId == null) it.copy(message = event.message) else it }
    }

    @Suppress("MissingPermission")
    private fun openInput(): RecordingInput =
        if (BuildConfig.DEBUG) inputFactoryForTests?.invoke() ?: DeviceInput() else DeviceInput()

    /** Why Android silenced the microphone: a call if the audio mode says so, else another app or the system. */
    private fun silencedReason(): String {
        val mode = getSystemService(AudioManager::class.java).mode
        return if (mode != AudioManager.MODE_NORMAL && mode >= AudioManager.MODE_RINGTONE) CaptureReason.CALL else CaptureReason.MICROPHONE
    }

    @Suppress("MissingPermission", "UsableSpace")
    private suspend fun captureAudio(session: Capture, id: String, first: RecordingInput) {
        var device: RecordingInput? = first
        var stream: BufferedOutputStream? = null
        var fileStream: FileOutputStream? = null
        var samplesWritten = 0L
        val frame = ShortArray(LevelMeter.FRAME_SAMPLES)
        // The speech worker frames the complete synced original; the meter only feeds the UI.
        val meter = LevelMeter()
        val metadata = CaptureMetadata(session, id)
        try {
            val raw = FileOutputStream(store.audioFile(id))
            fileStream = raw
            val buffered = BufferedOutputStream(raw, 16384)
            stream = buffered
            session.attach(first)
            var acknowledgedPause = false
            var lastUi = 0L
            var lastSync = SystemClock.elapsedRealtime()
            val bytes = ByteArray(frame.size * 2)
            fun syncAudio() {
                buffered.flush(); raw.fd.sync()
                metadata.enqueue(CaptureEvent.Duration(samplesWritten / 16))
            }
            fun publishPaused() {
                RecordingState.live.update {
                    if (it.noteId == id) it.copy(paused = true, pauseReason = session.pauseReason, storageShortBytes = session.storageShort,
                        duration = samplesWritten / 16, level = 0f, message = "Paused") else it
                }
                refreshNotification()
            }
            while (currentCoroutineContext().isActive && !session.stopping.get()) {
                if (session.paused.get()) {
                    if (!acknowledgedPause) {
                        session.stopInput()
                        syncAudio()
                        captureAwake.set(false); reconcileWake()
                        if (samplesWritten > 0) metadata.enqueue(CaptureEvent.Break(samplesWritten))
                        val reason = session.pauseReason
                        metadata.enqueue(if (reason == null) CaptureEvent.State(NoteState.PAUSED) else CaptureEvent.AutoPause(reason))
                        AudioLevel.publish(0f)
                        publishPaused()
                        if (reason != null) notifications.alertAutoPause(id, samplesWritten / 16, reason)
                        acknowledgedPause = true
                    }
                    if (session.resumeRequested.getAndSet(false)) {
                        val short = StorageReserve.shortfall(filesDir.usableSpace, StorageReserve.RESUME_BYTES)
                        if (short > 0) {
                            session.pauseReason = CaptureReason.STORAGE
                            session.storageShort = short
                            metadata.enqueue(CaptureEvent.AutoPause(CaptureReason.STORAGE))
                            publishPaused()
                        } else {
                            if (device == null || session.deviceFailed) {
                                // A microphone that stopped delivering audio is reopened for Resume.
                                runCatching { device?.close() }
                                session.attach(null)
                                device = runCatching { openInput() }.getOrNull()?.takeIf { it.initialized }
                                session.attach(device)
                                session.deviceFailed = device == null
                            }
                            if (device == null) {
                                session.pauseReason = CaptureReason.MICROPHONE
                                metadata.enqueue(CaptureEvent.AutoPause(CaptureReason.MICROPHONE))
                                publishPaused()
                            } else {
                                session.pauseReason = null
                                session.storageShort = 0
                                notifications.cancelAutoPause()
                                session.paused.set(false)
                            }
                        }
                    }
                    delay(30); continue
                }
                val current = device
                if (current == null) {
                    session.deviceFailed = true
                    session.pauseAutomatically(CaptureReason.MICROPHONE)
                    continue
                }
                if (!session.inputRunning) {
                    if (!session.startInput()) continue
                    captureAwake.set(true); reconcileWake()
                    acknowledgedPause = false
                    session.started = true
                    metadata.enqueue(CaptureEvent.State(NoteState.RECORDING))
                    RecordingState.live.update {
                        if (it.noteId == id) it.copy(paused = false, pauseReason = null, storageShortBytes = 0, message = "Listening") else it
                    }
                    refreshNotification()
                }
                if (current.silenced) { session.pauseAutomatically(silencedReason()); continue }
                val count = current.read(frame, 0, frame.size)
                if (count <= 0) {
                    if (session.stopping.get() || session.paused.get() || !session.inputRunning) continue
                    // The microphone stopped delivering audio: pause, keep what is saved, reopen on Resume.
                    session.deviceFailed = true
                    session.pauseAutomatically(CaptureReason.MICROPHONE)
                    continue
                }
                check(count <= frame.size) { "The microphone returned an invalid audio buffer." }
                repeat(count) { index ->
                    val sample = frame[index].toInt()
                    bytes[index * 2] = sample.toByte(); bytes[index * 2 + 1] = (sample shr 8).toByte()
                }
                buffered.write(bytes, 0, count * 2)
                samplesWritten += count
                session.elapsedMs = samplesWritten / 16
                meter.accept(frame, count)
                AudioLevel.publish(MeterMath.normalized(meter.rms))
                // A successful read belongs to the original, even if Android silenced
                // the microphone while that read was in flight. It is written before pausing.
                if (current.silenced) session.pauseAutomatically(silencedReason())
                val now = SystemClock.elapsedRealtime()
                if (now - lastUi >= 100) {
                    RecordingState.live.update { if (it.noteId == id) it.copy(duration = samplesWritten / 16, level = meter.level) else it }
                    lastUi = now
                }
                if (now - lastSync >= 1000) {
                    syncAudio(); lastSync = now
                    reconcileWake()
                    if (filesDir.usableSpace < StorageReserve.PAUSE_BYTES) session.pauseAutomatically(CaptureReason.STORAGE)
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        session.endReason = CaptureReason.PERMISSION
                        error("Microphone access was removed. Saved audio is safe.")
                    }
                }
            }
        } catch (error: Exception) {
            if (error !is CancellationException) session.failure = session.failure ?: (error.message ?: "Recording was interrupted.")
        } finally {
            session.attach(null)
            runCatching { device?.stop() }
            runCatching { device?.close() }
            try { stream?.flush(); fileStream?.fd?.sync() }
            catch (error: Exception) { session.failure = session.failure ?: (error.message ?: "Could not finish saving audio.") }
            try { stream?.close() }
            catch (error: Exception) { session.failure = session.failure ?: (error.message ?: "Could not close saved audio.") }
            AudioLevel.publish(0f)
            notifications.cancelAutoPause()
            session.claim.finishCapture()
            RecordingState.live.update {
                if (it.noteId == id) it.copy(processing = true, paused = false, pauseReason = null, storageShortBytes = 0,
                    level = 0f, message = session.failure ?: "Finishing transcript…") else it
            }
            // The microphone and original file are closed before waiting on any database work.
            withContext(NonCancellable + Dispatchers.IO) {
                try { metadata.finish() }
                catch (error: Exception) { session.failure = session.failure ?: (error.message ?: "Could not finish saving audio.") }
            }
        }
    }

    /**
     * The microphone session is over: the note's duration comes from the synced file, an empty recording is
     * discarded, and the note moves to the transcription queue (the capture slot is free for the next one).
     */
    private fun finishCapture(session: Capture) {
        val id = session.noteId
        var queued = false
        try {
            if (id != null) {
                val duration = store.audioFile(id).length() / 32
                store.duration(id, duration)
                session.endReason?.let { store.endReason(id, it) }
                if (store.audioIsEmpty(id) && store.paragraphs(id).isEmpty()) {
                    // No bytes, or exactly constant samples: nothing was recorded.
                    store.delete(id)
                    RecordingState.events.tryEmit(ServiceEvent.NothingRecorded)
                    RecordingState.live.update { if (it.noteId == id) LiveSession(message = "Nothing was recorded.") else it }
                } else {
                    store.state(id, NoteState.PROCESSING, session.failure.orEmpty())
                    RecordingState.live.update { if (it.noteId == id) it.copy(duration = duration) else it }
                    queued = true
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not finish the recording", error)
            if (id != null) runCatching { store.recover(id) }
        } finally {
            synchronized(workLock) {
                if (queued && id != null) {
                    SessionOperations.handOver(session.claim)?.let { owned[id] = it }
                    finishing.addLast(id)
                } else session.claim.release()
                if (capture === session) capture = null
                session.ended = true
            }
            captureAwake.set(false); reconcileWake()
        }
        if (queued) {
            // A new recording may already be current: it keeps the microphone type.
            try { promoteForCurrentWork() }
            catch (error: RuntimeException) {
                // Android refused background processing: the note waits for the next foreground start.
                Log.w(TAG, "Could not continue transcription in the background", error)
                synchronized(workLock) { finishing.remove(id); owned.remove(id)?.release() }
                runCatching { store.state(id!!, NoteState.INTERRUPTED, "Saved audio needs transcription. Open the note to continue.") }
            }
        }
        ensureWorker()
    }

    @Suppress("MissingPermission")
    private inner class DeviceInput : RecordingInput {
        private val recorder: AudioRecord
        private var callback: AudioManager.AudioRecordingCallback? = null
        @Volatile private var closed = false
        override val initialized get() = recorder.state == AudioRecord.STATE_INITIALIZED
        override val silenced get() = Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true
        init {
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum in 1..65536) { "This device cannot record 16 kHz audio." }
            recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(16000, (minimum * 4).coerceAtMost(65536))).build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                error("The microphone is unavailable. Close other recording apps and try again.")
            }
            if (Build.VERSION.SDK_INT >= 29) {
                callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (!closed && configs.any { it.isClientSilenced } && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                            capture?.pauseAutomatically(silencedReason())
                    }
                }.also {
                    try { recorder.registerAudioRecordingCallback(mainExecutor, it) }
                    catch (error: RuntimeException) { recorder.release(); throw error }
                }
            }
        }
        override fun start() { recorder.startRecording(); check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone could not start." } }
        override fun stop() { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
        override fun read(samples: ShortArray, offset: Int, count: Int) = recorder.read(samples, offset, count, AudioRecord.READ_BLOCKING)
        override fun close() {
            closed = true
            try { if (Build.VERSION.SDK_INT >= 29) callback?.let { recorder.unregisterAudioRecordingCallback(it) } }
            finally { recorder.release() }
        }
    }

    // ------------------------------------------------------------------ transcription

    private fun ensureWorker() {
        synchronized(workLock) {
            if (destroyed || worker != null) return
            val job = scope.launch(speechDispatcher, start = CoroutineStart.LAZY) { work() }
            worker = job
            job.invokeOnCompletion { main.post { onWorkerIdle(job) } }
            job.start()
        }
    }

    /** The worker found nothing left (or ended): restart it if work arrived meanwhile, else stop the service. */
    private fun onWorkerIdle(job: Job) {
        val more = synchronized(workLock) {
            if (worker !== job) return
            worker = null
            // A RECOVER still resetting its windows starts the worker itself when it is queued.
            if (pendingRequests > 0) return
            capture != null || finishing.isNotEmpty() || requested.isNotEmpty() || drainRequested
        }
        if (destroyed) return
        if (more) { ensureWorker(); return }
        RecordingState.live.update { if (it.noteId != null) LiveSession() else it }
        stopSelfResult(lastStartId)
    }

    private class Target(val noteId: String, val live: Boolean)

    /** The live recording, then the finished one, then retries, then (from the visible app) waiting notes. */
    private fun nextTarget(): Target? {
        synchronized(workLock) {
            capture?.let { session ->
                val id = session.noteId
                if (id != null && !session.ended) return Target(id, live = true)
                if (id == null && !session.ended) return Target("", live = true)
            }
            finishing.firstOrNull()?.let { return Target(it, live = false) }
            requested.firstOrNull()?.let { return Target(it, live = false) }
            if (!drainWaiting) return null
            // This scan answers every DRAIN so far; a later one makes the worker look again.
            drainRequested = false
        }
        val waiting = try { store.waitingNotes() } catch (_: Exception) { emptyList() }
        return synchronized(workLock) { waiting.firstOrNull { it !in skipped && it !in owned }?.let { Target(it, live = false) } }
    }

    private fun dequeue(id: String) = synchronized(workLock) {
        finishing.remove(id); requested.remove(id)
        owned.remove(id)?.release()
        Unit
    }

    /** Claims [id] for transcription unless a destructive operation holds it. */
    private fun own(id: String): Boolean = synchronized(workLock) {
        if (owned[id] != null) return true
        val claim = SessionOperations.reserveSession(capture = false, noteId = id) ?: return false
        SessionOperations.acceptSession(claim.token, capture = false, noteId = id)
        owned[id] = claim
        true
    }

    /** A capture is starting or running: the queue yields to it. */
    private fun preempted(): Boolean = capture?.let { !it.ended } == true

    private suspend fun work() {
        val engine = Engine()
        var feedNote: String? = null
        var feed: WindowFeed? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val target = nextTarget() ?: break
                if (target.noteId.isEmpty()) { delay(50); continue } // a capture is opening its note
                if (feedNote != target.noteId) {
                    feedNote = target.noteId
                    feed = WindowFeed(store, target.noteId) { engine.detector() }
                }
                val outcome = try { process(target, checkNotNull(feed), engine) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Transcription stopped for a note", error)
                    if (target.live) {
                        // Capture goes on; the note is transcribed after Finish (or waits, if this persists).
                        feedNote = null; feed = null
                        delay(1000); continue
                    }
                    Outcome.Blocked(error.message ?: "Saved audio can be transcribed later.")
                }
                when (outcome) {
                    Outcome.Continue -> Unit
                    Outcome.Yielded -> { feedNote = null; feed = null; refreshNotification() }
                    Outcome.Done -> { feedNote = null; feed = null; dequeue(target.noteId) }
                    is Outcome.Blocked -> {
                        feedNote = null; feed = null
                        runCatching { store.state(target.noteId, NoteState.INTERRUPTED, outcome.message) }
                        synchronized(workLock) { skipped += target.noteId }
                        dequeue(target.noteId)
                    }
                }
                if (outcome != Outcome.Continue) RecordingState.live.update {
                    if (it.noteId == target.noteId && it.processing) LiveSession() else it
                }
            }
        } finally {
            processingNote = null
            transcriber = null
            engine.close()
            decoderAwake.set(false); reconcileWake()
        }
    }

    private sealed interface Outcome {
        /** The live recording ended; its note continues as the finished recording. */
        data object Continue : Outcome
        /** A recording started: the note waits and is resumed after it. */
        data object Yielded : Outcome
        data object Done : Outcome
        data class Blocked(val message: String) : Outcome
    }

    private suspend fun process(target: Target, feed: WindowFeed, engine: Engine): Outcome {
        val id = target.noteId
        val live = target.live
        if (!live) {
            if (!own(id)) { synchronized(workLock) { skipped += id }; return Outcome.Done }
            val saved = store.note(id) ?: return Outcome.Done
            if (saved.state == NoteState.READY && !store.hasUntranscribedAudio(saved)) return Outcome.Done
            // A failed live finalization or process death can leave a durable tail: the file is the duration.
            store.recover(id)
            store.state(id, NoteState.PROCESSING)
            processingNote = id
            decoderAwake.set(true); reconcileWake()
            publishProcessing(id, engine)
        }
        try {
            var lastProgress = -1f
            while (true) {
                currentCoroutineContext().ensureActive()
                reconcileWake()
                val session = capture
                val stillLive = live && session != null && session.noteId == id && !session.ended
                if (live && !stillLive) return Outcome.Continue
                if (!live && preempted()) {
                    store.state(id, NoteState.INTERRUPTED)
                    synchronized(workLock) { owned.remove(id)?.release() }
                    return Outcome.Yielded
                }
                if (!engine.settled()) {
                    // The notification follows only a change (publishSpeech), never every poll.
                    publishSpeech(id, SpeechAvailability.PREPARING)
                    delay(250); continue
                }
                // The recognizer loads once the microphone delivers audio (never before capture starts).
                if (!live || session?.started == true) engine.load()
                publishSpeech(id, engine.availability)
                val note = store.note(id) ?: return Outcome.Done
                val pending = store.pendingWindows(id).firstOrNull()
                val decoder = engine.decoder
                if (pending != null && decoder != null) {
                    if (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                        RecordingState.live.update { if (it.noteId == id) it.copy(message = "Cooling down · audio is saved") else it }
                        delay(3000); continue
                    }
                    // A window that already failed or killed the process twice is marked failed instead.
                    val window = store.beginDecode(pending.id) ?: continue
                    decoderAwake.set(true); reconcileWake()
                    val started = SystemClock.elapsedRealtime()
                    try {
                        transcriber = decoder
                        decoder.transcribe(store, window, engine.threads)
                        engine.succeeded()
                    } catch (error: Exception) {
                        if (error is CancellationException) { runCatching { store.cancelDecode(window.id) }; throw error }
                        Log.w(TAG, "Window ${window.index} failed (attempt ${window.attempts})", error)
                        if (window.attempts >= MAX_DECODE_ATTEMPTS) store.failWindow(window.id, error.message ?: "This part could not be transcribed.")
                        engine.failed(error, window.id)
                        continue
                    } finally {
                        if (live) { decoderAwake.set(false); reconcileWake() }
                    }
                    if (!live) lastProgress = publishProgress(id, lastProgress)
                    else RecordingState.live.update { if (it.noteId == id) it.copy(pending = store.pendingCount(id)) else it }
                    val warm = DeviceSpeechPolicy.warm(this)
                    val cooldown = DecodePolicy.cooldownMs(SystemClock.elapsedRealtime() - started, power.isPowerSaveMode, warm)
                    if (cooldown > 0 && !feed.complete) delay(cooldown)
                    continue
                }
                if (feed.advance(note.duration, finished = !live, legacy = note.legacy)) continue
                if (!live && feed.complete) {
                    if (pending != null) return Outcome.Blocked(engine.failure ?: "Saved audio can be transcribed later.")
                    return complete(id)
                }
                delay(if (live) LIVE_POLL_MS else 50)
            }
        } finally {
            if (!live) {
                processingNote = null
                decoderAwake.set(false); reconcileWake()
            }
        }
    }

    /** Every window is decided: the note is ready (possibly with failed parts) and "Note ready" is posted. */
    private fun complete(id: String): Outcome {
        if (!store.markReady(id)) return Outcome.Blocked("Saved audio can still be transcribed.")
        store.note(id)?.let { note -> notifications.ready(id, note.title, note.created, partial = note.failedWindows > 0) }
        RecordingState.live.update { if (it.noteId == id) it.copy(progress = 1f, pending = 0) else it }
        return Outcome.Done
    }

    private fun publishProcessing(id: String, engine: Engine) {
        val progress = progressOf(id)
        val note = store.note(id)
        processingTitle = note?.title.orEmpty()
        val session = LiveSession(id, processing = true, duration = note?.duration ?: 0,
            message = "Transcribing", progress = progress, speech = engine.availabilityOrPreparing(),
            pending = runCatching { store.pendingCount(id) }.getOrDefault(0))
        // A recording that started since this note was picked owns the live state: never overwrite it.
        RecordingState.live.update { if (it.capturing) it else session }
        refreshNotification()
    }

    private fun publishSpeech(id: String, speech: SpeechAvailability) {
        if (RecordingState.live.value.let { it.noteId == id && it.speech != speech }) {
            RecordingState.live.update { if (it.noteId == id) it.copy(speech = speech) else it }
            refreshNotification()
        }
    }

    private fun progressOf(id: String): Float? = runCatching {
        val progress = store.progress(id)
        if (progress.durationMs <= 0) null else (1f - progress.remainingMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    }.getOrNull()

    /** Publishes progress; the notification changes only at whole percents. */
    private fun publishProgress(id: String, previous: Float): Float {
        val progress = progressOf(id) ?: return previous
        RecordingState.live.update {
            if (it.noteId == id) it.copy(progress = progress, pending = runCatching { store.pendingCount(id) }.getOrDefault(it.pending)) else it
        }
        if ((progress * 100).toInt() != (previous * 100).toInt()) {
            // The automatic title appears with the first words.
            store.note(id)?.title?.let { processingTitle = it }
            refreshNotification()
        }
        return progress
    }

    private fun speechAvailability(): SpeechAvailability = when (app.models.status.value) {
        is ModelStatus.Installing, ModelStatus.Checking -> SpeechAvailability.PREPARING
        is ModelStatus.Failed, ModelStatus.NotBundled -> SpeechAvailability.UNAVAILABLE
        is ModelStatus.Ready -> SpeechAvailability.READY
    }

    /**
     * The worker's speech resources: Silero VAD for segmentation (relative energy if it cannot load) and
     * one recognizer, created on the speech thread with the device's thread policy and released when the
     * worker has nothing left to do.
     */
    private inner class Engine {
        var decoder: RecordingDecoder? = null
            private set
        var failure: String? = null
            private set
        var threads = 1
            private set
        private var vad: SileroVad? = null
        private var files: Map<String, File>? = null
        private var resolved = false
        /** Resolved without a usable model (a failed install or a build without one). */
        private var modelUnavailable = false
        private var loadTried = false
        private var consecutiveFailures = 0
        private var lastFailedWindow: Long? = null
        private val testDecoder = if (BuildConfig.DEBUG) decoderFactoryForTests else null

        val availability: SpeechAvailability get() = when {
            decoder != null -> SpeechAvailability.READY
            failure != null -> SpeechAvailability.UNAVAILABLE
            else -> speechAvailability()
        }

        fun availabilityOrPreparing() = if (resolved) availability else SpeechAvailability.PREPARING

        /**
         * False while the model is still being installed; the detector is decided once it is not. A failed
         * install settles too (the note then waits with its message); it is never restarted from here, only
         * followed when a retry elsewhere (the user's, a foreground start) changes it.
         */
        fun settled(): Boolean {
            // The user's Retry gives a failed install or recognizer load another chance.
            if (engineRetry && decoder == null) {
                engineRetry = false
                reset()
            }
            if (testDecoder != null) { resolved = true; return true }
            val status = app.models.status.value
            if (resolved && modelUnavailable && status !is ModelStatus.Failed && status != ModelStatus.NotBundled) reset()
            if (resolved) return true
            when (status) {
                // Only the first attempt starts here: a failed one is not repeated on every poll.
                ModelStatus.Checking -> { app.models.ensureStarted(); return false }
                is ModelStatus.Installing -> return false
                is ModelStatus.Ready -> {
                    files = status.files
                    if (vad == null) vad = try { SileroVad.open(status.files.getValue(UltraModel.VAD)) }
                    catch (error: Throwable) {
                        // The relative-energy detector segments instead.
                        Log.w(TAG, "Silero VAD unavailable; using the energy detector", error)
                        null
                    }
                }
                is ModelStatus.Failed -> { failure = status.message; modelUnavailable = true }
                ModelStatus.NotBundled -> { failure = MODEL_MISSING; modelUnavailable = true }
            }
            resolved = true
            return true
        }

        private fun reset() {
            resolved = false; modelUnavailable = false; loadTried = false; failure = null
            consecutiveFailures = 0; lastFailedWindow = null
        }

        /** A fresh detector state for each new segmenter; null selects the energy fallback. */
        fun detector(): SpeechDetector? = vad?.also { it.reset() }

        /** Loads the recognizer once (again after a user retry). Never throws. */
        fun load() {
            if (loadTried || decoder != null) return
            loadTried = true
            val prefs = app.preferences
            // Loads that kept failing or killed the process wait for the user's Retry, also during capture.
            if (testDecoder == null && prefs.unfinishedEngineLoads >= MAX_UNFINISHED_LOADS) {
                failure = "Not enough free memory to transcribe right now."
                return
            }
            val installed = files
            if (testDecoder == null && installed == null) { failure = failure ?: MODEL_MISSING; return }
            threads = DeviceSpeechPolicy.recognizerThreads(this@RecordingService)
            decoderAwake.set(true); reconcileWake()
            try {
                if (testDecoder == null) prefs.unfinishedEngineLoads = prefs.unfinishedEngineLoads + 1
                val opened = testDecoder?.invoke() ?: UltraTranscriber { count, _ ->
                    UltraEngine.open(this@RecordingService, checkNotNull(installed), count)
                }
                try { opened.prepare(threads) }
                catch (error: Throwable) { runCatching { opened.close() }; throw error }
                decoder = opened
                failure = null
                if (testDecoder == null) prefs.unfinishedEngineLoads = 0
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "The recognizer could not load", error)
                failure = when (error) {
                    is OutOfMemoryError -> "Not enough free memory to transcribe right now."
                    else -> error.message ?: "The speech model could not load."
                }
            } finally {
                decoderAwake.set(processingNote != null); reconcileWake()
            }
        }

        fun succeeded() { consecutiveFailures = 0; lastFailedWindow = null }

        /**
         * Different windows failing one after another mean the recognizer itself is broken: it is dropped and
         * the remaining windows stay pending. A window failing twice on its own is marked failed by the store.
         */
        fun failed(error: Exception, windowId: Long) {
            if (lastFailedWindow == windowId) return
            lastFailedWindow = windowId
            if (++consecutiveFailures < BROKEN_AFTER_FAILURES) return
            failure = error.message ?: "Transcription failed."
            runCatching { decoder?.close() }
            decoder = null
        }

        fun close() {
            runCatching { decoder?.close() }
            decoder = null
            runCatching { vad?.close() }
            vad = null
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android's background-processing quota ran out (Android 15+, 6 h per day): pause the queue. The
        // note waits; the next foreground start of Focal continues it. Must stop within seconds.
        val id = processingNote ?: RecordingState.live.value.noteId
        transcriber?.cancel()
        synchronized(workLock) { worker?.cancel() }
        notifications.timedOut(id)
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(notificationLock) {
            destroyed = true
            try { stopForeground(STOP_FOREGROUND_REMOVE) }
            finally {
                // Android may already have demoted the service before onDestroy. A late
                // notify() can then be a regular ongoing notification, outside the FGS
                // removal contract. Cancel synchronously here, before a new service's
                // main-thread startup can take ownership of the same notification ID.
                notifications.cancelForeground()
            }
        }
        val session = capture
        session?.requestStop()
        // Prevent a worker that is still opening its engine from starting a decode
        // after teardown has already looked for an engine to cancel.
        scope.cancel()
        transcriber?.cancel()
        app.scope.launch {
            try {
                session?.job?.join()
                synchronized(workLock) { worker }?.join()
                val notes = synchronized(workLock) { (owned.keys + listOfNotNull(session?.noteId)).toSet() }
                for (id in notes) {
                    try {
                        if (store.note(id)?.state in NoteState.ACTIVE)
                            store.state(id, NoteState.INTERRUPTED, session?.failure.orEmpty())
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not mark a note as waiting", error)
                    }
                }
                RecordingState.live.update { if (it.noteId != null) LiveSession() else it }
            } finally {
                synchronized(workLock) {
                    owned.values.forEach { it.release() }
                    owned.clear()
                    session?.claim?.release()
                }
                captureAwake.set(false); decoderAwake.set(false); reconcileWake()
                captureDispatcher.close(); speechDispatcher.close()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val START = "app.focal.START"
        const val STOP = "app.focal.STOP"
        const val PAUSE = "app.focal.PAUSE"
        const val RESUME = "app.focal.RESUME"
        /** Transcribe one note now (user Retry or Continue); [command]'s retryFailed resets its failed windows. */
        const val RECOVER = "app.focal.RECOVER"
        /** Continue every note waiting for transcription. Send only from a visible activity. */
        const val DRAIN = "app.focal.DRAIN"
        const val NOTE = "note"
        private const val OPERATION = "operation"
        private const val RETRY_FAILED = "retryFailed"
        private const val TAG = "FocalService"
        private const val METADATA_CAPACITY = 64
        private const val LIVE_POLL_MS = 200L
        /** Matches the store's crash-loop guard. */
        private const val MAX_DECODE_ATTEMPTS = 2
        /** Consecutive failed windows after which the recognizer is treated as broken. */
        private const val BROKEN_AFTER_FAILURES = 2
        /** Recognizer loads that did not finish (failure or process death) before automatic loads stop. */
        const val MAX_UNFINISHED_LOADS = 2
        @Volatile internal var inputFactoryForTests: (() -> RecordingInput)? = null
        @Volatile internal var decoderFactoryForTests: (() -> RecordingDecoder)? = null
        @Volatile internal var metadataCapacityForTests: Int? = null

        /**
         * Sends a command. START needs the microphone permission and a visible activity; RECOVER claims
         * [id] for transcription ([retryFailed]: its failed windows get new attempts); DRAIN continues
         * waiting notes and must also come from a visible activity.
         */
        fun command(context: Context, action: String, id: String? = null, retryFailed: Boolean = false) {
            require(action != RECOVER || !id.isNullOrBlank()) { "Choose an existing note to continue transcription." }
            if (action == START && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Microphone access is required.")
            val intent = Intent(context, RecordingService::class.java).setAction(action).putExtra(NOTE, id)
                .putExtra(RETRY_FAILED, retryFailed)
            when (action) {
                START, RECOVER -> {
                    val capture = action == START
                    val claim = SessionOperations.reserveSession(capture, if (capture) null else id)
                    if (claim == null) {
                        // The microphone slot is still held while the previous recording closes its note:
                        // nothing would start, so say so instead of accepting the tap silently.
                        if (capture && SessionOperations.captureFinishing())
                            error("Finishing the previous recording. Try again in a moment.")
                        if (SessionOperations.matchesSession(capture, id)) return
                        error(if (capture) "A recording is already running." else "This note is being changed. Try again when it finishes.")
                    }
                    intent.putExtra(OPERATION, claim.token)
                    try { ContextCompat.startForegroundService(context, intent) }
                    catch (error: Exception) { claim.release(); throw error }
                }
                DRAIN -> ContextCompat.startForegroundService(context, intent)
                else -> context.startService(intent)
            }
        }
    }
}
