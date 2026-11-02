package app.focal.speech

import app.focal.core.Transcript
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.data.Passage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Transcriber(model: String) : AutoCloseable {
    private var handle = NativeWhisper.open(model)
    @Synchronized fun cancel() { if (handle != 0L) NativeWhisper.cancel(handle) }
    @Synchronized private fun activeHandle(): Long = handle.also { check(it != 0L) { "Speech engine is not open." } }

    suspend fun transcribe(store: NoteStore, chunk: Chunk, language: String, threads: Int) {
        currentCoroutineContext().ensureActive()
        val pcm = store.readChunk(chunk)
        check(pcm.size in 2..(30 * 16000 * 2) && pcm.size % 2 == 0) { "Saved audio is missing or damaged." }
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(buffer.remaining()) { buffer.get() / 32768f }
        val overlapEnd = if (chunk.overlap) store.previousChunkEnd(chunk) else null
        val recent = store.recentPassages(chunk.noteId, overlapEnd ?: chunk.start).map {
            Transcript.Segment(it.start, it.end, it.text, it.uncertain && !it.reviewed)
        }
        val context = Transcript.trustedContext(recent, chunk.start)
        val json = JSONArray(NativeWhisper.decode(activeHandle(), samples, language, threads, context).decodeToString())
        currentCoroutineContext().ensureActive()
        val duration = pcm.size / 32L
        val decoded = buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val text = item.getString("text").trim()
                if (text.isBlank()) continue
                val probs = item.getJSONArray("probabilities")
                val uncertain = Transcript.needsReview(List(probs.length()) { probs.getDouble(it).toFloat() }, item.getDouble("noSpeech").toFloat())
                val start = item.getLong("start")
                val end = item.getLong("end")
                // Short audio is zero-padded by the decoder. Its padding cannot own text.
                if (start >= duration || end <= 0 || end <= start) continue
                add(Transcript.Segment(chunk.start + start.coerceAtLeast(0),
                    chunk.start + end.coerceAtMost(duration), text, uncertain))
            }
        }
        val passages = Transcript.reconcileBoundary(recent, decoded, chunk.start, chunk.end, overlapEnd).map {
            Passage(noteId = chunk.noteId, chunkId = chunk.id, start = it.start,
                end = it.end, text = it.text, uncertain = it.uncertain)
        }
        currentCoroutineContext().ensureActive()
        store.complete(chunk, passages)
    }
    @Synchronized override fun close() { if (handle != 0L) { NativeWhisper.close(handle); handle = 0 } }
}
