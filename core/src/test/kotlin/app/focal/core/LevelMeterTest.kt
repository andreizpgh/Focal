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

class MeterMathTest {
    @Test fun `rms maps -50 to -10 dBFS onto zero to one`() {
        assertEquals(0f, MeterMath.normalized(0.0))
        assertEquals(0f, MeterMath.normalized(32768.0 * 0.001))
        assertEquals(1f, MeterMath.normalized(32768.0))
        assertEquals(0.25f, MeterMath.normalized(32768.0 * 0.01), 1e-4f)
        assertEquals(0.5f, MeterMath.normalized(32768.0 * Math.pow(10.0, -1.5)), 1e-4f)
    }

    @Test fun `the meter attacks fast and releases slowly`() {
        val up = MeterMath.smooth(0f, 1f, 40.0)
        val down = 1f - MeterMath.smooth(1f, 0f, 40.0)
        assertTrue(up > 0.6f && down < 0.25f, "up=$up down=$down")
        assertEquals(0.3f, MeterMath.smooth(0.3f, 1f, 0.0))
    }

    @Test fun `the rms ignores a constant offset`() {
        val meter = LevelMeter()
        meter.accept(ShortArray(LevelMeter.FRAME_SAMPLES) { 9_000 })
        assertEquals(0.0, meter.rms, 1e-6)
        meter.accept(ShortArray(LevelMeter.FRAME_SAMPLES) { if (it % 2 == 0) 1_000 else -1_000 })
        assertEquals(1_000.0, meter.rms, 1e-6)
    }
}

class LevelHistoryTest {
    @Test fun `bars read earlier levels by interpolation`() {
        val history = LevelHistory(8)
        assertEquals(0f, history.at(100))
        history.add(0, 0f)
        history.add(80, 0.8f)
        history.add(160, 0.4f)
        assertEquals(0.4f, history.bar(0, 160))
        assertEquals(0.8f, history.bar(1, 160))
        assertEquals(0.4f, history.at(40), 1e-6f)
        assertEquals(0f, history.bar(4, 160))
        assertEquals(0.4f, history.latest)
    }

    @Test fun `the ring keeps the newest entries`() {
        val history = LevelHistory(3)
        for (t in 0L..5L) history.add(t * 10, t.toFloat())
        assertEquals(5f, history.at(50))
        assertEquals(3f, history.at(0), "older than the ring clamps to its oldest entry")
        assertFailsWith<IllegalArgumentException> { history.add(10, 0f) }
        history.clear()
        assertEquals(0f, history.latest)
    }
}
