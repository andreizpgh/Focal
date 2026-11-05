package app.focal.core

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Microphone level for the recording screen. Runs on the capture thread and only measures:
 * it never decides which audio is saved or transcribed.
 */
class LevelMeter {
    /** 0 (silence) to 1 (loud), from the latest accepted read. */
    var level = 0f
        private set

    /** Root mean square of the varying part of the latest read, in sample units (0 to 32768). */
    var rms = 0.0
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
        val root = sqrt(power.coerceAtLeast(0.0))
        rms = root * 32768.0
        level = (root * GAIN).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 20 ms of mono 16 kHz PCM. */
        const val FRAME_SAMPLES = 320
        private const val GAIN = 14
    }
}

/**
 * The recording meter's maths (the nine bars): the RMS of each 20 ms read mapped from -50..-10 dBFS onto
 * 0..1, a one-pole smoother with a fast attack and a slow release, and a history the outer bars read with
 * a delay of [HISTORY_STEP_MS] per bar.
 */
object MeterMath {
    const val FLOOR_DBFS = -50.0
    const val CEILING_DBFS = -10.0
    const val ATTACK_MS = 40.0
    const val RELEASE_MS = 180.0
    /** Bar distance d from the centre shows the level d steps ago. */
    const val HISTORY_STEP_MS = 80L
    /** Bars on each side of the centre bar. */
    const val SIDE_BARS = 4

    /** [rms] in sample units (0 to 32768) mapped onto 0..1. */
    fun normalized(rms: Double): Float {
        val dbfs = 20 * log10(maxOf(rms, 1e-9) / 32768.0)
        return ((dbfs - FLOOR_DBFS) / (CEILING_DBFS - FLOOR_DBFS)).toFloat().coerceIn(0f, 1f)
    }

    /** One smoothing step from [previous] towards [target] over [dtMs]. */
    fun smooth(previous: Float, target: Float, dtMs: Double): Float {
        if (dtMs <= 0) return previous
        val tau = if (target > previous) ATTACK_MS else RELEASE_MS
        val alpha = 1 - exp(-dtMs / tau)
        return (previous + (target - previous) * alpha).toFloat()
    }
}

/**
 * Smoothed levels with their times, for the meter's bars: [at] reads the level at an earlier time by
 * linear interpolation. Holds [capacity] entries (at 60 frames per second, 64 cover about one second,
 * more than the 320 ms the outer bars need). Not thread-safe: use it from the drawing thread.
 */
class LevelHistory(private val capacity: Int = 64) {
    private val times = LongArray(capacity)
    private val levels = FloatArray(capacity)
    private var size = 0
    private var head = 0

    init { require(capacity >= 2) }

    /** Appends a level; [timeMs] must not decrease. */
    fun add(timeMs: Long, level: Float) {
        if (size > 0) require(timeMs >= times[(head - 1 + capacity) % capacity]) { "time went backwards" }
        times[head] = timeMs
        levels[head] = level
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** The latest level, or 0 when empty. */
    val latest: Float get() = if (size == 0) 0f else levels[(head - 1 + capacity) % capacity]

    /** The level at [timeMs]: interpolated between entries, clamped to the oldest and newest. */
    fun at(timeMs: Long): Float {
        if (size == 0) return 0f
        var newer = (head - 1 + capacity) % capacity
        if (timeMs >= times[newer]) return levels[newer]
        for (step in 1 until size) {
            val older = (newer - 1 + capacity) % capacity
            if (times[older] <= timeMs) {
                val span = times[newer] - times[older]
                if (span == 0L) return levels[newer]
                val t = (timeMs - times[older]).toFloat() / span
                return levels[older] + (levels[newer] - levels[older]) * t
            }
            newer = older
        }
        return levels[newer]
    }

    /** Level of the bar at [distance] from the centre (0..[MeterMath.SIDE_BARS]) at [nowMs]. */
    fun bar(distance: Int, nowMs: Long): Float = at(nowMs - distance * MeterMath.HISTORY_STEP_MS)

    fun clear() { size = 0; head = 0 }
}
