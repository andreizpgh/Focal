package app.focal.speech

import android.content.Context
import android.media.*
import app.focal.data.NoteStore
import app.focal.data.Passage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.RandomAccessFile

class SpeechPlayer(private val context: Context, private val store: NoteStore, private val scope: CoroutineScope) {
    val playing = MutableStateFlow<Long?>(null)
    private var job: Job? = null
    fun stop() { job?.cancel(); job = null; playing.value = null }
    fun play(passage: Passage, onError: (String) -> Unit) {
        if (playing.value == passage.id) { stop(); return }
        val previous = job
        previous?.cancel()
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            var track: AudioTrack? = null
            val manager = context.getSystemService(AudioManager::class.java)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change -> if (change < 0) stop() }.build()
            try {
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio playback is unavailable right now." }
                val file = store.audioFile(passage.noteId)
                check(file.exists()) { "Audio has been removed from this note." }
                val start = ((passage.start - 250).coerceAtLeast(0) * 32).coerceAtMost(file.length())
                val end = ((passage.end + 350) * 32).coerceIn(start, file.length())
                track = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(8192, AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
                playing.value = passage.id
                track.play()
                var written = 0
                RandomAccessFile(file, "r").use { input ->
                    input.seek(start)
                    val buffer = ByteArray(4096)
                    while (isActive && input.filePointer < end) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), end - input.filePointer).toInt())
                        if (count <= 0) break
                        var offset = 0
                        while (isActive && offset < count) {
                            val n = track.write(buffer, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(n >= 0) { "Audio output was disconnected." }
                            offset += n; written += n
                            if (n == 0) delay(10)
                        }
                    }
                }
                while (isActive && track.playbackHeadPosition < written / 2) delay(20)
            } catch (e: Exception) {
                if (e !is CancellationException) onError(e.message ?: "Could not play audio.")
            } finally {
                try { track?.stop() } catch (_: IllegalStateException) { }
                track?.release(); manager.abandonAudioFocusRequest(focus)
                playing.value = null
            }
        }
    }
}
