package app.focal.core

import kotlin.math.sin
import kotlin.test.*
import org.junit.Test

class LevelMeterTest {
    @Test fun `silence and a constant offset read as zero`() {
        val meter = LevelMeter()
        meter.accept(ShortArray(LevelMeter.FRAME_SAMPLES))
        assertEquals(0f, meter.level)
        meter.accept(ShortArray(LevelMeter.FRAME_SAMPLES) { 12_000 })
        assertEquals(0f, meter.level)
    }

    @Test fun `speech-like signal rises and loud signal is clamped`() {
        val meter = LevelMeter()
        meter.accept(ShortArray(LevelMeter.FRAME_SAMPLES) { (sin(it * 0.3) * 1_200).toInt().toShort() })
        assertTrue(meter.level in 0.2f..0.6f, "level=${meter.level}")
        meter.accept(ShortArray(LevelMeter.FRAME_SAMPLES) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE })
        assertEquals(1f, meter.level)
    }

    @Test fun `only the delivered part of a buffer is measured`() {
        val meter = LevelMeter()
        val buffer = ShortArray(LevelMeter.FRAME_SAMPLES) { if (it < 73) 0 else Short.MAX_VALUE }
        meter.accept(buffer, 73)
        assertEquals(0f, meter.level)
        assertFailsWith<IllegalArgumentException> { meter.accept(buffer, 0) }
        assertFailsWith<IllegalArgumentException> { meter.accept(buffer, buffer.size + 1) }
    }
}
