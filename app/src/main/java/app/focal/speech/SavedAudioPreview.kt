package app.focal.speech

import app.focal.core.Transcript
import app.focal.data.MAX_PASSAGE_EDIT_BYTES
import java.io.File
import java.io.RandomAccessFile

/** Reads the saved original without changing it. Call on a worker that owns access to the file. */
internal object SavedAudioPreview {
    const val SAMPLE_RATE = 16_000
    const val MAX_DURATION_SECONDS = 180
    private const val WINDOW_FRAMES = 30 * SAMPLE_RATE
    private const val OVERLAP_FRAMES = SAMPLE_RATE / 2
    private const val MIN_TAIL_FRAMES = 5 * SAMPLE_RATE
    private const val ENERGY_FRAME_SAMPLES = SAMPLE_RATE / 50
    private const val PAUSE_FRAMES = 12
    private const val MIN_NEW_WINDOW_FRAMES = 10 * SAMPLE_RATE
    private const val PAUSE_ENERGY_RATIO = 0.01
    private const val FRAMES_PER_MS = SAMPLE_RATE / 1_000

    interface Decoder : AutoCloseable {
        /** Called synchronously with one exact, unpadded window. Do not retain the samples. */
        fun decode(samples: FloatArray): String
    }

    /**
     * The caller provides a cancellation check that throws on cancellation. It is not a
     * native interrupt. The recognizer is created, used and closed on this calling worker.
     * Join the nonblank returned spans with two newlines to stay within the UTF-8 text budget.
     */
    fun process(
        file: File,
        decoderFactory: (checkCancelled: () -> Unit) -> Decoder,
        checkCancelled: () -> Unit = {},
        onProgress: (coveredFrames: Int, totalFrames: Int) -> Unit = { _, _ -> },
    ): List<Transcript.Segment> {
        checkCancelled()
        require(file.isFile) { "Saved audio is missing." }
        val passages = ArrayList<Transcript.Segment>()
        var textBytes = 0
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            require(length > 0 && length % 2 == 0L) { "Saved audio is empty or damaged." }
            require(length <= MAX_DURATION_SECONDS * SAMPLE_RATE * 2L) {
                "Alternative preview is limited to three minutes of saved audio."
            }
            val totalFrames = (length / 2).toInt()
            val scratch = ByteArray(8192)
            var start = 0
            var previousEnd: Int? = null
            var decoder: Decoder? = null
            try {
                while (true) {
                    checkCancelled()
                    val limit = if (totalFrames - start <= WINDOW_FRAMES) totalFrames
                    else minOf(start + WINDOW_FRAMES, totalFrames - MIN_TAIL_FRAMES)
                    val pause = if (limit < totalFrames) lowEnergyCut(input, start, limit,
                        (previousEnd ?: start) + MIN_NEW_WINDOW_FRAMES, scratch, checkCancelled)
                    else null
                    val end = pause ?: limit
                    val samples = readWindow(input, start, end - start, scratch)
                    checkCancelled()
                    if (samples.any { it != samples[0] }) {
                        val current = decoder ?: decoderFactory(checkCancelled).also { decoder = it }
                        checkCancelled()
                        val raw = current.decode(samples)
                        checkCancelled()
                        require(raw.length <= MAX_PASSAGE_EDIT_BYTES) { "Alternative preview text is too long." }
                        val text = raw.trim()
                        val startMs = (start / FRAMES_PER_MS).toLong()
                        // The last partial millisecond still belongs to this window.
                        val endMs = ((end + FRAMES_PER_MS - 1) / FRAMES_PER_MS).toLong()
                        val decoded = if (text.isBlank()) emptyList()
                        else listOf(Transcript.Segment(startMs, endMs, text, uncertain = true))
                        val reconciled = Transcript.reconcileBoundary(
                            passages, decoded, startMs, endMs, previousEnd?.let { (it / FRAMES_PER_MS).toLong() }
                        )
                        for (passage in reconciled) {
                            if (passage.text.isNotBlank()) {
                                val separatorBytes = if (textBytes == 0) 0 else 2
                                textBytes += separatorBytes + passage.text.toByteArray(Charsets.UTF_8).size
                                require(textBytes <= MAX_PASSAGE_EDIT_BYTES) { "Alternative preview text is too long." }
                            }
                            passages.add(passage)
                        }
                    }
                    check(input.length() == length) { "Saved audio changed during preview." }
                    checkCancelled()
                    onProgress(end, totalFrames)
                    if (end == totalFrames) break
                    previousEnd = end
                    start = if (pause == null) end - OVERLAP_FRAMES else end
                }
            } finally {
                decoder?.close()
            }
        }
        checkCancelled()
        return passages
    }

    /** Looks for a cut only. Every sample still belongs to at least one decode window. */
    private fun lowEnergyCut(
        input: RandomAccessFile, start: Int, limit: Int, minimumCut: Int,
        scratch: ByteArray, checkCancelled: () -> Unit,
    ): Int? {
        val frameBytes = ENERGY_FRAME_SAMPLES * 2
        val energies = LongArray((limit - start) / ENERGY_FRAME_SAMPLES)
        input.seek(start * 2L)
        var frame = 0
        while (frame < energies.size) {
            checkCancelled()
            val frames = minOf(energies.size - frame, scratch.size / frameBytes)
            input.readFully(scratch, 0, frames * frameBytes)
            for (offset in 0 until frames * frameBytes step frameBytes) {
                var sum = 0L
                var squares = 0L
                for (index in offset until offset + frameBytes step 2) {
                    val sample = pcmSample(scratch, index).toLong()
                    sum += sample
                    squares += sample * sample
                }
                // This scaled variance removes constant microphone offset exactly.
                energies[frame++] = ENERGY_FRAME_SAMPLES * squares - sum * sum
            }
        }
        val threshold = energies.average() * PAUSE_ENERGY_RATIO
        if (threshold == 0.0) return null

        // Prefer an earlier pause to cutting a word at the maximum window length.
        // Every nonfinal window still contributes at least ten seconds of new audio.
        val searchStart = minimumCut - start
        val firstFrame = (searchStart + ENERGY_FRAME_SAMPLES - 1) / ENERGY_FRAME_SAMPLES
        var quietFrames = 0
        for (index in energies.lastIndex downTo firstFrame) {
            quietFrames = if (energies[index] <= threshold) quietFrames + 1 else 0
            if (quietFrames == PAUSE_FRAMES) return start + (index + PAUSE_FRAMES / 2) * ENERGY_FRAME_SAMPLES
        }
        return null
    }

    private fun readWindow(input: RandomAccessFile, start: Int, frames: Int, scratch: ByteArray): FloatArray {
        input.seek(start * 2L)
        val samples = FloatArray(frames)
        var filled = 0
        while (filled < frames) {
            val bytes = minOf((frames - filled) * 2, scratch.size)
            input.readFully(scratch, 0, bytes)
            for (index in 0 until bytes step 2) {
                samples[filled++] = pcmSample(scratch, index) / 32768f
            }
        }
        return samples
    }

    private fun pcmSample(bytes: ByteArray, index: Int): Int =
        (bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xff)
}
