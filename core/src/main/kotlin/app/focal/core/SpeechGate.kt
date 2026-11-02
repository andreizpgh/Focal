package app.focal.core

import kotlin.math.max
import kotlin.math.sqrt

/** A bounded, conservative energy gate. No inference runs during long pauses.
 * Input: 20 ms frames of mono 16 kHz PCM. Callbacks run on the capture thread.
 * Audio is streamed to disk, not held until a whole utterance is finished.
 */
class SpeechGate(private val sink: Sink) {
    interface Sink {
        fun begin(startSample: Long, overlap: Boolean)
        fun audio(samples: ShortArray)
        fun end(endSample: Long)
    }
    private val preRoll = ArrayDeque<ShortArray>()
    private var position = 0L
    private var active = false
    private var activeSamples = 0
    private var quietFrames = 0
    private var loudFrames = 0
    private var floor = 0.0007
    private var continueWithOverlap = false
    var level = 0f
        private set

    fun accept(frame: ShortArray) {
        require(frame.size == FRAME_SAMPLES)
        val rms = sqrt(frame.sumOf { val x = it / 32768.0; x * x } / frame.size)
        level = (rms * 14).toFloat().coerceIn(0f, 1f)
        val voiced = rms > max(0.002, (floor * 2.8).coerceAtMost(0.009))
        if (!active && !voiced) floor = floor * 0.98 + rms * 0.02
        loudFrames = if (voiced) loudFrames + 1 else 0

        if (!active && (loudFrames >= 2 || continueWithOverlap)) {
            active = true
            activeSamples = preRoll.sumOf { it.size }
            quietFrames = 0
            sink.begin(position - activeSamples, continueWithOverlap)
            preRoll.forEach(sink::audio)
            continueWithOverlap = false
        }
        if (active) {
            sink.audio(frame)
            activeSamples += frame.size
            quietFrames = if (voiced) 0 else quietFrames + 1
        }
        preRoll.addLast(frame.copyOf())
        while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
        position += frame.size
        if (active && (quietFrames >= TRAILING_FRAMES || activeSamples >= MAX_SAMPLES)) {
            continueWithOverlap = quietFrames < TRAILING_FRAMES
            sink.end(position)
            active = false
            if (!continueWithOverlap) preRoll.clear()
            loudFrames = 0
        }
    }

    fun flush() {
        if (active) sink.end(position)
        active = false
        continueWithOverlap = false
        preRoll.clear()
        loudFrames = 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
        const val MAX_SAMPLES = 24 * SAMPLE_RATE
        private const val PRE_ROLL_FRAMES = 25
        private const val TRAILING_FRAMES = 45
    }
}
