package app.focal.speech

import app.focal.core.DecodedWindow
import app.focal.core.SpeechTuning
import app.focal.core.WordBuilder
import app.focal.data.NoteStore
import app.focal.data.RawAsr
import app.focal.data.StoredWindow
import app.focal.data.ULTRA_ENGINE
import app.focal.recording.RecordingDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10

/**
 * Construct, prepare, transcribe and close on the service's single speech worker. [prepare] loads the
 * recognizer ahead of the first window; [transcribe] decodes the audio range of one persisted window and
 * commits the raw result, which the store turns into paragraphs.
 */
internal class UltraTranscriber(
    private val openDecoder: (threads: Int, checkCancelled: () -> Unit) -> SpeechDecoder,
) : RecordingDecoder {
    private val owner = Thread.currentThread()
    private val cancelled = AtomicBoolean(false)
    private var decoder: SpeechDecoder? = null
    private var closed = false

    override fun prepare(threads: Int) {
        checkOwner()
        check(!closed) { "Speech engine is not open." }
        if (decoder == null) decoder = openDecoder(threads) { if (cancelled.get()) throw CancellationException("Ultra transcription stopped.") }
    }

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
        // The native thread count is fixed at recognizer creation. Keep one recognizer per service session.
        val engine = decoder ?: openDecoder(threads, ::checkCancelled).also { decoder = it }
        checkCancelled()
        val result = engine.decode(samples)
        checkCancelled()
        store.commitDecoded(window.id, ULTRA_ENGINE, RawAsr(result.text, result.asrTokens()))
    }

    override suspend fun preview(store: NoteStore, noteId: String, startMs: Long, endMs: Long, threads: Int): String? {
        checkOwner()
        check(!closed) { "Speech engine is not open." }
        val context = currentCoroutineContext()
        fun checkCancelled() {
            context.ensureActive()
            if (cancelled.get()) throw CancellationException("Ultra transcription stopped.")
        }

        checkCancelled()
        val pcm = store.readAudio(noteId, startMs, endMs)
        if (pcm.isEmpty()) return null
        var peak = 0
        for (sample in pcm) {
            val level = if (sample == Short.MIN_VALUE) 32768 else abs(sample.toInt())
            if (level > peak) peak = level
        }
        if (peak <= 0 || 20.0 * log10(peak / 32768.0) < SpeechTuning.SILENCE_PEAK_DBFS) return null
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        val engine = decoder ?: openDecoder(threads, ::checkCancelled).also { decoder = it }
        checkCancelled()
        val result = engine.decode(samples)
        checkCancelled()
        val words = WordBuilder.build(DecodedWindow(startMs, null, null, result.asrTokens()), 0)
        return words.joinToString(" ") { it.text }.trim().takeIf { it.isNotEmpty() }
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
