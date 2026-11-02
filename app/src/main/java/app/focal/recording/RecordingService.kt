package app.focal.recording

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.focal.FocalApplication
import app.focal.MainActivity
import app.focal.R
import app.focal.core.SpeechGate
import app.focal.core.Transcript
import app.focal.speech.Transcriber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class LiveSession(val noteId: String? = null, val paused: Boolean = false, val processing: Boolean = false,
                       val duration: Long = 0, val level: Float = 0f, val message: String = "", val pending: Int = 0)
object RecordingState { val live = MutableStateFlow(LiveSession()) }

/** The activity is only a controller. This microphone foreground service owns capture. */
class RecordingService : Service() {
    private val app get() = application as FocalApplication
    private val store get() = app.store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); task.run() }, "focal-capture")
    }.asCoroutineDispatcher()
    private val speechDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "focal-speech")
    }.asCoroutineDispatcher()
    private val stopping = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    @Volatile private var recordingDone = false
    @Volatile private var noteId: String? = null
    @Volatile private var transcriber: Transcriber? = null
    @Volatile private var failure: String? = null
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
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
                started = true
                val recovery = intent.action == RECOVER
                // Start immediately to meet Android's foreground service deadline.
                try { foreground("Preparing…", recovery) }
                catch (_: SecurityException) {
                    RecordingState.live.value = LiveSession(message = "Allow microphone access and start recording while Focal is open.")
                    stopSelf(); return START_NOT_STICKY
                }
                scope.launch { begin(if (recovery) intent.getStringExtra(NOTE) else null) }
            }
            PAUSE -> if (!recordingDone && !stopping.get()) {
                paused.set(!paused.get())
                RecordingState.live.update { it.copy(paused = paused.get(), level = 0f) }
                noteId?.let { id -> scope.launch { store.state(id, if (paused.get()) "PAUSED" else "RECORDING") } }
                updateNotification()
            }
            STOP -> { stopping.set(true); paused.set(false) }
        }
        // Never silently reopen a microphone after process death. Saved chunks are recoverable.
        return START_NOT_STICKY
    }

    private suspend fun begin(recover: String?) {
        try {
            app.initialized.await()
            check(app.models.ready()) { "A speech model is required before recording." }
            val id = recover ?: store.create(app.preferences.settings.value.language)
            noteId = id
            val note = checkNotNull(store.note(id))
            RecordingState.live.value = LiveSession(id, processing = recover != null, duration = note.duration, message = "Preparing speech model…")
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Focal:recording").apply { acquire(35 * 60_000L) }
            recordingDone = recover != null
            if (recover != null) store.state(id, "PROCESSING")
            else capture = scope.launch(captureDispatcher) { captureAudio(id) }
            worker = scope.launch(speechDispatcher) { processQueue(id, note.language) }
            scope.launch {
                var ticks = 0
                while (isActive && RecordingState.live.value.noteId == id) {
                    delay(1000)
                    if (++ticks % (30 * 60) == 0) {
                        wakeLock?.let { if (it.isHeld) it.release(); it.acquire(35 * 60_000L) }
                    }
                    RecordingState.live.update { it.copy(pending = store.chunks(id).count { c -> c.state == "PENDING" }) }
                    updateNotification()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            noteId?.let { store.state(it, "ERROR", e.message ?: "Could not start recording.") }
            RecordingState.live.value = LiveSession(message = e.message ?: "Could not start recording.")
            stopSelf()
        }
    }

    // Conservatively stop before storage exhaustion; do not evict other apps' caches while recording.
    @Suppress("MissingPermission", "UsableSpace")
    private suspend fun captureAudio(id: String) {
        var recorder: AudioRecord? = null
        var stream: BufferedOutputStream? = null
        var fileStream: FileOutputStream? = null
        var chunkId: String? = null
        var frames = 0L
        val gate = SpeechGate(object : SpeechGate.Sink {
            override fun begin(startSample: Long, overlap: Boolean) {
                chunkId = store.beginChunk(id, startSample / 16, overlap)
            }
            override fun audio(samples: ShortArray) = Unit // The unfiltered original is already on disk.
            override fun end(endSample: Long) {
                stream?.flush(); fileStream?.fd?.sync()
                chunkId?.let { store.finishChunk(it, endSample / 16) }; chunkId = null
            }
        })
        try {
            fileStream = FileOutputStream(store.audioFile(id))
            stream = BufferedOutputStream(fileStream, 16384)
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone access is required." }
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "This device cannot record 16 kHz audio." }
            recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 4, 16000)).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone is unavailable. Close other recording apps and try again." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone could not start." }
            var wasPaused = false
            var lastUi = 0L
            val frame = ShortArray(SpeechGate.FRAME_SAMPLES)
            var filled = 0
            while (currentCoroutineContext().isActive && !stopping.get()) {
                if (paused.get()) {
                    if (!wasPaused) { recorder.stop(); gate.flush(); filled = 0; wasPaused = true }
                    delay(60); continue
                }
                if (wasPaused) { recorder.startRecording(); wasPaused = false }
                val count = recorder.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                check(count > 0) { "Microphone disconnected. Saved audio is safe." }
                filled += count
                if (filled < frame.size) continue
                filled = 0
                val bytes = ByteArray(frame.size * 2)
                frame.forEachIndexed { i, sample -> bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte() }
                stream.write(bytes)
                gate.accept(frame); frames++
                val now = SystemClock.elapsedRealtime()
                if (now - lastUi >= 100) {
                    RecordingState.live.update { it.copy(duration = frames * 20, level = gate.level) }
                    lastUi = now
                }
                if (frames % 50 == 0L) {
                    stream.flush(); fileStream.fd.sync()
                    store.duration(id, frames * 20)
                    check(filesDir.usableSpace > 64 * 1024 * 1024) { "Storage is almost full. Recording stopped; your saved audio is safe." }
                    if (Build.VERSION.SDK_INT >= 29) {
                        val own = recorder.activeRecordingConfiguration
                        check(own?.isClientSilenced != true) { "Android muted the microphone, possibly because another app is using it. Saved audio is safe." }
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) failure = e.message ?: "Recording was interrupted."
        } finally {
            try { recorder?.stop() } catch (_: IllegalStateException) { }
            recorder?.release()
            try { gate.flush() } catch (e: Exception) { failure = e.message ?: "Could not finish saving audio." }
            try { stream?.flush(); fileStream?.fd?.sync() } catch (_: Exception) { }
            try { stream?.close() } catch (_: Exception) { }
            store.duration(id, frames * 20)
            recordingDone = true
            store.state(id, "PROCESSING", failure.orEmpty())
            RecordingState.live.update { it.copy(processing = true, paused = false, level = 0f) }
            try { foreground("Finishing transcript…", true) } catch (_: Exception) { }
        }
    }

    private suspend fun processQueue(id: String, language: String) {
        try {
            val engine = Transcriber(app.models.model.absolutePath)
            transcriber = engine
            engine.use {
                while (currentCoroutineContext().isActive) {
                    if (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                        RecordingState.live.update { it.copy(message = "Cooling down · audio is saved") }
                        delay(3000); continue
                    }
                    val chunk = store.nextChunk(id)
                    if (chunk == null) {
                        if (recordingDone) break
                        RecordingState.live.update { it.copy(message = if (paused.get()) "Paused" else "Listening") }
                        delay(350); continue
                    }
                    RecordingState.live.update { it.copy(message = if (recordingDone) "Finishing transcript…" else "Recording · transcribing") }
                    val start = SystemClock.elapsedRealtime()
                    val threads = if (power.isPowerSaveMode || (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE)) 1 else 2
                    engine.transcribe(store, chunk, language, threads)
                    // Bound sustained duty cycle instead of chasing live text at any thermal cost.
                    delay(((SystemClock.elapsedRealtime() - start) / 2).coerceIn(150, 10_000))
                }
            }
            transcriber = null
            store.state(id, if (failure == null) "READY" else "INTERRUPTED", failure.orEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failure = e.message ?: "Transcription paused. Saved audio can be retried."
            stopping.set(true); paused.set(false)
            capture?.join()
            store.state(id, "ERROR", failure!!)
        } finally {
            transcriber = null
            RecordingState.live.value = LiveSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java).putExtra(NOTE, noteId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Focal")
            .setContentText(text).setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (!recordingDone) {
            fun action(name: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecordingService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, if (paused.get()) "Resume" else "Pause", action(PAUSE, 1))
            builder.addAction(0, "Finish", action(STOP, 2))
        }
        return builder.build()
    }
    private fun foreground(text: String, processing: Boolean) {
        val type = if (processing && Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(text), type)
        else startForeground(NOTIFICATION, notification(text))
    }
    private fun updateNotification() {
        val live = RecordingState.live.value
        val text = when {
            live.processing -> "Finishing transcript · ${live.pending} parts left"
            paused.get() -> "Paused · ${Transcript.time(live.duration)}"
            else -> "Recording · ${Transcript.time(live.duration)}"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        failure = "Android stopped background processing. Open the note to continue."
        transcriber?.cancel(); stopping.set(true)
        noteId?.let { id -> store.state(id, "INTERRUPTED", failure!!) }
        stopSelf()
    }
    override fun onDestroy() {
        stopping.set(true); transcriber?.cancel(); scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        captureDispatcher.close(); speechDispatcher.close()
        app.scope.launch {
            capture?.join(); worker?.join()
            noteId?.let { id ->
                if (store.note(id)?.state in listOf("RECORDING", "PAUSED", "PROCESSING"))
                    store.state(id, "INTERRUPTED", failure ?: "Recording stopped. Saved audio can be transcribed.")
                if (RecordingState.live.value.noteId == id) RecordingState.live.value = LiveSession()
            }
        }
        super.onDestroy()
    }
    companion object {
        const val START = "app.focal.START"
        const val STOP = "app.focal.STOP"
        const val PAUSE = "app.focal.PAUSE"
        const val RECOVER = "app.focal.RECOVER"
        const val NOTE = "note"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 1
        fun command(context: Context, action: String, id: String? = null) {
            val intent = Intent(context, RecordingService::class.java).setAction(action).putExtra(NOTE, id)
            if (action == START || action == RECOVER) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }
    }
}
