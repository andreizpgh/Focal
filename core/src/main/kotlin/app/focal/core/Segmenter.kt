package app.focal.core

import kotlin.math.abs

/** How a window ends. */
enum class EndKind {
    /** At a pause in speech: the cut lies inside the pause; the next window gets a short pure-pause lead-in. */
    PAUSE,

    /** Forced at the maximum length: the neighbours overlap and keep words by midpoint. */
    HARD,

    /** The recording was paused here: the audio on either side is not continuous, no context crosses it. */
    BREAK,

    /** The recording ended. */
    FINAL,
}

/**
 * A window to transcribe. It owns the audio [ownStartMs, ownEndMs); windows tile the recording without
 * gaps. The recognizer decodes [audioStartMs, audioEndMs) and keeps the words whose midpoint lies in
 * [keepLoMs, keepHiMs) (null = unbounded). [decideAtMs] is when the cut was decided (for a pause cut, once
 * the right context was available). [speechMs] counts detected speech in the own range and [peak] is its
 * largest absolute sample: both feed [WindowGate]. [index] is the window's ordinal in the recording.
 */
data class WindowPlan(
    val index: Int,
    val ownStartMs: Long,
    val ownEndMs: Long,
    val audioStartMs: Long,
    val audioEndMs: Long,
    val keepLoMs: Long?,
    val keepHiMs: Long?,
    val endKind: EndKind,
    val decideAtMs: Long,
    val speechMs: Long,
    val peak: Int,
) {
    fun toDecodedWindow(tokens: List<AsrToken>) = DecodedWindow(audioStartMs, keepLoMs, keepHiMs, tokens)
}

/** Segmenter constants (defaults: [SpeechTuning]). */
data class SegmenterConfig(
    val minMs: Long = SpeechTuning.MIN_WINDOW_MS,
    val maxMs: Long = SpeechTuning.MAX_WINDOW_MS,
    val pauseMs: Long = SpeechTuning.PAUSE_MS,
    val overlapMs: Long = SpeechTuning.HARD_CUT_OVERLAP_MS,
    val hardCutMinRunMs: Long = SpeechTuning.HARD_CUT_MIN_RUN_MS,
    val rightContextMs: Long = SpeechTuning.RIGHT_CONTEXT_MS,
    val pauseLeadInMs: Long = SpeechTuning.PAUSE_LEAD_IN_MS,
    val warmUpMs: Long = SpeechTuning.RECOVERY_WARM_UP_MS,
) {
    init {
        require(minMs in 10..maxMs && pauseMs >= 20 && overlapMs >= 0 && rightContextMs >= 0 && pauseLeadInMs >= 0)
        require(pauseLeadInMs <= pauseMs / 2) { "the lead-in must stay inside the pause" }
    }
}

/**
 * Where segmentation starts: the next window's [index], its own start, and how the window before it ended
 * (null at the start of a recording). Resume after process death with [after] the last persisted window.
 * A range of an old recording that was never transcribed starts like a recording break.
 */
data class SegmenterStart(val index: Int, val ownStartMs: Long, val previousEnd: EndKind?) {
    init {
        require(index >= 0 && ownStartMs >= 0)
        require(previousEnd != EndKind.FINAL) { "a finished recording has nothing left to segment" }
    }

    companion object {
        val BEGINNING = SegmenterStart(0, 0, null)

        fun after(window: WindowPlan) = SegmenterStart(window.index + 1, window.ownEndMs, window.endKind)

        fun range(index: Int, startMs: Long) = SegmenterStart(index, startMs, EndKind.BREAK)
    }
}

/**
 * Speech decisions for consecutive, non-overlapping windows of [SpeechTuning.VAD_WINDOW_SAMPLES] samples
 * (float PCM in [-1, 1)), for example Silero VAD with its hysteresis. The array is reused between calls.
 */
fun interface SpeechDetector {
    fun isSpeech(window: FloatArray): Boolean
}

/**
 * Streaming segmenter for the speech worker. It reads durable PCM forward (never the capture thread),
 * keeps only per-10-ms numbers for the open window (bounded by the maximum window length, no audio
 * queue), and returns windows in order once their audio, including any right context, has been fed.
 *
 * Speech flags come from [detector] (Silero through sherpa-onnx); with no detector the gain-invariant
 * relative-energy detector is the fallback. Feed PCM from [feedFromSample]: after process death that
 * position lies [SegmenterConfig.warmUpMs] before the open window so the detector state is rebuilt
 * (pass a detector with fresh state); the energy fallback is re-warmed on its whole 10 s history, which
 * reproduces it exactly. Persist each returned window atomically with its transcription cursor;
 * [SegmenterStart.after] the last persisted window resumes exactly there.
 */
