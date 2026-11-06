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

    @Test fun smoothstepIsTheHermiteStep() {
        assertEquals(0f, smoothstep(0f, 0.5f, -1f), 0f)
        assertEquals(0.5f, smoothstep(0f, 1f, 0.5f), 1e-6f)
        assertEquals(1f, smoothstep(0.5f, 1f, 2f), 0f)
        // The fade-through never shows both contents at once: one is out by the time the other starts.
        assertEquals(0f, 1 - smoothstep(0f, 0.5f, 0.5f), 0f)
        assertEquals(0f, smoothstep(0.5f, 1f, 0.5f), 0f)
    }
}
