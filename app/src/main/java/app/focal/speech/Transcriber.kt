package app.focal.speech

import app.focal.core.Transcript
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.data.Passage
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Transcriber(model: String) : AutoCloseable {
    private var handle = NativeWhisper.open(model)
    @Synchronized fun cancel() { if (handle != 0L) NativeWhisper.cancel(handle) }
    fun transcribe(store: NoteStore, chunk: Chunk, language: String, threads: Int) {
        val pcm = store.readChunk(chunk)
        check(pcm.size in 2..(30 * 16000 * 2)) { "Saved audio is missing or damaged." }
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(buffer.remaining()) { buffer.get() / 32768f }
        val json = JSONArray(NativeWhisper.decode(handle, samples, language, threads).decodeToString())
        val passages = buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                var text = item.getString("text").trim()
                if (i == 0 && chunk.overlap) text = Transcript.removeOverlap(store.passages(chunk.noteId).lastOrNull()?.text.orEmpty(), text)
                if (text.isBlank()) continue
                val probs = item.getJSONArray("probabilities")
                val uncertain = Transcript.needsReview(List(probs.length()) { probs.getDouble(it).toFloat() }, item.getDouble("noSpeech").toFloat())
                val duration = pcm.size / 32L
                val start = item.getLong("start").coerceIn(0, duration)
                val end = item.getLong("end").coerceIn(start, duration)
                add(Passage(noteId = chunk.noteId, chunkId = chunk.id, start = chunk.start + start,
                    end = chunk.start + end, text = text, uncertain = uncertain))
            }
        }.ifEmpty {
            // Never silently discard a recorded utterance the decoder could not recognize.
            listOf(Passage(noteId = chunk.noteId, chunkId = chunk.id, start = chunk.start, end = chunk.end, text = "", uncertain = true))
        }
        store.complete(chunk, passages)
    }
    @Synchronized override fun close() { if (handle != 0L) { NativeWhisper.close(handle); handle = 0 } }
}
