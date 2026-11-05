package app.focal.speech

import app.focal.core.Transcript
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.data.fitsPassageDraftBudget
import app.focal.recording.RecordingDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Construct, transcribe and close on the service's single speech worker. Accepts any saved
 * range of at most 30 s, including PENDING ranges of notes recorded before Ultra.
 */
internal class UltraTranscriber(
    private val openDecoder: (threads: Int, checkCancelled: () -> Unit) -> SpeechDecoder,
) : RecordingDecoder {
    private val owner = Thread.currentThread()
    private val cancelled = AtomicBoolean(false)
    private var decoder: SpeechDecoder? = null
    private var closed = false

    override suspend fun transcribe(store: NoteStore, chunk: Chunk, threads: Int) {
        checkOwner()
        check(!closed) { "Speech engine is not open." }
        val context = currentCoroutineContext()
        fun checkCancelled() {
            context.ensureActive()
            if (cancelled.get()) throw CancellationException("Ultra transcription stopped.")
        }

        checkCancelled()
        val pcm = store.readChunk(chunk)
        check(pcm.size in 2..MAX_PCM_BYTES && pcm.size % 2 == 0) { "Saved audio is missing or damaged." }
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(buffer.remaining()) { buffer.get() / 32768f }
        checkCancelled()
        if (samples.all { it == samples[0] }) {
            checkCancelled()
            store.complete(chunk, emptyList())
            return
        }

        // The native thread count is fixed at recognizer creation. Keep one recognizer per session.
        val engine = decoder ?: openDecoder(threads.coerceIn(1, 2), ::checkCancelled).also { decoder = it }
        checkCancelled()
        val text = engine.decode(samples).text
        checkCancelled()
        check(fitsPassageDraftBudget(text)) { "Ultra transcription is too long for one passage." }

        val overlapEnd = if (chunk.overlap) store.previousChunkEnd(chunk) else null
        val recent = if (overlapEnd == null) emptyList() else store.recentPassages(chunk.noteId, overlapEnd).map {
            Transcript.Segment(it.start, it.end, it.text, it.uncertain && !it.reviewed)
        }
        // TDT token emission times do not give reliable word ends. Keep the exact source span.
        val decoded = listOf(Transcript.Segment(chunk.start, chunk.end, text))
        val passages = Transcript.reconcileBoundary(recent, decoded, chunk.start, chunk.end, overlapEnd).map {
            Passage(noteId = chunk.noteId, chunkId = chunk.id, start = it.start,
                end = it.end, text = it.text, uncertain = it.uncertain)
        }
        checkCancelled()
        store.complete(chunk, passages)
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

    private companion object {
        const val MAX_PCM_BYTES = 30 * 16_000 * 2
    }
}
