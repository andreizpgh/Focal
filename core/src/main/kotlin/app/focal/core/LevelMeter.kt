package app.focal.core

import kotlin.math.sqrt

/**
 * Microphone level for the recording screen. Runs on the capture thread and only measures:
 * it never decides which audio is saved or transcribed.
 */
class LevelMeter {
    /** 0 (silence) to 1 (loud), from the latest accepted read. */
    var level = 0f
        private set

    /** Measures the varying part of [count] samples, so a constant DC offset reads as silence. */
    fun accept(samples: ShortArray, count: Int = samples.size) {
        require(count in 1..samples.size)
        var sum = 0.0
        var squares = 0.0
        for (index in 0 until count) {
            val x = samples[index] / 32768.0
            sum += x
            squares += x * x
        }
        val mean = sum / count
        val power = squares / count - mean * mean
        level = (sqrt(power.coerceAtLeast(0.0)) * GAIN).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 20 ms of mono 16 kHz PCM. */
        const val FRAME_SAMPLES = 320
        private const val GAIN = 14
    }
}