class Segmenter(
    private val detector: SpeechDetector?,
    start: SegmenterStart = SegmenterStart.BEGINNING,
    config: SegmenterConfig = SegmenterConfig(),
) {
    /** The first PCM sample to feed (a multiple of the slot, frame and detector window sizes). */
    val feedFromSample: Long = if (start.previousEnd == null && start.ownStartMs == 0L) 0L
    else {
        val warmUpMs = if (detector == null) maxOf(config.warmUpMs, ENERGY_TRAIL_FRAMES * FRAME_MS) else config.warmUpMs
        floorDiv(maxOf(0L, (start.ownStartMs - warmUpMs) * SAMPLES_PER_MS), ALIGN) * ALIGN
    }

    /** Absolute index of the next sample to feed. */
    var fedSamples: Long = feedFromSample
        private set

    val isFinished: Boolean get() = planner.finished

    private val planner = CutPlanner(config, start, feedFromSample / SLOT_SAMPLES, feedFromSample / FRAME_SAMPLES)
    private val flagSamples = if (detector != null) SpeechTuning.VAD_WINDOW_SAMPLES else FRAME_SAMPLES
    private val energy = if (detector == null) RelativeEnergyDetector(ENERGY_TRAIL_FRAMES.toInt()) else null

    private var frameSum = 0L
    private var frameSquares = 0L
    private var frameFill = 0
    private var slotPeak = 0
    private var slotFill = 0
    private val vadWindow = FloatArray(SpeechTuning.VAD_WINDOW_SAMPLES)
    private var vadFill = 0

    /** Peaks of complete slots not yet planned; the first belongs to [CutPlanner.nextSlot]. */
    private val slotPeaks = ArrayDeque<Int>()

    /** Speech flags per detector window; the first has index [flagBase]. */
    private val flags = ArrayDeque<Boolean>()
    private var flagBase = feedFromSample / flagSamples
    private var lastFlag: Boolean? = null

    /** Feeds the next PCM samples; returns the windows that became ready, in order. */
    fun accept(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): List<WindowPlan> {
        check(!planner.finished) { "segmentation already finished" }
        require(offset >= 0 && length >= 0 && offset + length <= pcm.size)
        for (i in offset until offset + length) {
            val x = pcm[i].toInt()
            var event = false
            frameSum += x
            frameSquares += x.toLong() * x
            if (++frameFill == FRAME_SAMPLES) {
                val e = FRAME_SAMPLES * frameSquares - frameSum * frameSum
                planner.addFrame(e)
                energy?.let { pushFlag(!it.quiet(e)) }
                frameSum = 0
                frameSquares = 0
                frameFill = 0
                event = true
            }
            slotPeak = maxOf(slotPeak, abs(x))
            if (++slotFill == SLOT_SAMPLES) {
                slotPeaks.addLast(slotPeak)
                slotPeak = 0
                slotFill = 0
                event = true
            }
            if (detector != null) {
                vadWindow[vadFill] = x / 32768f
                if (++vadFill == vadWindow.size) {
                    pushFlag(detector.isSpeech(vadWindow))
                    vadFill = 0
                    event = true
                }
            }
            fedSamples++
            if (event) planSlots(force = false)
        }
        return planner.takeReady()
    }

    /**
     * The recording was paused (by the user or automatically) after the samples fed so far: closes the
     * open window there. Feed exactly the audio recorded before the pause first, and the audio recorded
     * after Resume as usual. When re-feeding after process death, replay each break at its position.
     */
    fun markBreak(): List<WindowPlan> {
        check(!planner.finished) { "segmentation already finished" }
        planSlots(force = true)
        planner.close(EndKind.BREAK, fedSamples / SAMPLES_PER_MS, slotPeak)
        slotPeak = 0 // the slot that straddles the break counts only its later samples
        return planner.takeReady()
    }

    /** The recording ended after the samples fed so far: closes the last window. */
    fun finish(): List<WindowPlan> {
        check(!planner.finished) { "segmentation already finished" }
        planSlots(force = true)
        planner.close(EndKind.FINAL, ceilDiv(fedSamples, SAMPLES_PER_MS.toLong()), slotPeak)
        return planner.takeReady()
    }

    private fun pushFlag(speech: Boolean) {
        flags.addLast(speech)
        lastFlag = speech
    }

    /** Plans every complete slot whose flag is known; with [force], unknown flags repeat the last one. */
    private fun planSlots(force: Boolean) {
        while (slotPeaks.isNotEmpty()) {
            val n = flagIndex(planner.nextSlot)
            while (flags.isNotEmpty() && flagBase < n) {
                flags.removeFirst()
                flagBase++
            }
            val speech = if (flagBase == n && flags.isNotEmpty()) flags.first()
            else if (force) lastFlag ?: true
            else return
            planner.addSlot(!speech, slotPeaks.removeFirst())
        }
    }

    private fun flagIndex(slot: Long): Long = (SLOT_SAMPLES * slot + SLOT_SAMPLES / 2) / flagSamples

    internal companion object {
        const val SAMPLES_PER_MS = SpeechTuning.SAMPLES_PER_MS
        const val SLOT_MS = 10L
        const val FRAME_MS = 20L
        const val SLOT_SAMPLES = 160
        const val FRAME_SAMPLES = 320
        /** Least common multiple of the slot, frame and VAD window sizes. */
        const val ALIGN = 2_560L
        /** The energy fallback's history (10 s): recovery re-warms it on all of it. */
        const val ENERGY_TRAIL_FRAMES = 500L
    }
}

