package app.focal.speech

import java.io.File
import java.io.RandomAccessFile

/** Plans one saved-audio window without reading past the durable recording watermark. */
internal object UltraWindowPlanner {
    data class Window(val startMs: Long, val endMs: Long, val nextStartMs: Long)

    /**
     * The caller persists each returned range before decoding it. For a hard cut,
     * previousEndMs is the preceding range's end and startMs includes its overlap.
     * A live recording waits for five seconds beyond the maximum window so a stop
     * just after publication still leaves new audio for the final window.
     */
    fun plan(
        file: File,
        startMs: Long,
        previousEndMs: Long?,
        durableEndMs: Long,
        finished: Boolean,
    ): Window? {
        require(startMs >= 0 && durableEndMs >= startMs && durableEndMs <= Long.MAX_VALUE / BYTES_PER_MS) {
            "Invalid durable audio range."
        }
        require(previousEndMs == null ||
            (previousEndMs in startMs..durableEndMs && previousEndMs - startMs <= OVERLAP_MS)) {
            "Invalid preceding audio range."
        }
        check(file.isFile && file.length() >= durableEndMs * BYTES_PER_MS) {
            "Saved audio is missing or shorter than its durable watermark."
        }
        val available = durableEndMs - startMs
        if (available == 0L || (!finished && available < WINDOW_MS + MIN_TAIL_MS)) return null
        if (finished && available <= WINDOW_MS) return Window(startMs, durableEndMs, durableEndMs)

        val limit = minOf(startMs + WINDOW_MS, durableEndMs - MIN_TAIL_MS)
        val minimumCut = maxOf(startMs, previousEndMs ?: startMs) + MIN_NEW_MS
        val pause = RandomAccessFile(file, "r").use { input ->
            lowEnergyCut(input, startMs, limit, minimumCut)
        }
        val end = pause ?: limit
        val next = if (pause == null) end - OVERLAP_MS else end
        check(end > startMs && next > startMs && end <= durableEndMs) { "Invalid planned audio window." }
        return Window(startMs, end, next)
    }

    private fun lowEnergyCut(input: RandomAccessFile, startMs: Long, limitMs: Long, minimumCutMs: Long): Long? {
        val frameCount = ((limitMs - startMs) / FRAME_MS).toInt()
        val energies = LongArray(frameCount)
        val scratch = ByteArray(8_192)
        val frameBytes = FRAME_SAMPLES * 2
        input.seek(startMs * BYTES_PER_MS)
        var frame = 0
        while (frame < frameCount) {
            val count = minOf(frameCount - frame, scratch.size / frameBytes)
            input.readFully(scratch, 0, count * frameBytes)
            for (offset in 0 until count * frameBytes step frameBytes) {
                var sum = 0L
                var squares = 0L
                for (index in offset until offset + frameBytes step 2) {
                    val sample = ((scratch[index + 1].toInt() shl 8) or
                        (scratch[index].toInt() and 0xff)).toLong()
                    sum += sample
                    squares += sample * sample
                }
                energies[frame++] = FRAME_SAMPLES * squares - sum * sum
            }
        }
        if (energies.isEmpty()) return null
        val threshold = energies.average() * PAUSE_ENERGY_RATIO
        if (threshold == 0.0) return null
        val searchStart = minimumCutMs - startMs
        val firstFrame = ((searchStart + FRAME_MS - 1) / FRAME_MS).toInt()
        var quietFrames = 0
        for (index in energies.lastIndex downTo firstFrame) {
            quietFrames = if (energies[index] <= threshold) quietFrames + 1 else 0
            if (quietFrames == PAUSE_FRAMES) return startMs + (index + PAUSE_FRAMES / 2) * FRAME_MS
        }
        return null
    }

    private const val BYTES_PER_MS = 32L
    private const val WINDOW_MS = 30_000L
    private const val MIN_TAIL_MS = 5_000L
    private const val OVERLAP_MS = 500L
    private const val MIN_NEW_MS = 10_000L
    private const val FRAME_MS = 20L
    private const val FRAME_SAMPLES = 320
    private const val PAUSE_FRAMES = 12
    private const val PAUSE_ENERGY_RATIO = 0.01
}
