package app.focal.speech

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.focal.data.Note
import app.focal.data.NoteStore
import app.focal.data.Paragraph
import app.focal.recording.SessionOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.RandomAccessFile

/** Positions are absolute milliseconds in the original, mono PCM16 recording. */
data class PlaybackState(
    val noteId: String? = null,
    val passageId: Long? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false
)

class SpeechPlayer(private val context: Context, private val store: NoteStore, private val scope: CoroutineScope) {
    val playback = MutableStateFlow(PlaybackState())
    // The existing passage editor uses -1 for the whole recording.
    val playing = MutableStateFlow<Long?>(null)

    private val lock = Any()
    private val outputMutex = Mutex()
    private var generation = 0L
    private var job: Job? = null
    private var active: Session? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private var playbackLease: SessionOperations.PlaybackLease? = null
    private var source: Source? = null
    private var requestedPlaying = false

    private data class Source(val noteId: String, val passageId: Long?, val startMs: Long = 0, val endMs: Long? = null)
    private class Session(
        val generation: Long,
        val source: Source,
        val track: AudioTrack,
        val startFrame: Long,
        val durationMs: Long
    ) {
        var submittedBytes = 0L
        var lastHead = 0L
        var headWraps = 0L

        fun playedFrames(): Long {
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (head < lastHead) headWraps += 1L shl 32
            lastHead = head
            return (headWraps + head).coerceAtMost(submittedBytes / BYTES_PER_FRAME)
        }

        fun positionMs(): Long = ((startFrame + playedFrames()) * 1000 / SAMPLE_RATE).coerceAtMost(durationMs)
    }

    fun stop() = synchronized(lock) {
        freezeLocked()
        generation++
        unregisterNoisyReceiverLocked()
        releasePlaybackLeaseLocked()
        requestedPlaying = false
        source = null
        active = null
        job?.cancel()
        job = null
        playing.value = null
        playback.value = PlaybackState()
    }

    /** Clears a note's player state without stopping a newer selection. */
    fun stopNote(noteId: String) = synchronized(lock) {
        if (source?.noteId == noteId) stop()
    }

    /** Pauses without clearing the note, excerpt, or absolute playback position. */
    fun pause() = synchronized(lock) { pauseLocked() }

    /** Repeating a paragraph pauses/resumes it without losing its absolute position. */
    fun play(paragraph: Paragraph, onError: (String) -> Unit) {
        if (paragraph.id == -1L) {
            playWhole(paragraph.noteId, onError)
            return
        }
        synchronized(lock) {
            val next = Source(paragraph.noteId, paragraph.id, (paragraph.startMs.coerceAtLeast(0) - 250).coerceAtLeast(0),
                paragraph.endMs.coerceAtLeast(0).let { if (it > Long.MAX_VALUE - 350) Long.MAX_VALUE else it + 350 })
            if (source == next && requestedPlaying) {
                pauseLocked()
                return
            }
            val state = playback.value
            val end = minOf(next.endMs ?: Long.MAX_VALUE, state.durationMs)
            val position = if (source == next && state.positionMs < end) state.positionMs else next.startMs
            startLocked(next, position, onError)
        }
    }

    /** Toggles whole-recording playback, retaining the actual played position while paused. */
    fun playNote(note: Note, onError: (String) -> Unit) = playWhole(note.id, onError)

    private fun playWhole(noteId: String, onError: (String) -> Unit) = synchronized(lock) {
        val next = Source(noteId, null)
        if (source == next && requestedPlaying) {
            pauseLocked()
            return@synchronized
        }
        freezeLocked()
        val state = playback.value
        val position = if (state.noteId == noteId && state.positionMs < state.durationMs) state.positionMs else 0
        startLocked(next, position, onError)
    }

    /** Seeking exits excerpt playback and starts the original recording at the clamped time. */
    fun seekTo(note: Note, positionMs: Long, onError: (String) -> Unit) = synchronized(lock) {
        startLocked(Source(note.id, null), positionMs.coerceAtLeast(0), onError)
    }

    private fun freezeLocked() {
        val session = active?.takeIf { it.generation == generation } ?: return
        try {
            session.track.pause()
            playback.value = playback.value.copy(positionMs = session.positionMs(), isPlaying = false)
        } catch (_: IllegalStateException) {
            playback.value = playback.value.copy(isPlaying = false)
        }
    }

    private fun pauseLocked() {
        freezeLocked()
        generation++
        unregisterNoisyReceiverLocked()
        releasePlaybackLeaseLocked()
        active = null
        requestedPlaying = false
        job?.cancel()
        job = null
        playing.value = null
        playback.value = playback.value.copy(isPlaying = false)
    }

    private fun pauseIfCurrent(token: Long) = synchronized(lock) {
        if (generation == token) pauseLocked()
    }

    private fun stopIfCurrent(token: Long) = synchronized(lock) {
        if (generation == token) stop()
    }

