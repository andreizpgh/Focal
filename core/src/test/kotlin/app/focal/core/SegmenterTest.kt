package app.focal.core

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmenterTest {
    /** Stateless stand-in for Silero: speech when the window's RMS exceeds 1 % of full scale. */
    private val detector = SpeechDetector { w -> sqrt(w.sumOf { it.toDouble() * it } / w.size) > 0.01 }

    private class Pcm {
        private val out = ArrayList<Short>()
        private var seed = 12345L
        val breaks = ArrayList<Long>()
        val size get() = out.size

        fun speech(ms: Int, amplitude: Int = 3000) = repeat(ms * 16) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            out += ((seed ushr 33) % (2 * amplitude + 1) - amplitude).toInt().toShort()
        }

        fun silence(ms: Int, amplitude: Int = 0) = if (amplitude == 0) repeat(ms * 16) { out += 0 } else speech(ms, amplitude)
        fun samples(n: Int) = repeat(n) { out += 1000 }
        fun breakHere() { breaks += out.size.toLong() }
        fun array() = out.toShortArray()
    }

    /** Feeds [pcm] from [from] in chunks of [chunk] samples, marking its breaks, then finishes. */
    private fun run(pcm: Pcm, segmenter: Segmenter, chunk: Int = 4096, from: Long = segmenter.feedFromSample): List<WindowPlan> {
        val data = pcm.array()
        val out = ArrayList<WindowPlan>()
        var position = from
        val stops = pcm.breaks.filter { it > from } + data.size.toLong()
        for (stop in stops) {
            while (position < stop) {
                val n = minOf(chunk.toLong(), stop - position).toInt()
                val ready = segmenter.accept(data, position.toInt(), n)
                position += n
                ready.forEach { assertTrue(it.decideAtMs * 16 <= position, "released before its audio: $it") }
                out += ready
            }
            out += if (stop == data.size.toLong()) segmenter.finish() else segmenter.markBreak()
        }
        return out
    }

    /** The same plan computed directly from per-window flags (breaks must lie on 160 ms boundaries). */
    private fun reference(pcm: Pcm): List<WindowPlan> {
        val data = pcm.array()
        val flags = (0 until data.size / 512).map { n ->
            detector.isSpeech(FloatArray(512) { data[512 * n + it] / 32768f })
        }
        val planner = CutPlanner(SegmenterConfig(), SegmenterStart.BEGINNING, 0, 0)
        val out = ArrayList<WindowPlan>()
        var slot = 0
        var frame = 0
        fun feed(untilSample: Long) {
            while (320L * (frame + 1) <= untilSample) {
                var sum = 0L
                var squares = 0L
                for (i in 320 * frame until 320 * (frame + 1)) { sum += data[i]; squares += data[i].toLong() * data[i] }
                planner.addFrame(320 * squares - sum * sum)
                frame++
            }
            while (160L * (slot + 1) <= untilSample) {
                val n = minOf((160 * slot + 80) / 512, flags.size - 1)
                val peak = (160 * slot until 160 * (slot + 1)).maxOf { kotlin.math.abs(data[it].toInt()) }
                planner.addSlot(!flags[n], peak)
                slot++
            }
        }
        for (b in pcm.breaks) {
            feed(b)
            planner.close(EndKind.BREAK, b / 16, 0)
            out += planner.takeReady()
        }
        feed(data.size.toLong())
        out += planner.takeReady()
        planner.close(EndKind.FINAL, (data.size + 15L) / 16, 0)
        return out + planner.takeReady()
    }

    private fun assertTiles(windows: List<WindowPlan>, endMs: Long) {
        windows.forEachIndexed { i, w -> assertEquals(i, w.index) }
        assertEquals(0, windows.first().ownStartMs)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.ownEndMs, b.ownStartMs) }
        assertEquals(endMs, windows.last().ownEndMs)
        assertEquals(EndKind.FINAL, windows.last().endKind)
    }

    private fun dictation() = Pcm().apply {
        speech(7_000); silence(800); speech(9_000); silence(300); speech(4_000); silence(700)
        speech(25_000) // no pause: hard cut
        silence(1_200); breakHere(); speech(3_040); breakHere(); silence(2_000); speech(6_400); silence(900)
        speech(2_000)
    }

    @Test
    fun pcmFrontEndMatchesDirectPlanningForAnyChunking() {
        val pcm = dictation()
        val expected = reference(pcm)
        assertTiles(expected, (pcm.size + 15L) / 16)
        assertTrue(expected.any { it.endKind == EndKind.HARD } && expected.count { it.endKind == EndKind.BREAK } == 2)
        for (chunk in listOf(1, 7, 320, 333, 4096, 1 shl 20)) {
            assertEquals(expected, run(pcm, Segmenter(detector), chunk), "chunk $chunk")
        }
    }

    @Test
    fun pauseCutWindowGetsRightContextAndTheNextOneALeadIn() {
        val windows = run(Pcm().apply { speech(7_000); silence(800); speech(3_000) }, Segmenter(detector))
        val (first, second) = windows
        assertEquals(EndKind.PAUSE, first.endKind)
        // Speech ends at 7000 ms; Silero-like flags see the first quiet slot at 7010-7020 ms.
        assertEquals(7_260, first.ownEndMs)
        assertEquals(first.ownEndMs + 1_000, first.audioEndMs)
        assertEquals(first.ownEndMs, first.keepHiMs)
        assertEquals(first.ownEndMs - 250, second.audioStartMs)
        assertNull(second.keepLoMs)
        assertEquals(EndKind.FINAL, second.endKind)
    }

    @Test
    fun breakClosesTheWindowAndClampsRightContext() {
        val pcm = Pcm().apply { speech(7_000); silence(600); samples(5); breakHere(); speech(2_000) }
        val windows = run(pcm, Segmenter(detector))
        val pause = windows[0]
        val brk = windows[1]
        assertEquals(EndKind.PAUSE, pause.endKind)
        assertEquals(EndKind.BREAK, brk.endKind)
        val breakMs = pcm.breaks[0] / 16
        assertEquals(breakMs, brk.ownEndMs)
        assertEquals(breakMs, pause.audioEndMs) // right context stops at the break
        assertEquals(breakMs, pause.decideAtMs)
        assertEquals(breakMs, windows[2].audioStartMs) // no lead-in across a break
        assertNull(windows[2].keepLoMs)
        assertTiles(windows, (pcm.size + 15L) / 16)
    }

    @Test
    fun finishCoversTheLastPartialMillisecond() {
        val pcm = Pcm().apply { speech(1_000); samples(7) }
        val windows = run(pcm, Segmenter(detector))
        assertEquals(1, windows.size)
        assertEquals(1_001, windows[0].ownEndMs)
        assertEquals(1_000, windows[0].speechMs)
        assertTrue(windows[0].peak in 2_900..3_000)
    }

    @Test
    fun recoveryAfterAnyPersistedWindowReproducesTheRest() {
        val pcm = dictation()
        val all = run(pcm, Segmenter(detector))
        for (k in 0 until all.size - 1) {
            val resumed = Segmenter(detector, SegmenterStart.after(all[k]))
            assertEquals(0, resumed.feedFromSample % 2560)
            assertTrue(resumed.feedFromSample <= all[k].ownEndMs * 16 - 32_000 || resumed.feedFromSample == 0L)
            assertEquals(all.drop(k + 1), run(pcm, resumed), "resumed after window $k")
        }
    }

    @Test
    fun energyFallbackRecoveryReproducesTheRelativeThreshold() {
        // Loud speech, then quieter speech: the relative-energy detector's 10 s history decides which
        // frames are quiet, so recovery must rebuild all of it.
        val pcm = Pcm().apply {
            speech(14_000, amplitude = 8_000); silence(600); speech(9_000, amplitude = 600)
            silence(700, amplitude = 5); speech(8_000, amplitude = 600); silence(900); speech(3_000, amplitude = 8_000)
        }
        val all = run(pcm, Segmenter(detector = null))
        assertTrue(all.size >= 3)
        for (k in 0 until all.size - 1) {
            val resumed = Segmenter(detector = null, start = SegmenterStart.after(all[k]))
            assertEquals(all.drop(k + 1), run(pcm, resumed), "resumed after window $k")
            assertTrue(resumed.feedFromSample <= all[k].ownEndMs * 16 - 160_000 || resumed.feedFromSample == 0L)
        }
    }

    @Test
    fun aRangeOfAnOldRecordingStartsLikeABreak() {
        val pcm = Pcm().apply { speech(3_000); silence(1_000); speech(8_000) }
        val segmenter = Segmenter(detector, SegmenterStart.range(5, 4_000))
        val windows = run(pcm, segmenter)
        assertEquals(5, windows[0].index)
        assertEquals(4_000, windows[0].ownStartMs)
        assertEquals(4_000, windows[0].audioStartMs)
        assertEquals(12_000, windows.last().ownEndMs)
    }

    @Test
    fun longSilenceBecomesSilentWindows() {
        val pcm = Pcm().apply { speech(7_000); silence(20_000, amplitude = 10); speech(2_000) }
        val windows = run(pcm, Segmenter(detector))
        val silent = windows.filter { it.speechMs == 0L }
        assertTrue(silent.size >= 2)
        silent.forEach {
            assertEquals(10, it.peak)
            assertEquals(WindowGate.Skip.SILENT, WindowGate.skip(it))
        }
        assertNull(WindowGate.skip(windows.first()))
    }

    @Test
    fun continuousSpeechIsHardCutWithBoundedWindows() {
        val pcm = Pcm().apply { speech(200_000) }
        val windows = run(pcm, Segmenter(detector), chunk = 16_000)
        assertTiles(windows, 200_000)
        windows.dropLast(1).forEach {
            assertEquals(EndKind.HARD, it.endKind)
            val own = it.ownEndMs - it.ownStartMs
            assertTrue(own in 6_000..20_000, "own length $own")
            assertEquals(it.ownEndMs + 500, it.audioEndMs)
        }
        windows.drop(1).forEach { assertEquals(it.ownStartMs - 500, it.audioStartMs) }
    }

    @Test
    fun energyFallbackFindsPausesWithoutAVad() {
        val pcm = Pcm().apply { speech(8_000); silence(1_000); speech(3_000) }
        val windows = run(pcm, Segmenter(detector = null))
        assertEquals(listOf(EndKind.PAUSE, EndKind.FINAL), windows.map { it.endKind })
        assertEquals(8_250, windows[0].ownEndMs)
    }

    @Test
    fun finishedSegmenterRejectsMoreAudio() {
        val segmenter = Segmenter(detector)
        segmenter.accept(ShortArray(1600))
        segmenter.finish()
        assertTrue(segmenter.isFinished)
        assertFailsWith<IllegalStateException> { segmenter.accept(ShortArray(16)) }
        val final = WindowPlan(0, 0, 100, 0, 100, null, null, EndKind.FINAL, 100, 0, 0)
        assertFailsWith<IllegalArgumentException> { SegmenterStart.after(final) }
    }

    @Test
    fun windowGate() {
        fun plan(ms: Long, peak: Int, speech: Long = ms) = WindowPlan(0, 0, ms, 0, ms, null, null, EndKind.FINAL, ms, speech, peak)
        assertEquals(WindowGate.Skip.TOO_SHORT, WindowGate.skip(plan(199, 30_000)))
        assertNull(WindowGate.skip(plan(200, 33)))
        assertEquals(WindowGate.Skip.SILENT, WindowGate.skip(plan(5_000, 32))) // -60 dBFS = 32.77
        assertTrue(WindowGate.hidesResult(plan(5_000, 900, speech = 0), wordCount = 2))
        assertTrue(!WindowGate.hidesResult(plan(5_000, 900, speech = 0), wordCount = 3))
        assertTrue(!WindowGate.hidesResult(plan(5_000, 900, speech = 10), wordCount = 1))
    }
}
