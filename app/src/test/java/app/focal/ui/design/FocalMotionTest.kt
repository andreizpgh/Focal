package app.focal.ui.design

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FocalMotionTest {
    /** The last time the spring's step response is more than 0.1 % away from its target. */
    private fun settleMs(damping: Float, stiffness: Float): Float {
        var last = 0f
        var t = 0f
        while (t < 3_000f) {
            if (abs(1f - FocalMotion.springFraction(damping, stiffness, t)) > 0.001f) last = t
            t += 0.1f
        }
        return last
    }

    /** The settle times of design spec 2.8, computed there from the same spring solutions. */
    @Test fun tokensSettleWhenTheSpecSays() {
        val expected = mapOf(
            "press" to Triple(FocalMotion.SPATIAL_DAMPING, FocalMotion.PRESS_STIFFNESS, 224f),
            "move" to Triple(FocalMotion.SPATIAL_DAMPING, FocalMotion.MOVE_STIFFNESS, 317f),
            "stage" to Triple(FocalMotion.SPATIAL_DAMPING, FocalMotion.STAGE_STIFFNESS, 484f),
            "fadeFast" to Triple(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_FAST_STIFFNESS, 150f),
            "fade" to Triple(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_STIFFNESS, 231f),
            "fadeSlow" to Triple(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_SLOW_STIFFNESS, 326f),
            "develop" to Triple(FocalMotion.EFFECTS_DAMPING, FocalMotion.DEVELOP_STIFFNESS, 533f),
        )
        for ((name, spring) in expected) {
            val (damping, stiffness, settle) = spring
            assertEquals(name, settle, settleMs(damping, stiffness), 3f)
        }
        assertEquals(FocalSettle.FADE_FAST_MS.toFloat(), settleMs(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_FAST_STIFFNESS), 3f)
        assertEquals(FocalSettle.FADE_MS.toFloat(), settleMs(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_STIFFNESS), 3f)
        assertEquals(FocalSettle.MOVE_MS.toFloat(), settleMs(FocalMotion.SPATIAL_DAMPING, FocalMotion.MOVE_STIFFNESS), 3f)
        assertEquals(FocalSettle.FADE_SLOW_MS.toFloat(), settleMs(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_SLOW_STIFFNESS), 3f)
    }

    /** The clock-drawn springs start at 0, follow the spring's solution and end exactly at 1 at their settle time. */
    @Test fun clockSpringsSettleOnTheirTime() {
        val springs = listOf(
            FocalClock::move to FocalSettle.MOVE_MS, FocalClock::fadeFast to FocalSettle.FADE_FAST_MS,
            FocalClock::fade to FocalSettle.FADE_MS, FocalClock::fadeSlow to FocalSettle.FADE_SLOW_MS,
        )
        for ((spring, settle) in springs) {
            assertEquals(0f, spring(0f), 0f)
            assertEquals(1f, spring(settle.toFloat()), 0f)
            assertEquals(1f, spring(Float.POSITIVE_INFINITY), 0f)
            assertTrue(spring(settle / 2f) in 0.5f..1f)
        }
        assertEquals(FocalMotion.springFraction(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_STIFFNESS, 80f), FocalClock.fade(80f), 0f)
    }

    /** A triggered motion's first frame already shows one frame of it, and it ends that much sooner. */
    @Test fun fromTriggerRunsOneFrameAhead() {
        val lead = FocalTiming.TRIGGER_LEAD_MS
        val plain = TargetBasedAnimation(FocalMotion.fadeFast<Float>(), Float.VectorConverter, 0f, 1f)
        val led = TargetBasedAnimation(FocalMotion.fromTrigger(FocalMotion.fadeFast<Float>()), Float.VectorConverter, 0f, 1f)
        assertEquals(plain.getValueFromNanos(lead * NANOS), led.getValueFromNanos(0L), 1e-6f)
        assertTrue(led.getValueFromNanos(0L) > 0f)
        assertEquals(plain.getValueFromNanos(100 * NANOS), led.getValueFromNanos((100 - lead) * NANOS), 1e-6f)
        assertEquals(plain.durationNanos - lead * NANOS, led.durationNanos)
    }

    private companion object { const val NANOS = 1_000_000L }

    /** No visible bounce: the spatial springs (damping 0.9) overshoot by 0.15 % (spec 2.8), never more. */
    @Test fun spatialSpringsDoNotVisiblyOvershoot() {
        for (stiffness in listOf(FocalMotion.PRESS_STIFFNESS, FocalMotion.MOVE_STIFFNESS, FocalMotion.STAGE_STIFFNESS)) {
            var peak = 0f
            var t = 0f
            while (t < 2_000f) { peak = maxOf(peak, FocalMotion.springFraction(FocalMotion.SPATIAL_DAMPING, stiffness, t)); t += 0.5f }
            assertTrue("overshoot $peak at $stiffness", peak < 1.0016f)
        }
    }

    @Test fun splashInterpolatorRunsFromZeroToRest() {
        val curve = FocalMotion.springInterpolator(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_FAST_STIFFNESS, FocalSettle.FADE_FAST_MS)
        assertEquals(0f, curve.getInterpolation(0f), 0f)
        assertEquals(1f, curve.getInterpolation(1f), 0.001f)
        assertTrue(curve.getInterpolation(0.25f) < curve.getInterpolation(0.5f))
    }

    @Test fun aSpringLetGoMovingStartsWhereAndAsFastAsTheFinger() {
        val damping = FocalMotion.SPATIAL_DAMPING
        val stiffness = FocalMotion.STAGE_STIFFNESS
        // At rest it is the step response.
        for (t in listOf(16f, 80f, 200f)) {
            assertEquals(FocalMotion.springFraction(damping, stiffness, t), FocalMotion.springTo(damping, stiffness, 0f, 1f, 0f, t), 1e-5f)
        }
        // It starts where it was let go, at the speed it was let go with (here 2 of its way per second).
        assertEquals(0.4f, FocalMotion.springTo(damping, stiffness, 0.4f, 1f, 2f, 0f), 0f)
        val slope = (FocalMotion.springTo(damping, stiffness, 0.4f, 1f, 2f, 1f) - 0.4f) / 0.001f
        assertEquals(2f, slope, 0.1f)
        // And it comes to rest at its target from either side, even moving away from it when let go.
        assertEquals(1f, FocalMotion.springTo(damping, stiffness, 0.4f, 1f, 2f, 1_000f), 0.001f)
        assertEquals(0f, FocalMotion.springTo(damping, stiffness, 0.3f, 0f, 1f, 1_000f), 0.001f)
    }

    @Test fun smoothstepIsTheHermiteStep() {
        assertEquals(0f, smoothstep(0f, 0.5f, -1f), 0f)
        assertEquals(0.5f, smoothstep(0f, 1f, 0.5f), 1e-6f)
        assertEquals(1f, smoothstep(0.5f, 1f, 2f), 0f)
        // The fade-through never shows both contents at once: one is out by the time the other starts.
        assertEquals(0f, 1 - smoothstep(0f, 0.5f, 0.5f), 0f)
        assertEquals(0f, smoothstep(0.5f, 1f, 0.5f), 0f)
    }
}
