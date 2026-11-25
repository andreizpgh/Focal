package app.focal.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlayerFadeInTest {
    private fun constantPcm16Le(sampleCount: Int, amplitude: Short): ByteArray {
        val out = ByteArray(sampleCount * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) { bb.putShort(amplitude) }
        return out
    }

    private fun readSample(buffer: ByteArray, index: Int): Short {
        val bb = ByteBuffer.wrap(buffer, index * 2, 2).order(ByteOrder.LITTLE_ENDIAN)
        return bb.short
    }

    @Test
    fun fadeInRampsSmoothlyFromZeroOver192SamplesAndLeavesRestUntouched() {
        val total = 300
        val amp: Short = 10_000
        val buffer = constantPcm16Le(total, amp)

        applyFadeInPcm16Le(buffer, buffer.size, startSampleOffset = 0, rampSamples = 192)

        assertEquals(0, readSample(buffer, 0).toInt())
        val mid = readSample(buffer, 96).toInt()
        assertTrue("Expected ~50% midpoint, got $mid", mid in 4900..5100)
        // Monotonic non-decreasing across the ramp
        for (i in 1 until 192) {
            assertTrue(readSample(buffer, i) >= readSample(buffer, i - 1))
        }
        // Samples from 192 onward remain untouched
        for (i in 192 until total) {
            assertEquals(amp, readSample(buffer, i))
        }
    }

    @Test
    fun fadeInAcrossSplitChunksMatchesSingleBuffer() {
        val amp: Short = -12_000
        val single = constantPcm16Le(250, amp)
        applyFadeInPcm16Le(single, single.size, startSampleOffset = 0, rampSamples = 192)

        val chunk1 = constantPcm16Le(100, amp)
        val chunk2 = constantPcm16Le(150, amp)
        applyFadeInPcm16Le(chunk1, chunk1.size, startSampleOffset = 0, rampSamples = 192)
        applyFadeInPcm16Le(chunk2, chunk2.size, startSampleOffset = 100, rampSamples = 192)

        for (i in 0 until 100) {
            assertEquals(readSample(single, i), readSample(chunk1, i))
        }
        for (i in 0 until 150) {
            assertEquals(readSample(single, 100 + i), readSample(chunk2, i))
        }
    }
}
