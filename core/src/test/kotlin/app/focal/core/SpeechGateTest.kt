package app.focal.core

import kotlin.math.sin
import kotlin.test.*
import org.junit.Test

class SpeechGateTest {
    private class Capture : SpeechGate.Sink {
        val starts = mutableListOf<Pair<Long, Boolean>>()
        val sizes = mutableListOf<Int>()
        var current = 0
        override fun begin(startSample: Long, overlap: Boolean) { starts += startSample to overlap; current = 0 }
        override fun audio(samples: ShortArray) { current += samples.size }
        override fun end(endSample: Long) { sizes += current }
    }
    private val silence = ShortArray(320)
    private val speech = ShortArray(320) { (sin(it * 0.1) * 2400).toInt().toShort() }

    @Test fun `a long pause never invokes the speech engine`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(60_000) { gate.accept(silence) }; gate.flush()
        assertTrue(sink.starts.isEmpty())
    }
    @Test fun `quiet onset is retained with pre roll and trailing context`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(100) { gate.accept(silence) }
        repeat(50) { gate.accept(speech) }
        repeat(100) { gate.accept(silence) }
        gate.flush()
        assertEquals(1, sink.sizes.size)
        assertTrue(sink.starts.single().first < 100 * 320)
        assertTrue(sink.sizes.single() >= (50 + 45) * 320)
    }
    @Test fun `uninterrupted speech is bounded and subsequent chunks overlap`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(5000) { gate.accept(speech) }; gate.flush()
        assertTrue(sink.sizes.size >= 4)
        assertTrue(sink.sizes.all { it <= SpeechGate.MAX_SAMPLES })
        assertFalse(sink.starts.first().second)
        assertTrue(sink.starts.drop(1).all { it.second })
    }
    @Test fun `flush keeps a short final utterance and prevents overlap across pause`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(8) { gate.accept(speech) }; gate.flush()
        repeat(8) { gate.accept(speech) }; gate.flush()
        assertEquals(listOf(8 * 320, 8 * 320), sink.sizes)
        assertTrue(sink.starts.none { it.second })
    }
    @Test fun `single impulse does not open a chunk`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        gate.accept(speech); repeat(100) { gate.accept(silence) }; gate.flush()
        assertTrue(sink.sizes.isEmpty())
    }
}