/**
 * The cut logic on 10 ms slots (quiet flag + peak) and 20 ms frame energies; [Segmenter] feeds it from PCM.
 * Slots belong to the window that contains their start, except that the slot straddling a break belongs
 * to the window after it.
 */
internal class CutPlanner(
    private val config: SegmenterConfig,
    start: SegmenterStart,
    firstSlot: Long,
    firstFrame: Long,
) {
    private val half = config.overlapMs / 2
    private var index = start.index
    private var ownStart = start.ownStartMs
    private var previousEnd = start.previousEnd
    private var run = 0L

    var nextSlot = firstSlot
        private set
    private var nextFrame = firstFrame
    var finished = false
        private set

    /** First slot owned by the open window; slots before it only warm up the pause run. */
    private var windowFirstSlot = if (previousEnd == EndKind.BREAK) floorDiv(ownStart, SLOT_MS) else ceilDiv(ownStart, SLOT_MS)
    private val runResetBefore = if (previousEnd == EndKind.BREAK) windowFirstSlot else Long.MIN_VALUE

    // Slots owned by the open window, from windowFirstSlot.
    private var quiet = BooleanArray(INITIAL)
    private var peaks = IntArray(INITIAL)
    private var slotCount = 0

    // Frame energies from frameBase, kept only where a hard cut may need them.
    private var frames = LongArray(INITIAL)
    private var frameBase = maxOf(frameKeepFrom(), firstFrame)
    private var frameCount = 0

    private val pending = ArrayDeque<WindowPlan>()
    private val ready = ArrayList<WindowPlan>()

    fun addFrame(energy: Long) {
        val i = nextFrame++
        if (i < frameBase) return
        if (frameCount == frames.size) frames = frames.copyOf(frames.size * 2)
        frames[frameCount++] = energy
    }

    fun addSlot(isQuiet: Boolean, peak: Int) {
        check(!finished)
        val k = nextSlot++
        val t = SLOT_MS * (k + 1)
        run = if (k < runResetBefore || !isQuiet) 0 else run + 1
        if (k >= windowFirstSlot) {
            if (slotCount == quiet.size) {
                quiet = quiet.copyOf(quiet.size * 2)
                peaks = peaks.copyOf(peaks.size * 2)
            }
            quiet[slotCount] = isQuiet
            peaks[slotCount] = peak
            slotCount++
        }
        val c = t - config.pauseMs / 2
        if (run * SLOT_MS >= config.pauseMs && c - ownStart >= config.minMs) {
            cut(c, EndKind.PAUSE, t, ceilDiv(c, SLOT_MS), 0)
        } else if (t - ownStart >= config.maxMs + half) {
            val h = hardCutPoint()
            cut(h, EndKind.HARD, t, ceilDiv(h, SLOT_MS), 0)
        }
        while (pending.isNotEmpty() && pending.first().decideAtMs <= t) ready += pending.removeFirst()
    }

    /** Closes the open window at [endMs] (a break or the end); [extraPeak] covers a trailing partial slot. */
    fun close(kind: EndKind, endMs: Long, extraPeak: Int) {
        check(!finished)
        require(kind == EndKind.BREAK || kind == EndKind.FINAL)
        if (endMs > ownStart) cut(endMs, kind, endMs, nextSlot, extraPeak)
        while (pending.isNotEmpty()) {
            val w = pending.removeFirst()
            ready += w.copy(audioEndMs = minOf(w.audioEndMs, endMs), decideAtMs = minOf(w.decideAtMs, endMs))
        }
        run = 0
        if (kind == EndKind.FINAL) finished = true
    }

    fun takeReady(): List<WindowPlan> = ready.toList().also { ready.clear() }

    private fun cut(at: Long, kind: EndKind, trigger: Long, endSlot: Long, extraPeak: Int) {
        val owned = (endSlot - windowFirstSlot).toInt().coerceIn(0, slotCount)
        var speechSlots = 0
        var peak = extraPeak
        for (j in 0 until owned) {
            if (!quiet[j]) speechSlots++
            if (peaks[j] > peak) peak = peaks[j]
        }
        val a = ownStart
        val audioStart = when (previousEnd) {
            EndKind.HARD -> a - half
            EndKind.PAUSE -> maxOf(0L, a - config.pauseLeadInMs)
            else -> a
        }
        val keepLo = if (previousEnd == EndKind.HARD) a else null
        var audioEnd = at
        var keepHi: Long? = null
        var decideAt = trigger
        if (kind == EndKind.HARD) {
            audioEnd = at + half
            keepHi = at
        } else if (kind == EndKind.PAUSE && config.rightContextMs > 0) {
            audioEnd = at + config.rightContextMs
            keepHi = at
            decideAt = maxOf(trigger, at + config.rightContextMs)
        }
        pending += WindowPlan(index, a, at, audioStart, audioEnd, keepLo, keepHi, kind, decideAt,
            SLOT_MS * speechSlots, peak)
        index++
        ownStart = at
        previousEnd = kind
        // The open window now starts at endSlot; drop what it does not own or need.
        val drop = (endSlot - windowFirstSlot).toInt().coerceIn(0, slotCount)
        quiet.copyInto(quiet, 0, drop, slotCount)
        peaks.copyInto(peaks, 0, drop, slotCount)
        slotCount -= drop
        windowFirstSlot = endSlot
        val keepFrom = frameKeepFrom()
        val frameDrop = (keepFrom - frameBase).coerceIn(0, frameCount.toLong()).toInt()
        frames.copyInto(frames, 0, frameDrop, frameCount)
        frameCount -= frameDrop
        frameBase = maxOf(frameBase, keepFrom)
    }

    /**
     * Hard cut in [start + MIN, start + MAX]: the centre of the longest quiet run (earliest on ties) if it
     * is long enough, else the frame with the lowest 5-frame energy sum (earliest on ties).
     */
    private fun hardCutPoint(): Long {
        val a = floorDiv(ownStart + config.minMs, SLOT_MS)
        val b = minOf(floorDiv(ownStart + config.maxMs, SLOT_MS), nextSlot)
        var bestLen = 0L
        var bestCentre = 0L
        var runLen = 0L
        for (j in a until b) {
            runLen = if (quiet[(j - windowFirstSlot).toInt()]) runLen + 1 else 0
            if (runLen > bestLen) {
                bestLen = runLen
                bestCentre = SLOT_MS * (j + 1) - SLOT_MS * runLen / 2
            }
        }
        if (bestLen * SLOT_MS >= config.hardCutMinRunMs) return bestCentre
        val fa = floorDiv(ownStart + config.minMs, FRAME_MS)
        val fb = minOf(floorDiv(ownStart + config.maxMs, FRAME_MS), nextFrame)
        if (fb <= fa) return FRAME_MS * fb + FRAME_MS / 2
        var best = fa
        var bestSum = Long.MAX_VALUE
        for (i in fa until fb) {
            var sum = 0L
            for (d in -2L..2L) sum += frameEnergy(i + d)
            if (sum < bestSum) {
                bestSum = sum
                best = i
            }
        }
        return FRAME_MS * best + FRAME_MS / 2
    }

    private fun frameEnergy(i: Long): Long {
        val j = i - frameBase
        return if (j in 0 until frameCount) frames[j.toInt()] else 0L
    }

    private fun frameKeepFrom(): Long = floorDiv(ownStart + config.minMs, FRAME_MS) - 2

    private companion object {
        const val SLOT_MS = Segmenter.SLOT_MS
        const val FRAME_MS = Segmenter.FRAME_MS
        const val INITIAL = 512
    }
}

