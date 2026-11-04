package app.focal.tools

import app.focal.core.SpeechGate
import java.io.ByteArrayInputStream
import kotlin.math.sin

/** Standalone checks; compile with the production gate and replay sources. */
object SpeechGateReplayTest {
    @JvmStatic fun main(args: Array<String>) {
        val voice = ShortArray(20 * SpeechGate.SAMPLE_RATE + 137) { (sin(it * 0.11) * 2400).toInt().toShort() }
        val encoded = pcm(voice)
        val ordinary = mutableListOf<SpeechGateReplay.Range>()
        check(SpeechGateReplay.replay(ByteArrayInputStream(encoded), ordinary::add) == voice.size.toLong())
        check(ordinary.size >= 2 && ordinary.last().endSample == voice.size.toLong()) { "The real partial tail was padded or dropped." }
        check(ordinary.all { it.endSample - it.startSample <= SpeechGate.MAX_SAMPLES })
        check(ordinary.drop(1).any { it.overlap }) { "The long recording did not exercise a hard boundary." }

        val fragmented = mutableListOf<SpeechGateReplay.Range>()
        val input = object : ByteArrayInputStream(encoded) {
            var calls = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(length <= SpeechGate.FRAME_SAMPLES * 2) { "Replay requested an unbounded input read." }
                val fragment = intArrayOf(1, 137, 639)[calls++ % 3]
                return super.read(buffer, offset, minOf(length, fragment))
            }
        }
        check(SpeechGateReplay.replay(input, fragmented::add) == voice.size.toLong())
        check(fragmented == ordinary) { "Short or odd-sized input reads changed audio boundaries." }
        check(ordinary == direct(voice)) { "Signed little-endian PCM replay differs from direct production-gate input." }

        for (samples in listOf(ShortArray(48_000), ShortArray(48_000) { 6000 },
            ShortArray(48_000) { ((it * 17 % 31) - 15).toShort() },
            ShortArray(48_000) { (sin(it * 0.11) * 40).toInt().toShort() })) {
            val silent = mutableListOf<SpeechGateReplay.Range>()
            SpeechGateReplay.replay(ByteArrayInputStream(pcm(samples)), silent::add)
            check(silent.isEmpty()) { "Silence, microphone offset or very quiet input unexpectedly opened a range." }
        }
        val quiet = ShortArray(48_000) { (sin(it * 0.11) * 160).toInt().toShort() }
        val quietRanges = mutableListOf<SpeechGateReplay.Range>()
        SpeechGateReplay.replay(ByteArrayInputStream(pcm(quiet)), quietRanges::add)
        check(quietRanges.isNotEmpty() && quietRanges.last().endSample == quiet.size.toLong())
        check(SpeechGateReplay.replay(ByteArrayInputStream(byteArrayOf())) { error("Empty input opened a range.") } == 0L)
        try {
            SpeechGateReplay.replay(ByteArrayInputStream(byteArrayOf(1))) { }
            error("An incomplete PCM sample was silently accepted.")
        } catch (_: IllegalArgumentException) { }
        println("SpeechGateReplay integration checks passed.")
    }

    private fun direct(samples: ShortArray): List<SpeechGateReplay.Range> {
        val ranges = mutableListOf<SpeechGateReplay.Range>()
        var start = 0L
        var activeOverlap = false
        val gate = SpeechGate(object : SpeechGate.Sink {
            override fun begin(startSample: Long, overlap: Boolean) { start = startSample; activeOverlap = overlap }
            override fun audio(samples: ShortArray) = Unit
            override fun end(endSample: Long) { ranges += SpeechGateReplay.Range(start, endSample, activeOverlap) }
        })
        var offset = 0
        while (samples.size - offset >= SpeechGate.FRAME_SAMPLES) {
            gate.accept(samples.copyOfRange(offset, offset + SpeechGate.FRAME_SAMPLES))
            offset += SpeechGate.FRAME_SAMPLES
        }
        if (offset < samples.size) gate.acceptPartial(samples.copyOfRange(offset, samples.size), samples.size - offset)
        gate.flush()
        return ranges
    }

    private fun pcm(samples: ShortArray) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }
}
