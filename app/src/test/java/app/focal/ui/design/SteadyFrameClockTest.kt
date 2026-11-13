package app.focal.ui.design

import androidx.compose.runtime.MonotonicFrameClock
import org.junit.Assert.assertEquals
import org.junit.Test

class SteadyFrameClockTest {
    private object NoFrames : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = error("No frames in this test.")
    }

    private val ms = 1_000_000L

    @Test fun steadyFramesPassUnchanged() {
        val clock = SteadyFrameClock(NoFrames)
        val start = 5_000 * ms
        assertEquals(start, clock.steady(start))
        assertEquals(start + 16 * ms, clock.steady(start + 16 * ms))
        // Every waiter of one frame reads the same time.
        assertEquals(start + 16 * ms, clock.steady(start + 16 * ms))
        assertEquals(start + 49 * ms, clock.steady(start + 49 * ms))
    }

    @Test fun aLongFrameDoesNotSkipTheMotion() {
        val clock = SteadyFrameClock(NoFrames, maxStepNanos = 40 * ms)
        val start = 5_000 * ms
        clock.steady(start)
        // A frame that took 300 ms counts as 40: what follows goes on from there, frame by frame.
        assertEquals(start + 40 * ms, clock.steady(start + 300 * ms))
        assertEquals(start + 56 * ms, clock.steady(start + 316 * ms))
        // Time never runs back.
        assertEquals(start + 56 * ms, clock.steady(start + 310 * ms))
    }
}
