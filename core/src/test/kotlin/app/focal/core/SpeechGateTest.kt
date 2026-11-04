package app.focal.core

import kotlin.math.sin
import kotlin.test.*
import org.junit.Test

class SpeechGateTest {
    private class Capture : SpeechGate.Sink {
        val starts = mutableListOf<Pair<Long, Boolean>>()
        val ends = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()
        var current = 0
        override fun begin(startSample: Long, overlap: Boolean) { starts += startSample to overlap; current = 0 }
        override fun audio(samples: ShortArray) { current += samples.size }
        override fun end(endSample: Long) { sizes += current; ends += endSample }
    }
    private val silence = ShortArray(320)
    private val speech = ShortArray(320) { (sin(it * 0.1) * 2400).toInt().toShort() }

    @Test fun `a long pause never invokes the speech engine`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(60_000) { gate.accept(silence) }; gate.flush()
        assertTrue(sink.starts.isEmpty())
    }
    @Test fun `a constant microphone offset is silence rather than an endless utterance`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        val offset = ShortArray(320) { 6000 }
        repeat(2000) { gate.accept(offset) }; gate.flush()
        assertTrue(sink.starts.isEmpty())
        assertEquals(0f, gate.level)
    }
    @Test fun `speech above a microphone offset is retained`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(50) { gate.accept(ShortArray(320) { 6000 }) }
        val shifted = ShortArray(320) { (speech[it] + 6000).toShort() }
        repeat(50) { gate.accept(shifted) }; gate.flush()
        assertEquals(1, sink.sizes.size)
        assertTrue(sink.sizes.single() >= 50 * 320)
    }
    @Test fun `a voiced partial tail is retained without padding the microphone buffer`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        gate.accept(speech)
        gate.acceptPartial(speech, 160)
        gate.flush()
        assertEquals(listOf(0L to false), sink.starts)
        assertEquals(listOf(480), sink.sizes)
        assertEquals(listOf(480L), sink.ends)
    }
    @Test fun `successive pause boundaries advance by actual partial sample counts`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        gate.accept(speech); gate.acceptPartial(speech, 137); gate.flush()
        gate.accept(speech); gate.acceptPartial(speech, 73); gate.flush()
        assertEquals(listOf(0L to false, 457L to false), sink.starts)
        assertEquals(listOf(457, 393), sink.sizes)
        assertEquals(listOf(457L, 850L), sink.ends)
    }
    @Test fun `quiet onset is retained with pre roll and trailing context`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(100) { gate.accept(silence) }
        repeat(50) { gate.accept(speech) }
        repeat(100) { gate.accept(silence) }
        gate.flush()
        assertEquals(1, sink.sizes.size)
        assertTrue(sink.starts.single().first < 100 * 320)
        assertTrue(sink.sizes.single() >= (50 + 30) * 320)
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
    @Test fun `long phrases end at a breath before the hard boundary`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(450) { gate.accept(speech) }
        repeat(12) { gate.accept(silence) }
        assertEquals(1, sink.sizes.size)
        repeat(100) { gate.accept(speech) }; gate.flush()
        assertFalse(sink.starts.last().second)
    }
    @Test fun `short hesitation does not split a short phrase`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(100) { gate.accept(speech) }
        repeat(12) { gate.accept(silence) }
        repeat(100) { gate.accept(speech) }; gate.flush()
        assertEquals(1, sink.sizes.size)
        assertEquals(212 * 320, sink.sizes.single())
    }
    @Test fun `single impulse does not open a chunk`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        gate.accept(speech); repeat(100) { gate.accept(silence) }; gate.flush()
        assertTrue(sink.sizes.isEmpty())
    }
    @Test fun `silence after a hard boundary does not decode the trailing overlap again`() {
        val sink = Capture(); val gate = SpeechGate(sink)
        repeat(SpeechGate.MAX_SAMPLES / 320) { gate.accept(speech) }
        repeat(100) { gate.accept(silence) }
        assertEquals(1, sink.sizes.size)
        repeat(50) { gate.accept(speech) }; gate.flush()
        assertEquals(2, sink.sizes.size)
        assertFalse(sink.starts.last().second)
    }
}
