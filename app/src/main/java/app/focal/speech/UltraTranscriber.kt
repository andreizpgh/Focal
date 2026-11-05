package app.focal.speech

import app.focal.data.NoteStore
import app.focal.data.RawAsr
import app.focal.data.StoredWindow
import app.focal.data.ULTRA_ENGINE
import app.focal.recording.RecordingDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Construct, transcribe and close on the service's single speech worker. Decodes the audio range of one
 * persisted window and commits the raw result; the store turns it into paragraphs.
 */
internal class UltraTranscriber(
    private val openDecoder: (threads: Int, checkCancelled: () -> Unit) -> SpeechDecoder,
) : RecordingDecoder {
    private val owner = Thread.currentThread()
    private val cancelled = AtomicBoolean(false)
    private var decoder: SpeechDecoder? = null
    private var closed = false

    override suspend fun transcribe(store: NoteStore, window: StoredWindow, threads: Int) {
        checkOwner()
        check(!closed) { "Speech engine is not open." }
        val context = currentCoroutineContext()
        fun checkCancelled() {
            context.ensureActive()
            if (cancelled.get()) throw CancellationException("Ultra transcription stopped.")
        }

        checkCancelled()
        val pcm = store.readAudio(window.noteId, window.audioStartMs, window.audioEndMs)
        check(pcm.isNotEmpty()) { "Saved audio is missing or damaged." }
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        // The native thread count is fixed at recognizer creation. Keep one recognizer per session.
        val engine = decoder ?: openDecoder(threads, ::checkCancelled).also { decoder = it }
        checkCancelled()
        val result = engine.decode(samples)
        checkCancelled()
        store.commitDecoded(window.id, ULTRA_ENGINE, RawAsr(result.text, result.asrTokens()))
    }

    override fun cancel() { cancelled.set(true) }

    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        val opened = decoder
        decoder = null
        opened?.close()
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Use the speech engine on its owner thread." }
    }
}
