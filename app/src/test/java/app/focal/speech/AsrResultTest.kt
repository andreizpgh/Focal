package app.focal.speech

import app.focal.core.AsrToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks on the bridge's raw decode result (layout shared with focal_speech.cpp). */
class AsrResultTest {
    @Test fun parallelArraysBecomeTokens() {
        val result = AsrResult.fromBridge(raw())
        assertEquals("Hi, Ёж", result.text)
        assertEquals(listOf(" Hi", ",", " Ёж"), result.tokens)
        assertEquals(listOf(
            AsrToken(" Hi", 0.24f.toDouble(), 0.16f.toDouble(), (-0.01f).toDouble()),
            AsrToken(",", 0.4f.toDouble(), 0.08f.toDouble(), (-0.5f).toDouble()),
            AsrToken(" Ёж", 0.56f.toDouble(), 0.32f.toDouble(), (-2f).toDouble()),
        ), result.asrTokens())
    }

    @Test fun missingLogProbsLeaveConfidenceUnknown() {
        val result = AsrResult.fromBridge(raw(logProbs = null))
        assertNull(result.logProbs)
        assertTrue(result.asrTokens().all { it.logProb == null })
    }

    @Test fun anEmptyResultHasEmptyArrays() {
        val result = AsrResult.fromBridge(arrayOf(ByteArray(0), ByteArray(0), IntArray(0), null, null, null))
        assertEquals("", result.text)
        assertTrue(result.tokens.isEmpty() && result.timestamps.isEmpty() && result.durations.isEmpty())
        assertArrayEquals(FloatArray(0), result.logProbs, 0f)
    }

    @Test fun malformedUtf8IsReplacedNotFatal() {
        val bytes = byteArrayOf(' '.code.toByte(), 0xC3.toByte())
        val result = AsrResult.fromBridge(
            arrayOf(bytes, bytes, intArrayOf(2), floatArrayOf(0f), floatArrayOf(0.08f), floatArrayOf(-1f)),
        )
        assertEquals(" �", result.tokens.single())
    }

    @Test fun misalignedMissingOrInvalidValuesAreRejected() {
        val broken = listOf(
            raw(timestamps = null),
            raw(durations = null),
            raw(timestamps = floatArrayOf(0f, 0.4f)),
            raw(logProbs = floatArrayOf(-1f)),
            raw(durations = floatArrayOf(0.1f, Float.NaN, 0.1f)),
            raw(timestamps = floatArrayOf(-0.1f, 0.4f, 0.56f)),
            raw(logProbs = floatArrayOf(-1f, Float.NEGATIVE_INFINITY, -1f)),
            raw(ends = intArrayOf(3, 2, 9)),
            raw(ends = intArrayOf(3, 4, 99)),
            raw(ends = intArrayOf(3, 4)),
            raw().copyOf(5),
        )
        for (value in broken) assertThrows(IllegalStateException::class.java) { AsrResult.fromBridge(value) }
        assertThrows(IllegalArgumentException::class.java) {
            AsrResult(" a", listOf(" a"), FloatArray(1), FloatArray(2), null)
        }
    }

    private fun raw(
        ends: IntArray = intArrayOf(3, 4, 9),
        timestamps: FloatArray? = floatArrayOf(0.24f, 0.4f, 0.56f),
        durations: FloatArray? = floatArrayOf(0.16f, 0.08f, 0.32f),
        logProbs: FloatArray? = floatArrayOf(-0.01f, -0.5f, -2f),
    ): Array<Any?> = arrayOf(
        "Hi, Ёж".encodeToByteArray(), " Hi, Ёж".encodeToByteArray(), ends, timestamps, durations, logProbs,
    )
}