/**
 * Fallback speech detector when the VAD cannot be loaded: a 20 ms frame is quiet when its energy is at most
 * 1 % of the mean over the last 500 frames (itself included). Gain-invariant and dependency-free.
 */
internal class RelativeEnergyDetector(private val trail: Int = 500) {
    private val ring = LongArray(trail)
    private var count = 0
    private var position = 0
    private var sum = 0L

    fun quiet(energy: Long): Boolean {
        if (count == trail) sum -= ring[position] else count++
        ring[position] = energy
        sum += energy
        position = (position + 1) % trail
        return energy * 100 * count <= sum
    }
}

/**
 * sherpa-onnx `SileroVadModel::IsSpeech` (v2.0.1) over per-window speech probabilities: speech starts after
 * [minSpeechSamples] above the threshold and ends after [minSilenceSamples] at or below the negative
 * threshold (threshold - 0.15). Use it when the detector exposes probabilities rather than decisions.
 */
class SileroHysteresis(
    private val threshold: Float = SpeechTuning.VAD_THRESHOLD,
    private val minSpeechSamples: Int = SpeechTuning.VAD_MIN_SPEECH_SAMPLES,
    private val minSilenceSamples: Int = SpeechTuning.VAD_MIN_SILENCE_SAMPLES,
    private val windowSamples: Int = SpeechTuning.VAD_WINDOW_SAMPLES,
) {
    private val negThreshold = maxOf(threshold - 0.15f, 0.01f)
    private var triggered = false
    private var tempStart = 0L
    private var tempEnd = 0L
    private var current = 0L

    /** The speech decision for the next window, given its probability. */
    fun accept(probability: Float): Boolean {
        current += windowSamples
        val p = probability
        if (p > threshold && tempEnd != 0L) tempEnd = 0
        if (p > threshold && tempStart == 0L) {
            tempStart = current
            return false
        }
        if (p > threshold && !triggered) {
            if (current - tempStart < minSpeechSamples) return false
            triggered = true
            return true
        }
        if (p < threshold && !triggered) {
            tempStart = 0
            tempEnd = 0
            return false
        }
        if (p > negThreshold && triggered) return true
        if (p < threshold && triggered) {
            if (tempEnd == 0L) tempEnd = current
            if (current - tempEnd < minSilenceSamples) return true
            tempStart = 0
            tempEnd = 0
            triggered = false
            return false
        }
        return false
    }
}