    private fun startLocked(next: Source, positionMs: Long, onError: (String) -> Unit) {
        freezeLocked()
        job?.cancel()
        val token = ++generation
        unregisterNoisyReceiverLocked()
        releasePlaybackLeaseLocked()
        active = null
        source = next
        requestedPlaying = false
        playing.value = null
        val knownDuration = playback.value.takeIf { it.noteId == next.noteId }?.durationMs ?: 0
        playback.value = PlaybackState(next.noteId, next.passageId, positionMs.coerceIn(0, knownDuration), knownDuration)
        val lease = SessionOperations.tryAcquirePlayback(next.noteId) { stopIfCurrent(token) }
        if (lease == null) {
            onError("Audio is unavailable while recording or removing this session.")
            return
        }
        playbackLease = lease
        requestedPlaying = true
        // A cancelled intermediate request cannot bypass the prior owner’s resource cleanup.
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            outputMutex.withLock { playSource(token, next, positionMs, onError) }
        }.also { it.start() }
    }

    private fun checkCurrentLocked(token: Long) {
        if (generation != token || !requestedPlaying) throw CancellationException()
    }

    private fun unregisterNoisyReceiverLocked() {
        noisyReceiver?.let { context.unregisterReceiver(it) }
        noisyReceiver = null
    }

    private fun releasePlaybackLeaseLocked() {
        playbackLease?.release()
        playbackLease = null
    }

    private fun publishLocked(session: Session) {
        checkCurrentLocked(session.generation)
        playback.value = PlaybackState(session.source.noteId, session.source.passageId,
            session.positionMs(), session.durationMs, true)
    }

    private suspend fun playSource(token: Long, next: Source, requestedMs: Long, onError: (String) -> Unit) {
        var track: AudioTrack? = null
        var session: Session? = null
        var manager: AudioManager? = null
        var focus: AudioFocusRequest? = null
        val routeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseIfCurrent(token)
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val audioManager = context.getSystemService(AudioManager::class.java)
            manager = audioManager
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ change -> if (change < 0) pauseIfCurrent(token) }, Handler(Looper.getMainLooper()))
                .build()
            focus = focusRequest
            val file = store.audioFile(next.noteId)
            check(file.isFile) { "Audio has been removed from this note." }
            val totalFrames = file.length() / BYTES_PER_FRAME
            check(totalFrames > 0) { "This note has no saved audio to play." }
            val durationMs = totalFrames * 1000 / SAMPLE_RATE
            val rangeStart = next.startMs.coerceIn(0, durationMs) * SAMPLE_RATE / 1000
            val rangeEnd = next.endMs?.coerceIn(0, durationMs)?.let { it * SAMPLE_RATE / 1000 } ?: totalFrames
            val clampedMs = requestedMs.coerceIn(0, durationMs)
            val requestedFrame = if (clampedMs == durationMs) totalFrames else clampedMs * SAMPLE_RATE / 1000
            val startFrame = requestedFrame.coerceIn(rangeStart, maxOf(rangeStart, rangeEnd))
            synchronized(lock) {
                checkCurrentLocked(token)
                playback.value = PlaybackState(next.noteId, next.passageId, startFrame * 1000 / SAMPLE_RATE, durationMs)
                if (startFrame >= rangeEnd) requestedPlaying = false
            }
            if (startFrame >= rangeEnd) return
            // The route can change without an audio-focus loss, for example when
            // headphones disconnect. Stop before private speech reaches a speaker.
            synchronized(lock) {
                checkCurrentLocked(token)
                ContextCompat.registerReceiver(context, routeReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                noisyReceiver = routeReceiver
            }
            check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Audio playback is unavailable right now."
            }
            currentCoroutineContext().ensureActive()
            val minimumBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimumBuffer in 1..MAX_OUTPUT_BUFFER_BYTES) { "Audio output is unavailable on this device." }
            val output = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(8192, minimumBuffer)).build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "Audio output could not be initialized." }
            val current = Session(token, next, output, startFrame, durationMs)
            session = current
            synchronized(lock) {
                checkCurrentLocked(token)
                active = current
                output.play()
                playback.value = PlaybackState(next.noteId, next.passageId, startFrame * 1000 / SAMPLE_RATE, durationMs, true)
                playing.value = next.passageId ?: -1L
            }
            RandomAccessFile(file, "r").use { input ->
                input.seek(startFrame * BYTES_PER_FRAME)
                val endByte = rangeEnd * BYTES_PER_FRAME
                val buffer = ByteArray(4096)
                while (input.filePointer < endByte) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), endByte - input.filePointer).toInt())
                    check(count > 0 && count % BYTES_PER_FRAME == 0) { "Saved audio ended unexpectedly." }
                    var offset = 0
                    while (offset < count) {
                        currentCoroutineContext().ensureActive()
                        val written = synchronized(lock) {
                            checkCurrentLocked(token)
                            val result = output.write(buffer, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(result >= 0) { "Audio output was disconnected." }
                            current.submittedBytes += result
                            publishLocked(current)
                            result
                        }
                        offset += written
                        if (written == 0) delay(10)
                    }
                }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val drained = synchronized(lock) {
                    publishLocked(current)
                    current.playedFrames() >= current.submittedBytes / BYTES_PER_FRAME
                }
                if (drained) break
                delay(20)
            }
        } catch (error: Exception) {
            if (error !is CancellationException) {
                val report = synchronized(lock) { generation == token && requestedPlaying }
                if (report) onError(error.message ?: "Could not play audio.")
            }
        } finally {
            synchronized(lock) {
                if (generation == token) {
                    val position = session?.let { runCatching { it.positionMs() }.getOrNull() } ?: playback.value.positionMs
                    playback.value = playback.value.copy(positionMs = position, isPlaying = false)
                    playing.value = null
                    requestedPlaying = false
                    releasePlaybackLeaseLocked()
                }
                if (noisyReceiver === routeReceiver) unregisterNoisyReceiverLocked()
                if (active === session) active = null
            }
            try { track?.stop() } catch (_: IllegalStateException) { }
            track?.release()
            if (manager != null && focus != null) manager.abandonAudioFocusRequest(focus)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val BYTES_PER_FRAME = 2
        const val MAX_OUTPUT_BUFFER_BYTES = 65536
    }
}
