package app.focal.recording

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.focal.BuildConfig
import app.focal.FocalApplication
import app.focal.MainActivity
import app.focal.R
import app.focal.core.DecodePolicy
import app.focal.core.SpeechGate
import app.focal.core.Transcript
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.speech.Transcriber
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class LiveSession(val noteId: String? = null, val paused: Boolean = false, val processing: Boolean = false,
                       val duration: Long = 0, val level: Float = 0f, val message: String = "", val pending: Int = 0)
object RecordingState { val live = MutableStateFlow(LiveSession()) }

/** Small device boundaries also allow deterministic capture-durability tests without speech models. */
internal interface RecordingInput : AutoCloseable {
    val initialized: Boolean
    val silenced: Boolean
    fun start()
    fun stop()
    fun read(samples: ShortArray, offset: Int, count: Int): Int
}
internal interface RecordingDecoder : AutoCloseable {
    suspend fun transcribe(store: NoteStore, chunk: Chunk, language: String, threads: Int)
    fun cancel()
}

/** The activity is only a controller. This microphone foreground service owns capture. */
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
    private val stopping = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val captureAwake = AtomicBoolean(false)
    private val decoderAwake = AtomicBoolean(false)
    private val inputLock = Any()
    private val notificationLock = Any()
    @Volatile private var destroyed = false
    private var input: RecordingInput? = null
    @Volatile private var inputRunning = false
    @Volatile private var recordingDone = false
    @Volatile private var noteId: String? = null
    @Volatile private var transcriber: RecordingDecoder? = null
    @Volatile private var captureFailure: String? = null
    @Volatile private var decodeFailure: String? = null
    private var started = false
    private var ownership: SessionOperations.SessionClaim? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startup: Job? = null
    private var worker: Job? = null
    private var capture: Job? = null
    private val power by lazy { getSystemService(PowerManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Recording controls and transcription progress"; setSound(null, null)
            })
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START, RECOVER -> if (!started) {
                val recovery = intent.action == RECOVER
                val recoverId = intent.getStringExtra(NOTE)
                recordingDone = recovery
                // Every raw FGS start must be promoted before rejecting it. A stale idle
                // command can share a ServiceRecord with a newer queued foreground start.
                try { foreground(if (recovery) "Preparing transcript…" else "Starting recording…", recovery) }
                catch (error: RuntimeException) {
                    RecordingState.live.value = LiveSession(message = if (error is SecurityException)
                        "Allow microphone access and start recording while Focal is open." else "Recording could not start. Open Focal and try again.")
                    // A microphone permission can disappear after command validation.
                    // Processing promotion opens no microphone and disarms the FGS deadline.
                    runCatching { foreground("Recording unavailable", true) }
                    SessionOperations.releasePending(intent.getStringExtra(OPERATION))
                    stopSelfResult(startId); return START_NOT_STICKY
                }
                if (recovery && recoverId.isNullOrBlank()) {
                    RecordingState.live.value = LiveSession(message = "This note is no longer available.")
                    stopSelfResult(startId); return START_NOT_STICKY
                }
                val claim = try {
                    SessionOperations.acceptSession(intent.getStringExtra(OPERATION), capture = !recovery, noteId = if (recovery) recoverId else null)
                } catch (_: Exception) { null }
                if (claim == null) {
                    if (RecordingState.live.value.noteId == null)
                        RecordingState.live.value = LiveSession(message = "This session is busy. Try again when its current operation finishes.")
                    stopSelfResult(startId); return START_NOT_STICKY
                }
                ownership = claim
                started = true
                startup = scope.launch { begin(if (recovery) recoverId else null) }
            }
            PAUSE, RESUME -> if (started && !recordingDone && !stopping.get() && !RecordingState.live.value.processing) {
                paused.set(intent.action == PAUSE)
                if (paused.get()) interruptInput()
                // Capture acknowledges only after the microphone stops and PCM is synced.
            } else if (!started) stopSelfResult(startId)
            STOP -> if (started) {
                stopping.set(true)
                interruptInput()
            } else stopSelfResult(startId)
            else -> if (!started) stopSelfResult(startId)
        }
        // Never silently reopen a microphone after process death.
        return START_NOT_STICKY
    }

    private suspend fun begin(recover: String?) {
        try {
            app.storageReady.await()
            if (stopping.get()) { stopSelf(); return }
            val id = recover?.also { checkNotNull(store.note(it)) { "This note is no longer available." } }
                ?: store.create(app.preferences.settings.value.language)
            noteId = id
            ownership?.bindNote(id)
            val note = checkNotNull(store.note(id))
            RecordingState.live.value = LiveSession(id, paused = paused.get(), processing = recover != null,
                duration = note.duration, message = if (recover != null) "Preparing transcript…" else "Starting recording…")
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Focal:recording").apply { setReferenceCounted(false) }
            recordingDone = recover != null
            if (recover != null) {
                // A failed live finalization can leave WRITING metadata or a
                // durable tail even though application startup already recovered.
                store.recover(id)
                val duration = checkNotNull(store.note(id)).duration
                RecordingState.live.update { if (it.noteId == id) it.copy(duration = duration) else it }
                store.state(id, "PROCESSING")
            }
            else capture = scope.launch(captureDispatcher) { captureAudio(id) }
            worker = scope.launch(speechDispatcher) { processQueue(id, note.language) }
            scope.launch {
                var ticks = 0
                while (isActive && RecordingState.live.value.noteId == id) {
                    delay(1000)
                    if (++ticks % (30 * 60) == 0) reconcileWake(renew = true)
                    runCatching { store.pendingCount(id) }.getOrNull()?.let { pending ->
                        RecordingState.live.update { if (it.noteId == id) it.copy(pending = pending) else it }
                    }
                    updateNotification()
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            captureFailure = error.message ?: "Could not start recording."
            noteId?.let { runCatching { store.state(it, "ERROR", captureFailure!!) } }
            RecordingState.live.value = LiveSession(message = captureFailure!!)
            stopSelf()
        }
    }

    private fun interruptInput() = synchronized(inputLock) { runCatching { input?.stop() }; inputRunning = false }
    private fun microphoneInterrupted() {
        captureFailure = "Android muted the microphone, possibly because of a call or another app. Saved audio is safe."
        stopping.set(true)
        interruptInput()
    }

    private sealed interface CaptureEvent {
        data class Begin(val startMs: Long, val overlap: Boolean) : CaptureEvent
        data class End(val endMs: Long) : CaptureEvent
        data class Duration(val ms: Long) : CaptureEvent
        data class State(val name: String) : CaptureEvent
    }

    /** Scalar metadata only: capture never waits for SQLite or queues audio in memory. */
    private inner class CaptureMetadata(private val id: String) {
        private val events = Channel<CaptureEvent>(
            if (BuildConfig.DEBUG) metadataCapacityForTests?.coerceIn(1, METADATA_CAPACITY) ?: METADATA_CAPACITY else METADATA_CAPACITY
        )
        private val failed = AtomicBoolean(false)
        private val unpublishedFrom = AtomicLong(Long.MAX_VALUE)
        @Volatile private var lastPublishedEndMs = 0L
        private val job = app.scope.launch(Dispatchers.IO) {
            var chunkId: String? = null
            var activeStart: Long? = null
            try {
                for (event in events) {
                    when (event) {
                        is CaptureEvent.Begin -> {
                            activeStart = event.startMs
                            chunkId = store.beginChunk(id, event.startMs, event.overlap)
                        }
                        is CaptureEvent.End -> {
                            store.finishChunk(checkNotNull(chunkId) { "Missing audio range metadata." }, event.endMs)
                            lastPublishedEndMs = maxOf(lastPublishedEndMs, event.endMs)
                            chunkId = null; activeStart = null
                        }
                        is CaptureEvent.Duration -> store.duration(id, event.ms)
                        is CaptureEvent.State -> store.state(id, event.name)
                    }
                }
            } catch (_: Exception) {
                failed.set(true)
                activeStart?.let { start -> unpublishedFrom.accumulateAndGet(start, { a, b -> minOf(a, b) }) }
                captureFailure = captureFailure ?: "Recording metadata could not be saved. Original audio is kept for recovery."
                stopping.set(true); interruptInput()
                events.close()
                while (true) {
                    val event = events.tryReceive().getOrNull() ?: break
                    if (event is CaptureEvent.Begin) unpublishedFrom.accumulateAndGet(event.startMs, { a, b -> minOf(a, b) })
                }
            }
        }

        fun enqueue(event: CaptureEvent) {
            if (events.trySend(event).isFailure) {
                failed.set(true)
                // A rejected scalar update is not a rejected audio range. Use the final
                // published watermark after drain; only an unqueued Begin pins an earlier offset.
                if (event is CaptureEvent.Begin) unpublishedFrom.accumulateAndGet(event.startMs, { a, b -> minOf(a, b) })
                error("Recording stopped because saved-audio metadata could not keep up. Original audio is kept for recovery.")
            }
        }

        suspend fun finish() { events.close(); job.join() }
        fun recoveryFromMs(): Long? = if (failed.get()) minOf(lastPublishedEndMs, unpublishedFrom.get()) else null
    }

    @Suppress("MissingPermission", "UsableSpace")
    private suspend fun captureAudio(id: String) {
        var device: RecordingInput? = null
        var stream: BufferedOutputStream? = null
        var fileStream: FileOutputStream? = null
        var samplesWritten = 0L
        val frame = ShortArray(SpeechGate.FRAME_SAMPLES)
        var filled = 0
        val metadata = CaptureMetadata(id)
        val gate = SpeechGate(object : SpeechGate.Sink {
            override fun begin(startSample: Long, overlap: Boolean) { metadata.enqueue(CaptureEvent.Begin(startSample / 16, overlap)) }
            override fun audio(samples: ShortArray) = Unit
            override fun end(endSample: Long) {
                stream?.flush(); fileStream?.fd?.sync()
                metadata.enqueue(CaptureEvent.End(endSample / 16))
            }
        })
        try {
            check(filesDir.usableSpace > MIN_FREE_BYTES) { "Storage is almost full. Free some space before recording." }
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone access is required." }
            val raw = FileOutputStream(store.audioFile(id))
            fileStream = raw
            val buffered = BufferedOutputStream(raw, 16384)
            stream = buffered
            val current = if (BuildConfig.DEBUG) inputFactoryForTests?.invoke() ?: DeviceInput() else DeviceInput()
            device = current
            check(current.initialized) { "The microphone is unavailable. Close other recording apps and try again." }
            synchronized(inputLock) { input = current }
            var acknowledgedPause = false
            var lastUi = 0L
            var lastSync = SystemClock.elapsedRealtime()
            val bytes = ByteArray(frame.size * 2)
            fun syncAudio() {
                buffered.flush(); raw.fd.sync()
                metadata.enqueue(CaptureEvent.Duration(samplesWritten / 16))
            }
            while (currentCoroutineContext().isActive && !stopping.get()) {
                if (paused.get()) {
                    if (!acknowledgedPause) {
                        synchronized(inputLock) { current.stop(); inputRunning = false }
                        if (filled > 0) { gate.acceptPartial(frame, filled); filled = 0 }
                        gate.flush(); syncAudio()
                        captureAwake.set(false); reconcileWake()
                        metadata.enqueue(CaptureEvent.State("PAUSED"))
                        RecordingState.live.update { if (it.noteId == id) it.copy(paused = true, duration = samplesWritten / 16, level = 0f, message = "Paused") else it }
                        updateNotification()
                        acknowledgedPause = true
                    }
                    delay(30); continue
                }
                if (!inputRunning) {
                    synchronized(inputLock) {
                        if (!stopping.get() && !paused.get()) { current.start(); inputRunning = true }
                    }
                    if (!inputRunning) continue
                    captureAwake.set(true); reconcileWake()
                    acknowledgedPause = false
                    metadata.enqueue(CaptureEvent.State("RECORDING"))
                    RecordingState.live.update { if (it.noteId == id) it.copy(paused = false, message = if (decodeFailure == null) "Listening" else "Transcription paused · audio is still being saved") else it }
                    updateNotification()
                }
                check(!current.silenced) { "Android muted the microphone, possibly because of a call or another app. Saved audio is safe." }
                val count = current.read(frame, filled, frame.size - filled)
                if (count <= 0) {
                    if (stopping.get() || paused.get() || !inputRunning) continue
                    error("Microphone disconnected. Saved audio is safe.")
                }
                check(count <= frame.size - filled) { "The microphone returned an invalid audio buffer." }
                repeat(count) { index ->
                    val sample = frame[filled + index].toInt()
                    bytes[index * 2] = sample.toByte(); bytes[index * 2 + 1] = (sample shr 8).toByte()
                }
                buffered.write(bytes, 0, count * 2)
                samplesWritten += count
                filled += count
                if (filled == frame.size) { gate.accept(frame); filled = 0 }
                // A successful read belongs to the original, even if Android silenced
                // the microphone while that read was in flight. Persist it before stopping.
                check(!current.silenced) { "Android muted the microphone, possibly because of a call or another app. Saved audio is safe." }
                val now = SystemClock.elapsedRealtime()
                if (now - lastUi >= 100) {
                    RecordingState.live.update { if (it.noteId == id) it.copy(duration = samplesWritten / 16, level = gate.level) else it }
                    lastUi = now
                }
                if (now - lastSync >= 1000) {
                    syncAudio(); lastSync = now
                    check(filesDir.usableSpace > MIN_FREE_BYTES) { "Storage is almost full. Recording stopped; your saved audio is safe." }
                    check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone access was removed. Saved audio is safe." }
                }
            }
        } catch (error: Exception) {
            if (error !is CancellationException) captureFailure = error.message ?: "Recording was interrupted."
        } finally {
            synchronized(inputLock) {
                runCatching { device?.stop() }
                runCatching { device?.close() }
                if (input === device) { input = null; inputRunning = false }
            }
            // Finish the gate using exactly the real partial tail; no zero padding or lost samples.
            try {
                if (filled in 1 until frame.size) { gate.acceptPartial(frame, filled); filled = 0 }
                gate.flush()
            } catch (error: Exception) { captureFailure = error.message ?: "Could not finish saving audio." }
            try { stream?.flush(); fileStream?.fd?.sync() }
            catch (error: Exception) { captureFailure = error.message ?: "Could not finish saving audio." }
            try { stream?.close() }
            catch (error: Exception) { captureFailure = error.message ?: "Could not close saved audio." }
            ownership?.finishCapture()
            RecordingState.live.update { if (it.noteId == id) it.copy(processing = true, paused = false, level = 0f, message = captureFailure ?: "Finishing transcript…") else it }
            // The microphone and original file are closed before waiting on any database work.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    try { foreground("Finishing transcript…", true) }
                    catch (_: RuntimeException) {
                        decodeFailure = "Saved audio needs transcription. Open the note to continue."
                        stopSelf()
                    }
                    metadata.finish()
                    val recoveryFrom = metadata.recoveryFromMs()
                    if (captureFailure != null || recoveryFrom != null) store.recover(id, tailFromMs = recoveryFrom)
                    val duration = store.audioFile(id).length() / 32
                    store.duration(id, duration)
                    store.state(id, "PROCESSING", captureFailure.orEmpty())
                    RecordingState.live.update { if (it.noteId == id) it.copy(duration = duration) else it }
                } catch (error: Exception) {
                    captureFailure = error.message ?: "Could not finish saving audio."
                } finally {
                    captureAwake.set(false); reconcileWake()
                    // Publish after the metadata actor has drained, including the final queued range.
                    recordingDone = true
                }
            }
        }
    }

    private suspend fun processQueue(id: String, language: String) {
        try {
            try {
                val testFactory = if (BuildConfig.DEBUG) decoderFactoryForTests else null
                if (testFactory == null) app.initialized.await()
                decoderAwake.set(true); reconcileWake()
                val engine = try {
                    testFactory?.invoke() ?: deviceDecoder()
                } finally { decoderAwake.set(false); reconcileWake() }
                transcriber = engine
                engine.use {
                    while (currentCoroutineContext().isActive) {
                        val finished = recordingDone
                        val chunk = store.nextChunk(id)
                        if (chunk == null && finished) break
                        if (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                            RecordingState.live.update { if (it.noteId == id) it.copy(message = "Cooling down · audio is saved") else it }
                            delay(3000); continue
                        }
                        if (chunk == null) { delay(150); continue }
                        decoderAwake.set(true); reconcileWake()
                        try {
                            RecordingState.live.update { if (it.noteId == id) it.copy(message = if (recordingDone) "Finishing transcript…" else "Recording · transcribing") else it }
                            val start = SystemClock.elapsedRealtime()
                            val warm = Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
                            val threads = DecodePolicy.threads(Runtime.getRuntime().availableProcessors(), power.isPowerSaveMode, warm)
                            engine.transcribe(store, chunk, language, threads)
                            RecordingState.live.update { if (it.noteId == id) it.copy(pending = store.pendingCount(id)) else it }
                            val cooldown = DecodePolicy.cooldownMs(SystemClock.elapsedRealtime() - start, power.isPowerSaveMode, warm)
                            if (cooldown > 0 && store.nextChunk(id) != null) delay(cooldown)
                        } finally { decoderAwake.set(false); reconcileWake() }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                decodeFailure = error.message ?: "Transcription paused. Saved audio can be retried."
                RecordingState.live.update { if (it.noteId == id) it.copy(message = "Transcription paused · audio is still being saved") else it }
                // Decoder failure never ends healthy capture; chunks remain durable and pending.
                capture?.join()
            }
            val problem = captureFailure ?: decodeFailure
            decoderAwake.set(true); reconcileWake()
            store.state(id, if (problem == null) "READY" else "INTERRUPTED", problem.orEmpty())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            decodeFailure = error.message ?: "Saved audio can be transcribed when storage is available."
            runCatching { store.state(id, "INTERRUPTED", captureFailure ?: decodeFailure!!) }
        } finally {
            transcriber = null
            decoderAwake.set(false); reconcileWake()
            // Keep live ownership until onDestroy has joined capture/worker cleanup.
            stopSelf()
        }
    }

    private fun deviceDecoder(): RecordingDecoder {
        check(app.models.ready()) { "Audio is saved. Add the speech model to transcribe this note." }
        val engine = Transcriber(app.models.model.absolutePath)
        return object : RecordingDecoder {
            override suspend fun transcribe(store: NoteStore, chunk: Chunk, language: String, threads: Int) = engine.transcribe(store, chunk, language, threads)
            override fun cancel() = engine.cancel()
            override fun close() = engine.close()
        }
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
                        if (!closed && configs.any { it.isClientSilenced } && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) microphoneInterrupted()
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

    private fun notification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java).putExtra(NOTE, noteId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Focal")
            .setContentText(text).setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (!recordingDone && !RecordingState.live.value.processing) {
            fun action(name: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecordingService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, if (paused.get()) "Resume" else "Pause", action(if (paused.get()) RESUME else PAUSE, 1))
            builder.addAction(0, "Finish", action(STOP, 2))
        }
        return builder.build()
    }
    @Synchronized private fun reconcileWake(renew: Boolean = false) {
        wakeLock?.let { lock ->
            val wanted = captureAwake.get() || decoderAwake.get()
            if (lock.isHeld && (!wanted || renew)) runCatching { lock.release() }
            if (wanted && !lock.isHeld) lock.acquire(35 * 60_000L)
        }
    }
    private fun foreground(text: String, processing: Boolean) = synchronized(notificationLock) {
        if (destroyed) return@synchronized
        val type = when {
            processing && Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            processing && Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(text), type)
        else startForeground(NOTIFICATION, notification(text))
    }
    private fun updateNotification() = synchronized(notificationLock) {
        if (destroyed) return@synchronized
        val live = RecordingState.live.value
        val text = when {
            live.processing -> "Finishing transcript · ${live.pending} parts left"
            live.paused -> "Paused · ${Transcript.time(live.duration)}"
            else -> "Recording · ${Transcript.time(live.duration)}"
        }
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text)) }
        catch (_: SecurityException) { /* Notification permission may be removed during capture. */ }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        decodeFailure = "Android stopped background processing. Open the note to continue."
        stopping.set(true); interruptInput(); transcriber?.cancel()
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
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
            }
        }
        stopping.set(true); interruptInput()
        // Prevent a worker that is still opening its engine from starting a decode
        // after teardown has already looked for an engine to cancel.
        scope.cancel()
        transcriber?.cancel()
        app.scope.launch {
            try {
                startup?.join(); capture?.join(); worker?.join()
                noteId?.let { id ->
                    var cleanupMessage = ""
                    try {
                        if (store.note(id)?.state in listOf("RECORDING", "PAUSED", "PROCESSING"))
                            store.state(id, "INTERRUPTED", captureFailure ?: decodeFailure ?: "Recording stopped. Saved audio can be transcribed.")
                    } catch (_: Exception) {
                        cleanupMessage = "Recording stopped. Reopen Focal to recover the saved audio."
                    } finally {
                        if (RecordingState.live.value.noteId == id) RecordingState.live.value = LiveSession(message = cleanupMessage)
                        ownership?.release()
                    }
                }
            } finally {
                captureAwake.set(false); decoderAwake.set(false); reconcileWake()
                captureDispatcher.close(); speechDispatcher.close()
                ownership?.release()
            }
        }
        super.onDestroy()
    }
    companion object {
        const val START = "app.focal.START"
        const val STOP = "app.focal.STOP"
        const val PAUSE = "app.focal.PAUSE"
        const val RESUME = "app.focal.RESUME"
        const val RECOVER = "app.focal.RECOVER"
        const val NOTE = "note"
        private const val OPERATION = "operation"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 1
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val METADATA_CAPACITY = 64
        @Volatile internal var inputFactoryForTests: (() -> RecordingInput)? = null
        @Volatile internal var decoderFactoryForTests: (() -> RecordingDecoder)? = null
        @Volatile internal var metadataCapacityForTests: Int? = null
        fun command(context: Context, action: String, id: String? = null) {
            require(action != RECOVER || !id.isNullOrBlank()) { "Choose an existing note to continue transcription." }
            if (action == START && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Microphone access is required.")
            val intent = Intent(context, RecordingService::class.java).setAction(action).putExtra(NOTE, id)
            if (action == START || action == RECOVER) {
                val capture = action == START
                val claim = SessionOperations.reserveSession(capture, if (capture) null else id)
                if (claim == null) {
                    if (SessionOperations.matchesSession(capture, id)) return
                    error("This session is busy. Finish its current operation before recording or transcribing.")
                }
                intent.putExtra(OPERATION, claim.token)
                try { ContextCompat.startForegroundService(context, intent) }
                catch (error: Exception) { claim.release(); throw error }
            } else context.startService(intent)
        }
    }
}