/** Decisions about whether a window is worth decoding and whether its result is shown. */
object WindowGate {
    enum class Skip {
        /** The window owns less audio than [SpeechTuning.MIN_DECODE_MS]. */
        TOO_SHORT,

        /** The window's peak is below [SpeechTuning.SILENCE_PEAK_DBFS]. */
        SILENT,
    }

    /** Why [window] is recorded as silent without decoding, or null to decode it. */
    fun skip(
        window: WindowPlan,
        minDecodeMs: Long = SpeechTuning.MIN_DECODE_MS,
        silencePeakDbfs: Double = SpeechTuning.SILENCE_PEAK_DBFS,
    ): Skip? = when {
        window.ownEndMs - window.ownStartMs < minDecodeMs -> Skip.TOO_SHORT
        isSilentPeak(window.peak, silencePeakDbfs) -> Skip.SILENT
        else -> null
    }

    fun isSilentPeak(peak: Int, dbfs: Double = SpeechTuning.SILENCE_PEAK_DBFS): Boolean =
        peak < 32_768.0 * Math.pow(10.0, dbfs / 20.0)

    /**
     * Hallucination guard: a window without any detected speech that decoded to at most [maxWords] words
     * is stored raw but not shown.
     */
    fun hidesResult(window: WindowPlan, wordCount: Int, maxWords: Int = SpeechTuning.HALLUCINATION_MAX_WORDS): Boolean =
        window.speechMs == 0L && wordCount <= maxWords
}

internal fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)

internal fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
